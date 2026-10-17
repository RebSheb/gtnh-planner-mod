package com.gtnhplanner.cloud.map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Tasks;

/**
 * What a shared plan links by id: a task's cards (the plan's ids in the plan, the board's on the board) and a card's
 * places in the world (kept by world: each game shows its own world's and carries the rest).
 */
class CloudLinksTest {

    private static JsonObject json(final String text) {
        return new JsonParser().parse(text)
            .getAsJsonObject();
    }

    /** The first card of a website plan whose id is the website's own (not a UUID) and that comes onto the board. */
    private static String websiteCard(final JsonObject plan, final Graph board) {
        for (final JsonElement e : plan.getAsJsonArray("nodes")) {
            final String id = e.getAsJsonObject()
                .get("id")
                .getAsString();
            if (board.nodes.containsKey(PlanIds.uuid(id)) && !PlanIds.uuid(id)
                .toString()
                .equals(id)) return id;
        }
        throw new AssertionError("no website card on the board");
    }

    @Test
    void aWebsiteTaskLinksACardOfAnyPlanAsThePlanSpellsIt() {
        for (final String name : List.of("biodiesel-demo", "pa-cell-loop-plan")) {
            final JsonObject plan = Boards.website(name);
            final TestGame game = new TestGame();
            final String card = websiteCard(
                plan,
                CloudPlans.read(plan, game)
                    .graph());
            final JsonArray tasks = new JsonArray(), items = new JsonArray();
            tasks.add(
                json(
                    "{\"id\":\"t1\",\"title\":\"Build it\",\"status\":\"todo\",\"order\":\"a0\",\"links\":["
                        + "{\"plan\":\"u1:p1\",\"node\":\""
                        + card
                        + "\"},{\"plan\":\"u1:p2\",\"node\":\"node-gone\"}]}"));
            items.add(
                json(
                    "{\"id\":\"i1\",\"taskId\":\"t1\",\"text\":\"Place it\",\"done\":false,\"order\":\"a0\","
                        + "\"link\":{\"plan\":\"u1:p1\",\"node\":\""
                        + card
                        + "\"}}"));
            plan.add("tasks", tasks);
            plan.add("taskItems", items);

            final CloudPlans.Read r = CloudPlans.read(plan, game);
            final Tasks.Task t = r.graph().tasks.getFirst();
            assertEquals(
                List.of(new Tasks.Link("u1:p1", card), new Tasks.Link("u1:p2", "node-gone")),
                t.links(),
                name + ": the plan and the card as the plan spells them");
            assertEquals(
                new Tasks.Link("u1:p1", card),
                r.graph().taskItems.getFirst()
                    .link(),
                name);
            // Written straight back, the plan is as it was.
            assertEquals(
                List.of(),
                CloudPlans.diff(
                    plan,
                    CloudPlans.write(r.graph(), game, plan, r.basis())
                        .plan()),
                name + ": round trip");
        }
    }

    @Test
    void anOlderTaskNamesItsCardsOfItsOwnPlanAndReadsAsLinks() {
        final JsonObject plan = Boards.website("biodiesel-demo");
        final TestGame game = new TestGame();
        final String card = websiteCard(
            plan,
            CloudPlans.read(plan, game)
                .graph());
        final JsonArray tasks = new JsonArray(), items = new JsonArray();
        tasks.add(
            json(
                "{\"id\":\"t1\",\"title\":\"Build it\",\"status\":\"todo\",\"order\":\"a0\",\"nodeIds\":[\"" + card
                    + "\"]}"));
        items.add(
            json(
                "{\"id\":\"i1\",\"taskId\":\"t1\",\"text\":\"Place it\",\"done\":false,\"order\":\"a0\","
                    + "\"nodeId\":\""
                    + card
                    + "\"}"));
        plan.add("tasks", tasks);
        plan.add("taskItems", items);
        final Graph g = CloudPlans.read(plan, game)
            .graph();
        // No plan named: the plan holding the task.
        assertEquals(
            List.of(new Tasks.Link("", card)),
            g.tasks.getFirst()
                .links());
        assertEquals(
            new Tasks.Link("", card),
            g.taskItems.getFirst()
                .link());
        // Written, the plan names itself.
        g.setCloudKey("u1:me");
        final JsonObject written = CloudPlans.write(g, game, null, null)
            .plan();
        assertEquals(
            "u1:me",
            written.getAsJsonArray("tasks")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("links")
                .get(0)
                .getAsJsonObject()
                .get("plan")
                .getAsString());
    }

    @Test
    void aGameCardsTaskLinksRoundTripExactly() {
        final Graph g = Boards.dress(Boards.corpus("platline"));
        final TestGame game = new TestGame().knowing(g);
        final JsonObject plan = CloudPlans.write(g, game, null, null)
            .plan();
        final Tasks.Link first = g.tasks.getFirst()
            .links()
            .getFirst();
        final CloudPlans.Read r = CloudPlans.read(plan, game);
        assertEquals(
            first,
            r.graph().tasks.getFirst()
                .links()
                .getFirst(),
            "a game card's id is its UUID in both");
        assertEquals(
            List.of(),
            CloudPlans.diff(
                plan,
                CloudPlans.write(r.graph(), game, plan, r.basis())
                    .plan()));
    }

    @Test
    void placementsAreKeptByWorld() {
        final Graph g = Boards.corpus("sulfuric-acid");
        final Node card = g.getNodes()
            .iterator()
            .next();
        card.worldLinks.add(new int[] { 0, 1, 64, 2, 1 });
        final TestGame inA = new TestGame().knowing(g), inB = new TestGame().knowing(g);
        inA.world = "world:a";
        inB.world = "server:b";
        final JsonObject plan = CloudPlans.write(g, inA, null, null)
            .plan();

        // In another world the card shows none of the first world's places, and keeps them.
        final CloudPlans.Read b = CloudPlans.read(plan, inB);
        final Node there = b.graph().nodes.get(card.id);
        assertTrue(there.worldLinks.isEmpty(), "another world's places are not this one's");
        assertArrayEquals(
            new int[] { 0, 1, 64, 2, 1 },
            there.elsewhere.get("world:a")
                .getFirst());
        assertEquals(
            List.of(),
            CloudPlans.diff(
                plan,
                CloudPlans.write(b.graph(), inB, plan, b.basis())
                    .plan()),
            "read in another world and written back, nothing changed");

        // Placed there too: both worlds' places in the plan.
        there.worldLinks.add(new int[] { -1, 5, 70, 5, 0 });
        final JsonObject both = CloudPlans.write(b.graph(), inB, plan, b.basis())
            .plan();
        final Node back = CloudPlans.read(both, inA)
            .graph().nodes.get(card.id);
        assertEquals(1, back.worldLinks.size(), "back in the first world: its own place only");
        assertArrayEquals(new int[] { 0, 1, 64, 2, 1 }, back.worldLinks.getFirst());
        assertArrayEquals(
            new int[] { -1, 5, 70, 5, 0 },
            back.elsewhere.get("server:b")
                .getFirst());
    }
}
