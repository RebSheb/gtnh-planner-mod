package com.gtnhplanner.ui.drawer;

import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.ui.card.CardLayout;
import com.gtnhplanner.ui.card.CardPaint;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * How a drawer looks: a card's port row lifted off the card and set on the board ({@link CardPaint#drawerRow}), the
 * striped box of what crosses the plan's edge in its kind's colours and shape (a source is a rounded tank, a product a
 * rounded square, trash a bin narrowing to its base), at the row's size, under a thin bar it is moved by.
 */
public final class DrawerPaint {

    /**
     * The bar along the top it is moved by (its last pixel the hairline under it), then a card's port row, as wide as
     * its striped box: the whole drawer.
     */
    public static final int BAR = 8, W = CardLayout.RAIL_W + 4, H = BAR + CardLayout.BOX_H;
    /** Where a card's row starts on the drawer: its box fills the drawer under the bar, a pixel inside the row. */
    public static final int ROW_Y = BAR - CardLayout.BOX_Y;
    /** The shape's corners at this share of its full size, as a card's forced rows have them. */
    public static final float SCALE = 0.5f;
    /**
     * The delete key, a square at the bar's left end: this big, as far in from the side as from the top, hanging over
     * the bar's lower edge so it is easy to hit.
     */
    public static final int DELETE = 10, DELETE_IN = 3;
    /** How far down the bar takes hold: past its hairline, so it is easy to grab though drawn no thicker. */
    public static final int GRAB = BAR + 5;

    // The shapes, in board pixels: the tank's corners, the crate's, the shield's cut and how far the bin narrows.
    private static final float TANK = 9, CRATE = 3, BIN = 6, CUT = 7;

    /** A buffer's colour: teal, the one drawer inside the plan (the website's {@code --cd-buffer}). */
    public static final int BUFFER_INK = 0xFF2E9C8F;

    /** A rate at nothing, and what trash takes (always grey, as on the website). */
    public static final int IDLE_INK = 0xFFA8AFBB, TRASH_INK = 0xFFB9C0CD;

    private DrawerPaint() {}

    // region Colours

    /** The kind's colour: sources blue, products orange (GregTech's inputs and outputs), trash steel. */
    public static int tint(final Drawer.Kind kind) {
        return switch (kind) {
            case SOURCE -> Hyb.SOURCE_INK;
            case PRODUCT, BYPRODUCT -> Hyb.PRODUCT_INK;
            case TRASH -> 0xFF8A93A6;
            case BUFFER -> BUFFER_INK;
        };
    }

    /** The 1 px outline, the kind's colour over the card's edge. */
    public static int edge(final Drawer.Kind kind) {
        return Hyb.mix(tint(kind), CardPaint.EDGE, 0.55f);
    }

    /** The surface: the card's, a breath of the kind's colour in it. */
    public static int fill(final Drawer.Kind kind) {
        return Hyb.mix(tint(kind), CardPaint.SURFACE, 0.1f);
    }

    /** The header's hairline. */
    public static int hair(final Drawer.Kind kind) {
        return Hyb.mix(tint(kind), CardPaint.HAIR, 0.3f);
    }

    /** What the plan moves through a drawer, in its colour: blue out of a source, orange into a product, trash grey. */
    public static int rateInk(final Drawer.Kind kind, final double rate) {
        if (rate <= 0) return IDLE_INK;
        return switch (kind) {
            case SOURCE -> Hyb.SOURCE_INK;
            case PRODUCT, BYPRODUCT -> Hyb.PRODUCT_INK;
            case TRASH -> TRASH_INK;
            case BUFFER -> BUFFER_INK;
        };
    }

    /** A rate's sign: taken from a source, put into a product or trash; none through a buffer. */
    public static String sign(final Drawer.Kind kind, final double rate) {
        return rate <= 0 || kind == Drawer.Kind.BUFFER ? "" : kind == Drawer.Kind.SOURCE ? "-" : "+";
    }

    // endregion

    // region The shape

