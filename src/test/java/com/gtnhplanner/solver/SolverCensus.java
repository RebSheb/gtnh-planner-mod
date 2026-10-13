package com.gtnhplanner.solver;

import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.gtnhplanner.data.flowchart.balancer.BalanceMode;
import com.gtnhplanner.data.flowchart.balancer.Balancer;
import com.gtnhplanner.data.flowchart.balancer.Profiler;
import com.gtnhplanner.harness.GtnhFlowLoader;

/**
 * How many models each plan's solve runs, by label, and how big they are:
 * {@code ./gradlew test -Pbench=census --tests '*SolverCensus*'}. The chain only (the alternatives search runs outside
 * the profiler); timeless, so every model a solve would run is counted.
 */
@Tag("bench")
class SolverCensus {

    @BeforeAll
    static void setUp() {
        GtnhFlowLoader.ensureDefaultMachineProfile();
    }

    @AfterAll
    static void tearDown() {}

    @Test
    void census() {
        for (final SolverCorpus.Case c : SolverCorpus.cases(4, 10)) {
            final Map<String, long[]> byLabel = new TreeMap<>();
            final int[] size = new int[4];
            Balancer.run(c.input(), new Profiler() {

                @Override
                public void runStarted(final BalanceMode mode, final int machines, final int ports, final int gates,
                    final int edges) {
                    size[0] = machines;
                    size[1] = ports;
                    size[2] = gates;
                    size[3] = edges;
                }

                @Override
                public void modelSolved(final String label, final long wallMillis, final String state, final int vars,
                    final int exprs) {
                    final long[] row = byLabel.computeIfAbsent(label, k -> new long[4]);
                    row[0]++;
                    row[1] += wallMillis;
                    row[2] = Math.max(row[2], vars);
                    row[3] = Math.max(row[3], exprs);
                }

                @Override
                public boolean enabled() {
                    return true;
                }
            });
            final StringBuilder line = new StringBuilder(
                String.format(
                    "[census] %-36s %4d machines %4d ports %4d gates %4d wires |",
                    c.name(),
                    size[0],
                    size[1],
                    size[2],
                    size[3]));
            for (final Map.Entry<String, long[]> e : byLabel.entrySet()) {
                final long[] r = e.getValue();
                line.append(String.format(" %s x%d %dms (%dv/%de)", e.getKey(), r[0], r[1], r[2], r[3]));
            }
            System.out.println(line);
        }
    }
}
