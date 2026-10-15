package com.gtnhplanner.machines.web;

import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

/**
 * GT++'s Dangote Distillus (MTEAdvDistillationTower), checked against the game's code. Its mode is the recipe's map,
 * as on the website: a Distillery recipe runs in distillery mode (x2 speed, 15% EU, 2 parallels per voltage tier for
 * every 3 layers of height), a Distillation Tower recipe in tower mode (x3 speed, 12 parallels whatever the height).
 *
 * <p>
 * The height is ours: the website assumes the full 12 layers, which is the setting's default, so its numbers are the
 * website's until a player lowers it. getMaxParallelRecipes: {@code 2 * floor((mHeight + 1) / 3f) * getTier(voltage)}
 * in distillery mode, where {@code mHeight + 1}, the layers, runs 3 to 12 (the STRUCTURE_HEIGHT channel's range).
 */
public final class DangoteDistillus {

    private DangoteDistillus() {}

    public static final String MACHINE = "Dangote Distillus";

    /** The height setting's id (a card's {@code machine:distillusHeight}, the website's machineConfigTiers key). */
    public static final String HEIGHT = "distillusHeight";
    public static final int MIN_HEIGHT = 3, MAX_HEIGHT = 12;

    /** The modes, as the game names them. */
    public static final String DISTILLERY = "Distillery", TOWER = "Distillation Tower";

    /** The height, 3 to 12 layers; 12 unless set. Only distillery mode reads it. */
    public static final Web.Control HEIGHT_CONTROL;

    static {
        final Web.Control control = MachineControls.countControl(HEIGHT, "Height", MAX_HEIGHT);
        final Web.Numeric numeric = new Web.Numeric();
        numeric.min = MIN_HEIGHT;
        numeric.max = (double) MAX_HEIGHT;
        numeric.step = 1.0;
        control.numeric = numeric;
        HEIGHT_CONTROL = control;
    }

    /** Whether a recipe map (the website's name for it) runs the machine in distillery mode. */
    public static boolean distillery(@Nullable final String recipeMap) {
        return recipeMap != null && recipeMap.toLowerCase(Locale.ROOT)
            .equals("distillery");
    }

    /** The mode a recipe runs the machine in, or null when the recipe is not the Dangote Distillus's. */
    @Nullable
    public static String mode(final Web.Recipe effective) {
        if (!MACHINE.equals(effective.machineType)) return null;
        return distillery(RecipeRules.recipeMapName(effective)) ? DISTILLERY : TOWER;
    }

    /** The settings a recipe offers: the height in distillery mode, nothing in tower mode. */
    public static List<Web.Control> controls(final Web.Recipe recipe) {
        return distillery(RecipeRules.recipeMapName(recipe)) ? List.of(HEIGHT_CONTROL) : List.of();
    }

    /** The height a setting reads as: whole layers from 3 to 12, the full 12 when unset. */
    public static int height(final double value) {
        if (!(value >= MIN_HEIGHT)) return MAX_HEIGHT;
        return (int) Math.min(MAX_HEIGHT, Math.floor(value));
    }

    /** The structure's parallels: 12 in tower mode; 2 per voltage tier for every 3 layers in distillery mode. */
    public static double parallels(final boolean distillery, final int voltageTier, final int height) {
        return distillery ? 2 * Math.floor(height / 3.0) * voltageTier : 12;
    }
}
