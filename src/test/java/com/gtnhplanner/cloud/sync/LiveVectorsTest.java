package com.gtnhplanner.cloud.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The shared test vectors ({@code live-vectors.json}, the website's {@code src/lib/live/}): the game's engine must
 * agree with the website's on every one, as its {@code ops.test.ts} runs them. Inputs are checked to come out of every
 * call unchanged.
 */
class LiveVectorsTest {

    private static JsonObject file() {
        try (InputStream in = LiveVectorsTest.class.getResourceAsStream("/live/live-vectors.json")) {
            assertNotNull(in, "live-vectors.json");
            return new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8))
                .getAsJsonObject();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<JsonObject> vectors() {
        final List<JsonObject> out = new ArrayList<>();
        for (final JsonElement v : file().getAsJsonArray("vectors")) out.add(v.getAsJsonObject());
        return out;
    }

    @Test
    void namesTheProtocolVersionAndTheCollections() {
        final JsonObject file = file();
        assertEquals(
            LiveOps.PROTOCOL_VERSION,
            file.get("version")
                .getAsInt());
        final List<String> collections = new ArrayList<>();
        for (final JsonElement c : file.getAsJsonArray("collections")) collections.add(c.getAsString());
        assertEquals(LiveOps.COLLECTIONS, collections);
    }

    @Test
    void everyKindIsCoveredOnce() {
        final Set<String> names = new HashSet<>(), kinds = new HashSet<>();
        final List<JsonObject> vectors = vectors();
        for (final JsonObject v : vectors) {
            assertTrue(
                names.add(
                    v.get("name")
                        .getAsString()),
                "unique names");
            kinds.add(
                v.get("kind")
                    .getAsString());
        }
        assertEquals(Set.of("jsonEqual", "plansEqual", "apply", "diff", "invert", "rebase", "client"), kinds);
        assertTrue(vectors.size() >= 73, "every vector read: " + vectors.size());
    }

    @TestFactory
    Stream<DynamicTest> everyVector() {
        return vectors().stream()
            .map(
                v -> DynamicTest.dynamicTest(
                    v.get("kind")
                        .getAsString() + ": "
                        + v.get("name")
                            .getAsString(),
                    () -> run(v)));
    }

    private static void run(final JsonObject v) {
        final String name = v.get("name")
            .getAsString();
        switch (v.get("kind")
            .getAsString()) {
            case "jsonEqual" -> {
                final boolean expect = v.get("expect")
                    .getAsBoolean();
                assertEquals(expect, LiveJson.equal(v.get("a"), v.get("b")), name);
                assertEquals(expect, LiveJson.equal(v.get("b"), v.get("a")), name);
            }
            case "plansEqual" -> {
                final boolean expect = v.get("expect")
                    .getAsBoolean();
                assertEquals(expect, LiveOps.plansEqual(obj(v, "a"), obj(v, "b")), name);
                assertEquals(expect, LiveOps.plansEqual(obj(v, "b"), obj(v, "a")), name);
            }
            case "apply" -> {
                final JsonObject plan = obj(v, "plan"), expect = obj(v, "expect");
                final JsonObject planCopy = LiveJson.copy(plan);
                final JsonArray opsJson = v.getAsJsonArray("ops"), opsCopy = LiveJson.copy(opsJson);
                final JsonObject result = LiveOps.apply(plan, ops(opsJson));
                assertTrue(LiveOps.plansEqual(result, expect), name + ": " + result);
                // The expected collections are listed in the order the rules give.
                for (final String c : LiveOps.COLLECTIONS) {
                    final JsonElement want = expect.get(c);
                    if (want != null && want.isJsonArray())
                        assertTrue(LiveJson.equal(result.get(c), want), name + ": " + c + " order " + result.get(c));
                }
                unchanged(planCopy, plan, opsCopy, opsJson, name);
            }
            case "diff" -> {
                final JsonObject before = obj(v, "before"), after = obj(v, "after");
                final JsonObject beforeCopy = LiveJson.copy(before), afterCopy = LiveJson.copy(after);
                final List<LiveOp> ops = LiveOps.diff(before, after);
                assertTrue(LiveJson.equal(LiveOp.toJson(ops), v.get("expect")), name + ": " + LiveOp.toJson(ops));
                assertTrue(LiveOps.plansEqual(LiveOps.apply(before, ops), after), name);
                assertTrue(LiveJson.equal(beforeCopy, before) && LiveJson.equal(afterCopy, after), name + " inputs");
            }
            case "invert" -> {
                final JsonObject plan = obj(v, "plan");
                final JsonObject planCopy = LiveJson.copy(plan);
                final JsonArray opsJson = v.getAsJsonArray("ops"), opsCopy = LiveJson.copy(opsJson);
                final List<LiveOp> ops = ops(opsJson);
                final List<LiveOp> inverse = LiveOps.invert(plan, ops);
                assertTrue(
                    LiveJson.equal(LiveOp.toJson(inverse), v.get("expect")),
                    name + ": " + LiveOp.toJson(inverse));
                assertTrue(LiveOps.plansEqual(LiveOps.apply(LiveOps.apply(plan, ops), inverse), plan), name);
                unchanged(planCopy, plan, opsCopy, opsJson, name);
            }
            case "rebase" -> {
                final JsonObject confirmed = obj(v, "confirmed"), expect = obj(v, "expect");
                JsonObject view = LiveOps.apply(confirmed, ops(v.getAsJsonArray("remote")));
                for (final JsonElement batch : v.getAsJsonArray("pending"))
                    view = LiveOps.apply(view, ops(batch.getAsJsonArray()));
                assertTrue(LiveOps.plansEqual(view, expect), name + ": " + view);
                // The client engine reaches the same view.
                final Deque<String> cids = new ArrayDeque<>();
                final LiveState state = new LiveState(1, confirmed, cids::removeFirst);
                int at = 0;
                for (final JsonElement batch : v.getAsJsonArray("pending")) {
                    cids.add("p" + at++);
                    state.local(ops(batch.getAsJsonArray()));
                }
                state.receive(2, "remote", ops(v.getAsJsonArray("remote")));
                assertTrue(LiveOps.plansEqual(state.view(), expect), name + ": " + state.view());
            }
            case "client" -> client(v);
            default -> throw new IllegalStateException("unknown vector kind in " + name);
        }
    }

    /** Runs one client engine through the steps, checking each step's expectations (the keys it names). */
    private static void client(final JsonObject v) {
        final JsonObject start = obj(v, "start");
        final Deque<String> cids = new ArrayDeque<>();
        final LiveState state = new LiveState(
            start.get("seq")
                .getAsLong(),
            obj(start, "plan"),
            () -> {
                if (cids.isEmpty()) throw new IllegalStateException("no cid queued");
                return cids.removeFirst();
            });
        int at = 0;
        for (final JsonElement s : v.getAsJsonArray("steps")) {
            final JsonObject step = s.getAsJsonObject();
            final String what = step.get("do")
                .getAsString();
            final String where = v.get("name")
                .getAsString() + " step "
                + at++
                + " ("
                + what
                + ")";
            Boolean own = null, gap = null;
            List<String> resend = null;
            JsonArray sent = null;
            switch (what) {
                case "local" -> {
                    cids.add(
                        step.get("cid")
                            .getAsString());
                    state.local(ops(step.getAsJsonArray("ops")));
                }
                case "receive" -> {
                    final LiveState.Received r = state.receive(
                        step.get("seq")
                            .getAsLong(),
                        step.get("cid")
                            .getAsString(),
                        ops(step.getAsJsonArray("ops")));
                    own = r.own();
                    gap = r.gap();
                }
                case "refuse" -> state.refuse(
                    step.get("cid")
                        .getAsString());
                case "snapshot" -> {
                    resend = new ArrayList<>();
                    for (final LiveState.Batch b : state.resync(
                        step.get("seq")
                            .getAsLong(),
                        obj(step, "plan"))) resend.add(b.cid());
                }
                case "undo" -> {
                    cids.add(
                        step.get("cid")
                            .getAsString());
                    final LiveState.Batch b = state.undo();
                    sent = b == null ? new JsonArray() : LiveOp.toJson(b.ops());
                }
                default -> throw new IllegalStateException(where + ": unknown step");
            }
            final JsonObject expect = step.has("expect") ? obj(step, "expect") : new JsonObject();
            for (final Map.Entry<String, JsonElement> e : expect.entrySet()) {
                final JsonElement want = e.getValue();
                switch (e.getKey()) {
                    case "seq" -> assertEquals(want.getAsLong(), state.seq(), where);
                    case "pending" -> assertEquals(strings(want), state.pendingCids(), where);
                    case "confirmed" -> assertTrue(
                        LiveOps.plansEqual(state.confirmed(), want.getAsJsonObject()),
                        where + " confirmed " + state.confirmed());
                    case "view" -> assertTrue(
                        LiveOps.plansEqual(state.view(), want.getAsJsonObject()),
                        where + " view " + state.view());
                    case "own" -> assertEquals(want.getAsBoolean(), own, where);
                    case "gap" -> assertEquals(want.getAsBoolean(), gap, where);
                    case "resend" -> assertEquals(strings(want), resend, where);
                    case "sent" -> assertTrue(LiveJson.equal(sent, want), where + " sent " + sent);
                    default -> throw new IllegalStateException(where + ": unknown expectation " + e.getKey());
                }
            }
        }
    }

    private static void unchanged(final JsonObject planCopy, final JsonObject plan, final JsonArray opsCopy,
        final JsonArray ops, final String name) {
        assertTrue(LiveJson.equal(planCopy, plan), name + ": the plan was changed");
        assertTrue(LiveJson.equal(opsCopy, ops), name + ": the ops were changed");
    }

    /** The vector's ops as given, unchecked: some carry fields an op may not touch, which applying ignores. */
    private static List<LiveOp> ops(final JsonArray json) {
        final List<LiveOp> ops = LiveOp.fromJson(json);
        assertNotNull(ops, json.toString());
        return ops;
    }

    private static JsonObject obj(final JsonObject o, final String key) {
        return o.getAsJsonObject(key);
    }

    private static List<String> strings(final JsonElement a) {
        final List<String> out = new ArrayList<>();
        for (final JsonElement e : a.getAsJsonArray()) out.add(e.getAsString());
        return out;
    }

}
