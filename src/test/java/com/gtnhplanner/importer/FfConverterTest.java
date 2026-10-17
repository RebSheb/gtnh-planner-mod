package com.gtnhplanner.importer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.importer.ImportReport.Kind;
import com.gtnhplanner.library.PlanExport;

/**
 * Whole FF plans converted against a game whose recipes are the plans' own, so every card matches. The expected
 * counts come from FF's rules applied to the fixtures by hand: cards (a shared machine's recipes each a node), wires
 * between cards (a buffer drawer's feeders wired to its takers), and drawers.
 */
class FfConverterTest {

    private static FfConverter.Result convert(final String json, final FakeGame game) {
        return FfImport.importFromText(json, game, game);
    }

    private static FfConverter.Result fixture(final String name) {
        return convert(FakeGame.fixture(name), new FakeGame());
    }

    private static List<Node> nodes(final Graph g, final String machine) {
        return g.getNodes()
            .stream()
            .filter(n -> n.machineName.equals(machine))
            .collect(Collectors.toList());
    }

    private static Node node(final Graph g, final String machine) {
        final List<Node> found = nodes(g, machine);
        assertEquals(1, found.size(), "one " + machine);
        return found.getFirst();
    }

    @Nullable
    private static Drawer drawer(final Graph g, final String key) {
        for (final Drawer d : g.getDrawers()) if (d.getResourceKey()
            .equals(key)) return d;
        return null;
    }

    private static List<Edge> edgesInto(final Graph g, final Node node, final int input) {
        final List<Edge> out = new ArrayList<>();
        for (final Edge e : g.getEdges()) if (e.targetNodeId.equals(node.id) && e.targetInputIndex == input) out.add(e);
        return out;
    }

    private static boolean has(final ImportReport report, final Kind kind, final String text) {
        return report.entries(kind)
            .stream()
            .anyMatch(
                e -> e.message()
                    .contains(text)
                    || e.subject()
                        .contains(text));
    }

    @Test
    void theSmallReactorPlan() {
        final FakeGame game = new FakeGame();
        final FfConverter.Result r = convert(FakeGame.fixture("high-amperage-reactor.json"), game);
        final Graph g = r.graph();
        assertEquals("Benzene", g.getName());
        assertEquals(1, g.nodes.size());
        assertEquals(0, g.edges.size());
        assertEquals(3, g.drawers.size());

        final Node lcr = node(g, "Large Chemical Reactor");
        assertEquals("UV", lcr.machineConfig.settings.get("voltage"));
        assertEquals(16, lcr.machineConfig.settings.get("amp"));
        assertEquals(true, lcr.machineConfig.settings.get("gt_multiblock"));
        assertEquals(1, lcr.machineConfig.getMachineCount());
        assertTrue(lcr.isMachineCountFixed(), "a Build plan runs its counts: they come over pinned");
        assertEquals("Large Chemical Reactor", game.machines.get(lcr.id));
        assertEquals(2, lcr.inputs.size(), "the circuit is no port");

        final Drawer hydrogen = drawer(g, "fluid:hydrogen");
        assertNotNull(hydrogen);
        assertEquals(Drawer.Kind.SOURCE, hydrogen.getKind());
        assertEquals("Hydrogen", hydrogen.getLabel());
        assertEquals(List.of(new Drawer.Link(lcr.id, game.port(lcr, false, "hydrogen"))), hydrogen.getLinks());
        assertEquals(Drawer.Rule.ANY, hydrogen.getRule(), "Build plans carry no rules");
        final Drawer water = drawer(g, "fluid:ic2distilledwater");
        assertNotNull(water);
        assertEquals(Drawer.Kind.PRODUCT, water.getKind());
        assertEquals(List.of(new Drawer.Link(lcr.id, game.port(lcr, true, "ic2distilledwater"))), water.getLinks());

        // FF's layout, one to one: the hydrogen drawer is leftmost and topmost, the card 180 FF px right of it.
        assertEquals(FfConverter.MARGIN, hydrogen.getX());
        assertEquals(FfConverter.MARGIN, hydrogen.getY());
        assertEquals(FfConverter.MARGIN + 180, lcr.x);
        assertEquals(FfConverter.MARGIN + 20, lcr.y);

        assertTrue(
            r.report()
                .entries(Kind.UNMATCHED)
                .isEmpty());
        assertTrue(has(r.report(), Kind.NOTE, "Build plan"));
        assertEquals(
            "1 card, 0 wires, 3 drawers",
            r.report()
                .summary());
    }

