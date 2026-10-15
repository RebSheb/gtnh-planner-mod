package com.gtnhplanner.data.flowchart.balancer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.data.flowchart.Drawer;

/**
 * Where AUTO trades, on farm, mill and oven, each turning one thing into one at a craft a second: it buys as far
 * upstream as it can and sells as far downstream as it can, and a machine nothing pinned feeds or needs stays at zero.
 */
class TradeRuleTest {

    private static final UUID FARM = new UUID(0, 1), MILL = new UUID(0, 2), OVEN = new UUID(0, 3),
        CAKE = new UUID(0, 4);

    private static SolveInput.PortIn port(final int resource, final String name) {
        return new SolveInput.PortIn(1, 1f, 1, resource, name, false);
    }

    private static SolveInput.Machine machine(final UUID id, final String name, final List<SolveInput.PortIn> inputs,
        final List<SolveInput.PortIn> outputs, final int pinnedCount, final Map<Integer, Double> wanted) {
        return new SolveInput.Machine(
            id,
            name,
            20,
            1,
            1,
            inputs,
            outputs,
            pinnedCount > 0,
            Math.max(1, pinnedCount),
            wanted,
            Map.of());
    }

    /**
     * The chain, the farm pinned at {@code farms} (0 for unpinned), the oven asked for {@code bread} a second (0 for
     * nothing asked), and with {@code cake} a cake maker on the flour line that nothing asks for.
     */
    private static SolutionView bread(final int farms, final double bread, final boolean cake) {
        final List<SolveInput.Machine> machines = new ArrayList<>(
            List.of(
                machine(FARM, "farm", List.of(), List.of(port(0, "wheat")), farms, Map.of()),
                machine(MILL, "mill", List.of(port(0, "wheat")), List.of(port(1, "flour")), 0, Map.of()),
                machine(
                    OVEN,
                    "oven",
                    List.of(port(1, "flour")),
                    List.of(port(2, "bread")),
                    0,
                    bread > 0 ? Map.of(0, bread) : Map.of())));
        final List<SolveInput.EdgeIn> edges = new ArrayList<>(
            List.of(
                new SolveInput.EdgeIn(new UUID(1, 1), FARM, 0, MILL, 0),
                new SolveInput.EdgeIn(new UUID(1, 2), MILL, 0, OVEN, 0)));
        if (cake) {
            machines.add(machine(CAKE, "cake maker", List.of(port(1, "flour")), List.of(port(3, "cake")), 0, Map.of()));
            edges.add(new SolveInput.EdgeIn(new UUID(1, 3), MILL, 0, CAKE, 0));
        }
        final SolveInput input = new SolveInput(
            BalanceMode.AUTO,
            null,
            machines,
            edges,
            List.of(),
            List.of(),
            Map.of());
        return assertInstanceOf(Balancer.Answer.Solved.class, Balancer.solveWithAlternatives(input)).solution();
    }

    /** What is traded at one machine's port, in units a second; 0 when nothing is. */
    private static double traded(final List<External> trades, final UUID machine) {
        double total = 0;
        for (final External e : trades) if (e.port()
            .nodeId()
            .equals(machine)) total += e.ratePerSecond();
        return total;
    }

    @Test
    void shortOfWheat_buysWheatAndRunsTheMill() {
        final SolutionView s = bread(5, 10, false);
        assertEquals(5, traded(s.gatedSources, MILL), 1e-9, "wheat bought at the mill");
        assertEquals(0, traded(s.gatedSources, OVEN), 1e-9, "no flour bought");
        assertEquals(10, s.machineCounts.get(MILL), 1e-9);
        assertEquals(10, s.machineCounts.get(OVEN), 1e-9);
        assertEquals(List.of(), s.gatedSinks);
    }

    @Test
    void spareWheat_isMilledAndSoldAsFlour() {
        final SolutionView s = bread(15, 10, false);
        assertEquals(5, traded(s.gatedSinks, MILL), 1e-9, "flour sold from the mill");
        assertEquals(0, traded(s.gatedSinks, FARM), 1e-9, "no wheat sold");
        assertEquals(15, s.machineCounts.get(MILL), 1e-9);
        assertEquals(10, s.machineCounts.get(OVEN), 1e-9);
        assertEquals(List.of(), s.gatedSources);
    }

    @Test
    void spareFlour_goesOnToTheCakeMakerRatherThanBeingSold() {
        final SolutionView s = bread(15, 10, true);
        assertEquals(15, s.machineCounts.get(MILL), 1e-9);
        assertEquals(10, s.machineCounts.get(OVEN), 1e-9);
        assertEquals(5, s.machineCounts.get(CAKE), 1e-9);
        assertEquals(List.of(), s.gatedSources);
        assertEquals(List.of(), s.gatedSinks);
    }

    @Test
    void onlyTheFarmPinned_whatItGrowsGoesAllTheWayToBread() {
        final SolutionView s = bread(10, 0, false);
        assertEquals(10, s.machineCounts.get(MILL), 1e-9);
        assertEquals(10, s.machineCounts.get(OVEN), 1e-9);
        assertEquals(List.of(), s.gatedSources);
        assertEquals(List.of(), s.gatedSinks);
        assertTrue(s.unneeded.isEmpty());
    }

