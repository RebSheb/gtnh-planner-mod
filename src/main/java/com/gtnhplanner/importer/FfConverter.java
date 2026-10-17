package com.gtnhplanner.importer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.gtnhplanner.data.flowchart.BoardGroup;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Groups;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.importer.FfDrawers.Role;
import com.gtnhplanner.importer.FfDrawers.Target;
import com.gtnhplanner.importer.FfPlan.FfEdge;
import com.gtnhplanner.importer.FfPlan.FfNode;
import com.gtnhplanner.importer.FfPlan.FfNote;
import com.gtnhplanner.importer.FfPlan.FfRecipe;
import com.gtnhplanner.importer.FfPlan.FfSection;
import com.gtnhplanner.importer.FfPlan.FfSlot;
import com.gtnhplanner.importer.FfPlan.FfStorage;
import com.gtnhplanner.importer.FfPlan.FfTarget;
import com.gtnhplanner.importer.ImportReport.Kind;
import com.gtnhplanner.importer.NodeMaker.PortInfo;

/**
 * Turns an {@link FfPlan} into a GTNH Planner {@link Graph}, reporting whatever does not carry over as it was.
 *
 * <ul>
 * <li>Cards become nodes, each FF recipe matched to an in-game one ({@link RecipeMatcher}); a card with no match is
 * left out. A shared machine stays one: its recipes become the sections of one shared card.</li>
 * <li>Wires become edges, landing on the port that holds the wire's resource (FF names ports by resource, never by
 * slot index). A buffer drawer (fed and drawn from) wired only to cards comes over as a buffer drawer, its feeders its
 * makers and its takers its users; any other buffer has no counterpart, and each feeder is wired straight to each
 * taker.</li>
 * <li>Drawers keep the role their wires give them (FF's storage roles); their rate rules come over only from Solve
 * and Pool plans, the only plans where FF enforces them. Custom rate cards come in as custom rate cards (as drawers
 * where the game cannot build one), hatch-supplied fluids as source drawers.</li>
 * <li>Pool plans ignore saved wires in FF, so every maker of a resource is wired to every user of it.</li>
 * <li>Positions keep FF's layout, scaled to GTNH Planner's cards; an Arrange afterwards tidies it.</li>
 * <li>Text notes become the board's notes, placed and sized at the same scale, with their colour and text size.</li>
 * </ul>
 * Power cards and disabled cards are left out.
 */
public final class FfConverter {

    /**
     * FF's board px for ours: one to one. Its one-mode board draws our card (320 wide, rows of two cells) and our
     * drawers, and places every plan, old ones too, where its positions say, so a plan reads here as it shows there.
     */
    static final double SCALE_X = 1;
    static final double SCALE_Y = 1;
    static final int MARGIN = 40;
    /** Where a hatch's source drawer sits, left of its card, in GTNH Planner pixels. */
    static final int HATCH_DRAWER_DX = 180;

    public record Result(Graph graph, ImportReport report) {}

    private FfConverter() {}

    public static Result convert(final FfPlan plan, final RecipeIndex index, final NodeMaker maker) {
        return new Run(plan, index, maker).run();
    }

    /** One recipe of a card, on the board. */
    private record Placed(Node node, FfNode card, FfRecipe recipe, Map<Integer, FfSlot> overrides, int section,
        String name) {}

    /** A custom rate card, waiting to become a drawer. */
    private record CustomRate(FfNode card, FfSlot slot, boolean supply) {}

    /** A custom rate card's dial: which way it runs, its rate a second, and its pinned count (null: none). */
    private record Dial(boolean supply, double perSecond, @Nullable Double pinned) {}

    /** A drawer to be: what it is, and the ports it takes. */
    private static final class PendingDrawer {

        final Drawer.Kind kind;
        final String subject;
        @Nullable
        final String label;
        final int x, y;
        final Target target;
        @Nullable
        final String converted;
        final List<Drawer.Link> links = new ArrayList<>();
        final List<PortInfo> infos = new ArrayList<>();
        /** A buffer's users: the input ports it feeds. */
        final List<Drawer.Link> takers = new ArrayList<>();
        /** A custom rate card's dial, when this drawer to be is one: it comes in as the card itself where it can. */
        @Nullable
        Dial dial;

        PendingDrawer(final Drawer.Kind kind, final String subject, @Nullable final String label, final int x,
            final int y, final Target target, @Nullable final String converted) {
            this.kind = kind;
            this.subject = subject;
            this.label = label;
            this.x = x;
            this.y = y;
            this.target = target;
            this.converted = converted;
        }

        void link(final Node node, final int port, final PortInfo info) {
            final Drawer.Link link = new Drawer.Link(node.id, port);
            if (links.contains(link)) return;
            links.add(link);
            infos.add(info);
        }

        /** A buffer's user: an input port it feeds. */
        void taker(final Node node, final int port, final PortInfo info) {
            final Drawer.Link link = new Drawer.Link(node.id, port);
            if (takers.contains(link)) return;
            takers.add(link);
            infos.add(info);
        }
    }

