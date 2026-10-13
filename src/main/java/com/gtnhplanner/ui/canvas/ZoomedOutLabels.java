package com.gtnhplanner.ui.canvas;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.gtnhplanner.ui.theme.Hyb;

/**
 * The names over cards and drawers when the board is zoomed far out, and on the minimap (the "Names when zoomed out"
 * setting), placed the way a map places its labels: drawn on top of everything in one pass, small (a screen pixel per
 * font pixel, so sharp at any zoom), no wider than what they name (cut short with "..."), each where it keeps clear of
 * the other names, the cards and the drawers, and left out where there is no such place. Zooming in makes room for the
 * rest. Cards' names go first, then the drawers', the busiest first.
 */
public final class ZoomedOutLabels {

    /** Where a name goes against its box, in order of preference. */
    enum Side {
        ABOVE,
        BELOW,
        LEFT,
        RIGHT
    }

    /**
     * A name for a box (world coordinates), the sides it may sit on, best first, how much it matters, and how wide it
     * may be (world units).
     */
    private record Request(String text, float x, float y, float w, float h, Side[] sides, double priority,
        float widest) {}

    private final List<Request> requests = new ArrayList<>();
    /** The cards' and drawers' boxes this frame: names keep off them. */
    private final List<float[]> boxes = new ArrayList<>();

    public void clear() {
        requests.clear();
        boxes.clear();
    }

    public void card(final String text, final float x, final float y, final float w, final float h) {
        boxes.add(new float[] { x, y, w, h });
        requests.add(new Request(text, x, y, w, h, new Side[] { Side.ABOVE, Side.BELOW }, 1e18, w));
    }

    /** A drawer: a source's name to its left (cards are to its right), a product's to its right. */
    public void drawer(final String text, final float x, final float y, final float w, final float h,
        final boolean source, final double rate) {
        final Side[] sides = source ? new Side[] { Side.LEFT, Side.ABOVE, Side.BELOW }
            : new Side[] { Side.RIGHT, Side.ABOVE, Side.BELOW };
        boxes.add(new float[] { x, y, w, h });
        requests.add(new Request(text, x, y, w, h, sides, Math.abs(rate), 2 * w));
    }

    /**
     * Places and draws the names in world space, the transform already applied ({@code zoom} GUI pixels to a world
     * unit).
     */
    /** A font pixel in world units at {@code zoom}: a screen pixel (two at large GUI scales). */
    private static float font(final float zoom) {
        final int gui = com.gtnhplanner.ui.PlannerScale.board();
        return Math.max(1, Math.round(gui / 2f)) / (float) gui / zoom;
    }

    /**
     * A name's label at {@code zoom}, in world units: {its width, its height, the gap it keeps from its box}. No wider
     * than {@code widest} allows.
     */
    static float[] size(final String text, final float widest, final float zoom) {
        final float font = font(zoom), pad = 2 * font;
        final String fitted = Hyb.fit(text, (int) ((widest - 2 * pad) / font));
        return new float[] { Hyb.width(fitted) * font + 2 * pad, 8 * font + 2 * pad, 3 / zoom };
    }

    public void draw(final float zoom) {
        if (requests.isEmpty()) return;
        final float font = font(zoom);
        final float s = 1 / zoom, gap = 3 * s, pad = 2 * font, lh = 8 * font + 2 * pad;
        // The same names in the same places as last frame (the board standing still): placed as they were.
        final long key = key(zoom);
        if (key != laidFor) {
            lay(zoom, font, s, gap, pad, lh);
            laidFor = key;
        }
        // Every backing in one batch, then every name: two draws, however many names.
        Hyb.beginBatch();
        for (final Laid l : laid) Hyb.rect(l.x(), l.y(), l.w(), l.h(), 0xC0101114);
        Hyb.endBatch();
        for (final Laid l : laid) Hyb.text(l.text(), l.x() + pad, l.y() + pad, font, Hyb.INK);
    }

    /** A name as placed: its words and its box. */
    private record Laid(String text, float x, float y, float w, float h) {}

    private List<Laid> laid = List.of();
    private long laidFor = Long.MIN_VALUE;

