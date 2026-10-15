package com.gtnhplanner.ui.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import org.lwjgl.opengl.GL11;

import com.gtnhplanner.ui.PlannerSettings;
import com.gtnhplanner.ui.card.RecipeCard;
import com.gtnhplanner.ui.theme.Hyb;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * The pin list: the machines pinned in the overview (its pin keys), listed in a corner of the screen while playing, as
 * the overview lists them: the machine, its tier and how many to build. Drawn from the {@link PlanSnapshot} of the plan
 * last open, so it shows what the minimap shows. Shares a corner with the minimap by standing beside it.
 */
public final class PinList {

    /** One line: a pinned machine, and how many of it the pinned cards build at each tier, highest first. */
    record Line(String name, @Nullable ItemStack icon, List<Tiered> tiers) {}

    /** How many of a machine at a tier ("" for a machine without one). */
    record Tiered(String tier, int count) {}

    /** Where the list was last drawn, {x, y, w, h}, or null when it is not showing: cards over the world keep out. */
    @Nullable
    private static int[] last;

    @Nullable
    public static int[] bounds() {
        return last;
    }

    private static final int MARGIN = 6, PAD = 4, TITLE_H = 13, ROW_H = 18, MIN_W = 120, MAX_W = 240;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onOverlay(final RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        last = null;
        if (!PlannerSettings.pinList()) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null && !(mc.currentScreen instanceof com.gtnhplanner.ui.tutorial.TourScreen)
            || mc.gameSettings.hideGUI
            || mc.gameSettings.showDebugInfo) return;
        final PlanSnapshot snap = PlanSnapshot.latest();
        if (snap == null || snap.pinList()
            .isEmpty()) return;
        final List<Line> lines = lines(snap);
        if (lines.isEmpty()) return;
        draw(event.resolution, snap.planName(), lines);
    }

    /**
     * The pinned cards' machines, in the order they were first pinned, each with its tiers: the machines each builds,
     * so a crop card counts its harvesters, never the crops planted (issue 8).
     */
    static List<Line> lines(final PlanSnapshot snap) {
        final Map<String, Map<String, int[]>> byName = new LinkedHashMap<>();
        final Map<String, ItemStack> icons = new LinkedHashMap<>();
        for (final java.util.UUID id : snap.pinList()) {
            for (final PlanSnapshot.Card c : snap.cards()) {
                if (!c.id()
                    .equals(id)) continue;
                byName
                    .computeIfAbsent(
                        c.name(),
                        n -> new java.util.TreeMap<>(
                            java.util.Comparator
                                .comparingInt((String t) -> -com.gtnhplanner.ui.card.CardDefaults.tierIndex(t))
                                .thenComparing(t -> t)))
                    .computeIfAbsent(c.tier(), t -> new int[1])[0] += (int) Math.ceil(c.builds() - 1e-9);
                if (c.machine() != null) icons.putIfAbsent(c.name(), c.machine());
            }
        }
        final List<Line> out = new ArrayList<>();
        for (final Map.Entry<String, Map<String, int[]>> e : byName.entrySet()) {
            final List<Tiered> tiers = new ArrayList<>();
            for (final Map.Entry<String, int[]> t : e.getValue()
                .entrySet()) tiers.add(new Tiered(t.getKey(), t.getValue()[0]));
            out.add(new Line(e.getKey(), icons.get(e.getKey()), tiers));
        }
        return out;
    }

    private static void draw(final ScaledResolution sr, final String plan, final List<Line> lines) {
        // As wide as the longest line needs, within bounds; names give way first.
        int right = 0;
        for (final Line l : lines) right = Math.max(right, rightWidth(l));
        int w = Hyb.width(plan) + 22 + PAD;
        for (final Line l : lines) w = Math.max(w, 23 + Hyb.width(l.name()) + 6 + right + PAD);
        w = Math.max(MIN_W, Math.min(MAX_W, w));
        final int h = TITLE_H + lines.size() * ROW_H + PAD;
        final int[] at = place(sr, w, h);
        final int x = at[0], y = at[1];
        last = new int[] { x, y, w, h };

        GL11.glPushMatrix();
        GL11.glColor4f(1, 1, 1, 1);
        Hyb.beginBatch();
        Hyb.ring(x, y, w, h, 1, 0xB03C3E45);
        Hyb.rect(x, y, w, h, Minimap.BACKING);
        Hyb.rect(x + 1, y + TITLE_H - 1, w - 2, 1, 0x30FFFFFF);
        Hyb.endBatch();
        RecipeCard.pinIcon(x + 8, y + 7, Hyb.GOLD);
        Hyb.text(Hyb.fit(plan, w - 22), x + 16, y + 3, Hyb.MUTED);

        int ry = y + TITLE_H + 1;
        for (int i = 0; i < lines.size(); i++) {
            final Line l = lines.get(i);
            if (i % 2 == 1) Hyb.rect(x + 1, ry, w - 2, ROW_H, 0x0DE8E9EE);
            if (l.icon() != null) Hyb.item(l.icon(), x + 4, ry + 1, 16, 0);
            // Its tiers from the right, each a tag and a count.
            float tx = x + w - PAD;
            for (final Tiered t : l.tiers()) {
                final String count = "×" + t.count();
                Hyb.textRight(count, tx, ry + 5, t.count() > 0 ? Hyb.INK : Hyb.MUTED);
                tx -= Hyb.width(count) + 2;
                if (!t.tier()
                    .isEmpty()) {
                    tx -= Hyb.tierTagWidth(t.tier(), null);
                    Hyb.tierTag(t.tier(), null, tx, ry + 5);
                }
                tx -= 6;
            }
            final int nameRoom = (int) (tx - (x + 23));
            Hyb.text(Hyb.fit(l.name(), nameRoom), x + 23, ry + 5, Hyb.INK);
            ry += ROW_H;
        }
        GL11.glPopMatrix();
        GL11.glColor4f(1, 1, 1, 1);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
    }

    /** The room a line's tier tags and counts take at its right. */
    private static int rightWidth(final Line l) {
        int w = 0;
        for (final Tiered t : l.tiers()) w += Hyb.width("×" + t.count()) + 2
            + (t.tier()
                .isEmpty() ? 0 : Hyb.tierTagWidth(t.tier(), null))
            + 6;
        return w - 6;
    }

    /**
     * Its top left in its corner, clear of the minimap and the keys strip: below them at the top of the screen, above
     * them at the bottom.
     */
    private static int[] place(final ScaledResolution sr, final int w, final int h) {
        final PlannerSettings.Corner corner = PlannerSettings.pinListCorner();
        final boolean left = corner == PlannerSettings.Corner.TOP_LEFT || corner == PlannerSettings.Corner.BOTTOM_LEFT;
        final boolean top = corner == PlannerSettings.Corner.TOP_LEFT || corner == PlannerSettings.Corner.TOP_RIGHT;
        final int x = left ? MARGIN : sr.getScaledWidth() - MARGIN - w;
        int y = top ? MARGIN : sr.getScaledHeight() - MARGIN - h - 40;
        final int half = sr.getScaledHeight() / 2;
        for (final int[] o : new int[][] { Minimap.devCentred ? null : Minimap.bounds(sr),
            PlacementKeys.stripBounds() }) {
            if (o == null || o[0] >= x + w || o[0] + o[2] <= x) continue;
            if (top && o[1] < half) y = Math.max(y, o[1] + o[3] + 4);
            else if (!top && o[1] + o[3] > half) y = Math.min(y, o[1] - 4 - h);
        }
        return new int[] { x, y };
    }
}
