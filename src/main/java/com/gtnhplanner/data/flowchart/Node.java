package com.gtnhplanner.data.flowchart;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import com.gtnhplanner.api.RecipePropertyAPI;
import com.gtnhplanner.data.MachineConfig;
import com.gtnhplanner.data.MachineProfileRegistry;
import com.gtnhplanner.data.properties.PropertyProvider;
import com.gtnhplanner.data.properties.RecipeProperty;
import com.gtnhplanner.data.provider.DefaultProvider;
import com.gtnhplanner.power.PowerModel;
import com.gtnhplanner.power.game.PowerPorts;

import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.Recipe;
import codechicken.nei.recipe.RecipeHandlerRef;
import lombok.Getter;
import lombok.Setter;

public class Node {

    /**
     * Set only while the node is built, before any graph holds it: a reader that keeps a shared plan's ids
     * (cloud/map) gives a card the importer made the id its plan knows it by.
     */
    public UUID id;
    public int x;
    public int y;

    /** The board group it is in ({@link BoardGroup}), or null. Every recipe of a shared machine is in its card's. */
    @Nullable
    public UUID group;

    public final List<Port<?>> inputs = new ArrayList<>();
    public final List<Port<?>> outputs = new ArrayList<>();

    public String machineName;

    public Recipe.RecipeId recipeId;
    public int handlerRecipeIndex;

    public final MachineConfig machineConfig;
    public final Map<RecipeProperty<?>, Object> properties = new HashMap<>();

    @Setter
    private boolean machineCountFixed;

    /**
     * Whether the machine count is pinned. Never for a fluid tank: it has no count to show or clear (it keeps up with
     * the plan), so a pin on it, from a website Build plan or an older save, would force cells through the plan with
     * nothing shown as set.
     */
    public boolean isMachineCountFixed() {
        return machineCountFixed && !com.gtnhplanner.power.FluidTank.ID.equals(powerSource);
    }

    /**
     * Target production rates by output port index, in ingredient units per second. A target is
     * a pin: AUTO holds the machine's extent so the targeted output hits the rate exactly, and
     * the rest of the chart follows. With several targets on one machine the largest implied
     * extent wins - parallel outputs share one extent, so only the tightest can be exact.
     */
    public final Map<Integer, Double> targetOutputRates = new LinkedHashMap<>();

    /**
     * Target consumption rates by input port index, units per second: pins as the output targets are (the machine's
     * extent is held so the input takes the rate exactly), the largest implied extent winning among all its targets.
     */
    public final Map<Integer, Double> targetInputRates = new LinkedHashMap<>();

    /**
     * Which item an input whose recipe slot takes several (an ore dictionary slot, GregTech's alternatives) is set to,
     * by input port index: the chosen alternative's resource key ({@link Port#key()}). The website's
     * {@code recipeInputOverrides}. A pick the port no longer lists does nothing and is kept.
     */
    public final Map<Integer, String> inputPicks = new TreeMap<>();

    /**
     * A non-recipe machine (a generator): its power source id ({@link com.gtnhplanner.power.PowerRegistry}), null on a
     * recipe card. Its ports come from the source's model at {@link #powerSettings}, not from NEI.
     */
    @Nullable
    public String powerSource;
    /** A power card's settings by setting id, as strings (the website's machineConfigTiers). */
    public final Map<String, String> powerSettings = new LinkedHashMap<>();
    /** The power card's model at its settings, rebuilt by {@link #refresh()}; null on a recipe card. */
    @Nullable
    public transient PowerModel powerModel;

    /**
     * The blocks in the world this card's machines are, each {dimension, x, y, z}: set by linking the card to them in
     * game, so the plan can point at its machines and they at it. Saved with the plan, never copied with the card.
     */
    public final List<int[]> worldLinks = new ArrayList<>();

    /**
     * A shared plan's placements in other worlds, by world ({@code cloud/map/game/GameCloud.world}): kept, never shown,
     * so the plan carries them. {@link #worldLinks} are this world's. Never in a local save.
     */
    public final transient java.util.SortedMap<String, List<int[]>> elsewhere = new java.util.TreeMap<>();

    /** The recipe page this node was made from, for its first read (a page NEI cannot find again by id). */
    private transient RecipeHandlerRef madeFrom;
    @Getter
    private transient PropertyProvider extractor;
    @Getter
    private transient List<PropertyProvider> availableExtractors = List.of();
    @Getter
    @Setter
    private int extractorIndex;