    private static final class Run {

        final FfPlan plan;
        final RecipeIndex index;
        final NodeMaker maker;
        final ImportReport report = new ImportReport();
        final Graph graph;
        final Map<String, FfRecipe> recipes;
        final Map<String, FfStorage> storages = new LinkedHashMap<>();
        final Map<String, Role> roles;
        /** Card id, then section, to what was placed. */
        final Map<String, Map<Integer, Placed>> placed = new LinkedHashMap<>();
        final Map<String, CustomRate> customRates = new LinkedHashMap<>();
        /** Every card of the plan: a wire to one that was left out goes quietly, counted. */
        final Set<String> cardIds = new HashSet<>();
        /** Drawers to be, by FF storage id, custom rate card id, or "hatch:<card>:<fluid>". */
        final Map<String, PendingDrawer> pending = new LinkedHashMap<>();
        /** What each placed node's ports hold. */
        final FfCards.Ports ports;
        final FfCards.Context ctx;
        final double minX, minY;
        int lostWires;
        /** Buffers joined into wires (wired to another drawer or by a cell wire): no drawer of their own. */
        final Set<String> joinedBuffers = new HashSet<>();

        Run(final FfPlan plan, final RecipeIndex index, final NodeMaker maker) {
            this.plan = plan;
            this.index = index;
            this.maker = maker;
            this.graph = new Graph(plan.name());
            this.ports = new FfCards.Ports(maker);
            this.ctx = new FfCards.Context(index, maker, plan.solveMode(), report);
            this.recipes = plan.recipesById();
            for (final FfStorage s : plan.storages()) storages.put(s.id(), s);
            for (final FfNode n : plan.nodes()) cardIds.add(n.id());
            this.roles = FfDrawers.roles(plan);
            double x = Double.POSITIVE_INFINITY, y = Double.POSITIVE_INFINITY;
            for (final FfNode n : plan.nodes()) {
                x = Math.min(x, n.x());
                y = Math.min(y, n.y());
            }
            for (final FfStorage s : plan.storages()) {
                x = Math.min(x, s.x());
                y = Math.min(y, s.y());
            }
            for (final FfNote n : plan.textNotes()) {
                x = Math.min(x, n.x());
                y = Math.min(y, n.y());
            }
            minX = Double.isFinite(x) ? x : 0;
            minY = Double.isFinite(y) ? y : 0;
        }

        /** What each node and drawer the import made came from, by FF id (a card, storage or custom rate card). */
        final Map<UUID, String> origin = new HashMap<>();

        /** Notes what was added to the graph since {@code before} (node and drawer ids) as made from {@code ffId}. */
        private void madeFrom(final Set<UUID> before, final String ffId) {
            for (final UUID id : graph.nodes.keySet()) if (!before.contains(id)) origin.putIfAbsent(id, ffId);
            for (final UUID id : graph.drawers.keySet()) if (!before.contains(id)) origin.putIfAbsent(id, ffId);
        }

        private Set<UUID> ids() {
            final Set<UUID> ids = new HashSet<>(graph.nodes.keySet());
            ids.addAll(graph.drawers.keySet());
            return ids;
        }

        Result run() {
            for (final FfNode card : plan.nodes()) {
                final Set<UUID> before = ids();
                placeCard(card);
                madeFrom(before, card.id());
            }
            if (plan.poolMode()) wirePool();
            else for (final FfEdge e : expandBuffers()) wire(e);
            supplyHatches();
            planTarget();
            makeDrawers();
            reportUnusedStorages();
            placeNotes();
            placeBoards();
            if (lostWires > 0) report.add(
                Kind.WIRE,
                "Plan",
                lostWires + " wire" + (lostWires == 1 ? "" : "s") + " to cards that were left out dropped");
            if (!plan.solveMode()) report.add(
                Kind.NOTE,
                "Plan",
                "A Build plan: each card's machine count came over pinned, as Build runs it, and drawer rates as no"
                    + " rule. Unpin a card, or set a drawer's rate, to let the plan size itself.");
            for (final String note : plan.notes()) report.add(Kind.NOTE, "Plan", note);
            report.setCounts(graph.nodes.size(), graph.edges.size(), graph.drawers.size(), graph.notes.size());
            return new Result(graph, report);
        }

        // region Cards

        void placeCard(final FfNode card) {
            final FfRecipe recipe = recipes.get(card.recipeId());
            if (recipe == null) {
                report.add(Kind.UNMATCHED, card.recipeId(), "the plan does not carry this card's recipe");
                return;
            }
            if (recipe.isCustomRate()) {
                customRate(card, recipe);
                return;
            }
            if (!card.enabled()) {
                report.add(Kind.DROPPED, recipe.name(), "a switched-off card; left out");
                return;
            }
            if (recipe.power()) {
                placePower(card, recipe);
                return;
            }
            // FF's free Tank is a fluid tank card here; a game that builds none gets the Canner recipe it mirrors.
            if (recipe.isPlannerTank() && placeTank(card, recipe)) return;
            final List<FfSection> sections = new ArrayList<>();
            sections.add(new FfSection(card.recipeId(), card.overrides()));
            sections.addAll(card.extraRecipes());
            final List<Node> made = new ArrayList<>();
            for (int i = 0; i < sections.size(); i++) {
                final FfRecipe r = recipes.get(
                    sections.get(i)
                        .recipeId());
                final String name = r == null ? card.recipeId() : r.name();
                if (r == null) {
                    report.add(Kind.UNMATCHED, name, "the plan does not carry this recipe");
                    continue;
                }
                final Node node = placeSection(
                    card,
                    r,
                    sections.get(i)
                        .overrides(),
                    i,
                    name);
                if (node != null) made.add(node);
            }
            if (made.size() > 1) share(made);
        }