    @Test
    void aMachineNothingPinnedFeedsOrNeeds_staysAtZeroAndAsksForACount() {
        final SolutionView s = bread(0, 10, true);
        assertEquals(10, s.machineCounts.get(FARM), 1e-9);
        assertEquals(10, s.machineCounts.get(MILL), 1e-9);
        assertEquals(0, s.machineCounts.get(CAKE), 1e-9);
        assertEquals(java.util.Set.of(CAKE), s.unneeded);
    }

    /**
     * Farm, mill, oven and cake maker in a line (wheat, flour, bread, cake), the farm pinned at 10, and with
     * {@code cakes} above zero the cake maker pinned at that many machines (a craft a second each).
     */
    private static SolutionView line(final int cakes) {
        return line(cakes, null);
    }

    /**
     * As {@link #line(int)}, the cake maker's cake going to a product drawer asking for 5 a second under {@code rule}.
     */
    private static SolutionView line(final int cakes, @javax.annotation.Nullable final Drawer.Rule rule) {
        final List<SolveInput.Machine> machines = List.of(
            machine(FARM, "farm", List.of(), List.of(port(0, "wheat")), 10, Map.of()),
            machine(MILL, "mill", List.of(port(0, "wheat")), List.of(port(1, "flour")), 0, Map.of()),
            machine(OVEN, "oven", List.of(port(1, "flour")), List.of(port(2, "bread")), 0, Map.of()),
            machine(CAKE, "cake maker", List.of(port(2, "bread")), List.of(port(3, "cake")), cakes, Map.of()));
        final List<SolveInput.EdgeIn> edges = List.of(
            new SolveInput.EdgeIn(new UUID(1, 1), FARM, 0, MILL, 0),
            new SolveInput.EdgeIn(new UUID(1, 2), MILL, 0, OVEN, 0),
            new SolveInput.EdgeIn(new UUID(1, 3), OVEN, 0, CAKE, 0));
        final List<SolveInput.DrawerIn> drawers = rule == null ? List.of()
            : List.of(
                new SolveInput.DrawerIn(
                    new UUID(2, 1),
                    "cake",
                    Drawer.Kind.PRODUCT,
                    rule,
                    5,
                    List.of(new Drawer.Link(CAKE, 0))));
        final SolveInput input = new SolveInput(BalanceMode.AUTO, null, machines, edges, List.of(), drawers, Map.of());
        return assertInstanceOf(Balancer.Answer.Solved.class, Balancer.solveWithAlternatives(input)).solution();
    }

    @Test
    void onlyTheWheatPinned_itAllBecomesCake() {
        final SolutionView s = line(0);
        assertEquals(10, s.machineCounts.get(MILL), 1e-9);
        assertEquals(10, s.machineCounts.get(OVEN), 1e-9);
        assertEquals(10, s.machineCounts.get(CAKE), 1e-9);
        assertEquals(List.of(), s.gatedSources);
        assertEquals(List.of(), s.gatedSinks);
    }

    @Test
    void cakesHeldAtFive_theSpareIsSoldAsBreadRightBeforeThem() {
        final SolutionView s = line(5);
        assertEquals(10, s.machineCounts.get(MILL), 1e-9);
        assertEquals(10, s.machineCounts.get(OVEN), 1e-9);
        assertEquals(5, s.machineCounts.get(CAKE), 1e-9);
        assertEquals(5, traded(s.gatedSinks, OVEN), 1e-9, "bread sold at the oven");
        assertEquals(5, traded(s.gatedSinks, OVEN) + traded(s.gatedSinks, MILL) + traded(s.gatedSinks, FARM), 1e-9);
        assertEquals(List.of(), s.gatedSources);
    }

    @Test
    void aCakeDrawerTakingFive_theSpareIsSoldAsBreadRightBeforeTheCakes() {
        // Every cake goes to the drawer, and it takes no more than 5: the last place anything can leave is the bread.
        for (final Drawer.Rule rule : List.of(Drawer.Rule.EXACTLY, Drawer.Rule.AT_MOST)) {
            final SolutionView s = line(0, rule);
            assertEquals(10, s.machineCounts.get(OVEN), 1e-9, rule.name());
            assertEquals(5, s.machineCounts.get(CAKE), 1e-9, rule.name());
            assertEquals(5, traded(s.gatedSinks, OVEN), 1e-9, rule.name() + ": bread sold at the oven");
            assertEquals(1, s.gatedSinks.size(), rule.name());
            assertEquals(List.of(), s.gatedSources, rule.name());
        }
        // At least 5: all of it becomes cake and the drawer takes the 10.
        final SolutionView s = line(0, Drawer.Rule.AT_LEAST);
        assertEquals(10, s.machineCounts.get(CAKE), 1e-9);
        assertEquals(List.of(), s.gatedSinks);
    }
}
