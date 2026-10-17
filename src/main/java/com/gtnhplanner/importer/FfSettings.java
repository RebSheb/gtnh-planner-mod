package com.gtnhplanner.importer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.gtnhplanner.data.Settings;
import com.gtnhplanner.importer.FfPlan.FfHandler;
import com.gtnhplanner.importer.FfPlan.FfNode;
import com.gtnhplanner.importer.FfPlan.FfRecipe;

/**
 * An FF card's machine settings as GTNH Planner machine settings: the tier and amps it runs at, the multiblock flag,
 * the coil, the energy hatch type, every machine option (the website's machineConfigTiers, as {@code machine:<id>};
 * the Tree Growth Simulator's and Bacterial Vat's under their own keys), and the machine count.
 *
 * <p>
 * FF reads a single block's tier off the card, floored at the recipe's own tier; a multiblock's off its energy
 * hatches (tier and amps), or off a raw EU/t budget, as FF's power.ts does. Amps are whole, up to the website's
 * 16,777,216.
 */
public final class FfSettings {

    /** GregTech's tiers in setting order. */
    public static final String[] TIERS = { "ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV", "UHV", "UEV", "UIV",
        "UMV", "UXV", "MAX" };

    /** FF's coil keys and their heat (FF's machine-configs.mjs heatingCoilTiers; GregTech's HeatingCoilLevel). */
    public static final Map<String, Integer> COIL_HEAT = new LinkedHashMap<>();

    static {
        final String[] coils = { "cupronickel", "kanthal", "nichrome", "tpv", "hss_g", "hss_s", "naquadah",
            "naquadah_alloy", "trinium", "electrum_flux", "awakened_draconium", "infinity", "hypogen", "eternal" };
        for (int i = 0; i < coils.length; i++) COIL_HEAT.put(coils[i], 1801 + 900 * i);
    }

    private static final int MAX_AMPS = 16_777_216;
    private static final int MAX_MACHINES = 4096;
    /** A crop card counts crops planted: an Industrial Farm alone works over a thousand. */
    private static final int MAX_CROPS = 1_000_000;

