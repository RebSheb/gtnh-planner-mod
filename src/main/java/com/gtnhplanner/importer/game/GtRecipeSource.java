package com.gtnhplanner.importer.game;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.gtnhplanner.data.RecipeHandlerAccess;
import com.gtnhplanner.importer.FfIds;
import com.gtnhplanner.importer.FfPlan.FfRecipe;
import com.gtnhplanner.importer.RecipeIndex.GameRecipe;
import com.gtnhplanner.importer.RecipeIndex.GameStack;
import com.gtnhplanner.importer.RecipeIndex.Lookup;
import com.gtnhplanner.machines.game.WebCards;
import com.gtnhplanner.machines.web.HandlerData;

import codechicken.nei.recipe.GuiCraftingRecipe;
import codechicken.nei.recipe.ICraftingHandler;
import codechicken.nei.recipe.TemplateRecipeHandler;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTRecipeConstants;
import gregtech.common.items.ItemFluidDisplay;
import gregtech.nei.GTNEIDefaultHandler;
import gregtech.nei.GTNEIDefaultHandler.CachedDefaultRecipe;

/**
 * GregTech recipes by recipe map, as NEI lists them. GT class references, isolated: only call behind
 * {@code Compat.GREGTECH.isLoaded}.
 *
 * <p>
 * GregTech registers one NEI handler per recipe category; asking one for its map's unlocalized name
 * ({@code getRecipeHandler("gt.recipe.largechemicalreactor")}) loads every recipe of that category, the way NEI's
 * own "all recipes" view does. A node built from such a handler and index is the node NEI's + would have made.
 */
final class GtRecipeSource {

    private static final double GT_CHANCE_SCALE = 10_000.0;

    private GtRecipeSource() {}

    /** Every recipe of the recipe's map (all its categories), read once per map into {@code cache}. */
    static Lookup find(final FfRecipe recipe, final Map<String, List<GameRecipe>> cache) {
        final List<String> maps = mapsFor(recipe);
        if (maps.isEmpty()) {
            final String named = recipe.gameMapId() != null ? recipe.gameMapId() : recipe.machineType();
            return Lookup.none("no GregTech recipe map " + named + " in game");
        }
        final List<GameRecipe> all = new ArrayList<>();
        for (final String map : maps) all.addAll(cache.computeIfAbsent(map, GtRecipeSource::read));
        return Lookup.of(all);
    }

    /**
     * The in-game maps an FF recipe names: by unlocalized name (the website's, whose GT++ maps are gtpp.recipe.*, as
     * well as the game's) or FF's slug of it; failing both (an old plan), by the NEI page's name, which is FF's machine
     * type. A recipe the game posted before it named its map (0.2.0-alpha.5 and earlier) names the machine block
     * instead: the maps that block works, by NEI's catalysts.
     */
    private static List<String> mapsFor(final FfRecipe recipe) {
        final String id = recipe.gameMapId(), slug = recipe.gameMapSlug();
        final Set<String> exact = new LinkedHashSet<>(), named = new LinkedHashSet<>();
        final boolean byMachine = id == null && slug == null && recipe.fromGame();
        for (final ICraftingHandler h : handlers()) {
            if (!(h instanceof final GTNEIDefaultHandler g) || g.getRecipeMap() == null) continue;
            final String map = g.getRecipeMap().unlocalizedName;
            if (sameMap(map, id, slug)) exact.add(map);
            else if (h.getRecipeName()
                .trim()
                .equalsIgnoreCase(recipe.machineType())
                || byMachine && NeiRecipeIndex.runsOn(h, recipe.machineType())) named.add(map);
        }
        return new ArrayList<>(exact.isEmpty() ? named : exact);
    }

