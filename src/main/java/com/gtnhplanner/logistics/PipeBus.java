package com.gtnhplanner.logistics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.gtnhplanner.logistics.BlockRoute.Cell;

/**
 * One shared run for several machines moving the same thing. A horizontal trunk at {@code ceilingY} spans them, each
 * machine joins it by its own branch, and each block is labelled with how much has to pass through it: a branch with
 * that machine's rate, the trunk with whatever is on the trunk at that point. A pipe for the block is then the pipe
 * that can carry that label.
 */
public final class PipeBus {

    /** A machine's join onto the bus. Positive flow is supply onto the bus, negative is demand off it. */
    public record Tap(Cell at, double flow) {}

    private PipeBus() {}

    /**
     * The blocks of the bus, each with the rate it has to carry. One tap is not a bus. {@code ceilingY} is the layer
     * the trunk runs on, above the machines.
     */
    public static Map<Cell, Double> lay(final List<Tap> taps, final Set<Cell> blocked, final int ceilingY) {
        final List<Tap> live = new ArrayList<>();
        for (final Tap tap : taps) if (tap.at() != null && tap.flow() != 0) live.add(tap);
        if (live.size() < 2) return Map.of();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (final Tap tap : live) {
            minX = Math.min(
                minX,
                tap.at()
                    .x());
            maxX = Math.max(
                maxX,
                tap.at()
                    .x());
            minZ = Math.min(
                minZ,
                tap.at()
                    .z());
            maxZ = Math.max(
                maxZ,
                tap.at()
                    .z());
        }
        final boolean alongX = maxX - minX >= maxZ - minZ;
        final int lateral = median(live, alongX);
        final int from = alongX ? minX : minZ, to = alongX ? maxX : maxZ;
        final Map<Cell, Double> flow = new HashMap<>();
        final Map<Integer, Double> delta = new HashMap<>();
        for (final Tap tap : live) {
            final int along = alongX ? tap.at()
                .x()
                : tap.at()
                    .z();
            final Cell join = alongX ? new Cell(along, ceilingY, lateral) : new Cell(lateral, ceilingY, along);
            for (final Cell cell : BlockRoute.path(tap.at(), join, blocked))
                flow.merge(cell, Math.abs(tap.flow()), Double::sum);
            delta.merge(along, tap.flow(), Double::sum);
        }
        double carried = 0;
        for (int i = from; i <= to; i++) {
            final double before = Math.abs(carried);
            carried += delta.getOrDefault(i, 0.0);
            final Cell cell = alongX ? new Cell(i, ceilingY, lateral) : new Cell(lateral, ceilingY, i);
            flow.merge(cell, Math.max(before, Math.abs(carried)), Math::max);
        }
        return flow;
    }

    /** The middle of the taps on the axis the trunk does not run along. */
    private static int median(final List<Tap> taps, final boolean alongX) {
        final int[] values = new int[taps.size()];
        for (int i = 0; i < taps.size(); i++) values[i] = alongX ? taps.get(i)
            .at()
            .z()
            : taps.get(i)
                .at()
                .x();
        java.util.Arrays.sort(values);
        return values[values.length / 2];
    }
}
