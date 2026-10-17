package com.gtnhplanner.ui.task;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.layout.IViewport;
import com.cleanroommc.modularui.api.layout.IViewportStack;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.drawable.Stencil;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.TaskEdits;
import com.gtnhplanner.data.flowchart.Tasks;
import com.gtnhplanner.library.Account;
import com.gtnhplanner.ui.BoardScreen;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.PlannerScale;
import com.gtnhplanner.ui.card.CardPaint;
import com.gtnhplanner.ui.note.NoteText;
import com.gtnhplanner.ui.popup.PickList;
import com.gtnhplanner.ui.popup.Popup;
import com.gtnhplanner.ui.popup.TextArea;
import com.gtnhplanner.ui.popup.TextPopup;
import com.gtnhplanner.ui.popup.Tip;
import com.gtnhplanner.ui.sound.Sfx;
import com.gtnhplanner.ui.theme.Hyb;
import com.gtnhplanner.ui.theme.Icons;

/**
 * A task, open in a box over its board, laid out as an issue is in Jira: a wide main column (the title, big and written
 * in place; its notes; its things, each with its own checkbox: machines, places in a world and screenshots, with keys
 * to add where you stand and a screenshot; then the comments) and a narrow side column (its status as a picker, who is
 * on it, the board it is on, who made it and when). The comment box sits at the foot of the main column and Delete
 * task at the foot of the side column. Every change is one undoable edit of the plan, made as it happens; the box
 * follows the plan, so undo shows here too, and a task undone away closes it.
 *
 * <p>
 * The box fits the planner it opens in (GTNH's GUI scale 4 leaves the planner 330 to 440 pixels wide): below
 * {@value #TWO_COLUMNS} pixels the side column goes under the title, and whatever is taller than the box scrolls as a
 * page between its bar and its foot.
 */
public final class TaskPanel extends ParentWidget<TaskPanel> implements Interactable {

    /** The box's widest and tallest, and the least width that holds the side column beside the main one. */
    private static final int MAX_W = 600, MAX_H = 460, TWO_COLUMNS = 400;
    private static final int BAR = 22, FOOT = 38, PAD = 12, GUTTER = 14, SIDE_W = 150, SIDE_PAD = 10;
    private static final int LINE = 10, LABEL = 14, SECTION = 16, KEY_H = 16, ROW_PAD = 5;
    /** Colours: the wells written in, keys and rows lit, the side column's panel, the quieter words. */
    static final int WELL = 0xFF16171A, CONTROL = 0xFF2A2C31, CONTROL_HOVER = 0xFF33363C, CYAN = 0xFF22D3EE,
        ROW_HOVER = 0xFF2A2C31, SIDE = 0xFF1D1F23, SIDE_EDGE = 0xFF2C2E34, DIM = 0xFF7E8089, FAINT = 0xFF6A6C74,
        QUEST_INK = 0xFFF2C14E;
    private static final int DRAG_SLOP = 6;
    /** A row of the side column's lists of tasks. */
    private static final int LINK_H = 15;
    /** The side column's rhythm: the room under a label and between items; its labels' quiet ink. */
    private static final int LABEL_GAP = 13, ITEM_GAP = 14, LABEL_INK = 0xFF6F727B;

    /** Why the last task picked to wait on was refused, shown under the list a few seconds. */
    @Nullable
    private String refused;
    private long refusedUntil;

    /** The box open now, for the dev harness and the screen's releases. */
    private static WeakReference<TaskPanel> showing = new WeakReference<>(null);

    private final BoardSession session;
    private final String taskId;
    private final int boxW, boxH;
    /** Side by side, or the side column under the title. */
    private final boolean wide;
    private final int mainX, mainW, sideX, sideW;
    /** The title's size: a step up that keeps whole screen pixels at the planner's scale. */
    private final float titleScale;
    private final TextArea title, notes, comment;
    private final Page page;

    private enum Kind {
        CLOSE,
        STATUS,
        PRIORITY,
        ASSIGN,
        SIGN_IN,
        BOX,
        THING,
        SHOW,
        THING_DELETE,
        ADD_HERE,
        ADD_SHOT,
        ADD_QUEST,
        COMMENT,
        COMMENT_DELETE,
        POST,
        DELETE,
        BLOCKER,
        UNBLOCK,
        ADD_BLOCKER,
        BLOCKS
    }

    private record Hit(Kind kind, int x0, int y0, int x1, int y1, @Nullable Object data) {

        boolean contains(final int x, final int y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }
    }

    /** The bar's and the foot's parts, in the box's pixels. */
    private final List<Hit> hits = new ArrayList<>(), lastHits = new ArrayList<>();

    private TaskPanel(final BoardSession session, final String taskId, final int boxW, final int boxH) {
        this.session = session;
        this.taskId = taskId;
        this.boxW = boxW;
        this.boxH = boxH;
        wide = boxW >= TWO_COLUMNS;
        mainX = PAD;
        // About a third of the box, so its lists read on one line: 150 to 184 wide.
        sideW = wide ? Math.max(SIDE_W, Math.min(184, Math.round(boxW * 0.31f))) : boxW - 2 * PAD;
        sideX = wide ? boxW - PAD - sideW : PAD;
        mainW = wide ? sideX - GUTTER - mainX : boxW - 2 * PAD;
        final int k = Math.max(1, PlannerScale.board());
        titleScale = (k + 1) / (float) k;
        size(boxW, boxH);

        comment = new TextArea("Write a comment").maxLength(TaskEdits.COMMENT_MAX);
        child(
            comment.pos(mainX + 1, boxH - FOOT + 8)
                .size(commentW() - 2, 22));

        title = new TextArea("Name this task").scale(titleScale)
            .singleLine()
            .maxLength(TaskEdits.TITLE_MAX)
            .onDone(this::setTitle);
        notes = new TextArea("Add notes").maxLength(TaskEdits.TEXT_MAX)
            .onDone(this::setText);
        page = new Page();
        page.child(title);
        page.child(notes);
        child(
            page.pos(0, BAR)
                .size(boxW, boxH - BAR - FOOT));
        final Tasks.Task t = task();
        if (t != null) {
            title.text(t.title());
            notes.text(t.text() == null ? "" : t.text());
        }
    }

    /** The comment box's width: the main column less the Comment key. */
    private int commentW() {
        return mainW - 62 - 6;
    }

    /** The foot's height: it grows with the comment being written, up to four lines, and the page gives way. */
    private int foot = FOOT;

    private void fitFoot() {
        final int lines = Math.max(
            1,
            Math.min(
                4,
                NoteText.of(comment.text(), commentW() - 12, Hyb::width)
                    .lines()
                    .size()));
        final int need = FOOT + (lines - 1) * LINE;
        if (need == foot) return;
        foot = need;
        comment.pos(mainX + 1, boxH - foot + 8)
            .size(commentW() - 2, 22 + (lines - 1) * LINE);
        page.size(boxW, boxH - BAR - foot);
        scheduleResize();
    }

    // region Opening

    /** Opens a task in a box in the middle of the planner, as big as it has room for. */
    public static void open(final BoardSession session, final String taskId) {
        if (TaskEdits.task(session.graph(), taskId) == null) return;
        final int[] room = Popup.room(session.panel());
        final int w = Math.min(MAX_W, room[2] - 8), h = Math.min(MAX_H, room[3] - 8);
        final TaskPanel panel = new TaskPanel(session, taskId, w, h);
        final Popup popup = new Popup("gtnhplanner_task", w, h) {

            @Override
            public void drawBackground(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
                Hyb.rect(-getArea().x, -getArea().y, PlannerScale.width(), PlannerScale.height(), 0x60000000);
                Hyb.dropShadow(0, 0, getArea().width, getArea().height);
                CardPaint.surface(getArea().width, getArea().height);
            }
        };
        popup.child(panel.pos(0, 0));
        popup.onCloseAction(panel::closing);
        showing = new WeakReference<>(panel);
        Popup.openCentred(session.panel(), popup, 0.5f);
    }

    /**
     * The planner steps aside for another screen (the quest book) that will bring it back on the task: the box puts
     * in what was written and counts as closed, so the task opens again when the board comes back.
     */
    static void stepAside() {
        final TaskPanel p = showing.get();
        if (p != null && !p.closed) p.closing();
    }

    /** A task to open once its board shows (after the planner comes back, or a tab switched to it). */
    @Nullable
    private static Graph pendingBoard;
    @Nullable
    private static String pendingTask;

    /** Opens {@code taskId} in its box the next time {@code board} shows its tasks. */
    public static void openWhenShown(final Graph board, final String taskId) {
        pendingBoard = board;
        pendingTask = taskId;
    }

    /** The task board shows: the task asked for opens, if it is this board's. */
    static void openPending(final BoardSession session) {
        if (pendingBoard == null) return;
        if (pendingBoard != session.graph()) return;
        final String id = pendingTask;
        pendingBoard = null;
        pendingTask = null;
        final TaskPanel open = showing.get();
        if (open != null && !open.closed) return;
        if (id != null) open(session, id);
    }

    // endregion

    @Nullable
    private Tasks.Task task() {
        return TaskEdits.task(session.graph(), taskId);
    }

    private Graph graph() {
        return session.graph();
    }

    // region Edits

    private void edit(final Runnable change) {
        session.editTasks(change);
    }

    /** Puts a changed task in its place. */
    private void put(final java.util.function.UnaryOperator<Tasks.Task> change) {
        final Tasks.Task t = task();
        if (t == null) return;
        final Tasks.Task next = change.apply(t);
        if (!next.equals(t)) edit(() -> TaskEdits.put(session.graph(), next));
    }

    private void setTitle(@Nullable final String text) {
        if (text == null || text.isBlank()) {
            // A title cannot be empty: the one it had comes back.
            final Tasks.Task t = task();
            if (t != null) title.text(t.title());
            return;
        }
        put(t -> TaskEdits.withTitle(t, text));
    }

    private void setText(@Nullable final String text) {
        put(t -> TaskEdits.withText(t, text));
    }

    private void putItem(final Tasks.Item item) {
        edit(() -> TaskEdits.putItem(session.graph(), item));
    }

