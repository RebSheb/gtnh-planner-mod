package com.gtnhplanner.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhplanner.cloud.net.LiveClient;
import com.gtnhplanner.cloud.net.LiveSocket;

/** The protocol on top of the socket, against the stand-in service: handshake, rooms, ops, presence, rejoining. */
class LiveClientTest {

    @TempDir
    File dir;

    private LiveTestServer server;
    private LiveClient client;

    /** Everything the client dispatched, in order, as short strings. */
    private static final class Heard implements LiveClient.Listener {

        final List<String> events = new ArrayList<>();
        final List<LiveClient.Snapshot> snapshots = new ArrayList<>();
        final List<LiveClient.OpsBatch> ops = new ArrayList<>();
        final List<LiveClient.Presence> presence = new ArrayList<>();
        LiveClient.Person you;
        final List<String> reasons = new ArrayList<>();

        @Override
        public void onState(final LiveSocket.State state, final String reason) {
            events.add("state " + state);
            reasons.add(state + " " + reason);
        }

        @Override
        public void onRefused(final String doc, final String cid, final String code, final String message) {
            events.add("refused " + doc + " " + cid + " " + code);
        }

        @Override
        public void onWelcome(final LiveClient.Person p) {
            you = p;
            events.add("welcome");
        }

        @Override
        public void onSnapshot(final LiveClient.Snapshot s) {
            snapshots.add(s);
            events.add("snapshot " + s.doc());
        }

        @Override
        public void onOps(final LiveClient.OpsBatch b) {
            ops.add(b);
            events.add("ops " + b.cid());
        }

        @Override
        public void onPresence(final LiveClient.Presence p) {
            presence.add(p);
            events.add("presence " + p.who());
        }

        @Override
        public void onPresent(final String doc, final List<LiveClient.Person> present) {
            events.add("present " + doc + " " + present.size());
        }

        @Override
        public void onMembers(final String doc, final List<LiveClient.Member> members) {
            events.add("members " + doc + " " + members.size());
        }

        @Override
        public void onKicked(final String doc, final String reason) {
            events.add("kicked " + doc + " " + reason);
        }

        @Override
        public void onError(final String doc, final String code, final String message) {
            events.add("error " + doc + " " + code + " " + message);
        }
    }

    @AfterEach
    void close() {
        if (client != null) client.stop();
        if (server != null) server.close();
    }

    private Heard connect() throws Exception {
        server = new LiveTestServer(null);
        client = new LiveClient(
            URI.create("ws://127.0.0.1:" + server.port() + "/live"),
            () -> "tok",
            new LiveClient.Hello("0.2.0-test", LiveClient.serverHash("play.example.net:25565"), "Ada"),
            null,
            LiveSocketTest.FAST);
        final Heard heard = new Heard();
        client.addListener(heard);
        client.start();
        return heard;
    }

    private void await(final BooleanSupplier done) throws Exception {
        final long end = System.nanoTime() + 8_000_000_000L;
        while (System.nanoTime() < end) {
            client.poll();
            if (done.getAsBoolean()) return;
            Thread.sleep(10);
        }
        throw new AssertionError("timed out");
    }

    private static JsonArray ops() {
        final JsonArray a = new JsonArray();
        final JsonObject op = new JsonObject();
        op.addProperty("o", "put");
        op.addProperty("c", "nodes");
        op.addProperty("id", "n1");
        a.add(op);
        return a;
    }

