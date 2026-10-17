package com.gtnhplanner.data.flowchart.balancer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.gtnhplanner.data.flowchart.Drawer;

/**
 * What the plan does at each drawer, and which drawers' rules it cannot keep.
 *
 * @param rates      per drawer id, the total flowing through it in units per second: what the
 *                   linked outputs deliver to a product, byproduct or trash, what a source supplies
 *                   to its linked inputs. Every drawer on the chart has an entry after a solve; empty
 *                   when nothing was solved.
 * @param unmet      drawers whose rule the plan does not keep (beyond the validation tolerance).
 * @param shortfalls for each unmet drawer the relaxation could measure, how far it gets and what
 *                   holds it back.
 * @param ports      per buffer id, each port linked to it and what it puts in (a maker's output) or takes out (a
 *                   user's input), makers first, in link order: what each of its wires carries.
 */
public record DrawerReadout(Map<UUID, Double> rates, Set<UUID> unmet, List<Shortfall> shortfalls,
    Map<UUID, List<External>> ports) {

    /** Nothing solved, nothing known. */
    public static final DrawerReadout NONE = new DrawerReadout(Map.of(), Set.of(), List.of(), Map.of());

    public DrawerReadout {
        rates = Collections.unmodifiableMap(new LinkedHashMap<>(rates));
        unmet = Collections.unmodifiableSet(new LinkedHashSet<>(unmet));
        shortfalls = List.copyOf(shortfalls);
        final Map<UUID, List<External>> copy = new LinkedHashMap<>();
        for (final Map.Entry<UUID, List<External>> e : ports.entrySet())
            copy.put(e.getKey(), List.copyOf(e.getValue()));
        ports = Collections.unmodifiableMap(copy);
    }

    /** What flows through one port of a buffer, in units per second; 0 when it is not one of its ports. */
    public double portRate(final UUID drawer, final PortRef port) {
        final List<External> list = ports.get(drawer);
        if (list == null) return 0;
        for (final External e : list) if (e.port()
            .equals(port)) return e.ratePerSecond();
        return 0;
    }

    /**
     * One drawer whose rule the chart cannot keep, measured by the relaxation: the most (or, for an
     * at-most rule, the least) the drawer can get while every pin and every other keepable rule
     * holds, and the constraints that, dropped one at a time, would let it be kept. A limit is a
     * pinned machine ({@code limitingNodes}), another drawer's rule ({@code limitingDrawers}) or a
     * capped machine group ({@code limitingGroups}, by capacity row order). All three empty means
     * the chart's own shape caps it (e.g. a drawer linked to nothing).
     */
    public record Shortfall(UUID drawer, Drawer.Rule rule, double target, double reachable, List<UUID> limitingNodes,
        List<UUID> limitingDrawers, int limitingGroups) {

        public Shortfall {
            limitingNodes = List.copyOf(limitingNodes);
            limitingDrawers = List.copyOf(limitingDrawers);
        }
    }

    /** Whether the drawer's rule holds (true for drawers with no rule, and for unknown ids). */
    public boolean met(final UUID drawer) {
        return !unmet.contains(drawer);
    }

    /** The drawer's total in units per second; 0 when nothing was solved. */
    public double rate(final UUID drawer) {
        return rates.getOrDefault(drawer, 0.0);
    }

    @Nullable
    public Shortfall shortfall(final UUID drawer) {
        for (final Shortfall s : shortfalls) {
            if (s.drawer()
                .equals(drawer)) return s;
        }
        return null;
    }

    /** The read-out at a committed point: every drawer's total, checked against its bounds. */
    static DrawerReadout of(final SolveContext ctx, final double[] extents, final double[] flows,
        final double[] externals) {
        final Map<UUID, Double> rates = new LinkedHashMap<>();
        final Set<UUID> unmet = new LinkedHashSet<>(ctx.unmetDrawers);
        final Map<UUID, List<External>> ports = new LinkedHashMap<>();
        for (int d = 0; d < ctx.model.drawers.size(); d++) {
            final ModelData.DrawerRow row = ctx.model.drawers.get(d);
            final UUID id = row.id();
            final double total = ctx.drawerTotal(d, extents, flows, externals);
            rates.put(id, total);
            if (!ctx.drawerHolds(d, total)) unmet.add(id);
            if (row.kind() == com.gtnhplanner.data.flowchart.Drawer.Kind.BUFFER)
                ports.put(id, bufferPorts(ctx, row, flows));
        }
        return new DrawerReadout(rates, unmet, ctx.shortfalls, ports);
    }

    /** Each port of a buffer and the flow on its edges at that port. */
    private static List<External> bufferPorts(final SolveContext ctx, final ModelData.DrawerRow row,
        final double[] flows) {
        final List<External> out = new ArrayList<>();
        for (final PortRef ref : row.links()) {
            double rate = 0;
            for (final int e : row.flowEdges()) {
                final ModelData.EdgeData edge = ctx.model.edges.get(e);
                if (ctx.refOf(ref.input() ? edge.dstPort() : edge.srcPort())
                    .equals(ref)) rate += flows[e];
            }
            out.add(new External(ref, rate));
        }
        return out;
    }

    /** The read-out of a run that committed no point: only what the context already knew was unmet. */
    static DrawerReadout unsolved(final SolveContext ctx) {
        if (ctx.unmetDrawers.isEmpty() && ctx.shortfalls.isEmpty()) return NONE;
        return new DrawerReadout(Map.of(), ctx.unmetDrawers, ctx.shortfalls, Map.of());
    }
}