    private void post() {
        final String text = comment.text()
            .strip();
        if (text.isEmpty() || task() == null) {
            Sfx.DENY.play();
            return;
        }
        edit(
            () -> TaskEdits.addComment(
                session.graph(),
                UUID.randomUUID()
                    .toString(),
                taskId,
                TaskLook.me(),
                TaskLook.myName(),
                TaskLook.now(),
                text));
        comment.text("");
        Sfx.NOTE_STICK.play();
        // The newest comment is at the foot of the page: show it.
        page.scroll = Integer.MAX_VALUE;
    }

    /** Adds the block the player stands on as a location, then asks for a name for it (none is fine). */
    private void addHere() {
        final Tasks.Place here = TaskThings.here();
        if (here == null || task() == null) {
            Sfx.DENY.play();
            return;
        }
        final String id = UUID.randomUUID()
            .toString();
        edit(
            () -> TaskEdits
                .addThing(session.graph(), id, taskId, Tasks.LOCATION, TaskEdits.placeText(here), null, here, null));
        Sfx.NOTE_STICK.play();
        final Tasks.Item made = TaskEdits.item(session.graph(), id);
        if (made != null) rename(made);
    }

    /** Ticks a thing off, or not. A card an older task linked becomes a thing of its own to be ticked. */
    private void toggle(final TaskThings.Shown s) {
        final Tasks.Item item = s.item();
        if (s.loose()) {
            edit(() -> {
                final Tasks.Item made = TaskEdits.addThing(
                    session.graph(),
                    UUID.randomUUID()
                        .toString(),
                    taskId,
                    Tasks.MACHINE,
                    s.words(),
                    item.link(),
                    null,
                    null);
                TaskEdits.putItem(session.graph(), TaskEdits.withDone(made, true));
            });
            Sfx.TOGGLE_ON.play();
            return;
        }
        TaskQuests.byHand(item, !item.done());
        putItem(TaskEdits.withDone(item, !item.done()));
        (item.done() ? Sfx.TOGGLE_OFF : Sfx.TOGGLE_ON).play();
    }

    /** Takes a thing off the task (a loose card: its link). */
    private void takeOff(final TaskThings.Shown s) {
        if (s.loose()) {
            final Tasks.Task t = task();
            if (t == null) return;
            final List<Tasks.Link> links = new ArrayList<>(t.links());
            links.remove(
                s.item()
                    .link());
            put(x -> TaskEdits.withLinks(x, links));
        } else edit(
            () -> TaskEdits.removeThing(
                session.graph(),
                s.item()
                    .id()));
        Sfx.NOTE_CRUMPLE.play();
    }

    /**
     * What a click on a thing does: a card shows on its plan, a spot in the world, a screenshot big, words to write.
     */
    private void act(final TaskThings.Shown s) {
        switch (s.kind()) {
            case Tasks.MACHINE -> {
                close();
                session.openLink(
                    s.item()
                        .link());
            }
            case Tasks.LOCATION -> showPlace(
                s.item()
                    .place());
            case Tasks.PICTURE -> PictureView.open(
                session,
                s.item()
                    .picture());
            case Tasks.QUEST -> openQuest(s);
            default -> rewrite(s.item());
        }
    }

    /** Opens a quest in the quest book; closing the book comes back here. */
    private void openQuest(final TaskThings.Shown s) {
        final Tasks.Quest q = s.item()
            .quest();
        if (q == null || !TaskQuests.open(graph(), taskId, q)) {
            Sfx.DENY.play();
            session.flash(
                com.gtnhplanner.data.flowchart.balancer.Severity.WARN,
                TaskQuests.available() ? "That quest is not in this quest book"
                    : "There is no quest book in this game");
            return;
        }
        Hyb.click();
    }

    /** Shows a spot in the world: the planner closes and a beam stands on it; a spot elsewhere says where it is. */
    private void showPlace(final Tasks.Place p) {
        if (!TaskThings.inThisWorld(p)) {
            Sfx.DENY.play();
            session.flash(com.gtnhplanner.data.flowchart.balancer.Severity.WARN, "That spot is in another world");
            return;
        }
        Hyb.click();
        com.gtnhplanner.ui.world.WorldView.show(p);
    }

    /** Shows a placed card's machine in the world. */
    private void showPlaced(final TaskThings.Shown s) {
        if (s.plan() == null || s.node() == null) return;
        Hyb.click();
        com.gtnhplanner.ui.world.WorldView.show(s.plan(), s.node().id);
    }

    private void rewrite(final Tasks.Item item) {
        Popup.open(session.panel(), TextPopup.create("Change the words", item.text(), text -> {
            if (text == null || text.isBlank()) return;
            final Tasks.Item now = TaskEdits.item(session.graph(), item.id());
            if (now != null && !text.strip()
                .equals(now.text())) putItem(TaskEdits.withText(now, text));
        }), getContext().getAbsMouseX(), getContext().getAbsMouseY());
    }

    private void rename(final Tasks.Item item) {
        final Tasks.Place p = item.place();
        Popup.open(
            session.panel(),
            TextPopup.create("Name this spot", p == null || p.label() == null ? "" : p.label(), text -> {
                final Tasks.Item now = TaskEdits.item(session.graph(), item.id());
                if (now != null) {
                    final Tasks.Item next = TaskEdits.withLabel(now, text);
                    if (!next.equals(now)) putItem(next);
                }
            }),
            getContext().getAbsMouseX(),
            getContext().getAbsMouseY());
    }

    private void close() {
        if (getPanel() != null) getPanel().closeIfOpen();
        Sfx.CLOSE.play();
    }

    /** What is written but not yet put in the plan goes in as the box closes, however it closes. */
    private void closing() {
        closed = true;
        final Tasks.Task t = task();
        if (t == null) return;
        final String written = title.text()
            .strip();
        if (!written.isEmpty() && !written.equals(t.title())) setTitle(written);
        final String text = notes.text()
            .isBlank() ? null
                : notes.text()
                    .strip();
        if (!java.util.Objects.equals(text, t.text())) setText(text);
    }

    /** Set once the box has closed. */
    private boolean closed;

    /** The screen hears every release: a thing let go off the box never reaches it, and ends here all the same. */
    public static void screenReleased(final int mouseButton) {
        final TaskPanel p = showing.get();
        if (mouseButton == 0 && p != null && !p.closed && p.page.pressed != null) p.page.release();
    }

    // endregion

    @Override
    public void onUpdate() {
        super.onUpdate();
        final Tasks.Task t = task();
        // Undone away, or deleted: nothing left to show.
        if (t == null) {
            if (getPanel() != null) getPanel().closeIfOpen();
            return;
        }
        if (!title.isFocused()) title.text(t.title());
        if (!notes.isFocused()) notes.text(t.text() == null ? "" : t.text());
    }

    // region The bar and the foot

    private int mouseX() {
        return getContext().getAbsMouseX() - getArea().x;
    }

    private int mouseY() {
        return getContext().getAbsMouseY() - getArea().y;
    }

    @Override
    public boolean canHover() {
        return true;
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        hits.clear();
        final Tasks.Task t = task();
        if (t == null) return;
        final Hit hover = page.carrying ? null : hitAt(lastHits, mouseX(), mouseY());
        // The bar: where the task is (its board, its column), and the close key.
        final float ty = (BAR - 8) / 2f;
        TaskLook.dot(PAD, ty + 1, 6, TaskLook.colour(t.status()));
        final String board = graph().getName()
            .isBlank() ? "Task board" : graph().getName();
        final String where = board + "  /  " + TaskLook.name(t.status());
        Hyb.text(Hyb.width(where) <= boxW - PAD - 40 ? where : TaskLook.name(t.status()), PAD + 11, ty, DIM);
        final int cx = boxW - 20, cy = (BAR - 14) / 2;
        final boolean closeHot = is(hover, Kind.CLOSE);
        if (closeHot) Hyb.rect(cx, cy, 14, 14, CONTROL_HOVER);
        cross(cx + 7f, cy + 7f, closeHot ? Hyb.INK : Hyb.MUTED);
        hits.add(new Hit(Kind.CLOSE, cx, cy, cx + 14, cy + 14, null));
        Hyb.rect(1, BAR - 1, boxW - 2, 1, CardPaint.HAIR);

        // The foot: the comment box and its key under the main column; Delete task under the side column.
        fitFoot();
        final int fy = boxH - foot;
        Hyb.rect(1, fy, boxW - 2, 1, CardPaint.HAIR);
        final int cw = commentW();
        final boolean writing = comment.isFocused();
        Hyb.rect(mainX, fy + 7, cw, foot - 14, writing ? 0xFF3E7F8C : CardPaint.EDGE);
        Hyb.rect(mainX + 1, fy + 8, cw - 2, foot - 16, WELL);
        final int kx = mainX + mainW - 62, ky = fy + 11;
        final boolean postHot = is(hover, Kind.POST);
        final boolean any = !comment.text()
            .isBlank();
        Hyb.rect(kx, ky, 62, KEY_H, any ? postHot ? 0xFF38E1F5 : CYAN : postHot ? CONTROL_HOVER : CONTROL);
        if (any) Hyb.rect(kx, ky, 62, 1, 0x66FFFFFF);
        Hyb.text("Comment", kx + (62 - Hyb.width("Comment")) / 2f, ky + 4, 1f, any ? 0xFF0B1A1E : DIM, false);
        hits.add(new Hit(Kind.POST, kx, ky, kx + 62, ky + KEY_H, null));
        if (wide) {
            final String del = "Delete task";
            final int dw = Hyb.width(del) + 12, dx = sideX + sideW - dw, dy = fy + 11;
            Hyb.bevel(
                dx,
                dy,
                dw,
                KEY_H,
                is(hover, Kind.DELETE) ? Hyb.KEY_HOVER : Hyb.KEY,
                Hyb.KEY_HI,
                Hyb.KEY_LO,
                0,
                1);
            Hyb.text(del, dx + 6, dy + 4, Hyb.RED_INK);
            hits.add(new Hit(Kind.DELETE, dx, dy, dx + dw, dy + KEY_H, null));
        }
        lastHits.clear();
        lastHits.addAll(hits);
    }

    // endregion

    // region The page

    /**
     * Everything between the bar and the foot, as one page that scrolls when it is taller than its window: drawn here,
     * with the title and notes boxes moved along with it, all clipped to the window.
     */
    private final class Page extends ParentWidget<Page> implements Interactable, IViewport {

