package com.gtnhplanner.data.flowchart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Reordering the open plans' tabs: closed plans keep their places, the active plan stays active, the order is saved.
 */
class PlanReorderTest {

    private static Plan plan(final String... names) throws ReflectiveOperationException {
        final java.lang.reflect.Constructor<Plan> constructor = Plan.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        final Plan plan = constructor.newInstance();
        for (final String name : names) plan.getGraphs()
            .add(new Graph(name));
        return plan;
    }

    private static String names(final Plan plan) {
        final StringBuilder out = new StringBuilder();
        for (final int i : plan.openSlots()) out.append(
            plan.getGraphs()
                .get(i)
                .getName());
        return out.toString();
    }

    @Test
    void openPlansMoveAmongTheirOwnPlacesAndClosedOnesStay() throws ReflectiveOperationException {
        final Plan plan = plan("A", "B", "C", "D");
        plan.getGraphs()
            .get(1)
            .setOpen(false);
        final Graph c = plan.getGraphs()
            .get(2);
        assertTrue(plan.reorderOpen(List.of(3, 2, 0)));
        assertEquals("DCA", names(plan));
        assertEquals(
            "B",
            plan.getGraphs()
                .get(1)
                .getName());
        assertSame(
            c,
            plan.getGraphs()
                .get(2));
    }

    @Test
    void theActivePlanStaysActive() throws ReflectiveOperationException {
        final Plan plan = plan("A", "B", "C");
        plan.setActiveIndex(0);
        final Graph a = plan.getGraphs()
            .get(0);
        assertTrue(plan.reorderOpen(List.of(2, 1, 0)));
        assertSame(
            a,
            plan.getGraphs()
                .get(plan.getActiveIndex()));
        assertEquals(2, plan.getActiveIndex());
    }

    @Test
    void anOrderThatIsNotAPermutationOfTheOpenSlotsChangesNothing() throws ReflectiveOperationException {
        final Plan plan = plan("A", "B", "C");
        assertFalse(plan.reorderOpen(List.of(0, 1, 2)));
        assertFalse(plan.reorderOpen(List.of(0, 1)));
        assertFalse(plan.reorderOpen(List.of(0, 1, 7)));
        assertEquals("ABC", names(plan));
    }

    @Test
    void theNewOrderSurvivesASave() throws ReflectiveOperationException {
        final Plan plan = plan("A", "B", "C");
        plan.reorderOpen(List.of(1, 2, 0));
        final Plan read = Serializer.decodePlan(Serializer.encodePlan(plan));
        assertEquals("BCA", names(read));
    }
}
