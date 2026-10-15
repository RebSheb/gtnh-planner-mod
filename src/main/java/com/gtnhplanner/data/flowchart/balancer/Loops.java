package com.gtnhplanner.data.flowchart.balancer;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;

/** The loops of a directed graph: its strongly connected components, by Tarjan's algorithm without recursion. */
final class Loops {

    private Loops() {}

    /**
     * Each vertex's loop. Loops are numbered so that every edge from one loop to another runs from a higher number to a
     * lower one: counting down visits the graph of loops in topological order.
     *
     * @param next each vertex's successors.
     */
    static int[] of(final List<List<Integer>> next) {
        final int n = next.size();
        final int[] index = new int[n], low = new int[n], loop = new int[n], cursor = new int[n];
        Arrays.fill(index, -1);
        final boolean[] onStack = new boolean[n];
        final ArrayDeque<Integer> stack = new ArrayDeque<>(), path = new ArrayDeque<>();
        int counter = 0, loops = 0;
        for (int start = 0; start < n; start++) {
            if (index[start] >= 0) continue;
            path.push(start);
            index[start] = low[start] = counter++;
            stack.push(start);
            onStack[start] = true;
            while (!path.isEmpty()) {
                final int v = path.peek();
                if (cursor[v] < next.get(v)
                    .size()) {
                    final int w = next.get(v)
                        .get(cursor[v]++);
                    if (index[w] < 0) {
                        index[w] = low[w] = counter++;
                        stack.push(w);
                        onStack[w] = true;
                        path.push(w);
                    } else if (onStack[w]) low[v] = Math.min(low[v], index[w]);
                    continue;
                }
                path.pop();
                if (!path.isEmpty()) low[path.peek()] = Math.min(low[path.peek()], low[v]);
                if (low[v] == index[v]) {
                    int w;
                    do {
                        w = stack.pop();
                        onStack[w] = false;
                        loop[w] = loops;
                    } while (w != v);
                    loops++;
                }
            }
        }
        // Tarjan completes a loop only after every loop it reaches: the first completed has no successor loop.
        return loop;
    }
}
