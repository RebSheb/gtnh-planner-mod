package com.gtnhplanner.machines.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.gtnhplanner.data.MachineConfig;
import com.gtnhplanner.data.RecipeContext;
import com.gtnhplanner.data.effect.EffectResult;
import com.gtnhplanner.data.effect.EffectStep;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.properties.RecipeProperty;
import com.gtnhplanner.machines.CropsNh;
import com.gtnhplanner.machines.CropsNh.Setup;
import com.gtnhplanner.machines.FormulaLine;
import com.gtnhplanner.machines.game.MachineModels.Option;
import com.gtnhplanner.machines.game.MachineModels.Setting;

/**
 * The game's side of a crop card (machines/CropsNh, the website's model): the crop's facts on the node (read from
 * CropsNH by CropsNhProvider), the harvest time, the solver's per-drop multipliers after every refresh, the harvesters
 * the planted crops fill and their draw, and the card's settings and formulas, under the website's keys. Knows nothing
 * of CropsNH's classes, so it loads without the mod.
 */
public final class CropCards {

    private CropCards() {}

    /** The crop's facts ({@link #write}) and its CropsNH id. */
    public static final RecipeProperty<String> STATS = RecipeProperty.<String>builder("gtnhplanner.crop.stats", "")
        .build();
    public static final RecipeProperty<String> CROP_ID = RecipeProperty.<String>builder("gtnhplanner.crop.id", "")
        .build();

    public static boolean isCrop(final Node node) {
        return node != null && !node.isPower() && stats(node) != null;
    }

    public static boolean isCrop(final Map<RecipeProperty<?>, Object> props) {
        return props.get(STATS) instanceof final String s && !s.isEmpty();
    }

    // region The crop's facts

    /** tier;points;dropChance;cycle;multiplier;machineOnly;minSeedBed;subSoil;id=stack=weight|... */
    public static String write(final CropsNh.Stats s) {
        final StringBuilder b = new StringBuilder();
        b.append(s.tier())
            .append(';')
            .append(s.growthPoints())
            .append(';')
            .append(s.dropChance())
            .append(';')
            .append(s.growthCycleTicks())
            .append(';')
            .append(s.growthMultiplier())
            .append(';')
            .append(s.machineOnly() ? 1 : 0)
            .append(';')
            .append(s.minSeedBedTier())
            .append(';')
            .append(
                s.subSoil() == null ? ""
                    : s.subSoil()
                        .replace(';', ',')
                        .replace('|', '/'))
            .append(';');
        for (int i = 0; i < s.drops()
            .size(); i++) {
            final CropsNh.Drop d = s.drops()
                .get(i);
            if (i > 0) b.append('|');
            b.append(d.id())
                .append('=')
                .append(d.stackSize())
                .append('=')
                .append(d.weight());
        }
        return b.toString();
    }