    public Node(final IRecipeHandler handler, final int recipeIndex, final int x, final int y) {
        this.id = UUID.randomUUID();
        this.x = x;
        this.y = y;

        this.machineName = handler.getRecipeName()
            .trim();
        this.recipeId = Recipe.RecipeId.of(handler, recipeIndex);
        this.handlerRecipeIndex = recipeIndex;
        this.madeFrom = RecipeHandlerRef.of(handler, recipeIndex);

        this.availableExtractors = RecipePropertyAPI.getExtractors(handler.getClass());
        this.extractorIndex = 0;
        if (availableExtractors.isEmpty()) {
            this.extractor = DefaultProvider.INSTANCE;
        } else {
            this.extractor = pickBestExtractor(handler, recipeIndex);
        }

        final String pid = this.extractor.getProfileId(handler, recipeIndex);
        if (pid != null && !MachineProfileRegistry.defaultId()
            .equals(pid)) {
            this.machineConfig = new MachineConfig(this, MachineProfileRegistry.get(pid));
        } else {
            this.machineConfig = new MachineConfig(this);
        }

        refresh();
        machineConfig.seedRouteDefaults();
    }

    /** Whether this is a non-recipe machine (a generator) rather than a recipe from NEI. */
    public boolean isPower() {
        return powerSource != null;
    }

    /** A new power card for a source at the given settings (missing ones read as the source's defaults). */
    public static Node power(final String sourceId, final Map<String, String> settings, final int x, final int y) {
        final Node node = new Node(UUID.randomUUID(), x, y);
        node.powerSource = sourceId;
        node.powerSettings.putAll(settings);
        node.refresh();
        return node;
    }

    public void refresh() {
        if (powerSource != null) {
            PowerPorts.build(this);
            return;
        }
        if (extractor == null) return;
        // The page it was made from, while there is one; else found again by its id. Not found: left as it is.
        RecipeHandlerRef ref = madeFrom != null ? madeFrom : com.gtnhplanner.nei.RecipeRefs.of(recipeId);
        if (ref == null) return;
        final IRecipeHandler handler = ref.handler;
        final int recipeIndex = ref.recipeIndex;
        inputs.clear();
        outputs.clear();
        properties.clear();

        final Map<RecipeProperty<?>, Object> props = extractor.extract(this, handler, recipeIndex);
        if (props != null && !props.isEmpty()) {
            this.properties.putAll(props);
        }

        readAlternatives(handler, recipeIndex);
        deduplicate(inputs, false);
        deduplicate(outputs, true);
        applyPicks();
        afterRefresh.accept(this);
    }

    /**
     * Each item input's alternatives: every item NEI shows in the slot the input's item is in (an ore dictionary
     * slot's members, GregTech's alternatives), the port's own first, never a wildcard.
     */
    private void readAlternatives(final IRecipeHandler handler, final int recipeIndex) {
        final List<PositionedStack> slots;
        try {
            slots = handler.getIngredientStacks(recipeIndex);
        } catch (final RuntimeException e) {
            return;
        }
        if (slots == null) return;
        for (final Port<?> port : inputs) {
            if (!(port.getValue() instanceof final ItemStack own) || own.getItem() == null) continue;
            for (final PositionedStack slot : slots) {
                if (slot == null || slot.items == null || slot.items.length < 2 || !holds(slot, own)) continue;
                final List<ItemStack> items = new ArrayList<>();
                items.add(own.copy());
                for (final ItemStack s : slot.items) {
                    if (s == null || s.getItem() == null
                        || s.getItemDamage() == net.minecraftforge.oredict.OreDictionary.WILDCARD_VALUE) continue;
                    boolean seen = false;
                    for (final ItemStack had : items) seen |= had.getItem() == s.getItem()
                        && had.getItemDamage() == s.getItemDamage();
                    if (!seen) items.add(s.copy());
                }
                if (items.size() > 1) setAlternatives(port, items);
                break;
            }
        }
    }

