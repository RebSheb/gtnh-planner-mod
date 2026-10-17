package com.gtnhplanner.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhplanner.data.Settings;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.importer.ImportReport.Kind;
import com.gtnhplanner.library.PlanExport;
import com.gtnhplanner.machines.CropsNh;
import com.gtnhplanner.machines.web.HandlerData;
import com.gtnhplanner.machines.web.Web;

/**
 * A plan shared to the library from the game and opened from it again (issues 488 and 489): what the game posts
 * (library/PlanExport) must be a plan the game's importer finds every card of, with its wires, drawers and settings.
 * The game looks recipes up only where {@code importer.game.NeiRecipeIndex} does ({@link FakeGame#routed}): before the
 * fix the posted recipes named neither their recipe map nor anything else the game could find them by, and every card
 * was left out, so the plan opened empty.
 */
class LibraryRoundTripTest {

    /** Issue 489's plan, as 0.2.0-alpha.5 posted it: 19 cards, none of whose recipes names its map. */
    private static final String POSTED_ALPHA5 = "titanium-line-posted-alpha5.json";

    private static FakeGame game() {
        final FakeGame game = new FakeGame();
        game.routed = true;
        game.generators = true;
        return game;
    }

    private static FfConverter.Result open(final String json, final FakeGame game) {
        return FfImport.importFromText(json, game, game);
    }

    @Test
    void aPlanPostedBeforeTheFixOpensWithEveryCard() {
        final FakeGame game = game();
        final FfConverter.Result r = open(FakeGame.fixture(POSTED_ALPHA5), game);
        final Graph g = r.graph();
        assertEquals(
            List.of(),
            r.report()
                .entries(Kind.UNMATCHED),
            "no card left out");
        assertEquals(19, g.nodes.size(), "all 19 cards");
        assertEquals(
            "19 cards, 26 wires, 36 drawers",
            r.report()
                .summary());
        for (final String path : game.paths.values())
            assertEquals("machine", path, "found by the machine block the card named, as the game finds it");

        // Its multiblocks (the cards with hatches) come back as multiblocks at their hatches, its singles at their
        // tier.
        final Node ebf = only(g, "Electric Blast Furnace");
        assertEquals(Boolean.TRUE, ebf.machineConfig.settings.get(Settings.GT_MULTIBLOCK.key()));
        assertEquals("IV", ebf.machineConfig.settings.get(Settings.VOLTAGE.key()));
        assertEquals(1, ebf.machineConfig.settings.get(Settings.AMP.key()));
        assertEquals("hss_s", ebf.machineConfig.settings.get(FfSettings.COIL));
        assertEquals("electric-blast-furnace", game.handlerIds.get(ebf.id));
        final Node heater = only(g, "Advanced Fluid Heater II");
        assertNull(heater.machineConfig.settings.get(Settings.GT_MULTIBLOCK.key()));
        assertEquals("HV", heater.machineConfig.settings.get(Settings.VOLTAGE.key()));
        assertEquals("Advanced Fluid Heater II", game.machines.get(heater.id), "the block it named is its machine");
        // The two pinned ore lines stay pinned, everything else is the solve's.
        int pinned = 0;
        for (final Node n : g.getNodes()) if (n.isMachineCountFixed()) pinned++;
        assertEquals(2, pinned);
    }

