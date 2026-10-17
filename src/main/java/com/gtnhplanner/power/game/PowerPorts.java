package com.gtnhplanner.power.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.gtnhplanner.api.RecipePropertyAPI;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.power.Energy;
import com.gtnhplanner.power.PowerModel;
import com.gtnhplanner.power.PowerRegistry;
import com.gtnhplanner.power.PowerResources;
import com.gtnhplanner.power.PowerSource;
import com.gtnhplanner.power.sources.Helpers;

/**
 * A power card's ports, from its source's model at the card's settings (the website's buildPowerRecipe): a power card
 * runs one craft a second, so a port's amount per craft is its rate per second, exact. EU leads the outputs. A flow
 * the resource map does not know, or this game does not have, is no port: the card lists it with its stats.
 */
public final class PowerPorts {

    /** Ticks per craft of every power card. */
    public static final int DURATION_TICKS = 20;

    /** Rebuilds a power node's model and ports from its source and settings. */
    public static void build(final Node node) {
        if (com.gtnhplanner.power.CustomRate.is(node)) {
            com.gtnhplanner.power.CustomRate.build(node);
            return;
        }
        if (com.gtnhplanner.power.FluidTank.is(node)) {
            com.gtnhplanner.power.FluidTank.build(node);
            return;
        }
        node.inputs.clear();
        node.outputs.clear();
        node.properties.clear();
        final PowerSource source = PowerRegistry.get(node.powerSource);
        if (source == null) {
            node.powerModel = null;
            return;
        }
        final PowerModel model = source.compute(node.powerSettings);
        node.powerModel = model;
        node.machineName = source.name();
        node.properties.put(RecipePropertyAPI.DURATION_TICKS, DURATION_TICKS);
        if (model.euPerTick() > 0) {
            final double perSecond = model.euPerTick() * 20;
            final Port<Energy> eu = new Port<>(RecipePropertyAPI.POWER, new Energy(perSecond), 1f);
            eu.setExactAmount(perSecond);
            node.outputs.add(eu);
        }
        for (final PowerModel.Flow flow : model.inputs()) {
            final Port<?> port = port(flow);
            if (port != null) node.inputs.add(port);
        }
        for (final PowerModel.Flow flow : model.outputs()) {
            final Port<?> port = port(flow);
            if (port != null) node.outputs.add(port);
        }
    }

    /** The flows that are not ports, as stat lines: "Name", "1.5 L/s in" (unresolvedFlowStats). */
    public static List<PowerModel.Stat> unported(final PowerModel model) {
        final List<PowerModel.Stat> lines = new ArrayList<>();
        addUnported(model.inputs(), "in", lines);
        addUnported(model.outputs(), "out", lines);
        return lines;
    }

    private static void addUnported(final List<PowerModel.Flow> flows, final String direction,
        final List<PowerModel.Stat> lines) {
        for (final PowerModel.Flow flow : flows) {
            if (!(flow.perSecond() > 0) || resolve(flow.name()) != null) continue;
            lines.add(
                new PowerModel.Stat(
                    flow.name(),
                    Helpers.formatAmount(flow.perSecond()) + (flow.unit() == PowerModel.Unit.L ? " L" : "")
                        + "/s "
                        + direction));
        }
    }

    @Nullable
    private static Port<?> port(final PowerModel.Flow flow) {
        if (!(flow.perSecond() > 0)) return null;
        final Object value = resolve(flow.name());
        if (value instanceof final FluidStack fluid) {
            final FluidStack stack = fluid.copy();
            stack.amount = (int) Math.max(1, Math.min(Integer.MAX_VALUE, Math.round(flow.perSecond())));
            final Port<FluidStack> port = new Port<>(RecipePropertyAPI.FLUID, stack, 1f);
            port.setExactAmount(flow.perSecond());
            return port;
        }
        if (value instanceof final ItemStack item) {
            final ItemStack stack = item.copy();
            stack.stackSize = (int) Math.max(1, Math.min(Integer.MAX_VALUE, Math.round(flow.perSecond())));
            final Port<ItemStack> port = new Port<>(RecipePropertyAPI.ITEM, stack, 1f);
            port.setExactAmount(flow.perSecond());
            return port;
        }
        return null;
    }

    private static final Map<String, Object> RESOLVED = new HashMap<>();
    private static final Object MISSING = new Object();

    /** The game's item or fluid (one of it) for a workbook flow name; null when the map or the game lacks it. */
    @Nullable
    public static Object resolve(final String name) {
        final PowerResources.Ref ref = PowerResources.resolve(name);
        return ref == null ? null : ref.fluid() ? fluid(ref.id) : item(ref.id);
    }

    /** The machine item a source is drawn as, or null. */
    @Nullable
    public static ItemStack machineStack(final String sourceId) {
        // The fluid tank is GregTech's LV Fluid Tank, the block (its picture, and its ghost when placed in the world);
        // a pack without it draws an empty cell.
        if (com.gtnhplanner.power.FluidTank.ID.equals(sourceId)) {
            final ItemStack tank = item("gregtech:gt.blockmachines@818");
            return tank != null && tank.getDisplayName()
                .contains("Tank") ? tank : item("IC2:itemCellEmpty");
        }
        final PowerResources.Ref ref = PowerResources.machineIcon(sourceId);
        return ref == null ? null : item(ref.id);
    }

    /** A fluid by registry name, one bucket's worth. */
    @Nullable
    public static FluidStack fluid(final String id) {
        final Object found = RESOLVED.computeIfAbsent("fluid:" + id, k -> {
            final Fluid fluid = FluidRegistry.getFluid(id);
            return fluid == null ? MISSING : new FluidStack(fluid, 1000);
        });
        return found instanceof final FluidStack fluid ? fluid.copy() : null;
    }

    /** An item by the website's id, {@code mod:name@meta} (no {@code @} for meta 0). */
    @Nullable
    public static ItemStack item(final String id) {
        final Object found = RESOLVED.computeIfAbsent("item:" + id, k -> {
            final int at = id.lastIndexOf('@');
            final String name = at < 0 ? id : id.substring(0, at);
            int meta = 0;
            if (at >= 0) {
                try {
                    meta = Integer.parseInt(id.substring(at + 1));
                } catch (final NumberFormatException e) {
                    return MISSING;
                }
            }
            Object item = Item.itemRegistry.getObject(name);
            // The website writes mod ids lower case ("railcraft:"); the registry keeps the mod's own ("Railcraft:").
            if (!(item instanceof Item)) for (final Object key : Item.itemRegistry.getKeys()) {
                if (String.valueOf(key)
                    .equalsIgnoreCase(name)) {
                    item = Item.itemRegistry.getObject(key);
                    break;
                }
            }
            return item instanceof final Item registered ? new ItemStack(registered, 1, meta) : MISSING;
        });
        return found instanceof final ItemStack stack ? stack.copy() : null;
    }

    private PowerPorts() {}
}