    /** Whether a slot shows the item among its permutations (a wildcard permutation holds every meta). */
    private static boolean holds(final PositionedStack slot, final ItemStack item) {
        for (final ItemStack s : slot.items) {
            if (s == null || s.getItem() != item.getItem()) continue;
            if (s.getItemDamage() == item.getItemDamage()
                || s.getItemDamage() == net.minecraftforge.oredict.OreDictionary.WILDCARD_VALUE) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static void setAlternatives(final Port<?> port, final List<ItemStack> items) {
        ((Port<ItemStack>) port).setAlternatives(items);
    }

    /**
     * Sets each input to the item picked for it ({@link #inputPicks}), and an input with no pick back to the recipe's
     * own. Ports keep their place, so wires and drawers on them keep theirs.
     */
    public void applyPicks() {
        for (int i = 0; i < inputs.size(); i++) {
            final Port<?> port = inputs.get(i);
            if (!port.hasChoices()) continue;
            final String want = inputPicks.get(i);
            final Object to = want == null ? port.own() : port.alternative(want);
            if (to != null && !port.keyOf(to)
                .equals(port.key())) inputs.set(i, port.withAlternative(to));
        }
    }

    /**
     * Sets input {@code index} to the alternative with {@code key}: null, or the recipe's own item, clears the pick.
     * False, changing nothing, when the port has no such alternative or already holds it.
     */
    public boolean pickInput(final int index, @Nullable final String key) {
        if (index < 0 || index >= inputs.size()) return false;
        final Port<?> port = inputs.get(index);
        if (!port.hasChoices()) return false;
        final Object to = key == null ? port.own() : port.alternative(key);
        if (to == null) return false;
        if (port.keyOf(to)
            .equals(port.keyOf(port.own()))) inputPicks.remove(index);
        else inputPicks.put(index, port.keyOf(to));
        if (port.keyOf(to)
            .equals(port.key())) return false;
        applyPicks();
        return true;
    }

    /**
     * Set by the game side (GTProvider): adjusts a recipe node once its ports are read, as the modelled machines do
     * (machines/game/MachineModels). Does nothing without the game, as in the headless tests.
     */
    public static java.util.function.Consumer<Node> afterRefresh = node -> {};

    /**
     * Folds ports of one resource into one. Inputs fold by what wires to them (an ore name: a recipe that takes any
     * plank twice takes two planks); outputs only when they are the same resource ({@code same}), since two items that
     * share an ore name are both made: Lazulia's Lapis Lazuli folded into its Lapis Dust and was lost (issue 487).
     */
    static void deduplicate(final List<Port<?>> ports, final boolean same) {
        final List<Port<?>> aggregate = new ArrayList<>(ports);
        for (int i = 0; i < aggregate.size(); i++) {
            for (int j = i + 1; j < aggregate.size(); j++) {
                if (same ? aggregate.get(i)
                    .same(aggregate.get(j))
                    : aggregate.get(i)
                        .canConnect(aggregate.get(j))) {
                    aggregate.get(i)
                        .merge(aggregate.get(j));
                    aggregate.remove(j);
                    j--;
                }
            }
        }

        ports.clear();
        ports.addAll(aggregate);
    }

    public void switchExtractor() {
        if (availableExtractors.size() < 2) return;
        extractorIndex = (extractorIndex + 1) % availableExtractors.size();
        this.extractor = availableExtractors.get(extractorIndex);

        final RecipeHandlerRef ref = com.gtnhplanner.nei.RecipeRefs.of(recipeId);
        if (ref != null) {
            final String pid = extractor.getProfileId(ref.handler, ref.recipeIndex);
            if (pid != null && !MachineProfileRegistry.defaultId()
                .equals(pid)) {
                this.machineConfig.profileId = pid;
            } else {
                this.machineConfig.profileId = MachineProfileRegistry.defaultId();
            }
        }

        refresh();
        machineConfig.seedRouteDefaults();
    }

    private PropertyProvider pickBestExtractor(final IRecipeHandler handler, final int recipeIndex) {
        for (final PropertyProvider p : availableExtractors) {
            if (p.canCraft(handler, recipeIndex)) return p;
        }
        return availableExtractors.getFirst();
    }

    public int getRecipeDuration() {
        return (int) properties.getOrDefault(RecipePropertyAPI.DURATION_TICKS, 0);
    }

    public void initExtractor() {
        final RecipeHandlerRef ref = com.gtnhplanner.nei.RecipeRefs.of(recipeId);
        if (ref == null) {
            this.extractor = null;
            this.availableExtractors = List.of();
            return;
        }
        this.availableExtractors = RecipePropertyAPI.getExtractors(ref.handler.getClass());
        if (this.extractorIndex >= this.availableExtractors.size()) {
            this.extractorIndex = 0;
        }
        this.extractor = this.availableExtractors.isEmpty() ? DefaultProvider.INSTANCE
            : this.availableExtractors.get(this.extractorIndex);
    }

    /**
     * To be used only for serialization/deserialization
     */
    public Node(final UUID id, final int x, final int y) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.machineConfig = new MachineConfig(this);
    }
}
