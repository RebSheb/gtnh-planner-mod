package com.gtnhplanner.cloud.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhplanner.cloud.sync.LiveJson;
import com.gtnhplanner.cloud.sync.LiveOp;
import com.gtnhplanner.cloud.sync.LiveOps;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.TaskEdits;
import com.gtnhplanner.data.flowchart.Tasks;
import com.gtnhplanner.library.PlanExport;

/**
 * A board as a shared plan and back (docs/design/cloud-live.md, "Game mapping"), over every real plan the tests have:
 * the solver corpus's and the website's.
 */
class CloudPlansTest {

    /** Every corpus plan and every website plan as the importer brings it in, dressed, by name. */
    private static List<Object[]> boards() {
        final List<Object[]> out = new ArrayList<>();
        for (final String name : Boards.CORPUS) {
            final Graph g = Boards.dress(Boards.corpus(name));
            out.add(new Object[] { "corpus " + name, g, new TestGame().knowing(g) });
        }
        for (final String name : Boards.WEBSITE) {
            final TestGame game = new TestGame();
            final Graph g = Boards.dress(Boards.imported(name, game.fake));
            out.add(new Object[] { "website " + name, g, game.knowing(g) });
        }
        return out;
    }

    @Test
    void placesAreExactBothWays() {
        for (int v = -20_000; v <= 20_000; v++) {
            final JsonObject p = PlanExport.position(v, v);
            assertEquals(
                v,
                PlanExport.gameX(
                    p.get("x")
                        .getAsDouble()));
            assertEquals(
                v,
                PlanExport.gameY(
                    p.get("y")
                        .getAsDouble()));
        }
    }

    /** Board, plan, board: everything the save holds comes back, ids and places to the pixel. */
    @Test
    void aBoardComesBackFromItsPlanAsItWas() {
        for (final Object[] b : boards()) {
            final String name = (String) b[0];
            final Graph g = (Graph) b[1];
            final TestGame game = (TestGame) b[2];
            final CloudPlans.Written w = CloudPlans.write(g, game, null, null);
            final CloudPlans.Read r = CloudPlans.read(w.plan(), game);
            assertEquals(
                Canon.of(Boards.persisted(g)),
                Canon.of(Boards.persisted(r.graph())),
                name + ": " + firstDifference(Boards.persisted(g), Boards.persisted(r.graph())));
            assertTrue(r.graph().pinList.isEmpty(), name + ": the pin list is each player's own, never in the plan");
            // Every thing the game wrote carries its own data.
            for (final String c : List.of("nodes", "storages", "edges", "annotations", "pockets"))
                for (final JsonElement t : LiveOps.collection(w.plan(), c)) assertTrue(
                    t.getAsJsonObject()
                        .has("gtnhplanner"),
                    name + ": " + t);
            assertEquals(
                g.nodes.size(),
                w.plan()
                    .getAsJsonArray("recipes")
                    .size(),
                name + ": a recipe per card");
        }
    }

    /**
     * A screenshot that came in a plan code or a chat link has no picture: the plan holds it as a screenshot thing
     * with no picture (the website refuses a picture without its thumbnail, so the whole plan would never go up).
     */
    @Test
    void aScreenshotWithoutItsPictureGoesUpWithoutOne() {
        final Graph g = new Graph("Board");
        g.setPrimaryView(Tasks.TASKS);
        TaskEdits.add(g, "t", "Build the line", Tasks.Status.TODO, List.of(), null, "2026-10-15T09:00:00.000Z");
        TaskEdits.addThing(
            g,
            "p",
            "t",
            Tasks.PICTURE,
            "Screenshot",
            null,
            null,
            new Tasks.Picture("", 1920, 1080, "2026-10-15T09:00:00.000Z", null));
        final JsonObject item = LiveOps.collection(
            CloudPlans.write(g, new TestGame().knowing(g), null, null)
                .plan(),
            "taskItems")
            .get(0)
            .getAsJsonObject();
        assertTrue(!item.has("picture"), item.toString());
        assertEquals(
            "picture",
            item.get("kind")
                .getAsString());
        assertEquals(
            "Screenshot",
            item.get("text")
                .getAsString());
    }

