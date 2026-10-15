package com.gtnhplanner.ui.tutorial;

import java.util.Properties;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import com.gtnhplanner.api.PlanAPI;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Plan;
import com.gtnhplanner.data.flowchart.Serializer;
import com.gtnhplanner.ui.Planner;
import com.gtnhplanner.ui.PlannerSettings;
import com.gtnhplanner.ui.card.MachinePicks;
import com.gtnhplanner.ui.card.SettingMemory;
import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.world.PlanSnapshot;

import codechicken.nei.LayoutManager;

/**
 * Keeps the player's things safe while the tour runs: their plans are put aside for plans of the tour's own (never
 * saved), and everything else the tour touches is noted and put back when it ends: the minimap's settings, the top
 * bar's unit keys and the overview's folds, the machine picked per NEI tab, the settings remembered per machine, NEI's
 * search, the minimap's picture of the plan, and NEI's item groups the tour opened. The game's GUI scale is left
 * alone: the board takes a scale of its own with room for the tour's moves ({@link com.gtnhplanner.ui.PlannerScale}).
 * It ends where the player is: the plan in front of them is theirs from then on, and the screen stays.
 */
final class Sandbox {

    private boolean active;
    /** Whether the plan in front of the player when it ends becomes one of theirs (the tour's, not a film's). */
    boolean keepPlan;
    private Properties picks, memory;
    private PlanSnapshot snapshot;
    private float viewX, viewY;
    private String search;
    private boolean minimap, circle, follows, pinList;
    private int size, zoom;
    private PlannerSettings.Corner corner, pinCorner;
    /** The top bar's unit keys: the tour types its rates a second, and shows power as EU/t on average. */
    private Fmt.RateUnit rateUnit;
    private boolean amps, peak;
    /** The overview: the tour shows it open, every section but Internal unfolded, as it is the first time. */
    private boolean overviewOpen;
    private Set<String> overviewFolded;

    void enter() {
        if (active) return;
        active = true;
        final GuiScreen screen = Minecraft.getMinecraft().currentScreen;
        // The board closing saves the player's plan as it is.
        if (screen != null) Minecraft.getMinecraft()
            .displayGuiScreen(null);
        PlanAPI.save();
        picks = MachinePicks.copy();
        memory = SettingMemory.copy();
        snapshot = PlanSnapshot.latest();
        viewX = PlanSnapshot.lastViewX();
        viewY = PlanSnapshot.lastViewY();
        search = LayoutManager.searchField == null ? "" : LayoutManager.searchField.text();
        minimap = PlannerSettings.minimap();
        circle = PlannerSettings.minimapCircle();
        follows = PlannerSettings.minimapFollows();
        size = PlannerSettings.minimapSizeIndex();
        zoom = PlannerSettings.minimapZoomIndex();
        corner = PlannerSettings.minimapCorner();
        pinList = PlannerSettings.pinList();
        pinCorner = PlannerSettings.pinListCorner();
        rateUnit = PlannerSettings.rateUnit();
        amps = PlannerSettings.powerAsAmps();
        peak = PlannerSettings.peakPower();
        PlannerSettings.setRateUnit(Fmt.RateUnit.SECOND);
        PlannerSettings.setPowerAsAmps(false);
        PlannerSettings.setPeakPower(false);
        overviewOpen = PlannerSettings.overviewOpen();
        overviewFolded = PlannerSettings.overviewFolded();
        PlannerSettings.setOverviewOpen(true);
        PlannerSettings.setOverviewFolded(PlannerSettings.OVERVIEW_FOLDED_AT_FIRST);
        Plan.enterSandbox();
    }

    /** The tour's plans as they are, to go back to. */
    String saveTourPlans() {
        return Serializer.encodePlan(Plan.getInstance());
    }

    /** Puts the tour's plans back as {@link #saveTourPlans()} had them. */
    void loadTourPlans(final String saved) {
        Plan.replaceSandbox(Serializer.decodePlan(saved));
    }

    /** Clears NEI's search, as the tour found it before it typed (between chapters too). */
    void clearSearch() {
        if (LayoutManager.searchField != null) LayoutManager.searchField.setText(search == null ? "" : search);
    }

    /**
     * Ends it where the player is. The plan in front of them, if the tour put anything on it, becomes one of theirs
     * ("Tour plan"), the one shown; the screen stays (the board laid out again at their own size, the world out from
     * under the tour's cover, NEI's page as it is). The settings the tour borrowed go back as they were.
     */
    void leave() {
        if (!active) return;
        active = false;
        final Minecraft mc = Minecraft.getMinecraft();
        final boolean world = mc.theWorld != null;
        // The world gone: nothing to stay in; off the tour's screens while its plans still stand in.
        if (!world && mc.currentScreen != null) mc.displayGuiScreen(null);
        final Graph tour = Plan.getActiveGraph();
        final boolean keep = keepPlan && world
            && tour != null
            && !(tour.nodes.isEmpty() && tour.drawers.isEmpty() && tour.notes.isEmpty());
        Plan.leaveSandbox();
        if (keep) adopt(tour);
        MachinePicks.restore(picks);
        SettingMemory.restore(memory);
        Targets.closeNeiGroups();
        PlannerSettings.setMinimap(minimap);
        PlannerSettings.setMinimapCircle(circle);
        PlannerSettings.setMinimapFollows(follows);
        PlannerSettings.setMinimapSizeIndex(size);
        PlannerSettings.setMinimapZoomIndex(zoom);
        PlannerSettings.setMinimapCorner(corner);
        PlannerSettings.setPinList(pinList);
        PlannerSettings.setPinListCorner(pinCorner);
        PlannerSettings.setRateUnit(rateUnit);
        PlannerSettings.setPowerAsAmps(amps);
        PlannerSettings.setPeakPower(peak);
        PlannerSettings.setOverviewOpen(overviewOpen);
        PlannerSettings.setOverviewFolded(overviewFolded);
        if (world) {
            final GuiScreen screen = mc.currentScreen;
            if (Planner.isPlanner(screen)) Planner.open();
            else if (screen instanceof TourScreen) mc.displayGuiScreen(null);
        }
        // The minimap's picture: the kept plan's comes from the board; else the player's as it was.
        if (!keep) {
            PlanSnapshot.publish(snapshot);
            PlanSnapshot.setView(viewX, viewY);
        }
        PlanAPI.save();
    }

    /** The tour's plan, one of the player's now: named apart from theirs, in a tab, the one shown. */
    private static void adopt(final Graph tour) {
        final Plan plan = Plan.getInstance();
        String name = "Tour plan";
        for (int n = 2; named(plan, name); n++) name = "Tour plan " + n;
        tour.setName(name);
        plan.getGraphs()
            .add(tour);
        plan.setActiveIndex(
            plan.getGraphs()
                .size() - 1);
    }

    private static boolean named(final Plan plan, final String name) {
        for (final Graph g : plan.getGraphs()) if (name.equals(g.getName())) return true;
        return false;
    }

    boolean active() {
        return active;
    }
}
