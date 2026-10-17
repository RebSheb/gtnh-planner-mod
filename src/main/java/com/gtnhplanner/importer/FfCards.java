package com.gtnhplanner.importer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.gtnhplanner.data.Settings;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.importer.FfPlan.FfNode;
import com.gtnhplanner.importer.FfPlan.FfRecipe;
import com.gtnhplanner.importer.FfPlan.FfSlot;
import com.gtnhplanner.importer.FfPlan.FfTarget;
import com.gtnhplanner.importer.ImportReport.Kind;
import com.gtnhplanner.importer.NodeMaker.PortInfo;
import com.gtnhplanner.importer.RecipeIndex.Lookup;

/**
 * One FF card as a GTNH Planner node, without the rest of its plan: its recipe matched and built, its machine,
 * settings,
 * count, pin, picks and target rate. The importer ({@link FfConverter}) places and wires what this builds for a whole
 * plan; a reader that keeps each card's id and place (the cloud plans) builds one card at a time.
 */
public final class FfCards {

    /**
     * What building cards needs: the game's recipes and node maker, whether the plan is a Solve plan (its counts the
     * answer, a pin the only fixed count), and where to report what did not carry over.
     */
    public record Context(RecipeIndex index, NodeMaker maker, boolean solveMode, ImportReport report) {}

    /** What each port of a node holds, kept per node: describing a port asks the game. */
    public static final class Ports {

        private final NodeMaker maker;
        private final Map<UUID, List<PortInfo>> in = new HashMap<>(), out = new HashMap<>();

        public Ports(final NodeMaker maker) {
            this.maker = maker;
        }

        public List<PortInfo> describe(final Node node, final boolean output) {
            return (output ? out : in).computeIfAbsent(node.id, k -> {
                final List<PortInfo> infos = new ArrayList<>();
                for (final Port<?> port : output ? node.outputs : node.inputs) infos.add(maker.describe(port));
                return infos;
            });
        }

        /** Forgets a node's inputs, whose items changed. */
        public void forgetInputs(final Node node) {
            in.remove(node.id);
        }

        /** The first port of a side holding a resource of the kind and any of the ids; -1 when none does. */
        public int find(final Node node, final boolean output, final String kind, final List<String> ids) {
            final List<PortInfo> infos = describe(node, output);
            for (int i = 0; i < infos.size(); i++) {
                final PortInfo info = infos.get(i);
                if (info.kind()
                    .equals(kind) && FfIds.anySame(info.ids(), ids)) return i;
            }
            return -1;
        }
    }

    private FfCards() {}

