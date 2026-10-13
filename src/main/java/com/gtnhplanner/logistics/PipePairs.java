package com.gtnhplanner.logistics;

import java.util.ArrayList;
import java.util.List;

/**
 * How a card's placed machines join another card's: first to first when both are placed, a leftover source to another
 * hatch on the last target, a leftover target from the last source. The wire's rate is split across those runs.
 */
public final class PipePairs {

    /** One placed machine to another, and this run's share of the wire's rate. */
    public record Pair(int from, int to, double share) {}

    private PipePairs() {}

    /**
     * {@code fromCount} and {@code toCount} are how many of each card are in the world. Empty when either side has
     * none. One of each keeps the whole rate; more than one splits it so each machine is sized on its own.
     */
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
