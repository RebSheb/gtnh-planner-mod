package com.gtnhplanner.ui.world;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.opengl.GL11;

import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.ui.PlannerSettings;
import com.gtnhplanner.ui.theme.Hyb;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Cards' blocks in the world: outlined while they are being picked ({@link LinkPicker}); outlined with a beam and a
 * label for a while after "Show in the world"; and the placed block under the crosshair has its card rung on the
 * minimap. The plan over the world outlines that block itself; with it off, the world shows nothing of the plan.
 */
public final class WorldView {

    public static final WorldView INSTANCE = new WorldView();

    /** How far the crosshair looks for a linked block, in blocks: further than the arm reaches. */
    private static final double LOOK_RANGE = 64;
    private static final long SHOW_MILLIS = 30_000;

    /** The card shown with "Show in the world", until when; set from a menu, so the planner closes next tick. */
    @Nullable
    private Graph shownGraph;
    @Nullable
    private UUID shownId;
    private long shownUntil;
    private boolean closing;
    /** A line at the top of the screen for a few seconds (the shown card being somewhere else). */
    private String note = "";
    private int noteColor = Hyb.AMBER_INK;
    private long noteUntil;

    /** A line at the top of the screen for a few seconds, after an action that sends the player back to the world. */
    /** The note being shown, or null once it has gone. */
    @Nullable
    static String note() {
        return System.currentTimeMillis() < INSTANCE.noteUntil ? INSTANCE.note : null;
    }

    static int noteColor() {
        return INSTANCE.noteColor;
    }

    public static void say(final String text) {
        INSTANCE.note = text;
        INSTANCE.noteColor = Hyb.INK;
        INSTANCE.noteUntil = System.currentTimeMillis() + 4000;
    }

    /** The linked block under the crosshair and its card, found each tick. */
    @Nullable
    private WorldLinks.Hit look;
    private int lookX, lookY, lookZ;

    private WorldView() {}

    /** The placed spot under the crosshair this tick (a structure anywhere on it), or null. */
    @Nullable
    static WorldLinks.Spot looked() {
        final WorldLinks.Hit hit = INSTANCE.look;
        return hit == null ? null : new WorldLinks.Spot(hit, INSTANCE.lookX, INSTANCE.lookY, INSTANCE.lookZ, 0);
    }

    /** Outlines a card's blocks for a while, with beams to find them by; the planner closes. */
    public static void show(final Graph graph, final UUID nodeId) {
        INSTANCE.shownGraph = graph;
        INSTANCE.shownId = nodeId;
        INSTANCE.shownPlace = null;
        INSTANCE.shownUntil = System.currentTimeMillis() + SHOW_MILLIS;
        INSTANCE.closing = true;
    }

    @Nullable
    private Node shown() {
        if (shownGraph == null || System.currentTimeMillis() > shownUntil) return null;
        return WorldLinks.node(shownGraph, shownId);
    }

    /** A task's spot shown with "Show where it is", until when. */
    @Nullable
    private com.gtnhplanner.data.flowchart.Tasks.Place shownPlace;

    /** Every open task's unticked locations in this world and dimension, looked at again each second. */
    private java.util.List<com.gtnhplanner.data.flowchart.Tasks.Place> taskPlaces = java.util.List.of();
    private int taskScan;

    /**
     * Outlines a task's spot for a while, with a beam to find it by and its label and distance over it; the planner
     * closes. A spot in another dimension says so instead.
     */
    public static void show(final com.gtnhplanner.data.flowchart.Tasks.Place place) {
        // Pressing Show on the spot already shown puts it away again.
        INSTANCE.shownPlace = place.equals(INSTANCE.shownPlace) ? null : place;
        INSTANCE.shownGraph = null;
        INSTANCE.closing = true;
    }

    @Nullable
    private com.gtnhplanner.data.flowchart.Tasks.Place shownPlace() {
        return shownPlace;
    }

    /** Looks through the loaded plans for the unticked locations of tasks not done, in this world and dimension. */
    private static java.util.List<com.gtnhplanner.data.flowchart.Tasks.Place> scanPlaces(final int dim) {
        final com.gtnhplanner.data.flowchart.Plan plan = com.gtnhplanner.data.flowchart.Plan.loaded();
        if (plan == null) return java.util.List.of();
        final java.util.List<com.gtnhplanner.data.flowchart.Tasks.Place> out = new java.util.ArrayList<>();
        for (final Graph g : plan.getGraphs()) {
            final java.util.Set<String> done = new java.util.HashSet<>();
            for (final com.gtnhplanner.data.flowchart.Tasks.Task t : g.tasks)
                if (t.status() == com.gtnhplanner.data.flowchart.Tasks.Status.DONE) done.add(t.id());
            for (final com.gtnhplanner.data.flowchart.Tasks.Item i : g.taskItems) {
                final com.gtnhplanner.data.flowchart.Tasks.Place p = i.place();
                if (p == null || i.done()
                    || done.contains(i.taskId())
                    || p.dim() != dim
                    || !com.gtnhplanner.ui.task.TaskThings.inThisWorld(p)) continue;
                out.add(p);
            }
        }
        return out;
    }