    /**
     * The kind's silhouette over the box {@code x, y, w, h} grown by {@code grow} all round (negative: shrunk), in one
     * colour, its shape's sizes times {@code scale} (1 on the board). Drawn as rows of rectangles in whole-pixel steps,
     * as round corners are everywhere on the board, each row once, so a see-through colour stays even.
     */
    public static void shape(final Drawer.Kind kind, final float x, final float y, final float w, final float h,
        final float grow, final int colour, final float scale) {
        shape(kind, x, y, w, h, grow, colour, scale, 0, Integer.MAX_VALUE);
    }

    /** As above, only its rows from {@code fromRow} up to {@code toRow} (counted from the grown box's top). */
    public static void shape(final Drawer.Kind kind, final float x, final float y, final float w, final float h,
        final float grow, final int colour, final float scale, final int fromRow, final int toRow) {
        final float bx = x - grow, by = y - grow, bw = w + 2 * grow, bh = h + 2 * grow;
        final int rows = Math.max(0, Math.round(bh)), first = Math.max(0, fromRow), last = Math.min(rows, toRow);
        if (rows == 0 || bw <= 0 || first >= last) return;
        final boolean own = !Hyb.batching();
        if (own) Hyb.beginBatch();
        // A solid bin as a polygon, so its slanted sides stay smooth zoomed in (triangles draw both ways round, so a
        // see-through one would come out twice as dark: that goes by rows).
        if ((colour >>> 24) == 0xFF && kind == Drawer.Kind.TRASH && first == 0 && last == rows) {
            final float lean = BIN * scale * bh / Math.max(1, bh - 2 * grow);
            final float[] p = { bx, by, bx + bw, by, bx + bw - lean, by + bh, bx + lean, by + bh };
            for (int i = 2; i + 3 < p.length; i += 2)
                Hyb.triangle(p[0], p[1], p[i], p[i + 1], p[i + 2], p[i + 3], colour);
            if (own) Hyb.endBatch();
            return;
        }
        int from = first, inset = in(kind, first, bh, grow, scale);
        for (int j = first + 1; j <= last; j++) {
            final int next = j < last ? in(kind, j, bh, grow, scale) : -1;
            if (next == inset) continue;
            final float top = by + from, bottom = j < rows ? by + j : by + bh;
            Hyb.rect(bx + inset, top, bw - 2 * inset, bottom - top, colour);
            from = j;
            inset = next;
        }
        if (own) Hyb.endBatch();
    }

    /**
     * Diagonal stripes inside the kind's silhouette over the box (grown by {@code grow}, as {@link #shape}):
     * {@code width}
     * pixels of {@code colour} every {@code period}, rising to the right, drawn as rows of rectangles.
     */
    public static void stripes(final Drawer.Kind kind, final float x, final float y, final float w, final float h,
        final float grow, final int colour, final float scale, final int period, final int width) {
        stripes(kind, x, y, w, h, grow, colour, scale, period, width, 0);
    }

    /** As above, from row {@code fromRow} of the grown box down. */
    public static void stripes(final Drawer.Kind kind, final float x, final float y, final float w, final float h,
        final float grow, final int colour, final float scale, final int period, final int width, final int fromRow) {
        final float bx = x - grow, by = y - grow, bw = w + 2 * grow, bh = h + 2 * grow;
        final int rows = Math.max(0, Math.round(bh));
        final boolean own = !Hyb.batching();
        if (own) Hyb.beginBatch();
        for (int j = Math.max(0, fromRow); j < rows; j++) {
            final int inset = in(kind, j, bh, grow, scale);
            final float left = bx + inset, right = bx + bw - inset;
            // Where a stripe starts on this row: one pixel further left each row down, so the stripes rise rightwards.
            float sx = left - Math.floorMod(inset + j, period);
            for (; sx < right; sx += period) {
                final float a = Math.max(sx, left), b = Math.min(sx + width, right);
                if (b > a) Hyb.rect(a, by + j, b - a, 1, colour);
            }
        }
        if (own) Hyb.endBatch();
    }

