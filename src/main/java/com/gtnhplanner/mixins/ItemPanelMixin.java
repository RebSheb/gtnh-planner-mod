package com.gtnhplanner.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.gtnhplanner.nei.ItemPageReset;

import codechicken.nei.ItemPanel;
import codechicken.nei.ItemsPanelGrid;

/** NEI's search thread no longer turns the item list's page itself ({@link ItemPageReset}). */
@Mixin(value = ItemPanel.class, remap = false)
public class ItemPanelMixin {

    /** Absent from NEI 2.8.161 on, which turns the page on the render thread itself. */
    @Redirect(
        method = "updateItemList",
        at = @At(value = "INVOKE", target = "Lcodechicken/nei/ItemsPanelGrid;setPage(I)V"),
        require = 0,
        remap = false)
    private static void gtnhplanner$pageLater(final ItemsPanelGrid grid, final int page) {
        ItemPageReset.request(grid);
    }
}
