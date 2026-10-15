package com.gtnhplanner.ui.card;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import org.lwjgl.opengl.GL11;

import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * The clean card's pieces, drawn the same by the board's cards ({@link RecipeCard}) and the cards over the world and on
 * the minimap ({@link CleanCardView}), in card units at the origin, with {@link CardLayout}'s geometry.
 */
public final class CardPaint {

    /** The card's one surface, its edge, and the hairlines that part the header and the strip. */
    public static final int SURFACE = 0xFF23252A, EDGE = 0xFF34363C, HAIR = 0xFF2C2E34;
    /** The outline of what would take the wire in hand (the website's green), and of the one it would go to now. */
    public static final int ACCEPT_FAINT = 0xB34ADE80, ACCEPT = 0xFF4ADE80;
    /** The machine's name: as large as fits, down to plain. */
    private static final float[] NAME_SIZES = { 1.5f, 1.25f, 1f };
    /** How small a port's name may go before it is cut, and how wide the fade is where it is cut. */
    private static final float NAME_SMALL = 0.75f;
    private static final int NAME_FADE = 10;

    private CardPaint() {}

    /** The surface with its edge, {@code w} by {@code h}. */
    public static void surface(final int w, final int h) {
        Hyb.rect(0, 0, w, h, EDGE);
        Hyb.rect(1, 1, w - 2, h - 2, SURFACE);
    }

    /** A hairline across the card. */
    public static void hair(final int y) {
        Hyb.rect(1, y, CardLayout.W - 2, 1, HAIR);
    }

    /**
     * The machine's name from {@code x} to {@code right}, level with the header's chips: large, smaller when long, then
     * cut. Returns where it ends.
     */
    public static float name(final String name, final float x, final float right, final int colour) {
        final float room = right - x;
        for (final float size : NAME_SIZES) {
            if (Hyb.width(name) * size <= room || size == NAME_SIZES[NAME_SIZES.length - 1]) {
                final String shown = Hyb.width(name) * size <= room ? name : Hyb.fit(name, (int) (room / size));
                // Level with the chips' labels: the game's capitals are seven of its eight rows.
                Hyb.text(
                    shown,
                    x,
                    RecipeCard.crisp(CardLayout.HEAD_Y + (CardLayout.HEAD - 7 * size) / 2f),
                    size,
                    colour);
                return x + Hyb.width(shown) * size;
            }
        }
        return x;
    }

    /** A thin line between two rows on one side. */
    public static void rowSeparator(final boolean output, final int y) {
        Hyb.rect(CardLayout.railX(output) + 4, y - 1, CardLayout.RAIL_W - 8, 1, HAIR);
    }

    /**
     * A port's words: how much in large type with its unit small beside it, and the name small under it on one line
     * (smaller when long, then cut and faded into the card), from the edge {@link CardLayout#textEdge} (right aligned
     * for an output), in a row at {@code y}. The number drops to the name's size when it would not fit.
     */
    public static void words(final String number, final String suffix, final String name, final int colour,
        final boolean output, final int y) {
        words(number, suffix, name, colour, Hyb.MUTED, output, y);
    }

    /** As {@link #words(String, String, String, int, boolean, int)}, the name in {@code nameColour}. */
    public static void words(final String number, final String suffix, final String name, final int colour,
        final int nameColour, final boolean output, final int y) {
        words(number, suffix, name, colour, nameColour, output, y, false);
    }