    @Test
    void aBufferDrawerWiredToCardsComesOverAsABuffer() {
        final FfConverter.Result r = fixture("pa-cell-loop-plan.json");
        final Graph g = r.graph();
        assertEquals(6, g.nodes.size());
        assertEquals(7, g.edges.size(), "7 wires between cards; the cell buffer keeps its own");
        assertEquals(4, g.drawers.size(), "water, hydrogen, oxygen and the cell buffer");
        final Drawer cells = drawer(g, "item:ic2:itemcellempty:0");
        assertNotNull(cells);
        assertEquals(Drawer.Kind.BUFFER, cells.getKind());
        assertEquals(
            2,
            cells.getLinks()
                .size(),
            "the two canners that empty cells feed it");
        assertEquals(
            2,
            cells.getTakers()
                .size(),
            "the electrolyzer and the acid canner draw from it");
        assertEquals(Drawer.Rule.ANY, cells.getRule(), "no older board enforced a buffer's rule");

        final FakeGame game = new FakeGame();
        final Node electrolyzer = node(g, "Electrolyzer");
        assertTrue(
            cells.holds(electrolyzer.id, game.port(electrolyzer, false, "ic2:itemcellempty"), true),
            "the electrolyzer's cell input draws from the buffer");
        for (final Edge e : g.getEdges()) {
            assertTrue(g.nodes.containsKey(e.sourceNodeId) && g.nodes.containsKey(e.targetNodeId));
        }
    }

    @Test
    void theTitaniumLine() {
        final FfConverter.Result r = fixture("titanium-line-chembath.slim.json");
        final Graph g = r.graph();
        assertEquals(10, g.nodes.size());
        assertEquals(14, g.edges.size());
        assertEquals(14, g.drawers.size(), "14 drawers, the magnesium buffer one of them");
        assertEquals(
            Drawer.Kind.BUFFER,
            g.getDrawers()
                .stream()
                .filter(Drawer::isBuffer)
                .findFirst()
                .orElseThrow()
                .getKind());

        final Node ebf = node(g, "Blast Furnace");
        assertEquals("kanthal", ebf.machineConfig.settings.get("coil"));
        assertEquals("HV", ebf.machineConfig.settings.get("voltage"));
        assertEquals(4, ebf.machineConfig.settings.get("amp"), "two hatches at 2 A");

        final Drawer iron = drawer(g, "item:gregtech:gt.metaitem.01:2032");
        assertNotNull(iron);
        assertEquals(Drawer.Kind.PRODUCT, iron.getKind(), "a byproduct is a product with no rule");
        assertEquals(Drawer.Rule.ANY, iron.getRule());
        assertEquals(
            3,
            iron.getLinks()
                .size(),
            "fed by the electrolyzer, the centrifuge and the macerator");
        for (final Drawer d : g.getDrawers()) {
            for (final Drawer.Held h : d.held()) {
                final Node n = g.nodes.get(
                    h.link()
                        .nodeId());
                assertNotNull(n);
                final var ports = h.input() ? n.inputs : n.outputs;
                assertEquals(
                    d.getResourceKey(),
                    new FakeGame().describe(
                        ports.get(
                            h.link()
                                .portIndex()))
                        .key(),
                    "a drawer links its own resource, on both sides of a buffer");
            }
        }
        assertTrue(
            r.report()
                .entries(Kind.UNMATCHED)
                .isEmpty());
    }

    @Test
    void aSharedMachineStaysOneCardWithItsRecipes() {
        final FfConverter.Result r = fixture("alumina-line-shared-reactor.json");
        final Graph g = r.graph();
        assertEquals(11, g.nodes.size(), "7 cards, one with 1 extra recipe and one with 3");
        assertEquals(12, g.edges.size());
        assertEquals(28, g.drawers.size(), "26 drawers and 2 water hatches");
        // The two shared machines, a reactor of four recipes and one of two, each one card at its place.
        final List<com.gtnhplanner.data.flowchart.MachineGroup> shared = g.getGroups()
            .stream()
            .filter(x -> x instanceof com.gtnhplanner.data.flowchart.MachineGroup)
            .map(x -> (com.gtnhplanner.data.flowchart.MachineGroup) x)
            .sorted(
                java.util.Comparator.comparingInt(
                    x -> x.getSections()
                        .size()))
            .collect(Collectors.toList());
        assertEquals(2, shared.size());
        assertEquals(
            2,
            shared.get(0)
                .getSections()
                .size());
        assertEquals(
            4,
            shared.get(1)
                .getSections()
                .size());
        for (final com.gtnhplanner.data.flowchart.MachineGroup m : shared) {
            final Node host = g.nodes.get(
                m.getSections()
                    .getFirst());
            for (final java.util.UUID id : m.getSections()) {
                assertEquals(host.x, g.nodes.get(id).x, "a section sits where its card does");
                assertEquals(host.y, g.nodes.get(id).y);
                assertFalse(
                    g.nodes.get(id)
                        .isMachineCountFixed(),
                    "the pin is the machine's, not a recipe's");
            }
        }
        assertTrue(has(r.report(), Kind.CONVERTED, "water hatch"));
        assertTrue(
            g.nodes.values()
                .stream()
                .anyMatch(n -> n.machineConfig.settings.containsKey("machine:itemPipeCasing")),
            "the reactors' pipe casings come across under the website's id");

        final FakeGame game = new FakeGame();
        final List<Node> reactors = nodes(g, "Large Chemical Reactor");
        assertEquals(6, reactors.size(), "two plain reactors, and the shared one's four recipes");
        // Section 2 of the shared reactor makes aluminium hydroxide for section 0: a wire between two of its recipes.
        final Node hydroxideUser = reactors.stream()
            .filter(n -> game.port(n, false, "gregtech:gt.metaitem.01@2698") >= 0)
            .findFirst()
            .orElseThrow();
        assertEquals(
            1,
            edgesInto(g, hydroxideUser, game.port(hydroxideUser, false, "gregtech:gt.metaitem.01@2698")).size());
        final Edge e = edgesInto(g, hydroxideUser, game.port(hydroxideUser, false, "gregtech:gt.metaitem.01@2698"))
            .getFirst();
        assertFalse(e.sourceNodeId.equals(e.targetNodeId));
        assertTrue(
            shared.get(1)
                .getSections()
                .containsAll(List.of(e.sourceNodeId, e.targetNodeId)),
            "both recipes on the shared reactor");

        final Node ebf = node(g, "Blast Furnace");
        assertEquals("naquadah_alloy", ebf.machineConfig.settings.get("coil"));
        assertEquals(2, ebf.machineConfig.getMachineCount(), "Build count kept as the starting count");

        final List<Drawer> waterHatches = g.getDrawers()
            .stream()
            .filter(
                d -> d.getResourceKey()
                    .equals("fluid:water") && d.getKind() == Drawer.Kind.SOURCE)
            .collect(Collectors.toList());
        assertEquals(2, waterHatches.size());
        assertEquals(
            3,
            waterHatches.stream()
                .mapToInt(
                    d -> d.getLinks()
                        .size())
                .sum(),
            "the mixer, and two recipes of the shared reactor");
    }

