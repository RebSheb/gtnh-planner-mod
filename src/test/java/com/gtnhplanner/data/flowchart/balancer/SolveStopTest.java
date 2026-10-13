package com.gtnhplanner.data.flowchart.balancer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.harness.GtnhFlowLoader;
import com.gtnhplanner.harness.GtnhFlowLoader.LoadedChart;

/**
 * A solve a newer edit made worthless stops before its next model, and only then: the stop signal is the one thing
 * that ends a solve early, never the clock.
 */
class SolveStopTest {

    @Test
    void aSolveNobodyStoppedRunsToTheEnd() {
        final SolveResult result = assertDoesNotThrow(() -> Solver.flowMinimal(context(() -> false), Set.of(), 0.0));
        assertFalse(result.isRejected(), "light_fuel balances gate-free: " + result.rejection());
    }

    @Test
    void aStoppedSolveEndsBeforeItsNextModel() {
        assertThrows(Balancer.Stopped.class, () -> Solver.flowMinimal(context(() -> true), Set.of(), 0.0));
    }

    private static SolveContext context(final BooleanSupplier stop) {
        final LoadedChart chart = GtnhFlowLoader.load("light_fuel");
        final SolveInput input = SolveInput.of(chart.graph(), BalanceMode.AUTO, null);
        return new SolveContext(
            input,
            Heuristics.auto(),
            stop,
            Map.of(),
            BalanceMode.AUTO.pins(),
            true,
            Profiler.disabled());
    }
}
