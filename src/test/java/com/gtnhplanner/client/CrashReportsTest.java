package com.gtnhplanner.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Telling the planner's crashes from the rest, and the error in a line, from the game's crash reports. */
class CrashReportsTest {

    private static final String OURS = """
        ---- Minecraft Crash Report ----
        // Don't be sad, have a hug! <3

        Time: 10/9/26 10:56 AM
        Description: Ticking screen

        java.lang.NullPointerException: Cannot read field "inputs" because "m" is null
        \tat Launch//com.gtnhplanner.ui.card.CardModel.of(CardModel.java:206)
        \tat Launch//com.gtnhplanner.ui.BoardSession.tickBoard(BoardSession.java:1549)
        \tat net.minecraft.client.Minecraft.runTick(Minecraft.java:1700)


        A detailed walkthrough of the error, its code path and all known details is as follows:
        ---------------------------------------------------------------------------------------
        """;

    private static final String THEIRS = """
        ---- Minecraft Crash Report ----
        Description: Unexpected error

        java.lang.IllegalStateException: Someone else's bug
        \tat gregtech.api.Thing.tick(Thing.java:12)
        \tat net.minecraft.client.Minecraft.runTick(Minecraft.java:1700)


        A detailed walkthrough of the error, its code path and all known details is as follows:
        -- System Details --
        \tFML: gtnhplanner{0.2.0} [GTNH Planner] (gtnhplanner.jar) UCHIJAAAA
        \tat Launch//com.gtnhplanner.SomeThread.run(SomeThread.java:1)
        """;

    @Test
    void aCrashThroughThePlannersCodeIsOurs() {
        assertTrue(CrashReports.plannerAtFault(OURS));
    }

    @Test
    void theModListAndOtherThreadsDoNotCount() {
        assertFalse(CrashReports.plannerAtFault(THEIRS), "only the error that ended the game counts");
    }

    @Test
    void aToolkitErrorWhileThePlannerIsOpenIsOursToo() {
        final String toolkit = """
            ---- Minecraft Crash Report ----
            Description: Rendering screen

            java.lang.IndexOutOfBoundsException: Index 0 out of bounds for length 0
            \tat java.base/java.util.ArrayList.get(ArrayList.java:428)
            \tat Launch//com.cleanroommc.modularui.widgets.textfield.TextFieldRenderer.drawMeasuredLines(TextFieldRenderer.java:93)
            \tat Launch//net.minecraft.client.renderer.EntityRenderer.updateCameraAndRender(EntityRenderer.java:1136)


            A detailed walkthrough of the error, its code path and all known details is as follows:
            -- System Details --
            """;
        assertTrue(
            CrashReports
                .plannerAtFault(toolkit + "\tGTNH Planner: 0.2.0, plan 'x' (1 cards, 0 drawers), the planner open\n"));
        assertFalse(
            CrashReports.plannerAtFault(toolkit + "\tGTNH Planner: 0.2.0, screen GuiChest\n"),
            "another mod's ModularUI screen is not the planner's");
    }

    /** NEI's layout crash in another screen, laid out through the planner's style as every screen was before. */
    private static final String NEI_LAYOUT = """
        ---- Minecraft Crash Report ----
        Description: Rendering screen

        java.lang.IndexOutOfBoundsException: Index: -1, Size: 81
        \tat java.util.ArrayList.get(ArrayList.java:427)
        \tat codechicken.nei.bookmark.BookmarkGrid.getMask(BookmarkGrid.java:300)
        \tat codechicken.nei.BookmarkPanel.resize(BookmarkPanel.java:400)
        \tat codechicken.nei.LayoutStyleMinecraft.layout(LayoutStyleMinecraft.java:122)
        \tat com.gtnhplanner.nei.FlowchartLayoutStyle.layout(FlowchartLayoutStyle.java:39)
        \tat codechicken.nei.LayoutManager.layout(LayoutManager.java:395)
        \tat codechicken.nei.LayoutManager.renderObjects(LayoutManager.java:275)
        \tat com.cleanroommc.modularui.screen.GuiContainerWrapper.drawScreen(GuiContainerWrapper.java:100)


        A detailed walkthrough of the error, its code path and all known details is as follows:
        -- System Details --
        Details:
        """;

    @Test
    void neiLaidOutThroughThePlannerIsNeisWhenThePlannerIsShut() {
        // As in #494: NEI's own crash (its bookmarks, on GTNH 2.9.0-RC-1) in another screen, offered as the planner's.
        assertFalse(CrashReports.plannerAtFault(NEI_LAYOUT + "\tGTNH Planner: 0.2.0, screen MetaTileEntityGui\n"));
        assertTrue(
            CrashReports.plannerAtFault(
                NEI_LAYOUT + "\tGTNH Planner: 0.2.0, plan 'x' (1 cards, 0 drawers), the planner open\n"),
            "on the board the layout is the planner's");
    }

    @Test
    void onlyThePlannersOwnLineSaysItWasOpen() {
        final String toolkit = """
            ---- Minecraft Crash Report ----
            Description: Rendering screen

            java.lang.NullPointerException: oops
            \tat Launch//com.cleanroommc.modularui.screen.ModularPanel.draw(ModularPanel.java:10)


            A detailed walkthrough of the error, its code path and all known details is as follows:
            -- System Details --
            Details:
            \tGTNH Planner: 0.2.0, plan 'x' (1 cards, 0 drawers), screen MetaTileEntityGui

            Complete log:
            [10:00:00] [Client thread/FATAL]: \tGTNH Planner: 0.2.0, plan 'x' (1 cards, 0 drawers), the planner open
            """;
        assertFalse(CrashReports.plannerOpen(toolkit), "an earlier crash in the log is not this one");
        assertFalse(CrashReports.plannerAtFault(toolkit));
    }

    @Test
    void theErrorInALine() {
        assertEquals(
            "java.lang.NullPointerException: Cannot read field \"inputs\" because \"m\" is null",
            CrashReports.summary(OURS));
        assertEquals("The game crashed", CrashReports.summary("not a crash report"));
    }
}