    /** As above; {@code controls}, the words sit higher, leaving room under them for the port's rule key and box. */
    public static void words(final String number, final String suffix, final String name, final int colour,
        final int nameColour, final boolean output, final int y, final boolean controls) {
        final int edge = CardLayout.textEdge(output), room = CardLayout.TEXT_W;
        final float rateW = Hyb.width(number) * Hyb.FIGURE + 2 + Hyb.width(suffix);
        final boolean big = rateW <= room;
        final float rateH = big ? 8 * Hyb.FIGURE : 8;
        final String label = name == null ? "" : name;
        final float size = Hyb.width(label) <= room ? 1 : NAME_SMALL;
        final boolean cut = Hyb.width(label) * size > room;
        final String shown = cut ? Hyb.font()
            .trimStringToWidth(label, (int) (room / size)) : label;
        final float block = rateH + 2 + 8 * size + (controls ? 2 + CardLayout.CTRL_H : 0);
        final float top = RecipeCard.crisp(y + (CardLayout.ROW - block) / 2f);
        if (big) {
            final float x = output ? edge - rateW : edge;
            Hyb.text(number, x, top, Hyb.FIGURE, colour);
            Hyb.text(suffix, x + Hyb.width(number) * Hyb.FIGURE + 2, top + 3.5f, Hyb.MUTED);
        } else {
            final String both = Hyb.fit(number + suffix, room);
            Hyb.text(both, output ? edge - Hyb.width(both) : edge, top, colour);
        }
        final float nameW = Hyb.width(shown) * size, nameY = top + rateH + 2;
        final float nx = output ? edge - nameW : edge;
        Hyb.text(shown, nx, nameY, size, nameColour);
        if (!cut) return;
        // The cut end fades into the card.
        for (int k = 0; k < NAME_FADE; k++) {
            final int a = (int) (255f * (k + 1) / NAME_FADE);
            final float px = nx + nameW - NAME_FADE + k;
            Hyb.rect(px, nameY - 1, 1, 8 * size + 2, a << 24 | SURFACE & 0xFFFFFF);
        }
    }

    /** A flow's words: its rate in the plan's unit (EU/t for power) and its name. */
    public static void flowWords(final double perSecond, final boolean power, final boolean fluid, final String name,
        final Fmt.RateUnit unit, final boolean output, final int y) {
        flowWords(perSecond, power, fluid, name, unit, output, y, false, false);
    }

    /**
     * As above; {@code set}, the rate is the player's (the port's own rate), in gold as a pinned count is;
     * {@code controls}, room is left under the words for the port's rule key and box.
     */
    public static void flowWords(final double perSecond, final boolean power, final boolean fluid, final String name,
        final Fmt.RateUnit unit, final boolean output, final int y, final boolean set, final boolean controls) {
        final String number = power ? Fmt.power(perSecond / 20) : Fmt.compact(perSecond * unit.perSecond);
        final String suffix = power ? " EU/t" : (fluid ? " L" : "") + unit.suffix;
        words(
            number,
            suffix,
            name,
            set ? Hyb.GOLD : perSecond <= 0 ? 0xFFA8AFBB : Hyb.INK,
            Hyb.MUTED,
            output,
            y,
            controls);
    }

    /**
     * A port's rule key and rate box under its words in the row at {@code y}: the key's mark ({@code ~} greyed while
     * the plan decides, {@code =} in gold once set) in the middle of a small dark key, and the box with "Set", or the
     * rate set in gold with its unit, centred. {@code hotKey}/{@code hotBox}: the one under the mouse; {@code prompt}:
     * "Set" pulses gold, with every other thing waiting to be set, while the plan has nothing to solve for.
     */
    public static void portControls(final boolean output, final int y, @Nullable final String number,
        final String suffix, final boolean hotKey, final boolean hotBox, final boolean prompt,
        @Nullable final com.gtnhplanner.ui.popup.InlineNumber edit) {
        ruleControls(
            output,
            y,
            number != null ? com.gtnhplanner.data.flowchart.Drawer.Rule.EXACTLY
                : com.gtnhplanner.data.flowchart.Drawer.Rule.ANY,
            null,
            number,
            suffix,
            null,
            hotKey,
            hotBox,
            false,
            prompt,
            edit);
    }

