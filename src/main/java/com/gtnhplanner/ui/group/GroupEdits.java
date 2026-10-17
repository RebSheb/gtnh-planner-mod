package com.gtnhplanner.ui.group;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.gtnhplanner.data.flowchart.BoardGroup;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Groups;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.sound.Sfx;

/**
 * The board's group edits ({@code docs/design/groups.md}), each one undoable step: make a group round the selection,
 * rename, recolour, collapse and expand, resize, ungroup, delete with its contents, and the membership a drop decides.
 * Sizes come from {@code box}: a thing's {x, y, w, h} on the board as the board draws it (null when not known).
 */
public final class GroupEdits {

    private final BoardSession session;
    private final Function<UUID, int[]> box;

    public GroupEdits(final BoardSession session, final Function<UUID, int[]> box) {
        this.session = session;
        this.box = box;
    }

    private Graph graph() {
        return session.graph();
    }

    /** The cards, drawers, notes and groups among {@code ids} a group can take (a shared machine's recipes whole). */
    private List<UUID> things(final Collection<UUID> ids) {
        final Graph g = graph();
        final Set<UUID> out = new LinkedHashSet<>();
        for (final UUID id : ids) {
            if (g.nodes.containsKey(id)) out.addAll(session.sectionsOf(id));
            else if (g.drawers.containsKey(id) || g.notes.containsKey(id) || g.boardGroups.containsKey(id)) out.add(id);
        }
        // Riding in a selected group: it keeps its place inside it.
        out.removeIf(
            id -> Groups.ancestors(g, id)
                .stream()
                .anyMatch(out::contains));
        return new ArrayList<>(out);
    }

    /** A thing's box on the board: a group's frame, else what the board says. */
    @Nullable
    public int[] boxOf(final UUID id) {
        final BoardGroup g = graph().boardGroups.get(id);
        if (g != null) return new int[] { g.getX(), g.getY(), g.getWidth(), g.getHeight() };
        return box.apply(id);
    }

    /**
     * A group round these things where they stand (nothing moves): the frame is their box plus {@link BoardGroup#PAD}
     * and the title strip. All in one group: the new one goes inside it; else they leave theirs and it sits on the
     * board. The selection becomes the group. Returns it, or null when nothing could be grouped.
     */
    @Nullable
    public UUID group(final Collection<UUID> ids) {
        final List<UUID> things = things(ids);
        if (things.isEmpty()) return null;
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for (final UUID id : things) {
            final int[] b = boxOf(id);
            if (b == null) continue;
            x0 = Math.min(x0, b[0]);
            y0 = Math.min(y0, b[1]);
            x1 = Math.max(x1, b[0] + b[2]);
            y1 = Math.max(y1, b[1] + b[3]);
        }
        if (x0 > x1) return null;
        final UUID parent = Groups.commonGroup(graph(), things);
        final BoardGroup made = new BoardGroup();
        made.setName(Groups.newName(graph()));
        made.setColor(Groups.newColor(graph()));
        made.setParent(parent);
        final int gx = BoardSession.snap(x0 - BoardGroup.PAD - 4),
            gy = BoardSession.snap(y0 - BoardGroup.PAD - BoardGroup.STRIP - 4);
        made.setX(gx);
        made.setY(gy);
        made.setWidth(Math.max(BoardGroup.MIN_W, x1 + BoardGroup.PAD - gx));
        made.setHeight(Math.max(BoardGroup.MIN_H, y1 + BoardGroup.PAD - gy));
        session.editLayout(() -> {
            graph().boardGroups.put(made.getId(), made);
            for (final UUID id : things) Groups.setGroupOf(graph(), id, made.getId());
            Groups.fit(graph(), this::boxOf);
        });
        session.clearSelection();
        session.select(made.getId(), false);
        Sfx.MERGE.play();
        return made.getId();
    }

    public void rename(final UUID group, final String name) {
        final BoardGroup g = graph().boardGroups.get(group);
        if (g == null || name == null
            || name.trim()
                .isEmpty()
            || name.equals(g.getName())) return;
        session.editLayout(() -> g.setName(name.trim()));
    }

    public void recolor(final UUID group, final String tag) {
        final BoardGroup g = graph().boardGroups.get(group);
        if (g == null) return;
        Sfx.ADJUST.play();
        session.editLayout(() -> g.setColor(tag));
    }

    /** Collapses or expands; the selection lets go of what goes out of sight. */
    public void setCollapsed(final UUID group, final boolean collapsed) {
        final BoardGroup g = graph().boardGroups.get(group);
        if (g == null || g.isCollapsed() == collapsed) return;
        (collapsed ? Sfx.CLOSE : Sfx.OPEN).play();
        session.editLayout(() -> {
            g.setCollapsed(collapsed);
            if (!collapsed) Groups.fit(graph(), this::boxOf);
        });
        if (collapsed) for (final UUID id : Groups.descendants(graph(), group)) session.deselect(id);
    }

