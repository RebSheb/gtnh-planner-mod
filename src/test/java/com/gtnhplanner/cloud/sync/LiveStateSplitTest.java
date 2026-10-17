package com.gtnhplanner.cloud.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** An edit too big for one batch goes as several the service takes, and undoes and redoes as one. */
class LiveStateSplitTest {

    private static JsonObject json(final String text) {
        return new JsonParser().parse(text)
            .getAsJsonObject();
    }

    private static int nodes(final JsonObject plan) {
        return LiveOps.collection(plan, "nodes")
            .size();
    }

    @Test
    void aBigEditGoesInBatchesAndUndoesAsOne() {
        final AtomicInteger n = new AtomicInteger();
        final LiveState s = new LiveState(1, json("{\"name\":\"Big\",\"nodes\":[]}"), () -> "c" + n.incrementAndGet());
        final List<LiveOp> ops = new ArrayList<>();
        final String filler = "x".repeat(900);
        for (int i = 0; i < 300; i++)
            ops.add(LiveOp.put("nodes", "n" + i, json("{\"id\":\"n" + i + "\",\"label\":\"" + filler + "\"}")));

        final List<LiveState.Batch> sent = s.local(ops, 20_000);
        assertTrue(sent.size() > 10, "cut into batches: " + sent.size());
        int count = 0;
        for (final LiveState.Batch b : sent) {
            assertTrue(
                LiveOp.toJson(b.ops())
                    .toString()
                    .length() <= 20_000,
                "each under the size");
            count += b.ops()
                .size();
        }
        assertEquals(300, count, "every op once");
        assertEquals(300, nodes(s.view()));
        assertEquals(
            sent.size(),
            s.pending()
                .size());

        final List<LiveState.Batch> undone = s.undo(20_000);
        assertFalse(undone.isEmpty());
        assertEquals(0, nodes(s.view()), "one undo takes all of it back");
        assertFalse(s.canUndo());
        assertTrue(s.canRedo());
        s.redo(20_000);
        assertEquals(300, nodes(s.view()), "and one redo brings it again");
    }

    @Test
    void manySmallOpsAreCutAtTheServicesCount() {
        final List<LiveOp> ops = new ArrayList<>();
        for (int i = 0; i < 2_500; i++) ops.add(LiveOp.del("nodes", "n" + i));
        final List<List<LiveOp>> runs = LiveState.split(ops, 10_000_000);
        assertEquals(3, runs.size());
        assertEquals(1000, LiveState.MAX_OPS);
        assertEquals(
            LiveState.MAX_OPS,
            runs.getFirst()
                .size());
        assertEquals(
            2_500 - 2 * LiveState.MAX_OPS,
            runs.getLast()
                .size());
    }

    @Test
    void anOpBiggerThanABatchGoesAlone() {
        final List<LiveOp> ops = List.of(
            LiveOp.put("nodes", "a", json("{\"id\":\"a\"}")),
            LiveOp.put("nodes", "big", json("{\"id\":\"big\",\"t\":\"" + "y".repeat(5000) + "\"}")),
            LiveOp.put("nodes", "b", json("{\"id\":\"b\"}")));
        assertEquals(
            3,
            LiveState.split(ops, 1000)
                .size());
    }

    @Test
    void aSingleOpOverTheLimitIsKeptOutOfPendingAndReported() {
        final LiveState s = new LiveState(1, json("{\"name\":\"Big\",\"nodes\":[]}"));
        final List<LiveOp> ops = List.of(
            LiveOp.put("nodes", "a", json("{\"id\":\"a\"}")),
            LiveOp.put("nodes", "huge", json("{\"id\":\"huge\",\"t\":\"" + "y".repeat(5000) + "\"}")),
            LiveOp.put("nodes", "b", json("{\"id\":\"b\"}")));
        final List<LiveState.Batch> sent = s.local(ops, 1000);
        assertEquals(1, sent.size());
        assertEquals(
            2,
            sent.getFirst()
                .ops()
                .size());
        assertEquals(
            1,
            s.takeTooBig()
                .size());
        assertTrue(
            s.takeTooBig()
                .isEmpty(),
            "taken once");
        assertEquals(2, nodes(s.view()), "the huge one never entered the live copy");
    }

    @Test
    void everyFrameStaysUnderTheServicesLimits() {
        final LiveState s = new LiveState(1, json("{\"name\":\"Big\",\"nodes\":[]}"));
        final List<LiveOp> ops = new ArrayList<>();
        final String filler = "é".repeat(3000);
        for (int i = 0; i < 1500; i++)
            ops.add(LiveOp.put("nodes", "n" + i, json("{\"id\":\"n" + i + "\",\"label\":\"" + filler + "\"}")));
        final int max = 240 * 1024 - 512;
        for (final LiveState.Batch b : s.local(ops, max)) {
            assertTrue(
                b.ops()
                    .size() <= 1000);
            final int bytes = s.frame("u1:doc", b)
                .toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            assertTrue(bytes <= 256 * 1024, "frame " + bytes);
        }
    }
}
