package com.gtnhplanner.ui.popup;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.item.ItemStack;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * Rows of icon, label and detail; click one to pick it and close the popup. The current choice is shaded, the
 * hovered row lit; the wheel scrolls when there are more rows than fit. Used for the actions menu, the machine
 * switch and dropdowns such as coils.
 */
public final class PickList extends Widget<PickList> implements Interactable {

    /**
     * A row: an icon or a coloured dot before its label ({@code dot} 0 for none), a detail at its right, shaded when it
     * is the current choice.
     */
    public record Entry(ItemStack icon, String label, String detail, int labelColor, boolean current, Runnable action,
        int dot) {

        public Entry(final ItemStack icon, final String label, final String detail, final int labelColor,
            final boolean current, final Runnable action) {
            this(icon, label, detail, labelColor, current, action, 0);
        }

        public static Entry of(final String label, final Runnable action) {
            return new Entry(null, label, "", Hyb.INK, false, action);
        }

        /** A row with a dot of {@code colour} before its label, as a task's status shows. */
        public static Entry dotted(final int colour, final String label, final String detail, final Runnable action) {
            return new Entry(null, label, detail, Hyb.INK, false, action, colour);
        }

        /** A row that makes something new: a plus before its label, in line with dotted rows. */
        public static Entry adding(final String label, final String detail, final Runnable action) {
            return new Entry(null, label, detail, Hyb.INK, false, action, PLUS);
        }

        /** The {@link #dot} that draws a plus instead (no colour has no alpha and is drawn). */
        static final int PLUS = 0x00FFFFFF;

        /** A line between groups of rows: it does nothing. */
        public static Entry rule() {
            return new Entry(null, "", "", Hyb.INK, false, null);
        }

        boolean isRule() {
            return action == null && label.isEmpty();
        }
    }

    public static final int ROW = 20;
    private static final int MAX_ROWS = 10;

    private final List<Entry> entries;
    private final int rows;
    private String filter = "";
    private List<Entry> shown;
    private int scroll;