        /**
         * A shared machine's recipes on one machine again: one card, its recipes as sections at its place, the
         * card's pin (set on the first) the machine's count, which its recipes' machines then add up to.
         */
        void share(final List<Node> made) {
            final com.gtnhplanner.data.flowchart.MachineGroup g = FfCards
                .share(made, new com.gtnhplanner.data.flowchart.MachineGroup());
            graph.groups.put(g.getId(), g);
        }

        Node placeSection(final FfNode card, final FfRecipe recipe, final Map<Integer, FfSlot> overrides,
            final int section, final String name) {
            final Node node = FfCards
                .section(ctx, ports, card, recipe, overrides, section, name, px(card.x()), py(card.y()));
            if (node == null) return null;
            graph.addNode(node);
            final Placed p = new Placed(node, card, recipe, overrides, section, name);
            placed.computeIfAbsent(card.id(), k -> new HashMap<>())
                .put(section, p);
            return node;
        }

        /** A power card (FfCards.power), placed. */
        void placePower(final FfNode card, final FfRecipe recipe) {
            final Node node = FfCards.power(ctx, card, recipe, px(card.x()), py(card.y()));
            if (node == null) return;
            graph.addNode(node);
            placed.computeIfAbsent(card.id(), k -> new HashMap<>())
                .put(0, new Placed(node, card, recipe, Map.of(), 0, recipe.name()));
        }

        /** FF's Tank card as a fluid tank card (FfCards.tank), placed; false when the game builds no tank for it. */
        boolean placeTank(final FfNode card, final FfRecipe recipe) {
            final Node node = FfCards.tank(ctx, card, recipe, px(card.x()), py(card.y()));
            if (node == null) return false;
            graph.addNode(node);
            placed.computeIfAbsent(card.id(), k -> new HashMap<>())
                .put(0, new Placed(node, card, recipe, Map.of(), 0, recipe.name()));
            return true;
        }

        /**
         * A loose cell wire (FF wires a cell straight into a fluid slot, or a fluid into a cell slot, and its solver
         * slips a free Tank between): a fluid tank card between them, wired to both. False when the game builds none.
         */
        boolean tankBetween(final FfEdge e, final Placed from, final Placed to) {
            final FfHandle out = FfHandle.parse(e.sourceHandle()), in = FfHandle.parse(e.targetHandle());
            if (out == null || in == null
                || out.kind()
                    .equals(in.kind()))
                return false;
            final boolean emptying = "item".equals(out.kind());
            final Node tank = maker.makeTank(emptying, emptying ? out.resourceId() : in.resourceId());
            if (tank == null) return false;
            final int o = port(from, true, e.sourceHandle(), e, null), i = port(to, false, e.targetHandle(), e, null);
            final int tankIn = findPort(tank, false, out.kind(), List.of(out.resourceId())),
                tankOut = findPort(tank, true, in.kind(), List.of(in.resourceId()));
            if (o < 0 || i < 0 || tankIn < 0 || tankOut < 0) return false;
            tank.x = snap((from.node().x + to.node().x) / 2.0);
            tank.y = snap((from.node().y + to.node().y) / 2.0);
            graph.addNode(tank);
            graph.addEdge(new Edge(UUID.randomUUID(), from.node().id, tank.id, o, tankIn));
            graph.addEdge(new Edge(UUID.randomUUID(), tank.id, to.node().id, tankOut, i));
            report.add(
                Kind.CONVERTED,
                e.resourceId(),
                "a loose cell wire from " + from.name()
                    + " to "
                    + to.name()
                    + ": a fluid tank put between them, "
                    + (emptying ? "emptying" : "filling")
                    + " the cells");
            return true;
        }

        void customRate(final FfNode card, final FfRecipe recipe) {
            final String name = "Custom rate card";
            if (!card.enabled()) {
                report.add(Kind.DROPPED, name, "a switched-off card; left out");
                return;
            }
            final boolean supply = !recipe.outputs()
                .isEmpty();
            final FfSlot slot = supply ? recipe.outputs()
                .getFirst()
                : recipe.inputs()
                    .isEmpty() ? null
                        : recipe.inputs()
                            .getFirst();
            if (slot == null) {
                report.add(Kind.DROPPED, name, "it holds no resource; left out");
                return;
            }
            customRates.put(card.id(), new CustomRate(card, slot, supply));
        }

