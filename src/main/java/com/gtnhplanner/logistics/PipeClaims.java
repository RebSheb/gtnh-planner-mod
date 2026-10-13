package com.gtnhplanner.logistics;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.gtnhplanner.logistics.BlockRoute.Cell;

/**
 * Each resource keeps its own blocks and hatches. Light Fuel and Fuel do not share a pipe, and they do not leave or
 * enter through the same hatch or the same face of a single-block. The same resource may share, so a bus can join.
 */
public final class PipeClaims {

    private final Map<Cell, String> cells = new HashMap<>();
    private final Map<Cell, String> hatches = new HashMap<>();

    /** Machines plus every cell already taken by a different resource. */
    public Set<Cell> blocked(final Set<Cell> machines, final String resource) {
        final Set<Cell> out = new HashSet<>(machines);
        for (final Map.Entry<Cell, String> entry : cells.entrySet()) if (!entry.getValue()
            .equals(resource)) out.add(entry.getKey());
        for (final Map.Entry<Cell, String> entry : hatches.entrySet()) if (!entry.getValue()
            .equals(resource)) out.add(entry.getKey());
        return out;
    }

    /** Whether this hatch is still free for that resource: no other resource has claimed it. */
    public boolean hatchFree(final Cell hatch, final String resource) {
        if (hatch == null) return true;
        final String owner = hatches.get(hatch);
        return owner == null || owner.equals(resource);
    }

    public void claim(final Iterable<Cell> pipes, @Nullable final Cell fromHatch, @Nullable final Cell toHatch,
        final String resource) {
        for (final Cell cell : pipes) cells.putIfAbsent(cell, resource);
        if (fromHatch != null) hatches.putIfAbsent(fromHatch, resource);
        if (toHatch != null) hatches.putIfAbsent(toHatch, resource);
    }
}
