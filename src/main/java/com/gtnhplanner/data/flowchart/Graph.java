package com.gtnhplanner.data.flowchart;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.Nullable;

import com.gtnhplanner.data.flowchart.balancer.BalanceMode;
import com.gtnhplanner.data.flowchart.balancer.BalanceResult;
import com.gtnhplanner.data.flowchart.balancer.BalanceView;
import com.gtnhplanner.data.flowchart.balancer.ChoiceKey;

import lombok.Getter;
import lombok.Setter;

public class Graph {

    // TODO make these use getters
    /**
     * Sorted, so the graph hands its contents back in id order and every consumer that needs a
     * reproducible answer gets one without sorting first - the solver, the serializer, the router
     * and the layout all read these directly.
     */
    public final SortedMap<UUID, Node> nodes = new TreeMap<>();
    public final SortedMap<UUID, Edge> edges = new TreeMap<>();
    public final SortedMap<UUID, Note> notes = new TreeMap<>();
    public final SortedMap<UUID, Group> groups = new TreeMap<>();
    /**
     * The board's drawers. Read through {@link #getDrawers()}; change membership and links through
     * {@link #addDrawer}, {@link #removeDrawer}, {@link #linkDrawer} and {@link #unlinkDrawer} so the
     * version moves.
     */
    public final SortedMap<UUID, Drawer> drawers = new TreeMap<>();
    /**
     * The board's groups ({@link BoardGroup}, read and changed through {@link Groups}); nothing to do with the solve.
     */
    public final SortedMap<UUID, BoardGroup> boardGroups = new TreeMap<>();

    /** The plan's name; changing it is an edit ({@link #setName}). */
    @Getter
    private String name;

    @Getter
    @Setter
    private float zoom = 1f;
    @Getter
    @Setter
    private float panX;
    @Getter
    @Setter
    private float panY;
    @Getter
    @Setter
    private boolean snapToGrid;

    /**
     * When the plan was last open (ms since the epoch), so menus can list the most recent first. Saved with the slot.
     */
    @Getter
    @Setter
    private long lastOpen;

    /**
     * The settings each machine's cards show on themselves, pinned from the settings sheet: machine (a card's
     * {@code SettingPins.machine}) to setting keys, in order. A machine missing here shows its defaults. Saved with the
     * plan, so it looks the same wherever it is opened.
     */
    public final java.util.Map<String, java.util.List<String>> settingPins = new java.util.TreeMap<>();

    /**
     * The cards on the pin list shown while playing ({@code ui/world/PinList}), by node id (a shared machine by its
     * host), in the order they were pinned: the machines to gather for a build. Saved with the plan.
     */
    public final java.util.LinkedHashSet<UUID> pinList = new java.util.LinkedHashSet<>();

    /**
     * How each leftover is split between the places that could bear it ({@code balancer/Spares}): per leftover (its
     * bearers' keys, sorted and joined), each bearer's share. A leftover missing here is where the solver puts it.
     * Saved with the plan; edit through {@link #setSpareSplit} so the plan re-solves.
     */
    public final java.util.Map<String, java.util.Map<String, Double>> spareShares = new java.util.TreeMap<>();

    /** Sets one leftover's split (bearer key to share), or clears it back to the solver's own with null. */
    public void setSpareSplit(final String leftover, @Nullable final java.util.Map<String, Double> shares) {
        if (shares == null || shares.isEmpty()) spareShares.remove(leftover);
        else spareShares.put(leftover, new java.util.TreeMap<>(shares));
        touch();
    }

    /**
     * The plan's task board ({@link Tasks}): tasks, their checklist lines and comments, in no particular order (a task
     * board sorts by each one's order key). Saved with the plan and carried by its plan code; change them with
     * {@link #touchLayout()}, since they take no part in the solve.
     */
    public final List<Tasks.Task> tasks = new ArrayList<>();
    public final List<Tasks.Item> taskItems = new ArrayList<>();
    public final List<Tasks.Comment> taskComments = new ArrayList<>();

    /** Which view the plan opens on: {@link Tasks#PLAN}, {@link Tasks#TASKS} (a task board), or null for the board. */
    @Getter
    @Nullable
    private String primaryView;

