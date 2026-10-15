package com.gtnhplanner.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.Tags;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Plan;
import com.gtnhplanner.library.ReportText;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.ICrashCallable;

/**
 * Crashes the planner had a hand in. At startup the game's crash reports written since the last one dealt with are
 * read, and the newest whose error runs through the planner's code waits to be offered when the planner opens (it
 * keeps waiting, launch after launch, until it is sent or put away). And every crash report the game writes gets a
 * GTNH Planner section: its version and the plan open.
 */
public final class CrashReports {

    /** A crash report: its file, its error in a line, and all of it. */
    public record Crash(File file, String summary, String text) {}

    /** Up to when the crash reports have been dealt with (ms since the epoch), in the config folder. */
    private static final String CHECKED_UNTIL = "checkedUntil";

    @Nullable
    private static Crash pending;

    private CrashReports() {}

    /** Adds the planner's section to crash reports and looks for one of its own crashes. Client start. */
    public static void init() {
        FMLCommonHandler.instance()
            .registerCrashCallable(new ICrashCallable() {

                @Override
                public String getLabel() {
                    return LABEL;
                }

                @Override
                public String call() {
                    return state();
                }
            });
        try {
            pending = scan(Minecraft.getMinecraft().mcDataDir);
        } catch (final RuntimeException e) {
            GtnhPlanner.LOG.info("Could not read the crash reports", e);
        }
    }

    /** The planner's own crash waiting to be offered, if any. */
    @Nullable
    public static Crash pending() {
        return pending;
    }

    /** The offer was answered (sent or put away): that crash, and every one before it, is dealt with. */
    public static void dealtWith() {
        if (pending == null) return;
        final Properties props = load();
        props.setProperty(CHECKED_UNTIL, Long.toString(pending.file.lastModified()));
        save(props);
        pending = null;
    }

    /** The newest crash report since the last one dealt with whose error runs through the planner. */
    @Nullable
    static Crash scan(final File gameDir) {
        final Properties props = load();
        final String until = props.getProperty(CHECKED_UNTIL);
        if (until == null) {
            // The first start with this: older crashes were seen to before it.
            props.setProperty(CHECKED_UNTIL, Long.toString(System.currentTimeMillis()));
            save(props);
            return null;
        }
        long checked;
        try {
            checked = Long.parseLong(until);
        } catch (final NumberFormatException e) {
            checked = System.currentTimeMillis();
        }
        final File[] files = new File(gameDir, "crash-reports")
            .listFiles((dir, name) -> name.startsWith("crash-") && name.endsWith(".txt"));
        if (files == null) return null;
        Crash newest = null;
        for (final File f : files) {
            if (f.lastModified() <= checked || newest != null && f.lastModified() <= newest.file.lastModified())
                continue;
            try {
                final String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                if (plannerAtFault(text)) newest = new Crash(f, summary(text), text);
            } catch (final IOException e) {
                // Unreadable: not one to offer.
            }
        }
        return newest;
    }

    /**
     * Whether the error that ended the game ran through the planner: one of its frames in the report's first stack
     * trace (before the walkthrough, which also lists every thread and mod). NEI laid out every screen through the
     * planner's layout before 0.2.0-alpha.6, so with the planner shut that frame is NEI's own work (#494: an NEI
     * bookmark crash was offered as the planner's).
     */
    public static boolean plannerAtFault(final String report) {
        final int walkthrough = report.indexOf(ReportText.WALKTHROUGH);
        final String head = walkthrough > 0 ? report.substring(0, walkthrough) : report;
        final boolean open = plannerOpen(report);
        boolean toolkit = false;
        for (final String line : head.split("\n")) {
            final String t = line.trim();
            if (!t.startsWith("at ")) continue;
            if (t.contains("gtnhplanner") && (open || !t.contains(NEI_LAYOUT))) return true;
            toolkit |= t.contains("cleanroommc.modularui");
        }
        // The planner's screens are drawn by ModularUI: its errors there are the planner's too.
        return toolkit && open;
    }

    /** The planner's layout of NEI's panels. */
    private static final String NEI_LAYOUT = "com.gtnhplanner.nei.FlowchartLayoutStyle.layout";

    /**
     * Whether the planner was up when the game crashed, as its line in the report's details says (never anything in
     * the log GTNH's reports end with, which can hold any text, an earlier crash report too).
     */
    static boolean plannerOpen(final String report) {
        final int walkthrough = Math.max(0, report.indexOf(ReportText.WALKTHROUGH));
        int end = report.indexOf("\nComplete log:", walkthrough);
        if (end < 0) end = report.length();
        for (final String line : report.substring(walkthrough, end)
            .split("\n")) {
            final String t = line.trim();
            if (t.startsWith(LABEL + ":")) return t.endsWith(PLANNER_OPEN);
        }
        return false;
    }

    /** The planner's section of a crash report: its label, and what it says while its screen is up. */
    static final String LABEL = "GTNH Planner", PLANNER_OPEN = "the planner open";

    /** The error in a line: the exception and its message, under the report's description. */
    public static String summary(final String report) {
        final String[] lines = report.replace("\r", "")
            .split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].startsWith("Description:")) continue;
            for (int j = i + 1; j < lines.length; j++) {
                final String t = lines[j].trim();
                if (t.isEmpty()) continue;
                return t.length() > 120 ? t.substring(0, 117) + "..." : t;
            }
        }
        return "The game crashed";
    }

    /** The planner's section of a crash report. Never throws: a crash report must always get written. */
    static String state() {
        try {
            final StringBuilder s = new StringBuilder(Tags.VERSION);
            final Graph g = Plan.getInstance()
                .getGraphs()
                .isEmpty() ? null : Plan.getActiveGraph();
            if (g != null) s.append(", plan '")
                .append(g.getName())
                .append("' (")
                .append(
                    g.getNodes()
                        .size())
                .append(" cards, ")
                .append(
                    g.getDrawers()
                        .size())
                .append(" drawers)");
            final net.minecraft.client.gui.GuiScreen screen = Minecraft.getMinecraft().currentScreen;
            if (com.gtnhplanner.ui.Planner.isPlanner(screen)) s.append(", " + PLANNER_OPEN);
            else s.append(", screen ")
                .append(
                    screen == null ? "none"
                        : screen.getClass()
                            .getSimpleName());
            return s.toString();
        } catch (final Throwable t) {
            return Tags.VERSION + " (state unknown: " + t + ")";
        }
    }

    private static File file() {
        return new File(Minecraft.getMinecraft().mcDataDir, "config/gtnhplanner-crashes.properties");
    }

    private static Properties load() {
        final Properties props = new Properties();
        final File f = file();
        if (!f.isFile()) return props;
        try (Reader in = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            props.load(in);
        } catch (final IOException e) {
            GtnhPlanner.LOG.info("Could not read the crash notes", e);
        }
        return props;
    }

    private static void save(final Properties props) {
        final File f = file(), dir = f.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        try (Writer out = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            props.store(out, "GTNH Planner: crash reports already offered for sending");
        } catch (final IOException e) {
            GtnhPlanner.LOG.info("Could not write the crash notes", e);
        }
    }
}