    /**
     * One recipe of a card (its first, or a shared machine's later section) at a place: the in-game recipe it matches,
     * built on the machine the card runs, with the card's settings, count and pin, its picks among the items a slot
     * takes, and (on the first section) its target rate. Null, reported, when the game has no such recipe.
     */
    @Nullable
    public static Node section(final Context ctx, final Ports ports, final FfNode card, final FfRecipe recipe,
        final Map<Integer, FfSlot> overrides, final int section, final String name, final int x, final int y) {
        final ImportReport report = ctx.report();
        final RecipeIndex.Lookup lookup = ctx.index()
            .find(recipe);
        // A crop is the crop it names, a bee the species: their rates are what the card's settings make of them,
        // nothing to match by. A recipe found by its own id is the one.
        final RecipeMatcher.Match match = recipe.isCrop() || recipe.isBee() || lookup.exact() ? exactly(lookup)
            : RecipeMatcher.match(recipe, overrides, lookup.candidates());
        if (!match.accepted()) {
            String why = match.detail();
            if (why.isEmpty()) why = lookup.note() != null ? lookup.note() : "no recipe like it in game";
            report.add(Kind.UNMATCHED, name, why);
            return null;
        }
        final RecipeIndex.GameRecipe game = match.recipe();
        if (match.grade() == RecipeMatcher.Grade.SAME_RESOURCES)
            report.add(Kind.FUZZY, name, "the in-game recipe differs: " + match.detail());
        if (recipe.isPlannerTank())
            report.add(Kind.CONVERTED, name, "the free, instant Tank is a Canner recipe in game");

        final long eut = game.euPerTick() != null ? game.euPerTick() : Math.round(recipe.eut());
        final FfSettings.Mapped mapped = FfSettings.map(card, recipe, eut, ctx.solveMode(), section == 0);
        final NodeMaker maker = ctx.maker();
        final Node node = maker.make(game, mapped.machine());
        if (node == null) {
            report.add(Kind.UNMATCHED, name, "the game could not build a card for it");
            return null;
        }
        node.x = x;
        node.y = y;
        maker.applySettings(node, mapped.settings());
        // A single block's tier is its machine: where the game has none at the plan's tier, the card runs at the
        // nearest it has.
        final Object asked = mapped.settings()
            .get(Settings.VOLTAGE.key()),
            runs = node.machineConfig == null ? null : node.machineConfig.settings.get(Settings.VOLTAGE.key());
        if (asked != null && runs != null && !asked.equals(runs)) report.add(
            Kind.CONVERTED,
            name,
            "runs at " + runs + ", the nearest tier its machine comes in (the plan has " + asked + ")");
        node.machineConfig.setMachineCount(mapped.machines());
        node.setMachineCountFixed(mapped.pinned());
        for (final String note : mapped.notes()) report.add(Kind.CONVERTED, name, note);
        pickInputs(ctx, ports, node, recipe, overrides, name);

        final FfTarget target = section == 0 ? card.targetOutput() : null;
        if (target != null) {
            final int out = ports.find(node, true, target.kind(), List.of(target.resourceId()));
            if (out >= 0) node.targetOutputRates.put(out, target.perSecond());
            else report
                .add(Kind.WIRE, name, "its target rate names " + target.resourceId() + ", which it does not make");
        }
        portRates(ports, node, recipe, overrides, card, section, name, report);
        return node;
    }

    /**
     * Plan mode's port-rate pins ({@code portRates}) of one section onto its card's rates: each handle's port (an
     * input by the slot it fills, its alternatives too), the rate a second.
     */
    static void portRates(final Ports ports, final Node node, @Nullable final FfRecipe recipe,
        final Map<Integer, FfSlot> overrides, final FfNode card, final int section, final String name,
        final ImportReport report) {
        for (final Map.Entry<String, Double> rate : card.portRates()
            .entrySet()) {
            final FfHandle h = FfHandle.parse(rate.getKey());
            if (h == null || h.section() != section) continue;
            final int at = portOf(ports, node, recipe, overrides, h);
            if (at < 0) {
                report.add(Kind.WIRE, name, "its rate names " + h.resourceId() + ", which it has no port for");
                continue;
            }
            (h.output() ? node.targetOutputRates : node.targetInputRates).put(at, rate.getValue());
        }
    }

    /** The port a handle names on a card: by its resource, an input by the slot it fills; -1 when none. */
    static int portOf(final Ports ports, final Node node, @Nullable final FfRecipe recipe,
        final Map<Integer, FfSlot> overrides, final FfHandle h) {
        final List<String> want = new ArrayList<>();
        want.add(h.resourceId());
        if (!h.output() && recipe != null) {
            final List<FfSlot> inputs = recipe.inputs();
            for (int i = 0; i < inputs.size(); i++) {
                final List<String> ids = new ArrayList<>(
                    inputs.get(i)
                        .ids());
                final FfSlot pick = overrides.get(i);
                if (pick != null) ids.add(pick.id());
                if (FfIds.anySame(ids, want)) {
                    want.addAll(ids);
                    break;
                }
            }
        }
        return ports.find(node, h.output(), h.kind(), want);
    }

