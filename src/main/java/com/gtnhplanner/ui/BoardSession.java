package com.gtnhplanner.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.api.PlanAPI;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Plan;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.data.flowchart.balancer.BalanceMode;
import com.gtnhplanner.data.flowchart.balancer.BalanceResult;
import com.gtnhplanner.data.flowchart.balancer.DrawerReadout;
import com.gtnhplanner.data.flowchart.balancer.Note;
import com.gtnhplanner.data.flowchart.balancer.Severity;
import com.gtnhplanner.data.flowchart.balancer.SolveInput;
import com.gtnhplanner.data.flowchart.balancer.SolveService;
import com.gtnhplanner.data.flowchart.balancer.SolverMessage;
import com.gtnhplanner.nei.NodeLookupContext;
import com.gtnhplanner.ui.card.CardDefaults;
import com.gtnhplanner.ui.card.CardLayout;
import com.gtnhplanner.ui.card.CardModel;
import com.gtnhplanner.ui.card.MachineChoices;
import com.gtnhplanner.ui.drawer.DrawerCard;
import com.gtnhplanner.ui.drawer.DrawerModel;
import com.gtnhplanner.ui.sound.Sfx;
import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;

import codechicken.nei.recipe.IRecipeHandler;
import codechicken.nei.recipe.RecipeHandlerRef;

/**
 * The open board: which plan slot is shown, its latest solve, and the card models built from it. Every edit goes
 * through {@link #edit} so it is undoable, saved, and re-solved. Lives as long as the screen.
 */
public final class BoardSession {

    private static BoardSession current;

    private Graph graph;
    private long seenVersion = Long.MIN_VALUE;
    private BalanceResult result;
    private Map<UUID, CardModel> models = Collections.emptyMap();
    private Map<UUID, DrawerModel> drawerModels = Collections.emptyMap();
    private List<Notice> notices = List.of();
    private boolean nothingToSolveFor;
    /** The recipes and drawers in parts of the plan nothing sets ({@link Unanchored}), as of the last solve. */
    private java.util.Set<UUID> unset = java.util.Set.of();
    /** Bumped when the set of cards changes, so the canvas knows to rebuild its widgets. */
    private int structure;
    /** The sticky notes the widgets were last built for. */
    private Set<UUID> noteIds = Set.of();
    /** The top bar's unit keys, as the player last left them ({@link PlannerSettings}). */
    private Fmt.RateUnit rateUnit = PlannerSettings.rateUnit();
    private NodeLookupContext pendingLookup;
    private SolveService solver = new SolveService();
    /** Set when the board closed; NEI's pages close it too, and closing back to it reopens it. */
    private boolean closed;
    private SolveService.Result lastResult;

    BoardSession() {
        graph = Plan.getActiveGraph();
        current = this;
    }

    /** The session of the open board, or null when the planner is closed. */
    public static BoardSession current() {
        return current;
    }

    void close() {
        closed = true;
        solver.close();
        settleLive(true);
        PlanAPI.save();
        if (current == this) current = null;
    }

    /**
     * The board is showing again after a close it came back from: an NEI page opened over it (clicking a port, adding a
     * recipe) closes the board's screen and closes back to the same one. The old solver stopped with the close, so a
     * fresh one, or every edit after the visit would wait on a solve that never comes.
     */
    void reopen() {
        if (!closed) return;
        closed = false;
        solver = new SolveService();
        current = this;
    }

    public Graph graph() {
        return graph;
    }

    public Map<UUID, CardModel> models() {
        return models;
    }

    public CardModel model(final UUID nodeId) {
        return models.get(nodeId);
    }

    public Map<UUID, DrawerModel> drawerModels() {
        return drawerModels;
    }

    public DrawerModel drawerModel(final UUID drawerId) {
        return drawerModels.get(drawerId);
    }

    public BalanceResult result() {
        return result;
    }

    public int structure() {
        return structure;
    }

    private HoverScope hover = HoverScope.NONE;
    private String railHoverKey;
    private HoverScope railHover = HoverScope.NONE;

    /**
     * What glows for what the mouse is on: on the board, a port, drawer or wire and what it is wired to; in the
     * overview, a resource everywhere it flows (that wins while set).
     */
    public HoverScope lit() {
        if (spareMenuLive()) return spareMenuScope;
        if (spareHover != null) return spareHoverScope;
        return railHoverKey != null ? railHover : hover;
    }

    /** The resource under the mouse in the overview rail; it wins over the board's while set. */
    public void setRailHoverKey(final String key) {
        final String next = key == null || key.isEmpty() ? null : key;
        if (java.util.Objects.equals(next, railHoverKey)) return;
        railHoverKey = next;
        railHover = HoverScope.resource(next);
    }

    public void setHover(final HoverScope scope) {
        hover = scope == null ? HoverScope.NONE : scope;
    }

    public Fmt.RateUnit rateUnit() {
        return rateUnit;
    }

    public void setRateUnit(final Fmt.RateUnit unit) {
        rateUnit = unit;
        PlannerSettings.setRateUnit(unit);
    }

    /**
     * One undoable edit: records the before state, runs the change, marks the plan changed and saves. The next tick
     * re-solves and rebuilds the cards. Every custom rate card or fluid tank the change left with nothing wired lets go
     * of what it held, in the same step.
     */
    @SuppressWarnings("deprecation")
    public void edit(final Runnable change) {
        settleLive(true);
        PlanAPI.recordEdit(graph, () -> {
            change.run();
            releaseHolders();
            dropWiredTargets();
        });
        graph.touch();
        PlanAPI.save();
    }

    /** What the wheel is changing: the plan as it was before the spin, or null when none is under way. */
    private String liveBefore;
    private Graph liveGraph;
    private long liveAt;
    private Sfx liveSound;

    /** How long the wheel is still before a spin becomes one undo step and is saved. */
    private static final long LIVE_SETTLE_MS = 500;

    /**
     * A change the wheel makes, one notch at a time: applied and solved at once, so the plan follows the wheel, and
     * recorded as one undo step and saved once the wheel has been still a moment ({@link #settleLive}), with
     * {@code settled} played then (null for none).
     */
    public void live(final Runnable change, @Nullable final Sfx settled) {
        if (liveBefore != null && liveGraph != graph) settleLive(true);
        if (liveBefore == null) {
            liveBefore = PlanAPI.undoHistory(graph)
                .beginEdit(graph);
            liveGraph = graph;
        }
        change.run();
        releaseHolders();
        dropWiredTargets();
        graph.touch();
        liveAt = System.currentTimeMillis();
        liveSound = settled;
    }

    /** Ends a spin: one undo step for all of it, and a save. Only once the wheel has been still, unless {@code now}. */
    private void settleLive(final boolean now) {
        if (liveBefore == null || !now && System.currentTimeMillis() - liveAt < LIVE_SETTLE_MS) return;
        if (PlanAPI.undoHistory(liveGraph)
            .commitEdit(liveBefore, liveGraph)) liveGraph.touchLayout();
        liveBefore = null;
        liveGraph = null;
        if (liveSound != null && !now) liveSound.play();
        liveSound = null;
        if (wheeledRates != null) wheeledRates.clear();
        if (wheeledPorts != null) wheeledPorts.clear();
        PlanAPI.save();
    }

    /**
     * A port's own rate is for an open end of the plan only: one wired to a card, or to a drawer, takes its rate from
     * what it is wired to, so a rate left on it goes.
     */
    private void dropWiredTargets() {
        final Set<String> wired = new HashSet<>();
        for (final Edge e : graph.getEdges()) {
            wired.add(portKey(e.sourceNodeId, true, e.sourceOutputIndex));
            wired.add(portKey(e.targetNodeId, false, e.targetInputIndex));
        }
        for (final Drawer d : graph.getDrawers()) for (final Drawer.Link l : d.getLinks()) wired.add(
            portKey(
                l.nodeId(),
                !d.getKind()
                    .linksInputs(),
                l.portIndex()));
        for (final Node n : graph.getNodes()) {
            n.targetOutputRates.keySet()
                .removeIf(i -> wired.contains(portKey(n.id, true, i)));
            n.targetInputRates.keySet()
                .removeIf(i -> wired.contains(portKey(n.id, false, i)));
        }
    }

    /** An undoable change that only moves things: saved, but no re-solve. */
    @SuppressWarnings("deprecation")
    public void editLayout(final Runnable change) {
        settleLive(true);
        PlanAPI.recordEdit(graph, change);
        graph.touchLayout();
        PlanAPI.save();
    }

    /**
     * Remembers the port an NEI lookup (R/U) started from, so the recipe added from NEI's page next wires into it.
     */
    public void armLookup(final UUID nodeId, final boolean output, final int portIndex) {
        pendingLookup = new NodeLookupContext(nodeId, output, portIndex);
    }

    /** Forgets an armed lookup, when the page it was for never opened. */
    public void disarmLookup() {
        pendingLookup = null;
    }

    /** Set when the board comes back; its next tick forgets what is still armed. */
    private boolean disarmOnTick;

    /**
     * Forgets an armed lookup (a port's) on the tick after the board is back. NEI
     * shows the board again and then hands it the recipe picked with "+", in the same click, so that add still finds
     * its lookup; anything armed after it was for a page closed without one.
     */
    public void disarmPending() {
        disarmOnTick = true;
    }

    /** The card added from NEI last, once, for the board to bring into view. */
    private UUID justAdded;
    /** Whether that card is to be centred and selected (the plan button's), not just brought into view (+'s). */
    private boolean focusAdded;

    public UUID takeJustAdded() {
        final UUID id = justAdded;
        justAdded = null;
        return id;
    }

    public boolean takeFocusAdded() {
        final boolean focus = focusAdded;
        focusAdded = false;
        return focus;
    }

    /**
     * Puts an NEI recipe on the board, on the machine last picked for its tab (else NEI's first). When it came from a
     * lookup on a port, it lands beside that card and only that resource is wired; otherwise it lands beside what it
     * wires to.
     */
    public Node addRecipe(final IRecipeHandler handler, final int recipeIndex) {
        return add(MachineChoices.newNode(handler, recipeIndex, null), false, true);
    }

    /**
     * As above, for a card already set up (the plan button's, on the machine picked in its menu), and then centred in
     * view and selected: the board was just opened on it from NEI.
     */
    public Node addAndFocus(final Node node) {
        return add(node, true, true);
    }

    /**
     * Puts a card on the board. Only a card from NEI ({@code fromNei}: its + or the plan button) takes what was armed
     * there: the port it was looked up from (it lands beside it, wired to it alone). Anything else (a non-recipe
     * machine, a custom rate card) never wires itself.
     */
    private Node add(final Node node, final boolean focus, final boolean fromNei) {
        focusAdded = focus;
        final NodeLookupContext origin = fromNei ? pendingLookup : null;
        pendingLookup = null;
        justAdded = node.id;
        edit(() -> {
            graph.addNode(node);
            final Node from = origin == null ? null : graph.nodes.get(origin.nodeId());
            if (from != null && wireToOrigin(node, from, origin)) {
                node.x = origin.output() ? from.x + CardLayout.W + NewCards.GAP : from.x - CardLayout.W - NewCards.GAP;
                node.y = from.y;
                for (int tries = 0; tries < 50 && NewCards.overlapsAnything(graph, node); tries++) node.y += 40;
            } else NewCards.place(graph, node);
        });
        Sfx.PLACE.play();
        return node;
    }

    // region Wires and drawers (each one undoable, saved, re-solved)

    /** Wires an output to an input; drawing a wire that already exists removes it instead. */
    public void connect(final UUID from, final int output, final UUID to, final int input) {
        final boolean[] cut = { false };
        edit(() -> {
            for (final Edge e : new ArrayList<>(graph.getEdges())) {
                if (e.sourceNodeId.equals(from) && e.sourceOutputIndex == output
                    && e.targetNodeId.equals(to)
                    && e.targetInputIndex == input) {
                    graph.removeEdge(e.id);
                    cut[0] = true;
                    return;
                }
            }
            graph.addEdge(new Edge(UUID.randomUUID(), from, to, output, input));
        });
        final String key = outputKey(from, output);
        if (cut[0]) Sfx.cut(key);
        else Sfx.connect(key);
    }

    public void deleteEdge(final UUID edgeId) {
        String key = null;
        for (final Edge e : graph.getEdges())
            if (e.id.equals(edgeId)) key = outputKey(e.sourceNodeId, e.sourceOutputIndex);
        edit(() -> graph.removeEdge(edgeId));
        Sfx.cut(key);
    }

    /** The resource an output carries, for its wire's sound; null when there is no such port. */
    private String outputKey(final UUID nodeId, final int output) {
        final Node n = graph.nodes.get(nodeId);
        return n == null || output < 0 || output >= n.outputs.size() ? null : Resources.key(n.outputs.get(output));
    }

    /**
     * A port dragged onto another card: an output wires into the first input there that takes it, an input takes the
     * first output there that fits. False when nothing on that card matches.
     */
    public boolean dropPortOnCard(final UUID fromNode, final boolean output, final int port, final UUID toNode) {
        final Node to = graph.nodes.get(toNode);
        if (com.gtnhplanner.power.Holders.is(to)) return dropPortOnHolder(fromNode, output, port, to);
        // A shared machine's recipes in turn: the first with a matching port takes the wire, as on the website.
        for (final UUID section : sectionsOf(toNode)) {
            if (!section.equals(fromNode) && dropPortOnRecipe(fromNode, output, port, section)) return true;
        }
        return false;
    }

    private boolean dropPortOnRecipe(final UUID fromNode, final boolean output, final int port, final UUID toNode) {
        final Node src = graph.nodes.get(fromNode), dst = graph.nodes.get(toNode);
        if (src == null || dst == null || src == dst) return false;
        // A wire that lands on an input as one of the other items its slot takes sets it to that item, as the website's
        // wires do: wired with an ore dictionary match (coal dust fed carbon dust), or as an item it only lists.
        if (output) {
            final int in = graph.findCompatibleInput(src, port, dst);
            if (in >= 0) {
                final String key = pickFor(dst, in, src.outputs.get(port));
                if (key != null && !wired(src.id, port, dst.id, in))
                    connectPicking(src, port, dst, new Pick(in, key, port));
                else connect(src.id, port, dst.id, in);
                return true;
            }
            final Pick pick = pickTaking(src.outputs.get(port), dst);
            if (pick == null) return false;
            connectPicking(src, port, dst, pick);
            return true;
        }
        final Port<?> want = src.inputs.get(port);
        for (int out = 0; out < dst.outputs.size(); out++) {
            if (!dst.outputs.get(out)
                .canConnect(want)) continue;
            final String key = pickFor(src, port, dst.outputs.get(out));
            if (key != null && !wired(dst.id, out, src.id, port))
                connectPicking(dst, out, src, new Pick(port, key, out));
            else connect(dst.id, out, src.id, port);
            return true;
        }
        final Pick pick = pickTaken(src, port, dst);
        if (pick == null) return false;
        connectPicking(dst, pick.other(), src, pick);
        return true;
    }

    /**
     * The key to set input {@code in} of {@code node} to for a wire from {@code from}: what it carries when the input's
     * slot lists it among its items and the input holds something else; null otherwise.
     */
    @Nullable
    private static String pickFor(final Node node, final int in, final Port<?> from) {
        if (in < 0 || in >= node.inputs.size()) return null;
        final Port<?> p = node.inputs.get(in);
        final String key = from.key();
        if (!p.hasChoices() || key.equals(p.key())) return null;
        return p.alternative(key) != null ? key : null;
    }

