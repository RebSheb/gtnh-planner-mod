package com.gtnhplanner.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.gtnhplanner.cloud.net.LiveSocket;

/** The socket against a netty WebSocket server in this process: plain and TLS, the cookie, frames, pings, drops. */
class LiveSocketTest {

    /** Short timings so a drop and a retry take a blink. */
    static final LiveSocket.Options FAST = new LiveSocket.Options(150, 40, 160, 8, 1 << 25, 2_000);

    @TempDir
    File dir;

    private LiveTestServer server;
    private LiveSocket socket;

    /** What a socket handed out, in order. */
    private static final class Log implements LiveSocket.Sink {

        final List<LiveSocket.State> states = new ArrayList<>();
        final List<String> reasons = new ArrayList<>();
        final List<String> frames = new ArrayList<>();

        @Override
        public void state(final LiveSocket.State state, final String reason) {
            states.add(state);
            reasons.add(reason);
        }

        @Override
        public void frame(final String text) {
            frames.add(text);
        }

        long count(final LiveSocket.State s) {
            return states.stream()
                .filter(x -> x == s)
                .count();
        }
    }

    @AfterEach
    void close() {
        if (socket != null) socket.stop();
        if (server != null) server.close();
    }

    private static void await(final Log log, final LiveSocket s, final BooleanSupplier done) throws Exception {
        final long end = System.nanoTime() + 8_000_000_000L;
        while (System.nanoTime() < end) {
            s.poll(log);
            if (done.getAsBoolean()) return;
            Thread.sleep(10);
        }
        s.poll(log);
        throw new AssertionError("timed out; states " + log.states + " " + log.reasons + " frames " + log.frames);
    }

    private LiveSocket open(final String scheme, final javax.net.ssl.SSLContext ssl) {
        return new LiveSocket(URI.create(scheme + "://localhost:" + server.port() + "/live"), () -> "tok", ssl, FAST);
    }

    @Test
    void connectsWithTheSessionAndTalksBothWays() throws Exception {
        server = new LiveTestServer(null);
        socket = open("ws", null);
        final Log log = new Log();
        assertFalse(socket.send("early"));
        socket.start();
        await(log, socket, () -> socket.state() == LiveSocket.State.OPEN);
        assertEquals(List.of(LiveSocket.State.CONNECTING, LiveSocket.State.OPEN), log.states);
        assertEquals(List.of("gtnh_session=tok"), server.cookies);

        assertTrue(socket.send("{\"t\":\"hello\",\"v\":1}"));
        assertTrue(socket.send("{\"t\":\"leave\",\"doc\":\"d\"}"));
        await(log, socket, () -> log.frames.size() == 1);
        assertTrue(
            log.frames.get(0)
                .contains("welcome"));
        assertEquals(
            2,
            server.received.stream()
                .filter(s -> !s.contains("ping"))
                .count());
        assertTrue(
            server.received.get(0)
                .contains("hello"));

        socket.stop();
        await(log, socket, () -> socket.state() == LiveSocket.State.CLOSED);
        assertFalse(socket.send("late"));
        Thread.sleep(300);
        assertEquals(1, server.connections.get());
    }

    @Test
    void pingsOnItsOwnAndLeavesAQuietLineAlone() throws Exception {
        server = new LiveTestServer(null);
        socket = open("ws", null);
        final Log log = new Log();
        socket.start();
        await(log, socket, () -> server.pings.get() >= 3);
        assertEquals(1, server.connections.get());
        // The pongs were frames like any other.
        assertTrue(
            log.frames.stream()
                .allMatch(f -> f.contains("pong")));
    }

    @Test
    void aPeerThatStopsAnsweringIsDroppedAndRetried() throws Exception {
        server = new LiveTestServer(null);
        server.answerPings = false;
        socket = open("ws", null);
        final Log log = new Log();
        socket.start();
        await(log, socket, () -> server.connections.get() >= 2);
        assertTrue(log.count(LiveSocket.State.WAITING) >= 1);
    }

    @Test
    void aDroppedConnectionComesBackAfterABackoff() throws Exception {
        server = new LiveTestServer(null);
        socket = open("ws", null);
        final Log log = new Log();
        socket.start();
        await(log, socket, () -> socket.state() == LiveSocket.State.OPEN);
        socket.send("{\"t\":\"leave\",\"doc\":\"a\"}");
        server.dropAll();
        await(log, socket, () -> log.count(LiveSocket.State.OPEN) == 2);
        assertEquals(
            List.of(
                LiveSocket.State.CONNECTING,
                LiveSocket.State.OPEN,
                LiveSocket.State.WAITING,
                LiveSocket.State.CONNECTING,
                LiveSocket.State.OPEN),
            log.states);
        assertEquals(2, server.connections.get());
        assertEquals(List.of("gtnh_session=tok", "gtnh_session=tok"), server.cookies);
        assertTrue(socket.send("{\"t\":\"hello\"}"));
    }

