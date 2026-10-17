package com.gtnhplanner.ui.note;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ChatAllowedCharacters;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.IDraggable;
import com.cleanroommc.modularui.api.widget.IFocusedWidget;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widget.sizer.Area;
import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.canvas.BoardCanvas;
import com.gtnhplanner.ui.note.DrawingGrips.Grip;
import com.gtnhplanner.ui.popup.PickList;
import com.gtnhplanner.ui.popup.Popup;
import com.gtnhplanner.ui.popup.Tip;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * A sticky note on the board: coloured paper with its corner folded, the text wrapped on it. Drag it to move it (with
 * the selection), drag its folded corner or its right or bottom edge to resize it, double-click it to write on it.
 * Hovering shows its keys: delete at its top left, smaller and bigger text at its top right; a right-click offers
 * editing, the colour, the text size and delete. Notes take no part in the solve or the wiring: they sit over the wires
 * and under the cards.
 *
 * <p>
 * A box, zone or arrow (a drawing) is the same widget with a few more holds ({@link DrawingGrips}): a box moves by its
 * border and resizes by its corners and edges, an arrow moves by its line and is reshaped by its end handles, and a
 * right-click offers its colour and style. Its widget reaches {@link DrawingGrips#PAD} outside it so those can be
 * held.
 */
public final class NoteCard extends Widget<NoteCard> implements Interactable, IDraggable, IFocusedWidget {

    public enum Part {
        BODY,
        DELETE,
        SMALLER,
        BIGGER,
        RESIZE
    }

    /** The folded corner, at most; it shrinks on a small note. */
    private static final int FOLD = 14;
    /** How deep the edges that resize reach in, in screen pixels (kept however far out the board is). */
    private static final int EDGE = 10;
    /**
     * The keys (delete in the glued strip's left end, text size in its right end): their side, the gap between the
     * two on the right and their inset from the edge, at the board's own scale. Zoomed out they keep that size on
     * screen, so they can always be hit; they show whenever they fit on the note.
     */
    private static final int KEY = 9, KEY_GAP = 2, KEY_INSET = 3;
    /** The glued strip along the top, where the keys sit. */
    private static final int BAND = 11;
    /** The text's margin from the edges, and its first line's top. */
    private static final int PAD = 7, TEXT_TOP = BAND + 4;
    /** A line's height at the board's own text size (the font is 9 high; 14 px on the website). */
    private static final int LINE = 11;

    private final BoardSession session;
    public final UUID noteId;
    private boolean moving;

    public NoteCard(final BoardSession session, final UUID noteId) {
        this.session = session;
        this.noteId = noteId;
        final Note n = note();
        if (n != null) {
            final int pad = n.isDrawing() ? DrawingGrips.PAD : 0;
            pos(n.getX() - pad, n.getY() - pad);
            size(n.getWidth() + 2 * pad, n.getHeight() + 2 * pad);
        }
    }

    /** The note, looked up afresh: an undo puts a new graph in place. */
    @Nullable
    public Note note() {
        return session.graph().notes.get(noteId);
    }

    private BoardCanvas canvas() {
        // Once the board has rebuilt it away it has no parent to ask for (ModularUI throws): it is on no board.
        return isValid() && getParent() instanceof final BoardCanvas c ? c : null;
    }

    /** The place and size last given to the widget, so it is only told again when the note changes. */
    private int shownX = Integer.MIN_VALUE, shownY, shownW, shownH;

    @Override
    public void onUpdate() {
        super.onUpdate();
        sync();
    }

    /** Puts the widget where the note is, at its size (or the size it is being dragged or written to). */
    public void sync() {
        final Note n = note();
        if (n == null) return;
        final int w = resizing ? liveW : editing ? Math.max(n.getWidth(), editW) : n.getWidth();
        final int h = resizing ? liveH : editing ? Math.max(n.getHeight(), editH) : n.getHeight();
        if (shownX == n.getX() && shownY == n.getY() && shownW == w && shownH == h) return;
        shownX = n.getX();
        shownY = n.getY();
        shownW = w;
        shownH = h;
        final int pad = n.isDrawing() ? DrawingGrips.PAD : 0;
        pos(n.getX() - pad, n.getY() - pad);
        size(w + 2 * pad, h + 2 * pad);
        scheduleResize();
    }

    @Override
    public boolean canHover() {
        return true;
    }

    /**
     * A box or zone takes the pointer only at its border (and just outside it, to resize) and an arrow only at its
     * line and ends (the cards over and inside them keep every click); a note takes its whole face.
     */
    @Override
    public boolean isInside(final com.cleanroommc.modularui.api.layout.IViewportStack stack, final int mx, final int my,
        final boolean transform) {
        if (!super.isInside(stack, mx, my, transform)) return false;
        final Note n = note();
        if (n == null || !n.isDrawing()) return true;
        if (canvas() == null) return true;
        // The mouse as the rest of the card reads it (localX), which the hover pass is asking about.
        return DrawingGrips.at(n, n.getWidth(), n.getHeight(), localX(), localY()) != null;
    }

    // region Geometry

    private int w() {
        return getArea().width;
    }

    private int h() {
        return getArea().height;
    }

    private int fold() {
        return Math.max(6, Math.min(FOLD, Math.min(w(), h()) / 4));
    }

    private float localX() {
        final BoardCanvas c = canvas();
        final Note n = note();
        return c == null || n == null ? -1 : c.worldX(getContext().getAbsMouseX()) - n.getX();
    }

    private float localY() {
        final BoardCanvas c = canvas();
        final Note n = note();
        return c == null || n == null ? -1 : c.worldY(getContext().getAbsMouseY()) - n.getY();
    }

    public Part partAt(final float x, final float y) {
        final int w = w(), h = h(), f = fold();
        final Note self = note();
        if (self != null && self.isDrawing()) return Part.BODY;
        // The keys first: the text size key sits at the right edge, in the band that resizes.
        if (keysShown()) for (final Part key : KEYS) {
            final float[] r = keyRect(key);
            if (x >= r[0] && y >= r[1] && x < r[0] + r[2] && y < r[1] + r[3]) return key;
        }
        // The folded corner (and a margin round it) and a wide band along the right and bottom edges resize.
        final float edge = EDGE * keyUnit(), corner = f + edge;
        if (x >= w - corner && y >= h - corner) return Part.RESIZE;
        if (x >= w - edge || y >= h - edge) return Part.RESIZE;
        return Part.BODY;
    }

    private static final Part[] KEYS = { Part.DELETE, Part.SMALLER, Part.BIGGER };

    /** How many note units a screen pixel of a key takes: 1 at the board's own scale and closer, more zoomed out. */
    private float keyUnit() {
        return Math.max(
            1f,
            1f / session.graph()
                .getZoom());
    }

    /** A key's note-local rectangle {x, y, w, h}. */
    private float[] keyRect(final Part key) {
        final float u = keyUnit(), k = KEY * u, inset = KEY_INSET * u;
        return switch (key) {
            case DELETE -> new float[] { inset, u, k, k };
            case SMALLER -> new float[] { w() - 2 * k - KEY_GAP * u - inset, u, k, k };
            case BIGGER -> new float[] { w() - k - inset, u, k, k };
            default -> new float[] { 0, 0, 0, 0 };
        };
    }

    /** Whether the three keys fit on the note at their size, with room between the left one and the right two. */
    private boolean keysFit() {
        final float u = keyUnit();
        final Note self = note();
        return w() >= (3 * KEY + 2 * KEY_GAP + 2 * KEY_INSET) * u && h() >= (2 * KEY + 1) * u;
    }

    /** Note-local rectangle of a part, for the dev harness and the tour. */
    public int[] partRect(final Part part) {
        final int w = w(), h = h(), f = fold();
        return switch (part) {
            case BODY -> new int[] { 0, 0, w, h };
            case DELETE, SMALLER, BIGGER -> {
                final float[] r = keyRect(part);
                yield new int[] { Math.round(r[0]), Math.round(r[1]), Math.round(r[2]), Math.round(r[3]) };
            }
            case RESIZE -> new int[] { w - f, h - f, f, f };
        };
    }

    public Part partUnderMouse() {
        final float x = localX(), y = localY();
        return x < 0 || y < 0 || x >= w() || y >= h() ? null : partAt(x, y);
    }

    /** The keys show while the mouse is on the note or it is being written on, at any zoom, when they fit on it. */
    private boolean keysShown() {
        return (isHovering() || editing) && keysFit();
    }

    // endregion

    // region Text layout

    private float scale(final Note n) {
        return n.fontSizeOrDefault() / (float) Note.DEFAULT_FONT;
    }

    private NoteText layout;
    private String layoutText;
    private float layoutWidth;

    /** The text laid out for the note's width at its text size, kept until either changes. */
    private NoteText layout(final String text, final float width) {
        if (layout == null || !text.equals(layoutText) || width != layoutWidth) {
            layout = NoteText.of(text, width, Hyb::width);
            layoutText = text;
            layoutWidth = width;
        }
        return layout;
    }

    /** The width the text wraps at, in the font's own units. */
    private float wrapWidth(final Note n, final int w) {
        return Math.max(10, (w - 2 * PAD) / scale(n));
    }

    // endregion

    // region Drawing

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final Note n = note();
        if (n == null) return;
        if (n.isDrawing()) {
            drawDrawing(n);
            return;
        }
        final String tag = n.colorTag();
        final int w = w(), h = h(), f = fold();
        final BoardCanvas board = canvas();
        final boolean carried = board != null && board.isCarried(noteId);
        final int paper = NoteColors.paper(tag), ink = NoteColors.ink(tag);

        Hyb.beginBatch();
        // A soft shadow, lifted further while carried.
        final int lift = carried ? 3 : 0;
        for (int i = 2; i >= 0; i--) {
            final float g = i * 1.5f;
            Hyb.rect(2 - g + lift, 3 - g + lift * 1.3f, w + 2 * g - f / 2f, h + 2 * g - f / 2f, 0x16000000);
        }
        if (session.showsSelected(noteId)) Hyb.ring(
            0,
            0,
            w,
            h,
            Math.max(
                1,
                1.5f / session.graph()
                    .getZoom()),
            Hyb.SELECTION);
        // The paper, its corner folded over: the flap darker, the corner past it gone.
        Hyb.rect(0, 0, w, h - f, paper);
        Hyb.rect(0, h - f, w - f, f, paper);
        Hyb.triangle(w - f, h - f, w, h - f, w - f, h, NoteColors.fold(tag));
        Hyb.rect(0, 0, w, BAND, NoteColors.band(tag));
        Hyb.rect(0, h - 1, w - f, 1, NoteColors.edge(tag));
        Hyb.rect(w - 1, 0, 1, h - f, NoteColors.edge(tag));
        if (isHovering()) {
            // The grip: three short strokes on the fold, along its edge.
            final int grip = Hyb.mix(ink, NoteColors.fold(tag), 0.4f);
            for (int a = 4; a < f; a += 3)
                for (int t = 1; t < a - 1; t++) Hyb.rect(w - f + a - t, h - f + t, 1, 1, grip);
        }
        Hyb.endBatch();

        final float s = scale(n);
        final String text = editing ? draft : n.joined();
        final NoteText lay = layout(text, wrapWidth(n, w));
        final float lineH = LINE * s;
        final float bottom = h - Math.max(4, f / 2f);
        if (editing && hasSelection()) {
            // The selection, line by line, under the text.
            final int a = Math.min(caret, anchor), b = Math.max(caret, anchor);
            for (int i = 0; i < lay.lines()
                .size(); i++) {
                final NoteText.Line l = lay.lines()
                    .get(i);
                final int from = Math.max(a, l.start()), to = Math.min(b, l.end());
                if (from > to || from == to && !(a <= l.start() && b > l.end())) continue;
                final float y = TEXT_TOP + i * lineH;
                if (y + 9 * s > bottom) break;
                final float x0 = PAD + Hyb.width(text.substring(l.start(), from)) * s;
                final float x1 = PAD + Hyb.width(text.substring(l.start(), to)) * s;
                Hyb.rect(x0, y - 1, Math.max(2, x1 - x0), lineH, 0x40000000 | ink & 0xFFFFFF);
            }
        }
        if (text.isEmpty() && !editing) {
            Hyb.text("Double-click to write", PAD, TEXT_TOP, s, 0x90000000 | ink & 0xFFFFFF, false);
        } else {
            for (int i = 0; i < lay.lines()
                .size(); i++) {
                final float y = TEXT_TOP + i * lineH;
                if (y + 9 * s > bottom) break;
                Hyb.text(lay.shown(i), PAD, y, s, ink, false);
            }
        }
        if (editing && (System.currentTimeMillis() - caretSince) % 1000 < 600) {
            final int line = lay.lineOf(caret);
            final float y = TEXT_TOP + line * lineH;
            if (y + 9 * s <= bottom) Hyb.rect(PAD + lay.xOf(caret) * s, y - 1, Math.max(1, s), 10 * s, ink);
        }
        if (keysShown()) {
            drawKey(Part.DELETE, true);
            drawKey(Part.SMALLER, n.fontSizeOrDefault() > Note.MIN_FONT);
            drawKey(Part.BIGGER, n.fontSizeOrDefault() < Note.MAX_FONT);
        }
    }

    /**
     * A box, zone or arrow: its own look; the outline round it only with several selected; its handles (a box's
     * corners, an arrow's ends) while it is the one selected, an arrow's also while hovered.
     */
    private void drawDrawing(final Note n) {
        final int w = n.getWidth(), h = n.getHeight(), pad = DrawingGrips.PAD;
        GL11.glPushMatrix();
        GL11.glTranslatef(pad, pad, 0);
        DrawingPaint.draw(n, w, h);
        Hyb.beginBatch();
        if (session.showsSelected(noteId)) Hyb.ring(
            -2,
            -2,
            w + 4,
            h + 4,
            Math.max(
                1,
                1f / session.graph()
                    .getZoom()),
            Hyb.SELECTION);
        final boolean alone = session.isSelected(noteId) && session.selection()
            .size() == 1 && !com.gtnhplanner.ui.canvas.PlanPicture.drawing();
        final Grip lit = isHovering() ? DrawingGrips.at(n, w, h, localX(), localY()) : null;
        final float side = 6 * keyUnit();
        if (Note.ARROW.equals(n.kind())) {
            if (alone || isHovering() && !hideHandles()) {
                final float[] e = DrawingPaint.ends(n, w, h);
                handle(e[0], e[1], side, lit == Grip.TAIL || grabbed == Grip.TAIL);
                handle(e[2], e[3], side, lit == Grip.HEAD || grabbed == Grip.HEAD);
            }
        } else if (alone) {
            final boolean zone = Note.ZONE.equals(n.kind());
            for (final Grip corner : new Grip[] { Grip.TL, Grip.TR, Grip.BL, Grip.BR }) {
                if (zone && corner != Grip.BR) continue;
                final float cx = corner == Grip.TL || corner == Grip.BL ? 0 : w;
                final float cy = corner == Grip.TL || corner == Grip.TR ? 0 : h;
                handle(cx, cy, side, lit == corner || grabbed == corner);
            }
        }
        Hyb.endBatch();
        GL11.glPopMatrix();
    }

    /** Whether the handles stay hidden: while the drawing is carried they would only be in the way. */
    private boolean hideHandles() {
        final BoardCanvas c = canvas();
        return c != null && c.isCarried(noteId);
    }

    /** A handle: a small square in the selection colour with a dark edge, lit while held or pointed at. */
    private static void handle(final float cx, final float cy, final float side, final boolean lit) {
        Hyb.rect(cx - side / 2 - 1, cy - side / 2 - 1, side + 2, side + 2, 0xFF0E7490);
        Hyb.rect(cx - side / 2, cy - side / 2, side, side, lit ? 0xFFFFFFFF : Hyb.SELECTION);
    }

    /** A key: a small dark square with a cross, a minus or a plus, drawn in its own pixels however far out. */
    private void drawKey(final Part key, final boolean enabled) {
        final float[] r = keyRect(key);
        final float x = r[0], y = r[1], u = keyUnit();
        final boolean lit = partUnderMouse() == key && enabled;
        Hyb.rect(x, y, r[2], r[3], lit ? 0xE0303238 : 0xB0303238);
        final int c = enabled ? 0xFFF2F3F7 : 0x80F2F3F7;
        switch (key) {
            case DELETE -> {
                for (int i = 0; i < KEY - 4; i++) {
                    Hyb.rect(x + (2 + i) * u, y + (2 + i) * u, u, u, c);
                    Hyb.rect(x + (KEY - 3 - i) * u, y + (2 + i) * u, u, u, c);
                }
            }
            case SMALLER -> Hyb.rect(x + 2 * u, y + 4 * u, (KEY - 4) * u, u, c);
            default -> {
                Hyb.rect(x + 2 * u, y + 4 * u, (KEY - 4) * u, u, c);
                Hyb.rect(x + 4 * u, y + 2 * u, u, (KEY - 4) * u, c);
            }
        }
    }

    /** The tip for the part under the mouse; the paper itself only explains itself after a moment. */
    @Nullable
    public Tip tip() {
        if (editing) return null;
        final Note self = note();
        if (self != null && self.isDrawing()) {
            final Grip grip = DrawingGrips.at(self, self.getWidth(), self.getHeight(), localX(), localY());
            if (grip == null) return null;
            return switch (grip) {
                case TAIL, HEAD -> Tip.of("Arrow end")
                    .action(Tip.Input.DRAG, "Reshape");
                case BODY -> Tip.of(capital(kindWord(self)))
                    .action(Tip.Input.DRAG, "Move")
                    .action(Tip.Input.RIGHT, "Colour, style, delete");
                default -> Tip.of("Resize")
                    .action(Tip.Input.DRAG, "Drag the corner or edge");
            };
        }
        final Part part = partUnderMouse();
        if (part == null) return null;
        return switch (part) {
            case DELETE -> Tip.of("Delete note");
            case SMALLER -> Tip.of("Smaller text");
            case BIGGER -> Tip.of("Bigger text");
            case RESIZE -> Tip.of("Resize")
                .action(Tip.Input.DRAG, "Drag the corner or an edge");
            case BODY -> Tip.of("Sticky note")
                .action(Tip.Input.LEFT, "Click: write")
                .action(Tip.Input.DRAG, "Move")
                .action(Tip.Input.RIGHT, "Colour, text size, delete");
        };
    }

    private static String kindWord(final Note n) {
        return switch (n.kind()) {
            case Note.BOX -> "box";
            case Note.ZONE -> "zone";
            default -> "arrow";
        };
    }

    private static String capital(final String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // endregion

    // region Mouse

    /** The part a left press went down on: let go without moving, a click on it; moved, the note is carried. */
    private Part pressed;
    /** A drawing: what the press went down on, and what is being dragged (a corner, an edge, an arrow's end). */
    private Grip held, grabbed;
    private DrawingGrips.Rect gripFrom;
    private String gripDirection, gripUndo;
    private int gripMouseX, gripMouseY;
    private boolean gripMoved;

    @Override
    public Result onMousePressed(final int mouseButton) {
        final Note n = note();
        if (n == null) return Result.IGNORE;
        if (mouseButton == 1) {
            openMenu();
            return Result.SUCCESS;
        }
        if (mouseButton != 0) return Result.IGNORE;
        pressed = partAt(localX(), localY());
        held = n.isDrawing() ? DrawingGrips.at(n, n.getWidth(), n.getHeight(), localX(), localY()) : null;
        // ModularUI takes the focus away from a press that may become a drag; written on, the note keeps writing
        // through it and takes the focus back when the press ends.
        holdFocus = editing;
        return Result.ACCEPT;
    }

    /** A press on the note while it is written on: losing the focus to it does not end the writing. */
    private boolean holdFocus;

    /** Back to writing after a press while written on: the keys go to the note again. */
    private void regainFocus() {
        final boolean held = holdFocus;
        holdFocus = false;
        if (held && editing) getContext().focus(this);
    }

    /**
     * A click (a press let go without moving): a key does what it is for; anywhere else writes on the note, the caret
     * where it was clicked.
     */
    private void click(final Part part) {
        switch (part) {
            case DELETE -> {
                Hyb.click();
                // Let go of the keyboard first, words unsaved: the note is going.
                if (editing) {
                    editing = false;
                    getContext().removeFocus();
                }
                session.deleteNote(noteId);
            }
            case SMALLER -> stepFont(-1);
            case BIGGER -> stepFont(1);
            default -> {
                // A drawing is selected by a click, never written on.
                final Note self = note();
                if (self != null && self.isDrawing()) {
                    if (canvas() != null) canvas().clickSelect(noteId);
                    return;
                }
                // Shift adds it to the selection, as it does a card.
                if (!editing && GuiScreen.isShiftKeyDown()) {
                    if (canvas() != null) canvas().clickSelect(noteId);
                    return;
                }
                final boolean extend = editing && GuiScreen.isShiftKeyDown();
                if (!editing) startEditing();
                placeCaret(extend);
            }
        }
    }

    @Override
    public boolean onMouseScroll(final UpOrDown direction, final int amount) {
        return false;
    }

    /** Steps the text size by the website's step. */
    public void stepFont(final int steps) {
        final Note n = note();
        if (n == null) return;
        final int next = Math
            .max(Note.MIN_FONT, Math.min(Note.MAX_FONT, n.fontSizeOrDefault() + steps * Note.FONT_STEP));
        if (next == n.fontSizeOrDefault()) return;
        com.gtnhplanner.ui.sound.Sfx.TICK.play(steps > 0 ? 1.12f : 0.9f);
        session.editLayout(() -> n.setFontSize(next));
    }

    private void openMenu() {
        final Note n = note();
        final BoardCanvas board = canvas();
        if (n == null || board == null) return;
        final List<PickList.Entry> rows = new ArrayList<>();
        if (n.isDrawing()) {
            rows.add(PickList.Entry.of("Colour...", () -> onLive(board, NoteCard::openColours)));
            if (Note.ARROW.equals(n.kind())) {
                rows.add(
                    PickList.Entry.of("Thickness...", () -> onLive(board, c -> c.openChoices("Thickness", THICKNESS))));
                rows.add(PickList.Entry.of("Heads...", () -> onLive(board, c -> c.openChoices("Heads", HEADS))));
            } else {
                rows.add(PickList.Entry.of("Fill...", () -> onLive(board, c -> c.openChoices("Fill", FILL))));
                rows.add(PickList.Entry.of("Border...", () -> onLive(board, c -> c.openChoices("Border", BORDER))));
                rows.add(PickList.Entry.of("Marks...", () -> onLive(board, c -> c.openChoices("Marks", MARKS))));
            }
            rows.add(
                new PickList.Entry(
                    null,
                    "Delete " + kindWord(n),
                    "",
                    Hyb.RED_INK,
                    false,
                    () -> session.deleteNote(noteId)));
            Popup.open(
                session.panel(),
                PickList.popup("gtnhplanner_note", null, rows, false, 140),
                getContext().getAbsMouseX(),
                getContext().getAbsMouseY());
            return;
        }
        rows.add(PickList.Entry.of("Write on it", () -> onLive(board, NoteCard::startEditing)));
        rows.add(PickList.Entry.of("Colour...", () -> onLive(board, NoteCard::openColours)));
        rows.add(
            new PickList.Entry(null, "Bigger text", n.fontSizeOrDefault() + " px", Hyb.INK, false, () -> stepFont(1)));
        rows.add(PickList.Entry.of("Smaller text", () -> stepFont(-1)));
        rows.add(new PickList.Entry(null, "Delete note", "", Hyb.RED_INK, false, () -> session.deleteNote(noteId)));
        Popup.open(
            session.panel(),
            PickList.popup("gtnhplanner_note", null, rows, false, 140),
            getContext().getAbsMouseX(),
            getContext().getAbsMouseY());
    }

    /**
     * Runs on the note's widget as the board has it when a row of its menu is picked: the board may have built its
     * widgets afresh while the menu was open, and this one is then on no panel (ModularUI throws when asked for it).
     */
    private void onLive(final BoardCanvas board, final java.util.function.Consumer<NoteCard> action) {
        final NoteCard live = board.notes()
            .get(noteId);
        if (live != null && live.isValid()) action.accept(live);
    }

    /** One style choice: what it is called and the value it sets. */
    private record Choice(String label, String value) {}

    /** A box's or arrow's style: which property the choices set. */
    private enum Style {
        FILL,
        BORDER,
        MARKS,
        THICKNESS,
        HEADS
    }

    private record Choices(Style style, List<Choice> items) {}

    private static final Choices FILL = new Choices(
        Style.FILL,
        List.of(new Choice("Tint", "tint"), new Choice("Solid", "solid"), new Choice("None", "none")));
    private static final Choices BORDER = new Choices(
        Style.BORDER,
        List.of(new Choice("Solid", "solid"), new Choice("Dashed", "dashed")));
    private static final Choices MARKS = new Choices(
        Style.MARKS,
        List.of(
            new Choice("None", "none"),
            new Choice("Dots", "dots"),
            new Choice("Grid", "grid"),
            new Choice("Graph", "graph"),
            new Choice("Ruled", "ruled"),
            new Choice("Hatch", "hatch")));
    private static final Choices THICKNESS = new Choices(
        Style.THICKNESS,
        List.of(new Choice("Thin", "1"), new Choice("Normal", "2"), new Choice("Thick", "4")));
    private static final Choices HEADS = new Choices(
        Style.HEADS,
        List.of(new Choice("One end", "end"), new Choice("Both ends", "both")));

    /** What the note has now for a style, as the value a choice sets. */
    private static String current(final Note n, final Style style) {
        return switch (style) {
            case FILL -> n.fillStyle();
            case BORDER -> n.borderStyle();
            case MARKS -> n.marksStyle();
            case THICKNESS -> String.valueOf(n.arrowThickness());
            case HEADS -> n.bothHeads() ? "both" : "end";
        };
    }

    /** Sets a style. */
    private static void apply(final Note n, final Style style, final String value) {
        switch (style) {
            case FILL -> n.setFill(value);
            case BORDER -> n.setBorder(value);
            case MARKS -> n.setMarks(value);
            case THICKNESS -> n.setArrowWidth(Integer.parseInt(value));
            case HEADS -> n.setArrowHeads(value);
        }
    }

    /** A short list of one style's choices, the one in force ticked; picking one sets it as one undoable step. */
    private void openChoices(final String title, final Choices choices) {
        final Note n = note();
        if (n == null) return;
        final String now = current(n, choices.style());
        final List<PickList.Entry> rows = new ArrayList<>();
        for (final Choice c : choices.items()) rows.add(
            new PickList.Entry(
                null,
                c.label(),
                "",
                Hyb.INK,
                c.value()
                    .equals(now),
                () -> {
                    com.gtnhplanner.ui.sound.Sfx.ADJUST.play();
                    session.editLayout(() -> apply(n, choices.style(), c.value()));
                }));
        Popup.open(
            session.panel(),
            PickList.popup("gtnhplanner_note_style", title, rows, false, 120),
            getContext().getAbsMouseX(),
            getContext().getAbsMouseY());
    }

    private void openColours() {
        final Note n = note();
        if (n == null) return;
        final List<PickList.Entry> rows = new ArrayList<>();
        for (final String[] c : NoteColors.MENU) rows
            .add(new PickList.Entry(null, "■ " + c[1], "", NoteColors.paper(c[0]), c[0].equals(n.colorTag()), () -> {
                com.gtnhplanner.ui.sound.Sfx.ADJUST.play();
                session.editLayout(() -> {
                    n.setColor(c[0]);
                    if (n.isDrawing()) {
                        n.setBorderColor(null);
                        n.setFillColor(null);
                    }
                });
            }));
        Popup.open(
            session.panel(),
            PickList.popup("gtnhplanner_note_colour", "Colour", rows, false, 120, NoteColors.MENU.size()),
            getContext().getAbsMouseX(),
            getContext().getAbsMouseY());
    }

    // endregion

    // region Moving and resizing

    private boolean resizing, resizeW, resizeH;
    private int liveW, liveH, startW, startH, startMouseX, startMouseY;
    private String resizeUndo;

    @Override
    public boolean onDragStart(final int button) {
        final Note n = note();
        if (button != 0 || n == null || canvas() == null) return false;
        final float x = localX(), y = localY();
        if (n.isDrawing()) {
            // A corner, an edge or an arrow's end: held, it reshapes the drawing; the rest of it carries it.
            if (held != null && held != Grip.BODY) {
                grabbed = held;
                gripFrom = new DrawingGrips.Rect(n.getX(), n.getY(), n.getWidth(), n.getHeight());
                gripDirection = n.getArrowDirection();
                gripPoints = n.getPoints();
                gripMouseX = getContext().getAbsMouseX();
                gripMouseY = getContext().getAbsMouseY();
                gripMoved = false;
                gripUndo = com.gtnhplanner.api.PlanAPI.undoHistory(session.graph())
                    .beginEdit(session.graph());
                if (!session.isSelected(noteId) || session.selection()
                    .size() > 1) session.select(noteId, false);
                return true;
            }
            canvas().beginMove(noteId);
            return true;
        }
        if (partAt(x, y) == Part.RESIZE) {
            resizing = true;
            // As partAt decides: the corner and its margin take both ways, an edge's band its own way.
            final float edge = EDGE * keyUnit(), reach = fold() + edge;
            final boolean corner = x >= w() - reach && y >= h() - reach;
            resizeW = corner || x >= w() - edge;
            resizeH = corner || y >= h() - edge;
            startW = liveW = n.getWidth();
            startH = liveH = n.getHeight();
            startMouseX = getContext().getAbsMouseX();
            startMouseY = getContext().getAbsMouseY();
            resizeUndo = com.gtnhplanner.api.PlanAPI.undoHistory(session.graph())
                .beginEdit(session.graph());
            return true;
        }
        // Pressed anywhere else (a key too, even while written on), it may be moved; let go first, it is a click.
        canvas().beginMove(noteId);
        return true;
    }

    @Override
    public void onDrag(final int mouseButton, final long timeSinceLastClick) {
        if (grabbed != null) {
            dragGrip();
            return;
        }
        if (resizing) {
            final float zoom = session.graph()
                .getZoom();
            final Note self = note();
            final boolean drawing = self != null && self.isDrawing();
            final int minW = drawing ? Note.DRAWING_MIN : Note.MIN_W, minH = drawing ? Note.DRAWING_MIN : Note.MIN_H;
            if (resizeW)
                liveW = Math.max(minW, BoardSession.snap(startW + (getContext().getAbsMouseX() - startMouseX) / zoom));
            if (resizeH)
                liveH = Math.max(minH, BoardSession.snap(startH + (getContext().getAbsMouseY() - startMouseY) / zoom));
            if (drawing && Note.IMAGE.equals(self.kind())) {
                // A picture keeps its proportions: the way the corner went further decides.
                final float sx = liveW / (float) startW, sy = liveH / (float) startH;
                if (sx >= sy) liveH = Math.max(minH, BoardSession.snap(startH * sx));
                else liveW = Math.max(minW, BoardSession.snap(startW * sy));
            }
            sync();
            return;
        }
        if (canvas() != null) canvas().dragMove();
    }

    @Override
    public void onDragEnd(final boolean successful) {
        if (grabbed != null) {
            endGrip(successful);
            return;
        }
        if (resizing) {
            resizing = false;
            final Note n = note();
            if (n != null && (liveW != startW || liveH != startH)) {
                // A zone's outline grows with it.
                if (n.getPoints() != null && startW > 0 && startH > 0) {
                    final List<int[]> scaled = new ArrayList<>();
                    for (final int[] p : n.getPoints()) scaled.add(
                        new int[] { Math.round(p[0] * (liveW / (float) startW)),
                            Math.round(p[1] * (liveH / (float) startH)) });
                    n.setPoints(scaled);
                }
                n.setWidth(liveW);
                n.setHeight(liveH);
                // Written on, it stays the size it was dragged to.
                editW = liveW;
                editH = liveH;
                com.gtnhplanner.api.PlanAPI.undoHistory(session.graph())
                    .commitEdit(resizeUndo, session.graph());
                session.graph()
                    .touchLayout();
                com.gtnhplanner.api.PlanAPI.save();
            }
            resizeUndo = null;
            pressed = null;
            regainFocus();
            // A press on the edge that never went anywhere is a click on the note.
            if (Math.max(
                Math.abs(getContext().getAbsMouseX() - startMouseX),
                Math.abs(getContext().getAbsMouseY() - startMouseY)) < 3 && n != null) click(Part.BODY);
            return;
        }
        final Part part = pressed;
        pressed = null;
        regainFocus();
        if (canvas() != null && canvas().endMove(successful) && part != null && note() != null) click(part);
    }

    /** A zone's outline as it was when the grip was taken, so scaling never compounds. */
    private List<int[]> gripPoints;

    /** The drawing as the pointer has it now: the note itself changes as it goes, the undo step comes at the end. */
    private void dragGrip() {
        final Note n = note();
        if (n == null) return;
        final float zoom = session.graph()
            .getZoom();
        final int mx = getContext().getAbsMouseX() - gripMouseX, my = getContext().getAbsMouseY() - gripMouseY;
        gripMoved |= Math.max(Math.abs(mx), Math.abs(my)) >= 3;
        if (!gripMoved) return;
        final float dx = mx / zoom, dy = my / zoom;
        if (grabbed == Grip.TAIL || grabbed == Grip.HEAD) {
            final Note was = new Note();
            was.setArrowDirection(gripDirection);
            final DrawingGrips.Shape s = DrawingGrips.reshaped(was, gripFrom, grabbed, dx, dy);
            n.setX(
                s.box()
                    .x());
            n.setY(
                s.box()
                    .y());
            n.setWidth(
                s.box()
                    .w());
            n.setHeight(
                s.box()
                    .h());
            n.setArrowDirection(s.direction());
        } else {
            final DrawingGrips.Rect r = DrawingGrips.resized(gripFrom, grabbed, dx, dy);
            n.setX(r.x());
            n.setY(r.y());
            // A zone's outline grows with it.
            if (gripPoints != null && gripFrom.w() > 0 && gripFrom.h() > 0) {
                final List<int[]> scaled = new ArrayList<>();
                for (final int[] p : gripPoints) scaled.add(
                    new int[] { Math.round(p[0] * (r.w() / (float) gripFrom.w())),
                        Math.round(p[1] * (r.h() / (float) gripFrom.h())) });
                n.setPoints(scaled);
            }
            n.setWidth(r.w());
            n.setHeight(r.h());
        }
        sync();
    }

    private void endGrip(final boolean successful) {
        final Note n = note();
        final boolean moved = gripMoved;
        grabbed = null;
        held = null;
        pressed = null;
        final List<int[]> originalPoints = gripPoints;
        gripPoints = null;
        final String undo = gripUndo;
        gripUndo = null;
        if (n == null) return;
        if (!moved || !successful) {
            if (moved) {
                // Given up: back to where it was.
                n.setX(gripFrom.x());
                n.setY(gripFrom.y());
                n.setWidth(gripFrom.w());
                n.setHeight(gripFrom.h());
                n.setArrowDirection(gripDirection);
                if (originalPoints != null) n.setPoints(originalPoints);
                sync();
            } else if (canvas() != null) canvas().clickSelect(noteId);
            return;
        }
        com.gtnhplanner.api.PlanAPI.undoHistory(session.graph())
            .commitEdit(undo, session.graph());
        session.graph()
            .touchLayout();
        com.gtnhplanner.api.PlanAPI.save();
        com.gtnhplanner.ui.sound.Sfx.DROP.play();
    }

    @Override
    public void drawMovingState(final ModularGuiContext context, final float partialTicks) {}

    @Override
    public @Nullable Area getMovingArea() {
        return null;
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

    // region Writing

    private boolean editing;
    /** The text being written, the caret in it and the other end of the selection (equal: nothing selected). */
    private String draft = "";
    private int caret, anchor;
    private long caretSince;
    /** The last key event taken, so one offered twice is typed once. */
    private long lastKeyNanos = -1;

    public boolean editing() {
        return editing;
    }

    /** Starts writing on the note, everything selected so typing replaces it (as the website does). */
    public void startEditing() {
        final Note n = note();
        if (n == null) return;
        draft = n.joined();
        anchor = 0;
        caret = draft.length();
        caretSince = System.currentTimeMillis();
        editW = n.getWidth();
        editH = n.getHeight();
        editing = true;
        getContext().focus(this);
    }

    /** Ends writing: the text goes in as one undoable step, the note grown to show it all. */
    public void stopEditing() {
        if (!editing) return;
        editing = false;
        final Note n = note();
        if (n == null) return;
        final int needed = neededHeight(n, draft);
        // New words grow the note to show them; the same words leave it the size it was made.
        if (!draft.equals(n.joined())) {
            final String text = draft;
            session.editLayout(() -> {
                n.setJoined(text);
                n.setHeight(Math.max(n.getHeight(), needed));
            });
        }
    }

    /** The height that shows every line of {@code text} at the note's width and text size. */
    private int neededHeight(final Note n, final String text) {
        final NoteText lay = NoteText.of(text, wrapWidth(n, n.getWidth()), Hyb::width);
        final float lines = lay.lines()
            .size();
        final int f = Math.max(6, Math.min(FOLD, Math.min(n.getWidth(), n.getHeight()) / 4));
        return (int) Math.ceil((TEXT_TOP + lines * LINE * scale(n) + Math.max(4, f / 2f) + 3) / 10f) * 10;
    }

    @Override
    public boolean isFocused() {
        return editing;
    }

    @Override
    public void onFocus(final ModularGuiContext context) {}

    @Override
    public void onRemoveFocus(final ModularGuiContext context) {
        if (!holdFocus) stopEditing();
    }

    private boolean hasSelection() {
        return caret != anchor;
    }

    private void placeCaret(final boolean extend) {
        final Note n = note();
        if (n == null) return;
        final float s = scale(n);
        final NoteText lay = layout(draft, wrapWidth(n, w()));
        final int line = (int) Math.floor((localY() - TEXT_TOP + 1) / (LINE * s));
        caret = lay.caretAt(line, (localX() - PAD) / s);
        if (!extend) anchor = caret;
        caretSince = System.currentTimeMillis();
    }

    @Override
    public Result onKeyPressed(final char typedChar, final int keyCode) {
        if (!editing) return Result.IGNORE;
        final long nanos = Keyboard.getEventNanoseconds();
        if (nanos == lastKeyNanos && nanos != 0 && !com.gtnhplanner.ui.tutorial.Pointer.typing) return Result.SUCCESS;
        lastKeyNanos = nanos;
        caretSince = System.currentTimeMillis();
        final boolean ctrl = GuiScreen.isCtrlKeyDown(), shift = GuiScreen.isShiftKeyDown();
        switch (keyCode) {
            case Keyboard.KEY_ESCAPE -> {
                getContext().removeFocus();
                stopEditing();
                return Result.SUCCESS;
            }
            case Keyboard.KEY_RETURN, Keyboard.KEY_NUMPADENTER -> {
                insert("\n");
                return Result.SUCCESS;
            }
            case Keyboard.KEY_BACK -> {
                if (!hasSelection()) anchor = ctrl ? wordStart(caret) : Math.max(0, caret - 1);
                insert("");
                return Result.SUCCESS;
            }
            case Keyboard.KEY_DELETE -> {
                if (!hasSelection()) anchor = ctrl ? wordEnd(caret) : Math.min(draft.length(), caret + 1);
                insert("");
                return Result.SUCCESS;
            }
            case Keyboard.KEY_LEFT -> {
                moveCaret(
                    hasSelection() && !shift ? Math.min(caret, anchor) : ctrl ? wordStart(caret) : caret - 1,
                    shift);
                return Result.SUCCESS;
            }
            case Keyboard.KEY_RIGHT -> {
                moveCaret(
                    hasSelection() && !shift ? Math.max(caret, anchor) : ctrl ? wordEnd(caret) : caret + 1,
                    shift);
                return Result.SUCCESS;
            }
            case Keyboard.KEY_UP, Keyboard.KEY_DOWN -> {
                final Note n = note();
                if (n != null) {
                    final NoteText lay = layout(draft, wrapWidth(n, w()));
                    final int line = lay.lineOf(caret) + (keyCode == Keyboard.KEY_UP ? -1 : 1);
                    if (line < 0) moveCaret(0, shift);
                    else if (line >= lay.lines()
                        .size()) moveCaret(draft.length(), shift);
                    else moveCaret(lay.caretAt(line, lay.xOf(caret)), shift);
                }
                return Result.SUCCESS;
            }
            case Keyboard.KEY_HOME, Keyboard.KEY_END -> {
                final Note n = note();
                if (n != null) {
                    final NoteText lay = layout(draft, wrapWidth(n, w()));
                    final NoteText.Line l = lay.lines()
                        .get(lay.lineOf(caret));
                    moveCaret(keyCode == Keyboard.KEY_HOME ? l.start() : l.end(), shift);
                }
                return Result.SUCCESS;
            }
            default -> {}
        }
        if (ctrl) {
            switch (keyCode) {
                case Keyboard.KEY_A -> {
                    anchor = 0;
                    caret = draft.length();
                }
                case Keyboard.KEY_C -> {
                    if (hasSelection()) GuiScreen.setClipboardString(selected());
                }
                case Keyboard.KEY_X -> {
                    if (hasSelection()) {
                        GuiScreen.setClipboardString(selected());
                        insert("");
                    }
                }
                case Keyboard.KEY_V -> insert(
                    GuiScreen.getClipboardString()
                        .replace("\r", ""));
                default -> {}
            }
            return Result.SUCCESS;
        }
        if (typedChar != 0 && ChatAllowedCharacters.isAllowedCharacter(typedChar)) insert(String.valueOf(typedChar));
        // Every key stays with the note while it is being written on (Delete must not delete the selection).
        return Result.SUCCESS;
    }

    /** Types {@code s} at the caret, over the selection; the note grows to show the line the caret is on. */
    public void insert(final String s) {
        final int a = Math.min(caret, anchor), b = Math.max(caret, anchor);
        draft = draft.substring(0, a) + s + draft.substring(b);
        caret = anchor = a + s.length();
        growToFit();
    }

    /** Selects everything typed so far: the next typing replaces it. */
    public void selectAll() {
        anchor = 0;
        caret = draft.length();
    }

    /** The size the note shows at while it is written on: it grows with the text, and is saved when writing ends. */
    private int editW, editH;

    private void growToFit() {
        final Note n = note();
        if (n == null) return;
        editH = Math.max(n.getHeight(), neededHeight(n, draft));
    }

    private String selected() {
        return draft.substring(Math.min(caret, anchor), Math.max(caret, anchor));
    }

    private void moveCaret(final int to, final boolean extend) {
        caret = Math.max(0, Math.min(draft.length(), to));
        if (!extend) anchor = caret;
    }

    private int wordStart(final int from) {
        int i = Math.max(0, from - 1);
        while (i > 0 && Character.isWhitespace(draft.charAt(i))) i--;
        while (i > 0 && !Character.isWhitespace(draft.charAt(i - 1))) i--;
        return i;
    }

    private int wordEnd(final int from) {
        int i = from;
        while (i < draft.length() && Character.isWhitespace(draft.charAt(i))) i++;
        while (i < draft.length() && !Character.isWhitespace(draft.charAt(i))) i++;
        return i;
    }

    // endregion
}