    /** Whether a wire already joins that output to that input. */
    private boolean wired(final UUID from, final int output, final UUID to, final int input) {
        for (final Edge e : graph.getEdges()) if (e.sourceNodeId.equals(from) && e.sourceOutputIndex == output
            && e.targetNodeId.equals(to)
            && e.targetInputIndex == input) return true;
        return false;
    }

    /**
     * An input set to one of its other items for a wire: {@code input} on its card, the item's key, and the port at the
     * other end of the wire ({@code other}, -1 when that is not known yet).
     */
    private record Pick(int input, String key, int other) {}

    /** The first input of {@code dst} that would take what {@code out} carries once set to another of its items. */
    @Nullable
    private static Pick pickTaking(final Port<?> out, final Node dst) {
        for (int i = 0; i < dst.inputs.size(); i++) {
            final Port<?> in = dst.inputs.get(i);
            if (!in.hasChoices()) continue;
            for (final Object alt : in.getAlternatives())
                if (out.canConnect(in.withAlternative(alt))) return new Pick(i, in.keyOf(alt), -1);
        }
        return null;
    }

    /** The first output of {@code dst} that input {@code input} of {@code src} would take once set to another item. */
    @Nullable
    private static Pick pickTaken(final Node src, final int input, final Node dst) {
        if (input < 0 || input >= src.inputs.size()) return null;
        final Port<?> in = src.inputs.get(input);
        if (!in.hasChoices()) return null;
        for (final Object alt : in.getAlternatives()) {
            final Port<?> as = in.withAlternative(alt);
            for (int out = 0; out < dst.outputs.size(); out++) if (dst.outputs.get(out)
                .canConnect(as)) return new Pick(input, in.keyOf(alt), out);
        }
        return null;
    }

    /** Sets {@code to}'s input to the picked item and wires {@code from}'s output into it, as one undo step. */
    private void connectPicking(final Node from, final int output, final Node to, final Pick pick) {
        edit(() -> {
            applyInputPick(to, pick.input(), pick.key());
            graph.addEdge(new Edge(UUID.randomUUID(), from.id, to.id, output, pick.input()));
        });
        Sfx.connect(pick.key());
    }

    /**
     * Sets an input to another of the items its recipe slot takes (an ore dictionary slot's members, GregTech's
     * alternatives), the wheel over it: wires that no longer fit it go, and a drawer feeding it alone follows it to
     * the new item (one feeding other inputs too lets go of it). A spin of the wheel is one undo step.
     */
    public void pickInput(final Node node, final int index, final String key) {
        live(() -> applyInputPick(node, index, key), null);
    }

    private void applyInputPick(final Node node, final int index, final String key) {
        if (!node.pickInput(index, key)) return;
        final Port<?> port = node.inputs.get(index);
        for (final Edge e : new ArrayList<>(graph.getEdges())) {
            if (!e.targetNodeId.equals(node.id) || e.targetInputIndex != index) continue;
            final Node src = graph.nodes.get(e.sourceNodeId);
            if (src == null || e.sourceOutputIndex < 0
                || e.sourceOutputIndex >= src.outputs.size()
                || !src.outputs.get(e.sourceOutputIndex)
                    .canConnect(port))
                graph.removeEdge(e.id);
        }
        final String now = Resources.key(port);
        for (final Drawer d : new ArrayList<>(graph.getDrawers())) {
            if (!d.getKind()
                .linksInputs() || !d.isLinked(node.id, index) || now.equals(d.getResourceKey())) continue;
            if (d.getLinks()
                .size() == 1) {
                d.setResourceKey(now);
                d.setLabel(port.getDisplayName());
            } else graph.unlinkDrawer(d.getId(), new Drawer.Link(node.id, index));
        }
    }

    /**
     * The keys of the other items an input could be set to (its slot's alternatives); empty for an output, or an input
     * that takes one thing only.
     */
    public java.util.Set<String> choiceKeys(final UUID nodeId, final boolean output, final int index) {
        final Node n = graph.nodes.get(nodeId);
        if (output || n == null || index < 0 || index >= n.inputs.size()) return java.util.Set.of();
        final Port<?> p = n.inputs.get(index);
        if (!p.hasChoices()) return java.util.Set.of();
        final java.util.Set<String> keys = new HashSet<>();
        for (final Object alt : p.getAlternatives()) keys.add(p.keyOf(alt));
        return keys;
    }

    /**
     * A port dropped on a custom rate card or a fluid tank: the card takes the port's resource and is wired to it,
     * supplying an input or taking an output.
     */
    private boolean dropPortOnHolder(final UUID fromNode, final boolean output, final int port, final Node card) {
        final Node src = graph.nodes.get(fromNode);
        if (src == null || src == card || com.gtnhplanner.power.Holders.is(src)) return false;
        final List<Port<?>> ports = output ? src.outputs : src.inputs;
        if (port < 0 || port >= ports.size()) return false;
        final String key = Resources.key(ports.get(port));
        if (key.isEmpty()) return false;
        return holdAndWire(card, key, !output, src.id, port);
    }

    /**
     * Sets a custom rate card or a fluid tank to a resource on a side and wires it to a port of another card
     * ({@code supply}: the card feeds that input; else it takes that output). One it does not hold yet first drops the
     * card's old wires. One undo step; false, changing nothing, when the card cannot take it (a tank given an item that
     * holds no fluid).
     */
    public boolean holdAndWire(final Node card, final String key, final boolean supply, final UUID other,
        final int otherPort) {
        if (holderPort(card, key, supply) < 0 && !com.gtnhplanner.power.Holders.canTake(card, key, supply))
            return false;
        edit(() -> {
            if (!holdOn(card, key, supply)) return;
            final int own = holderPort(card, key, supply);
            final UUID from = supply ? card.id : other, to = supply ? other : card.id;
            final int out = supply ? own : otherPort, in = supply ? otherPort : own;
            for (final Edge e : graph.getEdges()) if (e.sourceNodeId.equals(from) && e.sourceOutputIndex == out
                && e.targetNodeId.equals(to)
                && e.targetInputIndex == in) return;
            graph.addEdge(new Edge(UUID.randomUUID(), from, to, out, in));
        });
        Sfx.connect(key);
        return true;
    }

    /**
     * Sets a custom rate card or a fluid tank to a drawer's resource, on the side the drawer links (a source drawer's
     * resource is taken in, a product drawer's given out), and links them. One undo step; false when it cannot.
     */
    public boolean holdAndLink(final Node card, final Drawer drawer) {
        final boolean supply = !drawer.getKind()
            .linksInputs();
        final String key = drawer.getResourceKey();
        if (key == null || key.isEmpty()
            || holderPort(card, key, supply) < 0 && !com.gtnhplanner.power.Holders.canTake(card, key, supply))
            return false;
        edit(() -> {
            if (holdOn(card, key, supply))
                graph.linkDrawer(drawer.getId(), new Drawer.Link(card.id, holderPort(card, key, supply)));
        });
        Sfx.connect(key);
        return true;
    }

    /**
     * Makes a holder card hold {@code key} on that side, dropping its old wires when it changes what it holds; whether
     * it now has a port for it (a resource this game lacks makes none).
     */
    private boolean holdOn(final Node card, final String key, final boolean supply) {
        if (holderPort(card, key, supply) < 0) {
            unwire(card.id);
            if (!com.gtnhplanner.power.Holders.take(card, key, supply)) return false;
            card.refresh();
        }
        return holderPort(card, key, supply) >= 0;
    }

    /** The port holding {@code key} on a card's side ({@code supply}: its outputs), or -1. */
    private static int holderPort(final Node card, final String key, final boolean supply) {
        final List<Port<?>> ports = supply ? card.outputs : card.inputs;
        for (int i = 0; i < ports.size(); i++) if (key.equals(Resources.key(ports.get(i)))) return i;
        return -1;
    }

    /** Puts an empty fluid tank on the board, centred in view and selected: it takes the cell or fluid wired to it. */
    public Node addFluidTank() {
        return add(
            Node.power(com.gtnhplanner.power.FluidTank.ID, com.gtnhplanner.power.FluidTank.fresh(), 0, 0),
            true,
            false);
    }

    /** Every wire and drawer link on a card, gone. */
    private void unwire(final UUID nodeId) {
        for (final Edge e : new ArrayList<>(graph.getEdges()))
            if (e.sourceNodeId.equals(nodeId) || e.targetNodeId.equals(nodeId)) graph.removeEdge(e.id);
        for (final Drawer d : graph.getDrawers()) d.removeLinksTo(nodeId);
    }

    /**
     * Custom rate cards and fluid tanks holding something with nothing wired to them let go of it (the website's
     * releaseCustomRates).
     */
    private void releaseHolders() {
        for (final Node n : graph.nodes.values()) {
            if (!com.gtnhplanner.power.Holders.is(n) || !com.gtnhplanner.power.Holders.holding(n) || wired(n.id))
                continue;
            com.gtnhplanner.power.Holders.release(n);
            n.refresh();
        }
    }

    /** Whether anything is wired to a card: a wire either way, or a drawer. */
    private boolean wired(final UUID nodeId) {
        for (final Edge e : graph.getEdges())
            if (e.sourceNodeId.equals(nodeId) || e.targetNodeId.equals(nodeId)) return true;
        for (final Drawer d : graph.getDrawers()) for (final Drawer.Link link : d.getLinks()) if (link.nodeId()
            .equals(nodeId)) return true;
        return false;
    }

    /** A port dragged onto a drawer: linked when the drawer holds the same resource on the same side. */
    public boolean dropPortOnDrawer(final UUID fromNode, final boolean output, final int port, final Drawer drawer) {
        final Node node = graph.nodes.get(fromNode);
        if (node == null || drawer.getKind()
            .linksInputs() == output) return false;
        final String key = Resources.key((output ? node.outputs : node.inputs).get(port));
        // An input that takes the drawer's item as one of its others is set to it.
        final boolean pick = !key.equals(drawer.getResourceKey())
            && choiceKeys(node.id, output, port).contains(drawer.getResourceKey());
        if (!key.equals(drawer.getResourceKey()) && !pick) return false;
        final boolean linked = drawer.isLinked(node.id, port);
        final Double rate = portTarget(node, output, port);
        edit(() -> {
            if (pick) {
                // The drawer it had goes, rather than following it to the new item: this one takes its place.
                for (final Drawer other : graph.getDrawers()) if (other != drawer && other.getKind()
                    .linksInputs() && other.isLinked(node.id, port))
                    graph.unlinkDrawer(other.getId(), new Drawer.Link(node.id, port));
                applyInputPick(node, port, drawer.getResourceKey());
            }
            if (linked) graph.unlinkDrawer(drawer.getId(), new Drawer.Link(node.id, port));
            else {
                graph.linkDrawer(drawer.getId(), new Drawer.Link(node.id, port));
                // A rate set on the port goes to a drawer with none; the port's own goes (it is wired now).
                if (rate != null && drawer.effectiveRule() == Drawer.Rule.ANY
                    && drawer.getKind()
                        .hasRule())
                    drawer.setTarget(Drawer.Rule.EXACTLY, rate);
            }
        });
        if (linked) Sfx.cut(key);
        else Sfx.connect(key);
        return true;
    }

    /**
     * A port dragged onto empty board: a new drawer there, linked to it. An input makes a source (it sits to the left
     * of the drop point), an output a product (to the right).
     */
       /** A port on a recipe: its node, which side, and its index there. */
    public record PortRef(UUID node, boolean output, int index) {}

    /**
     * The port a port dropped on a card would be wired to, as {@link #dropPortOnCard} picks it: the first recipe on the
     * card with a port that takes it. Null when none does, and for a custom rate card (its own socket takes it).
     */
    @Nullable
    public PortRef dropTarget(final UUID fromNode, final boolean output, final int port, final UUID toNode) {
        final Node to = graph.nodes.get(toNode), src = graph.nodes.get(fromNode);
        if (to == null || src == null || com.gtnhplanner.power.Holders.is(to)) return null;
        for (final UUID section : sectionsOf(toNode)) {
            final Node dst = graph.nodes.get(section);
            if (dst == null || dst == src) continue;
            if (output) {
                final int in = graph.findCompatibleInput(src, port, dst);
                if (in >= 0) return new PortRef(section, false, in);
                if (port < 0 || port >= src.outputs.size()) return null;
                final Pick pick = pickTaking(src.outputs.get(port), dst);
                if (pick != null) return new PortRef(section, false, pick.input());
                continue;
            }
            if (port < 0 || port >= src.inputs.size()) return null;
            final Port<?> want = src.inputs.get(port);
            for (int out = 0; out < dst.outputs.size(); out++) if (dst.outputs.get(out)
                .canConnect(want)) return new PortRef(section, true, out);
            final Pick pick = pickTaken(src, port, dst);
            if (pick != null) return new PortRef(section, true, pick.other());
        }
        return null;
    }

    /** Whether a port already has a drawer on it: one per port, as on the website. */
    public boolean portHasDrawer(final UUID node, final boolean output, final int port) {
        for (final Drawer d : graph.getDrawers()) {
            if (d.getKind()
                .linksInputs() == output) continue;
            for (final Drawer.Link l : d.getLinks()) if (l.nodeId()
                .equals(node) && l.portIndex() == port) return true;
        }
        return false;
    }

    /**
     * A port dropped on empty board: a drawer there for it, wired to it (a product for an output, a source for an
     * input). Refused, false, when the port has a drawer already.
     */
    public boolean dropPortOnBoard(final UUID fromNode, final boolean output, final int port, final int worldX,
        final int worldY) {
        final Node node = graph.nodes.get(fromNode);
        if (node == null || portHasDrawer(fromNode, output, port)) return false;
        // Dropped nearly level with the port: make it level, so the wire runs straight instead of jogging.
        final CardModel card = models.get(fromNode);
        final int portY = card == null ? worldY : node.y + new CardLayout(card, graph()).anchorY(output, port);
        return dropPortAsDrawer(
            fromNode,
            output,
            port,
            output ? worldX : worldX - DrawerCard.W,
            (Math.abs(worldY - portY) <= 24 ? portY : worldY) - DrawerCard.ANCHOR_Y);
    }

    /**
     * A port picked up off its card and put down as a drawer, its top left at {@code x, y}: a source for an input, a
     * product for an output, linked to the port, taking the rate set on the port (whose own goes: it is wired now).
     */
    public boolean dropPortAsDrawer(final UUID fromNode, final boolean output, final int port, final int x,
        final int y) {
        final Drawer drawer = portDrawer(fromNode, output, port);
        return drawer != null && placePortDrawer(drawer, x, y);
    }

    /**
     * The drawer a port would make, linked to it but not on the plan yet (what the board carries while a port is picked
     * up off its card): a source for an input, a product for an output; null when the port has a drawer already.
     */
    @Nullable
    public Drawer portDrawer(final UUID fromNode, final boolean output, final int port) {
        final Node node = graph.nodes.get(fromNode);
        if (node == null || port < 0
            || port >= (output ? node.outputs : node.inputs).size()
            || portHasDrawer(fromNode, output, port)) return null;
        final Port<?> p = (output ? node.outputs : node.inputs).get(port);
        final Drawer drawer = new Drawer(output ? Drawer.Kind.PRODUCT : Drawer.Kind.SOURCE, Resources.key(p));
        drawer.setLabel(p.getDisplayName());
        drawer.addLink(new Drawer.Link(node.id, port));
        return drawer;
    }

