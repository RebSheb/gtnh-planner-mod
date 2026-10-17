package com.gtnhplanner.ui.popup;

import java.util.ArrayList;
import java.util.List;

import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.gtnhplanner.ui.PlannerScale;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * A floating panel in Factory Flow's menu style (bevelled #3c3e45 with a hard drop shadow) that closes on any click
 * outside it. Opened at a screen position, kept on screen.
 */
public class Popup extends ModularPanel {

    protected final int popupW, popupH;

    /**
     * ModularUI keeps one handler per panel name for the life of the screen and re-opens the old panel when a name
     * comes back, so every popup gets a name of its own.
     */
    private static int opened;

    public Popup(final String name, final int width, final int height) {
        super(name + "_" + ++opened);
        this.popupW = width;
        this.popupH = height;
        size(width, height);
    }

    /**
     * The room a popup has, {x, y, w, h} in the board's pixels: the planner's own panel, left of NEI's item list (which
     * draws over anything past it) and above its search box. The whole screen off the planner.
     */
    public static int[] room(@org.jetbrains.annotations.Nullable final ModularPanel parent) {
        com.cleanroommc.modularui.widget.sizer.Area a = null;
        try {
            final com.cleanroommc.modularui.screen.ModularScreen screen = parent == null
                ? com.cleanroommc.modularui.screen.ModularScreen.getCurrent()
                : parent.getScreen();
            final ModularPanel main = screen == null ? null : screen.getMainPanel();
            if (main != null) a = main.getArea();
        } catch (final RuntimeException ignored) {
            // A panel not on a screen yet: the screen it will open on.
        }
        if (a == null || a.width <= 0 || a.height <= 0)
            return new int[] { 0, 0, PlannerScale.width(), PlannerScale.height() };
        return new int[] { a.x, a.y, a.width, a.height };
    }

    /** The top-left that centres a box {@code w} by {@code h} in the room, {@code down} of the way down (a half). */
    public static int[] centred(final ModularPanel parent, final int w, final int h, final float down) {
        final int[] r = room(parent);
        return new int[] { r[0] + (r[2] - w) / 2, r[1] + Math.round((r[3] - h) * down) };
    }

    /**
     * Opens {@code popup} over {@code parent} with its top-left at the given screen point, nudged into the planner's
     * room ({@link #room}). It opens on the next tick: opened inside a click handler, the click that opened it would
     * reach it as a click outside and close it again. With no parent (a board not built yet) nothing opens.
     */
    public static void open(final ModularPanel parent, final Popup popup, final int screenX, final int screenY) {
        open(parent, popup, () -> new int[] { screenX, screenY });
    }

    /**
     * Opens {@code popup} centred in the planner's room, {@code down} of the way down (a half centres it). The room is
     * measured as it opens: a box offered as the planner opens finds the planner laid out by then.
     */
    public static void openCentred(final ModularPanel parent, final Popup popup, final float down) {
        open(parent, popup, () -> centred(parent, popup.popupW, popup.popupH, down));
    }

    private static void open(final ModularPanel parent, final Popup popup,
        final java.util.function.Supplier<int[]> at) {
        if (parent == null) return;
        PENDING.add(() -> {
            if (!parent.isOpen()) return;
            final int w = popup.popupW, h = popup.popupH;
            final int[] r = room(parent), want = at.get();
            popup.pos(
                Math.max(r[0] + 2, Math.min(want[0], r[0] + r[2] - w - 2)),
                Math.max(r[1] + 2, Math.min(want[1], r[1] + r[3] - h - 2)));
            popup.shown = true;
            IPanelHandler.simple(parent, (p, player) -> popup, true)
                .openPanel();
            com.gtnhplanner.ui.sound.Sfx.OPEN.play();
        });
    }

    private static final List<Runnable> PENDING = new ArrayList<>();

    /** Boxes opened from a key that toggles, by key name: that key again closes the box, and it never opens twice. */
    private static final java.util.Map<String, Popup> TOGGLED = new java.util.HashMap<>();
    private static final java.util.Map<String, Long> CLICKED_OFF = new java.util.HashMap<>();
    private String toggleKey;
    private boolean shown;

    /** Marks this box as the one {@code key} opens (see {@link #toggled}). */
    public <P extends Popup> P toggledBy(final String key) {
        toggleKey = key;
        TOGGLED.put(key, this);
        @SuppressWarnings("unchecked")
        final P self = (P) this;
        return self;
    }

    /**
     * Whether the key's press only closes (or waits on) its box: it is open, it is about to open, or the press that
     * landed just now was the click off it that closed it. False means open it.
     */
    public static boolean toggled(final String key) {
        final Long off = CLICKED_OFF.remove(key);
        if (off != null && System.currentTimeMillis() - off < 250) return true;
        final Popup p = TOGGLED.get(key);
        if (p == null) return false;
        if (!p.shown) return true;
        if (p.isOpen()) {
            p.closeIfOpen();
            com.gtnhplanner.ui.sound.Sfx.CLOSE.play();
            return true;
        }
        TOGGLED.remove(key);
        return false;
    }

    /** Opens the popups asked for since the last tick. The board calls this every tick. */
    public static void openPending() {
        if (PENDING.isEmpty()) return;
        final List<Runnable> now = new ArrayList<>(PENDING);
        PENDING.clear();
        now.forEach(Runnable::run);
    }

    /** A click off the popup dismisses it, with the fold-away sound (picking a row sounds as what it does). */
    @Override
    public boolean closeOnOutOfBoundsClick() {
        if (toggleKey != null) CLICKED_OFF.put(toggleKey, System.currentTimeMillis());
        com.gtnhplanner.ui.sound.Sfx.CLOSE.play();
        return true;
    }

    @Override
    public boolean isDraggable() {
        return false;
    }

    @Override
    public boolean shouldAnimate() {
        return false;
    }

    @Override
    public boolean disablePanelsBelow() {
        return false;
    }

    @Override
    public void drawBackground(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final int w = getArea().width, h = getArea().height;
        Hyb.rect(4, 4, w, h, 0x59000000);
        Hyb.rect(0, 0, w, h, Hyb.KEY_EDGE);
        Hyb.rect(1, 1, w - 2, h - 2, Hyb.MENU);
        Hyb.rect(1, 1, w - 2, 1, Hyb.HIGHLIGHT);
        Hyb.rect(1, 1, 1, h - 2, Hyb.HIGHLIGHT);
        Hyb.rect(1, h - 2, w - 2, 1, Hyb.SHADOW);
        Hyb.rect(w - 2, 1, 1, h - 2, Hyb.SHADOW);
    }
}
