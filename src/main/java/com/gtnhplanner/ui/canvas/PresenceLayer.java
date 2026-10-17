package com.gtnhplanner.ui.canvas;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.cleanroommc.modularui.widget.sizer.Area;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.ui.card.CardLayout;
import com.gtnhplanner.ui.card.RecipeCard;
import com.gtnhplanner.ui.drawer.DrawerCard;
import com.gtnhplanner.ui.live.Peers;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * The other people in the plan over the board, as the website's presence layer draws them: each one's cursor, a small
 * arrow in their colour with their name on a tag, and an outline in their colour round what they have selected, their
 * name over the first. Two people on one thing each get an outline of their own, one round the other, and their tags
 * side by side. Drawn over everything on the board in the board's own screen pixels, so arrows, tags and lines are one
 * size at every zoom and land on whole pixels; the shapes in one batch, then the words. Nothing at all when nobody is
 * pointing at or holding anything.
 */
final class PresenceLayer {

    /**
     * The arrow, tip at 0,0, in screen pixels: the website's pointer at about two thirds of its size, as the board's
     * pixels are larger than a browser's.
     */
    private static final float[] ARROW = { 0, 0, 0, 11, 3, 8, 5, 13, 7, 12, 5, 7, 9, 7 };
    /** Its triangles, by point: the head as a fan from the tip, then the tail. */
    private static final int[] TRIANGLES = { 0, 1, 2, 0, 2, 5, 0, 5, 6, 2, 3, 4, 2, 4, 5 };
    private static final int EDGE = 0xFF0B0C0E;
    /** The outline round a selected thing: its thickness and how far out it sits, in screen pixels. */
    private static final int LINE = 2, OUT = 3;
    /** A name tag's height; its words sit a pixel in. */
    private static final int TAG_H = 10;

    /** A name on a tag, drawn once the shapes are down. */
    private record Words(String text, int x, int y, int ink) {}

    private final BoardCanvas canvas;

    PresenceLayer(final BoardCanvas canvas) {
        this.canvas = canvas;
    }

    /** Draws everyone's outlines, then their cursors, in the canvas's own pixels (0,0 its top-left). */
    void draw(final Peers peers, final Area a, final float panX, final float panY, final float zoom) {
        if (peers.quiet()) return;
        final long now = System.currentTimeMillis();
        final List<Words> words = new ArrayList<>();
        // How many people have each thing selected, so their outlines and tags can stand apart.
        final Map<Object, Integer> holders = new HashMap<>();
        for (final Peers.Peer p : peers.all())
            for (final Object thing : things(p)) holders.merge(thing, 1, Integer::sum);
        final Map<Object, int[]> placed = new HashMap<>();
        Hyb.beginBatch();
        try {
            for (final Peers.Peer p : peers.all()) {
                boolean named = false;
                for (final Object thing : things(p)) {
                    final float[] box = box(thing);
                    final int[] at = placed.computeIfAbsent(thing, k -> new int[] { 0, Integer.MIN_VALUE });
                    // This person's ring, one out from the last one round it.
                    final int out = OUT + at[0]++ * (LINE + 1);
                    final int x0 = Math.round(panX + box[0] * zoom), y0 = Math.round(panY + box[1] * zoom);
                    final int x1 = Math.round(panX + (box[0] + box[2]) * zoom),
                        y1 = Math.round(panY + (box[1] + box[3]) * zoom);
                    final boolean first = !named;
                    named = true;
                    if (x1 + out < 0 || y1 + out < 0 || x0 - out > a.width || y0 - out > a.height + TAG_H + 8) continue;
                    Hyb.ring(x0 - out, y0 - out, x1 - x0 + 2 * out, y1 - y0 + 2 * out, LINE, p.colour());
                    if (!first) continue;
                    // The name over the outermost ring, after the names already there.
                    final int outer = OUT + (holders.get(thing) - 1) * (LINE + 1) + LINE;
                    final int tx = at[1] == Integer.MIN_VALUE ? x0 - outer : at[1], ty = y0 - outer - TAG_H - 1;
                    final int tw = Hyb.width(p.username()) + 4;
                    Hyb.rect(tx, ty, tw, TAG_H, p.colour());
                    words.add(new Words(p.username(), tx + 2, ty + 1, Hyb.contrastInk(p.colour())));
                    at[1] = tx + tw + 2;
                }
            }
            for (final Peers.Peer p : peers.all()) {
                if (!p.hasCursor()) continue;
                final float shown = p.shown(now);
                if (shown <= 0) continue;
                final int x = Math.round(panX + p.x() * zoom), y = Math.round(panY + p.y() * zoom);
                if (x < -120 || y < -20 || x > a.width + 4 || y > a.height + 4) continue;
                arrow(x, y, p.colour(), shown);
                Hyb.rect(x + 8, y + 11, Hyb.width(p.username()) + 4, TAG_H, alpha(p.colour(), shown));
                words.add(new Words(p.username(), x + 10, y + 12, alpha(Hyb.contrastInk(p.colour()), shown)));
            }
        } finally {
            Hyb.endBatch();
        }
        // The names on their tags, over the shapes; the game's font takes a nearly clear colour as solid.
        for (final Words w : words) if (w.ink() >>> 24 >= 8) Hyb.text(w.text(), w.x(), w.y(), 1f, w.ink(), false);
    }

