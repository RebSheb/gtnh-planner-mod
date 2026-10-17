package com.gtnhplanner.cloud.net;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import javax.annotation.Nullable;
import javax.net.ssl.SSLContext;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.GtnhPlanner;

/**
 * The live service's protocol (version 1, {@code docs/design/cloud-live.md}) over {@link LiveSocket}: hello and
 * welcome, joining and leaving rooms by doc key, ops batches and presence out, everything the service sends dispatched
 * to listeners. It never reads an op: the sync engine does.
 * <p>
 * Everything runs on the thread that calls {@link #poll} each tick (the client thread): listeners are called from it,
 * and the send calls are made from it. After a reconnect every joined doc is joined again once the service has said
 * welcome; each answers with a snapshot, and the caller sends its pending batches again from that.
 */
public final class LiveClient {

    public static final int PROTOCOL = 1;

    /**
     * An account in a room: {@code client} is "game" or "web"; {@code conn} tells one account's two connections apart.
     */
    public record Person(String id, String username, String color, String client, String conn) {}

    /** An account that may edit a plan. */
    public record Member(String id, String username) {}

    /**
     * The whole plan as the service holds it at {@code seq}, this session's role in it, whose it is (null when the
     * service did not say), who may edit it, and who is in it.
     */
    public record Snapshot(String doc, long seq, JsonObject plan, String role, @Nullable Member owner,
        List<Member> members, List<Person> present) {}

    /** An accepted batch, to everyone in the room; {@code cid} is the sender's batch id (its ack, for the sender). */
    public record OpsBatch(String doc, long seq, String by, String cid, JsonArray ops) {}

    /** Where someone ({@code who}, on connection {@code conn}) is looking; {@code cursor} null when off the board. */
    public record Presence(String doc, String who, String conn, @Nullable JsonObject cursor, JsonArray sel,
        @Nullable JsonObject view) {}

    /** What the service sends, one method each. Called from {@link #poll}; override what is needed. */
    public interface Listener {

        default void onState(final LiveSocket.State state, final String reason) {}

        /** Said hello back: this is who the service knows the session as. */
        default void onWelcome(final Person you) {}

        default void onSnapshot(final Snapshot snapshot) {}

        default void onOps(final OpsBatch batch) {}

        default void onPresence(final Presence presence) {}

        default void onPresent(final String doc, final List<Person> present) {}

        default void onMembers(final String doc, final List<Member> members) {}

        /** Out of the room: {@code reason} is "unshared", "deleted", or "forbidden" or "not_found" (a join refused). */
        default void onKicked(final String doc, final String reason) {}

        /** {@code doc} is empty for an error about the connection. */
        default void onError(final String doc, final String code, final String message) {}

        /**
         * The account was added to a plan: {@code doc} is its key, {@code owner} whose it is, {@code name} its name.
         */
        default void onShared(final String doc, final Member owner, final String name) {}

        /** The account was taken off a plan it was shared on. */
        default void onUnshared(final String doc) {}

        /** The service refused one of this client's batches ({@code cid}): it never happened. After its error. */
        default void onRefused(final String doc, final String cid, final String code, final String message) {}
    }

    /**
     * What the hello tells the service. {@code serverHash} (a hash of the game server's address or the world) null for
     * none.
     */
    public record Hello(String clientVersion, @Nullable String serverHash, @Nullable String mcName) {}

