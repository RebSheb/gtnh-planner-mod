package com.gtnhplanner.cloud.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.cloud.sync.LiveOp;
import com.gtnhplanner.cloud.sync.LiveOps;
import com.gtnhplanner.cloud.sync.LiveState;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.data.flowchart.Tasks;
import com.gtnhplanner.data.flowchart.balancer.BalanceMode;

/**
 * Edits made on the board as the ops they send, and the engine and the mapping together: whatever a player does, the
 * batch applied to the plan gives the plan written, undoes cleanly, and reads back as the board.
 */
class CloudEditsTest {

    /** One open plan in the game: the board, the plan it shows and what that was read as. */
    private static final class Open {

        final TestGame game;
        Graph board;
        JsonObject plan;
        Basis basis;

        Open(final Graph g) {
            game = new TestGame().knowing(g);
            plan = CloudPlans.write(g, game, null, null)
                .plan();
            final CloudPlans.Read r = CloudPlans.read(plan, game);
            board = r.graph();
            basis = r.basis();
        }

        /** The board written against the plan: the batch it sends, checked against the engine's rules. */
        List<LiveOp> send(final String what) {
            final CloudPlans.Written w = CloudPlans.write(board, game, plan, basis);
            final List<LiveOp> ops = CloudPlans.diff(plan, w.plan());
            final JsonObject applied = LiveOps.apply(Derived.plan(plan), ops);
            assertTrue(
                LiveOps.plansEqual(applied, Derived.plan(w.plan())),
                what + ": the batch makes the plan written");
            final List<LiveOp> undo = LiveOps.invert(Derived.plan(plan), ops);
            assertTrue(LiveOps.plansEqual(LiveOps.apply(applied, undo), Derived.plan(plan)), what + ": and undoes");
            // Read back, the plan written is the board.
            final CloudPlans.Read r = CloudPlans.read(w.plan(), game.knowing(board));
            assertEquals(
                Canon.of(Boards.persisted(board)),
                Canon.of(Boards.persisted(r.graph())),
                what + ": " + CloudPlansTest.firstDifference(Boards.persisted(board), Boards.persisted(r.graph())));
            plan = w.plan();
            basis = w.basis();
            return ops;
        }
    }

    private static JsonObject json(final String text) {
        return new JsonParser().parse(text)
            .getAsJsonObject();
    }

    @Test
    void eachBoardEditIsTheOpsItTouches() {
        final Open o = new Open(Boards.dress(Boards.corpus("platline")));
        assertEquals(List.of(), o.send("nothing"));

        final Node a = o.board.getNodes()
            .iterator()
            .next();
        a.x += 30;
        List<LiveOp> ops = o.send("a move");
        assertEquals(1, ops.size(), ops.toString());
        assertEquals(
            LiveOp.Kind.PATCH,
            ops.getFirst()
                .o());
        assertEquals("[\"position\"]", fields(ops.getFirst()));

        a.machineConfig.setMachineCount(a.machineConfig.getMachineCount() + 2);
        a.setMachineCountFixed(true);
        ops = o.send("a pinned count");
        assertEquals(1, ops.size(), ops.toString());
        assertEquals("[\"gtnhplanner\",\"machineCount\",\"solvePin\"]", fields(ops.getFirst()));

        final Drawer d = o.board.getDrawers()
            .iterator()
            .next();
        d.setTarget(Drawer.Rule.AT_LEAST, 12.5);
        ops = o.send("a drawer's rate");
        assertEquals(1, ops.size(), ops.toString());
        assertEquals(
            "storages",
            ops.getFirst()
                .c());

        final Note n = new Note();
        n.setX(10);
        n.setY(20);
        n.setJoined("New");
        o.board.notes.put(n.getId(), n);
        ops = o.send("a new note");
        assertEquals(1, ops.size(), ops.toString());
        assertEquals(
            LiveOp.Kind.PUT,
            ops.getFirst()
                .o());
        assertEquals(
            "annotations",
            ops.getFirst()
                .c());

        o.board.setName("Renamed");
        o.board.setBalanceMode(
            BalanceMode.values()[(o.board.getBalanceMode()
                .ordinal() + 1) % BalanceMode.values().length]);
        ops = o.send("a rename and a balance mode");
        assertEquals(1, ops.size(), ops.toString());
        assertEquals("[\"gtnhplanner\",\"name\"]", fields(ops.getFirst()));

        // A card deleted takes its recipe, its wires and its drawer links with it, and nothing else.
        final Node gone = busiest(o.board);
        final int wires = wiresOf(o.board, gone.id);
        o.board.removeNode(gone.id);
        ops = o.send("a card deleted");
        int dels = 0;
        for (final LiveOp op : ops) {
            assertEquals(LiveOp.Kind.DEL, op.o(), ops.toString());
            dels++;
        }
        assertEquals(2 + wires, dels, "the card, its recipe and its " + wires + " wires: " + ops);

        // A new wire is one put.
        final List<Node> nodes = new ArrayList<>(o.board.getNodes());
        Edge added = null;
        for (final Node from : nodes) {
            for (final Node to : nodes) {
                if (from == to || from.outputs.isEmpty() || to.inputs.isEmpty()) continue;
                added = new Edge(UUID.randomUUID(), from.id, to.id, 0, 0);
                break;
            }
            if (added != null) break;
        }
        assertNotNull(added);
        o.board.addEdge(added);
        ops = o.send("a new wire");
        assertTrue(
            ops.stream()
                .anyMatch(op -> op.o() == LiveOp.Kind.PUT && "edges".equals(op.c())),
            ops.toString());
    }

