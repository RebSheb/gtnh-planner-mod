package com.gtnhplanner.ui.note;

import java.util.Arrays;
import java.util.List;

import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * How a box, zone or arrow annotation is drawn (docs/design/annotations.md; the website's
 * {@code clean/note/CleanDrawing} draws the same): flat and square, a 2 px line, no shadow, no rounding. Everything is
 * in the note's own pixels, from its top left. Hit testing lives here too, so a box lets clicks through its inside and
 * an arrow takes only its line.
 */
public final class DrawingPaint {

    private DrawingPaint() {}

    /** The line (border, arrow), its dashes and the arrowhead (an arrow thicker than 2 has a bigger head). */
    public static final int LINE = 2, DASH_ON = 8, DASH_OFF = 6, HEAD_LENGTH = 14, HEAD_WIDTH = 10;
    /** The surface's alpha for a tint and a solid, and the marks' alphas. */
    public static final int TINT = 0x14, SOLID = 0x47;
    /** A mark's cell, and the most cells marks are drawn on (a vast zone goes bare rather than slow). */
    private static final int CELL = 10, MAX_MARK_CELLS = 12000;
    /** How far from a drawing's line the pointer still takes it. */
    public static final int REACH = 8;

    // region Colours

    /** The border's colour: its tag, else the note's. */
    public static int borderColor(final Note n) {
        return NoteColors.swatch(n.getBorderColor() != null ? n.getBorderColor() : n.colorTag());
    }

    /** The fill's colour: its tag, else the note's. */
    public static int fillColor(final Note n) {
        return NoteColors.swatch(n.getFillColor() != null ? n.getFillColor() : n.colorTag());
    }

    /** The surface colour: the fill at a tint's or a solid's alpha; 0 for none. */
    public static int surface(final Note n) {
        return switch (n.fillStyle()) {
            case "none" -> 0;
            case "solid" -> alpha(fillColor(n), SOLID);
            default -> alpha(fillColor(n), TINT);
        };
    }

    static int alpha(final int rgb, final int a) {
        return a << 24 | rgb & 0xFFFFFF;
    }

    // endregion

    // region Drawing

    /** Draws the drawing (not its keys or selection) into a {@code w} by {@code h} area. */
    public static void draw(final Note n, final int w, final int h) {
        switch (n.kind()) {
            case Note.BOX -> box(n, w, h);
            case Note.ZONE -> zone(n, w, h);
            case Note.ARROW -> arrow(n, w, h);
            default -> {}
        }
    }

    private static void box(final Note n, final int w, final int h) {
        Hyb.beginBatch();
        final int surface = surface(n);
        if (surface != 0) Hyb.rect(0, 0, w, h, surface);
        marks(n, w, h, null);
        if (!"none".equals(n.borderStyle())) {
            final int c = borderColor(n);
            final boolean dashed = "dashed".equals(n.borderStyle());
            edge(0, 0, w, LINE, dashed, c, true);
            edge(0, h - LINE, w, LINE, dashed, c, true);
            edge(0, LINE, LINE, h - 2 * LINE, dashed, c, false);
            edge(w - LINE, LINE, LINE, h - 2 * LINE, dashed, c, false);
        }
        Hyb.endBatch();
    }

    /** A straight strip of the border, whole or dashed along its length. */
    private static void edge(final float x, final float y, final float w, final float h, final boolean dashed,
        final int c, final boolean horizontal) {
        if (!dashed) {
            Hyb.rect(x, y, w, h, c);
            return;
        }
        final float length = horizontal ? w : h;
        for (float d = 0; d < length; d += DASH_ON + DASH_OFF) {
            final float run = Math.min(DASH_ON, length - d);
            if (horizontal) Hyb.rect(x + d, y, run, h, c);
            else Hyb.rect(x, y + d, w, run, c);
        }
    }

    private static void zone(final Note n, final int w, final int h) {
        final List<int[]> pts = n.getPoints();
        if (pts == null || pts.size() < 3) return;
        Hyb.beginBatch();
        final int surface = surface(n);
        if (surface != 0) fill(pts, h, surface);
        marks(n, w, h, pts);
        if (!"none".equals(n.borderStyle())) {
            final int c = borderColor(n);
            final boolean dashed = "dashed".equals(n.borderStyle());
            for (int i = 0; i < pts.size(); i++) {
                final int[] a = pts.get(i), b = pts.get((i + 1) % pts.size());
                segment(a[0], a[1], b[0], b[1], LINE, dashed, c);
            }
        }
        Hyb.endBatch();
    }

