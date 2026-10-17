package com.gtnhplanner.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Dragging a plan tab: which place it is over, the order that gives, and the keyboard's step. */
class TabOrderTest {

    private static final int[] WIDTHS = { 50, 70, 60 };

    @Test
    void slotIsCountedAmongTheOtherTabsCentres() {
        assertEquals(0, TabOrder.slotAt(WIDTHS, 0, 10, 2));
        assertEquals(1, TabOrder.slotAt(WIDTHS, 0, 100, 2));
        assertEquals(2, TabOrder.slotAt(WIDTHS, 0, 400, 2));
        assertEquals(0, TabOrder.slotAt(WIDTHS, 2, -30, 2));
        // Dragged to the far end, its centre on the last tab's own: it takes the last place.
        assertEquals(2, TabOrder.slotAt(WIDTHS, 0, 124 + 30, 2));
    }

    @Test
    void orderPutsTheHeldTabBackAtItsSlot() {
        assertEquals(List.of(1, 2, 0), TabOrder.orderWith(List.of(0, 1, 2), 0, 2));
        assertEquals(List.of(2, 0, 1), TabOrder.orderWith(List.of(0, 1, 2), 2, 0));
        assertEquals(List.of(0, 1, 2), TabOrder.orderWith(List.of(0, 1, 2), 1, 1));
        // Open slots need not be consecutive (a closed plan sits between).
        assertEquals(List.of(4, 0, 2), TabOrder.orderWith(List.of(0, 2, 4), 4, 0));
    }

    @Test
    void steppingStopsAtTheEnds() {
        assertEquals(List.of(0, 2, 1), TabOrder.stepped(List.of(0, 1, 2), 1, 1));
        assertEquals(List.of(1, 0, 2), TabOrder.stepped(List.of(0, 1, 2), 1, -1));
        final List<Integer> ids = List.of(0, 1, 2);
        assertSame(ids, TabOrder.stepped(ids, 0, -1));
        assertSame(ids, TabOrder.stepped(ids, 2, 1));
    }
}
