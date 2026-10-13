package com.gtnhplanner.ui.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.util.Vec3;

import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Plan;
import com.gtnhplanner.ui.gt.StructureGhosts;

/**
 * Every machine the loaded plans place in one dimension, by where it is: what it fills, in columns of 16 by 16 blocks.
 * The look ray and the overlay ask it what is near instead of going through every placement of every plan each tick
 * and frame (a plan of thousands of placed machines made that the frame's whole cost). Made again when a plan, its
 * placements, the board's snapshot (a card's machine and structure) or the ghosts known change. Client thread only.
 */
final class WorldIndex {

    private WorldIndex() {}

    /** A placement: its plan and card, its spot and where that is in the card's list, and what it fills. */
    static final class Entry {

        final Graph graph;
        final Node node;
        final int[] link;
        final int index;
        /** The card's first placement in this dimension: the one its card stands over. */
        final boolean first;
        final double[] box;
        /** The query that last met it: one spanning several columns is reported once. */
        private int seen;

        Entry(final Graph graph, final Node node, final int[] link, final int index, final boolean first,
            final double[] box) {
            this.graph = graph;
            this.node = node;
            this.link = link;
            this.index = index;
            this.first = first;
            this.box = box;
        }
    }

    private static final int CELL = 16;

    private static Map<Long, List<Entry>> cells = Map.of();
    private static int query;

    /** What the index was made from. */
    private static Plan builtPlan;
    private static Graph[] builtGraphs = {};
    private static long[] builtVersions = {};
    private static PlanSnapshot builtSnapshot;
    private static int builtGhosts = -1, builtDim = Integer.MIN_VALUE;
    private static long builtChanges = -1, changes;

    /** A placement changed in a way the plans' versions do not show: placing, moving, turning, removing. */
    static void changed() {
        changes++;
    }

    /** The placements of a dimension whose columns come within {@code range} of (x, z), each once. */
    static List<Entry> near(final int dim, final double x, final double z, final double range) {
        final List<Entry> out = new ArrayList<>();
        if (!fresh(dim)) return out;
        collect(x - range, z - range, x + range, z + range, out);
        return out;
    }

    /** The placements of a dimension whose columns a ray passes over within {@code range}, each once. */
    static List<Entry> along(final int dim, final Vec3 from, final Vec3 dir, final double range) {
        final List<Entry> out = new ArrayList<>();
        if (!fresh(dim)) return out;
        final double x1 = from.xCoord + dir.xCoord * range, z1 = from.zCoord + dir.zCoord * range;
        collect(
            Math.min(from.xCoord, x1),
            Math.min(from.zCoord, z1),
            Math.max(from.xCoord, x1),
            Math.max(from.zCoord, z1),
            out);
        return out;
    }

    private static void collect(final double x0, final double z0, final double x1, final double z1,
        final List<Entry> out) {
        query++;
        final int cx0 = cell(x0), cz0 = cell(z0), cx1 = cell(x1), cz1 = cell(z1);
        for (int cx = cx0; cx <= cx1; cx++) for (int cz = cz0; cz <= cz1; cz++) {
            final List<Entry> in = cells.get(key(cx, cz));
            if (in == null) continue;
            for (final Entry e : in) if (e.seen != query) {
                e.seen = query;
                out.add(e);
            }
        }
    }

    /** Whether the index holds this dimension's placements as they are now; makes it again when not. */
    private static boolean fresh(final int dim) {
        final Plan plan = Plan.loaded();
        if (plan == null) return false;
        final List<Graph> graphs = plan.getGraphs();
        final PlanSnapshot snapshot = PlanSnapshot.latest();
        final int ghosts = StructureGhosts.generation();
        boolean same = plan == builtPlan && dim == builtDim
            && snapshot == builtSnapshot
            && ghosts == builtGhosts
            && changes == builtChanges
            && graphs.size() == builtGraphs.length;
        for (int i = 0; same && i < builtGraphs.length; i++) same = graphs.get(i) == builtGraphs[i] && graphs.get(i)
            .version() == builtVersions[i];
        if (same) return true;
        final Map<Long, List<Entry>> made = new HashMap<>();
        for (final Graph g : graphs) for (final Node n : g.nodes.values()) {
            boolean first = true;
            for (int i = 0; i < n.worldLinks.size(); i++) {
                final int[] l = n.worldLinks.get(i);
                if (l[0] != dim) continue;
                final double[] box = WorldLinks.extent(g, n, l);
                final Entry e = new Entry(g, n, l, i, first, box);
                first = false;
                for (int cx = cell(box[0]); cx <= cell(box[3] - 1e-6); cx++)
                    for (int cz = cell(box[2]); cz <= cell(box[5] - 1e-6); cz++)
                        made.computeIfAbsent(key(cx, cz), k -> new ArrayList<>())
                            .add(e);
            }
        }
        cells = made;
        builtPlan = plan;
        builtDim = dim;
        builtSnapshot = snapshot;
        builtGhosts = ghosts;
        builtChanges = changes;
        builtGraphs = graphs.toArray(new Graph[0]);
        builtVersions = new long[builtGraphs.length];
        for (int i = 0; i < builtGraphs.length; i++) builtVersions[i] = builtGraphs[i].version();
        return true;
    }

    /** The placement a ray meets first within {@code range}, in {@code only} or every plan; or null. */
    @Nullable
    static WorldLinks.Spot ray(final int dim, final Vec3 from, final Vec3 dir, final double range,
        @Nullable final Graph only) {
        WorldLinks.Spot best = null;
        double bestT = range;
        for (final Entry e : along(dim, from, dir, range)) {
            if (only != null && e.graph != only) continue;
            final double t = WorldLinks.enter(from, dir, e.box);
            if (t >= 0 && t < bestT) {
                bestT = t;
                best = new WorldLinks.Spot(new WorldLinks.Hit(e.graph, e.node), e.link[1], e.link[2], e.link[3], t);
            }
        }
        return best;
    }

    private static int cell(final double v) {
        return (int) Math.floor(v / CELL);
    }

    private static long key(final int cx, final int cz) {
        return (long) cx << 32 | cz & 0xFFFFFFFFL;
    }
}
