package com.gtnhplanner.ui;

import java.util.ArrayList;
import java.util.List;

/** The arithmetic of dragging a plan tab along the strip, apart from the widget: where it lands, what order results. */
final class TabOrder {

    private TabOrder() {}

    /**
     * The place (0-based, among the other tabs) a dragged tab's centre is over. {@code widths} are the tabs' widths in
     * their order before the drag, {@code held} the dragged one's position in it; the others are judged where they
     * sit, so a tab does not flicker between two places as the others slide.
     */
    static int slotAt(final int[] widths, final int held, final float center, final int gap) {
        int slot = 0, left = 0;
        for (int i = 0; i < widths.length; i++) {
            if (i != held && left + widths[i] / 2f <= center) slot++;
            left += widths[i] + gap;
        }
        return slot;
    }

    /** {@code ids} with {@code held} taken out and put back at {@code slot} among the rest. */
    static List<Integer> orderWith(final List<Integer> ids, final int held, final int slot) {
        final List<Integer> out = new ArrayList<>(ids);
        out.remove(Integer.valueOf(held));
        out.add(Math.max(0, Math.min(slot, out.size())), held);
        return out;
    }

    /** {@code ids} with {@code id} stepped one place ({@code by} -1 or 1); unchanged at an end. */
    static List<Integer> stepped(final List<Integer> ids, final int id, final int by) {
        final int at = ids.indexOf(id), to = at + by;
        if (at < 0 || to < 0 || to >= ids.size()) return ids;
        final List<Integer> out = new ArrayList<>(ids);
        out.set(at, ids.get(to));
        out.set(to, id);
        return out;
    }
}