    /** Whether the game's map is the one a plan names, by its id or the website's id for it, or by FF's slug. */
    private static boolean sameMap(final String map, @Nullable final String id, @Nullable final String slug) {
        if (id == null && slug == null) return false;
        if (map.equals(id) || slug != null && FfIds.slug(map)
            .equals(slug)) return true;
        final HandlerData.MapEntry site = WebCards.entry(map);
        return site != null && !site.id.equals(map)
            && (site.id.equals(id) || slug != null && FfIds.slug(site.id)
                .equals(slug));
    }

    private static List<ICraftingHandler> handlers() {
        final List<ICraftingHandler> all = new ArrayList<>(GuiCraftingRecipe.craftinghandlers);
        all.addAll(GuiCraftingRecipe.serialCraftingHandlers);
        return all;
    }

    private static List<GameRecipe> read(final String map) {
        final List<GameRecipe> out = new ArrayList<>();
        for (final ICraftingHandler proto : handlers()) {
            if (!(proto instanceof final GTNEIDefaultHandler g) || g.getRecipeMap() == null
                || !map.equals(g.getRecipeMap().unlocalizedName)) continue;
            if (!(g.getRecipeHandler(map) instanceof final GTNEIDefaultHandler loaded)) continue;
            final List<TemplateRecipeHandler.CachedRecipe> cached = RecipeHandlerAccess.getArecipes(loaded);
            for (int i = 0; i < cached.size(); i++) {
                if (cached.get(i) instanceof final CachedDefaultRecipe c && c.mRecipe != null)
                    out.add(of(loaded, i, c.mRecipe));
            }
        }
        return out;
    }

    /** A GregTech recipe as plain data, read the way GTProvider reads it for a node's ports. */
    private static GameRecipe of(final GTNEIDefaultHandler handler, final int index, final GTRecipe r) {
        final List<GameStack> inputs = new ArrayList<>(), outputs = new ArrayList<>();
        if (r.mInputs != null) for (final ItemStack s : r.mInputs) {
            if (s == null || s.getItem() == null || s.getItem() instanceof ItemFluidDisplay) continue;
            // GregTech keeps what a recipe does not use up (a circuit, a mold) as a stack of 0.
            final boolean consumed = s.stackSize > 0;
            inputs.add(new GameStack("item", GameIds.itemIds(s), consumed ? s.stackSize : 1, 1, consumed));
        }
        if (r.mFluidInputs != null) for (final FluidStack f : r.mFluidInputs) {
            if (f == null || f.getFluid() == null || f.amount <= 0) continue;
            inputs.add(new GameStack("fluid", List.of(GameIds.fluidId(f)), f.amount, 1, true));
        }
        if (r.mOutputs != null) for (int i = 0; i < r.mOutputs.length; i++) {
            final ItemStack s = r.mOutputs[i];
            if (s == null || s.getItem() == null || s.getItem() instanceof ItemFluidDisplay) continue;
            outputs.add(
                new GameStack("item", GameIds.itemIds(s), s.stackSize, r.getOutputChance(i) / GT_CHANCE_SCALE, true));
        }
        if (r.mFluidOutputs != null) for (int i = 0; i < r.mFluidOutputs.length; i++) {
            final FluidStack f = r.mFluidOutputs[i];
            if (f == null || f.getFluid() == null || f.amount <= 0) continue;
            outputs.add(
                new GameStack(
                    "fluid",
                    List.of(GameIds.fluidId(f)),
                    f.amount,
                    r.getFluidOutputChance(i) / GT_CHANCE_SCALE,
                    true));
        }
        // Newer GregTech keeps a coil recipe's heat in metadata, older in the special value; FF's dataset has it as
        // the special value either way.
        int special = r.mSpecialValue;
        if (special == 0) {
            final Integer heat = r.getMetadataOrDefault(GTRecipeConstants.COIL_HEAT, 0);
            if (heat != null) special = heat;
        }
        return new GameRecipe(
            handler,
            index,
            handler.getRecipeName()
                .trim(),
            inputs,
            outputs,
            r.mDuration,
            (long) Math.max(0, r.mEUt),
            special);
    }
}
