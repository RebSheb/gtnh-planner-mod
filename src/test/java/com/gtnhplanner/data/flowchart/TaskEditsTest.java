package com.gtnhplanner.data.flowchart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The task board's edits: columns in order, moves that write one key, checklists, and the cards' status dots. */
class TaskEditsTest {

    private static final String AT = "2026-10-14T12:00:00.000Z";

    private static List<String> ids(final List<Tasks.Task> tasks) {
        return tasks.stream()
            .map(Tasks.Task::id)
            .toList();
    }

    @Test
    void tasksAddToTheBottomAndMoveBetweenTheirNeighbours() {
        final Graph g = new Graph("Board");
        TaskEdits.add(g, "a", "A", Tasks.Status.TODO, List.of(), null, AT);
        TaskEdits.add(g, "b", "B", Tasks.Status.TODO, List.of(), null, AT);
        TaskEdits.add(g, "c", "C", Tasks.Status.TODO, List.of(), null, AT);
        assertEquals(List.of("a", "b", "c"), ids(TaskEdits.column(g, Tasks.Status.TODO)));

        // c to the top: only c changes.
        final String bOrder = TaskEdits.task(g, "b")
            .order();
        TaskEdits.move(g, "c", Tasks.Status.TODO, 0, AT);
        assertEquals(List.of("c", "a", "b"), ids(TaskEdits.column(g, Tasks.Status.TODO)));
        assertEquals(
            bOrder,
            TaskEdits.task(g, "b")
                .order());

        // a to done, then back to doing: done stamps when, leaving clears it.
        TaskEdits.move(g, "a", Tasks.Status.DONE, 0, AT);
        assertEquals(
            AT,
            TaskEdits.task(g, "a")
                .doneAt());
        assertEquals(List.of("c", "b"), ids(TaskEdits.column(g, Tasks.Status.TODO)));
        TaskEdits.move(g, "a", Tasks.Status.DOING, 5, AT);
        assertNull(
            TaskEdits.task(g, "a")
                .doneAt());
        assertEquals(List.of("a"), ids(TaskEdits.column(g, Tasks.Status.DOING)));
    }

    @Test
    void aMoveToAnotherColumnStampsWhenAndOneWithinItDoesNot() {
        final Graph g = new Graph("B");
        TaskEdits.add(g, "a", "A", Tasks.Status.TODO, List.of(), null, "2026-10-01T00:00:00.000Z");
        TaskEdits.add(g, "b", "B", Tasks.Status.TODO, List.of(), null, "2026-10-01T00:00:00.000Z");
        TaskEdits.move(g, "a", Tasks.Status.TODO, 1, "2026-10-09T00:00:00.000Z");
        org.junit.jupiter.api.Assertions.assertEquals(
            "2026-10-01T00:00:00.000Z",
            TaskEdits.task(g, "a")
                .movedAt());
        TaskEdits.move(g, "a", Tasks.Status.DOING, 0, "2026-10-10T00:00:00.000Z");
        org.junit.jupiter.api.Assertions.assertEquals(
            "2026-10-10T00:00:00.000Z",
            TaskEdits.task(g, "a")
                .movedAt());
    }

    @Test
    void aColumnWhoseKeysClashIsNumberedAfresh() {
        final Graph g = new Graph("Board");
        for (final String id : List.of("x", "y", "z"))
            g.tasks.add(new Tasks.Task(id, id, null, Tasks.Status.TODO, "a1", List.of(), List.of(), null, null, null));
        // Between x and y, both "a1": the column is numbered again with z in its place.
        TaskEdits.move(g, "z", Tasks.Status.TODO, 1, AT);
        assertEquals(List.of("x", "z", "y"), ids(TaskEdits.column(g, Tasks.Status.TODO)));
        for (final Tasks.Task t : g.tasks) assertTrue(OrderKeys.valid(t.order()), t.order());

        // A column of keys from somewhere else (none at all) takes new ones too.
        final Graph h = new Graph("Old");
        h.tasks.add(new Tasks.Task("p", "P", null, Tasks.Status.DOING, "", List.of(), List.of(), null, null, null));
        TaskEdits.add(h, "q", "Q", Tasks.Status.DOING, List.of(), null, AT);
        assertEquals(List.of("p", "q"), ids(TaskEdits.column(h, Tasks.Status.DOING)));
    }

