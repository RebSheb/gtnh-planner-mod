package com.gtnhplanner.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhplanner.data.flowchart.BoardGroup;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Groups;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.library.PlanExport;

/**
 * Groups travel both ways ({@code docs/design/groups.md}): the website's boards (open, collapsed, never opened) come in
 * as the board's groups with what was in them, and a plan's groups go out as the website's boards, members measured
 * from their frame, and come back the same.
 */
class GroupsRoundTripTest {

    private static FakeGame game() {
        final FakeGame game = new FakeGame();
        game.routed = true;
        game.generators = true;
        return game;
    }

    /** How many things each board holds, by board id. */
    private static Map<String, Integer> held(final FfPlan plan) {
        final Map<String, Integer> out = new TreeMap<>();
        for (final String board : plan.boardOf()
            .values()) out.merge(board, 1, Integer::sum);
        return out;
    }

    @Test
    void openBoardsFromAPostComeAlongWithWhatIsInThem() {
        final FfPlan plan = FfPlanParser.parse(FakeGame.fixture("early-baux-ti-w-boards.json"));
        assertEquals(
            3,
            plan.boards()
                .size());
        assertTrue(
            plan.boards()
                .stream()
                .allMatch(FfPlan.FfBoard::expanded));
        assertEquals(
            Map.of(
                "pocket-04cda9f2-2016-401e-8c15-de76200843bf",
                8,
                "pocket-94163ed2-df25-42ed-898d-fad0b40786a6",
                13,
                "pocket-d94ce1f2-0399-44f0-896b-3c81cb8600d1",
                32),
            held(plan));
    }

    @Test
    void collapsedBoardsStayCollapsed() {
        final FfPlan plan = FfPlanParser.parse(FakeGame.fixture("passive-chemicals-collapsed-boards.json"));
        assertEquals(
            4,
            plan.boards()
                .size());
        assertTrue(
            plan.boards()
                .stream()
                .noneMatch(FfPlan.FfBoard::expanded));
        assertEquals(
            List.of("Board 1", "Board 2", "Board 3", "Board 4"),
            plan.boards()
                .stream()
                .map(FfPlan.FfBoard::name)
                .toList());
        assertEquals(
            39,
            held(plan).values()
                .stream()
                .mapToInt(Integer::intValue)
                .sum());
    }

    @Test
    void aPlansGroupsGoOutAsBoardsAndComeBackTheSame() {
        final FakeGame first = game();
        final Graph g = FfImport.importFromText(FakeGame.fixture("titanium-line-posted-alpha5.json"), first, first)
            .graph();
        final List<Node> nodes = new ArrayList<>(g.getNodes());
        final BoardGroup outer = new BoardGroup(), inner = new BoardGroup(), shut = new BoardGroup();
        outer.setName("Outer");
        outer.setColor("lime");
        inner.setName("Inner");
        inner.setParent(outer.getId());
        shut.setName("Shut");
        shut.setCollapsed(true);
        shut.setColor("orange");
        for (final BoardGroup b : List.of(outer, inner, shut)) g.boardGroups.put(b.getId(), b);
        Groups.setGroupOf(g, nodes.get(0).id, outer.getId());
        Groups.setGroupOf(g, nodes.get(1).id, outer.getId());
        Groups.setGroupOf(g, nodes.get(2).id, inner.getId());
        Groups.setGroupOf(g, nodes.get(3).id, shut.getId());
        final Drawer drawer = g.getDrawers()
            .iterator()
            .next();
        drawer.setGroup(shut.getId());
        Groups.fit(g, id -> {
            final Node n = g.nodes.get(id);
            if (n != null) return new int[] { n.x, n.y, 320, 100 };
            final Drawer d = g.drawers.get(id);
            return d == null ? null : new int[] { d.getX(), d.getY(), 40, 40 };
        });

        final JsonObject posted = PlanExport.project(g, g.getName(), LibraryRoundTripTest.world(first, g));
        final Map<String, JsonObject> pockets = new HashMap<>();
        for (final JsonElement e : posted.getAsJsonArray("pockets")) pockets.put(
            e.getAsJsonObject()
                .get("name")
                .getAsString(),
            e.getAsJsonObject());
        assertEquals(3, pockets.size());
        assertEquals(
            outer.webId(),
            pockets.get("Inner")
                .get("parentPocketId")
                .getAsString());
        assertFalse(
            pockets.get("Shut")
                .get("expanded")
                .getAsBoolean());
        assertEquals(
            "orange",
            pockets.get("Shut")
                .get("colorTag")
                .getAsString());
        assertTrue(
            pockets.get("Outer")
                .has("size"));
        // A member's position is measured from its board's frame.
        for (final JsonElement e : posted.getAsJsonArray("nodes")) {
            final JsonObject n = e.getAsJsonObject();
            if (!n.has("pocketId")) continue;
            final JsonObject at = n.getAsJsonObject("position");
            assertTrue(
                at.get("x")
                    .getAsInt() >= 0
                    && at.get("y")
                        .getAsInt() >= 0,
                n.toString());
        }

        final FakeGame same = game();
        final Graph back = FfImport.importFromText(posted.toString(), same, same)
            .graph();
        final Map<String, BoardGroup> byName = new HashMap<>();
        for (final BoardGroup b : back.boardGroups.values()) byName.put(b.getName(), b);
        assertEquals(java.util.Set.of("Outer", "Inner", "Shut"), byName.keySet());
        assertEquals(
            byName.get("Outer")
                .getId(),
            byName.get("Inner")
                .getParent());
        assertTrue(
            byName.get("Shut")
                .isCollapsed());
        assertEquals(
            outer.webId(),
            byName.get("Outer")
                .webId(),
            "the website's id goes back out the same");
        final Map<String, Integer> count = new TreeMap<>();
        for (final Node n : back.getNodes()) if (n.group != null) count.merge(
            back.boardGroups.get(n.group)
                .getName(),
            1,
            Integer::sum);
        assertEquals(Map.of("Outer", 2, "Inner", 1, "Shut", 1), count);
        assertNotNull(
            back.getDrawers()
                .stream()
                .filter(d -> d.getGroup() != null)
                .findFirst()
                .orElse(null),
            "a drawer comes back in its group");
        // Each member sits inside its frame again.
        for (final Node n : back.getNodes()) {
            if (n.group == null) continue;
            final BoardGroup b = back.boardGroups.get(n.group);
            assertTrue(n.x >= b.getX() && n.y >= b.getY(), b.getName());
        }
        final UUID none = null;
        assertEquals(
            none,
            byName.get("Outer")
                .getParent());
    }
}
