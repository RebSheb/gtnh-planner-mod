package com.gtnhplanner.data.flowchart;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A task's things: machines, places and screenshots, each with its own checkbox, as the plan spells them; an older
 * save's checklist lines read as things; how far and which way a spot is.
 */
class TaskThingsTest {

    private static final String AT = "2026-10-15T09:00:00.000Z";
    private static final Tasks.Place SPOT = new Tasks.Place(120, 64, -30, 0, "Overworld", "world:abc", "Ore field");
    private static final Tasks.Picture SHOT = new Tasks.Picture(
        "data:image/jpeg;base64,/9j/AA==",
        1920,
        1080,
        AT,
        "task_2026-10-15_09.00.00.png");

    private static final Tasks.Quest QUEST = new Tasks.Quest(
        "0f2a43a5-6c1e-4d7a-9b3b-6a8f3b0e2c11",
        "Steam Age",
        "item:IC2:itemToolBronzePickaxe:0",
        "Tier 1 - Steam");

    private static Graph board() {
        final Graph g = new Graph("Board");
        g.setPrimaryView(Tasks.TASKS);
        TaskEdits.add(g, "t", "Build the line", Tasks.Status.DOING, List.of(), null, AT);
        TaskEdits.addThing(
            g,
            "m",
            "t",
            Tasks.MACHINE,
            "4× Electric Blast Furnace (HV)",
            new Tasks.Link("u1:plan", "n1"),
            null,
            null);
        TaskEdits.addThing(g, "l", "t", Tasks.LOCATION, TaskEdits.placeText(SPOT), null, SPOT, null);
        TaskEdits.addThing(g, "p", "t", Tasks.PICTURE, "Screenshot", null, null, SHOT);
        TaskEdits.addQuest(g, "q", "t", QUEST);
        return g;
    }

    @Test
    void aPlanCodeKeepsTheScreenshotThingButNotItsPicture() {
        final Graph g = board();
        final Graph shared = Serializer.decode(Serializer.encodeShared(g));
        final Tasks.Item p = TaskEdits.firstPicture(shared, "t");
        assertEquals(
            "",
            p.picture()
                .thumb());
        assertEquals(
            1920,
            p.picture()
                .w());
        assertEquals(
            SHOT.file(),
            p.picture()
                .file());
        assertEquals("Screenshot", p.text());
        assertEquals(g.taskItems.size(), shared.taskItems.size());
        // A save and the account's copy still carry it.
        assertEquals(
            SHOT.thumb(),
            TaskEdits.firstPicture(Serializer.decode(Serializer.encode(g)), "t")
                .picture()
                .thumb());
    }

    @Test
    void everyKindOfThingGoesThroughASaveAndAPlanCode() {
        final Graph g = board();
        final Graph back = Serializer.decode(Serializer.encode(g));
        assertEquals(g.taskItems, back.taskItems);
        assertEquals(
            List.of(Tasks.MACHINE, Tasks.LOCATION, Tasks.PICTURE, Tasks.QUEST),
            TaskEdits.items(back, "t")
                .stream()
                .map(Tasks.Item::shownKind)
                .toList());
    }

    @Test
    void thingsAreWrittenAsThePlanSpellsThemWithTheirWords() {
        final JsonObject root = new JsonObject();
        Tasks.write(board(), root);
        final JsonObject spot = root.getAsJsonArray("taskItems")
            .get(1)
            .getAsJsonObject();
        assertEquals(
            "{\"id\":\"l\",\"taskId\":\"t\",\"kind\":\"location\",\"text\":\"Ore field at 120 64 -30 in Overworld\","
                + "\"done\":false,\"order\":\""
                + spot.get("order")
                    .getAsString()
                + "\",\"place\":{\"x\":120,\"y\":64,\"z\":-30,\"dim\":0,\"dimName\":\"Overworld\",\"world\":\"world:abc\","
                + "\"label\":\"Ore field\"}}",
            spot.toString());
        final JsonObject shot = root.getAsJsonArray("taskItems")
            .get(2)
            .getAsJsonObject()
            .getAsJsonObject("picture");
        assertEquals(
            "{\"thumb\":\"data:image/jpeg;base64,/9j/AA==\",\"w\":1920,\"h\":1080,\"takenAt\":\"" + AT
                + "\",\"file\":\"task_2026-10-15_09.00.00.png\"}",
            shot.toString());
    }