    /** Puts a drawer from {@link #portDrawer} on the plan, its top left at {@code x, y}. */
    public boolean placePortDrawer(final Drawer drawer, final int x, final int y) {
        if (drawer.getLinks()
            .isEmpty()) return false;
        final Drawer.Link link = drawer.getLinks()
            .get(0);
        final boolean output = !drawer.getKind()
            .linksInputs();
        final Node node = graph.nodes.get(link.nodeId());
        if (node == null || portHasDrawer(node.id, output, link.portIndex())) return false;
        drawer.setX(x);
        drawer.setY(y);
        // A rate set on the port goes with it, to the drawer; the port's own goes (it is wired now).
        final Double rate = portTarget(node, output, link.portIndex());
        if (rate != null) drawer.setTarget(Drawer.Rule.EXACTLY, rate);
        edit(() -> graph.addDrawer(drawer));
        Sfx.connect(drawer.getResourceKey());
        return true;
    }

    /**
     * A drawer for a resource from the overview: a source left of the first card that uses it, or a product right of
     * the first card that makes it, linked to every port waiting for it.
     */
    /**
     * A rate typed on an overview line with no drawer: on its one open port (nothing wired, no drawer) it is that
     * port's rate, as typed on the card; on several, one drawer takes them all at that rate.
     */
    public void setLineRate(final String key, final String label, final boolean source, final double perSecond) {
        if (!(perSecond > 0)) return;
        Node only = null;
        int at = -1, open = 0;
        for (final Node n : graph.getNodes()) {
            final List<Port<?>> ports = source ? n.inputs : n.outputs;
            for (int i = 0; i < ports.size(); i++) {
                if (!canonicalKey(Resources.key(ports.get(i))).equals(key) || isWired(n, !source, i)) continue;
                open++;
                only = n;
                at = i;
            }
        }
        if (open == 1) setPortTarget(only, !source, at, perSecond);
        else if (open > 1) addDrawerFor(key, label, source, perSecond);
    }

    public void addDrawerFor(final String key, final String label, final boolean source) {
        addDrawerFor(key, label, source, 0);
    }

    /** As {@link #addDrawerFor(String, String, boolean)}, set to {@code perSecond} (none for 0). */
    public void addDrawerFor(final String key, final String label, final boolean source, final double perSecond) {
        int x = 40, y = 40;
        search: for (final CardModel card : models.values()) {
            final List<CardModel.PortView> ports = source ? card.inputs : card.outputs;
            for (int i = 0; i < ports.size(); i++) {
                if (!ports.get(i)
                    .key()
                    .equals(key)) continue;
                x = source ? card.node.x - DrawerCard.W - 60 : card.node.x + CardLayout.W + 60;
                // Its wire runs straight: the drawer's anchor level with the port's.
                y = card.node.y + new CardLayout(card, graph()).anchorY(!source, i) - DrawerCard.ANCHOR_Y;
                break search;
            }
        }
        // In line with, and below, any drawers already there.
        for (final Drawer d : graph.getDrawers()) if (Math.abs(d.getX() - x) <= 40) {
            x = d.getX();
            break;
        }
        addDrawer(source ? Drawer.Kind.SOURCE : Drawer.Kind.PRODUCT, key, label, x, y, true, perSecond);
    }

    /** A new, unlinked drawer for a resource at a world point (from an NEI drag): it is wired by hand. */
    public void addDrawer(final Drawer.Kind kind, final String resourceKey, final String label, final int x,
        final int y) {
        addDrawer(kind, resourceKey, label, x, y, false, 0);
    }

    /**
     * A new drawer for a resource at a world point, kept clear of everything; {@code takeWaiting}: linked to every port
     * of that resource with no wire yet (the overview's drawer for a resource), else unlinked.
     */
    private void addDrawer(final Drawer.Kind kind, final String resourceKey, final String label, final int x,
        final int y, final boolean takeWaiting, final double perSecond) {
        final Drawer drawer = new Drawer(kind, resourceKey);
        drawer.setLabel(label);
        drawer.setX(x);
        // Never under or over a card or another drawer: step down until it is clear.
        int clear = y;
        for (int tries = 0; tries < 100
            && NewCards.overlapsAnything(graph, x, clear, DrawerCard.W, DrawerCard.H, null); tries++) clear += 20;
        drawer.setY(clear);
        // A rate set as it is made: exactly for a source, at least for a product, as typing one on "Any" does.
        if (perSecond > 0)
            drawer.setTarget(kind == Drawer.Kind.SOURCE ? Drawer.Rule.EXACTLY : Drawer.Rule.AT_LEAST, perSecond);
        edit(() -> {
            graph.addDrawer(drawer);
            if (!takeWaiting) return;
            // Take the ports already waiting for this resource.
            final String want = canonicalKey(resourceKey);
            for (final Node n : graph.getNodes()) {
                final List<Port<?>> ports = kind.linksInputs() ? n.inputs : n.outputs;
                for (int i = 0; i < ports.size(); i++) {
                    if (!canonicalKey(Resources.key(ports.get(i))).equals(want) || isWired(n, !kind.linksInputs(), i))
                        continue;
                    graph.linkDrawer(drawer.getId(), new Drawer.Link(n.id, i));
                }
            }
        });
        Sfx.connect(resourceKey);
    }

    private boolean isWired(final Node node, final boolean output, final int port) {
        for (final Edge e : graph.getEdges()) {
            if (output && e.sourceNodeId.equals(node.id) && e.sourceOutputIndex == port) return true;
            if (!output && e.targetNodeId.equals(node.id) && e.targetInputIndex == port) return true;
        }
        return graph.drawerAt(node.id, port, !output) != null;
    }

    /** A drawer for a wire's resource beside it, taking the wire's surplus (product) or topping it up (source). */
    public void addDrawerOnEdge(final Edge edge, final Drawer.Kind kind, final int worldX, final int worldY) {
        final Node src = graph.nodes.get(edge.sourceNodeId), dst = graph.nodes.get(edge.targetNodeId);
        if (src == null || dst == null) return;
        final boolean source = kind == Drawer.Kind.SOURCE;
        final Port<?> p = source ? dst.inputs.get(edge.targetInputIndex) : src.outputs.get(edge.sourceOutputIndex);
        final Drawer drawer = new Drawer(kind, Resources.key(p));
        drawer.setLabel(p.getDisplayName());
        drawer.setX(worldX - DrawerCard.W / 2);
        drawer.setY(worldY + 12);
        edit(() -> {
            graph.addDrawer(drawer);
            graph.linkDrawer(
                drawer.getId(),
                source ? new Drawer.Link(dst.id, edge.targetInputIndex)
                    : new Drawer.Link(src.id, edge.sourceOutputIndex));
        });
        Sfx.connect(drawer.getResourceKey());
    }

    public void unlinkDrawer(final Drawer drawer, final Drawer.Link link) {
        edit(() -> graph.unlinkDrawer(drawer.getId(), link));
        Sfx.cut(drawer.getResourceKey());
    }

    public void deleteDrawer(final Drawer drawer) {
        edit(() -> graph.removeDrawer(drawer.getId()));
        Sfx.REMOVE.play();
    }

    /** Product, trash, product again; a source stays a source. */
    public void cycleDrawer(final Drawer drawer) {
        edit(
            () -> drawer.setKind(
                drawer.getKind()
                    .next()));
        Sfx.ADJUST.playStep(
            drawer.getKind()
                .ordinal(),
            0.9f,
            1.12f);
    }

    /** Rates being wheeled, per drawer (per second), shown at once and set once the wheel is still; null for none. */
    private Map<UUID, Double> wheeledRates;

    /**
     * A drawer rate from the wheel: shown and solved at once, one undo step once the wheel goes still ({@link #live}).
     */
    public void wheelDrawerRate(final Drawer drawer, final double perSecond) {
        if (wheeledRates == null) wheeledRates = new HashMap<>();
        final Double was = wheeledRates.get(drawer.getId());
        Sfx.TICK.play(perSecond >= (was == null ? drawer.getRate() : was) ? 1.12f : 0.9f);
        wheeledRates.put(drawer.getId(), perSecond);
        live(() -> applyDrawerRate(drawer, perSecond), perSecond > 0 ? Sfx.PIN : Sfx.UNPIN);
    }

    /** The rate a drawer is being wheeled to, per second; null when it is not. */
    public Double wheeledRate(final UUID drawerId) {
        return wheeledRates == null ? null : wheeledRates.get(drawerId);
    }

    public void setDrawerRule(final Drawer drawer, final Drawer.Rule rule) {
        edit(() -> drawer.setRule(rule));
        Sfx.ADJUST.playStep(rule.ordinal(), 0.9f, 1.12f);
    }

    /**
     * Sets the drawer's rate (per second). Typing a rate on "Any" picks the rule the player almost always means:
     * exactly for a source, at least for a product. Zero clears the rule.
     */
    public void setDrawerRate(final Drawer drawer, final double perSecond) {
        edit(() -> applyDrawerRate(drawer, perSecond));
        // A rate is a target the plan solves for: pinned, or let go.
        (perSecond > 0 ? Sfx.PIN : Sfx.UNPIN).play();
    }

    private static void applyDrawerRate(final Drawer drawer, final double perSecond) {
        if (!(perSecond > 0)) {
            drawer.setTarget(Drawer.Rule.ANY, 0);
            return;
        }
        Drawer.Rule rule = drawer.getRule();
        if (rule == Drawer.Rule.ANY)
            rule = drawer.getKind() == Drawer.Kind.SOURCE ? Drawer.Rule.EXACTLY : Drawer.Rule.AT_LEAST;
        drawer.setTarget(rule, perSecond);
    }

    // endregion

    private boolean wireToOrigin(final Node added, final Node origin, final NodeLookupContext lookup) {
        final List<Port<?>> originPorts = lookup.output() ? origin.outputs : origin.inputs;
        final int idx = lookup.portIndex();
        if (idx < 0 || idx >= originPorts.size()) return false;
        if (lookup.output()) {
            final int in = graph.findCompatibleInput(origin, idx, added);
            if (in >= 0) {
                final String key = pickFor(added, in, originPorts.get(idx));
                if (key != null) applyInputPick(added, in, key);
                graph.addEdge(new Edge(UUID.randomUUID(), origin.id, added.id, idx, in));
                return true;
            }
            // Looked up from what it uses: the new card's input is set to it when the slot takes it among others.
            final Pick pick = pickTaking(originPorts.get(idx), added);
            if (pick == null) return false;
            applyInputPick(added, pick.input(), pick.key());
            graph.addEdge(new Edge(UUID.randomUUID(), origin.id, added.id, idx, pick.input()));
            return true;
        }
        final Port<?> want = originPorts.get(idx);
        for (int out = 0; out < added.outputs.size(); out++) {
            if (!added.outputs.get(out)
                .canConnect(want)) continue;
            final String key = pickFor(origin, idx, added.outputs.get(out));
            if (key != null) applyInputPick(origin, idx, key);
            graph.addEdge(new Edge(UUID.randomUUID(), added.id, origin.id, out, idx));
            return true;
        }
        final Pick pick = pickTaken(origin, idx, added);
        if (pick == null) return false;
        applyInputPick(origin, idx, pick.key());
        graph.addEdge(new Edge(UUID.randomUUID(), added.id, origin.id, pick.other(), idx));
        return true;
    }

    // region Copy and paste

    /** A copied card: its recipe and settings, and where it sat from the copy's top-left. */
    private record ClipNode(codechicken.nei.recipe.Recipe.RecipeId recipe, Map<String, Object> settings,
        String machineName, boolean fixed, int dx, int dy, String powerSource, Map<String, String> powerSettings,
        Map<Integer, String> picks) {}

    /** A copied drawer, and the copied cards' ports it was linked to, as {card index in the copy, port}. */
    private record ClipDrawer(Drawer.Kind kind, String key, String label, Drawer.Rule rule, double rate, int dx, int dy,
        List<int[]> links) {}

    /** A wire between two copied cards, by their index in the copy. */
    private record ClipEdge(int from, int output, int to, int input) {}

    /** A copied sticky note, and where it sat from the copy's top-left. */
    private record ClipNote(com.gtnhplanner.data.flowchart.Note note, int dx, int dy) {}

    private record Clip(List<ClipNode> nodes, List<ClipDrawer> drawers, List<ClipEdge> edges, List<ClipNote> notes) {}

    /** The last copy, shared by every plan, so a group copied from one plan pastes into another. */
    private static Clip clipboard;

    /** Copies the selected cards, drawers and notes with the wires among them; false when nothing is selected. */
    public boolean copySelection() {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE;
        final List<Node> nodes = new ArrayList<>();
        final List<Drawer> drawers = new ArrayList<>();
        final List<com.gtnhplanner.data.flowchart.Note> notes = new ArrayList<>();
        for (final UUID id : selection) {
            final Node n = graph.nodes.get(id);
            if (n != null) {
                nodes.add(n);
                x0 = Math.min(x0, n.x);
                y0 = Math.min(y0, n.y);
            }
            final Drawer d = graph.getDrawer(id);
            if (d != null) {
                drawers.add(d);
                x0 = Math.min(x0, d.getX());
                y0 = Math.min(y0, d.getY());
            }
            final com.gtnhplanner.data.flowchart.Note note = graph.notes.get(id);
            if (note != null) {
                notes.add(note);
                x0 = Math.min(x0, note.getX());
                y0 = Math.min(y0, note.getY());
            }
        }
        if (nodes.isEmpty() && drawers.isEmpty() && notes.isEmpty()) return false;
        final List<ClipNote> clipNotes = new ArrayList<>();
        for (final com.gtnhplanner.data.flowchart.Note note : notes)
            clipNotes.add(new ClipNote(note.copy(), note.getX() - x0, note.getY() - y0));
        final Map<UUID, Integer> index = new HashMap<>();
        final List<ClipNode> clipNodes = new ArrayList<>();
        for (final Node n : nodes) {
            index.put(n.id, clipNodes.size());
            clipNodes.add(
                new ClipNode(
                    n.recipeId,
                    new HashMap<>(n.machineConfig.settings),
                    n.machineName,
                    n.isMachineCountFixed(),
                    n.x - x0,
                    n.y - y0,
                    n.powerSource,
                    new LinkedHashMap<>(n.powerSettings),
                    new HashMap<>(n.inputPicks)));
        }
        final List<ClipEdge> edges = new ArrayList<>();
        for (final Edge e : graph.getEdges()) {
            final Integer from = index.get(e.sourceNodeId), to = index.get(e.targetNodeId);
            if (from != null && to != null) edges.add(new ClipEdge(from, e.sourceOutputIndex, to, e.targetInputIndex));
        }
        final List<ClipDrawer> clipDrawers = new ArrayList<>();
        for (final Drawer d : drawers) {
            final List<int[]> links = new ArrayList<>();
            for (final Drawer.Link l : d.getLinks()) {
                final Integer at = index.get(l.nodeId());
                if (at != null) links.add(new int[] { at, l.portIndex() });
            }
            clipDrawers.add(
                new ClipDrawer(
                    d.getKind(),
                    d.getResourceKey(),
                    d.getLabel(),
                    d.getRule(),
                    d.getRate(),
                    d.getX() - x0,
                    d.getY() - y0,
                    links));
        }
        clipboard = new Clip(clipNodes, clipDrawers, edges, clipNotes);
        return true;
    }