    @Test
    void aLegacyPlanWithoutHandles() {
        final FfConverter.Result r = fixture("biodiesel-demo.json");
        final Graph g = r.graph();
        assertEquals(7, g.nodes.size());
        assertEquals(7, g.edges.size());
        assertEquals(1, g.drawers.size());
        final FakeGame game = new FakeGame();
        final Node biodiesel = nodes(g, "Chemical Reactor").stream()
            .filter(n -> game.port(n, true, "biodiesel") >= 0)
            .findFirst()
            .orElseThrow();
        assertEquals(100.0, biodiesel.targetOutputRates.get(game.port(biodiesel, true, "biodiesel")));
        assertFalse(biodiesel.machineConfig.settings.containsKey("voltage"), "a DEMO tier is no tier");
        final Node electrolyzer = node(g, "Electrolyzer");
        assertEquals(
            Drawer.Kind.PRODUCT,
            g.getDrawers()
                .iterator()
                .next()
                .getKind());
        assertEquals(3, electrolyzer.machineConfig.getMachineCount());
    }

    @Test
    void solvePlansCarryRulesAndPins() {
        final JsonObject plan = new Gson().fromJson(FakeGame.fixture("high-amperage-reactor.json"), JsonObject.class);
        plan.addProperty("solveMode", true);
        plan.getAsJsonArray("nodes")
            .get(0)
            .getAsJsonObject()
            .addProperty("solvePin", 2);
        for (final var e : plan.getAsJsonArray("storages")) {
            final JsonObject s = e.getAsJsonObject();
            switch (s.get("resourceId")
                .getAsString()) {
                case "hydrogen" -> s.addProperty("targetPerSecond", -2000);
                case "oxygen" -> {
                    s.addProperty("targetPerSecond", -1000);
                    s.addProperty("targetMode", "at-most");
                }
                default -> s.addProperty("targetPerSecond", 100);
            }
        }
        final Graph g = convert(plan.toString(), new FakeGame()).graph();
        final Node lcr = g.getNodes()
            .iterator()
            .next();
        assertTrue(lcr.isMachineCountFixed());
        assertEquals(2, lcr.machineConfig.getMachineCount());
        assertEquals(Drawer.Rule.EXACTLY, drawer(g, "fluid:hydrogen").getRule());
        assertEquals(2000, drawer(g, "fluid:hydrogen").getRate());
        assertEquals(Drawer.Rule.AT_MOST, drawer(g, "fluid:oxygen").getRule());
        assertEquals(1000, drawer(g, "fluid:oxygen").getRate());
        assertEquals(Drawer.Rule.AT_LEAST, drawer(g, "fluid:ic2distilledwater").getRule());
        assertEquals(100, drawer(g, "fluid:ic2distilledwater").getRate());
    }

    @Test
    void aCardWithNoRecipeInGameIsLeftOutAndSaysWhy() {
        final FakeGame game = new FakeGame();
        game.missing.add("oracle:local-2.9.0-beta-2:gregtech:gt-recipe-largechemicalreactor:6db988ddcbfcf8e3");
        final FfConverter.Result r = convert(FakeGame.fixture("titanium-line-chembath.slim.json"), game);
        assertEquals(9, r.graph().nodes.size());
        final List<ImportReport.Entry> unmatched = r.report()
            .entries(Kind.UNMATCHED);
        assertEquals(1, unmatched.size());
        assertTrue(
            unmatched.getFirst()
                .subject()
                .startsWith("Large Chemical Reactor"));
        assertEquals(
            "not in this game",
            unmatched.getFirst()
                .message());
        assertTrue(has(r.report(), Kind.WIRE, "5 wires to cards that were left out"));
        assertTrue(
            r.report()
                .summary()
                .contains("1 left out"));
    }

