package com.gtnhplanner.dev;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.TaskEdits;
import com.gtnhplanner.data.flowchart.Tasks;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.card.CardModel;

/**
 * A task board as a team's would look a few weeks into a build, for footage ({@code call 'tasks?demo=1'} with a plan
 * open): four people, every column in use, priorities, things to build linked to the open plan's cards, blockers,
 * comments, and tasks that have sat a while. Made as a new task board next to the plan, and opened.
 */
public final class DevTaskBoard {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneOffset.UTC);

    private static final String[][] PEOPLE = { { "fake-ada", "Ada" }, { "fake-bob", "Bob" },
        { "fake-cyrus", "Cyrus_Ironworks" }, { "fake-dee", "Dee" } };

    private DevTaskBoard() {}

    private static String ago(final double days) {
        return ISO.format(
            Instant.now()
                .minus(Duration.ofMinutes(Math.round(days * 24 * 60))));
    }

    private static String id(final String who) {
        for (final String[] p : PEOPLE) if (p[1].equals(who)) return p[0];
        return who;
    }

    /** The plan's cards by a word of their machine's name, as task links, with how many machines each runs. */
    private record Card(Tasks.Link link, String line) {}

    public static Graph fill(final BoardSession session) {
        final Graph plan = session.graph();
        final Map<String, Card> cards = new HashMap<>();
        for (final Node n : plan.getNodes()) {
            final CardModel m = session.model(n.id);
            if (m == null || m.machineName == null) continue;
            final String name = m.machineName;
            final String key = name.toLowerCase(Locale.ROOT);
            final long count = Math.max(1, Math.round(m.machines));
            final String line = count + "× " + name + (m.tier == null || m.tier.isEmpty() ? "" : " (" + m.tier + ")");
            for (final String word : new String[] { "electrolyzer", "distillery", "chemical", "tank", "turbine" })
                if (key.contains(word)) cards.putIfAbsent(word, new Card(session.linkOf(n.id), line));
        }
        final Graph board = session.addTaskBoard(true);
        board.setName("Refinery build");
        final Map<String, String> ids = new HashMap<>();

        // Done
        task(
            board,
            ids,
            "boilers",
            "Set up the steam boilers",
            Tasks.Status.DONE,
            "Ada",
            Tasks.MEDIUM,
            9,
            12,
            null,
            List.of(thing("4× Large Bronze Boiler", null, true), thing("Water pump on the lake", null, true)));
        task(
            board,
            ids,
            "oil",
            "Tap the oil field by spawn",
            Tasks.Status.DONE,
            "Bob",
            Tasks.HIGH,
            6,
            11,
            null,
            List.of(thing("2× Pump (MV)", null, true), thing("Fluid pipes to base", null, true)));
        task(
            board,
            ids,
            "tanks",
            "Craft the fluid tanks",
            Tasks.Status.DONE,
            "Cyrus_Ironworks",
            Tasks.LOW,
            4,
            6,
            null,
            List.of(machine(cards, "tank", true)));
        task(
            board,
            ids,
            "electro",
            "LV electrolyzer line",
            Tasks.Status.DONE,
            "Dee",
            Tasks.MEDIUM,
            2,
            7,
            null,
            List.of(machine(cards, "electrolyzer", true)));

        // Doing
        task(
            board,
            ids,
            "distill",
            "Distillery row",
            Tasks.Status.DOING,
            "Cyrus_Ironworks",
            Tasks.HIGH,
            1,
            5,
            null,
            List.of(
                machine(cards, "distillery", true),
                thing("Output hatches", null, false),
                thing("Pipes to the reactors", null, false)));
        task(
            board,
            ids,
            "more-electro",
            "Two more electrolyzers",
            Tasks.Status.DOING,
            "Dee",
            Tasks.HIGH,
            0.1,
            0.2,
            null,
            List.of(machine(cards, "electrolyzer", false)));
        task(
            board,
            ids,
            "fuel",
            "Route light fuel to the turbines",
            Tasks.Status.DOING,
            "Ada",
            Tasks.MEDIUM,
            3,
            4,
            null,
            List.of(thing("Light fuel tank", null, true), thing("Pipes along the east wall", null, false)));
        task(
            board,
            ids,
            "h2s",
            "Hydrogen sulfide cleanup",
            Tasks.Status.DOING,
            "Bob",
            Tasks.LOW,
            9,
            10,
            null,
            List.of(thing("Void excess in a trash can", null, false)));

        // To do
        task(
            board,
            ids,
            "reactors",
            "Chemical reactor bank",
            Tasks.Status.TODO,
            "Bob",
            Tasks.MEDIUM,
            2,
            3,
            null,
            List.of(machine(cards, "chemical", false), thing("Cleanroom casing", null, false)));
        task(
            board,
            ids,
            "turbines",
            "Steam turbine power room",
            Tasks.Status.TODO,
            "Ada",
            Tasks.MEDIUM,
            3,
            3,
            List.of("fuel"),
            List.of(machine(cards, "turbine", false)));
        task(
            board,
            ids,
            "pe",
            "Polyethylene storage",
            Tasks.Status.TODO,
            null,
            Tasks.LOW,
            1,
            1,
            null,
            List.of(thing("Super Tank for the output", null, false)));
        task(
            board,
            ids,
            "me",
            "Hook the line into ME",
            Tasks.Status.TODO,
            "Cyrus_Ironworks",
            Tasks.LOW,
            5,
            5,
            null,
            List.of(thing("ME interface by the reactors", null, false), thing("Fluid storage bus", null, false)));
        task(board, ids, "labels", "Label every pipe", Tasks.Status.TODO, null, Tasks.LOW, 12, 12, null, List.of());

        // Blocked
        task(
            board,
            ids,
            "mv",
            "MV upgrade for the reactors",
            Tasks.Status.BLOCKED,
            "Bob",
            Tasks.HIGH,
            8,
            8,
            List.of("distill"),
            List.of(thing("4× MV machine hulls", null, false)));
        task(
            board,
            ids,
            "o2",
            "Oxygen buffer tank",
            Tasks.Status.BLOCKED,
            "Dee",
            Tasks.MEDIUM,
            15,
            16,
            List.of("more-electro"),
            List.of(thing("Steel tank walls", null, false)));

        comment(board, ids.get("distill"), "Cyrus_Ironworks", 1.2, "Two down, one to go. Out of steel plates.");
        comment(board, ids.get("distill"), "Ada", 1.0, "I'll send you a stack tonight.");
        comment(board, ids.get("more-electro"), "Bob", 0.12, "The note on the plan says why. We're short on hydrogen.");
        comment(board, ids.get("more-electro"), "Dee", 0.1, "On it. Adding them now.");
        comment(board, ids.get("mv"), "Bob", 7.5, "Waiting on the distilleries so the room is free.");
        comment(board, ids.get("o2"), "Dee", 14, "Parked until the new electrolyzers are in.");
        comment(board, ids.get("fuel"), "Ada", 2.5, "Halfway along the east wall.");
        comment(board, ids.get("h2s"), "Bob", 8, "Does anyone need the sulfur, or do we void it?");
        board.touchLayout();
        com.gtnhplanner.api.PlanAPI.save();
        return board;
    }

    private record Thing(String text, Tasks.Link link, boolean done) {}

    private static Thing thing(final String text, final Tasks.Link link, final boolean done) {
        return new Thing(text, link, done);
    }

    private static Thing machine(final Map<String, Card> cards, final String word, final boolean done) {
        final Card c = cards.get(word);
        return c == null ? thing("1× " + word, null, done) : thing(c.line(), c.link(), done);
    }

    private static void task(final Graph g, final Map<String, String> ids, final String key, final String title,
        final Tasks.Status status, final String who, final String priority, final double movedDays,
        final double madeDays, final List<String> waitsOn, final List<Thing> things) {
        final String id = UUID.randomUUID()
            .toString();
        ids.put(key, id);
        final List<Tasks.Link> links = new ArrayList<>();
        for (final Thing t : things) if (t.link() != null) links.add(t.link());
        final String by = who == null ? id("Ada") : id(who);
        final Tasks.Task made = TaskEdits.add(g, id, title, status, links, by, ago(madeDays));
        final List<String> blocked = new ArrayList<>();
        if (waitsOn != null) for (final String k : waitsOn) if (ids.containsKey(k)) blocked.add(ids.get(k));
        TaskEdits.put(
            g,
            new Tasks.Task(
                id,
                title,
                null,
                status,
                made.order(),
                who == null ? List.of() : List.of(id(who)),
                links,
                by,
                ago(madeDays),
                status == Tasks.Status.DONE ? ago(movedDays) : null,
                priority,
                ago(movedDays),
                blocked));
        for (final Thing t : things) {
            final Tasks.Item item = TaskEdits.addThing(
                g,
                UUID.randomUUID()
                    .toString(),
                id,
                t.link() != null ? Tasks.MACHINE : "",
                t.text(),
                t.link(),
                null,
                null);
            if (t.done()) TaskEdits.putItem(g, TaskEdits.withDone(item, true));
        }
    }

    private static void comment(final Graph g, final String taskId, final String who, final double days,
        final String text) {
        if (taskId == null) return;
        TaskEdits.addComment(
            g,
            UUID.randomUUID()
                .toString(),
            taskId,
            id(who),
            who,
            ago(days),
            text);
    }
}
