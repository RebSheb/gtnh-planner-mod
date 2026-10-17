package com.gtnhplanner.api;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.cloud.session.CloudSession;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Plan;
import com.gtnhplanner.data.flowchart.Serializer;
import com.gtnhplanner.data.flowchart.UndoHistory;

import codechicken.nei.NEIClientConfig;

public final class PlanAPI {

    @Nonnull
    public static Graph getActiveGraph() {
        return Plan.getActiveGraph();
    }

    /** The active slot's undo history. */
    public static UndoHistory undoHistory() {
        return undoHistory(getActiveGraph());
    }

    /** The undo history of the slot {@code graph} is; see {@link Plan#history(Graph)}. */
    public static UndoHistory undoHistory(final Graph graph) {
        return Plan.getInstance()
            .history(graph);
    }

    /**
     * Runs {@code edit} as one undo step; no-op edits leave no trace. An edit that changed the graph moves its version
     * (at least as a layout edit), so everything keyed on it, the save's kept encodings too, sees the change.
     */
    public static void recordEdit(final Graph graph, final Runnable edit) {
        final UndoHistory history = undoHistory(graph);
        final String before = history.beginEdit(graph);
        edit.run();
        if (history.commitEdit(before, graph)) graph.touchLayout();
    }

    /** Copies the serialised graph to the system clipboard. */
    public static void copyToClipboard(final Graph graph) {
        GuiScreen.setClipboardString(Serializer.encodeShared(graph));
    }

    /**
     * Reads graph data from the system clipboard and deserialises it.
     *
     * @return the deserialised graph, or {@code null} if clipboard is empty
     *         or the data is invalid.
     */
    @Nullable
    public static Graph importFromClipboard() {
        final String data = GuiScreen.getClipboardString();
        if (data.isEmpty()) return null;
        try {
            return Serializer.decode(data);
        } catch (final Exception e) {
            return null;
        }
    }

    /** Plans in the last save, to tell when one was deleted; -1 before the first save of the session. */
    private static int lastSavedPlans = -1;
    private static boolean backedUpThisSession;

    /**
     * Keeps old saves in a {@code backups} folder beside the save: one per session (the state the session started
     * from), and one before any save with fewer plans than the last (a plan was deleted). The newest ten are kept.
     */
    private static void backUp(final File saveFile, final int plans) {
        final boolean fewer = lastSavedPlans >= 0 && plans < lastSavedPlans;
        lastSavedPlans = plans;
        if (!saveFile.isFile() || backedUpThisSession && !fewer) return;
        backedUpThisSession = true;
        copyToBackups(saveFile, fewer ? "-before-delete" : "");
    }

    /**
     * Copies the save on disk into {@code backups} now, whatever the session has already backed up (a change about to
     * touch every plan, such as uploading them). The newest ten are kept. False when there was nothing to copy or it
     * failed. The suffix must not be empty.
     */
    public static boolean backUpNow(final String suffix) {
        // The session's own backup has no suffix and the same stamp format: it must never be overwritten by this one.
        if (suffix == null || suffix.isBlank()) throw new IllegalArgumentException("a backup needs a suffix");
        final File saveFile = getSaveFile();
        return saveFile.isFile() && copyToBackups(saveFile, suffix);
    }

