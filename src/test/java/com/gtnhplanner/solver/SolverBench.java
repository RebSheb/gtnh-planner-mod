package com.gtnhplanner.solver;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.gtnhplanner.data.flowchart.balancer.Balancer;
import com.gtnhplanner.harness.GtnhFlowLoader;

/**
 * How long each corpus plan takes to solve: {@code ./gradlew test -Pbench=<label> --tests '*SolverBench*'}, and
 * {@code -PbenchOnly=<name>} for one plan. Each plan is solved once to warm up, then {@code -Pruns} times (default 3);
 * the least wall and CPU time are reported, the least being what the solver costs and the rest what else the machine
 * was doing.
 */
@Tag("bench")
class SolverBench {

    @BeforeAll
    static void profile() {
        GtnhFlowLoader.ensureDefaultMachineProfile();
    }

    @Test
    void solveTimes() {
        final String only = System.getProperty("gtnhplanner.bench.only", "");
        final int runs = Integer.getInteger("gtnhplanner.bench.runs", 3);
        final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        final List<SolverCorpus.Case> cases = new ArrayList<>(SolverCorpus.cases(4, 10));
        cases.add(new SolverCorpus.Case("platline-x10-joined", SolverCorpus.joined(SolverCorpus.plan("platline"), 10)));
        for (final SolverCorpus.Case c : cases) {
            if (!only.isEmpty() && !c.name()
                .equals(only)) continue;
            Balancer.solveWithAlternatives(c.input());
            long wall = Long.MAX_VALUE, cpu = Long.MAX_VALUE;
            for (int i = 0; i < runs; i++) {
                final long w = System.nanoTime(), t = threads.getCurrentThreadCpuTime();
                Balancer.solveWithAlternatives(c.input());
                cpu = Math.min(cpu, threads.getCurrentThreadCpuTime() - t);
                wall = Math.min(wall, System.nanoTime() - w);
            }
            System.out.printf(
                "[bench] %-36s %4d machines  wall %8.1f ms  cpu %8.1f ms%n",
                c.name(),
                c.input()
                    .machines()
                    .size(),
                wall / 1e6,
                cpu / 1e6);
        }
    }
}