    /** Whether the plan has a tab. A closed plan is still kept, in the library's My plans. Saved with the slot. */
    @Getter
    @Setter
    private boolean open = true;

    /**
     * The post on gtnhplanner.com this plan went up as (its id there), so posting it again updates that post; null when
     * it has none. Saved with the slot, never in a plan code.
     */
    @Getter
    @Setter
    @Nullable
    private String postId;

    /**
     * The cloud plan this slot was uploaded as (its doc key, {@code <ownerId>:<designId>}), so it is never uploaded
     * twice; null while it is only on this computer. Saved with the slot, never in a plan code.
     */
    @Getter
    @Setter
    @Nullable
    private String cloudKey;

    /**
     * A plan on this computer's own name for itself, so a task can point at its cards ({@link Tasks.Link}): made when
     * first asked, saved with the slot, never in a plan code. A plan that goes to the account keeps it, and is still
     * found by it.
     */
    @Getter
    @Setter
    @Nullable
    private String localId;

    /** The id a link names this plan by: its cloud doc key, else its id on this computer. */
    public String planId() {
        if (cloudKey != null) return cloudKey;
        if (localId == null) localId = UUID.randomUUID()
            .toString();
        return localId;
    }

    /** Whether a link's plan id is this plan's (its cloud key, or the id it had before it went to the account). */
    public boolean isPlan(final String id) {
        return !id.isEmpty() && (id.equals(cloudKey) || id.equals(localId));
    }

    /** A line or two about the plan: its post's description. Saved with the slot. */
    @Getter
    private String description = "";

    /** The plan's face: a resource key ({@code Resources}), shown on its tile and its post; null for none. */
    @Getter
    @Nullable
    private String icon;

    /**
     * A slot that could not be read when the plans were loaded: its saved data, written back as it was while the slot
     * stays empty (so a failed read never costs the plan), and read again when the slot is opened. Null otherwise.
     */
    @Getter
    @Setter
    private transient String unreadable;

    @Getter
    private BalanceMode balanceMode = BalanceMode.AUTO;

    /**
     * Which slot this graph is, for state kept per slot outside the graph (the undo history, see
     * {@link UndoHistories}). A graph an undo or redo puts in a slot's place takes over the slot of
     * the graph it replaces; any other graph is a slot of its own.
     */
    private transient UUID slot = UUID.randomUUID();

    /**
     * The display view, built on first ask after a solve rather than with it: the canvas wants the
     * boundary every frame and never the choices, which the summary reads straight from the solve.
     */
    private List<BalanceView.Boundary> boundaryView = null;

    /** Shared by every graph, so a version names one state of one graph across the whole client. */
    private static final AtomicLong VERSIONS = new AtomicLong();

    /**
     * Moves on every change, layout included. Drawn from one client-wide counter, so it only ever
     * grows and no two graphs - a slot and the graph an undo put in its place, say - ever share a
     * value: equal versions mean the same graph in the same state. Transient because a loaded plan
     * starts cold and re-derives everything on first ask.
     */
    private transient long version = VERSIONS.incrementAndGet();

    /**
     * Moves on every change that can change the solve - not on a move or a resize. The solve caches
     * (and a {@code SolveService} request) key on this, so dragging a card never re-solves.
     */
    private transient long solveVersion = version;

    /** The solve version the solve caches above were built from. */
    private transient long solvedAt = -1;

    /**
     * An imported plan whose cards may lie on each other (sized differently where it was made): the board pulls them
     * apart once it has solved and knows their sizes ({@link #takeUnstack}).
     */
    private transient boolean unstackPending;

    /** Asks the board to pull the plan's cards and drawers apart where they overlap, the next time it shows it. */
    public void unstackWhenShown() {
        unstackPending = true;
    }

    /** Whether the board should pull the plan's cards apart now; asks only once. */
    public boolean takeUnstack() {
        final boolean pending = unstackPending;
        unstackPending = false;
        return pending;
    }

    public boolean unstackPending() {
        return unstackPending;
    }

    public Graph() {
        this.name = "";
    }

    public Graph(final String name) {
        this.name = name;
    }

    /** Every change moves this, layout included; equal values mean the same graph, unchanged. */
    public long version() {
        return version;
    }