    /**
     * A row's rule key and rate box, a port's or a drawer's: the key's mark ({@code keyMark}, else the rule's: greyed
     * for Any, gold for the rest) in the middle of a small dark key, and the box with the rate (gold, red when it
     * cannot be reached: {@code unmet}) and its unit centred, or {@code fixed} in grey, or "Set" (gold and pulsing
     * while the plan waits for something to be set: {@code prompt}).
     */
    public static void ruleControls(final boolean output, final int y,
        final com.gtnhplanner.data.flowchart.Drawer.Rule rule, @Nullable final String keyMark,
        @Nullable final String number, final String suffix, @Nullable final String fixed, final boolean hotKey,
        final boolean hotBox, final boolean unmet, final boolean prompt,
        @Nullable final com.gtnhplanner.ui.popup.InlineNumber edit) {
        final int cy = CardLayout.ctrlY(y), kx = CardLayout.keyX(output), bx = CardLayout.boxX(output);
        final int h = CardLayout.CTRL_H, kw = CardLayout.CTRL_KEY, bw = CardLayout.CTRL_BOX;
        // The key: its mark in the middle.
        Hyb.rect(kx, cy, kw, h, 0xFF111317);
        Hyb.rect(kx + 1, cy + 1, kw - 2, h - 2, hotKey ? 0xFF454952 : 0xFF34373E);
        Hyb.rect(kx + 1, cy + 1, kw - 2, 1, 0x1FFFFFFF);
        if (keyMark != null) Hyb.text(keyMark, kx + (kw - Hyb.width(keyMark) + 1) / 2f, cy + 1, Hyb.MUTED);
        else com.gtnhplanner.ui.drawer.DrawerCard.drawRuleMark(
            rule,
            kx + (kw - Hyb.width(com.gtnhplanner.ui.drawer.DrawerCard.ruleMark(rule)) + 1) / 2f,
            cy + 1,
            rule == com.gtnhplanner.data.flowchart.Drawer.Rule.ANY ? Hyb.MUTED : Hyb.GOLD);
        // The box: being typed into; or the rate with its unit, centred; or what stands there instead.
        if (edit != null) {
            edit.drawBox(bx, cy, bw, h, cy + 1);
            return;
        }
        Hyb.rect(bx, cy, bw, h, unmet ? 0xBFF87171 : hotBox ? Hyb.GOLD : 0xFF5A5E68);
        Hyb.rect(bx + 1, cy + 1, bw - 2, h - 2, hotBox ? 0xFF15171C : 0xFF0F1114);
        Hyb.rect(bx + 1, cy + 1, bw - 2, 1, 0xB3000000);
        if (fixed != null || number == null) {
            Hyb.textCentered(
                fixed != null ? fixed : "Set",
                bx + bw / 2f,
                cy + 1,
                fixed == null && prompt ? prompt() : 0xFF6F737C);
            return;
        }
        final String shown = Hyb.fit(number, bw - 4 - Hyb.width(suffix));
        final float tw = Hyb.width(shown) + Hyb.width(suffix), tx = bx + (bw - tw) / 2f;
        Hyb.text(shown, tx, cy + 1, unmet ? 0xFFF87171 : Hyb.GOLD);
        Hyb.text(suffix, tx + Hyb.width(shown), cy + 1, 0xFF8A8E97);
    }

    /**
     * What is on a drawer's row, a port row lifted off a card ({@code y} its top; the drawer's frame,
     * {@link com.gtnhplanner.ui.drawer.DrawerPaint#frame}, is its striped box): the resource at the edge (a source's on
     * the left, as an input's; a product's or trash's on the right, as an output's), what the plan moves through it and
     * its name, with room under them for its rule key and box when {@code controls}. Drawn in a card's coordinates: the
     * caller moves it onto the drawer.
     */
    public static void drawerRow(final com.gtnhplanner.data.flowchart.Drawer.Kind kind, @Nullable final ItemStack item,
        @Nullable final net.minecraftforge.fluids.FluidStack fluid, final boolean power, final String number,
        final String suffix, final String name, final int colour, final boolean controls, final int y, final float z) {
        final boolean output = kind != com.gtnhplanner.data.flowchart.Drawer.Kind.SOURCE;
        final int tint = com.gtnhplanner.ui.drawer.DrawerPaint.tint(kind);
        icon(item, fluid, power, output, y, z);
        words(number, suffix, name, colour, Hyb.mix(tint, Hyb.MUTED, 0.45f), output, y, controls);
    }

