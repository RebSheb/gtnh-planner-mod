package com.gtnhplanner.data.flowchart;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * The task board's edits on a graph, pure: what the task view and its panel do to {@link Graph#tasks},
 * {@link Graph#taskItems} and {@link Graph#taskComments}. Callers wrap them in one undoable edit. Lists come back in
 * the board's order: a column's tasks and a checklist by their {@link OrderKeys order keys} (then id, where two keys
 * clash), comments oldest first. A move writes one new key between its neighbours'; only a list whose keys cannot take
 * one (clashing, or not keys at all) is numbered afresh.
 */
public final class TaskEdits {

    /** The plan's limits on lengths (docs/design/cloud-live.md). */
    public static final int TITLE_MAX = 120, TEXT_MAX = 4000, ITEM_MAX = 200, COMMENT_MAX = 2000;

    private TaskEdits() {}

    // region Reading

    @Nullable
    public static Tasks.Task task(final Graph g, final String id) {
        for (final Tasks.Task t : g.tasks) if (t.id()
            .equals(id)) return t;
        return null;
    }

    @Nullable
    public static Tasks.Item item(final Graph g, final String id) {
        for (final Tasks.Item i : g.taskItems) if (i.id()
            .equals(id)) return i;
        return null;
    }

    /** A column's tasks, top first. */
    public static List<Tasks.Task> column(final Graph g, final Tasks.Status status) {
        final List<Tasks.Task> out = new ArrayList<>();
        for (final Tasks.Task t : g.tasks) if (t.status() == status) out.add(t);
        out.sort(
            Comparator.comparing(Tasks.Task::order)
                .thenComparing(Tasks.Task::id));
        return out;
    }

    /** A task's checklist, top first. */
    public static List<Tasks.Item> items(final Graph g, final String taskId) {
        final List<Tasks.Item> out = new ArrayList<>();
        for (final Tasks.Item i : g.taskItems) if (i.taskId()
            .equals(taskId)) out.add(i);
        out.sort(
            Comparator.comparing(Tasks.Item::order)
                .thenComparing(Tasks.Item::id));
        return out;
    }

    /** A task's comments, oldest first. */
    public static List<Tasks.Comment> comments(final Graph g, final String taskId) {
        final List<Tasks.Comment> out = new ArrayList<>();
        for (final Tasks.Comment c : g.taskComments) if (c.taskId()
            .equals(taskId)) out.add(c);
        out.sort(
            Comparator.comparing(Tasks.Comment::at)
                .thenComparing(Tasks.Comment::id));
        return out;
    }

    /**
     * Adds to {@code out} each linked card's most pressing task of this board, by link: blocked first, then doing, to
     * do and done. A link with no plan is one to a card of the board itself.
     */
    public static void statusByLink(final Graph g, final Map<Tasks.Link, Tasks.Status> out) {
        final Map<String, Tasks.Status> status = new java.util.HashMap<>();
        for (final Tasks.Task t : g.tasks) {
            status.put(t.id(), t.status());
            for (final Tasks.Link l : t.links()) mark(g, l, t.status(), out);
        }
        // A task's machines are its cards too.
        for (final Tasks.Item i : g.taskItems) {
            final Tasks.Status s = status.get(i.taskId());
            if (s != null && i.link() != null) mark(g, i.link(), s, out);
        }
    }

    private static void mark(final Graph g, final Tasks.Link l, final Tasks.Status s,
        final Map<Tasks.Link, Tasks.Status> out) {
        out.merge(
            l.plan()
                .isEmpty() ? new Tasks.Link(g.planId(), l.node()) : l,
            s,
            (a, b) -> urgency(a) >= urgency(b) ? a : b);
    }

    /**
     * The cards a task links that none of its things names: an older task's cards, made before every card was a thing
     * of its own. Each once, in its order.
     */
    public static List<Tasks.Link> looseLinks(final Graph g, final String taskId) {
        final Tasks.Task t = task(g, taskId);
        if (t == null) return List.of();
        final Set<Tasks.Link> named = new HashSet<>();
        for (final Tasks.Item i : items(g, taskId)) if (i.link() != null) named.add(i.link());
        final List<Tasks.Link> out = new ArrayList<>();
        for (final Tasks.Link l : t.links()) if (named.add(l)) out.add(l);
        return out;
    }

    /**
     * Takes a thing off its task; a card it was also linked by (an older task's) goes with it, so it does not come
     * back as a loose card.
     */
    public static void removeThing(final Graph g, final String id) {
        final Tasks.Item item = item(g, id);
        if (item == null) return;
        removeItem(g, id);
        final Tasks.Task t = task(g, item.taskId());
        if (t == null || item.link() == null
            || !t.links()
                .contains(item.link()))
            return;
        final List<Tasks.Link> links = new ArrayList<>(t.links());
        links.remove(item.link());
        put(g, withLinks(t, links));
    }

    /** How much a status asks for attention: blocked most, done least. */
    public static int urgency(final Tasks.Status s) {
        return switch (s) {
            case BLOCKED -> 3;
            case DOING -> 2;
            case TODO -> 1;
            case DONE -> 0;
        };
    }

    // endregion

    // region Tasks

    /** Puts a task in place of the one with its id, or adds it. */
    public static void put(final Graph g, final Tasks.Task t) {
        for (int i = 0; i < g.tasks.size(); i++) if (g.tasks.get(i)
            .id()
            .equals(t.id())) {
                g.tasks.set(i, t);
                return;
            }
        g.tasks.add(t);
    }

    /** A new task at the bottom of a column. */
    public static Tasks.Task add(final Graph g, final String id, final String title, final Tasks.Status status,
        final List<Tasks.Link> links, @Nullable final String by, final String at) {
        final List<Tasks.Task> column = column(g, status);
        String order;
        try {
            order = OrderKeys.between(
                column.isEmpty() ? null
                    : column.get(column.size() - 1)
                        .order(),
                null);
        } catch (final IllegalArgumentException e) {
            renumber(g, column);
            order = OrderKeys.between(
                column.isEmpty() ? null
                    : task(
                        g,
                        column.get(column.size() - 1)
                            .id()).order(),
                null);
        }
        final Tasks.Task t = new Tasks.Task(
            id,
            clip(title, TITLE_MAX),
            null,
            status,
            order,
            List.of(),
            links,
            by,
            at,
            status == Tasks.Status.DONE ? at : null,
            null,
            at);
        g.tasks.add(t);
        return t;
    }

    /**
     * Moves a task to {@code index} among the other tasks of column {@code to} (0 the top). Into done stamps when, at
     * {@code at}; out of done clears it.
     */
    public static void move(final Graph g, final String id, final Tasks.Status to, final int index, final String at) {
        final Tasks.Task t = task(g, id);
        if (t == null) return;
        final List<Tasks.Task> others = column(g, to);
        others.removeIf(
            o -> o.id()
                .equals(id));
        final int i = Math.max(0, Math.min(others.size(), index));
        String order;
        try {
            order = strictBetween(
                i > 0 ? others.get(i - 1)
                    .order() : null,
                i < others.size() ? others.get(i)
                    .order() : null);
        } catch (final IllegalArgumentException e) {
            // The column's keys clash or are not keys: number it afresh with the task in its place.
            final List<String> keys = OrderKeys.sequence(others.size() + 1);
            for (int k = 0; k < others.size(); k++) {
                final Tasks.Task o = others.get(k);
                final String key = keys.get(k < i ? k : k + 1);
                if (!key.equals(o.order())) put(g, withOrder(o, key));
            }
            order = keys.get(i);
        }
        final String doneAt = to == Tasks.Status.DONE ? t.status() == Tasks.Status.DONE ? t.doneAt() : at : null;
        put(
            g,
            new Tasks.Task(
                t.id(),
                t.title(),
                t.text(),
                to,
                order,
                t.assignees(),
                t.links(),
                t.createdBy(),
                t.createdAt(),
                doneAt,
                t.priority(),
                to == t.status() ? t.movedAt() : at,
                t.blockedBy()));
    }

    /** Takes a task off the board, with its checklist and comments; the tasks that waited on it wait on it no more. */
    public static void remove(final Graph g, final String id) {
        g.tasks.removeIf(
            t -> t.id()
                .equals(id));
        for (final Tasks.Task t : List.copyOf(g.tasks)) if (t.blockedBy()
            .contains(id)) {
                final List<String> waits = new ArrayList<>(t.blockedBy());
                waits.remove(id);
                put(g, withBlockedBy(t, waits));
            }
        g.taskItems.removeIf(
            i -> i.taskId()
                .equals(id));
        g.taskComments.removeIf(
            c -> c.taskId()
                .equals(id));
    }

    public static Tasks.Task withTitle(final Tasks.Task t, final String title) {
        return new Tasks.Task(
            t.id(),
            clip(title, TITLE_MAX),
            t.text(),
            t.status(),
            t.order(),
            t.assignees(),
            t.links(),
            t.createdBy(),
            t.createdAt(),
            t.doneAt(),
            t.priority(),
            t.movedAt(),
            t.blockedBy());
    }

    /** The task with its text; blank text is none. */
    public static Tasks.Task withText(final Tasks.Task t, @Nullable final String text) {
        final String s = text == null || text.isBlank() ? null : clip(text, TEXT_MAX);
        return new Tasks.Task(
            t.id(),
            t.title(),
            s,
            t.status(),
            t.order(),
            t.assignees(),
            t.links(),
            t.createdBy(),
            t.createdAt(),
            t.doneAt(),
            t.priority(),
            t.movedAt(),
            t.blockedBy());
    }

    public static Tasks.Task withAssignees(final Tasks.Task t, final List<String> assignees) {
        return new Tasks.Task(
            t.id(),
            t.title(),
            t.text(),
            t.status(),
            t.order(),
            assignees,
            t.links(),
            t.createdBy(),
            t.createdAt(),
            t.doneAt(),
            t.priority(),
            t.movedAt(),
            t.blockedBy());
    }

    public static Tasks.Task withLinks(final Tasks.Task t, final List<Tasks.Link> links) {
        return new Tasks.Task(
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

    /** The task with its priority; Medium is the default and is written as none. */
    public static Tasks.Task withPriority(final Tasks.Task t, @Nullable final String priority) {
        return new Tasks.Task(
            t.id(),
            t.title(),
            t.text(),
            t.status(),
            t.order(),
            t.assignees(),
            t.links(),
            t.createdBy(),
            t.createdAt(),
            t.doneAt(),
            priority,
            t.movedAt(),
            t.blockedBy());
    }

    /** The task waiting on these tasks (by id; itself and repeats dropped). */
    public static Tasks.Task withBlockedBy(final Tasks.Task t, final List<String> blockedBy) {
        return new Tasks.Task(
            t.id(),
            t.title(),
            t.text(),
            t.status(),
            t.order(),
            t.assignees(),
            t.links(),
            t.createdBy(),
            t.createdAt(),
            t.doneAt(),
            t.priority(),
            t.movedAt(),
            blockedBy);
    }

    private static Tasks.Task withOrder(final Tasks.Task t, final String order) {
        return new Tasks.Task(
            t.id(),
            t.title(),
            t.text(),
            t.status(),
            order,
            t.assignees(),
            t.links(),
            t.createdBy(),
            t.createdAt(),
            t.doneAt(),
            t.priority(),
            t.movedAt(),
            t.blockedBy());
    }

    /**
     * A key between two neighbours of a move; throws when they are not in order (equal or reversed keys), so the
     * caller numbers the list afresh instead of guessing a place.
     */
    private static String strictBetween(@Nullable final String a, @Nullable final String b) {
        if (a != null && b != null && a.compareTo(b) >= 0) throw new IllegalArgumentException(a + " >= " + b);
        return OrderKeys.between(a, b);
    }

    /** Numbers a column afresh, in its order: for a column whose last key nothing can follow. */
    private static void renumber(final Graph g, final List<Tasks.Task> column) {
        final List<String> keys = OrderKeys.sequence(column.size());
        for (int k = 0; k < column.size(); k++) put(g, withOrder(column.get(k), keys.get(k)));
    }

    // endregion

    // region Blocked by

    /** The tasks a task waits on that are on the board, in the order it lists them. */
    public static List<Tasks.Task> blockers(final Graph g, final String id) {
        final Tasks.Task t = task(g, id);
        final List<Tasks.Task> out = new ArrayList<>();
        if (t == null) return out;
        for (final String b : t.blockedBy()) {
            final Tasks.Task w = task(g, b);
            if (w != null) out.add(w);
        }
        return out;
    }

    /** The tasks a task waits on that are not Done yet. */
    public static List<Tasks.Task> openBlockers(final Graph g, final String id) {
        final List<Tasks.Task> out = blockers(g, id);
        out.removeIf(t -> t.status() == Tasks.Status.DONE);
        return out;
    }

    /** The tasks waiting on a task ("Blocks"), in the board's order: column by column, top first. */
    public static List<Tasks.Task> blocking(final Graph g, final String id) {
        final List<Tasks.Task> out = new ArrayList<>();
        for (final Tasks.Status s : Tasks.Status.values()) for (final Tasks.Task t : column(g, s)) if (t.blockedBy()
            .contains(id)) out.add(t);
        return out;
    }

    /** Whether a task not done waits on tasks and every one of them is Done: it can start. */
    public static boolean ready(final Graph g, final Tasks.Task t) {
        if (t.status() == Tasks.Status.DONE) return false;
        final List<Tasks.Task> waits = blockers(g, t.id());
        return !waits.isEmpty() && openBlockers(g, t.id()).isEmpty();
    }

    /**
     * Whether {@code task} waiting on {@code blocker} would close a loop: the blocker is the task, or waits on it,
     * directly or down the chain.
     */
    public static boolean wouldLoop(final Graph g, final String task, final String blocker) {
        if (task.equals(blocker)) return true;
        final Set<String> seen = new HashSet<>();
        final List<String> todo = new ArrayList<>(List.of(blocker));
        while (!todo.isEmpty()) {
            final String at = todo.remove(todo.size() - 1);
            if (!seen.add(at)) continue;
            final Tasks.Task t = task(g, at);
            if (t == null) continue;
            for (final String b : t.blockedBy()) {
                if (b.equals(task)) return true;
                todo.add(b);
            }
        }
        return false;
    }

    /**
     * Makes {@code task} wait on {@code blocker}. Refused (false, nothing changes) when either is not on the board, it
     * already waits on it, or it would close a loop.
     */
    public static boolean addBlocker(final Graph g, final String task, final String blocker) {
        final Tasks.Task t = task(g, task);
        if (t == null || task(g, blocker) == null
            || t.blockedBy()
                .contains(blocker)
            || wouldLoop(g, task, blocker)) return false;
        final List<String> waits = new ArrayList<>(t.blockedBy());
        waits.add(blocker);
        put(g, withBlockedBy(t, waits));
        return true;
    }

    /** {@code task} waits on {@code blocker} no more. */
    public static void removeBlocker(final Graph g, final String task, final String blocker) {
        final Tasks.Task t = task(g, task);
        if (t == null || !t.blockedBy()
            .contains(blocker)) return;
        final List<String> waits = new ArrayList<>(t.blockedBy());
        waits.remove(blocker);
        put(g, withBlockedBy(t, waits));
    }

    // endregion

    // region Things

    /** Puts a thing in place of the one with its id, or adds it. */
    public static void putItem(final Graph g, final Tasks.Item item) {
        for (int i = 0; i < g.taskItems.size(); i++) if (g.taskItems.get(i)
            .id()
            .equals(item.id())) {
                g.taskItems.set(i, item);
                return;
            }
        g.taskItems.add(item);
    }

    /** A new line of words at the bottom of a task's things (how an older checklist was made). */
    public static Tasks.Item addItem(final Graph g, final String id, final String taskId, final String text,
        @Nullable final Tasks.Link link) {
        return addThing(g, id, taskId, "", text, link, null, null);
    }

    /**
     * A new thing at the bottom of a task's things, not done: a machine ({@code link}), a location ({@code place}) or
     * a screenshot ({@code picture}); {@code text} is the thing in words.
     */
    public static Tasks.Item addThing(final Graph g, final String id, final String taskId, final String kind,
        final String text, @Nullable final Tasks.Link link, @Nullable final Tasks.Place place,
        @Nullable final Tasks.Picture picture) {
        return addThing(g, id, taskId, kind, text, link, place, picture, null);
    }

    /** {@link #addThing}, with a quest for a {@link Tasks#QUEST} thing. */
    public static Tasks.Item addThing(final Graph g, final String id, final String taskId, final String kind,
        final String text, @Nullable final Tasks.Link link, @Nullable final Tasks.Place place,
        @Nullable final Tasks.Picture picture, @Nullable final Tasks.Quest quest) {
        final List<Tasks.Item> list = items(g, taskId);
        String order;
        try {
            order = OrderKeys.between(
                list.isEmpty() ? null
                    : list.get(list.size() - 1)
                        .order(),
                null);
        } catch (final IllegalArgumentException e) {
            final List<String> keys = OrderKeys.sequence(list.size() + 1);
            for (int k = 0; k < list.size(); k++) putItem(g, withOrder(list.get(k), keys.get(k)));
            order = keys.get(list.size());
        }
        final Tasks.Item item = new Tasks.Item(
            id,
            taskId,
            kind,
            clip(text, ITEM_MAX),
            false,
            order,
            link,
            place,
            picture,
            quest);
        g.taskItems.add(item);
        return item;
    }

    // region Quests

    /**
     * Adds a quest to a task as a thing at the bottom, its words the quest's name; null (and nothing added) when the
     * task already has that quest or is gone.
     */
    @Nullable
    public static Tasks.Item addQuest(final Graph g, final String id, final String taskId, final Tasks.Quest quest) {
        if (task(g, taskId) == null || questThing(g, taskId, quest.id()) != null) return null;
        final String words = quest.name()
            .isBlank() ? "A quest" : quest.name();
        return addThing(g, id, taskId, Tasks.QUEST, words, null, null, null, quest);
    }

    /** The task's thing for the quest {@code questId}; null when it has none. */
    @Nullable
    public static Tasks.Item questThing(final Graph g, final String taskId, final String questId) {
        for (final Tasks.Item i : items(g, taskId)) if (Tasks.QUEST.equals(i.shownKind()) && i.quest()
            .id()
            .equals(questId)) return i;
        return null;
    }

    /**
     * The quest things to tick because the player has done their quest: not ticked yet, their quest done
     * ({@code done} says, by quest id), and not in {@code left} (things the player unticked by hand after their quest
     * was done, or ticked once already, which are theirs to keep as they are).
     */
    public static List<Tasks.Item> questsToTick(final Graph g, final java.util.function.Predicate<String> done,
        final Set<String> left) {
        final List<Tasks.Item> out = new ArrayList<>();
        for (final Tasks.Item i : g.taskItems) {
            if (i.done() || !Tasks.QUEST.equals(i.shownKind()) || left.contains(i.id())) continue;
            if (done.test(
                i.quest()
                    .id()))
                out.add(i);
        }
        return out;
    }

    // endregion

    /** A task's things done and all of them: {done, total}. */
    public static int[] progress(final Graph g, final String taskId) {
        int done = 0, all = 0;
        for (final Tasks.Item i : g.taskItems) if (i.taskId()
            .equals(taskId)) {
                all++;
                if (i.done()) done++;
            }
        return new int[] { done, all };
    }

    /** A task's first screenshot, in its order; null when it has none. */
    @Nullable
    public static Tasks.Item firstPicture(final Graph g, final String taskId) {
        for (final Tasks.Item i : items(g, taskId)) if (Tasks.PICTURE.equals(i.shownKind())) return i;
        return null;
    }

    /** Moves a line to {@code index} among the other lines of its checklist (0 the top). */
    public static void moveItem(final Graph g, final String id, final int index) {
        final Tasks.Item item = item(g, id);
        if (item == null) return;
        final List<Tasks.Item> others = items(g, item.taskId());
        others.removeIf(
            o -> o.id()
                .equals(id));
        final int i = Math.max(0, Math.min(others.size(), index));
        String order;
        try {
            order = strictBetween(
                i > 0 ? others.get(i - 1)
                    .order() : null,
                i < others.size() ? others.get(i)
                    .order() : null);
        } catch (final IllegalArgumentException e) {
            final List<String> keys = OrderKeys.sequence(others.size() + 1);
            for (int k = 0; k < others.size(); k++) {
                final Tasks.Item o = others.get(k);
                final String key = keys.get(k < i ? k : k + 1);
                if (!key.equals(o.order())) putItem(g, withOrder(o, key));
            }
            order = keys.get(i);
        }
        putItem(g, withOrder(item, order));
    }

    public static void removeItem(final Graph g, final String id) {
        g.taskItems.removeIf(
            i -> i.id()
                .equals(id));
    }

    public static Tasks.Item withDone(final Tasks.Item i, final boolean done) {
        return new Tasks.Item(
            i.id(),
            i.taskId(),
            i.kind(),
            i.text(),
            done,
            i.order(),
            i.link(),
            i.place(),
            i.picture(),
            i.quest());
    }

    public static Tasks.Item withText(final Tasks.Item i, final String text) {
        return new Tasks.Item(
            i.id(),
            i.taskId(),
            i.kind(),
            clip(text, ITEM_MAX),
            i.done(),
            i.order(),
            i.link(),
            i.place(),
            i.picture(),
            i.quest());
    }

    public static Tasks.Item withLink(final Tasks.Item i, @Nullable final Tasks.Link link) {
        return new Tasks.Item(
            i.id(),
            i.taskId(),
            i.kind(),
            i.text(),
            i.done(),
            i.order(),
            link,
            i.place(),
            i.picture(),
            i.quest());
    }

    /**
     * A location with its label (blank is none); its words follow it, as {@link #placeText} spells them.
     */
    public static Tasks.Item withLabel(final Tasks.Item i, @Nullable final String label) {
        final Tasks.Place p = i.place();
        if (p == null) return i;
        final String l = label == null || label.isBlank() ? null : clip(label, LABEL_MAX);
        final Tasks.Place next = new Tasks.Place(p.x(), p.y(), p.z(), p.dim(), p.dimName(), p.world(), l);
        return new Tasks.Item(
            i.id(),
            i.taskId(),
            i.kind(),
            placeText(next),
            i.done(),
            i.order(),
            i.link(),
            next,
            i.picture(),
            i.quest());
    }

    private static Tasks.Item withOrder(final Tasks.Item i, final String order) {
        return new Tasks.Item(
            i.id(),
            i.taskId(),
            i.kind(),
            i.text(),
            i.done(),
            order,
            i.link(),
            i.place(),
            i.picture(),
            i.quest());
    }

    /** The most a location's label holds. */
    public static final int LABEL_MAX = 60;

    /** A location in words: its label and its block, "Ore field at 120 64 -30 in the Overworld". */
    public static String placeText(final Tasks.Place p) {
        final String at = p.x() + " "
            + p.y()
            + " "
            + p.z()
            + (p.dimName()
                .isEmpty() ? "" : " in " + p.dimName());
        return p.label() == null ? at : p.label() + " at " + at;
    }

    // endregion

    // region Where a spot is

    /**
     * Which way a spot lies from someone, in eight points of the compass as the game draws them (north is toward -z,
     * east toward +x); "" when they are on it.
     */
    public static String compass(final double dx, final double dz) {
        if (Math.abs(dx) < 0.5 && Math.abs(dz) < 0.5) return "";
        // 0 east, counter-clockwise in the game's x and -z.
        final double deg = Math.toDegrees(Math.atan2(-dz, dx));
        final int sector = (int) Math.floorMod(Math.round(deg / 45), 8);
        return switch (sector) {
            case 0 -> "east";
            case 1 -> "north-east";
            case 2 -> "north";
            case 3 -> "north-west";
            case 4 -> "west";
            case 5 -> "south-west";
            case 6 -> "south";
            default -> "south-east";
        };
    }

    /** "12 m north-east", "here": how far a spot is and which way, across the ground. */
    public static String howFar(final double dx, final double dz) {
        final long d = Math.round(Math.sqrt(dx * dx + dz * dz));
        final String way = compass(dx, dz);
        if (d < 1 || way.isEmpty()) return "here";
        return (d >= 10_000 ? Math.round(d / 1000.0) + " km " : d + " m ") + way;
    }

    // endregion

    // region Comments

    public static Tasks.Comment addComment(final Graph g, final String id, final String taskId, final String by,
        final String byName, final String at, final String text) {
        final Tasks.Comment c = new Tasks.Comment(id, taskId, by, byName, at, clip(text, COMMENT_MAX));
        g.taskComments.add(c);
        return c;
    }

    public static void removeComment(final Graph g, final String id) {
        g.taskComments.removeIf(
            c -> c.id()
                .equals(id));
    }

    // endregion

    /** Text trimmed and kept within the plan's limit for it. */
    public static String clip(final String text, final int max) {
        final String t = text.strip();
        return t.length() <= max ? t
            : t.substring(0, max)
                .strip();
    }
}