    /** The outline's inside in flat rows (even-odd), each as tall as the polygon is tall over about 240. */
    private static void fill(final List<int[]> pts, final int h, final int color) {
        final int step = Math.max(1, Math.round(h / 240f));
        final float[] xs = new float[pts.size()];
        for (int y = 0; y < h; y += step) {
            final float yc = y + step / 2f;
            int n = 0;
            for (int i = 0; i < pts.size(); i++) {
                final int[] a = pts.get(i), b = pts.get((i + 1) % pts.size());
                if (a[1] == b[1] || yc < Math.min(a[1], b[1]) || yc >= Math.max(a[1], b[1])) continue;
                xs[n++] = a[0] + (yc - a[1]) / (b[1] - a[1]) * (b[0] - a[0]);
            }
            Arrays.sort(xs, 0, n);
            for (int i = 0; i + 1 < n; i += 2) Hyb.rect(xs[i], y, xs[i + 1] - xs[i], Math.min(step, h - y), color);
        }
    }

    /** A line of {@code t} px between two points, as a flat quad; dashed when asked. */
    static void segment(final float x1, final float y1, final float x2, final float y2, final float t,
        final boolean dashed, final int color) {
        final float len = (float) Math.hypot(x2 - x1, y2 - y1);
        if (len < 0.5f) return;
        if (!dashed) {
            quad(x1, y1, x2, y2, t, color);
            return;
        }
        final float ux = (x2 - x1) / len, uy = (y2 - y1) / len;
        for (float d = 0; d < len; d += DASH_ON + DASH_OFF) {
            final float run = Math.min(DASH_ON, len - d);
            quad(x1 + ux * d, y1 + uy * d, x1 + ux * (d + run), y1 + uy * (d + run), t, color);
        }
    }

    private static void quad(final float x1, final float y1, final float x2, final float y2, final float t,
        final int color) {
        final float len = (float) Math.hypot(x2 - x1, y2 - y1);
        final float nx = -(y2 - y1) / len * t / 2, ny = (x2 - x1) / len * t / 2;
        Hyb.triangle(x1 + nx, y1 + ny, x2 + nx, y2 + ny, x2 - nx, y2 - ny, color);
        Hyb.triangle(x1 + nx, y1 + ny, x2 - nx, y2 - ny, x1 - nx, y1 - ny, color);
    }

