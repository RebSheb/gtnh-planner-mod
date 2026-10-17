package com.gtnhplanner.ui.group;

import com.gtnhplanner.data.flowchart.BoardGroup;
import com.gtnhplanner.ui.card.CardLayout;
import com.gtnhplanner.ui.card.CardPaint;
import com.gtnhplanner.ui.note.NoteColors;
import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * How a group looks ({@code docs/design/groups.md}, "The look"; the website's {@code clean/group/group.css} draws the
 * same): flat and square, 1 px chrome, the clean card's tokens, the group colour {@code C} from its tag. Sizes in board
 * px. The body is painted under the wires ({@link #body}), the frame, strip and summary card by {@link GroupCard}.
 */
public final class GroupPaint {

    private GroupPaint() {}

    /** The title strip's height, the collapse key's side and inset, where text starts. */
    public static final int STRIP = BoardGroup.STRIP, KEY = 14, KEY_INSET = 3, TEXT = 6;
    /** The summary card: a card wide; its machines line, a row, an icon, the padding under the last row. */
    public static final int SUM_W = CardLayout.W, LINE = 14, ROW = 20, ICON = 16, SUM_PAD = 4;

    /** The group colour. */
    public static int color(final BoardGroup g) {
        return NoteColors.swatch(g.colorTag());
    }

    /** Border and the rule under the strip: 60% canvas, 40% C. */
    public static int border(final int c) {
        return Hyb.mix(Hyb.CANVAS, c, 0.6f);
    }

    /** The title strip: 30% C over the card surface. */
    public static int strip(final int c) {
        return Hyb.mix(c, CardPaint.SURFACE, 0.3f);
    }

    /** The summary card's height for its rows. */
    public static int summaryHeight(final int rows) {
        final int h = STRIP + LINE + Math.max(1, rows) * ROW + SUM_PAD;
        return (h + 9) / 10 * 10;
    }

    /**
     * An open group's body, under the wires: C at 8%; lit while a carried thing would join it (brighter, with a 2 px
     * line inside in C), dimmed while one is carried out of it.
     */
    public static void body(final BoardGroup g, final boolean lit, final boolean leaving) {
        final int c = color(g);
        final int alpha = lit ? 0x38 : leaving ? 0x08 : 0x14;
        Hyb.rect(g.getX(), g.getY(), g.getWidth(), g.getHeight(), Hyb.seeThrough(alpha << 24 | c & 0xFFFFFF));
        if (lit) Hyb.ring(g.getX() + 1, g.getY() + 1, g.getWidth() - 2, g.getHeight() - 2, 2, c);
    }

    /** "N machines", "1 machine"; empty at 0. */
    public static String machinesText(final int count) {
        return count <= 0 ? "" : count == 1 ? "1 machine" : count + " machines";
    }

    /**
     * The title strip at the frame's top (local coordinates): name, muted machines, the collapse key at the right end
     * showing a minus (open) or a plus (collapsed).
     */
    public static void strip(final BoardGroup g, final int w, final String count, final boolean lit,
        final boolean leaving, final boolean keyHot, final boolean editing) {
        final int c = color(g);
        int fill = strip(c);
        if (lit) fill = Hyb.mix(0xFFFFFFFF, fill, 0.22f);
        else if (leaving) fill = Hyb.mix(0xFF000000, fill, 0.2f);
        Hyb.rect(1, 1, w - 2, STRIP - 1, fill);
        Hyb.rect(1, STRIP, w - 2, 1, border(c));
        final int keyX = w - KEY_INSET - KEY, keyY = (STRIP + 1 - KEY) / 2;
        if (!editing) {
            final String name = g.getName();
            final int room = keyX - TEXT - 4;
            final String shown = Hyb.fit(name, room);
            Hyb.text(shown, TEXT, (STRIP - 8) / 2f + 1, Hyb.INK);
            final int after = TEXT + Hyb.width(shown) + 6;
            if (!count.isEmpty() && after + Hyb.width(count) * 0.75f < keyX - 4)
                Hyb.text(count, after, (STRIP - 6) / 2f + 1.5f, 0.75f, Hyb.MUTED);
        }
        Hyb.rect(keyX, keyY, KEY, KEY, Hyb.KEY_EDGE);
        Hyb.rect(keyX + 1, keyY + 1, KEY - 2, KEY - 2, keyHot ? Hyb.KEY_HOVER : Hyb.KEY);
        Hyb.rect(keyX + 4, keyY + KEY / 2f - 0.5f, KEY - 8, 1, Hyb.INK);
        if (g.isCollapsed()) Hyb.rect(keyX + KEY / 2f - 0.5f, keyY + 4, 1, KEY - 8, Hyb.INK);
    }

    /** The collapse key's box in local coordinates {x, y, w, h}, for a frame (or summary) {@code w} wide. */
    public static int[] keyRect(final int w) {
        return new int[] { w - KEY_INSET - KEY, (STRIP + 1 - KEY) / 2, KEY, KEY };
    }

    /** The 1 px border round {@code w, h}: the group's, white while lit, the selection's while selected. */
    public static void border(final BoardGroup g, final int w, final int h, final boolean lit, final boolean selected) {
        Hyb.ring(0, 0, w, h, 1, selected ? Hyb.SELECTION : lit ? 0xFFFFFFFF : border(color(g)));
    }

    /** A collapsed group's summary card, in local coordinates. */
    public static void summary(final BoardGroup g, final GroupSummary s, final Fmt.RateUnit unit,
        final boolean selected, final boolean keyHot, final boolean editing, final float z) {
        final int h = summaryHeight(s.rows());
        Hyb.rect(0, 0, SUM_W, h, CardPaint.SURFACE);
        strip(g, SUM_W, machinesText(s.machines()), false, false, keyHot, editing);
        final String line = (s.machines() > 0 ? machinesText(s.machines()) : "No machines")
            + (s.euPerTick() > 0 ? "  " + Fmt.power(s.euPerTick()) + " EU/t" : "");
        Hyb.text(line, TEXT, STRIP + 1 + (LINE - 6) / 2f, 0.75f, Hyb.MUTED);
        final int top = STRIP + 1 + LINE;
        if (s.inputs()
            .isEmpty()
            && s.outputs()
                .isEmpty())
            Hyb.text("Nothing goes in or out.", TEXT, top + (ROW - 6) / 2f, 0.75f, Hyb.MUTED);
        for (int i = 0; i < s.inputs()
            .size(); i++)
            flow(
                s.inputs()
                    .get(i),
                TEXT,
                top + i * ROW,
                unit,
                z);
        for (int i = 0; i < s.outputs()
            .size(); i++)
            flow(
                s.outputs()
                    .get(i),
                SUM_W / 2 + TEXT,
                top + i * ROW,
                unit,
                z);
        border(g, SUM_W, h, false, selected);
    }

    private static void flow(final GroupSummary.Flow f, final int x, final int y, final Fmt.RateUnit unit,
        final float z) {
        Hyb.icon(f.item(), f.fluid(), x, y + (ROW - ICON) / 2f, ICON, z);
        Hyb.text(Hyb.fit(rate(f, unit), SUM_W / 2 - ICON - TEXT - 8), x + ICON + 4, y + (ROW - 8) / 2f + 1, Hyb.INK);
    }

    /** A flow's rate as the overview prints it: EU in EU/t, the rest in the board's unit. */
    public static String rate(final GroupSummary.Flow f, final Fmt.RateUnit unit) {
        if (com.gtnhplanner.ui.Resources.isPower(f.key())) return Fmt.power(f.perSecond() / 20) + " EU/t";
        return Fmt.rate(f.perSecond(), unit, f.fluid() != null);
    }
}
