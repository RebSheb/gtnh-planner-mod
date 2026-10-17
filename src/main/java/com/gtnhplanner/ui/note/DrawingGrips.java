package com.gtnhplanner.ui.note;

import com.gtnhplanner.data.flowchart.Note;

/**
 * Where a box, zone or arrow is held, and what dragging each hold does (docs/design/annotations.md; the website's
 * {@code CleanDrawing} does the same). Pure geometry in the note's own pixels (its top left is 0, 0; a point may lie
 * up to {@link #PAD} outside it), so it can be tested without a game.
 *
 * <ul>
 * <li>A box moves by its border (the line and a band inside it), resizes by its four corners and, just outside the
 * line, its four edges.</li>
 * <li>A zone moves by its outline and resizes by its bottom right corner (its outline scales with it).</li>
 * <li>An arrow moves by its line and is reshaped by its two end handles.</li>
 * </ul>
 */
public final class DrawingGrips {

    private DrawingGrips() {}

    public enum Grip {
        BODY,
        TL,
        T,
        TR,
        R,
        BR,
        B,
        BL,
        L,
        TAIL,
        HEAD
    }

    /** How far outside its box a drawing takes the pointer: the room its widget has all round. */
    public static final int PAD = DrawingPaint.REACH;
    /** The smallest a box or zone is resized to; an arrow's ends may meet along an axis but not both. */
    public static final int MIN = 20;
    /** A corner or end handle takes the pointer this far from its point. */
    public static final int HANDLE = 8;

    /** What is held at a point of the note, or null when nothing is. */
    public static Grip at(final Note n, final int w, final int h, final float x, final float y) {
        switch (n.kind()) {
            case Note.ARROW -> {
                final float[] e = DrawingPaint.ends(n, w, h);
                if (Math.abs(x - e[2]) < HANDLE && Math.abs(y - e[3]) < HANDLE) return Grip.HEAD;
                if (Math.abs(x - e[0]) < HANDLE && Math.abs(y - e[1]) < HANDLE) return Grip.TAIL;
                return DrawingPaint.hit(n, w, h, x, y) ? Grip.BODY : null;
            }
            case Note.ZONE -> {
                if (Math.abs(x - w) < HANDLE && Math.abs(y - h) < HANDLE) return Grip.BR;
                return DrawingPaint.hit(n, w, h, x, y) ? Grip.BODY : null;
            }
            default -> {
                final boolean nearL = Math.abs(x) < HANDLE, nearR = Math.abs(x - w) < HANDLE;
                final boolean nearT = Math.abs(y) < HANDLE, nearB = Math.abs(y - h) < HANDLE;
                if (nearT && nearL) return Grip.TL;
                if (nearT && nearR) return Grip.TR;
                if (nearB && nearL) return Grip.BL;
                if (nearB && nearR) return Grip.BR;
                // Just outside the line, an edge resizes; inside it (the border band) the box moves.
                final boolean across = x >= 0 && x < w, along = y >= 0 && y < h;
                if (across && y < 0 && y >= -PAD) return Grip.T;
                if (across && y >= h && y < h + PAD) return Grip.B;
                if (along && x < 0 && x >= -PAD) return Grip.L;
                if (along && x >= w && x < w + PAD) return Grip.R;
                return DrawingPaint.hit(n, w, h, x, y) ? Grip.BODY : null;
            }
        }
    }

    /** A box's place and size: x, y, width, height. */
    public record Rect(int x, int y, int w, int h) {}

    /** A grid spot (the board's 10 px). */
    static int snap(final float v) {
        return Math.round(v / 10f) * 10;
    }

    /**
     * The box dragged by {@code grip} from {@code from}, the pointer having moved {@code dx}, {@code dy} board px.
     * Edges land on the grid and nothing goes under {@link #MIN}; the side not held stays where it was.
     */
    public static Rect resized(final Rect from, final Grip grip, final float dx, final float dy) {
        int left = from.x(), top = from.y();
        int right = from.x() + from.w(), bottom = from.y() + from.h();
        switch (grip) {
            case TL, L, BL -> left = Math.min(snap(from.x() + dx), right - MIN);
            case TR, R, BR -> right = Math.max(snap(right + dx), left + MIN);
            default -> {}
        }
        switch (grip) {
            case TL, T, TR -> top = Math.min(snap(from.y() + dy), bottom - MIN);
            case BL, B, BR -> bottom = Math.max(snap(bottom + dy), top + MIN);
            default -> {}
        }
        return new Rect(left, top, right - left, bottom - top);
    }

    /** An arrow as it is stored: its box and the corners tail and head are at. */
    public record Shape(Rect box, String direction) {}

    /**
     * The arrow with one end ({@link Grip#TAIL} or {@link Grip#HEAD}) dragged by {@code dx}, {@code dy}: the end lands
     * on the grid and the other stays put. The two ends may line up (a level arrow has no height) but not coincide:
     * then the arrow is left as it was.
     */
    public static Shape reshaped(final Note n, final Rect from, final Grip grip, final float dx, final float dy) {
        final float[] e = DrawingPaint.ends(n, from.w(), from.h());
        int tx = from.x() + Math.round(e[0]), ty = from.y() + Math.round(e[1]);
        int hx = from.x() + Math.round(e[2]), hy = from.y() + Math.round(e[3]);
        if (grip == Grip.TAIL) {
            tx = snap(tx + dx);
            ty = snap(ty + dy);
        } else {
            hx = snap(hx + dx);
            hy = snap(hy + dy);
        }
        if (tx == hx && ty == hy)
            return new Shape(from, n.getArrowDirection() == null ? "down-right" : n.getArrowDirection());
        return new Shape(
            new Rect(Math.min(tx, hx), Math.min(ty, hy), Math.abs(hx - tx), Math.abs(hy - ty)),
            (hy < ty ? "up" : "down") + "-" + (hx < tx ? "left" : "right"));
    }
}