    /** Plan, board, plan: a plan read and written straight back is unchanged, the website's own plans too. */
    @Test
    void aPlanReadAndWrittenBackIsUnchanged() {
        for (final Object[] b : boards()) {
            final String name = (String) b[0];
            final TestGame game = (TestGame) b[2];
            final JsonObject plan = CloudPlans.write((Graph) b[1], game, null, null)
                .plan();
            assertUnchanged(name, plan, game);
        }
        for (final String name : Boards.WEBSITE)
            assertUnchanged("website " + name, Boards.website(name), new TestGame());
    }

    private static void assertUnchanged(final String name, final JsonObject plan, final TestGame game) {
        final String text = plan.toString();
        final CloudPlans.Read r = CloudPlans.read(plan, game);
        final CloudPlans.Written back = CloudPlans.write(r.graph(), game, plan, r.basis());
        assertEquals(List.of(), CloudPlans.diff(plan, back.plan()), name);
        assertEquals(text, plan.toString(), name + ": the plan read was changed");
        // And again from what the write gave: the next edit starts from it.
        final CloudPlans.Written again = CloudPlans.write(r.graph(), game, back.plan(), back.basis());
        assertEquals(List.of(), CloudPlans.diff(back.plan(), again.plan()), name + " (second write)");
    }

    /** A card moved on the website moves on the board, and nothing else does; written back, nothing changes. */
    @Test
    void aMoveOnTheWebsiteMovesOnlyThat() {
        for (final Object[] b : boards()) {
            final String name = (String) b[0];
            final Graph g = (Graph) b[1];
            final TestGame game = (TestGame) b[2];
            final JsonObject plan = CloudPlans.write(g, game, null, null)
                .plan();
            final Node moved = g.getNodes()
                .iterator()
                .next();
            final JsonObject at = new JsonObject();
            at.addProperty("x", 1001.5);
            at.addProperty("y", -333);
            final JsonObject f = new JsonObject();
            f.add("position", at);
            final JsonObject edited = LiveOps.apply(plan, List.of(LiveOp.patch("nodes", moved.id.toString(), f, null)));
            final CloudPlans.Read r = CloudPlans.read(edited, game);
            final Node there = r.graph().nodes.get(moved.id);
            // In its group's frame, when it is in one (the dressed first card is).
            final com.gtnhplanner.data.flowchart.BoardGroup frame = moved.group == null ? null
                : g.boardGroups.get(moved.group);
            assertEquals((frame == null ? 0 : frame.getX()) + PlanExport.gameX(1001.5), there.x, name);
            assertEquals((frame == null ? 0 : frame.getY()) + PlanExport.gameY(-333), there.y, name);
            final JsonObject before = Boards.persisted(g), after = Boards.persisted(r.graph());
            for (final JsonElement n : after.getAsJsonArray("nodes")) if (n.getAsJsonObject()
                .get("id")
                .getAsString()
                .equals(moved.id.toString())) {
                    n.getAsJsonObject()
                        .addProperty("x", moved.x);
                    n.getAsJsonObject()
                        .addProperty("y", moved.y);
                }
            assertEquals(Canon.of(before), Canon.of(after), name + ": " + firstDifference(before, after));
            assertEquals(
                List.of(),
                CloudPlans.diff(
                    edited,
                    CloudPlans.write(r.graph(), game, edited, r.basis())
                        .plan()),
                name);
        }
    }