    /**
     * Pastes the last copy with its top-left at a board point, as one undoable edit, and selects and returns what it
     * made. A card whose recipe NEI no longer knows is left out, with its wires.
     */
    public List<UUID> paste(final int worldX, final int worldY) {
        return pasteAt(List.of(new int[] { worldX, worldY }));
    }

    /**
     * Pastes the last copy once at each board point, all as one undoable edit (the dev harness's stress plans tile a
     * plan this way), and selects and returns what it made.
     */
    public List<UUID> pasteAt(final List<int[]> origins) {
        final Clip clip = clipboard;
        if (clip == null || origins.isEmpty()) return List.of();
        final List<Runnable> adds = new ArrayList<>();
        selection.clear();
        for (final int[] origin : origins) adds.add(pasteOnce(clip, origin[0], origin[1]));
        edit(() -> { for (final Runnable add : adds) add.run(); });
        (selection.size() > 1 ? Sfx.SWEEP : Sfx.PLACE).play();
        return List.copyOf(selection);
    }

    /** One copy of the clip at a board point: its cards made now, the returned step adds everything to the graph. */
    private Runnable pasteOnce(final Clip clip, final int worldX, final int worldY) {
        final List<Node> made = new ArrayList<>();
        for (final ClipNode c : clip.nodes()) {
            if (c.powerSource() != null) {
                final Node n = Node
                    .power(c.powerSource(), c.powerSettings(), snap(worldX + c.dx()), snap(worldY + c.dy()));
                n.setMachineCountFixed(c.fixed());
                made.add(n);
                continue;
            }
            final RecipeHandlerRef ref = com.gtnhplanner.nei.RecipeRefs.of(c.recipe());
            if (ref == null) {
                made.add(null);
                continue;
            }
            final Node n = new Node(ref.handler, ref.recipeIndex, snap(worldX + c.dx()), snap(worldY + c.dy()));
            n.machineConfig.settings.clear();
            n.machineConfig.settings.putAll(c.settings());
            n.machineName = c.machineName();
            n.setMachineCountFixed(c.fixed());
            n.inputPicks.putAll(c.picks());
            n.applyPicks();
            made.add(n);
        }
        final List<Drawer> madeDrawers = new ArrayList<>();
        final List<UUID> madeNotes = new ArrayList<>();
        return () -> {
            for (final Node n : made) if (n != null) graph.addNode(n);
            for (final ClipEdge e : clip.edges()) {
                final Node from = made.get(e.from()), to = made.get(e.to());
                if (from != null && to != null)
                    graph.addEdge(new Edge(UUID.randomUUID(), from.id, to.id, e.output(), e.input()));
            }
            for (final ClipDrawer c : clip.drawers()) {
                final Drawer d = new Drawer(c.kind(), c.key());
                d.setLabel(c.label());
                d.setX(snap(worldX + c.dx()));
                d.setY(snap(worldY + c.dy()));
                d.setTarget(c.rule(), c.rate());
                graph.addDrawer(d);
                for (final int[] l : c.links()) {
                    final Node n = made.get(l[0]);
                    if (n != null) graph.linkDrawer(d.getId(), new Drawer.Link(n.id, l[1]));
                }
                madeDrawers.add(d);
            }
            for (final ClipNote c : clip.notes()) {
                final com.gtnhplanner.data.flowchart.Note note = c.note()
                    .copy();
                note.setX(snap(worldX + c.dx()));
                note.setY(snap(worldY + c.dy()));
                graph.notes.put(note.getId(), note);
                madeNotes.add(note.getId());
            }
            for (final Node n : made) if (n != null) selection.add(n.id);
            for (final Drawer d : madeDrawers) selection.add(d.getId());
            selection.addAll(madeNotes);
        };
    }

    // endregion

    // region Sticky notes

    /** Adds a sticky note with its top-left at a board point, as one undoable step, and selects it. */
    public com.gtnhplanner.data.flowchart.Note addNote(final int worldX, final int worldY) {
        final com.gtnhplanner.data.flowchart.Note note = new com.gtnhplanner.data.flowchart.Note();
        note.setX(snap(worldX));
        note.setY(snap(worldY));
        editLayout(() -> graph.notes.put(note.getId(), note));
        selection.clear();
        selection.add(note.getId());
        Sfx.NOTE_STICK.play();
        return note;
    }

    /** Deletes a sticky note, as one undoable step. */
    public void deleteNote(final UUID id) {
        selection.remove(id);
        if (!graph.notes.containsKey(id)) return;
        editLayout(() -> graph.notes.remove(id));
        Sfx.NOTE_CRUMPLE.play();
    }

    // endregion

    // region Selection

    /**
     * Where cards and drawers land when dragged: a 10 px grid, fine enough to feel smooth, coarse enough to line up.
     */
    public static int snap(final float v) {
        return Math.round(v / 10f) * 10;
    }

    private final Set<UUID> selection = new java.util.LinkedHashSet<>();

    /** Selects a card or drawer; {@code add} keeps the others (Shift), and toggles this one. */
    public void select(final UUID id, final boolean add) {
        Sfx.CLICK.play(0.6f, 1.15f);
        if (!add) {
            selection.clear();
            selection.add(id);
        } else if (!selection.remove(id)) selection.add(id);
    }

    public boolean isSelected(final UUID id) {
        return selection.contains(id);
    }

    /**
     * Whether a card, drawer or note draws its selection outline: only when it is one of several selected, where the
     * outline says what a move or a delete takes. One thing alone is just the thing clicked.
     */
    public boolean showsSelected(final UUID id) {
        // A picture of the plan shows no selection.
        return selection.size() > 1 && selection.contains(id) && !com.gtnhplanner.ui.canvas.PlanPicture.drawing();
    }

    public Set<UUID> selection() {
        return java.util.Collections.unmodifiableSet(selection);
    }

    public boolean hasSelection() {
        return !selection.isEmpty();
    }

    public void clearSelection() {
        selection.clear();
    }

    /** Every card, drawer and note on the board. */
    public void selectAll() {
        selection.clear();
        selection.addAll(graph.nodes.keySet());
        selection.addAll(graph.drawers.keySet());
        selection.addAll(graph.notes.keySet());
        Sfx.CLICK.play(0.6f, 1.15f);
    }

    /** Deletes every selected card, drawer and note as one undoable step. */
    public void deleteSelected() {
        if (selection.isEmpty()) return;
        // A shared machine goes whole: every recipe on its card.
        final List<UUID> ids = new ArrayList<>();
        for (final UUID id : selection) for (final UUID s : sectionsOf(id)) if (!ids.contains(s)) ids.add(s);
        selection.clear();
        final boolean notesOnly = ids.stream()
            .allMatch(graph.notes::containsKey);
        edit(() -> {
            for (final UUID id : ids) {
                if (graph.nodes.containsKey(id)) graph.removeNode(id);
                else if (graph.drawers.containsKey(id)) graph.removeDrawer(id);
                else graph.notes.remove(id);
            }
        });
        (notesOnly ? Sfx.NOTE_CRUMPLE : Sfx.REMOVE).play();
    }

    // endregion

    // region Undo, plan slots, board keys

    public boolean canUndo() {
        return PlanAPI.undoHistory(graph)
            .canUndo();
    }

    public boolean canRedo() {
        return PlanAPI.undoHistory(graph)
            .canRedo();
    }

    public void undo() {
        settleLive(true);
        if (!canUndo()) {
            Sfx.DENY.play();
            return;
        }
        adopt(
            PlanAPI.undoHistory(graph)
                .undo(graph));
        Sfx.UNDO.play();
    }

    public void redo() {
        settleLive(true);
        if (!canRedo()) {
            Sfx.DENY.play();
            return;
        }
        adopt(
            PlanAPI.undoHistory(graph)
                .redo(graph));
        Sfx.REDO.play();
    }

    /**
     * Puts a graph restored by undo or redo into the active slot. The view is not part of an edit, so it carries over;
     * the slot must take the graph or the next save writes the old one back.
     */
    private void adopt(final Graph restored) {
        restored.setZoom(graph.getZoom());
        restored.setPanX(graph.getPanX());
        restored.setPanY(graph.getPanY());
        final Plan plan = Plan.getInstance();
        plan.getGraphs()
            .set(plan.getActiveIndex(), restored);
        graph = restored;
        seenVersion = Long.MIN_VALUE;
        PlanAPI.save();
    }

    /**
     * The board's own panel, set by the screen: every popup a card, drawer or note opens goes over it, as it is open
     * for as long as the board is. Never the widget's own panel: the board builds its cards afresh whenever one changes
     * shape, and a sheet or menu still open on one it let go of would ask a widget that is on no panel, which
     * ModularUI throws for (issue 492: a boiler's fuel picked from its open gear sheet).
     */
    private com.cleanroommc.modularui.screen.ModularPanel panel;

    public void setPanel(final com.cleanroommc.modularui.screen.ModularPanel panel) {
        this.panel = panel;
    }

    /** The board's own panel, for popups to open over; null until the screen has set it. */
    public com.cleanroommc.modularui.screen.ModularPanel panel() {
        return panel;
    }

    /** Opens the library in place of the board: set by the screen, used by the plan tabs' + menu. */
    private Runnable libraryOpener = () -> {};

    private Runnable powerPickerOpener = () -> {};

    public void setPowerPickerOpener(final Runnable opener) {
        powerPickerOpener = opener;
    }

    /** Opens the non-recipe machines over the board. */
    public void openPowerPicker() {
        powerPickerOpener.run();
    }

    public void setLibraryOpener(final Runnable opener) {
        libraryOpener = opener;
    }

    public void openLibrary() {
        libraryOpener.run();
    }

    /** Opens the library on My plans: every plan kept, open in a tab or not. */
    private Runnable myPlansOpener = () -> {};

    public void setMyPlansOpener(final Runnable opener) {
        myPlansOpener = opener;
    }

    public void openMyPlans() {
        myPlansOpener.run();
    }

    /** Opens the library on what makes a resource ({@code kind:id}, Factory Flow's key, and its name). */
    private java.util.function.BiConsumer<String, String> librarySearch = (key, label) -> {};

    public void setLibrarySearch(final java.util.function.BiConsumer<String, String> search) {
        librarySearch = search;
    }

    /** The library, showing the public setups that make a resource on the overview. */
    public void showSetupsMaking(final TotalLine line) {
        final String key = line.isFluid()
            ? "fluid:" + com.gtnhplanner.importer.game.FactoryFlowImport.idOf(line.fluid())
            : line.item() == null ? null : "item:" + com.gtnhplanner.importer.game.FactoryFlowImport.idOf(line.item());
        if (key != null) librarySearch.accept(key, line.label());
    }

    public List<Graph> slots() {
        return Plan.getInstance()
            .getGraphs();
    }

    public int activeSlot() {
        return Plan.getInstance()
            .getActiveIndex();
    }

    public void switchSlot(final int index) {
        final Plan plan = Plan.getInstance();
        if (index < 0 || index >= plan.getGraphs()
            .size() || index == plan.getActiveIndex()) return;
        plan.setActiveIndex(index);
        follow();
        PlanAPI.save();
        Sfx.PAGE.play();
    }

    /** Takes up the active plan when it changed (a tab, or the plan button on NEI's recipe page picking another). */
    private void follow() {
        final Graph active = Plan.getActiveGraph();
        if (active != graph) {
            graph = active;
            seenVersion = Long.MIN_VALUE;
        }
    }

    public void addSlot() {
        final Plan plan = Plan.getInstance();
        final int size = plan.getGraphs()
            .size();
        plan.getGraphs()
            .add(new Graph("Plan " + (size + 1)));
        plan.setActiveIndex(size);
        PlanAPI.save();
        Sfx.PAGE.play();
    }

    public void renameSlot(final int index, final String name) {
        if (index < 0 || index >= slots().size() || name == null || name.isBlank()) return;
        slots().get(index)
            .setName(name.trim());
        PlanAPI.save();
        Sfx.ADJUST.play();
    }

    /** Removes a plan slot; the last one stays. */
    public void deleteSlot(final int index) {
        final Plan plan = Plan.getInstance();
        if (index < 0 || index >= plan.getGraphs()
            .size()) return;
        final String name = plan.getGraphs()
            .get(index)
            .getName();
        plan.removeSlot(index);
        follow();
        PlanAPI.save();
        Sfx.NOTE_CRUMPLE.play();
        flash(Severity.INFO, "Deleted '" + name + "'");
    }

    /** Closes a plan's tab; the plan stays in the library's My plans. */
    public void closeSlot(final int index) {
        final Plan plan = Plan.getInstance();
        if (index < 0 || index >= plan.getGraphs()
            .size()) return;
        plan.closeSlot(index);
        follow();
        PlanAPI.save();
        Sfx.CLOSE.play();
    }

    /** Closes every tab but this one, which becomes the open plan; the others stay in My plans. */
    public void closeOtherSlots(final int index) {
        final Plan plan = Plan.getInstance();
        if (index < 0 || index >= plan.getGraphs()
            .size()) return;
        if (index != plan.getActiveIndex()) switchSlot(index);
        for (final int other : plan.openSlots()) if (other != index) plan.closeSlot(other);
        follow();
        PlanAPI.save();
        Sfx.CLOSE.play();
    }

    /** Asks before deleting a plan for good: it cannot be undone. */
    public void confirmDelete(final int index, final com.cleanroommc.modularui.screen.ModularPanel panel, final int x,
        final int y) {
        if (index < 0 || index >= slots().size()) return;
        final String name = slots().get(index)
            .getName();
        final List<com.gtnhplanner.ui.popup.PickList.Entry> rows = List.of(
            new com.gtnhplanner.ui.popup.PickList.Entry(
                null,
                "Delete it",
                "",
                Hyb.RED_INK,
                false,
                () -> deleteSlot(index)),
            com.gtnhplanner.ui.popup.PickList.Entry.of("Keep it", () -> {}));
        com.gtnhplanner.ui.popup.Popup.open(
            panel,
            com.gtnhplanner.ui.popup.PickList
                .popup("gtnhplanner_delete", "Delete '" + name + "' for good?", rows, false, 150),
            x,
            y);
    }

    /** How the board shows power: EU/t, or amps at each card's tier. */
    public enum PowerKey {
        EU,
        AMPS
    }

    private PowerKey powerKey = PlannerSettings.powerAsAmps() ? PowerKey.AMPS : PowerKey.EU;
    private boolean peakPower = PlannerSettings.peakPower();

    public PowerKey powerKey() {
        return powerKey;
    }

    public void togglePowerKey() {
        powerKey = powerKey == PowerKey.EU ? PowerKey.AMPS : PowerKey.EU;
        PlannerSettings.setPowerAsAmps(powerKey == PowerKey.AMPS);
    }

    /** Peak counts every machine running at once (whole machines); average uses the solved fraction. */
    public boolean peakPower() {
        return peakPower;
    }

    /** Puts a card on the pin list shown while playing, or takes it off: one undo step, saved. */
    public void togglePinList(final UUID machine) {
        final boolean on = !graph.pinList.contains(machine);
        (on ? com.gtnhplanner.ui.sound.Sfx.TOGGLE_ON : com.gtnhplanner.ui.sound.Sfx.TOGGLE_OFF).play();
        editLayout(() -> {
            if (on) graph.pinList.add(machine);
            else graph.pinList.remove(machine);
        });
        if (graph.pinList.contains(machine)) PlannerSettings.setPinList(true);
    }