        // endregion

        // region Wires

        /**
         * The plan's wires with every buffer drawer taken out: each wire into a buffer joined to each wire out of
         * it, one buffer at a time, so a chain of buffers still joins its ends.
         */
        List<FfEdge> expandBuffers() {
            final List<FfEdge> all = new ArrayList<>(plan.edges());
            for (final Map.Entry<String, Role> r : roles.entrySet()) {
                if (r.getValue() != Role.BUFFER) continue;
                final String b = r.getKey();
                // A buffer wired only to cards comes over as a buffer drawer; only the rest are joined into wires.
                if (FfDrawers.keepsBuffer(b, plan.edges(), storages.keySet())) continue;
                joinedBuffers.add(b);
                final List<FfEdge> ins = new ArrayList<>(), outs = new ArrayList<>();
                for (final FfEdge e : all) {
                    if (e.target()
                        .equals(b)
                        && !e.source()
                            .equals(b))
                        ins.add(e);
                    if (e.source()
                        .equals(b)
                        && !e.target()
                            .equals(b))
                        outs.add(e);
                }
                all.removeIf(
                    e -> e.source()
                        .equals(b)
                        || e.target()
                            .equals(b));
                for (final FfEdge in : ins) for (final FfEdge out : outs) all.add(
                    new FfEdge(
                        in.id() + ">" + out.id(),
                        in.source(),
                        out.target(),
                        in.sourceHandle(),
                        out.targetHandle(),
                        in.resourceKind(),
                        in.resourceId(),
                        in.crossForm() || out.crossForm()));
                final FfStorage s = storages.get(b);
                report.add(
                    Kind.CONVERTED,
                    s == null ? b : storageName(s),
                    "a buffer drawer wired to another drawer or by a cell wire, replaced by " + ins.size() * outs.size()
                        + " direct wire"
                        + (ins.size() * outs.size() == 1 ? "" : "s")
                        + " from its "
                        + ins.size()
                        + " feeder"
                        + (ins.size() == 1 ? "" : "s")
                        + " to its "
                        + outs.size()
                        + " taker"
                        + (outs.size() == 1 ? "" : "s"));
            }
            return all;
        }

        /** What a wire end is on the board: a placed card section, or a drawer to be. */
        private record End(@Nullable Placed card, @Nullable String drawer) {}

        @Nullable
        End end(final String id, @Nullable final String handle) {
            final Map<Integer, Placed> sections = placed.get(id);
            if (sections != null) {
                final Placed p = sections.get(FfHandle.sectionOf(handle));
                return p == null ? null : new End(p, null);
            }
            if (storages.containsKey(id) || customRates.containsKey(id)) return new End(null, id);
            return null;
        }

        void wire(final FfEdge e) {
            final End from = end(e.source(), e.sourceHandle()), to = end(e.target(), e.targetHandle());
            if (from == null || to == null) {
                if (from == null && !known(e.source()) || to == null && !known(e.target()))
                    report.add(Kind.WIRE, e.resourceId(), "a wire to something not on the plan dropped");
                else lostWires++;
                return;
            }
            if (e.crossForm()) {
                if (from.card() != null && to.card() != null && tankBetween(e, from.card(), to.card())) return;
                report.add(
                    Kind.WIRE,
                    e.resourceId(),
                    "a loose cell wire (cell into a fluid slot) dropped; GTNH Planner needs a fluid tank there");
                return;
            }
            if (from.card() != null && to.card() != null) {
                final int out = port(from.card(), true, e.sourceHandle(), e, null);
                final int in = port(to.card(), false, e.targetHandle(), e, null);
                if (out < 0 || in < 0) {
                    report.add(
                        Kind.WIRE,
                        e.resourceId(),
                        "wire from " + from.card()
                            .name()
                            + " to "
                            + to.card()
                                .name()
                            + ": no "
                            + (out < 0 ? "output" : "input")
                            + " for it on "
                            + (out < 0 ? from.card() : to.card()).name());
                    return;
                }
                graph.addEdge(
                    new Edge(
                        UUID.randomUUID(),
                        from.card()
                            .node().id,
                        to.card()
                            .node().id,
                        out,
                        in));
                return;
            }
            if (from.drawer() != null && to.drawer() != null) {
                report.add(Kind.WIRE, e.resourceId(), "a wire between two drawers dropped");
                return;
            }
            final boolean drawerFeeds = from.drawer() != null;
            final String drawerId = drawerFeeds ? from.drawer() : to.drawer();
            final Placed card = drawerFeeds ? to.card() : from.card();
            final PendingDrawer d = pendingFor(drawerId);
            final boolean buffer = d != null && d.kind == Drawer.Kind.BUFFER;
            if (d == null || !buffer && d.kind.linksInputs() != drawerFeeds) {
                report.add(Kind.WIRE, e.resourceId(), "a drawer wired the wrong way round dropped");
                return;
            }
            final int port = port(
                card,
                !drawerFeeds,
                drawerFeeds ? e.targetHandle() : e.sourceHandle(),
                e,
                resourceOf(drawerId));
            if (port < 0) {
                report.add(Kind.WIRE, d.subject, "no port for it on " + card.name());
                return;
            }
            if (buffer && drawerFeeds) d.taker(card.node(), port, describe(card.node(), false).get(port));
            else d.link(card.node(), port, describe(card.node(), !drawerFeeds).get(port));
        }

