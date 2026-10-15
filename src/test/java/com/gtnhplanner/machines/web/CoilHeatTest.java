package com.gtnhplanner.machines.web;

import static com.gtnhplanner.machines.web.MapRecipes.recipe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * A card's heating coil as the board reads it (machines/game/WebSettings.coil): every coil a machine can take has its
 * heat, and only a heat machine's recipe asks heat of it. Issue 6: the Multi Smelter's coil options carry its parallels
 * and no heat, so its card read 0 K and said the coil was too cold for a recipe that asks none.
 */
class CoilHeatTest {

    private static Web.Node node(final String handlerId) {
        return MapRecipes.node(handlerId, "IV", Map.of());
    }

    @Test
    void theMultiSmeltersCoilIsHotAndAsksNothing() {
        final Web.Recipe recipe = recipe("smelting", 0, 200, 0);
        final NodeMath.Result r = NodeMath.compute(recipe, node("multi-smelter"));
        final Web.Recipe e = r.effectiveRecipe();
        assertEquals("Multi Smelter", e.machineType);
        final RecipeRules.TierControl coil = RecipeRules.coilTierControl(e, null);
        assertNotNull(coil, "the Multi Smelter has a coil");
        assertEquals("cupronickel", coil.current().key);
        assertEquals(1801, Heat.coilHeat(coil.current()));
        assertEquals(0, Heat.recipeHeat(e), "a furnace recipe asks no heat");
        assertFalse(Heat.tooCold(Heat.coilHeat(coil.current()), Heat.recipeHeat(e)));
        // MTEMultiFurnace: the coil is its parallels, 8 doubled per tier above cupronickel.
        assertEquals(8, r.machineParallels());
        final Web.Node kanthal = node("multi-smelter");
        kanthal.coilTier = "kanthal";
        assertEquals(
            16,
            NodeMath.compute(recipe, kanthal)
                .machineParallels());
    }

    @Test
    void aBlastFurnaceRecipeAsksItsHeat() {
        final Web.Recipe recipe = recipe("gt.recipe.blastfurnace", 120, 1000, 4500);
        final Web.Recipe e = NodeMath.compute(recipe, node("electric-blast-furnace"))
            .effectiveRecipe();
        assertEquals(4500, Heat.recipeHeat(e));
        final RecipeRules.TierControl coil = RecipeRules.coilTierControl(e, null);
        // The first coil hot enough is the least the card offers, as on the website.
        assertEquals("tpv", coil.current().key);
        assertFalse(Heat.tooCold(Heat.coilHeat(coil.current()), Heat.recipeHeat(e)));
        assertTrue(Heat.tooCold(2701, Heat.recipeHeat(e)), "a Kanthal coil is too cold for 4500 K");
        assertFalse(Heat.tooCold(0, 0), "no heat asked, no coil needed");
    }

    /**
     * Every machine of every map with a coil: each of its coils has a heat, and none but a heat machine asks heat of
     * it, so no card but a heat machine's can read its coil as too cold (the pyrolyse oven, the crackers, the Mega
     * Alloy Blast Smelter, the autoclave, the Multi Smelter...).
     */
    @Test
    void everyCoilHasItsHeatAndOnlyHeatMachinesAskIt() {
        final List<String> checked = new ArrayList<>();
        for (final HandlerData.MapEntry entry : HandlerData.maps()
            .values()) {
            if (entry.handlers == null) continue;
            final double special = entry.coilFromSpecialValue ? 1800 : 0;
            final Web.Recipe recipe = recipe(entry.id, 120, 200, special);
            for (final HandlerData.Template t : entry.handlers) {
                final Web.Node node = node(t.id);
                if (!NodeMath.covers(recipe, node)) continue;
                final Web.Recipe e = NodeMath.compute(recipe, node)
                    .effectiveRecipe();
                final RecipeRules.TierControl coil = RecipeRules.coilTierControl(e, null);
                if (coil == null) continue;
                checked.add(e.machineType);
                for (final Web.TierOption option : coil.tiers()) assertTrue(
                    Heat.coilHeat(option) > 1800,
                    e.machineType + " on " + entry.id + ": " + option.key + " has no heat");
                if (!Heat.isHeatOverclockMachine(e.machineType))
                    assertEquals(0, Heat.recipeHeat(e), e.machineType + " asks no heat");
                assertFalse(
                    Heat.tooCold(Heat.coilHeat(coil.current()), Heat.recipeHeat(e)),
                    e.machineType + " on " + entry.id + " is not too cold at its first coil");
            }
        }
        for (final String machine : List.of(
            "Multi Smelter",
            "Pyrolyse Oven",
            "Oil Cracking Unit",
            "Mega Oil Cracker",
            "Industrial Autoclave",
            "Mega Alloy Blast Smelter",
            "Industrial Coke Oven",
            "Chemical Plant",
            "Large Thermal Refinery",
            "Electric Blast Furnace")) assertTrue(checked.contains(machine), machine + " was checked: " + checked);
    }
}