    public void togglePeakPower() {
        peakPower = !peakPower;
        PlannerSettings.setPeakPower(peakPower);
        // The totals rail sums power with it.
        seenVersion = Long.MIN_VALUE;
    }

    /** A card's power as the board's switches say: average or peak. */
    public double power(final CardModel card) {
        // A crop: its harvesters draw as whole machines (a farm bills its full draw), however few crops it holds.
        if (com.gtnhplanner.machines.game.CropCards.isCrop(card.node))
            return com.gtnhplanner.machines.game.CropCards.drawEut(card.node, card.machines);
        return card.euPerTick * (peakPower ? Math.ceil(card.machines - 1e-9) : card.machines);
    }

    // endregion

    // region Shared machines (Factory Flow's: one card, several recipes time-sharing one machine)

    /** Each section's shared machine, as of the last tick. */
    private Map<UUID, com.gtnhplanner.data.flowchart.MachineGroup> sharedBySection = Map.of();

    /** The settings that belong to the machine rather than the recipe: a shared card keeps them the same throughout. */
    private static final Set<String> RECIPE_SETTINGS = Set.of("duration_ticks", "recipe_heat", "machines");

    /** The shared machine a recipe is a section of, or null when its card is its own. */
    public com.gtnhplanner.data.flowchart.MachineGroup sharedOf(final UUID nodeId) {
        return sharedBySection.get(nodeId);
    }

    /** The recipes on the card a node is on, top first: its shared machine's sections, or just itself. */
    public List<UUID> sectionsOf(final UUID nodeId) {
        final com.gtnhplanner.data.flowchart.MachineGroup g = sharedBySection.get(nodeId);
        return g == null ? List.of(nodeId) : List.copyOf(g.getSections());
    }

    /** The node whose card a node is drawn on: the first section of its shared machine, or itself. */
    public UUID hostOf(final UUID nodeId) {
        final com.gtnhplanner.data.flowchart.MachineGroup g = sharedBySection.get(nodeId);
        return g == null ? nodeId
            : g.getSections()
                .get(0);
    }

    /** The cards a set of ids covers, each once, by host: what a selection of cards is. */
    public List<UUID> cardsIn(final java.util.Collection<UUID> ids) {
        final List<UUID> out = new ArrayList<>();
        for (final UUID id : ids) {
            if (!graph.nodes.containsKey(id)) continue;
            final UUID host = hostOf(id);
            if (!out.contains(host)) out.add(host);
        }
        return out;
    }

    /**
     * Rebuilds the section index, and keeps every shared machine in shape: a recipe that left the plan leaves its
     * machine, a machine left with one recipe is an ordinary card again, and every section sits where its card does
     * (wires find its ports from there).
     */
    private void indexSharedMachines() {
        final Map<UUID, com.gtnhplanner.data.flowchart.MachineGroup> index = new HashMap<>();
        for (final com.gtnhplanner.data.flowchart.Group group : new ArrayList<>(graph.getGroups())) {
            if (!(group instanceof final com.gtnhplanner.data.flowchart.MachineGroup g) || g.getSections()
                .isEmpty()) continue;
            for (final UUID id : new ArrayList<>(g.getSections())) if (!graph.nodes.containsKey(id)) g.removeSection(id);
            if (!g.isShared()) {
                graph.removeGroup(g.getId());
                continue;
            }
            final Node host = graph.nodes.get(
                g.getSections()
                    .get(0));
            for (final UUID id : g.getSections()) {
                final Node n = graph.nodes.get(id);
                n.x = host.x;
                n.y = host.y;
                index.put(id, g);
            }
        }
        sharedBySection = index;
    }

    /** The machines (by NEI catalyst) that run every one of these recipes, in the first one's order. */
    public List<ItemStack> commonMachines(final List<UUID> nodeIds) {
        List<ItemStack> common = null;
        for (final UUID id : nodeIds) {
            final CardModel m = models.get(id);
            if (m == null) return List.of();
            if (common == null) common = new ArrayList<>(m.catalysts);
            else common.removeIf(
                c -> m.catalysts.stream()
                    .noneMatch(o -> ItemStack.areItemStacksEqual(o, c)));
        }
        return common == null ? List.of() : common;
    }

    /** Every recipe on these cards, card by card, each card's sections in order. */
    private List<UUID> allSections(final List<UUID> cards) {
        final List<UUID> out = new ArrayList<>();
        for (final UUID card : cards) for (final UUID s : sectionsOf(card)) if (!out.contains(s)) out.add(s);
        return out;
    }

    /** Whether these ids are two or more recipe cards that one machine can run all of. */
    public boolean canCombine(final java.util.Collection<UUID> ids) {
        for (final UUID id : ids) if (!graph.nodes.containsKey(id)) return false;
        final List<UUID> cards = cardsIn(ids);
        return cards.size() >= 2 && !commonMachines(allSections(cards)).isEmpty();
    }

    /**
     * Puts these cards' recipes on one machine, Factory Flow's "Combine N into one machine". The top-left card is the
     * host: it stays where it is and keeps its machine and settings, which every recipe then takes; the other cards'
     * recipes become its sections, their wires with them. A pinned count on the host becomes the machine's. One undo
     * step. Returns the host, or null when they cannot share a machine.
     */
    public UUID combine(final java.util.Collection<UUID> ids) {
        if (!canCombine(ids)) {
            Sfx.DENY.play();
            return null;
        }
        final List<UUID> cards = cardsIn(ids);
        cards.sort(
            java.util.Comparator.comparingInt((UUID id) -> graph.nodes.get(id).y)
                .thenComparingInt(id -> graph.nodes.get(id).x));
        final UUID hostId = cards.get(0);
        final List<UUID> sections = allSections(cards);
        final List<ItemStack> common = commonMachines(sections);
        edit(() -> {
            final Node host = graph.nodes.get(hostId);
            com.gtnhplanner.data.flowchart.MachineGroup g = sharedOf(hostId);
            final int capacity = g != null ? g.getMachineCapacity()
                : host.isMachineCountFixed() ? host.machineConfig.getMachineCount() : 0;
            final boolean pinned = g != null ? g.isPinned() : host.isMachineCountFixed();
            for (final UUID card : cards) {
                final com.gtnhplanner.data.flowchart.MachineGroup old = sharedOf(card);
                if (old != null) graph.removeGroup(old.getId());
            }
            g = new com.gtnhplanner.data.flowchart.MachineGroup();
            g.setHeader("Shared machine");
            for (final UUID s : sections) g.addSection(s);
            g.setMachineCapacity(capacity);
            g.setPinned(pinned && capacity > 0);
            graph.groups.put(g.getId(), g);
            // The host's machine when every recipe runs on it, else the first that runs them all.
            final CardModel hostModel = models.get(hostId);
            final boolean hostCommon = hostModel != null && hostModel.machineStack != null
                && common.stream()
                    .anyMatch(c -> ItemStack.areItemStacksEqual(c, hostModel.machineStack));
            if (!hostCommon) host.machineName = CardDefaults.itemKey(common.get(0));
            for (final UUID s : sections) {
                final Node n = graph.nodes.get(s);
                n.setMachineCountFixed(false);
                n.x = host.x;
                n.y = host.y;
            }
            copyMachineSettings(host, sections);
        });
        selection.clear();
        selection.add(hostId);
        Sfx.MERGE.play();
        return hostId;
    }

    /** Gives every section the host's machine and its machine settings; each keeps its own recipe's. */
    private void copyMachineSettings(final Node host, final List<UUID> sections) {
        for (final UUID s : sections) {
            final Node n = graph.nodes.get(s);
            if (n == null || n == host) continue;
            n.machineName = host.machineName;
            for (final Map.Entry<String, Object> e : host.machineConfig.settings.entrySet()) {
                if (!RECIPE_SETTINGS.contains(e.getKey())) n.machineConfig.settings.put(e.getKey(), e.getValue());
            }
        }
    }

    /** An edit to a card's machine (one undo step); a shared machine's other recipes take it too. */
    public void editMachine(final Node node, final Runnable change) {
        edit(() -> {
            change.run();
            syncShared(node);
        });
    }

    /** After an edit to a card's machine, the same on every other recipe of its shared machine. */
    private void syncShared(final Node node) {
        final com.gtnhplanner.data.flowchart.MachineGroup g = sharedOf(node.id);
        if (g != null) copyMachineSettings(node, g.getSections());
    }

    /** Takes a recipe off its shared machine: the recipe and its wires go, as Factory Flow's X does. */
    public void removeSection(final UUID nodeId) {
        final com.gtnhplanner.data.flowchart.MachineGroup g = sharedOf(nodeId);
        if (g == null) return;
        edit(() -> {
            g.removeSection(nodeId);
            graph.removeNode(nodeId);
            if (!g.isShared()) graph.removeGroup(g.getId());
        });
        Sfx.REMOVE.play();
    }

    /** Moves a recipe up (-1) or down (+1) on its shared card; the first one is the host. */
    public void moveSection(final UUID nodeId, final int step) {
        final com.gtnhplanner.data.flowchart.MachineGroup g = sharedOf(nodeId);
        if (g == null) return;
        final List<UUID> sections = g.getSections();
        final int i = sections.indexOf(nodeId), j = i + step;
        if (i < 0 || j < 0 || j >= sections.size()) return;
        editLayout(() -> java.util.Collections.swap(sections, i, j));
        Sfx.TICK.play(step < 0 ? 1.12f : 0.9f);
    }

    /** Pins a shared machine's count (zero unpins): its recipes' machines then add up to exactly that. */
    public void pinShared(final com.gtnhplanner.data.flowchart.MachineGroup g, final double count) {
        (count > 0 ? Sfx.PIN : Sfx.UNPIN).play();
        edit(() -> applyPinShared(g, count));
    }

    private static void applyPinShared(final com.gtnhplanner.data.flowchart.MachineGroup g, final double count) {
        g.setPinned(count > 0);
        g.setMachineCapacity(count > 0 ? Math.max(1, (int) Math.round(count)) : 0);
    }

    // endregion

    // region Card edits (each one undoable, saved, re-solved)

    /** Puts a non-recipe machine (a generator) on the board at these settings, centred in view and selected. */
    public Node addPower(final String sourceId, final Map<String, String> settings) {
        return add(Node.power(sourceId, settings, 0, 0), true, false);
    }

    /**
     * Puts an empty custom rate card on the board, centred in view and selected: at the dial last set on one, holding
     * nothing until something is wired to it.
     */
    public Node addCustomRate() {
        final String id = com.gtnhplanner.power.CustomRate.ID;
        final Node node = Node.power(
            id,
            com.gtnhplanner.power.CustomRate.fresh(com.gtnhplanner.ui.card.SettingMemory.powerSettings(id, Map.of())),
            0,
            0);
        // Its dial is its rate: one of it, pinned, until the player unpins it to let the plan scale it.
        node.machineConfig.setMachineCount(1);
        node.setMachineCountFixed(true);
        return add(node, true, false);
    }

    /**
     * Sets one of a power card's settings and rebuilds its ports. A wire or drawer on a port follows its resource to
     * wherever it now is on the card, and goes when the card no longer has it (a fuel switched for another).
     */
    public void setPowerSetting(final Node node, final String settingId, final String value) {
        if (!node.isPower() || value.equals(node.powerSettings.get(settingId))) return;
        if ("true".equals(value)) Sfx.TOGGLE_ON.play();
        else if ("false".equals(value)) Sfx.TOGGLE_OFF.play();
        else Sfx.ADJUST.play();
        edit(() -> {
            final List<String> ins = keys(node.inputs), outs = keys(node.outputs);
            node.powerSettings.put(settingId, value);
            node.refresh();
            final List<String> nowIns = keys(node.inputs), nowOuts = keys(node.outputs);
            for (final Edge e : new ArrayList<>(graph.getEdges())) {
                if (e.sourceNodeId.equals(node.id)) {
                    final int at = moved(outs, nowOuts, e.sourceOutputIndex);
                    if (at < 0) graph.removeEdge(e.id);
                    else e.sourceOutputIndex = at;
                }
                if (e.targetNodeId.equals(node.id)) {
                    final int at = moved(ins, nowIns, e.targetInputIndex);
                    if (at < 0) graph.removeEdge(e.id);
                    else e.targetInputIndex = at;
                }
            }
            for (final Drawer d : graph.getDrawers()) {
                final boolean input = d.getKind()
                    .linksInputs();
                for (final Drawer.Link link : new ArrayList<>(d.getLinks())) {
                    if (!link.nodeId()
                        .equals(node.id)) continue;
                    final int at = input ? moved(ins, nowIns, link.portIndex())
                        : moved(outs, nowOuts, link.portIndex());
                    if (at == link.portIndex()) continue;
                    graph.unlinkDrawer(d.getId(), link);
                    if (at >= 0) graph.linkDrawer(d.getId(), new Drawer.Link(node.id, at));
                }
            }
        });
    }

    private static List<String> keys(final List<com.gtnhplanner.data.flowchart.Port<?>> ports) {
        final List<String> keys = new ArrayList<>(ports.size());
        for (final com.gtnhplanner.data.flowchart.Port<?> p : ports) keys.add(Resources.key(p));
        return keys;
    }

    /** Where the port that was at {@code index} is now, by its resource; -1 when it is gone. */
    private static int moved(final List<String> was, final List<String> now, final int index) {
        return index >= 0 && index < was.size() ? now.indexOf(was.get(index)) : -1;
    }

    public void setVoltage(final Node node, final String tier) {
        edit(() -> {
            node.machineConfig.setString("voltage", tier);
            syncShared(node);
        });
    }

    public void setSetting(final Node node, final String key, final Object value) {
        if (value instanceof final Boolean on) (on ? Sfx.TOGGLE_ON : Sfx.TOGGLE_OFF).play();
        else Sfx.ADJUST.play();
        edit(() -> {
            if (value instanceof final Boolean b) node.machineConfig.setBoolean(key, b);
            else if (value instanceof final Integer i) node.machineConfig.setInt(key, i);
            else node.machineConfig.setString(key, String.valueOf(value));
            syncShared(node);
        });
    }

    /** Sets several of a card's settings as one edit (a crop farm's units, kept as one normalized set). */
    public void setSettings(final Node node, final java.util.Map<String, String> values) {
        Sfx.ADJUST.play();
        edit(() -> {
            for (final java.util.Map.Entry<String, String> e : values.entrySet())
                node.machineConfig.setString(e.getKey(), e.getValue());
            syncShared(node);
        });
    }

    /** Picks which machine runs the recipe; GregTech single blocks bring their tier, multiblocks their options. */
    public void chooseMachine(final Node node, final ItemStack machine, final boolean gregtech) {
        edit(() -> {
            CardDefaults.switchMachine(node, machine, gregtech);
            syncShared(node);
        });
    }

    /**
     * Pins the machine count (gold on the card); zero or less unpins and lets the plan decide. On a shared machine the
     * count is the machine's: its recipes' machines add up to it.
     */
    public void pin(final Node node, final double count) {
        final com.gtnhplanner.data.flowchart.MachineGroup g = sharedOf(node.id);
        if (g != null) {
            pinShared(g, count);
            return;
        }
        (count > 0 ? Sfx.PIN : Sfx.UNPIN).play();
        edit(() -> applyPin(node, count));
    }