    /**
     * A port's extra row ({@link CardModel.PortView#extra}), at {@code y} under the port's own: what the plan is forced
     * to send out at the port with no drawer to take it, or bring in with no source, drawn as the drawer it stands for
     * in a row of the card. A faint striped box in that drawer's colours and shape (a product's rounded square out, a
     * source's tank in), the resource, the rate in the drawer's ink, and the word under it.
     */
    public static void extraRow(final CardModel.PortView p, final Fmt.RateUnit unit, final int y, final float z) {
        extraRow(p, unit, y, z, 0);
    }

    /**
     * As {@link #extraRow(CardModel.PortView, Fmt.RateUnit, int, float)}, with {@code carried} of it picked up and in
     * hand: the row shows the rest, or, all of it carried, lies empty (a dashed outline where it was).
     */
    public static void extraRow(final CardModel.PortView p, final Fmt.RateUnit unit, final int y, final float z,
        final double carried) {
        final com.gtnhplanner.data.flowchart.Drawer.Kind kind = p.output()
            ? com.gtnhplanner.data.flowchart.Drawer.Kind.PRODUCT
            : com.gtnhplanner.data.flowchart.Drawer.Kind.SOURCE;
        final int tint = com.gtnhplanner.ui.drawer.DrawerPaint.tint(kind);
        final double left = Math.max(0, p.extra() - carried);
        final int bx = CardLayout.railX(p.output()) - 2, bw = CardLayout.RAIL_W + 4;
        if (carried > 0 && left < 1e-6) {
            Hyb.dashed(bx + 1, y + 3, bw - 2, CardLayout.ROW - 6, Hyb.mix(tint, EDGE, 0.35f), 3, 3);
            final String gone = "picked up";
            final float x = p.output() ? CardLayout.textEdge(true) - Hyb.width(gone) : CardLayout.textEdge(false);
            Hyb.text(gone, x, y + (CardLayout.ROW - 8) / 2f, Hyb.mix(tint, Hyb.MUTED, 0.3f));
            return;
        }
        spareRow(p, unit, y, z, left, p.output() ? EXTRA_OUT : EXTRA_IN, false, false);
    }

    /**
     * A port with no wire and no drawer that the plan runs through anyway (a pinned machine runs whatever is wired to
     * it): what it takes comes in from outside, what it makes leaves, as a spare's does. So it is drawn as one, in a
     * source's blue or a product's orange, striped, with its own name.
     */
    public static void openRow(final CardModel.PortView p, final Fmt.RateUnit unit, final int y, final float z,
        final boolean set) {
        spareRow(p, unit, y, z, p.perSecond(), p.name(), set, true);
    }

    /**
     * The striped row of a flow leaving or coming into the plan at a port: {@code amount} a second, {@code label}; the
     * number in gold when it is the player's (the port's own rate, {@code set}).
     */
    private static void spareRow(final CardModel.PortView p, final Fmt.RateUnit unit, final int y, final float z,
        final double amount, final String label, final boolean set, final boolean controls) {
        final int tint = com.gtnhplanner.ui.drawer.DrawerPaint.tint(
            p.output() ? com.gtnhplanner.data.flowchart.Drawer.Kind.PRODUCT
                : com.gtnhplanner.data.flowchart.Drawer.Kind.SOURCE);
        spareBox(
            !p.output(),
            CardLayout.railX(p.output()) - 2,
            y + CardLayout.BOX_Y,
            CardLayout.RAIL_W + 4,
            CardLayout.BOX_H);
        icon(p.item(), p.fluid(), p.isPower(), p.output(), y, z);
        final String number = p.isPower() ? Fmt.power(amount / 20) : Fmt.compact(amount * unit.perSecond);
        final String suffix = p.isPower() ? " EU/t" : (p.isFluid() ? " L" : "") + unit.suffix;
        words(
            number,
            suffix,
            label,
            set ? Hyb.GOLD : Hyb.mix(tint, Hyb.INK, 0.75f),
            Hyb.mix(tint, Hyb.MUTED, 0.45f),
            p.output(),
            y,
            controls);
    }