    private static boolean copyToBackups(final File saveFile, final String suffix) {
        try {
            final File dir = new File(saveFile.getParentFile(), "backups");
            dir.mkdirs();
            final String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
            final File copy = new File(dir, "plans-" + stamp + suffix + ".dat");
            Files.copy(saveFile.toPath(), copy.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            final File[] all = dir.listFiles((d, name) -> name.startsWith("plans-") && name.endsWith(".dat"));
            if (all != null && all.length > 10) {
                java.util.Arrays.sort(all, java.util.Comparator.comparing(File::getName));
                for (int i = 0; i < all.length - 10; i++) all[i].delete();
            }
            return true;
        } catch (final Exception e) {
            com.gtnhplanner.GtnhPlanner.LOG.warn("Could not back up the plans", e);
            return false;
        }
    }

    /** A save waiting for the writer: the file, what goes in it, and how many plans it holds. */
    private record Pending(File file, com.google.gson.JsonObject tree, int plans) {}

    private static final java.util.concurrent.atomic.AtomicReference<Pending> PENDING = new java.util.concurrent.atomic.AtomicReference<>();

    /** Writes saves off the game thread, so an edit never waits on the disk; the newest save waiting wins. */
    private static final java.util.concurrent.ExecutorService WRITER = java.util.concurrent.Executors
        .newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "GTNH Planner save");
            t.setDaemon(true);
            return t;
        });

    static {
        // A save still waiting when the game closes is written before it goes.
        Runtime.getRuntime()
            .addShutdownHook(new Thread(() -> writePending(), "GTNH Planner save on exit"));
    }

    /**
     * Saves every plan. Each plan is encoded here, on the game thread, where the plans are edited, and only those
     * changed since they were last encoded ({@link Serializer#planTree}); the text is made and the disk written on the
     * writer's thread.
     */
    public static void save() {
        // The tour's own plans are never written.
        if (Plan.sandboxed()) return;
        // An account's cloud plans: every change the boards made goes to the account, never into this world's save.
        if (Plan.getInstance()
            .isCloud()) {
            final CloudSession cloud = CloudSession.current();
            if (cloud != null) cloud.save(Plan.getInstance());
            return;
        }
        final Pending save = new Pending(
            getSaveFile(),
            Serializer.planTree(Plan.getInstance()),
            Plan.getInstance()
                .getGraphs()
                .size());
        if (PENDING.getAndSet(save) == null) WRITER.execute(PlanAPI::writePending);
    }

    /**
     * Saves every plan now, on the calling thread (client thread: it encodes the plans there): when this returns the
     * file on disk holds them, whatever the writer had queued. False when it could not be written.
     */
    public static boolean saveNow() {
        if (Plan.sandboxed()) return true;
        if (Plan.getInstance()
            .isCloud()) {
            save();
            return true;
        }
        return saveLocal(Plan.getInstance());
    }

    /**
     * Writes {@code plan} as this world's save now, on the calling thread (client thread), whatever plans are in hand:
     * a world's own plans kept while an account's are open (their cloud keys, once sent). False when it failed.
     */
    public static boolean saveLocal(final Plan plan) {
        final Pending save = new Pending(
            getSaveFile(),
            Serializer.planTree(plan),
            plan.getGraphs()
                .size());
        // Whatever was queued is older than this; the writer's queued task finds nothing left and ends.
        PENDING.set(save);
        return writePending();
    }

    /**
     * Writes the newest save waiting, if any: a backup first when one is due, then the file, replaced whole. True when
     * there was nothing to write or it went down.
     */
    private static synchronized boolean writePending() {
        final Pending save = PENDING.getAndSet(null);
        if (save == null) return true;
        try {
            save.file()
                .getParentFile()
                .mkdirs();
            backUp(save.file(), save.plans());
            final File next = new File(
                save.file()
                    .getParentFile(),
                save.file()
                    .getName() + ".new");
            Files.writeString(next.toPath(), Serializer.GSON.toJson(save.tree()), StandardCharsets.UTF_8);
            Files.move(
                next.toPath(),
                save.file()
                    .toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (final Exception e) {
            GtnhPlanner.LOG.warn("Could not save the plans", e);
            return false;
        }
    }

    /** The plans' folder for this world (or the game's, outside one). */
    private static File folder(final String name) {
        final Minecraft mc = Minecraft.getMinecraft();
        final String worldName = NEIClientConfig.getWorldPath();
        if (worldName != null && !worldName.isEmpty())
            return new File(mc.mcDataDir, "saves/NEI/" + worldName + "/" + name);
        return new File(mc.mcDataDir, name);
    }

    public static File getSaveFile() {
        return new File(folder("gtnhplanner"), "plans.dat");
    }

    /** Where plans were kept before the mod was renamed: read while there is no save of the new name. */
    public static File getLegacySaveFile() {
        return new File(folder("plannh"), "plannh.dat");
    }

}
