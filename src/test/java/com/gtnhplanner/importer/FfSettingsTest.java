package com.gtnhplanner.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.importer.FfPlan.FfHandler;
import com.gtnhplanner.importer.FfPlan.FfNode;
import com.gtnhplanner.importer.FfPlan.FfRecipe;

/** FF card settings as GTNH Planner machine settings. */
class FfSettingsTest {

    private static final FfRecipe SINGLE = recipe(new FfHandler("ebf", "Chemical Reactor", "single", false));
    private static final FfRecipe MULTI = recipe(
        new FfHandler("lcr", "Large Chemical Reactor", "multiblock", true),
        new FfHandler("mega", "Mega Chemical Reactor", "multiblock", false));

    private static FfRecipe recipe(final FfHandler... handlers) {
        return new FfRecipe(
            "r",
            "R",
            "gregtech_machine",
            "gregtech",
            "Chemical Reactor",
            "LV",
            100,
            30,
            0.0,
            null,
            List.of(),
            List.of(),
            List.of(handlers),
            "gt.recipe.chemicalreactor",
            null,
            null);
    }

    /** A card with FF's defaults; the arguments are what the tests vary. */
    private static FfNode card(final String tier, @Nullable final String hatchTier, @Nullable final Double amps,
        @Nullable final String mode, @Nullable final Double budget, final int hatches, @Nullable final String handler) {
        return card(tier, hatchTier, amps, mode, budget, hatches, handler, null, 1, Map.of(), 1, null);
    }

    private static FfNode card(final String tier, @Nullable final String hatchTier, @Nullable final Double amps,
        @Nullable final String mode, @Nullable final Double budget, final int hatches, @Nullable final String handler,
        @Nullable final String coil, final int parallel, final Map<String, String> config, final double count,
        @Nullable final Double pin) {
        return new FfNode(
            "n",
            "r",
            count,
            parallel,
            tier,
            hatches,
            null,
            budget,
            hatchTier,
            amps,
            mode,
            handler,
            coil,
            config,
            Map.of(),
            List.of(),
            pin,
            null,
            true,
            0,
            0,
            null,
            null,
            List.of());
    }

    @Test
    void theModelledMachinesSettingsComeAcrossUnderTheWebsitesKeys() {
        // The Tree Growth Simulator's tools and the Bacterial Vat's fill are kept; any other machine option is not.
        final FfSettings.Mapped m = FfSettings.map(
            card(
                "LV",
                null,
                null,
                null,
                null,
                1,
                null,
                null,
                1,
                Map.of("tgsLogTool", "log:chainsaw", "bioVatFill", "49.5", "eecLooting", "2"),
                1,
                null),
            MULTI,
            30,
            false,
            true);
        assertEquals(
            "log:chainsaw",
            m.settings()
                .get("tgsLogTool"));
        assertEquals(
            "49.5",
            m.settings()
                .get("bioVatFill"));
        assertEquals(
            "2",
            m.settings()
                .get("machine:eecLooting"),
            "every other machine's option, under the website's id");
    }

    @Test
    void aMultiblockRunsOnItsHatches() {
        final FfSettings.Mapped m = FfSettings
            .map(card("LV", "UV", 16.0, "amps", 8388608.0, 0, null), MULTI, 30, false, true);
        assertEquals(
            "UV",
            m.settings()
                .get("voltage"));
        assertEquals(
            16,
            m.settings()
                .get("amp"));
        assertEquals(
            true,
            m.settings()
                .get("gt_multiblock"));
        assertEquals("Large Chemical Reactor", m.machineLabel());
        assertTrue(
            m.notes()
                .isEmpty(),
            m.notes()
                .toString());
    }

    @Test
    void manyAmpsStayAmpsAtTheirTier() {
        final FfSettings.Mapped m = FfSettings
            .map(card("LV", "LuV", 256.0, "amps", null, 0, "mega"), MULTI, 30, false, true);
        assertEquals(
            "LuV",
            m.settings()
                .get("voltage"));
        assertEquals(
            256,
            m.settings()
                .get("amp"));
        assertEquals("Mega Chemical Reactor", m.machineLabel());
        assertTrue(
            m.notes()
                .isEmpty(),
            m.notes()
                .toString());
    }