    /**
     * The faint striped box a leftover is drawn in (a card's extra row, the overview's spare bar): a product's square
     * in its orange for a forced output, a source's tank in its blue for a forced input.
     */
    public static void spareBox(final boolean importing, final float x, final float y, final float w, final float h) {
        edgeBox(
            importing ? com.gtnhplanner.data.flowchart.Drawer.Kind.SOURCE
                : com.gtnhplanner.data.flowchart.Drawer.Kind.PRODUCT,
            x,
            y,
            w,
            h);
    }

    /**
     * The striped box of what crosses the plan's edge, in a drawer kind's colours and shape: a forced row's, a
     * drawer's (trash a grey bin).
     */
    public static void edgeBox(final com.gtnhplanner.data.flowchart.Drawer.Kind kind, final float x, final float y,
        final float w, final float h) {
        final int tint = com.gtnhplanner.ui.drawer.DrawerPaint.tint(kind);
        final boolean own = !Hyb.batching();
        if (own) Hyb.beginBatch();
        com.gtnhplanner.ui.drawer.DrawerPaint.shape(kind, x, y, w, h, 0, Hyb.mix(tint, EDGE, 0.4f), EXTRA_SHAPE);
        com.gtnhplanner.ui.drawer.DrawerPaint.shape(kind, x, y, w, h, -1, Hyb.mix(tint, SURFACE, 0.07f), EXTRA_SHAPE);
        com.gtnhplanner.ui.drawer.DrawerPaint
            .stripes(kind, x, y, w, h, -1, Hyb.mix(tint, SURFACE, 0.13f), EXTRA_SHAPE, 8, 3);
        if (own) Hyb.endBatch();
    }

    /** The extra row's drawer shape, at a drawer's own share of its corners. */
    private static final float EXTRA_SHAPE = com.gtnhplanner.ui.drawer.DrawerPaint.SCALE;
    public static final String EXTRA_OUT = "Forced", EXTRA_IN = "Forced";

    /** A port's icon at the card's edge (a bolt for power). */
    public static void icon(@Nullable final ItemStack item, @Nullable final net.minecraftforge.fluids.FluidStack fluid,
        final boolean power, final boolean output, final int y, final float z) {
        final int x = CardLayout.iconX(output), iy = y + CardLayout.ICON_Y;
        if (power) RecipeCard.euIcon(x, iy, CardLayout.ICON);
        else Hyb.icon(item, fluid, x, iy, CardLayout.ICON, z);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }

    /**
     * The small amber square on the bottom left of an input's icon that can be set to another item (the website's
     * marker on a slot that takes several).
     */
    public static void choiceMark(final int y) {
        final int x = CardLayout.iconX(false) - 1, by = y + CardLayout.ICON_Y + CardLayout.ICON - 4;
        Hyb.rect(x, by, 5, 5, 0xFF000000);
        Hyb.rect(x + 1, by + 1, 3, 3, 0xFFFFAA00);
    }

    /** The circuit's row among the inputs: its icon, its number large, "Circuit" small. */
    public static void circuitRow(final ItemStack circuit, final int y, final float z) {
        com.gtnhplanner.ui.gt.CircuitIcons
            .draw(circuit, CardLayout.iconX(false), y + CardLayout.ICON_Y, CardLayout.ICON, z);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        words(String.valueOf(circuit.getItemDamage()), "", "Circuit", Hyb.INK, false, y);
    }

