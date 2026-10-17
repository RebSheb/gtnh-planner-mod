package com.gtnhplanner.data.flowchart;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A task board as chains, the quest book's way (the chain view, {@code ui/task/TaskChainView}): tasks joined by what
 * they wait on, each chain laid out in layers left to right (a task one layer past the furthest of the tasks it waits
 * on, those waiting on nothing first), and the tasks with no link either way on their own at the end. Pure: the view
 * draws what this works out.
 *
 * <p>
 * Inside a layer tasks sit near the tasks they wait on and the tasks they unblock (two sweeps of their neighbours'
 * average place), so arrows cross little. Chains come in the order of their first task on the board, and a loop (two
 * people each adding half of one at once) is broken where it was closed last: its tasks still get a layer each.
 */
public final class TaskChain {

    /** One chain: its layers left to right, each its tasks' ids top to bottom. */
    public record Chain(List<List<String>> layers) {

        /** Its tallest layer: how many rows it takes. */
        public int rows() {
            int n = 0;
            for (final List<String> l : layers) n = Math.max(n, l.size());
            return n;
        }

        /** Its task ids, layer by layer. */
        public List<String> ids() {
            final List<String> out = new ArrayList<>();
            for (final List<String> l : layers) out.addAll(l);
            return out;
        }
    }

    /** The board as chains, and the tasks linked to nothing in the board's order. */
    public record Layout(List<Chain> chains, List<String> loose) {

        /** Every task id: the chains' in order, then the loose ones. */
        public List<String> ids() {
            final List<String> out = new ArrayList<>();
            for (final Chain c : chains) out.addAll(c.ids());
            out.addAll(loose);
            return out;
        }
    }

    private TaskChain() {}

    /** The board laid out as chains. */
    public static Layout of(final Graph g) {
        // The board's order: column by column, top first. Everything below keeps to it where nothing else decides.
        final List<Tasks.Task> board = new ArrayList<>();
        for (final Tasks.Status s : Tasks.Status.values()) board.addAll(TaskEdits.column(g, s));
        final Map<String, Integer> rank = new HashMap<>();
        for (final Tasks.Task t : board) rank.put(t.id(), rank.size());

        // What each task waits on and unblocks, on this board.
        final Map<String, List<String>> waits = new LinkedHashMap<>(), unblocks = new LinkedHashMap<>();
        for (final Tasks.Task t : board) {
            waits.put(t.id(), new ArrayList<>());
            unblocks.put(t.id(), new ArrayList<>());
        }
        for (final Tasks.Task t : board) for (final String b : t.blockedBy()) if (rank.containsKey(b)) {
            waits.get(t.id())
                .add(b);
            unblocks.get(b)
                .add(t.id());
        }

        final List<String> loose = new ArrayList<>();
        final Map<String, Integer> component = new HashMap<>();
        final List<List<String>> components = new ArrayList<>();
        for (final Tasks.Task t : board) {
            final String id = t.id();
            if (component.containsKey(id)) continue;
            if (waits.get(id)
                .isEmpty()
                && unblocks.get(id)
                    .isEmpty()) {
                loose.add(id);
                continue;
            }
            // Everything joined to it either way.
            final List<String> members = new ArrayList<>();
            final List<String> todo = new ArrayList<>(List.of(id));
            component.put(id, components.size());
            while (!todo.isEmpty()) {
                final String at = todo.remove(todo.size() - 1);
                members.add(at);
                for (final List<String> next : List.of(waits.get(at), unblocks.get(at)))
                    for (final String n : next) if (!component.containsKey(n)) {
                        component.put(n, components.size());
                        todo.add(n);
                    }
            }
            members.sort(Comparator.comparing(rank::get));
            components.add(members);
        }

        final List<Chain> chains = new ArrayList<>();
        for (final List<String> members : components) chains.add(chain(members, waits, unblocks, rank));
        return new Layout(chains, loose);
    }

    /** One chain's layers, its tasks placed to keep arrows short and uncrossed. */
    private static Chain chain(final List<String> members, final Map<String, List<String>> waits,
        final Map<String, List<String>> unblocks, final Map<String, Integer> rank) {
        // Layers: one past the furthest task waited on. Taken in waiting order; a loop is broken at its earliest task.
        final Map<String, Integer> layer = new HashMap<>();
        final Map<String, Integer> left = new HashMap<>();
        for (final String m : members) left.put(
            m,
            waits.get(m)
                .size());
        final List<String> ready = new ArrayList<>();
        for (final String m : members) if (left.get(m) == 0) ready.add(m);
        int placed = 0;
        while (placed < members.size()) {
            if (ready.isEmpty()) {
                // A loop: the earliest task left on the board goes next, as if what it waits on in the loop were not.
                for (final String m : members) if (!layer.containsKey(m)) {
                    ready.add(m);
                    break;
                }
            }
            ready.sort(Comparator.comparing(rank::get));
            final String at = ready.remove(0);
            if (layer.containsKey(at)) continue;
            int l = 0;
            for (final String w : waits.get(at)) if (layer.containsKey(w)) l = Math.max(l, layer.get(w) + 1);
            layer.put(at, l);
            placed++;
            for (final String u : unblocks.get(at)) {
                final int n = left.merge(u, -1, Integer::sum);
                if (n == 0 && !layer.containsKey(u)) ready.add(u);
            }
        }
        // A task waiting on nothing sits just before the first task it unblocks, not far back at the start.
        for (final String m : members) {
            if (!waits.get(m)
                .isEmpty()) continue;
            int first = Integer.MAX_VALUE;
            for (final String u : unblocks.get(m)) first = Math.min(first, layer.get(u));
            if (first != Integer.MAX_VALUE && first - 1 > layer.get(m)) layer.put(m, first - 1);
        }
        int depth = 0;
        for (final int l : layer.values()) depth = Math.max(depth, l + 1);
        final List<List<String>> layers = new ArrayList<>();
        for (int i = 0; i < depth; i++) layers.add(new ArrayList<>());
        for (final String m : members) layers.get(layer.get(m))
            .add(m);

        // Two sweeps: each layer by where what it waits on sits, then each by where what it unblocks sits.
        final Map<String, Double> place = new HashMap<>();
        for (final List<String> l : layers) number(l, place);
        for (int i = 1; i < layers.size(); i++) arrange(layers.get(i), waits, place, rank);
        for (int i = layers.size() - 2; i >= 0; i--) arrange(layers.get(i), unblocks, place, rank);
        for (int i = 1; i < layers.size(); i++) arrange(layers.get(i), waits, place, rank);
        return new Chain(layers);
    }

    /** Sorts a layer by the average place of each task's neighbours (the board's order where it has none). */
    private static void arrange(final List<String> l, final Map<String, List<String>> neighbours,
        final Map<String, Double> place, final Map<String, Integer> rank) {
        final Map<String, Double> key = new HashMap<>();
        for (final String id : l) {
            double sum = 0;
            int n = 0;
            for (final String o : neighbours.get(id)) {
                final Double p = place.get(o);
                if (p == null) continue;
                sum += p;
                n++;
            }
            key.put(id, n == 0 ? place.get(id) : sum / n);
        }
        l.sort(
            Comparator.comparing((String id) -> key.get(id))
                .thenComparing(rank::get));
        number(l, place);
    }

    /** Each task's place in its layer, centred, so a short layer lines up with the middle of a tall one. */
    private static void number(final List<String> l, final Map<String, Double> place) {
        for (int i = 0; i < l.size(); i++) place.put(l.get(i), i - (l.size() - 1) / 2.0);
    }
}
