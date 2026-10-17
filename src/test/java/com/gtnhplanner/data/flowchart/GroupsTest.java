package com.gtnhplanner.data.flowchart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.harness.GtnhFlowLoader;

/** Board groups (docs/design/groups.md): membership, nesting, hiding, fitting, colours and saves. */
class GroupsTest {

    private static Graph chart() {
        return GtnhFlowLoader.load("mk1")
            .graph();
    }

    private static BoardGroup group(final Graph g, final int x, final int y) {
        final BoardGroup b = new BoardGroup();
        b.setName(Groups.newName(g));
        b.setColor(Groups.newColor(g));
        b.setX(x);
        b.setY(y);
        g.boardGroups.put(b.getId(), b);
        return b;
    }

    @Test
    void membersNestHideAndUngroup() {
        final Graph g = chart();
        final List<Node> nodes = List.copyOf(g.getNodes());
        final BoardGroup outer = group(g, 0, 0), inner = group(g, 0, 0);
        assertEquals("light_blue", outer.colorTag());
        assertEquals("lime", inner.colorTag());
        assertTrue(Groups.setGroupOf(g, inner.getId(), outer.getId()));
        assertFalse(Groups.setGroupOf(g, outer.getId(), inner.getId()), "a group never goes inside itself");
        Groups.setGroupOf(g, nodes.get(0).id, inner.getId());
        Groups.setGroupOf(g, nodes.get(1).id, outer.getId());
        assertEquals(List.of(inner.getId(), outer.getId()), Groups.ancestors(g, nodes.get(0).id));
        assertEquals(Set.of(inner.getId(), nodes.get(0).id, nodes.get(1).id), Groups.descendants(g, outer.getId()));
        assertNull(Groups.hiddenBy(g, nodes.get(0).id));
        outer.setCollapsed(true);
        assertEquals(outer.getId(), Groups.hiddenBy(g, nodes.get(0).id));
        assertFalse(Groups.shows(g, inner));
        Groups.ungroup(g, inner.getId());
        assertEquals(outer.getId(), nodes.get(0).group, "ungrouped contents go to the group it sat in");
        assertEquals(outer.getId(), Groups.commonGroup(g, List.of(nodes.get(0).id, nodes.get(1).id)));
    }

    @Test
    void framesGrowToHoldMembers() {
        final Graph g = chart();
        final Node n = g.getNodes()
            .iterator()
            .next();
        n.x = 500;
        n.y = 300;
        final BoardGroup b = group(g, 400, 200);
        Groups.setGroupOf(g, n.id, b.getId());
        assertTrue(Groups.fit(g, id -> id.equals(n.id) ? new int[] { n.x, n.y, 320, 200 } : null));
        assertEquals(400, b.getX());
        assertEquals(200, b.getY());
        assertEquals(500 + 320 + BoardGroup.PAD - 400, b.getWidth());
        assertEquals(300 + 200 + BoardGroup.PAD - 200, b.getHeight());
        assertFalse(Groups.fit(g, id -> id.equals(n.id) ? new int[] { n.x, n.y, 320, 200 } : null));
    }

    @Test
    void coloursFollowTheWebsite() {
        // paperForBoardId on the website: the same 31 hash, abs, mod 8.
        assertEquals(BoardGroup.PAPERS.get(Math.abs("pocket-abc".hashCode()) % 8), BoardGroup.paperForId("pocket-abc"));
        final BoardGroup b = new BoardGroup();
        b.setTheme("midnight");
        assertEquals("blue", b.colorTag());
        b.setColor("pink");
        assertEquals("pink", b.colorTag());
    }

    @Test
    void groupsSurviveASaveAndDanglingMembersAreRepaired() {
        final Graph g = chart();
        final Node n = g.getNodes()
            .iterator()
            .next();
        final BoardGroup b = group(g, 10, 20);
        b.setWidth(400);
        b.setHeight(300);
        b.setCollapsed(true);
        b.setTheme("slate");
        b.setFfId("pocket-1");
        Groups.setGroupOf(g, n.id, b.getId());
        final Note note = new Note();
        g.notes.put(note.getId(), note);
        Groups.setGroupOf(g, note.getId(), b.getId());

        final Graph back = Serializer.decode(Serializer.encode(g));
        final BoardGroup r = back.boardGroups.get(b.getId());
        assertEquals(b.getName(), r.getName());
        assertEquals(400, r.getWidth());
        assertTrue(r.isCollapsed());
        assertEquals("slate", r.getTheme());
        assertEquals("pocket-1", r.webId());
        assertEquals(b.getId(), back.nodes.get(n.id).group);
        assertEquals(
            b.getId(),
            back.notes.get(note.getId())
                .getGroup());

        // A member of a group the save lost is on the board.
        g.boardGroups.clear();
        final Graph lost = Serializer.decode(Serializer.encode(g));
        assertNull(lost.nodes.get(n.id).group);
        assertNull(
            lost.notes.get(note.getId())
                .getGroup());
        assertTrue(lost.boardGroups.isEmpty());
        assertFalse(
            Serializer.encode(lost)
                .contains("boardGroups"));
        final UUID unused = UUID.randomUUID();
        assertNull(Groups.groupOf(lost, unused));
    }
}