    /** The circuit on the picture's bottom left corner, large. */
    public static void circuitBadge(final ItemStack circuit, final int[] picture, final float z) {
        com.gtnhplanner.ui.gt.CircuitIcons.draw(circuit, picture[0], picture[1] + picture[2] - 24, 24, z);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }

    /** Under a pinned count, centred, in grey. */
    public static final String PINNED = "(pinned)";
    /** Under a count the plan waits for (nothing set anywhere): what to do, pulsing gold with the drawers' Set rate. */
    public static final String SET_COUNT = "(set count)";

    /**
     * Card-local {x, y, w, h} of the machine count on the picture's bottom right corner and the line kept under it (for
     * "(pinned)" or "(set count)"), whatever it says: the count never moves.
     */
    public static int[] countRect(final String count, final int[] picture) {
        final int cw = Math.round(Hyb.width(count) * Hyb.FIGURE);
        final int w = Math.max(cw, Hyb.width(SET_COUNT)) + 4, h = 23;
        return new int[] { picture[0] + picture[2] - w, picture[1] + picture[2] - h, w, h };
    }

    /**
     * How many machines, in white on the picture's bottom right corner (gold when {@code pinned}, as everything pinned
     * is), and under it, centred, {@code under}
     * ("(pinned)"
     * in grey, "(set count)" pulsing gold, or nothing) in {@code underColor}; a dotted line under the count when the
     * mouse
     * is on it. The count sits in the same place whatever is under it.
     */
    public static void count(final String count, final boolean none, final boolean pinned, @Nullable final String under,
        final int underColor, final boolean hover, final int[] picture) {
        final float s = Hyb.FIGURE, tw = Hyb.width(count) * s;
        final float block = Math.max(Math.max(tw, Hyb.width(SET_COUNT)), under == null ? 0 : Hyb.width(under));
        final float right = picture[0] + picture[2] - 2;
        final float cx = right - block / 2f;
        final float y = picture[1] + picture[2] - 8 * s - 2 - 9;
        final float x = cx - tw / 2f;
        Hyb.text(count, x, y, s, pinned ? Hyb.GOLD : none ? Hyb.MUTED : Hyb.INK);
        if (under != null && !under.isEmpty()) Hyb.text(under, cx - Hyb.width(under) / 2f, y + 8 * s + 1, underColor);
        if (hover) for (int dx = 0; dx < tw; dx += 3) Hyb.rect(x + dx, y + 8 * s, 1, 1, Hyb.MUTED);
    }

    /** Gold on the beat the drawers' Set rate pulses on, grey off it: the next thing to do, while nothing is set. */
    public static int prompt() {
        // A picture of the plan holds still, at rest.
        if (com.gtnhplanner.ui.canvas.PlanPicture.drawing()) return 0xFF6F737C;
        return (System.currentTimeMillis() / 500) % 2 == 0 ? Hyb.GOLD : 0xFF6F737C;
    }

    /**
     * A chip in the settings strip, {@code w} wide: its icon, its label quiet and its value plain; red edged when it
     * stops the recipe. A reading has no edge, only its words.
     */
    public static void chip(final int x, final int y, final int w, final String label, final String value,
        @Nullable final ItemStack icon, final boolean warn, final boolean reading, final boolean hover, final float z) {
        if (!reading) {
            Hyb.rect(x, y, w, CardLayout.CHIP, warn ? 0xFF6B2A2A : hover ? 0xFF4A4D55 : EDGE);
            Hyb.rect(x + 1, y + 1, w - 2, CardLayout.CHIP - 2, hover ? 0xFF33363C : 0xFF2A2C31);
        }
        int tx = x + CardLayout.CHIP_PAD;
        if (icon != null) {
            Hyb.item(icon, tx, y + 2, 14, z);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            tx += 14 + 3;
        }
        Hyb.text(label, tx, y + 5, Hyb.MUTED);
        Hyb.text(value, tx + Hyb.width(label) + 4, y + 5, warn ? Hyb.RED_INK : Hyb.INK);
    }
}