    /** The group's frame set by a resize; it never cuts a member off. */
    public void resize(final UUID group, final int x, final int y, final int w, final int h) {
        final BoardGroup g = graph().boardGroups.get(group);
        if (g == null) return;
        final int[] keep = clampFrame(group, x, y, w, h);
        if (keep[0] == g.getX() && keep[1] == g.getY() && keep[2] == g.getWidth() && keep[3] == g.getHeight()) return;
        session.editLayout(() -> {
            g.setX(keep[0]);
            g.setY(keep[1]);
            g.setWidth(keep[2]);
            g.setHeight(keep[3]);
            Groups.fit(graph(), this::boxOf);
        });
    }

    /** A frame as a resize may make it: at least the smallest, and never cutting into its members' box. */
    public int[] clampFrame(final UUID group, int x, int y, int w, int h) {
        w = Math.max(BoardGroup.MIN_W, w);
        h = Math.max(BoardGroup.MIN_H, h);
        final int[] need = Groups.membersBox(graph(), group, this::boxOf);
        if (need == null) return new int[] { x, y, w, h };
        final int x1 = Math.max(x + w, need[2] + BoardGroup.PAD), y1 = Math.max(y + h, need[3] + BoardGroup.PAD);
        x = Math.min(x, need[0] - BoardGroup.PAD);
        y = Math.min(y, need[1] - BoardGroup.PAD - BoardGroup.STRIP);
        return new int[] { x, y, x1 - x, y1 - y };
    }

    /** The group goes; what it held stays where it is, in the group it sat in. */
    public void ungroup(final UUID group) {
        if (!graph().boardGroups.containsKey(group)) return;
        Sfx.SWEEP.play(0.8f, 0.9f);
        session.deselect(group);
        session.editLayout(() -> Groups.ungroup(graph(), group));
    }

    /** The group and everything in it, however deep, as one step. */
    public void deleteWithContents(final UUID group) {
        if (!graph().boardGroups.containsKey(group)) return;
        final Set<UUID> all = Groups.descendants(graph(), group);
        all.add(group);
        for (final UUID id : all) session.deselect(id);
        session.edit(() -> removeAll(graph(), all));
        Sfx.REMOVE.play();
    }

    /**
     * Takes cards, drawers, notes and groups off the plan (a group's contents are the caller's to list). Drawers go
     * first, each healing as its own delete does ({@link Graph#removeDrawerAndHeal}): a wire healed onto a card that is
     * also going goes with it.
     */
    public static void removeAll(final Graph g, final Collection<UUID> ids) {
        for (final UUID id : ids) if (g.drawers.containsKey(id)) g.removeDrawerAndHeal(id);
        for (final UUID id : ids) {
            if (g.nodes.containsKey(id)) g.removeNode(id);
            else if (g.notes.containsKey(id)) g.notes.remove(id);
            else g.boardGroups.remove(id);
        }
    }

    /**
     * Where a drop puts carried things: each carried card, drawer and note not riding in a carried group joins
     * {@code target} (null: the board), and the frames grow to hold what they took. Inside the move's own undo step
     * (no step of its own). Returns {joined, left}: how many went into a group, and out of one.
     */
    public int[] land(final Collection<UUID> carried, @Nullable final UUID target) {
        final Graph g = graph();
        int joined = 0, left = 0;
        for (final UUID id : carried) {
            if (g.boardGroups.containsKey(id)) continue;
            if (!g.nodes.containsKey(id) && !g.drawers.containsKey(id) && !g.notes.containsKey(id)) continue;
            if (Groups.ancestors(g, id)
                .stream()
                .anyMatch(carried::contains)) continue;
            final UUID was = Groups.groupOf(g, id);
            if (java.util.Objects.equals(was, target)) continue;
            Groups.setGroupOf(g, id, target);
            if (target != null) joined++;
            else left++;
        }
        Groups.fit(g, this::boxOf);
        return new int[] { joined, left };
    }

    /** The open group a box's centre is over (the innermost), skipping {@code excluded}; null for the board. */
    @Nullable
    public static UUID groupUnder(final Graph g, final float cx, final float cy, final Set<UUID> excluded) {
        UUID best = null;
        int depth = -1;
        for (final BoardGroup b : g.boardGroups.values()) {
            if (b.isCollapsed() || excluded.contains(b.getId()) || !Groups.shows(g, b)) continue;
            if (cx < b.getX() || cx > b.getX() + b.getWidth()
                || cy < b.getY() + BoardGroup.STRIP
                || cy > b.getY() + b.getHeight()) continue;
            final int d = Groups.ancestors(g, b.getId())
                .size();
            if (d > depth) {
                depth = d;
                best = b.getId();
            }
        }
        return best;
    }

    /** Whether a note is shown on a group-aware board (not hidden by its kind nor by a collapsed group). */
    public static boolean shows(final Graph g, final Note n) {
        return !n.isHidden() && !Groups.hidden(g, n.getId());
    }

    /** Whether a card is out of sight in a collapsed group. */
    public static boolean hidden(final Graph g, final Node n) {
        return n.group != null && Groups.hidden(g, n.id);
    }

    public static boolean hidden(final Graph g, final Drawer d) {
        return d.getGroup() != null && Groups.hidden(g, d.getId());
    }
}
