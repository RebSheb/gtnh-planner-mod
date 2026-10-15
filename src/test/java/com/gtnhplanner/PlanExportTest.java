package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.harness.GtnhFlowLoader;
import com.gtnhplanner.library.PlanExport;

/**
 * A plan posted to the library is Factory Flow's project JSON. The shape is checked here; the build writes the
 * export to {@code build/tmp/gtnhplanner-export.json} so it can also be run past the website's own schema.
 */
class PlanExportTest {

    /** The game's side, stood in for: every port names itself. */
    private static final PlanExport.World WORLD = new PlanExport.World() {

        @Override
        public PlanExport.Res port(final Port<?> port) {
            final String name = port.getDisplayName();
            return new PlanExport.Res(
                "item",
                "test:" + name.toLowerCase(Locale.ROOT)
                    .replace(' ', '_'),
                name);
        }

        @Override
        public PlanExport.Res resource(final String key) {
            return new PlanExport.Res("item", "test:" + key, key);
        }

        @Override
        public String machine(final Node node) {
            return node.machineName == null ? "Machine" : node.machineName;
        }

        @Override
        public double machines(final Node node) {
            return 2;
        }

        @Override
        public String tier(final Node node) {
            return "LV";
        }
    };

    @Test
    void aModelledMachinesSettingsGoOutUnderTheWebsitesKeys() throws IOException {
        final Graph g = GtnhFlowLoader.load("mk1")
            .graph();
        final Node first = g.getNodes()
            .iterator()
            .next();
        first.machineConfig.settings.put("tgsLogTool", "log:chainsaw");
        first.machineConfig.settings.put("bioVatFill", "49.5");
        first.machineConfig.settings.put("tgsHeight", "");
        final JsonObject p = PlanExport.project(g, "Test plan", WORLD);
        JsonObject tiers = null;
        for (final JsonElement n : p.getAsJsonArray("nodes")) if (n.getAsJsonObject()
            .get("id")
            .getAsString()
            .equals(first.id.toString()))
            tiers = n.getAsJsonObject()
                .getAsJsonObject("machineConfigTiers");
        assertEquals(
            "log:chainsaw",
            tiers.get("tgsLogTool")
                .getAsString());
        assertEquals(
            "49.5",
            tiers.get("bioVatFill")
                .getAsString());
        assertFalse(tiers.has("tgsHeight"), "a default (empty) setting stays out");
    }

