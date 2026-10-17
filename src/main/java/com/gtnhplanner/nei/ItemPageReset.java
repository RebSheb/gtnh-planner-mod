package com.gtnhplanner.nei;

import java.util.concurrent.atomic.AtomicReference;

import codechicken.nei.ItemsPanelGrid;

/**
 * NEI's item list turns back to its first page when a search changes how many items it shows. NEI up to 2.8.160 does
 * that on its search thread, which empties the panel's slot list while the game may be filling it on the render
 * thread, and the game crashes (an NPE in {@code ItemsPanelGrid.getMask}; GTNH 2.9.0-RC-2 ships NEI 2.8.155). The
 * mixins hand the turn to the render thread, as NEI 2.8.161 does itself.
 */
public final class ItemPageReset {

    private static final AtomicReference<ItemsPanelGrid> PENDING = new AtomicReference<>();

    private ItemPageReset() {}

    /** On NEI's search thread: the grid should go back to its first page. */
    public static void request(final ItemsPanelGrid grid) {
        PENDING.set(grid);
    }

    /** On the render thread, as the grid refreshes: turns it back if asked. */
    public static void apply(final ItemsPanelGrid grid) {
        if (PENDING.compareAndSet(grid, null)) grid.setPage(0);
    }
}
