package com.gtnhplanner.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.gtnhplanner.ui.canvas.BoardCanvas;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * Factory Flow's selection action bar, top middle over the board: with two or more cards selected that one machine
 * can run, "Combine N into one machine"; beside it, with two or more things selected, "Group" (as Ctrl+G, a group round
 * them: docs/design/groups.md). Shown only when one applies; never disabled, never explained when absent.
 */
final class SelectionBar extends Widget<SelectionBar> implements Interactable {

    private static final int H = 18, PAD = 8, ICON = 10, GAP = 6;
    private static final String GROUP = "Group";

    private final BoardSession session;
    private final BoardCanvas canvas;
    private final NoticeBar notices;
    private int shownX = Integer.MIN_VALUE, shownY = Integer.MIN_VALUE, shownW = -1;

    SelectionBar(final BoardSession session, final BoardCanvas canvas, final NoticeBar notices) {
        this.session = session;
        this.canvas = canvas;
        this.notices = notices;
        size(0, H);
    }

    /** The selected cards, when there are two or more that can share a machine; else empty. */
    private List<UUID> combinable() {
        final List<UUID> selected = new ArrayList<>(session.selection());
        return session.canCombine(selected) ? session.cardsIn(selected) : List.of();
    }

    private boolean groupable() {
        return session.selection()
            .size() >= 2;
    }

    /**
     * How the keys give way over a narrow board: 0 as they are, 1 with Combine short ("Combine 3"), 2 with Group as
     * its picture alone too (their tips say the rest).
     */
    private int tight;

    private String label(final int cards) {
        return tight > 0 ? "Combine " + cards : "Combine " + cards + " into one machine";
    }

    private int combineW(final List<UUID> cards) {
        return cards.isEmpty() ? 0 : Hyb.width(label(cards.size())) + 2 * PAD + ICON + 4;
    }

    private int groupW() {
        return groupable() ? tight > 1 ? 2 * PAD + ICON : Hyb.width(GROUP) + 2 * PAD + ICON + 4 : 0;
    }

    private int barW(final List<UUID> cards) {
        final int cw = combineW(cards), gw = groupW();
        return cw + gw + (cw > 0 && gw > 0 ? GAP : 0);
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        final List<UUID> cards = combinable();
        tight = 0;
        while (tight < 2 && barW(cards) > canvas.getArea().width - 8) tight++;
        final int w = barW(cards);
        // Centred over the board, under the notices so it never covers one.
        final int x = canvas.getArea().x + Math.max(4, (canvas.getArea().width - w) / 2);
        final int y = notices.getArea().y + notices.getArea().height + 4;
        if (x != shownX || y != shownY || w != shownW) {
            shownX = x;
            shownY = y;
            shownW = w;
            left(x);
            top(y);
            width(Math.max(1, w));
            scheduleResize();
        }
    }

    @Override
    public boolean canHover() {
        return shownW > 0;
    }

    /** Whether the mouse is on the Group key (the tip and the press ask). */
    boolean overGroupKey() {
        final int gw = groupW();
        if (gw == 0) return false;
        final int mx = getContext().getAbsMouseX() - getArea().x;
        return mx >= getArea().width - gw;
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final List<UUID> cards = combinable();
        final int cw = combineW(cards), gw = groupW();
        if (cw == 0 && gw == 0) return;
        final boolean hover = isHovering(), onGroup = hover && overGroupKey();
        if (cw > 0) {
            // Factory Flow's: a deep teal face, a 2 px cyan rim, lit top-left and shaded bottom-right inside it.
            Hyb.rect(2, 3, cw, H, 0x59000000);
            Hyb.rect(0, 0, cw, H, Hyb.SELECTION);
            Hyb.rect(1, 1, cw - 2, H - 2, hover && !onGroup ? 0xFF0E6878 : 0xFF0B5563);
            Hyb.rect(1, 1, cw - 2, 1, 0x9922D3EE);
            Hyb.rect(1, 1, 1, H - 2, 0x9922D3EE);
            Hyb.rect(1, H - 2, cw - 2, 1, 0xFF063640);
            Hyb.rect(cw - 2, 1, 1, H - 2, 0xFF063640);
            // Two boxes coming together.
            final int ix = PAD, iy = (H - ICON) / 2;
            Hyb.ring(ix + 1, iy + 1, 5, 5, 1, 0xFFFFFFFF);
            Hyb.ring(ix + 5, iy + 5, 4, 4, 1, 0xFFFFFFFF);
            Hyb.text(label(cards.size()), PAD + ICON + 4, (H - 8) / 2f + 0.5f, 0xFFFFFFFF);
        }
        if (gw > 0) {
            // Plain chrome, as every other key: groups have no house colour.
            final int x = getArea().width - gw;
            Hyb.rect(x + 2, 3, gw, H, 0x59000000);
            Hyb.rect(x, 0, gw, H, Hyb.KEY_EDGE);
            Hyb.rect(x + 1, 1, gw - 2, H - 2, onGroup ? Hyb.KEY_HOVER : Hyb.KEY);
            Hyb.rect(x + 1, 1, gw - 2, 1, Hyb.KEY_HI);
            Hyb.rect(x + 1, H - 2, gw - 2, 1, Hyb.KEY_LO);
            // A frame with a title strip.
            final int ix = x + PAD, iy = (H - ICON) / 2;
            Hyb.ring(ix, iy, ICON, ICON, 1, Hyb.INK);
            Hyb.rect(ix, iy, ICON, 3, Hyb.INK);
            if (tight < 2) Hyb.text(GROUP, x + PAD + ICON + 4, (H - 8) / 2f + 0.5f, Hyb.INK);
        }
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (mouseButton != 0 || shownW <= 0) return Result.IGNORE;
        Hyb.click();
        if (overGroupKey()) canvas.groups()
            .group(new ArrayList<>(session.selection()));
        else if (!combinable().isEmpty()) session.combine(new ArrayList<>(session.selection()));
        return Result.SUCCESS;
    }
}
