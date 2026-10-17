package com.gtnhplanner.data.flowchart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** A plan's task board through a save, a plan code and an older save that has none. */
class TasksTest {

    static Graph board() {
        final Graph g = new Graph("Board");
        g.setPrimaryView(Tasks.TASKS);
        g.tasks.add(
            new Tasks.Task(
                "t1",
                "Build the line",
                "Two LV mixers first.",
                Tasks.Status.DOING,
                "a0",
                List.of("11111111-1111-4111-8111-111111111111"),
                List.of(new Tasks.Link("u1:plan-a", "n1"), new Tasks.Link("local-b", "n2")),
                "11111111-1111-4111-8111-111111111111",
                "2026-10-14T10:00:00.000Z",
                null));
        g.tasks.add(
            new Tasks.Task(
                "t2",
                "Done thing",
                null,
                Tasks.Status.DONE,
                "a1",
                List.of(),
                List.of(),
                null,
                null,
                "2026-10-14T11:00:00.000Z"));
        g.taskItems.add(new Tasks.Item("i1", "t1", "Place mixers", true, "a0", new Tasks.Link("u1:plan-a", "n1")));
        g.taskItems.add(new Tasks.Item("i2", "t1", "Wire them", false, "a1", null));
        g.taskComments.add(
            new Tasks.Comment(
                "c1",
                "t1",
                "11111111-1111-4111-8111-111111111111",
                "ann",
                "2026-10-14T10:05:00.000Z",
                "On it"));
        return g;
    }

    @Test
    void aTaskBoardGoesThroughASaveAndAPlanCode() {
        final Graph g = board();
        final Graph back = Serializer.decode(Serializer.encode(g));
        assertEquals(Tasks.TASKS, back.getPrimaryView());
        assertEquals(g.tasks, back.tasks);
        assertEquals(g.taskItems, back.taskItems);
        assertEquals(g.taskComments, back.taskComments);
    }

    @Test
    void priorityAndTheMoveStampAreCarriedAndOldTasksAreMedium() {
        final Graph g = new Graph("P");
        g.tasks.add(
            new Tasks.Task(
                "t",
                "T",
                null,
                Tasks.Status.DOING,
                "a0",
                List.of(),
                List.of(),
                null,
                "2026-10-01T00:00:00.000Z",
                null,
                Tasks.HIGH,
                "2026-10-05T00:00:00.000Z"));
        final Graph back = Serializer.decode(Serializer.encode(g));
        assertEquals(g.tasks, back.tasks);
        assertEquals(
            Tasks.HIGH,
            back.tasks.get(0)
                .priority());
        assertEquals(
            "2026-10-05T00:00:00.000Z",
            back.tasks.get(0)
                .sinceColumn());

        final Tasks.Task old = Tasks.task(
            new JsonParser().parse(
                "{\"id\":\"a\",\"title\":\"A\",\"status\":\"todo\",\"order\":\"a0\",\"createdAt\":\"2026-10-01T00:00:00.000Z\"}")
                .getAsJsonObject());
        assertEquals(Tasks.MEDIUM, old.shownPriority());
        assertEquals("2026-10-01T00:00:00.000Z", old.sinceColumn());
        assertEquals(
            null,
            TaskEdits.withPriority(old, Tasks.MEDIUM)
                .priority());
        assertEquals(
            -1,
            Tasks.toJson(old)
                .toString()
                .indexOf("priority"));
    }

    @Test
    void theBoardIsWrittenAsThePlanSpellsIt() {
        final JsonObject root = new JsonObject();
        Tasks.write(board(), root);
        assertEquals(
            "{\"id\":\"t2\",\"title\":\"Done thing\",\"status\":\"done\",\"order\":\"a1\",\"doneAt\":\"2026-10-14T11:00:00.000Z\"}",
            root.getAsJsonArray("tasks")
                .get(1)
                .toString());
        assertEquals(
            "{\"id\":\"i2\",\"taskId\":\"t1\",\"text\":\"Wire them\",\"done\":false,\"order\":\"a1\"}",
            root.getAsJsonArray("taskItems")
                .get(1)
                .toString());
        assertEquals(
            "tasks",
            root.get("primaryView")
                .getAsString());
    }

    @Test
    void aLinkNamesAPlanAndItsCardAndOlderSavesNamedOnlyTheCard() {
        final Graph g = board();
        final JsonObject root = new JsonObject();
        Tasks.write(g, root);
        final JsonObject first = root.getAsJsonArray("tasks")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            "[{\"plan\":\"u1:plan-a\",\"node\":\"n1\"},{\"plan\":\"local-b\",\"node\":\"n2\"}]",
            first.getAsJsonArray("links")
                .toString());
        assertEquals(
            "{\"plan\":\"u1:plan-a\",\"node\":\"n1\"}",
            root.getAsJsonArray("taskItems")
                .get(0)
                .getAsJsonObject()
                .get("link")
                .toString());