    /** Every change that can change the solve moves this; layout-only edits do not. */
    public long solveVersion() {
        return solveVersion;
    }

    /** The slot this graph is; see {@link UndoHistories}. */
    public UUID slot() {
        return slot;
    }

    /**
     * This graph is a cloud plan read again (others' edits, an undo) in {@code old}'s place: it takes over what this
     * device keeps of the slot and is never part of the plan (the slot, the camera, its tab, the pin list, its post).
     */
    public void takeDeviceStateOf(final Graph old) {
        this.slot = old.slot;
        this.zoom = old.zoom;
        this.panX = old.panX;
        this.panY = old.panY;
        this.snapToGrid = old.snapToGrid;
        this.lastOpen = old.lastOpen;
        this.open = old.open;
        this.postId = old.postId;
        this.cloudKey = old.cloudKey;
        this.localId = old.localId;
        pinList.clear();
        pinList.addAll(old.pinList);
    }

    /** This graph takes over {@code replaced}'s slot: it is that slot's state after an undo or redo. */
    void takeSlotOf(final Graph replaced) {
        this.slot = replaced.slot;
        this.lastOpen = replaced.lastOpen;
        this.open = replaced.open;
        this.postId = replaced.postId;
        this.cloudKey = replaced.cloudKey;
        this.localId = replaced.localId;
        this.description = replaced.description;
        this.icon = replaced.icon;
    }

    private void bumpVersion() {
        version = VERSIONS.incrementAndGet();
        solveVersion = version;
    }

    /**
     * Records an edit the graph cannot see for itself that may change the solve: a machine setting,
     * a pinned machine count or a target rate, a drawer's rule, rate, kind or label, a machine
     * group's capacity or members, a node's recipe. Moves both {@link #version()} and
     * {@link #solveVersion()}. Edits made through the graph's own methods (nodes, edges, drawers,
     * links, balance mode, excess choice) already do this.
     */
    public void touch() {
        bumpVersion();
    }

    // The plan's own fields are edits like any other (a cloud plan sends what changed): each moves the version, never
    // the solve.

    public void setName(final String name) {
        if (java.util.Objects.equals(this.name, name)) return;
        this.name = name;
        touchLayout();
    }

    public void setDescription(final String description) {
        if (java.util.Objects.equals(this.description, description)) return;
        this.description = description;
        touchLayout();
    }

    public void setIcon(@Nullable final String icon) {
        if (java.util.Objects.equals(this.icon, icon)) return;
        this.icon = icon;
        touchLayout();
    }

    public void setPrimaryView(@Nullable final String primaryView) {
        if (java.util.Objects.equals(this.primaryView, primaryView)) return;
        this.primaryView = primaryView;
        touchLayout();
    }

    /**
     * Records a layout-only edit: something moved or was resized, a note's text changed. Moves
     * {@link #version()} but not {@link #solveVersion()}, so nothing is re-solved.
     */
    public void touchLayout() {
        version = VERSIONS.incrementAndGet();
    }

    /** @deprecated Use {@link #touch()} (or {@link #touchLayout()} for a layout-only edit). */
    @Deprecated
    public void markDirty() {
        touch();
    }

    public ChoiceKey getExcessChoice() {
        return Plan.getInstance()
            .getSummary()
            .getExcessChoice();
    }

    public void setExcessChoice(final ChoiceKey choice) {
        Plan.getInstance()
            .getSummary()
            .setExcessChoice(choice);
        bumpVersion();
    }

    public void setBalanceMode(final BalanceMode mode) {
        balanceMode = mode;
        bumpVersion();
    }

    /** Removes the node, every edge touching it and every drawer link to it. */
    public void removeNode(final UUID id) {
        nodes.remove(id);
        edges.values()
            .removeIf(e -> e.sourceNodeId.equals(id) || e.targetNodeId.equals(id));
        for (final Drawer drawer : drawers.values()) {
            drawer.removeLinksTo(id);
        }
        bumpVersion();
    }

    // ── Drawers ──

    public Collection<Drawer> getDrawers() {
        return drawers.values();
    }

    @Nullable
    public Drawer getDrawer(final UUID id) {
        return drawers.get(id);
    }