        boolean known(final String id) {
            return cardIds.contains(id) || storages.containsKey(id);
        }

        @Nullable
        String resourceOf(final String drawerId) {
            final FfStorage s = storages.get(drawerId);
            if (s != null) return s.resourceId();
            final CustomRate c = customRates.get(drawerId);
            return c == null ? null
                : c.slot()
                    .id();
        }

        /**
         * The port on a card that a wire end names, found by resource: the handle's, the wire's, and for an input
         * every id of the recipe slot it lands on (alternatives, the card's pick), so an ore dictionary slot fed a
         * concrete item still finds its port.
         */
        int port(final Placed p, final boolean output, @Nullable final String handle, final FfEdge e,
            @Nullable final String extra) {
            return FfCards.port(ports, p.node(), p.recipe(), p.overrides(), output, handle, e, extra);
        }

        int findPort(final Node node, final boolean output, final String kind, final List<String> ids) {
            return ports.find(node, output, kind, ids);
        }

        List<PortInfo> describe(final Node node, final boolean output) {
            return ports.describe(node, output);
        }

        /** Pool plans: every maker of a resource wired to every user of it, drawers to every port of theirs. */
        void wirePool() {
            final Map<String, List<int[]>> makers = new LinkedHashMap<>(), users = new LinkedHashMap<>();
            final List<Placed> all = new ArrayList<>();
            for (final Map<Integer, Placed> sections : placed.values()) all.addAll(sections.values());
            for (int n = 0; n < all.size(); n++) {
                final Node node = all.get(n)
                    .node();
                final List<PortInfo> outs = describe(node, true), ins = describe(node, false);
                for (int i = 0; i < outs.size(); i++) makers.computeIfAbsent(
                    outs.get(i)
                        .key(),
                    k -> new ArrayList<>())
                    .add(new int[] { n, i });
                for (int i = 0; i < ins.size(); i++) users.computeIfAbsent(
                    ins.get(i)
                        .key(),
                    k -> new ArrayList<>())
                    .add(new int[] { n, i });
            }
            for (final Map.Entry<String, List<int[]>> m : makers.entrySet()) {
                if (m.getKey()
                    .isEmpty()) continue;
                final List<int[]> takers = users.get(m.getKey());
                if (takers == null) continue;
                for (final int[] from : m.getValue()) for (final int[] to : takers) {
                    if (from[0] == to[0]) continue;
                    graph.addEdge(
                        new Edge(
                            UUID.randomUUID(),
                            all.get(from[0])
                                .node().id,
                            all.get(to[0])
                                .node().id,
                            from[1],
                            to[1]));
                }
            }
            report.add(
                Kind.CONVERTED,
                "Plan",
                "a Pool plan: every card making a resource is wired to every card using it");
            final Set<String> drawerIds = new LinkedHashSet<>(storages.keySet());
            drawerIds.addAll(customRates.keySet());
            for (final String id : drawerIds) {
                final PendingDrawer d = pendingFor(id);
                final String resource = resourceOf(id);
                if (d == null || resource == null) continue;
                for (final Placed p : all) {
                    final List<PortInfo> infos = describe(p.node(), !d.kind.linksInputs());
                    for (int i = 0; i < infos.size(); i++) if (FfIds.anySame(
                        infos.get(i)
                            .ids(),
                        List.of(resource))) d.link(p.node(), i, infos.get(i));
                }
            }
        }

        // endregion

        // region Drawers

        /** The drawer to be for a storage or custom rate card; null for a buffer or an unwired drawer. */
        @Nullable
        PendingDrawer pendingFor(final String id) {
            final PendingDrawer had = pending.get(id);
            if (had != null) return had;
            final FfStorage s = storages.get(id);
            if (s != null) {
                final Role role = roles.getOrDefault(id, Role.IDLE);
                final Drawer.Kind kind = FfDrawers.kindOf(role);
                if (kind == null) return null;
                final PendingDrawer d = new PendingDrawer(
                    kind,
                    storageName(s),
                    s.displayName(),
                    px(s.x()),
                    py(s.y()),
                    // A buffer's rule is live only where a board enforced it: a one-mode plan.
                    FfDrawers.target(s, role, role == Role.BUFFER ? plan.planMode() : plan.solveMode()),
                    null);
                pending.put(id, d);
                return d;
            }
            final CustomRate c = customRates.get(id);
            if (c == null) return null;
            final FfNode card = c.card();
            final double perSecond = card.customRatePerSecond() != null ? card.customRatePerSecond()
                : c.slot()
                    .amount();
            // A custom rate card is a machine making (or eating) perSecond each: Build fixes how many, Solve only a
            // pin does; unpinned in Solve the plan decides, so no rule.
            final Double count = plan.solveMode() ? card.solvePin() : Double.valueOf(card.machineCount());
            final Target target;
            if (count == null || count <= 0) target = Target.NONE;
            else target = new Target(
                c.supply() && !plan.solveMode() ? Drawer.Rule.AT_MOST : Drawer.Rule.EXACTLY,
                perSecond * count);
            final String rule = target.rule() == Drawer.Rule.ANY ? "no rule"
                : (target.rule() == Drawer.Rule.AT_MOST ? "at most " : "exactly ") + fmt(target.rate()) + "/s";
            final PendingDrawer d = new PendingDrawer(
                c.supply() ? Drawer.Kind.SOURCE : Drawer.Kind.PRODUCT,
                "Custom rate card",
                c.slot()
                    .displayName(),
                px(card.x()),
                py(card.y()),
                target,
                "turned into a " + (c.supply() ? "source" : "product")
                    + " drawer for "
                    + c.slot()
                        .displayName()
                    + ", "
                    + rule);
            d.dial = new Dial(c.supply(), perSecond, count == null || count <= 0 ? null : count);
            pending.put(id, d);
            return d;
        }

