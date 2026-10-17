package com.gtnhplanner.ui.task;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import com.cleanroommc.modularui.screen.ModularPanel;
import com.gtnhplanner.api.PlanAPI;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.TaskEdits;
import com.gtnhplanner.data.flowchart.Tasks;
import com.gtnhplanner.data.flowchart.balancer.Severity;
import com.gtnhplanner.machines.game.CropCards;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.card.CardModel;
import com.gtnhplanner.ui.popup.PickList;
import com.gtnhplanner.ui.popup.Popup;
import com.gtnhplanner.ui.sound.Sfx;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * What the task boards do with a plan's cards: a card as a task names it (its machine, tier and how many the solve
 * builds), and "Add to a task": cards of a plan go into a task of one of the player's task boards (a plan of its own
 * kind), each as a machine thing with its own checkbox. Asked from a card's pin key, its actions and a right click on
 * the board: which task board, then which task in it (or a new one).
 */
public final class TaskActions {

    private TaskActions() {}

    /**
     * A card as a task shows it: its machine, the tier of a GregTech card ("" for none) and how many to build, all its
     * recipes' together on a shared machine, rounded up.
     */
    public record Card(Tasks.Link link, String name, String tier, int count, @Nullable ItemStack icon) {

        /** "4× Electric Blast Furnace (HV)": a machine thing's words. */
        public String line() {
            return (count > 0 ? count + "× " : "") + name + (tier.isEmpty() ? "" : " (" + tier + ")");
        }
    }

    /** A card of the open plan by its node, or null when the plan has no such card (or it is not solved yet). */
    @Nullable
    public static Card card(final BoardSession session, final UUID nodeId) {
        final UUID host = session.hostOf(nodeId);
        final CardModel m = session.model(host);
        if (m == null) return null;
        double builds = 0;
        for (final UUID s : session.sectionsOf(host)) {
            final CardModel section = session.model(s);
            if (section != null) builds += CropCards.builds(section.node, section.machines);
        }
        final String tier = m.gregtech && !m.steam() && m.tier != null ? m.tier : "";
        return new Card(session.linkOf(host), m.machineName, tier, (int) Math.ceil(builds - 1e-9), m.machineStack);
    }

    /** The cards selected on the board, by their host node. */
    public static List<UUID> selectedCards(final BoardSession session) {
        return session.cardsIn(session.selection());
    }

    // region Add to a task

    /**
     * "Add to a task" on cards: a menu of the player's task boards (and a new one), then of the board's tasks with
     * their status (and a new one); the cards go into the task picked, a machine thing each.
     */
    public static void addMenu(final BoardSession session, final ModularPanel panel, final Collection<UUID> ids,
        final int sx, final int sy) {
        final List<UUID> cards = session.cardsIn(ids);
        if (cards.isEmpty()) {
            Sfx.DENY.play();
            return;
        }
        final List<PickList.Entry> rows = new ArrayList<>();
        for (final Graph board : session.taskBoards()) {
            final int n = board.tasks.size();
            rows.add(
                new PickList.Entry(
                    null,
                    board.getName(),
                    n == 0 ? "" : n + (n == 1 ? " task" : " tasks"),
                    Hyb.INK,
                    false,
                    () -> taskMenu(session, panel, cards, board, sx, sy)));
        }
        if (!rows.isEmpty()) rows.add(PickList.Entry.rule());
        rows.add(PickList.Entry.of("New task board", () -> addTo(session, cards, session.addTaskBoard(false), null)));
        Popup.open(
            panel,
            PickList.popup("gtnhplanner_task_boards", "Which task board", rows, rows.size() > 9, 160),
            sx,
            sy);
    }

    /** The second step: the board's tasks, the ones under way first, each with its status dot; and New task. */
    private static void taskMenu(final BoardSession session, final ModularPanel panel, final List<UUID> cards,
        final Graph board, final int sx, final int sy) {
        final List<PickList.Entry> rows = new ArrayList<>();
        rows.add(PickList.Entry.adding("New task", "in To do", () -> addTo(session, cards, board, null)));
        boolean any = false;
        for (final Tasks.Status s : new Tasks.Status[] { Tasks.Status.DOING, Tasks.Status.BLOCKED, Tasks.Status.TODO,
            Tasks.Status.DONE }) {
            for (final Tasks.Task t : TaskEdits.column(board, s)) {
                if (!any) rows.add(PickList.Entry.rule());
                any = true;
                final int[] p = TaskEdits.progress(board, t.id());
                rows.add(
                    PickList.Entry.dotted(
                        TaskLook.colour(s),
                        t.title(),
                        p[1] == 0 ? "" : p[0] + "/" + p[1],
                        () -> addTo(session, cards, board, t.id())));
            }
        }
        Popup
            .open(panel, PickList.popup("gtnhplanner_task_pick", board.getName(), rows, rows.size() > 10, 180), sx, sy);
    }

    /**
     * Adds cards of the open plan to a task on {@code board} as machine things, one a card with how many to build; with
     * no task, a new one in To do titled from their machines. Says so with a key that opens the task. Returns the
     * task's id, or null when none of them is a card.
     */
    @Nullable
    public static String addTo(final BoardSession session, final Collection<UUID> ids, final Graph board,
        @Nullable final String taskId) {
        final List<Card> cards = new ArrayList<>();
        for (final UUID id : session.cardsIn(ids)) {
            final Card c = card(session, id);
            if (c != null) cards.add(c);
        }
        final Tasks.Task into = taskId == null ? null : TaskEdits.task(board, taskId);
        if (cards.isEmpty() || taskId != null && into == null) {
            Sfx.DENY.play();
            return null;
        }
        final String id = into != null ? into.id()
            : UUID.randomUUID()
                .toString();
        final String title = into != null ? into.title() : title(cards);
        PlanAPI.recordEdit(board, () -> {
            if (into == null)
                TaskEdits.add(board, id, title, Tasks.Status.TODO, List.of(), TaskLook.me(), TaskLook.now());
            for (final Card c : cards) TaskEdits.addThing(
                board,
                UUID.randomUUID()
                    .toString(),
                id,
                Tasks.MACHINE,
                c.line(),
                c.link(),
                null,
                null);
        });
        board.touchLayout();
        PlanAPI.save();
        Sfx.NOTE_STICK.play();
        final String what = cards.size() == 1 ? cards.get(0)
            .name() : cards.size() + " machines";
        session.flash(
            Severity.INFO,
            "Added " + what + " to '" + title + "'",
            "Open",
            () -> session.openTask(board, id),
            10);
        return id;
    }

    /** "Build the Electric Blast Furnace", "Build A and B", "Build A and 3 more": a title from cards' machines. */
    static String title(final List<Card> cards) {
        final Set<String> names = new LinkedHashSet<>();
        for (final Card c : cards) names.add(c.name());
        final List<String> n = new ArrayList<>(names);
        final String title = switch (n.size()) {
            case 1 -> "Build " + n.get(0);
            case 2 -> "Build " + n.get(0) + " and " + n.get(1);
            default -> "Build " + n.get(0) + " and " + (n.size() - 1) + " more";
        };
        return TaskEdits.clip(title, TaskEdits.TITLE_MAX);
    }

    // endregion
}
