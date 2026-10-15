package com.gtnhplanner.machines.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import com.gtnhplanner.api.RecipePropertyAPI;
import com.gtnhplanner.data.MachineConfig;
import com.gtnhplanner.data.RecipeContext;
import com.gtnhplanner.data.effect.EffectResult;
import com.gtnhplanner.data.effect.EffectStep;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.data.properties.RecipeProperty;
import com.gtnhplanner.machines.Bees;
import com.gtnhplanner.machines.FormulaLine;
import com.gtnhplanner.machines.game.MachineModels.Option;
import com.gtnhplanner.machines.game.MachineModels.Setting;

/**
 * The game's side of a bee card (machines/Bees, worked from the game's code): the species on the node (read from
 * Forestry by BeeProvider off NEI's bee products page), a craft's ticks and draw, the solver's per-product multipliers
 * after every refresh, the housings it can live in and the card's settings and formulas, under the website's keys.
 * Knows nothing of Forestry's classes, so it loads without the mod.
 */
public final class BeeCards {

    private BeeCards() {}

    /** The species' facts ({@link #write}). */
    public static final RecipeProperty<String> SPECIES = RecipeProperty.<String>builder("gtnhplanner.bee.species", "")
        .build();
    /** Each product's item, one a port, as the provider read them: Node merges ports that share an ore name. */
    public static final RecipeProperty<List<ItemStack>> STACKS = RecipeProperty
        .<List<ItemStack>>builder("gtnhplanner.bee.stacks", List.of())
        .build();

    public static boolean isBee(final Node node) {
        return node != null && !node.isPower() && species(node) != null;
    }

    // region The species' facts

    /** uid;name;speed;lifespan;megaOnly;id=stack=chance=S|... (S 1 for a specialty) */
    public static String write(final Bees.Species s) {
        final StringBuilder b = new StringBuilder();
        b.append(clean(s.uid()))
            .append(';')
            .append(clean(s.name()))
            .append(';')
            .append(s.speed())
            .append(';')
            .append(s.lifespan())
            .append(';')
            .append(s.megaOnly() ? 1 : 0)
            .append(';');
        for (int i = 0; i < s.products()
            .size(); i++) {
            final Bees.Product p = s.products()
                .get(i);
            if (i > 0) b.append('|');
            b.append(p.id())
                .append('=')
                .append(p.stack())
                .append('=')
                .append(p.chance())
                .append('=')
                .append(p.specialty() ? 1 : 0)
                .append('=')
                .append(clean(p.name()));
        }
        return b.toString();
    }

    private static String clean(final String s) {
        return s == null ? ""
            : s.replace(';', ',')
                .replace('|', '/')
                .replace('=', '-');
    }