    /** A mixer and a dryer, a power card, a switched-off card and a custom rate card supplying water. */
    private static final String SMALL = """
        {"name": "Mud", %s
         "recipes": [
           {"id": "mix", "name": "Mixer: Mud", "machineType": "Mixer", "durationTicks": 20, "eut": 8,
            "inputs": [{"kind": "item", "id": "a:dust", "amount": 1}, {"kind": "fluid", "id": "water", "amount": 1000}],
            "outputs": [{"kind": "fluid", "id": "mud", "amount": 1000}]},
           {"id": "dry", "name": "Dryer: Clay", "machineType": "Dryer", "durationTicks": 40, "eut": 8,
            "inputs": [{"kind": "fluid", "id": "mud", "amount": 500}],
            "outputs": [{"kind": "item", "id": "a:clay", "amount": 1}]},
           {"id": "gen", "name": "Gas Turbine", "machineType": "Gas Turbine", "durationTicks": 20, "eut": 0,
            "inputs": [], "outputs": [], "power": {"sourceId": "turbine", "euPerTick": 32, "stats": []}},
           {"id": "cr", "name": "Custom Rate: Water", "category": "custom-rate", "machineType": "Custom Rate",
            "durationTicks": 20, "eut": 0, "inputs": [],
            "outputs": [{"kind": "fluid", "id": "water", "amount": 50, "displayName": "Water"}]}],
         "nodes": [
           {"id": "m", "recipeId": "mix", "machineCount": 1, "parallel": 1, "overclockTier": "LV", "enabled": true,
            "position": {"x": 400, "y": 0}},
           {"id": "d", "recipeId": "dry", "machineCount": 1, "parallel": 1, "overclockTier": "LV", "enabled": true,
            "position": {"x": 800, "y": 0}},
           {"id": "g", "recipeId": "gen", "machineCount": 1, "parallel": 1, "overclockTier": "LV", "enabled": true,
            "position": {"x": 800, "y": 400}},
           {"id": "off", "recipeId": "dry", "machineCount": 1, "parallel": 1, "overclockTier": "LV", "enabled": false,
            "position": {"x": 800, "y": 800}},
           {"id": "c", "recipeId": "cr", "machineCount": 2, "parallel": 1, "overclockTier": "NONE", "enabled": true,
            "customRate": {"perSecond": 50, "mode": "supply"}, "position": {"x": 0, "y": 0}}],
         "edges": [%s],
         "storages": [
           {"id": "dust", "kind": "item", "resourceId": "a:dust", "displayName": "Dust", "targetPerSecond": -1,
            "position": {"x": 0, "y": 200}},
           {"id": "clay", "kind": "item", "resourceId": "a:clay", "displayName": "Clay", "targetPerSecond": 2,
            "position": {"x": 1200, "y": 0}}]}
        """;

    private static final String SMALL_WIRES = """
        {"id": "w1", "source": "c", "target": "m", "sourceHandle": "output:fluid:water", "targetHandle": "input:fluid:water",
         "resourceKind": "fluid", "resourceId": "water"},
        {"id": "w2", "source": "m", "target": "d", "sourceHandle": "output:fluid:mud", "targetHandle": "input:fluid:mud",
         "resourceKind": "fluid", "resourceId": "mud"},
        {"id": "w3", "source": "m", "target": "off", "resourceKind": "fluid", "resourceId": "mud"},
        {"id": "w4", "source": "dust", "target": "m", "resourceKind": "item", "resourceId": "a:dust"},
        {"id": "w5", "source": "d", "target": "clay", "resourceKind": "item", "resourceId": "a:clay"}""";

    @Test
    void customRateCardsBecomeDrawersAndPowerAndOffCardsGo() {
        final FfConverter.Result r = convert(SMALL.formatted("", SMALL_WIRES), new FakeGame());
        final Graph g = r.graph();
        assertEquals(2, g.nodes.size(), "the mixer and the dryer");
        assertEquals(1, g.edges.size(), "mud, mixer to dryer");
        assertEquals(3, g.drawers.size(), "dust, clay, and the custom rate card's water");
        final Drawer water = drawer(g, "fluid:water");
        assertNotNull(water);
        assertEquals(Drawer.Kind.SOURCE, water.getKind());
        assertEquals(Drawer.Rule.AT_MOST, water.getRule(), "a Build plan's custom rate supplies up to its rate");
        assertEquals(100, water.getRate(), "50/s on each of 2 machines");
        assertEquals(Drawer.Rule.ANY, drawer(g, "item:a:clay:0").getRule(), "Build: no drawer rules");
        // The stand-in game builds no generators; the real one rebuilds the card from its source (PowerCardTest).
        assertTrue(has(r.report(), Kind.UNMATCHED, "generator"));
        assertTrue(has(r.report(), Kind.DROPPED, "switched-off"));
        assertTrue(has(r.report(), Kind.CONVERTED, "source drawer for Water, at most 100/s"));
        assertTrue(has(r.report(), Kind.WIRE, "1 wire to cards that were left out"));
    }

