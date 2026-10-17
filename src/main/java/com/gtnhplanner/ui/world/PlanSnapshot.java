package com.gtnhplanner.ui.world;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.ui.card.StructureArt;

/**
 * The plan as the board last drew it, for the views outside the planner (the minimap, the world): every card with its
 * machine, count, flows and linked blocks; every drawer; every wire's path. The board publishes a new one whenever its
 * models or routes change ({@link #publish}); readers take {@link #latest()}. Immutable, client thread only.
 *
 * @param graph        the plan it was taken of (compared by identity)
 * @param graphVersion the plan's version when taken: a different version now means it changed since
 * @param viewX        the board's view centre, world units
 * @param rateUnit     the unit the board shows rates in
 * @param pinList      the cards on the plan's pin list, in the order they were pinned
 */
public record PlanSnapshot(Graph graph, String planName, long graphVersion, List<Card> cards, List<Box> drawers,
    List<Line> wires, float viewX, float viewY, com.gtnhplanner.ui.theme.Fmt.RateUnit rateUnit, List<UUID> pinList,
    List<GroupBox> groups) {

    /** A plan with no groups. */
    public PlanSnapshot(final Graph graph, final String planName, final long graphVersion, final List<Card> cards,
        final List<Box> drawers, final List<Line> wires, final float viewX, final float viewY,
        final com.gtnhplanner.ui.theme.Fmt.RateUnit rateUnit, final List<UUID> pinList) {
        this(graph, planName, graphVersion, cards, drawers, wires, viewX, viewY, rateUnit, pinList, List.of());
    }

    /**
     * A group on show (docs/design/groups.md): an open one's frame, or a collapsed one's summary card, in its colour;
     * outer groups first.
     */
    public record GroupBox(UUID id, float x, float y, float w, float h, int color, boolean collapsed) {}

    /** What flows through a port or drawer, per second. */
    public record Flow(String name, @Nullable ItemStack item, @Nullable FluidStack fluid, boolean power,
        double perSecond, String key) {}

    /**
     * A card (a shared machine is one, its recipes' flows together), at its place on the board.
     *
     * @param art       its structure picture, or null for its item
     * @param machines  its count, as the card shows it: for a crop card the crops planted
     * @param links     its placements in the world, each {dimension, x, y, z}, then a facing and a size when set
     * @param needs     what its recipes ask of its structure, for its ghost
     * @param nodeIds   every recipe on it, the card's own first
     * @param builds    the machines it builds: its count, or a crop card's harvesters (CropCards.builds); what the pin
     *                  list, the minimap and the world count
     * @param crop      a crop card, whose count is crops planted
     * @param countless a card with no count to show: the fluid tank, which keeps up with whatever the plan needs
     */
    public record Card(UUID id, List<UUID> nodeIds, float x, float y, float w, float h, String name,
        @Nullable ItemStack machine, @Nullable StructureArt.Art art, int tint, double machines, boolean pinned,
        List<Flow> inputs, List<Flow> outputs, double euPerTick, double madeEuPerTick, List<int[]> links, String tier,
        int amps, @Nullable ItemStack circuit, List<Setting> settings,
        com.gtnhplanner.ui.gt.StructureGhosts.Needs needs, double builds, boolean crop, boolean countless) {}

    /**
     * A setting pinned to the card, as a chip ({@code ui/card/SettingControls}, {@code SettingPins}): the coil, a
     * machine setting, a power card's setting; {@code reading} for a power card's reading; {@code warn} when it stops
     * the recipe (a coil too cold).
     */
    public record Setting(String label, String value, @Nullable ItemStack icon, boolean warn, boolean reading) {}

    /** A drawer: its resource, kind (a source supplies, the rest take) and rate. */
    public record Box(UUID id, float x, float y, float w, float h, String label, @Nullable ItemStack item,
        @Nullable FluidStack fluid, boolean power, com.gtnhplanner.data.flowchart.Drawer.Kind kind, double rate) {

        public boolean source() {
            return kind == com.gtnhplanner.data.flowchart.Drawer.Kind.SOURCE;
        }

        public int tint() {
            return com.gtnhplanner.ui.drawer.DrawerPaint.tint(kind);
        }
    }

    /**
     * A wire: its route on the board, colour and width; {@code flowing} false for one nothing moves along.
     * {@code perSecond} is this wire's own share after the solve (items or litres a second), not the port's total, so
     * a split is not sized as if every branch carried all of it. Between two cards, {@code from} and {@code to} are
     * their recipes' nodes; a drawer's wire has neither.
     */
    public record Line(List<int[]> path, int color, float width, boolean flowing, String resource, @Nullable UUID from,
        @Nullable UUID to, double perSecond) {}

    private static volatile PlanSnapshot latest;
    /** Where the board's view was last centred, kept apart so panning the board needs no new snapshot. */
    private static volatile float lastViewX, lastViewY;

    public static void setView(final float x, final float y) {
        lastViewX = x;
        lastViewY = y;
    }

    public static float lastViewX() {
        return lastViewX;
    }

    public static float lastViewY() {
        return lastViewY;
    }

    public static void publish(final PlanSnapshot snapshot) {
        latest = snapshot;
    }

    /**
     * A plan's slot took a new graph (a shared plan read again): the last snapshot, if it was of the old one, is of the
     * new one now, so what the world edits goes to the plan; it shows the old drawing until the board draws again.
     */
    public static void regraph(final Graph old, final Graph next) {
        final PlanSnapshot s = latest;
        if (s == null || s.graph() != old) return;
        latest = new PlanSnapshot(
            next,
            s.planName(),
            s.graphVersion(),
            s.cards(),
            s.drawers(),
            s.wires(),
            s.viewX(),
            s.viewY(),
            s.rateUnit(),
            s.pinList());
    }

    @Nullable
    public static PlanSnapshot latest() {
        return latest;
    }

    /** The card holding a node (a recipe on a shared machine is on its host's card), or null. */
    @Nullable
    public Card cardOf(final UUID nodeId) {
        // Asked for every placed machine every tick: the snapshot's cards by node, made once per snapshot.
        if (indexed != this) {
            final java.util.Map<UUID, Card> by = new java.util.HashMap<>();
            for (final Card c : cards) for (final UUID id : c.nodeIds()) by.putIfAbsent(id, c);
            byNode = by;
            indexed = this;
        }
        return byNode.get(nodeId);
    }

    /** The snapshot {@link #byNode} was made for. */
    private static PlanSnapshot indexed;
    private static java.util.Map<UUID, Card> byNode;

    /**
     * Where in {@link #wires} the wires leaving a node are, in order; empty for none. Made once per snapshot: the world
     * asks for the wires among the machines near the player every frame.
     */
    public int[] wiresFrom(final UUID nodeId) {
        if (wiresIndexed != this) {
            final java.util.Map<UUID, List<Integer>> by = new java.util.HashMap<>();
            for (int i = 0; i < wires.size(); i++) {
                final UUID from = wires.get(i)
                    .from();
                if (from != null) by.computeIfAbsent(from, k -> new java.util.ArrayList<>())
                    .add(i);
            }
            final java.util.Map<UUID, int[]> made = new java.util.HashMap<>();
            for (final java.util.Map.Entry<UUID, List<Integer>> e : by.entrySet()) made.put(
                e.getKey(),
                e.getValue()
                    .stream()
                    .mapToInt(Integer::intValue)
                    .toArray());
            wiresByNode = made;
            wiresIndexed = this;
        }
        return wiresByNode.getOrDefault(nodeId, NO_WIRES);
    }

    private static final int[] NO_WIRES = {};
    private static PlanSnapshot wiresIndexed;
    private static java.util.Map<UUID, int[]> wiresByNode;
}
