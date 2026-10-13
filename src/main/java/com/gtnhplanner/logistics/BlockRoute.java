package com.gtnhplanner.logistics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * An orthogonal run of blocks from one cell to another that stays out of a set of blocked cells (the machines). When
 * no such run fits in the neighbourhood, the run goes up and across instead, so there is always a route to place
 * pipes on.
 */
public final class BlockRoute {

    public record Cell(int x, int y, int z) {}

    private static final int PADDING = 6;
    private static final int MAX_STEPS = 8000;
    private static final int[][] STEPS = { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 },
        { 0, 0, -1 } };

    private BlockRoute() {}

    /** The blocks of the run, start and goal included. Empty when either end is missing. */
    public static List<Cell> path(final Cell start, final Cell goal, final Set<Cell> blocked) {
        if (start == null || goal == null) return List.of();
        if (start.equals(goal)) return List.of(start);
        if (blocked.contains(start) || blocked.contains(goal)) return List.of();
        final List<Cell> found = search(start, goal, blocked);
        return found != null ? found : over(start, goal, ceiling(start, goal, blocked));
    }

    /**
     * A walk from {@code start} to {@code goal} that stays on {@code allowed} cells, so a wire can follow a shared bus
     * from one tap to another.
     */
    public static List<Cell> along(final Cell start, final Cell goal, final Set<Cell> allowed) {
        if (start == null || goal == null || !allowed.contains(start) || !allowed.contains(goal)) return List.of();
        if (start.equals(goal)) return List.of(start);
        final Map<Cell, Cell> came = new HashMap<>();
        final ArrayList<Cell> open = new ArrayList<>();
        final Set<Cell> seen = new HashSet<>();
        open.add(start);
        seen.add(start);
        while (!open.isEmpty()) {
            final Cell at = open.remove(0);
            if (at.equals(goal)) return walk(came, start, goal);
            for (final int[] step : STEPS) {
                final Cell to = new Cell(at.x + step[0], at.y + step[1], at.z + step[2]);
                if (!allowed.contains(to) || !seen.add(to)) continue;
                came.put(to, at);
                open.add(to);
            }
        }
        return List.of();
    }

    /**
     * The first cell out from {@code at} along a step that is not part of this machine. Stops at a neighbour: walking
     * through another machine is what made a run loop around and back into the controller.
     */
    public static Cell outside(final Cell at, final int dx, final int dy, final int dz, final Set<Cell> self) {
        int sx = Integer.signum(dx), sy = Integer.signum(dy), sz = Integer.signum(dz);
        if (sx == 0 && sy == 0 && sz == 0) sx = 1;
        Cell cell = new Cell(at.x + sx, at.y + sy, at.z + sz);
        for (int i = 0; i < 8 && self.contains(cell); i++) cell = new Cell(cell.x + sx, cell.y + sy, cell.z + sz);
        return cell;
    }

    /**
     * A free face off {@code at}: the first cell out of {@code self} that is not {@code blocked}, preferring the one
     * nearest {@code toward}. Item and fluid each take a face; a taken pipe is blocked for the other kind.
     */
    @javax.annotation.Nullable
    public static Cell freeExit(final Cell at, final Set<Cell> self, final Set<Cell> blocked, final Cell toward) {
        Cell best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (final int[] step : STEPS) {
            final Cell exit = outside(at, step[0], step[1], step[2], self);
            if (blocked.contains(exit) || self.contains(exit)) continue;
            final int distance = toward == null ? 0 : manhattan(exit, toward);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = exit;
            }
        }
        return best;
    }

    private static int ceiling(final Cell start, final Cell goal, final Set<Cell> blocked) {
        int top = Math.max(start.y, goal.y);
        final int x0 = Math.min(start.x, goal.x) - 1, x1 = Math.max(start.x, goal.x) + 1;
        final int z0 = Math.min(start.z, goal.z) - 1, z1 = Math.max(start.z, goal.z) + 1;
        for (final Cell cell : blocked)
            if (cell.x >= x0 && cell.x <= x1 && cell.z >= z0 && cell.z <= z1) top = Math.max(top, cell.y);
        return top + 1;
    }

    /** Up to a clear layer, across, and down. */
    private static List<Cell> over(final Cell start, final Cell goal, final int y) {
        final List<Cell> out = new ArrayList<>();
        int x = start.x, cy = start.y, z = start.z;
        out.add(start);
        while (cy < y) out.add(new Cell(x, ++cy, z));
        while (x != goal.x) out.add(new Cell(x += Integer.signum(goal.x - x), cy, z));
        while (z != goal.z) out.add(new Cell(x, cy, z += Integer.signum(goal.z - z)));
        while (cy > goal.y) out.add(new Cell(x, --cy, z));
        while (cy < goal.y) out.add(new Cell(x, ++cy, z));
        if (!out.get(out.size() - 1)
            .equals(goal)) out.add(goal);
        return out;
    }

    private static List<Cell> search(final Cell start, final Cell goal, final Set<Cell> blocked) {
        if (blocked.contains(start) || blocked.contains(goal)) return null;
        final int minX = Math.min(start.x, goal.x) - PADDING, maxX = Math.max(start.x, goal.x) + PADDING;
        final int minY = Math.min(start.y, goal.y) - PADDING, maxY = Math.max(start.y, goal.y) + PADDING;
        final int minZ = Math.min(start.z, goal.z) - PADDING, maxZ = Math.max(start.z, goal.z) + PADDING;
        final Map<Cell, Cell> came = new HashMap<>();
        final Map<Cell, Integer> cost = new HashMap<>();
        final PriorityQueue<Open> open = new PriorityQueue<>(
            Comparator.comparingInt(Open::f)
                .thenComparingInt(o -> o.cell.x)
                .thenComparingInt(o -> o.cell.y)
                .thenComparingInt(o -> o.cell.z));
        cost.put(start, 0);
        open.add(new Open(start, 0, manhattan(start, goal)));
        final Set<Cell> closed = new HashSet<>();
        int steps = 0;
        while (!open.isEmpty() && steps++ < MAX_STEPS) {
            final Open next = open.poll();
            final Cell at = next.cell;
            if (next.g != cost.getOrDefault(at, Integer.MAX_VALUE) || !closed.add(at)) continue;
            if (at.equals(goal)) return walk(came, start, goal);
            for (final int[] step : STEPS) {
                final Cell to = new Cell(at.x + step[0], at.y + step[1], at.z + step[2]);
                if (to.x < minX || to.x > maxX || to.y < minY || to.y > maxY || to.z < minZ || to.z > maxZ) continue;
                if (blocked.contains(to) || closed.contains(to)) continue;
                final int nextCost = next.g + 1;
                if (nextCost >= cost.getOrDefault(to, Integer.MAX_VALUE)) continue;
                cost.put(to, nextCost);
                came.put(to, at);
                open.add(new Open(to, nextCost, nextCost + manhattan(to, goal)));
            }
        }
        return null;
    }

    private record Open(Cell cell, int g, int f) {}

    private static int manhattan(final Cell a, final Cell b) {
        return Math.abs(a.x - b.x) + Math.abs(a.y - b.y) + Math.abs(a.z - b.z);
    }

    private static List<Cell> walk(final Map<Cell, Cell> came, final Cell start, final Cell goal) {
        final List<Cell> out = new ArrayList<>();
        for (Cell at = goal; at != null && !at.equals(start); at = came.get(at)) out.add(at);
        out.add(start);
        java.util.Collections.reverse(out);
        return out;
    }
}
