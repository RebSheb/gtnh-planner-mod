package com.gtnhplanner.cloud.map;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.gtnhplanner.cloud.sync.LiveOp;
import com.gtnhplanner.cloud.sync.LiveState;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;

/**
 * How long a 300-card plan takes to read and write: what a remote batch every tick and a local edit cost. Run with
 * {@code ./gradlew test -Pbench=cloud --tests '*CloudMapBench*'}; it prints, it never fails on time.
 */
@Tag("bench")
class CloudMapBench {

    /** Rounds that also read the whole plan afresh, to compare. */
    private static final int WHOLE = 10;

    @Test
    void readAndWriteABigPlan() {
        final Graph g = Boards.corpusCopies("platline", 6);
        final TestGame game = new TestGame().knowing(g);
        final JsonObject start = CloudPlans.write(g, game, null, null)
            .plan();
        final LiveState state = new LiveState(1, start);
        CloudPlans.Read r = CloudPlans.read(state.view(), game);
        System.out.println(
            "cards: " + r.graph().nodes.size()
                + ", things: "
                + r.basis()
                    .size());
        final List<Node> nodes = List.copyOf(
            r.graph()
                .getNodes());
        long readNs = 0, readTouchedNs = 0, writeNs = 0;
        final int rounds = Integer.getInteger("gtnhplanner.bench.runs", 40);
        for (int i = 0; i < rounds; i++) {
            // Someone else moves a card: the board reads the plan again.
            final Node other = nodes.get((i * 7) % nodes.size());
            final JsonObject at = new JsonObject();
            final JsonObject pos = new JsonObject();
            pos.addProperty("x", 100 + i);
            pos.addProperty("y", 50);
            at.add("position", pos);
            state.receive(state.seq() + 1, null, List.of(LiveOp.patch("nodes", other.id.toString(), at, null)));
            long t = System.nanoTime();
            if (i < WHOLE) {
                CloudPlans.read(state.view(), game);
                readNs += System.nanoTime() - t;
            }
            t = System.nanoTime();
            final CloudPlans.Read touched = CloudPlans.read(state.view(), game, r);
            readTouchedNs += System.nanoTime() - t;
            r = touched;
            // This player moves a card: the board is written against the plan.
            final Node mine = r.graph().nodes.get(nodes.get((i * 13 + 3) % nodes.size()).id);
            mine.x += 10;
            t = System.nanoTime();
            final CloudPlans.Written w = CloudPlans.write(r.graph(), game, state.view(), r.basis());
            final List<LiveOp> ops = CloudPlans.diff(state.view(), w.plan());
            writeNs += System.nanoTime() - t;
            state.local(ops);
            r = r.after(w, state.view());
            if (r.graph().nodes.size() != nodes.size()) throw new AssertionError("lost cards");
        }
        System.out.printf(
            "whole read %.2f ms, touched read %.2f ms, write and diff %.2f ms, a round each%n",
            readNs / 1e6 / Math.min(WHOLE, rounds),
            readTouchedNs / 1e6 / rounds,
            writeNs / 1e6 / rounds);
    }
}
