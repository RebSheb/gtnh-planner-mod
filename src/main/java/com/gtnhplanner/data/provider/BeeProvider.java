package com.gtnhplanner.data.provider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.api.RecipePropertyAPI;
import com.gtnhplanner.data.MachineProfile;
import com.gtnhplanner.data.MachineProfileRegistry;
import com.gtnhplanner.data.SettingDef;
import com.gtnhplanner.data.Settings;
import com.gtnhplanner.data.effect.Effects;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.data.properties.PropertyProvider;
import com.gtnhplanner.data.properties.RecipeProperty;
import com.gtnhplanner.machines.Bees;
import com.gtnhplanner.machines.game.BeeCards;
import com.gtnhplanner.machines.game.MachineModels;

import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.Recipe;
import codechicken.nei.recipe.RecipeHandlerRef;
import codechicken.nei.recipe.TemplateRecipeHandler;
import forestry.api.apiculture.BeeManager;
import forestry.api.apiculture.IAlleleBeeSpecies;
import forestry.api.apiculture.IBee;
import forestry.api.apiculture.IBeeGenome;

/**
 * Bees, from NEI's bee products page (NEI Addons' "beeproduce", read by name: it is not compiled against) and
 * Forestry's own species: a card per species, pure-bred, its products and specialties at their base chances, the speed
 * and lifespan of its genes, and whether only a Mega Apiary pleases it. The bee is never used up, so it is not a port;
 * the card's housing and settings decide the rates (machines/Bees, BeeCards after every refresh).
 */
public final class BeeProvider implements PropertyProvider {

    public static final String PROFILE = "forestry:bees";
    /** NEI Addons' bee products page. */
    private static final String HANDLER = "net.bdew.neiaddons.forestry.bees.BeeProduceHandler";

    @Nullable
    private static Class<?> handlerClass;

    @Override
    public void register() {
        if (!com.gtnhplanner.Compat.FORESTRY.isLoaded) return;
        try {
            handlerClass = Class.forName(HANDLER);
        } catch (final ClassNotFoundException | LinkageError e) {
            GtnhPlanner.LOG.info("[compat] no bee products page in NEI; bees are not read");
            return;
        }
        RecipePropertyAPI.registerExtractor(handlerClass, this);
        // NEI finds a recipe again by its result, and many species make the same comb: the species finds its own.
        com.gtnhplanner.nei.RecipeRefs.addFallback(BeeProvider::findAgain);
        final MachineProfile.Builder builder = MachineProfile.builder(PROFILE, "Bees")
            .setting(Settings.MACHINES.def());
        for (final String key : Bees.SETTING_KEYS)
            builder.setting(SettingDef.enumDef(key, "", List.of(), (v, c) -> null));
        MachineProfileRegistry.register(
            builder.effect(
                Effects.durationFromHandler()
                    .andThen(new BeeCards.Step()))
                .build());
    }

    @Override
    public boolean canCraft(final IRecipeHandler handler, final int recipeIndex) {
        return species(handler, recipeIndex) != null;
    }

    @Override
    @Nullable
    public String getProfileId(final IRecipeHandler handler, final int recipeIndex) {
        return species(handler, recipeIndex) != null ? PROFILE : null;
    }

    @Override
    @Nonnull
    public Map<RecipeProperty<?>, Object> extract(final Node node, final IRecipeHandler handler,
        final int recipeIndex) {
        final Read read = read(handler, recipeIndex);
        if (read == null) return PropertyProvider.super.extract(node, handler, recipeIndex);
        node.inputs.clear();
        node.outputs.clear();
        final List<ItemStack> stacks = new ArrayList<>();
        final List<String> ids = new ArrayList<>();
        for (int i = 0; i < read.species()
            .products()
            .size(); i++) {
            final String id = read.species()
                .products()
                .get(i)
                .id();
            if (ids.contains(id)) continue;
            ids.add(id);
            final ItemStack one = read.stacks()
                .get(i)
                .copy();
            one.stackSize = 1;
            stacks.add(one);
            node.outputs.add(new Port<>(RecipePropertyAPI.ITEM, one.copy(), 1f));
        }
        final Map<RecipeProperty<?>, Object> props = new HashMap<>();
        props.put(BeeCards.SPECIES, BeeCards.write(read.species()));
        props.put(BeeCards.STACKS, List.copyOf(stacks));
        props.put(RecipePropertyAPI.DURATION_TICKS, Bees.CYCLE_TICKS);
        return props;
    }

