package com.gtnhplanner.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widget.sizer.Unit;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * The planner's top bar, for the account's things only: the plan tabs (with their live status and faces), then the
 * keys in their groups (library, share and the account; feedback, settings and help). What edits or views the board
 * is in {@link BoardToolbar}, along the top of the board.
 *
 * <p>
 * It gives way as the planner narrows, as the website's bar does. First the keys sit closer, the non-recipe key keeps
 * only its bolt and arrange's progress only its number, and the faces of who else is here fold to two and a count (in
 * {@link PlanTabs}). When the keys still leave the tabs too little room they move to rows of their own under the tabs,
 * wrapped a group at a time; the last of those rows goes up beside the tabs when it fits there. The tabs always keep
 * room for the open plan's tab and "+".
 */
final class TopBar extends ParentWidget<TopBar> {

    /** One row of the bar, and the keys on it. */
    static final int ROW_H = 20, KEY_H = 16;
    private static final int PAD = 4, GAP = 3, GROUP_GAP = 9, TIGHT_GAP = 1, TIGHT_GROUP_GAP = 4;
    private static final int BG = 0xFF0E0F12, LINE = 0xFF2A2C31;

    /** A key: its group, and its width with room to spare and when the bar is tight (a key may change its width). */
    private record Key(Widget<?> widget, int group, IntSupplier roomy, IntSupplier tight) {}

    /** Where everything goes: the rows, the tabs' width, and each key's place and width. */
    private record Layout(int rows, int tabsW, int[] x, int[] y, int[] w) {}

    private final PlanTabs tabs;
    private final List<Key> keys = new ArrayList<>();
    private Layout laid;
    private int laidWidth = -1, laidReserve = -1, laidKeys = -1;

    TopBar(final PlanTabs tabs) {
        this.tabs = tabs;
        widthRel(1f);
        height(() -> layout().rows() * ROW_H, Unit.Measure.PIXEL);
        background((IDrawable) (ctx, x, y, w, h, theme) -> {
            Hyb.rect(x, y, w, h, BG);
            // The tabs sit on a line, as on one row; the keys' rows under it share the bar's ground.
            if (h > ROW_H) Hyb.rect(x, y + ROW_H - 1, w, 1, LINE);
            Hyb.rect(x, y + h - 1, w, 1, LINE);
        });
        tabs.left(PAD)
            .top(2)
            .height(KEY_H)
            .width(() -> layout().tabsW(), Unit.Measure.PIXEL);
        child(tabs);
    }

    /**
     * Adds a key to {@code group} (groups in order, from 0), {@code roomy} wide with room and {@code tight} without.
     */
    <W extends Widget<?>> W key(final int group, final W widget, final int roomy, final int tight) {
        return key(group, widget, () -> roomy, () -> tight);
    }

    /** As above, for a key whose width changes (the account's: "Sign in", then a face). */
    <W extends Widget<?>> W key(final int group, final W widget, final IntSupplier roomy, final IntSupplier tight) {
        final int i = keys.size();
        keys.add(new Key(widget, group, roomy, tight));
        widget.left(() -> layout().x()[i], Unit.Measure.PIXEL)
            .top(() -> layout().y()[i], Unit.Measure.PIXEL)
            .width(() -> layout().w()[i], Unit.Measure.PIXEL)
            .height(KEY_H);
        child(widget);
        return widget;
    }

    /** The bar's height now: a row, or more when the keys moved under the tabs. */
    int barHeight() {
        return layout().rows() * ROW_H;
    }

    /** The planner's width, which the bar spans. */
    private int barWidth() {
        final ModularPanel panel = isValid() ? getPanel() : null;
        final int w = panel != null ? panel.getArea().width : getArea().width;
        return w > 0 ? w : getArea().width;
    }

    /** The keys' widths added up: it moves when one of them changes its width. */
    private int keysSig() {
        int sig = 0;
        for (final Key k : keys) sig = sig * 31 + k.roomy()
            .getAsInt() * 7
            + k.tight()
                .getAsInt();
        return sig;
    }

    private Layout layout() {
        final int width = barWidth(), reserve = tabs.reserve(), sig = keysSig();
        if (laid == null || width != laidWidth || reserve != laidReserve || sig != laidKeys) {
            laid = lay(width, reserve);
            laidWidth = width;
            laidReserve = reserve;
            laidKeys = sig;
        }
        return laid;
    }

    /** The planner was resized, or the tabs' end changed (someone came or went): lay the bar out again. */
    @Override
    public void onUpdate() {
        super.onUpdate();
        if (barWidth() != laidWidth || tabs.reserve() != laidReserve || keysSig() != laidKeys) {
            layout();
            final ModularPanel panel = getPanel();
            if (panel != null) panel.scheduleResize();
        }
    }

    // region The layout

    private Layout lay(final int width, final int reserve) {
        final int room = Math.max(0, width - 2 * PAD), n = keys.size();
        final int[] x = new int[n], y = new int[n], w = new int[n];
        // One row: the keys at its right end, roomy if they can be, the tabs taking the rest.
        for (final boolean tight : new boolean[] { false, true }) {
            final int keysW = span(0, n, tight), groupGap = tight ? TIGHT_GROUP_GAP : GROUP_GAP;
            if (reserve + groupGap + keysW > room) continue;
            place(0, n, PAD + room - keysW, 2, tight, x, y, w);
            return new Layout(1, room - keysW - groupGap, x, y, w);
        }
        // Rows: the tabs on the first, the keys under them, wrapped a group at a time.
        final List<int[]> rows = new ArrayList<>();
        int from = 0;
        for (int i = 1; i < n; i++) {
            // A group goes on whole: to the next row when it does not fit what is left of this one. Only a group wider
            // than a row breaks between its keys.
            final boolean groupStart = keys.get(i)
                .group()
                != keys.get(i - 1)
                    .group();
            if (span(from, groupStart ? groupEnd(i) : i + 1, true) > room) {
                rows.add(new int[] { from, i });
                from = i;
            }
        }
        if (n > 0) rows.add(new int[] { from, n });
        int tabsW = room;
        int below = rows.size();
        // The last row beside the tabs, when they keep their room.
        if (rows.size() > 1) {
            final int[] last = rows.get(rows.size() - 1);
            final int lastW = span(last[0], last[1], true);
            if (reserve + TIGHT_GROUP_GAP + lastW <= room) {
                place(last[0], last[1], PAD + room - lastW, 2, true, x, y, w);
                tabsW = room - lastW - TIGHT_GROUP_GAP;
                below--;
            }
        }
        for (int r = 0; r < below; r++) place(rows.get(r)[0], rows.get(r)[1], PAD, (r + 1) * ROW_H + 2, true, x, y, w);
        return new Layout(1 + below, tabsW, x, y, w);
    }

    /** The end of the group key {@code i} is in (exclusive). */
    private int groupEnd(final int i) {
        int end = i;
        while (end < keys.size() && keys.get(end)
            .group()
            == keys.get(i)
                .group())
            end++;
        return end;
    }

    /** The width of keys {@code from} to {@code to} (exclusive) side by side, a group's gap between groups. */
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

    /** Puts keys {@code from} to {@code to} (exclusive) side by side from {@code left}, at {@code top}. */
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

    // endregion
}