    @Nullable
    public static CropsNh.Stats read(final String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            final String[] p = text.split(";", -1);
            final List<CropsNh.Drop> drops = new ArrayList<>();
            if (p.length > 8 && !p[8].isEmpty()) for (final String d : p[8].split("\\|")) {
                final int b = d.lastIndexOf('='), a = d.lastIndexOf('=', b - 1);
                drops.add(
                    new CropsNh.Drop(
                        d.substring(0, a),
                        Integer.parseInt(d.substring(a + 1, b)),
                        Integer.parseInt(d.substring(b + 1))));
            }
            return new CropsNh.Stats(
                Integer.parseInt(p[0]),
                Integer.parseInt(p[1]),
                Double.parseDouble(p[2]),
                Integer.parseInt(p[3]),
                Double.parseDouble(p[4]),
                "1".equals(p[5]),
                Integer.parseInt(p[6]),
                p[7].isEmpty() ? null : p[7],
                drops);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    @Nullable
    public static CropsNh.Stats stats(final Node node) {
        return node.properties.get(STATS) instanceof final String s ? read(s) : null;
    }

    @Nullable
    private static CropsNh.Stats stats(final Map<RecipeProperty<?>, Object> props) {
        return props.get(STATS) instanceof final String s ? read(s) : null;
    }

    // endregion

    // region The run

    /**
     * A crop's harvest: the baseline harvest time (NEI's page, read at the reference) times the card's duration
     * multiplier, at no EU (the harvesters' draw is the card's own figure, as on the website). Voltage buys nothing.
     */
    public static final class Step implements EffectStep {

        @Override
        public EffectResult apply(final EffectResult current, final Map<String, Object> s, final RecipeContext ctx) {
            final CropsNh.Stats stats = stats(ctx.properties());
            if (stats == null) return current;
            current.durationTicks(
                (int) Math.max(1, Math.round(current.durationTicks() * CropsNh.durationMultiplier(stats, s))));
            current.energyPerT(0);
            current.throughputFactor(1);
            return current;
        }
    }

    /**
     * After every refresh: each output's multiplier, the card's gain and harvester over the baseline. Found by the
     * output's item, not its place: an item the table lists twice is one output.
     */
    public static void afterRefresh(final Node node) {
        final CropsNh.Stats stats = stats(node);
        final MachineConfig cfg = node.machineConfig;
        if (stats == null || cfg == null) return;
        cfg.inputConsumption.clear();
        cfg.outputProductivity.clear();
        for (int i = 0; i < node.outputs.size(); i++) {
            final String id = node.outputs.get(i)
                .getValue() instanceof final net.minecraft.item.ItemStack s ? MachineModels.websiteId(s)
                    : i < stats.drops()
                        .size()
                            ? stats.drops()
                                .get(i)
                                .id()
                            : "";
            cfg.outputProductivity.put(i, (float) CropsNh.outputMultiplier(stats, cfg.settings, id));
        }
    }

    // endregion

    // region The card

    public static Setup setup(final Node node) {
        final CropsNh.Stats stats = stats(node);
        return stats == null || node.machineConfig == null ? null : CropsNh.setup(node.machineConfig.settings, stats);
    }

    /** The harvester's name: the Crop Manager's tier name, or the Industrial Farm. */
    public static String harvesterName(final Setup setup) {
        if (setup.farm()) return "Industrial Farm";
        final String name = CropsNh.MANAGER_NAMES.get(CropsNh.tierName(setup.tier()));
        return name != null ? name : "Crop Manager";
    }

    /** The card's tier chip: the Crop Manager's tier, or the seed bed's. */
    public static String tierName(final Node node) {
        final Setup setup = setup(node);
        return setup == null ? "" : CropsNh.tierName(setup.tier());
    }

    /** CropsNH's machines in GregTech's machine block: a Crop Manager at LV..UV, the Industrial Farm's controller. */
    private static final int MANAGER_META = 28000, FARM_META = 28055;

    /** The harvester's item: the Crop Manager at its tier, or the Industrial Farm; null without CropsNH. */
    @Nullable
    public static net.minecraft.item.ItemStack harvesterStack(final Setup setup) {
        return machineStack(setup.farm() ? FARM_META : MANAGER_META + setup.tier());
    }

    @Nullable
    private static net.minecraft.item.ItemStack machineStack(final int meta) {
        final net.minecraft.item.Item block = cpw.mods.fml.common.registry.GameRegistry
            .findItem("gregtech", "gt.blockmachines");
        return block == null ? null : new net.minecraft.item.ItemStack(block, 1, meta);
    }

    /** Whether an item is the Industrial Farm's controller (else a Crop Manager). */
    public static boolean isFarm(final net.minecraft.item.ItemStack machine) {
        return machine != null && machine.getItemDamage() == FARM_META;
    }

    /**
     * The card's machine menu: the Crop Manager (at the card's tier, or LV) and the Industrial Farm, as the website's
     * two harvesters. A crop only a machine can grow offers the farm alone.
     */
    public static List<net.minecraft.item.ItemStack> harvesterChoices(final Node node) {
        final List<net.minecraft.item.ItemStack> out = new ArrayList<>();
        final CropsNh.Stats stats = stats(node);
        final Setup setup = setup(node);
        if (stats == null || setup == null) return out;
        if (!stats.machineOnly()) {
            final int tier = setup.farm() ? CropsNh.MANAGER_MIN : setup.tier();
            final net.minecraft.item.ItemStack manager = machineStack(MANAGER_META + tier);
            if (manager != null) out.add(manager);
        }
        final net.minecraft.item.ItemStack farm = machineStack(FARM_META);
        if (farm != null) out.add(farm);
        return out;
    }

    /** The setting a tier step changes (the manager's tier or the seed bed's), and its next value; null at an end. */
    @Nullable
    public static Map.Entry<String, String> stepTier(final Node node, final int step) {
        final CropsNh.Stats stats = stats(node);
        final Setup setup = setup(node);
        if (stats == null || setup == null) return null;
        final int min = setup.farm() ? CropsNh.seedBedFloor(stats) : CropsNh.MANAGER_MIN;
        final int max = setup.farm() ? CropsNh.SEED_BED_MAX : CropsNh.MANAGER_MAX;
        final int next = Math.max(min, Math.min(max, setup.tier() + step));
        if (next == setup.tier()) return null;
        return Map.entry(setup.farm() ? CropsNh.SEED_BED_TIER : CropsNh.MANAGER_TIER, String.valueOf(next));
    }

    /** How long one harvest takes on this card, in seconds. */
    public static double harvestSeconds(final Node node) {
        final CropsNh.Stats stats = stats(node);
        if (stats == null || node.machineConfig == null) return 0;
        return CropsNh.harvestTicks(stats, CropsNh.REFERENCE)
            * CropsNh.durationMultiplier(stats, node.machineConfig.settings)
            / 20.0;
    }

    /** How many crops one harvester works. */
    public static int cropsPerHarvester(final Node node) {
        final Setup setup = setup(node);
        return setup == null ? 0 : CropsNh.cropsPerMachine(setup);
    }

    /** The harvesters this many crops fill (a solved count planted whole: {@link CropsNh#seeds}). */
    public static int harvesters(final Node node, final double crops) {
        final Setup setup = setup(node);
        return setup == null ? 0 : CropsNh.machineCount(setup, CropsNh.seeds(crops));
    }

    /**
     * The machines a card builds at this count, wherever machines are counted (the overview, the pin list, the world,
     * the screenshot): its count, or for a crop card, whose count is crops planted, the harvesters they fill. A fluid
     * tank is one tank: it has no rate, it keeps up with whatever the plan needs.
     */
    public static double builds(final Node node, final double count) {
        if (com.gtnhplanner.power.FluidTank.is(node)) return 1;
        return isCrop(node) ? harvesters(node, count) : count;
    }

    /** The harvesters' draw for this many crops, EU/t. */
    public static double drawEut(final Node node, final double crops) {
        final CropsNh.Stats stats = stats(node);
        return stats == null || node.machineConfig == null ? 0
            : CropsNh.drawEut(stats, node.machineConfig.settings, CropsNh.seeds(crops));
    }

    public static List<FormulaLine> formulas(final Node node, final double crops) {
        final CropsNh.Stats stats = stats(node);
        if (stats == null || node.machineConfig == null) return Collections.emptyList();
        return CropsNh.formulas(stats, node.machineConfig.settings, Math.max(1, CropsNh.seeds(crops)));
    }

    // region Settings

    /** The sheet's sections, in order: what harvests it, the crop's own stats, where it grows, the farm's units. */
    public static final String MACHINE = "MACHINE", CROP = "CROP", SURROUNDINGS = "SURROUNDINGS",
        FARM_UNITS = "FARM UNITS";

    /** The section a crop setting sits in. */
    public static String group(final String key) {
        return switch (key) {
            case CropsNh.HARVESTER, CropsNh.MANAGER_TIER, CropsNh.SEED_BED_TIER -> MACHINE;
            case CropsNh.GROWTH, CropsNh.GAIN -> CROP;
            case CropsNh.GROWTH_UNITS, CropsNh.FERTILIZER_UNITS, CropsNh.HARVEST_UNITS, CropsNh.ENVIRONMENT_UNITS, CropsNh.OVERCLOCKS -> FARM_UNITS;
            default -> SURROUNDINGS;
        };
    }

    /**
     * Whether a crop card shows a setting on itself until the plan says otherwise: everything that changes its rate
     * except the harvester and its tier (the head shows those) and the farm's fertilizer (fed unless said).
     */
    public static boolean pinnedByDefault(final String key) {
        return !MACHINE.equals(group(key)) && !CropsNh.FERTILIZED.equals(key) && !CropsNh.OVERCLOCKS.equals(key);
    }

    /**
     * The card's settings by section: the harvester and its tier, the crop's stats, the surroundings (a Crop Manager
     * works the world as it is; the farm simulates its own water, fertilizer and sky, so only its biome and feed),
     * and the farm's units, each with the block it is.
     */
    public static List<Setting> settings(final Node node) {
        final List<Setting> out = new ArrayList<>();
        final CropsNh.Stats stats = stats(node);
        if (stats == null || node.machineConfig == null) return out;
        final Map<String, Object> s = node.machineConfig.settings;
        final Setup setup = CropsNh.setup(s, stats);
        final CropsNh.Environment env = CropsNh.environment(s);
        out.add(
            choice(
                CropsNh.HARVESTER,
                "Harvester",
                setup.id(),
                List.of(
                    new Option(CropsNh.MANAGER, "Crop Manager", machineStack(MANAGER_META + CropsNh.MANAGER_MIN)),
                    new Option(CropsNh.FARM, "Industrial Farm", machineStack(FARM_META))),
                stats.machineOnly() && !setup.farm()));
        if (setup.farm()) {
            final List<Option> beds = new ArrayList<>();
            for (int t = CropsNh.seedBedFloor(stats); t <= CropsNh.SEED_BED_MAX; t++)
                beds.add(new Option(String.valueOf(t), "Seed Bed (" + CropsNh.tierName(t) + ")", seedBed(t)));
            out.add(choice(CropsNh.SEED_BED_TIER, "Seed bed", String.valueOf(setup.tier()), beds, false));
        } else {
            final List<Option> managers = new ArrayList<>();
            for (int t = CropsNh.MANAGER_MIN; t <= CropsNh.MANAGER_MAX; t++) managers.add(
                new Option(
                    String.valueOf(t),
                    CropsNh.MANAGER_NAMES.getOrDefault(CropsNh.tierName(t), "Crop Manager"),
                    machineStack(MANAGER_META + t)));
            out.add(choice(CropsNh.MANAGER_TIER, "Tier", String.valueOf(setup.tier()), managers, false));
        }
        out.add(number(CropsNh.GROWTH, "Growth", env.growth(), 1, 31, null));
        out.add(number(CropsNh.GAIN, "Gain", env.gain(), 1, 31, null));
        if (!setup.farm()) {
            out.add(
                choice(
                    CropsNh.WATER,
                    "Water",
                    String.valueOf(env.water()),
                    List.of(
                        new Option("0", "Dry", item("minecraft", "bucket", 0)),
                        new Option("50", "Half", item("minecraft", "water_bucket", 0)),
                        new Option("100", "Full", item("minecraft", "water_bucket", 0))),
                    false));
            final net.minecraft.item.ItemStack fertilizer = item("IC2", "itemFertilizer", 0);
            out.add(
                choice(
                    CropsNh.FERTILIZER,
                    "Fertilizer",
                    String.valueOf(env.fertilizer()),
                    List.of(
                        new Option("0", "None", null),
                        new Option("50", "Half", fertilizer),
                        new Option("100", "Full", fertilizer)),
                    false));
            out.add(
                choice(
                    CropsNh.SKY,
                    "Sky",
                    env.sky() ? "yes" : "no",
                    List.of(
                        new Option("no", "Covered", item("minecraft", "glass", 0)),
                        new Option("yes", "Open", item("minecraft", "double_plant", 0))),
                    false));
        }
        final List<Option> biomes = new ArrayList<>();
        final net.minecraft.item.ItemStack grass = item("minecraft", "grass", 0);
        for (final CropsNh.Biome b : CropsNh.BIOMES)
            biomes.add(new Option(b.key(), b.label(), "none".equals(b.key()) ? null : grass));
        final Object biomeNow = s.get(CropsNh.BIOME);
        final boolean hungry = CropsNh.growthRate(stats, CropsNh.harvesterEnvironment(setup, env)) <= 0;
        out.add(
            choice(
                CropsNh.BIOME,
                "Biome",
                biomeNow == null || "".equals(biomeNow) ? "two-tags" : String.valueOf(biomeNow),
                biomes,
                hungry));
        if (setup.farm()) {
            out.add(
                choice(
                    CropsNh.FERTILIZED,
                    "Fertilizer",
                    setup.fertilized() ? "yes" : "no",
                    List.of(new Option("no", "None", null), new Option("yes", "Fed", item("IC2", "itemFertilizer", 0))),
                    false));
            final int slots = CropsNh.upgradeSlots(setup.tier());
            final int remaining = Math.max(0, slots - CropsNh.unitSlotsUsed(setup));
            out.add(
                number(
                    CropsNh.GROWTH_UNITS,
                    "Accelerators",
                    setup.growthUnits(),
                    0,
                    setup.overclocks() > 0 ? 0 : setup.growthUnits() + remaining,
                    unit("growthAccelerationUnit")));
            out.add(
                number(
                    CropsNh.FERTILIZER_UNITS,
                    "Fertilizer unit",
                    setup.fertilizerUnits(),
                    0,
                    Math.min(CropsNh.MAX_FERTILIZER_UNITS, setup.fertilizerUnits() + remaining),
                    unit("fertilizerUnit")));
            out.add(
                number(
                    CropsNh.HARVEST_UNITS,
                    "Harvest units",
                    setup.harvestUnits(),
                    0,
                    Math.min(CropsNh.MAX_HARVEST_UNITS, setup.harvestUnits() + remaining),
                    unit("advancedHarvestingUnit")));
            out.add(
                number(
                    CropsNh.ENVIRONMENT_UNITS,
                    "Biome cards",
                    setup.environmentUnits(),
                    0,
                    Math.min(CropsNh.MAX_ENVIRONMENT_UNITS, setup.environmentUnits() + remaining),
                    unit("environmentalEnhancementUnit")));
            final boolean noOverclockSlot = setup.overclocks() == 0 && remaining == 0;
            out.add(
                number(
                    CropsNh.OVERCLOCKS,
                    "Overclocks",
                    setup.overclocks(),
                    0,
                    setup.tier() < CropsNh.OVERCLOCK_UNIT_MIN_TIER || noOverclockSlot ? setup.overclocks()
                        : CropsNh.MAX_OVERCLOCKS,
                    unit("overclockedGrowthAccelerationUnit")));
        }
        return out;
    }

    /** The farm's upgrade slots, used and all: "2 of 3". Null on a Crop Manager. */
    @Nullable
    public static String slots(final Node node) {
        final Setup setup = setup(node);
        if (setup == null || !setup.farm()) return null;
        return CropsNh.unitSlotsUsed(setup) + " of " + CropsNh.upgradeSlots(setup.tier()) + " used";
    }

    /** An item by registry name; null when that mod is missing. */
    @Nullable
    private static net.minecraft.item.ItemStack item(final String mod, final String name, final int meta) {
        final net.minecraft.item.Item item = cpw.mods.fml.common.registry.GameRegistry.findItem(mod, name);
        return item == null ? null : new net.minecraft.item.ItemStack(item, 1, meta);
    }

    /** The seed bed block of a tier (its meta is the tier). */
    @Nullable
    private static net.minecraft.item.ItemStack seedBed(final int tier) {
        return item("cropsnh", "cropsnh.seedBed", tier);
    }

    private static final Map<String, net.minecraft.item.ItemStack> UNITS = new java.util.HashMap<>();

    /** A farm unit's block at its lowest tier (the first the mod lists). */
    @Nullable
    private static net.minecraft.item.ItemStack unit(final String name) {
        return UNITS.computeIfAbsent(name, n -> {
            final net.minecraft.item.Item item = cpw.mods.fml.common.registry.GameRegistry
                .findItem("cropsnh", "cropsnh." + n);
            if (item == null) return null;
            final List<net.minecraft.item.ItemStack> subs = new ArrayList<>();
            try {
                item.getSubItems(item, null, subs);
            } catch (final RuntimeException ignored) {}
            return subs.isEmpty() ? new net.minecraft.item.ItemStack(item) : subs.get(0);
        });
    }

    // endregion

    /**
     * What a change to one setting writes: the setting itself, and for a farm unit the whole normalized set of units,
     * so what is stored is always what is shown and a later change never reshuffles slots (the website's way).
     */
    public static Map<String, String> change(final Node node, final String key, final String value) {
        final Map<String, String> out = new LinkedHashMap<>();
        out.put(key, value);
        final CropsNh.Stats stats = stats(node);
        if (stats == null || node.machineConfig == null) return out;
        if (key.equals(CropsNh.GROWTH_UNITS) || key.equals(CropsNh.FERTILIZER_UNITS)
            || key.equals(CropsNh.HARVEST_UNITS)
            || key.equals(CropsNh.ENVIRONMENT_UNITS)
            || key.equals(CropsNh.OVERCLOCKS)) {
            final Map<String, Object> next = new LinkedHashMap<>(node.machineConfig.settings);
            next.put(key, value);
            final Setup setup = CropsNh.setup(next, stats);
            out.put(CropsNh.GROWTH_UNITS, String.valueOf(setup.growthUnits()));
            out.put(CropsNh.FERTILIZER_UNITS, String.valueOf(setup.fertilizerUnits()));
            out.put(CropsNh.HARVEST_UNITS, String.valueOf(setup.harvestUnits()));
            out.put(CropsNh.ENVIRONMENT_UNITS, String.valueOf(setup.environmentUnits()));
            out.put(CropsNh.OVERCLOCKS, String.valueOf(setup.overclocks()));
        }
        return out;
    }

    private static Setting number(final String key, final String label, final int value, final int min, final int max,
        @Nullable final net.minecraft.item.ItemStack icon) {
        return new Setting(key, label, String.valueOf(value), icon, List.of(), true, min, Math.max(min, max), false);
    }

    private static Setting choice(final String key, final String label, final String value, final List<Option> options,
        final boolean warn) {
        Option current = null;
        for (final Option o : options) if (o.key()
            .equals(value)) current = o;
        return new Setting(
            key,
            label,
            current == null ? value : current.label(),
            current == null ? null : current.icon(),
            options,
            false,
            0,
            0,
            warn);
    }

    // endregion
}