    @Test
    void anyRunOfBoardEditsReadsBackAsTheBoard() {
        final Random random = new Random(11);
        for (final String name : Boards.CORPUS) {
            final Open o = new Open(Boards.dress(Boards.corpus(name)));
            for (int round = 0; round < 25; round++) {
                edit(o.board, random);
                o.send(name + " round " + round);
            }
        }
    }

    /**
     * Reading only what a batch touched (the read following the last one) gives the board a whole read gives, and that
     * board writes back as the plan, whatever someone else and this player did in between.
     */
    @Test
    void aReadFollowingTheLastIsAWholeRead() {
        final Random random = new Random(5);
        for (final String name : Boards.CORPUS) {
            final Graph g = Boards.dress(Boards.corpus(name));
            final TestGame game = new TestGame().knowing(g);
            followingReads(
                name,
                game,
                CloudPlans.write(g, game, null, null)
                    .plan(),
                random);
        }
        for (final String name : Boards.WEBSITE) followingReads(name, new TestGame(), Boards.website(name), random);
    }

    private static void followingReads(final String name, final TestGame game, final JsonObject start,
        final Random random) {
        final LiveState state = new LiveState(1, start);
        CloudPlans.Read mine = CloudPlans.read(state.view(), game);
        for (int round = 0; round < 16; round++) {
            final String what = name + " round " + round;
            if (round % 2 == 0) {
                edit(mine.graph(), random);
                final CloudPlans.Written w = CloudPlans.write(mine.graph(), game, state.view(), mine.basis());
                state.local(CloudPlans.diff(state.view(), w.plan()));
                mine = mine.after(w, state.view());
                game.knowing(mine.graph());
            }
            final CloudPlans.Read theirs = CloudPlans.read(state.view(), game);
            edit(theirs.graph(), random);
            state.receive(
                state.seq() + 1,
                null,
                CloudPlans.diff(
                    state.view(),
                    CloudPlans.write(theirs.graph(), game, state.view(), theirs.basis())
                        .plan()));
            final CloudPlans.Read touched = CloudPlans.read(state.view(), game, mine);
            final CloudPlans.Read whole = CloudPlans.read(state.view(), game);
            assertEquals(
                Canon.of(Boards.persisted(whole.graph())),
                Canon.of(Boards.persisted(touched.graph())),
                what + ": "
                    + CloudPlansTest
                        .firstDifference(Boards.persisted(whole.graph()), Boards.persisted(touched.graph())));
            assertEquals(whole.basis().byGame, touched.basis().byGame, what + ": read as the same things");
            assertEquals(
                List.of(),
                CloudPlans.diff(
                    state.view(),
                    CloudPlans.write(touched.graph(), game, state.view(), touched.basis())
                        .plan()),
                what + ": written straight back");
            mine = touched;
        }
    }