        // Older: nodeIds and nodeId, the cards of the plan holding the task.
        final Graph old = new Graph("Old");
        Tasks.read(
            new JsonParser().parse(
                "{\"tasks\":[{\"id\":\"a\",\"title\":\"A\",\"status\":\"todo\",\"order\":\"a0\",\"nodeIds\":[\"x\",\"y\"]}],"
                    + "\"taskItems\":[{\"id\":\"i\",\"taskId\":\"a\",\"text\":\"T\",\"done\":false,\"order\":\"a0\",\"nodeId\":\"x\"}]}")
                .getAsJsonObject(),
            old);
        assertEquals(
            List.of(new Tasks.Link("", "x"), new Tasks.Link("", "y")),
            old.tasks.get(0)
                .links());
        assertEquals(
            new Tasks.Link("", "x"),
            old.taskItems.get(0)
                .link());
        // Saved again, they name the plan holding them.
        final JsonObject again = new JsonObject();
        Tasks.write(old, again);
        final String plan = old.planId();
        assertEquals(
            plan,
            again.getAsJsonArray("tasks")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("links")
                .get(1)
                .getAsJsonObject()
                .get("plan")
                .getAsString());
    }

    @Test
    void aPlanKeepsItsLocalIdThroughASaveAndIsFoundByEitherId() {
        final Graph g = new Graph("Mine");
        final String id = g.planId();
        final com.gtnhplanner.data.flowchart.Plan plan = com.gtnhplanner.data.flowchart.Plan.cloud("u1");
        plan.getGraphs()
            .add(g);
        final com.gtnhplanner.data.flowchart.Plan back = Serializer.decodePlan(Serializer.encodePlan(plan));
        assertEquals(
            id,
            back.getGraphs()
                .get(0)
                .getLocalId());
        // Gone to the account, it is its cloud key; the id it had still finds it.
        g.setCloudKey("u1:abc");
        assertEquals("u1:abc", g.planId());
        assertTrue(g.isPlan("u1:abc") && g.isPlan(id) && !g.isPlan("") && !g.isPlan("other"));
    }

    @Test
    void anOlderSaveLoadsWithNoBoard() {
        final Graph plain = Serializer.decode(Serializer.encode(new Graph("Plain")));
        assertNull(plain.getPrimaryView());
        assertTrue(plain.tasks.isEmpty() && plain.taskItems.isEmpty() && plain.taskComments.isEmpty());

        // A task the game cannot read (no title, an unknown status) is skipped, not the plan.
        final Graph g = new Graph("x");
        Tasks.read(
            new JsonParser()
                .parse(
                    "{\"primaryView\":\"sideways\",\"tasks\":[{\"id\":\"a\",\"status\":\"todo\",\"order\":\"a0\"},"
                        + "{\"id\":\"b\",\"title\":\"B\",\"status\":\"later\",\"order\":\"a1\"},"
                        + "{\"id\":\"c\",\"title\":\"C\",\"status\":\"blocked\",\"order\":\"a2\"}]}")
                .getAsJsonObject(),
            g);
        assertNull(g.getPrimaryView());
        assertEquals(1, g.tasks.size());
        assertEquals(
            Tasks.Status.BLOCKED,
            g.tasks.get(0)
                .status());
    }

    @Test
    void blockedByIsCarriedAndOldTasksWaitOnNothing() {
        final Graph g = new Graph("B");
        TaskEdits.add(g, "a", "A", Tasks.Status.TODO, List.of(), null, null);
        TaskEdits.add(g, "b", "B", Tasks.Status.TODO, List.of(), null, null);
        assertTrue(TaskEdits.addBlocker(g, "b", "a"));
        final Graph back = Serializer.decode(Serializer.encode(g));
        assertEquals(g.tasks, back.tasks);
        assertEquals(
            List.of("a"),
            TaskEdits.task(back, "b")
                .blockedBy());

        final Tasks.Task old = Tasks.task(
            new JsonParser().parse("{\"id\":\"a\",\"title\":\"A\",\"status\":\"todo\",\"order\":\"a0\"}")
                .getAsJsonObject());
        assertTrue(
            old.blockedBy()
                .isEmpty());
        assertEquals(
            -1,
            Tasks.toJson(old)
                .toString()
                .indexOf("blockedBy"));
        // Itself and repeats are dropped as it reads.
        final Tasks.Task odd = Tasks.task(
            new JsonParser().parse(
                "{\"id\":\"a\",\"title\":\"A\",\"status\":\"todo\",\"order\":\"a0\",\"blockedBy\":[\"a\",\"b\",\"b\"]}")
                .getAsJsonObject());
        assertEquals(List.of("b"), odd.blockedBy());
    }
}
