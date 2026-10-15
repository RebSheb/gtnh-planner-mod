package com.gtnhplanner.ui.card;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.canvas.BoardCanvas;
import com.gtnhplanner.ui.popup.Popup;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * A card's settings (its gear key), in the clean card's look: the settings by section, each under its title, in one
 * column, or two side by side when there are many (a machine with worked formulas has them as the second, titled
 * Formulas; one with nothing to set says so), each a row with a pin, its name and its control (a
 * switch, a number with arrows, or a list), and a line on how it works. Pinned settings show on the card as chips, on
 * every card of the machine. It stays open while settings change, and reads them afresh every frame.
 */
public final class SettingsSheet extends Popup {

    static final int COL = 236, HEAD = 4, HEADING = 16, ROW = 18, FOOT = 16;
    /** A section's title: the same for every section, settings or formulas. */
    static final int TITLE = 0xFF8A8D96;
    /** What a machine with nothing to set shows in place of its settings. */
    static final String NONE = "No settings for this machine.";
    /** At most this many settings in one column; more go in two. */
    private static final int ONE_COLUMN = 7;

    private SettingsSheet(@Nullable final BoardCanvas board, final RecipeCard card, final Sheet sheet) {
        super("gtnhplanner_card_settings", sheet.w, sheet.h);
        child(new Body(board, card, this, sheet).pos(0, 0));
    }

    /**
     * Opens the card's settings with its top left at a screen point (nudged onto the screen), over the board's own
     * panel. The sheet follows the card on {@code board} from then on (see {@link Body#card()}).
     */
    static void open(final RecipeCard card, @Nullable final BoardCanvas board, final int x, final int y) {
        final CardModel m = card.model();
        if (m == null) return;
        Popup.open(
            card.session()
                .panel(),
            new SettingsSheet(board, card, Sheet.of(m)),
            x,
            y);
    }

    @Override
    public void drawBackground(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final int w = getArea().width, h = getArea().height;
        Hyb.dropShadow(0, 0, w, h);
        CardPaint.surface(w, h);
    }

    /** A line of the sheet, where it is: a section's heading ({@code control} null) or a setting. */
    private record Line(String heading, SettingControls.Control control, int x, int y) {}

    /** A formula line's height; the formulas column's narrowest and widest. */
    private static final int FORMULA = 12, FORMULAS_MIN = 200, FORMULAS_MAX = 340;

    /**
     * The sheet laid out: its lines, its size, how many columns, and a modelled machine's worked formulas, read-only
     * (they show here and never on the card): the settings in the left column, the formulas beside them in a column
     * of their own, as wide as they need ({@code formulasX}, {@code formulasW}).
     */
    private record Sheet(List<Line> lines, int w, int h, int columns,
        List<com.gtnhplanner.machines.FormulaLine> formulas, int formulasX, int formulasW) {

        static Sheet of(final CardModel m) {
            return of(SettingControls.of(m), com.gtnhplanner.machines.game.MachineModels.formulas(m.node, m.machines));
        }

        static Sheet of(final List<SettingControls.Control> controls,
            final List<com.gtnhplanner.machines.FormulaLine> formulas) {
            final List<List<SettingControls.Control>> sections = new ArrayList<>();
            String group = null;
            for (final SettingControls.Control c : controls) {
                if (!c.group()
                    .equals(group)) {
                    group = c.group();
                    sections.add(new ArrayList<>());
                }
                sections.get(sections.size() - 1)
                    .add(c);
            }
            // A modelled machine's settings stay in one column: the formulas take the second.
            final int columns = formulas.isEmpty() && controls.size() > ONE_COLUMN && sections.size() > 1 ? 2 : 1;
            final int[] height = new int[columns];
            final List<Line> lines = new ArrayList<>();
            for (final List<SettingControls.Control> section : sections) {
                // Each section goes in the shorter column.
                int col = 0;
                for (int k = 1; k < columns; k++) if (height[k] < height[col]) col = k;
                final int x = col * (COL + 1);
                int y = HEAD + height[col];
                lines.add(
                    new Line(
                        section.get(0)
                            .group(),
                        null,
                        x,
                        y));
                y += HEADING;
                for (final SettingControls.Control c : section) {
                    lines.add(new Line(null, c, x, y));
                    y += ROW;
                }
                height[col] = y - HEAD + 4;
            }
            int tallest = ROW;
            for (final int h : height) tallest = Math.max(tallest, h);
            if (formulas.isEmpty()) return new Sheet(
                lines,
                columns * COL + (columns - 1),
                HEAD + tallest + foot(columns) + 4,
                columns,
                formulas,
                0,
                0);
            final int fw = Body.formulasWidth(formulas);
            final int formulasH = Body.formulasHeight(formulas, fw);
            // A machine with nothing to set (most single blocks): says so beside its working.
            if (controls.isEmpty()) {
                final int left = Hyb.width(NONE) + 16;
                return new Sheet(
                    lines,
                    left + 1 + fw,
                    HEAD + Math.max(HEADING + ROW, formulasH) + 4,
                    2,
                    formulas,
                    left + 1,
                    fw);
            }
            return new Sheet(
                lines,
                COL + 1 + fw,
                HEAD + Math.max(tallest, formulasH) + foot(2) + 4,
                2,
                formulas,
                COL + 1,
                fw);
        }

        /** The line on how it works: two short lines in one column, one long line across two. */
        static int foot(final int columns) {
            return columns == 1 ? FOOT + 10 : FOOT;
        }
    }

