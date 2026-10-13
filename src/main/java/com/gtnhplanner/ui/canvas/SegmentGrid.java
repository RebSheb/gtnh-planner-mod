package com.gtnhplanner.ui.canvas;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Wires' straight runs by where they are on the board, in square cells: what lies near a point, or near another wire,
 * without going through every run of every wire (a plan of thousands of wires made the hover test and the hops the
 * frame's cost). Each run is filed under every cell its box, grown by its wire's margin, touches; a run is named by its
 * wire's place in the list and its own place in the wire's path, packed as {@code wire << 32 | run}.
 */
final class SegmentGrid {

    private static final int CELL = 64;

    private final Map<Long, long[]> cells = new HashMap<>();

    /**
     * @param margin how far around each of a wire's runs it counts as there (a hover's tolerance, a crossing's slack)
     */
    SegmentGrid(final List<WireLayer.Wire> wires, final ToDoubleFunction<WireLayer.Wire> margin) {
        final Map<Long, List<Long>> filing = new HashMap<>();
        for (int i = 0; i < wires.size(); i++) {
            final WireLayer.Wire w = wires.get(i);
            final double m = margin.applyAsDouble(w);
            final List<int[]> path = w.path();
            for (int k = 1; k < path.size(); k++) {
                final int[] a = path.get(k - 1), b = path.get(k);
                final long run = (long) i << 32 | k;
                for (int cx = cell(Math.min(a[0], b[0]) - m); cx <= cell(Math.max(a[0], b[0]) + m); cx++)
                    for (int cy = cell(Math.min(a[1], b[1]) - m); cy <= cell(Math.max(a[1], b[1]) + m); cy++)
                        filing.computeIfAbsent(key(cx, cy), c -> new ArrayList<>())
                            .add(run);
            }
        }
        for (final Map.Entry<Long, List<Long>> e : filing.entrySet()) {
            final long[] runs = new long[e.getValue()
                .size()];
            for (int i = 0; i < runs.length; i++) runs[i] = e.getValue()
                .get(i);
            cells.put(e.getKey(), runs);
        }
    }

    /** The runs filed where a point is. */
    long[] at(final double x, final double y) {
        final long[] runs = cells.get(key(cell(x), cell(y)));
        return runs == null ? new long[0] : runs;
    }

    /** Every run filed in a cell a box touches, each once, in no particular order. */
    java.util.Set<Long> near(final double x0, final double y0, final double x1, final double y1) {
        final java.util.Set<Long> out = new java.util.HashSet<>();
        for (int cx = cell(x0); cx <= cell(x1); cx++) for (int cy = cell(y0); cy <= cell(y1); cy++) {
            final long[] runs = cells.get(key(cx, cy));
            if (runs != null) for (final long r : runs) out.add(r);
        }
        return out;
    }

    static int wire(final long run) {
        return (int) (run >>> 32);
    }

    static int run(final long run) {
        return (int) run;
    }

    private static int cell(final double v) {
        return (int) Math.floor(v / CELL);
    }

    private static long key(final int cx, final int cy) {
        return (long) cx << 32 | cy & 0xFFFFFFFFL;
    }
}