    @Test
    void helloWelcomeJoinAndSnapshot() throws Exception {
        final Heard heard = connect();
        server.plan.addProperty("name", "Smelter");
        assertFalse(client.ready());
        client.join("u9:d1");
        await(() -> !heard.snapshots.isEmpty());

        final JsonObject hello = server.framesOf("hello")
            .get(0);
        assertEquals(
            1,
            hello.get("v")
                .getAsInt());
        assertEquals(
            "game",
            hello.get("client")
                .getAsString());
        assertEquals(
            "0.2.0-test",
            hello.get("clientVersion")
                .getAsString());
        assertEquals(
            LiveClient.serverHash("play.example.net:25565"),
            hello.getAsJsonObject("game")
                .get("server")
                .getAsString());
        assertEquals(
            "Ada",
            hello.getAsJsonObject("game")
                .get("mcName")
                .getAsString());
        assertEquals(
            64,
            hello.getAsJsonObject("game")
                .get("server")
                .getAsString()
                .length());

        assertEquals("ada", heard.you.username());
        assertEquals(
            "u1",
            client.you()
                .id());
        assertTrue(client.ready());
        // The join waited for the welcome; the hello was first on the wire.
        assertTrue(
            server.received.get(0)
                .contains("hello"));
        assertEquals(List.of("state CONNECTING", "state OPEN", "welcome", "snapshot u9:d1"), heard.events);
        final LiveClient.Snapshot s = heard.snapshots.get(0);
        assertEquals("owner", s.role());
        assertEquals(
            "Smelter",
            s.plan()
                .get("name")
                .getAsString());
        assertEquals(
            "bob",
            s.members()
                .get(0)
                .username());
        assertEquals(0, s.seq());
    }

    @Test
    void anOpsBatchIsEchoedBackAsItsAck() throws Exception {
        final Heard heard = connect();
        client.join("u9:d1");
        await(() -> client.ready() && !heard.snapshots.isEmpty());
        assertTrue(client.sendOps("u9:d1", 0, "c-1", ops()));
        await(() -> !heard.ops.isEmpty());
        final LiveClient.OpsBatch b = heard.ops.get(0);
        assertEquals("c-1", b.cid());
        assertEquals(1, b.seq());
        assertEquals("u1", b.by());
        assertEquals(
            "n1",
            b.ops()
                .get(0)
                .getAsJsonObject()
                .get("id")
                .getAsString());
        final JsonObject sent = server.framesOf("ops")
            .get(0);
        assertEquals(
            0,
            sent.get("base")
                .getAsInt());
        assertEquals(
            "u9:d1",
            sent.get("doc")
                .getAsString());
    }

    @Test
    void notReadyMeansNothingIsSent() throws Exception {
        connect();
        assertFalse(client.sendOps("d", 0, "c", ops()));
        client.sendPresence("d", LiveClient.at(1, 2), List.of(), null);
        client.poll();
        assertTrue(
            server.framesOf("presence")
                .isEmpty());
    }

    @Test
    void presenceIsThrottledAndTheNewestWins() throws Exception {
        final Heard heard = connect();
        client.join("d");
        await(() -> !heard.snapshots.isEmpty());
        final long start = System.nanoTime();
        for (int i = 0; i < 200; i++) client.sendPresence("d", LiveClient.at(i, 0), List.of("n" + i), null);
        final long took = System.nanoTime() - start;
        // The first went at once, the rest are one waiting, replaced each time.
        await(
            () -> server.framesOf("presence")
                .size() >= 2);
        final List<JsonObject> frames = server.framesOf("presence");
        assertTrue(
            frames.size() <= 1 + took / 100_000_000L + 2,
            "sent " + frames.size() + " in " + took / 1_000_000 + " ms");
        final JsonObject last = frames.get(frames.size() - 1);
        assertEquals(
            199,
            last.getAsJsonObject("cursor")
                .get("x")
                .getAsInt());
        assertEquals(
            "n199",
            last.getAsJsonArray("sel")
                .get(0)
                .getAsString());

        // And a rate of twenty a second stays at about ten.
        final int before = server.framesOf("presence")
            .size();
        final long t0 = System.nanoTime();
        int i = 0;
        while (System.nanoTime() - t0 < 1_000_000_000L) {
            client.sendPresence("d", LiveClient.at(i++, 1), List.of(), null);
            client.poll();
            Thread.sleep(50);
        }
        Thread.sleep(150);
        final int sent = server.framesOf("presence")
            .size() - before;
        assertTrue(sent >= 6 && sent <= 12, "sent " + sent);
        // Leaving the cursor off the board is a null.
        Thread.sleep(150);
        client.sendPresence("d", null, List.of(), null);
        await(() -> {
            final List<JsonObject> all = server.framesOf("presence");
            return all.get(all.size() - 1)
                .get("cursor")
                .isJsonNull();
        });
    }

