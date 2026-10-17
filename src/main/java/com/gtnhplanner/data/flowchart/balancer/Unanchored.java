package com.gtnhplanner.data.flowchart.balancer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Group;
import com.gtnhplanner.data.flowchart.MachineGroup;
import com.gtnhplanner.data.flowchart.Node;

/**
 * The parts of a plan that nothing sets: each group of recipes and drawers joined by wires (or sharing a machine) with
 * no pinned count, no target rate and no drawer asking for flow, the anchors {@link SolveContext} looks for. The solve
 * scales those to nothing, so the board asks for a number there, however much of the rest of the plan is set.
 */
public final class Unanchored {

    private Unanchored() {}

    /** The ids of every recipe and drawer in a part of the plan that nothing sets. */
    public static Set<UUID> of(final Graph graph) {
        final Map<UUID, UUID> parent = new HashMap<>();
        for (final Node n : graph.getNodes()) parent.put(n.id, n.id);
        for (final Drawer d : graph.getDrawers()) parent.put(d.getId(), d.getId());
        for (final Edge e : graph.getEdges()) join(parent, e.sourceNodeId, e.targetNodeId);
        for (final Drawer d : graph.getDrawers())
            for (final Drawer.Held h : d.held()) join(
                parent,
                d.getId(),
                h.link()
                    .nodeId());
        for (final Group g : graph.getGroups()) if (g instanceof MachineGroup) {
            UUID first = null;
            for (final UUID id : g.getNodeIds()) {
                if (first == null) first = id;
                else join(parent, first, id);
            }
        }

        final Set<UUID> anchored = new HashSet<>();
        for (final Node n : graph.getNodes()) if (n.isMachineCountFixed() || any(n.targetOutputRates)
            || any(n.targetInputRates)) anchored.add(root(parent, n.id));
        for (final Drawer d : graph.getDrawers()) {
            final Drawer.Rule rule = d.effectiveRule();
            if (rule != Drawer.Rule.ANY && rule != Drawer.Rule.AT_MOST && d.getRate() > 0 && !d.getLinks()
                .isEmpty()) anchored.add(root(parent, d.getId()));
        }
        for (final Group g : graph.getGroups())
            if (g instanceof final MachineGroup m && m.isPinned() && m.getMachineCapacity() > 0)
                for (final UUID id : g.getNodeIds()) if (parent.containsKey(id)) anchored.add(root(parent, id));

        final Set<UUID> out = new HashSet<>();
        for (final UUID id : parent.keySet()) if (!anchored.contains(root(parent, id))) out.add(id);
        return out;
    }

    private static boolean any(final Map<Integer, Double> rates) {
        for (final Double r : rates.values()) if (r != null && r > 0) return true;
        return false;
    }

    private static void join(final Map<UUID, UUID> parent, final UUID a, final UUID b) {
        if (!parent.containsKey(a) || !parent.containsKey(b)) return;
        final UUID ra = root(parent, a), rb = root(parent, b);
        if (!ra.equals(rb)) parent.put(ra, rb);
    }

    private static UUID root(final Map<UUID, UUID> parent, UUID id) {
        while (true) {
            final UUID up = parent.get(id);
            if (up.equals(id)) return id;
            final UUID next = parent.get(up);
            parent.put(id, next);
            id = next;
        }
    }
}