    /** The one recipe a lookup found by its own id (or a crop's or bee's), as a match; none when it found nothing. */
    private static RecipeMatcher.Match exactly(final Lookup lookup) {
        return lookup.candidates()
            .isEmpty()
                ? new RecipeMatcher.Match(null, RecipeMatcher.Grade.NONE, "", 0)
                : new RecipeMatcher.Match(
                    lookup.candidates()
                        .getFirst(),
                    RecipeMatcher.Grade.EXACT,
                    "",
                    1);
    }

    /**
     * The card's picks among the items a slot takes (FF's {@code recipeInputOverrides}, by its recipe's input index):
     * each slot's port set to the picked item, when the game lists it for that slot. A pick of a whole ore dictionary
     * group is no pick.
     */
    static void pickInputs(final Context ctx, final Ports ports, final Node node, final FfRecipe recipe,
        final Map<Integer, FfSlot> overrides, final String name) {
        boolean picked = false;
        for (final Map.Entry<Integer, FfSlot> o : overrides.entrySet()) {
            final FfSlot chosen = o.getValue();
            final int slot = o.getKey();
            if (slot < 0 || slot >= recipe.inputs()
                .size()
                || chosen.id()
                    .startsWith("oredict:"))
                continue;
            final FfSlot own = recipe.inputs()
                .get(slot);
            if (FfIds.same(own.id(), chosen.id())) continue;
            final List<String> ids = new ArrayList<>(own.ids());
            ids.add(chosen.id());
            final int port = ports.find(node, false, chosen.kind(), ids);
            final Port<?> p = port < 0 ? null : node.inputs.get(port);
            Object item = null;
            if (p != null) for (final Object alt : p.getAlternatives()) {
                if (FfIds.same(
                    ctx.maker()
                        .describe(p.withAlternative(alt))
                        .ids()
                        .getFirst(),
                    chosen.id())) {
                    item = alt;
                    break;
                }
            }
            if (item == null) {
                ctx.report()
                    .add(
                        Kind.CONVERTED,
                        name,
                        "takes " + chosen.displayName() + " in the plan, which its slot does not take in game");
                continue;
            }
            picked |= node.pickInput(port, p.keyOf(item));
        }
        // The ports' items changed: what they hold is read again.
        if (picked) ports.forgetInputs(node);
    }

    /**
     * A power card: the same generator at the same settings, which rebuilds its ports from the source as the site
     * does on load. Its pin comes over as a pin in a Solve plan (an unpinned one's count as a starting count), its
     * count pinned in a Build plan. Null, reported, for a generator the game does not know.
     */
    @Nullable
    public static Node power(final Context ctx, final FfNode card, final FfRecipe recipe, final int x, final int y) {
        final Node node = ctx.maker()
            .makePower(recipe.powerSource(), card.machineConfigTiers());
        if (node == null) {
            ctx.report()
                .add(Kind.UNMATCHED, recipe.name(), "a generator GTNH Planner does not know");
            return null;
        }
        node.x = x;
        node.y = y;
        final Double pin = card.solvePin();
        if (ctx.solveMode() && pin != null && pin > 0) {
            node.machineConfig.setMachineCount((int) Math.ceil(pin - 1e-9));
            node.setMachineCountFixed(true);
        } else {
            node.machineConfig.setMachineCount((int) Math.max(1, Math.ceil(card.machineCount() - 1e-9)));
            // Build runs every card at its count, generators too (as recipe cards come over: FfSettings).
            if (!ctx.solveMode()) node.setMachineCountFixed(true);
        }
        return node;
    }