    /** How far in from each side the silhouette is on row {@code j} of a box {@code bh} tall. */
    private static int in(final Drawer.Kind kind, final int j, final float bh, final float grow, final float scale) {
        final float yc = j + 0.5f;
        return switch (kind) {
            case SOURCE -> round(TANK * scale + grow, yc, bh);
            case PRODUCT, BYPRODUCT -> round(CRATE * scale + grow, yc, bh);
            // The sides lean in toward the base.
            case TRASH -> Math.round(BIN * scale * (bh / Math.max(1, bh - 2 * grow)) * yc / bh);
            // A barrel: its four corners cut straight, open to both sides.
            case BUFFER -> cut(CUT * scale + grow, yc, bh);
        };
    }

    /** A corner cut at 45 degrees, {@code r} deep: how far in it is at height {@code yc} of a box {@code bh} tall. */
    private static int cut(final float r, final float yc, final float bh) {
        if (r <= 0) return 0;
        final float edge = Math.min(yc, bh - yc);
        return edge >= r ? 0 : Math.round(r - edge);
    }

    /** A round corner of radius {@code r}: how far in it is at height {@code yc} of a box {@code bh} tall. */
    private static int round(final float r, final float yc, final float bh) {
        if (r <= 0) return 0;
        final float edge = Math.min(yc, bh - yc);
        if (edge >= r) return 0;
        final float dy = r - edge;
        return Math.round(r - (float) Math.sqrt(Math.max(0, r * r - dy * dy)));
    }

    /** How far in from each side the drawer's own outline is at height {@code y} of the board's drawer. */
    public static int insetAt(final Drawer.Kind kind, final float y) {
        final int j = Math.max(0, Math.min(H - 1, (int) y));
        return in(kind, j, H, 0, SCALE);
    }

    /** How far the content keeps in from the sides: trash's sides lean in. */
    public static int side(final Drawer.Kind kind) {
        return kind == Drawer.Kind.TRASH ? 2 : 0;
    }

    /**
     * The drawer itself, at {@code 0, 0}: its outline and striped box in its kind's colours and shape, with the bar it
     * is moved by along the top, plain, a hairline under it and a grip in its middle ({@code hot}: the mouse is on the
     * bar, so it lights).
     */
    public static void frame(final Drawer.Kind kind, final boolean hot) {
        final int tint = tint(kind), edge = Hyb.mix(tint, CardPaint.EDGE, 0.4f);
        final boolean own = !Hyb.batching();
        if (own) Hyb.beginBatch();
        shape(kind, 0, 0, W, H, 0, edge, SCALE);
        // The bar: the rows inside the outline down to its hairline.
        shape(kind, 0, 0, W, H, -1, Hyb.mix(tint, CardPaint.SURFACE, hot ? 0.32f : 0.2f), SCALE, 0, BAR - 2);
        shape(kind, 0, 0, W, H, -1, edge, SCALE, BAR - 2, BAR - 1);
        // The row's box under it, striped.
        shape(kind, 0, 0, W, H, -1, Hyb.mix(tint, CardPaint.SURFACE, 0.07f), SCALE, BAR - 1, Integer.MAX_VALUE);
        stripes(kind, 0, 0, W, H, -1, Hyb.mix(tint, CardPaint.SURFACE, 0.13f), SCALE, 8, 3, BAR - 1);
        // The grip: two rows of dots in the bar's middle.
        final int dots = Hyb.mix(tint, Hyb.MUTED, hot ? 0.15f : 0.5f), gw = 15, gx = (W - gw) / 2;
        for (int i = 0; i < gw; i += 2) {
            Hyb.rect(gx + i, 2, 1, 1, dots);
            Hyb.rect(gx + i, 4, 1, 1, dots);
        }
        if (own) Hyb.endBatch();
    }

    /** The shadow the drawer casts, as the cards' (lifted: nearer and darker). */
    public static void shadow(final Drawer.Kind kind, final boolean lifted) {
        final boolean own = !Hyb.batching();
        if (own) Hyb.beginBatch();
        if (lifted) {
            shape(kind, 5, 7, W, H, 0, 0x50000000, SCALE);
            shape(kind, 3, 4, W, H, 0, 0x40000000, SCALE);
        } else for (int i = 3; i >= 0; i--) shape(kind, 6 - 2 * i, 8 - 2 * i, W, H, 2 * i, 0x18000000, SCALE);
        if (own) Hyb.endBatch();
    }

    // endregion

    // region Words
    // endregion
}