    /** The things someone has selected that the board shows (a shared machine's card once), in their order. */
    private List<Object> things(final Peers.Peer p) {
        if (p.selected()
            .isEmpty()) return List.of();
        final List<Object> out = new ArrayList<>();
        final Set<Object> seen = new HashSet<>();
        for (final UUID id : p.selected()) {
            final Object thing = thing(id);
            if (thing != null && seen.add(thing)) out.add(thing);
        }
        return out;
    }

    /**
     * What a board id is drawn as: its card (a shared machine's, whichever recipe), drawer, note or group; else null.
     */
    @Nullable
    private Object thing(final UUID id) {
        final RecipeCard card = canvas.cards()
            .get(id);
        if (card != null) return card.model() == null || card.layout() == null ? null : card;
        final DrawerCard drawer = canvas.drawers()
            .get(id);
        if (drawer != null) return drawer.model() == null ? null : drawer;
        final com.gtnhplanner.ui.group.GroupCard group = canvas.groupCards()
            .get(id);
        if (group != null) return group.group() == null ? null : group;
        return canvas.session()
            .graph().notes.get(id);
    }

    /** Where a thing is on the board {x, y, w, h}. */
    private static float[] box(final Object thing) {
        if (thing instanceof final RecipeCard card) {
            final Node n = card.model().node;
            return new float[] { n.x, n.y, CardLayout.W, card.layout().height };
        }
        if (thing instanceof final DrawerCard drawer) {
            final Drawer d = drawer.model().drawer;
            return new float[] { d.getX(), d.getY(), DrawerCard.W, DrawerCard.H };
        }
        if (thing instanceof final com.gtnhplanner.ui.group.GroupCard card) {
            // Its frame, or its summary card when collapsed.
            final com.gtnhplanner.data.flowchart.BoardGroup g = card.group();
            return g.isCollapsed()
                ? new float[] { g.getX(), g.getY(), com.gtnhplanner.ui.group.GroupPaint.SUM_W,
                    com.gtnhplanner.ui.group.GroupPaint.summaryHeight(
                        card.summary()
                            .rows()) }
                : new float[] { g.getX(), g.getY(), g.getWidth(), g.getHeight() };
        }
        final Note n = (Note) thing;
        return new float[] { n.getX(), n.getY(), n.getWidth(), n.getHeight() };
    }

    /**
     * The arrow at its tip, a dark edge round it (the same shape a pixel out each way under it), faded by
     * {@code shown}.
     */
    private static void arrow(final int x, final int y, final int colour, final float shown) {
        final int edge = alpha(EDGE, shown);
        shape(x - 1, y, edge);
        shape(x + 1, y, edge);
        shape(x, y - 1, edge);
        shape(x, y + 1, edge);
        shape(x, y, alpha(colour, shown));
    }

    private static void shape(final float x, final float y, final int argb) {
        for (int t = 0; t < TRIANGLES.length; t += 3) {
            final int a = 2 * TRIANGLES[t], b = 2 * TRIANGLES[t + 1], c = 2 * TRIANGLES[t + 2];
            Hyb.triangle(
                x + ARROW[a],
                y + ARROW[a + 1],
                x + ARROW[b],
                y + ARROW[b + 1],
                x + ARROW[c],
                y + ARROW[c + 1],
                argb);
        }
    }

    /** A colour with its alpha times {@code k}. */
    private static int alpha(final int argb, final float k) {
        if (k >= 1) return argb;
        return Math.round((argb >>> 24) * k) << 24 | argb & 0xFFFFFF;
    }
}