    /**
     * Adds (or replaces, by id) a drawer. Its links are taken as they are; a port another drawer of
     * the same direction already holds is taken away from that drawer, as {@link #linkDrawer} does.
     */
    public void addDrawer(final Drawer drawer) {
        drawers.put(drawer.getId(), drawer);
        final boolean input = drawer.getKind()
            .linksInputs();
        for (final Drawer.Link link : drawer.getLinks()) {
            releasePort(drawer, link, input);
        }
        for (final Drawer.Link link : drawer.getTakers()) {
            releasePort(drawer, link, true);
        }
        bumpVersion();
    }

    public void removeDrawer(final UUID id) {
        drawers.remove(id);
        bumpVersion();
    }

    /**
     * Takes a drawer off the plan as the board's delete does (the website's removeStorageAndHeal). A buffer with one
     * maker or one user leaves its makers wired straight to its users; several of each, all from one card to one card,
     * pair off in order; any other junction is a choice and heals nothing. A source or product on one port gives the
     * rate it asked for (at least or exactly: at most is a cap) back to that port, on a card with no pin or rate of its
     * own, so the card runs to it again.
     */
    public void removeDrawerAndHeal(final UUID id) {
        final Drawer drawer = drawers.get(id);
        if (drawer == null) return;
        final List<Drawer.Held> held = drawer.held();
        final List<Drawer.Link> makers = new ArrayList<>(), users = new ArrayList<>();
        for (final Drawer.Held h : held) (h.input() ? users : makers).add(h.link());
        removeDrawer(id);
        if (!makers.isEmpty() && !users.isEmpty()) {
            final List<Drawer.Link[]> pairs = new ArrayList<>();
            if (makers.size() == 1 || users.size() == 1) {
                for (final Drawer.Link m : makers)
                    for (final Drawer.Link u : users) pairs.add(new Drawer.Link[] { m, u });
            } else if (oneCard(makers) && oneCard(users)) {
                for (int i = 0; i < Math.min(makers.size(), users.size()); i++)
                    pairs.add(new Drawer.Link[] { makers.get(i), users.get(i) });
            }
            for (final Drawer.Link[] p : pairs) {
                final UUID from = p[0].nodeId(), to = p[1].nodeId();
                if (nodes.containsKey(from) && nodes.containsKey(to) && !from.equals(to))
                    addEdge(new Edge(UUID.randomUUID(), from, to, p[0].portIndex(), p[1].portIndex()));
            }
            return;
        }
        final Drawer.Rule rule = drawer.effectiveRule();
        if (held.size() != 1 || drawer.getKind() == Drawer.Kind.BUFFER
            || (rule != Drawer.Rule.AT_LEAST && rule != Drawer.Rule.EXACTLY)
            || !(drawer.getRate() > 0)) return;
        final Drawer.Held port = held.get(0);
        final Node node = nodes.get(
            port.link()
                .nodeId());
        if (node == null || node.isMachineCountFixed()
            || !node.targetOutputRates.isEmpty()
            || !node.targetInputRates.isEmpty()) return;
        (port.input() ? node.targetInputRates : node.targetOutputRates).put(
            port.link()
                .portIndex(),
            drawer.getRate());
        bumpVersion();
    }

    private static boolean oneCard(final List<Drawer.Link> links) {
        for (final Drawer.Link l : links) if (!l.nodeId()
            .equals(
                links.get(0)
                    .nodeId()))
            return false;
        return true;
    }

    /**
     * Attaches a node port to a drawer: an input port for a source, an output port otherwise. A port
     * belongs to at most one drawer per direction, so any other drawer holding it lets go. Returns
     * false (and changes nothing) when there is no such drawer.
     */
    public boolean linkDrawer(final UUID drawerId, final Drawer.Link link) {
        final Drawer drawer = drawers.get(drawerId);
        if (drawer == null) return false;
        final boolean input = drawer.getKind()
            .linksInputs();
        releasePort(drawer, link, input);
        drawer.addLink(link);
        bumpVersion();
        return true;
    }

