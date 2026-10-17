package com.gtnhplanner.data.flowchart;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

/**
 * Who is in which board group ({@link BoardGroup}): the rules of {@code docs/design/groups.md} over a {@link Graph},
 * Minecraft-free so they are tested headless. A card, drawer, note or group is in at most one group; groups nest.
 */
public final class Groups {

    private Groups() {}

    /** The group a card, drawer, note or group is in, or null for the board (or no such thing). */
    @Nullable
    public static UUID groupOf(final Graph graph, final UUID id) {
        final Node n = graph.nodes.get(id);
        if (n != null) return n.group;
        final Drawer d = graph.drawers.get(id);
        if (d != null) return d.getGroup();
        final Note note = graph.notes.get(id);
        if (note != null) return note.getGroup();
        final BoardGroup g = graph.boardGroups.get(id);
        return g == null ? null : g.getParent();
    }

    /**
     * Puts a card, drawer, note or group in a group (null: on the board). A recipe of a shared machine takes the
     * machine's other recipes with it; a group is never put inside itself or what it holds (false then, nothing
     * changes). Changes no version: the caller's edit does.
     */
    public static boolean setGroupOf(final Graph graph, final UUID id, @Nullable final UUID group) {
        if (group != null && !graph.boardGroups.containsKey(group)) return false;
        final BoardGroup g = graph.boardGroups.get(id);
        if (g != null) {
            if (group != null && (group.equals(id) || ancestors(graph, group).contains(id))) return false;
            g.setParent(group);
            return true;
        }
        final Node n = graph.nodes.get(id);
        if (n != null) {
            for (final UUID s : sections(graph, id)) {
                final Node section = graph.nodes.get(s);
                if (section != null) section.group = group;
            }
            return true;
        }
        final Drawer d = graph.drawers.get(id);
        if (d != null) {
            d.setGroup(group);
            return true;
        }
        final Note note = graph.notes.get(id);
        if (note != null) {
            note.setGroup(group);
            return true;
        }
        return false;
    }

    /** A recipe and every other recipe on its shared machine. */
    private static List<UUID> sections(final Graph graph, final UUID nodeId) {
        for (final Group g : graph.getGroups())
            if (g instanceof final MachineGroup m && m.isShared()
                && m.getSections()
                    .contains(nodeId))
                return m.getSections();
        return List.of(nodeId);
    }

    /** The groups round a thing, innermost first (its own group first); empty on the board. */
    public static List<UUID> ancestors(final Graph graph, final UUID id) {
        final List<UUID> out = new ArrayList<>();
        final Set<UUID> seen = new HashSet<>();
        for (UUID at = groupOf(graph, id); at != null && seen.add(at); at = groupOf(graph, at)) {
            if (!graph.boardGroups.containsKey(at)) break;
            out.add(at);
        }
        return out;
    }

    /** What sits directly in a group: cards, drawers, notes and groups, in that order. */
    public static List<UUID> children(final Graph graph, final UUID group) {
        final List<UUID> out = new ArrayList<>();
        for (final Node n : graph.getNodes()) if (group.equals(n.group)) out.add(n.id);
        for (final Drawer d : graph.getDrawers()) if (group.equals(d.getGroup())) out.add(d.getId());
        for (final Note n : graph.getNotes()) if (group.equals(n.getGroup())) out.add(n.getId());
        for (final BoardGroup g : graph.boardGroups.values()) if (group.equals(g.getParent())) out.add(g.getId());
        return out;
    }

    /** Everything a group holds, however deep: its children, their children, and so on (not the group itself). */
    public static Set<UUID> descendants(final Graph graph, final UUID group) {
        final Set<UUID> out = new LinkedHashSet<>();
        final List<UUID> todo = new ArrayList<>(List.of(group));
        while (!todo.isEmpty()) {
            final UUID at = todo.remove(todo.size() - 1);
            for (final UUID c : children(graph, at)) if (out.add(c) && graph.boardGroups.containsKey(c)) todo.add(c);
        }
        return out;
    }

    /** The outermost collapsed group round a thing (what the board shows in its place), or null when it shows. */
    @Nullable
    public static UUID hiddenBy(final Graph graph, final UUID id) {
        UUID hider = null;
        for (final UUID g : ancestors(graph, id)) if (graph.boardGroups.get(g)
            .isCollapsed()) hider = g;
        return hider;
    }

    /** Whether a thing is out of sight inside a collapsed group. */
    public static boolean hidden(final Graph graph, final UUID id) {
        return hiddenBy(graph, id) != null;
    }

    /** Whether a group shows on the board (it is not inside a collapsed one). */
    public static boolean shows(final Graph graph, final BoardGroup g) {
        return hiddenBy(graph, g.getId()) == null;
    }