    /** A species as read, with each product's item in the same order. */
    private record Read(Bees.Species species, List<ItemStack> stacks) {}

    /** The species a recipe of the bee products page shows; null for anything else. */
    @Nullable
    public static Bees.Species species(final IRecipeHandler handler, final int recipeIndex) {
        final Read read = read(handler, recipeIndex);
        return read == null ? null : read.species();
    }

    @Nullable
    private static Read read(final IRecipeHandler handler, final int recipeIndex) {
        if (handlerClass == null || !handlerClass.isInstance(handler)) return null;
        try {
            final List<PositionedStack> ingredients = handler.getIngredientStacks(recipeIndex);
            if (ingredients == null || ingredients.isEmpty() || ingredients.get(0) == null) return null;
            final IBee bee = BeeManager.beeRoot.getMember(ingredients.get(0).item);
            if (bee == null) return null;
            final IBeeGenome genome = bee.getGenome();
            final IAlleleBeeSpecies primary = genome.getPrimary();
            final List<Bees.Product> products = new ArrayList<>();
            final List<ItemStack> stacks = new ArrayList<>();
            add(primary.getProductChances(), false, products, stacks);
            add(primary.getSpecialtyChances(), true, products, stacks);
            if (products.isEmpty()) return null;
            return new Read(
                new Bees.Species(
                    primary.getUID(),
                    primary.getName(),
                    genome.getSpeed(),
                    genome.getLifespan(),
                    megaOnly(primary),
                    products),
                stacks);
        } catch (final RuntimeException | LinkageError e) {
            GtnhPlanner.LOG.debug("Bees: no species on recipe {}", recipeIndex, e);
            return null;
        }
    }

    private static void add(final Map<ItemStack, Float> chances, final boolean specialty,
        final List<Bees.Product> products, final List<ItemStack> stacks) {
        if (chances == null) return;
        for (final Map.Entry<ItemStack, Float> e : chances.entrySet()) {
            final ItemStack stack = e.getKey();
            if (stack == null || stack.getItem() == null || e.getValue() == null || e.getValue() <= 0) continue;
            products.add(
                new Bees.Product(
                    MachineModels.websiteId(stack),
                    stack.getDisplayName(),
                    Math.max(1, stack.stackSize),
                    e.getValue(),
                    specialty));
            stacks.add(stack);
        }
    }

    /**
     * Whether the species' specialties come only in a Mega Apiary (GregTech's JubilanceMegaApiary). Read by reflection:
     * older Forestry builds have no getJubilanceProvider.
     */
    private static boolean megaOnly(final IAlleleBeeSpecies species) {
        try {
            final Object provider = species.getClass()
                .getMethod("getJubilanceProvider")
                .invoke(species);
            return provider != null && provider.getClass()
                .getSimpleName()
                .contains("MegaApiary");
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    // region Finding a species again

    /** Every species, on a bee products page of our own, loaded once. */
    private static TemplateRecipeHandler allBees;

    @Nullable
    private static TemplateRecipeHandler allBees() {
        if (allBees == null && handlerClass != null) {
            try {
                final TemplateRecipeHandler h = (TemplateRecipeHandler) handlerClass.getDeclaredConstructor()
                    .newInstance();
                h.loadCraftingRecipes("beeproduce");
                if (!h.arecipes.isEmpty()) allBees = h;
            } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
                GtnhPlanner.LOG.warn("Bees: the bee products page could not be read", e);
            }
        }
        return allBees;
    }

    @Nullable
    private static RecipeHandlerRef findAgain(final Recipe.RecipeId id) {
        final TemplateRecipeHandler bees = allBees();
        if (bees == null) return null;
        for (int i = 0; i < bees.arecipes.size(); i++) if (Recipe.RecipeId.of(bees, i)
            .equals(id)) return RecipeHandlerRef.of(bees, i);
        return null;
    }

    /** The bee products page's recipe for the species whose Forestry id gives {@code matches}; null when none does. */
    @Nullable
    public static RecipeHandlerRef bee(final Predicate<String> matches) {
        final TemplateRecipeHandler bees = allBees();
        if (bees == null) return null;
        for (int i = 0; i < bees.arecipes.size(); i++) {
            final Bees.Species s = species(bees, i);
            if (s != null && matches.test(s.uid())) return RecipeHandlerRef.of(bees, i);
        }
        return null;
    }

    // endregion
}