    @Test
    void keepsTryingWhileTheServerIsDownAndConnectsWhenItIsBack() throws Exception {
        server = new LiveTestServer(null);
        final int port = server.port();
        server.close();
        server = null;
        socket = new LiveSocket(URI.create("ws://127.0.0.1:" + port + "/live"), () -> "tok", null, FAST);
        final Log log = new Log();
        socket.start();
        await(log, socket, () -> log.count(LiveSocket.State.WAITING) >= 3);
        assertTrue(
            log.reasons.stream()
                .anyMatch(r -> r.startsWith("could not connect")));
    }

    @Test
    void aRefusedSessionStopsTheTrying() throws Exception {
        server = new LiveTestServer(null);
        server.refuseAuth = true;
        socket = open("ws", null);
        final Log log = new Log();
        socket.start();
        await(log, socket, () -> socket.state() == LiveSocket.State.CLOSED);
        assertEquals(LiveSocket.AUTH, log.reasons.get(log.reasons.size() - 1));
        Thread.sleep(500);
        socket.poll(log);
        assertEquals(1, server.cookies.size());
        assertEquals(1, log.count(LiveSocket.State.CONNECTING));
    }

    @Test
    void noSessionMeansNoConnection() throws Exception {
        server = new LiveTestServer(null);
        socket = new LiveSocket(URI.create("ws://127.0.0.1:" + server.port() + "/live"), () -> null, null, FAST);
        final Log log = new Log();
        socket.start();
        // The socket starts closed and stays so: wait for its reason, not its state (start runs on the event loop).
        await(log, socket, () -> !log.reasons.isEmpty());
        assertEquals(LiveSocket.State.CLOSED, socket.state());
        assertEquals(LiveSocket.AUTH, log.reasons.get(0));
        assertTrue(server.cookies.isEmpty());
    }

    @Test
    void bigAndFragmentedMessagesArriveWhole() throws Exception {
        server = new LiveTestServer(null);
        socket = open("ws", null);
        final Log log = new Log();
        socket.start();
        await(log, socket, () -> socket.state() == LiveSocket.State.OPEN);
        final String big = "x".repeat(3_000_000);
        server.send(big);
        server.sendFragmented("{\"t\":\"ops\",", "\"seq\":1}");
        await(log, socket, () -> log.frames.size() == 2);
        assertEquals(big, log.frames.get(0));
        assertEquals("{\"t\":\"ops\",\"seq\":1}", log.frames.get(1));
    }

    @Test
    void aQueueNobodyPollsDoesNotGrowForever() throws Exception {
        server = new LiveTestServer(null);
        socket = open("ws", null);
        final Log log = new Log();
        socket.start();
        await(log, socket, () -> socket.state() == LiveSocket.State.OPEN);
        for (int i = 0; i < 21_000; i++) server.send("{\"t\":\"presence\"}");
        // Not polling: the socket gives up the connection rather than keep it all, and then reconnects.
        Thread.sleep(2_000);
        await(log, socket, () -> log.count(LiveSocket.State.OPEN) >= 2 && socket.state() == LiveSocket.State.OPEN);
        assertTrue(log.frames.size() < 21_000);
    }

    @Test
    void tlsWithTheNormalChecks() throws Exception {
        final TestTls tls = TestTls.make(dir, "dns:localhost,ip:127.0.0.1");
        server = new LiveTestServer(tls.server());
        socket = open("wss", tls.client());
        final Log log = new Log();
        socket.start();
        await(log, socket, () -> socket.state() == LiveSocket.State.OPEN);
        assertEquals(List.of("gtnh_session=tok"), server.cookies);
        assertTrue(socket.send("{\"t\":\"hello\"}"));
        await(log, socket, () -> log.frames.size() == 1);
        assertTrue(
            log.frames.get(0)
                .contains("welcome"));
    }

    @Test
    void tlsRefusesACertificateForAnotherName() throws Exception {
        final TestTls tls = TestTls.make(dir, "dns:somewhere-else.example");
        server = new LiveTestServer(tls.server());
        socket = open("wss", tls.client());
        final Log log = new Log();
        socket.start();
        await(log, socket, () -> log.count(LiveSocket.State.WAITING) >= 2);
        assertEquals(0, log.count(LiveSocket.State.OPEN));
        assertTrue(server.cookies.isEmpty());
    }

    @Test
    void tlsRefusesACertificateNobodyTrusts() throws Exception {
        final TestTls tls = TestTls.make(dir, "dns:localhost");
        server = new LiveTestServer(tls.server());
        // The JDK's own trust store knows nothing of this certificate.
        socket = open("wss", null);
        final Log log = new Log();
        socket.start();
        await(log, socket, () -> log.count(LiveSocket.State.WAITING) >= 2);
        assertEquals(0, log.count(LiveSocket.State.OPEN));
        assertTrue(server.cookies.isEmpty());
    }
}
