package com.gtnhplanner.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.item.ItemStack;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.drawable.Stencil;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.ui.canvas.BoardCanvas;
import com.gtnhplanner.ui.card.CardModel;
import com.gtnhplanner.ui.drawer.DrawerCard;
import com.gtnhplanner.ui.drawer.DrawerModel;
import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * The overview, Factory Flow's resources column moved to the board's left edge: what the plan needs from outside
 * (inputs), what it gives out (outputs) and what it makes and uses itself (internal), each resource with the drawers
 * that hold it and their rule and rate; then the machines to build, with power. Hovering a resource lights it up on the
 * board; double-clicking flies to the cards that use it. Folds to a thin strip.
 */
final class OverviewRail extends ParentWidget<OverviewRail>
    implements Interactable, com.cleanroommc.modularui.integration.recipeviewer.RecipeViewerIngredientProvider,
    com.cleanroommc.modularui.api.widget.IFocusedWidget {

    /** The rate box or count field being typed into: one at a time. */
    private final com.gtnhplanner.ui.popup.InlineNumber edit = new com.gtnhplanner.ui.popup.InlineNumber();

    @Override
    public boolean isFocused() {
        return edit.active();
    }

    @Override
    public void onFocus(final com.cleanroommc.modularui.screen.viewport.ModularGuiContext context) {}

    /** Clicking anything else sets what was typed. */
    @Override
    public void onRemoveFocus(final com.cleanroommc.modularui.screen.viewport.ModularGuiContext context) {
        edit.commit();
    }

    @Override
    public Result onKeyPressed(final char typedChar, final int keyCode) {
        if (!edit.active()) return Result.IGNORE;
        edit.keyPressed(typedChar, keyCode);
        if (!edit.active()) getContext().removeFocus();
        return Result.SUCCESS;
    }

    static final int W = 198;
    static final int W_FOLDED = 14;

    private static final int HEAD_H = 18, FILTER_Y = 20, FILTER_H = 14, LIST_Y = 38;
    private static final int SECTION_H = 12, ROW_H = 13, DRAWER_H = 12, GROUP_H = 18, LINE_H = 14, GAP = 4;
    /** A resource row's icon: its size and left, and where its name starts (as the Spare section's lines). */
    private static final int ICON = 10, ICON_X = 8, NAME_X = ICON_X + 13;
    /** The line under the mouse: a faint gold, as a spare's lines have it. */
    private static final int HOVER = 0x1AE8B34A;
    private static final int TOGGLE_W = 30, PIN_W = 12;
    /** The machine count field: a drawer's rate box, "Set" while it has none. */
    private static final int COUNT_W = 38;
    private static final int INTERNAL_INK = 0xFFD4D4D4;

    private enum Kind {
        ADD,
        FOLD,
        SECTION,
        RESOURCE,
        RULE,
        RATE,
        PEAK,
        GROUP,
        MACHINE,
        /** A machine line's count: click to type a pinned count, as on the card. */
        COUNT,
        /** A machine line's rule key: Any (the plan decides) or = (pinned); a click switches. */
        MACHINE_RULE,
        /** A machine's pin key: puts it on the pin list shown while playing, or takes it off. */
        PIN,
        /** The list's scroll bar: drag the thumb, or click the track to jump. */
        SCROLLBAR,
        /** A leftover ({@link SpareBar}): its header, Split and Auto, and a line per place with its bar. */
        SPARE,
        /** A port set to a rate on its card ({@link PortPin}): its = key (a click lets the plan decide again). */
        PORT_RULE,
        /** A port's rate, set on its card: click to type it, the wheel steps it, as on the card. */
        PORT_RATE,
        /**
         * A line's "~ Set" while nothing sets it: a rate typed there goes to its one open port (as on the card), or to
         * a
         * drawer for all of them.
         */
        LINE_SET
    }

    /** A port of a line's resource with nothing wired to it and no drawer, and the machine it is on. */
    private record OpenPort(com.gtnhplanner.data.flowchart.Node node, boolean output, int index, double perSecond,
        String machine) {}

    /**
     * A machine's port with a rate set on its card (the machine runs so it takes or gives exactly that), where the
     * plan's edge is: listed under its resource as a drawer is, with the same controls.
     */
    private record PortPin(com.gtnhplanner.data.flowchart.Node node, boolean output, int index, double rate,
        String machine) {}

    /** Something clickable or hoverable, in rail coordinates, rebuilt every frame by the draw pass. */
    private record Hit(Kind kind, int x0, int y0, int x1, int y1, Object data) {

        boolean contains(final int x, final int y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }
    }

    private final BoardSession session;
    private final BoardCanvas canvas;
    private final TextFieldWidget filterField;
    /** The sections folded to their bars, and whether the overview is open: as the player last left them. */
    private final Set<String> folded = new HashSet<>(PlannerSettings.overviewFolded());
    private final List<Hit> hits = new ArrayList<>();
    private boolean open = PlannerSettings.overviewOpen();
    private String filter = "";
    private int scroll;
    private int contentH;
    private final Map<String, Integer> flyIndex = new LinkedHashMap<>();

    OverviewRail(final BoardSession session, final BoardCanvas canvas) {
        this.session = session;
        this.canvas = canvas;
        width(currentWidth());
        filterField = new TextFieldWidget()
            .value(new StringValue.Dynamic(() -> filter, s -> filter = s == null ? "" : s))
            .hintText("Filter resources...")
            .pos(4, FILTER_Y)
            .size(W - 8, FILTER_H);
        filterField.setEnabled(open);
        child(filterField);
    }

    boolean isOpen() {
        return open;
    }

    int currentWidth() {
        return open ? W : W_FOLDED;
    }

    void toggle() {
        open = !open;
        PlannerSettings.setOverviewOpen(open);
        scroll = 0;
        filterField.setEnabled(open);
        width(currentWidth());
        if (getParent() != null) getParent().scheduleResize();
        scheduleResize();
    }

    @Override
    public boolean canHover() {
        return true;
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        // The field commits only on Enter; read its live text so the list filters as the player types.
        final String live = filterField.getText();
        if (!live.equals(filter)) {
            filter = live;
            scroll = 0;
        }
    }

    private int localX() {
        return getContext().getAbsMouseX() - getArea().x;
    }

    private int localY() {
        return getContext().getAbsMouseY() - getArea().y;
    }

    // region Drawing

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        if (!com.gtnhplanner.dev.DevPerf.on()) {
            drawRail(context, widgetTheme);
            return;
        }
        final long started = System.nanoTime();
        drawRail(context, widgetTheme);
        com.gtnhplanner.dev.DevPerf.time("overview", System.nanoTime() - started);
    }

    /** The least of its background the overview keeps when the board is all see-through. */
    private static final float RAIL_FLOOR = 0.45f;

    private void drawRail(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        hits.clear();
        final int w = getArea().width, h = getArea().height;
        // However see-through the board is, the overview keeps a little of its background, so it reads.
        Hyb.rect(0, 0, w, h, Hyb.seeThrough(0xFF202226, RAIL_FLOOR));
        // Its edge on the board: a dark line, a border rather than a seam; as see-through as the rest.
        Hyb.rect(w - 1, 0, 1, h, Hyb.seeThrough(0xFF0C0D10));
        if (!open) {
            drawFolded(h);
            session.setRailHoverKey(null);
            hoverSpare(null, w);
            return;
        }
        final Hit hover = hitAtMouseFromLastFrame();
        session.setRailHoverKey(
            hover != null && hover.kind() == Kind.RESOURCE ? ((BoardSession.TotalLine) hover.data()).key() : null);
        hoverSpare(hover, w);
        final SpareBar held = dragging();
        String keep = null;
        if (held != null) {
            for (final java.util.Map.Entry<String, SpareBar> e : spareBars.entrySet())
                if (e.getValue() == held) keep = e.getKey();
        } else if (hover != null && hover.kind() == Kind.SPARE) keep = ((BoardSession.SpareLine) hover.data()).key();
        if (!java.util.Objects.equals(keep, anchorKey)) {
            anchorKey = keep;
            anchorAt = Integer.MIN_VALUE;
        }
        anchorShift = 0;

        // Heading: the title and the fold key.
        Hyb.rect(0, 0, w - 1, HEAD_H, Hyb.seeThrough(0xFF2A2D33, RAIL_FLOOR));
        Hyb.text("OVERVIEW", 6, 5, Hyb.MUTED);
        final int fx = w - 4 - 14;
        Hyb.bevel(
            fx,
            2,
            14,
            14,
            hover != null && hover.kind() == Kind.FOLD ? Hyb.KEY_HOVER : Hyb.KEY,
            Hyb.KEY_HI,
            Hyb.KEY_LO,
            0,
            1);
        Hyb.textCentered("«", fx + 7.5f, 5, Hyb.INK);
        hits.add(new Hit(Kind.FOLD, fx, 2, fx + 14, 16, null));

        // The list scrolls under the heading and filter.
        final float z = context.getCurrentDrawingZ();
        final int listY = LIST_Y;
        final BoardSession.Totals t = session.totals();
        inputs.clear();
        inputs.addAll(t.inputs());
        // When the plan's numbers change under the slider in hand (or under the mouse), lines above it can come or
        // go: the list is laid out once unseen first (nothing reaches the screen), so it is drawn already scrolled to
        // keep the slider where it was, never a frame out of place.
        if (anchorKey != null && anchorAt != Integer.MIN_VALUE && (t != laidTotals || session.spares() != laidSpares)) {
            final int before = hits.size();
            GL11.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_SCISSOR_BIT);
            GL11.glColorMask(false, false, false, false);
            GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(0, 0, 0, 0);
            list(t, listY - scroll, w, z, hover);
            GL11.glPopAttrib();
            hits.subList(before, hits.size())
                .clear();
            scroll += anchorShift;
            anchorShift = 0;
        }
        laidTotals = t;
        laidSpares = session.spares();
        Stencil.apply(0, listY, w - 1, h - listY, context);
        final int y = list(t, listY - scroll, w, z, hover);
        contentH = y + scroll - listY;
        // Where things sit in the list does not depend on the scroll, so one correction holds.
        if (anchorShift != 0) scroll += anchorShift;
        Stencil.remove();
        // Folding a section, or a slider kept still while lines above it go, can leave the list scrolled past an end:
        // once nothing is kept still it glides back (a quarter of the way a frame). A thin bar says when there is more.
        final int visible = h - listY, max = Math.max(0, contentH - visible);
        if (anchorKey == null) {
            if (scroll > max) scroll = Math.max(max, scroll - Math.max(1, (scroll - max) / 4));
            else if (scroll < 0) scroll = Math.min(0, scroll + Math.max(1, -scroll / 4));
        }
        scrollMax = max;
        if (max > 0) {
            final int thumb = Math.max(14, visible * visible / contentH);
            barThumb = thumb;
            barTrack = visible - thumb;
            final int top = listY + barTrack * Math.max(0, Math.min(max, scroll)) / max;
            final boolean hot = draggingBar || hover != null && hover.kind() == Kind.SCROLLBAR;
            if (hot) Hyb.rect(w - BAR_W - 1, listY, BAR_W, visible, 0x40000000);
            Hyb.rect(w - BAR_W, top, BAR_W - 2, thumb, hot ? Hyb.INK : 0xFF6A6C74);
        }
        // Keep what is on screen of the list; the heading's fold key stays as it is.
        final List<Hit> moved = new ArrayList<>(hits.size());
        for (final Hit hit : hits) {
            if (hit.kind() == Kind.FOLD) {
                moved.add(hit);
                continue;
            }
            if (hit.y1() <= listY || hit.y0() >= h) continue;
            moved.add(new Hit(hit.kind(), hit.x0(), Math.max(hit.y0(), listY), hit.x1(), hit.y1(), hit.data()));
        }
        // The scroll bar sits over the list's right edge, on top of everything there (hits are found first to last).
        if (max > 0) moved.add(0, new Hit(Kind.SCROLLBAR, w - BAR_W - 2, listY, w, h, null));
        hits.clear();
        hits.addAll(moved);
        lastHits.clear();
        lastHits.addAll(moved);
    }

    /** The list under the heading and filter, from {@code y}: every section in turn. Returns where it ends. */
    private int list(final BoardSession.Totals t, int y, final int w, final float z, final Hit hover) {
        final Set<String> keys = new HashSet<>();
        y = resources("INPUTS", t.inputs(), y, w, z, Hyb.SOURCE_INK, "-", "Nothing missing.", hover, keys);
        y = resources("OUTPUTS", t.outputs(), y, w, z, Hyb.PRODUCT_INK, "+", "Nothing coming out yet.", hover, keys);
        y = resources("INTERNAL", t.internal(), y, w, z, INTERNAL_INK, "", "Nothing internal.", hover, keys);
        // A folded section keeps its sliders (a bar in hand among them keeps its place).
        for (final BoardSession.SpareLine line : session.spares())
            if (folded.contains(line.importing() ? "INPUTS" : "OUTPUTS")) keys.add(line.key());
        // The bar in hand stays, even while a solve has its line away a moment.
        final SpareBar inHand = dragging();
        for (final Map.Entry<String, SpareBar> e : spareBars.entrySet())
            if (e.getValue() == inHand) keys.add(e.getKey());
        spareBars.keySet()
            .retainAll(keys);
        return machines(t, y, w, z, hover);
    }

    /**
     * The GUI rectangle {x, y, w, h} of a row as last drawn, for the tour: "section:INPUTS", "resource:Hydrogen",
     * "machine:Large Chemical", "peak", "rate:Hydrogen", or "all" for the whole rail; null when it is not on screen.
     */
    int[] rowRect(final String id) {
        final com.cleanroommc.modularui.widget.sizer.Area a = getArea();
        if ("all".equals(id)) return new int[] { a.x, a.y, a.width, a.height };
        // A whole section, its header and every row under it ("block:MACHINES").
        if (id.startsWith("block:")) {
            final String title = id.substring(6);
            int[] out = null;
            boolean in = false;
            for (final Hit h : lastHits) {
                if (h.kind() == Kind.SECTION) {
                    if (in) break;
                    in = title.equals(h.data());
                }
                if (!in) continue;
                final int x0 = a.x + h.x0(), y0 = a.y + h.y0(), x1 = a.x + h.x1(), y1 = a.y + h.y1();
                out = out == null ? new int[] { x0, y0, x1, y1 }
                    : new int[] { Math.min(out[0], x0), Math.min(out[1], y0), Math.max(out[2], x1), Math.max(out[3], y1) };
            }
            return out == null ? null : new int[] { out[0], out[1], out[2] - out[0], out[3] - out[1] };
        }
        // A leftover's block ("spare:Oxygen"), or a part of it ("sparebar:Oxygen:FILL:0", "sparebar:Oxygen:SPLIT:-1"):
        // the first whose places have that name.
        if (id.startsWith("spare:") || id.startsWith("sparebar:")) {
            final String[] p = id.split(":");
            for (final Hit h : lastHits) {
                if (h.kind() != Kind.SPARE || !(h.data() instanceof final BoardSession.SpareLine line)) continue;
                boolean named = false;
                for (final BoardSession.SpareBearer b : line.bearers()) named |= b.name()
                    .contains(p[1]);
                if (!named) continue;
                if (p.length < 4) return new int[] { a.x + h.x0(), a.y + h.y0(), h.x1() - h.x0(), h.y1() - h.y0() };
                final SpareBar bar = spareBars.get(line.key());
                final float[] r = bar == null ? null
                    : bar.rectOf(SpareBar.Kind.valueOf(p[2]), Integer.parseInt(p[3]));
                return r == null ? null
                    : new int[] { Math.round(a.x + r[0]), Math.round(a.y + r[1]), Math.round(r[2] - r[0]),
                        Math.round(r[3] - r[1]) };
            }
            return null;
        }
        final int colon = id.indexOf(':');
        final String kind = colon < 0 ? id : id.substring(0, colon), what = colon < 0 ? "" : id.substring(colon + 1);
        for (final Hit h : lastHits) {
            final boolean match = switch (kind) {
                case "section" -> h.kind() == Kind.SECTION && what.equals(h.data());
                case "resource" -> h.kind() == Kind.RESOURCE && h.data() instanceof final BoardSession.TotalLine l
                    && l.label()
                        .contains(what);
                case "machine" -> h.kind() == Kind.MACHINE && h.data() instanceof final BoardSession.MachineLine m
                    && m.name()
                        .contains(what);
                case "group" -> h.kind() == Kind.GROUP && h.data() instanceof final String g && g.contains(what);
                case "peak" -> h.kind() == Kind.PEAK;
                case "rate" -> h.kind() == Kind.RATE && h.data() instanceof final DrawerModel d
                    && d.label.contains(what);
                default -> false;
            };
            if (match) return new int[] { a.x + h.x0(), a.y + h.y0(), h.x1() - h.x0(), h.y1() - h.y0() };
        }
        return null;
    }

    /** Hover is resolved against the previous frame's hit list, which is what is on screen. */
    private final List<Hit> lastHits = new ArrayList<>();

    private Hit hitAtMouseFromLastFrame() {
        if (!isHovering()) return null;
        final int x = localX(), y = localY();
        for (final Hit h : lastHits) if (h.contains(x, y)) return h;
        return null;
    }

    private void drawFolded(final int h) {
        Hyb.textCentered("»", W_FOLDED / 2f + 0.5f, 5, Hyb.INK);
        // The title runs down the strip.
        GL11.glPushMatrix();
        GL11.glTranslatef(3, 20, 0);
        GL11.glRotatef(90, 0, 0, 1);
        Hyb.text("OVERVIEW", 0, -9, Hyb.MUTED);
        GL11.glPopMatrix();
        hits.add(new Hit(Kind.FOLD, 0, 0, W_FOLDED, h, null));
        lastHits.clear();
        lastHits.addAll(hits);
    }

    private boolean matches(final String label) {
        final String f = filter.trim()
            .toLowerCase();
        return f.isEmpty() || label.toLowerCase()
            .contains(f);
    }

    private int section(final String title, final int count, final int shown, final int y, final int w, final int ink,
        final Hit hover) {
        final boolean isFolded = folded.contains(title);
        if (hover != null && hover.kind() == Kind.SECTION && title.equals(hover.data()))
            Hyb.rect(0, y, w - 1, SECTION_H, 0xFF30333A);
        else Hyb.rect(0, y, w - 1, SECTION_H, Hyb.seeThrough(0xFF2A2D33, RAIL_FLOOR));
        // A small triangle: pointing right when folded, down when open.
        if (isFolded) Hyb.triangle(5, y + 3, 5, y + 9, 8, y + 6, ink);
        else Hyb.triangle(4, y + 4, 10, y + 4, 7, y + 7, ink);
        Hyb.text(title, 13, y + 2, ink);
        final String badge = filter.isBlank() ? Integer.toString(count) : shown + " / " + count;
        final int tw = Hyb.width(title), bw = Hyb.width(badge) + 6;
        Hyb.rect(13 + tw + 4, y + 1, bw, 10, 0xFF353942);
        Hyb.text(badge, 13 + tw + 7, y + 2, 0xFFA3A3A3);
        hits.add(new Hit(Kind.SECTION, 0, y, w - 1, y + SECTION_H, title));
        return y + SECTION_H;
    }

    /** The rate unit at the end of the section bar just drawn above {@code y}, in the bar's type. */
    private void unitAtBar(final int y, final int w) {
        final String unit = session.rateUnit().suffix;
        Hyb.textRight(unit, w - 6, y - SECTION_H + 2, Hyb.MUTED);
    }

    /**
     * How much of each resource the plan's spares with a choice of place carry out ({@code importing} false) or in, by
     * resource, as the last solve has it: the section's lines leave that to the spare's own block.
     */
    private Map<String, Double> spared(final boolean importing) {
        final Map<String, Double> out = new HashMap<>();
        for (final BoardSession.SpareLine l : session.spares()) {
            if (l.importing() != importing || l.bearers()
                .size() < 2) continue;
            for (int i = 0; i < l.bearers()
                .size(); i++) {
                final BoardSession.SpareBearer b = l.bearers()
                    .get(i);
                out.merge(session.canonicalKey(b.resourceKey()), l.solvedShares()[i] * b.max(), Double::sum);
            }
        }
        return out;
    }

    /**
     * What an input or output line shows: what goes in or out less what the plan's spares with a choice carry (they
     * have blocks of their own); an internal line as it is.
     */
    private double railAmount(final BoardSession.TotalLine line) {
        final boolean in = inputs.contains(line), out = !in && session.totals()
            .outputs()
            .contains(line);
        if (!in && !out) return line.amount();
        return Math.max(0, line.amount() - spared(in).getOrDefault(line.key(), 0.0));
    }

    private int resources(final String title, final List<BoardSession.TotalLine> lines, int y, final int w,
        final float z, final int ink, final String sign, final String empty, final Hit hover, final Set<String> keys) {
        // What the plan's spares carry is theirs: a line left with nothing but that (and no drawer) is not listed here.
        final boolean internal = title.equals("INTERNAL"), sources = title.equals("INPUTS");
        final Map<String, Double> spared = internal ? Map.of() : spared(sources);
        final List<BoardSession.TotalLine> kept = new ArrayList<>();
        final Map<BoardSession.TotalLine, Double> amounts = new HashMap<>();
        for (final BoardSession.TotalLine line : lines) {
            final double left = Math.max(0, line.amount() - spared.getOrDefault(line.key(), 0.0));
            if (!internal && left <= Math.max(1e-6, line.amount() * 1e-4)
                && drawersFor(line, sources).isEmpty()
                && pinsFor(line, sources).isEmpty()) continue;
            kept.add(line);
            amounts.put(line, internal ? line.amount() : left);
        }
        final List<BoardSession.TotalLine> shown = new ArrayList<>();
        for (final BoardSession.TotalLine line : kept) if (matches(line.label())) shown.add(line);
        // The spares with a choice of place come after the lines, as blocks of their own.
        final List<BoardSession.SpareLine> groups = internal ? List.of() : groups(sources);
        y = section(title, kept.size() + groups.size(), shown.size() + groups.size(), y, w, ink, hover);
        // Every section's numbers in one column, left of where the drawer controls sit (empty on internal lines).
        final int rateRight = w - 6 - CONTROLS_W - 3;
        // The unit every rate below is in, at the header's end.
        if (!folded.contains(title)) unitAtBar(y, w);
        if (folded.contains(title)) return y + GAP;
        if (shown.isEmpty() && groups.isEmpty()) {
            Hyb.text(kept.isEmpty() ? empty : "No matches.", 8, y + 3, 0xFF6A6C74);
            return y + ROW_H + GAP;
        }
        final Fmt.RateUnit unit = session.rateUnit();
        final int tint = Hyb.seeThrough(ink & 0x00FFFFFF | 0x0D000000);
        for (final BoardSession.TotalLine line : shown) {
            final boolean hot = hover != null && hover.data() == line && hover.kind() == Kind.RESOURCE;
            final double amount = amounts.get(line);
            // One line per drawer or port: the resource's own line carries the first; any more get a short line each.
            final int setters = internal ? 0 : drawersFor(line, sources).size() + pinsFor(line, sources).size();
            final int rows = Math.max(1, setters);
            final int h = ROW_H + (rows - 1) * DRAWER_H;
            // Scrolled out of the list: nothing to draw (a long plan has hundreds of rows, and every icon costs).
            if (y + h <= LIST_Y || y >= getArea().height) {
                y += h;
                continue;
            }
            // What sets it: its drawers, then the ports set to a rate on their cards.
            final List<Object> drawers = new ArrayList<>(setters);
            if (!internal) {
                drawers.addAll(drawersFor(line, sources));
                drawers.addAll(pinsFor(line, sources));
            }
            Hyb.rect(0, y, w - 1, h, hot ? HOVER : tint);
            final boolean eu = Resources.isPower(line.key());
            if (eu) com.gtnhplanner.ui.card.RecipeCard.euIcon(ICON_X, y + 1, ICON);
            else Hyb.icon(line.item(), line.fluid(), ICON_X, y + 1, ICON, z);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            // What the plan moves, right-aligned against the controls so the numbers line up down the list.
            final String number = (amount > 0 ? sign : "") + Fmt.compact(eu ? amount / 20 : amount * unit.perSecond);
            // The time unit is the same on every line, so it is said once, in the section's header; litres go without
            // saying; EU is in EU/t.
            final String suffix = eu ? " EU/t" : "";
            final int rateW = Hyb.width(number) + Hyb.width(suffix) + 1;
            Hyb.text(Hyb.fit(line.label(), rateRight - rateW - 4 - NAME_X), NAME_X, y + 3, hot ? Hyb.GOLD : Hyb.INK);
            Hyb.text(number, rateRight - rateW, y + 3, amount > 0 ? ink : Hyb.MUTED);
            Hyb.text(suffix, rateRight - Hyb.width(suffix), y + 3, Hyb.MUTED);
            if (drawers.isEmpty() && !internal && !eu && !openPorts(line, sources).isEmpty())
                lineControls(line, y + 1, w, hover);
            if (!drawers.isEmpty()) {
                setterControls(drawers.get(0), y + 1, w, hover);
                for (int i = 1; i < drawers.size(); i++) {
                    final int dy = y + ROW_H + (i - 1) * DRAWER_H;
                    // Out of the list: a resource made in hundreds of places has a row for each.
                    if (dy + DRAWER_H <= LIST_Y || dy >= getArea().height) continue;
                    // A branch from the resource down to each further drawer.
                    final int bx = ICON_X + ICON / 2;
                    Hyb.rect(bx, y + ROW_H - 1, 1, dy - y - ROW_H + DRAWER_H / 2 + 1, Hyb.MUTED);
                    Hyb.rect(bx, dy + DRAWER_H / 2, 5, 1, Hyb.MUTED);
                    setterControls(drawers.get(i), dy, w, hover);
                }
            }
            hits.add(new Hit(Kind.RESOURCE, 0, y, w - 1, y + h, line));
            y += h;
        }
        if (!internal) y = spareGroups(sources, y, w, z, hover, keys);
        return y + GAP;
    }

    // region Spare

    /**
     * The plan's spares with a choice of place in one direction ({@code importing}: those brought in, under INPUTS;
     * else surpluses, under OUTPUTS), each a block of its own after the section's lines: a slim header ("Spare", Split,
     * Auto) and a line per place with a bar of its share where the drawer controls sit ({@link SpareBar}). Hovering a
     * place lights it on the board (and peeks there when it is off view). {@code keys} gathers the spares drawn.
     */
    private int spareGroups(final boolean importing, int y, final int w, final float z, final Hit hover,
        final Set<String> keys) {
        for (final BoardSession.SpareLine line : groups(importing)) {
            y += 2;
            // The slider the mouse is on (or dragging) stays where it is on screen while lines above it come and go:
            // the list scrolls by as much as it moved.
            if (line.key()
                .equals(anchorKey)) {
                final int at = y + scroll;
                if (anchorAt != Integer.MIN_VALUE && at != anchorAt) anchorShift += at - anchorAt;
                anchorAt = at;
            }
            keys.add(line.key());
            final SpareBar bar = spareBars.computeIfAbsent(line.key(), k -> new SpareBar());
            bar.barWidth(CONTROLS_W);
            final boolean mine = hover != null && hover.kind() == Kind.SPARE && hover.data() == line;
            final int h = SpareBar.height(line);
            Hyb.rect(0, y, w - 1, h, Hyb.seeThrough(SpareBar.tint(line)));
            bar.draw(session, line, SPARE_X, y, sliderW(w), z, mine ? bar.hitAt(localX(), localY()) : null);
            hits.add(new Hit(Kind.SPARE, 0, y, w - 1, y + h, line));
            y += h;
        }
        return y;
    }

    /** The spares with a choice of place in one direction that the filter lets through. */
    private List<BoardSession.SpareLine> groups(final boolean importing) {
        final List<BoardSession.SpareLine> out = new ArrayList<>();
        for (final BoardSession.SpareLine line : session.spares()) {
            if (line.importing() != importing || line.bearers()
                .size() < 2) continue;
            boolean match = false;
            for (final BoardSession.SpareBearer b : line.bearers()) match |= matches(b.name());
            if (match) out.add(line);
        }
        return out;
    }

    private static final int SPARE_X = 8;
    /**
     * The slider kept still on screen (the one under the mouse or being dragged), where it was, and how far it moved.
     */
    private String anchorKey;
    /** When the wheel last scrolled the list (ms): while it is scrolling, fields it passes over do not take it. */
    private long listWheelAt;
    private int anchorAt = Integer.MIN_VALUE, anchorShift;
    /** The numbers the list was last laid out with: new ones may move the slider kept still. */
    private BoardSession.Totals laidTotals;
    private List<BoardSession.SpareLine> laidSpares;
    /** One slider per leftover, kept by its key: each remembers what it drew last (for hits) and an edge in hand. */
    private final Map<String, SpareBar> spareBars = new LinkedHashMap<>();

    /** How wide a spare's lines are: from the icons to where the drawer controls end. */
    private static int sliderW(final int w) {
        return w - 6 - SPARE_X;
    }

    /** The slider and what on it is under the mouse, for a hit on a leftover; null for anything else. */
    private SpareBar.Hit spareHitAt(final Hit hit) {
        if (hit == null || hit.kind() != Kind.SPARE) return null;
        final SpareBar bar = spareBars.get(((BoardSession.SpareLine) hit.data()).key());
        return bar == null ? null : bar.hitAt(localX(), localY());
    }

    /** The slider whose edge is in hand, if any. */
    private SpareBar dragging() {
        for (final SpareBar bar : spareBars.values()) if (bar.dragging()) return bar;
        return null;
    }

    /**
     * Tells the session which place the mouse is on (its card and rows light up on the board), and after a short rest
     * peeks the board at it when it is off view. Leaving the overview onto the board keeps that view; leaving anywhere
     * else puts the board back.
     */
    private void hoverSpare(final Hit hover, final int w) {
        BoardSession.SpareLine line = null;
        BoardSession.SpareBearer bearer = null;
        final int place = SpareBar.place(spareHitAt(hover));
        if (place >= 0) {
            line = (BoardSession.SpareLine) hover.data();
            bearer = line.bearers()
                .get(place);
        }
        session.setSpareHover(line, bearer, getArea().x + w, getArea().y + localY());
    }

    // endregion

    /** The input rows drawn this frame, to tell an input's "+" (source) from an output's (product). */
    private final java.util.Set<Object> inputs = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private boolean hasDrawer(final BoardSession.TotalLine line, final boolean sources) {
        return !drawersFor(line, sources).isEmpty();
    }

    /** The drawers holding a resource on one side (sources for inputs, products for outputs) that take a rule. */
    private List<DrawerModel> drawersFor(final BoardSession.TotalLine line, final boolean sources) {
        return index().drawers(sources)
            .getOrDefault(line.key(), List.of());
    }

    /**
     * The ports on one side set to a rate on their cards and holding a resource (inputs for inputs, outputs for
     * outputs), where nothing is wired to them: there it is the plan's edge, as a drawer is.
     */
    private List<PortPin> pinsFor(final BoardSession.TotalLine line, final boolean sources) {
        return index().pins(sources)
            .getOrDefault(line.key(), List.of());
    }

    /** The ports of a line's resource on one side with nothing wired to them and no drawer. */
    private List<OpenPort> openPorts(final BoardSession.TotalLine line, final boolean sources) {
        return index().open(sources)
            .getOrDefault(line.key(), List.of());
    }

    /**
     * What sets each resource, by its canonical key and side: its drawers, the ports set to a rate with nothing wired,
     * and the open ports. Worked out once per state of the plan (the list asks for every line every frame, and a plan
     * of thousands of cards made that the frame's whole cost).
     */
    private record Index(Object graph, long version, Object models, Object drawerModels, Object totals,
        Map<String, List<DrawerModel>> sourceDrawers, Map<String, List<DrawerModel>> productDrawers,
        Map<String, List<PortPin>> inputPins, Map<String, List<PortPin>> outputPins,
        Map<String, List<OpenPort>> openInputs, Map<String, List<OpenPort>> openOutputs) {

        Map<String, List<DrawerModel>> drawers(final boolean sources) {
            return sources ? sourceDrawers : productDrawers;
        }

        Map<String, List<PortPin>> pins(final boolean sources) {
            return sources ? inputPins : outputPins;
        }

        Map<String, List<OpenPort>> open(final boolean sources) {
            return sources ? openInputs : openOutputs;
        }
    }

    private Index index;

    private Index index() {
        final com.gtnhplanner.data.flowchart.Graph graph = session.graph();
        final Index was = index;
        if (was != null && was.graph() == graph
            && was.version() == graph.version()
            && was.models() == session.models()
            && was.drawerModels() == session.drawerModels()
            && was.totals() == session.totals()) return was;
        final Map<String, List<DrawerModel>> sourceDrawers = new HashMap<>(), productDrawers = new HashMap<>();
        for (final DrawerModel d : session.drawerModels()
            .values()) {
            if (!d.kind.hasRule()) continue;
            (d.kind == Drawer.Kind.SOURCE ? sourceDrawers : productDrawers)
                .computeIfAbsent(session.canonicalKey(d.drawer.getResourceKey()), k -> new ArrayList<>())
                .add(d);
        }
        // Every wired port, and every port a drawer holds, once.
        final Set<String> wired = new java.util.HashSet<>(), held = new java.util.HashSet<>();
        for (final com.gtnhplanner.data.flowchart.Edge e : graph.getEdges()) {
            wired.add(portKey(e.targetNodeId, true, e.targetInputIndex));
            wired.add(portKey(e.sourceNodeId, false, e.sourceOutputIndex));
        }
        for (final Drawer d : graph.getDrawers()) {
            final boolean input = d.getKind()
                .linksInputs();
            for (final Drawer.Link l : d.getLinks()) held.add(portKey(l.nodeId(), input, l.portIndex()));
        }
        final Map<String, List<PortPin>> inputPins = new HashMap<>(), outputPins = new HashMap<>();
        for (final com.gtnhplanner.data.flowchart.Node n : graph.getNodes()) {
            for (final boolean sources : new boolean[] { true, false }) {
                final Map<Integer, Double> rates = sources ? n.targetInputRates : n.targetOutputRates;
                if (rates.isEmpty()) continue;
                final List<com.gtnhplanner.data.flowchart.Port<?>> ports = sources ? n.inputs : n.outputs;
                for (final Map.Entry<Integer, Double> r : rates.entrySet()) {
                    final int i = r.getKey();
                    if (r.getValue() == null || r.getValue() <= 0 || i < 0 || i >= ports.size()) continue;
                    if (wired.contains(portKey(n.id, sources, i))) continue;
                    final CardModel m = session.model(n.id);
                    (sources ? inputPins : outputPins)
                        .computeIfAbsent(session.canonicalKey(Resources.key(ports.get(i))), k -> new ArrayList<>())
                        .add(new PortPin(n, !sources, i, r.getValue(), m != null ? m.machineName : n.machineName));
                }
            }
        }
        final Map<String, List<OpenPort>> openInputs = new HashMap<>(), openOutputs = new HashMap<>();
        for (final CardModel m : session.models()
            .values()) {
            for (final boolean sources : new boolean[] { true, false }) {
                for (final CardModel.PortView p : sources ? m.inputs : m.outputs) {
                    if (p.wired() || held.contains(portKey(m.node.id, sources, p.index()))) continue;
                    (sources ? openInputs : openOutputs)
                        .computeIfAbsent(session.canonicalKey(p.key()), k -> new ArrayList<>())
                        .add(new OpenPort(m.node, !sources, p.index(), p.perSecond(), m.machineName));
                }
            }
        }
        index = new Index(
            graph,
            graph.version(),
            session.models(),
            session.drawerModels(),
            session.totals(),
            sourceDrawers,
            productDrawers,
            inputPins,
            outputPins,
            openInputs,
            openOutputs);
        return index;
    }

    private static String portKey(final UUID node, final boolean input, final int index) {
        return node + (input ? "<" : ">") + index;
    }

    /** A line nothing sets yet: the rule key on Any and a "Set" box, as a port's on the card. */
    private void lineControls(final BoardSession.TotalLine line, final int y, final int w, final Hit hover) {
        final int bx = w - 6 - BOX_W, rx = bx - 2 - RULE_W, h = 11;
        final boolean hot = hover != null && hover.kind() == Kind.LINE_SET && hover.data() == line;
        Hyb.rect(rx, y, RULE_W, h, 0xFF111317);
        Hyb.rect(rx + 1, y + 1, RULE_W - 2, h - 2, 0xFF34373E);
        Hyb.rect(rx + 1, y + 1, RULE_W - 2, 1, 0x1FFFFFFF);
        DrawerCard.drawRuleMark(
            Drawer.Rule.ANY,
            rx + (RULE_W - Hyb.width(DrawerCard.ruleMark(Drawer.Rule.ANY)) + 1) / 2f,
            y + 2,
            Hyb.MUTED);
        hits.add(new Hit(Kind.LINE_SET, rx, y, bx + BOX_W, y + h, line));
        if (edit.editing(lineEditKey(line))) {
            edit.drawBox(bx, y, BOX_W, h, y + 2);
            return;
        }
        Hyb.rect(bx, y, BOX_W, h, hot ? Hyb.GOLD : 0xFF5A5E68);
        Hyb.rect(bx + 1, y + 1, BOX_W - 2, h - 2, hot ? 0xFF15171C : 0xFF0F1114);
        Hyb.rect(bx + 1, y + 1, BOX_W - 2, 1, 0xB3000000);
        Hyb.textCentered("Set", bx + BOX_W / 2f, y + 2, 0xFF6F737C);
    }

    private String lineEditKey(final BoardSession.TotalLine line) {
        return "line:" + (inputs.contains(line) ? "in:" : "out:") + line.key();
    }

    /** A drawer's controls, or a port's set on its card. */
    private void setterControls(final Object setter, final int y, final int w, final Hit hover) {
        if (setter instanceof final DrawerModel d) drawerControls(d, y, w, hover);
        else if (setter instanceof final PortPin p) portControls(p, y, w, hover);
    }

    /** A port's rate set on its card, as a drawer's controls: its = key, and the rate in gold. */
    private void portControls(final PortPin p, final int y, final int w, final Hit hover) {
        final int bx = w - 6 - BOX_W, rx = bx - 2 - RULE_W, h = 11;
        final boolean ruleHot = hover != null && hover.kind() == Kind.PORT_RULE && hover.data() == p;
        final boolean rateHot = hover != null && hover.kind() == Kind.PORT_RATE && hover.data() == p;
        Hyb.rect(rx, y, RULE_W, h, 0xFF111317);
        Hyb.rect(rx + 1, y + 1, RULE_W - 2, h - 2, ruleHot ? 0xFF454952 : 0xFF34373E);
        Hyb.rect(rx + 1, y + 1, RULE_W - 2, 1, 0x1FFFFFFF);
        DrawerCard.drawRuleMark(
            Drawer.Rule.EXACTLY,
            rx + (RULE_W - Hyb.width(DrawerCard.ruleMark(Drawer.Rule.EXACTLY)) + 1) / 2f,
            y + 2,
            Hyb.GOLD);
        hits.add(new Hit(Kind.PORT_RULE, rx, y, rx + RULE_W, y + h, p));
        hits.add(new Hit(Kind.PORT_RATE, bx, y, bx + BOX_W, y + h, p));
        if (edit.editing(portEditKey(p))) {
            edit.drawBox(bx, y, BOX_W, h, y + 2);
            return;
        }
        Hyb.rect(bx, y, BOX_W, h, rateHot ? Hyb.GOLD : 0xFF5A5E68);
        Hyb.rect(bx + 1, y + 1, BOX_W - 2, h - 2, rateHot ? 0xFF15171C : 0xFF0F1114);
        Hyb.rect(bx + 1, y + 1, BOX_W - 2, 1, 0xB3000000);
        final Double wheeled = session.wheeledPortRate(p.node(), p.output(), p.index());
        final double rate = wheeled != null ? wheeled : p.rate();
        Hyb.textCentered(
            Hyb.fit(Fmt.compact(rate * session.rateUnit().perSecond), BOX_W - 7),
            bx + BOX_W / 2f,
            y + 2,
            Hyb.GOLD);
    }

    private static String portEditKey(final PortPin p) {
        return "port:" + p.node().id + ":" + p.output() + ":" + p.index();
    }

    /** The wheel on a port's rate in the overview: as on the card (Ctrl x10, Shift x100). */
    private void stepPortRate(final PortPin p, final int direction) {
        final Fmt.RateUnit unit = session.rateUnit();
        final boolean ctrl = org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_LCONTROL)
            || org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_RCONTROL);
        final boolean shift = org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_LSHIFT)
            || org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_RSHIFT);
        final Double wheeled = session.wheeledPortRate(p.node(), p.output(), p.index());
        final double now = (wheeled != null ? wheeled : p.rate()) * unit.perSecond;
        final double next = Math.max(0, Math.round(now) + direction * (shift ? 100 : ctrl ? 10 : 1));
        session.wheelPortRate(p.node(), p.output(), p.index(), next / unit.perSecond);
    }

    /** The rule key and the rate box, as the drawer card wears them, at the right of a line. */
    private static final int RULE_W = 12, BOX_W = COUNT_W, CONTROLS_W = RULE_W + 2 + BOX_W;

    private void drawerControls(final DrawerModel d, final int y, final int w, final Hit hover) {
        final int bx = w - 6 - BOX_W, rx = bx - 2 - RULE_W, h = 11;
        final boolean ruleHot = hover != null && hover.kind() == Kind.RULE && hover.data() == d;
        final boolean rateHot = hover != null && hover.kind() == Kind.RATE && hover.data() == d;
        // The rule: its mark in gold (muted for Any), in the middle of a small dark key.
        final int mark = d.rule == Drawer.Rule.ANY ? Hyb.MUTED : Hyb.GOLD;
        Hyb.rect(rx, y, RULE_W, h, 0xFF111317);
        Hyb.rect(rx + 1, y + 1, RULE_W - 2, h - 2, ruleHot ? 0xFF454952 : 0xFF34373E);
        Hyb.rect(rx + 1, y + 1, RULE_W - 2, 1, 0x1FFFFFFF);
        DrawerCard.drawRuleMark(d.rule, rx + (RULE_W - Hyb.width(DrawerCard.ruleMark(d.rule)) + 1) / 2f, y + 2, mark);
        hits.add(new Hit(Kind.RULE, rx, y, rx + RULE_W, y + h, d));
        hits.add(new Hit(Kind.RATE, bx, y, bx + BOX_W, y + h, d));
        // The rate: being typed into; or a dark box, the number in gold; red when it cannot be reached.
        if (edit.editing("drawer:" + d.drawer.getId())) {
            edit.drawBox(bx, y, BOX_W, h, y + 2);
            return;
        }
        Hyb.rect(bx, y, BOX_W, h, d.unmet ? 0xBFF87171 : rateHot ? Hyb.GOLD : 0xFF5A5E68);
        Hyb.rect(bx + 1, y + 1, BOX_W - 2, h - 2, rateHot ? 0xFF15171C : 0xFF0F1114);
        Hyb.rect(bx + 1, y + 1, BOX_W - 2, 1, 0xB3000000);
        final Double wheeled = session.wheeledRate(d.drawer.getId());
        final double target = wheeled != null ? wheeled : d.target;
        final boolean empty = wheeled != null ? wheeled <= 0 : d.rule == Drawer.Rule.ANY || d.target <= 0;
        if (empty) Hyb.textCentered("Set", bx + BOX_W / 2f, y + 2, 0xFF6F737C);
        else {
            final Fmt.RateUnit unit = session.rateUnit();
            // In the line's own unit, as the number beside it; centred in the box, as on the board.
            Hyb.textCentered(
                Hyb.fit(Fmt.compact(d.shown(target, unit)), BOX_W - 7),
                bx + BOX_W / 2f,
                y + 2,
                d.unmet ? 0xFFF87171 : Hyb.GOLD);
        }
    }

    /**
     * The machine lines grouped by machine, and the whole machines in all and shown, for a set of totals and filter.
     */
    private record MachineGroups(Object totals, String filter, Map<String, List<BoardSession.MachineLine>> groups,
        int whole, int shown) {}

    private MachineGroups machineGroups;

    private int machines(final BoardSession.Totals t, int y, final int w, final float z, final Hit hover) {
        if (machineGroups == null || machineGroups.totals() != t
            || !machineGroups.filter()
                .equals(filter)) {
            final Map<String, List<BoardSession.MachineLine>> made = new LinkedHashMap<>();
            for (final BoardSession.MachineLine m : t.machines()) {
                if (matches(m.name())) made.computeIfAbsent(m.name(), k -> new ArrayList<>())
                    .add(m);
            }
            // Whole machines to build: all of them, and those the filter lets through.
            int all = 0, through = 0;
            for (final BoardSession.MachineLine m : t.machines()) {
                final int n = (int) Math.ceil(m.machines() - 1e-9);
                all += n;
                if (made.containsKey(m.name())) through += n;
            }
            machineGroups = new MachineGroups(t, filter, made, all, through);
        }
        final Map<String, List<BoardSession.MachineLine>> groups = machineGroups.groups();
        final int whole = machineGroups.whole(), shown = machineGroups.shown();
        y = section("MACHINES", whole, shown, y, w, Hyb.INK, hover);
        // Peak / average, at the right of the section heading.
        final int px = w - 6 - TOGGLE_W;
        final boolean peakHot = hover != null && hover.kind() == Kind.PEAK;
        Hyb.bevel(
            px,
            y - SECTION_H + 1,
            TOGGLE_W,
            10,
            peakHot ? Hyb.KEY_HOVER : 0xFF202329,
            Hyb.KEY_HI,
            Hyb.KEY_LO,
            0,
            1);
        final String peak = session.peakPower() ? "Peak" : "Avg";
        Hyb.textCentered(peak, px + TOGGLE_W / 2f, y - SECTION_H + 2, Hyb.INK);
        // Ahead of the heading's own hit, which spans the bar: the first hit under the mouse takes the click, and the
        // key's is on top (behind the heading, a click on the key folded the section).
        hits.add(hits.size() - 1, new Hit(Kind.PEAK, px, y - SECTION_H + 1, px + TOGGLE_W, y - 1, null));
        // What the machines draw, in the bar beside the key.
        Hyb.textRight(Fmt.power(t.euPerTick()) + " EU/t", px - 5, y - SECTION_H + 2, Hyb.MUTED);
        if (folded.contains("MACHINES")) return y + GAP;

        if (t.euMade() > 0) {
            // With generators on the board: what they make, and what is left (what the machines use is in the bar).
            final double net = t.euMade() - t.euPerTick();
            Hyb.text("Made", 8, y + 3, Hyb.MUTED);
            Hyb.textRight(Fmt.power(t.euMade()) + " EU/t", w - 6, y + 3, 0xFFFCD34D);
            y += ROW_H;
            Hyb.text("Net", 8, y + 3, Hyb.MUTED);
            Hyb.textRight(
                (net >= 0 ? "+" : "-") + Fmt.power(Math.abs(net)) + " EU/t",
                w - 6,
                y + 3,
                net >= 0 ? Hyb.OK_INK : Hyb.RED_INK);
            y += ROW_H + 2;
        }
        if (groups.isEmpty()) {
            Hyb.text(
                t.machines()
                    .isEmpty() ? "No machines yet." : "No matches.",
                8,
                y + 3,
                0xFF6A6C74);
            return y + ROW_H + GAP;
        }
        // One block per machine, as the resources have a line each: a faint band, the machine's name, then a line for
        // each card it runs on: its tier, what it draws, how many it takes, and the count field (pinned, or not yet).
        final int band = Hyb.seeThrough(0x0DE8E9EE);
        // The count field sits where a drawer's rate box does, with room for the rule key before it.
        final int fieldX = w - 6 - COUNT_W, keyX = fieldX - 2 - RULE_W, numberRight = keyX - 3;
        for (final Map.Entry<String, List<BoardSession.MachineLine>> g : groups.entrySet()) {
            final List<BoardSession.MachineLine> lines = g.getValue();
            // A group scrolled out of the list: skip its drawing, keep its room.
            final int groupH = ROW_H + lines.size() * ROW_H;
            if (y + groupH + 1 <= LIST_Y || y >= getArea().height) {
                y += groupH + 1;
                continue;
            }
            Hyb.rect(0, y, w - 1, groupH, band);
            final boolean groupHot = hover != null && hover.kind() == Kind.GROUP
                && g.getKey()
                    .equals(hover.data());
            if (groupHot) Hyb.rect(0, y, w - 1, ROW_H, HOVER);
            if (y + ROW_H > LIST_Y) Hyb.icon(
                lines.get(0)
                    .stack(),
                null,
                ICON_X,
                y + 1.5f,
                ICON,
                z);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            Hyb.text(Hyb.fit(g.getKey(), w - 6 - NAME_X), NAME_X, y + 3, Hyb.INK);
            hits.add(new Hit(Kind.GROUP, 0, y, w - 1, y + ROW_H, g.getKey()));
            y += ROW_H;
            for (final BoardSession.MachineLine m : lines) {
                // A line scrolled out of the list keeps its room and draws nothing (a machine on hundreds of cards).
                if (y + ROW_H <= LIST_Y || y >= getArea().height) {
                    y += ROW_H;
                    continue;
                }
                final boolean pinHot = hover != null && hover.kind() == Kind.PIN && hover.data() == m;
                final boolean countHot = hover != null && hover.kind() == Kind.COUNT && hover.data() == m;
                final boolean ruleHot = hover != null && hover.kind() == Kind.MACHINE_RULE && hover.data() == m;
                final boolean hot = hover != null && hover.kind() == Kind.MACHINE && hover.data() == m || pinHot
                    || countHot
                    || ruleHot;
                if (hot) Hyb.rect(0, y, w - 1, ROW_H, HOVER);
                final com.gtnhplanner.ui.card.RecipeCard card = cardOf(m.nodeId());
                // The pin key, under the machine's icon: gold while the card is placed or on the pin list, else on
                // hover.
                final int pinX = ICON_X + ICON / 2 - PIN_W / 2;
                final boolean pinned = card != null && card.pinnedAnywhere();
                if (card != null && (pinned || hot)) {
                    if (pinHot) Hyb.rect(pinX, y, PIN_W, ROW_H, Hyb.KEY_HOVER);
                    com.gtnhplanner.ui.card.RecipeCard
                        .pinIcon(pinX + PIN_W / 2f, y + ROW_H / 2f, pinned ? Hyb.GOLD : pinHot ? Hyb.INK : Hyb.MUTED);
                }
                if (card != null) hits.add(new Hit(Kind.PIN, pinX, y, pinX + PIN_W, y + ROW_H, m));
                int cx = NAME_X;
                // A steam machine has no tier (CardModel.steam).
                if (m.gregtech() && !m.tier()
                    .isEmpty())
                    cx += Hyb.tierTag(m.tier(), m.multiblock() && m.amps() > 1 ? m.amps() + "A" : null, cx, y + 2) + 4;
                // How many it takes, then the count field: as the card's count, the wheel and a click set it here too;
                // a count being wheeled shows in the field.
                final long wheeled = card == null ? -1 : card.wheeledCount();
                final String count = "×" + Fmt.machines(m.machines());
                final int countW = Hyb.width(count);
                Hyb.text(count, numberRight - countW, y + 3, Hyb.INK);
                final boolean set = wheeled > 0 || wheeled < 0 && m.pinned();
                // The count's rule, as a drawer's: Any (greyed) while the plan decides how many, = (gold) once pinned.
                // A click switches: Any pins it at the whole machines it takes now, = lets the plan decide again.
                final Drawer.Rule rule = set ? Drawer.Rule.EXACTLY : Drawer.Rule.ANY;
                Hyb.rect(keyX, y + 1, RULE_W, 11, 0xFF111317);
                Hyb.rect(keyX + 1, y + 2, RULE_W - 2, 9, ruleHot ? 0xFF454952 : 0xFF34373E);
                Hyb.rect(keyX + 1, y + 2, RULE_W - 2, 1, 0x1FFFFFFF);
                DrawerCard.drawRuleMark(
                    rule,
                    keyX + (RULE_W - Hyb.width(DrawerCard.ruleMark(rule)) + 1) / 2f,
                    y + 3,
                    set ? Hyb.GOLD : Hyb.MUTED);
                if (card != null) hits.add(new Hit(Kind.MACHINE_RULE, keyX, y, keyX + RULE_W, y + ROW_H, m));
                // The count field: being typed into; or the pinned count in gold, or "Set".
                if (edit.editing("count:" + m.nodeId())) edit.drawBox(fieldX, y + 1, COUNT_W, 11, y + 3);
                else {
                    Hyb.rect(fieldX, y + 1, COUNT_W, 11, countHot ? Hyb.GOLD : 0xFF5A5E68);
                    Hyb.rect(fieldX + 1, y + 2, COUNT_W - 2, 9, countHot ? 0xFF15171C : 0xFF0F1114);
                    Hyb.rect(fieldX + 1, y + 2, COUNT_W - 2, 1, 0xB3000000);
                    if (set) Hyb.textCentered(
                        wheeled > 0 ? Long.toString(wheeled) : Fmt.machines(count(m)),
                        fieldX + COUNT_W / 2f,
                        y + 3,
                        Hyb.GOLD);
                    else Hyb.textCentered("Set", fieldX + COUNT_W / 2f, y + 3, 0xFF6F737C);
                }
                if (card != null) hits.add(new Hit(Kind.COUNT, fieldX, y, fieldX + COUNT_W, y + ROW_H, m));
                // What it draws, grey beside the tier (the unit dropped when there is no room for it).
                final String eu = m.tooLow() ? "TIER!"
                    : m.madeEuPerTick() > 0 ? "+" + Fmt.power(m.madeEuPerTick()) : Fmt.power(m.euPerTick());
                final int euInk = m.tooLow() ? Hyb.RED_INK : m.madeEuPerTick() > 0 ? 0xFFE0B04A : Hyb.MUTED;
                final int room = numberRight - countW - 4 - cx;
                final String euText = m.tooLow() || Hyb.width(eu + " EU/t") > room ? eu : eu + " EU/t";
                Hyb.text(Hyb.fit(euText, room), cx, y + 3, euInk);
                hits.add(new Hit(Kind.MACHINE, 0, y, w - 1, y + ROW_H, m));
                y += ROW_H;
            }
            y += 1;
        }
        return y + GAP;
    }

    // endregion

    // region Mouse

    /** The scroll bar's width, and its state as last drawn: the thumb's height, how far it travels, the most scroll. */
    private static final int BAR_W = 5;
    private int barThumb, barTrack, scrollMax;
    /** Dragging the thumb, and where on it it was taken. */
    private boolean draggingBar;
    private int barGrab;

    /** Scrolls so the thumb's top is at {@code thumbTop} (rail-local). */
    private void scrollBarTo(final int thumbTop) {
        if (barTrack <= 0) return;
        final int at = Math.max(0, Math.min(barTrack, thumbTop - LIST_Y));
        scroll = Math.round((float) at * scrollMax / barTrack);
    }

    @Override
    public void onMouseDrag(final int mouseButton, final long timeSinceClick) {
        if (draggingBar) scrollBarTo(localMouseY() - barGrab);
        final SpareBar edge = dragging();
        if (edge != null) edge.drag(session, localX(), SPARE_X, sliderW(getArea().width));
    }

    @Override
    public boolean onMouseRelease(final int mouseButton) {
        final SpareBar edge = dragging();
        if (edge != null) {
            edge.release(session);
            return true;
        }
        if (!draggingBar) return false;
        draggingBar = false;
        return true;
    }

    private int localMouseY() {
        return getContext().getAbsMouseY() - getArea().y;
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        // A press anywhere on the overview sets what was being typed.
        edit.commit();
        final Hit hit = hitAtMouseFromLastFrame();
        if (hit == null) return open ? Result.SUCCESS : Result.IGNORE;
        if (hit.kind() == Kind.SCROLLBAR) {
            if (mouseButton != 0) return Result.SUCCESS;
            // On the thumb it is taken where it was grabbed; on the track the thumb jumps to centre on the mouse.
            final int y = localMouseY(), top = LIST_Y + (scrollMax <= 0 ? 0 : barTrack * scroll / scrollMax);
            barGrab = y >= top && y < top + barThumb ? y - top : barThumb / 2;
            draggingBar = true;
            scrollBarTo(y - barGrab);
            return Result.SUCCESS;
        }
        // The middle button goes to what a line is about, anywhere in the overview (on a rule or a rate it clears it).
        if (hit.kind() == Kind.LINE_SET) {
            if (mouseButton != 0) return Result.SUCCESS;
            final BoardSession.TotalLine line = (BoardSession.TotalLine) hit.data();
            final boolean source = inputs.contains(line);
            final Fmt.RateUnit unit = session.rateUnit();
            Hyb.click();
            edit.start(
                lineEditKey(line),
                railAmount(line) * unit.perSecond,
                v -> session.setLineRate(line.key(), line.label(), source, v / unit.perSecond));
            getContext().focus(this);
            return Result.SUCCESS;
        }
        if (hit.kind() == Kind.PORT_RULE || hit.kind() == Kind.PORT_RATE) {
            final PortPin p = (PortPin) hit.data();
            if (mouseButton == 2 || mouseButton == 0 && hit.kind() == Kind.PORT_RULE) {
                Hyb.click();
                session.setPortTarget(p.node(), p.output(), p.index(), 0);
            } else if (mouseButton == 0) {
                Hyb.click();
                final Fmt.RateUnit unit = session.rateUnit();
                edit.start(
                    portEditKey(p),
                    p.rate() * unit.perSecond,
                    v -> session.setPortTarget(p.node(), p.output(), p.index(), v / unit.perSecond));
                getContext().focus(this);
            }
            return Result.SUCCESS;
        }
        if (mouseButton == 2 && hit.kind() != Kind.RULE && hit.kind() != Kind.RATE) {
            if (focus(hit)) Hyb.click();
            return Result.SUCCESS;
        }
        if (hit.kind() == Kind.SPARE) {
            if (mouseButton != 0) return Result.SUCCESS;
            final BoardSession.SpareLine line = (BoardSession.SpareLine) hit.data();
            final SpareBar bar = spareBars.get(line.key());
            final SpareBar.Hit at = spareHitAt(hit);
            if (bar != null && !bar.press(session, line, at, localX(), SPARE_X, sliderW(getArea().width))
                && bar.click(session, line, at)) Hyb.click();
            return Result.SUCCESS;
        }
        // Middle-click on a rule or rate clears the drawer back to "Set rate", as on the website.
        if (mouseButton == 2 && (hit.kind() == Kind.RULE || hit.kind() == Kind.RATE)) {
            Hyb.click();
            session.setDrawerRate(((DrawerModel) hit.data()).drawer, 0);
            return Result.SUCCESS;
        }
        if (mouseButton == 1 && hit.kind() == Kind.RESOURCE) {
            Hyb.click();
            session.showSetupsMaking((BoardSession.TotalLine) hit.data());
            return Result.SUCCESS;
        }
        if (mouseButton == 1 && hit.kind() == Kind.PIN) {
            final com.gtnhplanner.ui.card.RecipeCard card = cardOf(((BoardSession.MachineLine) hit.data()).nodeId());
            if (card != null) card.clearPins();
            return Result.SUCCESS;
        }
        // A right-click steps a rule back, and flips Avg and Peak as a left one does (the top bar's key does the same).
        if (mouseButton != 0 && hit.kind() != Kind.RULE && hit.kind() != Kind.PEAK) return Result.SUCCESS;
        final int sx = getArea().x + hit.x0(), sy = getArea().y + hit.y1() + 2;
        if (hit.kind() != Kind.RESOURCE) Hyb.click();
        switch (hit.kind()) {
            case FOLD -> toggle();
            case ADD -> {
                final BoardSession.TotalLine line = (BoardSession.TotalLine) hit.data();
                session.addDrawerFor(line.key(), line.label(), inputs.contains(line));
            }
            case SECTION -> {
                final String title = (String) hit.data();
                if (!folded.remove(title)) folded.add(title);
                PlannerSettings.setOverviewFolded(folded);
            }
            case RULE -> {
                final DrawerModel d = (DrawerModel) hit.data();
                if (mouseButton == 1) {
                    final Drawer.Rule[] rules = Drawer.Rule.values();
                    session.setDrawerRule(d.drawer, rules[(d.rule.ordinal() + rules.length - 1) % rules.length]);
                } else DrawerCard.openRules(getPanel(), session, d, sx, sy);
            }
            case RATE -> {
                final DrawerModel d = (DrawerModel) hit.data();
                final Fmt.RateUnit unit = session.rateUnit();
                edit.start(
                    "drawer:" + d.drawer.getId(),
                    d.rule == Drawer.Rule.ANY ? 0 : d.shown(d.target, unit),
                    v -> session.setDrawerRate(d.drawer, v / d.shown(1, unit)));
                getContext().focus(this);
            }
            case PEAK -> session.togglePeakPower();
            case GROUP -> flyToGroup((String) hit.data());
            case PIN -> {
                final com.gtnhplanner.ui.card.RecipeCard card = cardOf(
                    ((BoardSession.MachineLine) hit.data()).nodeId());
                if (card != null) card.openPlaceMenu(sx, sy);
            }
            case MACHINE -> canvas.frame(List.of(((BoardSession.MachineLine) hit.data()).nodeId()));
            case MACHINE_RULE -> {
                final BoardSession.MachineLine m = (BoardSession.MachineLine) hit.data();
                final com.gtnhplanner.ui.card.RecipeCard card = cardOf(m.nodeId());
                if (card != null && card.model() != null)
                    session.pin(card.model().node, m.pinned() ? 0 : Math.max(1, Math.ceil(count(m) - 1e-9)));
            }
            case COUNT -> {
                final BoardSession.MachineLine m = (BoardSession.MachineLine) hit.data();
                final com.gtnhplanner.ui.card.RecipeCard card = cardOf(m.nodeId());
                if (card != null && card.model() != null) {
                    final com.gtnhplanner.data.flowchart.Node node = card.model().node;
                    edit.start("count:" + m.nodeId(), m.pinned() ? count(m) : 0, v -> session.pin(node, v));
                    getContext().focus(this);
                }
            }
        }
        return Result.SUCCESS;
    }

    /**
     * What a machine line's count field shows and sets: the card's own count. A crop card's is the crops planted,
     * while the line's ×count is the harvesters they fill (issue 8).
     */
    private double count(final BoardSession.MachineLine m) {
        final com.gtnhplanner.ui.card.CardModel card = session.model(m.nodeId());
        return card != null ? card.machines : m.machines();
    }

    /** Whether a machine line is a crop card's, whose count field is crops planted. */
    private boolean crop(final BoardSession.MachineLine m) {
        final com.gtnhplanner.ui.card.CardModel card = session.model(m.nodeId());
        return card != null && com.gtnhplanner.machines.game.CropCards.isCrop(card.node);
    }

    /** The card a recipe is on: its own, or the shared machine it is a recipe of. */
    @org.jetbrains.annotations.Nullable
    private com.gtnhplanner.ui.card.RecipeCard cardOf(final java.util.UUID node) {
        final com.gtnhplanner.ui.card.RecipeCard own = canvas.cards()
            .get(node);
        if (own != null) return own;
        for (final com.gtnhplanner.ui.card.RecipeCard card : canvas.cards()
            .values()) if (card.sectionOf(node) >= 0) return card;
        return null;
    }

    /**
     * Goes to what a line is about: a resource's cards (the next each time), a spare's place's card, a machine's card
     * (or, on its name, the next of its cards). False when there is nothing to go to.
     */
    private boolean focus(final Hit hit) {
        switch (hit.kind()) {
            case RESOURCE -> {
                flyTo(((BoardSession.TotalLine) hit.data()).key());
                return true;
            }
            case SPARE -> {
                final int place = SpareBar.place(spareHitAt(hit));
                if (place < 0) return false;
                final com.gtnhplanner.ui.card.RecipeCard card = cardOf(
                    ((BoardSession.SpareLine) hit.data()).bearers()
                        .get(place)
                        .node());
                if (card == null) return false;
                canvas.frame(List.of(card.nodeId));
                return true;
            }
            case MACHINE, COUNT, MACHINE_RULE, PIN -> {
                canvas.frame(List.of(((BoardSession.MachineLine) hit.data()).nodeId()));
                return true;
            }
            case GROUP -> {
                flyToGroup((String) hit.data());
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** Frames the next card that makes or uses a resource, cycling each time. */
    private void flyTo(final String key) {
        final List<UUID> using = new ArrayList<>();
        for (final CardModel card : session.models()
            .values()) {
            boolean uses = false;
            for (final CardModel.PortView p : card.inputs) uses |= session.canonicalKey(p.key())
                .equals(key);
            for (final CardModel.PortView p : card.outputs) uses |= session.canonicalKey(p.key())
                .equals(key);
            if (uses) using.add(card.node.id);
        }
        if (using.isEmpty()) return;
        final int i = (flyIndex.merge(key, 1, Integer::sum) - 1) % using.size();
        canvas.frame(List.of(using.get(i)));
    }

    private void flyToGroup(final String name) {
        final List<UUID> cards = new ArrayList<>();
        for (final BoardSession.MachineLine m : session.totals()
            .machines())
            if (m.name()
                .equals(name)) cards.add(m.nodeId());
        if (cards.isEmpty()) return;
        final int i = (flyIndex.merge("machine:" + name, 1, Integer::sum) - 1) % cards.size();
        canvas.frame(List.of(cards.get(i)));
    }

    @Override
    public boolean onMouseScroll(final UpOrDown direction, final int amount) {
        if (!open) return false;
        // A rate box or a machine's count takes the wheel, as on the cards; but a wheel that scrolled the list a
        // moment ago keeps scrolling it, so scrolling past one never steps it by surprise.
        final long now = System.currentTimeMillis();
        if (now - listWheelAt > 400) {
            final Hit hit = hitAtMouseFromLastFrame();
            final int step = direction == UpOrDown.UP ? 1 : -1;
            if (hit != null && hit.kind() == Kind.RATE) {
                DrawerCard.stepRate(session, (DrawerModel) hit.data(), step);
                return true;
            }
            if (hit != null && hit.kind() == Kind.RULE) {
                DrawerCard.stepRule(session, (DrawerModel) hit.data(), step);
                return true;
            }
            if (hit != null && hit.kind() == Kind.PORT_RATE) {
                stepPortRate((PortPin) hit.data(), step);
                return true;
            }
            if (hit != null && hit.kind() == Kind.LINE_SET) {
                final BoardSession.TotalLine line = (BoardSession.TotalLine) hit.data();
                final List<OpenPort> open = openPorts(line, inputs.contains(line));
                if (open.size() == 1) {
                    final OpenPort p = open.get(0);
                    stepPortRate(new PortPin(p.node(), p.output(), p.index(), p.perSecond(), p.machine()), step);
                }
                return true;
            }
            if (hit != null && hit.kind() == Kind.COUNT) {
                final com.gtnhplanner.ui.card.RecipeCard card = cardOf(
                    ((BoardSession.MachineLine) hit.data()).nodeId());
                if (card != null) card.wheelCount(step);
                return true;
            }
            if (hit != null && hit.kind() == Kind.MACHINE_RULE) {
                final BoardSession.MachineLine m = (BoardSession.MachineLine) hit.data();
                final com.gtnhplanner.ui.card.RecipeCard card = cardOf(m.nodeId());
                if (card != null && card.model() != null)
                    session.pin(card.model().node, m.pinned() ? 0 : Math.max(1, Math.ceil(count(m) - 1e-9)));
                return true;
            }
        }
        listWheelAt = now;
        final int max = Math.max(0, contentH - (getArea().height - LIST_Y));
        scroll = Math.max(0, Math.min(max, scroll + (direction == UpOrDown.UP ? -ROW_H : ROW_H)));
        return true;
    }

    /** Tooltip lines for the row under the mouse. */
    /** NEI's keys (R, U, bookmarks) work on a resource row and its drawer controls, as on a port. */
    @Override
    public ItemStack getStackForRecipeViewer() {
        if (!isValid()) return null;
        final Hit hit = hitAtMouseFromLastFrame();
        if (hit == null) return null;
        return switch (hit.kind()) {
            case RESOURCE, ADD -> {
                final BoardSession.TotalLine line = (BoardSession.TotalLine) hit.data();
                yield Resources.lookupStack(line.item(), line.fluid());
            }
            case RULE, RATE -> {
                final DrawerModel d = (DrawerModel) hit.data();
                yield Resources.lookupStack(d.item, d.fluid);
            }
            default -> null;
        };
    }

    /**
     * The tip for what the mouse is on: a spare's own (its whole story, with icons), or the lines of {@link
     * #hoverLines}.
     */
    com.gtnhplanner.ui.popup.Tip hoverTip() {
        final Hit hit = hitAtMouseFromLastFrame();
        if (hit != null && hit.kind() == Kind.SPARE) {
            final BoardSession.SpareLine line = (BoardSession.SpareLine) hit.data();
            final SpareBar.Hit at = spareHitAt(hit);
            final boolean choice = line.bearers()
                .size() > 1;
            if (at != null) switch (at.kind()) {
                case HEAD -> {
                    return SpareTips.situation(session, line, null, false);
                }
                case ROW -> {
                    return SpareTips
                        .situation(
                            session,
                            line,
                            !choice ? null : line.importing() ? "Bring it all in here" : "Ship it all out here",
                            false)
                        .action(com.gtnhplanner.ui.popup.Tip.Input.MIDDLE, "Go to its card");
                }
                case FILL -> {
                    return SpareTips.situation(session, line, "Fill the bar to here", false)
                        .action(com.gtnhplanner.ui.popup.Tip.Input.MIDDLE, "Go to its card");
                }
                default -> {}
            }
        }
        return com.gtnhplanner.ui.popup.Tip.ofLines(hoverLines());
    }

    /**
     * What the mouse is on, as lines: the tip of most rows, and for every row what the tip waits on (it shows once
     * these have stayed the same a moment).
     */
    List<String> hoverLines() {
        final Hit hit = hitAtMouseFromLastFrame();
        if (hit == null) return null;
        final String hint = "§7";
        final Fmt.RateUnit unit = session.rateUnit();
        return switch (hit.kind()) {
            case FOLD -> List.of(open ? "Fold the overview" : "Open the overview");
            case SCROLLBAR -> null;
            case ADD -> List.of(
                inputs.contains(hit.data()) ? "Add a source drawer for it" : "Add a product drawer for it",
                hint + "Connected to every unwired port of it");
            case SECTION -> List.of(hint + "Click: fold or unfold");
            case RESOURCE -> {
                final BoardSession.TotalLine line = (BoardSession.TotalLine) hit.data();
                yield List.of(
                    line.label(),
                    hint + Fmt.rate(railAmount(line), unit, line.isFluid()),
                    hint + "Middle click: go to the cards that make or use it",
                    hint + "Right-click: find public plans that make it",
                    hint + "R, U: its recipes and uses in NEI");
            }
            case RULE -> List.of(
                "Rule",
                hint + "Click: choose  Right-click: previous  Middle click: clear",
                hint + "Wheel: next or previous");
            case RATE -> List.of(
                "Rate",
                hint + "Click: type it (2.5k, 1M, 1/3)  Middle click: clear",
                hint + "Wheel: up or down (Ctrl x10, Shift x100)");
            case LINE_SET -> {
                final BoardSession.TotalLine line = (BoardSession.TotalLine) hit.data();
                final List<OpenPort> open = openPorts(line, inputs.contains(line));
                final String side = inputs.contains(line) ? "input" : "output";
                yield open.size() == 1 ? List.of(
                    "Rate: the plan decides",
                    hint + "Sets the "
                        + open.get(0)
                            .machine()
                        + "'s "
                        + side
                        + ", as on its card",
                    hint + "Click: type it (2.5k, 1M, 1/3)",
                    hint + "Wheel: up or down (Ctrl x10, Shift x100)")
                    : List.of(
                        "Rate: the plan decides",
                        hint + "Makes a drawer for it, wired to the " + open.size() + " " + side + "s waiting for it",
                        hint + "Click: type it (2.5k, 1M, 1/3)");
            }
            case PORT_RULE, PORT_RATE -> {
                final PortPin p = (PortPin) hit.data();
                final String side = p.output() ? "output" : "input";
                yield hit.kind() == Kind.PORT_RULE
                    ? List.of(
                        "Set on the " + p.machine() + "'s " + side,
                        hint + "The machine runs so this " + side + (p.output() ? " gives" : " takes") + " exactly it",
                        hint + "Click: let the plan decide")
                    : List.of(
                        "Rate set on the " + p.machine() + "'s " + side,
                        hint + "Click: type it (2.5k, 1M, 1/3)  Middle click: clear",
                        hint + "Wheel: up or down (Ctrl x10, Shift x100)");
            }
            case PEAK -> List.of(
                session.peakPower() ? "Peak power: every machine running at once"
                    : "Average power: machines running only as much as the plan needs");
            case GROUP -> List.of((String) hit.data(), hint + "Click or middle click: go to its cards one by one");
            case PIN -> {
                final com.gtnhplanner.ui.card.RecipeCard card = cardOf(
                    ((BoardSession.MachineLine) hit.data()).nodeId());
                yield card != null && card.pinnedAnywhere()
                    ? List.of("Pin", hint + "Click: choose", hint + "Right-click: unpin it everywhere")
                    : List.of("Pin", hint + "Click: place it in the world, or list it on screen while playing");
            }
            case COUNT -> List.of(
                crop((BoardSession.MachineLine) hit.data()) ? "Crops planted" : "Machine count",
                hint + "Click: type a pinned count",
                hint + "Wheel: one more or fewer (below one unpins)");
            case MACHINE_RULE -> {
                final BoardSession.MachineLine m = (BoardSession.MachineLine) hit.data();
                yield m.pinned()
                    ? List.of(
                        "Exactly",
                        hint + "Pinned at " + Fmt.machines(count(m)) + (crop(m) ? " crops" : ""),
                        hint + "Click: let the plan decide")
                    : List.of(
                        "Any",
                        hint + "The plan decides how many",
                        hint + "Click: pin it at "
                            + (long) Math.max(1, Math.ceil(count(m) - 1e-9))
                            + (crop(m) ? " crops" : ""));
            }
            case SPARE -> {
                final SpareBar.Hit at = spareHitAt(hit);
                final BoardSession.SpareLine line = (BoardSession.SpareLine) hit.data();
                if (at == null) yield null;
                yield switch (at.kind()) {
                    case AUTO -> List.of("Auto", hint + "Click: back to the plan's own pick");
                    case SPLIT -> List.of("Split", hint + "Click: share it evenly between them");
                    // The tip itself is the leftover's story (hoverTip); this says what it waits on.
                    case HEAD, ROW, FILL -> List.of(
                        "Spare",
                        line.key(),
                        at.kind()
                            .name(),
                        Integer.toString(at.index()));
                };
            }
            case MACHINE -> {
                final BoardSession.MachineLine m = (BoardSession.MachineLine) hit.data();
                yield List.of(
                    m.name(),
                    hint + Fmt.machines(m.machines()) + " machines" + (m.pinned() ? " (pinned)" : ""),
                    hint + Fmt.power(m.euPerTick()) + " EU/t " + (session.peakPower() ? "peak" : "average"),
                    hint + "Click or middle click: go to the card");
            }
        };
    }

    // endregion
}