        private int scroll, fieldsAt = Integer.MIN_VALUE;
        private int pageH;
        private final List<Hit> pageHits = new ArrayList<>(), lastPageHits = new ArrayList<>();
        /** Where the title and notes boxes go on the page, and how tall, as last laid out. */
        private int titleY, titleH, notesY, notesH;
        /** A thing pressed, where, and whether it is being carried to a new place; where it would land. */
        @Nullable
        private String pressed;
        private int pressX, pressY, drop;
        private boolean carrying;
        /** The things as last drawn, by id, for a click to act on. */
        private final Map<String, TaskThings.Shown> shown = new HashMap<>();
        private final Map<String, int[]> rows = new LinkedHashMap<>();

        Page() {
            // The title and notes boxes are placed as the page lays out.
        }

        private int windowH() {
            return getArea().height;
        }

        private int mouseX() {
            return getContext().getAbsMouseX() - getArea().x;
        }

        /** The mouse on the page: its y in the window, scrolled. */
        private int mouseY() {
            return getContext().getAbsMouseY() - getArea().y + scroll;
        }

        private boolean mouseIn() {
            final int x = getContext().getAbsMouseX() - getArea().x, y = getContext().getAbsMouseY() - getArea().y;
            return x >= 0 && y >= 0 && x < getArea().width && y < getArea().height;
        }

        @Override
        public boolean canHover() {
            return true;
        }

        /** The title and notes boxes take the mouse only inside the window. */
        @Override
        public void getWidgetsAt(final IViewportStack stack,
            final com.cleanroommc.modularui.utils.HoveredWidgetList widgets, final int x, final int y) {
            if (mouseIn()) IViewport.super.getWidgetsAt(stack, widgets, x, y);
        }

        @Override
        public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
            // Laid out before anything is drawn, so the boxes are where the page puts them this frame.
            layout();
        }

        @Override
        public void preDraw(final ModularGuiContext context, final boolean transformed) {
            if (!transformed) {
                Stencil.applyAtZero(getArea(), context);
                return;
            }
            pageHits.clear();
            final Tasks.Task t = task();
            if (t == null) return;
            if (carrying) drop = findDrop();
            final Hit hover = carrying ? null : hover();
            GL11.glPushMatrix();
            GL11.glTranslatef(0, -scroll, 0);
            drawPage(t, hover, context.getCurrentDrawingZ());
            GL11.glPopMatrix();
        }

        @Override
        public void postDraw(final ModularGuiContext context, final boolean transformed) {
            if (transformed) {
                if (carrying) drawCarried();
                return;
            }
            Stencil.remove();
            final int max = maxScroll();
            if (max > 0) {
                // A thin bar down the right edge: how far down the page the window is.
                final int track = windowH() - 4, bar = Math.max(12, track * track / (track + max));
                Hyb.rect(boxW - 4, 2 + (track - bar) * scroll / max, 2, bar, 0xFF4A4C54);
            }
            // What the mouse rests on explains itself, over everything in the box.
            if (getScreen().getPanelManager()
                .getTopMostPanel() != TaskPanel.this.getPanel()) return;
            final Hit pageHover = carrying || pressed != null ? null : hover();
            final Hit boxHover = carrying ? null : hitAt(lastHits, TaskPanel.this.mouseX(), TaskPanel.this.mouseY());
            final Hit h = pageHover != null ? pageHover : boxHover;
            if (!tipWait.ready(h == null ? null : h.kind() + ":" + h.data())) return;
            final Tip tip = tip(h);
            if (tip != null) {
                GL11.glPushMatrix();
                GL11.glTranslatef(0, -BAR, 0);
                tip.drawNear(TaskPanel.this.mouseX(), TaskPanel.this.mouseY(), boxW, boxH);
                GL11.glPopMatrix();
            }
        }

        private int maxScroll() {
            return Math.max(0, pageH - windowH());
        }

        @Nullable
        private Hit hover() {
            if (!mouseIn()) return null;
            return hitAt(lastPageHits, mouseX(), mouseY());
        }

        // region Layout

        /** Where the title and notes go this frame, and the boxes moved there. */
        private void layout() {
            final Tasks.Task t = task();
            if (t == null) return;
            titleY = 8;
            titleH = title.heightFor(title.isFocused() ? title.text() : t.title(), mainW);
            int y = titleY + titleH + 6;
            if (!wide) y += sideHeight(t) + SECTION;
            notesY = y + LABEL;
            final int lines = NoteText.of(notes.text(), mainW - 10, Hyb::width)
                .lines()
                .size();
            notesH = Math.max(32, Math.min(122, lines * LINE + 10));
            scroll = Math.max(0, Math.min(maxScroll(), scroll));
            if (scroll != fieldsAt || title.getArea().height != titleH || notes.getArea().height != notesH - 2) {
                fieldsAt = scroll;
                title.pos(mainX, titleY - scroll)
                    .size(mainW, titleH);
                notes.pos(mainX + 1, notesY + 1 - scroll)
                    .size(mainW - 2, notesH - 2);
                scheduleResize();
            }
        }

        // endregion

        // region Drawing

        private void drawPage(final Tasks.Task t, @Nullable final Hit hover, final float z) {
            // The title: a heading, lit while the mouse is on it, a box to write in while it is being written.
            if (title.isFocused()) {
                Hyb.rect(mainX - 1, titleY - 1, mainW + 2, titleH + 2, 0xFF3E7F8C);
                Hyb.rect(mainX, titleY, mainW, titleH, WELL);
            } else if (title.isHovering()) Hyb.rect(mainX, titleY, mainW, titleH, ROW_HOVER);
            int y = titleY + titleH + 6;
            if (!wide) {
                drawSide(t, hover, PAD, y, sideW);
                y += sideHeight(t) + SECTION;
            } else drawSide(t, hover, sideX, titleY, sideW);

            // Notes.
            label("Notes", null, y);
            final boolean writing = notes.isFocused();
            Hyb.rect(mainX, notesY, mainW, notesH, writing ? 0xFF3E7F8C : CardPaint.EDGE);
            Hyb.rect(mainX + 1, notesY + 1, mainW - 2, notesH - 2, WELL);
            y = notesY + notesH + SECTION;

            // Things.
            y = drawThings(t, hover, y, z);
            y += SECTION;

            // Comments.
            y = drawComments(hover, y);
            pageH = Math.max(y + 10, wide ? titleY + sideHeight(t) + 10 : 0);
            lastPageHits.clear();
            lastPageHits.addAll(pageHits);
        }

        /** A section's name, muted, with a count after it. */
        private void label(final String name, @Nullable final String count, final int y) {
            Hyb.text(name, mainX, y + 1, Hyb.MUTED);
            if (count != null) Hyb.text(count, mainX + Hyb.width(name) + 5, y + 1, FAINT);
        }

        // region Things

        private int drawThings(final Tasks.Task t, @Nullable final Hit hover, final int top, final float z) {
            final List<TaskThings.Shown> things = TaskThings.of(session, graph(), taskId);
            shown.clear();
            for (final TaskThings.Shown s : things) shown.put(
                s.item()
                    .id(),
                s);
            int done = 0;
            for (final TaskThings.Shown s : things) if (s.item()
                .done()) done++;
            label("Things", things.isEmpty() ? null : done + "/" + things.size(), top);
            if (!things.isEmpty()) {
                // How far along: a thin bar after the count.
                final String n = done + "/" + things.size();
                final int bx = mainX + Hyb.width("Things") + 5 + Hyb.width(n) + 8, bw = mainX + mainW - bx;
                if (bw > 16) {
                    Hyb.rect(bx, top + 4, bw, 3, 0xFF2C2E34);
                    Hyb.rect(bx, top + 4, Math.round(bw * done / (float) things.size()), 3, Hyb.OK_INK);
                }
            }
            int y = top + LABEL;
            rows.clear();
            int index = 0;
            int dropY = -1;
            for (final TaskThings.Shown s : things) {
                final String id = s.item()
                    .id();
                final boolean carried = carrying && id.equals(pressed);
                if (carrying && index == drop && !carried) dropY = y;
                if (!carried) index++;
                final int h = rowH(s);
                rows.put(id, new int[] { y, h });
                drawThing(s, hover, y, h, z, carried);
                y += h;
            }
            if (carrying && dropY < 0) dropY = y;
            if (carrying) Hyb.rect(mainX, dropY - 1, mainW, 2, CYAN);
            if (things.isEmpty()) {
                Hyb.text("Nothing on it yet.", mainX + 2, y + 2, FAINT);
                y += 14;
            }
            // The keys that add things: where you stand, a screenshot and a quest, while in a world.
            y += 4;
            if (net.minecraft.client.Minecraft.getMinecraft().theWorld != null) {
                final List<AddKey> keys = new ArrayList<>();
                keys.add(new AddKey("Add my location", Kind.ADD_HERE, Icons::pin));
                keys.add(new AddKey("Add a screenshot", Kind.ADD_SHOT, Icons::camera));
                if (TaskQuests.available()) keys.add(new AddKey("Add a quest", Kind.ADD_QUEST, Icons::book));
                int x = mainX;
                for (final AddKey k : keys) {
                    final String label = k.label();
                    if (x > mainX && x + 6 + keyW(label) > mainX + mainW) {
                        x = mainX;
                        y += KEY_H + 4;
                    } else if (x > mainX) x += 6;
                    x = addKey(x, y, label, k.kind(), hover, k.icon());
                }
                y += KEY_H + 6;
            }
            final NoteText hint = NoteText.of("Add machines from a card's pin button.", mainW, Hyb::width);
            for (int i = 0; i < hint.lines()
                .size(); i++) {
                Hyb.text(hint.shown(i), mainX, y + 1, FAINT);
                y += LINE;
            }
            return y;
        }

        /** A key that adds a thing: its words, what it does, its picture. */
        private record AddKey(String label, Kind kind, Picture icon) {}

        private int keyW(final String label) {
            return Hyb.width(label) + 12 + Icons.SIZE + 4;
        }