    /** A card's count pinned on the website lands on the card, which keeps its id and what only the game has. */
    @Test
    void aCountSetOnTheWebsiteLandsOnTheCard() {
        for (final Object[] b : boards()) {
            final String name = (String) b[0];
            final Graph g = (Graph) b[1];
            final TestGame game = (TestGame) b[2];
            final JsonObject plan = CloudPlans.write(g, game, null, null)
                .plan();
            final Node first = g.getNodes()
                .iterator()
                .next();
            final JsonObject f = new JsonObject();
            f.addProperty("machineCount", 7);
            f.addProperty("solvePin", 7);
            final JsonObject edited = LiveOps.apply(plan, List.of(LiveOp.patch("nodes", first.id.toString(), f, null)));
            final CloudPlans.Read r = CloudPlans.read(edited, game);
            final Node card = r.graph().nodes.get(first.id);
            assertNotNull(card, name + ": the card keeps its id");
            assertEquals(7, card.machineConfig.getMachineCount(), name);
            assertTrue(card.isMachineCountFixed(), name);
            assertEquals(first.x, card.x, name);
            assertEquals(first.y, card.y, name);
            assertEquals(1, card.worldLinks.size(), name + ": its place in the world is the game's own");
            assertEquals(first.targetInputRates, card.targetInputRates, name);
            assertEquals(150, card.machineConfig.settings.get("tick_modifier"), name + ": a setting only the game has");
            assertEquals(g.nodes.size(), r.graph().nodes.size(), name);
            assertEquals(g.edges.keySet(), r.graph().edges.keySet(), name + ": its wires found its ports again");
            // Written back unchanged, the website's edit stays as the website made it.
            assertEquals(
                List.of(),
                CloudPlans.diff(
                    edited,
                    CloudPlans.write(r.graph(), game, edited, r.basis())
                        .plan()),
                name);
        }
    }

    /**
     * A card the website made comes in under its own id and goes back under it; a change made in the game changes only
     * the fields it touched, and the card then carries the game's data for it.
     */
    @Test
    void aWebsiteCardKeepsItsId() {
        for (final String name : Boards.WEBSITE) {
            final JsonObject plan = Boards.website(name);
            final TestGame game = new TestGame();
            final CloudPlans.Read r = CloudPlans.read(plan, game);
            final List<String> ids = new ArrayList<>();
            for (final JsonElement n : plan.getAsJsonArray("nodes")) ids.add(
                n.getAsJsonObject()
                    .get("id")
                    .getAsString());
            Node card = null;
            String cardId = null;
            for (final String id : ids) {
                card = r.graph().nodes.get(PlanIds.uuid(id));
                cardId = id;
                if (card != null) break;
            }
            assertNotNull(card, name + ": a card came in under its own id");
            card.machineConfig.setMachineCount(card.machineConfig.getMachineCount() + 3);
            card.setMachineCountFixed(true);
            final CloudPlans.Written w = CloudPlans.write(r.graph(), game, plan, r.basis());
            final List<LiveOp> ops = CloudPlans.diff(plan, w.plan());
            assertEquals(1, ops.size(), name + ": " + ops);
            final LiveOp op = ops.getFirst();
            assertEquals(LiveOp.Kind.PATCH, op.o(), name);
            assertEquals(cardId, op.id(), name + ": written back under the website's id");
            final List<String> fields = new ArrayList<>();
            for (final Map.Entry<String, JsonElement> e : op.f()
                .entrySet()) fields.add(e.getKey());
            fields.sort(String::compareTo);
            assertTrue(
                List.of("gtnhplanner", "machineCount", "solvePin")
                    .containsAll(fields) && fields.contains("gtnhplanner")
                    && fields.contains("solvePin"),
                name + ": " + op);
            // Read again, the card is the game's own: its sum matches.
            final CloudPlans.Read again = CloudPlans.read(w.plan(), game.knowing(r.graph()));
            final Node back = again.graph().nodes.get(card.id);
            assertEquals(card.machineConfig.getMachineCount(), back.machineConfig.getMachineCount(), name);
            assertEquals(
                Canon.of(Fresh.nodeGame(card, "")),
                Canon.of(Fresh.nodeGame(back, "")),
                name + ": rebuilt from its game data");
        }
    }