        /** Fluids a card's hatch supplies (FF's hatchSupplies) come from a source drawer beside it. */
        void supplyHatches() {
            for (final Map<Integer, Placed> sections : placed.values()) for (final Placed p : sections.values()) {
                for (final String fluid : p.card()
                    .hatchSupplies()) {
                    final int in = findPort(p.node(), false, "fluid", List.of(fluid));
                    if (in < 0 || fed(p.node(), in)) continue;
                    final String key = "hatch:" + p.card()
                        .id() + ":" + fluid;
                    PendingDrawer d = pending.get(key);
                    if (d == null) {
                        final FfRecipe own = recipes.get(
                            p.card()
                                .recipeId());
                        d = new PendingDrawer(
                            Drawer.Kind.SOURCE,
                            own != null ? own.name() : p.name(),
                            null,
                            p.node().x - HATCH_DRAWER_DX,
                            p.node().y,
                            Target.NONE,
                            "its " + fluid + " hatch is a source drawer");
                        pending.put(key, d);
                    }
                    d.link(p.node(), in, describe(p.node(), false).get(in));
                }
            }
        }

        boolean fed(final Node node, final int input) {
            for (final Edge e : graph.getEdges())
                if (e.targetNodeId.equals(node.id) && e.targetInputIndex == input) return true;
            final Drawer.Link link = new Drawer.Link(node.id, input);
            for (final PendingDrawer d : pending.values())
                if (d.kind.linksInputs() && d.links.contains(link)) return true;
            return false;
        }

        /** The plan's legacy target rate (FF's project.targetRate) lands on the first card making it. */
        void planTarget() {
            final FfTarget t = plan.targetRate();
            if (t == null) return;
            for (final FfNode card : plan.nodes()) {
                final FfTarget own = card.targetOutput();
                if (own != null && FfIds.same(own.resourceId(), t.resourceId())) return;
            }
            for (final Map<Integer, Placed> sections : placed.values()) for (final Placed p : sections.values()) {
                final int out = findPort(p.node(), true, t.kind(), List.of(t.resourceId()));
                if (out < 0 || p.node().targetOutputRates.containsKey(out)) continue;
                p.node().targetOutputRates.put(out, t.perSecond());
                report.add(Kind.CONVERTED, p.name(), "the plan's target rate of " + fmt(t.perSecond()) + "/s set here");
                return;
            }
            report.add(
                Kind.DROPPED,
                "Plan",
                "its target rate names " + t.resourceId() + ", which no imported card makes");
        }

        void makeDrawers() {
            for (final Map.Entry<String, PendingDrawer> entry : pending.entrySet()) {
                final PendingDrawer d = entry.getValue();
                final Set<UUID> before = ids();
                try {
                    makeDrawer(d);
                } finally {
                    // A hatch's drawer is its card's (hatch:<card>:<fluid>).
                    final String key = entry.getKey();
                    madeFrom(before, hatchCard(key));
                }
            }
        }

        /** The card a hatch drawer's key names, or the key itself when it is no hatch's. */
        private String hatchCard(final String key) {
            if (key.startsWith("hatch:"))
                for (final String card : cardIds) if (key.startsWith("hatch:" + card + ":")) return card;
            return key;
        }