    /**
     * FF's Tank card (its free mirror of a fluid Canner recipe) as a fluid tank card: emptying when the recipe takes
     * the cell (its fluid comes out), filling when it takes the fluid. Its count is FF's crafts a second (a Tank craft
     * is a tick), never pinned: a tank has no count to show or clear, and FF's Build plans pin every card, tanks too
     * (20 cells a second for a count of 1). Null when the game builds no tank for it.
     */
    @Nullable
    public static Node tank(final Context ctx, final FfNode card, final FfRecipe recipe, final int x, final int y) {
        boolean takesFluid = false;
        for (final FfSlot in : recipe.inputs()) takesFluid |= "fluid".equals(in.kind());
        final List<FfSlot> side = takesFluid ? recipe.outputs() : recipe.inputs();
        FfSlot cell = null;
        for (final FfSlot s : side) if ("item".equals(s.kind()) && cell == null) cell = s;
        if (cell == null) return null;
        final Node node = ctx.maker()
            .makeTank(!takesFluid, cell.id());
        if (node == null) return null;
        node.x = x;
        node.y = y;
        final double perSecond = card.machineCount() * 20.0 / Math.max(1, recipe.durationTicks());
        node.machineConfig.setMachineCount((int) Math.max(1, Math.min(Integer.MAX_VALUE, Math.ceil(perSecond))));
        return node;
    }

    /**
     * A shared machine's recipes on one machine again: one card, its recipes as sections at its place, the card's pin
     * (set on the first) the machine's count, which its recipes' machines then add up to.
     */
    public static com.gtnhplanner.data.flowchart.MachineGroup share(final List<Node> made,
        final com.gtnhplanner.data.flowchart.MachineGroup g) {
        g.setHeader("Shared machine");
        final Node host = made.getFirst();
        if (host.isMachineCountFixed()) {
            g.setMachineCapacity(host.machineConfig.getMachineCount());
            g.setPinned(true);
        }
        for (final Node n : made) {
            g.addSection(n.id);
            n.setMachineCountFixed(false);
            n.x = host.x;
            n.y = host.y;
        }
        return g;
    }

    /**
     * The port on a card that a wire end names, found by resource: the handle's, the wire's, the drawer's
     * ({@code extra}), and for an input every id of the recipe slot it lands on (alternatives, the card's pick), so an
     * ore dictionary slot fed a concrete item still finds its port. -1 when the card has none.
     *
     * @param recipe the card's recipe as the plan carries it; null when the plan has none for it (only the ids then)
     */
    public static int port(final Ports ports, final Node node, @Nullable final FfRecipe recipe,
        final Map<Integer, FfSlot> overrides, final boolean output, @Nullable final String handle,
        final FfPlan.FfEdge e, @Nullable final String extra) {
        final FfHandle h = FfHandle.parse(handle);
        final String kind = h != null ? h.kind() : e.resourceKind();
        final List<String> want = new ArrayList<>();
        if (h != null) want.add(h.resourceId());
        want.add(e.resourceId());
        if (extra != null) want.add(extra);
        if (!output && recipe != null) {
            final List<FfSlot> inputs = recipe.inputs();
            for (int i = 0; i < inputs.size(); i++) {
                final List<String> ids = new ArrayList<>(
                    inputs.get(i)
                        .ids());
                final FfSlot pick = overrides.get(i);
                if (pick != null) ids.add(pick.id());
                if (FfIds.anySame(ids, want)) {
                    want.addAll(ids);
                    break;
                }
            }
        }
        return ports.find(node, output, kind, want);
    }

    /**
     * A custom rate card's settings ({@link com.gtnhplanner.power.CustomRate}): which way it runs, its dial (EU/t on a
     * card holding EU, a second's worth of anything else) and the resource it holds, by the game's key.
     */
    public static Map<String, String> customRateSettings(final boolean supply, final double perSecond,
        final String resourceKey) {
        final boolean eu = com.gtnhplanner.power.Energy.KEY.equals(resourceKey);
        final Map<String, String> settings = new LinkedHashMap<>();
        settings.put(
            com.gtnhplanner.power.CustomRate.MODE,
            supply ? com.gtnhplanner.power.CustomRate.SUPPLY : com.gtnhplanner.power.CustomRate.REQUEST);
        settings.put(com.gtnhplanner.power.CustomRate.RATE, String.valueOf(eu ? perSecond / 20 : perSecond));
        settings.put(com.gtnhplanner.power.CustomRate.RESOURCE, resourceKey);
        return settings;
    }
}
