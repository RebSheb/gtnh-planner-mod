package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Serializer;
import com.gtnhplanner.data.flowchart.balancer.BalanceMode;
import com.gtnhplanner.data.flowchart.balancer.Balancer;
import com.gtnhplanner.data.flowchart.balancer.Balancer.Answer;
import com.gtnhplanner.data.flowchart.balancer.ChoiceKey;
import com.gtnhplanner.data.flowchart.balancer.SolverMessage;
import com.gtnhplanner.data.flowchart.balancer.alternatives.Alternative;
import com.gtnhplanner.data.flowchart.balancer.alternatives.Alternatives;
import com.gtnhplanner.data.flowchart.balancer.alternatives.Rank;
import com.gtnhplanner.harness.GtnhFlowLoader;
import com.gtnhplanner.harness.GtnhFlowLoader.LoadedChart;

class AlternativesTest {

    private static Alternatives alternatives(final String chart) {
        return Balancer.alternatives(
            BalanceMode.AUTO,
            GtnhFlowLoader.load(chart)
                .graph());
    }

    @Test
    void symmetricChoice_sellingIngotEarlierIsListedWithItsReason() {
        final Alternatives a = alternatives("symmetric_choice");

        // Both benders run full and the spare leaves as plate, at the brick wall: the furnaces' ingot goes on to every
        // machine that takes it. Selling ingot at either furnace instead is a real answer somebody might want (one
        // bender runs half), listed with why it is not the default.
        assertEquals(
            3,
            a.options()
                .size());
        assertEquals(
            Rank.DEFAULT,
            a.options()
                .get(0)
                .rank());
        for (int i = 1; i < 3; i++) assertEquals(
            Rank.SELLS_MORE,
            a.options()
                .get(i)
                .rank(),
            "selling ingot sells earlier in the line than selling plate");
        assertTrue(a.complete(), "small chart, the search finishes");
        for (final Alternative option : a.options()) {
            assertEquals(
                1,
                option.externals()
                    .size(),
                "each leaves its surplus in one place");
            assertFalse(
                option.externals()
                    .get(0)
                    .port()
                    .input(),
                "and leaves it rather than importing");
            assertEquals(
                5.0,
                option.externals()
                    .get(0)
                    .ratePerSecond(),
                1e-4);
        }
        assertEquals(
            3,
            a.options()
                .stream()
                .map(
                    o -> o.externals()
                        .get(0)
                        .port())
                .distinct()
                .count(),
            "and each leaves it somewhere different");
    }

    @Test
    void excessChoice_theRejectedOptionIsListedWithItsReason() {
        // The default sends all the nitrogen on to the oven and sells charcoal where the second oven
        // takes no more. Selling nitrogen at the centrifuge is not wrong, it just sells earlier in the
        // line - a real answer somebody might want, so it must be listed rather than deleted.
        final Alternatives a = alternatives("excess_choice");

        assertEquals(
            2,
            a.options()
                .size());
        assertEquals(
            Rank.DEFAULT,
            a.options()
                .get(0)
                .rank());
        // Both sell; the default sells further down the line, so the other sells earlier in it - a real
        // answer somebody might want, so the loser is listed instead of deleted.
        assertEquals(
            Rank.SELLS_MORE,
            a.options()
                .get(1)
                .rank());
        assertEquals(
            10.6,
            a.options()
                .get(0)
                .externals()
                .get(0)
                .ratePerSecond(),
            1e-4,
            "default: charcoal");
        assertEquals(
            530.0,
            a.options()
                .get(1)
                .externals()
                .get(0)
                .ratePerSecond(),
            1e-4,
            "alternative: nitrogen");
    }

    @Test
    void mk1_theSourceSinkTiltIsShownRatherThanHidden() {
        // The planner buys only what the pins need, so leaving a surplus beats importing; the import
        // it passes over still has to appear in the list.
        final Alternatives a = alternatives("mk1");

        assertEquals(
            2,
            a.options()
                .size());
        assertEquals(
            Rank.DEFAULT,
            a.options()
                .get(0)
                .rank());
        assertEquals(
            Rank.BUYS_MORE,
            a.options()
                .get(1)
                .rank());
        assertFalse(
            a.options()
                .get(0)
                .externals()
                .get(0)
                .port()
                .input(),
            "the default leaves a surplus");
        assertTrue(
            a.options()
                .get(1)
                .externals()
                .get(0)
                .port()
                .input(),
            "the alternative imports");
    }