    /** A machine count from the wheel: solved at once, one undo step once the wheel goes still ({@link #live}). */
    public void wheelPin(final Node node, final double count) {
        final com.gtnhplanner.data.flowchart.MachineGroup g = sharedOf(node.id);
        if (g != null) live(() -> applyPinShared(g, count), count > 0 ? Sfx.PIN : Sfx.UNPIN);
        else live(() -> applyPin(node, count), count > 0 ? Sfx.PIN : Sfx.UNPIN);
    }

    /** A setting from the wheel (a card's amps): solved at once, one undo step once the wheel goes still. */
    public void wheelSetting(final Node node, final String key, final int value) {
        live(() -> {
            node.machineConfig.setInt(key, value);
            syncShared(node);
        }, null);
    }

    private static void applyPin(final Node node, final double count) {
        if (count <= 0) {
            node.setMachineCountFixed(false);
            return;
        }
        node.machineConfig.setMachineCount(Math.max(1, (int) Math.round(count)));
        node.setMachineCountFixed(true);
        // A pinned count is the machine's run: a port rate set before gives way to it.
        node.targetOutputRates.clear();
        node.targetInputRates.clear();
    }

    /**
     * Sets the rate a machine's port runs at (units a second): the machine runs so that this input takes, or this
     * output gives, exactly that. One per machine: setting it clears the machine's other port rates and its pinned
     * count; zero or less clears it and lets the plan decide.
     */
    public void setPortTarget(final Node node, final boolean output, final int index, final double perSecond) {
        (perSecond > 0 ? Sfx.PIN : Sfx.UNPIN).play();
        edit(() -> applyPortTarget(node, output, index, perSecond));
    }

    private static void applyPortTarget(final Node node, final boolean output, final int index,
        final double perSecond) {
        node.targetOutputRates.clear();
        node.targetInputRates.clear();
        if (perSecond <= 0) return;
        (output ? node.targetOutputRates : node.targetInputRates).put(index, perSecond);
        node.setMachineCountFixed(false);
    }

    /** Port rates being wheeled (per second), by the port, with the node they are on; null for none. */
    private Map<String, Object[]> wheeledPorts;

    /**
     * A port's rate from the wheel: shown and solved at once, one undo step once the wheel goes still ({@link #live}).
     */
    public void wheelPortRate(final Node node, final boolean output, final int index, final double perSecond) {
        if (wheeledPorts == null) wheeledPorts = new HashMap<>();
        final Double was = wheeledPortRate(node, output, index);
        final Double set = was != null ? was : portTarget(node, output, index);
        Sfx.TICK.play(perSecond >= (set == null ? 0 : set) ? 1.12f : 0.9f);
        wheeledPorts.put(portKey(node.id, output, index), new Object[] { node, output, index, perSecond });
        live(() -> applyPortTarget(node, output, index, perSecond), perSecond > 0 ? Sfx.PIN : Sfx.UNPIN);
    }

    /** The rate a port is being wheeled to, a second; null when it is not. */
    @Nullable
    public Double wheeledPortRate(final Node node, final boolean output, final int index) {
        final Object[] w = wheeledPorts == null ? null : wheeledPorts.get(portKey(node.id, output, index));
        return w == null ? null : (Double) w[3];
    }

    /** The rate a machine's port is set to, a second; null when the plan decides it. */
    @Nullable
    public Double portTarget(final Node node, final boolean output, final int index) {
        return (output ? node.targetOutputRates : node.targetInputRates).get(index);
    }

    /** Deletes a card: every recipe on it, when it is a shared machine. */
    public void delete(final Node node) {
        final List<UUID> sections = sectionsOf(node.id);
        edit(() -> { for (final UUID s : sections) graph.removeNode(s); });
        Sfx.REMOVE.play();
    }

    /**
     * A copy of the card on the nearest clear spot to just under it (in line with it), with the same machine and
     * settings, not wired; a shared machine with its recipes. The board brings it into view.
     */
    public void cloneNode(final Node node) {
        final int[] at = clearSpotUnder(node);
        final List<Node> copies = new ArrayList<>();
        for (final UUID s : sectionsOf(node.id)) {
            final Node from = graph.nodes.get(s);
            if (from != null && from.isPower()) {
                final Node copy = Node.power(from.powerSource, from.powerSettings, at[0], at[1]);
                copy.machineConfig.copySettingsFrom(from.machineConfig);
                copies.add(copy);
                continue;
            }
            final RecipeHandlerRef ref = from == null ? null : com.gtnhplanner.nei.RecipeRefs.of(from.recipeId);
            if (ref == null) return;
            final Node copy = new Node(ref.handler, ref.recipeIndex, at[0], at[1]);
            copy.machineConfig.copySettingsFrom(from.machineConfig);
            copy.machineName = from.machineName;
            copy.inputPicks.putAll(from.inputPicks);
            copy.applyPicks();
            copies.add(copy);
        }
        final com.gtnhplanner.data.flowchart.MachineGroup shared = sharedOf(node.id);
        edit(() -> {
            for (final Node copy : copies) graph.addNode(copy);
            if (shared == null) return;
            final com.gtnhplanner.data.flowchart.MachineGroup g = new com.gtnhplanner.data.flowchart.MachineGroup();
            g.setHeader("Shared machine");
            for (final Node copy : copies) g.addSection(copy.id);
            g.setMachineCapacity(shared.getMachineCapacity());
            g.setPinned(shared.isPinned());
            graph.groups.put(g.getId(), g);
        });
        if (!copies.isEmpty()) {
            justAdded = copies.get(0).id;
            focusAdded = false;
        }
        Sfx.CLONE.play();
    }

    /**
     * The clear spot nearest to just under a card (a gap below it, in line with it): on rings of grid steps round
     * there, nearest first, clear of every card and drawer by a margin; none within reach, there itself.
     */
    private int[] clearSpotUnder(final Node node) {
        final CardModel model = models.get(node.id);
        final int h = model == null ? 150 : new CardLayout(model, graph()).height;
        final int x0 = node.x, y0 = node.y + h + 40, step = 20;
        if (!NewCards.overlapsAnything(graph, x0, y0, CardLayout.W, h, null)) return new int[] { x0, y0 };
        for (int r = 1; r <= 40; r++) {
            int[] best = null;
            double bestD = Double.MAX_VALUE;
            for (int dy = -r; dy <= r; dy++) for (int dx = -r; dx <= r; dx++) {
                if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                final int x = x0 + dx * step, y = y0 + dy * step;
                if (NewCards.overlapsAnything(graph, x, y, CardLayout.W, h, null)) continue;
                final double d = Math.hypot(dx, dy);
                if (d < bestD) {
                    bestD = d;
                    best = new int[] { x, y };
                }
            }
            if (best != null) return best;
        }
        return new int[] { x0, y0 };
    }

    // endregion

    /** True while the board shows an answer older than the plan (a solve is queued or running). */
    public boolean solving() {
        final SolveService.Result latest = solver.latest();
        return latest == null || latest.version() != graph.solveVersion();
    }

    private long solvingSince = -1;

    /** Solving for long enough to say so: a quick solve should not flicker the top bar. */
    public boolean solvingVisibly() {
        if (!solving()) {
            solvingSince = -1;
            return false;
        }
        if (solvingSince < 0) solvingSince = System.currentTimeMillis();
        return System.currentTimeMillis() - solvingSince > 250;
    }

    /**
     * Called every client tick while the board is open, never from drawing. Hands the solver a snapshot when the plan
     * changed in a way that matters to the answer, and rebuilds the card models when the layout moved or an answer
     * came back. Until then the cards keep showing the last answer.
     */
    public void tick() {
        if (!com.gtnhplanner.dev.DevPerf.on()) {
            tickBoard();
            return;
        }
        final long started = System.nanoTime();
        tickBoard();
        com.gtnhplanner.dev.DevPerf.time("session.tick", System.nanoTime() - started);
    }

    private void tickBoard() {
        settleLive(false);
        if (disarmOnTick) {
            disarmOnTick = false;
            pendingLookup = null;
        }
        follow();
        try {
            solver.request(graph.solveVersion(), () -> SolveInput.of(graph, BalanceMode.AUTO, null));
        } catch (final RuntimeException e) {
            GtnhPlanner.LOG.warn("Could not snapshot the plan for solving", e);
        }
        final SolveService.Result latest = solver.latest();
        final boolean answered = latest != null && latest != lastResult;
        if (answered) {
            lastResult = latest;
            if (latest.error() != null) GtnhPlanner.LOG.warn("Solve failed", latest.error());
            result = latest.balance();
        } else if (graph.version() == seenVersion) {
            return;
        }
        indexSharedMachines();
        seenVersion = graph.version();
        final Set<String> wired = wiredPorts();
        final Map<String, Double> extras = extraFlows();
        final Map<UUID, CardModel> next = new LinkedHashMap<>();
        for (final Node node : graph.getNodes()) {
            try {
                next.put(
                    node.id,
                    CardModel.of(
                        node,
                        result == null ? null
                            : result.nodeBalances()
                                .get(node.id),
                        (output, index) -> wired.contains(portKey(node.id, output, index)),
                        (output, index) -> extras.getOrDefault(portKey(node.id, output, index), 0.0)));
            } catch (final RuntimeException e) {
                GtnhPlanner.LOG.warn("Card for {} failed", node.machineName, e);
            }
        }
        final Map<UUID, DrawerModel> nextDrawers = new LinkedHashMap<>();
        for (final Drawer drawer : graph.getDrawers()) nextDrawers.put(drawer.getId(), DrawerModel.of(drawer, result));
        if (!next.keySet()
            .equals(models.keySet())
            || !nextDrawers.keySet()
                .equals(drawerModels.keySet())
            || !graph.notes.keySet()
                .equals(noteIds)) {
            structure++;
            noteIds = new HashSet<>(graph.notes.keySet());
        }
        models = next;
        drawerModels = nextDrawers;
        notices = buildNotices(next);
        totals = buildTotals(next);
        spareLines = buildSpares(next);
        if (answered) heardRunning(next);
    }

    /** Whether the plan last solved to machines running, and for which graph: another one is taken as it is. */
    private boolean running;
    private Graph runningOf;

    /**
     * The relay when a fresh answer has the plan running where it did not (the first pin or rate that makes it
     * solve). A graph taken up (a tab, an undo, back from NEI) only sets where things stand.
     */
    private void heardRunning(final Map<UUID, CardModel> cards) {
        final boolean now = result instanceof BalanceResult.Solved && !nothingToSolveFor
            && notices.stream()
                .noneMatch(n -> n.severity() == Severity.ERROR)
            && cards.values()
                .stream()
                .anyMatch(m -> m.machines > 0);
        if (runningOf == graph && now && !running) Sfx.RUNNING.play();
        running = now;
        runningOf = graph;
    }

    // region Totals

    /** One resource line of the overview: what it is and its rate (per second). */
    public record TotalLine(String key, String label, ItemStack item, net.minecraftforge.fluids.FluidStack fluid,
        double amount) {

        public boolean isFluid() {
            return fluid != null;
        }
    }

    /** One card in the overview's machine list. */
    public record MachineLine(UUID nodeId, String name, ItemStack stack, double machines, boolean pinned, String tier,
        int amps, boolean multiblock, boolean gregtech, double euPerTick, boolean tooLow, double madeEuPerTick) {}

    /**
     * The overview, Factory Flow's resources column: per resource what the plan needs from outside (deficit), what it
     * gives out (surplus) and what it makes and uses itself (internal); its power; one line per card to build.
     */
    public record Totals(List<TotalLine> inputs, List<TotalLine> outputs, List<TotalLine> internal, double euPerTick,
        List<MachineLine> machines, double euMade) {

        static final Totals EMPTY = new Totals(List.of(), List.of(), List.of(), 0, List.of(), 0);
    }

    private Totals totals = Totals.EMPTY;

    public Totals totals() {
        return totals;
    }

    /** A resource's name and icon, from the first port or drawer that holds it. */
    private record Look(String label, ItemStack item, net.minecraftforge.fluids.FluidStack fluid) {}

    /** Below this a rate (per second) is solver noise, not a flow worth a line. */
    private static final double EPS = 1e-6;

    /**
     * Resource keys a wire joins to another: the key an input asks for, to the key of what is wired into it (a recipe
     * asking for any sand, fed the sand a card makes). From the last model; null before the first.
     */
    private Map<String, String> sameKeys;

    /**
     * The key the overview counts a resource under: what is wired into a port asking for it, when that differs (two
     * keys a wire joins are one resource).
     */
    public String canonicalKey(final String key) {
        final Map<String, String> same = sameKeys;
        if (same == null || key == null) return key;
        String at = key;
        for (int i = 0; i < 16 && same.containsKey(at); i++) at = same.get(at);
        return at;
    }

    private static String keyAt(final List<CardModel.PortView> ports, final int index) {
        for (final CardModel.PortView p : ports) if (p.index() == index) return p.key();
        return null;
    }

    private Totals buildTotals(final Map<UUID, CardModel> cards) {
        // Two ports wired together carry one resource even when their keys differ: counted as one, under the key of
        // the side that makes it.
        final Map<String, String> same = new HashMap<>();
        for (final Edge e : graph.getEdges()) {
            final CardModel from = cards.get(e.sourceNodeId), to = cards.get(e.targetNodeId);
            if (from == null || to == null) continue;
            final String made = keyAt(from.outputs, e.sourceOutputIndex), used = keyAt(to.inputs, e.targetInputIndex);
            if (made != null && used != null && !made.equals(used)) same.putIfAbsent(used, made);
        }
        sameKeys = same;
        final Map<String, double[]> flow = new LinkedHashMap<>();
        final Map<String, Look> looks = new HashMap<>();
        double eu = 0, euMade = 0;
        final List<MachineLine> machines = new ArrayList<>();
        for (final CardModel card : cards.values()) {
            // A custom rate card is the plan's edge, as a drawer is: what it supplies comes in from outside (an input)
            // and what it drains leaves (an output). It is no machine to build.
            if (com.gtnhplanner.power.CustomRate.is(card.node)) continue;
            for (final CardModel.PortView p : card.outputs) {
                final String key = canonicalKey(p.key());
                flow.computeIfAbsent(key, k -> new double[2])[0] += p.perSecond();
                looks.putIfAbsent(key, new Look(p.name(), p.item(), p.fluid()));
            }
            for (final CardModel.PortView p : card.inputs) {
                final String key = canonicalKey(p.key());
                flow.computeIfAbsent(key, k -> new double[2])[1] += p.perSecond();
                // Named as what makes it, where something does.
                if (key.equals(p.key())) looks.putIfAbsent(key, new Look(p.name(), p.item(), p.fluid()));
            }
            final double power = power(card);
            eu += power;
            euMade += card.madeEuPerTick();
            // A fluid tank is free and instant: nothing to build.
            if ((card.machines > 0 || card.pinned) && !com.gtnhplanner.power.FluidTank.is(card.node)) machines.add(
                new MachineLine(
                    card.node.id,
                    card.machineName,
                    card.machineStack,
                    // A crop card counts crops; the plan builds its harvesters.
                    com.gtnhplanner.machines.game.CropCards.builds(card.node, card.machines),
                    card.pinned,
                    card.tier,
                    card.amps,
                    card.multiblock,
                    card.gregtech,
                    power,
                    card.tierTooLow(),
                    card.madeEuPerTick()));
        }
        // What trash voids leaves the plan without being an output of it.
        final Map<String, Double> trashed = new HashMap<>();
        for (final DrawerModel d : drawerModels.values()) if (d.kind == Drawer.Kind.TRASH)
            trashed.merge(canonicalKey(d.drawer.getResourceKey()), Math.max(0, d.rate), Double::sum);
        final List<TotalLine> in = new ArrayList<>(), out = new ArrayList<>(), internal = new ArrayList<>();
        for (final Map.Entry<String, double[]> e : flow.entrySet()) {
            final double made = e.getValue()[0], used = e.getValue()[1];
            final Look look = looks.get(e.getKey());
            final double leaves = made - used - trashed.getOrDefault(e.getKey(), 0.0);
            if (used - made > EPS) in.add(line(e.getKey(), look, used - made));
            if (leaves > EPS) out.add(line(e.getKey(), look, leaves));
            if (Math.min(made, used) > EPS) internal.add(line(e.getKey(), look, Math.min(made, used)));
        }
        sortByAmount(in);
        sortByAmount(out);
        sortByAmount(internal);
        // A resource with a drawer stays listed at 0, so its rule and rate can be set from here.
        for (final DrawerModel d : drawerModels.values()) {
            if (d.kind == Drawer.Kind.TRASH) continue;
            final String key = canonicalKey(d.drawer.getResourceKey());
            final List<TotalLine> side = d.kind == Drawer.Kind.SOURCE ? in : out;
            if (side.stream()
                .noneMatch(
                    l -> l.key()
                        .equals(key)))
                side.add(new TotalLine(key, d.label, d.item, d.fluid, 0));
        }
        machines.sort(
            java.util.Comparator.<MachineLine>comparingInt(m -> -CardDefaults.tierIndex(m.tier()))
                .thenComparing(m -> -m.euPerTick())
                .thenComparing(MachineLine::name));
        return new Totals(in, out, internal, eu, machines, euMade);
    }