    @Test
    void aRawPowerBudgetIsHatchesOfTheTierThatFits() {
        final FfSettings.Mapped m = FfSettings
            .map(card("LV", null, null, "eut", 6000.0, 0, null), MULTI, 30, false, true);
        assertEquals(
            "EV",
            m.settings()
                .get("voltage"));
        assertEquals(
            3,
            m.settings()
                .get("amp"),
            "2.93 A rounds to 3");
        assertTrue(
            m.notes()
                .getFirst()
                .contains("carried as 3 A EV"));
    }

    @Test
    void aLegacyMultiblockCountsItsHatches() {
        final FfSettings.Mapped one = FfSettings
            .map(card("HV", null, null, null, null, 1, null), MULTI, 30, false, true);
        assertEquals(
            "HV",
            one.settings()
                .get("voltage"));
        assertEquals(
            1,
            one.settings()
                .get("amp"));
        final FfSettings.Mapped two = FfSettings
            .map(card("HV", null, null, null, null, 2, null), MULTI, 30, false, true);
        assertEquals(
            4,
            two.settings()
                .get("amp"),
            "two or more hatches work at 2 A each");
    }

    @Test
    void aSingleBlockRunsAtItsTierButNeverBelowTheRecipes() {
        final FfSettings.Mapped hv = FfSettings
            .map(card("HV", null, null, null, null, 0, null), SINGLE, 30, false, true);
        assertEquals(
            "HV",
            hv.settings()
                .get("voltage"));
        assertFalse(
            hv.settings()
                .containsKey("amp"));
        assertEquals(
            false,
            hv.settings()
                .get("gt_multiblock"),
            "a single block says so, whatever machine the board would pick first");
        final FfSettings.Mapped floored = FfSettings
            .map(card("LV", null, null, null, null, 0, null), SINGLE, 480, false, true);
        assertEquals(
            "HV",
            floored.settings()
                .get("voltage"),
            "480 EU/t is an HV recipe");
        final FfSettings.Mapped demo = FfSettings
            .map(card("DEMO", null, null, null, null, 0, null), SINGLE, 30, false, true);
        assertFalse(
            demo.settings()
                .containsKey("voltage"),
            "not a tier: the board's default stays");
    }

    @Test
    void coilsParallelsAndOptions() {
        final FfSettings.Mapped m = FfSettings.map(
            card("HV", null, null, null, null, 0, null, "kanthal", 4, Map.of("itemPipeCasing", "quantium"), 1, null),
            SINGLE,
            30,
            false,
            true);
        assertEquals(
            "kanthal",
            m.settings()
                .get("coil"));
        assertEquals(
            "quantium",
            m.settings()
                .get("machine:itemPipeCasing"));
        assertTrue(
            m.notes()
                .contains("4 parallels on the card not carried over"));
        final FfSettings.Mapped odd = FfSettings.map(
            card("HV", null, null, null, null, 0, null, "unobtainium", 1, Map.of(), 1, null),
            SINGLE,
            30,
            false,
            true);
        assertFalse(
            odd.settings()
                .containsKey("coil"));
        assertTrue(
            odd.notes()
                .getFirst()
                .contains("unobtainium"));
        assertEquals(13501, FfSettings.COIL_HEAT.get("eternal"));
        assertEquals(10801, FfSettings.COIL_HEAT.get("awakened_draconium"));
    }

    @Test
    void solvePlansPinOnlyWhatFactoryFlowPinned() {
        final FfNode pinned = card("HV", null, null, null, null, 0, null, null, 1, Map.of(), 7, 2.5);
        final FfSettings.Mapped p = FfSettings.map(pinned, SINGLE, 30, true, true);
        assertTrue(p.pinned());
        assertEquals(3, p.machines());
        assertTrue(
            p.notes()
                .getFirst()
                .contains("rounded to 3"));

        final FfSettings.Mapped section = FfSettings.map(pinned, SINGLE, 30, true, false);
        assertFalse(section.pinned(), "a shared machine's extra recipe never carries the card's pin");

        final FfNode free = card("HV", null, null, null, null, 0, null, null, 1, Map.of(), 7, null);
        final FfSettings.Mapped f = FfSettings.map(free, SINGLE, 30, true, true);
        assertFalse(f.pinned(), "machine counts are the answer in Solve");
        assertEquals(1, f.machines());
    }

    @Test
    void buildPlansPinTheirCounts() {
        // Build runs a card at its count, so the count is a pin, rounded up to whole machines; a shared machine's
        // extra recipes carry no pin of their own.
        final FfNode built = card("HV", null, null, null, null, 0, null, null, 1, Map.of(), 2.3, 5.0);
        final FfSettings.Mapped m = FfSettings.map(built, SINGLE, 30, false, true);
        assertTrue(m.pinned());
        assertEquals(3, m.machines());
        assertFalse(
            FfSettings.map(built, SINGLE, 30, false, false)
                .pinned());
    }

