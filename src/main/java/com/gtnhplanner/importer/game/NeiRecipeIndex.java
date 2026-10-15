package com.gtnhplanner.importer.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;

import com.google.gson.JsonParser;
import com.gtnhplanner.Compat;
import com.gtnhplanner.importer.FfIds;
import com.gtnhplanner.importer.FfPlan.FfRecipe;
import com.gtnhplanner.importer.FfPlan.FfSlot;
import com.gtnhplanner.importer.RecipeIndex;
import com.gtnhplanner.nei.RecipeRefs;

import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.GuiCraftingRecipe;
import codechicken.nei.recipe.ICraftingHandler;
import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.Recipe;
import codechicken.nei.recipe.RecipeCatalysts;
import codechicken.nei.recipe.RecipeHandlerRef;

/**
 * NEI's recipes for the importer. A plan posted from the game names each recipe by NEI's own id, which finds the very
 * recipe in the same pack; otherwise GregTech recipes come from their recipe map (see {@link GtRecipeSource}), crafting
 * and smelting recipes from NEI's crafting and furnace pages for the recipe's first output. One per import: it keeps
 * each recipe map it has read. Client thread only.
 */
public final class NeiRecipeIndex implements RecipeIndex {

    private final Map<String, List<GameRecipe>> gregtechMaps = new HashMap<>();

    @Override
    public Lookup find(final FfRecipe recipe) {
        // A plan posted from the game names the very recipe, as a saved plan does: found the same way.
        if (recipe.neiRecipe() != null) {
            final Lookup own = byNeiId(recipe.neiRecipe());
            if (own != null) return own;
        }
        if ("crafting".equals(recipe.category()) || recipe.machineType()
            .endsWith(" Crafting")) return byOutput(recipe, h -> "crafting".equals(h.getOverlayIdentifier()));
        if ("furnace".equals(recipe.category()) || "Furnace".equals(recipe.machineType()))
            return byOutput(recipe, h -> "smelting".equals(h.getOverlayIdentifier()));
        if (recipe.isCrop()) return crop(recipe);
        if (recipe.isBee()) return bee(recipe);
        if (recipe.gregtech()) {
            Lookup found;
            if (!Compat.GREGTECH.isLoaded) found = Lookup.none("GregTech is not loaded");
            else try {
                found = GtRecipeSource.find(recipe, gregtechMaps);
            } catch (final LinkageError e) {
                found = Lookup.none("GregTech's recipes could not be read (" + e + ")");
            }
            // The game's older posts call every card a machine recipe: one on another mod's page is found by the
            // machine it names.
            if (found.candidates()
                .isEmpty() && recipe.fromGame()) {
                final Lookup other = byOutput(recipe, h -> runsOn(h, recipe.machineType()));
                if (!other.candidates()
                    .isEmpty()) return other;
            }
            return found;
        }
        final String kind = (recipe.kind()
            .isEmpty() ? recipe.category() : recipe.kind()).replace('_', ' ');
        return Lookup.none("its " + (kind.isEmpty() ? recipe.machineType() : kind) + " cards have no recipe in game");
    }

    /**
     * The recipe NEI's own id names (what a saved card keeps; a plan posted from the game carries it), or null when
     * this game has no such recipe (another pack version), so it is looked for as any other.
     */
    @Nullable
    private static Lookup byNeiId(final String json) {
        final RecipeHandlerRef ref;
        try {
            ref = RecipeRefs.of(
                Recipe.RecipeId.of(
                    new JsonParser().parse(json)
                        .getAsJsonObject()));
        } catch (final RuntimeException | LinkageError e) {
            return null;
        }
        if (ref == null || ref.handler == null) return null;
        return Lookup.exact(
            new GameRecipe(
                ref.handler,
                ref.recipeIndex,
                ref.handler.getRecipeName()
                    .trim(),
                List.of(),
                List.of(),
                null,
                null,
                null));
    }

    /** Whether one of the machines NEI shows for a page (its catalysts) is the one named. */
    static boolean runsOn(final IRecipeHandler handler, final String machine) {
        if (machine.isEmpty()) return false;
        try {
            for (final PositionedStack ps : RecipeCatalysts.getRecipeCatalysts(handler)) {
                if (ps == null || ps.item == null || ps.item.getItem() == null) continue;
                final String name = EnumChatFormatting.getTextWithoutFormattingCodes(ps.item.getDisplayName());
                if (name != null && name.trim()
                    .equalsIgnoreCase(machine.trim())) return true;
            }
        } catch (final RuntimeException ignored) {
            // A page whose machines cannot be listed runs nothing by name.
        }
        return false;
    }