        /** A key with an icon before its words; returns its right edge. */
        private int addKey(final int x, final int y, final String label, final Kind kind, @Nullable final Hit hover,
            final Picture icon) {
            final int w = keyW(label);
            final boolean hot = is(hover, kind);
            Hyb.bevel(x, y, w, KEY_H, hot ? Hyb.KEY_HOVER : Hyb.KEY, Hyb.KEY_HI, Hyb.KEY_LO, 0, 1);
            final int ix = x + 6, iy = y + (KEY_H - 1 - Icons.SIZE) / 2;
            icon.draw(ix, iy, kind == Kind.ADD_QUEST ? QUEST_INK : CYAN);
            Hyb.text(label, ix + Icons.SIZE + 4, y + 4, Hyb.INK);
            pageHits.add(new Hit(kind, x, y, x + w, y + KEY_H, null));
            return x + w;
        }

        /** Where a thing's words start, after its checkbox and its picture. */
        private int textX(final TaskThings.Shown s) {
            return mainX + 17 + switch (s.kind()) {
                case Tasks.MACHINE, Tasks.LOCATION, Tasks.QUEST -> 16 + 6;
                case Tasks.PICTURE -> THUMB_W + 6;
                default -> 0;
            };
        }

        private static final int THUMB_W = 56, THUMB_H = 32;

        /** The room a thing's words have: up to its keys at the right. */
        private int textW(final TaskThings.Shown s) {
            return mainX + mainW - textX(s) - 16 - (showKey(s) ? showKeyW(s) + 8 : 0);
        }

        private NoteText words(final TaskThings.Shown s) {
            return NoteText.of(s.words(), textW(s), Hyb::width);
        }

        private List<String> detail(final TaskThings.Shown s) {
            return parts(s.detail(), textW(s));
        }

        private int rowH(final TaskThings.Shown s) {
            final int lines = words(s).lines()
                .size() + detail(s).size();
            final int picture = switch (s.kind()) {
                case Tasks.MACHINE, Tasks.LOCATION, Tasks.QUEST -> 16;
                case Tasks.PICTURE -> THUMB_H;
                default -> 0;
            };
            return Math.max(picture, lines * LINE) + 2 * ROW_PAD;
        }

        /** Whether a thing has a key that shows where it is in the world: a spot here, a card placed here. */
        private boolean showKey(final TaskThings.Shown s) {
            return switch (s.kind()) {
                case Tasks.LOCATION -> TaskThings.inThisWorld(
                    s.item()
                        .place());
                case Tasks.MACHINE -> s.node() != null && !s.node().worldLinks.isEmpty();
                default -> false;
            };
        }

        private int showKeyW(final TaskThings.Shown s) {
            return Tasks.LOCATION.equals(s.kind()) ? Hyb.width("Show") + 10 + Icons.SIZE + 3 : 16;
        }

        private void drawThing(final TaskThings.Shown s, @Nullable final Hit hover, final int y, final int h,
            final float z, final boolean carried) {
            final Tasks.Item item = s.item();
            final String id = item.id();
            final boolean rowHot = !carrying && hover != null && id.equals(hover.data());
            if (rowHot) Hyb.rect(mainX, y, mainW, h, ROW_HOVER);
            if (carried) Hyb.rect(mainX, y, mainW, h, 0x3022D3EE);
            // Its checkbox, level with the first line of its words.
            final NoteText words = words(s);
            final List<String> detail = detail(s);
            final int lines = words.lines()
                .size() + detail.size();
            final int textTop = y + (h - lines * LINE) / 2 + 1;
            final int bx = mainX + 3, by = Math.max(y + ROW_PAD, textTop - 1);
            final boolean boxHot = is(hover, Kind.BOX, id);
            checkbox(bx, by, item.done(), boxHot);
            // Its picture.
            final int px = mainX + 17;
            switch (s.kind()) {
                case Tasks.MACHINE -> {
                    if (s.icon() != null) {
                        Hyb.item(s.icon(), px, y + (h - 16) / 2f, 16, z);
                        GL11.glDisable(GL11.GL_LIGHTING);
                    } else tile(px, y + (h - 16) / 2, Icons::card, s.gone() ? FAINT : Hyb.MUTED);
                }
                case Tasks.LOCATION -> tile(px, y + (h - 16) / 2, Icons::pin, CYAN);
                case Tasks.QUEST -> {
                    if (s.icon() != null) {
                        Hyb.item(s.icon(), px, y + (h - 16) / 2f, 16, z);
                        GL11.glDisable(GL11.GL_LIGHTING);
                    } else tile(px, y + (h - 16) / 2, Icons::book, s.gone() ? FAINT : QUEST_INK);
                }
                case Tasks.PICTURE -> {
                    final int ty = y + (h - THUMB_H) / 2;
                    Hyb.rect(px - 1, ty - 1, THUMB_W + 2, THUMB_H + 2, CardPaint.EDGE);
                    TaskPictures.draw(TaskPictures.thumb(item.picture()), px, ty, THUMB_W, THUMB_H);
                }
                default -> {}
            }
            // Its words and the line under them.
            final int tx = textX(s);
            final int ink = item.done() || s.gone() ? 0xFF8A8C94 : Hyb.INK;
            int ty = textTop;
            for (int i = 0; i < words.lines()
                .size(); i++) {
                final String line = words.shown(i);
                Hyb.text(line, tx, ty, ink);
                if (item.done()) Hyb.rect(tx, ty + 3, Hyb.width(line), 1, 0xA08A8C94);
                ty += LINE;
            }
            for (final String line : detail) {
                drawParts(line, tx, ty, s.gone() ? Hyb.AMBER_INK : DIM);
                ty += LINE;
            }
            pageHits.add(new Hit(Kind.THING, mainX, y, mainX + mainW, y + h, id));
            pageHits.add(new Hit(Kind.BOX, mainX, y, mainX + 15, y + h, id));
            // Its keys: where it is in the world; hovered, take it off.
            int right = mainX + mainW - 2;
            if (rowHot) {
                final boolean on = is(hover, Kind.THING_DELETE, id);
                cross(right - 5f, y + h / 2f, on ? Hyb.RED_INK : Hyb.MUTED);
                pageHits.add(new Hit(Kind.THING_DELETE, right - 11, y, right + 1, y + h, id));
            }
            right -= 14;
            if (showKey(s)) {
                final int kw = showKeyW(s), kx = right - kw, ky = y + (h - KEY_H) / 2;
                final boolean on = is(hover, Kind.SHOW, id);
                Hyb.bevel(kx, ky, kw, KEY_H, on ? Hyb.KEY_HOVER : Hyb.KEY, Hyb.KEY_HI, Hyb.KEY_LO, 0, 1);
                if (Tasks.LOCATION.equals(s.kind())) {
                    Icons.pin(kx + 5, ky + (KEY_H - 1 - Icons.SIZE) / 2, CYAN);
                    Hyb.text("Show", kx + 5 + Icons.SIZE + 3, ky + 4, Hyb.INK);
                } else Icons.pin(kx + (kw - Icons.SIZE) / 2, ky + (KEY_H - 1 - Icons.SIZE) / 2, CYAN);
                pageHits.add(new Hit(Kind.SHOW, kx, ky, kx + kw, ky + KEY_H, id));
            }
        }

        private interface Picture {

            void draw(int x, int y, int colour);
        }

        /** A 16 pixel tile with a small picture in it, for a thing with no item to show. */
        private void tile(final int x, final int y, final Picture picture, final int colour) {
            Hyb.rect(x, y, 16, 16, 0xFF2A2C31);
            picture.draw(x + 3, y + 3, colour);
        }

        private void checkbox(final int x, final int y, final boolean done, final boolean hot) {
            Hyb.rect(x, y, 10, 10, done ? Hyb.OK_INK : hot ? 0xFF8F939C : 0xFF5A5D66);
            if (!done) {
                Hyb.rect(x + 1, y + 1, 8, 8, WELL);
                return;
            }
            // A tick.
            final int ink = 0xFF0B1A12;
            Hyb.rect(x + 2, y + 5, 1, 2, ink);
            Hyb.rect(x + 3, y + 6, 1, 2, ink);
            Hyb.rect(x + 4, y + 5, 1, 2, ink);
            Hyb.rect(x + 5, y + 4, 1, 2, ink);
            Hyb.rect(x + 6, y + 3, 1, 2, ink);
            Hyb.rect(x + 7, y + 2, 1, 2, ink);
        }

        /** Where a carried thing would land: before the first other thing whose middle is below the mouse. */
        private int findDrop() {
            final int my = mouseY();
            int index = 0;
            for (final Map.Entry<String, int[]> e : rows.entrySet()) {
                if (e.getKey()
                    .equals(pressed)) continue;
                final int[] r = e.getValue();
                if (my < r[0] + r[1] / 2) return index;
                index++;
            }
            return index;
        }

        /** The thing in hand, lifted, following the mouse. */
        private void drawCarried() {
            final TaskThings.Shown s = shown.get(pressed);
            if (s == null) return;
            final int h = rowH(s), y = Math.max(0, Math.min(windowH(), mouseY() - scroll)) - h / 2;
            Hyb.rect(mainX + 3, y + 4, mainW, h, 0x50000000);
            Hyb.rect(mainX, y, mainW, h, CardPaint.EDGE);
            Hyb.rect(mainX + 1, y + 1, mainW - 2, h - 2, ROW_HOVER);
            final NoteText words = words(s);
            for (int i = 0; i < words.lines()
                .size(); i++) Hyb.text(words.shown(i), textX(s), y + ROW_PAD + 1 + i * LINE, Hyb.INK);
        }

        // endregion

        // region Comments