    @Test
    void everyChartEnumeratesWithinBudget_andAdmitsWhenItStoppedEarly() {
        // The list is opened by a click, so it may cost a beat - but never a hang, and never a
        // claim of completeness it did not earn. palladium_line has ~1100 candidate swaps and is
        // capped at 64; what it skipped has to reach the user.
        for (final String chart : GtnhFlowLoader.CORPUS) {
            final long start = System.currentTimeMillis();
            final Alternatives a = alternatives(chart);
            final long elapsed = System.currentTimeMillis() - start;
            assertTrue(elapsed < 15_000, () -> chart + " took " + elapsed + "ms to enumerate");
            if (!a.complete()) {
                assertTrue(
                    a.notes()
                        .stream()
                        .anyMatch(
                            n -> n.message() == SolverMessage.STOPPED_EARLY
                                || n.message() == SolverMessage.SHOWING_CLOSEST),
                    () -> chart + " truncated its list without saying so: " + a.notes());
            }
            // One current answer per decision, not one for the chart: a chart with three open
            // gates is asking three questions and each needs its own "this is what it does now".
            for (final var decision : a.options()
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(Alternative::replaces))
                .entrySet()) {
                assertEquals(
                    1,
                    decision.getValue()
                        .stream()
                        .filter(o -> o.rank() == Rank.DEFAULT)
                        .count(),
                    () -> chart + " decision " + decision.getKey() + " must have exactly one current answer");
            }
        }
    }

    @Test
    void loopGraph_theRejectedAcidSourceIsStillOnOffer() {
        // The hand-derived answer sources diluted acid at the DT. Sourcing sulfuric at the LCR
        // instead is what stage 3 rejected on internal flow - a real preference, so it is listed.
        final Alternatives a = alternatives("loopGraph");

        assertEquals(
            2,
            a.options()
                .size());
        assertEquals(
            Rank.DEFAULT,
            a.options()
                .get(0)
                .rank());
        assertTrue(
            a.options()
                .get(1)
                .externals()
                .get(0)
                .port()
                .input(),
            "the alternative is also an import");
        assertTrue(
            a.options()
                .get(1)
                .rank() == Rank.MOVES_MORE
                || a.options()
                    .get(1)
                    .rank() == Rank.BUYS_MORE,
            () -> "and it lost on a comparison, not a coin flip: " + a.options()
                .get(1)
                .rank());
    }

    @Test
    void unambiguousCharts_askNothing() {
        // Nothing crosses the boundary at a wired port, so there is no decision to present - not
        // even a one-row list saying so, which would be a question with no question in it.
        for (final String chart : List.of("light_fuel", "nanocircuits", "light_fuel_hydrogen_loop")) {
            assertTrue(
                alternatives(chart).options()
                    .isEmpty(),
                chart + " balances with no gates");
        }
    }

    @Test
    void aChosenAlternativeIsHonouredAndSurvivesTheSolve() {
        final LoadedChart chart = GtnhFlowLoader.load("symmetric_choice");
        final Alternatives a = alternatives("symmetric_choice");
        final Alternative other = a.options()
            .get(1);

        final Answer picked = Balancer.solveWithAlternatives(BalanceMode.AUTO, chart.graph(), other.key(), Map.of());
        final Answer.Solved solved = solved(picked);
        assertEquals(other.key(), solved.solution().key, "the chart came back on the chosen support");
        assertEquals(
            other.externals()
                .get(0)
                .port(),
            solved.solution().gatedSinks.get(0)
                .port(),
            "surplus leaves where the user asked");
    }

    @Test
    void aChoiceSurvivesEncodeAndDecode() {
        final LoadedChart chart = GtnhFlowLoader.load("symmetric_choice");
        final Alternatives a = alternatives("symmetric_choice");
        final ChoiceKey picked = a.options()
            .get(1)
            .key();
        // Graph speaks the balancer package's ChoiceKey directly: gate anchors are ports, not gate
        // indices, so the key itself survives a save with nothing in it a rebuild could invalidate.
        chart.graph()
            .setExcessChoice(picked);

        // Only the key is asserted here: Serializer cannot rebuild ports for the harness's
        // Minecraft-free ingredient type, so a reloaded corpus chart has no ports to re-solve
        // against. What matters is that the key itself is node ids and port indices, with nothing
        // in it that a rebuild could invalidate.
        final Graph reloaded = Serializer.decode(Serializer.encode(chart.graph()));
        assertEquals(picked, reloaded.getExcessChoice(), "gate anchors are ports, so they survive a save");
    }

    @Test
    void aChoiceThatNoLongerFitsFallsBackWithANote() {
        final LoadedChart chart = GtnhFlowLoader.load("symmetric_choice");
        final Alternatives a = alternatives("symmetric_choice");
        // A key from a different chart names ports this one does not have.
        final Alternatives other = alternatives("mk1");

        final Answer r = Balancer.solveWithAlternatives(BalanceMode.AUTO, chart.graph(), other.chosen(), Map.of());
        final Answer.Solved solved = solved(r);
        assertEquals(a.chosen(), solved.solution().key, "fell back to the solver's own answer");
        assertTrue(
            solved.solution().notes.stream()
                .anyMatch(
                    n -> n.message() == SolverMessage.CHOICE_NO_LONGER_FITS
                        || n.message() == SolverMessage.CHOICE_NEEDS_MORE_GATES),
            () -> "and said so: " + solved.solution().notes);
    }

    private static Answer.Solved solved(final Answer answer) {
        if (answer instanceof final Answer.Solved solved) return solved;
        throw new AssertionError("solve failed: " + describe(answer));
    }

    private static String describe(final Answer answer) {
        return answer instanceof final Answer.Failed failed ? failed.failure().describe() : answer.toString();
    }
}
