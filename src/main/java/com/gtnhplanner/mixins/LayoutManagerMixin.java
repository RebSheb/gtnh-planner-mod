package com.gtnhplanner.mixins;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnhplanner.nei.FlowchartLayoutStyle;
import com.gtnhplanner.nei.OpenFlowchartButton;
import com.gtnhplanner.ui.Planner;

import codechicken.nei.Button;
import codechicken.nei.LayoutManager;
import codechicken.nei.VisiblityData;
import codechicken.nei.api.LayoutStyle;

@Mixin(value = LayoutManager.class, remap = false)
public class LayoutManagerMixin {

    @Unique
    private static final Button planNH$openFlowchartButton = new OpenFlowchartButton();

    @Unique
    private static final int FC_BUTTON_GAP = 2;
    @Unique
    private static final int FC_BUTTON_BOTTOM_OFF = 22;
    @Unique
    private static final int FC_BUTTON_SIZE = 20;

    /** How NEI lays itself out beside the board. */
    @Unique
    private static LayoutStyle gtnhplanner$boardLayout;

    @Inject(method = "updateWidgetVisiblities", at = @At("TAIL"), remap = false)
    private static void gtnhplanner$onUpdateWidgetVisiblities(final GuiContainer gui, final VisiblityData visiblity,
        final CallbackInfo ci) {
        if (!visiblity.showNEI) return;

        planNH$openFlowchartButton.x = LayoutManager.bookmarksButton.x + LayoutManager.bookmarksButton.w
            + FC_BUTTON_GAP;
        planNH$openFlowchartButton.y = gui.height - FC_BUTTON_BOTTOM_OFF;
        planNH$openFlowchartButton.h = FC_BUTTON_SIZE;
        planNH$openFlowchartButton.w = FC_BUTTON_SIZE;
        LayoutManager.addWidget(planNH$openFlowchartButton);
    }

    /**
     * On the board NEI lays out by the planner's style; every other screen keeps NEI's own, so the planner is not in
     * the stack of NEI's errors there, named as a suspect and offering them as its own (#494, an NEI crash on GTNH
     * 2.9.0-RC-1).
     */
    @Inject(
        method = "getLayoutStyle()Lcodechicken/nei/api/LayoutStyle;",
        at = @At("HEAD"),
        cancellable = true,
        remap = false)
    private static void gtnhplanner$boardLayout(final CallbackInfoReturnable<LayoutStyle> cir) {
        if (!Planner.isPlanner(Minecraft.getMinecraft().currentScreen)) return;
        if (gtnhplanner$boardLayout == null) gtnhplanner$boardLayout = new FlowchartLayoutStyle();
        cir.setReturnValue(gtnhplanner$boardLayout);
    }
}