        private int drawComments(@Nullable final Hit hover, final int top) {
            final List<Tasks.Comment> comments = TaskEdits.comments(graph(), taskId);
            label("Comments", comments.isEmpty() ? null : Integer.toString(comments.size()), top);
            int y = top + LABEL;
            if (comments.isEmpty()) {
                Hyb.text("No comments yet.", mainX + 2, y + 2, FAINT);
                return y + 14;
            }
            final int tx = mainX + TaskLook.FACE + 6, tw = mainX + mainW - tx;
            for (final Tasks.Comment c : comments) {
                final NoteText words = NoteText.of(c.text(), tw, Hyb::width);
                final int h = 12 + words.lines()
                    .size() * LINE + 2;
                pageHits.add(new Hit(Kind.COMMENT, mainX, y, mainX + mainW, y + h, c.id()));
                final boolean rowHot = hover != null && c.id()
                    .equals(hover.data());
                TaskLook.face(graph(), c.by(), mainX, y);
                final String who = c.byName()
                    .isEmpty() ? "Someone" : c.byName();
                Hyb.text(who, tx, y + 2, Hyb.INK);
                Hyb.text(TaskLook.ago(c.at()), tx + Hyb.width(who) + 6, y + 2, FAINT);
                if (TaskLook.mine(c.by()) && rowHot) {
                    final String del = "Delete";
                    final int dx = mainX + mainW - Hyb.width(del) - 2;
                    final boolean on = is(hover, Kind.COMMENT_DELETE, c.id());
                    Hyb.text(del, dx, y + 2, on ? 0xFFFF8080 : Hyb.RED_INK);
                    pageHits.add(new Hit(Kind.COMMENT_DELETE, dx - 2, y, dx + Hyb.width(del) + 2, y + 12, c.id()));
                }
                for (int i = 0; i < words.lines()
                    .size(); i++) Hyb.text(words.shown(i), tx, y + 13 + i * LINE, 0xFFD4D5DA);
                y += h + 8;
            }
            return y - 8;
        }

        // endregion

        // region The side column

        /** The side column's height at the box's width: its fields, and Delete task at its foot when narrow. */
        private int sideHeight(final Tasks.Task t) {
            return side(t, null, 0, 0, sideW, false);
        }

        /** The side column on a panel of its own: measured, its panel drawn, then its fields over it. */
        private void drawSide(final Tasks.Task t, @Nullable final Hit hover, final int x, final int y, final int w) {
            final int h = side(t, null, x, y, w, false);
            Hyb.rect(x, y, w, h, SIDE_EDGE);
            Hyb.rect(x + 1, y + 1, w - 2, h - 2, SIDE);
            side(t, hover, x, y, w, true);
        }

        /**
         * The side column, in a panel of its own: Status, Priority and People; Waiting on and Unblocks; Board, Made and
         * Time. Wide, one under the other in three groups; narrow, two side by side (faces only), then a line of the
         * board and who made it with Delete task at its end. Returns its height; draws only when {@code draw}.
         */
        private int side(final Tasks.Task t, @Nullable final Hit hover, final int x, final int y, final int w,
            final boolean draw) {
            final int inner = w - 2 * SIDE_PAD;
            final int h;
            if (wide) {
                // Three groups, a hairline between each: the task's state; what it waits on and unblocks; quiet facts.
                final int fx0 = x + SIDE_PAD;
                int at = y + SIDE_PAD + 2;
                at = item("Status", fx0, at, inner, draw, (fx, fy, fw) -> status(t, hover, fx, fy, fw, draw));
                at = item("Priority", fx0, at, inner, draw, (fx, fy, fw) -> priority(t, hover, fx, fy, fw, draw));
                at = item("People", fx0, at, inner, draw, (fx, fy, fw) -> people(t, hover, fx, fy, fw, true, draw));
                at = divider(x, at, w, draw);
                at = item("Waiting on", fx0, at, inner, draw, (fx, fy, fw) -> blockedBy(t, hover, fx, fy, fw, draw));
                at = item("Unblocks", fx0, at, inner, draw, (fx, fy, fw) -> blocks(t, hover, fx, fy, fw, draw));
                at = divider(x, at, w, draw);
                at = fact("Board", boardName(), fx0, at, inner, draw);
                at = fact("Made", made(t), fx0, at, inner, draw);
                at = fact("Time", TaskLook.inColumnShort(t), fx0, at, inner, draw);
                h = at - 6 + SIDE_PAD - y;
            } else {
                final int half = (inner - 12) / 2, lx = x + SIDE_PAD, rx = lx + half + 12;
                int at = y + SIDE_PAD + 2;
                final int a0 = item("Status", lx, at, half, draw, (fx, fy, fw) -> status(t, hover, fx, fy, fw, draw));
                final int a = item(
                    "Priority",
                    lx,
                    a0,
                    half,
                    draw,
                    (fx, fy, fw) -> priority(t, hover, fx, fy, fw, draw));
                final int b = item(
                    "People",
                    rx,
                    at,
                    half,
                    draw,
                    (fx, fy, fw) -> people(t, hover, fx, fy, fw, false, draw));
                at = divider(x, Math.max(a, b), w, draw);
                // What it waits on and what it unblocks, side by side.
                final int c0 = at;
                final int c = item(
                    "Waiting on",
                    lx,
                    c0,
                    half,
                    draw,
                    (fx, fy, fw) -> blockedBy(t, hover, fx, fy, fw, draw));
                final int d = item("Unblocks", rx, c0, half, draw, (fx, fy, fw) -> blocks(t, hover, fx, fy, fw, draw));
                at = divider(x, Math.max(c, d), w, draw) - 3;
                // The board and who made it, Delete task at the line's end.
                final String del = "Delete task";
                final int dw = Hyb.width(del) + 12, dx = x + w - SIDE_PAD - dw;
                final List<String> info = parts(boardName() + TaskThings.SEP + madeBy(t), dx - 8 - lx);
                if (draw) {
                    for (int i = 0; i < info.size(); i++) drawParts(info.get(i), lx, at + 4 + i * LINE, DIM);
                    Hyb.bevel(
                        dx,
                        at,
                        dw,
                        KEY_H,
                        is(hover, Kind.DELETE) ? Hyb.KEY_HOVER : Hyb.KEY,
                        Hyb.KEY_HI,
                        Hyb.KEY_LO,
                        0,
                        1);
                    Hyb.text(del, dx + 6, at + 4, Hyb.RED_INK);
                    pageHits.add(new Hit(Kind.DELETE, dx, at, dx + dw, at + KEY_H, null));
                }
                h = at + Math.max(KEY_H, info.size() * LINE + 6) + SIDE_PAD - y;
            }
            return h;
        }

        private interface Field {

            /** Draws (or measures) a field's value at x, y, w; returns the y under it. */
            int at(int x, int y, int w);
        }

        /**
         * A side column item: its label, quiet and in capitals, and its value under it; returns where the next starts.
         */
        private int item(final String name, final int x, final int y, final int w, final boolean draw,
            final Field value) {
            if (draw) Hyb.text(name.toUpperCase(java.util.Locale.ROOT), x, y, LABEL_INK);
            return value.at(x, y + LABEL_GAP, w) + ITEM_GAP;
        }

        /** A hairline across the panel between two groups; returns where the next group starts. */
        private int divider(final int x, final int y, final int w, final boolean draw) {
            if (draw) Hyb.rect(x + 1, y - 2, w - 2, 1, SIDE_EDGE);
            return y + ITEM_GAP - 2;
        }

        /**
         * A quiet fact: its label and its words on one line, the words at the right; when they do not fit side by
         * side, the words go under the label. Returns where the next starts.
         */
        private int fact(final String name, final String text, final int x, final int y, final int w,
            final boolean draw) {
            if (Hyb.width(name) + 10 + Hyb.width(text) <= w) {
                if (draw) {
                    Hyb.text(name, x, y, LABEL_INK);
                    Hyb.textRight(text, x + w, y, Hyb.MUTED);
                }
                return y + LINE + 6;
            }
            if (draw) Hyb.text(name, x, y, LABEL_INK);
            final NoteText lines = NoteText.of(text, w, Hyb::width);
            int at = y + LINE + 1;
            for (int i = 0; i < lines.lines()
                .size(); i++) {
                if (draw) Hyb.text(lines.shown(i), x, at, Hyb.MUTED);
                at += LINE;
            }
            return at + 6;
        }

        /** Words wrapped to a width; returns the y under them. */
        private int words(final String text, final int x, final int y, final int w, final boolean draw) {
            final NoteText lines = NoteText.of(text, w, Hyb::width);
            int at = y;
            for (int i = 0; i < lines.lines()
                .size(); i++) {
                if (draw) Hyb.text(lines.shown(i), x, at + 1, Hyb.INK);
                at += LINE;
            }
            return at;
        }

        private String boardName() {
            return graph().getName()
                .isBlank() ? "Task board" : graph().getName();
        }

        /** "made by you 3d ago", "made by ann 5m ago": who made it and when, as a part of a line. */
        private String madeBy(final Tasks.Task t) {
            final String when = TaskLook.ago(t.createdAt());
            final String by = TaskLook.mine(t.createdBy()) ? "you"
                : t.createdBy() == null ? null : TaskLook.nameOf(graph(), t.createdBy());
            String s = "made" + (by == null ? "" : " by " + by) + (when.isEmpty() ? "" : " " + when);
            if (t.status() == Tasks.Status.DONE && t.doneAt() != null)
                s += TaskThings.SEP + "done " + TaskLook.ago(t.doneAt());
            return s + TaskThings.SEP + TaskLook.inColumnShort(t);
        }

        /** "You, 3d ago", "ann, 5m ago": who made it and when. */
        private String made(final Tasks.Task t) {
            final String when = TaskLook.ago(t.createdAt());
            final String by = TaskLook.mine(t.createdBy()) ? "You"
                : t.createdBy() == null ? null : TaskLook.nameOf(graph(), t.createdBy());
            String s = by == null ? "" : by;
            if (!when.isEmpty()) s = s.isEmpty() ? when : s + ", " + when;
            if (t.status() == Tasks.Status.DONE && t.doneAt() != null) s += ". Done " + TaskLook.ago(t.doneAt());
            return s.isEmpty() ? "Not known" : s;
        }

        /** The status as a picker: its dot, its name and a caret, a menu of the four on a click. */
        private int status(final Tasks.Task t, @Nullable final Hit hover, final int x, final int y, final int w,
            final boolean draw) {
            final int h = 18;
            if (draw) {
                final boolean hot = is(hover, Kind.STATUS);
                final int colour = TaskLook.colour(t.status());
                Hyb.rect(x, y, w, h, hot ? Hyb.mix(colour, CONTROL, 0.5f) : Hyb.mix(colour, CONTROL, 0.65f));
                Hyb.rect(
                    x + 1,
                    y + 1,
                    w - 2,
                    h - 2,
                    hot ? Hyb.mix(colour, CardPaint.SURFACE, 0.22f) : Hyb.mix(colour, CardPaint.SURFACE, 0.14f));
                TaskLook.dot(x + 6, y + 6, 6, colour);
                Hyb.text(TaskLook.name(t.status()), x + 16, y + 5, Hyb.INK);
                // The caret: a small triangle pointing down.
                final int cx = x + w - 11, cy = y + 7;
                for (int i = 0; i < 3; i++) Hyb.rect(cx + i, cy + i, 5 - 2 * i, 1, Hyb.MUTED);
                pageHits.add(new Hit(Kind.STATUS, x, y, x + w, y + h, null));
            }
            return y + h;
        }

