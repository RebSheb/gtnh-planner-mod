package com.gtnhplanner.ui.world;

import java.util.List;

import javax.annotation.Nullable;

import org.lwjgl.opengl.GL11;

import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * A machine's label, as the plan over the world draws it in each ghost and the settings' preview draws it: on a soft
 * dark backing, its name (over two lines when long), then its tier, the power it draws (or makes) and how many machines
 * its card has ("2/4": which of them this is). It always fits its room: as the room gets small the name goes first,
 * then the power, then the count, and with not even its tier fitting at the smallest scale that reads there is none.
 */
public final class MachineLabel {

    private MachineLabel() {}

    /** Its padding, unscaled, and its largest scale. */
    private static final int PAD = 4;

    private static final float MOST = 0.75f;

    /** Power a generator makes. */
    private static final int MADE = 0xFF8FD694;

    /**
     * Draws a card's label centred on (cx, cy), as much of it as fits {@code roomW} by {@code roomH} at no less than
     * {@code least}, clear of the boxes in {@code taken} {x0, y0, x1, y1} (its own then added), the same way: less of
     * it, or none. {@code nth} is which of the card's machines this is, from 1; 0 for none in particular. Returns
     * whether it drew.
     */
       /**
        * A label measured: its words, and its levels of detail, most first, each {width, height, name lines, power,
        * count}.
        */
    record Layout(String count, String power, boolean makes, @Nullable String amps, int tierW, List<String> name,
        int[][] levels) {}

    /**
     * Labels measured, by card (snapshots are replaced, never changed) and which machine; a frame measures none twice.
     */
    private static final java.util.Map<PlanSnapshot.Card, Layout[]> MEASURED = new java.util.IdentityHashMap<>();

    private static Layout layout(final PlanSnapshot.Card c, final int nth) {
        if (MEASURED.size() > 2048) MEASURED.clear();
        final int of = (int) Math.ceil(c.builds() - 1e-9);
        final int slot = of > 1 && nth > 0 ? Math.min(nth, 64) : 0;
        final Layout[] known = MEASURED.computeIfAbsent(c, k -> new Layout[65]);
        if (known[slot] == null) known[slot] = measure(c, slot);
        return known[slot];
    }

    private static Layout measure(final PlanSnapshot.Card c, final int nth) {
        // One of several: which, "2/4"; a card of one machine, how many, "×1". Machines built: a crop card's
        // harvesters, not its crops.
        final int of = (int) Math.ceil(c.builds() - 1e-9);
        final String count = nth > 0 ? nth + "/" + of : "×" + Fmt.machines(c.builds());
        final boolean makes = c.madeEuPerTick() > 0;
        final double eu = makes ? c.madeEuPerTick() : c.euPerTick();
        final String power = eu > 0 ? (makes ? "+" : "") + Fmt.power(eu) + " EU/t" : "";
        final String amps = c.amps() > 1 ? c.amps() + "A" : null;
        final int tierW = c.tier()
            .isEmpty() ? 0 : Hyb.tierTagWidth(c.tier(), amps);
        final int countW = Hyb.width(count);
        final int full = tierW + (tierW > 0 && !power.isEmpty() ? 4 : 0) + Hyb.width(power) + 6 + countW;
        final List<String> name = wrap(c.name(), Math.max(full, 70));
        int nameW = 0;
        for (final String s : name) nameW = Math.max(nameW, Hyb.width(s));
        final int[][] levels = { { Math.max(nameW, full), 4 + name.size() * 10 + 15, name.size(), 1, 1 },
            { full, 17, 0, 1, 1 }, { tierW + (tierW > 0 ? 6 : 0) + countW, 17, 0, 0, 1 },
            { tierW > 0 ? tierW : countW, 17, 0, 0, tierW > 0 ? 0 : 1 } };
        return new Layout(count, power, makes, amps, tierW, name, levels);
    }

    /** A label placed: the card it is for, its words, which level of detail, its top left and its scale. */
    record Placement(PlanSnapshot.Card card, Layout layout, int level, float x0, float y0, float scale) {