    @Test
    void anOlderChecklistLineIsAThingAndKeepsItsWords() {
        final Graph g = new Graph("Old");
        Tasks.read(
            new JsonParser().parse(
                "{\"tasks\":[{\"id\":\"a\",\"title\":\"A\",\"status\":\"todo\",\"order\":\"a0\",\"nodeIds\":[\"x\",\"y\"]}],"
                    + "\"taskItems\":[{\"id\":\"i\",\"taskId\":\"a\",\"text\":\"2× Mixer\",\"done\":true,\"order\":\"a0\",\"nodeId\":\"x\"},"
                    + "{\"id\":\"j\",\"taskId\":\"a\",\"text\":\"Wire them\",\"done\":false,\"order\":\"a1\"}]}")
                .getAsJsonObject(),
            g);
        final Tasks.Item linked = TaskEdits.item(g, "i"), words = TaskEdits.item(g, "j");
        assertEquals(Tasks.MACHINE, linked.shownKind());
        assertEquals("", words.shownKind());
        assertEquals("2× Mixer", linked.text());
        assertTrue(linked.done());
        // Written back as they were: no kind, so a reader that knows only lines reads them the same.
        final JsonObject root = new JsonObject();
        Tasks.write(g, root);
        assertNull(
            root.getAsJsonArray("taskItems")
                .get(1)
                .getAsJsonObject()
                .get("kind"));
        // The task's card y has no thing of its own: it shows as a loose card, so it is not lost.
        assertEquals(List.of(new Tasks.Link("", "y")), TaskEdits.looseLinks(g, "a"));
    }

    @Test
    void aKindWithoutWhatItNeedsShowsAsWords() {
        final Tasks.Item spotless = new Tasks.Item(
            "x",
            "t",
            Tasks.LOCATION,
            "Somewhere",
            false,
            "a0",
            null,
            null,
            null,
            null);
        assertEquals("", spotless.shownKind());
        final JsonObject bad = new JsonParser().parse("{\"x\":1.5,\"y\":64,\"z\":3,\"dim\":0}")
            .getAsJsonObject();
        assertNull(Tasks.place(bad));
        assertNull(Tasks.picture(new JsonObject()));
    }

    @Test
    void thingsCountTowardTheTaskAndTheCardsTheyName() {
        final Graph g = board();
        assertArrayEquals(new int[] { 0, 4 }, TaskEdits.progress(g, "t"));
        TaskEdits.putItem(g, TaskEdits.withDone(TaskEdits.item(g, "l"), true));
        assertArrayEquals(new int[] { 1, 4 }, TaskEdits.progress(g, "t"));
        assertEquals(
            "p",
            TaskEdits.firstPicture(g, "t")
                .id());
        // The machine's card shows the task's dot.
        final Map<Tasks.Link, Tasks.Status> dots = new HashMap<>();
        TaskEdits.statusByLink(g, dots);
        assertEquals(Tasks.Status.DOING, dots.get(new Tasks.Link("u1:plan", "n1")));
    }

    @Test
    void takingAMachineOffTakesItsLinkToo() {
        final Graph g = new Graph("Board");
        final Tasks.Link card = new Tasks.Link("p", "n");
        TaskEdits.add(g, "t", "T", Tasks.Status.TODO, List.of(card), null, AT);
        TaskEdits.addThing(g, "m", "t", Tasks.MACHINE, "Mixer", card, null, null);
        assertTrue(
            TaskEdits.looseLinks(g, "t")
                .isEmpty());
        TaskEdits.removeThing(g, "m");
        assertTrue(
            TaskEdits.task(g, "t")
                .links()
                .isEmpty());
        assertTrue(
            TaskEdits.items(g, "t")
                .isEmpty());
    }

    @Test
    void aSpotIsNamedAndItsWordsFollow() {
        final Graph g = board();
        final Tasks.Item named = TaskEdits.withLabel(TaskEdits.item(g, "l"), "  Mine entrance ");
        assertEquals(
            "Mine entrance",
            named.place()
                .label());
        assertEquals("Mine entrance at 120 64 -30 in Overworld", named.text());
        final Tasks.Item unnamed = TaskEdits.withLabel(named, " ");
        assertNull(
            unnamed.place()
                .label());
        assertEquals("120 64 -30 in Overworld", unnamed.text());
    }

    @Test
    void howFarAndWhichWayASpotIs() {
        // North is toward -z, east toward +x, as the game's compass has it.
        assertEquals("north", TaskEdits.compass(0, -10));
        assertEquals("south", TaskEdits.compass(0, 10));
        assertEquals("east", TaskEdits.compass(10, 0));
        assertEquals("west", TaskEdits.compass(-10, 0));
        assertEquals("north-east", TaskEdits.compass(10, -10));
        assertEquals("south-west", TaskEdits.compass(-7, 7));
        assertEquals("", TaskEdits.compass(0.2, -0.3));
        assertEquals("40 m north-east", TaskEdits.howFar(28.3, -28.3));
        assertEquals("12 km south", TaskEdits.howFar(0, 12_345));
        assertEquals("here", TaskEdits.howFar(0, 0.4));
    }

