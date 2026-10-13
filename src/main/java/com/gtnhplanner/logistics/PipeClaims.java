package com.gtnhplanner.logistics;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.gtnhplanner.logistics.BlockRoute.Cell;

/**
 * Each resource keeps its own blocks and hatches. Light Fuel and Fuel do not share a pipe, and they do not leave or
 * enter through the same hatch or the same face of a single-block. A shared bus lets the same resource reuse pipe
 * blocks; each pairing still takes its own hatch.
 */
public final class PipeClaims {

    private final Map<Cell, String> cells = new HashMap<>();
    private final Map<Cell, String> hatches = new HashMap<>();

    /** Machines plus every cell already taken, so each direct run keeps its own blocks. */
    public Set<Cell> blockedAll(final Set<Cell> machines) {
        final Set<Cell> out = new HashSet<>(machines);
        out.addAll(cells.keySet());
        out.addAll(hatches.keySet());
        return out;
    }

    /** Machines plus every cell already taken by a different resource, so a bus can join. */
    public Set<Cell> blocked(final Set<Cell> machines, final String resource) {
        final Set<Cell> out = new HashSet<>(machines);
        for (final Map.Entry<Cell, String> entry : cells.entrySet()) if (!entry.getValue()
            .equals(resource)) out.add(entry.getKey());
        for (final Map.Entry<Cell, String> entry : hatches.entrySet()) if (!entry.getValue()
            .equals(resource)) out.add(entry.getKey());
        return out;
    }

    /** Whether this hatch is still free: each pairing takes its own, even for the same resource. */
    public boolean hatchFree(final Cell hatch) {
        return hatch == null || !hatches.containsKey(hatch);
    }

    public void claim(final Iterable<Cell> pipes, @Nullable final Cell fromHatch, @Nullable final Cell toHatch,
        final String resource) {
        for (final Cell cell : pipes) cells.putIfAbsent(cell, resource);
        if (fromHatch != null) hatches.putIfAbsent(fromHatch, resource);
        if (toHatch != null) hatches.putIfAbsent(toHatch, resource);
    }
}