        /** What it covers on the screen {x0, y0, x1, y1}. */
        float[] box() {
            final int[] l = layout.levels()[level];
            return new float[] { x0, y0, x0 + (l[0] + 2 * PAD) * scale, y0 + l[1] * scale };
        }
    }

    /**
     * Places a card's label centred on (cx, cy), as much of it as fits {@code roomW} by {@code roomH} at no less than
     * {@code least}, clear of the boxes in {@code taken} {x0, y0, x1, y1} (its own then added), the same way: less of
     * it, or none. {@code nth} is which of the card's machines this is, from 1; 0 for none in particular. Null when
     * none fits.
     */
    @Nullable
    static Placement place(final PlanSnapshot.Card c, final int nth, final float cx, final float cy, final float roomW,
        final float roomH, final float least, @Nullable final List<float[]> taken) {
        final Layout lay = layout(c, nth);
        final int[][] levels = lay.levels();
        for (int i = 0; i < levels.length; i++) {
            final int w = levels[i][0] + 2 * PAD, h = levels[i][1];
            final float scale = Math.min(Math.min(roomW / w, roomH / h), MOST);
            if (scale < least) continue;
            final float x0 = cx - w * scale / 2, y0 = cy - h * scale / 2, x1 = x0 + w * scale, y1 = y0 + h * scale;
            if (taken != null) {
                boolean clear = true;
                for (final float[] t : taken)
                    if (x0 < t[2] + 1 && t[0] < x1 + 1 && y0 < t[3] + 1 && t[1] < y1 + 1) clear = false;
                if (!clear) continue;
                taken.add(new float[] { x0, y0, x1, y1 });
            }
            return new Placement(c, lay, i, x0, y0, scale);
        }
        return null;
    }

    /** Draws a label where it was placed. */
    static void draw(final Placement p) {
        final Layout lay = p.layout();
        final int[] l = lay.levels()[p.level()];
        final int w = l[0] + 2 * PAD, h = l[1], tierW = lay.tierW();
        GL11.glPushMatrix();
        GL11.glTranslatef(p.x0(), p.y0(), 0);
        GL11.glScalef(p.scale(), p.scale(), 1);
        Hyb.roundRect(0, 0, w, h, 3, 0xA8141518);
        // No text shadows on the dark backing: each would draw the text twice, every frame.
        for (int i = 0; i < l[2]; i++) Hyb.text(
            lay.name()
                .get(i),
            PAD,
            3 + 10 * i,
            1f,
            Hyb.INK,
            false);
        final float y = h - 13;
        float x = PAD;
        if (tierW > 0) {
            Hyb.tierTag(
                p.card()
                    .tier(),
                lay.amps(),
                x,
                y);
            x += tierW + 4;
        }
        if (l[3] == 1 && !lay.power()
            .isEmpty()) Hyb.text(lay.power(), x, y, 1f, lay.makes() ? MADE : Hyb.MUTED, false);
        if (l[4] == 1) Hyb.text(lay.count(), w - PAD - Hyb.width(lay.count()), y, 1f, Hyb.INK, false);
        GL11.glPopMatrix();
    }

    /** {@link #place} and {@link #draw(Placement)} at once; whether it drew. */
    public static boolean draw(final PlanSnapshot.Card c, final int nth, final float cx, final float cy,
        final float roomW, final float roomH, final float least, @Nullable final List<float[]> taken) {
        final Placement p = place(c, nth, cx, cy, roomW, roomH, least, taken);
        if (p != null) draw(p);
        return p != null;
    }

    /** A name on one line, or two split at a space when it is wider than {@code room}, each cut to fit. */
    private static List<String> wrap(final String s, final int room) {
        if (Hyb.width(s) <= room) return List.of(s);
        final String[] words = s.split(" ");
        String first = words[0];
        int i = 1;
        while (i < words.length && Hyb.width(first + " " + words[i]) <= room) first += " " + words[i++];
        if (i >= words.length) return List.of(Hyb.fit(first, room));
        return List.of(
            Hyb.fit(first, room),
            Hyb.fit(String.join(" ", java.util.Arrays.copyOfRange(words, i, words.length)), room));
    }
}