    private static final class Body extends Widget<Body> implements Interactable {

        @Nullable
        private final BoardCanvas board;
        /** The card the sheet was opened on, and its id on the board. */
        private final RecipeCard opened;
        private final UUID cardId;
        private final BoardSession session;
        private final SettingsSheet popup;
        private Sheet sheet;

        Body(@Nullable final BoardCanvas board, final RecipeCard card, final SettingsSheet popup, final Sheet sheet) {
            this.board = board;
            this.opened = card;
            this.cardId = card.nodeId;
            this.session = card.session();
            this.popup = popup;
            this.sheet = sheet;
            size(sheet.w, sheet.h);
        }

        /**
         * The card as the board has it now; null once it is gone (deleted, undone, another plan). The sheet stays open
         * while settings change, and a change that adds or takes away a port (a boiler's fuel) has the board build
         * every card afresh: the one the sheet opened on is then on no panel, ModularUI throws when it is asked for
         * one, and its model is no longer the plan's (issue 492).
         */
        @Nullable
        private RecipeCard card() {
            final RecipeCard live = board != null ? board.cards()
                .get(cardId) : opened;
            return live != null && live.isValid() ? live : null;
        }

        /**
         * Reads the settings afresh; when some come or go (a multiblock's own appear when it is one), resizes. Closes
         * once its card is gone.
         */
        @Override
        public void onUpdate() {
            super.onUpdate();
            final RecipeCard card = card();
            if (card == null) {
                popup.closeIfOpen();
                return;
            }
            final CardModel m = card.model();
            if (m == null) return;
            final Sheet next = Sheet.of(m);
            if (next.w != sheet.w || next.h != sheet.h) {
                size(next.w, next.h);
                popup.size(next.w, next.h);
                popup.scheduleResize();
            }
            sheet = next;
        }

        @Override
        public boolean canHover() {
            return true;
        }

        private int mouseX() {
            return getContext().getAbsMouseX() - getArea().x;
        }

        private int mouseY() {
            return getContext().getAbsMouseY() - getArea().y;
        }

        /** The setting's line under the mouse, or null. */
        private Line lineAtMouse() {
            if (!isHovering()) return null;
            final int mx = mouseX(), my = mouseY();
            for (final Line l : sheet.lines) {
                if (l.control() != null && mx >= l.x() && mx < l.x() + COL && my >= l.y() && my < l.y() + ROW) return l;
            }
            return null;
        }

        // region Drawing

        @Override
        public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
            final RecipeCard card = card();
            final CardModel m = card == null ? null : card.model();
            if (m == null) return;
            final Line hot = lineAtMouse();
            final int mx = mouseX();
            final boolean none = sheet.lines.isEmpty() && !sheet.formulas.isEmpty();
            final int fy = none ? sheet.h - 2 : sheet.h - Sheet.foot(sheet.columns) - 2;
            if (sheet.columns > 1) {
                final int divider = sheet.formulas.isEmpty() ? COL : sheet.formulasX - 1;
                Hyb.rect(divider, HEAD + 2, 1, fy - HEAD - 4, CardPaint.HAIR);
            }
            for (final Line l : sheet.lines) {
                if (l.control() == null) title(l.heading(), l.x(), l.y(), COL);
                else row(m, l, l == hot, mx - l.x());
            }
            if (!sheet.formulas.isEmpty()) formulas(sheet.formulas, sheet.formulasX, HEAD, sheet.formulasW);
            if (none) {
                // Nothing to set: the section says so, and there is nothing to say about setting it.
                title("Settings", 0, HEAD, sheet.formulasX);
                Hyb.text(NONE, 8, HEAD + HEADING + 3, Hyb.MUTED);
                return;
            }
            Hyb.rect(1, fy, sheet.w - 2, 1, CardPaint.HAIR);
            if (sheet.columns == 1) {
                Hyb.text("Pinned settings show on the card.", 8, fy + 5, 0xFF6E7179);
                Hyb.text("Click, right-click or scroll to change.", 8, fy + 15, 0xFF6E7179);
            } else Hyb.text(
                "Pinned settings show on the card. Click, right-click or scroll to change.",
                8,
                fy + 5,
                0xFF6E7179);
        }