    /**
     * Attaches a node port to a drawer on the port's own side: an output feeds it, an input draws from it. A product
     * given an input, or a source given an output, becomes a buffer ({@link Drawer#addPort}). Any other drawer holding
     * the port that way lets go. False when there is no such drawer or it takes no such port (trash an input).
     */
    public boolean linkDrawerPort(final UUID drawerId, final Drawer.Link link, final boolean input) {
        final Drawer drawer = drawers.get(drawerId);
        if (drawer == null || drawer.getKind() == Drawer.Kind.TRASH && input) return false;
        releasePort(drawer, link, input);
        drawer.addPort(link, input);
        bumpVersion();
        return true;
    }

    /** Detaches a node port from a drawer on the port's side; a buffer missing a side becomes an end again. */
    public boolean unlinkDrawerPort(final UUID drawerId, final Drawer.Link link, final boolean input) {
        final Drawer drawer = drawers.get(drawerId);
        if (drawer == null || !drawer.removePort(link, input)) return false;
        bumpVersion();
        return true;
    }

    /** Detaches a node port from a drawer; false when the drawer did not hold it. */
    public boolean unlinkDrawer(final UUID drawerId, final Drawer.Link link) {
        final Drawer drawer = drawers.get(drawerId);
        if (drawer == null || !drawer.removePort(
            link,
            drawer.getKind()
                .linksInputs()))
            return false;
        bumpVersion();
        return true;
    }

    /** The drawer holding a node port in the given direction, or null when the port has none. */
    @Nullable
    public Drawer drawerAt(final UUID nodeId, final int portIndex, final boolean input) {
        for (final Drawer drawer : drawers.values()) {
            if (drawer.holds(nodeId, portIndex, input)) return drawer;
        }
        return null;
    }

    /** Takes the port {@code link} names, on side {@code input}, away from every drawer but {@code keeper}. */
    private void releasePort(final Drawer keeper, final Drawer.Link link, final boolean input) {
        for (final Drawer other : drawers.values()) {
            if (other != keeper && other.holds(link.nodeId(), link.portIndex(), input)) other.removePort(link, input);
        }
    }

    /**
     * The balance, solved on the calling thread when the solve version moved since the last ask.
     * The legacy canvas reads this; the board solves through a {@code SolveService} instead.
     */
    public BalanceResult balance() {
        if (solvedAt != solveVersion) {
            Plan.getInstance()
                .getSummary()
                .recompute(this);
            solvedAt = solveVersion;
            boundaryView = null;
        }
        return Plan.getInstance()
            .getSummary()
            .balance();
    }

    /**
     * Everything crossing the chart's boundary. Held from solve to solve because the canvas asks
     * once per frame and the answer only moves when the chart does.
     */
    public List<BalanceView.Boundary> boundary() {
        balance(); // drops a view built before the last edit
        if (boundaryView == null) boundaryView = BalanceView.boundary(this);
        return boundaryView;
    }

    public Collection<Node> getNodes() {
        return nodes.values();
    }

    public void addNode(final Node node) {
        nodes.put(node.id, node);
        bumpVersion();
    }

    public Collection<Edge> getEdges() {
        return edges.values();
    }

    public void addEdge(final Edge edge) {
        // A source-port/target-port pair carries at most one edge: re-wiring it replaces the
        // existing edge instead of stacking a duplicate.
        edges.values()
            .removeIf(
                e -> e.sourceNodeId.equals(edge.sourceNodeId) && e.sourceOutputIndex == edge.sourceOutputIndex
                    && e.targetNodeId.equals(edge.targetNodeId)
                    && e.targetInputIndex == edge.targetInputIndex);
        edges.put(edge.id, edge);
        bumpVersion();
    }

    /**
     * Adds wires read from a save, in order, as {@link #addEdge} would one at a time (a pair of ports carries one wire:
     * a later one on the same pair replaces it), without looking through every wire for each: a plan of thousands of
     * wires took most of a minute to load.
     */
    public void addEdgesLoaded(final List<Edge> loaded) {
        final Map<String, UUID> byPorts = new HashMap<>();
        for (final Edge e : edges.values()) byPorts.put(ports(e), e.id);
        for (final Edge e : loaded) {
            final UUID was = byPorts.put(ports(e), e.id);
            if (was != null) edges.remove(was);
            edges.put(e.id, e);
        }
        bumpVersion();
    }

