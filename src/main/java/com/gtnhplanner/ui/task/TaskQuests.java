package com.gtnhplanner.ui.task;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import com.gtnhplanner.api.PlanAPI;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Plan;
import com.gtnhplanner.data.flowchart.TaskEdits;
import com.gtnhplanner.data.flowchart.Tasks;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.Resources;
import com.gtnhplanner.ui.card.CardDefaults;
import com.gtnhplanner.ui.quest.QuestBook;
import com.gtnhplanner.ui.sound.Sfx;

import cpw.mods.fml.common.Loader;

/**
 * A task's quests (game only): picking one from the quest book, opening one there, how one reads (the book's name,
 * icon and line, and whether the player has done it; what the task kept when the book is not here), and ticking a
 * quest thing off once the player has done its quest. Nothing here touches the quest book unless it is loaded: its
 * calls go through {@link QuestBook}, which is never loaded otherwise.
 */
public final class TaskQuests {

    private TaskQuests() {}

    private static Boolean loaded;

    /** Whether the game has the quest book (BetterQuesting). */
    public static boolean available() {
        if (loaded == null) loaded = Loader.isModLoaded("betterquesting");
        return loaded;
    }

    /** Where a quest stands for the player. */
    public enum State {
        /** Done in their quest book. */
        DONE,
        /** In their quest book, not done yet. */
        OPEN,
        /** The book is here but has no such quest. */
        GONE,
        /** No quest book in this game. */
        NO_BOOK
    }

    /** A quest as shown: its name, its icon, the line it is in and where it stands. */
    public record Seen(String name, @Nullable ItemStack icon, @Nullable String line, State state) {}

    /** What the book said of each quest lately: read again after a second, not every frame. */
    private static final Map<String, Seen> SEEN = new HashMap<>();
    private static long seenAt;

    public static Seen seen(final Tasks.Quest q) {
        final long now = System.currentTimeMillis();
        if (now - seenAt > 1000) {
            SEEN.clear();
            seenAt = now;
        }
        return SEEN.computeIfAbsent(q.id(), k -> look(q));
    }

    private static Seen look(final Tasks.Quest q) {
        final ItemStack kept = q.icon() == null ? null : Resources.item(q.icon());
        if (!available()) return new Seen(q.name(), kept, q.line(), State.NO_BOOK);
        final QuestBook.Look look = QuestBook.look(q.id());
        if (look == null) return new Seen(q.name(), kept, q.line(), State.GONE);
        return new Seen(
            look.name()
                .isEmpty() ? q.name() : look.name(),
            look.icon() != null ? look.icon() : kept,
            look.line() != null ? look.line() : q.line(),
            look.done() ? State.DONE : State.OPEN);
    }

    /** The quest as a task keeps it, from the book; null when the book has none such. */
    @Nullable
    static Tasks.Quest quest(final String id) {
        if (!available()) return null;
        final QuestBook.Look look = QuestBook.look(id);
        if (look == null) return null;
        final ItemStack icon = look.icon();
        return new Tasks.Quest(id, look.name(), icon == null ? null : CardDefaults.itemKey(icon), look.line());
    }

    /**
     * Opens the quest book to pick a quest for the task: the quest clicked goes on it, and the planner comes back on
     * the task either way.
     */
    public static void pick(final BoardSession session, final Graph board, final String taskId) {
        if (!available() || TaskEdits.task(board, taskId) == null) {
            Sfx.DENY.play();
            return;
        }
        TaskPanel.stepAside();
        final boolean opened = QuestBook.pick(id -> add(board, taskId, id), () -> comeBack(board, taskId));
        if (!opened) {
            Sfx.DENY.play();
            session.flash(com.gtnhplanner.data.flowchart.balancer.Severity.WARN, "The quest book would not open");
        }
    }

    /** Opens the quest in the book; closing it comes back to the task. False when the book has no such quest. */
    public static boolean open(final Graph board, final String taskId, final Tasks.Quest q) {
        if (!available() || QuestBook.look(q.id()) == null) return false;
        TaskPanel.stepAside();
        return QuestBook.open(q.id(), () -> comeBack(board, taskId));
    }

    /** Puts the quest on the task, as one edit; a quest it has already is left as it is. */
    static void add(final Graph board, final String taskId, final String id) {
        final Tasks.Quest quest = quest(id);
        if (quest == null || TaskEdits.task(board, taskId) == null) {
            Sfx.DENY.play();
            return;
        }
        if (TaskEdits.questThing(board, taskId, id) != null) return;
        PlanAPI.recordEdit(
            board,
            () -> TaskEdits.addQuest(
                board,
                UUID.randomUUID()
                    .toString(),
                taskId,
                quest));
        board.touchLayout();
        PlanAPI.save();
        Sfx.NOTE_STICK.play();
    }

    /** The planner opens again on the next tick, on the board with the task open. */
    private static void comeBack(final Graph board, final String taskId) {
        final Plan plan = Plan.getInstance();
        final int at = plan.getGraphs()
            .indexOf(board);
        if (at >= 0) plan.setActiveIndex(at);
        TaskPanel.openWhenShown(board, taskId);
        TaskPictures.INSTANCE.reopen = true;
    }

    // region Ticking them off

    /**
     * Quest things the player has unticked by hand while their quest was done, or that were ticked for them once
     * already: left as they are from then on (this game).
     */
    private static final Set<String> LEFT = new HashSet<>();
    private static long syncedAt;

    /** A quest thing ticked or unticked by hand: unticked while its quest is done, it stays unticked. */
    static void byHand(final Tasks.Item item, final boolean done) {
        if (!done && Tasks.QUEST.equals(item.shownKind()) && seen(item.quest()).state() == State.DONE)
            LEFT.add(item.id());
    }

    /**
     * Ticks off the board's quest things whose quest the player has done, at most every two seconds (or now, with
     * {@code now}), as one edit.
     */
    static void sync(final BoardSession session, final boolean now) {
        if (!available()) return;
        final long t = System.currentTimeMillis();
        if (!now && t - syncedAt < 2000) return;
        syncedAt = t;
        final Graph g = session.graph();
        final List<Tasks.Item> tick = TaskEdits.questsToTick(
            g,
            id -> QuestBook.look(id) instanceof final QuestBook.Look l && l.done(),
            LEFT);
        if (tick.isEmpty()) return;
        for (final Tasks.Item i : tick) LEFT.add(i.id());
        session.editTasks(() -> {
            for (final Tasks.Item i : tick) {
                final Tasks.Item cur = TaskEdits.item(g, i.id());
                if (cur != null && !cur.done()) TaskEdits.putItem(g, TaskEdits.withDone(cur, true));
            }
        });
        Sfx.TOGGLE_ON.play();
    }

    // endregion
}