        // region Formulas

        /** Each setting's number in a colour of its own, as on the website's cards. */
        private static final java.util.Map<String, Integer> KNOB_COLORS = java.util.Map.ofEntries(
            java.util.Map.entry("tier", 0xFF5AA7D9),
            java.util.Map.entry("amps", 0xFF5AA7D9),
            java.util.Map.entry("coil", 0xFFE0904A),
            java.util.Map.entry("tgsLogTool", 0xFFC9A24A),
            java.util.Map.entry("tgsSaplingTool", 0xFF6FBF50),
            java.util.Map.entry("tgsLeavesTool", 0xFF3FB08A),
            java.util.Map.entry("tgsFruitTool", 0xFFE06060),
            java.util.Map.entry("tgsHeight", 0xFFB06FD9),
            java.util.Map.entry("tgsSaplings", 0xFFB06FD9),
            java.util.Map.entry("tgsYield", 0xFFB06FD9),
            java.util.Map.entry("bioVatFill", 0xFF5AA7D9),
            java.util.Map.entry("bioVatOutputHatch", 0xFFC9A24A),
            java.util.Map.entry("bioVatGlass", 0xFF9FD3E6),
            java.util.Map.entry("bioVatRadio", 0xFF7FD94A),
            java.util.Map.entry("bioVatShutter", 0xFFE0A060),
            // A crop's: its stats and surroundings, then the farm's units in the website's pip colours.
            java.util.Map.entry("cropGrowthStat", 0xFF7FD94A),
            java.util.Map.entry("cropGainStat", 0xFFE0C060),
            java.util.Map.entry("cropWater", 0xFF5AA7D9),
            java.util.Map.entry("cropFertilizer", 0xFFC9A24A),
            java.util.Map.entry("cropIfFertilized", 0xFFC9A24A),
            java.util.Map.entry("cropSky", 0xFF9FD3E6),
            java.util.Map.entry("cropBiome", 0xFF3FB08A),
            java.util.Map.entry("cropManagerTier", 0xFFB0B8C8),
            java.util.Map.entry("cropSeedBedTier", 0xFFB0B8C8),
            java.util.Map.entry("cropIfGrowthUnits", 0xFF6FBF50),
            java.util.Map.entry("cropIfFertilizerUnits", 0xFFC9A24A),
            java.util.Map.entry("cropIfHarvestUnits", 0xFF5AA7D9),
            java.util.Map.entry("cropIfEnvironmentUnits", 0xFFB06FD9),
            java.util.Map.entry("cropIfOverclocks", 0xFFE06060));
        private static final int FORMULA_TEXT = 0xFF8A8D96, LABEL_W = 62;

        /**
         * A section's title at the top of its column (a hairline above it when it sits under another section), in
         * sentence case: "Settings", "Formulas", "Readings".
         */
        private static void title(final String text, final int x, final int y, final int w) {
            if (y > HEAD) Hyb.rect(x + 8, y + 1, w - 16, 1, CardPaint.HAIR);
            final String lower = text.toLowerCase(java.util.Locale.ROOT);
            Hyb.text(
                lower.isEmpty() ? lower : Character.toUpperCase(lower.charAt(0)) + lower.substring(1),
                x + 8,
                y + 5,
                TITLE);
        }