        /** The priority as a picker: its icon, its name and a caret, a menu of the three on a click. */
        private int priority(final Tasks.Task t, @Nullable final Hit hover, final int x, final int y, final int w,
            final boolean draw) {
            final int h = 18;
            if (draw) {
                final boolean hot = is(hover, Kind.PRIORITY);
                final String p = t.shownPriority();
                final int colour = TaskLook.priorityColour(p);
                Hyb.rect(x, y, w, h, hot ? Hyb.mix(colour, CONTROL, 0.5f) : Hyb.mix(colour, CONTROL, 0.65f));
                Hyb.rect(
                    x + 1,
                    y + 1,
                    w - 2,
                    h - 2,
                    hot ? Hyb.mix(colour, CardPaint.SURFACE, 0.22f) : Hyb.mix(colour, CardPaint.SURFACE, 0.14f));
                TaskLook.priority(p, x + 5, y + 6);
                Hyb.text(TaskLook.priorityName(p), x + 16, y + 5, Hyb.INK);
                final int cx = x + w - 11, cy = y + 7;
                for (int i = 0; i < 3; i++) Hyb.rect(cx + i, cy + i, 5 - 2 * i, 1, Hyb.MUTED);
                pageHits.add(new Hit(Kind.PRIORITY, x, y, x + w, y + h, null));
            }
            return y + h;
        }

        /**
         * A task in a list of the side column: its status dot and title on one line, cut after a whole word; a click
         * opens it. Returns
         * its height; draws only when {@code draw}.
         */
        private int linkRow(final Tasks.Task o, final Kind kind, @Nullable final Hit hover, final int x, final int y,
            final int w, final boolean removable, final boolean draw) {
            final int room = w - 9 - (removable ? 12 : 0);
            // One line, cut after a whole word; its tip has the whole title.
            final List<String> lines = List.of(TaskLook.fitWords(o.title(), room));
            final int h = LINK_H;
            if (!draw) return h;
            final boolean hot = is(hover, kind, o.id()) || removable && is(hover, Kind.UNBLOCK, o.id());
            if (hot) Hyb.rect(x - 2, y, w + 4, h, CONTROL_HOVER);
            TaskLook.dot(x, y + 5, 5, TaskLook.colour(o.status()));
            final boolean done = o.status() == Tasks.Status.DONE;
            for (int i = 0; i < lines.size(); i++)
                Hyb.text(lines.get(i), x + 9, y + 4 + i * LINE, done ? DIM : Hyb.INK);
            pageHits.add(new Hit(kind, x - 2, y, x + w + 2, y + h, o.id()));
            if (removable && hot) {
                final boolean on = is(hover, Kind.UNBLOCK, o.id());
                cross(x + w - 4f, y + 7.5f, on ? Hyb.RED_INK : Hyb.MUTED);
                pageHits.add(new Hit(Kind.UNBLOCK, x + w - 10, y, x + w + 2, y + h, o.id()));
            }
            return h;
        }

        /** The tasks it waits on, each a row with a key off it, then the key that adds one; why one was refused. */
        private int blockedBy(final Tasks.Task t, @Nullable final Hit hover, final int x, final int y, final int w,
            final boolean draw) {
            int at = y;
            for (final Tasks.Task o : TaskEdits.blockers(graph(), t.id())) {
                at += linkRow(o, Kind.BLOCKER, hover, x, at, w, true, draw);
            }
            if (at > y) at += 3;
            final String label = "Add";
            final int kw = Hyb.width(label) + 12 + Icons.SIZE + 3;
            if (draw) {
                Hyb.bevel(
                    x,
                    at,
                    kw,
                    KEY_H,
                    is(hover, Kind.ADD_BLOCKER) ? Hyb.KEY_HOVER : Hyb.KEY,
                    Hyb.KEY_HI,
                    Hyb.KEY_LO,
                    0,
                    1);
                Icons.plus(x + 5, at + (KEY_H - 1 - Icons.SIZE) / 2, CYAN);
                Hyb.text(label, x + 5 + Icons.SIZE + 3, at + 4, Hyb.INK);
                pageHits.add(new Hit(Kind.ADD_BLOCKER, x, at, x + kw, at + KEY_H, null));
            }
            at += KEY_H;
            if (refused != null && System.currentTimeMillis() < refusedUntil) {
                final NoteText why = NoteText.of(refused, w, Hyb::width);
                for (int i = 0; i < why.lines()
                    .size(); i++) {
                    if (draw) Hyb.text(why.shown(i), x, at + 3 + i * LINE, Hyb.AMBER_INK);
                }
                at += 3 + why.lines()
                    .size() * LINE;
            }
            return at;
        }

        /** The tasks waiting on it, read only: each a row that opens it. */
        private int blocks(final Tasks.Task t, @Nullable final Hit hover, final int x, final int y, final int w,
            final boolean draw) {
            final List<Tasks.Task> waiting = TaskEdits.blocking(graph(), t.id());
            if (waiting.isEmpty()) {
                if (draw) Hyb.text("Nothing waits on it.", x, y + 1, FAINT);
                return y + LINE;
            }
            int at = y;
            for (final Tasks.Task o : waiting) {
                at += linkRow(o, Kind.BLOCKS, hover, x, at, w, false, draw);
            }
            return at;
        }

        /**
         * Who is on it, a face and a name a line ({@code names}) or faces side by side; the key to choose who, or to
         * sign in.
         */
        private int people(final Tasks.Task t, @Nullable final Hit hover, final int x, final int y, final int w,
            final boolean names, final boolean draw) {
            int at = y;
            if (names) for (final String id : t.assignees()) {
                if (draw) {
                    final int fw = TaskLook.face(graph(), id, x, at);
                    final String name = TaskLook.mine(id) ? "You" : TaskLook.nameOf(graph(), id);
                    final String shown = Hyb.width(name == null ? "Someone" : name) <= w - fw - 4
                        ? name == null ? "Someone" : name
                        : TaskLook.initials(name);
                    Hyb.text(shown, x + fw + 4, at + 2, Hyb.INK);
                }
                at += TaskLook.FACE + 3;
            }
            else if (!t.assignees()
                .isEmpty()) {
                    // Faces in a row, wrapping.
                    int fx = x;
                    for (final String id : t.assignees()) {
                        final int fw = TaskLook.faceWidth(TaskLook.initials(TaskLook.nameOf(graph(), id)));
                        if (fx > x && fx + fw > x + w) {
                            fx = x;
                            at += TaskLook.FACE + 3;
                        }
                        if (draw) TaskLook.face(graph(), id, fx, at);
                        fx += fw + 2;
                    }
                    at += TaskLook.FACE + 4;
                }
            final String me = Account.userId();
            if (me == null) {
                if (t.assignees()
                    .isEmpty()) {
                    if (draw) Hyb.text("No one yet.", x, at + 1, FAINT);
                    at += LINE + 2;
                }
                final NoteText s = NoteText.of("Sign in to assign", w, Hyb::width);
                if (draw) {
                    final boolean hot = is(hover, Kind.SIGN_IN);
                    for (int i = 0; i < s.lines()
                        .size(); i++) Hyb.text(s.shown(i), x, at + 1 + i * LINE, hot ? 0xFFFFFFFF : CYAN);
                    pageHits.add(
                        new Hit(
                            Kind.SIGN_IN,
                            x,
                            at,
                            x + w,
                            at + s.lines()
                                .size() * LINE,
                            null));
                }
                return at + s.lines()
                    .size() * LINE;
            }
            final boolean shared = !TaskLook.people(graph())
                .isEmpty();
            final String s = shared ? "Assign"
                : t.assignees()
                    .contains(me) ? "Unassign me" : "Assign me";
            final int kw = Math.min(w, Hyb.width(s) + 12);
            if (draw) {
                Hyb.bevel(
                    x,
                    at,
                    kw,
                    KEY_H,
                    is(hover, Kind.ASSIGN) ? Hyb.KEY_HOVER : Hyb.KEY,
                    Hyb.KEY_HI,
                    Hyb.KEY_LO,
                    0,
                    1);
                Hyb.text(s, x + 6, at + 4, Hyb.INK);
                pageHits.add(new Hit(Kind.ASSIGN, x, at, x + kw, at + KEY_H, shared ? Boolean.TRUE : null));
            }
            return at + KEY_H;
        }

        // endregion

        // endregion

        // region Input

        @Override
        public Result onMousePressed(final int mouseButton) {
            if (BoardScreen.textFocused(getScreen())) getContext().removeFocus();
            final Hit hit = hover();
            final Tasks.Task t = task();
            if (hit == null || t == null) return Result.ACCEPT;
            if (mouseButton == 1) {
                if (hit.kind() == Kind.THING || hit.kind() == Kind.BOX) thingMenu((String) hit.data());
                return Result.SUCCESS;
            }
            if (mouseButton != 0) return Result.ACCEPT;
            final TaskThings.Shown s = hit.data() instanceof final String id ? shown.get(id) : null;
            switch (hit.kind()) {
                case STATUS -> pickStatus(t, hit);
                case PRIORITY -> pickPriority(t, hit);
                case ASSIGN -> assign(t, hit);
                case SIGN_IN -> {
                    Hyb.click();
                    com.gtnhplanner.ui.library.AccountForms.signIn(session.panel(), () -> {});
                }
                case BOX -> {
                    if (s != null) toggle(s);
                }
                case THING -> {
                    // A press on a thing: let go here to act on it, or carry it to a new place.
                    if (s != null && !s.loose()) {
                        pressed = s.item()
                            .id();
                        pressX = getContext().getAbsMouseX();
                        pressY = getContext().getAbsMouseY();
                    } else if (s != null) act(s);
                }
                case SHOW -> {
                    if (s != null && Tasks.LOCATION.equals(s.kind())) showPlace(
                        s.item()
                            .place());
                    else if (s != null) showPlaced(s);
                }
                case THING_DELETE -> {
                    if (s != null) takeOff(s);
                }
                case ADD_HERE -> addHere();
                case ADD_SHOT -> {
                    Hyb.click();
                    TaskPictures.take(graph(), taskId);
                }
                case ADD_QUEST -> {
                    Hyb.click();
                    TaskQuests.pick(session, graph(), taskId);
                }
                case COMMENT_DELETE -> {
                    edit(() -> TaskEdits.removeComment(session.graph(), (String) hit.data()));
                    Sfx.NOTE_CRUMPLE.play();
                }
                case DELETE -> confirmDelete(hit.x0(), hit.y0() - scroll + BAR);
                case BLOCKER, BLOCKS -> openOther((String) hit.data());
                case UNBLOCK -> {
                    edit(() -> TaskEdits.removeBlocker(session.graph(), taskId, (String) hit.data()));
                    Sfx.NOTE_CRUMPLE.play();
                }
                case ADD_BLOCKER -> pickBlocker(hit);
                default -> {}
            }
            return Result.SUCCESS;
        }

