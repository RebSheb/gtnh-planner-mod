package com.gtnhplanner.ui.world;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.glu.GLU;

import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.logistics.PipeCatalog;
import com.gtnhplanner.logistics.PipeSelect;
import com.gtnhplanner.ui.PlannerSettings;
import com.gtnhplanner.ui.Resources;
import com.gtnhplanner.ui.card.RecipeCard;
import com.gtnhplanner.ui.theme.Hyb;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * The plan overlaid on the world: every card of the plan last open in the planner that has been placed on a spot is
 * drawn as the board draws it just above the ghost of its machine, never on it, a line down to it; the same size within
 * ten blocks and shrinking with distance beyond. A card is small and faint until you look at its machine (or the card),
 * then grows and comes up solid; with Focus on, only that one shows. Every ghost's edges always show. Cards move out of
 * each other's way, their lines pointing back to their machines. The plan's wires between placed cards are drawn flat
 * on the screen as the board draws them, along the pipe blocks of that wire when it has them, with arrowheads moving
 * towards the card fed and a tag naming what goes along, how much, and the pipe that can carry that wire's own share.
 * Nothing shows until a card is placed. What the crosshair is on is highlighted.
 */
public final class PlanOverlay {

    public static final PlanOverlay INSTANCE = new PlanOverlay();

    /**
     * Cards and wire tags are drawn at {@link #SIZE} of the board's size within this many blocks of the eye, and shrink
     * with distance beyond it as objects do.
     */
    private static final float FULL_SIZE_WITHIN = 10f, SIZE = 0.55f;

    /** The room kept between cards (and wire tags) on the screen, in GUI pixels. */
    private static final float CARD_GAP = 3;

    /** How far the crosshair reaches to a wire's line, in GUI pixels. */
    private static final float WIRE_REACH = 24;

    /** A placed card this frame: where its block is, and where and how big its card is on the screen. */
    private static final class Placed {

        final PlanSnapshot.Card card;
        final int x, y, z, facing, size;
        /** The card's first spot: the one its card stands over and its wires run from. */
        final boolean main;
        /** Where the machine being placed or moved would go now: the picker draws its ghost. */
        final boolean preview;
        /** Where the card stands: the middle of its spot, or of its structure. */
        final double[] home;
        /** What it fills in the world, its spot or its structure: {x0, y0, z0, x1, y1, z1}. */
        final double[] box;
        double distance;
        /** How much its card is looked at, 0 to 1 (eased): it grows and comes up solid as it is. */
        float a;
        /** Whether it fills much of the screen (close up): its card goes by the crosshair, over it, not around it. */
        boolean big;
        /** What its box covers on the screen {x0, y0, x1, y1}, or null when none of it is in front of the camera. */
        @Nullable
        float[] rect;
        /**
         * Where the card's centre would be on the screen, just above its machine; the middle of the machine's top (the
         * card's line ends there); its scale and size there; and where it is drawn, its top left, once moved out of the
         * way.
         */
        float sx, sy, bx, by, scale, w, h, left, top;

        /** Which of its card's placements it is, from 1 (its place in the card's list); 0 for the one being placed. */
        int nth;

        Placed(final PlanSnapshot.Card card, final int[] at, final boolean main) {
            this(card, at, main, false);
        }

        Placed(final PlanSnapshot.Card card, final int[] at, final boolean main, final boolean preview) {
            this.preview = preview;
            this.card = card;
            this.x = at[1];
            this.y = at[2];
            this.z = at[3];
            this.facing = WorldLinks.facing(at);
            this.size = WorldLinks.size(at);
            this.main = main;
            box = WorldMarks.extent(card.machine(), card.needs(), size, x, y, z, facing);
            home = new double[] { (box[0] + box[3]) / 2, (box[1] + box[4]) / 2, (box[2] + box[5]) / 2 };
        }

        boolean covers(final float px, final float py) {
            return px >= left && px < left + w && py >= top && py < top + h;
        }
    }

    @Nullable
    private UUID looked;
    /** The card the crosshair is on, its spot or the card itself, found on the screen last frame; drawn highlighted. */
    @Nullable
    private UUID lit;
    /** The placement the crosshair meets (its ghost), found with {@link #looked}. */
    @Nullable
    private Placed lookedAt;
    /**
     * The placement lit gold this frame, its card or its ghost under the crosshair, for the keys that act on a placed
     * machine; null when none is (or a wire is).
     */
    @Nullable
    private WorldLinks.Spot highlight;
    /** The wire under the crosshair, found on the screen last frame, and drawn lit. */
    @Nullable
    private Conn hovered;

    /**
     * A wire of the plan between two placed machines: its ends, what goes along it (from the card that makes it), and
     * this run's share of the card's rate when more than one of a 2× card is placed.
     */
    private record Conn(Placed from, Placed to, PlanSnapshot.Line line, @Nullable PlanSnapshot.Flow flow,
        double perSecond) {

        String what() {
            return flow != null ? flow.name() : "Wire";
        }

        boolean same(@Nullable final Conn o) {
            return o != null && o.from.x == from.x
                && o.from.y == from.y
                && o.from.z == from.z
                && o.to.x == to.x
                && o.to.y == to.y
                && o.to.z == to.z
                && o.line.resource()
                    .equals(line.resource());
        }
    }

    private final FloatBuffer modelview = BufferUtils.createFloatBuffer(16),
        projection = BufferUtils.createFloatBuffer(16);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private final FloatBuffer out = BufferUtils.createFloatBuffer(3);
    private double camX, camY, camZ;
    private boolean cameraKnown;

    private PlanOverlay() {}

    /** When the last timed part of a frame began, for the dev harness's perf ({@code call perf}). */
    private static long lapAt;

    /** Times the part since the last lap under {@code name} (none: only starts the clock), while perf is on. */
    private static void lap(@Nullable final String name) {
        if (!com.gtnhplanner.dev.DevPerf.on()) return;
        final long now = System.nanoTime();
        if (name != null) com.gtnhplanner.dev.DevPerf.time(name, now - lapAt);
        lapAt = now;
    }

    /** The placement lit gold by the plan over the world this frame, or null. */
    @Nullable
    static WorldLinks.Spot highlighted() {
        return INSTANCE.highlight;
    }

    public static boolean on() {
        return PlannerSettings.arLens();
    }

    /**
     * How solid a card is drawn (a touch see-through, so the world behind it still shows). A card shows only with Focus
     * on, for the machine you look at, and grows from {@link #POP_SIZE} of its size as it comes up.
     */
    private static final float SOLID = 0.85f, POP_SIZE = 0.6f;

    /**
     * How solid a wire is drawn, and with a card it joins looked at; its tag shows once one is looked at this much (or
     * the crosshair is on it).
     */
    private static final float WIRE_SOLID = 0.6f, TAG_FROM = 0.3f;

    /** The room kept between a card and its machine on the screen, in GUI pixels. */
    private static final float GHOST_GAP = 4;

    /** How solid a card is drawn, looked at {@code a}. */
    private static float solid(final float a) {
        return SOLID * a;
    }

    /** How solid a wire is drawn, its cards looked at {@code a}. */
    private static float wireSolid(final float a) {
        return WIRE_SOLID + (SOLID - WIRE_SOLID) * a;
    }

    /** A card's size, looked at {@code a}, as a share of its full size: it pops up as it comes in. */
    private static float sized(final float a) {
        return POP_SIZE + (1 - POP_SIZE) * a;
    }

    /** The cards placed in this dimension within range, nearest first. */
    private static List<Placed> placed(final Minecraft mc) {
        final List<Placed> all = new ArrayList<>();
        final PlanSnapshot snap = PlanSnapshot.latest();
        if (snap == null || mc.theWorld == null || mc.renderViewEntity == null) return all;
        final int dim = mc.theWorld.provider.dimensionId;
        final Node picking = LinkPicker.active() ? LinkPicker.node() : null;
        final int[] preview = picking == null ? null : LinkPicker.preview();
        final double range = PlannerSettings.arRange();
        final EntityLivingBase eye = mc.renderViewEntity;
        // The card in hand, every one of its placements as it is (one of them may be in hand); the rest from what is
        // near (the plan may place thousands of machines).
        final PlanSnapshot.Card held = picking == null ? null : snap.cardOf(picking.id);
        if (held != null) {
            final Node n = snap.graph().nodes.get(held.id());
            boolean first = true;
            // The machine in hand goes where it would be put down, card and wires with it, as dragging on the board.
            // Its place in the card's row is kept, so a 2× card still pipes first to first while it is carried.
            if (preview != null && n != null) {
                final Placed p = new Placed(held, preview, true, true);
                final int[] moving = LinkPicker.moving();
                p.nth = moving != null
                    ? Math.max(1, WorldLinks.indexOf(n, moving[0], moving[1], moving[2], moving[3]) + 1)
                    : LinkPicker.replacing() ? 1 : n.worldLinks.size() + 1;
                all.add(p);
                first = false;
            }
            for (int i = 0; n != null && i < n.worldLinks.size(); i++) {
                final int[] l = n.worldLinks.get(i);
                // Where the machine in hand was is empty while it is in hand.
                if (l[0] != dim || LinkPicker.hidden(l)) continue;
                final Placed p = new Placed(held, l, first);
                p.nth = i + 1;
                all.add(p);
                first = false;
            }
        }
        for (final WorldIndex.Entry e : WorldIndex.near(dim, eye.posX, eye.posZ, range)) {
            if (e.graph != snap.graph()) continue;
            final PlanSnapshot.Card c = snap.cardOf(e.node.id);
            if (c == null || c == held
                || !c.id()
                    .equals(e.node.id))
                continue;
            final Placed p = new Placed(c, e.link, e.first);
            p.nth = e.index + 1;
            all.add(p);
        }
        final List<Placed> list = new ArrayList<>();
        for (final Placed p : all) {
            // The near face, not the centre: a large multiblock beside the player stays in range, so its pipes still
            // run to the machines it feeds.
            p.distance = distanceToBox(p.box, eye.posX, eye.posY, eye.posZ);
            if (p.distance <= range) list.add(p);
        }
        list.sort(Comparator.comparingDouble(p -> p.distance));
        return list;
    }