    /** The hash the service groups "players near you" by: SHA-256, hex, of the address or world name. */
    public static String serverHash(final String addressOrWorld) {
        try {
            final byte[] d = MessageDigest.getInstance("SHA-256")
                .digest(addressOrWorld.getBytes(StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder();
            for (final byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** At most this many presence frames a second per doc; the newest wins. */
    /** A join the service called too fast is asked again after this long. */
    static final long JOIN_RETRY_MS = 3_000;
    /** JSON nested deeper than this is not read: the service refuses it too, and parsing it is recursion. */
    public static final int MAX_DEPTH = 64;
    private static final long PRESENCE_GAP_NANOS = 100_000_000L;

    private final LiveSocket socket;
    private final Hello hello;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Set<String> joined = new LinkedHashSet<>();
    private final Map<String, JsonObject> presenceWaiting = new HashMap<>();
    private final Map<String, Long> presenceSentAt = new HashMap<>();
    /** Docs whose join the service refused as too fast, and when (nanoTime) to ask again. */
    private final Map<String, Long> joinRetryAt = new HashMap<>();
    private long joinRetryNanos = JOIN_RETRY_MS * 1_000_000L;
    /** The last batch sent on this connection that has not come back: the one a too-big close is about. */
    @Nullable
    private String[] lastBatch;
    private boolean welcomed;
    @Nullable
    private Person you;

    public LiveClient(final URI live, final Supplier<String> token, final Hello hello) {
        this(new LiveSocket(live, token), hello);
    }

    public LiveClient(final URI live, final Supplier<String> token, final Hello hello, @Nullable final SSLContext ssl,
        final LiveSocket.Options options) {
        this(new LiveSocket(live, token, ssl, options), hello);
    }

    LiveClient(final LiveSocket socket, final Hello hello) {
        this.socket = socket;
        this.hello = hello;
    }

    /** How long a join the service refused as too fast waits before it is asked again (tests shorten it). */
    public void joinRetryAfterMs(final long ms) {
        joinRetryNanos = ms * 1_000_000L;
    }

    public void addListener(final Listener l) {
        listeners.add(l);
    }

    public void removeListener(final Listener l) {
        listeners.remove(l);
    }

    /** Connects and keeps the connection until {@link #stop}. */
    public void start() {
        socket.start();
    }

    public void stop() {
        socket.stop();
    }

    public LiveSocket.State state() {
        return socket.state();
    }

    /** True once the service has said welcome on this connection: frames sent now are heard. */
    public boolean ready() {
        return welcomed;
    }

    /** Who the service knows this session as, from the last welcome; null before the first. */
    @Nullable
    public Person you() {
        return you;
    }

    /** The docs joined (or to be joined once connected), oldest first. */
    public Set<String> joined() {
        return new LinkedHashSet<>(joined);
    }

    /** Joins a doc's room; its snapshot arrives as {@link Listener#onSnapshot}. Joined again after every reconnect. */
    public void join(final String doc) {
        if (!joined.add(doc)) return;
        joinRetryAt.remove(doc);
        if (welcomed) socket.send(frame("join", doc).toString());
    }

    public void leave(final String doc) {
        if (!joined.remove(doc)) return;
        presenceWaiting.remove(doc);
        presenceSentAt.remove(doc);
        joinRetryAt.remove(doc);
        if (welcomed) socket.send(frame("leave", doc).toString());
    }

    /**
     * Sends one local edit: {@code base} is the seq the client had, {@code cid} its batch id. False when not connected
     * or the queue is full; the caller keeps the batch pending and sends it again from the next snapshot.
     */
    public boolean sendOps(final String doc, final long base, final String cid, final JsonArray ops) {
        if (!welcomed) return false;
        final JsonObject f = frame("ops", doc);
        f.addProperty("base", base);
        f.addProperty("cid", cid);
        f.add("ops", ops);
        final boolean sent = socket.send(f.toString());
        if (sent) lastBatch = new String[] { doc, cid };
        return sent;
    }

    /** A cursor position in the plan's own coordinates. */
    public static JsonObject at(final double x, final double y) {
        final JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("y", y);
        return o;
    }

    /**
     * Says where this player is looking: {@code cursor} (null off the board), the selected ids, and the view when it
     * is to be shared. Sent at once if the last one went more than 0.1 s ago, else when {@link #poll} finds it due;
     * a newer one replaces one still waiting.
     */
    public void sendPresence(final String doc, @Nullable final JsonObject cursor, final List<String> selected,
        @Nullable final JsonObject view) {
        if (!joined.contains(doc)) return;
        final JsonObject f = frame("presence", doc);
        if (cursor == null) f.add("cursor", com.google.gson.JsonNull.INSTANCE);
        else f.add("cursor", cursor);
        final JsonArray sel = new JsonArray();
        for (final String id : selected) sel.add(new com.google.gson.JsonPrimitive(id));
        f.add("sel", sel);
        if (view != null) f.add("view", view);
        presenceWaiting.put(doc, f);
        flushPresence();
    }

    /** Hands out what has arrived, then sends any presence that is due. Run it each tick on the client thread. */
    public void poll() {
        socket.poll(new LiveSocket.Sink() {

            @Override
            public void state(final LiveSocket.State s, final String reason) {
                stateChanged(s, reason);
            }

            @Override
            public void frame(final String text) {
                received(text);
            }
        });
        flushPresence();
        retryJoins();
    }

    private void retryJoins() {
        if (!welcomed || joinRetryAt.isEmpty()) return;
        final long now = System.nanoTime();
        for (final Map.Entry<String, Long> e : new ArrayList<>(joinRetryAt.entrySet())) {
            if (now - e.getValue() < 0) continue;
            joinRetryAt.remove(e.getKey());
            if (joined.contains(e.getKey())) socket.send(frame("join", e.getKey()).toString());
        }
    }

    private void flushPresence() {
        if (!welcomed || presenceWaiting.isEmpty()) return;
        final long now = System.nanoTime();
        for (final String doc : new ArrayList<>(presenceWaiting.keySet())) {
            final Long last = presenceSentAt.get(doc);
            if (last != null && now - last < PRESENCE_GAP_NANOS) continue;
            if (socket.send(
                presenceWaiting.get(doc)
                    .toString())) {
                presenceSentAt.put(doc, now);
                presenceWaiting.remove(doc);
            }
        }
    }

    private void stateChanged(final LiveSocket.State s, final String reason) {
        if (s == LiveSocket.State.OPEN) {
            final JsonObject h = new JsonObject();
            h.addProperty("t", "hello");
            h.addProperty("v", PROTOCOL);
            h.addProperty("client", "game");
            h.addProperty("clientVersion", hello.clientVersion());
            if (hello.serverHash() != null) {
                final JsonObject game = new JsonObject();
                game.addProperty("server", hello.serverHash());
                if (hello.mcName() != null) game.addProperty("mcName", hello.mcName());
                h.add("game", game);
            }
            socket.send(h.toString());
        } else {
            welcomed = false;
            joinRetryAt.clear();
            final String[] culprit = lastBatch;
            lastBatch = null;
            if (("close:" + LiveSocket.CLOSE_TOO_BIG).equals(reason) && culprit != null) {
                // The service closed on a frame it found too big: that batch can never succeed, so it is refused here
                // (and not sent again) rather than looping through reconnects.
                GtnhPlanner.LOG
                    .error("Live: the service closed on a frame too big (an edit to {}); dropping it", culprit[0]);
                for (final Listener l : listeners)
                    l.onRefused(culprit[0], culprit[1], "too_big", "the batch was too big for the service");
            }
        }
        for (final Listener l : listeners) l.onState(s, reason);
    }

    private void received(final String text) {
        if (tooDeep(text)) {
            GtnhPlanner.LOG.warn("Live: dropped a frame nested more than {} deep", MAX_DEPTH);
            return;
        }
        final JsonObject o;
        try {
            o = new JsonParser().parse(text)
                .getAsJsonObject();
        } catch (final StackOverflowError e) {
            GtnhPlanner.LOG.warn("Live: dropped a frame too deeply nested to read");
            return;
        } catch (final RuntimeException e) {
            GtnhPlanner.LOG.debug("Live: unreadable frame", e);
            return;
        }
        final String t = str(o, "t");
        final String doc = str(o, "doc");
        try {
            switch (t) {
                case "welcome" -> {
                    you = person(o.getAsJsonObject("you"));
                    welcomed = true;
                    for (final String d : joined) socket.send(frame("join", d).toString());
                    for (final Listener l : listeners) l.onWelcome(you);
                }
                case "snapshot" -> {
                    final JsonObject owner = object(o, "owner");
                    final Snapshot s = new Snapshot(
                        doc,
                        num(o, "seq"),
                        o.getAsJsonObject("plan"),
                        str(o, "role"),
                        owner == null ? null : new Member(str(owner, "id"), str(owner, "username")),
                        members(o.get("members")),
                        people(o.get("present")));
                    joinRetryAt.remove(doc);
                    for (final Listener l : listeners) l.onSnapshot(s);
                }
                case "ops" -> {
                    final OpsBatch b = new OpsBatch(doc, num(o, "seq"), str(o, "by"), str(o, "cid"), array(o, "ops"));
                    if (lastBatch != null && lastBatch[1].equals(b.cid())) lastBatch = null;
                    for (final Listener l : listeners) l.onOps(b);
                }
                case "presence" -> {
                    final JsonElement who = o.get("who");
                    final Presence p = new Presence(
                        doc,
                        who != null && who.isJsonObject() ? str(who.getAsJsonObject(), "id")
                            : who == null || who.isJsonNull() ? "" : who.getAsString(),
                        str(o, "conn"),
                        object(o, "cursor"),
                        array(o, "sel"),
                        object(o, "view"));
                    for (final Listener l : listeners) l.onPresence(p);
                }
                case "present" -> {
                    final List<Person> p = people(o.get("present"));
                    for (final Listener l : listeners) l.onPresent(doc, p);
                }
                case "members" -> {
                    final List<Member> m = members(o.get("members"));
                    for (final Listener l : listeners) l.onMembers(doc, m);
                }
                case "shared" -> {
                    final JsonObject owner = object(o, "owner");
                    final Member who = owner == null ? new Member("", "")
                        : new Member(str(owner, "id"), str(owner, "username"));
                    for (final Listener l : listeners) l.onShared(doc, who, str(o, "name"));
                }
                case "unshared" -> {
                    for (final Listener l : listeners) l.onUnshared(doc);
                }
                case "kicked" -> {
                    joined.remove(doc);
                    presenceWaiting.remove(doc);
                    for (final Listener l : listeners) l.onKicked(doc, str(o, "reason"));
                }
                case "error" -> {
                    final String code = str(o, "code");
                    final String cid = str(o, "cid");
                    // The service will not have us in that room (a join refused, not a batch): stop joining it on every
                    // reconnect.
                    final boolean refused = !doc.isEmpty() && cid.isEmpty()
                        && (code.equals("forbidden") || code.equals("not_found"));
                    if (code.equals("rate") && !doc.isEmpty() && cid.isEmpty() && joined.contains(doc))
                        joinRetryAt.put(doc, System.nanoTime() + joinRetryNanos);
                    if (refused) {
                        joined.remove(doc);
                        presenceWaiting.remove(doc);
                        presenceSentAt.remove(doc);
                    }
                    for (final Listener l : listeners) l.onError(doc, code, str(o, "msg"));
                    if (!cid.isEmpty()) for (final Listener l : listeners) l.onRefused(doc, cid, code, str(o, "msg"));
                    if (refused) for (final Listener l : listeners) l.onKicked(doc, code);
                }
                default -> {
                    // "pong", and anything a newer service adds.
                }
            }
        } catch (final StackOverflowError e) {
            GtnhPlanner.LOG.warn("Live: dropped a '{}' frame too deeply nested to read", t);
        } catch (final RuntimeException e) {
            GtnhPlanner.LOG.warn("Live: a '{}' frame did not read", t, e);
        }
    }

    /** Whether the JSON text nests arrays or objects more than {@link #MAX_DEPTH} deep (strings skipped). */
    public static boolean tooDeep(final String text) {
        int depth = 0;
        boolean inString = false;
        for (int i = 0, n = text.length(); i < n; i++) {
            final char c = text.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
            } else if (c == '"') inString = true;
            else if (c == '{' || c == '[') {
                if (++depth > MAX_DEPTH) return true;
            } else if (c == '}' || c == ']') depth--;
        }
        return false;
    }

    private static JsonObject frame(final String type, final String doc) {
        final JsonObject f = new JsonObject();
        f.addProperty("t", type);
        f.addProperty("doc", doc);
        return f;
    }

    // region JSON

    private static Person person(@Nullable final JsonObject o) {
        if (o == null) return new Person("", "", "", "", "");
        return new Person(str(o, "id"), str(o, "username"), str(o, "color"), str(o, "client"), str(o, "conn"));
    }

    private static List<Person> people(@Nullable final JsonElement e) {
        final List<Person> out = new ArrayList<>();
        if (e != null && e.isJsonArray()) for (final JsonElement p : e.getAsJsonArray()) {
            if (p.isJsonObject()) out.add(person(p.getAsJsonObject()));
        }
        return out;
    }

    private static List<Member> members(@Nullable final JsonElement e) {
        final List<Member> out = new ArrayList<>();
        if (e != null && e.isJsonArray()) for (final JsonElement m : e.getAsJsonArray()) {
            if (m.isJsonObject())
                out.add(new Member(str(m.getAsJsonObject(), "id"), str(m.getAsJsonObject(), "username")));
        }
        return out;
    }

    private static String str(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private static long num(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? 0 : e.getAsLong();
    }

    private static JsonArray array(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    @Nullable
    private static JsonObject object(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    // endregion
}
