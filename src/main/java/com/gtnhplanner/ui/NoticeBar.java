package com.gtnhplanner.ui;

import java.util.List;
import java.util.UUID;

import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.gtnhplanner.ui.canvas.BoardCanvas;
import com.gtnhplanner.ui.note.NoteText;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * Notices along the top of the board, one row each: a stripe in the severity's colour, the sentence, and "Show me",
 * which frames the cards and drawers the notice is about. A sentence too long for the board wraps onto more lines in
 * its row, never cut. At most {@value #MAX_ROWS} rows; the rest are counted.
 */
final class NoticeBar extends Widget<NoticeBar> implements Interactable {

    static final int ROW = 14;
    static final int MAX_ROWS = 4;
    private static final int LINE = 10;

    /** Which card "Show me" went to last, per notice, for notices it walks through. */
    private final java.util.Map<String, Integer> turns = new java.util.HashMap<>();

    private final BoardSession session;
    private final BoardCanvas canvas;
    private int shownHeight = -1;

    NoticeBar(final BoardSession session, final BoardCanvas canvas) {
        this.session = session;
        this.canvas = canvas;
        height(0);
    }

    private int localX() {
        return getContext().getAbsMouseX() - getArea().x;
    }

    private int localY() {
        return getContext().getAbsMouseY() - getArea().y;
    }

    /** A notice's sentence in lines, wrapped to the room its row leaves it. */
    private static NoteText lines(final Notice n, final int w) {
        return NoteText.of(n.text(), Math.max(20, textRoom(n, rowWidth(n, w))), Hyb::width);
    }

    private static int textRoom(final Notice n, final int rw) {
        return rw - 10 - (n.hasKey() ? Hyb.width(n.keyLabel()) + 10 : 0);
    }

    /** A row's height: a line, or as many as its sentence wraps to. */
    private static int rowHeight(final Notice n, final int w) {
        return ROW + (lines(n, w).lines()
            .size() - 1) * LINE;
    }

    /** All the rows' height, with the count of the rest when there are more. */
    private int totalHeight() {
        final List<Notice> notices = session.notices();
        final int w = getArea().width;
        int h = 0;
        for (int i = 0; i < Math.min(notices.size(), MAX_ROWS); i++) h += rowHeight(notices.get(i), w);
        return notices.size() > MAX_ROWS ? h + ROW : h;
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        final int h = totalHeight();
        if (h != shownHeight) {
            shownHeight = h;
            height(h);
            scheduleResize();
        }
    }

    @Override
    public boolean canHover() {
        return shownHeight > 0;
    }

    private static int stripe(final com.gtnhplanner.data.flowchart.balancer.Severity severity) {
        return switch (severity) {
            case ERROR -> Hyb.RED_INK;
            case WARN -> Hyb.AMBER_INK;
            case INFO -> 0xFF6A8CB5;
        };
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final List<Notice> notices = session.notices();
        final int w = getArea().width;
        final int hover = isHovering() ? rowAt(localY()) : -1;
        int y = 0;
        for (int i = 0; i < Math.min(notices.size(), MAX_ROWS); i++) {
            final Notice n = notices.get(i);
            final int rw = rowWidth(n, w), rh = rowHeight(n, w);
            Hyb.rect(0, y, rw, rh - 1, 0xE0181A1F);
            Hyb.rect(0, y, 3, rh - 1, stripe(n.severity()));
            final NoteText text = lines(n, w);
            for (int l = 0; l < text.lines()
                .size(); l++)
                Hyb.text(
                    text.shown(l)
                        .strip(),
                    7,
                    y + 3 + l * LINE,
                    Hyb.INK);
            if (n.hasKey()) {
                final int showW = Hyb.width(n.keyLabel());
                final boolean hot = hover == i && localX() >= rw - showW - 8 && localX() < rw;
                final int color = hot ? 0xFFFFFFFF : Hyb.SELECTION;
                Hyb.text(n.keyLabel(), rw - showW - 5, y + 3, color);
                Hyb.rect(rw - showW - 5, y + 12, showW, 1, color);
            }
            y += rh;
        }
        if (notices.size() > MAX_ROWS) {
            Hyb.rect(0, y, w, ROW - 1, 0xE0181A1F);
            Hyb.text("+" + (notices.size() - MAX_ROWS) + " more", 7, y + 3, Hyb.MUTED);
        }
    }

    /** The notice row at {@code y}, or -1. */
    private int rowAt(final int y) {
        final List<Notice> notices = session.notices();
        final int w = getArea().width;
        int top = 0;
        for (int i = 0; i < Math.min(notices.size(), MAX_ROWS); i++) {
            final int rh = rowHeight(notices.get(i), w);
            if (y >= top && y < top + rh) return i;
            top += rh;
        }
        return -1;
    }

    /**
     * The GUI rectangle {x, y, w, h} of the first notice's key labelled {@code label} (the tour presses one), or null
     * when none shows.
     */
    public int[] keyRect(final String label) {
        final List<Notice> notices = session.notices();
        final int w = getArea().width;
        int y = 0;
        for (int i = 0; i < Math.min(notices.size(), MAX_ROWS); i++) {
            final Notice n = notices.get(i);
            if (n.hasKey() && label.equals(n.keyLabel())) {
                final int showW = Hyb.width(n.keyLabel());
                return new int[] { getArea().x + rowWidth(n, w) - showW - 5, getArea().y + y + 2, showW, LINE + 2 };
            }
            y += rowHeight(n, w);
        }
        return null;
    }

    /** A row is as wide as its sentence and "Show me", so the board stays visible beside it. */
    private static int rowWidth(final Notice n, final int max) {
        final int show = n.hasKey() ? Hyb.width(n.keyLabel()) + 10 : 0;
        return Math.min(max, 7 + Hyb.width(n.text()) + 8 + show);
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (mouseButton != 0) return Result.IGNORE;
        final int row = rowAt(localY());
        final List<Notice> notices = session.notices();
        if (row < 0) return Result.IGNORE;
        final Notice n = notices.get(row);
        final int rw = rowWidth(n, getArea().width);
        if (localX() >= rw) return Result.IGNORE;
        if (!n.hasKey() || localX() < rw - Hyb.width(n.keyLabel()) - 8) return Result.SUCCESS;
        if (n.action() != null) {
            Hyb.click();
            n.action()
                .run();
            return Result.SUCCESS;
        }
        // All at once while that still shows their ports; else one at a time, in turn, close enough to read.
        Hyb.click();
        final List<UUID> focus = n.focus();
        if (focus.size() == 1 || canvas.framesReadably(focus)) canvas.frame(focus);
        else canvas.frame(List.of(focus.get((turns.merge(n.text(), 1, Integer::sum) - 1) % focus.size())));
        return Result.SUCCESS;
    }
}
