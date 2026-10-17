package com.gtnhplanner.cloud.net;

import java.net.URI;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.annotation.Nullable;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.Tags;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.ScheduledFuture;

/**
 * A WebSocket client for the live service, on Minecraft's own netty (4.0.10: only what that has), so no library ships.
 * {@code ws://} and {@code wss://} (TLS through the JDK's engine, with SNI and normal certificate and hostname checks),
 * the session sent as {@code Cookie: gtnh_session=<token>} on the upgrade, JSON text frames, the protocol's own ping
 * every 25 s (a connection that has heard nothing for three pings is dropped), and reconnecting with a backoff that
 * doubles from 1 s to 30 s, jittered, for as long as it is wanted.
 * <p>
 * Nothing here calls game code: netty's one daemon thread only queues what happens, and the caller drains it with
 * {@link #poll} each tick, on the client thread. Frames still queued when a connection drops are discarded (the
 * protocol rejoins and resends on its own), and a caller that never polls cannot grow the queue without bound: past
 * {@value #INCOMING_MAX} waiting events the connection is dropped and the queue emptied.
 */
public final class LiveSocket {

    /** Where the connection is: {@code WAITING} is between tries. */
    public enum State {
        CLOSED,
        CONNECTING,
        OPEN,
        WAITING
    }

    /** What {@link #poll} hands out, in the order it happened. */
    public interface Sink {

        /** The state changed; {@code reason} says why when it is not simply a try that began or went well. */
        void state(State state, String reason);

        void frame(String text);
    }

    /** Timings and limits; {@link #standard} is what the game runs on, tests shorten them. */
    public record Options(int pingMs, int retryMinMs, int retryMaxMs, int queueMax, int maxFrameBytes,
        int connectTimeoutMs) {

        public static Options standard() {
            return new Options(25_000, 1_000, 30_000, 512, 32 * 1024 * 1024, 10_000);
        }
    }

    /** The reason a state carries when the service refused the session: nothing retries until {@link #start} again. */
    public static final String AUTH = "auth";

    /** Close codes the live service uses besides the normal ones (see the live service's wire protocol). */
    public static final int CLOSE_TOO_BIG = 1009, CLOSE_RESTART = 1012, CLOSE_LATER = 1013;

    private static final java.util.regex.Pattern BAD_CLOSE = java.util.regex.Pattern
        .compile("Invalid close frame getStatus code: ([0-9]+)");

    private static final int INCOMING_MAX = 20_000;
    private static final String PING = "{\"t\":\"ping\"}";

    private static NioEventLoopGroup group;

    /** One daemon event loop thread for every socket, made on first use and shut down with the game. */
    private static synchronized NioEventLoopGroup group() {
        if (group == null) {
            group = new NioEventLoopGroup(1, r -> {
                final Thread t = new Thread(r, "GTNH Planner live");
                t.setDaemon(true);
                return t;
            });
            Runtime.getRuntime()
                .addShutdownHook(new Thread(LiveSocket::shutdown, "GTNH Planner live shutdown"));
        }
        return group;
    }

    /** Stops the event loop, closing every socket. Safe to call twice; a later socket starts a new loop. */
    public static synchronized void shutdown() {
        if (group == null) return;
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        group = null;
    }

    private record Event(@Nullable State state, String text) {}

    private final URI uri;
    private final Supplier<String> token;
    @Nullable
    private final SSLContext ssl;
    private final Options options;

    private final BlockingQueue<Event> incoming = new LinkedBlockingQueue<>(INCOMING_MAX);
    private final BlockingQueue<String> outgoing;
    private volatile State state = State.CLOSED;

    // Touched only on the event loop.
    private volatile NioEventLoopGroup loop;
    @Nullable
    private Channel channel;
    private boolean wanted;
    private int generation, attempts;
    private long lastHeard;
    /** The status of the last close frame the service sent on this connection, 0 for none. */
    private int closeCode;
    /** The least the next try waits, from an upgrade answered 429 with Retry-After (0 for none). */
    private long retryAfterMs;
    @Nullable
    private ScheduledFuture<?> retry, pinger, giveUp;

