package com.gtnhplanner.cloud.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/** The engine beyond the shared vectors: reading ops off the wire, sharing, size, and properties over real plans. */
class LiveOpsTest {

    /** Website plans of every size, as the library and its users made them. */
    static final String[] PLANS = { "alumina-line-shared-reactor", "biodiesel-demo", "high-amperage-reactor",
        "pa-cell-loop-plan", "titanium-line-chembath.slim", "titanium-line-posted-alpha5" };

    static JsonObject plan(final String name) {
        try (InputStream in = LiveOpsTest.class.getResourceAsStream("/factory-flow/" + name + ".json")) {
            assertNotNull(in, name);
            return new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8))
                .getAsJsonObject();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonObject json(final String text) {
        return new JsonParser().parse(text)
            .getAsJsonObject();
    }

    private static boolean refused(final String ops) {
        final LiveOps.Read read = LiveOps.read(new JsonParser().parse(ops));
        return read.ops() == null && read.error() != null;
    }

    @Test
    void readsOpsOffTheWireAndRefusesBadShapes() {
        final LiveOps.Read ok = LiveOps
            .read(new JsonParser().parse("[{\"o\":\"put\",\"c\":\"nodes\",\"id\":\"n\",\"v\":{\"id\":\"n\"}}]"));
        assertNotNull(ok.ops());
        assertEquals(
            "[{\"o\":\"put\",\"c\":\"nodes\",\"id\":\"n\",\"v\":{\"id\":\"n\"}}]",
            LiveOp.toJson(ok.ops())
                .toString());
        assertTrue(refused("{}"));
        assertTrue(refused("[{\"o\":\"put\",\"c\":\"nodes\",\"id\":\"n\",\"v\":{\"id\":\"m\"}}]"));
        assertTrue(refused("[{\"o\":\"put\",\"c\":\"widgets\",\"id\":\"n\",\"v\":{\"id\":\"n\"}}]"));
        assertTrue(refused("[{\"o\":\"patch\",\"c\":\"nodes\",\"id\":\"n\",\"f\":{\"id\":\"x\"}}]"));
        assertTrue(refused("[{\"o\":\"patch\",\"c\":\"nodes\",\"id\":\"n\",\"f\":{},\"d\":[3]}]"));
        assertTrue(refused("[{\"o\":\"meta\",\"f\":{\"nodes\":[]}}]"));
        assertTrue(refused("[{\"o\":\"del\",\"c\":\"nodes\",\"id\":\"\"}]"));
        assertTrue(refused("[{\"o\":\"move\",\"c\":\"nodes\",\"id\":\"n\"}]"));
        assertEquals(
            "op 0: field \"nodes\" cannot be set",
            LiveOps.read(new JsonParser().parse("[{\"o\":\"meta\",\"f\":{\"nodes\":[]}}]"))
                .error());
    }

    @Test
    void sharesWhatABatchDidNotTouch() {
        final JsonObject plan = json("{\"nodes\":[{\"id\":\"n1\",\"a\":1}],\"edges\":[{\"id\":\"e1\"}]}");
        final JsonObject next = LiveOps.apply(plan, List.of(LiveOp.patch("nodes", "n1", json("{\"a\":2}"), null)));
        assertSame(plan.get("edges"), next.get("edges"));
        assertTrue(plan.get("nodes") != next.get("nodes"));
        assertEquals(
            1,
            plan.getAsJsonArray("nodes")
                .get(0)
                .getAsJsonObject()
                .get("a")
                .getAsInt(),
            "the input is unchanged");
        assertTrue(
            LiveOps.diff(plan, plan)
                .isEmpty());
    }