    /** The marks over the surface, in the fill colour, a cell at a time (only cells inside {@code outline}). */
    private static void marks(final Note n, final int w, final int h, final List<int[]> outline) {
        final String m = n.marksStyle();
        if ("none".equals(m)) return;
        final int cols = (w + CELL - 1) / CELL, rows = (h + CELL - 1) / CELL;
        if ((long) cols * rows > MAX_MARK_CELLS) return;
        final int ink = fillColor(n);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                final float x = c * CELL, y = r * CELL;
                if (outline != null && !inside(outline, x + CELL / 2f, y + CELL / 2f)) continue;
                final float cw = Math.min(CELL, w - x), ch = Math.min(CELL, h - y);
                switch (m) {
                    case "dots" -> Hyb.rect(x + CELL / 2f, y + CELL / 2f, 1, 1, alpha(ink, 0x66));
                    case "grid" -> {
                        Hyb.rect(x, y, cw, 1, alpha(ink, 0x30));
                        Hyb.rect(x, y, 1, ch, alpha(ink, 0x30));
                    }
                    case "graph" -> {
                        Hyb.rect(x, y, cw, 1, alpha(ink, r % 5 == 0 ? 0x66 : 0x28));
                        Hyb.rect(x, y, 1, ch, alpha(ink, c % 5 == 0 ? 0x66 : 0x28));
                    }
                    case "ruled" -> {
                        if (r % 2 == 1) Hyb.rect(x, y + CELL - 1, cw, 1, alpha(ink, 0x48));
                    }
                    case "hatch" -> segment(x, y + ch, x + cw, y, 1, false, alpha(ink, 0x3A));
                    default -> {}
                }
            }
        }
    }

    /** Whether a point is inside a closed outline (even-odd). */
    static boolean inside(final List<int[]> pts, final float x, final float y) {
        boolean in = false;
        for (int i = 0, j = pts.size() - 1; i < pts.size(); j = i++) {
            final int[] a = pts.get(i), b = pts.get(j);
            if ((a[1] > y) != (b[1] > y) && x < (b[0] - a[0]) * (y - a[1]) / (float) (b[1] - a[1]) + a[0]) in = !in;
        }
        return in;
    }

    // endregion

    // region Arrow

    /** The tail and head of an arrow in its box: {tail x, tail y, head x, head y}. */
    public static float[] ends(final Note n, final int w, final int h) {
        final String d = n.getArrowDirection() == null ? "down-right" : n.getArrowDirection();
        final boolean left = d.endsWith("left"), up = d.startsWith("up");
        return new float[] { left ? w : 0, up ? h : 0, left ? 0 : w, up ? 0 : h };
    }

    /** The head's length and width for a line {@code t} thick: 14 by 10, bigger on a thick line. */
    public static float headLength(final int t) {
        return Math.max(HEAD_LENGTH, 4f * t);
    }

    public static float headWidth(final int t) {
        return Math.max(HEAD_WIDTH, 3f * t);
    }

    private static void arrow(final Note n, final int w, final int h) {
        final float[] e = ends(n, w, h);
        final int c = borderColor(n);
        final float len = (float) Math.hypot(e[2] - e[0], e[3] - e[1]);
        if (len < 1) return;
        final float ux = (e[2] - e[0]) / len, uy = (e[3] - e[1]) / len;
        final int t = n.arrowThickness();
        final boolean both = n.bothHeads();
        final float head = Math.min(headLength(t), both ? len / 2 : len), wide = headWidth(t);
        // The line stops where the head starts, at either end that has one.
        final float bx = e[2] - ux * head, by = e[3] - uy * head;
        final float ax = both ? e[0] + ux * head : e[0], ay = both ? e[1] + uy * head : e[1];
        Hyb.beginBatch();
        segment(ax, ay, bx, by, t, false, c);
        final float nx = -uy * wide / 2, ny = ux * wide / 2;
        Hyb.triangle(e[2], e[3], bx + nx, by + ny, bx - nx, by - ny, c);
        if (both) Hyb.triangle(e[0], e[1], ax + nx, ay + ny, ax - nx, ay - ny, c);
        Hyb.endBatch();
    }

    // endregion

    // region Hit

    /**
     * Whether a point of the note (its own pixels) is on the drawing as the pointer should take it: a box or zone only
     * at its border (a band either side), an arrow near its line. A drawing is never solid inside, so the cards over
     * it keep every click.
     */
    public static boolean hit(final Note n, final int w, final int h, final float x, final float y) {
        final float reach = REACH;
        switch (n.kind()) {
            case Note.BOX -> {
                final boolean outer = x >= -reach && y >= -reach && x < w + reach && y < h + reach;
                final boolean inner = x >= reach && y >= reach && x < w - reach && y < h - reach;
                return outer && !inner;
            }
            case Note.ZONE -> {
                final List<int[]> pts = n.getPoints();
                if (pts == null) return false;
                for (int i = 0; i < pts.size(); i++) {
                    final int[] a = pts.get(i), b = pts.get((i + 1) % pts.size());
                    if (distance(x, y, a[0], a[1], b[0], b[1]) <= reach) return true;
                }
                return false;
            }
            case Note.ARROW -> {
                final float[] e = ends(n, w, h);
                return distance(x, y, e[0], e[1], e[2], e[3]) <= reach;
            }
            default -> {
                return x >= 0 && y >= 0 && x < w && y < h;
            }
        }
    }

    /** How far a point is from a segment. */
    static float distance(final float px, final float py, final float x1, final float y1, final float x2,
        final float y2) {
        final float dx = x2 - x1, dy = y2 - y1;
        final float len2 = dx * dx + dy * dy;
        final float t = len2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - x1) * dx + (py - y1) * dy) / len2));
        return (float) Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
    }

    // endregion
}