    /** A mixer making oxygen cells, a tank (FF's Tank map) emptying them, and a dryer taking the oxygen. */
    private static final String CELLS = """
        {"name": "Cells", "solveMode": true,
         "recipes": [
           {"id": "mix", "name": "Mixer: Oxygen Cell", "machineType": "Mixer", "durationTicks": 20, "eut": 8,
            "inputs": [{"kind": "item", "id": "a:dust", "amount": 1}],
            "outputs": [{"kind": "item", "id": "a:ocell", "amount": 1}]},
           {"id": "dry", "name": "Dryer: Clay", "machineType": "Dryer", "durationTicks": 40, "eut": 8,
            "inputs": [{"kind": "fluid", "id": "oxygen", "amount": 500}],
            "outputs": [{"kind": "item", "id": "a:clay", "amount": 1}]},
           {"id": "tank", "name": "Tank: Oxygen", "machineType": "Tank", "durationTicks": 1, "eut": 0,
            "metadata": {"recipeMapId": "planner.tank"},
            "inputs": [{"kind": "item", "id": "a:ocell", "amount": 1}],
            "outputs": [{"kind": "fluid", "id": "oxygen", "amount": 1000}, {"kind": "item", "id": "a:cell", "amount": 1}]}],
         "nodes": [
           {"id": "m", "recipeId": "mix", "machineCount": 1, "parallel": 1, "overclockTier": "LV", "enabled": true,
            "position": {"x": 0, "y": 0}},
           {"id": "d", "recipeId": "dry", "machineCount": 1, "parallel": 1, "overclockTier": "LV", "enabled": true,
            "position": {"x": 800, "y": 0}}%s],
         "edges": [%s], "storages": []}
        """;

    @Test
    void theWebsitesTankIsAFluidTank() {
        final FakeGame game = new FakeGame();
        game.cells.put("a:ocell", new String[] { "oxygen", "1000", "a:cell" });
        final String tank = """
            ,{"id": "t", "recipeId": "tank", "machineCount": 1, "parallel": 1, "overclockTier": "NONE",
              "enabled": true, "position": {"x": 400, "y": 0}}""";
        final String wires = """
            {"id": "w1", "source": "m", "target": "t", "sourceHandle": "output:item:a%3Aocell",
             "targetHandle": "input:item:a%3Aocell", "resourceKind": "item", "resourceId": "a:ocell"},
            {"id": "w2", "source": "t", "target": "d", "sourceHandle": "output:fluid:oxygen",
             "targetHandle": "input:fluid:oxygen", "resourceKind": "fluid", "resourceId": "oxygen"}""";
        final Graph g = convert(CELLS.formatted(tank, wires), game).graph();
        final Node t = g.nodes.values()
            .stream()
            .filter(n -> com.gtnhplanner.power.FluidTank.ID.equals(n.powerSource))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no fluid tank on the board"));
        assertEquals(3, g.nodes.size(), "the mixer, the dryer and the tank: no Canner");
        assertEquals(2, g.edges.size(), "cells into the tank, oxygen out of it");
        assertFalse(t.isMachineCountFixed(), "free and instant: the plan sizes it");
        for (final Edge e : g.getEdges()) assertTrue(e.sourceNodeId.equals(t.id) || e.targetNodeId.equals(t.id));
    }

    @Test
    void aPinnedWebsiteTankIsNotPinnedHere() {
        // FF's Build plans pin every card at its count, tanks too: a count of 1 was 20 cells a second forced through
        // the plan, on a card that shows no count to clear.
        final FakeGame game = new FakeGame();
        game.cells.put("a:ocell", new String[] { "oxygen", "1000", "a:cell" });
        final String tank = """
            ,{"id": "t", "recipeId": "tank", "machineCount": 1, "solvePin": 1, "parallel": 1, "overclockTier": "NONE",
              "enabled": true, "position": {"x": 400, "y": 0}}""";
        final Graph g = convert(CELLS.formatted(tank, ""), game).graph();
        final Node t = g.nodes.values()
            .stream()
            .filter(n -> com.gtnhplanner.power.FluidTank.ID.equals(n.powerSource))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no fluid tank on the board"));
        assertFalse(t.isMachineCountFixed(), "its pin is dropped");
        // Nor does a pin set on one (an older save) hold it.
        t.setMachineCountFixed(true);
        assertFalse(t.isMachineCountFixed());
        assertTrue(
            com.gtnhplanner.data.flowchart.balancer.Unanchored.of(g)
                .contains(t.id),
            "with nothing else set, nothing sets it");
    }

