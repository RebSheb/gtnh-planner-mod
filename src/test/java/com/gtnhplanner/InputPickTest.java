package com.gtnhplanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Drawer.Kind;
import com.gtnhplanner.data.flowchart.Drawer.Link;
import com.gtnhplanner.data.flowchart.Drawer.Rule;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Serializer;
import com.gtnhplanner.data.flowchart.balancer.BalanceMode;
import com.gtnhplanner.data.flowchart.balancer.Balancer;
import com.gtnhplanner.data.flowchart.balancer.Balancer.Answer;
import com.gtnhplanner.data.flowchart.balancer.SolutionView;
import com.gtnhplanner.harness.GtnhFlowLoader;
import com.gtnhplanner.harness.GtnhFlowLoader.LoadedChart;
import com.gtnhplanner.harness.TestIngredients;

/**
 * An input whose recipe slot takes several items (issue 496: the blast furnace's Coal Dust, which takes Carbon Dust
 * too) set to another of them: the port becomes that item, the save keeps the pick, and the solve wires and counts it.
 */
class InputPickTest {

    private static final double EPS = 1e-6;

    /**
     * A carbonizer making carbon, and a blast furnace taking iron and a coal slot that takes carbon or charcoal too.
     */
    private static LoadedChart steelLine() {
        final LoadedChart chart = GtnhFlowLoader.parse(
            "steel",
            "- {m: carbonizer, dur: 1, I: {wood: 1}, O: {carbon: 1}}\n"
                + "- {m: ebf, dur: 1, I: {iron: 1, coal: 1}, O: {steel: 1}}");
        chart.machine(1).inputs.set(1, TestIngredients.choice(1, "coal", "carbon", "charcoal"));
        return chart;
    }

    @Test
    void aPickSetsThePortAndClearingItPutsTheRecipesOwnBack() {
        final Node ebf = steelLine().machine(1);
        assertTrue(
            ebf.inputs.get(1)
                .hasChoices());
        assertFalse(
            ebf.inputs.get(0)
                .hasChoices(),
            "iron takes iron only");

        assertTrue(ebf.pickInput(1, "carbon"));
        assertEquals("carbon", TestIngredients.nameOf(ebf.inputs.get(1)));
        assertEquals(1, TestIngredients.quantityOf(ebf.inputs.get(1)), EPS, "the amount stays the recipe's");
        assertEquals(Map.of(1, "carbon"), ebf.inputPicks);
        assertEquals(
            3,
            ebf.inputs.get(1)
                .getAlternatives()
                .size(),
            "it still lists every item the slot takes");

        assertFalse(ebf.pickInput(1, "sand"), "not one the slot takes");
        assertFalse(ebf.pickInput(0, "carbon"), "iron cannot be set to anything else");
        assertEquals("carbon", TestIngredients.nameOf(ebf.inputs.get(1)));

        assertTrue(ebf.pickInput(1, "coal"));
        assertEquals("coal", TestIngredients.nameOf(ebf.inputs.get(1)));
        assertTrue(ebf.inputPicks.isEmpty(), "the recipe's own item is no pick");
    }

    @Test
    void aPickSurvivesASave() {
        final LoadedChart chart = steelLine();
        final Node ebf = chart.machine(1);
        ebf.pickInput(1, "charcoal");
        final Graph back = Serializer.decode(Serializer.encode(chart.graph()));
        assertEquals(Map.of(1, "charcoal"), back.nodes.get(ebf.id).inputPicks);
    }

    @Test
    void theSolveRunsOnThePickedItem() {
        final LoadedChart chart = steelLine();
        final Graph g = chart.graph();
        final Node carbonizer = chart.machine(0), ebf = chart.machine(1);
        assertEquals(-1, g.findCompatibleInput(carbonizer, 0, ebf), "coal dust takes no carbon");

        ebf.pickInput(1, "carbon");
        final int in = g.findCompatibleInput(carbonizer, 0, ebf);
        assertEquals(1, in, "set to carbon, it does");
        g.addEdge(new Edge(UUID.randomUUID(), carbonizer.id, ebf.id, 0, in));
        final Drawer steel = new Drawer(Kind.PRODUCT, "test:steel");
        steel.setTarget(Rule.EXACTLY, 3);
        g.addDrawer(steel);
        g.linkDrawer(steel.getId(), new Link(ebf.id, 0));

        final Answer answer = Balancer.solveWithAlternatives(BalanceMode.AUTO, g, null, Map.of());
        if (!(answer instanceof final Answer.Solved solved)) throw new AssertionError("failed: " + answer);
        final SolutionView s = solved.solution();
        assertEquals(3, s.machineCounts.get(ebf.id), EPS);
        assertEquals(3, s.machineCounts.get(carbonizer.id), EPS, "the carbon is made on the plan");
    }
}