    @Test
    void theMachinesIdGoesWithItsName() {
        // The game finds FF's machine by its id among the website's machine data; the name is the fallback.
        final FfRecipe extractor = recipe(
            new FfHandler("extractor", "Extractor", "single", false),
            new FfHandler("steam-extractor", "Steam Extractor", "single", false));
        final FfSettings.Mapped m = FfSettings
            .map(card("HV", null, null, null, null, 0, null), extractor, 7, true, true);
        assertEquals(
            "extractor",
            m.machine()
                .handlerId(),
            "a card that names no machine runs the recipe's first, the website's default");
        assertEquals("Extractor", m.machineLabel());
        assertEquals(
            "HV",
            m.settings()
                .get("voltage"));
        // A plan that left the recipe's machines out still names the card's.
        assertEquals(
            "large-electric-compressor",
            FfSettings.map(card("HV", "HV", 1.0, null, null, 0, "large-electric-compressor"), recipe(), 2, true, true)
                .machine()
                .handlerId());
    }

    @Test
    void steamMachinesCarryNoTier() {
        // FF hides a steam machine's tier chip, but the card keeps whatever tier it had: here HV, from when it ran on
        // an
        // electric Compressor. Steam runs at no voltage, so none comes over.
        final FfRecipe compressor = recipe(
            new FfHandler("compressor", "Compressor", "single", false),
            new FfHandler("steam-compressor", "Steam Compressor", "single", false),
            new FfHandler("steam-squasher", "Steam Squasher", "multiblock", false));
        final FfSettings.Mapped single = FfSettings
            .map(card("HV", null, null, null, null, 0, "steam-compressor"), compressor, 2, true, true);
        assertNull(
            single.settings()
                .get("voltage"));
        assertEquals(
            false,
            single.settings()
                .get("gt_multiblock"));
        final FfSettings.Mapped multi = FfSettings
            .map(card("HV", "HV", 4.0, null, null, 0, "steam-squasher"), compressor, 2, true, true);
        assertNull(
            multi.settings()
                .get("voltage"));
        assertNull(
            multi.settings()
                .get("amp"));
        assertEquals(
            true,
            multi.settings()
                .get("gt_multiblock"));
        assertTrue(FfSettings.steam("High Pressure Alloy Smelter", null));
        assertTrue(FfSettings.steam(null, "high-pressure-steam-extractor"));
        assertFalse(FfSettings.steam("Extractor", "extractor"));
    }

