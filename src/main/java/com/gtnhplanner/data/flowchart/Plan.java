package com.gtnhplanner.data.flowchart;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import com.gtnhplanner.api.PlanAPI;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Plan {

    @Nullable
    private static Plan INSTANCE;

    private transient final List<Graph> graphs = new ArrayList<>();
    private int activeIndex = 0;
    private boolean snapToGrid;

    @Getter
    @Setter
    private Summary summary = new Summary();

    /** The slots' undo histories; transient, so a loaded plan starts with none. */
    private final transient UndoHistories histories = new UndoHistories();

    /**
     * The account whose cloud plans these are ({@code cloud/session/CloudSession}: kept in the account, saved on every
     * edit, tabs per device); null for this world's own save.
     */
    @Nullable
    private transient String cloudAccount;

    private Plan() {}

    /** A signed-in account's cloud plans, none yet: the account's session fills them. */
    public static Plan cloud(final String accountId) {
        final Plan p = new Plan();
        p.cloudAccount = accountId;
        return p;
    }

    /** Whether these are an account's cloud plans rather than this world's save. */
    public boolean isCloud() {
        return cloudAccount != null;
    }

    @Nullable
    public String cloudAccount() {
        return cloudAccount;
    }

    /**
     * Opens a slot: it becomes the active one, with a tab. The one it replaces was open until now, and is stamped so.
     */
    public void setActiveIndex(final int index) {
        final long now = System.currentTimeMillis();
        if (activeIndex >= 0 && activeIndex < graphs.size()) graphs.get(activeIndex)
            .setLastOpen(now);
        activeIndex = index;
        if (index >= 0 && index < graphs.size()) {
            graphs.get(index)
                .setLastOpen(now);
            graphs.get(index)
                .setOpen(true);
        }
    }

    /**
     * Closes a slot's tab; the plan stays. If it was the active one, the most recently open of the others takes over.
     * The last tab stays open.
     */
    public void closeSlot(final int index) {
        if (index < 0 || index >= graphs.size() || openSlots().size() <= 1) return;
        if (index == activeIndex) setActiveIndex(mostRecentOpenExcept(index));
        graphs.get(index)
            .setOpen(false);
    }

    /** Removes a slot for good; the most recently open of the others takes over. The last slot stays. */
    public void removeSlot(final int index) {
        if (graphs.size() <= 1 || index < 0 || index >= graphs.size()) return;
        if (index == activeIndex) {
            int next = mostRecentOpenExcept(index);
            if (next < 0) next = index == 0 ? 1 : 0;
            setActiveIndex(next);
        }
        graphs.remove(index);
        if (activeIndex > index) activeIndex--;
    }

    private int mostRecentOpenExcept(final int index) {
        for (final int i : byRecency()) if (i != index && graphs.get(i)
            .isOpen()) return i;
        return -1;
    }

    /** The slots with a tab, in tab order. */
    public List<Integer> openSlots() {
        final List<Integer> out = new ArrayList<>();
        for (int i = 0; i < graphs.size(); i++) if (graphs.get(i)
            .isOpen()) out.add(i);
        return out;
    }

    /**
     * Puts the open plans' tabs in a new order: {@code order} lists the open slots (as {@link #openSlots()} does) in
     * the order they should now run. The plans move among the places the open ones held, so a closed plan keeps its
     * own place and the active plan stays active. Anything that is not a permutation of the open slots changes nothing.
     *
     * @return whether the order changed
     */
    public boolean reorderOpen(final List<Integer> order) {
        final List<Integer> open = openSlots();
        if (order.size() != open.size() || !new java.util.HashSet<>(order).equals(new java.util.HashSet<>(open))
            || order.equals(open)) return false;
        final List<Graph> before = new ArrayList<>(graphs);
        final Graph active = activeIndex >= 0 && activeIndex < before.size() ? before.get(activeIndex) : null;
        for (int k = 0; k < open.size(); k++) graphs.set(open.get(k), before.get(order.get(k)));
        if (active != null) activeIndex = graphs.indexOf(active);
        return true;
    }

    /** The slots' indices, most recently open first: the active one, then by when each was last open. */
    public List<Integer> byRecency() {
        final List<Integer> order = new ArrayList<>();
        for (int i = 0; i < graphs.size(); i++) order.add(i);
        order.sort((a, b) -> {
            if (a.equals(b)) return 0;
            if (a == activeIndex || b == activeIndex) return a == activeIndex ? -1 : 1;
            return Long.compare(
                graphs.get(b)
                    .getLastOpen(),
                graphs.get(a)
                    .getLastOpen());
        });
        return order;
    }

    /**
     * The undo history of the slot {@code graph} is; it survives undo and redo swapping the graph. Cloud plans keep
     * none here: their undo is the player's own edits' inverses.
     */
    public UndoHistory history(final Graph graph) {
        return cloudAccount != null ? UndoHistory.off() : histories.of(graph);
    }

    public static Plan getInstance() {
        if (INSTANCE == null) {
            INSTANCE = loadPlan();
        }
        return INSTANCE;
    }

    /**
     * The plans if they are loaded, without loading them: for code that runs in the world (the minimap, world links),
     * which must not be what loads them, since that can happen while NEI is still loading its recipes.
     */
    @Nullable
    public static Plan loaded() {
        return INSTANCE;
    }

    public static Graph getActiveGraph() {
        Plan plan = getInstance();
        if (plan.graphs.isEmpty()) {
            plan.graphs.add(new Graph("Slot 1"));
        }
        if (plan.activeIndex < 0 || plan.activeIndex >= plan.graphs.size()) {
            plan.activeIndex = 0;
        }
        plan.reread(plan.activeIndex);
        return plan.graphs.get(plan.activeIndex);
    }

    /** A slot that could not be read at load, read again (NEI has its recipes by the time a slot is opened). */
    private void reread(final int index) {
        final Graph g = graphs.get(index);
        if (g.getUnreadable() == null || !g.nodes.isEmpty()) return;
        try {
            final Graph read = Serializer.decode(g.getUnreadable());
            read.setName(g.getName());
            read.setOpen(g.isOpen());
            read.setLastOpen(g.getLastOpen());
            graphs.set(index, read);
            com.gtnhplanner.GtnhPlanner.LOG.info("Slot '{}' read on a second try", g.getName());
        } catch (final RuntimeException e) {
            com.gtnhplanner.GtnhPlanner.LOG
                .error("Slot '{}' still cannot be read; its data stays kept", g.getName(), e);
        }
    }

    /**
     * Where the plans come from while an account is signed in ({@code cloud/session}): its cloud plans. Unset, or
     * answering null, the plans are this world's save, as when signed out.
     */
    @Nullable
    private static Supplier<Plan> cloudSource;

    public static void setCloudSource(@Nullable final Supplier<Plan> source) {
        cloudSource = source;
    }

    private static Plan loadPlan() {
        if (cloudSource != null) {
            try {
                final Plan cloud = cloudSource.get();
                if (cloud != null) return cloud;
            } catch (final RuntimeException e) {
                com.gtnhplanner.GtnhPlanner.LOG.error("Could not open the account's plans; this world's are open", e);
            }
        }
        final Plan local = loadLocal();
        if (local != null) return local;
        final Plan plan = new Plan();
        plan.getGraphs()
            .add(new Graph("Slot 1"));
        return plan;
    }

    /** This world's own save, read now whatever plans are in hand; null when it has none. Client thread (NEI). */
    @Nullable
    public static Plan loadLocal() {
        try {
            File saveFile = PlanAPI.getSaveFile();
            if (!saveFile.isFile()) saveFile = PlanAPI.getLegacySaveFile();
            if (saveFile.isFile()) {
                final String data = Files.readString(saveFile.toPath(), StandardCharsets.UTF_8);
                return Serializer.decodePlan(data);
            }
        } catch (final Exception | Error ignored) {}
        return null;
    }

    public static void unloadPlan() {
        leaveSandbox();
        PlanAPI.save();
        INSTANCE = null;
    }

    /** Set when the plans were to be loaded afresh while the tour stood in for them: done once it ends. */
    private static boolean reloadAfterTour;

    /**
     * Lets go of the plans in hand, saved first, so the next ask loads them from where they now come from: signing in
     * or out changes that. During the tour, once it ends.
     */
    public static void reload() {
        if (aside != null) {
            reloadAfterTour = true;
            return;
        }
        if (INSTANCE != null) PlanAPI.save();
        INSTANCE = null;
    }

    /** The player's plans, the tour's sandbox aside: the ones in hand, or null when none are loaded. */
    @Nullable
    public static Plan player() {
        return aside != null ? aside : INSTANCE;
    }

    // region The tour's sandbox

    /** The player's plans, kept aside while the tour works in plans of its own; null otherwise. */
    @Nullable
    private static Plan aside;

    /** Whether the tour's plans stand in for the player's: nothing is saved meanwhile. */
    public static boolean sandboxed() {
        return aside != null;
    }

    /** Puts the player's plans aside and stands in plans of the tour's own: one empty plan, never saved. */
    public static void enterSandbox() {
        if (aside != null) return;
        aside = getInstance();
        INSTANCE = new Plan();
        INSTANCE.graphs.add(new Graph("Plan 1"));
    }

    /** The tour's plans as they were at some point, decoded again (the tour going back). */
    public static void replaceSandbox(final Plan plan) {
        if (aside != null) INSTANCE = plan;
    }

    /** Puts the player's plans back as they were. */
    public static void leaveSandbox() {
        if (aside == null) return;
        INSTANCE = aside;
        aside = null;
        if (reloadAfterTour) {
            reloadAfterTour = false;
            reload();
        }
    }

    // endregion
}