        @Override
        public void onMouseDrag(final int mouseButton, final long timeSinceClick) {
            lift();
        }

        @Override
        public void onUpdate() {
            super.onUpdate();
            lift();
        }

        /** A thing pressed and moved {@value #DRAG_SLOP} pixels is carried. */
        private void lift() {
            if (pressed == null || carrying) return;
            final int dx = getContext().getAbsMouseX() - pressX, dy = getContext().getAbsMouseY() - pressY;
            if (Math.max(Math.abs(dx), Math.abs(dy)) < DRAG_SLOP) return;
            carrying = true;
            drop = findDrop();
            Sfx.LIFT.play();
        }

        @Override
        public boolean onMouseRelease(final int mouseButton) {
            if (mouseButton != 0 || pressed == null) return false;
            release();
            return true;
        }

        /** A thing let go: a click acts on it; a carry puts it where it would land. */
        private void release() {
            final String id = pressed;
            pressed = null;
            final TaskThings.Shown s = shown.get(id);
            if (!carrying) {
                if (s != null) act(s);
                return;
            }
            carrying = false;
            final int to = drop;
            final List<Tasks.Item> items = TaskEdits.items(graph(), taskId);
            final Tasks.Item item = TaskEdits.item(graph(), id);
            if (item != null && items.indexOf(item) != to)
                edit(() -> TaskEdits.moveItem(session.graph(), id, Math.min(to, items.size() - 1)));
            Sfx.DROP.play();
        }

        @Override
        public boolean onMouseScroll(final UpOrDown direction, final int amount) {
            scroll = Math.max(0, Math.min(maxScroll(), scroll + (direction == UpOrDown.UP ? -1 : 1) * 24));
            return true;
        }