    private static TotalLine line(final String key, final Look look, final double amount) {
        return look == null ? new TotalLine(key, Resources.name(key), Resources.item(key), Resources.fluid(key), amount)
            : new TotalLine(key, look.label(), look.item(), look.fluid(), amount);
    }

    private static void sortByAmount(final List<TotalLine> lines) {
        lines.sort((a, b) -> Double.compare(b.amount(), a.amount()));
    }

    // endregion

    // region Wiring state and notices

    private static String portKey(final UUID nodeId, final boolean output, final int index) {
        return nodeId + (output ? ":o:" : ":i:") + index;
    }

    /**
     * What the solve sends out of the plan or brings into it at a wired port with no drawer there (the solver's open
     * gates), per port: a card shows it as a row of its own under the port, in the colours of the drawer it stands for.
     * With a drawer on the port, that drawer takes it.
     */
    private Map<String, Double> extraFlows() {
        final Map<String, Double> out = new HashMap<>();
        if (!(result instanceof final BalanceResult.Solved solved) || solved.auto() == null) return out;
        for (final List<com.gtnhplanner.data.flowchart.balancer.External> side : List
            .of(solved.auto().gatedSinks, solved.auto().gatedSources)) {
            for (final com.gtnhplanner.data.flowchart.balancer.External e : side) {
                final boolean output = !e.port()
                    .input();
                final UUID node = e.port()
                    .nodeId();
                final int index = e.port()
                    .portIndex();
                if (e.ratePerSecond() <= 0 || portHasDrawer(node, output, index)) continue;
                out.merge(portKey(node, output, index), e.ratePerSecond(), Double::sum);
            }
        }
        return out;
    }

    // region Spare

    /**
     * One place a leftover can be borne, as the overview shows it: its resource (the anchor port's), the card it is
     * on, what it carries when it bears all of it, and the ports the leftover crosses there (the anchor alone when it
     * bears none now).
     */
    public record SpareBearer(String key, UUID node, boolean output, int port, String name, String resourceKey,
        ItemStack item, net.minecraftforge.fluids.FluidStack fluid, String machine, double max, boolean solverPick,
        List<com.gtnhplanner.data.flowchart.balancer.PortRef> ports) {

        public boolean isFluid() {
            return fluid != null;
        }
    }

    /**
     * One leftover with more than one place that could bear it, and the shares the last solve gave it ({@link
     * #spareShares} reads the stored ones first, so an edit shows before the solve catches up).
     */
    public record SpareLine(String key, boolean importing, List<SpareBearer> bearers, double[] solvedShares) {}

    private List<SpareLine> spareLines = List.of();

    /** The plan's leftovers that have a choice, from the last solve. */
    public List<SpareLine> spares() {
        return spareLines;
    }

    /**
     * One thing the player pinned, as a spare's tooltip lists it: its words (what matters between asterisks) and its
     * icon.
     */
    public record Pin(String text, ItemStack item, net.minecraftforge.fluids.FluidStack fluid, boolean eu) {}

    /**
     * What the player pinned that a leftover follows from: the pinned machines, the cards' pinned output rates and the
     * drawers' set rates among the cards wired (or sharing a drawer) with the places that could bear it.
     */
    public List<Pin> pinsBehind(final SpareLine line) {
        final List<UUID> places = new ArrayList<>();
        for (final SpareBearer b : line.bearers()) places.add(b.node());
        return pinsBehind(places);
    }

    /** As above, for what is wired (or shares a drawer) with any of {@code start}. */
    public List<Pin> pinsBehind(final java.util.Collection<UUID> start) {
        final Map<UUID, List<UUID>> near = new HashMap<>();
        for (final Edge e : graph.getEdges()) {
            near.computeIfAbsent(e.sourceNodeId, k -> new ArrayList<>())
                .add(e.targetNodeId);
            near.computeIfAbsent(e.targetNodeId, k -> new ArrayList<>())
                .add(e.sourceNodeId);
        }
        for (final Drawer d : graph.getDrawers()) {
            final List<Drawer.Link> links = d.getLinks();
            for (int i = 1; i < links.size(); i++) {
                near.computeIfAbsent(
                    links.get(0)
                        .nodeId(),
                    k -> new ArrayList<>())
                    .add(
                        links.get(i)
                            .nodeId());
                near.computeIfAbsent(
                    links.get(i)
                        .nodeId(),
                    k -> new ArrayList<>())
                    .add(
                        links.get(0)
                            .nodeId());
            }
        }
        final Set<UUID> reach = new HashSet<>();
        final java.util.ArrayDeque<UUID> next = new java.util.ArrayDeque<>();
        for (final UUID n : start) if (reach.add(n)) next.add(n);
        while (!next.isEmpty())
            for (final UUID to : near.getOrDefault(next.poll(), List.of())) if (reach.add(to)) next.add(to);
        final List<Pin> out = new ArrayList<>();
        final Fmt.RateUnit unit = rateUnit();
        for (final MachineLine m : totals.machines()) if (m.pinned() && reach.contains(m.nodeId()))
            out.add(new Pin(m.name() + " *×" + Fmt.machines(m.machines()) + "*", m.stack(), null, false));
        for (final Node n : graph.getNodes()) {
            if (!reach.contains(n.id) || n.targetOutputRates.isEmpty() && n.targetInputRates.isEmpty()) continue;
            final CardModel card = models.get(n.id);
            if (card == null) continue;
            for (final boolean output : new boolean[] { true, false })
                for (final Map.Entry<Integer, Double> t : (output ? n.targetOutputRates : n.targetInputRates)
                    .entrySet())
                    for (final CardModel.PortView p : output ? card.outputs : card.inputs)
                        if (p.index() == t.getKey()) out.add(
                            new Pin(
                                p.name() + ", *" + pinRate(p.key(), t.getValue(), p.isFluid(), unit) + "*",
                                p.item(),
                                p.fluid(),
                                Resources.isPower(p.key())));
        }
        for (final DrawerModel d : drawerModels.values()) {
            if (!d.kind.hasRule() || d.rule == Drawer.Rule.ANY || d.rule != Drawer.Rule.AT_MOST && d.target <= 0)
                continue;
            boolean linked = false;
            for (final Drawer.Link l : d.drawer.getLinks()) linked |= reach.contains(l.nodeId());
            if (!linked) continue;
            final String rule = switch (d.rule) {
                case AT_LEAST -> "at least ";
                case AT_MOST -> "at most ";
                default -> "";
            };
            final String key = d.drawer.getResourceKey();
            out.add(
                new Pin(
                    d.label + ", " + rule + "*" + pinRate(key, d.target, d.fluid != null, unit) + "*",
                    d.item,
                    d.fluid,
                    Resources.isPower(key)));
        }
        return out;
    }

    private static String pinRate(final String key, final double perSecond, final boolean fluid,
        final Fmt.RateUnit unit) {
        return Resources.isPower(key) ? Fmt.compact(perSecond / 20) + " EU/t" : Fmt.rate(perSecond, unit, fluid);
    }

    /** Whether a leftover is split by hand rather than left where the solver puts it. */
    public boolean spareCustom(final SpareLine line) {
        return graph.spareShares.containsKey(line.key());
    }

    /** A leftover's shares, one per bearer, summing to one: the stored split, else the solve's. */
    public double[] spareShares(final SpareLine line) {
        final Map<String, Double> stored = graph.spareShares.get(line.key());
        if (stored == null) return line.solvedShares()
            .clone();
        final double[] out = new double[line.bearers()
            .size()];
        double total = 0;
        for (int i = 0; i < out.length; i++) {
            out[i] = Math.max(
                0,
                stored.getOrDefault(
                    line.bearers()
                        .get(i)
                        .key(),
                    0.0));
            total += out[i];
        }
        if (total <= 0) return line.solvedShares()
            .clone();
        for (int i = 0; i < out.length; i++) out[i] /= total;
        return out;
    }

    /** Sets a leftover's split (one share per bearer), as one undoable edit. */
    public void setSpareShares(final SpareLine line, final double[] shares) {
        final Map<String, Double> split = spareMap(line, shares);
        edit(() -> graph.setSpareSplit(line.key(), split));
    }

    /** Puts a leftover back where the solver puts it, as one undoable edit. */
    public void clearSpare(final SpareLine line) {
        edit(() -> graph.setSpareSplit(line.key(), null));
    }

    /**
     * A split shown while it is still being dragged: the plan re-solves with it, but it is no edit yet (no undo step,
     * not saved). {@code null} puts back what was stored before the drag.
     */
    public void previewSpareShares(final SpareLine line, @Nullable final double[] shares,
        @Nullable final Map<String, Double> before) {
        graph.setSpareSplit(line.key(), shares == null ? before : spareMap(line, shares));
    }

    private static Map<String, Double> spareMap(final SpareLine line, final double[] shares) {
        final Map<String, Double> split = new LinkedHashMap<>();
        for (int i = 0; i < shares.length; i++) split.put(
            line.bearers()
                .get(i)
                .key(),
            Math.max(0, shares[i]));
        return split;
    }

    /** The bearer the overview's mouse is on, and where on the overview's edge its line starts (GUI pixels). */
    private SpareLine spareHoverLine;
    private SpareBearer spareHover;
    private int spareHoverX, spareHoverY;
    private HoverScope spareHoverScope = HoverScope.NONE;

    public void setSpareHover(@Nullable final SpareLine line, @Nullable final SpareBearer bearer, final int x,
        final int y) {
        spareHoverX = x;
        spareHoverY = y;
        if (bearer == spareHover && line == spareHoverLine) return;
        spareHoverLine = line;
        spareHover = bearer;
        if (bearer == null) {
            spareHoverScope = HoverScope.NONE;
            return;
        }
        final List<UUID> nodes = new ArrayList<>();
        final List<Boolean> outputs = new ArrayList<>();
        final List<Integer> ports = new ArrayList<>();
        for (final com.gtnhplanner.data.flowchart.balancer.PortRef ref : bearer.ports()) {
            nodes.add(ref.nodeId());
            outputs.add(!ref.input());
            ports.add(ref.portIndex());
        }
        spareHoverScope = HoverScope.portsOnly(nodes, outputs, ports);
    }

    /** The place a spare menu's entry under the mouse names; it wins over the overview's while set. */
    private SpareLine spareMenuLine;
    private SpareBearer spareMenuHover;
    private HoverScope spareMenuScope = HoverScope.NONE;

    /** When a spare menu last said what is under the mouse: it says so every frame it is open, so silence is closed. */
    private long spareMenuAt;

    public void setSpareMenuHover(@Nullable final SpareLine line, @Nullable final SpareBearer bearer) {
        spareMenuAt = System.currentTimeMillis();
        if (bearer == spareMenuHover && line == spareMenuLine) return;
        spareMenuLine = line;
        spareMenuHover = bearer;
        spareMenuScope = bearer == null ? HoverScope.NONE : scopeOf(bearer);
    }

    private static HoverScope scopeOf(final SpareBearer bearer) {
        final List<UUID> nodes = new ArrayList<>();
        final List<Boolean> outputs = new ArrayList<>();
        final List<Integer> ports = new ArrayList<>();
        for (final com.gtnhplanner.data.flowchart.balancer.PortRef ref : bearer.ports()) {
            nodes.add(ref.nodeId());
            outputs.add(!ref.input());
            ports.add(ref.portIndex());
        }
        return HoverScope.portsOnly(nodes, outputs, ports);
    }

    /** Whether a spare menu's entry is under the mouse now (the menu said so within the last few frames). */
    private boolean spareMenuLive() {
        return spareMenuHover != null && System.currentTimeMillis() - spareMenuAt < 250;
    }

    @Nullable
    public SpareBearer spareHover() {
        return spareMenuLive() ? spareMenuHover : spareHover;
    }

    @Nullable
    public SpareLine spareHoverLine() {
        return spareMenuLive() ? spareMenuLine : spareHoverLine;
    }

    /** Where the hovered bearer's line leaves the overview, {x, y} in GUI pixels. */
    public int[] spareHoverFrom() {
        return new int[] { spareHoverX, spareHoverY };
    }

    /**
     * How far each card is from where the plan starts, along its wires: the cards nothing is wired into are 0, the
     * cards they feed 1, and so on (the nearest way, so a loop does not run on). A card no wire reaches from a start is
     * left out.
     */
    private Map<UUID, Integer> depths() {
        final Map<UUID, List<UUID>> feeds = new HashMap<>();
        final Set<UUID> fed = new HashSet<>();
        for (final Edge e : graph.getEdges()) {
            feeds.computeIfAbsent(e.sourceNodeId, k -> new ArrayList<>())
                .add(e.targetNodeId);
            fed.add(e.targetNodeId);
        }
        final Map<UUID, Integer> depth = new HashMap<>();
        final java.util.ArrayDeque<UUID> next = new java.util.ArrayDeque<>();
        for (final Node n : graph.getNodes()) if (!fed.contains(n.id)) {
            depth.put(n.id, 0);
            next.add(n.id);
        }
        while (!next.isEmpty()) {
            final UUID at = next.poll();
            for (final UUID to : feeds.getOrDefault(at, List.of())) if (!depth.containsKey(to)) {
                depth.put(to, depth.get(at) + 1);
                next.add(to);
            }
        }
        return depth;
    }

