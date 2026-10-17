package com.gtnhplanner.machines.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * dangote-distillus.test.ts, table side: the two recipe-map modes' speed, EU and parallels, and the stale handler
 * controls it drops. The handler, power report and overclock cases go through the solver and are covered by the
 * golden fixture. Then the height, which is ours (issue 495): distillery parallels by the game's formula, the
 * website's 12 layers when unset.
 */
class DangoteDistillusTest {

    private static final String DANGOTE = "Dangote Distillus";

    private static MachineTable.Context ctx(final int voltageTier, final String recipeMap) {
        return new MachineTable.Context(id -> 0, id -> 0, voltageTier, null, null, recipeMap, null, null);
    }

    @Test
    void dropsStaleParallelControlsAndSeedsFromTheRecipesBase() {
        final MachineTable.Behaviour b = MachineTable.behaviour(DANGOTE);
        assertEquals(List.of("machineParallel", "voltageParallel"), MachineTable.hiddenControlIds(DANGOTE));
        assertEquals(List.of(), MachineTable.controls(DANGOTE, null));
        assertTrue(b.recipeTierFromBase);
        assertTrue(MachineTable.seedsFromBase(DANGOTE));
    }

    @ParameterizedTest(name = "scales a 12-layer distillery at {0} to {1} structural parallels")
    @CsvSource({ "LV, 8", "MV, 16", "HV, 24", "EV, 32", "IV, 40", "LuV, 48", "ZPM, 56" })
    void scalesATwelveLayerDistillery(final String tier, final int parallels) {
        final MachineTable.Behaviour b = MachineTable.behaviour(DANGOTE);
        final MachineTable.Context distillery = ctx(Tiers.index(tier), "Distillery");
        assertEquals(parallels, Math.floor(MachineTable.resolve(b.parallels, distillery, 1)));
        assertEquals(0.5, 1 / MachineTable.resolve(b.speed, distillery, 1));
        assertEquals(0.15, MachineTable.resolve(b.power, distillery, 1));

        final MachineTable.Context tower = ctx(Tiers.index(tier), "Distillation Tower");
        assertEquals(12, Math.floor(MachineTable.resolve(b.parallels, tower, 1)));
        assertEquals(1.0 / 3, 1 / MachineTable.resolve(b.speed, tower, 1));
        assertEquals(1, MachineTable.resolve(b.power, tower, 1));
    }

    // region The height (ours: the website assumes 12 layers, the default)

    /** A card on a game map at IV (voltage tier 5), at these settings, through the whole port. */
    private static NodeMath.Result card(final String mapId, final Map<String, String> settings) {
        return NodeMath
            .compute(MapRecipes.recipe(mapId, 120, 600, 0), MapRecipes.node("dangote-distillus", "IV", settings));
    }

    /** MTEAdvDistillationTower.getMaxParallelRecipes: 2 * floor(layers / 3) * the voltage tier, distillery mode. */
    @ParameterizedTest(name = "a {0}-layer distillery at IV has {1} structural parallels")
    @CsvSource({ "3, 10", "5, 10", "6, 20", "8, 20", "9, 30", "11, 30", "12, 40" })
    void distilleryParallelsFollowTheHeight(final int height, final int parallels) {
        final NodeMath.Result r = card("gt.recipe.distillery", Map.of(DangoteDistillus.HEIGHT, "" + height));
        final Web.Recipe e = r.effectiveRecipe();
        assertEquals(
            parallels,
            MachineEffects.structuralParallels(
                e,
                MapRecipes.node("dangote-distillus", "IV", Map.of(DangoteDistillus.HEIGHT, "" + height))));
        assertEquals(DangoteDistillus.DISTILLERY, DangoteDistillus.mode(e));
    }

    @Test
    void anUnsetHeightIsTheWebsitesTwelveLayers() {
        final Web.Recipe e = card("gt.recipe.distillery", Map.of()).effectiveRecipe();
        assertEquals(40, MachineEffects.structuralParallels(e, MapRecipes.node("dangote-distillus", "IV", Map.of())));
        final RecipeRules.TierControl height = height(e, Map.of());
        assertEquals("12", height.current().key);
        // The website's ladder (its countControl): 3 to 12 layers, the lowest the minimum.
        assertEquals("3", height.minimum().key);
        assertEquals(
            List.of("3", "4", "5", "6", "7", "8", "9", "10", "11", "12"),
            DangoteDistillus.HEIGHT_CONTROL.tiers.stream()
                .map(t -> t.key)
                .toList());
    }

    @Test
    void towerModeHasNoHeightAndTwelveParallels() {
        final Web.Recipe e = card("gt.recipe.distillationtower", Map.of(DangoteDistillus.HEIGHT, "3"))
            .effectiveRecipe();
        assertEquals(DangoteDistillus.TOWER, DangoteDistillus.mode(e));
        assertEquals(List.of(), RecipeRules.configTierControls(e, Map.of()));
        assertEquals(
            12,
            MachineEffects.structuralParallels(
                e,
                MapRecipes.node("dangote-distillus", "IV", Map.of(DangoteDistillus.HEIGHT, "3"))));
    }

    @Test
    void onlyTheDangoteHasAMode() {
        final Web.Recipe e = NodeMath
            .compute(MapRecipes.recipe("gt.recipe.distillery", 120, 600, 0), MapRecipes.node(null, "IV", Map.of()))
            .effectiveRecipe();
        assertNull(DangoteDistillus.mode(e), e.machineType);
    }

    private static RecipeRules.TierControl height(final Web.Recipe effective, final Map<String, String> settings) {
        final List<RecipeRules.TierControl> controls = RecipeRules.configTierControls(effective, settings);
        assertEquals(1, controls.size(), "the distillery offers its height alone");
        assertEquals(
            DangoteDistillus.HEIGHT,
            controls.getFirst()
                .id());
        return controls.getFirst();
    }

    // endregion
}