    /**
     * {@code token} is asked on every try, so a session that changed or ended is noticed; null or empty is no session.
     */
    public LiveSocket(final URI uri, final Supplier<String> token) {
        this(uri, token, null, Options.standard());
    }

    /** {@code ssl} decides who is trusted for {@code wss://}; null is the JDK's default. */
    public LiveSocket(final URI uri, final Supplier<String> token, @Nullable final SSLContext ssl,
        final Options options) {
        this.uri = uri;
        this.token = token;
        this.ssl = ssl;
        this.options = options;
        this.outgoing = new ArrayBlockingQueue<>(options.queueMax());
    }

    public State state() {
        return state;
    }

    /** Wants the connection: opens it now and keeps it open, retrying after a loss, until {@link #stop}. */
    public void start() {
        final NioEventLoopGroup g = group();
        g.execute(() -> {
            loop = g;
            wanted = true;
            if (state == State.CLOSED || state == State.WAITING) {
                cancel(retry);
                connect();
            }
        });
    }

    /** Closes the connection and stops retrying. */
    public void stop() {
        final NioEventLoopGroup g;
        synchronized (LiveSocket.class) {
            g = group;
        }
        if (g == null) {
            if (state != State.CLOSED) setState(State.CLOSED, "");
            return;
        }
        g.execute(() -> {
            wanted = false;
            cancel(retry);
            generation++;
            drop();
            if (state != State.CLOSED) setState(State.CLOSED, "");
        });
    }

    /**
     * Queues a text frame. False when the connection is not open or the queue is full (the frame is not kept: what
     * matters is resent after a reconnect by whoever sent it).
     */
    public boolean send(final String text) {
        final NioEventLoopGroup g = loop;
        if (state != State.OPEN || g == null || !outgoing.offer(text)) return false;
        g.execute(this::flush);
        return true;
    }

    /** Hands the waiting events to {@code sink}, oldest first. Run it each tick on the client thread. */
    public void poll(final Sink sink) {
        for (Event e; (e = incoming.poll()) != null;) {
            if (e.state() != null) sink.state(e.state(), e.text());
            else sink.frame(e.text());
        }
    }

    // region On the event loop

