package com.gtnhplanner.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widget.sizer.Unit;

/**
 * The keys that float over the top edge of the board, in the middle column between the overview and NEI, with no band
 * behind them and no room reserved: the non-recipe machines, undo and redo at the left; arrange and fit, the rate unit,
 * EU/t and average or peak at the right. They edit or view the board. The top bar above holds the account's things
 * only. A task board has none of these: it shows its own header instead ({@code ui/task/TaskBoardView}).
 *
 * <p>
 * It gives way as the board narrows: the keys first sit closer (the non-recipe key keeps only its bolt, arrange's
 * progress only its number), then the right group drops to a second row, still right-aligned.
 */
final class BoardToolbar extends ParentWidget<BoardToolbar> {

    private static final int MARGIN = 4, RIGHT_FROM = 2, GAP = 3, GROUP_GAP = 9, TIGHT_GAP = 1, TIGHT_GROUP_GAP = 4;

    /** A key: its group, and its width with room to spare and when the strip is tight. */
    private record Key(Widget<?> widget, int group, IntSupplier roomy, IntSupplier tight) {}

    /** Where everything goes: the rows, and each key's place and width. */
    private record Layout(int rows, int[] x, int[] y, int[] w) {}

    private final List<Key> keys = new ArrayList<>();
    private Layout laid;
    private int laidWidth = -1;
    private int laidSizes;

    BoardToolbar() {
        height(() -> MARGIN + layout().rows() * TopBar.ROW_H, Unit.Measure.PIXEL);
    }

    /**
     * Adds a key to {@code group} (groups in order, from 0; groups 0 and 1 sit at the left, the rest at the right),
     * {@code roomy} wide with room and {@code tight} without.
     */
    <W extends Widget<?>> W key(final int group, final W widget, final int roomy, final int tight) {
        return key(group, widget, () -> roomy, () -> tight);
    }

    /** As above, for a key whose width changes (the strip is laid out again then). */
    <W extends Widget<?>> W key(final int group, final W widget, final IntSupplier roomy, final IntSupplier tight) {
        final int i = keys.size();
        keys.add(new Key(widget, group, roomy, tight));
        widget.left(() -> layout().x()[i], Unit.Measure.PIXEL)
            .top(() -> layout().y()[i], Unit.Measure.PIXEL)
            .width(() -> layout().w()[i], Unit.Measure.PIXEL)
            .height(TopBar.KEY_H);
        child(widget);
        return widget;
    }

    /** What the keys take from the board's top now: the margin and a row, or more when the keys wrapped. */
    int barHeight() {
        return isEnabled() ? MARGIN + layout().rows() * TopBar.ROW_H : 0;
    }

    private int barWidth() {
        final int w = getArea().width;
        return w > 0 ? w : 100_000;
    }

    private Layout layout() {
        final int width = barWidth(), sizes = sizes();
        if (laid == null || width != laidWidth || sizes != laidSizes) {
            laid = lay(width);
            laidWidth = width;
            laidSizes = sizes;
        }
        return laid;
    }

    /** The keys' widths now, as one number: it changes when any of them does. */
    private int sizes() {
        int h = 17;
        for (final Key k : keys) h = h * 31 + k.roomy()
            .getAsInt() * 7
            + k.tight()
                .getAsInt();
        return h;
    }

    /** The board was resized: lay the keys out again. */
    @Override
    public void onUpdate() {
        super.onUpdate();
        if (barWidth() != laidWidth || sizes() != laidSizes) {
            layout();
            final com.cleanroommc.modularui.screen.ModularPanel panel = getPanel();
            if (panel != null) panel.scheduleResize();
        }
    }

    private Layout lay(final int width) {
        final int room = Math.max(0, width - 2 * MARGIN), n = keys.size(), split = split();
        final int[] x = new int[n], y = new int[n], w = new int[n];
        // One row with room, one row tight, then the right group on a second row, roomy then tight.
        for (final boolean tight : new boolean[] { false, true }) {
            if (span(0, split, tight) + span(split, n, tight) + (tight ? TIGHT_GROUP_GAP : GROUP_GAP) > room) continue;
            place(0, split, MARGIN, MARGIN, tight, x, y, w);
            place(split, n, width - MARGIN - span(split, n, tight), MARGIN, tight, x, y, w);
            return new Layout(1, x, y, w);
        }
        for (final boolean tight : new boolean[] { false, true }) {
            if (span(0, split, tight) > room || span(split, n, tight) > room) continue;
            place(0, split, MARGIN, MARGIN, tight, x, y, w);
            place(split, n, width - MARGIN - span(split, n, tight), MARGIN + TopBar.ROW_H, tight, x, y, w);
            return new Layout(2, x, y, w);
        }
        // Too narrow even so: each side wraps key by key, the left from the left, the right from the right.
        int row = wrap(0, split, room, 0, width, false, x, y, w);
        row = wrap(split, n, room, row, width, true, x, y, w);
        return new Layout(Math.max(1, row), x, y, w);
    }

    /** Where the right group starts: the first key past the left groups. */
    private int split() {
        int i = 0;
        while (i < keys.size() && keys.get(i)
            .group() < RIGHT_FROM) i++;
        return i;
    }

    /** Wraps keys {@code from} to {@code to}, tight, into rows from {@code row} on; returns the next free row. */
    private int wrap(final int from, final int to, final int room, int row, final int width, final boolean right,
        final int[] x, final int[] y, final int[] w) {
        int start = from;
        while (start < to) {
            int end = start + 1;
            while (end < to && span(start, end + 1, true) <= room) end++;
            final int left = right ? width - MARGIN - span(start, end, true) : MARGIN;
            place(start, end, left, MARGIN + row * TopBar.ROW_H, true, x, y, w);
            row++;
            start = end;
        }
        return row;
    }

    private int span(final int from, final int to, final boolean tight) {
        int w = 0;
        for (int i = from; i < to; i++) {
            final Key k = keys.get(i);
            if (i > from) w += k.group() != keys.get(i - 1)
                .group() ? tight ? TIGHT_GROUP_GAP : GROUP_GAP : tight ? TIGHT_GAP : GAP;
            w += (tight ? k.tight() : k.roomy()).getAsInt();
        }
        return w;
    }

    private void place(final int from, final int to, final int left, final int top, final boolean tight, final int[] x,
        final int[] y, final int[] w) {
        int at = left;
        for (int i = from; i < to; i++) {
            final Key k = keys.get(i);
            if (i > from) at += k.group() != keys.get(i - 1)
                .group() ? tight ? TIGHT_GROUP_GAP : GROUP_GAP : tight ? TIGHT_GAP : GAP;
            x[i] = at;
            y[i] = top;
            w[i] = (tight ? k.tight() : k.roomy()).getAsInt();
            at += w[i];
        }
    }
}
