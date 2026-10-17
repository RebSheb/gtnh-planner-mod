package com.gtnhplanner.data.flowchart.balancer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.gtnhplanner.data.flowchart.Drawer;

/**
 * A plan's islands: the parts nothing joins, each a plan of its own. Machines are joined by a wire, by a capped
 * shared machine (one pool row over its recipes) and by a drawer (one row over every port it is linked to), so no
 * row of any model ever spans two islands and each solves exactly as it would alone: an island never changes because
 * of another, a broken one leaves the rest solved, and an edit re-solves only its own ({@link Balancer}).
 *
 * <p>
 * Each island's snapshot keeps the plan's order of machines, wires, pools and drawers, the extent pins on its
 * machines, and the stored leftover splits and choice anchors that name them, so it is equal to the same island's
 * snapshot from any plan around it.
 */
final class Islands {

    private Islands() {}

    /** The plan's islands, in the order of their first machine; the plan itself when it is one. */
    static List<SolveInput> of(final SolveInput input) {
        final List<SolveInput.Machine> machines = input.machines();
        final int n = machines.size();
        if (n <= 1) return List.of(input);
        final Map<UUID, Integer> index = new HashMap<>(n * 2);
        for (int m = 0; m < n; m++) index.put(
            machines.get(m)
                .id(),
            m);
        final int[] root = new int[n];
        for (int m = 0; m < n; m++) root[m] = m;
        for (final SolveInput.EdgeIn e : input.edges()) join(root, index.get(e.source()), index.get(e.target()));
        for (final SolveInput.PoolIn pool : input.pools()) {
            Integer first = null;
            for (final UUID id : pool.machines()) {
                final Integer m = index.get(id);
                if (m == null) continue;
                if (first == null) first = m;
                else join(root, first, m);
            }
        }
        for (final SolveInput.DrawerIn drawer : input.drawers()) {
            Integer first = null;
            for (final Drawer.Link link : drawer.allLinks()) {
                final Integer m = index.get(link.nodeId());
                if (m == null) continue;
                if (first == null) first = m;
                else join(root, first, m);
            }
        }

        // Islands numbered by their first machine.
        final int[] island = new int[n];
        final Map<Integer, Integer> numberOf = new LinkedHashMap<>();
        for (int m = 0; m < n; m++) {
            island[m] = numberOf.computeIfAbsent(find(root, m), r -> numberOf.size());
        }
        final int count = numberOf.size();
        if (count == 1) return List.of(input);

        final List<List<SolveInput.Machine>> ms = lists(count);
        final List<List<SolveInput.EdgeIn>> es = lists(count);
        final List<List<SolveInput.PoolIn>> ps = lists(count);
        final List<List<SolveInput.DrawerIn>> ds = lists(count);
        final List<Map<UUID, Double>> pins = new ArrayList<>(count);
        final List<Set<UUID>> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            pins.add(new LinkedHashMap<>());
            ids.add(new HashSet<>());
        }
        for (int m = 0; m < n; m++) {
            final SolveInput.Machine machine = machines.get(m);
            ms.get(island[m])
                .add(machine);
            ids.get(island[m])
                .add(machine.id());
            final Double pin = input.extentPins()
                .get(machine.id());
            if (pin != null) pins.get(island[m])
                .put(machine.id(), pin);
        }
        for (final SolveInput.EdgeIn e : input.edges()) es.get(island[index.get(e.source())])
            .add(e);
        for (final SolveInput.PoolIn pool : input.pools()) {
            final int at = islandOf(pool.machines(), index, island);
            ps.get(at < 0 ? 0 : at)
                .add(pool);
        }
        for (final SolveInput.DrawerIn drawer : input.drawers()) {
            final List<UUID> linked = new ArrayList<>();
            for (final Drawer.Link link : drawer.allLinks()) linked.add(link.nodeId());
            final int at = islandOf(linked, index, island);
            ds.get(at < 0 ? 0 : at)
                .add(drawer);
        }

        final List<SolveInput> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(
                new SolveInput(
                    input.mode(),
                    choiceIn(input.choice(), ids.get(i)),
                    ms.get(i),
                    es.get(i),
                    ps.get(i),
                    ds.get(i),
                    pins.get(i),
                    sparesIn(input.spares(), ids.get(i))));
        }
        return out;
    }

    /** The island a group of machines sits in (they share one), or -1 when it names none of the plan's. */
    private static int islandOf(final List<UUID> members, final Map<UUID, Integer> index, final int[] island) {
        for (final UUID id : members) {
            final Integer m = index.get(id);
            if (m != null) return island[m];
        }
        return -1;
    }

    /** The anchors of a stored choice that sit on the island's machines, or null when none do. */
    private static ChoiceKey choiceIn(final ChoiceKey choice, final Set<UUID> machines) {
        if (choice == null) return null;
        final List<PortRef> anchors = new ArrayList<>();
        for (final PortRef anchor : choice.gateAnchors()) if (machines.contains(anchor.nodeId())) anchors.add(anchor);
        return anchors.isEmpty() ? null : new ChoiceKey(List.copyOf(anchors));
    }

    /** The stored leftover splits whose places sit on the island's machines. */
    private static Map<String, Map<String, Double>> sparesIn(final Map<String, Map<String, Double>> spares,
        final Set<UUID> machines) {
        if (spares.isEmpty()) return spares;
        final Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        for (final Map.Entry<String, Map<String, Double>> e : spares.entrySet()) {
            for (final String bearer : e.getValue()
                .keySet()) {
                if (machines.contains(nodeOf(bearer))) {
                    out.put(e.getKey(), e.getValue());
                    break;
                }
            }
        }
        return out;
    }

    /** The node of a bearer key ({@link Spares#keyOf}: {@code node:i:port} or {@code node:o:port}). */
    private static UUID nodeOf(final String bearer) {
        final int colon = bearer.indexOf(':');
        try {
            return UUID.fromString(colon < 0 ? bearer : bearer.substring(0, colon));
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    private static <T> List<List<T>> lists(final int count) {
        final List<List<T>> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) out.add(new ArrayList<>());
        return out;
    }

    private static void join(final int[] root, final Integer a, final Integer b) {
        if (a == null || b == null) return;
        final int ra = find(root, a), rb = find(root, b);
        if (ra != rb) root[Math.max(ra, rb)] = Math.min(ra, rb);
    }

    private static int find(final int[] root, int m) {
        while (root[m] != m) {
            root[m] = root[root[m]];
            m = root[m];
        }
        return m;
    }
}
