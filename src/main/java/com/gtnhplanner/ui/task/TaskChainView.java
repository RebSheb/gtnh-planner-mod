package com.gtnhplanner.ui.task;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.drawable.Stencil;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.TaskChain;
import com.gtnhplanner.data.flowchart.TaskEdits;
import com.gtnhplanner.data.flowchart.Tasks;
import com.gtnhplanner.ui.card.CardPaint;
import com.gtnhplanner.ui.note.NoteText;
import com.gtnhplanner.ui.popup.Tip;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * A task board's chain view, the quest book's way (BetterQuesting, as GTNH ships it): a list of the tasks on the left,
 * chain by chain, and on the right a map that pans and zooms, each task a node (its status colour, title, priority and
 * faces) with arrows from each task to the tasks it unblocks, laid out in layers left to right ({@link TaskChain}).
 * Done tasks are dimmed; the tasks with no link sit in a row of
 * their own at the end. A click on a node opens its task; a row in the list selects its task and brings the map to
 * it. Narrow (GTNH's GUI scale 4 leaves a planner 330 to 440 wide) the list folds to a strip and opens on a click.
 *
 * <p>
 * Drawn and fed by {@link TaskBoardView} in the columns' place, under its header.
 */
final class TaskChainView {

    /** A node's size and the room between them, in map pixels. */
    private static final int NODE_W = 112, NODE_H = 40, COL_GAP = 30, ROW_GAP = 10, CHAIN_GAP = 24, MARGIN = 16,
        NODE_PAD = 6, LINE = 10;
    /** The list: its width open and folded, a row's height and a chain heading's. */
    private static final int LIST_W = 132, LIST_SHUT = 14, ROW_H = 13, HEAD_H = 16;
    /** The map's zoom steps. */
    private static final float[] ZOOMS = { 0.5f, 0.67f, 0.75f, 1f, 1.25f, 1.5f, 2f };
    private static final int DRAG_SLOP = 6;
    private static final int LIST_BG = 0xFF18191C, LIST_EDGE = 0xFF26282D, ROW_HOT = 0xFF24262B, ROW_ON = 0xFF2C3036,
        ARROW = 0xFF5A5D66, ARROW_DONE = 0xFF3D7A57, CARD_HOT = 0xFF2A2C32;

    private final TaskBoardView view;

    /** The selected task: lit in the list and ringed on the map. */
    @Nullable
    private String selected;
    /** The map's camera: the map point at its top left, and its zoom step; where a glide is taking it. */
    private float panX, panY, toX, toY;
    private int zoom = 3;
    private boolean gliding;
    private long glideNanos = System.nanoTime();
    /** The list open or folded: open by default where there is room for it. */
    @Nullable
    private Boolean listOpen;
    private int listScroll, listH;
    /** Whether the camera has been set for this board: it starts where everything shows. */
    private boolean cameraSet;
    private int cameraW, cameraH;

    /** A press: what it went down on, where, and whether it has become a drag of the map. */
    @Nullable
    private Hit pressedOn;
    private int pressX, pressY;
    private float pressPanX, pressPanY;
    private boolean panning;

    private enum Kind {
        NODE,
        ROW,
        FOLD,
        FIT,
        MAP,
        LIST
    }

    private record Hit(Kind kind, float x0, float y0, float x1, float y1, @Nullable String id) {

        boolean contains(final int x, final int y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }
    }

    private final List<Hit> hits = new ArrayList<>(), lastHits = new ArrayList<>();

    /** The board as chains, and every task's node on the map, worked out once per change of the plan. */
    private TaskChain.Layout layout = new TaskChain.Layout(List.of(), List.of());
    private final Map<String, float[]> nodes = new LinkedHashMap<>();
    /** Each task's layer in its chain, and the map's size. */
    private final Map<String, Integer> layers = new HashMap<>();
    private float mapW, mapH, looseY = -1;
    private Graph laidGraph;
    private long laidVersion = Long.MIN_VALUE;
    /** The view's top and size, as last drawn. */
    private int top, width, height;

    TaskChainView(final TaskBoardView view) {
        this.view = view;
    }

    private Graph graph() {
        return view.session()
            .graph();
    }

    private float scale() {
        return ZOOMS[zoom];
    }

    // region Layout

    private void lay() {
        final Graph g = graph();
        if (g == laidGraph && g.version() == laidVersion) return;
        if (g != laidGraph) {
            // Another board: its own camera and selection.
            panX = panY = toX = toY = 0;
            zoom = 3;
            selected = null;
            listScroll = 0;
            cameraSet = false;
        }
        laidGraph = g;
        laidVersion = g.version();
        layout = TaskChain.of(g);
        nodes.clear();
        layers.clear();
        float y = MARGIN, right = 0;
        for (final TaskChain.Chain c : layout.chains()) {
            final int rows = c.rows();
            for (int l = 0; l < c.layers()
                .size(); l++) {
                final List<String> layer = c.layers()
                    .get(l);
                final float x = MARGIN + l * (NODE_W + COL_GAP);
                // A short layer sits in the middle of the chain's band, as its neighbours' places were reckoned.
                float ny = y + (rows - layer.size()) * (NODE_H + ROW_GAP) / 2f;
                for (final String id : layer) {
                    nodes.put(id, new float[] { x, Math.round(ny) });
                    layers.put(id, l);
                    ny += NODE_H + ROW_GAP;
                }
                right = Math.max(right, x + NODE_W);
            }
            y += rows * (NODE_H + ROW_GAP) - ROW_GAP + CHAIN_GAP;
        }
        looseY = -1;
        if (!layout.loose()
            .isEmpty()) {
            // Their own row at the end, under a quiet heading.
            looseY = y;
            y += HEAD_H;
            float x = MARGIN;
            for (final String id : layout.loose()) {
                nodes.put(id, new float[] { x, y });
                x += NODE_W + ROW_GAP;
            }
            right = Math.max(right, x - ROW_GAP);
            y += NODE_H + CHAIN_GAP;
        }
        mapW = right + MARGIN;
        mapH = y - CHAIN_GAP + MARGIN;
        if (selected != null && !nodes.containsKey(selected)) selected = null;
    }

    private boolean listShown() {
        return listOpen != null ? listOpen : width >= 560;
    }

    private int listW() {
        return listShown() ? Math.min(LIST_W, width / 2) : LIST_SHUT;
    }

    /** The map's left edge in the view. */
    private int mapLeft() {
        return listW();
    }

    // endregion

    // region Drawing

    void draw(final ModularGuiContext context, final int top, final int w, final int h, final boolean mouseFree) {
        this.top = top;
        width = w;
        height = h;
        lay();
        hits.clear();
        // The view resized (the GUI scale, the planner's size): the camera starts again.
        if (w != cameraW || h != cameraH) cameraSet = false;
        if (!cameraSet && w > 0) {
            cameraW = w;
            cameraH = h;
            // First shown: from its top left, zoomed out (to half at most) so its chains show end to end.
            cameraSet = true;
            final float mw = w - mapLeft();
            zoom = 3;
            while (zoom > 0 && mapW * scale() > mw) zoom--;
            panX = toX = 0;
            panY = toY = 0;
            gliding = false;
        }
        final Hit hover = mouseFree && pressedOn == null ? hitAt() : null;
        glide();

        // The map first, clipped to its part of the view.
        final int ml = mapLeft(), mw = w - ml;
        Stencil.apply(ml, top, mw, h, context);
        hits.add(new Hit(Kind.MAP, ml, top, w, top + h, null));
        drawMap(context, ml, mw, h, hover);
        Stencil.remove();
        drawMapKeys(ml, mw, hover);
        drawList(context, hover);
        lastHits.clear();
        lastHits.addAll(hits);
    }

    private void glide() {
        final long now = System.nanoTime();
        final float dt = Math.min(0.05f, (now - glideNanos) / 1e9f);
        glideNanos = now;
        if (!gliding) return;
        final float k = 1 - (float) Math.exp(-dt / 0.06f);
        panX += (toX - panX) * k;
        panY += (toY - panY) * k;
        if (Math.abs(toX - panX) < 0.3f && Math.abs(toY - panY) < 0.3f) {
            panX = toX;
            panY = toY;
            gliding = false;
        }
    }

    /** A map point's place in the view: the camera, snapped to whole pixels so text stays sharp. */
    private float sx(final float mx) {
        return mapLeft() + Math.round((mx - panX) * scale());
    }

    private float sy(final float my) {
        return top + Math.round((my - panY) * scale());
    }

    private void drawMap(final ModularGuiContext context, final int ml, final int mw, final int h,
        @Nullable final Hit hover) {
        final Graph g = graph();
        if (g.tasks.isEmpty()) {
            centred("No tasks yet.", ml + mw / 2, top + h / 2 - 10, mw - 16, Hyb.MUTED);
            centred("Press New task to add one.", ml + mw / 2, top + h / 2 + 2, mw - 16, TaskPanel.FAINT);
            return;
        }
        final String lit = hover != null && (hover.kind() == Kind.NODE || hover.kind() == Kind.ROW) ? hover.id()
            : selected;
        final float s = scale();
        GL11.glPushMatrix();
        GL11.glTranslatef(sx(0), sy(0), 0);
        GL11.glScalef(s, s, 1);
        // Arrows under the nodes: from each task to the tasks it unblocks.
        final float t = Math.max(1f, 1f / s);
        Hyb.beginBatch();
        for (final Tasks.Task task : g.tasks) {
            final float[] to = nodes.get(task.id());
            if (to == null) continue;
            for (final String b : task.blockedBy()) {
                final float[] from = nodes.get(b);
                final Tasks.Task blocker = TaskEdits.task(g, b);
                if (from == null || blocker == null) continue;
                final boolean on = task.id()
                    .equals(lit) || b.equals(lit);
                if (on) continue;
                arrow(from, to, blocker.status() == Tasks.Status.DONE ? ARROW_DONE : ARROW, t);
            }
        }
        // The lit task's arrows over the others.
        for (final Tasks.Task task : g.tasks) {
            final float[] to = nodes.get(task.id());
            if (to == null) continue;
            for (final String b : task.blockedBy()) {
                final float[] from = nodes.get(b);
                if (from == null || TaskEdits.task(g, b) == null
                    || !(task.id()
                        .equals(lit) || b.equals(lit)))
                    continue;
                arrow(from, to, TaskPanel.CYAN, t);
            }
        }
        Hyb.endBatch();
        if (looseY >= 0) Hyb.text(
            layout.chains()
                .isEmpty() ? "No task waits on another yet" : "Not linked",
            MARGIN,
            looseY + 3,
            TaskPanel.FAINT);
        // The nodes in view.
        final float vx0 = panX, vy0 = panY, vx1 = panX + mw / s, vy1 = panY + h / s;
        for (final Map.Entry<String, float[]> e : nodes.entrySet()) {
            final float[] p = e.getValue();
            if (p[0] + NODE_W < vx0 || p[0] > vx1 || p[1] + NODE_H < vy0 || p[1] > vy1) continue;
            final Tasks.Task task = TaskEdits.task(g, e.getKey());
            if (task == null) continue;
            final boolean hot = hover != null && hover.kind() == Kind.NODE
                && e.getKey()
                    .equals(hover.id());
            drawNode(
                task,
                p[0],
                p[1],
                hot,
                e.getKey()
                    .equals(selected));
            final float x0 = Math.max(ml, sx(p[0])), y0 = Math.max(top, sy(p[1]));
            final float x1 = Math.min(ml + mw, sx(p[0] + NODE_W)), y1 = Math.min(top + h, sy(p[1] + NODE_H));
            if (x1 > x0 && y1 > y0) hits.add(new Hit(Kind.NODE, x0, y0, x1, y1, e.getKey()));
        }
        GL11.glPopMatrix();
        if (layout.chains()
            .isEmpty()) {
            // Nothing linked yet: how to link tasks, along the map's foot.
            centred(
                "Open a task and add what it waits on to link it.",
                ml + mw / 2,
                top + h - 22,
                mw - 16,
                TaskPanel.FAINT);
        }
    }

    /**
     * An arrow from a blocker's right side to the task it unblocks: out level, down or up in the gap before the task,
     * then in level to a head on its left side.
     */
    private static void arrow(final float[] from, final float[] to, final int colour, final float t) {
        final float x0 = from[0] + NODE_W, y0 = from[1] + NODE_H / 2f;
        final float x1 = to[0], y1 = to[1] + NODE_H / 2f;
        final float mid = x1 > x0 ? x1 - COL_GAP / 2f : x0 + COL_GAP / 2f;
        final float h = t / 2;
        Hyb.rect(Math.min(x0, mid), y0 - h, Math.abs(mid - x0) + h, t, colour);
        Hyb.rect(mid - h, Math.min(y0, y1) - h, t, Math.abs(y1 - y0) + t, colour);
        Hyb.rect(Math.min(mid, x1 - 4), y1 - h, Math.abs(x1 - 4 - mid) + h, t, colour);
        Hyb.triangle(x1 - 5, y1 - 3.5f, x1 - 5, y1 + 3.5f, x1, y1, colour);
    }

    /**
     * A task's node, kept plain (the arrows say what waits on what): its column's colour down the left, its title, its
     * priority and faces.
     */
    private void drawNode(final Tasks.Task t, final float x, final float y, final boolean hot, final boolean on) {
        final Graph g = graph();
        final boolean done = t.status() == Tasks.Status.DONE;
        Hyb.rect(x, y, NODE_W, NODE_H, on ? TaskPanel.CYAN : CardPaint.EDGE);
        Hyb.rect(x + 1, y + 1, NODE_W - 2, NODE_H - 2, hot ? CARD_HOT : CardPaint.SURFACE);
        // Its column's colour down the left, as on the board.
        Hyb.rect(x + 1, y + 1, 2, NODE_H - 2, TaskLook.colour(t.status()));
        final int tw = NODE_W - 2 * NODE_PAD - 2 - TaskLook.PRIORITY_W - 3;
        final List<String> title = titleLines(t.title(), tw);
        final float tx = x + NODE_PAD + 2;
        for (int i = 0; i < title.size(); i++)
            Hyb.text(title.get(i), tx, y + NODE_PAD - 1 + i * LINE, done ? Hyb.MUTED : Hyb.INK);
        TaskLook.priority(t.shownPriority(), x + NODE_W - NODE_PAD - TaskLook.PRIORITY_W + 1, y + NODE_PAD);
        TaskLook.faces(g, t.assignees(), tx, y + NODE_H - NODE_PAD + 1 - TaskLook.FACE);
        // Done: faded back into the map.
        if (done) Hyb.rect(x, y, NODE_W, NODE_H, 0x8C141414);
    }

    /** A title in at most two lines, the second cut at a word with "..." when there is more. */
    static List<String> titleLines(final String title, final int width) {
        final NoteText lines = NoteText.of(title, width, Hyb::width);
        final List<String> out = new ArrayList<>();
        if (lines.lines()
            .isEmpty()) return out;
        out.add(lines.shown(0));
        if (lines.lines()
            .size() == 2) out.add(lines.shown(1));
        else if (lines.lines()
            .size() > 2)
            out.add(
                TaskLook.fitWords(
                    title.substring(
                        lines.lines()
                            .get(1)
                            .start())
                        .strip(),
                    width));
        return out;
    }

    /** The map's own key at its foot on the right: Fit. */
    private void drawMapKeys(final int ml, final int mw, @Nullable final Hit hover) {
        if (nodes.isEmpty()) return;
        final String label = "Fit";
        final int kw = Hyb.width(label) + 12, x = ml + mw - kw - 6, y = top + height - 16 - 6;
        final boolean hot = hover != null && hover.kind() == Kind.FIT;
        Hyb.bevel(x, y, kw, 16, hot ? Hyb.KEY_HOVER : Hyb.KEY, Hyb.KEY_HI, Hyb.KEY_LO, 0, 1);
        Hyb.text(label, x + 6, y + 4, Hyb.INK);
        hits.add(new Hit(Kind.FIT, x, y, x + kw, y + 16, null));
    }

    /** The list: chain by chain, each task a row with its status dot and title; folded, a strip with a key. */
    private void drawList(final ModularGuiContext context, @Nullable final Hit hover) {
        final int lw = listW(), h = height;
        Hyb.rect(0, top, lw, h, LIST_BG);
        Hyb.rect(lw - 1, top, 1, h, LIST_EDGE);
        final boolean foldHot = hover != null && hover.kind() == Kind.FOLD;
        if (!listShown()) {
            // Folded: the whole strip opens it, a caret pointing out.
            if (foldHot) Hyb.rect(0, top, lw - 1, h, ROW_HOT);
            caret(lw / 2f - 1, top + 10, true, foldHot ? Hyb.INK : Hyb.MUTED);
            hits.add(new Hit(Kind.FOLD, 0, top, lw, top + h, null));
            return;
        }
        hits.add(new Hit(Kind.LIST, 0, top, lw, top + h, null));
        // Its head: how many, and the key that folds it.
        final int n = graph().tasks.size();
        Hyb.text(n + (n == 1 ? " task" : " tasks"), 6, top + 5, Hyb.MUTED);
        final int fx = lw - 16, fy = top + 2;
        if (foldHot) Hyb.rect(fx, fy, 14, 14, Hyb.KEY_HOVER);
        caret(fx + 6, fy + 4, false, foldHot ? Hyb.INK : Hyb.MUTED);
        hits.add(new Hit(Kind.FOLD, fx, fy, fx + 14, fy + 14, null));
        Hyb.rect(0, top + 18, lw - 1, 1, LIST_EDGE);

        final int bodyTop = top + 19, bodyH = h - 19;
        listScroll = Math.max(0, Math.min(listScroll, Math.max(0, listH - bodyH)));
        Stencil.apply(0, bodyTop, lw - 1, bodyH, context);
        int y = bodyTop + 3 - listScroll;
        final Graph g = graph();
        int chainNo = 0;
        for (final TaskChain.Chain c : layout.chains()) {
            chainNo++;
            final List<String> ids = c.ids();
            y = heading("Chain " + chainNo, ids.size(), y);
            for (final String id : ids) y = row(g, id, layers.getOrDefault(id, 0), y, bodyTop, bodyH, hover);
            y += 4;
        }
        if (!layout.loose()
            .isEmpty()) {
            y = heading(
                "Not linked",
                layout.loose()
                    .size(),
                y);
            for (final String id : layout.loose()) y = row(g, id, 0, y, bodyTop, bodyH, hover);
        }
        listH = y + listScroll - bodyTop + 3;
        Stencil.remove();
        if (listH > bodyH) {
            final int bar = Math.max(12, bodyH * bodyH / listH);
            final int by = bodyTop + (bodyH - bar) * listScroll / Math.max(1, listH - bodyH);
            Hyb.rect(lw - 4, by + 1, 2, bar - 2, 0xFF4A4C54);
        }
    }

    /** A small caret: pointing right (open the list) or left (fold it). */
    private static void caret(final float x, final float y, final boolean right, final int colour) {
        for (int i = 0; i < 4; i++) {
            final float len = 7 - 2 * i;
            if (right) Hyb.rect(x + i, y + i, 1, len, colour);
            else Hyb.rect(x + 3 - i, y + i, 1, len, colour);
        }
    }

    private int heading(final String name, final int count, final int y) {
        Hyb.text(name, 6, y + 4, TaskPanel.DIM);
        Hyb.text(Integer.toString(count), 6 + Hyb.width(name) + 5, y + 4, TaskPanel.FAINT);
        return y + HEAD_H;
    }

    /** A task's row: indented by its layer, its status dot and its title. */
    private int row(final Graph g, final String id, final int layer, final int y, final int bodyTop, final int bodyH,
        @Nullable final Hit hover) {
        final Tasks.Task t = TaskEdits.task(g, id);
        if (t == null) return y;
        final int lw = listW() - 1;
        if (y + ROW_H < bodyTop || y > bodyTop + bodyH) return y + ROW_H;
        final boolean hot = hover != null && hover.kind() == Kind.ROW && id.equals(hover.id());
        if (id.equals(selected)) {
            Hyb.rect(0, y, lw, ROW_H, ROW_ON);
            Hyb.rect(0, y, 2, ROW_H, TaskPanel.CYAN);
        } else if (hot) Hyb.rect(0, y, lw, ROW_H, ROW_HOT);
        final int indent = 6 + Math.min(layer, 4) * 5;
        TaskLook.dot(indent, y + 4, 5, TaskLook.colour(t.status()));
        final boolean done = t.status() == Tasks.Status.DONE;
        final int tx = indent + 9, tw = lw - tx - 5;
        Hyb.text(TaskLook.fitWords(t.title(), tw), tx, y + 3, done ? TaskPanel.FAINT : Hyb.INK);
        final int y0 = Math.max(bodyTop, y), y1 = Math.min(bodyTop + bodyH, y + ROW_H);
        if (y1 > y0) hits.add(new Hit(Kind.ROW, 0, y0, lw, y1, id));
        return y + ROW_H;
    }

    /** Words centred on {@code cx}, wrapped to {@code width}. */
    private static void centred(final String words, final int cx, final float y, final int width, final int colour) {
        final NoteText t = NoteText.of(words, width, Hyb::width);
        for (int i = 0; i < t.lines()
            .size(); i++)
            Hyb.textCentered(
                t.shown(i)
                    .strip(),
                cx,
                y + i * LINE,
                colour);
    }

    // endregion

    // region Tips

    /** What the mouse rests on, for the tip's wait: a node or row by its task, else the part. */
    @Nullable
    Object tipKey() {
        final Hit h = pressedOn == null ? hitAt() : null;
        if (h == null || h.kind() == Kind.MAP || h.kind() == Kind.LIST) return null;
        return h.kind() + ":" + h.id();
    }

    @Nullable
    Tip tip() {
        final Hit h = pressedOn == null ? hitAt() : null;
        if (h == null) return null;
        return switch (h.kind()) {
            case NODE, ROW -> {
                final Tasks.Task t = TaskEdits.task(graph(), h.id());
                if (t == null) yield null;
                final Tip tip = Tip.of(t.title())
                    .sub(TaskLook.inColumn(t));
                tip.action(Tip.Input.LEFT, h.kind() == Kind.NODE ? "Open it" : "Show it on the map");
                yield tip;
            }
            case FOLD -> Tip.of(listShown() ? "Fold the list away" : "Show the list of tasks");
            case FIT -> Tip.of("Show every task");
            default -> null;
        };
    }

    // endregion

    // region Input

    @Nullable
    private Hit hitAt() {
        final int x = view.localX(), y = view.localY();
        for (int i = lastHits.size() - 1; i >= 0; i--) if (lastHits.get(i)
            .contains(x, y)) return lastHits.get(i);
        return null;
    }

    /** A press in the view under the header; true when it was taken. */
    boolean press(final int button) {
        final Hit h = hitAt();
        if (h == null) return false;
        if (button != 0) return true;
        switch (h.kind()) {
            case FOLD -> {
                Hyb.click();
                listOpen = !listShown();
                return true;
            }
            case FIT -> {
                Hyb.click();
                fit();
                return true;
            }
            case ROW -> {
                Hyb.click();
                select(h.id(), true);
                return true;
            }
            case LIST -> {
                return true;
            }
            default -> {}
        }
        // A node or the map: let go here to open the node, or drag to pan.
        pressedOn = h;
        pressX = view.getContext()
            .getAbsMouseX();
        pressY = view.getContext()
            .getAbsMouseY();
        pressPanX = panX;
        pressPanY = panY;
        panning = false;
        return true;
    }

    void drag() {
        if (pressedOn == null) return;
        final int dx = view.getContext()
            .getAbsMouseX() - pressX,
            dy = view.getContext()
                .getAbsMouseY() - pressY;
        if (!panning && Math.max(Math.abs(dx), Math.abs(dy)) < DRAG_SLOP) return;
        panning = true;
        gliding = false;
        panX = toX = pressPanX - dx / scale();
        panY = toY = pressPanY - dy / scale();
        clampPan();
    }

    /** A release; true when it ended a press of the chain view's. */
    boolean release(final int button) {
        if (button != 0 || pressedOn == null) return false;
        final Hit h = pressedOn;
        pressedOn = null;
        if (panning) {
            panning = false;
            return true;
        }
        if (h.kind() == Kind.NODE && h.id() != null) {
            select(h.id(), false);
            TaskPanel.open(view.session(), h.id());
        } else selected = null;
        return true;
    }

    /** The wheel: over the list it scrolls it, over the map it zooms about the mouse. */
    boolean scroll(final UpOrDown direction) {
        final int mx = view.localX(), my = view.localY();
        if (mx < listW()) {
            if (listShown()) listScroll = Math.max(0, listScroll + (direction == UpOrDown.UP ? -1 : 1) * 24);
            return true;
        }
        final int next = Math.max(0, Math.min(ZOOMS.length - 1, zoom + (direction == UpOrDown.UP ? 1 : -1)));
        if (next == zoom) return true;
        // The map point under the mouse stays under it.
        final float px = panX + (mx - mapLeft()) / scale(), py = panY + (my - top) / scale();
        zoom = next;
        panX = toX = px - (mx - mapLeft()) / scale();
        panY = toY = py - (my - top) / scale();
        gliding = false;
        clampPan();
        return true;
    }

    /** Selects a task; {@code centre} glides the map to put it in the middle. */
    private void select(final String id, final boolean centre) {
        selected = id;
        final float[] p = nodes.get(id);
        if (!centre || p == null) return;
        final float s = scale(), mw = width - mapLeft();
        toX = p[0] + NODE_W / 2f - mw / 2f / s;
        toY = p[1] + NODE_H / 2f - height / 2f / s;
        gliding = true;
    }

    /** Zooms out (or in, to 1) until every task shows, and centres them. */
    private void fit() {
        final float mw = width - mapLeft();
        zoom = 0;
        for (int z = ZOOMS.length - 1; z >= 0; z--)
            if (ZOOMS[z] <= 1f && mapW * ZOOMS[z] <= mw && mapH * ZOOMS[z] <= height) {
                zoom = z;
                break;
            }
        final float s = scale();
        // Centred where it all shows; from its top left where even the smallest zoom leaves some out.
        toX = mapW * s <= mw ? mapW / 2f - mw / 2f / s : 0;
        toY = mapH * s <= height ? mapH / 2f - height / 2f / s : 0;
        gliding = true;
    }

    /** Keeps some of the map in view however far it is dragged. */
    private void clampPan() {
        final float s = scale(), mw = (width - mapLeft()) / s, mh = height / s;
        panX = toX = Math.max(-mw + 40 / s, Math.min(mapW - 40 / s, panX));
        panY = toY = Math.max(-mh + 40 / s, Math.min(mapH - 40 / s, panY));
    }

    // endregion

    // region For the dev harness

    /** The chain view as data: the list open, the zoom, each chain's layers, and each node's and row's rectangle. */
    Map<String, Object> describe() {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("list", listShown());
        out.put("zoom", scale());
        out.put("selected", selected);
        final List<Object> chains = new ArrayList<>();
        for (final TaskChain.Chain c : layout.chains()) chains.add(c.layers());
        out.put("chains", chains);
        out.put("loose", layout.loose());
        final Map<String, Object> parts = new LinkedHashMap<>();
        for (final Hit h : lastHits) {
            if (h.kind() == Kind.MAP || h.kind() == Kind.LIST) continue;
            final String key = h.kind()
                .name()
                .toLowerCase(java.util.Locale.ROOT) + (h.id() == null ? "" : ":" + h.id());
            parts.putIfAbsent(key, view.rect(h.x0(), h.y0(), h.x1(), h.y1()));
        }
        out.put("parts", parts);
        return out;
    }

    /** Selects a task and brings the map to it, as a click on its row does (for the harness). */
    void selectForHarness(final String id) {
        lay();
        select(id, true);
    }

    // endregion
}