        /**
         * The worked formulas, a column of their own: a heading, then each line's label, its working right-aligned into
         * one column, and its answer bright beside it (green or red for a requirement). The game's font has no ceiling
         * or floor brackets and no minus sign: those are written out; its middle dot is wide, so a small one is drawn.
         */
        private void formulas(final List<com.gtnhplanner.machines.FormulaLine> lines, final int x, final int y,
            final int w) {
            title("Formulas", x, y, w);
            final int answersW = answersWidth(lines), answerX = x + w - 8 - answersW;
            int ly = y + HEADING;
            for (int i = 0; i < lines.size(); i++) {
                final com.gtnhplanner.machines.FormulaLine l = lines.get(i);
                final Laid laid = lay(l, w, answersW);
                // A faint band behind every other line, so a long working is easy to follow to its answer.
                if (i % 2 == 1) Hyb.rect(x + 4, ly - 2, w - 8, laid.rows() * FORMULA, 0x08FFFFFF);
                Hyb.text(Hyb.fit(l.label(), LABEL_W - 4), x + 8, ly, 0xFF6E7179);
                for (int k = 0; k < l.math()
                    .size(); k++)
                    term(
                        l.math()
                            .get(k),
                        x + 8 + LABEL_W + laid.dx()[k],
                        ly + laid.row()[k] * FORMULA);
                final int answer = switch (l.tone()) {
                    case GOOD -> 0xFF7FD94A;
                    case BAD -> 0xFFE06060;
                    default -> Hyb.INK;
                };
                // The answer on the working's last row, or on a row of its own under a working too long to share one.
                final int ay = ly + (laid.rows() - 1) * FORMULA;
                // A line with no working (a count) shows its answer alone, in the answers' column.
                if (!l.math()
                    .isEmpty()) Hyb.text("=", answerX, ay, FORMULA_TEXT);
                Hyb.text(l.result(), answerX + Hyb.width("= "), ay, answer);
                ly += laid.rows() * FORMULA;
            }
        }

        /**
         * Where a line's working goes, from the working column's left: each term's offset and row, and how many rows
         * the line takes. A working that fits sits right-aligned against its answer on one row; a longer one starts
         * at the left, runs to the column's edge (breaking between terms), and its answer takes the row under it.
         */
        record Laid(int[] dx, int[] row, int rows) {}

        static Laid lay(final com.gtnhplanner.machines.FormulaLine l, final int w, final int answersW) {
            final int n = l.math()
                .size();
            final int[] dx = new int[n], row = new int[n];
            final int oneRow = w - 8 - answersW - 6 - 8 - LABEL_W, total = mathWidth(l);
            if (total <= oneRow) {
                int at = oneRow - total;
                for (int k = 0; k < n; k++) {
                    dx[k] = at;
                    at += termWidth(
                        l.math()
                            .get(k));
                }
                return new Laid(dx, row, 1);
            }
            final int full = w - 8 - 8 - LABEL_W;
            int at = 0, r = 0;
            for (int k = 0; k < n; k++) {
                final int tw = termWidth(
                    l.math()
                        .get(k));
                if (at > 0 && at + tw > full) {
                    r++;
                    at = 0;
                }
                dx[k] = at;
                row[k] = r;
                at += tw;
            }
            return new Laid(dx, row, r + 2);
        }

        /** The formulas' height: the heading, then each line's rows. */
        static int formulasHeight(final List<com.gtnhplanner.machines.FormulaLine> lines, final int w) {
            final int answersW = answersWidth(lines);
            int rows = 0;
            for (final com.gtnhplanner.machines.FormulaLine l : lines) rows += lay(l, w, answersW).rows();
            return HEADING + rows * FORMULA + 4;
        }

        /** The column the formulas need: label, the widest working and the widest answer, within limits. */
        static int formulasWidth(final List<com.gtnhplanner.machines.FormulaLine> lines) {
            int math = 0;
            for (final com.gtnhplanner.machines.FormulaLine l : lines) math = Math.max(math, mathWidth(l));
            return Math.max(FORMULAS_MIN, Math.min(FORMULAS_MAX, 8 + LABEL_W + math + 6 + answersWidth(lines) + 8));
        }

        private static int answersWidth(final List<com.gtnhplanner.machines.FormulaLine> lines) {
            int w = 0;
            for (final com.gtnhplanner.machines.FormulaLine l : lines) w = Math.max(w, Hyb.width("= " + l.result()));
            return w;
        }

        private static int mathWidth(final com.gtnhplanner.machines.FormulaLine l) {
            int w = 0;
            for (final com.gtnhplanner.machines.FormulaLine.Term term : l.math()) w += termWidth(term);
            return w;
        }

        private static String glyphs(final String s) {
            return s.replace("\u2212", "-")
                .replace("\u2308", "ceil(")
                .replace("\u2309", ")")
                .replace("\u230A", "floor(")
                .replace("\u230B", ")");
        }

