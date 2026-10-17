package com.gtnhplanner.ui.popup;

import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ChatAllowedCharacters;

import org.lwjgl.input.Keyboard;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.IFocusedWidget;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.gtnhplanner.ui.note.NoteText;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * Text of many lines to write in, in the planner's look: wrapped at word breaks (the sticky notes' layout), a caret
 * and a selection, the keys the notes take (arrows, Home and End, Ctrl with a word or the clipboard), a hint while it
 * is empty, and the wheel when it runs longer than the box. It can write bigger, at a {@link #scale} (a heading), and
 * keep to one paragraph ({@link #singleLine}: Enter ends the writing).
 */
public final class TextArea extends Widget<TextArea> implements Interactable, IFocusedWidget {

    private static final int PAD = 4, LINE = 10;
    private static final int SELECTION = 0x6622D3EE;

    private final String hint;
    private String text = "";
    private int caret, anchor, scroll;
    private boolean focused, enabled = true;
    private long caretSince;
    /** The last key event taken, so one offered twice is typed once. */
    private long lastKeyNanos = -1;
    /** How big it writes: 1 the game's font. */
    private float scale = 1;
    /** One paragraph: Enter ends the writing, and pasted lines are joined. */
    private boolean single;
    private int ink = Hyb.INK;

    public TextArea(final String hint) {
        this.hint = hint;
    }

    public String text() {
        return text;
    }

    /** Puts text in the box (its caller keeps it from changing what is being written). */
    public TextArea text(@Nullable final String text) {
        this.text = text == null ? "" : text;
        caret = anchor = Math.min(caret, this.text.length());
        return this;
    }

    /** Told what was written each time the box lets go of the keyboard. */
    @Nullable
    private Consumer<String> onDone;

    /** Lets {@code done} hear the text each time writing ends (Esc, or a click elsewhere). */
    public TextArea onDone(final Consumer<String> done) {
        onDone = done;
        return this;
    }

    /** Writes bigger: a heading at a size that keeps whole screen pixels (see the caller). */
    public TextArea scale(final float scale) {
        this.scale = scale;
        return this;
    }

    /** Keeps to one paragraph: Enter ends the writing, as Esc does. */
    public TextArea singleLine() {
        single = true;
        return this;
    }

    /** The colour it writes in. */
    public TextArea ink(final int colour) {
        ink = colour;
        return this;
    }

    /** The height a text takes at {@code width}: its lines and the padding round them. */
    public int heightFor(final String text, final int width) {
        final int lines = NoteText.of(text, (width - 2 * PAD - 2) / scale, Hyb::width)
            .lines()
            .size();
        return Math.round(Math.max(1, lines) * LINE * scale) + 2 * PAD;
    }

    /** The most it holds, in characters. */
    private int maxLength = Integer.MAX_VALUE;

    public TextArea maxLength(final int max) {
        maxLength = max;
        return this;
    }

    /** Stops taking input, as a form being sent does. */
    public void lock() {
        enabled = false;
        focused = false;
    }

    /** Takes input again (the send failed). */
    public void unlock() {
        enabled = true;
    }

    private NoteText layout() {
        return NoteText.of(text, (getArea().width - 2 * PAD - 2) / scale, Hyb::width);
    }

    private float line() {
        return LINE * scale;
    }

    private int rows() {
        return Math.max(1, (int) ((getArea().height - 2 * PAD + 1) / line()));
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        if (text.isEmpty() && !focused) {
            Hyb.text(hint, PAD, PAD + 1, scale, 0xFF6A6C74);
            return;
        }
        final NoteText lay = layout();
        final int lines = lay.lines()
            .size();
        // The caret's line kept in view.
        final int at = lay.lineOf(caret);
        if (focused) {
            if (at < scroll) scroll = at;
            if (at >= scroll + rows()) scroll = at - rows() + 1;
        }
        scroll = Math.max(0, Math.min(scroll, lines - rows()));
        final int a = Math.min(caret, anchor), b = Math.max(caret, anchor);
        for (int i = scroll; i < Math.min(lines, scroll + rows()); i++) {
            final float y = PAD + (i - scroll) * line();
            final NoteText.Line l = lay.lines()
                .get(i);
            if (b > a && b > l.start() && a < l.end()) {
                final int from = Math.max(a, l.start()), to = Math.min(b, l.end());
                final int x0 = Hyb.width(text.substring(l.start(), from)),
                    x1 = Hyb.width(text.substring(l.start(), to));
                Hyb.rect(PAD + x0 * scale, y - scale, Math.max(2, (x1 - x0) * scale), line(), SELECTION);
            }
            Hyb.text(lay.shown(i), PAD, y, scale, ink);
        }
        if (focused && (System.currentTimeMillis() - caretSince) % 1000 < 600 && at >= scroll && at < scroll + rows())
            Hyb.rect(PAD + lay.xOf(caret) * scale, PAD + (at - scroll) * line() - scale, 1, line(), ink);
        if (lines > rows()) {
            final int track = getArea().height - 4, bar = Math.max(8, track * rows() / lines);
            Hyb.rect(getArea().width - 3, 2 + (track - bar) * scroll / Math.max(1, lines - rows()), 2, bar, Hyb.MUTED);
        }
    }

    @Override
    public boolean canHover() {
        return true;
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (mouseButton != 0 || !enabled) return Result.ACCEPT;
        getContext().focus(this);
        final NoteText lay = layout();
        final int line = scroll + (int) ((getContext().getAbsMouseY() - getArea().y - PAD) / line());
        caret = lay.caretAt(line, Math.round((getContext().getAbsMouseX() - getArea().x - PAD) / scale));
        if (!GuiScreen.isShiftKeyDown()) anchor = caret;
        caretSince = System.currentTimeMillis();
        return Result.SUCCESS;
    }

    @Override
    public boolean onMouseScroll(final UpOrDown direction, final int amount) {
        // Only while it runs longer than the box: else the wheel is for what holds it.
        if (layout().lines()
            .size() <= rows()) return false;
        scroll += direction == UpOrDown.UP ? -1 : 1;
        return true;
    }

    @Override
    public boolean isFocused() {
        return focused;
    }

    @Override
    public void onFocus(final ModularGuiContext context) {
        focused = enabled;
        caretSince = System.currentTimeMillis();
    }

    @Override
    public void onRemoveFocus(final ModularGuiContext context) {
        final boolean was = focused;
        focused = false;
        if (was && onDone != null) onDone.accept(text);
    }

    @Override
    public Result onKeyPressed(final char typedChar, final int keyCode) {
        if (!focused) return Result.IGNORE;
        final long nanos = Keyboard.getEventNanoseconds();
        if (nanos == lastKeyNanos && nanos != 0) return Result.SUCCESS;
        lastKeyNanos = nanos;
        caretSince = System.currentTimeMillis();
        final boolean ctrl = GuiScreen.isCtrlKeyDown(), shift = GuiScreen.isShiftKeyDown();
        switch (keyCode) {
            case Keyboard.KEY_ESCAPE -> {
                getContext().removeFocus();
                return Result.SUCCESS;
            }
            case Keyboard.KEY_RETURN, Keyboard.KEY_NUMPADENTER -> {
                if (single) getContext().removeFocus();
                else insert("\n");
                return Result.SUCCESS;
            }
            case Keyboard.KEY_BACK -> {
                if (caret == anchor) anchor = ctrl ? wordStart(caret) : Math.max(0, caret - 1);
                insert("");
                return Result.SUCCESS;
            }
            case Keyboard.KEY_DELETE -> {
                if (caret == anchor) anchor = ctrl ? wordEnd(caret) : Math.min(text.length(), caret + 1);
                insert("");
                return Result.SUCCESS;
            }
            case Keyboard.KEY_LEFT -> {
                move(caret != anchor && !shift ? Math.min(caret, anchor) : ctrl ? wordStart(caret) : caret - 1, shift);
                return Result.SUCCESS;
            }
            case Keyboard.KEY_RIGHT -> {
                move(caret != anchor && !shift ? Math.max(caret, anchor) : ctrl ? wordEnd(caret) : caret + 1, shift);
                return Result.SUCCESS;
            }
            case Keyboard.KEY_UP, Keyboard.KEY_DOWN -> {
                final NoteText lay = layout();
                final int line = lay.lineOf(caret) + (keyCode == Keyboard.KEY_UP ? -1 : 1);
                if (line < 0) move(0, shift);
                else if (line >= lay.lines()
                    .size()) move(text.length(), shift);
                else move(lay.caretAt(line, lay.xOf(caret)), shift);
                return Result.SUCCESS;
            }
            case Keyboard.KEY_HOME, Keyboard.KEY_END -> {
                final NoteText lay = layout();
                final NoteText.Line l = lay.lines()
                    .get(lay.lineOf(caret));
                move(keyCode == Keyboard.KEY_HOME ? l.start() : l.end(), shift);
                return Result.SUCCESS;
            }
            default -> {}
        }
        if (ctrl) {
            switch (keyCode) {
                case Keyboard.KEY_A -> {
                    anchor = 0;
                    caret = text.length();
                }
                case Keyboard.KEY_C -> {
                    if (caret != anchor) GuiScreen.setClipboardString(selected());
                }
                case Keyboard.KEY_X -> {
                    if (caret != anchor) {
                        GuiScreen.setClipboardString(selected());
                        insert("");
                    }
                }
                case Keyboard.KEY_V -> {
                    final String pasted = GuiScreen.getClipboardString()
                        .replace("\r", "");
                    insert(single ? pasted.replace('\n', ' ') : pasted);
                }
                default -> {}
            }
            return Result.SUCCESS;
        }
        if (typedChar != 0 && ChatAllowedCharacters.isAllowedCharacter(typedChar)) insert(String.valueOf(typedChar));
        // Every key stays here while writing (the planner's own keys must not act on it).
        return Result.SUCCESS;
    }

    private void insert(final String typed) {
        final int a = Math.min(caret, anchor), b = Math.max(caret, anchor);
        final String s = typed.substring(0, Math.max(0, Math.min(typed.length(), maxLength - text.length() + b - a)));
        text = text.substring(0, a) + s + text.substring(b);
        caret = anchor = a + s.length();
    }

    private String selected() {
        return text.substring(Math.min(caret, anchor), Math.max(caret, anchor));
    }

    private void move(final int to, final boolean extend) {
        caret = Math.max(0, Math.min(text.length(), to));
        if (!extend) anchor = caret;
    }

    private int wordStart(final int from) {
        int i = Math.max(0, from - 1);
        while (i > 0 && Character.isWhitespace(text.charAt(i))) i--;
        while (i > 0 && !Character.isWhitespace(text.charAt(i - 1))) i--;
        return i;
    }

    private int wordEnd(final int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        while (i < text.length() && !Character.isWhitespace(text.charAt(i))) i++;
        return i;
    }
}