    @Test
    void aBeesHousingAndSettingsComeOverAsTheBeeCardsOwn() {
        final FfRecipe bee = new FfRecipe(
            "oracle:local-2.9.0-beta-2:forestry-bee:gregtech-bee-speciespulsatingiron",
            "Pulsating Iron Produce",
            "bee_produce",
            "forestry-bee",
            "Bee Produce",
            "NONE",
            550,
            0,
            null,
            null,
            List.of(
                new FfPlan.FfSlot(
                    "item",
                    "factoryflow:bee_species:gregtech-bee-speciespulsatingiron",
                    1,
                    1,
                    false,
                    List.of(),
                    "Pulsating Iron Bee")),
            List.of(),
            List.of(),
            null,
            "gregtech.bee.speciesPulsatingiron",
            null);
        assertTrue(bee.isBee());
        assertEquals("gregtech-bee-speciespulsatingiron", bee.beeSlug());
        final FfSettings.Mapped ia = FfSettings.map(
            card(
                "MV",
                null,
                null,
                null,
                null,
                0,
                "industrial-apiary",
                null,
                1,
                Map.of("beeIndustrialSpeed", "speed-4", "beeIndustrialProduction", "2", "beeEnvironment", "tolerated"),
                3,
                3.0),
            bee,
            0,
            true,
            true);
        assertEquals(
            "industrial-apiary",
            ia.settings()
                .get(com.gtnhplanner.machines.Bees.HOUSING));
        assertEquals(
            "speed-4",
            ia.settings()
                .get(com.gtnhplanner.machines.Bees.IA_SPEED));
        assertEquals(
            "tolerated",
            ia.settings()
                .get(com.gtnhplanner.machines.Bees.CLIMATE),
            "not jubilant");
        assertEquals(
            "normal",
            ia.settings()
                .get(com.gtnhplanner.machines.Bees.SPEED),
            "the website's speed gene unset is Normal");
        assertNull(
            ia.settings()
                .get("voltage"),
            "a housing has no voltage to pick");
        assertEquals(3, ia.machines());
        // The website's bee card with no machine is its first, the Magic Apiary; its Alveary is not modelled yet.
        final FfSettings.Mapped plain = FfSettings.map(
            card("NONE", null, null, null, null, 0, null, null, 1, Map.of("beeSpeedGene", "fast"), 1, null),
            bee,
            0,
            true,
            true);
        assertEquals(
            "magic-apiary",
            plain.settings()
                .get(com.gtnhplanner.machines.Bees.HOUSING));
        assertEquals(
            "fast",
            plain.settings()
                .get(com.gtnhplanner.machines.Bees.SPEED));
        // A plan the mod posted lists only its Apiary among the recipe's machines: the card's own still wins.
        final FfRecipe posted = new FfRecipe(
            "gtnhplanner:card",
            bee.name(),
            bee.kind(),
            bee.category(),
            bee.machineType(),
            "NONE",
            550,
            0,
            null,
            null,
            bee.inputs(),
            List.of(),
            List.of(new FfPlan.FfHandler("apiary", "Apiary", "single", false)),
            null,
            bee.rawRecipeId(),
            null);
        assertEquals(
            "industrial-apiary",
            FfSettings.map(card("MV", null, null, null, null, 0, "industrial-apiary"), posted, 0, true, true)
                .settings()
                .get(com.gtnhplanner.machines.Bees.HOUSING));
        assertEquals(
            "apiary",
            FfSettings.map(card("NONE", null, null, null, null, 0, "apiary"), posted, 0, true, true)
                .settings()
                .get(com.gtnhplanner.machines.Bees.HOUSING));
        final FfSettings.Mapped alveary = FfSettings
            .map(card("NONE", null, null, null, null, 0, "alveary", null, 1, Map.of(), 1, null), bee, 0, true, true);
        assertEquals(
            "apiary",
            alveary.settings()
                .get(com.gtnhplanner.machines.Bees.HOUSING));
        assertTrue(
            alveary.notes()
                .stream()
                .anyMatch(n -> n.contains("alveary")),
            "the report says so");
    }

    @Test
    void aCropsHarvesterAndSettingsComeOverAsTheCropCardsOwn() {
        final FfRecipe crop = new FfRecipe(
            "oracle:local-2.9.0-beta-2:cropsnh-crop:cropsnh:cropsnh-sugarbeet",
            "Crop Farm: Sugar Beet",
            "crop_produce",
            "cropsnh-crop",
            "Crop Farm",
            "NONE",
            1024,
            0,
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            null,
            "cropsnh:cropsnh:sugarBeet",
            null);
        assertTrue(crop.isCrop());
        assertEquals("cropsnh-sugarbeet", crop.cropSlug());
        final FfSettings.Mapped m = FfSettings.map(
            card(
                "MV",
                null,
                null,
                null,
                null,
                0,
                "crop-industrial-farm",
                null,
                1,
                Map.of("cropBiome", "humid"),
                9000,
                null),
            crop,
            0,
            false,
            true);
        assertEquals(
            "crop-industrial-farm",
            m.settings()
                .get(com.gtnhplanner.machines.CropsNh.HARVESTER));
        assertEquals(
            "humid",
            m.settings()
                .get(com.gtnhplanner.machines.CropsNh.BIOME));
        assertNull(
            m.settings()
                .get("voltage"),
            "a harvester has no voltage of its own");
        assertEquals(9000, m.machines(), "crops planted, past any machine count's cap");
        assertTrue(m.pinned());
    }

    @Test
    void tiers() {
        assertEquals(1, FfSettings.recipeTier(30));
        assertEquals(1, FfSettings.recipeTier(32));
        assertEquals(2, FfSettings.recipeTier(33));
        assertEquals(0, FfSettings.recipeTier(0));
        assertEquals(4, FfSettings.tierWithin(6000));
        assertEquals(0, FfSettings.tierWithin(3));
        assertEquals(-1, FfSettings.tierIndex("NONE"));
        assertEquals(6, FfSettings.tierIndex("luv"));
        assertEquals(
            "lcr",
            MULTI.handler("nope")
                .id(),
            "an unknown pick falls back to the first machine");
        assertNull(recipe().handler(null), "a recipe with no machines has none to pick");
    }
}