    private void connect() {
        if (!wanted) return;
        final String session = token.get();
        if (session == null || session.isEmpty()) {
            wanted = false;
            setState(State.CLOSED, AUTH);
            return;
        }
        setState(State.CONNECTING, "");
        closeCode = 0;
        final int gen = ++generation;
        final boolean secure = "wss".equalsIgnoreCase(uri.getScheme());
        final int port = uri.getPort() >= 0 ? uri.getPort() : secure ? 443 : 80;
        final DefaultHttpHeaders headers = new DefaultHttpHeaders();
        headers.add("Cookie", "gtnh_session=" + session);
        headers.add("User-Agent", "GTNH Planner/" + Tags.VERSION + " (GT New Horizons planner mod)");
        final WebSocketClientHandshaker handshaker;
        try {
            handshaker = WebSocketClientHandshakerFactory
                .newHandshaker(uri, WebSocketVersion.V13, null, false, headers, options.maxFrameBytes());
        } catch (final RuntimeException e) {
            lost(gen, "bad address: " + e.getMessage());
            return;
        }
        final Handler handler = new Handler(gen, handshaker);
        final Bootstrap b = new Bootstrap().group(loop)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, options.connectTimeoutMs())
            .option(ChannelOption.TCP_NODELAY, true)
            .handler(new ChannelInitializer<SocketChannel>() {

                @Override
                protected void initChannel(final SocketChannel ch) throws Exception {
                    if (secure) ch.pipeline()
                        .addLast(new SslHandler(engine(uri.getHost(), port)));
                    ch.pipeline()
                        .addLast(
                            new HttpClientCodec(),
                            new HttpObjectAggregator(64 * 1024),
                            new WebSocketFrameAggregator(options.maxFrameBytes()),
                            handler);
                }
            });
        b.connect(uri.getHost(), port)
            .addListener(f -> {
                if (!f.isSuccess()) lost(gen, "could not connect: " + f.cause());
                else if (gen != generation) ((io.netty.channel.ChannelFuture) f).channel()
                    .close();
                else channel = ((io.netty.channel.ChannelFuture) f).channel();
            });
        giveUp = loop.schedule(() -> {
            if (gen == generation && state == State.CONNECTING) {
                drop();
                lost(gen, "no answer");
            }
        }, options.connectTimeoutMs() * 2L, TimeUnit.MILLISECONDS);
    }

    private SSLEngine engine(final String host, final int port) throws Exception {
        final SSLContext ctx = ssl != null ? ssl : SSLContext.getDefault();
        final SSLEngine engine = ctx.createSSLEngine(host, port);
        engine.setUseClientMode(true);
        // Netty 4.0.10 predates TLS 1.3 (the JDKs newer than 8 offer it first) and stalls in its handshake: 1.2 it is.
        for (final String proto : engine.getSupportedProtocols())
            if (proto.equals("TLSv1.2")) engine.setEnabledProtocols(new String[] { proto });
        final SSLParameters p = engine.getSSLParameters();
        p.setEndpointIdentificationAlgorithm("HTTPS");
        if (!isAddress(host)) p.setServerNames(List.of(new SNIHostName(host)));
        engine.setSSLParameters(p);
        return engine;
    }

    /** Literal addresses are not host names for SNI. */
    private static boolean isAddress(final String host) {
        return host.indexOf(':') >= 0 || host.matches("[0-9.]+");
    }

    private void opened(final int gen, final Channel ch) {
        if (gen != generation) {
            ch.close();
            return;
        }
        cancel(giveUp);
        attempts = 0;
        lastHeard = System.nanoTime();
        channel = ch;
        setState(State.OPEN, "");
        final long ping = options.pingMs();
        pinger = ch.eventLoop()
            .scheduleAtFixedRate(() -> {
                if (gen != generation) return;
                if (System.nanoTime() - lastHeard > TimeUnit.MILLISECONDS.toNanos(ping * 3)) {
                    ch.close();
                    return;
                }
                ch.writeAndFlush(new TextWebSocketFrame(PING));
            }, ping, ping, TimeUnit.MILLISECONDS);
    }

    private void flush() {
        final Channel ch = channel;
        if (ch == null || state != State.OPEN) return;
        boolean any = false;
        for (String s; (s = outgoing.poll()) != null;) {
            ch.write(new TextWebSocketFrame(s));
            any = true;
        }
        if (any) ch.flush();
    }

    private void heard(final String text) {
        lastHeard = System.nanoTime();
        if (!incoming.offer(new Event(null, text))) {
            // Nobody is polling: start over rather than grow; the rejoin fetches whole snapshots again.
            incoming.clear();
            lost(generation, "queue full");
        }
    }

    /** The connection of generation {@code gen} is gone (or never came): retry, or rest if it is not wanted. */
    private void lost(final int gen, final String why) {
        if (gen != generation) return;
        generation++;
        drop();
        if (!wanted) {
            if (state != State.CLOSED) setState(State.CLOSED, why);
            return;
        }
        final int code = closeCode;
        closeCode = 0;
        final long minWait = retryAfterMs;
        retryAfterMs = 0;
        final long wait;
        final String reason;
        if (code == CLOSE_RESTART || code == CLOSE_TOO_BIG) {
            // The service is restarting (or lost the room's lease): it is back in a moment, so no backoff, only a
            // small jitter so a whole game's worth of clients do not arrive on the same tick. A frame it found too
            // big was our fault, not its: also straight back.
            attempts = 0;
            wait = 50 + ThreadLocalRandom.current()
                .nextLong(Math.max(2, options.retryMinMs() / 2));
            reason = "close:" + code;
        } else {
            final long ceiling = Math.min(options.retryMaxMs(), (long) options.retryMinMs() << Math.min(attempts, 20));
            attempts++;
            wait = Math.max(
                minWait,
                Math.max(
                    1,
                    ceiling / 2 + ThreadLocalRandom.current()
                        .nextLong(ceiling / 2 + 1)));
            reason = code == CLOSE_LATER ? "close:" + code : why;
        }
        setState(State.WAITING, reason);
        retry = loop.schedule(() -> { if (wanted && state == State.WAITING) connect(); }, wait, TimeUnit.MILLISECONDS);
    }

    /** The service said no to the session: no retrying with a token it will refuse again. */
    private void refused(final int gen) {
        if (gen != generation) return;
        generation++;
        wanted = false;
        drop();
        setState(State.CLOSED, AUTH);
    }

    private void drop() {
        cancel(pinger);
        cancel(giveUp);
        pinger = giveUp = null;
        outgoing.clear();
        final Channel ch = channel;
        channel = null;
        if (ch != null) ch.close();
    }

    private static void cancel(@Nullable final ScheduledFuture<?> f) {
        if (f != null) f.cancel(false);
    }

    private void setState(final State next, final String reason) {
        state = next;
        incoming.offer(new Event(next, reason));
    }

    private final class Handler extends SimpleChannelInboundHandler<Object> {

        private final int gen;
        private final WebSocketClientHandshaker handshaker;

        Handler(final int gen, final WebSocketClientHandshaker handshaker) {
            this.gen = gen;
            this.handshaker = handshaker;
        }

        @Override
        public void channelActive(final ChannelHandlerContext ctx) throws Exception {
            handshaker.handshake(ctx.channel());
            super.channelActive(ctx);
        }

        @Override
        protected void channelRead0(final ChannelHandlerContext ctx, final Object msg) {
            if (gen != generation) {
                ctx.close();
                return;
            }
            final Channel ch = ctx.channel();
            if (!handshaker.isHandshakeComplete()) {
                if (!(msg instanceof FullHttpResponse)) return;
                final FullHttpResponse r = (FullHttpResponse) msg;
                final int status = r.getStatus()
                    .code();
                if (status == 401 || status == 403) {
                    refused(gen);
                    return;
                }
                if (status == 429) {
                    // Too many upgrades: not a sign-in problem. Back off, for as long as the service asks if it does.
                    final String after = r.headers()
                        .get("Retry-After");
                    if (after != null) try {
                        retryAfterMs = Math.min(60_000L, Long.parseLong(after.trim()) * 1000L);
                    } catch (final NumberFormatException ignored) {}
                    lost(gen, "busy: 429");
                    return;
                }
                try {
                    handshaker.finishHandshake(ch, r);
                } catch (final RuntimeException e) {
                    lost(gen, "refused: " + status);
                    return;
                }
                opened(gen, ch);
                return;
            }
            if (msg instanceof TextWebSocketFrame) {
                heard(((TextWebSocketFrame) msg).text());
            } else if (msg instanceof PingWebSocketFrame) {
                lastHeard = System.nanoTime();
                ch.writeAndFlush(
                    new PongWebSocketFrame(
                        ((WebSocketFrame) msg).content()
                            .retain()));
            } else if (msg instanceof PongWebSocketFrame) {
                lastHeard = System.nanoTime();
            } else if (msg instanceof CloseWebSocketFrame) {
                closeCode = ((CloseWebSocketFrame) msg).statusCode();
                ch.close();
            }
        }

        @Override
        public void channelInactive(final ChannelHandlerContext ctx) {
            lost(gen, "connection lost");
        }

        @Override
        public void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
            // Minecraft's netty (4.0.10) knows only the close codes of RFC 6455 and calls 1012 and 1013 a corrupt frame
            // before this handler sees it, saying the code in its message: read it from there.
            final java.util.regex.Matcher m = BAD_CLOSE.matcher(String.valueOf(cause.getMessage()));
            if (cause instanceof io.netty.handler.codec.CorruptedFrameException && m.find())
                closeCode = Integer.parseInt(m.group(1));
            else GtnhPlanner.LOG.debug("Live connection error", cause);
            ctx.close();
        }
    }

    // endregion
}