    @Test
    void aLooseCellWireGetsAFluidTankBetween() {
        // FF wires the mixer's cells straight into the dryer's oxygen; its solver slips a free tank between.
        final FakeGame game = new FakeGame();
        game.cells.put("a:ocell", new String[] { "oxygen", "1000", "a:cell" });
        final String wire = """
            {"id": "w1", "source": "m", "target": "d", "sourceHandle": "output:item:a%3Aocell",
             "targetHandle": "input:fluid:oxygen", "resourceKind": "item", "resourceId": "a:ocell",
             "crossForm": {"litresPerCell": 1000}}""";
        final FfConverter.Result r = convert(CELLS.formatted("", wire), game);
        final Graph g = r.graph();
        assertEquals(3, g.nodes.size(), "the mixer, the dryer and a tank between them");
        assertEquals(2, g.edges.size());
        assertTrue(has(r.report(), Kind.CONVERTED, "a fluid tank put between them, emptying the cells"));
        // A game without tanks still says what it dropped.
        final FfConverter.Result none = convert(CELLS.formatted("", wire), new FakeGame());
        assertTrue(has(none.report(), Kind.WIRE, "loose cell wire"));
    }

    @Test
    void aBuildPlansGeneratorsRunAtTheirCount() {
        // Build runs every card at its count: a generator comes over pinned, as recipe cards do, not left for the solve
        // to size (which made one plan's 10 combustion engines 20.8).
        final FakeGame game = new FakeGame();
        game.generators = true;
        final Node built = power(
            convert(
                SMALL.formatted("", SMALL_WIRES)
                    .replace(
                        "\"recipeId\": \"gen\", \"machineCount\": 1",
                        "\"recipeId\": \"gen\", \"machineCount\": 10"),
                game).graph());
        assertEquals(10, built.machineConfig.getMachineCount());
        assertTrue(built.isMachineCountFixed());
        // In Solve, only a pin is a fixed count: an unpinned generator starts at its count.
        final Node solved = power(convert(SMALL.formatted("\"solveMode\": true,", SMALL_WIRES), game).graph());
        assertFalse(solved.isMachineCountFixed());
    }

    private static Node power(final Graph g) {
        for (final Node n : g.nodes.values()) if (n.isPower()) return n;
        throw new AssertionError("no generator on the board");
    }

    @Test
    void poolPlansWireEveryMakerToEveryUser() {
        // No wire between the mixer and the dryer: in a pool, sharing a resource is enough.
        final String wires = """
            {"id": "w4", "source": "dust", "target": "m", "resourceKind": "item", "resourceId": "a:dust"},
            {"id": "w5", "source": "d", "target": "clay", "resourceKind": "item", "resourceId": "a:clay"}""";
        final FfConverter.Result r = convert(SMALL.formatted("\"poolMode\": true,", wires), new FakeGame());
        final Graph g = r.graph();
        assertEquals(2, g.nodes.size());
        assertEquals(1, g.edges.size(), "mud runs from the mixer to the dryer");
        final Drawer clay = drawer(g, "item:a:clay:0");
        assertNotNull(clay);
        assertEquals(Drawer.Rule.AT_LEAST, clay.getRule(), "a Pool plan is a Solve plan: rules come over");
        assertEquals(2, clay.getRate());
        final Drawer dust = drawer(g, "item:a:dust:0");
        assertNotNull(dust);
        assertEquals(Drawer.Rule.EXACTLY, dust.getRule());
        assertEquals(1, dust.getRate());
        final Drawer water = drawer(g, "fluid:water");
        assertNotNull(water, "the custom rate card supplies the pool");
        assertEquals(Drawer.Rule.ANY, water.getRule(), "unpinned in Solve: the plan decides how much");
        assertTrue(has(r.report(), Kind.CONVERTED, "Pool plan"));
    }

    /**
     * A mixer between two text notes, one above-left of everything that comes over, and a box further out still, which
     * does not come over and so does not move anything.
     */
    private static final String NOTES = """
        {"name": "Notes",
         "recipes": [
           {"id": "mix", "name": "Mixer: Mud", "machineType": "Mixer", "durationTicks": 20, "eut": 8,
            "inputs": [{"kind": "item", "id": "a:dust", "amount": 1}],
            "outputs": [{"kind": "fluid", "id": "mud", "amount": 1000}]}],
         "nodes": [
           {"id": "m", "recipeId": "mix", "machineCount": 1, "parallel": 1, "overclockTier": "LV", "enabled": true,
            "position": {"x": 400, "y": 200}}],
         "edges": [],
         "annotations": [
           {"id": "t1", "kind": "text", "colorTag": "light_blue", "text": "Mud line\\nfeeds the dryer",
            "position": {"x": 0, "y": 0}, "size": {"width": 240, "height": 80}, "fontSize": 20},
           {"id": "t2", "kind": "text", "text": "small", "position": {"x": 760, "y": 400},
            "size": {"width": 100, "height": 40}},
           {"id": "b1", "kind": "box", "colorTag": "azure", "position": {"x": 200, "y": 100}, "size": {"width": 800, "height": 800},
            "style": {"border": "dashed", "fill": "dots", "borderColor": "white"}},
           {"id": "z1", "kind": "zone", "position": {"x": 100, "y": 100}, "size": {"width": 400, "height": 200},
            "points": [{"x": 0, "y": 0}, {"x": 400, "y": 0}, {"x": 400, "y": 200}], "style": {"fill": "paper"}},
           {"id": "a1", "kind": "arrow", "arrowDirection": "up-left", "colorTag": "scarlet", "position": {"x": 300, "y": 300},
            "size": {"width": 200, "height": 100}},
           {"id": "i1", "kind": "image", "text": "Hello", "imageUrl": "https://example.com/p.png",
            "position": {"x": 500, "y": 300}, "size": {"width": 360, "height": 280}}]}
        """;

