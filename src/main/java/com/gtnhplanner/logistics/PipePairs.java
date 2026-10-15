package com.gtnhplanner.logistics;

import java.util.ArrayList;
import java.util.List;

/**
 * How a card's placed machines join another card's. The two sides are lined up along the way they both spread, so the
 * upper machine feeds the upper one, and a leftover source takes another hatch on the last target. The wire's rate is
 * split across those runs.
 */
public final class PipePairs {

    /** One placed machine to another, and this run's share of the wire's rate. */
    public record Pair(int from, int to, double share) {}

    private PipePairs() {}

    /**
     * {@code fromCount} and {@code toCount} are how many of each card are in the world. Empty when either side has
     * none. One of each keeps the whole rate; more than one splits it so each machine is sized on its own.
     */
    /**
     * Which axis both rows spread on, so they can be lined up: 0 is x, 1 is y, 2 is z. Each value of {@code fromRange}
     * and {@code toRange} is how far that side stretches on that axis. A single machine spreads nothing, and y is no
     * more special than x.
     */
    public static int alignAxis(final double[] fromRange, final double[] toRange) {
        int best = 0;
        double score = -1;
        for (int i = 0; i < 3; i++) {
            final double spread = Math.min(fromRange[i], toRange[i]);
            if (spread > score) {
                score = spread;
                best = i;
            }
        }
        return best;
    }

    public static List<Pair> pair(final int fromCount, final int toCount, final double rate) {
        if (fromCount <= 0 || toCount <= 0) return List.of();
        final int n = Math.max(fromCount, toCount);
        final double share = rate / n;
        final List<Pair> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++)
            out.add(new Pair(i < fromCount ? i : fromCount - 1, i < toCount ? i : toCount - 1, share));
        return out;
    }
}