    /** One edit a player might make, picked at random. */
    private static void edit(final Graph g, final Random random) {
        final List<Node> nodes = new ArrayList<>(g.getNodes());
        final List<Drawer> drawers = new ArrayList<>(g.getDrawers());
        final List<Edge> edges = new ArrayList<>(g.getEdges());
        switch (random.nextInt(9)) {
            case 0 -> {
                if (nodes.isEmpty()) return;
                final Node n = nodes.get(random.nextInt(nodes.size()));
                n.x += random.nextInt(201) - 100;
                n.y += random.nextInt(201) - 100;
            }
            case 1 -> {
                if (nodes.isEmpty()) return;
                final Node n = nodes.get(random.nextInt(nodes.size()));
                n.setMachineCountFixed(!n.isMachineCountFixed());
                n.machineConfig.setMachineCount(1 + random.nextInt(9));
            }
            case 2 -> {
                if (edges.isEmpty()) return;
                g.removeEdge(edges.get(random.nextInt(edges.size())).id);
            }
            case 3 -> {
                if (drawers.isEmpty()) return;
                final Drawer d = drawers.get(random.nextInt(drawers.size()));
                if (!d.getLinks()
                    .isEmpty())
                    g.unlinkDrawer(
                        d.getId(),
                        d.getLinks()
                            .getFirst());
                else d.setLabel("Relabelled " + random.nextInt(9));
            }
            case 4 -> {
                final Note n = new Note();
                n.setX(random.nextInt(500));
                n.setY(random.nextInt(500));
                n.setJoined("Note " + random.nextInt(99));
                g.notes.put(n.getId(), n);
            }
            case 5 -> {
                if (nodes.size() < 3) return;
                g.removeNode(nodes.get(random.nextInt(nodes.size())).id);
            }
            case 6 -> {
                final String id = "t" + random.nextInt(1000);
                g.tasks.add(
                    new Tasks.Task(
                        id,
                        "Task " + id,
                        null,
                        Tasks.Status.TODO,
                        "b" + id,
                        List.of(),
                        List.of(),
                        null,
                        null,
                        null));
            }
            case 7 -> {
                if (nodes.isEmpty()) return;
                nodes.get(random.nextInt(nodes.size())).worldLinks.add(new int[] { 0, random.nextInt(99), 64, 7 });
            }
            default -> {
                if (drawers.isEmpty()) return;
                final Drawer d = drawers.get(random.nextInt(drawers.size()));
                d.setX(d.getX() + 17);
                d.setTarget(Drawer.Rule.EXACTLY, random.nextInt(50));
            }
        }
    }

    /**
     * A website plan: the game deletes one of its cards, and every wire to it goes, those the game had no place for
     * too.
     */
    @Test
    void aCardDeletedTakesEveryWireToIt() {
        for (final String name : Boards.WEBSITE) {
            final JsonObject plan = Boards.website(name);
            final TestGame game = new TestGame();
            final CloudPlans.Read r = CloudPlans.read(plan, game);
            Node gone = null;
            String goneId = null;
            for (final JsonElement e : plan.getAsJsonArray("nodes")) {
                final String id = e.getAsJsonObject()
                    .get("id")
                    .getAsString();
                gone = r.graph().nodes.get(PlanIds.uuid(id));
                goneId = id;
                if (gone != null) break;
            }
            assertNotNull(gone, name);
            r.graph()
                .removeNode(gone.id);
            final JsonObject written = CloudPlans.write(r.graph(), game, plan, r.basis())
                .plan();
            final Set<String> ids = new HashSet<>();
            for (final JsonElement e : written.getAsJsonArray("nodes")) ids.add(
                e.getAsJsonObject()
                    .get("id")
                    .getAsString());
            assertFalse(ids.contains(goneId), name);
            for (final JsonElement e : written.getAsJsonArray("edges")) {
                final JsonObject edge = e.getAsJsonObject();
                assertNotEquals(
                    goneId,
                    edge.get("source")
                        .getAsString(),
                    name + ": " + edge);
                assertNotEquals(
                    goneId,
                    edge.get("target")
                        .getAsString(),
                    name + ": " + edge);
            }
            assertEquals(
                plan.getAsJsonArray("nodes")
                    .size() - 1,
                written.getAsJsonArray("nodes")
                    .size(),
                name + ": only that card");
        }
    }