    private static String ports(final Edge e) {
        return e.sourceNodeId + ">" + e.sourceOutputIndex + ">" + e.targetNodeId + ">" + e.targetInputIndex;
    }

    /**
     * Adds drawers read from a save, in order, as {@link #addDrawer} would one at a time (a port belongs to at most one
     * drawer each way: a later drawer holding it takes it from an earlier one), without looking through every drawer
     * for each port.
     */
    public void addDrawersLoaded(final List<Drawer> loaded) {
        final Map<String, Drawer> holder = new HashMap<>();
        for (final Drawer d : drawers.values()) {
            for (final Drawer.Link l : d.getLinks()) holder.put(held(d.getKind(), l), d);
            for (final Drawer.Link l : d.getTakers()) holder.put(held(true, l), d);
        }
        for (final Drawer d : loaded) {
            drawers.put(d.getId(), d);
            final boolean input = d.getKind()
                .linksInputs();
            for (final Drawer.Link l : new ArrayList<>(d.getLinks())) {
                final Drawer other = holder.put(held(input, l), d);
                if (other != null && other != d) other.removePort(l, input);
            }
            for (final Drawer.Link l : new ArrayList<>(d.getTakers())) {
                final Drawer other = holder.put(held(true, l), d);
                if (other != null && other != d) other.removePort(l, true);
            }
        }
        bumpVersion();
    }

    /**
     * Links read from a plan, in order, as {@link #linkDrawerPort} would one at a time (a port belongs to at most one
     * drawer each way: a later link takes it from an earlier drawer), without looking through every drawer for each.
     * Each link is on its port's side: an input the drawer feeds, or an output that feeds it; a product given an input,
     * or a source an output, becomes a buffer.
     */
    public void linkDrawersLoaded(final List<LoadedLink> loaded) {
        if (loaded.isEmpty()) return;
        final Map<String, Drawer> holder = new HashMap<>();
        for (final Drawer d : drawers.values())
            for (final Drawer.Held h : d.held()) holder.put(held(h.input(), h.link()), d);
        final Set<Drawer> buffers = new HashSet<>();
        for (final LoadedLink e : loaded) {
            final Drawer d = drawers.get(e.drawer());
            if (d == null || !d.takesSide(e.input())) continue;
            final Drawer other = holder.put(held(e.input(), e.link()), d);
            if (other != null && other != d) other.removePort(e.link(), e.input());
            d.addPort(e.link(), e.input());
            if (d.isBuffer()) buffers.add(d);
        }
        // A buffer whose wires found one side only is the end it is: a product with no users, a source with no makers.
        for (final Drawer d : buffers) d.sanitize();
        bumpVersion();
    }

    /** A drawer link read from a plan: the drawer, the port, and the port's side ({@link #linkDrawersLoaded}). */
    public record LoadedLink(UUID drawer, Drawer.Link link, boolean input) {}

    private static String held(final Drawer.Kind kind, final Drawer.Link l) {
        return held(kind.linksInputs(), l);
    }

    private static String held(final boolean input, final Drawer.Link l) {
        return input + ">" + l.nodeId() + ">" + l.portIndex();
    }

    public void removeEdge(final UUID id) {
        edges.remove(id);
        bumpVersion();
    }

    /**
     * First input port of {@code dst} that accepts {@code src}'s given output; -1 when none is
     * compatible.
     */
    public int findCompatibleInput(final Node src, final int srcOutIdx, final Node dst) {
        if (src == dst || srcOutIdx < 0 || srcOutIdx >= src.outputs.size()) return -1;
        final Port<?> out = src.outputs.get(srcOutIdx);
        for (int i = 0; i < dst.inputs.size(); i++) {
            if (out.canConnect(dst.inputs.get(i))) return i;
        }
        return -1;
    }

    /** Removes a group; a machine group's capacity is a solve constraint, so this moves the version. */
    public void removeGroup(final UUID id) {
        groups.remove(id);
        bumpVersion();
    }

    public Collection<Group> getGroups() {
        return groups.values();
    }

    public Collection<Note> getNotes() {
        return notes.values();
    }
}