    /** How far (x, y, z) is from a box {x0, y0, z0, x1, y1, z1}: 0 when the point is inside. */
    private static double distanceToBox(final double[] box, final double x, final double y, final double z) {
        final double dx = x < box[0] ? box[0] - x : x > box[3] ? x - box[3] : 0;
        final double dy = y < box[1] ? box[1] - y : y > box[4] ? y - box[4] : 0;
        final double dz = z < box[2] ? box[2] - z : z > box[5] ? z - box[5] : 0;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** The placed spot under the crosshair, as far as the overlay reaches. */
    @SubscribeEvent
    public void onTick(final TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        looked = null;
        lookedAt = null;
        final Minecraft mc = Minecraft.getMinecraft();
        if (!on() || mc.theWorld == null || mc.renderViewEntity == null || mc.currentScreen != null) return;
        // The spot the crosshair meets first, through blocks: spots are often empty, and the plan shows over the world.
        final net.minecraft.util.Vec3 from = mc.renderViewEntity.getPosition(1f), dir = mc.renderViewEntity.getLook(1f);
        double first = PlannerSettings.arRange();
        for (final Placed p : placed(mc)) {
            if (p.preview) continue;
            final double t = WorldLinks.enter(from, dir, p.box);
            if (t >= 0 && t < first) {
                first = t;
                looked = p.card.id();
                lookedAt = p;
            }
        }
    }

    // region The world: the camera, the connectors, the block looked at

    @SubscribeEvent
    public void onRenderWorld(final RenderWorldLastEvent event) {
        cameraKnown = false;
        final Minecraft mc = Minecraft.getMinecraft();
        if (!on() || PlanSnapshot.latest() == null || mc.theWorld == null) return;
        modelview.clear();
        projection.clear();
        viewport.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, modelview);
        GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, projection);
        GL11.glGetInteger(GL11.GL_VIEWPORT, viewport);
        camX = WorldMarks.cameraX();
        camY = WorldMarks.cameraY();
        camZ = WorldMarks.cameraZ();
        cameraKnown = true;
        final List<Placed> placed = placed(mc);
        if (placed.isEmpty()) return;
        lap(null);
        // Only what the camera can see: a plan placing hundreds of multiblocks has most of them behind it. Made from
        // the
        // camera's matrices as the world left them, before ours move them.
        final net.minecraft.client.renderer.culling.Frustrum frustum = new net.minecraft.client.renderer.culling.Frustrum();
        frustum.setPosition(camX, camY, camZ);
        WorldMarks.begin();
        // Each placed card's spot: the machine's ghost (unless the machine is built there), see-through, solid or not
        // shown as set; the one looked at outlined. Every multiblock's depth first, so each shows its outside alone and
        // nearer ghosts hide farther ones.
        final int look = PlannerSettings.arGhosts();
        final boolean shown = look != PlannerSettings.GHOSTS_HIDDEN, solid = look == PlannerSettings.GHOSTS_SOLID;
        final List<Placed> seen = new ArrayList<>(placed.size());
        for (final Placed p : placed) if (frustum.isBoundingBoxInFrustum(
            net.minecraft.util.AxisAlignedBB
                .getBoundingBox(p.box[0], p.box[1], p.box[2], p.box[3], p.box[4], p.box[5])))
            seen.add(p);
        if (shown) for (final Placed p : seen) if (!p.preview && p.card.machine() != null)
            WorldMarks.ghostDepth(p.card.machine(), p.card.needs(), p.size, p.x, p.y, p.z, p.facing);
        for (final Placed p : seen) {
            if (p.preview) continue;
            if (shown && p.card.machine() != null) WorldMarks
                .machineGhost(p.card.machine(), p.card.needs(), p.size, p.x, p.y, p.z, p.facing, built(mc, p), solid);
            // The one looked at outlined: what the keys act on.
            if (p.card.id()
                .equals(lit)) WorldMarks.outline(p.box, Hyb.LIT & 0xFFFFFF, 0.012f, 1.5f, 0.6f);
        }
        final PipeBlocks.Drawn pipes = pipes(placed);
        for (final PipeBlocks.Mark hatch : pipes.hatches())
            WorldMarks.outline(hatch.x(), hatch.y(), hatch.z(), Hyb.GOLD & 0xFFFFFF, 0.02f, 2.2f);
        for (final PipeBlocks.Mark pipe : pipes.pipes()) {
            if (pipe.stack() == null || pipeThere(mc, pipe)) continue;
            WorldMarks.ghostPipe(pipe.stack(), pipe.x(), pipe.y(), pipe.z(), pipe.connections(), pipe.thickness());
        }
        WorldMarks.end();
        lap("ar.ghosts");
    }

    /** The pipe blocks and hatches for the placed machines, from the wires between them. */
    private static PipeBlocks.Drawn pipes(final List<Placed> placed) {
        final PlanSnapshot snap = PlanSnapshot.latest();
        if (snap == null) return new PipeBlocks.Drawn(List.of(), List.of(), List.of());
        final List<PipeBlocks.Machine> machines = new ArrayList<>();
        final Map<Placed, Integer> index = new java.util.IdentityHashMap<>();
        for (final Placed p : placed) {
            if (p.preview) continue;
            index.put(p, machines.size());
            machines.add(
                new PipeBlocks.Machine(
                    p.x,
                    p.y,
                    p.z,
                    p.facing,
                    p.size,
                    p.card.machine(),
                    p.card.needs(),
                    p.home[0],
                    p.home[1],
                    p.home[2]));
        }
        final List<PipeBlocks.Link> links = new ArrayList<>();
        for (final Conn conn : conns(placed)) {
            if (!conn.line.flowing() || conn.perSecond() <= 0) continue;
            final Integer from = index.get(conn.from), to = index.get(conn.to);
            if (from == null || to == null) continue;
            final PlanSnapshot.Flow flow = conn.flow;
            int stack = com.gtnhplanner.logistics.PipeCatalog.STACK, temperature = 0;
            boolean gas = false;
            if (flow != null && flow.item() != null) stack = Math.max(
                1,
                flow.item()
                    .getMaxStackSize());
            if (flow != null && flow.fluid() != null
                && flow.fluid()
                    .getFluid() != null) {
                temperature = flow.fluid()
                    .getFluid()
                    .getTemperature(flow.fluid());
                gas = flow.fluid()
                    .getFluid()
                    .isGaseous(flow.fluid());
            }
            links.add(
                new PipeBlocks.Link(
                    from,
                    to,
                    conn.line.resource(),
                    conn.perSecond(),
                    com.gtnhplanner.ui.Resources.isFluid(conn.line.resource()),
                    stack,
                    temperature,
                    gas,
                    outputLayer(conn.from, conn.line.resource())));
        }
        return PipeBlocks.draw(machines, links, PlannerSettings.sharedPipes());
    }

    /**
     * A distillation tower's hatch layer for this fluid, from the recipe's fluid outputs in order (the controller and
     * items are layer 0). -1 when the machine is not a tower or the resource is not one of its fluids.
     */
    private static int outputLayer(final Placed from, final String resource) {
        if (!com.gtnhplanner.ui.Resources.isFluid(resource)
            || !com.gtnhplanner.ui.gt.StructureGhosts.layeredOutputs(from.card.machine())) return -1;
        final List<String> fluids = new ArrayList<>();
        for (final PlanSnapshot.Flow flow : from.card.outputs()) {
            if (flow.fluid() != null || com.gtnhplanner.ui.Resources.isFluid(flow.key())) fluids.add(flow.key());
        }
        return com.gtnhplanner.logistics.TowerLayers.layer(fluids, resource);
    }

    /** Whether this pipe block is already in the world, so its ghost steps aside. */
    private static boolean pipeThere(final Minecraft mc, final PipeBlocks.Mark pipe) {
        final net.minecraft.block.Block block = mc.theWorld.getBlock(pipe.x(), pipe.y(), pipe.z());
        if (block == null || block.isAir(mc.theWorld, pipe.x(), pipe.y(), pipe.z())) return false;
        return net.minecraft.item.Item.getItemFromBlock(block) == pipe.stack()
            .getItem() && mc.theWorld.getBlockMetadata(pipe.x(), pipe.y(), pipe.z())
                == pipe.stack()
                    .getItemDamage();
    }

    /** Whether the card's machine is built on its block already: the block there is the machine. */
    private static boolean built(final Minecraft mc, final Placed p) {
        final net.minecraft.block.Block block = mc.theWorld.getBlock(p.x, p.y, p.z);
        if (block == null || block.isAir(mc.theWorld, p.x, p.y, p.z)) return false;
        final net.minecraft.item.ItemStack here = LinkPicker.pickBlock(
            mc,
            new MovingObjectPosition(p.x, p.y, p.z, 1, net.minecraft.util.Vec3.createVectorHelper(p.x, p.y, p.z)));
        final net.minecraft.item.ItemStack machine = p.card.machine();
        return here != null && machine != null
            && here.getItem() == machine.getItem()
            && here.getItemDamage() == machine.getItemDamage();
    }

    /** Each wire of the plan between placed machines, one run per pairing of a 2× card. */
    private static List<Conn> conns(final List<Placed> placed) {
        final List<Conn> out = new ArrayList<>();
        final PlanSnapshot snap = PlanSnapshot.latest();
        if (snap == null) return out;
        final Map<UUID, List<Placed>> byNode = new HashMap<>();
        for (final Placed p : placed)
            for (final UUID id : p.card.nodeIds()) byNode.computeIfAbsent(id, key -> new ArrayList<>())
                .add(p);
        for (final List<Placed> list : byNode.values()) list.sort(Comparator.comparingInt(p -> p.nth));
        // Only the wires leaving a card near the player, in the plan's order (a plan has tens of thousands).
        final java.util.BitSet leaving = new java.util.BitSet();
        for (final UUID id : byNode.keySet()) for (final int i : snap.wiresFrom(id)) leaving.set(i);
        for (int i = leaving.nextSetBit(0); i >= 0; i = leaving.nextSetBit(i + 1)) {
            final PlanSnapshot.Line w = snap.wires()
                .get(i);
            if (w.from() == null || w.to() == null) continue;
            final List<Placed> froms = byNode.get(w.from()), tos = byNode.get(w.to());
            if (froms == null || tos == null) continue;
            // Placement order crosses a stack: the machine placed first on the left feeds the one placed first on
            // the right, even when that one stands at the bottom. Line them up by where they stand.
            align(froms, tos);
            PlanSnapshot.Flow flow = null;
            for (final PlanSnapshot.Flow f : froms.get(0).card.outputs()) if (f.key()
                .equals(w.resource())) flow = f;
            for (final com.gtnhplanner.logistics.PipePairs.Pair pair : com.gtnhplanner.logistics.PipePairs
                .pair(froms.size(), tos.size(), w.perSecond())) {
                final Placed a = froms.get(pair.from()), b = tos.get(pair.to());
                if (a == b) continue;
                out.add(new Conn(a, b, w, flow, pair.share()));
            }
        }
        return out;
    }

    /**
     * Put both rows in the same order along the way they both spread, so the upper machine feeds the upper one. Where
     * they stand is a tie, the order they were placed keeps its place.
     */
    private static void align(final List<Placed> froms, final List<Placed> tos) {
        final int axis = com.gtnhplanner.logistics.PipePairs.alignAxis(span(froms), span(tos));
        final Comparator<Placed> order = Comparator.comparingDouble((Placed p) -> p.home[axis])
            .thenComparingInt(p -> p.nth);
        froms.sort(order);
        tos.sort(order);
    }

    /** How far these machines stretch on x, y and z, centre to centre. */
    private static double[] span(final List<Placed> placed) {
        if (placed.isEmpty()) return new double[] { 0, 0, 0 };
        final double[] min = { Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY };
        final double[] max = { Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY };
        for (final Placed p : placed) for (int i = 0; i < 3; i++) {
            min[i] = Math.min(min[i], p.home[i]);
            max[i] = Math.max(max[i], p.home[i]);
        }
        return new double[] { max[0] - min[0], max[1] - min[1], max[2] - min[2] };
    }

    // endregion

    // region The cards

    /** Under the minimap, which goes over them. */
    @SubscribeEvent(priority = cpw.mods.fml.common.eventhandler.EventPriority.HIGH)
    public void onOverlay(final RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        Minimap.INSTANCE.point(null, null);
        highlight = null;
        if (!on() || !cameraKnown) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.gameSettings.hideGUI) return;
        final PlanSnapshot snap = PlanSnapshot.latest();
        if (snap == null) return;
        final ScaledResolution sr = event.resolution;
        lap(null);
        final List<Placed> all = placed(mc);
        final float cx = sr.getScaledWidth() / 2f, cy = sr.getScaledHeight() / 2f;
        // Where each machine is on the screen, and how much its card is looked at.
        final float screenArea = sr.getScaledWidth() * sr.getScaledHeight();
        for (final Placed p : all) {
            p.rect = screenBox(p.box, sr, mc);
            final float[] v = onScreen(p.rect, sr);
            p.big = v != null
                && ((v[2] - v[0]) * (v[3] - v[1]) > 0.22f * screenArea || v[3] - v[1] > 0.5f * sr.getScaledHeight());
        }
        final Map<UUID, Float> seen = attention(all);
        for (final Placed p : all) p.a = seen.getOrDefault(p.card.id(), 0f);
        // A card stands over one of its machines, the one the crosshair meets, else its first (the one in hand while
        // placing): the others show as ghosts.
        final Map<UUID, Placed> stand = new HashMap<>();
        for (final Placed p : all) if (p.main) stand.put(p.card.id(), p);
        if (lookedAt != null && !LinkPicker.active()) for (final Placed p : all) if (!p.preview && p.card.id()
            .equals(lookedAt.card.id()) && p.x == lookedAt.x && p.y == lookedAt.y && p.z == lookedAt.z)
            stand.put(p.card.id(), p);
        final List<Placed> shown = new ArrayList<>();
        for (final Placed p : all)
            if (stand.get(p.card.id()) == p && solid(p.a) > 0.01f && project(p, sr, mc)) shown.add(p);
        // The card looked at takes its place first, then the rest nearest first.
        shown.sort(
            Comparator.comparingInt((Placed p) -> p.a > 0.5f ? 0 : 1)
                .thenComparingDouble(p -> p.distance));
        // The plan's wires, flat on the screen as the board draws them, and the one the crosshair is on.
        // Each machine's label in its ghost, near to far, each clear of those before it; the card and the wire tags
        // keep off them.
        final List<float[]> labelBoxes = new ArrayList<>();
        final List<MachineLabel.Placement> labels = new ArrayList<>();
        if (PlannerSettings.arLabels()) for (final Placed p : all) if (!p.preview) {
            final MachineLabel.Placement l = label(p, sr, mc, labelBoxes);
            if (l != null) labels.add(l);
        }
        final List<Run> runs = runs(conns(all), pipes(all).routes(), seen, sr, mc, snap);
        layout(shown, labelBoxes, all, sr);
        // The wire tags last, clear of the labels, the cards and the panels in the corners.
        final List<float[]> taken = new ArrayList<>(labelBoxes);
        for (final Placed p : shown) taken.add(new float[] { p.left, p.top, p.left + p.w, p.top + p.h });
        // The machine looked at, and the one being placed, stay in view.
        for (final Placed p : all) if (p.rect != null && !p.big && (p.preview || p.a > 0.5f)) taken.add(p.rect);
        for (final int[] b : new int[][] { Minimap.bounds(sr), PinList.bounds(), PlacementKeys.stripBounds(),
            LinkPicker.titleBounds() }) if (b != null) taken.add(new float[] { b[0], b[1], b[0] + b[2], b[1] + b[3] });
        placeTags(runs, taken, sr, mc);
        lap("ar.prep+layout");
        // What the crosshair is on: a wire's tag, else the nearest card it is over, else a spot (its ghost), else the
        // wire whose line is within reach.
        Run near = null;
        for (final Run r : runs) if (r.tag && r.covers(cx, cy)) near = r;
        Placed over = null;
        if (near == null) for (final Placed p : shown) if (p.covers(cx, cy)) {
            over = p;
            break;
        }
        overCard = over == null ? null : over.card.id();
        // While a machine is in hand, it is the one lit, and nothing else is under the crosshair.
        final Node inHand = LinkPicker.active() ? LinkPicker.node() : null;
        if (inHand != null) {
            near = null;
            over = null;
        }
        final UUID card = inHand != null ? inHand.id : near != null ? null : over != null ? over.card.id() : looked;
        if (inHand == null && near == null && card == null) {
            float nearest = WIRE_REACH;
            for (final Run r : runs) {
                final float d = r.reach(cx, cy);
                if (d < nearest) {
                    nearest = d;
                    near = r;
                }
            }
        }
        hovered = near == null ? null : near.conn;
        lit = card;
        // What the keys act on: the card the crosshair is over, else the ghost it meets.
        final Placed target = inHand != null || near != null ? null
            : over != null ? over
                : lookedAt != null && lookedAt.card.id()
                    .equals(card) ? lookedAt : null;
        final Node targetNode = target == null || target.preview ? null : snap.graph().nodes.get(target.card.id());
        if (targetNode != null) highlight = new WorldLinks.Spot(
            new WorldLinks.Hit(snap.graph(), targetNode),
            target.x,
            target.y,
            target.z,
            target.distance);
        Minimap.INSTANCE.point(card, hovered == null ? null : hovered.line);
        Hyb.beginBatch();
        for (final Run r : runs) {
            Hyb.fadeOut = 1 - wireSolid(shown(r, seen));
            if (Hyb.fadeOut < 0.99f) wire(r, r.conn.same(hovered));
        }
        Hyb.fadeOut = 0;
        Hyb.endBatch();
        lap("ar.wires");
        for (final MachineLabel.Placement l : labels) MachineLabel.draw(l);
        lap("ar.labels");
        // Far to near, so nearer cards cover farther ones as objects do, the one looked at over the rest; the one the
        // crosshair is on over them all.
        Placed top = null;
        for (int i = shown.size() - 1; i >= 0; i--) {
            final Placed p = shown.get(i);
            if (p.card.id()
                .equals(lit)) top = p;
            else {
                Hyb.fadeOut = 1 - solid(p.a);
                card(p, snap, false);
            }
        }
        if (top != null) {
            Hyb.fadeOut = 1 - solid(top.a);
            card(top, snap, true);
        }
        lap("ar.cards");
        // Over the cards: what each wire carries, where a card it joins is looked at.
        final boolean always = PlannerSettings.arWireLabels() == PlannerSettings.WIRE_LABELS_ALWAYS;
        for (final Run r : runs) if (r.tag && !r.conn.same(hovered)) {
            Hyb.fadeOut = 1 - (always ? SOLID : solid(shown(r, seen)));
            if (Hyb.fadeOut < 0.99f) tag(r, false);
        }
        Hyb.fadeOut = 1 - SOLID;
        if (near != null && PlannerSettings.arWireLabels() != PlannerSettings.WIRE_LABELS_OFF) tag(near, true);
        Hyb.fadeOut = 0;
        lap("ar.tags");
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    // region Labels in the ghosts

    /**
     * A machine's label in the middle of its ghost on the screen ({@link MachineLabel}): as much as fits inside the
     * ghost's face, at no less than a letter pixel a screen pixel (the smallest that reads), clear of the nearer
     * machines' labels ({@code taken}).
     */
    @Nullable
    private MachineLabel.Placement label(final Placed p, final ScaledResolution sr, final Minecraft mc,
        final List<float[]> taken) {
        // The part of it on the screen: a machine close up is labelled where it can be seen.
        final float[] r = onScreen(p.rect, sr);
        if (r == null) return null;
        return MachineLabel.place(
            p.card,
            p.nth,
            (r[0] + r[2]) / 2,
            (r[1] + r[3]) / 2,
            (r[2] - r[0]) * 0.8f,
            (r[3] - r[1]) * 0.7f,
            1f / sr.getScaleFactor(),
            taken);
    }

    // endregion

    // region How much each card is looked at

    /** How quickly a card comes up or goes down: a share of the way a second. */
    private static final float EASE = 12;

    /** Each card's share looked at, eased from frame to frame; and when it was last eased. */
    private final Map<UUID, Float> attention = new HashMap<>();
    private long attentionAt;
    /** The card the crosshair was over last frame: a card being read stays up. */
    @Nullable
    private UUID overCard;

    /**
     * How much each card is looked at, by card, easing in and out: with Focus on, whole with the crosshair on its
     * machine (its ghost, through blocks) or on its card, else none; with Focus off, and while placing, none.
     */
    private Map<UUID, Float> attention(final List<Placed> all) {
        final long now = System.nanoTime();
        final float step = attentionAt == 0 ? 1 : Math.min(1, (now - attentionAt) / 1e9f * EASE);
        attentionAt = now;
        final boolean focus = PlannerSettings.arFocus() && !LinkPicker.active();
        final Map<UUID, Float> want = new HashMap<>();
        for (final Placed p : all) {
            final UUID id = p.card.id();
            final float w = focus && (id.equals(looked) || id.equals(overCard)) ? 1 : 0;
            want.merge(id, w, Math::max);
        }
        attention.keySet()
            .retainAll(want.keySet());
        for (final Map.Entry<UUID, Float> e : want.entrySet()) {
            final float was = attention.getOrDefault(e.getKey(), 0f);
            attention.put(e.getKey(), was + (e.getValue() - was) * step);
        }
        return attention;
    }

    /**
     * What a box covers on the screen {x0, y0, x1, y1}, from its corners in front of the camera; null when none are.
     */
    @Nullable
    private float[] screenBox(final double[] box, final ScaledResolution sr, final Minecraft mc) {
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        int corners = 0;
        for (int i = 0; i < 8; i++) {
            final float[] at = screen(
                (i & 1) == 0 ? box[0] : box[3],
                (i & 2) == 0 ? box[1] : box[4],
                (i & 4) == 0 ? box[2] : box[5],
                sr,
                mc);
            if (at == null) continue;
            corners++;
            x0 = Math.min(x0, at[0]);
            y0 = Math.min(y0, at[1]);
            x1 = Math.max(x1, at[0]);
            y1 = Math.max(y1, at[1]);
        }
        return corners == 0 ? null : new float[] { x0, y0, x1, y1 };
    }

    /** How much of a wire shows: as much as the more looked at of the two cards it joins. */
    private static float shown(final Run r, final Map<UUID, Float> seen) {
        return Math.max(seen.getOrDefault(r.conn.from().card.id(), 0f), seen.getOrDefault(r.conn.to().card.id(), 0f));
    }

    // endregion

    // region The wires

    /** A wire's tag, unscaled: its height, icon and the rate's size against the name's. Taller once it names a pipe. */
    private static final int TAG_H = 26, TAG_H_PIPE = 36, TAG_ICON = 20;

    private static final float TAG_RATE = 1.5f;

    /** Arrowheads along a wire: how far apart and how fast they move, in blocks and blocks a second. */
    private static final double ARROW_GAP = 0.8, ARROW_SPEED = 0.67;

    /** How far from either end of a wire its arrowheads fade, in blocks. */
    private static final double ARROW_FADE = 0.35;

    /**
     * A wire on the screen this frame: the segments of its run (straight, or along its pipe), its arrowheads
     * {x, y, direction x, direction y}, how big its tag is drawn, and the tag's text and box.
     */
    private static final class Run {

        final Conn conn;
        final float ax, ay, bx, by, scale;
        final List<float[]> segs, arrows;
        final String name, rate, pipe;
        float tx, ty, tw, th;
        /**
         * Where along the wire its tag can go: the wire's ends in the world, the share of it shown, and its lane's
         * step.
         */
        /** The part of its line on the screen where its tag can sit {x0, y0, x1, y1}, or null when none is. */
        @Nullable
        float[] vis;
        /**
         * Its tag's spot on its wire on the screen, the wire's way across the screen, and whether the tag is off it.
         */
        float anchorX, anchorY, ux = 1, uy;
        boolean leader;
        /** Whether its tag shows: a card it joins is looked at, or the crosshair is on it. */
        boolean tag;

        Run(final Conn conn, final float[] a, final float[] b, final List<float[]> segs, final List<float[]> arrows,
            final float scale, final String name, final String rate, final String pipe) {
            this.conn = conn;
            this.ax = a[0];
            this.ay = a[1];
            this.bx = b[0];
            this.by = b[1];
            this.segs = segs;
            this.arrows = arrows;
            this.scale = scale;
            this.name = name;
            this.rate = rate;
            this.pipe = pipe;
        }

        boolean covers(final float x, final float y) {
            return x >= tx && x < tx + tw && y >= ty && y < ty + th;
        }

        float reach(final float x, final float y) {
            float best = Float.MAX_VALUE;
            for (final float[] s : segs) best = Math.min(best, distance(x, y, s[0], s[1], s[2], s[3]));
            return segs.isEmpty() ? distance(x, y, ax, ay, bx, by) : best;
        }
    }

    /**
     * Each wire's run on the screen, its tag at its middle. Several wires between the same two cards run side by side
     * in
     * lanes, each tag beside its own lane on its outer side.
     */
    private List<Run> runs(final List<Conn> conns, final List<PipeBlocks.Route> routes, final Map<UUID, Float> seen,
        final ScaledResolution sr, final Minecraft mc, final PlanSnapshot snap) {
        final List<Run> out = new ArrayList<>();
        final Map<String, List<Conn>> pairs = new java.util.LinkedHashMap<>();
        for (final Conn c : conns) {
            pairs
                .computeIfAbsent(
                    c.from.x + "," + c.from.y + "," + c.from.z + ">" + c.to.x + "," + c.to.y + "," + c.to.z,
                    key -> new ArrayList<>())
                .add(c);
        }
        final double clock = System.currentTimeMillis() / 1000.0;
        for (final List<Conn> pair : pairs.values()) for (int i = 0; i < pair.size(); i++) {
            final Run r = run(pair.get(i), routes, i - (pair.size() - 1) / 2f, clock, sr, mc, snap);
            if (r == null) continue;
            // Its tag: always, for the machine looked at (or the wire under the crosshair), or never, as set.
            final int labels = PlannerSettings.arWireLabels();
            r.tag = labels == PlannerSettings.WIRE_LABELS_ALWAYS
                || labels == PlannerSettings.WIRE_LABELS_LOOKED && (shown(r, seen) > TAG_FROM || r.conn.same(hovered));
            if (r.vis == null) r.tag = false;
            out.add(r);
        }
        return out;
    }

    /**
     * Each wire tag's place, kept from frame to frame by its wire: where it is going {dx, dy} from its spot on its
     * wire,
     * and where it is drawn now {dx, dy}, so it glides when it moves.
     */
    private final Map<String, float[]> tagSpots = new HashMap<>();
    private long tagsAt;

    /**
     * Places the wire tags, nearest wires first, clear of everything already on the screen ({@code taken}, {x0, y0, x1,
     * y1}: the machines' labels, the card, the machine looked at, the minimap) and of each other. A tag stays on its
     * wire wherever along it there is room, the nearest such place to its spot first; only with no room anywhere on its
     * wire does it step just off it, a line back to it; with no clear place at all, the least covered. It keeps a clear
     * place it has rather than hopping, goes home as soon as home is clear, and glides between places.
     */
    private void placeTags(final List<Run> runs, final List<float[]> taken, final ScaledResolution sr,
        final Minecraft mc) {
        final long now = System.currentTimeMillis();
        final float ease = tagsAt == 0 ? 1 : (float) (1 - Math.exp(-(now - tagsAt) / 70.0));
        tagsAt = now;
        if (tagSpots.size() > 512) tagSpots.clear();
        final List<Run> order = new ArrayList<>();
        for (final Run r : runs) if (r.tag) order.add(r);
        order.sort(Comparator.comparingDouble((Run r) -> -r.scale));
        final List<float[]> boxes = new ArrayList<>(taken);
        for (final Run r : order) {
            // {target dx, dy, drawn dx, dy, target off its wire (1)}
            final float[] spot = tagSpots.computeIfAbsent(tagKey(r), k -> new float[5]);
            final float[] go = choose(r, spot, boxes, sr, mc);
            spot[0] = go[0];
            spot[1] = go[1];
            spot[4] = go[2];
            spot[2] += (spot[0] - spot[2]) * ease;
            spot[3] += (spot[1] - spot[3]) * ease;
            r.tx = r.anchorX + spot[2] - r.tw / 2;
            r.ty = r.anchorY + spot[3] - r.th / 2;
            // Off its wire by more than its own size across it: a line back to its spot.
            final float across = Math.abs(spot[2] * -r.uy + spot[3] * r.ux);
            r.leader = across > Math.abs(r.ux) * r.th / 2 + Math.abs(r.uy) * r.tw / 2 + 2;
            final float gx = r.anchorX + go[0] - r.tw / 2, gy = r.anchorY + go[1] - r.th / 2;
            boxes.add(new float[] { gx, gy, gx + r.tw, gy + r.th });
        }
    }

    /** Where a tag goes now, {dx, dy, off its wire (1) or not (0)}: see {@link #placeTags}. */
    private float[] choose(final Run r, final float[] spot, final List<float[]> boxes, final ScaledResolution sr,
        final Minecraft mc) {
        if (clearAt(r, 0, 0, boxes)) return new float[] { 0, 0, 0 };
        final boolean kept = (spot[0] != 0 || spot[1] != 0) && clearAt(r, spot[0], spot[1], boxes);
        // Along its wire, nearest its spot first.
        final List<float[]> on = alongWire(r, sr, mc);
        for (final float[] c : on) if (clearAt(r, c[0], c[1], boxes)) {
            // A clear place it has on its wire, unless this one is much nearer home.
            if (kept && spot[4] == 0 && Math.hypot(spot[0], spot[1]) < Math.hypot(c[0], c[1]) * 1.6f)
                return new float[] { spot[0], spot[1], 0 };
            return c;
        }
        if (kept) return new float[] { spot[0], spot[1], spot[4] };
        // Just off its wire, by its spot, either side.
        final float nx = -r.uy, ny = r.ux, reach = Math.abs(nx) * r.tw / 2 + Math.abs(ny) * r.th / 2 + 5;
        final List<float[]> off = new ArrayList<>();
        for (int m = 1; m <= 2; m++) for (final int side : new int[] { -1, 1 })
            off.add(new float[] { nx * side * reach * m, ny * side * reach * m, 1 });
        for (final float[] c : off) if (clearAt(r, c[0], c[1], boxes)) return c;
        // Nowhere clear: the least covered, on its wire before off it.
        float[] best = new float[] { spot[0], spot[1], spot[4] };
        float least = overlap(r, spot[0], spot[1], boxes);
        for (final List<float[]> list : List.of(on, off)) for (final float[] c : list) {
            final float o = overlap(r, c[0], c[1], boxes);
            if (o < least * 0.8f) {
                least = o;
                best = c;
            }
        }
        return best;
    }

    /** A wire tag's name across frames: the wire's ends and what it carries. */
    private static String tagKey(final Run r) {
        return r.conn.from.x + ","
            + r.conn.from.y
            + ","
            + r.conn.from.z
            + ">"
            + r.conn.to.x
            + ","
            + r.conn.to.y
            + ","
            + r.conn.to.z
            + "/"
            + r.conn.line.resource();
    }

    /** Places along a tag's wire, its shown part end to end, as offsets from its spot, nearest first. */
    private List<float[]> alongWire(final Run r, final ScaledResolution sr, final Minecraft mc) {
        final List<float[]> out = new ArrayList<>();
        if (r.vis == null) return out;
        final int steps = 16;
        for (int i = 0; i <= steps; i++) {
            final float f = 0.03f + 0.94f * i / steps;
            out.add(
                new float[] { r.vis[0] + (r.vis[2] - r.vis[0]) * f - r.anchorX,
                    r.vis[1] + (r.vis[3] - r.vis[1]) * f - r.anchorY, 0 });
        }
        out.sort(Comparator.comparingDouble(c -> Math.hypot(c[0], c[1])));
        return out;
    }

    /** Whether a tag moved by (dx, dy) from its spot keeps clear of every box. */
    private static boolean clearAt(final Run r, final float dx, final float dy, final List<float[]> boxes) {
        final float x0 = r.anchorX + dx - r.tw / 2, y0 = r.anchorY + dy - r.th / 2, x1 = x0 + r.tw, y1 = y0 + r.th;
        for (final float[] b : boxes)
            if (x0 < b[2] + 2 && b[0] < x1 + 2 && y0 < b[3] + 2 && b[1] < y1 + 2) return false;
        return true;
    }

    /** How much of a tag moved by (dx, dy) from its spot other boxes cover, in square pixels. */
    private static float overlap(final Run r, final float dx, final float dy, final List<float[]> boxes) {
        final float x0 = r.anchorX + dx - r.tw / 2, y0 = r.anchorY + dy - r.th / 2, x1 = x0 + r.tw, y1 = y0 + r.th;
        float sum = 0;
        for (final float[] b : boxes) {
            final float w = Math.min(x1 + 2, b[2]) - Math.max(x0 - 2, b[0]),
                h = Math.min(y1 + 2, b[3]) - Math.max(y0 - 2, b[1]);
            if (w > 0 && h > 0) sum += w * h;
        }
        return sum;
    }

    /**
     * One wire on the screen, {@code lane} lanes aside (on the same side whichever way it runs, so a pair's two
     * directions part), or null when none of it is in front of the camera. Its arrowheads are set out along it in the
     * world, so they keep their pace and spacing however you move.
     */
    @Nullable
    private Run run(final Conn c, final List<PipeBlocks.Route> routes, final float lane, final double clock,
        final ScaledResolution sr, final Minecraft mc, final PlanSnapshot snap) {
        final List<double[]> world = worldPath(c, routes);
        if (world.size() < 2) return null;
        final boolean piped = world.size() > 2 || routeOf(c, routes) != null;
        final double length = polyLength(world);
        if (length < 1e-6) return null;
        final double[] first = world.get(0), last = world.get(world.size() - 1);
        final double leave = piped ? 0 : blockEdge(last[0] - first[0], last[1] - first[1], last[2] - first[2]);
        if (!piped && leave * 2 >= 1) return null;
        final List<float[]> segs = new ArrayList<>();
        float[] sa = null, sb = null;
        for (int i = 1; i < world.size(); i++) {
            final double[] a = world.get(i - 1), b = world.get(i);
            final double t0 = !piped && i == 1 ? leave : 0, t1 = !piped && i == world.size() - 1 ? 1 - leave : 1;
            final double[] span = inFront(a, b, t0, t1);
            if (span == null) continue;
            final float[] pa = screen(along(a, b, span[0]), sr, mc), pb = screen(along(a, b, span[1]), sr, mc);
            if (pa == null || pb == null) continue;
            segs.add(new float[] { pa[0], pa[1], pb[0], pb[1] });
            if (sa == null) sa = pa;
            sb = pb;
        }
        if (sa == null || sb == null) return null;
        final double[] middle = atPoint(world, length / 2);
        final float[] mid = screen(middle, sr, mc);
        if (mid == null) return null;
        final double far = Math.sqrt(
            (middle[0] - camX) * (middle[0] - camX) + (middle[1] - camY) * (middle[1] - camY)
                + (middle[2] - camZ) * (middle[2] - camZ));
        final float scale = (float) (SIZE * Math.min(1, FULL_SIZE_WITHIN / far));
        final boolean forward = c.from.card.id()
            .compareTo(c.to.card.id()) < 0;
        final float ux = forward ? sb[0] - sa[0] : sa[0] - sb[0], uy = forward ? sb[1] - sa[1] : sa[1] - sb[1];
        final float run = (float) Math.hypot(ux, uy);
        final float step = run < 1 ? 0 : lane * (lineWidth(scale) + 2 + Math.max(3, 10 * scale)) / run;
        final float ox = -uy * step, oy = ux * step;
        for (final float[] s : segs) {
            s[0] += ox;
            s[1] += oy;
            s[2] += ox;
            s[3] += oy;
        }
        final List<float[]> arrows = new ArrayList<>();
        for (double at = (clock * ARROW_SPEED) % ARROW_GAP; at < length; at += ARROW_GAP) {
            final float fade = (float) Math.min(1, Math.min(at, length - at) / ARROW_FADE);
            if (fade <= 0) continue;
            final float[] p = screen(atPoint(world, at), sr, mc), q = screen(atPoint(world, at + 0.05), sr, mc);
            if (p == null || q == null) continue;
            final float ax = q[0] - p[0], ay = q[1] - p[1], al = (float) Math.hypot(ax, ay);
            if (al > 0) arrows.add(new float[] { p[0] + ox, p[1] + oy, ax / al, ay / al, fade });
        }
        final PlanSnapshot.Flow f = c.flow;
        final double perSecond = c.line.flowing() ? c.perSecond() : f == null ? 0 : f.perSecond();
        final boolean fluid = (f != null && f.fluid() != null) || Resources.isFluid(c.line.resource());
        final String rate = f == null ? ""
            : f.power() ? com.gtnhplanner.ui.theme.Fmt.power(perSecond / 20) + " EU/t"
                : com.gtnhplanner.ui.theme.Fmt.rate(perSecond, snap.rateUnit(), fluid);
        final String pipe = Hyb.fit(pipeOf(c, f, perSecond, snap.rateUnit()), 200);
        final Run r = new Run(
            c,
            new float[] { sa[0] + ox, sa[1] + oy },
            new float[] { sb[0] + ox, sb[1] + oy },
            segs,
            arrows,
            scale,
            Hyb.fit(c.what(), 120),
            rate,
            pipe);
        r.tw = (TAG_ICON + 9 + Math.max(Hyb.width(r.name), Math.max(Hyb.width(rate) * TAG_RATE, Hyb.width(pipe))) + 5)
            * scale;
        r.th = (pipe.isEmpty() ? TAG_H : TAG_H_PIPE) * scale;
        // On its own line, on the part of it on the screen (its own half size in from the edges), so it is plain which
        // wire it names and it stays where the wire can be seen; wires sharing a pair of cards have theirs staggered
        // along them, a fifth of it apart.
        final float hw = r.tw / 2 + 2, hh = r.th / 2 + 2;
        r.vis = clip(
            sa[0] + ox,
            sa[1] + oy,
            sb[0] + ox,
            sb[1] + oy,
            hw,
            hh,
            sr.getScaledWidth() - hw,
            sr.getScaledHeight() - hh);
        final float share = Math.max(0.12f, Math.min(0.88f, 0.5f + lane * 0.2f));
        final float cx = r.vis == null ? mid[0] + ox : r.vis[0] + (r.vis[2] - r.vis[0]) * share,
            cy = r.vis == null ? mid[1] + oy : r.vis[1] + (r.vis[3] - r.vis[1]) * share;
        r.tx = cx - r.tw / 2;
        r.ty = cy - r.th / 2;
        r.anchorX = cx;
        r.anchorY = cy;
        final float sl = (float) Math.hypot(sb[0] - sa[0], sb[1] - sa[1]);
        if (sl > 0) {
            r.ux = (sb[0] - sa[0]) / sl;
            r.uy = (sb[1] - sa[1]) / sl;
        }
        return r;
    }

    /** A segment cut to a box (Liang-Barsky): its part inside {x0, y0, x1, y1}, or null when none is. */
    @Nullable
    private static float[] clip(final float x0, final float y0, final float x1, final float y1, final float minX,
        final float minY, final float maxX, final float maxY) {
        float t0 = 0, t1 = 1;
        final float dx = x1 - x0, dy = y1 - y0;
        final float[] p = { -dx, dx, -dy, dy }, q = { x0 - minX, maxX - x0, y0 - minY, maxY - y0 };
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) return null;
                continue;
            }
            final float t = q[i] / p[i];
            if (p[i] < 0) {
                if (t > t1) return null;
                t0 = Math.max(t0, t);
            } else {
                if (t < t0) return null;
                t1 = Math.min(t1, t);
            }
        }
        return new float[] { x0 + dx * t0, y0 + dy * t0, x0 + dx * t1, y0 + dy * t1 };
    }

    /** The part of a box {x0, y0, x1, y1} on the screen, or null when none is. */
    @Nullable
    private static float[] onScreen(@Nullable final float[] r, final ScaledResolution sr) {
        if (r == null) return null;
        final float x0 = Math.max(0, r[0]), y0 = Math.max(0, r[1]), x1 = Math.min(sr.getScaledWidth(), r[2]),
            y1 = Math.min(sr.getScaledHeight(), r[3]);
        return x1 > x0 && y1 > y0 ? new float[] { x0, y0, x1, y1 } : null;
    }

    /** The pipe blocks of this wire, or a straight run from one machine to the other when it has none. */
    private List<double[]> worldPath(final Conn c, final List<PipeBlocks.Route> routes) {
        final PipeBlocks.Route route = routeOf(c, routes);
        if (route != null && route.cells()
            .size() >= 2) {
            final List<double[]> out = new ArrayList<>();
            for (final com.gtnhplanner.logistics.BlockRoute.Cell cell : route.cells())
                out.add(new double[] { cell.x() + 0.5, cell.y() + 0.5, cell.z() + 0.5 });
            return out;
        }
        final double[] start = nearest(c.from, c.to.home), to = nearest(c.to, start), from = nearest(c.from, to);
        return List.of(from, to);
    }

    @Nullable
    private static PipeBlocks.Route routeOf(final Conn c, final List<PipeBlocks.Route> routes) {
        for (final PipeBlocks.Route route : routes) {
            if (route.resource()
                .equals(c.line.resource()) && route.fromX() == c.from.x
                && route.fromY() == c.from.y
                && route.fromZ() == c.from.z
                && route.toX() == c.to.x
                && route.toY() == c.to.y
                && route.toZ() == c.to.z) return route;
        }
        return null;
    }

    private static double polyLength(final List<double[]> pts) {
        double n = 0;
        for (int i = 1; i < pts.size(); i++) n += dist(pts.get(i - 1), pts.get(i));
        return n;
    }

    private static double[] atPoint(final List<double[]> pts, final double dist) {
        if (pts.isEmpty()) return new double[] { 0, 0, 0 };
        double left = dist;
        for (int i = 1; i < pts.size(); i++) {
            final double step = dist(pts.get(i - 1), pts.get(i));
            if (left <= step || i == pts.size() - 1)
                return along(pts.get(i - 1), pts.get(i), step < 1e-9 ? 0 : Math.max(0, Math.min(1, left / step)));
            left -= step;
        }
        return pts.get(pts.size() - 1);
    }

    private static double dist(final double[] a, final double[] b) {
        final double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double[] along(final double[] a, final double[] b, final double t) {
        return new double[] { a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t };
    }

    /** Where a wire from end to end by (dx, dy, dz) leaves a block centred on its end, as a share of the wire. */
    private static double blockEdge(final double dx, final double dy, final double dz) {
        return 0.5 / Math.max(1e-9, Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))));
    }

    /** Each placement's blocks' middles in the world, by its structure and where it is; kept from frame to frame. */
    @Nullable
    private Map<String, double[][]> cells;

    /** The middle of a placement's block nearest a point: one of its structure's, or its own block. */
    private double[] nearest(final Placed p, final double[] to) {
        final double[][] all = cells(p);
        double[] best = all[0];
        double bestD = Double.MAX_VALUE;
        for (final double[] c : all) {
            final double dx = c[0] - to[0], dy = c[1] - to[1], dz = c[2] - to[2];
            final double d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }

    private double[][] cells(final Placed p) {
        if (cells == null || cells.size() > 512) cells = new HashMap<>();
        final com.gtnhplanner.ui.gt.StructureGhosts.Ghost g = com.gtnhplanner.ui.gt.StructureGhosts
            .peek(p.card.machine(), p.card.needs(), p.size);
        final String key = System.identityHashCode(g) + " " + p.x + " " + p.y + " " + p.z + " " + p.facing;
        final double[][] known = cells.get(key);
        if (known != null) return known;
        final List<int[]> blocks = g == null ? List.of() : com.gtnhplanner.ui.gt.StructureGhosts.blocks(g);
        final double[][] out;
        if (blocks.isEmpty()) out = new double[][] { { p.x + 0.5, p.y + 0.5, p.z + 0.5 } };
        else {
            out = new double[blocks.size()][];
            for (int i = 0; i < out.length; i++) {
                final int[] b = blocks.get(i);
                final int[] at = com.gtnhplanner.ui.gt.StructureGhosts.turn(b[0], b[2], p.facing);
                out[i] = new double[] { p.x + at[0] + 0.5, p.y + b[1] + 0.5, p.z + at[1] + 0.5 };
            }
        }
        cells.put(key, out);
        return out;
    }

    /**
     * The part of a segment's stretch from {@code t0} to {@code t1} (fractions of it) in front of the camera, or null
     * when none is.
     */
    @Nullable
    private double[] inFront(final double[] a, final double[] b, final double t0, final double t1) {
        final float near = 0.1f;
        final double za = eyeZ(a), zb = eyeZ(b);
        double lo = t0, hi = t1;
        final double slope = zb - za;
        if (slope == 0) return za < -near ? new double[] { lo, hi } : null;
        // Where the segment crosses the camera's near side; in front is the part going away from the camera.
        final double cross = (-near - za) / slope;
        if (slope > 0) hi = Math.min(hi, cross);
        else lo = Math.max(lo, cross);
        return lo < hi ? new double[] { lo, hi } : null;
    }

    /** How far in front of the camera a point is, negative in front (the camera looks down its -z). */
    private float eyeZ(final double[] p) {
        final float x = (float) (p[0] - camX), y = (float) (p[1] - camY), z = (float) (p[2] - camZ);
        return modelview.get(2) * x + modelview.get(6) * y + modelview.get(10) * z + modelview.get(14);
    }

    // region Making room

    /**
     * Each card's offset from its home just above its machine, in its own widths (right) and heights (up), kept from
     * frame to frame: where it is going {x, y} and where it is drawn now {x, y}.
     */
    private final Map<UUID, float[]> offsets = new HashMap<>();
    private long laidOut;

    /**
     * Moves cards out of each other's way, as map labels are: the one looked at first, then nearest first, each keeps
     * where it is (at home, just above its machine) while that is clear and otherwise takes the clear place nearest
     * home, aside, up or down past its machine, a line pointing back to it. Clear is on the screen and off the cards
     * already placed, the wires' tags, its own machine, and the machine being placed or looked at. A card that moved
     * goes home again once that is clear, and moves quickly rather than gliding.
     */
    private void layout(final List<Placed> shown, final List<float[]> labelBoxes, final List<Placed> all,
        final ScaledResolution sr) {
        final long now = System.currentTimeMillis();
        final float ease = laidOut == 0 ? 1 : (float) (1 - Math.exp(-(now - laidOut) / 50.0));
        laidOut = now;
        final float sw = sr.getScaledWidth(), sh = sr.getScaledHeight();
        final List<float[]> taken = new ArrayList<>();
        for (final float[] b : labelBoxes) taken.add(new float[] { b[0], b[1], b[2] - b[0], b[3] - b[1] });
        // The minimap is drawn over the cards: they keep out from under it.
        final int[] map = Minimap.bounds(sr);
        if (map != null) taken.add(new float[] { map[0], map[1], map[2], map[3] });
        final int[] pins = PinList.bounds();
        if (pins != null) taken.add(new float[] { pins[0], pins[1], pins[2], pins[3] });
        final int[] title = LinkPicker.titleBounds();
        if (title != null) taken.add(new float[] { title[0], title[1], title[2], title[3] });
        // No card covers the machine being placed or the one looked at (each card keeps off its own anyway).
        final List<Placed> keep = new ArrayList<>();
        for (final Placed p : all) if (p.rect != null && !p.big && (p.preview || p.a > 0.5f)) keep.add(p);
        for (final Placed p : shown) {
            final float[] own = p.big ? new float[] { -1e6f, -1e6f, 0, 0 } : around(p.rect);
            final List<float[]> others = new ArrayList<>(taken);
            for (final Placed k : keep) if (k != p) others.add(around(k.rect));
            final float[] o = offsets.computeIfAbsent(p.card.id(), id -> new float[4]);
            float bestX = o[0], bestY = o[1];
            final boolean clear = clear(p, bestX, bestY, others, own, sw, sh);
            final float now0 = cost(p, bestX, bestY);
            if (!clear || now0 > 0) {
                // A clear place, nearest home first; where it is now is clear too, only one much nearer home. Far
                // enough aside and down to pass its machine, in steps that keep the search small.
                float best = clear ? now0 * 0.6f : Float.MAX_VALUE;
                final float across = 1.5f + own[2] / p.w / 2, down = 1.5f + own[3] / p.h;
                final float stepX = Math.max(0.125f, across / 20), stepY = Math.max(0.25f, (down + 1.5f) / 28);
                for (int j = -(int) (down / stepY); j * stepY <= 1.5f; j++)
                    for (int i = -(int) (across / stepX); i * stepX <= across; i++) {
                        final float dx = i * stepX, dy = j * stepY, c = cost(p, dx, dy);
                        if (c < best && clear(p, dx, dy, others, own, sw, sh)) {
                            best = c;
                            bestX = dx;
                            bestY = dy;
                        }
                    }
            }
            o[0] = bestX;
            o[1] = bestY;
            o[2] += (o[0] - o[2]) * ease;
            o[3] += (o[1] - o[3]) * ease;
            // Kept on the screen, whatever happens.
            p.left = Math.max(2, Math.min(sw - 2 - p.w, p.sx + o[2] * p.w - p.w / 2));
            p.top = Math.max(2, Math.min(sh - 2 - p.h, p.sy - o[3] * p.h - p.h / 2));
            taken.add(new float[] { p.sx + o[0] * p.w - p.w / 2, p.sy - o[1] * p.h - p.h / 2, p.w, p.h });
        }
    }

    /** A machine's rectangle on the screen {x0, y0, x1, y1} with room around it, as {x, y, w, h}. */
    private static float[] around(final float[] r) {
        return new float[] { r[0] - GHOST_GAP, r[1] - GHOST_GAP, r[2] - r[0] + 2 * GHOST_GAP,
            r[3] - r[1] + 2 * GHOST_GAP };
    }

    /**
     * How far from home an offset takes a card, on the screen; going up costs a little more than going aside, and down
     * more again.
     */
    private static float cost(final Placed p, final float dx, final float dy) {
        return Math.abs(dx) * p.w + (dy >= 0 ? 1.15f : 1.4f) * Math.abs(dy) * p.h;
    }

    /**
     * Whether a card at an offset is on the screen (where it fits) and keeps clear of everything placed and of its own
     * machine.
     */
    private static boolean clear(final Placed p, final float dx, final float dy, final List<float[]> taken,
        final float[] own, final float sw, final float sh) {
        final float x = p.sx + dx * p.w - p.w / 2, y = p.sy - dy * p.h - p.h / 2;
        if (p.w < sw - 4 && (x < 2 || x + p.w > sw - 2) || p.h < sh - 4 && (y < 2 || y + p.h > sh - 2)) return false;
        // Its own machine, a touch inside its margin, so home (touching it) counts as clear.
        if (x < own[0] + own[2] - 0.5f && own[0] + 0.5f < x + p.w
            && y < own[1] + own[3] - 0.5f
            && own[1] + 0.5f < y + p.h) return false;
        final float gx = x - CARD_GAP, gy = y - CARD_GAP, w = p.w + 2 * CARD_GAP, h = p.h + 2 * CARD_GAP;
        for (final float[] t : taken)
            if (gx < t[0] + t[2] && t[0] < gx + w && gy < t[1] + t[3] && t[1] < gy + h) return false;
        return true;
    }

    // endregion

    /**
     * A card where the layout put it, off its machine, with a line from its nearest edge to the middle of the machine's
     * top, ending in a small pin.
     */
    private static void card(final Placed p, final PlanSnapshot snap, final boolean ringed) {
        final boolean home = p.bx >= p.left && p.bx < p.left + p.w && p.by >= p.top && p.by < p.top + p.h;
        if (!home) {
            final int line = ringed ? 0x90000000 | Hyb.LIT & 0xFFFFFF : 0x90A4A8B0;
            final float ex = Math.max(p.left, Math.min(p.left + p.w, p.bx)),
                ey = Math.max(p.top, Math.min(p.top + p.h, p.by));
            band(ex, ey, p.bx, p.by, 2, line);
            Hyb.rect(p.bx - 2, p.by - 2, 4, 4, line);
        }
        GL11.glPushMatrix();
        GL11.glTranslatef(Math.round(p.left), Math.round(p.top), 0);
        GL11.glScalef(p.scale, p.scale, 1);
        com.gtnhplanner.ui.card.CleanCardView.draw(p.card, snap.rateUnit(), ringed);
        GL11.glPopMatrix();
    }

    /** A point's distance from a segment on the screen. */
    private static float distance(final float px, final float py, final float ax, final float ay, final float bx,
        final float by) {
        final float dx = bx - ax, dy = by - ay;
        final float len2 = dx * dx + dy * dy;
        final float t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2));
        return (float) Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    /**
     * A wire as the board draws one, in the resource's colour on a dark edge, with arrowheads sliding along towards the
     * card it feeds; faint where nothing flows yet; edged in the highlight when the crosshair is on it.
     */
    private static void wire(final Run r, final boolean lit) {
        if (r.segs.isEmpty() && Math.hypot(r.bx - r.ax, r.by - r.ay) < 4) return;
        final int colour = 0xFF000000 | r.conn.line.color() & 0xFFFFFF;
        final boolean flowing = r.conn.line.flowing();
        final float w = lineWidth(r.scale), arrow = arrowWidth(r.scale);
        final int edge = lit ? Hyb.LIT : flowing ? 0xB0000000 : 0x70000000;
        final int fill = flowing || lit ? colour : colour & 0x00FFFFFF | 0x99000000;
        final List<float[]> bands = r.segs.isEmpty() ? List.of(new float[] { r.ax, r.ay, r.bx, r.by }) : r.segs;
        for (final float[] s : bands) {
            if (Math.hypot(s[2] - s[0], s[3] - s[1]) < 1) continue;
            band(s[0], s[1], s[2], s[3], w + (lit ? 3 : 2), edge);
            band(s[0], s[1], s[2], s[3], w, fill);
        }
        // The arrowheads, set out along the wire in the world.
        final float head = 5.6f * arrow, half = 3f * arrow;
        final int tip = Hyb.mix(colour, 0xFFFFFFFF, 0.3f);
        for (final float[] a : r.arrows) {
            final float px = a[0], py = a[1], ux = a[2], uy = a[3], fade = a[4];
            final float fx = px + ux * head * 0.6f, fy = py + uy * head * 0.6f;
            final float bx = px - ux * head * 0.4f, by = py - uy * head * 0.4f;
            Hyb.triangle(
                fx + ux,
                fy + uy,
                bx - uy * (half + 1),
                by + ux * (half + 1),
                bx + uy * (half + 1),
                by - ux * (half + 1),
                faded(lit ? Hyb.LIT : 0xB0000000, fade));
            Hyb.triangle(fx, fy, bx - uy * half, by + ux * half, bx + uy * half, by - ux * half, faded(tip, fade));
        }
    }

    /** The size arrowheads are measured in, on the screen, at a scale: they stop growing early. */
    private static float arrowWidth(final float scale) {
        return Math.max(1.1f, Math.min(1.5f, 3f * scale));
    }

    /** How thick a wire is drawn on the screen at a scale. */
    private static float lineWidth(final float scale) {
        return 2 * Math.max(1.2f, 3f * scale);
    }

    /** A colour at a share of its own opacity. */
    private static int faded(final int argb, final float share) {
        return (int) ((argb >>> 24) * share) << 24 | argb & 0xFFFFFF;
    }

    /** A straight band {@code w} wide from one point to another. */
    private static void band(final float ax, final float ay, final float bx, final float by, final float w,
        final int colour) {
        final float len = (float) Math.hypot(bx - ax, by - ay), h = w / 2;
        final float nx = -(by - ay) / len * h, ny = (bx - ax) / len * h;
        Hyb.triangle(ax + nx, ay + ny, bx + nx, by + ny, bx - nx, by - ny, colour);
        Hyb.triangle(ax + nx, ay + ny, bx - nx, by - ny, ax - nx, ay - ny, colour);
    }

    /**
     * What a wire carries, at its middle: the item or fluid, how much in large type, its name small under it, and the
     * pipe that covers that rate in gold. On a plain soft dark backing. The wire the crosshair is on is ringed in the
     * highlight.
     */
    private static void tag(final Run r, final boolean lit) {
        // Off its wire: a line from its spot on the wire to its nearest edge, in the wire's colour, a dot on the wire.
        if (r.leader) {
            final int wire = 0xFF000000 | r.conn.line.color() & 0xFFFFFF;
            final float px = Math.max(r.tx, Math.min(r.tx + r.tw, r.anchorX)),
                py = Math.max(r.ty, Math.min(r.ty + r.th, r.anchorY));
            if (Math.hypot(px - r.anchorX, py - r.anchorY) > 1) band(r.anchorX, r.anchorY, px, py, 1.5f, wire);
            Hyb.rect(r.anchorX - 1.5f, r.anchorY - 1.5f, 3, 3, wire);
        }
        GL11.glPushMatrix();
        GL11.glTranslatef(r.tx, r.ty, 0);
        GL11.glScalef(r.scale, r.scale, 1);
        final int w = Math.round(r.tw / r.scale), h = r.pipe.isEmpty() ? TAG_H : TAG_H_PIPE;
        // In its wire's colours: a backing tinted with it and an edge of it (the highlight's when lit).
        final int wire = 0xFF000000 | r.conn.line.color() & 0xFFFFFF;
        // Nearly black for the text, only a hint of the wire in it; the edge says which wire.
        Hyb.rect(0, 0, w, h, 0xE8000000 | Hyb.mix(0xFF141518, wire, 0.88f) & 0xFFFFFF);
        // The edge alone, so none of its colour shows through the backing.
        Hyb.ring(-1, -1, w + 2, h + 2, 1, lit ? 0xE0000000 | Hyb.LIT & 0xFFFFFF : wire);
        final PlanSnapshot.Flow f = r.conn.flow;
        if (f != null) {
            if (f.power()) RecipeCard.euIcon(4, 3, TAG_ICON);
            // An item from the icon atlas, one quad, not drawn as an item every frame.
            else if (f.item() == null || !com.gtnhplanner.ui.theme.IconShadows.icon(f.item(), 4, 3, TAG_ICON))
                Hyb.icon(f.item(), f.fluid(), 4, 3, TAG_ICON, 0);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        }
        final int text = 4 + TAG_ICON + 5;
        // No text shadows on the dark backing: each would draw the text twice, every frame.
        Hyb.text(r.rate, text, 3, TAG_RATE, Hyb.INK, false);
        Hyb.text(r.name, text, 3 + 8 * TAG_RATE + 2, 1f, 0xFFB8BBC4, false);
        if (!r.pipe.isEmpty()) Hyb.text(r.pipe, text, 3 + 8 * TAG_RATE + 2 + 9, 1f, Hyb.GOLD, false);
        GL11.glPopMatrix();
    }

    /**
     * The pipe for this wire's own share: the smallest item or fluid pipe whose tooltip covers it. EU stays a bolt.
     * Nothing is named until the solve has put a rate on the wire. Heat and gas come from the fluid, when the pack
     * has one; a stack that will not stack to 64 needs a fatter item pipe.
     */
    private static String pipeOf(final Conn c, @Nullable final PlanSnapshot.Flow f, final double perSecond,
        final com.gtnhplanner.ui.theme.Fmt.RateUnit unit) {
        if (!c.line.flowing() || perSecond <= 0) return "";
        final String key = c.line.resource();
        final boolean fluid = Resources.isFluid(key);
        final boolean item = key.startsWith("item:");
        if (!fluid && !item) return "";
        int temperature = 0;
        boolean gas = false;
        int stack = PipeCatalog.STACK;
        if (f != null && f.fluid() != null
            && f.fluid()
                .getFluid() != null) {
            temperature = f.fluid()
                .getFluid()
                .getTemperature(f.fluid());
            gas = f.fluid()
                .getFluid()
                .isGaseous(f.fluid());
        } else if (f != null && f.item() != null) {
            stack = Math.max(
                1,
                f.item()
                    .getMaxStackSize());
        }
        final PipeSelect.Choice choice = PipeSelect
            .choose(fluid ? PipeCatalog.Kind.FLUID : PipeCatalog.Kind.ITEM, perSecond, stack, temperature, gas);
        return choice == null ? "" : choice.line(unit, stack);
    }

    // endregion

    /**
     * A card's home on the GUI, just above its machine (never on it: the machine stays in view), where its line ends
     * (the middle of the machine's top), and its scale: {@link #SIZE} within {@link #FULL_SIZE_WITHIN} blocks,
     * shrinking with distance beyond, and smaller as it pops up. False when its machine is off the screen.
     */
    private boolean project(final Placed p, final ScaledResolution sr, final Minecraft mc) {
        final float[] r = p.rect;
        if (r == null || r[2] < 0 || r[0] > sr.getScaledWidth() || r[3] < 0 || r[1] > sr.getScaledHeight())
            return false;
        final float[] top = screen(p.home[0], p.box[4], p.home[2], sr, mc);
        p.bx = top != null ? top[0] : (r[0] + r[2]) / 2;
        p.by = top != null ? top[1] : r[1];
        p.scale = (float) (SIZE * Math.min(1, FULL_SIZE_WITHIN / Math.max(0.5, p.distance))) * sized(p.a);
        p.w = com.gtnhplanner.ui.card.CleanCardView.width() * p.scale;
        p.h = com.gtnhplanner.ui.card.CleanCardView.height(p.card) * p.scale;
        if (p.big) {
            // Close up, the machine fills the view: the card stands by the crosshair, up and to the right, over it.
            final float cx = sr.getScaledWidth() / 2f, cy = sr.getScaledHeight() / 2f;
            p.sx = p.bx = cx + 16 + p.w / 2;
            p.sy = p.by = cy - 16 - p.h / 2;
            return p.scale > 0.02f;
        }
        p.sx = Math.max(r[0], Math.min(r[2], p.bx));
        p.sy = r[1] - GHOST_GAP - p.h / 2;
        return p.scale > 0.02f;
    }

    @Nullable
    private float[] screen(final double[] p, final ScaledResolution sr, final Minecraft mc) {
        return screen(p[0], p[1], p[2], sr, mc);
    }

    @Nullable
    private float[] screen(final double x, final double y, final double z, final ScaledResolution sr,
        final Minecraft mc) {
        out.clear();
        if (!GLU.gluProject(
            (float) (x - camX),
            (float) (y - camY),
            (float) (z - camZ),
            modelview,
            projection,
            viewport,
            out)) return null;
        final float wz = out.get(2);
        if (wz <= 0 || wz >= 1) return null;
        final int f = sr.getScaleFactor();
        return new float[] { out.get(0) / f, (mc.displayHeight - out.get(1)) / f };
    }

    // endregion
}
