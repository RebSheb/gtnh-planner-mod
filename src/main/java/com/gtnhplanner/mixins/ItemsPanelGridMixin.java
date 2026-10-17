package com.gtnhplanner.mixins;

import net.minecraft.client.gui.inventory.GuiContainer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhplanner.nei.ItemPageReset;

import codechicken.nei.ItemsPanelGrid;

/** The item list turns its page on the render thread, as it refreshes ({@link ItemPageReset}). */
@Mixin(value = ItemsPanelGrid.class, remap = false)
public class ItemsPanelGridMixin {

    @Inject(method = "refresh", at = @At("HEAD"), remap = false)
    private void gtnhplanner$turnPage(final GuiContainer gui, final CallbackInfo ci) {
        ItemPageReset.apply((ItemsPanelGrid) (Object) this);
    }
}