    /**
     * The game and the website editing one plan at once, through the live engine: the game moves a card while the
     * website pins another's count; the service takes the website's batch first. Both end on the same plan, and the
     * game's board, read from it, has both edits.
     */
    @Test
    void theGameAndTheWebsiteEditTogether() {
        final Graph g = Boards.dress(Boards.corpus("sulfuric-acid"));
        final TestGame game = new TestGame().knowing(g);
        final JsonObject start = CloudPlans.write(g, game, null, null)
            .plan();
        final Deque<String> cids = new ArrayDeque<>(List.of("g1", "w1"));
        final LiveState inGame = new LiveState(5, start, cids::removeFirst),
            onSite = new LiveState(5, start, cids::removeFirst);

        final CloudPlans.Read r = CloudPlans.read(inGame.view(), game);
        final List<Node> nodes = new ArrayList<>(
            r.graph()
                .getNodes());
        final Node moved = nodes.get(0), pinned = nodes.get(1);
        moved.x += 200;
        final CloudPlans.Written w = CloudPlans.write(r.graph(), game, inGame.view(), r.basis());
        final LiveState.Batch fromGame = inGame.local(CloudPlans.diff(inGame.view(), w.plan()));
        assertNotNull(fromGame);

        final LiveState.Batch fromSite = onSite.local(
            List.of(LiveOp.patch("nodes", pinned.id.toString(), json("{\"solvePin\":4,\"machineCount\":4}"), null)));
        assertNotNull(fromSite);

        // The service: the website's batch is seq 6, the game's seq 7; each goes to everyone.
        inGame.receive(6, fromSite.cid(), fromSite.ops());
        onSite.receive(6, fromSite.cid(), fromSite.ops());
        inGame.receive(7, fromGame.cid(), fromGame.ops());
        onSite.receive(7, fromGame.cid(), fromGame.ops());
        assertTrue(
            inGame.pending()
                .isEmpty()
                && onSite.pending()
                    .isEmpty());
        assertTrue(LiveOps.plansEqual(inGame.view(), onSite.view()), "both on the same plan");

        final CloudPlans.Read now = CloudPlans.read(inGame.view(), game);
        assertEquals(moved.x, now.graph().nodes.get(moved.id).x, "the game's move");
        assertEquals(4, now.graph().nodes.get(pinned.id).machineConfig.getMachineCount(), "the website's pin");
        assertTrue(
            now.graph().nodes.get(pinned.id)
                .isMachineCountFixed());
    }

    private static String fields(final LiveOp op) {
        final List<String> names = new ArrayList<>();
        for (final java.util.Map.Entry<String, JsonElement> e : op.f()
            .entrySet()) names.add(e.getKey());
        names.addAll(op.deletes());
        names.sort(String::compareTo);
        final StringBuilder s = new StringBuilder("[");
        for (int i = 0; i < names.size(); i++) s.append(i == 0 ? "" : ",")
            .append('"')
            .append(names.get(i))
            .append('"');
        return s.append(']')
            .toString();
    }

    /** The card with the most wires and drawer links. */
    private static Node busiest(final Graph g) {
        Node best = null;
        int most = -1;
        for (final Node n : g.getNodes()) {
            final int wires = wiresOf(g, n.id);
            if (wires > most) {
                most = wires;
                best = n;
            }
        }
        return best;
    }

    private static int wiresOf(final Graph g, final UUID node) {
        int wires = 0;
        for (final Edge e : g.getEdges()) if (e.sourceNodeId.equals(node) || e.targetNodeId.equals(node)) wires++;
        for (final Drawer d : g.getDrawers()) for (final Drawer.Link l : d.getLinks()) if (l.nodeId()
            .equals(node)) wires++;
        return wires;
    }
}