        void makeDrawer(final PendingDrawer d) {
            {
                if (d.links.isEmpty() && d.takers.isEmpty()) {
                    report.add(Kind.DROPPED, d.subject, "a drawer with no wires left in GTNH Planner; left out");
                    return;
                }
                if (d.kind == Drawer.Kind.BUFFER) {
                    makeBuffer(d);
                    return;
                }
                final PortInfo first = d.infos.getFirst();
                if (d.dial != null && makeCustomRate(d, first)) return;
                final Drawer drawer = new Drawer(d.kind, first.key());
                drawer.setLabel(d.label != null && !d.label.isEmpty() ? d.label : first.label());
                drawer.setX(d.x);
                drawer.setY(d.y);
                drawer.setTarget(d.target.rule(), d.target.rate());
                graph.addDrawer(drawer);
                for (final Drawer.Link link : d.links) {
                    final Drawer holder = graph.drawerAt(link.nodeId(), link.portIndex(), d.kind.linksInputs());
                    if (holder != null && holder != drawer) report.add(
                        Kind.WIRE,
                        d.subject,
                        "a port it shares with drawer " + holder.getLabel()
                            + " went to this one (a port has one drawer)");
                    graph.linkDrawer(drawer.getId(), link);
                }
                if (d.converted != null) report.add(Kind.CONVERTED, d.subject, d.converted);
            }
        }

        /**
         * A buffer: made a product, its makers linked, then its users, so it is a buffer when it has both and the end
         * it still is when a side found no port ({@link Graph#linkDrawerPort}).
         */
        void makeBuffer(final PendingDrawer d) {
            final PortInfo first = d.infos.getFirst();
            final Drawer drawer = new Drawer(Drawer.Kind.PRODUCT, first.key());
            drawer.setLabel(d.label != null && !d.label.isEmpty() ? d.label : first.label());
            drawer.setX(d.x);
            drawer.setY(d.y);
            drawer.setTarget(d.target.rule(), d.target.rate());
            graph.addDrawer(drawer);
            for (final Drawer.Link link : d.links) graph.linkDrawerPort(drawer.getId(), link, false);
            for (final Drawer.Link link : d.takers) graph.linkDrawerPort(drawer.getId(), link, true);
        }

        /**
         * A custom rate card as one: holding the resource of the ports it was wired to, at its dial, wired to them as
         * the drawer would have been linked. False when the maker builds none (it then comes in as a drawer).
         */
        boolean makeCustomRate(final PendingDrawer d, final PortInfo held) {
            final Dial dial = d.dial;
            final Node node = maker
                .makeCustomRate(FfCards.customRateSettings(dial.supply(), dial.perSecond(), held.key()));
            if (node == null) return false;
            node.x = d.x;
            node.y = d.y;
            if (dial.pinned() != null) {
                node.machineConfig.setMachineCount((int) Math.max(1, Math.round(dial.pinned())));
                node.setMachineCountFixed(true);
            }
            graph.addNode(node);
            for (final Drawer.Link link : d.links) graph.addEdge(
                dial.supply() ? new Edge(UUID.randomUUID(), node.id, link.nodeId(), 0, link.portIndex())
                    : new Edge(UUID.randomUUID(), link.nodeId(), node.id, link.portIndex(), 0));
            return true;
        }

        void reportUnusedStorages() {
            for (final FfStorage s : plan.storages()) {
                final Role role = roles.getOrDefault(s.id(), Role.IDLE);
                if (role == Role.IDLE) report.add(Kind.DROPPED, storageName(s), "an unwired drawer; left out");
                else if (!joinedBuffers.contains(s.id()) && !pending.containsKey(s.id()))
                    report.add(Kind.DROPPED, storageName(s), "its wires all went to cards that were left out");
            }
        }

        // endregion

        // region Notes

        /**
         * Each text note as a board note, at the cards' scale: placed like a card and sized by the same factors,
         * never under a note's smallest size. Notes need nothing else on the plan, so none is ever left out.
         */
        void placeNotes() {
            for (final FfNote n : plan.textNotes()) {
                final Note note = note(n, UUID.randomUUID(), px(n.x()), py(n.y()));
                graph.notes.put(note.getId(), note);
                if (!n.id()
                    .isEmpty()) noteOrigin.put(note.getId(), n.id());
            }
        }

        /** The notes the import made, by the FF id of the annotation each came from. */
        final Map<UUID, String> noteOrigin = new HashMap<>();

        /**
         * FF's boards as the board's groups: each frame at the cards' scale, open or collapsed as it was, its paper and
         * colour kept, and what was in it in it again (docs/design/groups.md). A frame grows to hold its members, here
         * with a guess at their sizes and again once the board knows them.
         */
        void placeBoards() {
            if (plan.boards()
                .isEmpty()) return;
            final Map<String, BoardGroup> byFf = new HashMap<>();
            for (final FfPlan.FfBoard b : plan.boards()) {
                final BoardGroup g = group(
                    b,
                    UUID.randomUUID(),
                    px(b.x()),
                    py(b.y()),
                    b.name() == null || b.name()
                        .isEmpty() ? Groups.newName(graph) : b.name());
                g.setFfId(b.id());
                graph.boardGroups.put(g.getId(), g);
                byFf.put(b.id(), g);
            }
            for (final FfPlan.FfBoard b : plan.boards()) {
                final BoardGroup parent = b.parent() == null ? null : byFf.get(b.parent());
                if (parent != null) Groups.setGroupOf(
                    graph,
                    byFf.get(b.id())
                        .getId(),
                    parent.getId());
            }
            for (final Map.Entry<UUID, String> e : origin.entrySet()) {
                final String in = plan.boardOf()
                    .get(e.getValue());
                final BoardGroup g = in == null ? null : byFf.get(in);
                if (g == null) continue;
                final Node n = graph.nodes.get(e.getKey());
                if (n != null) n.group = g.getId();
                final Drawer d = graph.drawers.get(e.getKey());
                if (d != null) d.setGroup(g.getId());
            }
            for (final Map.Entry<UUID, String> e : noteOrigin.entrySet()) {
                final String in = plan.boardOf()
                    .get(e.getValue());
                final BoardGroup g = in == null ? null : byFf.get(in);
                if (g != null) graph.notes.get(e.getKey())
                    .setGroup(g.getId());
            }
            Groups.sanitize(graph);
            Groups.fit(graph, id -> {
                final Node n = graph.nodes.get(id);
                if (n != null) return new int[] { n.x, n.y, 320, 60 };
                final Drawer d = graph.drawers.get(id);
                if (d != null) return new int[] { d.getX(), d.getY(), 40, 40 };
                final Note note = graph.notes.get(id);
                return note == null ? null : new int[] { note.getX(), note.getY(), note.getWidth(), note.getHeight() };
            });
        }