    /** The groups a new group is put in when made of these things: their one shared group, or the board. */
    @Nullable
    public static UUID commonGroup(final Graph graph, final java.util.Collection<UUID> ids) {
        UUID common = null;
        boolean first = true;
        for (final UUID id : ids) {
            final UUID g = groupOf(graph, id);
            if (first) common = g;
            else if (!java.util.Objects.equals(common, g)) return null;
            first = false;
        }
        return common;
    }

    /** "Group N", N one more than the groups on the plan. */
    public static String newName(final Graph graph) {
        return "Group " + (graph.boardGroups.size() + 1);
    }

    /** The colour a new group on this plan takes. */
    public static String newColor(final Graph graph) {
        final Set<String> worn = new HashSet<>();
        for (final BoardGroup g : graph.boardGroups.values()) worn.add(g.colorTag());
        return BoardGroup.newColor(worn);
    }

    /**
     * Removes a group; what it held goes to the group it sat in (or the board), where it stands. Changes no version.
     */
    public static void ungroup(final Graph graph, final UUID group) {
        final BoardGroup g = graph.boardGroups.get(group);
        if (g == null) return;
        for (final UUID c : children(graph, group)) setGroupOf(graph, c, g.getParent());
        graph.boardGroups.remove(group);
    }

    /**
     * Repairs groups read from a save or a plan: a member of a group that is not there is on the board, and a group
     * inside itself (a loop of parents) is lifted onto the board.
     */
    public static void sanitize(final Graph graph) {
        for (final Node n : graph.getNodes())
            if (n.group != null && !graph.boardGroups.containsKey(n.group)) n.group = null;
        for (final Drawer d : graph.getDrawers())
            if (d.getGroup() != null && !graph.boardGroups.containsKey(d.getGroup())) d.setGroup(null);
        for (final Note n : graph.getNotes())
            if (n.getGroup() != null && !graph.boardGroups.containsKey(n.getGroup())) n.setGroup(null);
        for (final BoardGroup g : graph.boardGroups.values()) {
            if (g.getParent() != null && !graph.boardGroups.containsKey(g.getParent())) g.setParent(null);
            if (g.getName() == null) g.setName("");
        }
        for (final BoardGroup g : graph.boardGroups.values()) {
            final Set<UUID> seen = new HashSet<>();
            for (UUID at = g.getParent(); at != null; at = graph.boardGroups.get(at)
                .getParent()) {
                if (at.equals(g.getId()) || !seen.add(at)) {
                    g.setParent(null);
                    break;
                }
            }
        }
    }

    /**
     * Grows each group's frame to hold its members (their boxes from {@code box}: {x, y, w, h}, null for one not
     * known) with {@link BoardGroup#PAD} round them and the title strip above, innermost groups first; never shrinks
     * one. True when any frame changed.
     */
    public static boolean fit(final Graph graph, final java.util.function.Function<UUID, int[]> box) {
        boolean changed = false;
        // Innermost first: a nested group's grown frame is then its parent's member box.
        final List<BoardGroup> order = new ArrayList<>(graph.boardGroups.values());
        order.sort((a, b) -> ancestors(graph, b.getId()).size() - ancestors(graph, a.getId()).size());
        for (final BoardGroup g : order) {
            final int[] need = membersBox(graph, g.getId(), box);
            if (need == null) continue;
            final int x0 = Math.min(g.getX(), need[0] - BoardGroup.PAD);
            final int y0 = Math.min(g.getY(), need[1] - BoardGroup.PAD - BoardGroup.STRIP);
            final int x1 = Math.max(g.getX() + g.getWidth(), need[2] + BoardGroup.PAD);
            final int y1 = Math.max(g.getY() + g.getHeight(), need[3] + BoardGroup.PAD);
            if (x0 != g.getX() || y0 != g.getY() || x1 - x0 != g.getWidth() || y1 - y0 != g.getHeight()) {
                // A collapsed group's card sits at the frame's corner: it keeps its place, the frame grows away.
                g.setX(x0);
                g.setY(y0);
                g.setWidth(x1 - x0);
                g.setHeight(y1 - y0);
                changed = true;
            }
        }
        return changed;
    }

    /** The box {x0, y0, x1, y1} round a group's direct members (a nested group by its frame), or null for none. */
    @Nullable
    public static int[] membersBox(final Graph graph, final UUID group,
        final java.util.function.Function<UUID, int[]> box) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for (final UUID c : children(graph, group)) {
            final BoardGroup child = graph.boardGroups.get(c);
            final int[] b = child != null
                ? new int[] { child.getX(), child.getY(), child.getWidth(), child.getHeight() }
                : box.apply(c);
            if (b == null) continue;
            x0 = Math.min(x0, b[0]);
            y0 = Math.min(y0, b[1]);
            x1 = Math.max(x1, b[0] + b[2]);
            y1 = Math.max(y1, b[1] + b[3]);
        }
        return x0 > x1 ? null : new int[] { x0, y0, x1, y1 };
    }
}