    @Test
    void everythingTheServiceSendsIsDispatched() throws Exception {
        final Heard heard = connect();
        client.join("d");
        await(() -> !heard.snapshots.isEmpty());
        heard.events.clear();
        server.send(
            "{\"t\":\"presence\",\"doc\":\"d\",\"who\":{\"id\":\"u2\",\"username\":\"bob\"},\"cursor\":{\"x\":3,\"y\":4},\"sel\":[\"a\"],\"view\":{\"x\":0,\"y\":0,\"zoom\":1}}");
        server.send(
            "{\"t\":\"present\",\"doc\":\"d\",\"present\":[{\"id\":\"u2\",\"username\":\"bob\",\"color\":\"#0af\",\"client\":\"web\"}]}");
        server.send(
            "{\"t\":\"members\",\"doc\":\"d\",\"members\":[{\"id\":\"u2\",\"username\":\"bob\"},{\"id\":\"u3\",\"username\":\"cy\"}]}");
        server.send("{\"t\":\"error\",\"doc\":\"d\",\"code\":\"bad_op\",\"msg\":\"no\"}");
        server.send("{\"t\":\"pong\"}");
        server.send("{\"t\":\"from-the-future\"}");
        server.send("not json at all");
        server.send("{\"t\":\"kicked\",\"doc\":\"d\",\"reason\":\"unshared\"}");
        await(() -> heard.events.contains("kicked d unshared"));
        assertEquals(
            List.of("presence u2", "present d 1", "members d 2", "error d bad_op no", "kicked d unshared"),
            heard.events);
        final LiveClient.Presence p = heard.presence.get(0);
        assertEquals(
            3,
            p.cursor()
                .get("x")
                .getAsInt());
        assertEquals(
            "a",
            p.sel()
                .get(0)
                .getAsString());
        assertNotNull(p.view());
        // Out of the room: it is not joined again.
        assertFalse(
            client.joined()
                .contains("d"));
    }

    @Test
    void everyOpenRoomIsJoinedAgainAfterADrop() throws Exception {
        final Heard heard = connect();
        client.join("a");
        client.join("b");
        await(() -> heard.snapshots.size() == 2);
        client.leave("b");
        // The leave reaches the service before the drop, or the count below races it.
        await(
            () -> server.framesOf("leave")
                .size() == 1);
        server.dropAll();
        await(() -> heard.snapshots.size() == 3);
        assertEquals(
            "a",
            heard.snapshots.get(2)
                .doc());
        assertEquals(
            2,
            server.framesOf("hello")
                .size());
        // Joined once before the drop and once after, b left in between; the hello came first both times.
        final List<String> joins = server.framesOf("join")
            .stream()
            .map(
                o -> o.get("doc")
                    .getAsString())
            .toList();
        assertEquals(List.of("a", "b", "a"), joins);
        assertEquals(
            1,
            server.framesOf("leave")
                .size());
        assertEquals(List.of("a"), List.copyOf(client.joined()));
        assertTrue(heard.events.contains("state WAITING"));
        assertEquals(
            2,
            heard.events.stream()
                .filter(e -> e.equals("welcome"))
                .count());
    }

    @Test
    void aRefusedJoinIsNotRepeatedOnReconnect() throws Exception {
        final Heard heard = connect();
        client.join("a");
        client.join("b");
        await(() -> heard.snapshots.size() == 2);
        server.send("{\"t\":\"error\",\"doc\":\"b\",\"code\":\"forbidden\",\"msg\":\"no\"}");
        await(() -> heard.events.contains("kicked b forbidden"));
        assertEquals(List.of("a"), List.copyOf(client.joined()));
        server.dropAll();
        await(() -> heard.snapshots.size() == 3);
        assertEquals(
            List.of("a", "b", "a"),
            server.framesOf("join")
                .stream()
                .map(
                    o -> o.get("doc")
                        .getAsString())
                .toList());
    }

    @Test
    void joiningTwiceAsksOnce() throws Exception {
        final Heard heard = connect();
        client.join("a");
        client.join("a");
        await(() -> !heard.snapshots.isEmpty());
        client.join("a");
        client.poll();
        assertEquals(
            1,
            server.framesOf("join")
                .size());
    }