    @Test
    void aPlanSharedToTheLibraryComesBackTheSame() throws IOException {
        final FakeGame first = game();
        final Graph g = open(FakeGame.fixture(POSTED_ALPHA5), first).graph();
        final JsonObject posted = PlanExport.project(g, g.getName(), world(first, g));
        final Path out = Path.of("build", "tmp", "gtnhplanner-export-roundtrip.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, posted.toString(), StandardCharsets.UTF_8);

        // Every recipe names itself twice over: NEI's own id (this game finds the very recipe), and its recipe map with
        // the machines that run it, as the website's own GregTech recipes do (another pack version, and the website).
        for (final JsonElement e : posted.getAsJsonArray("recipes")) {
            final JsonObject r = e.getAsJsonObject();
            final JsonObject meta = r.getAsJsonObject("metadata");
            assertNotNull(meta, r.toString());
            assertTrue(meta.has("neiRecipe"), r.toString());
            assertEquals(
                "gregtech",
                r.get("category")
                    .getAsString());
            final HandlerData.MapEntry map = HandlerData.map(
                meta.get("recipeMapId")
                    .getAsString());
            assertNotNull(map, r.toString());
            assertEquals(
                map.name,
                r.get("machineType")
                    .getAsString(),
                "the website's machine type is the map's name");
            assertEquals(
                map.name,
                r.getAsJsonObject("source")
                    .get("recipeMap")
                    .getAsString());
            assertEquals(map.handlersOnRecipes, r.has("machineHandlers"), map.id);
            assertTrue(r.has("runtimeCalculation"));
            assertTrue(
                r.getAsJsonObject("nei")
                    .getAsJsonArray("additionalInfo")
                    .size() > 0,
                "the website reads a recipe's special value (a blast furnace's heat) from NEI's lines alone");
        }

        final FakeGame same = game();
        final FfConverter.Result back = open(posted.toString(), same);
        assertEquals(
            List.of(),
            back.report()
                .entries(Kind.UNMATCHED));
        for (final String path : same.paths.values()) assertEquals("nei", path, "this game finds the very recipe");
        assertSameBoard(g, first, back.graph(), same);

        // A game of another pack version, where NEI's ids find nothing: each recipe by the map it names.
        final FakeGame other = game();
        other.otherPack = true;
        final FfConverter.Result there = open(posted.toString(), other);
        assertEquals(
            List.of(),
            there.report()
                .entries(Kind.UNMATCHED));
        for (final String path : other.paths.values()) assertTrue(path.startsWith("map:"), path);
        assertSameBoard(g, first, there.graph(), other);
    }

    /**
     * Every kind of card, from a plan made on the website: singles, multiblocks, a coil, a modelled machine, a
     * generator, a crop.
     */
    private static final String KINDS = """
        {"schemaVersion":1,"id":"kinds","name":"Every kind","solveMode":true,
         "recipes":[
          {"id":"cr","name":"Chemical Reactor: Polyethylene","kind":"gregtech_machine","category":"gregtech",
           "machineType":"Chemical Reactor","minimumTier":"LV","durationTicks":160,"eut":30,
           "inputs":[{"kind":"fluid","id":"ethylene","amount":144},{"kind":"fluid","id":"oxygen","amount":1000}],
           "outputs":[{"kind":"fluid","id":"molten.polyethylene","amount":144}],
           "machineHandlers":[{"id":"chemical-reactor","label":"Chemical Reactor","kind":"single",
             "machineType":"Chemical Reactor","minimumTier":"LV"}],
           "metadata":{"recipeMapId":"gt.recipe.chemicalreactor"}},
          {"id":"lcr","name":"Large Chemical Reactor: Polyethylene Sheet","kind":"gregtech_machine","category":"gregtech",
           "machineType":"Large Chemical Reactor","minimumTier":"LV","durationTicks":20,"eut":30,
           "inputs":[{"kind":"fluid","id":"molten.polyethylene","amount":144}],
           "outputs":[{"kind":"item","id":"gregtech:gt.metaitem.01@17874","amount":1}],
           "machineHandlers":[{"id":"large-chemical-reactor","label":"Large Chemical Reactor","kind":"multiblock",
             "machineType":"Large Chemical Reactor","minimumTier":"LV"}],
           "metadata":{"recipeMapId":"gt.recipe.largechemicalreactor"}},
          {"id":"ebf","name":"Blast Furnace: Hot Titanium Ingot","kind":"gregtech_machine","category":"gregtech",
           "machineType":"Blast Furnace","minimumTier":"EV","durationTicks":800,"eut":480,"specialValue":1941,
           "inputs":[{"kind":"item","id":"gregtech:gt.metaitem.01@2028","amount":1}],
           "outputs":[{"kind":"item","id":"gregtech:gt.metaitem.01@12028","amount":1}],
           "machineHandlers":[{"id":"electric-blast-furnace","label":"Electric Blast Furnace","kind":"multiblock",
             "machineType":"Electric Blast Furnace","minimumTier":"EV"}],
           "metadata":{"recipeMapId":"gt.recipe.blastfurnace"}},
          {"id":"tgs","name":"Tree Growth Simulator: Oak Wood","kind":"gregtech_machine","category":"gregtech",
           "machineType":"Tree Growth Simulator","minimumTier":"LV","durationTicks":100,"eut":30,
           "inputs":[],"outputs":[{"kind":"item","id":"minecraft:log","amount":5}],
           "metadata":{"recipeMapId":"gtpp.recipe.treefarm"}},
          {"id":"turbine","name":"Gas Turbine","kind":"custom","category":"power-source","machineType":"Gas Turbine",
           "minimumTier":"NONE","durationTicks":20,"eut":0,"inputs":[],"outputs":[],
           "power":{"sourceId":"gas-turbine","euPerTick":32,"stats":[]}},
          {"id":"beet","name":"Crop Farm: Sugar Beet","kind":"crop_produce","category":"cropsnh-crop",
           "machineType":"Crop Farm","minimumTier":"NONE","durationTicks":1024,"eut":0,"inputs":[],
           "outputs":[{"kind":"item","id":"cropsnh:berry@1","amount":2}],
           "source":{"recipeMap":"Crop Farm","rawRecipeId":"cropsnh:cropsnh:sugarBeet"}}],
         "nodes":[
          {"id":"n-cr","recipeId":"cr","machineCount":1,"parallel":1,"overclockTier":"HV","enabled":true,
           "machineHandlerId":"chemical-reactor","position":{"x":0,"y":0}},
          {"id":"n-lcr","recipeId":"lcr","machineCount":2,"parallel":1,"overclockTier":"EV","enabled":true,
           "hatchVoltageTier":"EV","hatchAmps":4,"powerInputMode":"amps","machineHandlerId":"large-chemical-reactor",
           "solvePin":2,"position":{"x":500,"y":0}},
          {"id":"n-ebf","recipeId":"ebf","machineCount":1,"parallel":1,"overclockTier":"IV","enabled":true,
           "hatchVoltageTier":"IV","hatchAmps":2,"powerInputMode":"amps","machineHandlerId":"electric-blast-furnace",
           "coilTier":"kanthal","position":{"x":1000,"y":0}},
          {"id":"n-tgs","recipeId":"tgs","machineCount":1,"parallel":1,"overclockTier":"LV","enabled":true,
           "machineHandlerId":"tree-growth-simulator","machineConfigTiers":{"tgsLogTool":"log:chainsaw","tgsSaplings":"3"},
           "position":{"x":0,"y":600}},
          {"id":"n-turbine","recipeId":"turbine","machineCount":3,"parallel":1,"overclockTier":"LV","enabled":true,
           "machineConfigTiers":{"tier":"LV","fuel":"Benzene"},"solvePin":3,"position":{"x":500,"y":600}},
          {"id":"n-beet","recipeId":"beet","machineCount":225,"parallel":1,"overclockTier":"NONE","enabled":true,
           "machineHandlerId":"crop-industrial-farm","machineConfigTiers":{"cropBiome":"humid","cropSeedBedTier":"4"},
           "solvePin":225,"position":{"x":1000,"y":600}}],
         "storages":[
          {"id":"s-eth","kind":"fluid","resourceId":"ethylene","position":{"x":-400,"y":0}},
          {"id":"s-sheet","kind":"item","resourceId":"gregtech:gt.metaitem.01@17874","drainMode":"product",
           "targetPerSecond":1,"targetMode":"at-least","position":{"x":800,"y":200}}],
         "edges":[
          {"id":"e1","source":"n-cr","target":"n-lcr","sourceHandle":"output:fluid:molten.polyethylene",
           "targetHandle":"input:fluid:molten.polyethylene","resourceKind":"fluid","resourceId":"molten.polyethylene"},
          {"id":"e2","source":"s-eth","target":"n-cr","sourceHandle":"output:fluid:ethylene",
           "targetHandle":"input:fluid:ethylene","resourceKind":"fluid","resourceId":"ethylene"},
          {"id":"e3","source":"n-lcr","target":"s-sheet","sourceHandle":"output:item:gregtech%3Agt.metaitem.01%4017874",
           "targetHandle":"input:item:gregtech%3Agt.metaitem.01%4017874","resourceKind":"item",
           "resourceId":"gregtech:gt.metaitem.01@17874"}],
         "fuelProfiles":[]}
        """;

    @Test
    void everyKindOfCardMakesTheTrip() {
        final FakeGame first = game();
        final FfConverter.Result made = open(KINDS, first);
        assertEquals(
            List.of(),
            made.report()
                .entries(Kind.UNMATCHED));
        final Graph g = made.graph();
        assertEquals(6, g.nodes.size());
        final JsonObject posted = PlanExport.project(g, g.getName(), world(first, g));

        final FakeGame same = game();
        final FfConverter.Result back = open(posted.toString(), same);
        assertEquals(
            List.of(),
            back.report()
                .entries(Kind.UNMATCHED));
        assertEquals(
            made.report()
                .summary(),
            back.report()
                .summary());
        assertSameBoard(g, first, back.graph(), same);

        final FakeGame other = game();
        other.otherPack = true;
        assertSameBoard(g, first, open(posted.toString(), other).graph(), other);
    }

    @Test
    void aBufferAndItsRuleGoToTheLibraryAndComeBack() {
        final FakeGame game = new FakeGame();
        final Graph g = open(FakeGame.fixture("pa-cell-loop-plan.json"), game).graph();
        final Drawer cells = bufferOf(g, "item:ic2:itemcellempty:0");
        assertNotNull(cells);
        cells.setTarget(Drawer.Rule.AT_MOST, 3);
        final JsonObject posted = PlanExport.project(g, "Round trip", world(game, g));
        assertTrue(
            posted.get("planMode")
                .getAsBoolean(),
            "a posted plan is a one-mode plan");
        final Graph back = open(posted.toString(), new FakeGame()).graph();
        final Drawer again = bufferOf(back, "item:ic2:itemcellempty:0");
        assertNotNull(again);
        assertEquals(Drawer.Kind.BUFFER, again.getKind());
        assertEquals(
            2,
            again.getLinks()
                .size());
        assertEquals(
            2,
            again.getTakers()
                .size());
        assertEquals(Drawer.Rule.AT_MOST, again.getRule(), "a one-mode plan's buffer rule is live");
        assertEquals(3, again.getRate(), 1e-9);
        assertEquals(g.edges.size(), back.edges.size());
    }

    private static Drawer bufferOf(final Graph g, final String key) {
        for (final Drawer d : g.getDrawers()) if (d.getResourceKey()
            .equals(key)) return d;
        return null;
    }

    // region The game's side of the export, over FakeGame's cards

    /**
     * What the game tells the export, for a FakeGame board: each port names the FF resource it came from; a GregTech
     * card's recipe is on the website's map for its machine (machines/web/HandlerData, as WebCards builds it in game);
     * every card but a generator has NEI's id for its recipe; a crop card is the crop it makes.
     */
    static PlanExport.World world(final FakeGame game, final Graph g) {
        final Map<String, PlanExport.Res> byKey = new HashMap<>();
        for (final Node n : g.getNodes()) {
            final List<Port<?>> ports = new ArrayList<>(n.inputs);
            ports.addAll(n.outputs);
            for (final Port<?> p : ports) {
                final NodeMaker.PortInfo info = game.describe(p);
                byKey.put(info.key(), new PlanExport.Res(info.kind(), info.label(), info.label()));
            }
        }
        return new PlanExport.World() {

            @Override
            public PlanExport.Res port(final Port<?> port) {
                final NodeMaker.PortInfo info = game.describe(port);
                return new PlanExport.Res(info.kind(), info.label(), info.label());
            }

            @Override
            public PlanExport.Res resource(final String key) {
                return byKey.get(key);
            }

            @Override
            public String machine(final Node node) {
                return node.machineName;
            }

            @Override
            public double machines(final Node node) {
                return node.machineConfig.getMachineCount();
            }

            @Override
            public String tier(final Node node) {
                return node.machineConfig.settings.get(Settings.VOLTAGE.key()) instanceof final String t ? t : "LV";
            }

            @Override
            public String handlerId(final Node node) {
                return game.handlerIds.get(node.id);
            }

            @Override
            public PlanExport.Crop crop(final Node node) {
                return "Crop Farm".equals(node.machineName) ? new PlanExport.Crop("cropsnh:sugarBeet", BEET) : null;
            }

            @Override
            public Web.Recipe gregtech(final Node node) {
                if (node.isPower() || crop(node) != null) return null;
                return siteRecipe(node, game.handlerIds.get(node.id));
            }

            @Override
            public JsonObject neiRecipe(final Node node) {
                if (node.isPower()) return null;
                final JsonObject id = new JsonObject();
                id.addProperty("handlerName", node.machineName);
                id.addProperty("card", node.id.toString());
                return id;
            }
        };
    }

    private static final CropsNh.Stats BEET = new CropsNh.Stats(
        4,
        450,
        0.81450623,
        256,
        1,
        false,
        2,
        null,
        List.of(new CropsNh.Drop("cropsnh:berry@1", 1, 10000)));

    /** A card's recipe as WebCards builds it in game, on the website's map that lists the card's machine. */
    @Nullable
    private static Web.Recipe siteRecipe(final Node node, @Nullable final String handlerId) {
        final List<HandlerData.MapEntry> maps = new ArrayList<>(
            HandlerData.maps()
                .values());
        maps.sort(Comparator.comparing(m -> m.id));
        HandlerData.MapEntry entry = null;
        for (final HandlerData.MapEntry m : maps) if (lists(m, handlerId)) {
            entry = m;
            break;
        }
        if (entry == null) return null;
        final Web.Recipe r = new Web.Recipe();
        r.id = entry.id + ":" + node.id;
        r.name = node.machineName;
        r.kind = "gregtech_machine";
        r.category = "gregtech";
        r.machineType = entry.name;
        r.durationTicks = node.properties.get(FakeGame.DURATION) instanceof final Integer d ? d : 1;
        r.eut = node.properties.get(FakeGame.EU_PER_TICK) instanceof final Long e ? e : 0;
        r.minimumTier = HandlerData.recipeMinimumTier(r.eut);
        r.specialValue = 0.0;
        r.nei = new Web.Nei();
        r.nei.additionalInfo = List.of("Special value: 0");
        r.source = new Web.Source();
        r.source.recipeMap = entry.name;
        r.metadata = new JsonObject();
        r.metadata.addProperty("recipeMapId", entry.id);
        r.metadata.addProperty("specialValue", 0);
        r.machineConfigControls = HandlerData.recipeControls(entry, 0);
        r.machineHandlers = HandlerData
            .handlers(entry, r.minimumTier, r.durationTicks, r.eut, r.machineConfigControls);
        final Web.RuntimeCalculation calc = new Web.RuntimeCalculation();
        calc.status = "computed";
        calc.oracleEligible = true;
        calc.strict = true;
        final Web.RuntimeVariant v = new Web.RuntimeVariant();
        v.id = "tier-" + r.minimumTier.toLowerCase(java.util.Locale.ROOT);
        v.overclockTier = r.minimumTier;
        v.durationTicks = r.durationTicks;
        v.eut = r.eut;
        v.parallel = 1.0;
        v.outputs = List.of();
        calc.variants = List.of(v);
        r.runtimeCalculation = calc;
        return r;
    }

    private static boolean lists(final HandlerData.MapEntry m, @Nullable final String handlerId) {
        if (handlerId == null) return false;
        if (m.handlers != null) for (final HandlerData.Template t : m.handlers) if (handlerId.equals(t.id)) return true;
        if (m.app != null) for (final HandlerData.AppHandler a : m.app) if (handlerId.equals(a.id)) return true;
        return false;
    }

    // endregion

    // region Comparing boards

    /** The same cards (ports, machine, settings, count, pin, place), wires and drawers, card ids aside. */
    private static void assertSameBoard(final Graph a, final FakeGame ga, final Graph b, final FakeGame gb) {
        assertEquals(cards(a, ga), cards(b, gb));
        assertEquals(wires(a, ga), wires(b, gb));
        assertEquals(drawers(a, ga), drawers(b, gb));
        assertFalse(cards(a, ga).isEmpty());
    }

    /** A card by where it sits and what it makes, which stay across the trip while its id does not. */
    private static String key(final Node n, final FakeGame game) {
        return n.x + "," + n.y + " " + ports(n.outputs, game);
    }

    private static String ports(final List<Port<?>> ports, final FakeGame game) {
        final List<String> out = new ArrayList<>();
        for (final Port<?> p : ports) out.add(
            game.describe(p)
                .label() + "x"
                + p.amount());
        return out.toString();
    }

    private static Map<String, String> cards(final Graph g, final FakeGame game) {
        final Map<String, String> out = new TreeMap<>();
        for (final Node n : g.getNodes()) {
            final Map<String, Object> settings = new TreeMap<>();
            for (final Map.Entry<String, Object> s : n.machineConfig.settings.entrySet()) {
                // Unsaid is a single block, as on a card.
                if (s.getKey()
                    .equals(Settings.GT_MULTIBLOCK.key()) && Boolean.FALSE.equals(s.getValue())) continue;
                settings.put(s.getKey(), s.getValue());
            }
            out.put(
                key(n, game),
                "in " + ports(n.inputs, game)
                    + " machine "
                    + (n.isPower() ? n.powerSource + " " + new TreeMap<>(n.powerSettings) : game.handlerIds.get(n.id))
                    + " settings "
                    + settings
                    + " count "
                    + n.machineConfig.getMachineCount()
                    + (n.isMachineCountFixed() ? " pinned" : ""));
        }
        return out;
    }

    private static TreeSet<String> wires(final Graph g, final FakeGame game) {
        final TreeSet<String> out = new TreeSet<>();
        for (final Edge e : g.getEdges()) {
            final Node from = g.nodes.get(e.sourceNodeId), to = g.nodes.get(e.targetNodeId);
            out.add(
                key(from, game) + " -> "
                    + key(to, game)
                    + " : "
                    + game.describe(from.outputs.get(e.sourceOutputIndex))
                        .label());
        }
        return out;
    }

    private static TreeSet<String> drawers(final Graph g, final FakeGame game) {
        final TreeSet<String> out = new TreeSet<>();
        for (final Drawer d : g.getDrawers()) {
            final TreeSet<String> links = new TreeSet<>();
            for (final Drawer.Link l : d.getLinks()) {
                final Node n = g.nodes.get(l.nodeId());
                final List<Port<?>> side = d.getKind()
                    .linksInputs() ? n.inputs : n.outputs;
                links.add(
                    key(n, game) + " port "
                        + game.describe(side.get(l.portIndex()))
                            .label());
            }
            out.add(d.getKind() + " " + d.getResourceKey() + " " + d.getRule() + " " + d.getRate() + " " + links);
        }
        return out;
    }

    /** The one card running on a machine block. */
    private static Node only(final Graph g, final String machine) {
        Node found = null;
        for (final Node n : g.getNodes()) if (machine.equals(n.machineName)) {
            assertNull(found, "one " + machine);
            found = n;
        }
        assertNotNull(found, machine);
        return found;
    }

    // endregion
}
