package com.gtnhplanner.machines;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * What a bee makes, worked from the game's code: Forestry 4.11.39's Bee.produceStacks, Bee.getFinalChance and
 * BeekeepingLogic (the Apiary, and MagicBees' Magic Apiary, which runs on them), and GregTech 5.09.54.205's
 * MTEIndustrialApiary and GTApiaryUpgrade. Pure: the game side is machines/game/BeeCards.
 *
 * <p>
 * A product with base chance b (a fraction) comes out with Forestry's getFinalChance,
 * {@code F(b) = 2.8 (100 b)^0.52 (p + t)^0.52 s^0.37 / 100}: p the housing's production modifier, t 1 in Forestry's
 * housings and 8 in the Industrial Apiary, s the speed gene. A pure-bred bee rolls every product twice, at b for its
 * first species and at b / 2 for its second, and every specialty once at b, only while it is jubilant (its climate,
 * or its own condition; a Mega Apiary species never is anywhere else).
 *
 * <p>
 * Forestry's housings roll once a bee cycle (550 ticks), each roll at most certain. The Industrial Apiary runs a
 * queen's whole life as one operation: F(b) times the cycles she lives (her lifespan, 1.5 times over in GTNH's HARD
 * beekeeping mode), no cap, in 550 ticks a cycle divided by its acceleration; when that would end an operation in
 * under 100 ticks it stops at 100 and makes up only the whole multiples. It draws 37 EU/t times its upgrades' energy
 * and the acceleration, plus 32 x 4^(speed - 1).
 *
 * <p>
 * Settings are kept under the website's own keys (its control ids), so plans go between the two unchanged; the housing
 * under {@link #HOUSING} (the website's machineHandlerId).
 */
public final class Bees {

    private Bees() {}

    // region Keys

    public static final String SPEED = "beeSpeedGene", CLIMATE = "beeEnvironment", FRAME = "beeFrameSlot",
        AURA = "beeMagicAura", IA_SPEED = "beeIndustrialSpeed", IA_PRODUCTION = "beeIndustrialProduction";

    /** The housing: the website keeps it as the card's machineHandlerId, the mod as this setting. */
    public static final String HOUSING = "beeHousing";

    public static final int FRAME_SLOTS = 3;

    /** Every bee setting, as plans carry them. */
    public static final Set<String> SETTING_KEYS = Set
        .of(SPEED, CLIMATE, FRAME + 1, FRAME + 2, FRAME + 3, AURA, IA_SPEED, IA_PRODUCTION, HOUSING);

    // endregion

    // region The game's constants

    /** BeekeepingLogic's queen work cycle and MTEIndustrialApiary.beeCycleLength. */
    public static final int CYCLE_TICKS = 550;
    /**
     * The beekeeping mode's lifespan modifier: GTNH ships Forestry on HARD (its config/forestry/apiculture.cfg), whose
     * queens live 1.5 times as long. Its production modifier is 0, as Normal's.
     */
    public static final float MODE_LIFESPAN = 1.5f;
    /** MTEIndustrialApiary.baseEUtUsage. */
    public static final int INDUSTRIAL_EUT = 37;
    /** GTApiaryUpgrade: the Upgraded Acceleration Upgrade x256 sets production to 4 x 1.2^8 (as a float). */
    public static final float UPGRADED_PRODUCTION = 17.199268f;

    // endregion

    // region The bee

    /**
     * One product: its item (the website's id), its name, how many a roll gives, its base chance (a fraction, as the
     * species registers it), and whether it is a specialty (made only while the bee is jubilant).
     */
    public record Product(String id, String name, int stack, float chance, boolean specialty) {}

    /**
     * A species as the card farms it, pure-bred: its Forestry id, name, the speed and lifespan of its own genes (the
     * card's speed gene starts there), whether its specialties are only ever made in a Mega Apiary
     * (JubilanceMegaApiary), and its products then specialties in the species' order.
     */
    public record Species(String uid, String name, float speed, int lifespan, boolean megaOnly,
        List<Product> products) {}

    /** The website's id for a species, as its dataset slugs it: {@code factoryflow:bee_species:<slug>}. */
    public static String websiteId(final String uid) {
        return "factoryflow:bee_species:" + slug(uid);
    }

    /** The website's slug (lower case, every run of other characters a dash, none at the ends). */
    public static String slug(final String value) {
        final String s = value.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-");
        int start = 0, end = s.length();
        if (start < end && s.charAt(start) == '-') start++;
        if (end > start && s.charAt(end - 1) == '-') end--;
        return s.substring(start, end);
    }

    // endregion

    // region The card's choices

    /**
     * A housing: its key (the website's handler id), name, production modifier before frames, Forestry's t, whether it
     * takes frames, and whether it is GregTech's Industrial Apiary.
     */
    public record Housing(String key, String label, float production, float t, boolean frames, boolean industrial) {}

    public static final Housing APIARY = new Housing("apiary", "Apiary", -0.9f, 1f, true, false);
    /** MagicBees' TileEntityMagicApiary: -0.1, or 0.8 with a production aura. The website's default bee machine. */
    public static final Housing MAGIC_APIARY = new Housing("magic-apiary", "Magic Apiary", -0.1f, 1f, true, false);
    public static final Housing INDUSTRIAL = new Housing("industrial-apiary", "Industrial Apiary", 2f, 8f, false, true);

    public static final List<Housing> HOUSINGS = List.of(APIARY, MAGIC_APIARY, INDUSTRIAL);

    /** The Magic Apiary's production with an aura provider charged for production. */
    public static final float MAGIC_AURA_PRODUCTION = 0.8f;

    @Nullable
    public static Housing housing(@Nullable final String key) {
        for (final Housing h : HOUSINGS) if (h.key()
            .equals(key)) return h;
        return null;
    }

    /** A speed gene: Forestry's EnumAllele.Speed and MagicBees' Blinding. */
    public record Allele(String key, String label, float value) {}

    public static final List<Allele> SPEEDS = List.of(
        new Allele("slowest", "Slowest", 0.3f),
        new Allele("slower", "Slower", 0.6f),
        new Allele("slow", "Slow", 0.8f),
        new Allele("normal", "Normal", 1.0f),
        new Allele("fast", "Fast", 1.2f),
        new Allele("faster", "Faster", 1.4f),
        new Allele("fastest", "Fastest", 1.7f),
        new Allele("blinding", "Blinding", 2.0f));

    @Nullable
    public static Allele speed(@Nullable final String key) {
        for (final Allele a : SPEEDS) if (a.key()
            .equals(key)) return a;
        return null;
    }

    /** The allele nearest a gene's value (a species' own speed). */
    public static Allele nearestSpeed(final float value) {
        Allele best = SPEEDS.get(3);
        for (final Allele a : SPEEDS) if (Math.abs(a.value() - value) < Math.abs(best.value() - value)) best = a;
        return best;
    }

    /** How a frame's production adds up, each against what the housing and earlier frames have made so far. */
    public enum FrameRule {
        /** Forestry's ItemHiveFrame: 1 while the sum is below 10, else 0. */
        FORESTRY,
        /** MagicBees' HiveFrameType: its own number. */
        CONSTANT,
        /** ExtraBees' BeeModifierLogic: a cut as it is; a boost no further than its ceiling. */
        CEILING
    }

    /** A frame: the website's key, its name, its item, and its production by its rule. */
    public record Frame(String key, String label, String itemId, FrameRule rule, float value, float max) {

        /** Its production modifier with {@code sum} so far (BeeHousingModifier passes the running sum). */
        public float production(final float sum) {
            return switch (rule) {
                case FORESTRY -> sum < 10.0f ? 1.0f : 0.0f;
                case CONSTANT -> value;
                case CEILING -> value < 0.0f ? value : sum >= max ? 0.0f : Math.min(value, max - sum);
            };
        }
    }

    public static final String NONE = "none";

    public static final List<Frame> FRAMES = List.of(
        new Frame("forestry:untreated", "Untreated Frame", "Forestry:frameUntreated", FrameRule.FORESTRY, 1, 0),
        new Frame("forestry:impregnated", "Impregnated Frame", "Forestry:frameImpregnated", FrameRule.FORESTRY, 1, 0),
        new Frame("forestry:proven", "Proven Frame", "Forestry:frameProven", FrameRule.FORESTRY, 1, 0),
        new Frame("extrabees:cocoa", "Chocolate Frame", "ExtraBees:hiveFrame.cocoa", FrameRule.CEILING, 0.5f, 2.0f),
        new Frame("extrabees:cage", "Restraint Frame", "ExtraBees:hiveFrame.cage", FrameRule.CEILING, -0.25f, 0),
        new Frame("extrabees:soul", "Soul Frame", "ExtraBees:hiveFrame.soul", FrameRule.CEILING, -0.75f, 0),
        new Frame("extrabees:clay", "Healing Frame", "ExtraBees:hiveFrame.clay", FrameRule.CEILING, -0.25f, 0),
        new Frame("magicbees:magic", "Magic Frame", "MagicBees:frameMagic", FrameRule.CONSTANT, 2.0f, 0),
        new Frame("magicbees:resilient", "Resilient Frame", "MagicBees:frameResilient", FrameRule.CONSTANT, 2.0f, 0),
        new Frame("magicbees:gentle", "Gentle Frame", "MagicBees:frameGentle", FrameRule.CONSTANT, 0.4f, 0),
        new Frame("magicbees:metabolic", "Metabolic Frame", "MagicBees:frameMetabolic", FrameRule.CONSTANT, 0.2f, 0),
        new Frame("magicbees:necrotic", "Necrotic Frame", "MagicBees:frameNecrotic", FrameRule.CONSTANT, -0.25f, 0),
        new Frame("magicbees:temporal", "Temporal Frame", "MagicBees:frameTemporal", FrameRule.CONSTANT, 0.0f, 0),
        new Frame("magicbees:oblivion", "Oblivion Frame", "MagicBees:frameOblivion", FrameRule.CONSTANT, -9001.0f, 0));

    @Nullable
    public static Frame frame(@Nullable final String key) {
        for (final Frame f : FRAMES) if (f.key()
            .equals(key)) return f;
        return null;
    }

    /**
     * The climate, the website's knob for whether the bee works and is jubilant: a bee in the wrong climate does not
     * work; in one it only tolerates it works without its specialties.
     */
    public record Climate(String key, String label, boolean works, boolean jubilant) {}

    public static final Climate PREFERRED = new Climate("preferred", "Yes", true, true);
    public static final List<Climate> CLIMATES = List.of(
        PREFERRED,
        new Climate("tolerated", "No", true, false),
        new Climate("wrong", "Wrong climate", false, false),
        new Climate("controlled", "Yes", true, true));

    public static Climate climate(@Nullable final String key) {
        for (final Climate c : CLIMATES) if (c.key()
            .equals(key)) return c;
        return PREFERRED;
    }

    /** An Industrial Apiary acceleration upgrade (GTApiaryUpgrade speed1..8, speed8upgraded): its item's meta. */
    public record Acceleration(String key, String label, int speed, boolean upgraded, int meta) {}

    public static final List<Acceleration> ACCELERATIONS;

    static {
        final List<Acceleration> list = new ArrayList<>();
        list.add(new Acceleration(NONE, "None", 0, false, -1));
        for (int s = 1; s <= 8; s++) list.add(new Acceleration("speed-" + s, "x" + (1 << s), s, false, 32199 + s));
        list.add(new Acceleration("speed-8-upgraded", "Upgraded x256", 8, true, 32208));
        ACCELERATIONS = List.copyOf(list);
    }

    public static Acceleration acceleration(@Nullable final String key) {
        for (final Acceleration a : ACCELERATIONS) if (a.key()
            .equals(key)) return a;
        return ACCELERATIONS.getFirst();
    }

    /** GTApiaryUpgrade's production upgrade: up to 8 in a stack. */
    public static final int MAX_PRODUCTION_UPGRADES = 8;
    /** The production upgrade's GregTech meta item. */
    public static final int PRODUCTION_UPGRADE_META = 32209;

    // endregion

    // region The setup

    /**
     * What a card runs: its housing, speed gene, frames (Forestry's housings; null for an empty slot), the Magic
     * Apiary's aura, climate, and the Industrial Apiary's acceleration and production upgrades.
     */
    public record Setup(Housing housing, Allele speed, List<Frame> frames, boolean aura, Climate climate,
        Acceleration acceleration, int productionUpgrades) {}

    /** The card's settings, each unset one at its default: an Apiary, the species' own speed, preferred climate. */
    public static Setup setup(final Map<String, ?> settings, final Species species) {
        final Housing housing = housing(string(settings, HOUSING));
        final Allele speed = speed(string(settings, SPEED));
        final List<Frame> frames = new ArrayList<>();
        for (int i = 1; i <= FRAME_SLOTS; i++) frames.add(frame(string(settings, FRAME + i)));
        int production = 0;
        try {
            final String p = string(settings, IA_PRODUCTION);
            if (p != null) production = Math.max(0, Math.min(MAX_PRODUCTION_UPGRADES, Integer.parseInt(p.trim())));
        } catch (final NumberFormatException ignored) {}
        return new Setup(
            housing != null ? housing : APIARY,
            speed != null ? speed : nearestSpeed(species.speed()),
            frames,
            "production-aura".equals(string(settings, AURA)),
            climate(string(settings, CLIMATE)),
            acceleration(string(settings, IA_SPEED)),
            production);
    }

    @Nullable
    private static String string(final Map<String, ?> settings, final String key) {
        final Object v = settings.get(key);
        return v == null || "".equals(v) ? null : String.valueOf(v);
    }

    /**
     * The production modifier p, as the housing sums its modifiers: Forestry's housings their own, then each frame in
     * its slot against the sum so far (BeeHousingModifier); the Industrial Apiary 2, 4 x 1.2^n with n production
     * upgrades, or the Upgraded Acceleration's 17.2 (which wins, as it does placed after them). The beekeeping mode
     * adds
     * nothing.
     */
    public static float productionModifier(final Setup s) {
        final Housing h = s.housing();
        if (h.industrial()) {
            if (s.acceleration()
                .upgraded()) return UPGRADED_PRODUCTION;
            return s.productionUpgrades() > 0 ? 4.0f * (float) powInt(1.2, s.productionUpgrades()) : h.production();
        }
        float sum = h == MAGIC_APIARY && s.aura() ? MAGIC_AURA_PRODUCTION : h.production();
        if (h.frames()) for (final Frame f : s.frames()) if (f != null) sum += f.production(sum);
        return sum;
    }

    /** p + t, the production term the chance grows with. */
    public static float term(final Setup s) {
        return productionModifier(s) + s.housing()
            .t();
    }

    /** Forestry's Bee.getFinalChance, in the game's float arithmetic: a roll's chance (above 1 in the GT apiary). */
    public static float finalChance(float chance, final float beeSpeed, final float productionModifier, final float t) {
        if (productionModifier + t <= 0.0F) return 0.0F;
        chance *= 100.0F;
        return (float) (2.8D * Math.pow(chance, 0.52D)
            * Math.pow(productionModifier + t, 0.52D)
            * Math.pow(beeSpeed, 0.37D)) / 100.0F;
    }

    /** GTUtility.powInt: by squaring, as GTApiaryUpgrade takes its powers. */
    static double powInt(double base, int exp) {
        double result = 1;
        while (exp > 0) {
            if ((exp & 1) == 1) result *= base;
            base *= base;
            exp >>= 1;
        }
        return result;
    }

    // endregion

    // region The run

    /**
     * One craft: its ticks, the expected items of each product a craft (by item id, in the species' order), its EU/t,
     * and for the Industrial Apiary the cycles a queen lives, its acceleration, what the life is divided by (the
     * acceleration, or less where that would end it in under 100 ticks) and how many times over the outputs come then.
     */
    public record Run(int ticks, Map<String, Double> perCraft, long euPerTick, float cycles, int acceleration,
        int divisor, int factor) {}

    /**
     * A craft of this setup: a bee cycle in Forestry's housings, a queen's whole life in the Industrial Apiary
     * (MTEIndustrialApiary.checkRecipe).
     */
    public static Run run(final Setup s, final Species species) {
        if (!s.housing()
            .industrial()) return new Run(CYCLE_TICKS, perCycle(s, species), 0, 1, 1, 1, 1);
        final float p = productionModifier(s), t = s.housing()
            .t(),
            speed = s.speed()
                .value();
        final boolean works = s.climate()
            .works();
        final boolean jubilant = jubilant(s, species);
        // The queen's life: health / (1 / (housing lifespan x mode lifespan)), the IA's own lifespan modifier 1.
        float lifespan = 1.0F * MODE_LIFESPAN;
        lifespan = 1.0F / lifespan;
        final float cycles = (float) species.lifespan() / lifespan;
        // Primary products, the second species' at half chance, then the specialties: summed per item as floats.
        final Map<String, Float> drops = new LinkedHashMap<>();
        for (final Product pr : species.products()) if (!pr.specialty())
            drops.merge(pr.id(), finalChance(pr.chance(), speed, p, t) * pr.stack() * cycles, Float::sum);
        for (final Product pr : species.products()) if (!pr.specialty())
            drops.merge(pr.id(), finalChance(pr.chance() / 2.0F, speed, p, t) * pr.stack() * cycles, Float::sum);
        for (final Product pr : species.products()) if (pr.specialty()) drops
            .merge(pr.id(), jubilant ? finalChance(pr.chance(), speed, p, t) * pr.stack() * cycles : 0f, Float::sum);
        final int life = (int) (cycles * (float) CYCLE_TICKS);
        final int limit = life / 100;
        final int accel = 1 << s.acceleration()
            .speed();
        final int d = Math.max(1, Math.min(accel, limit));
        final int ticks = Math.max(1, life / d);
        final int factor = accel / d;
        final Map<String, Double> out = new LinkedHashMap<>();
        for (final Map.Entry<String, Float> e : drops.entrySet())
            out.put(e.getKey(), works ? (double) e.getValue() * factor : 0);
        return new Run(ticks, out, euPerTick(s), cycles, accel, d, factor);
    }

    /** Whether the bee makes its specialties: working, jubilant, and not a species only a Mega Apiary pleases. */
    public static boolean jubilant(final Setup s, final Species species) {
        return s.climate()
            .works()
            && s.climate()
                .jubilant()
            && !species.megaOnly();
    }

    /**
     * The expected items of each product a bee cycle, before any acceleration (by item id, in the species' order):
     * every roll F, at most certain in Forestry's housings.
     */
    public static Map<String, Double> perCycle(final Setup s, final Species species) {
        final float p = productionModifier(s), t = s.housing()
            .t(),
            speed = s.speed()
                .value();
        final boolean works = s.climate()
            .works(), jubilant = jubilant(s, species),
            capped = !s.housing()
                .industrial();
        final Map<String, Double> out = new LinkedHashMap<>();
        for (final Product pr : species.products()) {
            double rolls = 0;
            if (works && pr.specialty()) rolls = jubilant ? roll(finalChance(pr.chance(), speed, p, t), capped) : 0;
            else if (works) rolls = roll(finalChance(pr.chance(), speed, p, t), capped)
                + roll(finalChance(pr.chance() / 2.0F, speed, p, t), capped);
            out.merge(pr.id(), rolls * pr.stack(), Double::sum);
        }
        return out;
    }

    /** A roll's expected successes: Forestry's {@code rand.nextFloat() < chance} is at most certain. */
    private static double roll(final float chance, final boolean capped) {
        return capped ? Math.min(1, chance) : chance;
    }

    /** MTEIndustrialApiary's draw: 37 x its upgrades' energy x the acceleration, plus 32 x 4^(speed - 1). */
    public static long euPerTick(final Setup s) {
        if (!s.housing()
            .industrial()) return 0;
        final int speed = s.acceleration()
            .speed();
        final int accel = 1 << speed;
        long eut = (int) (INDUSTRIAL_EUT * energy(s) * (float) accel);
        if (accel == 2) eut += 32;
        else if (accel > 2) eut += 32L * (accel << (speed - 2));
        return eut;
    }

    /** The upgrades' energy modifier: 1.4^n for n production upgrades, x14.75 for the Upgraded Acceleration. */
    public static float energy(final Setup s) {
        float energy = 1.0f;
        if (s.productionUpgrades() > 0) energy = (float) (energy * powInt(1.399999976158142, s.productionUpgrades()));
        if (s.acceleration()
            .upgraded()) energy = (float) (energy * 14.75);
        return energy;
    }

    // endregion

    // region Formulas

    /** The card's working, one line a step, each setting's number in its colour. */
    public static List<FormulaLine> formulas(final Species species, final Setup s) {
        final List<FormulaLine> lines = new ArrayList<>();
        final Housing h = s.housing();
        final float p = productionModifier(s);
        final List<Object> terms = new ArrayList<>();
        if (h.industrial()) {
            if (s.acceleration()
                .upgraded()) terms.add(FormulaLine.knob("17.2", IA_SPEED));
            else if (s.productionUpgrades() > 0) {
                terms.add("4·1.2");
                terms.add(new FormulaLine.Term(String.valueOf(s.productionUpgrades()), IA_PRODUCTION, true));
            } else terms.add("2");
        } else {
            terms.add(
                FormulaLine.knob(
                    num(h == MAGIC_APIARY && s.aura() ? MAGIC_AURA_PRODUCTION : h.production()),
                    h == MAGIC_APIARY ? AURA : HOUSING));
            float sum = h == MAGIC_APIARY && s.aura() ? MAGIC_AURA_PRODUCTION : h.production();
            for (int i = 0; i < s.frames()
                .size(); i++) {
                final Frame f = s.frames()
                    .get(i);
                if (f == null) continue;
                final float add = f.production(sum);
                sum += add;
                terms.add(add < 0 ? " − " : " + ");
                terms.add(FormulaLine.knob(num(Math.abs(add)), FRAME + (i + 1)));
            }
        }
        terms.add(" + " + num(h.t()));
        lines.add(FormulaLine.of("p + t", num(p + h.t()), terms.toArray()));
        lines.add(
            FormulaLine.of(
                "speed",
                "x" + FormulaLine.number(
                    Math.pow(
                        s.speed()
                            .value(),
                        0.37)),
                "s",
                FormulaLine.sup("0.37"),
                ", s = ",
                FormulaLine.knob(
                    num(
                        s.speed()
                            .value()),
                    SPEED)));
        lines.add(
            FormulaLine.of(
                "roll",
                "F(b)",
                "2.8·(100b)",
                FormulaLine.sup("0.52"),
                "·" + num(p + h.t()),
                FormulaLine.sup("0.52"),
                "·s",
                FormulaLine.sup("0.37"),
                " / 100"));
        final Run run = run(s, species);
        final boolean works = s.climate()
            .works(), jubilant = jubilant(s, species);
        final Map<String, Double> cycle = perCycle(s, species);
        final Map<String, String> names = new LinkedHashMap<>();
        for (final Product pr : species.products()) names.putIfAbsent(pr.id(), pr.name());
        for (final Map.Entry<String, String> e : names.entrySet()) {
            // The product named in the working, where it has room: a label is a word or two.
            final List<Object> math = new ArrayList<>();
            math.add(e.getValue() + ": ");
            boolean special = true;
            for (final Product pr : species.products()) {
                if (!pr.id()
                    .equals(e.getKey())) continue;
                special &= pr.specialty();
                if (math.size() > 1) math.add(" + ");
                final String stack = pr.stack() > 1 ? pr.stack() + "·" : "";
                if (pr.specialty()) {
                    math.add(stack + "F(" + pct(pr.chance()) + ")");
                    if (!jubilant) math.add(FormulaLine.knob(" not jubilant", CLIMATE));
                } else math.add(stack + "F(" + pct(pr.chance()) + ") + " + stack + "F(" + pct(pr.chance() / 2f) + ")");
            }
            lines.add(
                FormulaLine.of(
                    special ? "special" : "product",
                    FormulaLine.number(cycle.getOrDefault(e.getKey(), 0d)) + " a cycle",
                    math.toArray()));
        }
        if (!works) lines.add(FormulaLine.of("climate", "makes nothing", FormulaLine.Tone.BAD, "wrong climate"));
        else if (species.megaOnly() && species.products()
            .stream()
            .anyMatch(Product::specialty))
            lines.add(FormulaLine.of("special", "not made", FormulaLine.Tone.BAD, "only in a Mega Apiary"));
        if (h.industrial()) {
            lines.add(
                FormulaLine.of(
                    "life",
                    FormulaLine.number(run.cycles()) + " cycles",
                    species.lifespan() + "·" + num(MODE_LIFESPAN)));
            final int accel = run.acceleration();
            lines.add(
                FormulaLine.of(
                    "time",
                    FormulaLine.number(run.ticks() / 20.0) + " s",
                    FormulaLine.number(run.cycles()) + "·550 / ",
                    FormulaLine.knob(String.valueOf(run.divisor()), IA_SPEED)));
            // Her life too short for the whole acceleration: the operation stops at 100 ticks, its outputs times over.
            if (run.divisor() < accel) lines.add(
                FormulaLine.of(
                    "short life",
                    "x" + run.factor() + " outputs",
                    FormulaLine.Tone.BAD,
                    accel + " / " + run.divisor()));
            final int speed = s.acceleration()
                .speed();
            final List<Object> power = new ArrayList<>();
            power.add("37·");
            if (energy(s) != 1f) power.add(FormulaLine.knob(num(energy(s)) + "·", IA_PRODUCTION));
            power.add(FormulaLine.knob(String.valueOf(accel), IA_SPEED));
            if (speed > 0) {
                power.add(" + 32·4");
                power.add(FormulaLine.sup(String.valueOf(speed - 1)));
            }
            lines.add(FormulaLine.of("power", FormulaLine.number(run.euPerTick()) + " EU/t", power.toArray()));
        }
        return lines;
    }

    private static String num(final double v) {
        return FormulaLine.number(v);
    }

    private static String pct(final float chance) {
        return FormulaLine.number(chance * 100.0) + "%";
    }

    // endregion
}