    @Test
    void aRestartCloseReconnectsAtOnceAndJoinsAgain() throws Exception {
        final Heard heard = connect();
        client.join("a");
        await(() -> heard.snapshots.size() == 1);
        final long t0 = System.nanoTime();
        server.closeAll(1012);
        await(() -> heard.snapshots.size() == 2);
        assertTrue(heard.reasons.contains("WAITING close:1012"), heard.reasons.toString());
        assertEquals(2, server.connections.get());
        assertTrue((System.nanoTime() - t0) < 2_000_000_000L, "straight back, no backoff");
    }

    @Test
    void aTryLaterCloseBacksOffLikeAFailedConnect() throws Exception {
        final Heard heard = connect();
        client.join("a");
        await(() -> heard.snapshots.size() == 1);
        server.closeAll(1013);
        await(() -> heard.snapshots.size() == 2);
        assertTrue(heard.reasons.contains("WAITING close:1013"), heard.reasons.toString());
        // Not an auth problem: it came back, and kept the session.
        assertEquals(LiveSocket.State.OPEN, client.state());
    }

    @Test
    void aTooBigCloseDropsTheBatchThatCausedItAndGoesOn() throws Exception {
        final Heard heard = connect();
        server.rooms = true;
        server.refuse = "rate";
        client.join("a");
        await(() -> heard.snapshots.size() == 1);
        assertTrue(client.sendOps("a", 0, "big1", ops()));
        await(() -> heard.events.contains("refused a big1 rate"));
        server.closeAll(1009);
        await(() -> heard.events.contains("refused a big1 too_big"));
        await(() -> heard.snapshots.size() == 2);
    }

    @Test
    void anUpgradeAnswered429BacksOffAndIsNotAnAuthProblem() throws Exception {
        server = new LiveTestServer(null);
        server.upgradeStatus = 429;
        server.retryAfter = "0";
        client = new LiveClient(
            URI.create("ws://127.0.0.1:" + server.port() + "/live"),
            () -> "tok",
            new LiveClient.Hello("0.2.0-test", null, null),
            null,
            LiveSocketTest.FAST);
        final Heard heard = new Heard();
        client.addListener(heard);
        client.start();
        await(() -> server.upgrades.get() >= 2);
        assertFalse(heard.reasons.contains("CLOSED auth"));
        server.upgradeStatus = 0;
        await(() -> client.ready());
    }

    @Test
    void aJoinTheServiceCallsTooFastIsAskedAgainLater() throws Exception {
        final Heard heard = connect();
        server.rooms = true;
        server.rateJoins.set(1);
        client.joinRetryAfterMs(100);
        client.join("a");
        await(() -> heard.events.contains("error a rate slow down"));
        assertTrue(heard.snapshots.isEmpty());
        await(() -> heard.snapshots.size() == 1);
        assertEquals(
            2,
            server.framesOf("join")
                .size());
    }

    @Test
    void deeplyNestedFramesAreDroppedAndTheConnectionGoesOn() throws Exception {
        final Heard heard = connect();
        client.join("a");
        await(() -> heard.snapshots.size() == 1);
        server.send(
            "{\"t\":\"ops\",\"doc\":\"a\",\"seq\":1,\"by\":\"u2\",\"cid\":\"x\",\"ops\":" + "[".repeat(65)
                + "]".repeat(65)
                + "}");
        server.send(
            "{\"t\":\"ops\",\"doc\":\"a\",\"seq\":1,\"by\":\"u2\",\"cid\":\"y\",\"ops\":" + "[".repeat(200_000)
                + "]".repeat(200_000)
                + "}");
        server.send("{\"t\":\"ops\",\"doc\":\"a\",\"seq\":2,\"by\":\"u2\",\"cid\":\"z\",\"ops\":[]}");
        await(() -> heard.events.contains("ops z"));
        assertFalse(heard.events.contains("ops x"));
        assertFalse(heard.events.contains("ops y"));
        assertTrue(LiveClient.tooDeep("[".repeat(65)));
        assertFalse(LiveClient.tooDeep("{\"a\":\"" + "[".repeat(100) + "\"}"));
    }

}
