package com.gtnhplanner.importer.game;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.fluids.FluidStack;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.data.MachineConfig;
import com.gtnhplanner.data.SettingDef;
import com.gtnhplanner.data.Settings;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.importer.FfIds;
import com.gtnhplanner.importer.FfSettings;
import com.gtnhplanner.importer.NodeMaker;
import com.gtnhplanner.importer.RecipeIndex.GameRecipe;
import com.gtnhplanner.machines.game.WebCards;
import com.gtnhplanner.machines.web.RecipeRules;
import com.gtnhplanner.ui.Resources;
import com.gtnhplanner.ui.card.CardDefaults;
import com.gtnhplanner.ui.card.CardModel;
import com.gtnhplanner.ui.gt.GtMachines;

import codechicken.nei.recipe.IRecipeHandler;

/**
 * Real nodes for the importer: built from the NEI recipe the way NEI's + builds them ({@link CardDefaults} included),
 * then given the machine Factory Flow ran when the recipe's NEI page offers it. Client thread only.
 */
public final class GameNodeMaker implements NodeMaker {

    @Override
    @Nullable
    public Node make(final GameRecipe recipe, @Nullable final Machine machine) {
        if (!(recipe.handler() instanceof final IRecipeHandler handler)) return null;
        final Node node;
        try {
            node = new Node(handler, recipe.index(), 0, 0);
        } catch (final RuntimeException e) {
            GtnhPlanner.LOG.warn("Factory Flow import: no node for {} #{}", recipe.handlerName(), recipe.index(), e);
            return null;
        }
        CardDefaults.apply(node);
        pickMachine(node, machine == null ? new Machine(null, null) : machine);
        return node;
    }

    /**
     * The machine FF ran, among those NEI lists for the recipe: by the blocks the website's data gives its id (the
     * GregTech single blocks' every tier, a steam machine, a multiblock), else by its name or its family's (each tier
     * of a single block is named for its tier: "Advanced Extractor II" is an Extractor). Nothing found, a GregTech card
     * keeps NEI's first machine unless that runs on steam, which FF only ever runs when it says so.
     */
    private static void pickMachine(final Node node, final Machine machine) {
        final boolean gregtech = CardModel.GT_PROFILE.equals(node.machineConfig.profileId);
        final List<ItemStack> catalysts = CardModel.catalystsOf(node);
        ItemStack pick = null;
        if (gregtech) {
            final List<String> items = WebCards.handlerItems(node, machine.handlerId());
            for (final ItemStack s : catalysts) if (items.contains(GameIds.itemId(s))) {
                pick = s;
                break;
            }
        }
        if (pick == null && machine.label() != null) {
            final String want = FfIds.slug(machine.label());
            final boolean steamWanted = FfSettings.steam(machine.label(), machine.handlerId());
            for (final ItemStack s : catalysts) {
                final String name = EnumChatFormatting.getTextWithoutFormattingCodes(s.getDisplayName());
                if (name == null || gregtech && steam(s) != steamWanted) continue;
                if (FfIds.slug(name)
                    .equals(want)
                    || gregtech && FfIds.slug(familyOf(s))
                        .equals(want)) {
                    pick = s;
                    break;
                }
            }
        }
        if (pick == null && gregtech && steam(WebCards.machineStack(node))) {
            for (final ItemStack s : catalysts) if (!steam(s)) {
                pick = s;
                break;
            }
        }
        if (pick != null) CardDefaults.useMachine(node, pick, gregtech);
    }

    private static boolean steam(@Nullable final ItemStack machine) {
        final GtMachines.Kind kind = GtMachines.of(machine);
        return kind != null && kind.steam();
    }

    @Override
    public PortInfo describe(final Port<?> port) {
        final Object value = port.getValue();
        final String key = Resources.key(port), label = port.getDisplayName();
        if (value instanceof final FluidStack fluid && fluid.getFluid() != null)
            return new PortInfo("fluid", key, label, List.of(GameIds.fluidId(fluid)));
        if (value instanceof final ItemStack stack && stack.getItem() != null)
            return new PortInfo("item", key, label, GameIds.itemIds(stack));
        if (value instanceof com.gtnhplanner.power.Energy)
            return new PortInfo("power", key, label, List.of("eu"));
        return new PortInfo("", key, label, List.of());
    }