    private static Tasks.Link link(final String node) {
        return new Tasks.Link("u:plan", node);
    }

    @Test
    void checklistsAndCommentsGoWithTheirTask() {
        final Graph g = new Graph("Board");
        TaskEdits.add(g, "t", "T", Tasks.Status.TODO, List.of(link("n1")), "me", AT);
        TaskEdits.addItem(g, "i1", "t", "One", null);
        TaskEdits.addItem(g, "i2", "t", "Two", link("n1"));
        TaskEdits.addItem(g, "i3", "t", "Three", null);
        TaskEdits.moveItem(g, "i3", 0);
        assertEquals(
            List.of("i3", "i1", "i2"),
            TaskEdits.items(g, "t")
                .stream()
                .map(Tasks.Item::id)
                .toList());
        TaskEdits.putItem(g, TaskEdits.withDone(TaskEdits.item(g, "i1"), true));
        assertTrue(
            TaskEdits.item(g, "i1")
                .done());
        TaskEdits.addComment(g, "c2", "t", "me", "Me", "2026-10-14T12:05:00.000Z", "later");
        TaskEdits.addComment(g, "c1", "t", "me", "Me", AT, "first");
        assertEquals(
            List.of("c1", "c2"),
            TaskEdits.comments(g, "t")
                .stream()
                .map(Tasks.Comment::id)
                .toList());

        // Through a save and back, as undo takes it.
        final Graph back = Serializer.decode(Serializer.encode(g));
        assertEquals(g.taskItems, back.taskItems);
        assertEquals(g.taskComments, back.taskComments);

        TaskEdits.remove(g, "t");
        assertTrue(g.tasks.isEmpty() && g.taskItems.isEmpty() && g.taskComments.isEmpty());
    }

    @Test
    void aCardShowsItsMostPressingTask() {
        final Graph g = new Graph("Board");
        TaskEdits.add(g, "a", "A", Tasks.Status.DONE, List.of(link("n1"), link("n2")), null, AT);
        TaskEdits.add(g, "b", "B", Tasks.Status.BLOCKED, List.of(link("n2")), null, AT);
        TaskEdits.add(g, "c", "C", Tasks.Status.DOING, List.of(link("n1")), null, AT);
        // A link with no plan is a card of the board's own plan.
        TaskEdits.add(g, "d", "D", Tasks.Status.TODO, List.of(new Tasks.Link("", "n3")), null, AT);
        final Map<Tasks.Link, Tasks.Status> dots = new java.util.HashMap<>();
        TaskEdits.statusByLink(g, dots);
        assertEquals(
            Map.of(
                link("n1"),
                Tasks.Status.DOING,
                link("n2"),
                Tasks.Status.BLOCKED,
                new Tasks.Link(g.planId(), "n3"),
                Tasks.Status.TODO),
            dots);
    }

    @Test
    void textKeepsToThePlansLimits() {
        assertEquals("abc", TaskEdits.clip("  abc  ", 10));
        assertEquals(
            TaskEdits.TITLE_MAX,
            TaskEdits.clip("x".repeat(500), TaskEdits.TITLE_MAX)
                .length());
        final Tasks.Task t = new Tasks.Task(
            "t",
            "T",
            "words",
            Tasks.Status.TODO,
            "a0",
            List.of(),
            List.of(),
            null,
            null,
            null);
        assertNull(
            TaskEdits.withText(t, "   ")
                .text());
    }