    private PickList(final List<Entry> entries, final int width, final int maxRows) {
        this.entries = entries;
        this.shown = entries;
        this.rows = Math.max(1, Math.min(maxRows, entries.size()));
        size(width, rows * ROW);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).current && i >= rows) scroll = Math.min(i, entries.size() - rows);
        }
    }

    /**
     * A popup with an optional title and filter box above the list.
     *
     * @param width popup width in GUI pixels; rows show icon, label and a right-aligned detail
     */
    public static Popup popup(final String name, final String title, final List<Entry> entries,
        final boolean filterable, final int minWidth) {
        return popup(name, title, entries, filterable, minWidth, MAX_ROWS);
    }

    /** As above, showing up to {@code maxRows} rows before it scrolls. */
    public static Popup popup(final String name, final String title, final List<Entry> entries,
        final boolean filterable, final int minWidth, final int maxRows) {
        return popup(name, title, entries, filterable, minWidth, maxRows, null);
    }

    /**
     * As above, telling {@code onHover} which entry the mouse is on (its index in {@code entries}, -1 for none), and -1
     * once more when the popup closes: for showing on the board what an entry would do.
     */
    public static Popup popup(final String name, final String title, final List<Entry> entries,
        final boolean filterable, final int minWidth, final int maxRows,
        @org.jetbrains.annotations.Nullable final java.util.function.IntConsumer onHover) {
        // Never cut a row: grow to the widest label and detail (the game font has one size).
        int width = Math.max(minWidth, title == null ? 0 : Hyb.width(title) + 16);
        for (final Entry e : entries) {
            final int detail = e.detail() == null || e.detail()
                .isEmpty() ? 0 : Hyb.width(e.detail()) + 12;
            width = Math
                .max(width, (e.icon() != null ? 26 : e.dot() != 0 ? 18 : 6) + Hyb.width(e.label()) + detail + 18);
        }
        // Inside the planner's room: narrower drops a row's detail rather than cut its label; shorter scrolls.
        final int[] room = Popup.room(null);
        width = Math.min(width, Math.min(360, room[2] - 4));
        final int above = 8 + (title == null ? 0 : 14) + (filterable ? 18 : 0);
        final int fits = Math.max(2, (room[3] - 4 - above) / ROW);
        final PickList list = new PickList(entries, width - 8, Math.min(maxRows, fits));
        list.hover = onHover;
        int y = 4;
        final List<com.cleanroommc.modularui.api.widget.IWidget> top = new ArrayList<>();
        if (title != null) {
            top.add(
                new TextWidget<>(IKey.str(title)).color(Hyb.MUTED)
                    .shadow(true)
                    .pos(6, y + 2)
                    .size(width - 12, 10));
            y += 14;
        }
        if (filterable) {
            final TextFieldWidget field = new TextFieldWidget()
                .value(new StringValue.Dynamic(() -> list.filter, list::setFilter));
            field.hintText("Filter...")
                .pos(4, y)
                .size(width - 8, 14);
            list.field = field;
            top.add(field);
            y += 18;
        }
        final Popup popup = new Popup(name, width, y + list.rows * ROW + 4);
        for (final com.cleanroommc.modularui.api.widget.IWidget w : top) popup.child(w);
        popup.child(list.pos(4, y));
        return popup;
    }

    private TextFieldWidget field;
    /**
     * Told which entry the mouse is on; see {@link #popup(String, String, List, boolean, int, int,
     * java.util.function.IntConsumer)}.
     */
    private java.util.function.IntConsumer hover;
    private int toldHover = -1;

    /** Tells the listener every frame the list is drawn, so it can tell a closed list (no word for a while). */
    private void tellHover(final int entry) {
        if (hover == null) return;
        toldHover = entry;
        hover.accept(entry);
    }

    @Override
    public void dispose() {
        tellHover(-1);
        super.dispose();
    }

    /** The field only commits on Enter, so read its live text every tick to filter as the player types. */
    @Override
    public void onUpdate() {
        super.onUpdate();
        if (field != null && !field.getText()
            .equals(filter)) setFilter(field.getText());
    }

    private void setFilter(final String text) {
        filter = text == null ? "" : text;
        final String f = filter.toLowerCase(Locale.ROOT);
        if (f.isEmpty()) shown = entries;
        else {
            shown = new ArrayList<>();
            for (final Entry e : entries) {
                if (e.label.toLowerCase(Locale.ROOT)
                    .contains(f)) shown.add(e);
            }
        }
        scroll = 0;
    }

    private int hoveredRow() {
        if (!isHovering()) return -1;
        final int my = getContext().getAbsMouseY() - getArea().y;
        final int row = my / ROW;
        return row >= 0 && row < rows ? row + scroll : -1;
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final int w = getArea().width;
        final int hovered = hoveredRow();
        tellHover(hovered >= 0 && hovered < shown.size() ? entries.indexOf(shown.get(hovered)) : -1);
        final float z = context.getCurrentDrawingZ();
        for (int r = 0; r < rows; r++) {
            final int i = r + scroll;
            if (i >= shown.size()) break;
            final Entry e = shown.get(i);
            final int y = r * ROW;
            if (e.isRule()) {
                Hyb.rect(4, y + ROW / 2, w - 8, 1, 0xFF3A3C42);
                continue;
            }
            if (i == hovered) Hyb.rect(0, y, w, ROW, Hyb.MENU_HOVER);
            else if (e.current) Hyb.rect(0, y, w, ROW, Hyb.TILE);
            int x = 4;
            if (e.icon != null) {
                Hyb.item(e.icon, x, y + 2, 16, z);
                x += 20;
            } else if (e.dot == Entry.PLUS) {
                com.gtnhplanner.ui.theme.Icons.plus(x, y + 5, Hyb.MUTED);
                x += 14;
            } else if (e.dot != 0) {
                Hyb.roundRect(x + 2, y + 7, 6, 6, 3, e.dot);
                x += 14;
            }
            // The label says what the row does, so it is never cut for the detail: a detail with no room is left out.
            final String label = Hyb.fit(e.label, w - x - 4);
            final int room = w - 4 - (x + Hyb.width(label) + 8);
            final String detail = e.detail.isEmpty() || room < 30 ? "" : Hyb.fit(e.detail, room);
            Hyb.text(label, x, y + 6, e.labelColor);
            if (!detail.isEmpty()) Hyb.textRight(detail, w - 4, y + 6, Hyb.MUTED);
        }
        if (shown.size() > rows) {
            final int barH = Math.max(8, rows * ROW * rows / shown.size());
            final int barY = (rows * ROW - barH) * scroll / Math.max(1, shown.size() - rows);
            Hyb.rect(w - 2, barY, 2, barH, Hyb.MUTED);
        }
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        final int i = hoveredRow();
        if (mouseButton != 0 || i < 0 || i >= shown.size()) return Result.IGNORE;
        final Entry e = shown.get(i);
        if (e.isRule()) return Result.SUCCESS;
        final ModularPanel panel = getPanel();
        Hyb.click();
        panel.closeIfOpen();
        e.action.run();
        return Result.SUCCESS;
    }

    /**
     * The GUI rectangle {x, y, w, h} of the first row whose label contains {@code label}, scrolled into view; null
     * when there is none. For the tour.
     */
    public int[] rowRect(final String label) {
        for (int i = 0; i < shown.size(); i++) {
            if (!shown.get(i).label.contains(label)) continue;
            if (i < scroll) scroll = i;
            else if (i >= scroll + rows) scroll = i - rows + 1;
            final com.cleanroommc.modularui.widget.sizer.Area a = getArea();
            return new int[] { a.x, a.y + (i - scroll) * ROW, a.width, ROW };
        }
        return null;
    }

    @Override
    public boolean onMouseScroll(final UpOrDown direction, final int amount) {
        final int max = Math.max(0, shown.size() - rows);
        scroll = Math.max(0, Math.min(max, scroll + (direction == UpOrDown.UP ? -1 : 1)));
        return true;
    }
}