    @Test
    void aQuestIsWrittenUnderItsThingWithItsNameAsItsWords() {
        final JsonObject root = new JsonObject();
        Tasks.write(board(), root);
        final JsonObject quest = root.getAsJsonArray("taskItems")
            .get(3)
            .getAsJsonObject();
        assertEquals(
            "quest",
            quest.get("kind")
                .getAsString());
        assertEquals(
            "Steam Age",
            quest.get("text")
                .getAsString());
        assertEquals(
            "{\"id\":\"0f2a43a5-6c1e-4d7a-9b3b-6a8f3b0e2c11\",\"name\":\"Steam Age\","
                + "\"icon\":\"item:IC2:itemToolBronzePickaxe:0\",\"line\":\"Tier 1 - Steam\"}",
            quest.getAsJsonObject("quest")
                .toString());
        // A plan code carries it whole too: it is a few words, not a picture.
        final Graph shared = Serializer.decode(Serializer.encodeShared(board()));
        assertEquals(
            QUEST,
            TaskEdits.questThing(shared, "t", QUEST.id())
                .quest());
    }

    @Test
    void aQuestGoesOnATaskOnce() {
        final Graph g = board();
        assertNull(TaskEdits.addQuest(g, "q2", "t", QUEST));
        assertNull(TaskEdits.addQuest(g, "q3", "gone", QUEST));
        assertEquals(
            4,
            TaskEdits.items(g, "t")
                .size());
        final Tasks.Quest unnamed = new Tasks.Quest("11111111-2222-4333-8444-555555555555", "", null, null);
        assertEquals(
            "A quest",
            TaskEdits.addQuest(g, "q4", "t", unnamed)
                .text());
    }

    @Test
    void aQuestThingWithoutItsQuestShowsAsWords() {
        final Graph g = new Graph("Old");
        Tasks.read(
            new JsonParser().parse(
                "{\"tasks\":[{\"id\":\"a\",\"title\":\"A\",\"status\":\"todo\",\"order\":\"a0\"}],"
                    + "\"taskItems\":[{\"id\":\"i\",\"taskId\":\"a\",\"kind\":\"quest\",\"text\":\"Steam Age\",\"done\":false,\"order\":\"a0\"},"
                    + "{\"id\":\"j\",\"taskId\":\"a\",\"kind\":\"quest\",\"text\":\"No id\",\"done\":false,\"order\":\"a1\",\"quest\":{\"name\":\"x\"}}]}")
                .getAsJsonObject(),
            g);
        assertEquals(
            "",
            TaskEdits.item(g, "i")
                .shownKind());
        assertEquals(
            "",
            TaskEdits.item(g, "j")
                .shownKind());
        // Written back with its kind, as it came.
        final JsonObject root = new JsonObject();
        Tasks.write(g, root);
        assertEquals(
            "quest",
            root.getAsJsonArray("taskItems")
                .get(0)
                .getAsJsonObject()
                .get("kind")
                .getAsString());
    }

    @Test
    void doneQuestsTickTheirThingsOnceAndLeaveWhatThePlayerSet() {
        final Graph g = board();
        final java.util.Set<String> left = new java.util.HashSet<>();
        // Not done in the book: nothing to tick.
        assertTrue(
            TaskEdits.questsToTick(g, id -> false, left)
                .isEmpty());
        // Done: the quest thing alone is ticked, not the machine, the spot or the screenshot.
        assertEquals(
            List.of("q"),
            TaskEdits.questsToTick(g, id -> id.equals(QUEST.id()), left)
                .stream()
                .map(Tasks.Item::id)
                .toList());
        // Unticked by hand after: left alone.
        left.add("q");
        assertTrue(
            TaskEdits.questsToTick(g, id -> true, left)
                .isEmpty());
        // Already ticked: nothing to do.
        left.clear();
        TaskEdits.putItem(g, TaskEdits.withDone(TaskEdits.item(g, "q"), true));
        assertTrue(
            TaskEdits.questsToTick(g, id -> true, left)
                .isEmpty());
        // Every change to a thing keeps its quest.
        final Tasks.Item q = TaskEdits.item(g, "q");
        assertEquals(
            QUEST,
            TaskEdits.withDone(q, false)
                .quest());
        assertEquals(
            QUEST,
            TaskEdits.withText(q, "x")
                .quest());
        assertEquals(
            QUEST,
            Tasks.withoutThumb(q)
                .quest());
        assertEquals(
            QUEST,
            Tasks.claimed(q, "plan")
                .quest());
    }
}
