package com.gtnhplanner.data.flowchart;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * A plan's task board (docs/design/cloud-live.md, "Plan document additions"): tasks, their things (machines, places and
 * screenshots, each with a checkbox) and their comments, each its own thing so two people can change two of them at
 * once. Any plan can hold tasks; one whose
 * {@link Graph#getPrimaryView() primary view} is {@link #TASKS} opens on them. Saved and written exactly as the shared
 * plan spells them, so a plan's tasks are the same JSON in a save, a plan code and a cloud plan.
 */
public final class Tasks {

    /** The primary views: the board, or the task board. */
    public static final String PLAN = "plan", TASKS = "tasks";

    /** A task's priorities. A task with none is Medium. */
    public static final String HIGH = "high", MEDIUM = "medium", LOW = "low";

    /** The priority a plan spells: high or low; null for medium, none or one it does not know (all Medium). */
    @Nullable
    public static String priorityOf(@Nullable final String p) {
        return HIGH.equals(p) || LOW.equals(p) ? p : null;
    }

    /** A task's column, in the board's order left to right: Blocked, To do, Doing, Done. */
    public enum Status {

        BLOCKED("blocked"),
        TODO("todo"),
        DOING("doing"),
        DONE("done");

        public final String wire;

        Status(final String wire) {
            this.wire = wire;
        }

        /** The status a plan spells so; null for one it does not know. */
        @Nullable
        public static Status of(@Nullable final String wire) {
            for (final Status s : values()) if (s.wire.equals(wire)) return s;
            return null;
        }
    }

    /**
     * A task. {@code order} is a fractional index: tasks sort by it inside a column, and a move writes one new key
     * between two others.
     *
     * @param assignees account ids of the plan's members working on it
     * @param links     the cards it is about, each in the plan it is on (a task board is a plan of its own, so its
     *                  cards are on other plans)
     * @param blockedBy the tasks of the same board it waits on, by id, each once and never itself ("Blocked by"; a
     *                  description that stays whatever column it is in)
     */
    public record Task(String id, String title, @Nullable String text, Status status, String order,
        List<String> assignees, List<Link> links, @Nullable String createdBy, @Nullable String createdAt,
        @Nullable String doneAt, @Nullable String priority, @Nullable String movedAt, List<String> blockedBy) {

        public Task {
            assignees = List.copyOf(assignees);
            links = List.copyOf(links);
            priority = priorityOf(priority);
            final List<String> waits = new ArrayList<>();
            for (final String b : blockedBy == null ? List.<String>of() : blockedBy)
                if (b != null && !b.equals(id) && !waits.contains(b)) waits.add(b);
            blockedBy = List.copyOf(waits);
        }

        /** A task waiting on nothing. */
        public Task(final String id, final String title, @Nullable final String text, final Status status,
            final String order, final List<String> assignees, final List<Link> links, @Nullable final String createdBy,
            @Nullable final String createdAt, @Nullable final String doneAt, @Nullable final String priority,
            @Nullable final String movedAt) {
            this(
                id,
                title,
                text,
                status,
                order,
                assignees,
                links,
                createdBy,
                createdAt,
                doneAt,
                priority,
                movedAt,
                List.of());
        }

        /** A task with no priority (Medium), no move stamp and nothing it waits on, as an older save spelled it. */
        public Task(final String id, final String title, @Nullable final String text, final Status status,
            final String order, final List<String> assignees, final List<Link> links, @Nullable final String createdBy,
            @Nullable final String createdAt, @Nullable final String doneAt) {
            this(id, title, text, status, order, assignees, links, createdBy, createdAt, doneAt, null, null);
        }

        /** The priority it shows as: {@link #HIGH}, {@link #MEDIUM} or {@link #LOW}. */
        public String shownPriority() {
            return priority == null ? MEDIUM : priority;
        }

        /** When it last changed column: the move stamp, else when it was made, else null. */
        @Nullable
        public String sinceColumn() {
            return movedAt != null ? movedAt : createdAt;
        }
    }

    /**
     * A card on a plan: {@code plan} is the plan's id (its cloud doc key, or the local id of a plan on this computer,
     * {@link Graph#planId()}) and {@code node} the card's id in that plan (the plan's own spelling of it, which
     * {@code cloud/map/PlanIds.uuid} turns into the game's). An empty {@code plan} is the plan holding the task: how an
     * older save spelled it ({@code nodeIds}, {@code nodeId}).
     */
    public record Link(String plan, String node) {}

    /**
     * A thing's kinds: a card on a plan, a spot in a world, a screenshot, a quest in the quest book (game only, as
     * the screenshot is). An older save's lines have none.
     */
    public static final String MACHINE = "machine", LOCATION = "location", PICTURE = "picture", QUEST = "quest";

    /**
     * One thing on a task, each with its own checkbox: a {@link #MACHINE} (a card on a plan, {@code link}), a
     * {@link #LOCATION} (a spot in a world, {@code place}), a {@link #PICTURE} (a screenshot's thumbnail) or a
     * {@link #QUEST} (a quest in the quest book, {@code quest}). An older save's checklist line has no kind: its
     * words, and a card when it pointed at one. {@code text} is always written, the thing in words (what a reader that
     * knows only lines shows), so nothing is lost either way.
     */
    public record Item(String id, String taskId, String kind, String text, boolean done, String order,
        @Nullable Link link, @Nullable Place place, @Nullable Picture picture, @Nullable Quest quest) {

        public Item {
            kind = kind == null ? "" : kind;
        }

        /** A line of words, as an older save spelled a checklist line. */
        public Item(final String id, final String taskId, final String text, final boolean done, final String order,
            @Nullable final Link link) {
            this(id, taskId, "", text, done, order, link, null, null, null);
        }

        /**
         * What it shows as: its kind, or for an older line a machine when it points at a card and words when not
         * ({@code ""}).
         */
        public String shownKind() {
            if (MACHINE.equals(kind) && link != null || LOCATION.equals(kind) && place != null
                || PICTURE.equals(kind) && picture != null
                || QUEST.equals(kind) && quest != null) return kind;
            return link != null ? MACHINE : "";
        }
    }

    /**
     * A spot in a world: a block's {@code x y z} in dimension {@code dim} (named {@code dimName} as the game names it),
     * in the world {@code world} (as placed machines key them: {@code server:<hash>} or {@code world:<uuid>}), with an
     * optional label.
     */
    public record Place(int x, int y, int z, int dim, String dimName, String world, @Nullable String label) {}

    /**
     * A screenshot: {@code thumb} a small JPEG as a data URL, the full shot {@code w} by {@code h}, when it was taken,
     * and the full shot's file name in the screenshots folder of the game that took it (only there).
     */
    public record Picture(String thumb, int w, int h, String takenAt, @Nullable String file) {}

    /**
     * A quest in the game's quest book: {@code id} the quest's id there (BetterQuesting's UUID, the same in every
     * world of a pack), with what reads where the book is missing: its {@code name}, its {@code icon} (an item key,
     * {@code item:<mod>:<name>:<meta>}) and the name of the quest {@code line} it is in.
     */
    public record Quest(String id, String name, @Nullable String icon, @Nullable String line) {}

    /** A comment on a task; {@code at} is the server's time stamp. */
    public record Comment(String id, String taskId, String by, String byName, String at, String text) {}

    private Tasks() {}

    // region JSON, as the plan spells it

    public static JsonObject toJson(final Task t) {
        final JsonObject o = new JsonObject();
        o.addProperty("id", t.id());
        o.addProperty("title", t.title());
        if (t.text() != null) o.addProperty("text", t.text());
        o.addProperty("status", t.status().wire);
        o.addProperty("order", t.order());
        if (!t.assignees()
            .isEmpty()) o.add("assignees", strings(t.assignees()));
        if (!t.links()
            .isEmpty()) {
            final JsonArray a = new JsonArray();
            for (final Link l : t.links()) a.add(toJson(l));
            o.add("links", a);
        }
        if (t.createdBy() != null) o.addProperty("createdBy", t.createdBy());
        if (t.createdAt() != null) o.addProperty("createdAt", t.createdAt());
        if (t.doneAt() != null) o.addProperty("doneAt", t.doneAt());
        if (t.priority() != null) o.addProperty("priority", t.priority());
        if (t.movedAt() != null) o.addProperty("movedAt", t.movedAt());
        if (!t.blockedBy()
            .isEmpty()) o.add("blockedBy", strings(t.blockedBy()));
        return o;
    }

    public static JsonObject toJson(final Item i) {
        final JsonObject o = new JsonObject();
        o.addProperty("id", i.id());
        o.addProperty("taskId", i.taskId());
        if (!i.kind()
            .isEmpty()) o.addProperty("kind", i.kind());
        o.addProperty("text", i.text());
        o.addProperty("done", i.done());
        o.addProperty("order", i.order());
        if (i.link() != null) o.add("link", toJson(i.link()));
        if (i.place() != null) o.add("place", toJson(i.place()));
        if (i.picture() != null) o.add("picture", toJson(i.picture()));
        if (i.quest() != null) o.add("quest", toJson(i.quest()));
        return o;
    }

    public static JsonObject toJson(final Place p) {
        final JsonObject o = new JsonObject();
        o.addProperty("x", p.x());
        o.addProperty("y", p.y());
        o.addProperty("z", p.z());
        o.addProperty("dim", p.dim());
        o.addProperty("dimName", p.dimName());
        o.addProperty("world", p.world());
        if (p.label() != null) o.addProperty("label", p.label());
        return o;
    }

    public static JsonObject toJson(final Picture p) {
        final JsonObject o = new JsonObject();
        o.addProperty("thumb", p.thumb());
        o.addProperty("w", p.w());
        o.addProperty("h", p.h());
        o.addProperty("takenAt", p.takenAt());
        if (p.file() != null) o.addProperty("file", p.file());
        return o;
    }

    public static JsonObject toJson(final Quest q) {
        final JsonObject o = new JsonObject();
        o.addProperty("id", q.id());
        o.addProperty("name", q.name());
        if (q.icon() != null) o.addProperty("icon", q.icon());
        if (q.line() != null) o.addProperty("line", q.line());
        return o;
    }

    /** A quest as a plan spells it; null when it has no id. */
    @Nullable
    public static Quest quest(final JsonObject o) {
        final String id = str(o, "id");
        if (id == null || id.isEmpty()) return null;
        return new Quest(id, orEmpty(str(o, "name")), str(o, "icon"), str(o, "line"));
    }

    /** A spot as a plan spells it; null when it lacks its block or its dimension. */
    @Nullable
    public static Place place(final JsonObject o) {
        final Integer x = integer(o, "x"), y = integer(o, "y"), z = integer(o, "z"), dim = integer(o, "dim");
        if (x == null || y == null || z == null || dim == null) return null;
        final String label = str(o, "label");
        return new Place(
            x,
            y,
            z,
            dim,
            orEmpty(str(o, "dimName")),
            orEmpty(str(o, "world")),
            label == null || label.isBlank() ? null : label);
    }

    /** A screenshot as a plan spells it; null when it has no picture. */
    @Nullable
    public static Picture picture(final JsonObject o) {
        final String thumb = str(o, "thumb");
        if (thumb == null) return null;
        final Integer w = integer(o, "w"), h = integer(o, "h");
        return new Picture(thumb, w == null ? 0 : w, h == null ? 0 : h, orEmpty(str(o, "takenAt")), str(o, "file"));
    }

    public static JsonObject toJson(final Link l) {
        final JsonObject o = new JsonObject();
        o.addProperty("plan", l.plan());
        o.addProperty("node", l.node());
        return o;
    }

    /** A link as a plan spells it; null when it lacks its card. */
    @Nullable
    public static Link link(final JsonObject o) {
        final String node = str(o, "node");
        return node == null ? null : new Link(orEmpty(str(o, "plan")), node);
    }

    /** The task with the links that left their plan empty naming {@code plan}, the plan holding the task. */
    public static Task claimed(final Task t, final String plan) {
        boolean any = false;
        for (final Link l : t.links()) any |= l.plan()
            .isEmpty();
        if (!any) return t;
        final List<Link> links = new ArrayList<>();
        for (final Link l : t.links()) links.add(
            l.plan()
                .isEmpty() ? new Link(plan, l.node()) : l);
        return new Task(
            t.id(),
            t.title(),
            t.text(),
            t.status(),
            t.order(),
            t.assignees(),
            links,
            t.createdBy(),
            t.createdAt(),
            t.doneAt(),
            t.priority(),
            t.movedAt(),
            t.blockedBy());
    }

    /**
     * The thing as a shared plan holds it: a screenshot whose picture stayed behind (it came in a plan code or a chat
     * link) goes without its picture, as a plan takes no screenshot without one; it keeps its kind and words.
     */
    public static Item forPlan(final Item i) {
        final Picture p = i.picture();
        if (p == null || !p.thumb()
            .isEmpty()) return i;
        return new Item(
            i.id(),
            i.taskId(),
            i.kind(),
            i.text(),
            i.done(),
            i.order(),
            i.link(),
            i.place(),
            null,
            i.quest());
    }

    public static Item claimed(final Item i, final String plan) {
        final Link l = i.link();
        if (l == null || !l.plan()
            .isEmpty()) return i;
        return new Item(
            i.id(),
            i.taskId(),
            i.kind(),
            i.text(),
            i.done(),
            i.order(),
            new Link(plan, l.node()),
            i.place(),
            i.picture(),
            i.quest());
    }

    public static JsonObject toJson(final Comment c) {
        final JsonObject o = new JsonObject();
        o.addProperty("id", c.id());
        o.addProperty("taskId", c.taskId());
        o.addProperty("by", c.by());
        o.addProperty("byName", c.byName());
        o.addProperty("at", c.at());
        o.addProperty("text", c.text());
        return o;
    }

    /** A task as a plan spells it; null when it lacks what every task has (an id, a title, a known status). */
    @Nullable
    public static Task task(final JsonObject o) {
        final String id = str(o, "id"), title = str(o, "title");
        final Status status = Status.of(str(o, "status"));
        if (id == null || title == null || status == null) return null;
        return new Task(
            id,
            title,
            str(o, "text"),
            status,
            orEmpty(str(o, "order")),
            strings(o, "assignees"),
            links(o),
            str(o, "createdBy"),
            str(o, "createdAt"),
            str(o, "doneAt"),
            str(o, "priority"),
            str(o, "movedAt"),
            strings(o, "blockedBy"));
    }

    @Nullable
    public static Item item(final JsonObject o) {
        final String id = str(o, "id"), taskId = str(o, "taskId");
        if (id == null || taskId == null) return null;
        final JsonElement done = o.get("done");
        final JsonElement place = o.get("place"), picture = o.get("picture"), quest = o.get("quest");
        return new Item(
            id,
            taskId,
            orEmpty(str(o, "kind")),
            orEmpty(str(o, "text")),
            done != null && done.isJsonPrimitive()
                && done.getAsJsonPrimitive()
                    .isBoolean()
                && done.getAsBoolean(),
            orEmpty(str(o, "order")),
            itemLink(o),
            place != null && place.isJsonObject() ? place(place.getAsJsonObject()) : null,
            picture != null && picture.isJsonObject() ? picture(picture.getAsJsonObject()) : null,
            quest != null && quest.isJsonObject() ? quest(quest.getAsJsonObject()) : null);
    }

    /** A task's links; the older {@code nodeIds} (cards of the plan holding the task) read as links with no plan. */
    private static List<Link> links(final JsonObject o) {
        final List<Link> out = new ArrayList<>();
        for (final JsonObject l : objects(o, "links")) {
            final Link link = link(l);
            if (link != null && !out.contains(link)) out.add(link);
        }
        for (final String node : strings(o, "nodeIds")) {
            final Link link = new Link("", node);
            if (!out.contains(link)) out.add(link);
        }
        return out;
    }

    @Nullable
    private static Link itemLink(final JsonObject o) {
        final JsonElement e = o.get("link");
        if (e != null && e.isJsonObject()) {
            final Link l = link(e.getAsJsonObject());
            if (l != null) return l;
        }
        final String node = str(o, "nodeId");
        return node == null ? null : new Link("", node);
    }

    @Nullable
    public static Comment comment(final JsonObject o) {
        final String id = str(o, "id"), taskId = str(o, "taskId");
        if (id == null || taskId == null) return null;
        return new Comment(
            id,
            taskId,
            orEmpty(str(o, "by")),
            orEmpty(str(o, "byName")),
            orEmpty(str(o, "at")),
            orEmpty(str(o, "text")));
    }

    // endregion

    // region On a graph

    /** Writes the graph's task board into {@code root} as the plan spells it; nothing for a plan without one. */
    public static void write(final Graph g, final JsonObject root) {
        write(g, root, true);
    }

    /**
     * {@link #write(Graph, JsonObject)}, with the screenshots' thumbnails left empty when {@code pictures} is false
     * (a plan code or a chat link: the things stay, the pictures do not travel).
     */
    public static void write(final Graph g, final JsonObject root, final boolean pictures) {
        if (g.getPrimaryView() != null) root.addProperty("primaryView", g.getPrimaryView());
        if (!g.tasks.isEmpty()) {
            final JsonArray a = new JsonArray();
            for (final Task t : g.tasks) a.add(toJson(claimed(t, g.planId())));
            root.add("tasks", a);
        }
        if (!g.taskItems.isEmpty()) {
            final JsonArray a = new JsonArray();
            for (final Item i : g.taskItems) {
                final Item c = claimed(i, g.planId());
                a.add(toJson(pictures ? c : withoutThumb(c)));
            }
            root.add("taskItems", a);
        }
        if (!g.taskComments.isEmpty()) {
            final JsonArray a = new JsonArray();
            for (final Comment c : g.taskComments) a.add(toJson(c));
            root.add("taskComments", a);
        }
    }

    /** The thing with its screenshot's thumbnail emptied (a picture thing keeps its place, size and file name). */
    public static Item withoutThumb(final Item i) {
        final Picture p = i.picture();
        if (p == null || p.thumb()
            .isEmpty()) return i;
        return new Item(
            i.id(),
            i.taskId(),
            i.kind(),
            i.text(),
            i.done(),
            i.order(),
            i.link(),
            i.place(),
            new Picture("", p.w(), p.h(), p.takenAt(), p.file()),
            i.quest());
    }

    /** Reads a task board {@link #write} wrote (or a plan holds) onto the graph; what does not read is skipped. */
    public static void read(final JsonObject root, final Graph g) {
        final String view = str(root, "primaryView");
        g.setPrimaryView(PLAN.equals(view) || TASKS.equals(view) ? view : null);
        for (final JsonObject o : objects(root, "tasks")) {
            final Task t = task(o);
            if (t != null) g.tasks.add(t);
        }
        for (final JsonObject o : objects(root, "taskItems")) {
            final Item i = item(o);
            if (i != null) g.taskItems.add(i);
        }
        for (final JsonObject o : objects(root, "taskComments")) {
            final Comment c = comment(o);
            if (c != null) g.taskComments.add(c);
        }
    }

    // endregion

    private static List<JsonObject> objects(final JsonObject o, final String key) {
        final List<JsonObject> out = new ArrayList<>();
        final JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray()) return out;
        for (final JsonElement x : e.getAsJsonArray()) if (x.isJsonObject()) out.add(x.getAsJsonObject());
        return out;
    }

    @Nullable
    private static String str(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive()
            && e.getAsJsonPrimitive()
                .isString() ? e.getAsString() : null;
    }

    /** A whole number; null when it is not one. */
    @Nullable
    private static Integer integer(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()
            || !e.getAsJsonPrimitive()
                .isNumber())
            return null;
        final double d = e.getAsDouble();
        return d == Math.rint(d) && Math.abs(d) <= Integer.MAX_VALUE ? (int) d : null;
    }

    private static String orEmpty(@Nullable final String s) {
        return s == null ? "" : s;
    }

    private static List<String> strings(final JsonObject o, final String key) {
        final List<String> out = new ArrayList<>();
        final JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray()) return out;
        for (final JsonElement x : e.getAsJsonArray()) if (x.isJsonPrimitive()) out.add(x.getAsString());
        return out;
    }

    private static JsonArray strings(final List<String> values) {
        final JsonArray a = new JsonArray();
        for (final String v : values) a.add(new JsonPrimitive(v));
        return a;
    }

}