    /**
     * FF's isSteamMachineHandler: every machine named "Steam ..." or "High Pressure ..." burns steam, never EU, so it
     * has no tier to run at.
     */
    private static final java.util.regex.Pattern STEAM = java.util.regex.Pattern
        .compile("\\bsteam\\b|\\bhigh[ -]pressure\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Whether FF's machine (by its name, else its id) is one of the steam line's. */
    public static boolean steam(@Nullable final String label, @Nullable final String handlerId) {
        final String name = label != null ? label : handlerId;
        return name != null && STEAM.matcher(name)
            .find();
    }

    /** The card settings for the website's coil, hatch type and machine options (machines/game/WebCards). */
    public static final String COIL = "coil", HATCH_TYPE = "energy_hatch_type", MACHINE = "machine:";

    /**
     * @param settings to put on the node
     * @param machines the machine count to put on the node (crops planted, on a crop card)
     * @param pinned   whether that count is fixed (FF's Solve-mode pin)
     * @param machine  the machine FF runs the recipe on, for picking it in game
     * @param notes    what did not carry over exactly
     */
    public record Mapped(Map<String, Object> settings, int machines, boolean pinned, NodeMaker.Machine machine,
        List<String> notes) {

        @Nullable
        public String machineLabel() {
            return machine.label();
        }
    }

    private FfSettings() {}

    /**
     * @param euPerTick the recipe's EU/t (the game's when known), for the single-block tier floor
     * @param solveMode a Solve or Pool plan: machine counts are the answer, and only a pin is a fixed count
     * @param pinnable  false for the extra recipes of a shared machine, which never carry the card's pin
     */
    public static Mapped map(final FfNode node, final FfRecipe recipe, final long euPerTick, final boolean solveMode,
        final boolean pinnable) {
        final Map<String, Object> s = new LinkedHashMap<>();
        final List<String> notes = new ArrayList<>();
        final FfHandler handler = recipe.handler(node.machineHandlerId());
        // A recipe that lists no machines (the game's own posts up to 0.2.0-alpha.5) names its block as its machine
        // type, and gives hatches only to a multiblock.
        final boolean oldPost = handler == null && recipe.fromGame() && recipe.gregtech();
        final boolean multiblock = handler != null ? handler.multiblock() : oldPost && node.hatchVoltageTier() != null;
        final String handlerId = handler != null ? handler.id() : node.machineHandlerId();
        final String label = handler != null ? handler.label()
            : oldPost && !recipe.machineType()
                .isEmpty() ? recipe.machineType() : null;
        final NodeMaker.Machine machine = new NodeMaker.Machine(handlerId, label);
        final boolean crop = recipe.isCrop();

        int tier = -1;
        double amps = 1;
        if (crop) {
            // A crop's harvester (a Crop Manager or the Industrial Farm) is FF's machine and our setting (CropsNh); it
            // has no voltage of its own.
            if (handlerId != null) s.put(com.gtnhplanner.machines.CropsNh.HARVESTER, handlerId);
        } else if (recipe.isBee()) {
            // The card's own machine: a recipe the mod posted lists only the Apiary, which the website puts first.
            bee(node, node.machineHandlerId() != null ? node.machineHandlerId() : handlerId, s, notes);
        } else if (steam(machine.label(), handlerId)) {
            // Steam runs at no voltage (FF hides the tier chip): a tier the card still carries is left behind.
            s.put(Settings.GT_MULTIBLOCK.key(), multiblock);
        } else if (multiblock) {
            final Double budget = node.powerEuT();
            if ("eut".equals(node.powerInputMode()) && budget != null && budget > 0) {
                tier = tierWithin(budget);
                amps = budget / voltage(tier);
            } else if (tierIndex(node.hatchVoltageTier()) >= 0) {
                tier = tierIndex(node.hatchVoltageTier());
                amps = node.hatchAmps() == null ? 1 : node.hatchAmps();
            } else if (budget != null && budget > 0) {
                tier = tierWithin(budget);
                amps = budget / voltage(tier);
            } else {
                // A legacy card: its tier, one hatch at 1 A, more at 2 A each (FF's getHatchAmps).
                tier = tierIndex(node.overclockTier());
                amps = node.energyHatches() <= 1 ? 1 : 2.0 * node.energyHatches();
            }
            s.put(Settings.GT_MULTIBLOCK.key(), true);
        } else {
            // Said outright: the board's default picks a multiblock when the recipe's first machine is one.
            if (handler != null) s.put(Settings.GT_MULTIBLOCK.key(), false);
            final int picked = tierIndex(node.overclockTier());
            if (picked >= 0) tier = Math.max(picked, recipeTier(euPerTick));
        }
        if (tier >= 0) {
            if (multiblock) {
                if (amps <= 0) amps = 1;
                final String asSet = fmt(amps) + " A " + TIERS[tier];
                final int setTier = tier;
                while (amps > MAX_AMPS && tier < TIERS.length - 1) {
                    amps /= 4;
                    tier++;
                }
                final int whole = (int) Math.max(1, Math.min(MAX_AMPS, Math.round(amps)));
                if (tier != setTier || Math.abs(whole - amps) > 1e-6)
                    notes.add("hatches " + asSet + " carried as " + whole + " A " + TIERS[tier]);
                s.put(Settings.AMP.key(), whole);
            }
            s.put(Settings.VOLTAGE.key(), TIERS[tier]);
        }

        if (node.coilTier() != null) {
            if (COIL_HEAT.containsKey(node.coilTier())) s.put(COIL, node.coilTier());
            else notes.add("unknown coil '" + node.coilTier() + "' left at the default");
        }
        if (node.energyHatchType() != null) s.put(HATCH_TYPE, node.energyHatchType());
        if (node.parallel() > 1) notes.add(node.parallel() + " parallels on the card not carried over");
        // Every machine option, under the website's ids: the modelled machines' under their own keys, the rest as
        // machine:<id> (machines/game/WebCards).
        for (final Map.Entry<String, String> option : node.machineConfigTiers()
            .entrySet()) {
            s.put(modelled(option.getKey()) ? option.getKey() : MACHINE + option.getKey(), option.getValue());
        }

        final int most = crop ? MAX_CROPS : MAX_MACHINES;
        int machines = 1;
        boolean pinned = false;
        if (solveMode) {
            final Double pin = pinnable ? node.solvePin() : null;
            if (pin != null && pin > 0) {
                machines = (int) Math.max(1, Math.min(most, Math.round(pin)));
                pinned = true;
                if (Math.abs(machines - pin) > 1e-6)
                    notes.add("pinned " + fmt(pin) + " machines, rounded to " + machines);
            } else if (pin != null) {
                notes.add("pinned to 0 machines; left for the plan to decide");
            }
        } else {
            // Build mode: the count is the build, and the plan's flows follow from it, so it comes over as a pin (left
            // as is, a board that solves counts would have nothing to solve for and show every rate at 0).
            machines = (int) Math.max(1, Math.min(most, Math.ceil(node.machineCount() - 1e-9)));
            pinned = pinnable;
        }
        return new Mapped(s, machines, pinned, machine, notes);
    }

    /**
     * Whether the website's card keeps a setting (its tier, amps, coil, hatches, machine options, a crop's harvester,
     * a bee's housing): what {@link #map} puts on a node. Any other setting is the game's own.
     */
    public static boolean carried(final String key) {
        return Settings.VOLTAGE.key()
            .equals(key)
            || Settings.AMP.key()
                .equals(key)
            || Settings.GT_MULTIBLOCK.key()
                .equals(key)
            || COIL.equals(key)
            || HATCH_TYPE.equals(key)
            || key.startsWith(MACHINE)
            || modelled(key)
            || com.gtnhplanner.machines.CropsNh.HARVESTER.equals(key)
            || com.gtnhplanner.machines.Bees.HOUSING.equals(key)
            || com.gtnhplanner.machines.Bees.SPEED.equals(key);
    }

    /**
     * Whether a machine option is one of the modelled machines' (machines/), kept under its own key; every other
     * machine's goes under {@code machine:<id>}.
     */
    public static boolean modelled(final String key) {
        return com.gtnhplanner.machines.TreeGrowthSimulator.SETTING_KEYS.contains(key)
            || com.gtnhplanner.machines.BacterialVat.SETTING_KEYS.contains(key)
            || com.gtnhplanner.machines.CropsNh.SETTING_KEYS.contains(key)
            || com.gtnhplanner.machines.Bees.SETTING_KEYS.contains(key);
    }

    /**
     * A bee's housing, FF's machine and our setting (machines/Bees), with no voltage of its own. FF's card with no
     * machine is its first, the Magic Apiary; an Alveary comes in as an Apiary and a Mega Apiary as an Industrial
     * Apiary, the nearest the mod models. Its speed gene unset is FF's Normal (ours is the species' own).
     */
    private static void bee(final FfNode node, @Nullable final String handlerId, final Map<String, Object> s,
        final List<String> notes) {
        final String id = handlerId == null ? com.gtnhplanner.machines.Bees.MAGIC_APIARY.key() : handlerId;
        com.gtnhplanner.machines.Bees.Housing housing = com.gtnhplanner.machines.Bees.housing(id);
        if (housing == null) {
            housing = id.contains("mega") ? com.gtnhplanner.machines.Bees.INDUSTRIAL
                : com.gtnhplanner.machines.Bees.APIARY;
            notes.add("its " + id + " came in as an " + housing.label());
        }
        s.put(com.gtnhplanner.machines.Bees.HOUSING, housing.key());
        if (!node.machineConfigTiers()
            .containsKey(com.gtnhplanner.machines.Bees.SPEED)) s.put(com.gtnhplanner.machines.Bees.SPEED, "normal");
    }

    /** The tier's index, or -1 for "NONE", "DEMO" and anything else that is not a tier. */
    public static int tierIndex(@Nullable final String name) {
        if (name == null) return -1;
        for (int i = 0; i < TIERS.length; i++) if (TIERS[i].equalsIgnoreCase(name)) return i;
        return -1;
    }

    /** One amp of the tier, in EU/t (8 * 4^tier; MAX is GregTech's Integer.MAX_VALUE - 7). */
    public static long voltage(final int tier) {
        return tier >= TIERS.length - 1 ? Integer.MAX_VALUE - 7L : 8L << (2 * tier);
    }

    /** The lowest tier whose voltage covers the EU/t (FF's getVoltageTierForEuT). */
    public static int recipeTier(final long euPerTick) {
        int tier = 0;
        while (tier < TIERS.length - 1 && voltage(tier) < euPerTick) tier++;
        return tier;
    }

    /** The highest tier whose voltage fits inside a budget (FF's getVoltageTierWithinEuT), ULV at least. */
    public static int tierWithin(final double euPerTick) {
        int tier = 0;
        for (int i = 0; i < TIERS.length; i++) if (voltage(i) <= euPerTick) tier = i;
        return tier;
    }

    private static String fmt(final double d) {
        if (d == Math.rint(d)) return String.valueOf((long) d);
        return String.valueOf(Math.round(d * 100) / 100.0);
    }
}
