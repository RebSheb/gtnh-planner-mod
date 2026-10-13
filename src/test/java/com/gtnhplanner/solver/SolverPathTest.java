package com.gtnhplanner.solver;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.gtnhplanner.data.flowchart.balancer.Balancer;
import com.gtnhplanner.data.flowchart.balancer.ModelBuilder;
import com.gtnhplanner.harness.GtnhFlowLoader;

/**
 * The answer is the plan's, not the solve path's: every corpus plan answers the same when every model adds its
 * variables
 * in reverse order (a different path through the simplex to the same optima, often a different vertex among tied
 * ones), and when solved again later in the same game.
 */
class SolverPathTest {

    @BeforeAll
    static void setUp() {
        GtnhFlowLoader.ensureDefaultMachineProfile();
    }

    @AfterAll
    static void tearDown() {
        ModelBuilder.reversed(false);
    }

    @Test
    void answersDoNotDependOnHowTheyWereFound() {
        final Map<String, JsonElement> first = solveAll(false);
        final Map<String, Map<String, JsonElement>> others = new LinkedHashMap<>();
        others.put("built in reverse", solveAll(true));
        others.put("solved again", solveAll(false));
        final StringBuilder report = new StringBuilder();
        for (final String name : first.keySet()) {
            for (final Map.Entry<String, Map<String, JsonElement>> other : others.entrySet()) {
                final List<String> d = SolverCorpus.differences(
                    first.get(name),
                    other.getValue()
                        .get(name));
                if (!d.isEmpty()) report.append(name)
                    .append(' ')
                    .append(other.getKey())
                    .append(": ")
                    .append(String.join("; ", d.subList(0, Math.min(4, d.size()))))
                    .append('\n');
            }
        }
        assertTrue(report.length() == 0, report.toString());
    }

    private static Map<String, JsonElement> solveAll(final boolean reversed) {
        ModelBuilder.reversed(reversed);
        try {
            final Map<String, JsonElement> out = new LinkedHashMap<>();
            for (final SolverCorpus.Case c : SolverCorpus.cases()) {
                out.put(c.name(), SolverCorpus.digest(Balancer.solveWithAlternatives(c.input())));
            }
            return out;
        } finally {
            ModelBuilder.reversed(false);
        }
    }
}