    @Nullable
    public static Bees.Species read(final String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            final String[] f = text.split(";", -1);
            final List<Bees.Product> products = new ArrayList<>();
            if (f.length > 5 && !f[5].isEmpty()) for (final String p : f[5].split("\\|")) {
                final String[] q = p.split("=", -1);
                products.add(
                    new Bees.Product(
                        q[0],
                        q.length > 4 ? q[4] : q[0],
                        Integer.parseInt(q[1]),
                        Float.parseFloat(q[2]),
                        "1".equals(q[3])));
            }
            return new Bees.Species(
                f[0],
                f[1],
                Float.parseFloat(f[2]),
                Integer.parseInt(f[3]),
                "1".equals(f[4]),
                products);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    @Nullable
    public static Bees.Species species(final Node node) {
        return species(node.properties);
    }

    @Nullable
    private static Bees.Species species(final Map<RecipeProperty<?>, Object> props) {
        return props.get(SPECIES) instanceof final String s ? read(s) : null;
    }

    /** The card's setup, or null on any other card. */
    @Nullable
    public static Bees.Setup setup(final Node node) {
        final Bees.Species species = species(node);
        return species == null || node.machineConfig == null ? null : Bees.setup(node.machineConfig.settings, species);
    }

    // endregion

    // region The run

    /** A craft's ticks (a bee cycle, or the Industrial Apiary's queen's life) and the Industrial Apiary's draw. */
    public static final class Step implements EffectStep {

        @Override
        public EffectResult apply(final EffectResult current, final Map<String, Object> s, final RecipeContext ctx) {
            final Bees.Species species = species(ctx.properties());
            if (species == null) return current;
            final Bees.Run run = Bees.run(Bees.setup(s, species), species);
            current.durationTicks(run.ticks());
            current.energyPerT(run.euPerTick());
            current.throughputFactor(1);
            return current;
        }
    }

    /**
     * After every refresh: one port a product again (Node merges ports whose items share an ore name, and every comb is
     * a beeComb), and each one's expected items a craft as its multiplier.
     */
    public static void afterRefresh(final Node node) {
        final Bees.Species species = species(node);
        final MachineConfig cfg = node.machineConfig;
        if (species == null || cfg == null) return;
        if (node.properties.get(STACKS) instanceof final List<?> stacks && stacks.size() != node.outputs.size()) {
            node.outputs.clear();
            for (final Object o : stacks) if (o instanceof final ItemStack stack)
                node.outputs.add(new Port<>(RecipePropertyAPI.ITEM, stack.copy(), 1f));
        }
        cfg.inputConsumption.clear();
        cfg.outputProductivity.clear();
        final Map<String, Double> perCraft = Bees.run(Bees.setup(cfg.settings, species), species)
            .perCraft();
        for (int i = 0; i < node.outputs.size(); i++) {
            final Port<?> port = node.outputs.get(i);
            final double each = port.getValue() instanceof final ItemStack stack
                ? perCraft.getOrDefault(MachineModels.websiteId(stack), 0d)
                : 0;
            cfg.outputProductivity.put(i, (float) (each / Math.max(1e-9, port.amount())));
        }
    }

    // endregion

    // region The card

    /** The card's name: its species, "Forest Bee". */
    public static String cardName(final Node node) {
        final Bees.Species species = species(node);
        return species == null ? node.machineName : species.name() + " Bee";
    }

    /** The housings' items: Forestry's Apiary, MagicBees' Magic Apiary, GregTech's Industrial Apiary. */
    private static final Map<String, String[]> HOUSING_ITEMS = Map.of(
        Bees.APIARY.key(),
        new String[] { "Forestry", "apiculture", "0" },
        Bees.MAGIC_APIARY.key(),
        new String[] { "MagicBees", "magicApiary", "0" },
        Bees.INDUSTRIAL.key(),
        new String[] { "gregtech", "gt.blockmachines", "9399" });

    /** A housing's item; null when its mod is missing. */
    @Nullable
    public static ItemStack housingStack(final Bees.Housing housing) {
        final String[] id = HOUSING_ITEMS.get(housing.key());
        return id == null ? null : item(id[0], id[1], Integer.parseInt(id[2]));
    }

    /** The housing an item is, or null. */
    @Nullable
    public static Bees.Housing housingOf(@Nullable final ItemStack machine) {
        if (machine == null) return null;
        for (final Bees.Housing h : Bees.HOUSINGS) {
            final ItemStack s = housingStack(h);
            if (s != null && s.getItem() == machine.getItem() && s.getItemDamage() == machine.getItemDamage()) return h;
        }
        return null;
    }

    /** The housings this game has, in the card's order. */
    public static List<ItemStack> housingChoices() {
        final List<ItemStack> out = new ArrayList<>();
        for (final Bees.Housing h : Bees.HOUSINGS) {
            final ItemStack s = housingStack(h);
            if (s != null) out.add(s);
        }
        return out;
    }

    public static List<FormulaLine> formulas(final Node node) {
        final Bees.Species species = species(node);
        final Bees.Setup setup = setup(node);
        return species == null || setup == null ? Collections.emptyList() : Bees.formulas(species, setup);
    }

    // region Settings

    /** Settings the next bee card starts with: its housing comes from the plan menu, its speed from its species. */
    public static boolean remembered(final String key) {
        return !Bees.HOUSING.equals(key) && !Bees.SPEED.equals(key);
    }

    /**
     * The card's settings: the housing, the speed gene, whether it is jubilant, then the housing's own (Forestry's
     * three frames and the Magic Apiary's aura; the Industrial Apiary's acceleration and production upgrades).
     */
    public static List<Setting> settings(final Node node) {
        final List<Setting> out = new ArrayList<>();
        final Bees.Species species = species(node);
        final Bees.Setup s = setup(node);
        if (species == null || s == null) return out;
        final List<Option> housings = new ArrayList<>();
        for (final Bees.Housing h : Bees.HOUSINGS) {
            final ItemStack stack = housingStack(h);
            if (stack != null || h == s.housing()) housings.add(new Option(h.key(), h.label(), stack));
        }
        out.add(
            choice(
                Bees.HOUSING,
                "Housing",
                s.housing()
                    .key(),
                housings,
                false));
        final List<Option> speeds = new ArrayList<>();
        for (final Bees.Allele a : Bees.SPEEDS)
            speeds.add(new Option(a.key(), a.label() + " (" + FormulaLine.number(a.value()) + ")", null));
        out.add(
            choice(
                Bees.SPEED,
                "Speed",
                s.speed()
                    .key(),
                speeds,
                false));
        final List<Option> climates = new ArrayList<>();
        for (final Bees.Climate c : Bees.CLIMATES)
            if (!"controlled".equals(c.key())) climates.add(new Option(c.key(), c.label(), null));
        final boolean specialties = species.products()
            .stream()
            .anyMatch(Bees.Product::specialty);
        out.add(
            choice(
                Bees.CLIMATE,
                "Jubilant",
                "controlled".equals(
                    s.climate()
                        .key()) ? "preferred"
                            : s.climate()
                                .key(),
                climates,
                !s.climate()
                    .works() || specialties && !Bees.jubilant(s, species)));
        if (s.housing()
            .frames()) {
            for (int i = 1; i <= Bees.FRAME_SLOTS; i++) {
                final List<Option> frames = new ArrayList<>();
                frames.add(new Option(Bees.NONE, "Empty", null));
                for (final Bees.Frame f : Bees.FRAMES) frames.add(new Option(f.key(), f.label(), frameStack(f)));
                final Bees.Frame now = s.frames()
                    .get(i - 1);
                out.add(choice(Bees.FRAME + i, "Frame " + i, now == null ? Bees.NONE : now.key(), frames, false));
            }
        }
        if (s.housing() == Bees.MAGIC_APIARY) out.add(
            choice(
                Bees.AURA,
                "Aura",
                s.aura() ? "production-aura" : Bees.NONE,
                List.of(
                    new Option(Bees.NONE, "None", null),
                    new Option("production-aura", "Production aura", item("MagicBees", "visAuraProvider", 0))),
                false));
        if (s.housing()
            .industrial()) {
            final List<Option> speedUps = new ArrayList<>();
            for (final Bees.Acceleration a : Bees.ACCELERATIONS) speedUps.add(
                new Option(a.key(), a.label(), a.meta() < 0 ? null : item("gregtech", "gt.metaitem.03", a.meta())));
            out.add(
                choice(
                    Bees.IA_SPEED,
                    "Acceleration",
                    s.acceleration()
                        .key(),
                    speedUps,
                    false));
            out.add(
                new Setting(
                    Bees.IA_PRODUCTION,
                    "Production",
                    String.valueOf(s.productionUpgrades()),
                    item("gregtech", "gt.metaitem.03", Bees.PRODUCTION_UPGRADE_META),
                    List.of(),
                    true,
                    0,
                    Bees.MAX_PRODUCTION_UPGRADES,
                    false));
        }
        return out;
    }

    private static final Map<String, ItemStack> FRAME_ICONS = new HashMap<>();

    @Nullable
    private static ItemStack frameStack(final Bees.Frame f) {
        if (FRAME_ICONS.containsKey(f.key())) return FRAME_ICONS.get(f.key());
        final int colon = f.itemId()
            .indexOf(':');
        final ItemStack stack = item(
            f.itemId()
                .substring(0, colon),
            f.itemId()
                .substring(colon + 1),
            0);
        FRAME_ICONS.put(f.key(), stack);
        return stack;
    }

    /** An item by registry name; null when that mod is missing. */
    @Nullable
    private static ItemStack item(final String mod, final String name, final int meta) {
        final net.minecraft.item.Item item = cpw.mods.fml.common.registry.GameRegistry.findItem(mod, name);
        return item == null ? null : new ItemStack(item, 1, meta);
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

    // endregion
}