    /** The leftovers on the last solve, as the overview lists them (bearers named by the cards they sit on). */
    private List<SpareLine> buildSpares(final Map<UUID, CardModel> cards) {
        if (!(result instanceof final BalanceResult.Solved solved) || solved.auto() == null) return List.of();
        final Map<UUID, Integer> depth = depths();
        final List<SpareLine> out = new ArrayList<>();
        for (final com.gtnhplanner.data.flowchart.balancer.Spares.Leftover leftover : solved.auto()
            .spares()) {
            final List<SpareBearer> bearers = new ArrayList<>();
            final double[] shares = new double[leftover.bearers()
                .size()];
            int i = 0;
            for (final com.gtnhplanner.data.flowchart.balancer.Spares.Bearer b : leftover.bearers()) {
                final com.gtnhplanner.data.flowchart.balancer.PortRef anchor = b.anchor();
                final CardModel card = cards.get(anchor.nodeId());
                final List<CardModel.PortView> side = card == null ? List.of()
                    : anchor.input() ? card.inputs : card.outputs;
                if (anchor.portIndex() < 0 || anchor.portIndex() >= side.size()) break;
                final CardModel.PortView port = side.get(anchor.portIndex());
                final List<com.gtnhplanner.data.flowchart.balancer.PortRef> ports = new ArrayList<>();
                for (final com.gtnhplanner.data.flowchart.balancer.External e : b.flows()) ports.add(e.port());
                if (ports.isEmpty()) ports.add(anchor);
                bearers.add(
                    new SpareBearer(
                        b.key(),
                        anchor.nodeId(),
                        !anchor.input(),
                        anchor.portIndex(),
                        port.name(),
                        port.key(),
                        port.item(),
                        port.fluid(),
                        card.machineName,
                        b.max(),
                        b.solverPick(),
                        List.copyOf(ports)));
                shares[i++] = b.share();
            }
            if (bearers.size() != leftover.bearers()
                .size()) continue;
            // Most upstream first: a place leaving after a card sits just past it, one coming in just before it.
            final Integer[] order = new Integer[bearers.size()];
            for (int k = 0; k < order.length; k++) order[k] = k;
            java.util.Arrays.sort(
                order,
                java.util.Comparator.comparingDouble(k -> depth.getOrDefault(bearers.get(k)
                    .node(), Integer.MAX_VALUE / 2) + (bearers.get(k)
                        .output() ? 0.5 : -0.5)));
            final List<SpareBearer> sorted = new ArrayList<>(bearers.size());
            final double[] sortedShares = new double[shares.length];
            for (int k = 0; k < order.length; k++) {
                sorted.add(bearers.get(order[k]));
                sortedShares[k] = shares[order[k]];
            }
            out.add(new SpareLine(leftover.key(), leftover.importing(), List.copyOf(sorted), sortedShares));
        }
        return List.copyOf(out);
    }

    // endregion

    /** Every port with a wire or a drawer on it. */
    private Set<String> wiredPorts() {
        final Set<String> wired = new HashSet<>();
        for (final Edge e : graph.getEdges()) {
            wired.add(portKey(e.sourceNodeId, true, e.sourceOutputIndex));
            wired.add(portKey(e.targetNodeId, false, e.targetInputIndex));
        }
        for (final Drawer d : graph.getDrawers()) {
            for (final Drawer.Link link : d.getLinks()) {
                wired.add(
                    portKey(
                        link.nodeId(),
                        !d.getKind()
                            .linksInputs(),
                        link.portIndex()));
            }
        }
        return wired;
    }

    public List<Notice> notices() {
        if (flash == null) return notices;
        if (System.currentTimeMillis() > flashUntil) {
            flash = null;
            return notices;
        }
        final List<Notice> out = new ArrayList<>(notices.size() + 1);
        out.add(flash);
        out.addAll(notices);
        return out;
    }

    private Notice flash;
    private long flashUntil;

    /** Shows a sentence above the solver's notices for a few seconds (what a paste or an import did). */
    public void flash(final Severity severity, final String text) {
        flash = new Notice(severity, text, List.of());
        flashUntil = System.currentTimeMillis() + 7000;
    }

    /**
     * Adds the plan on the clipboard as a new slot: a Factory Flow link, code or JSON, or a GTNH Planner share code.
     * Says what happened in a notice.
     */
    public void pastePlan() {
        final String text = net.minecraft.client.gui.GuiScreen.getClipboardString();
        if (text == null || text.isBlank()) {
            flash(Severity.WARN, "The clipboard is empty: copy a plan link or code first");
            Sfx.DENY.play();
            return;
        }
        final Graph own = PlanAPI.importFromClipboard();
        if (own != null) {
            com.gtnhplanner.importer.game.FactoryFlowImport.addAsSlot(own);
            flash(Severity.INFO, "Pasted '" + own.getName() + "'");
            Sfx.SWEEP.play();
            return;
        }
        try {
            final com.gtnhplanner.importer.FfConverter.Result result = com.gtnhplanner.importer.game.FactoryFlowImport
                .importAsSlot(text);
            final com.gtnhplanner.importer.ImportReport report = result.report();
            final int missing = report.entries(com.gtnhplanner.importer.ImportReport.Kind.UNMATCHED)
                .size();
            final String name = result.graph()
                .getName();
            flash(missing == 0 ? Severity.INFO : Severity.WARN, "Imported '" + name + "': " + report.summary());
            Sfx.SWEEP.play();
        } catch (final RuntimeException e) {
            flash(Severity.WARN, "The clipboard does not hold a plan link or code");
            Sfx.DENY.play();
            com.gtnhplanner.GtnhPlanner.LOG.info("Paste plan failed", e);
        }
    }

    /** Copies a slot's GTNH Planner share code to the clipboard. */
    public void copyPlan(final int index) {
        if (index < 0 || index >= slots().size()) return;
        PlanAPI.copyToClipboard(slots().get(index));
        flash(
            Severity.INFO,
            "Copied '" + slots().get(index)
                .getName() + "' to the clipboard");
    }

    /** Shares a slot's plan in the game's chat, as a line everyone on the server can open. */
    public void shareInChat(final int index) {
        if (index < 0 || index >= slots().size()) return;
        final Graph graph = slots().get(index);
        final String problem = com.gtnhplanner.client.ChatShare.send(graph);
        if (problem != null) {
            flash(Severity.WARN, problem);
            Sfx.DENY.play();
            return;
        }
        flash(Severity.INFO, "Shared '" + graph.getName() + "' in chat");
    }

    /** True when the solver had nothing to scale the plan by: no product rate and no pinned count. */
    public boolean nothingToSolveFor() {
        return nothingToSolveFor;
    }

    /**
     * Whether this recipe or drawer asks for a number: nothing sets the part of the plan it is in (no pinned count or
     * rate anywhere along its wires), or the answer leaves it at zero because nothing pinned feeds it or needs it. Its
     * count and rates then ask for a number, however much of the rest of the plan is set.
     */
    public boolean unset(final UUID id) {
        return nothingToSolveFor || unset.contains(id);
    }

    /**
     * What asks for a number: every part of the plan nothing sets, and every machine the answer leaves at zero because
     * nothing pinned feeds it or needs what it makes ({@code SolutionView.unneeded}).
     */
    private java.util.Set<UUID> unsetParts() {
        final java.util.Set<UUID> out = new java.util.HashSet<>(com.gtnhplanner.data.flowchart.balancer.Unanchored.of(graph));
        if (result instanceof final com.gtnhplanner.data.flowchart.balancer.BalanceResult.Solved solved) out.addAll(
            solved.auto().unneeded);
        return out;
    }

    /**
     * The lines along the top of the board, as a player wants them: what is wrong in a few words, the thing it is about
     * named first, and "Show me" to find it. The solver's own sentences are for the log; problems of one kind share a
     * line.
     */
    private List<Notice> buildNotices(final Map<UUID, CardModel> cards) {
        final List<Notice> out = new ArrayList<>();
        nothingToSolveFor = false;
        unset = result == null ? java.util.Set.of() : unsetParts();
        if (result != null) {
            for (final Note note : result.notes()) {
                if (note.message() == SolverMessage.NO_PIN) {
                    nothingToSolveFor = !cards.isEmpty();
                    if (nothingToSolveFor) out.add(
                        new Notice(
                            Severity.INFO,
                            "Nothing to calculate yet: set a rate on a drawer, or pin a machine count",
                            List.of()));
                }
            }
            for (final DrawerReadout.Shortfall s : result.drawers()
                .shortfalls()) {
                final DrawerModel d = drawerModels.get(s.drawer());
                final boolean supply = d != null && d.kind == Drawer.Kind.SOURCE;
                final String label = "The " + (d == null ? "" : d.label + " ") + (supply ? "supply" : "drawer");
                final List<UUID> focus = new ArrayList<>();
                focus.add(s.drawer());
                focus.addAll(s.limitingNodes());
                focus.addAll(s.limitingDrawers());
                final String reach = d == null ? Fmt.rate(s.reachable(), rateUnit, false)
                    : d.rate(s.reachable(), rateUnit);
                final String target = d == null ? Fmt.rate(s.target(), rateUnit, false) : d.rate(s.target(), rateUnit);
                // Plain sentences: what the drawer asks for, and what the plan does instead.
                final String asks = s.rule() == Drawer.Rule.EXACTLY ? "exactly " : "";
                final String text;
                if (s.reachable() > s.target()) text = label + " allows at most "
                    + target
                    + ", but the plan "
                    + (supply ? "needs " : "makes ")
                    + reach;
                else text = label + (supply ? " should give " : " wants ")
                    + asks
                    + target
                    + ", but the plan can only "
                    + (supply ? "use " : "make ")
                    + reach;
                out.add(new Notice(Severity.WARN, text, focus));
            }
            for (final Note note : result.notes()) {
                // If/else, not a switch: a switch on an enum compiles to a lookup table a hot swap does not refresh.
                // The solver also guesses at missing wires (something made here and brought in too); that is a guess
                // about what the player meant, not a problem with the plan, so it is not shown.
                final SolverMessage m = note.message();
                if (m == SolverMessage.OVERSHOOTS_TARGET) out.add(overshoot(note, cards));
                else if (m == SolverMessage.DRAWER_NOT_CONNECTED) out.add(
                    new Notice(
                        Severity.WARN,
                        "The " + note.args()[0] + " drawer has a rate, but nothing is wired to it",
                        idsIn(note)));
                else if (m == SolverMessage.DRAWER_WIRED_IGNORED) out.add(
                    new Notice(
                        Severity.WARN,
                        "The " + note.args()[0] + " drawer's rate does nothing: the ports it's on are wired",
                        idsIn(note)));
                else if (m == SolverMessage.CHOICE_NO_LONGER_FITS || m == SolverMessage.CHOICE_NEEDS_MORE_GATES)
                    out.add(
                        new Notice(
                            Severity.WARN,
                            "Your saved choice no longer fits this plan, so the planner's own answer is shown",
                            List.of()));
                else if (note.severity() == Severity.ERROR && m != SolverMessage.EMPTY_GRAPH) out.add(failure(note));
            }
        } else if (lastResult != null && lastResult.errorNote() != null) {
            out.add(failure(lastResult.errorNote()));
        }
        // A port with no wire or drawer is no problem: what it takes comes in from outside the plan, what it makes
        // leaves it (the card draws it so).
        return out;
    }

    /** An output made past its target, said in full. */
    private Notice overshoot(final Note note, final Map<UUID, CardModel> cards) {
        final Object[] a = note.args();
        final String what = String.valueOf(a[1]);
        boolean fluid = false;
        final CardModel card = a.length > 4 && a[4] instanceof final UUID id ? cards.get(id) : null;
        if (card != null) for (final CardModel.PortView p : card.outputs) if (p.name()
            .equals(what)) fluid = p.isFluid();
        final double made = a[2] instanceof final Number n ? n.doubleValue() : 0;
        final double target = a[3] instanceof final Number n ? n.doubleValue() : 0;
        return new Notice(
            Severity.WARN,
            "The " + named(note).args()[0]
                + " makes "
                + Fmt.rate(made, rateUnit, fluid)
                + " of "
                + what
                + ", more than its "
                + Fmt.rate(target, rateUnit, fluid)
                + " target, because another of its outputs needs more",
            idsIn(note));
    }

    /**
     * A solve that found no answer, as what the player can do about it. The solver's own account goes to the log, once
     * per change.
     */
    private Notice failure(final Note note) {
        final String detail = render(note);
        if (!detail.equals(lastFailureLogged)) {
            lastFailureLogged = detail;
            GtnhPlanner.LOG.info("[solve] no answer: {}", detail);
        }
        final List<Note> clashing = new ArrayList<>();
        collect(note, SolverMessage.PIN_DROPPED, clashing);
        if (!clashing.isEmpty()) {
            final List<String> names = new ArrayList<>();
            final List<UUID> ids = new ArrayList<>();
            for (final Note pin : clashing) {
                final String name = String.valueOf(named(pin).args()[0]);
                if (!names.contains(name)) names.add(name);
                ids.addAll(idsIn(pin));
            }
            return new Notice(
                Severity.ERROR,
                "The pinned counts on " + String.join(" and ", names) + " can't all be met at once: unpin one",
                ids);
        }
        if (note.containsMessage(SolverMessage.MACHINES_CANNOT_RUN))
            return new Notice(Severity.ERROR, "Some machines can't run with these rates and pinned counts", List.of());
        if (note.containsMessage(SolverMessage.SOLVER_UNSATISFIABLE)
            || note.containsMessage(SolverMessage.SOLVER_NO_SOLUTION)
            || note.containsMessage(SolverMessage.SOLVER_BUDGET)
            || note.containsMessage(SolverMessage.STAGE_FAILED))
            return new Notice(
                Severity.ERROR,
                "There's no way to meet all these rates and pinned counts: change a rate or unpin a count",
                List.of());
        return new Notice(
            Severity.ERROR,
            "The planner couldn't solve this plan (the details are in the log)",
            List.of());
    }

    private String lastFailureLogged = "";

    /** Every note of a kind inside a note's arguments, nested notes and lists included. */
    private static void collect(final Object arg, final SolverMessage kind, final List<Note> into) {
        if (arg instanceof final Note note) {
            if (note.message() == kind) into.add(note);
            for (final Object a : note.args()) collect(a, kind, into);
        } else if (arg instanceof final List<?> list) for (final Object o : list) collect(o, kind, into);
    }

    private static String render(final Note note) {
        try {
            return named(note).render();
        } catch (final RuntimeException e) {
            return note.describe();
        }
    }

    /**
     * The note with every resource key among its arguments as the name a player reads: a GregTech card's machine is
     * remembered as its item key ("item:gregtech:gt.blockmachines:1000"), and the solver names machines by it.
     */
    private static Note named(final Note note) {
        final Object[] args = note.args()
            .clone();
        for (int i = 0; i < args.length; i++) args[i] = namedArg(args[i]);
        return new Note(note.message(), args);
    }

    private static Object namedArg(final Object arg) {
        if (arg instanceof final Note nested) return named(nested);
        if (arg instanceof final List<?> list) {
            final List<Object> out = new ArrayList<>(list.size());
            for (final Object o : list) out.add(namedArg(o));
            return out;
        }
        if (arg instanceof final String s && (s.startsWith("item:") || s.startsWith("fluid:"))) return Resources.name(s);
        return arg;
    }

    /** Node and drawer ids a note names, for "Show me". */
    private List<UUID> idsIn(final Note note) {
        final List<UUID> ids = new ArrayList<>();
        for (final Object arg : note.args()) {
            if (arg instanceof final UUID id && (graph.nodes.containsKey(id) || graph.drawers.containsKey(id)))
                ids.add(id);
            else if (arg instanceof final Note nested) ids.addAll(idsIn(nested));
        }
        return ids;
    }

    // endregion
}
