package com.gtnhplanner.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.library.PlanExport;

/**
 * Issue 496: the blast furnace's steel recipe takes Coal Dust or, in the same slot, Carbon or Charcoal Dust. The
 * website keeps the card's pick in {@code recipeInputOverrides} (by the recipe's input index); it comes in as the
 * card's pick, and goes out the same way.
 */
class InputPickImportTest {

    private static final String COAL = "gregtech:gt.metaitem.01@2535", CARBON = "gregtech:gt.metaitem.01@2010",
        CHARCOAL = "gregtech:gt.metaitem.01@2536";

    /** The issue's plan, the slot listing its alternatives, set to Carbon Dust and fed it from a drawer. */
    private static final String PLAN = """
        {"schemaVersion":1,"id":"p","name":"Steel","solveMode":true,
         "recipes":[{"id":"ebf","name":"Steel Ingot","kind":"gregtech_machine","category":"gregtech",
           "machineType":"Electric Blast Furnace","minimumTier":"MV","durationTicks":500,"eut":120,
           "inputs":[{"kind":"item","id":"gregtech:gt.metaitem.01@2032","amount":1,"displayName":"Iron Dust"},
             {"kind":"item","id":"%COAL%","amount":1,"displayName":"Coal Dust",
              "alternatives":[{"kind":"item","id":"%CARBON%","displayName":"Carbon Dust"},
                {"kind":"item","id":"%CHARCOAL%","displayName":"Charcoal Dust"}]},
             {"kind":"fluid","id":"oxygen","amount":1000,"displayName":"Oxygen"}],
           "outputs":[{"kind":"item","id":"gregtech:gt.metaitem.01@11305","amount":1,"displayName":"Steel Ingot"}]}],
         "nodes":[{"id":"n1","recipeId":"ebf","machineCount":1,"parallel":1,"overclockTier":"MV","enabled":true,
           "recipeInputOverrides":{"1":{"kind":"item","id":"%CARBON%","amount":1,"displayName":"Carbon Dust"}},
           "position":{"x":0,"y":0}}],
         "storages":[{"id":"s1","kind":"item","resourceId":"%CARBON%","displayName":"Carbon Dust",
           "position":{"x":-300,"y":0}}],
         "annotations":[],"pockets":[],
         "edges":[{"id":"e1","source":"s1","target":"n1","sourceHandle":"output:item:x",
           "targetHandle":"input:item:gregtech%3Agt.metaitem.01%402010",
           "resourceKind":"item","resourceId":"%CARBON%"}],
         "fuelProfiles":[]}
        """.replace("%COAL%", COAL)
        .replace("%CARBON%", CARBON)
        .replace("%CHARCOAL%", CHARCOAL);

    private static String held(final FakeGame game, final Port<?> port) {
        return game.describe(port)
            .ids()
            .getFirst();
    }

    @Test
    void theWebsitesPickComesInAsTheCardsPick() {
        final FakeGame game = new FakeGame();
        final Graph g = FfImport.importFromText(PLAN, game, game)
            .graph();
        final Node ebf = g.getNodes()
            .iterator()
            .next();
        final int coal = game.port(ebf, false, CARBON);
        assertEquals(1, coal, "the slot's port holds Carbon Dust");
        assertEquals(Map.of(1, "item|" + CARBON), ebf.inputPicks);
        assertEquals(
            3,
            ebf.inputs.get(1)
                .getAlternatives()
                .size());
        final Drawer source = g.getDrawers()
            .iterator()
            .next();
        assertTrue(source.isLinked(ebf.id, 1), "the carbon drawer feeds it");
    }

    @Test
    void aPickGoesOutAsTheWebsitesOverrideAndComesBack() {
        final FakeGame game = new FakeGame();
        final Graph first = FfImport.importFromText(PLAN, game, game)
            .graph();
        final PlanExport.World world = new PlanExport.World() {

            @Override
            public PlanExport.Res port(final Port<?> port) {
                final NodeMaker.PortInfo info = game.describe(port);
                return new PlanExport.Res(info.kind(), info.label(), info.label());
            }

            @Override
            public PlanExport.Res resource(final String key) {
                return new PlanExport.Res("item", CARBON, "Carbon Dust");
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
                return "MV";
            }
        };
        final JsonObject posted = PlanExport.project(first, "Steel", world);

        final JsonObject recipe = posted.getAsJsonArray("recipes")
            .get(0)
            .getAsJsonObject();
        final JsonObject slot = recipe.getAsJsonArray("inputs")
            .get(1)
            .getAsJsonObject();
        assertEquals(
            COAL,
            slot.get("id")
                .getAsString(),
            "the recipe keeps its own item");
        final JsonArray alternatives = slot.getAsJsonArray("alternatives");
        assertNotNull(alternatives);
        assertEquals(2, alternatives.size());
        final JsonObject node = posted.getAsJsonArray("nodes")
            .get(0)
            .getAsJsonObject();
        final JsonObject pick = node.getAsJsonObject("recipeInputOverrides")
            .getAsJsonObject("1");
        assertEquals(
            CARBON,
            pick.get("id")
                .getAsString());
        assertEquals(
            1,
            pick.get("amount")
                .getAsDouble());
        boolean wired = false;
        for (final JsonElement e : posted.getAsJsonArray("edges")) wired |= e.getAsJsonObject()
            .get("targetHandle")
            .getAsString()
            .equals("input:item:gregtech%3Agt.metaitem.01%402010");
        assertTrue(wired, "the wire lands on the slot as the item it is set to");

        final Graph back = FfImport.importFromText(posted.toString(), game, game)
            .graph();
        final Node ebf = back.getNodes()
            .iterator()
            .next();
        assertEquals(CARBON, held(game, ebf.inputs.get(1)));
        assertEquals(Map.of(1, "item|" + CARBON), ebf.inputPicks);
    }
}
