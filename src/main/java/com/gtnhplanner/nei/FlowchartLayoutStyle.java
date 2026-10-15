package com.gtnhplanner.nei;

import net.minecraft.client.gui.inventory.GuiContainer;

import com.cleanroommc.modularui.screen.GuiContainerWrapper;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.gtnhplanner.mixins.GuiContainerAccessor;

import codechicken.nei.ItemsGrid;
import codechicken.nei.LayoutManager;
import codechicken.nei.LayoutStyleMinecraft;
import codechicken.nei.NEIClientConfig;
import codechicken.nei.VisiblityData;

/**
 * NEI's layout beside the board: its buttons on the left, its item list on the right, the board between. NEI asks for
 * it only while the board is up ({@code LayoutManagerMixin}); every other screen keeps NEI's own layout.
 */
public class FlowchartLayoutStyle extends LayoutStyleMinecraft {

    private static final int PADDING = 2;
    private static final int SNAP_MARGIN = 2;
    private static final int NUMBER_OF_CHEAT_BUTTONS = 9;

    /**
     * Two-pass layout to reserve space for both the left cheat buttons and
     * the right item panel:
     * 1. Widen xSize so the left margin fits NUMBER_OF_CHEAT_BUTTONS
     * buttons, then run the default layout (buttons, search, …).
     * 2. Narrow xSize so the right margin fits `cols` item columns, then
     * re-layout only the item-side panels.
     *
     * guiLeft/guiTop must follow xSize/ySize because GuiContainer computes
     * them only in initGui() and NEI reads them for visibility checks.
     *
     * pageLabel.text is refreshed after resize() because resizeHeader()
     * runs before grid.refresh(), so the label would show the previous
     * frame's page count.
     */
    @Override
    public void layout(final GuiContainer gui, final VisiblityData visibility) {
        if (!com.gtnhplanner.ui.Planner.isPlanner(gui)) {
            super.layout(gui, visibility);
            return;
        }

        final GuiContainerAccessor accessor = (GuiContainerAccessor) gui;
        final int screenW = accessor.getWidth();
        final int screenH = accessor.getHeight();

        accessor.setYSize(screenH);
        accessor.setGuiTop(0);

        final int cols = Math.max(
            NEIPlanConfig.ConfigItemColumns.min,
            NEIClientConfig.getSetting(NEIPlanConfig.ConfigItemColumns.KEY)
                .getIntValue(NEIPlanConfig.ConfigItemColumns.defVal));

        // Pass 1: wide left margin for cheat buttons.
        final int wideGap = 2 * (BUTTON_SIZE * NUMBER_OF_CHEAT_BUTTONS + PADDING * 2);
        accessor.setXSize(screenW - wideGap);
        accessor.setGuiLeft(wideGap / 2);
        super.layout(gui, visibility);

        // Pass 2: narrow right margin for `cols` item columns.
        final int narrowFreeSpace = ItemsGrid.SLOT_SIZE * cols + SNAP_MARGIN;
        final int narrowGap = 2 * (narrowFreeSpace + PADDING * 2);
        accessor.setXSize(screenW - narrowGap);
        accessor.setGuiLeft(narrowGap / 2);

        LayoutManager.itemPanel.resize(gui);

        // Refresh label after resize() finally updates perPage.
        LayoutManager.itemPanel.pageLabel.text = LayoutManager.itemPanel.getLabelText();

        LayoutManager.bookmarkPanel.resize(gui);
        LayoutManager.itemZoom.resize(gui);
        // NEI's item zoom draws a big copy of the hovered item left of the GUI, which in the planner is on the board.
        // It draws only when the space it measured is wide enough, so tell it there is none.
        try {
            if (zoomArea == null) {
                zoomArea = codechicken.nei.ItemZoom.class.getDeclaredField("availableAreaWidth");
                zoomArea.setAccessible(true);
            }
            zoomArea.setInt(LayoutManager.itemZoom, 0);
        } catch (final ReflectiveOperationException ignored) {}

        // NEI lays out every frame; the board's width follows its item panel, so it is laid out again only when the
        // board last measured itself against the panel somewhere else (every frame, it was a whole new layout of every
        // card on the board). What it measured is the board's own record, not a copy kept here: the board also measures
        // as it opens, before NEI has laid out for it, and a copy here would not know.
        final ModularScreen screen = ((GuiContainerWrapper) gui).getScreen();
        if (!com.gtnhplanner.ui.BoardScreen.sizedFor(LayoutManager.itemPanel.x, screenW)) screen.getMainPanel()
            .scheduleResize();
    }

    private static java.lang.reflect.Field zoomArea;
}