        // endregion
    }

    // endregion

    // region Menus

    /** A thing's menu: what a click does, where it is, rename, take it off. */
    private void thingMenu(final String id) {
        final TaskThings.Shown s = page.shown.get(id);
        if (s == null) return;
        Hyb.click();
        final List<PickList.Entry> rows = new ArrayList<>();
        switch (s.kind()) {
            case Tasks.MACHINE -> {
                if (!s.gone()) rows.add(PickList.Entry.of("Show on its plan", () -> act(s)));
                if (s.node() != null && !s.node().worldLinks.isEmpty())
                    rows.add(PickList.Entry.of("Show in the world", () -> showPlaced(s)));
            }
            case Tasks.LOCATION -> {
                if (TaskThings.inThisWorld(
                    s.item()
                        .place()))
                    rows.add(PickList.Entry.of("Show where it is", () -> act(s)));
                rows.add(PickList.Entry.of("Name it", () -> rename(s.item())));
            }
            case Tasks.PICTURE -> rows.add(PickList.Entry.of("See it big", () -> act(s)));
            case Tasks.QUEST -> rows.add(PickList.Entry.of("Open in the quest book", () -> act(s)));
            default -> rows.add(PickList.Entry.of("Change the words", () -> act(s)));
        }
        rows.add(
            PickList.Entry.of(
                s.item()
                    .done() ? "Mark not done" : "Mark done",
                () -> toggle(s)));
        rows.add(PickList.Entry.rule());
        rows.add(new PickList.Entry(null, "Take it off the task", "", Hyb.RED_INK, false, () -> takeOff(s)));
        Popup.open(
            session.panel(),
            PickList.popup("gtnhplanner_task_thing", null, rows, false, 140),
            getContext().getAbsMouseX(),
            getContext().getAbsMouseY());
    }

    /** The four columns, each with its dot; the task's own shaded. */
    private void pickStatus(final Tasks.Task t, final Hit under) {
        Hyb.click();
        final List<PickList.Entry> rows = new ArrayList<>();
        for (final Tasks.Status s : Tasks.Status.values()) rows.add(
            new PickList.Entry(
                null,
                TaskLook.name(s),
                "",
                Hyb.INK,
                s == t.status(),
                () -> moveTo(s),
                TaskLook.colour(s)));
        Popup.open(
            session.panel(),
            PickList.popup("gtnhplanner_task_status", null, rows, false, under.x1() - under.x0()),
            getArea().x + under.x0(),
            getArea().y + BAR + under.y1() - page.scroll + 2);
    }

    /** High, Medium and Low, each with its icon's colour; the task's own shaded. */
    private void pickPriority(final Tasks.Task t, final Hit under) {
        Hyb.click();
        final List<PickList.Entry> rows = new ArrayList<>();
        for (final String p : new String[] { Tasks.HIGH, Tasks.MEDIUM, Tasks.LOW }) rows.add(
            new PickList.Entry(
                null,
                TaskLook.priorityName(p),
                "",
                Hyb.INK,
                p.equals(t.shownPriority()),
                () -> put(x -> TaskEdits.withPriority(x, p)),
                TaskLook.priorityColour(p)));
        Popup.open(
            session.panel(),
            PickList.popup("gtnhplanner_task_priority", null, rows, false, under.x1() - under.x0()),
            getArea().x + under.x0(),
            getArea().y + BAR + under.y1() - page.scroll + 2);
    }

    private void moveTo(final Tasks.Status s) {
        final Tasks.Task t = task();
        if (t == null || s == t.status()) return;
        edit(
            () -> TaskEdits.move(
                session.graph(),
                taskId,
                s,
                TaskEdits.column(session.graph(), s)
                    .size(),
                TaskLook.now()));
        Sfx.TICK.play(1f + 0.1f * s.ordinal());
    }

    /** The Assign key: on a shared plan, a menu of its people; on a plan of this computer, yourself on or off. */
    private void assign(final Tasks.Task t, final Hit key) {
        final String me = Account.userId();
        if (me == null) return;
        if (key.data() != null) {
            Hyb.click();
            pickPeople(t, key);
            return;
        }
        final List<String> next = new ArrayList<>(t.assignees());
        final boolean on = !next.remove(me);
        if (on) next.add(me);
        put(x -> TaskEdits.withAssignees(x, next));
        (on ? Sfx.TOGGLE_ON : Sfx.TOGGLE_OFF).play();
    }

    /** A shared plan's owner and members, under the Assign key: a pick puts them on the task or takes them off. */
    private void pickPeople(final Tasks.Task t, final Hit key) {
        final String me = Account.userId();
        final List<PickList.Entry> rows = new ArrayList<>();
        for (final TaskLook.Person p : TaskLook.people(graph())) {
            final boolean on = t.assignees()
                .contains(p.id());
            rows.add(
                new PickList.Entry(
                    null,
                    p.id()
                        .equals(me) ? p.name() + " (you)" : p.name(),
                    on ? "On it" : "",
                    Hyb.INK,
                    on,
                    () -> {
                        final Tasks.Task now = task();
                        if (now == null) return;
                        final List<String> next = new ArrayList<>(now.assignees());
                        final boolean add = !next.remove(p.id());
                        if (add) next.add(p.id());
                        put(x -> TaskEdits.withAssignees(x, next));
                        (add ? Sfx.TOGGLE_ON : Sfx.TOGGLE_OFF).play();
                    },
                    TaskLook.faceColour(p.id())));
        }
        Popup.open(
            session.panel(),
            PickList.popup("gtnhplanner_task_people", "Who is on it", rows, rows.size() > 8, 150),
            getArea().x + key.x0(),
            getArea().y + BAR + key.y1() - page.scroll + 2);
    }

    /** Opens another task of the board in this box's place. */
    private void openOther(final String id) {
        if (TaskEdits.task(graph(), id) == null) return;
        Hyb.click();
        if (getPanel() != null) getPanel().closeIfOpen();
        open(session, id);
    }

    /**
     * The board's other tasks, by column with their dots, under the Add key: a pick makes this task wait on it. One
     * that waits on this task (directly or down the chain) is refused, and the box says why.
     */
    private void pickBlocker(final Hit under) {
        final Tasks.Task t = task();
        if (t == null) return;
        Hyb.click();
        final List<PickList.Entry> rows = new ArrayList<>();
        for (final Tasks.Status s : Tasks.Status.values()) for (final Tasks.Task o : TaskEdits.column(graph(), s)) {
            if (o.id()
                .equals(taskId)
                || t.blockedBy()
                    .contains(o.id()))
                continue;
            final boolean loops = TaskEdits.wouldLoop(graph(), taskId, o.id());
            rows.add(
                new PickList.Entry(
                    null,
                    TaskLook.fitWords(o.title(), 180),
                    loops ? "waits on this" : "",
                    loops ? FAINT : Hyb.INK,
                    false,
                    () -> addBlocker(o.id()),
                    TaskLook.colour(o.status())));
        }
        if (rows.isEmpty()) {
            refuse("There is no other task to wait on.");
            return;
        }
        Popup.open(
            session.panel(),
            PickList.popup("gtnhplanner_task_blocker", "Waiting on", rows, rows.size() > 8, 160),
            getArea().x + under.x0(),
            getArea().y + BAR + under.y1() - page.scroll + 2);
    }

    private void addBlocker(final String blocker) {
        if (TaskEdits.wouldLoop(graph(), taskId, blocker)) {
            refuse("That task already waits on this one.");
            return;
        }
        final boolean[] added = new boolean[1];
        edit(() -> added[0] = TaskEdits.addBlocker(session.graph(), taskId, blocker));
        if (added[0]) {
            refused = null;
            Sfx.TOGGLE_ON.play();
        }
    }

    private void refuse(final String why) {
        refused = why;
        refusedUntil = System.currentTimeMillis() + 5000;
        Sfx.DENY.play();
    }

    /** Another task of the board, in a tip: its title, its column, how it is tied to this one. */
    @Nullable
    private Tip otherTip(final String id, final String how) {
        final Tasks.Task o = TaskEdits.task(graph(), id);
        return o == null ? null
            : Tip.of(o.title())
                .sub(TaskLook.inColumn(o))
                .muted(how)
                .action(Tip.Input.LEFT, "Open it");
    }

    /** Asks before deleting the task: its things and comments go with it. */
    private void confirmDelete(final int x, final int y) {
        Hyb.click();
        final List<PickList.Entry> rows = List.of(new PickList.Entry(null, "Delete it", "", Hyb.RED_INK, false, () -> {
            edit(() -> TaskEdits.remove(session.graph(), taskId));
            Sfx.NOTE_CRUMPLE.play();
        }), PickList.Entry.of("Keep it", () -> {}));
        Popup.open(
            session.panel(),
            PickList.popup("gtnhplanner_task_delete", "Delete this task for good?", rows, false, 150),
            getArea().x + x,
            getArea().y + y - 64);
    }

    // endregion

    // region Tips and input in the bar and the foot

    /** Tips wait until the mouse has rested on their thing a moment. */
    private final Tip.Wait tipWait = new Tip.Wait();

    @Nullable
    private Tip tip(@Nullable final Hit hit) {
        if (hit == null) return null;
        final TaskThings.Shown s = hit.data() instanceof final String id ? page.shown.get(id) : null;
        return switch (hit.kind()) {
            case CLOSE -> Tip.of("Close (Esc)");
            case STATUS -> Tip.of("Move it to another column");
            case PRIORITY -> Tip.of("How pressing it is");
            case ASSIGN -> Tip.of(hit.data() != null ? "Choose who is on it" : "Put yourself on it or take yourself off");
            case SIGN_IN -> Tip.of("Sign in to your account");
            case BOX -> s == null ? null
                : Tip.of(
                    s.item()
                        .done() ? "Not done after all" : "Tick it off");
            case THING -> s == null ? null : thingTip(s);
            case SHOW -> s == null ? null
                : Tip.of(Tasks.LOCATION.equals(s.kind()) ? "Show where it is" : "Show it in the world")
                    .sub("The planner closes and a beam marks the spot");
            case THING_DELETE -> Tip.of("Take it off the task");
            case ADD_HERE -> Tip.of("Add the spot you stand on");
            case ADD_SHOT -> Tip.of("Add a screenshot of the world")
                .sub("The planner steps aside for the shot and comes back");
            case ADD_QUEST -> Tip.of("Add a quest from your quest book")
                .sub("Click a quest in the book to add it");
            case COMMENT -> null;
            case COMMENT_DELETE -> Tip.of("Delete your comment");
            case POST -> Tip.of("Add your comment");
            case DELETE -> Tip.of("Delete this task with its things and comments");
            case BLOCKER -> otherTip((String) hit.data(), "This task waits on it");
            case BLOCKS -> otherTip((String) hit.data(), "It waits on this task");
            case UNBLOCK -> Tip.of("It waits on this no more");
            case ADD_BLOCKER -> Tip.of("Add a task it waits on");
        };
    }

    private Tip thingTip(final TaskThings.Shown s) {
        final Tip tip = Tip.of(s.words());
        if (!s.detail()
            .isEmpty())
            tip.sub(
                s.detail()
                    .replace(TaskThings.SEP, ", "));
        switch (s.kind()) {
            case Tasks.MACHINE -> {
                if (!s.gone()) tip.action(Tip.Input.LEFT, "Show it on its plan");
            }
            case Tasks.LOCATION -> tip.action(Tip.Input.LEFT, "Show where it is");
            case Tasks.PICTURE -> tip.action(Tip.Input.LEFT, "See it big");
            case Tasks.QUEST -> tip.action(Tip.Input.LEFT, "Open it in the quest book");
            default -> tip.action(Tip.Input.LEFT, "Change the words");
        }
        if (!s.loose()) tip.action(Tip.Input.DRAG, "Move it");
        return tip.action(Tip.Input.RIGHT, "More");
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        // A press anywhere in the box but its fields ends the writing (what was written goes in), so the keys are the
        // board's again: undo, redo.
        if (BoardScreen.textFocused(getScreen())) getContext().removeFocus();
        final Hit hit = hitAt(lastHits, mouseX(), mouseY());
        if (hit == null || mouseButton != 0) return Result.ACCEPT;
        switch (hit.kind()) {
            case CLOSE -> close();
            case POST -> post();
            case DELETE -> confirmDelete(hit.x0(), hit.y0());
            default -> {}
        }
        return Result.SUCCESS;
    }

    private static boolean is(@Nullable final Hit hover, final Kind kind) {
        return hover != null && hover.kind() == kind;
    }

    private static boolean is(@Nullable final Hit hover, final Kind kind, final Object data) {
        return is(hover, kind) && data.equals(hover.data());
    }

    @Nullable
    private static Hit hitAt(final List<Hit> from, final int x, final int y) {
        for (int i = from.size() - 1; i >= 0; i--) if (from.get(i)
            .contains(x, y)) return from.get(i);
        return null;
    }

    /**
     * A line of parts joined by {@link TaskThings#SEP}, wrapped to a width a whole part at a time (a part wider than
     * the width wraps at its words), so "11 m north" is never split across lines.
     */
    static List<String> parts(final String text, final int width) {
        final List<String> out = new ArrayList<>();
        if (text.isEmpty()) return out;
        String line = "";
        for (final String part : text.split(java.util.regex.Pattern.quote(TaskThings.SEP))) {
            final String next = line.isEmpty() ? part : line + TaskThings.SEP + part;
            if (partsWidth(next) <= width) {
                line = next;
                continue;
            }
            if (!line.isEmpty()) out.add(line);
            line = "";
            if (Hyb.width(part) <= width) {
                line = part;
                continue;
            }
            final NoteText words = NoteText.of(part, width, Hyb::width);
            for (int i = 0; i < words.lines()
                .size() - 1; i++) out.add(words.shown(i));
            line = words.shown(
                words.lines()
                    .size() - 1);
        }
        if (!line.isEmpty()) out.add(line);
        return out;
    }

    /** The room between two parts of a line, where a small dot sits. */
    private static final int SEP_W = 11;

    private static List<String> split(final String line) {
        return List.of(line.split(java.util.regex.Pattern.quote(TaskThings.SEP)));
    }

    /** How wide a line of parts draws: its parts, and a dot's room between each two. */
    static int partsWidth(final String line) {
        final List<String> parts = split(line);
        int w = (parts.size() - 1) * SEP_W;
        for (final String p : parts) w += Hyb.width(p);
        return w;
    }

    /** A line of parts, a small dot between each two (the font's middle dot reads as a dash). */
    static void drawParts(final String line, final float x, final float y, final int colour) {
        float at = x;
        boolean first = true;
        for (final String p : split(line)) {
            if (!first) {
                Hyb.rect(at + 4, y + 3, 2, 2, Hyb.mix(colour, 0xFF000000, 0.8f));
                at += SEP_W;
            }
            first = false;
            Hyb.text(p, at, y, colour);
            at += Hyb.width(p);
        }
    }

    static void cross(final float cx, final float cy, final int colour) {
        for (int d = -3; d <= 3; d++) {
            Hyb.rect(cx + d - 0.5f, cy + d - 0.5f, 1, 1, colour);
            Hyb.rect(cx + d - 0.5f, cy - d - 0.5f, 1, 1, colour);
        }
    }

    // endregion

    // region For the dev harness

    /** The box open now as data: its task, and the GUI rectangle of each part in the game's pixels; null if none. */
    @Nullable
    public static Map<String, Object> describe() {
        final TaskPanel p = showing.get();
        if (p == null || p.closed) return null;
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("task", p.taskId);
        out.put("size", List.of(p.boxW, p.boxH));
        out.put("wide", p.wide);
        out.put("scroll", p.page.scroll);
        out.put("pageHeight", p.page.pageH);
        final Map<String, Object> parts = new LinkedHashMap<>();
        for (final Hit h : p.lastHits)
            parts.putIfAbsent(key(h), p.rect(h.x0(), h.y0(), h.x1() - h.x0(), h.y1() - h.y0()));
        for (final Hit h : p.page.lastPageHits) {
            final int y0 = Math.max(0, h.y0() - p.page.scroll), y1 = Math.min(p.page.windowH(), h.y1() - p.page.scroll);
            if (y1 <= y0) continue;
            parts.putIfAbsent(key(h), p.rect(h.x0(), BAR + y0, h.x1() - h.x0(), y1 - y0));
        }
        parts.put(
            "title",
            p.rect(
                p.title.getArea().rx,
                BAR + p.title.getArea().ry,
                p.title.getArea().width,
                p.title.getArea().height));
        parts.put(
            "notes",
            p.rect(
                p.notes.getArea().rx,
                BAR + p.notes.getArea().ry,
                p.notes.getArea().width,
                p.notes.getArea().height));
        parts.put(
            "comment",
            p.rect(
                p.comment.getArea().rx,
                p.comment.getArea().ry,
                p.comment.getArea().width,
                p.comment.getArea().height));
        out.put("parts", parts);
        return out;
    }

    /** Scrolls the open box's page to {@code y} (clamped), for the harness. */
    public static void scrollTo(final int y) {
        final TaskPanel p = showing.get();
        if (p != null) p.page.scroll = y;
    }

    private static String key(final Hit h) {
        return h.kind()
            .name()
            .toLowerCase(java.util.Locale.ROOT) + (h.data() == null ? "" : ":" + h.data());
    }

    private Map<String, Object> rect(final int lx, final int ly, final int lw, final int lh) {
        final int x = PlannerScale.toGame(getArea().x + lx), y = PlannerScale.toGame(getArea().y + ly);
        final int w = PlannerScale.toGame(getArea().x + lx + lw) - x,
            hh = PlannerScale.toGame(getArea().y + ly + lh) - y;
        return Map.of("x", x, "y", y, "w", w, "h", hh, "cx", x + w / 2, "cy", y + hh / 2);
    }

    // endregion
}