    /** Tasks go out as the plan's collections and come back, and a task the website adds is on the board. */
    @Test
    void tasksMakeTheTrip() {
        final Graph g = Boards.dress(Boards.corpus("benzene"));
        final TestGame game = new TestGame().knowing(g);
        final JsonObject plan = CloudPlans.write(g, game, null, null)
            .plan();
        assertEquals(
            g.getPrimaryView(),
            plan.get("primaryView")
                .getAsString());
        assertEquals(
            2,
            plan.getAsJsonArray("tasks")
                .size());
        final CloudPlans.Read r = CloudPlans.read(plan, game);
        assertEquals(g.tasks, r.graph().tasks);
        assertEquals(g.taskItems, r.graph().taskItems);
        assertEquals(g.taskComments, r.graph().taskComments);
        assertEquals(
            g.getPrimaryView(),
            r.graph()
                .getPrimaryView());

        // The website adds a task and ticks a checklist line; the game sees both, then moves the task to Done.
        final JsonObject task = new JsonObject();
        task.addProperty("id", "t2");
        task.addProperty("title", "Wire it");
        task.addProperty("status", "todo");
        task.addProperty("order", "a1");
        task.addProperty("future", "a field the game does not know");
        final JsonObject tick = new JsonObject();
        tick.addProperty("done", true);
        final JsonObject edited = LiveOps
            .apply(plan, List.of(LiveOp.put("tasks", "t2", task), LiveOp.patch("taskItems", "i1", tick, null)));
        final CloudPlans.Read seen = CloudPlans.read(edited, game);
        assertEquals(3, seen.graph().tasks.size());
        assertTrue(
            seen.graph().taskItems.getFirst()
                .done());
        final Graph board = seen.graph();
        for (int i = 0; i < board.tasks.size(); i++) {
            final var t = board.tasks.get(i);
            if (t.id()
                .equals("t2"))
                board.tasks.set(
                    i,
                    new com.gtnhplanner.data.flowchart.Tasks.Task(
                        t.id(),
                        t.title(),
                        t.text(),
                        com.gtnhplanner.data.flowchart.Tasks.Status.DONE,
                        t.order(),
                        t.assignees(),
                        t.links(),
                        t.createdBy(),
                        t.createdAt(),
                        "2026-10-14T12:00:00Z"));
        }
        final List<LiveOp> ops = CloudPlans.diff(
            edited,
            CloudPlans.write(board, game, edited, seen.basis())
                .plan());
        assertEquals(1, ops.size(), ops.toString());
        assertEquals(
            "{\"o\":\"patch\",\"c\":\"tasks\",\"id\":\"t2\",\"f\":{\"status\":\"done\",\"doneAt\":\"2026-10-14T12:00:00Z\"}}",
            ops.getFirst()
                .toString(),
            "the field the game does not know is kept");
    }

    /** Where two saves first differ, for a failure's message. */
    static String firstDifference(final JsonElement a, final JsonElement b) {
        return firstDifference("", a, b);
    }

    private static String firstDifference(final String path, final JsonElement a, final JsonElement b) {
        if (LiveJson.equal(a, b)) return "";
        if (a != null && b != null && a.isJsonObject() && b.isJsonObject()) {
            final java.util.Set<String> keys = new java.util.TreeSet<>();
            for (final Map.Entry<String, JsonElement> e : a.getAsJsonObject()
                .entrySet()) keys.add(e.getKey());
            for (final Map.Entry<String, JsonElement> e : b.getAsJsonObject()
                .entrySet()) keys.add(e.getKey());
            for (final String k : keys) {
                final String d = firstDifference(
                    path + "/" + k,
                    a.getAsJsonObject()
                        .get(k),
                    b.getAsJsonObject()
                        .get(k));
                if (!d.isEmpty()) return d;
            }
        }
        if (a != null && b != null
            && a.isJsonArray()
            && b.isJsonArray()
            && a.getAsJsonArray()
                .size()
                == b.getAsJsonArray()
                    .size()) {
            for (int i = 0; i < a.getAsJsonArray()
                .size(); i++) {
                final String d = firstDifference(
                    path + "[" + i + "]",
                    a.getAsJsonArray()
                        .get(i),
                    b.getAsJsonArray()
                        .get(i));
                if (!d.isEmpty()) return d;
            }
        }
        return path + ": " + abbreviate(a) + " / " + abbreviate(b);
    }

    private static String abbreviate(final JsonElement e) {
        final String s = String.valueOf(e);
        return s.length() > 400 ? s.substring(0, 400) + "..." : s;
    }
}
