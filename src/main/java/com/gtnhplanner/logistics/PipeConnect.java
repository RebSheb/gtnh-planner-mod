package com.gtnhplanner.logistics;

import java.util.Set;

import com.gtnhplanner.logistics.BlockRoute.Cell;

/**
 * Which faces of a pipe block join the rest of its run: a neighbour on the same resource, or a hatch or machine it
 * docks to. Bits match Forge's sides (down 1, up 2, north 4, south 8, west 16, east 32).
 */
public final class PipeConnect {

    private static final int[][] SIDES = { { 0, -1, 0, 1 }, { 0, 1, 0, 2 }, { 0, 0, -1, 4 }, { 0, 0, 1, 8 },
        { -1, 0, 0, 16 }, { 1, 0, 0, 32 } };

    private PipeConnect() {}

    /**
     * {@code ports} are the cells that actually leave a machine: only they grow an arm into a dock. A pipe that walks
     * past another face of the same block does not.
     */
    public static int mask(final Cell cell, final Set<Cell> same, final Set<Cell> docks, final Set<Cell> ports) {
        int bits = 0;
        for (final int[] side : SIDES) {
            final Cell next = new Cell(cell.x() + side[0], cell.y() + side[1], cell.z() + side[2]);
            if (same.contains(next) || docks.contains(next) && ports.contains(cell)) bits |= side[3];
        }
        return bits;
    }
}