    @Test
    void appliesALargeBatchWithoutRescanningPerOp() {
        final JsonArray nodes = new JsonArray();
        final List<LiveOp> ops = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            final JsonObject n = new JsonObject();
            n.addProperty("id", "n" + i);
            nodes.add(n);
            ops.add(LiveOp.del("nodes", "n" + i));
        }
        final JsonObject plan = new JsonObject();
        plan.add("nodes", nodes);
        final long start = System.nanoTime();
        final JsonObject next = LiveOps.apply(plan, ops);
        assertEquals(
            0,
            next.getAsJsonArray("nodes")
                .size());
        assertTrue(System.nanoTime() - start < 2_000_000_000L, "a batch costs one pass");
    }

    @Test
    void aWholePlanEditBecomesABatchAndUndoesAndRedoes() {
        final int[] n = { 0 };
        final LiveState state = new LiveState(0, json("{\"name\":\"A\",\"nodes\":[]}"), () -> "c" + ++n[0]);
        final LiveState.Batch batch = state.edit(json("{\"name\":\"B\",\"nodes\":[{\"id\":\"n1\"}]}"));
        assertNotNull(batch);
        assertEquals(
            "[{\"o\":\"put\",\"c\":\"nodes\",\"id\":\"n1\",\"v\":{\"id\":\"n1\"}},{\"o\":\"meta\",\"f\":{\"name\":\"B\"}}]",
            LiveOp.toJson(batch.ops())
                .toString());
        assertNull(state.edit(state.view()), "nothing changed, no batch");
        assertEquals(
            "{\"t\":\"ops\",\"doc\":\"u:d\",\"base\":0,\"cid\":\"c1\",\"ops\":" + LiveOp.toJson(batch.ops()) + "}",
            state.frame("u:d", batch)
                .toString());
        state.undo();
        assertTrue(LiveOps.plansEqual(state.view(), json("{\"name\":\"A\",\"nodes\":[]}")));
        assertTrue(state.canRedo());
        state.redo();
        assertTrue(LiveOps.plansEqual(state.view(), json("{\"name\":\"B\",\"nodes\":[{\"id\":\"n1\"}]}")));
        assertEquals(List.of("c1", "c2", "c3"), state.pendingCids());
    }

    @Test
    void survivesASaveAndRestoreWithItsPendingBatches() {
        final LiveState state = new LiveState(3, json("{\"name\":\"A\"}"), () -> "x");
        state.edit(json("{\"name\":\"B\"}"));
        final LiveState copy = LiveState.restore(
            json(
                state.save()
                    .toString()));
        assertNotNull(copy);
        assertEquals(3, copy.seq());
        assertEquals(List.of("x"), copy.pendingCids());
        assertTrue(LiveOps.plansEqual(copy.view(), json("{\"name\":\"B\"}")));
        assertEquals(
            LiveOp.toJson(
                state.pending()
                    .get(0)
                    .inverse()),
            LiveOp.toJson(
                copy.pending()
                    .get(0)
                    .inverse()));
        assertNull(LiveState.restore(json("{\"confirmed\":{}}")), "no seq, no state");
    }

    /**
     * Over real plans: whatever edit is made (things added, replaced, patched, removed, fields deleted, meta changed),
     * its diff applied to the plan before gives the plan after, and the diff's inverse gives the plan before again.
     */
    @Test
    void diffApplyAndInvertAgreeOnRealPlans() {
        final Random random = new Random(7);
        for (final String name : PLANS) {
            final JsonObject before = plan(name);
            final String text = before.toString();
            for (int round = 0; round < 40; round++) {
                final JsonObject after = mutate(before, random);
                final List<LiveOp> ops = LiveOps.diff(before, after);
                assertTrue(LiveOps.plansEqual(LiveOps.apply(before, ops), after), name + " round " + round);
                final List<LiveOp> inverse = LiveOps.invert(before, ops);
                assertTrue(
                    LiveOps.plansEqual(LiveOps.apply(LiveOps.apply(before, ops), inverse), before),
                    name + " round " + round + " undo");
                assertTrue(
                    LiveOps.diff(after, LiveOps.apply(before, ops))
                        .isEmpty(),
                    name + " round " + round);
                // Every batch reads back off the wire as itself.
                final LiveOps.Read read = LiveOps.read(LiveOp.toJson(ops));
                assertNotNull(read.ops(), read.error());
                assertTrue(LiveJson.equal(LiveOp.toJson(read.ops()), LiveOp.toJson(ops)));
            }
            assertEquals(text, before.toString(), name + ": the plan was changed");
        }
    }

    /** A copy of the plan with a few random edits a person might make. */
    private static JsonObject mutate(final JsonObject plan, final Random random) {
        final JsonObject out = LiveJson.copy(plan);
        final int edits = 1 + random.nextInt(6);
        for (int i = 0; i < edits; i++) {
            final String c = LiveOps.COLLECTIONS.get(random.nextInt(5));
            final JsonElement listElement = out.get(c);
            final JsonArray list = listElement != null && listElement.isJsonArray() ? listElement.getAsJsonArray()
                : new JsonArray();
            out.add(c, list);
            switch (random.nextInt(6)) {
                case 0 -> {
                    final JsonObject thing = new JsonObject();
                    thing.addProperty("id", "new-" + i + "-" + random.nextInt(1000));
                    thing.addProperty("machineCount", random.nextInt(9));
                    list.add(thing);
                }
                case 1 -> out.add(c, without(list, random));
                case 2, 3 -> {
                    if (list.size() == 0) break;
                    final JsonObject thing = list.get(random.nextInt(list.size()))
                        .getAsJsonObject();
                    final List<String> keys = new ArrayList<>();
                    for (final Map.Entry<String, JsonElement> e : thing.entrySet())
                        if (!"id".equals(e.getKey())) keys.add(e.getKey());
                    if (keys.isEmpty()) break;
                    final String key = keys.get(random.nextInt(keys.size()));
                    if (random.nextBoolean()) thing.remove(key);
                    else {
                        final JsonObject position = new JsonObject();
                        position.addProperty("x", random.nextInt(2000) - 1000);
                        position.addProperty("y", random.nextDouble() * 1000);
                        thing.add(random.nextBoolean() ? "position" : key, position);
                    }
                }
                case 4 -> out.add("name", new JsonPrimitive("Plan " + random.nextInt(100)));
                default -> {
                    if (random.nextBoolean()) out.remove("description");
                    else out.add("primaryView", new JsonPrimitive("tasks"));
                }
            }
        }
        return out;
    }

    private static JsonArray without(final JsonArray list, final Random random) {
        final JsonArray out = new JsonArray();
        if (list.size() == 0) return out;
        final int drop = random.nextInt(list.size());
        for (int i = 0; i < list.size(); i++) if (i != drop) out.add(list.get(i));
        return out;
    }
}