        /** The small middle dot: a pixel with a pixel of room either side. */
        private static final int DOT_W = 3;
        private static final String DOT = "\u00B7", SQUARED = "\u00B2";

        private static int termWidth(final com.gtnhplanner.machines.FormulaLine.Term term) {
            if (term.sup())
                return "2".equals(term.text()) ? Hyb.width(SQUARED) : (int) Math.ceil(Hyb.width(term.text()) * 0.6f);
            final String[] parts = glyphs(term.text()).split(DOT, -1);
            int w = 0;
            for (final String p : parts) w += Hyb.width(p);
            return w + (parts.length - 1) * DOT_W;
        }

        /** Draws a term at x; returns its width. A squared is the font's own superscript two; others are small. */
        private static int term(final com.gtnhplanner.machines.FormulaLine.Term term, final int x, final int y) {
            final int color = term.knob() != null ? KNOB_COLORS.getOrDefault(term.knob(), Hyb.INK) : FORMULA_TEXT;
            if (term.sup() && !"2".equals(term.text())) {
                org.lwjgl.opengl.GL11.glPushMatrix();
                org.lwjgl.opengl.GL11.glTranslatef(x, y - 1, 0);
                org.lwjgl.opengl.GL11.glScalef(0.6f, 0.6f, 1);
                Hyb.text(term.text(), 0, 0, color);
                org.lwjgl.opengl.GL11.glPopMatrix();
                return termWidth(term);
            }
            if (term.sup()) {
                Hyb.text(SQUARED, x, y, color);
                return Hyb.width(SQUARED);
            }
            final String[] parts = glyphs(term.text()).split(DOT, -1);
            int at = x;
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    Hyb.rect(at + 1, y + 3, 1, 1, color);
                    at += DOT_W;
                }
                Hyb.text(parts[i], at, y, color);
                at += Hyb.width(parts[i]);
            }
            return at - x;
        }

        // endregion

        private void row(final CardModel m, final Line l, final boolean hot, final int mx) {
            final SettingControls.Control c = l.control();
            final int x = l.x(), y = l.y();
            if (hot && c.enabled()) Hyb.rect(x + 1, y, COL - 2, ROW, 0x0DFFFFFF);
            final boolean pinned = SettingPins.pinned(session.graph(), m, c);
            pinGlyph(x + 6, y + 4, pinned ? Hyb.LIT : hot && mx < 16 ? Hyb.INK : hot ? Hyb.MUTED : 0xFF44474F);
            final int[] box = controlBox(c);
            int lx = x + 18;
            // A number's icon (a farm unit's block) goes before its name; a list shows the one picked in its box.
            if (c.icon() != null && c.type() == SettingControls.Type.NUMBER) {
                Hyb.item(c.icon(), lx, y + 3, 12, 0);
                org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_LIGHTING);
                org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
                lx += 16;
            }
            Hyb.text(Hyb.fit(c.label(), x + box[0] - lx - 6), lx, y + 5, c.enabled() ? Hyb.INK : 0xFF5A5C65);
            control(c, x, y, hot, mx);
        }

        /** The control at the row's right, column-local: {x, w}, by type. */
        private static int[] controlBox(final SettingControls.Control c) {
            final int right = COL - 8;
            final int w = switch (c.type()) {
                case TOGGLE -> 22;
                case READING -> Hyb.width(c.value());
                case NUMBER -> Math.max(52, Hyb.width(c.value()) + 28);
                // A modelled machine's choices name a tool or material and its multiplier: room for both.
                default -> Math.min(
                    c.key()
                        .startsWith(SettingControls.MACHINE_PREFIX) ? 132 : 110,
                    Math.max(56, (c.icon() != null ? 15 : 0) + Hyb.width(c.value()) + 20));
            };
            return new int[] { right - w, w };
        }

        private void control(final SettingControls.Control c, final int lx, final int y, final boolean hot,
            final int mx) {
            final int[] box = controlBox(c);
            final int x = lx + box[0], w = box[1];
            final int cx = mx - box[0];
            final boolean live = c.enabled();
            switch (c.type()) {
                case TOGGLE -> {
                    final boolean on = "On".equals(c.value());
                    Hyb.roundRect(x, y + 4, 22, 10, 5, !live ? 0xFF2C2E33 : on ? 0xFF3FAE5C : 0xFF3A3C42);
                    Hyb.roundRect(on ? x + 13 : x + 1, y + 5, 8, 8, 4, live ? 0xFFF2F3F5 : 0xFF6E7179);
                }
                case READING -> Hyb.text(c.value(), x, y + 5, Hyb.INK);
                case NUMBER -> {
                    well(x, y, w, hot && live && cx >= 11 && cx < w - 11);
                    arrow(x + 4, y + 7, false, !live ? 0xFF44474F : hot && cx >= 0 && cx < 11 ? Hyb.INK : Hyb.MUTED);
                    arrow(
                        x + w - 7,
                        y + 7,
                        true,
                        !live ? 0xFF44474F : hot && cx >= w - 11 && cx < w ? Hyb.INK : Hyb.MUTED);
                    Hyb.textCentered(c.value(), x + w / 2f, y + 5, live ? Hyb.INK : 0xFF74767E);
                }
                default -> {
                    well(x, y, w, hot && live && cx >= 0);
                    int tx = x + 5;
                    if (c.icon() != null) {
                        Hyb.item(c.icon(), tx, y + 3, 12, 0);
                        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_LIGHTING);
                        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
                        tx += 15;
                    }
                    Hyb.text(
                        Hyb.fit(c.value(), x + w - 12 - tx),
                        tx,
                        y + 5,
                        c.warn() ? Hyb.RED_INK : live ? Hyb.INK : 0xFF74767E);
                    chevronDown(x + w - 9, y + 8, live ? Hyb.MUTED : 0xFF44474F);
                }
            }
        }

        private static void well(final int x, final int y, final int w, final boolean hot) {
            Hyb.rect(x, y + 2, w, ROW - 4, CardPaint.EDGE);
            Hyb.rect(x + 1, y + 3, w - 2, ROW - 6, hot ? 0xFF2E3036 : 0xFF1B1D21);
        }

        private static void arrow(final int x, final int y, final boolean right, final int color) {
            for (int i = 0; i < 3; i++) {
                final int len = 5 - 2 * i;
                Hyb.rect(right ? x + i : x + 2 - i, y + i, 1, len, color);
            }
        }

        private static void chevronDown(final int x, final int y, final int color) {
            Hyb.rect(x, y, 5, 1, color);
            Hyb.rect(x + 1, y + 1, 3, 1, color);
            Hyb.rect(x + 2, y + 2, 1, 1, color);
        }

        /** A pushpin, 7 wide and 10 tall. */
        private static void pinGlyph(final int x, final int y, final int color) {
            Hyb.rect(x + 1, y, 5, 1, color);
            Hyb.rect(x + 2, y + 1, 3, 2, color);
            Hyb.rect(x + 1, y + 3, 5, 1, color);
            Hyb.rect(x, y + 4, 7, 1, color);
            Hyb.rect(x + 3, y + 5, 1, 4, color);
        }

        // endregion

        // region Input

        @Override
        public Result onMousePressed(final int mouseButton) {
            final Line l = lineAtMouse();
            final RecipeCard card = card();
            final CardModel m = card == null ? null : card.model();
            if (l == null || m == null || mouseButton > 1) return Result.ACCEPT;
            final SettingControls.Control c = l.control();
            final int mx = mouseX() - l.x();
            if (mx < 16) {
                if (mouseButton == 0) {
                    SettingPins.toggle(session.graph(), m, c);
                    Hyb.click();
                }
                return Result.SUCCESS;
            }
            if (!c.enabled() || c.type() == SettingControls.Type.READING) return Result.ACCEPT;
            final int[] box = controlBox(c);
            final int sx = getArea().x + l.x() + box[0], sy = getArea().y + l.y() + ROW;
            if (c.type() == SettingControls.Type.NUMBER && mouseButton == 0) {
                if (mx >= box[0] && mx < box[0] + 11) card.stepSetting(c, -1);
                else if (mx >= box[0] + box[1] - 11 && mx < box[0] + box[1]) card.stepSetting(c, 1);
                else card.pressSetting(c, 0, sx, sy);
            } else card.pressSetting(c, mouseButton, sx, sy);
            Hyb.click();
            return Result.SUCCESS;
        }

        @Override
        public boolean onMouseScroll(final UpOrDown direction, final int amount) {
            final Line l = lineAtMouse();
            final RecipeCard card = card();
            if (l == null || card == null) return false;
            final SettingControls.Control c = l.control();
            if (!c.enabled() || c.type() == SettingControls.Type.READING) return false;
            card.stepSetting(c, direction == UpOrDown.UP ? 1 : -1);
            return true;
        }

        // endregion
    }
}