    @SubscribeEvent
    public void onTick(final TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) {
            look = null;
            shownPlace = null;
            taskPlaces = java.util.List.of();
            return;
        }
        if (taskScan-- <= 0) {
            taskScan = 20;
            taskPlaces = PlannerSettings.taskShow() == PlannerSettings.TASK_NEVER ? java.util.List.of()
                : scanPlaces(mc.theWorld.provider.dimensionId);
        }
        // A spot shown with Show goes away once the player is there.
        final com.gtnhplanner.data.flowchart.Tasks.Place there = shownPlace;
        if (there != null && !closing
            && there.dim() == mc.theWorld.provider.dimensionId
            && WorldMarks.distance(there.x(), there.y(), there.z()) <= 4) shownPlace = null;
        if (closing) {
            closing = false;
            mc.displayGuiScreen(null);
            final Node n = shown();
            if (n != null && WorldLinks.countIn(n, mc.theWorld.provider.dimensionId) == 0) {
                note = "Its blocks are in another dimension";
                noteColor = Hyb.AMBER_INK;
                noteUntil = System.currentTimeMillis() + 4000;
            }
            final com.gtnhplanner.data.flowchart.Tasks.Place p = shownPlace();
            if (p != null && p.dim() != mc.theWorld.provider.dimensionId) {
                note = p.dimName()
                    .isEmpty() ? "That spot is in another dimension" : "That spot is in " + p.dimName();
                noteColor = Hyb.AMBER_INK;
                noteUntil = System.currentTimeMillis() + 4000;
            }
        }
        findLook(mc);
    }

    /** The linked block under the crosshair, further out than the arm reaches; and the minimap follows its card. */
    private void findLook(final Minecraft mc) {
        final WorldLinks.Hit was = look;
        look = null;
        if (!LinkPicker.active()) {
            final WorldLinks.Spot spot = WorldLinks.onRay(mc, LOOK_RANGE, null);
            if (spot != null) {
                look = spot.hit();
                lookX = spot.x();
                lookY = spot.y();
                lookZ = spot.z();
            }
        }
        final PlanSnapshot snap = PlanSnapshot.latest();
        if (look != null && snap != null && look.graph() == snap.graph()) Minimap.INSTANCE.focus(look.node().id);
        else if (was != null) Minimap.INSTANCE.focus(null);
    }

    @SubscribeEvent
    public void onRenderWorld(final RenderWorldLastEvent event) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) return;
        final int dim = mc.theWorld.provider.dimensionId;
        final Node picking = LinkPicker.active() ? LinkPicker.node() : null;
        final Node shown = shown();
        final com.gtnhplanner.data.flowchart.Tasks.Place place = shownPlace();
        final boolean placeHere = place != null && place.dim() == dim;
        final int mode = PlannerSettings.taskShow();
        final java.util.List<com.gtnhplanner.data.flowchart.Tasks.Place> spots = new java.util.ArrayList<>();
        if (mode == PlannerSettings.TASK_ALWAYS || mode == PlannerSettings.TASK_NEAR) {
            final double near = mode == PlannerSettings.TASK_NEAR ? PlannerSettings.taskDistance() : Double.MAX_VALUE;
            for (final com.gtnhplanner.data.flowchart.Tasks.Place t : taskPlaces)
                if (!t.equals(place) && WorldMarks.distance(t.x(), t.y(), t.z()) <= near) spots.add(t);
        }
        if (picking == null && shown == null && !placeHere && spots.isEmpty()) return;
        WorldMarks.begin();
        if (picking != null) {
            // Where the card would go: the machine's ghost in front of the face looked at, a multiblock standing on it.
            final MovingObjectPosition hit = LinkPicker.aim();
            if (hit != null) {
                final int[] at = LinkPicker.spot(hit);
                final net.minecraft.item.ItemStack machine = LinkPicker.machine();
                final com.gtnhplanner.ui.gt.StructureGhosts.Needs needs = LinkPicker.needs();
                final int size = LinkPicker.size(), facing = LinkPicker.facing();
                if (machine != null) {
                    WorldMarks.ghostDepth(machine, needs, size, at[0], at[1], at[2], facing);
                    WorldMarks.machineGhost(machine, needs, size, at[0], at[1], at[2], facing, false);
                }
                final double[] box = WorldMarks.extent(machine, needs, size, at[0], at[1], at[2], facing);
                WorldMarks.outline(box, Hyb.LIT & 0xFFFFFF, 0.004f, 1.5f, 0.6f);
                // Which way it faces, so R's turn shows on any machine.
                WorldMarks.front(box, facing, Hyb.GOLD & 0xFFFFFF, 1f);
            }
        }
        if (shown != null) {
            for (final int[] l : shown.worldLinks) {
                if (l[0] != dim) continue;
                WorldMarks.outline(WorldLinks.extent(shownGraph, shown, l), Hyb.LIT & 0xFFFFFF, 0.004f, 2f, 0.8f);
                WorldMarks.beam(l[1], l[2], l[3], Hyb.LIT & 0xFFFFFF);
            }
        }
        if (shown != null) labels(shown, dim);
        if (placeHere) spot(place, true);
        for (final com.gtnhplanner.data.flowchart.Tasks.Place t : spots) spot(t, false);
        WorldMarks.end();
    }

    /**
     * A task's location: outlined, with a beam and its name unless the settings turn them off (a shown one keeps both).
     */
    private static void spot(final com.gtnhplanner.data.flowchart.Tasks.Place place, final boolean shown) {
        final double[] box = { place.x(), place.y(), place.z(), place.x() + 1, place.y() + 1, place.z() + 1 };
        WorldMarks.outline(box, Hyb.SELECTION & 0xFFFFFF, 0.004f, 2f, 0.8f);
        // No beam while standing in it: it would wash the whole view in its colour.
        if ((shown || PlannerSettings.taskBeam()) && WorldMarks.distance(place.x(), place.y(), place.z()) > 4)
            WorldMarks.beam(place.x(), place.y(), place.z(), Hyb.SELECTION & 0xFFFFFF);
        if (shown || PlannerSettings.taskLabel()) label(
            place.label() != null ? place.label() : place.x() + " " + place.y() + " " + place.z(),
            place.x(),
            place.y(),
            place.z());
    }

    /** A name and its distance over a block, facing the camera. */
    private static void label(final String name, final int x, final int y, final int z) {
        final FontRenderer font = Hyb.font();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        final double d = WorldMarks.distance(x, y, z);
        final float scale = (float) (0.012 * Math.max(1, d / 10));
        WorldMarks.beginPanel(x + 0.5, y + 1.6 + scale * 12, z + 0.5, scale);
        final String far = Math.round(d) + " m";
        final int w = Math.max(font.getStringWidth(name), font.getStringWidth(far));
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        Hyb.rect(-w / 2f - 3, -2, w + 6, 21, 0xC0101114);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        font.drawString(name, -font.getStringWidth(name) / 2, 0, Hyb.INK);
        font.drawString(far, -font.getStringWidth(far) / 2, 10, Hyb.MUTED);
        WorldMarks.endPanel();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }

    /** The shown card's name and distance over each of its blocks. */
    private static void labels(final Node node, final int dim) {
        final String name = cardName(node);
        final FontRenderer font = Hyb.font();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        for (final int[] l : node.worldLinks) {
            if (l[0] != dim) continue;
            final double d = WorldMarks.distance(l[1], l[2], l[3]);
            final float scale = (float) (0.012 * Math.max(1, d / 10));
            WorldMarks.beginPanel(l[1] + 0.5, l[2] + 1.6 + scale * 12, l[3] + 0.5, scale);
            final String far = Math.round(d) + " m";
            final int w = Math.max(font.getStringWidth(name), font.getStringWidth(far));
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            Hyb.rect(-w / 2f - 3, -2, w + 6, 21, 0xC0101114);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            font.drawString(name, -font.getStringWidth(name) / 2, 0, Hyb.INK);
            font.drawString(far, -font.getStringWidth(far) / 2, 10, Hyb.MUTED);
            WorldMarks.endPanel();
        }
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }

    /** A card's machine name as the board shows it (its snapshot's), else its node's. */
    static String cardName(final Node node) {
        final PlanSnapshot snap = PlanSnapshot.latest();
        final PlanSnapshot.Card card = snap == null ? null : snap.cardOf(node.id);
        if (card != null && card.name() != null) return card.name();
        if (node.isPower()) {
            // Its source's name ("Fluid Tank"), never its id.
            final com.gtnhplanner.power.PowerSource source = com.gtnhplanner.power.PowerRegistry.get(node.powerSource);
            return source != null ? source.name() : node.powerSource;
        }
        // A card of a plan not open since: its machine is saved as an item key.
        if (node.machineName != null && node.machineName.startsWith("item:")) {
            final net.minecraft.item.ItemStack machine = com.gtnhplanner.ui.Resources.item(node.machineName);
            if (machine != null) return machine.getDisplayName();
        }
        return node.machineName != null ? node.machineName : "Machine";
    }

    /** At the top of the screen, a passing note. */
    @SubscribeEvent
    public void onOverlay(final RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || mc.gameSettings.hideGUI) return;
        final ScaledResolution sr = event.resolution;
        final float cx = sr.getScaledWidth() / 2f;
        // While the keys strip shows, the note is its last line instead (unless too long for it); while placing, it
        // goes
        // under the title.
        if (System.currentTimeMillis() < noteUntil && (!PlacementKeys.stripShown() || PlacementKeys.noteAtTop())) {
            final int[] title = LinkPicker.titleBounds();
            final float y = title == null ? 6 : title[1] + title[3] + 3;
            Hyb.rect(cx - Hyb.width(note) / 2f - 4, y, Hyb.width(note) + 8, 13, 0xE0141414);
            Hyb.textCentered(note, cx, y + 3, noteColor);
        }
        GL11.glColor4f(1, 1, 1, 1);
    }
}