    /** A CropsNH crop: its recipe on CropsNH's Crops page, found by the crop's id (FF's crop farm card names it). */
    private static Lookup crop(final FfRecipe recipe) {
        if (!Compat.CROPSNH.isLoaded) return Lookup.none("CropsNH is not in this game");
        final String slug = recipe.cropSlug();
        if (slug == null) return Lookup.none("an empty crop farm, with no crop picked");
        final codechicken.nei.recipe.RecipeHandlerRef ref;
        try {
            ref = com.gtnhplanner.data.provider.CropsNhProvider.crop(
                id -> FfIds.slug(id)
                    .equals(slug));
        } catch (final LinkageError e) {
            return Lookup.none("CropsNH's crops could not be read (" + e + ")");
        }
        if (ref == null) return Lookup.none("no crop " + slug + " in this game");
        return Lookup.of(
            List.of(
                new GameRecipe(
                    ref.handler,
                    ref.recipeIndex,
                    ref.handler.getRecipeName()
                        .trim(),
                    List.of(),
                    List.of(),
                    null,
                    null,
                    null)));
    }

    /** A bee: its species on NEI's bee products page, found by the species' Forestry id (FF's bee card names it). */
    private static Lookup bee(final FfRecipe recipe) {
        if (!Compat.BEES.isLoaded || !Compat.FORESTRY.isLoaded) return Lookup.none("bees are not in this game");
        final String slug = recipe.beeSlug();
        if (slug == null) return Lookup.none("a bee card with no species");
        final codechicken.nei.recipe.RecipeHandlerRef ref;
        try {
            ref = com.gtnhplanner.data.provider.BeeProvider.bee(
                uid -> FfIds.slug(uid)
                    .equals(slug));
        } catch (final LinkageError e) {
            return Lookup.none("the bees could not be read (" + e + ")");
        }
        if (ref == null) return Lookup.none("no bee " + slug + " in this game");
        return Lookup.of(
            List.of(
                new GameRecipe(
                    ref.handler,
                    ref.recipeIndex,
                    ref.handler.getRecipeName()
                        .trim(),
                    List.of(),
                    List.of(),
                    null,
                    null,
                    null)));
    }

    /**
     * Every recipe on the NEI pages {@code page} takes (crafting, smelting...) that makes the recipe's first output.
     */
    private static Lookup byOutput(final FfRecipe recipe, final Predicate<ICraftingHandler> page) {
        if (recipe.outputs()
            .isEmpty()) return Lookup.none("it makes nothing");
        final FfSlot out = recipe.outputs()
            .getFirst();
        final ItemStack stack = GameIds.stackOf(out.id());
        if (stack == null) return Lookup.none("no item " + out.id() + " in game");
        final List<GameRecipe> found = new ArrayList<>();
        for (final ICraftingHandler handler : GuiCraftingRecipe.getCraftingHandlers("item", stack)) {
            if (!page.test(handler)) continue;
            for (int i = 0; i < handler.numRecipes(); i++) found.add(read(handler, i));
        }
        return Lookup.of(found);
    }

    /** One NEI recipe as plain data: a stack per ingredient slot, every permutation's ids on it; no timing. */
    private static GameRecipe read(final ICraftingHandler handler, final int index) {
        final List<GameStack> inputs = new ArrayList<>();
        for (final PositionedStack ps : handler.getIngredientStacks(index)) {
            final GameStack s = stack(ps);
            if (s != null) inputs.add(s);
        }
        final List<GameStack> outputs = new ArrayList<>();
        final GameStack result = stack(handler.getResultStack(index));
        if (result != null) outputs.add(result);
        return new GameRecipe(
            handler,
            index,
            handler.getRecipeName()
                .trim(),
            inputs,
            outputs,
            null,
            null,
            null);
    }

    @Nullable
    private static GameStack stack(@Nullable final PositionedStack ps) {
        if (ps == null || ps.item == null || ps.item.getItem() == null) return null;
        final List<String> ids = new ArrayList<>(GameIds.itemIds(ps.item));
        if (ps.items != null) for (final ItemStack alt : ps.items) {
            if (alt == null || alt.getItem() == null) continue;
            for (final String id : GameIds.itemIds(alt)) if (!ids.contains(id)) ids.add(id);
        }
        return new GameStack("item", ids, Math.max(1, ps.item.stackSize), 1, true);
    }
}