    @Test
    void aFluidTankPostsAsTheWebsitesTank() {
        // Emptying oxygen cells: a cell in, its oxygen and the empty cell out, 40 cells a second.
        final Graph g = new Graph("Tank");
        final Node tank = new Node(java.util.UUID.randomUUID(), 0, 0);
        tank.powerSource = com.gtnhplanner.power.FluidTank.ID;
        tank.inputs.add(com.gtnhplanner.harness.TestIngredients.port("Oxygen Cell", 1));
        tank.outputs.add(com.gtnhplanner.harness.TestIngredients.port("Oxygen", 1000));
        tank.outputs.add(com.gtnhplanner.harness.TestIngredients.port("Empty Cell", 1));
        g.addNode(tank);
        final JsonObject p = PlanExport.project(g, "Tank", new PlanExport.World() {

            @Override
            public PlanExport.Res port(final Port<?> port) {
                final String name = port.getDisplayName();
                return new PlanExport.Res(
                    "Oxygen".equals(name) ? "fluid" : "item",
                    "test:" + name.toLowerCase(Locale.ROOT)
                        .replace(' ', '_'),
                    name);
            }

            @Override
            public PlanExport.Res resource(final String key) {
                return WORLD.resource(key);
            }

            @Override
            public String machine(final Node node) {
                return "Fluid Tank";
            }

            @Override
            public double machines(final Node node) {
                return 40;
            }

            @Override
            public String tier(final Node node) {
                return "LV";
            }
        });
        final JsonObject recipe = p.getAsJsonArray("recipes")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            "Tank",
            recipe.get("machineType")
                .getAsString());
        assertEquals(
            "Tank: Oxygen",
            recipe.get("name")
                .getAsString());
        assertEquals(
            "planner.tank",
            recipe.getAsJsonObject("metadata")
                .get("recipeMapId")
                .getAsString(),
            "the site's own Tank map, which imports back as a fluid tank");
        assertEquals(
            1,
            recipe.get("durationTicks")
                .getAsInt());
        assertEquals(
            0,
            recipe.get("eut")
                .getAsInt());
        assertEquals(
            2.0,
            p.getAsJsonArray("nodes")
                .get(0)
                .getAsJsonObject()
                .get("machineCount")
                .getAsDouble(),
            1e-9,
            "40 cells a second is 2 Tank crafts a tick");
    }

    @Test
    void aCropPostsAsTheWebsitesCropFarmCard() throws IOException {
        final Graph g = new Graph("Crops");
        final Node beet = new Node(java.util.UUID.randomUUID(), 0, 0);
        beet.outputs.add(com.gtnhplanner.harness.TestIngredients.port("Sugar Beet", 2.69));
        beet.machineConfig.settings.put(com.gtnhplanner.machines.CropsNh.HARVESTER, "crop-industrial-farm");
        beet.machineConfig.settings.put(com.gtnhplanner.machines.CropsNh.BIOME, "humid");
        beet.machineConfig.settings.put(com.gtnhplanner.machines.CropsNh.SEED_BED_TIER, "4");
        g.addNode(beet);
        final com.gtnhplanner.machines.CropsNh.Stats stats = new com.gtnhplanner.machines.CropsNh.Stats(
            4,
            450,
            0.81450623,
            256,
            1,
            false,
            2,
            null,
            List.of(new com.gtnhplanner.machines.CropsNh.Drop("cropsnh:berry@1", 1, 10000)));
        final JsonObject p = PlanExport.project(g, "Crops", new PlanExport.World() {

            @Override
            public PlanExport.Res port(final Port<?> port) {
                return WORLD.port(port);
            }

            @Override
            public PlanExport.Res resource(final String key) {
                return WORLD.resource(key);
            }

            @Override
            public String machine(final Node node) {
                return "Industrial Farm";
            }

            @Override
            public double machines(final Node node) {
                return 225;
            }

            @Override
            public String tier(final Node node) {
                return "LV";
            }

            @Override
            public PlanExport.Crop crop(final Node node) {
                return new PlanExport.Crop("cropsnh:sugarBeet", stats);
            }
        });
        final JsonObject recipe = p.getAsJsonArray("recipes")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            "crop_produce",
            recipe.get("kind")
                .getAsString());
        assertEquals(
            "cropsnh-crop",
            recipe.get("category")
                .getAsString());
        assertEquals(
            "Crop Farm",
            recipe.get("machineType")
                .getAsString(),
            "the site knows a crop's recipe by its crop farm");
        assertEquals(
            "cropsnh:cropsnh:sugarBeet",
            recipe.getAsJsonObject("source")
                .get("rawRecipeId")
                .getAsString());
        final JsonObject crop = recipe.getAsJsonObject("metadata")
            .getAsJsonObject("cropsNh");
        assertEquals(
            450,
            crop.get("growthPoints")
                .getAsInt());
        assertEquals(
            "cropsnh:berry@1",
            crop.getAsJsonArray("drops")
                .get(0)
                .getAsJsonObject()
                .get("id")
                .getAsString());
        final JsonObject node = p.getAsJsonArray("nodes")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            "crop-industrial-farm",
            node.get("machineHandlerId")
                .getAsString(),
            "the harvester is the site's machine");
        assertEquals(
            225,
            node.get("machineCount")
                .getAsInt(),
            "crops planted");
        final JsonObject settings = node.getAsJsonObject("machineConfigTiers");
        assertEquals(
            "humid",
            settings.get("cropBiome")
                .getAsString());
        assertEquals(
            "4",
            settings.get("cropSeedBedTier")
                .getAsString());
        assertFalse(settings.has("cropHarvester"), "the harvester goes as the machine, not a setting");
        final Path out = Path.of("build", "tmp", "gtnhplanner-export-crops.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, p.toString(), StandardCharsets.UTF_8);
    }

    @Test
    void aBeePostsAsTheWebsitesBeeCard() throws IOException {
        GtnhFlowLoader.ensureDefaultMachineProfile();
        final Graph g = new Graph("Bees");
        final Node apiary = new Node(java.util.UUID.randomUUID(), 0, 0);
        apiary.outputs.add(com.gtnhplanner.harness.TestIngredients.port("Parched Comb", 0.1));
        apiary.outputs.add(com.gtnhplanner.harness.TestIngredients.port("Pulsating Iron Comb", 0.05));
        apiary.machineConfig.settings.put(com.gtnhplanner.machines.Bees.FRAME + 1, "forestry:proven");
        g.addNode(apiary);
        final Node ia = new Node(java.util.UUID.randomUUID(), 400, 0);
        ia.outputs.add(com.gtnhplanner.harness.TestIngredients.port("Parched Comb", 5));
        ia.machineConfig.settings.put(com.gtnhplanner.machines.Bees.HOUSING, "industrial-apiary");
        ia.machineConfig.settings.put(com.gtnhplanner.machines.Bees.IA_SPEED, "speed-4");
        ia.machineConfig.settings.put(com.gtnhplanner.machines.Bees.CLIMATE, "tolerated");
        g.addNode(ia);
        final com.gtnhplanner.machines.Bees.Species species = new com.gtnhplanner.machines.Bees.Species(
            "gregtech.bee.speciesPulsatingiron",
            "Pulsating Iron",
            0.6f,
            30,
            false,
            List.of(
                new com.gtnhplanner.machines.Bees.Product("forestry:beecombs@7", "Parched Comb", 1, 0.3f, false),
                new com.gtnhplanner.machines.Bees.Product(
                    "gregtech:gt.comb@18",
                    "Pulsating Iron Comb",
                    1,
                    0.15f,
                    true)));
        final JsonObject p = PlanExport.project(g, "Bees", new PlanExport.World() {

            @Override
            public PlanExport.Res port(final Port<?> port) {
                return WORLD.port(port);
            }

            @Override
            public PlanExport.Res resource(final String key) {
                return WORLD.resource(key);
            }

            @Override
            public String machine(final Node node) {
                return "Apiary";
            }

            @Override
            public double machines(final Node node) {
                return 4;
            }

            @Override
            public String tier(final Node node) {
                return "LV";
            }

            @Override
            public com.gtnhplanner.machines.Bees.Species bee(final Node node) {
                return species;
            }
        });
        final JsonObject recipe = p.getAsJsonArray("recipes")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            "bee_produce",
            recipe.get("kind")
                .getAsString());
        assertEquals(
            "Bee Produce",
            recipe.get("machineType")
                .getAsString(),
            "the site knows a bee's recipe by its map");
        assertEquals(
            550,
            recipe.get("durationTicks")
                .getAsInt(),
            "a bee cycle");
        final JsonObject bee = recipe.getAsJsonArray("inputs")
            .get(0)
            .getAsJsonObject();
        assertEquals(
            "factoryflow:bee_species:gregtech-bee-speciespulsatingiron",
            bee.get("id")
                .getAsString(),
            "the bee, as the site's dataset names it");
        assertFalse(
            bee.get("consumed")
                .getAsBoolean());
        final JsonArray outputs = recipe.getAsJsonArray("outputs");
        assertEquals(
            0.3,
            outputs.get(0)
                .getAsJsonObject()
                .get("chance")
                .getAsDouble(),
            1e-6,
            "the species' base chance, which the site scales");
        assertTrue(
            outputs.get(1)
                .getAsJsonObject()
                .get("byproduct")
                .getAsBoolean(),
            "a specialty is the site's byproduct");
        assertEquals(
            "apiary",
            recipe.getAsJsonArray("machineHandlers")
                .get(0)
                .getAsJsonObject()
                .get("id")
                .getAsString(),
            "the plain Apiary comes with the recipe: the site has none of its own");
        final JsonObject first = nodeOf(p, apiary), second = nodeOf(p, ia);
        assertEquals(
            "apiary",
            first.get("machineHandlerId")
                .getAsString());
        final JsonObject apiarySettings = first.getAsJsonObject("machineConfigTiers");
        assertEquals(
            "forestry:proven",
            apiarySettings.get("beeFrameSlot1")
                .getAsString());
        assertEquals(
            "slower",
            apiarySettings.get("beeSpeedGene")
                .getAsString(),
            "the species' own speed, said outright: the site's unset one is Normal");
        assertFalse(apiarySettings.has("beeHousing"), "the housing goes as the machine, not a setting");
        assertEquals(
            "industrial-apiary",
            second.get("machineHandlerId")
                .getAsString());
        assertEquals(
            "speed-4",
            second.getAsJsonObject("machineConfigTiers")
                .get("beeIndustrialSpeed")
                .getAsString());
        assertEquals(
            "tolerated",
            second.getAsJsonObject("machineConfigTiers")
                .get("beeEnvironment")
                .getAsString());
        final Path out = Path.of("build", "tmp", "gtnhplanner-export-bees.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, p.toString(), StandardCharsets.UTF_8);
    }

    /** A card's node in a posted plan (the plan keeps its cards in no set order). */
    private static JsonObject nodeOf(final JsonObject plan, final Node card) {
        for (final JsonElement n : plan.getAsJsonArray("nodes")) if (n.getAsJsonObject()
            .get("id")
            .getAsString()
            .equals(card.id.toString())) return n.getAsJsonObject();
        throw new AssertionError("no node for card " + card.id);
    }

    @Test
    void aPlanBecomesAFactoryFlowProject() throws IOException {
        final Graph g = GtnhFlowLoader.load("mk1")
            .graph();
        final JsonObject p = PlanExport.project(g, "Test plan", WORLD);
        assertEquals(
            1,
            p.get("schemaVersion")
                .getAsInt());
        assertTrue(
            p.get("solveMode")
                .getAsBoolean());
        assertEquals(
            g.getNodes()
                .size(),
            p.getAsJsonArray("nodes")
                .size(),
            "a node for every card");
        assertEquals(
            g.getNodes()
                .size(),
            p.getAsJsonArray("recipes")
                .size(),
            "and its recipe, carried in full");
        final Set<String> recipeIds = new HashSet<>();
        for (final JsonElement r : p.getAsJsonArray("recipes")) {
            final JsonObject o = r.getAsJsonObject();
            recipeIds.add(
                o.get("id")
                    .getAsString());
            assertTrue(
                o.get("durationTicks")
                    .getAsInt() >= 1);
            for (final JsonElement in : o.getAsJsonArray("inputs")) assertTrue(
                in.getAsJsonObject()
                    .get("amount")
                    .getAsDouble() > 0,
                "every amount above nothing");
        }
        for (final JsonElement n : p.getAsJsonArray("nodes")) {
            assertTrue(
                recipeIds.contains(
                    n.getAsJsonObject()
                        .get("recipeId")
                        .getAsString()),
                "every node's recipe is there");
        }
        assertTrue(
            p.getAsJsonArray("edges")
                .size() > 0,
            "the wires come along");
        for (final JsonElement e : p.getAsJsonArray("edges")) {
            final JsonObject o = e.getAsJsonObject();
            assertTrue(
                o.get("sourceHandle")
                    .getAsString()
                    .startsWith("output:item:"));
            assertTrue(
                o.get("targetHandle")
                    .getAsString()
                    .startsWith("input:item:"));
        }
        final Path out = Path.of("build", "tmp", "gtnhplanner-export.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, p.toString(), StandardCharsets.UTF_8);
    }

    /** The site's colour tags (factoryNodeColorTagSchema in its src/lib/model/schemas.ts). */
    private static final Set<String> SITE_COLOR_TAGS = Set.of(
        "white",
        "orange",
        "magenta",
        "light_blue",
        "yellow",
        "lime",
        "pink",
        "gray",
        "light_gray",
        "cyan",
        "purple",
        "blue",
        "brown",
        "green",
        "red",
        "black",
        "scarlet",
        "amber",
        "emerald",
        "azure",
        "steel",
        "onyx");

    /** The keys of the site's factoryAnnotationSchema. */
    private static final Set<String> ANNOTATION_KEYS = Set.of(
        "id",
        "kind",
        "colorTag",
        "text",
        "position",
        "size",
        "arrowDirection",
        "points",
        "fontSize",
        "imageUrl",
        "style",
        "pocketId");

    @Test
    void thePlanWearsItsFace() throws IOException {
        final Graph g = GtnhFlowLoader.load("mk1")
            .graph();
        final JsonObject bare = PlanExport.project(g, "Test plan", WORLD);
        assertFalse(bare.has("description"), "no description, none sent");
        assertFalse(bare.has("icon"), "no icon, none sent");
        g.setDescription("Steel, the slow way");
        g.setIcon("steel");
        final JsonObject p = PlanExport.project(g, "Test plan", WORLD);
        assertEquals(
            "Steel, the slow way",
            p.get("description")
                .getAsString());
        final JsonObject icon = p.getAsJsonObject("icon");
        assertEquals(
            "item",
            icon.get("kind")
                .getAsString());
        assertEquals(
            "test:steel",
            icon.get("resourceId")
                .getAsString(),
            "the site's EntryIcon: kind, resourceId, displayName");
        assertEquals(
            "steel",
            icon.get("displayName")
                .getAsString());
    }

    @Test
    void notesBecomeTextAnnotations() throws IOException {
        final Graph g = GtnhFlowLoader.load("mk1")
            .graph();
        final Note tagged = new Note();
        tagged.setX(320);
        tagged.setY(60);
        tagged.setWidth(202);
        tagged.setHeight(48);
        tagged.setColor("lime");
        tagged.setFontSize(22);
        tagged.setJoined("Feed the reactor\nfrom the left");
        g.notes.put(tagged.getId(), tagged);
        final Note plain = new Note();
        plain.setX(0);
        plain.setY(300);
        plain.setColor("chartreuse");
        plain.setJoined("");
        g.notes.put(plain.getId(), plain);

        final JsonObject p = PlanExport.project(g, "Test plan", WORLD);
        final JsonArray annotations = p.getAsJsonArray("annotations");
        assertEquals(2, annotations.size(), "an annotation for every note");
        for (final JsonElement a : annotations) fitsTheSchema(a.getAsJsonObject());

        final JsonObject t = annotation(annotations, tagged);
        assertEquals(
            "text",
            t.get("kind")
                .getAsString());
        assertEquals(
            "lime",
            t.get("colorTag")
                .getAsString());
        assertEquals(
            "Feed the reactor\nfrom the left",
            t.get("text")
                .getAsString());
        assertEquals(
            380,
            t.getAsJsonObject("position")
                .get("x")
                .getAsInt(),
            "placed as a card is: 320 at 380/320");
        assertEquals(
            100,
            t.getAsJsonObject("position")
                .get("y")
                .getAsInt(),
            "and 60 at 1/0.6");
        assertEquals(
            240,
            t.getAsJsonObject("size")
                .get("width")
                .getAsInt(),
            "202 at 380/320, to whole 20 px cells");
        assertEquals(
            80,
            t.getAsJsonObject("size")
                .get("height")
                .getAsInt());
        assertEquals(
            22,
            t.get("fontSize")
                .getAsInt());

        final JsonObject u = annotation(annotations, plain);
        assertFalse(u.has("colorTag"), "a colour the site does not know is left for its default");
        assertEquals(
            "",
            u.get("text")
                .getAsString());
        assertEquals(
            Note.DEFAULT_FONT,
            u.get("fontSize")
                .getAsInt());
        assertEquals(
            220,
            u.getAsJsonObject("size")
                .get("width")
                .getAsInt(),
            "a new note's 180 x 120");
        assertEquals(
            200,
            u.getAsJsonObject("size")
                .get("height")
                .getAsInt());

        final Path out = Path.of("build", "tmp", "gtnhplanner-export-notes.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, p.toString(), StandardCharsets.UTF_8);
    }

    private static JsonObject annotation(final JsonArray annotations, final Note note) {
        for (final JsonElement a : annotations) if (a.getAsJsonObject()
            .get("id")
            .getAsString()
            .equals(
                note.getId()
                    .toString()))
            return a.getAsJsonObject();
        throw new AssertionError("no annotation for note " + note.getId());
    }

    /** What the site's factoryAnnotationSchema asks of a text annotation. */
    private static void fitsTheSchema(final JsonObject a) {
        for (final Map.Entry<String, JsonElement> e : a.entrySet())
            assertTrue(ANNOTATION_KEYS.contains(e.getKey()), "unknown key " + e.getKey());
        assertFalse(
            a.get("id")
                .getAsString()
                .isEmpty());
        assertEquals(
            "text",
            a.get("kind")
                .getAsString());
        if (a.has("colorTag")) assertTrue(
            SITE_COLOR_TAGS.contains(
                a.get("colorTag")
                    .getAsString()),
            "a colour tag the site knows");
        assertTrue(
            a.get("text")
                .getAsJsonPrimitive()
                .isString());
        for (final String key : List.of("x", "y")) assertTrue(
            a.getAsJsonObject("position")
                .get(key)
                .getAsJsonPrimitive()
                .isNumber());
        for (final String key : List.of("width", "height")) assertTrue(
            a.getAsJsonObject("size")
                .get(key)
                .getAsJsonPrimitive()
                .isNumber());
        final int font = a.get("fontSize")
            .getAsInt();
        assertTrue(font >= 8 && font <= 96, "a text size the site takes");
    }
}