        // endregion

        int px(final double ffX) {
            return snap((ffX - minX) * SCALE_X) + MARGIN;
        }

        int py(final double ffY) {
            return snap((ffY - minY) * SCALE_Y) + MARGIN;
        }
    }

    /**
     * An annotation as a board note at a place: sized at the cards' scale, never under a note's smallest size (a
     * drawing's, which an arrow may go under on one axis to run level), with its colour, text size and text, and a
     * drawing's kind and dress (docs/design/annotations.md).
     */
    public static Note note(final FfNote n, final UUID id, final int x, final int y) {
        final Note note = new Note(id);
        note.setX(x);
        note.setY(y);
        final FfPlan.FfDrawing d = n.drawing();
        // An arrow may run level (an axis of 0); every other drawing is at least a cell.
        final boolean arrow = d != null && Note.ARROW.equals(d.kind());
        final int minW = d == null ? Note.MIN_W : arrow ? 0 : Note.DRAWING_MIN;
        final int minH = d == null ? Note.MIN_H : arrow ? 0 : Note.DRAWING_MIN;
        note.setWidth(Math.max(minW, (int) Math.round(n.width() * SCALE_X)));
        note.setHeight(Math.max(minH, (int) Math.round(n.height() * SCALE_Y)));
        if (n.colorTag() != null && !n.colorTag()
            .isEmpty()) note.setColor(n.colorTag());
        // A drawing with no tag keeps none (a picture is then framed in grey, not yellow).
        else if (d != null) note.setColor("");
        if (n.fontSize() != null) note.setFontSize(n.fontSize());
        note.setJoined(n.text());
        if (d != null) {
            note.setKind(d.kind());
            note.setArrowDirection(d.arrowDirection());
            note.setArrowHeads(d.arrowHeads());
            note.setArrowWidth(d.arrowWidth());
            if (d.points() != null) {
                final List<int[]> pts = new ArrayList<>();
                for (final double[] p : d.points())
                    pts.add(new int[] { (int) Math.round(p[0] * SCALE_X), (int) Math.round(p[1] * SCALE_Y) });
                note.setPoints(pts);
            }
            note.setImageUrl(d.imageUrl());
            note.setBorder(d.border());
            note.setBorderColor(d.borderColor());
            note.setFillColor(d.fillColor());
            note.setFill(d.fill());
            note.setMarks(d.marks());
        }
        return note;
    }

    /**
     * A board (FF's pocket) as a group at a place, its parent and website id left to the caller: its frame at the
     * cards' scale (the smallest a frame gets when it has no size), open or collapsed as it was, its colour and paper.
     */
    public static BoardGroup group(final FfPlan.FfBoard b, final UUID id, final int x, final int y, final String name) {
        final BoardGroup g = new BoardGroup(id);
        g.setName(name);
        if (b.colorTag() != null && !b.colorTag()
            .isEmpty()) g.setColor(b.colorTag());
        g.setTheme(b.theme());
        g.setPattern(b.pattern());
        g.setCollapsed(!b.expanded());
        g.setX(x);
        g.setY(y);
        g.setWidth(
            b.width() == null ? BoardGroup.MIN_W : Math.max(BoardGroup.MIN_W, (int) Math.round(b.width() * SCALE_X)));
        g.setHeight(
            b.height() == null ? BoardGroup.MIN_H : Math.max(BoardGroup.MIN_H, (int) Math.round(b.height() * SCALE_Y)));
        return g;
    }

    private static int snap(final double v) {
        return (int) Math.round(v / 10) * 10;
    }

    private static String storageName(final FfStorage s) {
        return s.displayName() != null && !s.displayName()
            .isEmpty() ? s.displayName() : s.resourceId();
    }

    private static String fmt(final double d) {
        if (d == Math.rint(d)) return String.valueOf((long) d);
        return String.valueOf(Math.round(d * 1000) / 1000.0);
    }
}