    private static Note drawing(final Graph g, final String kind) {
        for (final Note n : g.getNotes()) if (kind.equals(n.kind())) return n;
        throw new AssertionError("no " + kind);
    }

    private static Note note(final Graph g, final String firstLine) {
        for (final Note n : g.getNotes()) if (!n.getText()
            .isEmpty() && n.getText()
                .getFirst()
                .equals(firstLine))
            return n;
        throw new AssertionError("no note starting " + firstLine);
    }

    @Test
    void portRatesBecomeTheCardsRates() {
        // Plan mode's pins by handle: the mud it makes and the dust it takes, a second.
        final String plan = NOTES.replaceFirst(
            "\"enabled\": true,\\s*\"position\": \\{\"x\": 400",
            "\"enabled\": true, \"portRates\": {\"output:fluid:mud\": 2.5, \"input:item:a%3Adust\": 4,"
                + " \"output:item:nothing\": 9}, \"position\": {\"x\": 400");
        assertTrue(plan.contains("portRates"), "the fixture took the rates");
        final FfConverter.Result r = convert(plan, new FakeGame());
        final Node mixer = node(r.graph(), "Mixer");
        assertEquals(java.util.Map.of(0, 2.5), mixer.targetOutputRates);
        assertEquals(java.util.Map.of(0, 4.0), mixer.targetInputRates);
        assertTrue(
            r.report()
                .entries(Kind.WIRE)
                .stream()
                .anyMatch(
                    e -> e.toString()
                        .contains("nothing")),
            "a rate on a port it lacks is reported");
    }

    @Test
    void textNotesBecomeBoardNotes() {
        final FfConverter.Result r = convert(NOTES, new FakeGame());
        final Graph g = r.graph();
        assertEquals(6, g.notes.size());

        // The first note is the plan's top-left corner, so it sits at the margin and the mixer moves out from it.
        final Note big = note(g, "Mud line");
        assertEquals(List.of("Mud line", "feeds the dryer"), big.getText());
        assertEquals(FfConverter.MARGIN, big.getX());
        assertEquals(FfConverter.MARGIN, big.getY());
        assertEquals(240, big.getWidth(), "240 FF px, one to one");
        assertEquals(80, big.getHeight());
        assertEquals("light_blue", big.getColor());
        assertEquals(20, big.getFontSize());

        final Node mixer = node(g, "Mixer");
        assertEquals(FfConverter.MARGIN + 400, mixer.x);
        assertEquals(FfConverter.MARGIN + 200, mixer.y);

        final Note small = note(g, "small");
        assertEquals(FfConverter.MARGIN + 760, small.getX());
        assertEquals(FfConverter.MARGIN + 400, small.getY());
        assertEquals(Note.MIN_W, small.getWidth(), "never smaller than a note gets");
        assertEquals(Note.MIN_H, small.getHeight());
        assertEquals(Note.DEFAULT_COLOR, small.colorTag(), "no colour is FF's yellow");
        assertEquals(Note.DEFAULT_FONT, small.fontSizeOrDefault());

        // The drawings come over as notes of a kind, placed and sized as on the website.
        final Note box = drawing(g, Note.BOX);
        assertEquals(FfConverter.MARGIN + 200, box.getX());
        assertEquals(800, box.getWidth());
        assertEquals(800, box.getHeight());
        assertEquals("azure", box.colorTag());
        assertEquals("dashed", box.borderStyle());
        assertEquals("white", box.getBorderColor());
        assertEquals("tint", box.fillStyle(), "a mark in the fill is a tint wearing it");
        assertEquals("dots", box.marksStyle());
        final Note zone = drawing(g, Note.ZONE);
        assertEquals("solid", zone.fillStyle(), "paper is solid");
        assertEquals(
            3,
            zone.getPoints()
                .size());
        assertArrayEquals(
            new int[] { 400, 200 },
            zone.getPoints()
                .get(2));
        assertEquals("", zone.getColor(), "no tag stays none");
        final Note arrow = drawing(g, Note.ARROW);
        assertEquals("up-left", arrow.getArrowDirection());
        assertEquals("scarlet", arrow.colorTag());
        final Note image = drawing(g, Note.IMAGE);
        assertEquals("https://example.com/p.png", image.getImageUrl());
        assertEquals(List.of("Hello"), image.getText());

        assertEquals(
            "1 card, 0 wires, 0 drawers, 6 notes",
            r.report()
                .summary());
    }