    @Override
    public Node makePower(final String sourceId, final Map<String, String> settings) {
        return com.gtnhplanner.power.PowerRegistry.get(sourceId) == null ? null : Node.power(sourceId, settings, 0, 0);
    }

    @Override
    public Node makeCustomRate(final Map<String, String> settings) {
        final Node node = Node.power(com.gtnhplanner.power.CustomRate.ID, settings, 0, 0);
        return node.inputs.isEmpty() && node.outputs.isEmpty() ? null : node;
    }

    @Override
    public Node makeTank(final boolean emptying, final String cellId) {
        final ItemStack cell = GameIds.stackOf(cellId);
        if (cell == null) return null;
        final String key = CardDefaults.itemKey(cell);
        if (com.gtnhplanner.power.FluidTank.pair(key) == null) return null;
        final Map<String, String> settings = new java.util.LinkedHashMap<>();
        settings.put(
            com.gtnhplanner.power.FluidTank.MODE,
            emptying ? com.gtnhplanner.power.FluidTank.EMPTY : com.gtnhplanner.power.FluidTank.FILL);
        settings.put(com.gtnhplanner.power.FluidTank.CELL, key);
        return Node.power(com.gtnhplanner.power.FluidTank.ID, settings, 0, 0);
    }

    /**
     * Only the settings the node's machine profile has, and the website's own a card keeps beside them: the coil, the
     * hatch type and the machine options ({@code machine:<id>}, machines/game/WebSettings) and a modelled machine's
     * (the TGS's, the vat's, a bee's, a crop's); a crafting card takes its machine count and nothing else.
     */
    @Override
    public void applySettings(final Node node, final Map<String, Object> settings) {
        final MachineConfig cfg = node.machineConfig;
        final Set<String> known = new HashSet<>();
        for (final SettingDef<?> def : cfg.getProfile()
            .settings()) known.add(def.key);
        known.add(Settings.MACHINES.key());
        final boolean machine = CardModel.GT_PROFILE.equals(cfg.profileId)
            || com.gtnhplanner.machines.game.MachineModels.isModelled(node);
        for (final Map.Entry<String, Object> e : settings.entrySet()) {
            final String key = e.getKey();
            if (known.contains(key) || machine && (com.gtnhplanner.machines.game.WebSettings.isKey(key)
                || com.gtnhplanner.importer.FfSettings.modelled(key))) cfg.settings.put(key, e.getValue());
        }
        if (CardModel.GT_PROFILE.equals(cfg.profileId)) retier(node);
        // What the settings decide at refresh (a modelled machine's multipliers, machines/game/MachineModels).
        node.refresh();
    }

    /**
     * A GregTech single block's tier is its machine (each tier its own block, the tier chip steps between them, as on
     * a card added from NEI): the block of its family at the card's tier, else the nearest above, else the highest,
     * which then sets the tier.
     */
    private static void retier(final Node node) {
        final ItemStack now = WebCards.machineStack(node);
        if (!single(now)) return;
        final int want = Math.max(0, CardDefaults.tierIndex(CardDefaults.stringSetting(node.machineConfig, "voltage")));
        final List<String> family = WebCards.machineItems(node, GameIds.itemId(now));
        final String name = familyOf(now);
        ItemStack best = null;
        int bestTier = -1;
        for (final ItemStack s : CardModel.catalystsOf(node)) {
            if (!single(s) || !(family.isEmpty() ? familyOf(s).equals(name) : family.contains(GameIds.itemId(s))))
                continue;
            final int t = GtMachines.of(s)
                .tier();
            final boolean better;
            if (best == null) better = true;
            else if (t >= want != bestTier >= want) better = t >= want;
            else better = t >= want ? t < bestTier : t > bestTier;
            if (better) {
                best = s;
                bestTier = t;
            }
        }
        if (best != null) CardDefaults.useMachine(node, best, true);
    }

    private static String familyOf(final ItemStack machine) {
        final String name = EnumChatFormatting.getTextWithoutFormattingCodes(machine.getDisplayName());
        return RecipeRules.familyLabel(name == null ? "" : name);
    }

    /** One of GregTech's electric single blocks (not steam, not a multiblock), at a known tier. */
    private static boolean single(@Nullable final ItemStack machine) {
        final GtMachines.Kind kind = GtMachines.of(machine);
        return kind != null && !kind.multiblock() && !kind.steam() && kind.tier() >= 0;
    }
}