    /** What this frame's names are, at a zoom: equal when nothing about them changed. */
    private long key(final float zoom) {
        long k = Float.floatToIntBits(zoom);
        for (final Request r : requests) {
            k = k * 31 + System.identityHashCode(r.text());
            k = k * 31 + Float.floatToIntBits(r.x());
            k = k * 31 + Float.floatToIntBits(r.y());
            k = k * 31 + Float.floatToIntBits(r.h());
            k = k * 31 + Double.hashCode(r.priority());
        }
        return k;
    }

    /** Names cut to width, kept: the same few names come up every frame. */
    private final java.util.Map<String, java.util.Map<Integer, String>> fitted = new java.util.HashMap<>();

    private String fit(final String text, final int width) {
        if (fitted.size() > 4096) fitted.clear();
        return fitted.computeIfAbsent(text, t -> new java.util.HashMap<>())
            .computeIfAbsent(width, w -> Hyb.fit(text, w));
    }

    /** Places this frame's names: the busiest first, each on the first side clear of names and boxes. */
    private void lay(final float zoom, final float font, final float s, final float gap, final float pad,
        final float lh) {
        // Room kept between two names side by side, so they never read as one.
        final float apart = 6 * s;
        // What names keep clear of, filed by where it is, so each name looks only at its neighbours.
        final Grid placed = new Grid(64 * s), solid = new Grid(64 * s);
        for (final float[] b : boxes) solid.add(b);
        final List<Laid> out = new ArrayList<>();
        final List<Request> order = new ArrayList<>(requests);
        order.sort(Comparator.comparingDouble(r -> -r.priority()));
        for (final Request r : order) {
            final String text = fit(r.text(), (int) ((r.widest() - 2 * pad) / font));
            if (text.isEmpty()) continue;
            final float lw = Hyb.width(text) * font + 2 * pad;
            for (final Side side : r.sides()) {
                final float lx = switch (side) {
                    case ABOVE, BELOW -> r.x() + (r.w() - lw) / 2f;
                    case LEFT -> r.x() - gap - lw;
                    case RIGHT -> r.x() + r.w() + gap;
                };
                final float ly = switch (side) {
                    case ABOVE -> r.y() - gap - lh;
                    case BELOW -> r.y() + r.h() + gap;
                    case LEFT, RIGHT -> r.y() + (r.h() - lh) / 2f;
                };
                final float[] box = { lx, ly, lw, lh };
                final float[] room = { lx - apart, ly - gap, lw + 2 * apart, lh + 2 * gap };
                if (placed.overlaps(room) || solid.overlaps(box)) continue;
                placed.add(box);
                out.add(new Laid(text, lx, ly, lw, lh));
                break;
            }
        }
        laid = out;
    }

    /** Boxes {x, y, w, h} filed in square cells, for overlap tests against only those nearby. */
    private static final class Grid {

        private final float cell;
        private final java.util.Map<Long, List<float[]>> cells = new java.util.HashMap<>();

        Grid(final float cell) {
            this.cell = Math.max(1, cell);
        }

        void add(final float[] b) {
            for (int cx = c(b[0]); cx <= c(b[0] + b[2]); cx++) for (int cy = c(b[1]); cy <= c(b[1] + b[3]); cy++)
                cells.computeIfAbsent((long) cx << 32 | cy & 0xFFFFFFFFL, k -> new ArrayList<>())
                    .add(b);
        }

        boolean overlaps(final float[] a) {
            for (int cx = c(a[0]); cx <= c(a[0] + a[2]); cx++) for (int cy = c(a[1]); cy <= c(a[1] + a[3]); cy++) {
                final List<float[]> in = cells.get((long) cx << 32 | cy & 0xFFFFFFFFL);
                if (in != null && overlapsAny(a, in)) return true;
            }
            return false;
        }

        private int c(final float v) {
            return (int) Math.floor(v / cell);
        }
    }

    private static boolean overlapsAny(final float[] a, final List<float[]> others) {
        for (final float[] b : others) {
            if (a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3]) return true;
        }
        return false;
    }
}
