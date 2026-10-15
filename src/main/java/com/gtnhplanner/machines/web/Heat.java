package com.gtnhplanner.machines.web;

import javax.annotation.Nullable;

/**
 * The heat overclock (src/lib/solver/heat.ts): machines whose table entry overclocks on heat get a perfect overclock
 * per 1800 K of coil heat over the recipe's requirement and, unless they opt out, 5% off EU/t per 900 K. How the
 * machine heat is found is the entry's {@code heat}: +100 K per voltage tier above MV for the blast furnaces and the
 * Exothermic Hearth, coils counted double on Zyngen.
 */
public final class Heat {

    private Heat() {}

    private static final int MV = 2;

    /** HeatOverclockStats. */
    public record Stats(int heatOverclockSteps, int regularOverclockSteps, double heatDiscountMultiplier) {}

    /** isHeatOverclockMachine: by the machine, never the recipe map. */
    public static boolean isHeatOverclockMachine(@Nullable final String machineType) {
        final MachineTable.Behaviour b = MachineTable.behaviour(machineType);
        return b != null && MachineTable.HEAT.equals(b.overclock);
    }

    /**
     * A coil option's heat in K: its own, else that of the dataset's coil of the same key. The Multi Smelter's, the
     * autoclave's and the alloy blast smelters' coil options carry only what the coil buys there (parallels, speed, a
     * discount), but the block is the same coil, as hot.
     */
    public static double coilHeat(final Web.TierOption coil) {
        if (coil.heat != null && coil.heat > 0) return coil.heat;
        for (final HandlerData.Coil c : HandlerData.coils()) if (c.key()
            .equals(coil.key)) return c.heat();
        return 0;
    }

    /**
     * The heat a recipe asks of its machine's coil, in K; 0 when it asks none. Only a heat overclock machine reads the
     * recipe's special value as heat (the blast furnaces and their kin). Every other coil is no gate: the Multi
     * Smelter's sets its parallels, the cracker's its discount, the pyrolyse oven's its speed (MTEMultiFurnace,
     * MTEOilCracker, MTEPyrolyseOven), and the website asks nothing of it either.
     */
    public static int recipeHeat(final Web.Recipe effective) {
        final Integer special = RecipeRules.specialValue(effective);
        return isHeatOverclockMachine(effective.machineType) && special != null && special > 0 ? special : 0;
    }

    /** Whether a coil of {@code coilHeat} is too cold for a recipe asking {@code recipeHeat} (0: asks nothing). */
    public static boolean tooCold(final double coilHeat, final double recipeHeat) {
        return recipeHeat > 0 && coilHeat < recipeHeat;
    }

    /**
     * getHeatOverclockStats: of {@code overclockSteps}, how many heat pays for, and the discount. The bonus reads
     * {@code voltageOrdinal}, the summed hatches' tier (the tier's own when null).
     */
    public static Stats stats(final Web.Recipe recipe, final Web.Node node, final String tier, final int overclockSteps,
        @Nullable final Integer voltageOrdinal) {
        final Integer specialValue = RecipeRules.specialValue(recipe);
        final RecipeRules.TierControl coil = recipe.machineType != null
            ? RecipeRules.coilTierControl(recipe, node.coilTier)
            : null;
        if (specialValue == null || specialValue < 0
            || coil == null
            || coil.current().heat == null
            || coil.current().heat == 0
            || !isHeatOverclockMachine(recipe.machineType)) return new Stats(0, overclockSteps, 1);

        final MachineTable.Behaviour b = MachineTable.behaviour(recipe.machineType);
        final MachineTable.Heat config = b.heat;
        final double coilHeat = coil.current().heat
            * (config != null && config.coilHeatMultiplier() != null ? config.coilHeatMultiplier() : 1);
        final int ordinal = voltageOrdinal != null ? voltageOrdinal : Tiers.index(tier);
        final double machineHeat = config != null && Boolean.TRUE.equals(config.voltageBonus())
            ? coilHeat + 100 * Math.max(0, ordinal - MV)
            : coilHeat;
        final double excess = Math.max(0, machineHeat - specialValue);
        final int heatSteps = (int) Math.min(overclockSteps, Math.floor(excess / 1800));
        return new Stats(
            heatSteps,
            overclockSteps - heatSteps,
            config != null && Boolean.FALSE.equals(config.discount()) ? 1 : Math.pow(0.95, Math.floor(excess / 900)));
    }

    /** getHeatDiscountMultiplier: the discount alone, which parallels are paid with before the overclock. */
    public static double discount(final Web.Recipe recipe, final Web.Node node, final String tier,
        @Nullable final Integer voltageOrdinal) {
        return stats(recipe, node, tier, 0, voltageOrdinal).heatDiscountMultiplier();
    }
}