    @Test
    void notesGoToTheWebsiteAndBackUnchanged() {
        final FakeGame game = new FakeGame();
        final Graph first = convert(NOTES, game).graph();
        // The game's side of the export: each port names the FF resource it came from.
        final PlanExport.World world = new PlanExport.World() {

            @Override
            public PlanExport.Res port(final Port<?> port) {
                final NodeMaker.PortInfo info = game.describe(port);
                return new PlanExport.Res(info.kind(), info.label(), info.label());
            }

            @Override
            public PlanExport.Res resource(final String key) {
                return null;
            }

            @Override
            public String machine(final Node node) {
                return node.machineName;
            }

            @Override
            public double machines(final Node node) {
                return 1;
            }

            @Override
            public String tier(final Node node) {
                return "LV";
            }
        };
        final String posted = PlanExport.project(first, "Notes", world)
            .toString();
        final Graph back = convert(posted, game).graph();

        assertEquals(6, back.notes.size());
        for (final Note was : first.getNotes()) {
            if (was.isDrawing()) {
                final Note now = drawing(back, was.kind());
                assertEquals(was.getX(), now.getX(), was.kind() + " comes back where it went");
                assertEquals(was.getY(), now.getY());
                assertEquals(was.getWidth(), now.getWidth());
                assertEquals(was.getHeight(), now.getHeight());
                assertEquals(was.getColor(), now.getColor());
                assertEquals(was.getBorder(), now.getBorder());
                assertEquals(was.getFill(), now.getFill());
                assertEquals(was.getMarks(), now.getMarks());
                assertEquals(was.getBorderColor(), now.getBorderColor());
                assertEquals(was.getArrowDirection(), now.getArrowDirection());
                assertEquals(was.getImageUrl(), now.getImageUrl());
                assertEquals(was.getText(), now.getText());
                if (was.getPoints() != null) {
                    assertEquals(
                        was.getPoints()
                            .size(),
                        now.getPoints()
                            .size());
                    for (int i = 0; i < was.getPoints()
                        .size(); i++)
                        assertArrayEquals(
                            was.getPoints()
                                .get(i),
                            now.getPoints()
                                .get(i));
                }
                continue;
            }
            final Note now = note(
                back,
                was.getText()
                    .getFirst());
            assertEquals(was.getText(), now.getText());
            assertEquals(was.colorTag(), now.colorTag());
            assertEquals(was.fontSizeOrDefault(), now.fontSizeOrDefault());
            assertEquals(was.getX(), now.getX(), "the layout comes back as it went");
            assertEquals(was.getY(), now.getY());
        }
        final Note big = note(back, "Mud line");
        assertEquals(240, big.getWidth(), "a size in whole FF cells comes back as it went");
        assertEquals(80, big.getHeight());
        final Node mixer = node(back, "Mixer");
        assertEquals(node(first, "Mixer").x, mixer.x);
        assertEquals(node(first, "Mixer").y, mixer.y);
    }

    @Test
    void aPlanLinkImportsLikeItsJson() {
        final FfConverter.Result r = convert(FakeGame.fixture("high-amperage-reactor.link.txt"), new FakeGame());
        assertEquals(
            "Benzene",
            r.graph()
                .getName());
        assertEquals(
            "1 card, 0 wires, 3 drawers",
            r.report()
                .summary());
    }

    @Test
    void importedNodesAreDistinctAndWiredInsideTheGraph() {
        for (final String name : List.of(
            "high-amperage-reactor.json",
            "pa-cell-loop-plan.json",
            "titanium-line-chembath.slim.json",
            "alumina-line-shared-reactor.json",
            "biodiesel-demo.json")) {
            final Graph g = fixture(name).graph();
            final List<UUID> ids = new ArrayList<>(g.nodes.keySet());
            assertEquals(
                ids.size(),
                ids.stream()
                    .distinct()
                    .count(),
                name);
            for (final Edge e : g.getEdges()) {
                final Node from = g.nodes.get(e.sourceNodeId), to = g.nodes.get(e.targetNodeId);
                assertNotNull(from, name);
                assertNotNull(to, name);
                assertTrue(e.sourceOutputIndex >= 0 && e.sourceOutputIndex < from.outputs.size(), name);
                assertTrue(e.targetInputIndex >= 0 && e.targetInputIndex < to.inputs.size(), name);
                assertTrue(
                    from.outputs.get(e.sourceOutputIndex)
                        .canConnect(to.inputs.get(e.targetInputIndex)),
                    name + ": a wire joins one resource");
            }
            for (final Node n : g.getNodes()) {
                assertTrue(n.x >= FfConverter.MARGIN && n.y >= FfConverter.MARGIN, name);
                assertEquals(0, n.x % 10, name);
            }
        }
    }
}