    @Test
    void aTaskWaitsOnOthersButNeverInALoop() {
        final Graph g = new Graph("B");
        for (final String id : List.of("a", "b", "c", "d"))
            TaskEdits.add(g, id, id, Tasks.Status.TODO, List.of(), null, AT);
        assertTrue(TaskEdits.addBlocker(g, "b", "a"));
        assertTrue(TaskEdits.addBlocker(g, "c", "b"));
        // Itself, twice, directly back, and back down the chain are all refused.
        assertTrue(!TaskEdits.addBlocker(g, "a", "a"));
        assertTrue(!TaskEdits.addBlocker(g, "b", "a"));
        assertTrue(!TaskEdits.addBlocker(g, "a", "b"));
        assertTrue(!TaskEdits.addBlocker(g, "a", "c"));
        assertTrue(!TaskEdits.addBlocker(g, "a", "gone"));
        assertTrue(TaskEdits.addBlocker(g, "c", "d"));
        assertEquals(List.of("b", "d"), ids(TaskEdits.blockers(g, "c")));
        assertEquals(List.of("b"), ids(TaskEdits.blocking(g, "a")));

        // Ready once everything it waits on is done.
        assertTrue(!TaskEdits.ready(g, TaskEdits.task(g, "b")));
        TaskEdits.move(g, "a", Tasks.Status.DONE, 0, AT);
        assertTrue(TaskEdits.ready(g, TaskEdits.task(g, "b")));
        assertEquals(List.of("b", "d"), ids(TaskEdits.openBlockers(g, "c")));

        // A blocker deleted leaves the lists of those that waited on it; one taken off goes alone.
        TaskEdits.remove(g, "b");
        assertEquals(
            List.of("d"),
            TaskEdits.task(g, "c")
                .blockedBy());
        TaskEdits.removeBlocker(g, "c", "d");
        assertTrue(
            TaskEdits.task(g, "c")
                .blockedBy()
                .isEmpty());
    }

    @Test
    void chainsAreLaidOutInLayersWithTheUnlinkedAtTheEnd() {
        final Graph g = new Graph("B");
        for (final String id : List.of("ore", "dust", "ingot", "plate", "wire", "motor", "lone", "x", "y", "coal"))
            TaskEdits.add(g, id, id, Tasks.Status.TODO, List.of(), null, AT);
        // ore -> dust -> ingot -> plate -> motor, and ingot -> wire -> motor: two branches meeting.
        TaskEdits.addBlocker(g, "dust", "ore");
        TaskEdits.addBlocker(g, "ingot", "dust");
        TaskEdits.addBlocker(g, "plate", "ingot");
        TaskEdits.addBlocker(g, "wire", "ingot");
        TaskEdits.addBlocker(g, "motor", "plate");
        TaskEdits.addBlocker(g, "motor", "wire");
        TaskEdits.addBlocker(g, "y", "x");
        // Coal waits on nothing and is needed only by the ingots: it sits just before them, beside the dust.
        TaskEdits.addBlocker(g, "ingot", "coal");
        final TaskChain.Layout l = TaskChain.of(g);
        assertEquals(
            2,
            l.chains()
                .size());
        assertEquals(
            List.of(
                List.of("ore"),
                List.of("dust", "coal"),
                List.of("ingot"),
                List.of("plate", "wire"),
                List.of("motor")),
            l.chains()
                .get(0)
                .layers());
        assertEquals(
            List.of(List.of("x"), List.of("y")),
            l.chains()
                .get(1)
                .layers());
        assertEquals(List.of("lone"), l.loose());
        assertEquals(
            10,
            l.ids()
                .size());

        // A loop two people closed at once still lays out, each task once.
        g.tasks.set(0, TaskEdits.withBlockedBy(g.tasks.get(0), List.of("motor")));
        final TaskChain.Layout looped = TaskChain.of(g);
        assertEquals(
            10,
            looped.ids()
                .size());
        assertEquals(10, new java.util.HashSet<>(looped.ids()).size());
    }

    @Test
    void theColumnsRunBlockedToDoDoingDone() {
        assertEquals(
            List.of(Tasks.Status.BLOCKED, Tasks.Status.TODO, Tasks.Status.DOING, Tasks.Status.DONE),
            List.of(Tasks.Status.values()));
    }
}
