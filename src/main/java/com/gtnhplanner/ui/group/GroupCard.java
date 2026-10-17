package com.gtnhplanner.ui.group;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import org.jetbrains.annotations.NotNull;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.IDraggable;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widget.sizer.Area;
import com.gtnhplanner.data.flowchart.BoardGroup;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.canvas.BoardCanvas;
import com.gtnhplanner.ui.note.NoteColors;
import com.gtnhplanner.ui.popup.PickList;
import com.gtnhplanner.ui.popup.Popup;
import com.gtnhplanner.ui.popup.TextPopup;
import com.gtnhplanner.ui.popup.Tip;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * A group on the board ({@code docs/design/groups.md}): open, its frame's border, title strip (name, machines, the
 * collapse key) and resize edges, letting every press on its body through to the board; collapsed, its summary card.
 * The
 * strip (or the summary) carries the group and all it holds; a click on the name renames it, on the key collapses or
 * expands it, a double-click on the summary expands it; the right button opens its menu. The body is painted by the
 * board under the wires ({@link GroupPaint#body}).
 */
public final class GroupCard extends Widget<GroupCard> implements Interactable, IDraggable {

    public enum Part {
        NAME,
        STRIP,
        KEY,
        GRIP,
        SUMMARY,
        NONE
    }

    /** How deep the resize edges reach in from the border, in screen pixels (kept however far out the board is). */
    private static final int GRIP = 4;

    private final BoardSession session;
    public final UUID groupId;
    private boolean moving;

    public GroupCard(final BoardSession session, final UUID groupId) {
        this.session = session;
        this.groupId = groupId;
        sync();
    }

    @Nullable
    public BoardGroup group() {
        return session.graph().boardGroups.get(groupId);
    }

    @Nullable
    private BoardCanvas canvas() {
        return isValid() && getParent() instanceof final BoardCanvas c ? c : null;
    }

    // region Summary (cached per answer and plan version)

    private GroupSummary summary = GroupSummary.EMPTY;
    private Object summaryModels;
    private long summaryVersion = Long.MIN_VALUE;

    public GroupSummary summary() {
        final Object models = session.models();
        final long version = session.graph()
            .version();
        if (models != summaryModels || version != summaryVersion) {
            summaryModels = models;
            summaryVersion = version;
            summary = GroupSummary.of(session, groupId);
        }
        return summary;
    }

    // endregion

    // region Geometry

    private int shownX = Integer.MIN_VALUE, shownY, shownW, shownH;

    @Override
    public void onUpdate() {
        super.onUpdate();
        sync();
    }

    /** Puts the widget on the group's frame (or its summary card, collapsed), at the size being dragged to. */
    public void sync() {
        final BoardGroup g = group();
        if (g == null) return;
        final int x = resizing ? live[0] : g.getX(), y = resizing ? live[1] : g.getY();
        final int w = g.isCollapsed() ? GroupPaint.SUM_W : resizing ? live[2] : g.getWidth();
        final int h = g.isCollapsed() ? GroupPaint.summaryHeight(summary().rows()) : resizing ? live[3] : g.getHeight();
        if (x == shownX && y == shownY && w == shownW && h == shownH) return;
        shownX = x;
        shownY = y;
        shownW = w;
        shownH = h;
        pos(x, y);
        size(w, h);
        scheduleResize();
    }

    @Override
    public boolean canHover() {
        return true;
    }

    /** Open, it takes the pointer only at its strip and edges: the board under its body keeps every press. */
    @Override
    public boolean isInside(final com.cleanroommc.modularui.api.layout.IViewportStack stack, final int mx, final int my,
        final boolean transform) {
        if (!super.isInside(stack, mx, my, transform)) return false;
        final BoardGroup g = group();
        if (g == null || g.isCollapsed() || canvas() == null) return true;
        return partAt(localX(), localY()) != Part.NONE;
    }

    private float localX() {
        final BoardCanvas c = canvas();
        return c == null ? -1 : c.worldX(getContext().getAbsMouseX()) - getArea().rx;
    }

    private float localY() {
        final BoardCanvas c = canvas();
        return c == null ? -1 : c.worldY(getContext().getAbsMouseY()) - getArea().ry;
    }

    private float grip() {
        return GRIP / Math.max(
            0.01f,
            session.graph()
                .getZoom() * com.gtnhplanner.ui.PlannerScale.board());
    }

    public Part partAt(final float x, final float y) {
        final BoardGroup g = group();
        if (g == null) return Part.NONE;
        final int w = getArea().width, h = getArea().height;
        if (x < 0 || y < 0 || x > w || y > h) return Part.NONE;
        final int[] key = GroupPaint.keyRect(w);
        if (x >= key[0] && x < key[0] + key[2] && y >= key[1] && y < key[1] + key[3]) return Part.KEY;
        final boolean onName = y < GroupPaint.STRIP && x >= GroupPaint.TEXT - 2
            && x < GroupPaint.TEXT + Math.min(Hyb.width(g.getName()), key[0] - GroupPaint.TEXT - 4) + 2;
        if (g.isCollapsed()) return onName ? Part.NAME : Part.SUMMARY;
        final float e = grip();
        if (x < e || y < e || x > w - e || y > h - e) return Part.GRIP;
        if (y < GroupPaint.STRIP + 1) return onName ? Part.NAME : Part.STRIP;
        return Part.NONE;
    }

    // endregion

    // region Drawing

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final BoardGroup g = group();
        final BoardCanvas c = canvas();
        if (g == null) return;
        final boolean keyHot = isHovering() && partAt(localX(), localY()) == Part.KEY;
        final boolean selected = session.showsSelected(groupId);
        if (g.isCollapsed()) {
            Hyb.dropShadow(0, 0, getArea().width, getArea().height);
            GroupPaint.summary(g, summary(), session.rateUnit(), selected, keyHot, false, context.getCurrentDrawingZ());
            return;
        }
        final boolean lit = c != null && groupId.equals(c.dropGroup());
        final boolean leaving = c != null && groupId.equals(c.leavingGroup());
        final int w = getArea().width, h = getArea().height;
        GroupPaint.strip(g, w, GroupPaint.machinesText(summary().machines()), lit, leaving, keyHot, false);
        GroupPaint.border(g, w, h, lit, selected);
    }

    public Tip tip() {
        final BoardGroup g = group();
        if (g == null) return null;
        final Part part = partAt(localX(), localY());
        if (part == Part.KEY) return Tip.of(g.isCollapsed() ? "Expand" : "Collapse")
            .action(Tip.Input.LEFT, g.isCollapsed() ? "Expand" : "Collapse");
        final String count = GroupPaint.machinesText(summary().machines());
        final Tip tip = Tip.of(g.getName());
        if (!count.isEmpty()) tip.sub(count);
        if (part == Part.GRIP) return tip.action(Tip.Input.DRAG, "Resize the group");
        return tip.action(Tip.Input.DRAG, "Move the group")
            .action(
                Tip.Input.LEFT,
                g.isCollapsed() ? "Rename on the name, expand with a double-click" : "Rename (on the name)")
            .action(Tip.Input.RIGHT, "Group menu");
    }

    // endregion

    // region Mouse

    private Part pressed;
    private long lastClick;

    @Override
    public @NotNull Result onMousePressed(final int mouseButton) {
        final BoardGroup g = group();
        if (g == null) return Result.IGNORE;
        if (mouseButton == 1) {
            openMenu(getContext().getAbsMouseX(), getContext().getAbsMouseY());
            return Result.SUCCESS;
        }
        if (mouseButton != 0) return Result.IGNORE;
        pressed = partAt(localX(), localY());
        return Result.ACCEPT;
    }

    @Override
    public boolean onMouseScroll(final UpOrDown direction, final int amount) {
        return false;
    }

    private void click(final Part part) {
        final BoardGroup g = group();
        final BoardCanvas c = canvas();
        if (g == null || c == null) return;
        switch (part) {
            case KEY -> {
                Hyb.click();
                c.groups()
                    .setCollapsed(groupId, !g.isCollapsed());
            }
            case NAME -> rename();
            default -> {
                final long now = System.currentTimeMillis();
                if (g.isCollapsed() && now - lastClick < 350) {
                    lastClick = 0;
                    c.groups()
                        .setCollapsed(groupId, false);
                    return;
                }
                lastClick = now;
                c.clickSelect(groupId);
            }
        }
    }

    /** Rename in a text box at the name; empty keeps the old name. */
    public void rename() {
        final BoardGroup g = group();
        final BoardCanvas c = canvas();
        if (g == null || c == null) return;
        Popup.open(
            session.panel(),
            TextPopup.create(
                "Group name",
                g.getName(),
                name -> c.groups()
                    .rename(groupId, name)),
            c.screenX(g.getX()),
            c.screenY(g.getY()));
    }

    /**
     * The group's menu, the website's rows and words: Rename, Collapse or Expand, Colour..., Ungroup (keeps contents),
     * and in red Delete group and contents.
     */
    public void openMenu(final int sx, final int sy) {
        final BoardGroup g = group();
        final BoardCanvas c = canvas();
        if (g == null || c == null) return;
        final List<PickList.Entry> rows = new ArrayList<>();
        rows.add(PickList.Entry.of("Rename", () -> onLive(c, GroupCard::rename)));
        rows.add(
            PickList.Entry.of(
                g.isCollapsed() ? "Expand" : "Collapse",
                () -> c.groups()
                    .setCollapsed(groupId, !g.isCollapsed())));
        rows.add(PickList.Entry.of("Colour...", () -> onLive(c, card -> card.openColours(sx, sy))));
        rows.add(
            PickList.Entry.of(
                "Ungroup (keeps contents)",
                () -> c.groups()
                    .ungroup(groupId)));
        rows.add(
            new PickList.Entry(
                null,
                "Delete group and contents",
                "",
                Hyb.RED_INK,
                false,
                () -> c.groups()
                    .deleteWithContents(groupId)));
        Popup.open(session.panel(), PickList.popup("gtnhplanner_group", null, rows, false, 150), sx, sy);
    }

    private void openColours(final int sx, final int sy) {
        final BoardGroup g = group();
        final BoardCanvas c = canvas();
        if (g == null || c == null) return;
        final List<PickList.Entry> rows = new ArrayList<>();
        for (final String[] tag : NoteColors.MENU) rows.add(
            new PickList.Entry(
                null,
                "■ " + tag[1],
                "",
                NoteColors.swatch(tag[0]),
                tag[0].equals(g.colorTag()),
                () -> c.groups()
                    .recolor(groupId, tag[0])));
        Popup.open(
            session.panel(),
            PickList.popup("gtnhplanner_group_colour", "Colour", rows, false, 120, NoteColors.MENU.size()),
            sx,
            sy);
    }

    /** Runs on this group's widget as the board has it now (it may have been built afresh while a menu was open). */
    private void onLive(final BoardCanvas board, final java.util.function.Consumer<GroupCard> action) {
        final GroupCard live = board.groupCards()
            .get(groupId);
        if (live != null && live.isValid()) action.accept(live);
    }

    // endregion

    // region Moving and resizing

    private boolean resizing, resizeL, resizeR, resizeT, resizeB;
    private final int[] live = new int[4];
    private int[] from;
    private int startMouseX, startMouseY;

    @Override
    public boolean onDragStart(final int button) {
        final BoardGroup g = group();
        final BoardCanvas c = canvas();
        if (button != 0 || g == null || c == null) return false;
        if (pressed == Part.GRIP && !g.isCollapsed()) {
            final float x = localX(), y = localY(), e = grip();
            resizeL = x < e;
            resizeR = x > getArea().width - e;
            resizeT = y < e;
            resizeB = y > getArea().height - e;
            from = new int[] { g.getX(), g.getY(), g.getWidth(), g.getHeight() };
            System.arraycopy(from, 0, live, 0, 4);
            startMouseX = getContext().getAbsMouseX();
            startMouseY = getContext().getAbsMouseY();
            resizing = true;
            return true;
        }
        c.beginMove(groupId);
        return true;
    }

    @Override
    public void onDrag(final int mouseButton, final long timeSinceLastClick) {
        final BoardCanvas c = canvas();
        if (c == null) return;
        if (!resizing) {
            c.dragMove();
            return;
        }
        final float zoom = session.graph()
            .getZoom();
        final int dx = BoardSession.snap((getContext().getAbsMouseX() - startMouseX) / zoom);
        final int dy = BoardSession.snap((getContext().getAbsMouseY() - startMouseY) / zoom);
        int x = from[0], y = from[1], w = from[2], h = from[3];
        if (resizeL) {
            x += dx;
            w -= dx;
        }
        if (resizeR) w += dx;
        if (resizeT) {
            y += dy;
            h -= dy;
        }
        if (resizeB) h += dy;
        final int[] keep = c.groups()
            .clampFrame(groupId, x, y, w, h);
        System.arraycopy(keep, 0, live, 0, 4);
        sync();
    }

    @Override
    public void onDragEnd(final boolean successful) {
        final BoardCanvas c = canvas();
        final Part part = pressed;
        pressed = null;
        if (resizing) {
            resizing = false;
            if (c != null && successful) c.groups()
                .resize(groupId, live[0], live[1], live[2], live[3]);
            sync();
            if (Math.max(
                Math.abs(getContext().getAbsMouseX() - startMouseX),
                Math.abs(getContext().getAbsMouseY() - startMouseY)) < 3 && part != null) click(Part.STRIP);
            return;
        }
        if (c != null && c.endMove(successful) && part != null && group() != null) click(part);
    }

    @Override
    public void drawMovingState(final ModularGuiContext context, final float partialTicks) {}

    @Override
    public @Nullable Area getMovingArea() {
        return getArea();
    }

    @Override
    public boolean isMoving() {
        return moving;
    }

    @Override
    public void setMoving(final boolean moving) {
        this.moving = moving;
    }

    // endregion
}
