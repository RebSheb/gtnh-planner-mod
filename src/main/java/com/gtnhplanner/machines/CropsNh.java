package com.gtnhplanner.machines;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * A CropsNH crop and what harvests it, as the website models them (its passive-production.ts, the cropsNh functions;
 * transcribed from TileEntityCropSticks, MTECropManager and MTEIndustrialFarm in the GTNH 2.9 pack): how fast it grows
 * on what food, what it drops a harvest, and the two machines that harvest it, the Crop Manager (a single block
 * working real crop sticks) and the Industrial Farm (a multiblock that simulates them). A crop card's machine count
 * is the number of crops planted; the harvesters follow from it ({@link #machineCount}), and they are what the plan
 * builds wherever machines are counted. Pure: the game side is machines/game/CropCards.
 *
 * <p>
 * Settings are kept under the website's own keys (its control ids), so plans go between the two unchanged; the
 * harvester under {@link #HARVESTER} (the website's machineHandlerId).
 */
public final class CropsNh {

    private CropsNh() {}

    // region Keys

    public static final String GROWTH = "cropGrowthStat", GAIN = "cropGainStat", WATER = "cropWater",
        FERTILIZER = "cropFertilizer", SKY = "cropSky", BIOME = "cropBiome", MANAGER_TIER = "cropManagerTier",
        SEED_BED_TIER = "cropSeedBedTier", GROWTH_UNITS = "cropIfGrowthUnits",
        FERTILIZER_UNITS = "cropIfFertilizerUnits", HARVEST_UNITS = "cropIfHarvestUnits",
        ENVIRONMENT_UNITS = "cropIfEnvironmentUnits", OVERCLOCKS = "cropIfOverclocks", FERTILIZED = "cropIfFertilized";

    /** The harvester: the website keeps it as the card's machineHandlerId, the mod as this setting. */
    public static final String HARVESTER = "cropHarvester";

    public static final String MANAGER = "crop-manager", FARM = "crop-industrial-farm";

    /** Every crop setting, as plans carry them. */
    public static final Set<String> SETTING_KEYS = Set.of(
        GROWTH,
        GAIN,
        WATER,
        FERTILIZER,
        SKY,
        BIOME,
        MANAGER_TIER,
        SEED_BED_TIER,
        GROWTH_UNITS,
        FERTILIZER_UNITS,
        HARVEST_UNITS,
        ENVIRONMENT_UNITS,
        OVERCLOCKS,
        FERTILIZED,
        HARVESTER);

    // endregion

    // region The crop

    /** One drop: its item (the website's id), how many a successful roll gives, its weight out of 10000. */
    public record Drop(String id, int stackSize, int weight) {}

    /**
     * A crop's facts (ICropCard): its tier, the growth points it ripens at, its drop chance (drop rounds before gain),
     * the growth cycle (256 ticks) and config multiplier, whether only the farm can grow it, the seed bed it needs
     * (below MV means none), its subsoil rule (null for none), and its drop table.
     */
    public record Stats(int tier, int growthPoints, double dropChance, int growthCycleTicks, double growthMultiplier,
        boolean machineOnly, int minSeedBedTier, @Nullable String subSoil, List<Drop> drops) {}

    /**
     * The crop's surroundings: its growth and gain stats, the soil's water and fertilizer storage (0 to 100, 200 in
     * the farm), open sky, the biome bonus (two liked tags 28), and how many of those are real tags (the humid options
     * simulate one, which the farm's biome cards never stack with).
     */
    public record Environment(int growth, int gain, int water, int fertilizer, boolean sky, int biomeBonus,
        int biomeTags) {}

    /** Perfect 31/31 seeds on a fully supplied stick under open sky in a two-tag biome: the dataset's baseline. */
    public static final Environment REFERENCE = new Environment(31, 31, 100, 100, true, 28, 2);

    /** TileEntityCropSticks.getNutrientsPerCycle: the food a cycle brings. */
    public static int nutrientScore(final Environment env) {
        final int water = (Math.min(100, Math.max(0, env.water())) + 9) / 10;
        final int fertilizer = (Math.min(100, Math.max(0, env.fertilizer())) + 9) / 10;
        return 5 + water + fertilizer + (env.sky() ? 2 : 0) + Math.max(0, env.biomeBonus());
    }

    /** TileEntityCropSticks.getGrowthRate: points a cycle; 0 when the crop is 25 or more food short. */
    public static int growthRate(final Stats stats, final Environment env) {
        final int supply = nutrientScore(env) * 5, demand = stats.tier() * 10;
        final int base = 6 + Math.max(1, Math.min(31, env.growth()));
        final int rate = supply >= demand ? base * (100 + supply - demand) / 100
            : Math.max(0, base * (100 - (demand - supply) * 4) / 100);
        return (int) (rate * stats.growthMultiplier());
    }

    /** Ticks from planted (or just harvested) to ripe; infinite when it does not grow. */
    public static double harvestTicks(final Stats stats, final Environment env) {
        final int perCycle = growthRate(stats, env);
        if (perCycle <= 0) return Double.POSITIVE_INFINITY;
        return Math.ceil((double) stats.growthPoints() / perCycle) * stats.growthCycleTicks();
    }

    /**
     * One drop's expected items a harvest: dropChance x 1.03^gain rounds, each rolling the drop's weight in 10000 and
     * giving its stack plus one more (gain + 1) / 100 of the time.
     */
    public static double expectedDrop(final Stats stats, final int gain, final Drop drop) {
        final int g = Math.max(1, Math.min(31, gain));
        final double rounds = stats.dropChance() * Math.pow(1.03, g);
        return rounds * (drop.weight() / 10000.0) * (drop.stackSize() + (g + 1) / 100.0);
    }

    // endregion

    // region The surroundings as the card sets them

    /** The biome options: the bonus and the real liked tags behind each (the humid ones simulate one). */
    public record Biome(String key, String label, int bonus, int tags) {}

    public static final List<Biome> BIOMES = List.of(
        new Biome("none", "None", 0, 0),
        new Biome("humid-60", "60%", 4, 0),
        new Biome("humid-70", "70%", 9, 0),
        new Biome("humid", "80%", 14, 0),
        new Biome("one-tag", "1 Tag", 14, 1),
        new Biome("two-tags", "2 Tags", 28, 2));

    @Nullable
    public static Biome biome(final String key) {
        for (final Biome b : BIOMES) if (b.key()
            .equals(key)) return b;
        return null;
    }

    /** cropsNhEnvironmentFromTiers: the card's settings, each unset one at the reference. */
    public static Environment environment(final Map<String, ?> settings) {
        final Integer growth = integer(settings, GROWTH), gain = integer(settings, GAIN),
            water = integer(settings, WATER), fertilizer = integer(settings, FERTILIZER);
        final String sky = string(settings, SKY), biome = string(settings, BIOME);
        final Biome b = biome == null ? null : biome(biome);
        return new Environment(
            growth != null ? growth : REFERENCE.growth(),
            gain != null ? gain : REFERENCE.gain(),
            water != null ? water : REFERENCE.water(),
            fertilizer != null ? fertilizer : REFERENCE.fertilizer(),
            sky == null ? REFERENCE.sky() : "yes".equals(sky),
            biome == null ? REFERENCE.biomeBonus() : b == null ? 0 : b.bonus(),
            biome == null ? 2 : b == null ? 0 : b.tags());
    }

    // endregion

    // region Harvesters

    private static final double MANAGER_HARVEST_BONUS_PER_TIER = 0.05;
    public static final int MANAGER_LAYERS = 3, MANAGER_SUBSOIL_LAYERS = 2;
    private static final int MANAGER_OWN_BLOCK = 1;
    private static final double SEED_BED_HARVEST_ROUND_BONUS_PER_TIER = 0.2;
    private static final double GROWTH_UNIT_SPEED_BONUS = 1;
    private static final double FERTILIZER_UNIT_SPEED_MULTIPLIER = 0.5, FERTILIZER_UNIT_HARVEST_ROUND_BONUS = 0.5;
    private static final double HARVEST_UNIT_ROUND_MULTIPLIER = 0.2;
    public static final int MAX_HARVEST_UNITS = 2, MAX_ENVIRONMENT_UNITS = 2, MAX_FERTILIZER_UNITS = 1;
    private static final double ENVIRONMENT_UNIT_POWER = 0.5, GROWTH_UNIT_POWER = 1.25, FERTILIZER_UNIT_POWER = 0.5,
        HARVEST_UNIT_POWER = 0.5;
    private static final int FARM_SIMULATED_STORAGE = 200;
    private static final int BIOME_BONUS_PER_TAG = 14;
    public static final int SEED_BED_MIN = 2, SEED_BED_MAX = 13, MANAGER_MIN = 1, MANAGER_MAX = 8;
    public static final int OVERCLOCK_UNIT_MIN_TIER = 7, MAX_OVERCLOCKS = 6;

    public static final String[] TIER_NAMES = { "ULV", "LV", "MV", "HV", "EV", "IV", "LuV", "ZPM", "UV", "UHV", "UEV",
        "UIV", "UMV", "UXV" };

    /** The Crop Manager's tiers as the mod's lang file names them. */
    public static final Map<String, String> MANAGER_NAMES = Map.of(
        "LV",
        "Basic Crop Manager",
        "MV",
        "Advanced Crop Manager",
        "HV",
        "Advanced Crop Manager II",
        "EV",
        "Advanced Crop Manager III",
        "IV",
        "Advanced Crop Manager IV",
        "LuV",
        "Elite Crop Manager",
        "ZPM",
        "Elite Crop Manager II",
        "UV",
        "Ultimate Crop Manager");

    /**
     * What harvests the crop: the Crop Manager at a tier, or the Industrial Farm at a seed bed tier with its units
     * (growth acceleration, fertilization, advanced harvesting, environmental enhancement, the overclocked growth
     * unit's
     * overclocks), whether the farm is fed fertilizer, and whether the crop needs a block under its soil.
     */
    public record Setup(String id, int tier, int growthUnits, int fertilizerUnits, int harvestUnits,
        int environmentUnits, int overclocks, boolean fertilized, boolean subSoil) {

        public boolean farm() {
            return FARM.equals(id);
        }
    }

    public static String tierName(final int tier) {
        return tier >= 0 && tier < TIER_NAMES.length ? TIER_NAMES[tier] : "MAX";
    }

    /** One layer of a Crop Manager, and one Industrial Farm: (4 x tier + 7)^2 crops. */
    public static int squarePerTier(final int tier) {
        final int radius = 3 + Math.max(0, 2 * tier), side = radius * 2 + 1;
        return side * side;
    }

    public static int managerLayers(final boolean subSoil) {
        return subSoil ? MANAGER_SUBSOIL_LAYERS : MANAGER_LAYERS;
    }

    public static boolean handPicked(final Setup setup) {
        return MANAGER.equals(setup.id()) && setup.tier() < 0;
    }

    /** How many crops one machine works at once. */
    public static int cropsPerMachine(final Setup setup) {
        if (handPicked(setup)) return 1;
        if (!setup.farm()) return squarePerTier(setup.tier()) * managerLayers(setup.subSoil()) - MANAGER_OWN_BLOCK;
        return squarePerTier(setup.tier());
    }

    /** The harvesters a card builds for this many crops (none by hand). */
    public static int machineCount(final Setup setup, final long crops) {
        if (handPicked(setup) || crops <= 0) return 0;
        return (int) Math.ceil((double) crops / cropsPerMachine(setup));
    }

    /**
     * The whole crops a card's count plants: a solved count rounds up, as the website takes its solved seeds
     * (ceil(required - 0.000001)), so 2601.2 crops are 2602 and fill a second farm.
     */
    public static long seeds(final double crops) {
        return crops > 0 && Double.isFinite(crops) ? (long) Math.ceil(crops - 0.000001) : 0;
    }

    /** The farm's upgrade slots: clamp(tier - MV + 1, 1, 12). */
    public static int upgradeSlots(final int tier) {
        return clamp(tier - SEED_BED_MIN + 1, 1, 1 + SEED_BED_MAX - SEED_BED_MIN);
    }

    /** The lowest seed bed this crop takes: its own floor, at least MV. */
    public static int seedBedFloor(final Stats stats) {
        return clamp(Math.max(SEED_BED_MIN, stats.minSeedBedTier()), SEED_BED_MIN, SEED_BED_MAX);
    }

    /**
     * cropsNhHarvesterFromTiers: the harvester the card's settings describe, clamped as the game would have it. Every
     * unit competes for the farm's slots, taken in this order: the overclocked unit, fertilization, harvesting,
     * environment, then plain growth units with what is left (none beside the overclocked unit).
     */
    public static Setup setup(final Map<String, ?> settings, final Stats stats) {
        final String id = FARM.equals(string(settings, HARVESTER)) ? FARM : MANAGER;
        final int floor = seedBedFloor(stats);
        final int tier = MANAGER.equals(id) ? clamp(read(settings, MANAGER_TIER, MANAGER_MIN), MANAGER_MIN, MANAGER_MAX)
            : clamp(read(settings, SEED_BED_TIER, floor), floor, SEED_BED_MAX);
        final int slots = upgradeSlots(tier);
        final int[] remaining = { slots };
        final java.util.function.IntUnaryOperator take = want -> {
            final int got = Math.max(0, Math.min(want, remaining[0]));
            remaining[0] -= got;
            return got;
        };
        final int overclocksWanted = tier >= OVERCLOCK_UNIT_MIN_TIER
            ? clamp(read(settings, OVERCLOCKS, 0), 0, MAX_OVERCLOCKS)
            : 0;
        final int overclocks = overclocksWanted > 0 && take.applyAsInt(1) == 1 ? overclocksWanted : 0;
        final int fertilizerUnits = take
            .applyAsInt(clamp(read(settings, FERTILIZER_UNITS, 0), 0, MAX_FERTILIZER_UNITS));
        final int harvestUnits = take.applyAsInt(clamp(read(settings, HARVEST_UNITS, 0), 0, MAX_HARVEST_UNITS));
        final int environmentUnits = take
            .applyAsInt(clamp(read(settings, ENVIRONMENT_UNITS, 0), 0, MAX_ENVIRONMENT_UNITS));
        final int growthUnits = overclocks > 0 ? 0 : take.applyAsInt(clamp(read(settings, GROWTH_UNITS, 0), 0, slots));
        final boolean fertilized = fertilizerUnits > 0 || !"no".equals(string(settings, FERTILIZED));
        return new Setup(
            id,
            tier,
            growthUnits,
            fertilizerUnits,
            harvestUnits,
            environmentUnits,
            overclocks,
            fertilized,
            stats.subSoil() != null);
    }

    public static int unitSlotsUsed(final Setup setup) {
        return setup.growthUnits() + setup.fertilizerUnits()
            + setup.harvestUnits()
            + setup.environmentUnits()
            + (setup.overclocks() > 0 ? 1 : 0);
    }

    /**
     * The farm simulates its own water (200), fertilizer (200 while fed, else 0) and open sky; its biome cards each add
     * a liked tag to the biome's own, up to two, and never stack with the humid substitute. A Crop Manager works the
     * world as it is.
     */
    public static Environment harvesterEnvironment(final Setup setup, final Environment base) {
        if (!setup.farm()) return base;
        final int baseTags = base.biomeTags();
        final int humid = baseTags > 0 ? 0 : base.biomeBonus();
        final int tags = Math.min(2, baseTags + setup.environmentUnits());
        return new Environment(
            base.growth(),
            base.gain(),
            FARM_SIMULATED_STORAGE,
            setup.fertilized() ? FARM_SIMULATED_STORAGE : 0,
            true,
            Math.max(humid, tags * BIOME_BONUS_PER_TAG),
            tags);
    }

    /** MTEIndustrialFarm.getGrowthSpeedMultiplier; 1 for a Crop Manager, which grows at the world's pace. */
    public static double growthSpeedMultiplier(final Setup setup) {
        if (!setup.farm()) return 1;
        final double additive = 1 + setup.growthUnits() * GROWTH_UNIT_SPEED_BONUS;
        return additive * (1 + setup.fertilizerUnits() * FERTILIZER_UNIT_SPEED_MULTIPLIER)
            * Math.pow(2, setup.overclocks());
    }

    /** What multiplies the drop rounds: 1 + 0.05 x tier for the manager, the seed bed and units for the farm. */
    public static double harvestRoundMultiplier(final Setup setup) {
        if (handPicked(setup)) return 1;
        if (!setup.farm()) return 1 + MANAGER_HARVEST_BONUS_PER_TIER * setup.tier();
        final double additive = 1 + SEED_BED_HARVEST_ROUND_BONUS_PER_TIER * setup.tier()
            + setup.fertilizerUnits() * FERTILIZER_UNIT_HARVEST_ROUND_BONUS;
        return additive * (1 + setup.harvestUnits() * HARVEST_UNIT_ROUND_MULTIPLIER);
    }

    /** The farm's continuous draw per crop: VP[tier] plus each unit's share, x4 an overclock, over its square. */
    public static double eutPerCrop(final Setup setup) {
        if (!setup.farm()) return 0;
        final double base = Math.floor(voltage(setup.tier()) * 30 / 32.0);
        final double withUnits = base
            + base * (setup.environmentUnits() * ENVIRONMENT_UNIT_POWER + setup.growthUnits() * GROWTH_UNIT_POWER
                + setup.fertilizerUnits() * FERTILIZER_UNIT_POWER
                + setup.harvestUnits() * HARVEST_UNIT_POWER);
        return withUnits * Math.pow(4, setup.overclocks()) / squarePerTier(setup.tier());
    }

    /** One whole farm's draw, however full. */
    public static double farmEut(final Setup setup) {
        return eutPerCrop(setup) * squarePerTier(setup.tier());
    }

    /** The Crop Manager's EU for each crop it picks: V / 8. */
    public static double managerEuPerHarvest(final Setup setup) {
        return MANAGER.equals(setup.id()) && setup.tier() >= 0 ? voltage(setup.tier()) / 8 : 0;
    }

    public static double voltage(final int tier) {
        return 8 * Math.pow(4, Math.max(0, tier));
    }

    // endregion

    // region A card's numbers

    /**
     * The solver's multiplier for one output: the crop's expected drop at the card's gain over the reference's, times
     * the harvester's drop rounds; 0 when it never grows, or when only the farm can grow it and a Crop Manager has it.
     * An item the table lists more than once has its drops taken together.
     */
    public static double outputMultiplier(final Stats stats, final Map<String, ?> settings, final String dropId) {
        final Setup setup = setup(settings, stats);
        if (stats.machineOnly() && !setup.farm()) return 0;
        final Environment env = harvesterEnvironment(setup, environment(settings));
        if (growthRate(stats, env) <= 0) return 0;
        final double rounds = harvestRoundMultiplier(setup);
        double reference = 0, current = 0;
        boolean found = false;
        for (final Drop d : stats.drops()) if (d.id()
            .equals(dropId)) {
                found = true;
                reference += expectedDrop(stats, REFERENCE.gain(), d);
                current += expectedDrop(stats, env.gain(), d);
            }
        if (!found) return rounds;
        return (reference > 0 ? current / reference : 1) * rounds;
    }

    /** The harvest time over the reference's, divided by the farm's growth speed; 1 when it never grows. */
    public static double durationMultiplier(final Stats stats, final Map<String, ?> settings) {
        final Setup setup = setup(settings, stats);
        final Environment env = harvesterEnvironment(setup, environment(settings));
        final double ticks = harvestTicks(stats, env), reference = harvestTicks(stats, REFERENCE);
        if (Double.isInfinite(ticks) || reference <= 0) return 1;
        return ticks / reference / growthSpeedMultiplier(setup);
    }

    /**
     * The harvesters' draw for this many crops: the farms' whole draw, or the managers' EU per crop picked spread over
     * the harvest time.
     */
    public static double drawEut(final Stats stats, final Map<String, ?> settings, final long crops) {
        final Setup setup = setup(settings, stats);
        if (handPicked(setup)) return 0;
        if (setup.farm()) return farmEut(setup) * machineCount(setup, crops);
        final double ticks = harvestTicks(stats, environment(settings));
        return Double.isFinite(ticks) && ticks > 0 ? managerEuPerHarvest(setup) * Math.max(0, crops) / ticks : 0;
    }

    // endregion

    // region Formulas

    /** The keys the formulas colour each unit's count by (the website's pip colours, by setting). */
    public static List<FormulaLine> formulas(final Stats stats, final Map<String, ?> settings, final long crops) {
        final Setup setup = setup(settings, stats);
        final boolean farm = setup.farm();
        final Environment env = harvesterEnvironment(setup, environment(settings));
        final int water = (Math.min(100, Math.max(0, env.water())) + 9) / 10;
        final int fert = (Math.min(100, Math.max(0, env.fertilizer())) + 9) / 10;
        final int sky = env.sky() ? 2 : 0;
        final int supply = nutrientScore(env) * 5, demand = stats.tier() * 10;
        final double speed = growthSpeedMultiplier(setup), rounds = harvestRoundMultiplier(setup);
        final int surplus = supply >= demand ? 100 + (supply - demand) : Math.max(0, 100 - (demand - supply) * 4);
        final int rate = growthRate(stats, env);
        final double cycleSec = stats.growthCycleTicks() / 20.0;
        final int cycles = rate > 0 ? (int) Math.ceil((double) stats.growthPoints() / rate) : 0;
        final double harvestSec = rate > 0 ? cycles * cycleSec / speed : 0;
        double items = 0;
        for (final Drop d : stats.drops()) items += expectedDrop(stats, env.gain(), d);
        items *= rounds;
        final long seeds = Math.max(1, crops);
        final double outPerSec = rate > 0 ? items / harvestSec * seeds : 0;
        final List<FormulaLine> out = new ArrayList<>();
        out.add(
            FormulaLine.of(
                "food",
                supply + " vs " + demand + " -> " + surplus + "%",
                supply >= demand ? FormulaLine.Tone.GOOD : FormulaLine.Tone.BAD,
                "(5+",
                FormulaLine.knob(water, WATER),
                "+",
                FormulaLine.knob(fert, farm ? FERTILIZED : FERTILIZER),
                "+",
                FormulaLine.knob(sky, SKY),
                "+",
                FormulaLine.knob(env.biomeBonus(), BIOME),
                ")*5 vs " + stats.tier() + "*10"));
        out.add(
            FormulaLine.of(
                "grows",
                rate + " pts per " + FormulaLine.number(cycleSec) + "s",
                "(6+",
                FormulaLine.knob(env.growth(), GROWTH),
                ")*" + surplus + "%"));
        if (rate <= 0) out.add(FormulaLine.of("harvest", "never", FormulaLine.Tone.BAD, "too hungry to grow"));
        else if (farm) out.add(
            FormulaLine.of(
                "harvest",
                FormulaLine.number(harvestSec) + "s",
                "ceil(" + stats.growthPoints() + "/" + rate + ")*" + FormulaLine.number(cycleSec) + "s / (1+",
                FormulaLine.knob(setup.growthUnits(), GROWTH_UNITS),
                ")*(1+0.5*",
                FormulaLine.knob(setup.fertilizerUnits(), FERTILIZER_UNITS),
                ")*2",
                FormulaLine.sup(String.valueOf(setup.overclocks()))));
        else out.add(
            FormulaLine.of(
                "harvest",
                FormulaLine.number(harvestSec) + "s",
                "ceil(" + stats.growthPoints() + "/" + rate + ")*" + FormulaLine.number(cycleSec) + "s"));
        if (farm) out.add(
            FormulaLine.of(
                "drops",
                FormulaLine.number(items),
                FormulaLine.number(stats.dropChance()) + "*1.03",
                FormulaLine.sup(String.valueOf(env.gain())),
                "*(1+0.2*",
                FormulaLine.knob(setup.tier(), SEED_BED_TIER),
                "+0.5*",
                FormulaLine.knob(setup.fertilizerUnits(), FERTILIZER_UNITS),
                ")*(1+0.2*",
                FormulaLine.knob(setup.harvestUnits(), HARVEST_UNITS),
                ")"));
        else out.add(
            FormulaLine.of(
                "drops",
                FormulaLine.number(items),
                FormulaLine.number(stats.dropChance()) + "*1.03",
                FormulaLine.sup(String.valueOf(env.gain())),
                "*(1+0.05*",
                FormulaLine.knob(setup.tier(), MANAGER_TIER),
                ")"));
        final int machines = machineCount(setup, seeds);
        final String machineWord = farm ? (machines == 1 ? " farm" : " farms")
            : (machines == 1 ? " manager" : " managers");
        out.add(
            FormulaLine.of(
                farm ? "farms" : "managers",
                machines + machineWord,
                "ceil(" + seeds + " planted / " + cropsPerMachine(setup) + " each)"));
        out.add(
            FormulaLine.of(
                "output",
                FormulaLine.number(outPerSec) + "/s",
                FormulaLine.number(items) + " / " + FormulaLine.number(harvestSec) + "s * " + seeds + " planted"));
        if (farm) {
            final double unitFactor = 1 + 1.25 * setup.growthUnits()
                + 0.5 * (setup.fertilizerUnits() + setup.harvestUnits() + setup.environmentUnits());
            final double farmEu = farmEut(setup);
            final long baseEu = Math.round(farmEu / (unitFactor * Math.pow(4, setup.overclocks())));
            out.add(
                FormulaLine.of(
                    "power",
                    FormulaLine.number(Math.round(farmEu * machines)) + " EU/t",
                    baseEu + "*(1+1.25*",
                    FormulaLine.knob(setup.growthUnits(), GROWTH_UNITS),
                    "+0.5*(",
                    FormulaLine.knob(setup.fertilizerUnits(), FERTILIZER_UNITS),
                    "+",
                    FormulaLine.knob(setup.harvestUnits(), HARVEST_UNITS),
                    "+",
                    FormulaLine.knob(setup.environmentUnits(), ENVIRONMENT_UNITS),
                    "))*4",
                    FormulaLine.sup(String.valueOf(setup.overclocks())),
                    "*" + machines));
        } else {
            final double eu = managerEuPerHarvest(setup);
            out.add(
                FormulaLine.of(
                    "power",
                    FormulaLine.number(drawEut(stats, settings, seeds)) + " EU/t",
                    FormulaLine.number(
                        eu) + " EU * " + seeds + " picked / " + FormulaLine.number(harvestSec * 20) + "t"));
        }
        return out;
    }

    // endregion

    // region Reading settings

    private static int read(final Map<String, ?> settings, final String key, final int fallback) {
        final Integer v = integer(settings, key);
        return v == null ? fallback : v;
    }

    @Nullable
    private static Integer integer(final Map<String, ?> settings, final String key) {
        final Object v = settings.get(key);
        if (v instanceof final Number n) return n.intValue();
        if (v instanceof final String s && !s.isEmpty()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (final NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    @Nullable
    private static String string(final Map<String, ?> settings, final String key) {
        final Object v = settings.get(key);
        return v == null || "".equals(v) ? null : String.valueOf(v);
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }

    // endregion
}
