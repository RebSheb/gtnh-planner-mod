package com.gtnhplanner.ui.drawer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.IDraggable;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widget.sizer.Area;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.canvas.BoardCanvas;
import com.gtnhplanner.ui.popup.NumberPopup;
import com.gtnhplanner.ui.popup.PickList;
import com.gtnhplanner.ui.popup.Popup;
import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * A drawer on the board: what the player brings in (source), what leaves the plan (product: a rate wanted, or with no
 * rule whatever arrives), what is voided (trash), or what passes from makers to users inside the plan (buffer: an input
 * dropped on a product, or an output on a source, makes one). It is a card's port row lifted off the card ({@link
 * com.gtnhplanner.ui.card.CardPaint#drawerRow}): its resource, what the plan moves through it, its name, and under
 * them its rule key and rate box (trash: what it does). The rule key's menu also turns a product into trash and back.
 * It is moved by the thin bar along its top, where its delete key shows at the left; dragged anywhere else it draws a
 * wire to a card's port, as a port row on a card does.
 */
public final class DrawerCard extends Widget<DrawerCard> implements Interactable, IDraggable,
    com.cleanroommc.modularui.integration.recipeviewer.RecipeViewerIngredientProvider {

    public static final int W = DrawerPaint.W;
    public static final int H = DrawerPaint.H;
    /** The row's middle, level with the port a drawer is made for when it is placed beside a card. */
    public static final int ANCHOR_Y = DrawerPaint.BAR + com.gtnhplanner.ui.card.CardLayout.BOX_H / 2;
    /** The rule menu's rows that turn a product into trash, and trash back into a product. */
    public static final String TO_TRASH = "Void it (trash)", TO_PRODUCT = "Keep it (product)";

    public enum Part {
        DELETE,
        /** The bar along the top: it moves the drawer. */
        BAR,
        CYCLE,
        RULE,
        RATE,
        BODY
    }

    private final BoardSession session;
    public final UUID drawerId;
    private DrawerModel model;

    private boolean moving;
    /** Whether the drag under way draws a wire (pressed below the bar) rather than moving the drawer. */
    private boolean wiring;

    public DrawerCard(final BoardSession session, final UUID drawerId) {
        this.session = session;
        this.drawerId = drawerId;
        size(W, H);
        refresh();
    }

    /** NEI's keys (R, U, bookmarks) work over a drawer, on its resource. */
    @Override
    public ItemStack getStackForRecipeViewer() {
        if (!isValid() || model == null) return null;
        return com.gtnhplanner.ui.Resources.lookupStack(model.item, model.fluid);
    }

    public DrawerModel model() {
        return model;
    }

    public void refresh() {
        final DrawerModel next = session.drawerModel(drawerId);
        if (next == null) return;
        model = next;
        pos(next.drawer.getX(), next.drawer.getY());
    }

    @Override
    public void onUpdate() {
        super.onUpdate();
        syncModel();
    }

    /** Takes up the model the last solve made: every tick while in view, and from the board when it is not. */
    public void syncModel() {
        if (session.drawerModel(drawerId) != model) refresh();
    }

    @Override
    public boolean canHover() {
        return true;
    }

    // region Geometry

    private BoardCanvas canvas() {
        // Once the board has rebuilt it away it has no parent to ask for (ModularUI throws): it is on no board.
        return isValid() && getParent() instanceof final BoardCanvas c ? c : null;
    }

    private float localX() {
        final BoardCanvas c = canvas();
        return c == null ? -1 : c.worldX(getContext().getAbsMouseX()) - model.drawer.getX();
    }

    private float localY() {
        final BoardCanvas c = canvas();
        return c == null ? -1 : c.worldY(getContext().getAbsMouseY()) - model.drawer.getY();
    }

    private static boolean in(final float x, final float y, final int rx, final int ry, final int rw, final int rh) {
        return x >= rx && y >= ry && x < rx + rw && y < ry + rh;
    }

    private boolean hasRule() {
        return model.kind.hasRule();
    }

    public Part partAt(final float x, final float y) {
        // Far out the controls are not drawn, so nothing invisible answers a click or the wheel: all of it moves it.
        if (session.graph()
            .getZoom() <= com.gtnhplanner.ui.card.RecipeCard.GLANCE_ZOOM) return Part.BAR;
        for (final Part part : new Part[] { Part.DELETE, Part.BAR, Part.CYCLE, Part.RULE, Part.RATE }) {
            final int[] r = partRect(part);
            if (r != null && in(x, y, r[0], r[1], r[2], r[3])) return part;
        }
        return Part.BODY;
    }

    /** Whether it reads as an output's row (the resource on the right): a product or trash; a source as an input's. */
    private boolean outputLook() {
        return model.kind != Drawer.Kind.SOURCE;
    }

    /** Where the card's row starts on the drawer: under the bar, its box filling the rest, a pixel inside the row. */
    private static final int ROW_Y = DrawerPaint.ROW_Y;

    /** How far a card's coordinates move to put its row on the drawer. */
    private int shiftX() {
        return 2 - com.gtnhplanner.ui.card.CardLayout.railX(outputLook());
    }

    /** Card-local rectangle of a part, or null when this kind has none; for the dev harness. */
    public int[] partRect(final Part part) {
        if (model == null) return null;
        final boolean out = outputLook();
        final int dx = shiftX(), cy = com.gtnhplanner.ui.card.CardLayout.ctrlY(ROW_Y),
            h = com.gtnhplanner.ui.card.CardLayout.CTRL_H;
        return switch (part) {
            // At the bar's left end, whichever side the resource is on.
            case DELETE -> new int[] { DrawerPaint.DELETE_IN, DrawerPaint.DELETE_IN, DrawerPaint.DELETE,
                DrawerPaint.DELETE };
            case BAR -> new int[] { 0, 0, W, DrawerPaint.GRAB };
            // Product and trash take turns from the rule's menu now.
            case CYCLE -> null;
            case RULE -> hasRule() || model.kind == Drawer.Kind.TRASH
                ? new int[] { com.gtnhplanner.ui.card.CardLayout.keyX(out) + dx, cy,
                    com.gtnhplanner.ui.card.CardLayout.CTRL_KEY, h }
                : null;
            case RATE -> hasRule()
                ? new int[] { com.gtnhplanner.ui.card.CardLayout.boxX(out) + dx, cy,
                    com.gtnhplanner.ui.card.CardLayout.CTRL_BOX, h }
                : null;
            case BODY -> new int[] { 0, 0, W, H };
        };
    }

    public Part partUnderMouse() {
        if (model == null) return null;
        final float x = localX(), y = localY();
        return x < 0 || y < 0 || x >= W || y >= H ? null : partAt(x, y);
    }

    private int screenX(final int lx) {
        final BoardCanvas c = canvas();
        return c == null ? 0 : c.screenX(model.drawer.getX() + lx);
    }

    private int screenY(final int ly) {
        final BoardCanvas c = canvas();
        return c == null ? 0 : c.screenY(model.drawer.getY() + ly);
    }

    // endregion

    // region Drawing

    /** The kind's colour, which tints the whole drawer as on the website. */
    public static int tint(final Drawer.Kind kind) {
        return DrawerPaint.tint(kind);
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        if (!com.gtnhplanner.dev.DevPerf.on()) {
            drawDrawer(context, widgetTheme);
            return;
        }
        final long started = System.nanoTime();
        drawDrawer(context, widgetTheme);
        com.gtnhplanner.dev.DevPerf.time("drawers", System.nanoTime() - started);
    }

    private void drawDrawer(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final DrawerModel m = model;
        if (m == null) return;
        final float z = context.getCurrentDrawingZ();
        final Part hover = isHovering() ? partAt(localX(), localY()) : null;
        final BoardCanvas board = canvas();
        final Drawer.Kind kind = m.kind;
        final boolean accepts = board != null && board.drawerAcceptsDrag(m.drawer);
        final boolean lit = session.lit()
            .drawer(m.drawer);

        // Its shadow, its selection and its glow, all in its shape.
        Hyb.beginBatch();
        DrawerPaint.shadow(kind, board != null && board.isCarried(drawerId));
        if (session.showsSelected(drawerId)) DrawerPaint.shape(kind, 0, 0, W, H, 2, Hyb.SELECTION, DrawerPaint.SCALE);
        if (lit) for (int i = 3; i >= 1; i--)
            DrawerPaint.shape(kind, 0, 0, W, H, i, 0x16000000 | Hyb.LIT & 0xFFFFFF, DrawerPaint.SCALE);
        // While a wire is in hand, a drawer that would take it is outlined green; brighter where it would go now.
        if (accepts) {
            final boolean there = drawerId.equals(board.dragSnappedTo());
            DrawerPaint.shape(
                kind,
                0,
                0,
                W,
                H,
                there ? 2 : 1,
                there ? com.gtnhplanner.ui.card.CardPaint.ACCEPT : com.gtnhplanner.ui.card.CardPaint.ACCEPT_FAINT,
                DrawerPaint.SCALE);
        }
        final int edge = lit ? Hyb.mix(DrawerPaint.edge(kind), Hyb.LIT, 0.6f) : DrawerPaint.edge(kind);
        if (session.graph()
            .getZoom() <= com.gtnhplanner.ui.card.RecipeCard.GLANCE_ZOOM) {
            Hyb.endBatch();
            drawGlance(m, z, edge);
            return;
        }
        // The drawer: its striped box, and the bar it is moved by, lit under the mouse.
        DrawerPaint.frame(kind, hover == Part.BAR || hover == Part.DELETE);
        Hyb.endBatch();

        // The row, as it was on the card: the resource, what the plan moves through it, its name, and under them its
        // rule key and rate box (trash: what it does).
        final Fmt.RateUnit unit = session.rateUnit();
        final boolean out = outputLook(), trash = kind == Drawer.Kind.TRASH;
        final int ink = m.rate <= 0 ? DrawerPaint.IDLE_INK
            : trash ? DrawerPaint.TRASH_INK : Hyb.mix(DrawerPaint.tint(kind), Hyb.INK, 0.75f);
        org.lwjgl.opengl.GL11.glPushMatrix();
        org.lwjgl.opengl.GL11.glTranslatef(shiftX(), ROW_Y, 0);
        com.gtnhplanner.ui.card.CardPaint.drawerRow(
            kind,
            m.item,
            m.fluid,
            m.isPower(),
            Fmt.compact(m.shown(m.rate, unit)),
            m.suffix(unit, false),
            m.label,
            ink,
            true,
            0,
            z);
        if (hasRule()) {
            // A rate being wheeled shows at once, before it is set.
            final Double wheeled = session.wheeledRate(m.drawer.getId());
            final double target = wheeled != null ? wheeled : m.target;
            final boolean empty = wheeled != null ? wheeled <= 0 : m.rule == Drawer.Rule.ANY || m.target <= 0;
            com.gtnhplanner.ui.card.CardPaint.ruleControls(
                out,
                0,
                m.rule,
                null,
                empty ? null : Fmt.compact(m.shown(target, unit)),
                m.suffix(unit, true),
                null,
                hover == Part.RULE,
                hover == Part.RATE,
                m.unmet,
                session.unset(drawerId),
                board != null && board.edit()
                    .editing(BoardCanvas.drawerEditKey(drawerId)) ? board.edit() : null);
        } else if (trash) com.gtnhplanner.ui.card.CardPaint.ruleControls(
            out,
            0,
            Drawer.Rule.ANY,
            "×",
            null,
            "",
            "Voided",
            hover == Part.RULE,
            false,
            false,
            false,
            null);
        org.lwjgl.opengl.GL11.glPopMatrix();
        // The delete key, at the bar's left end, while the mouse is on the drawer or it is selected.
        if (hover != null || session.showsSelected(drawerId)) {
            final int[] del = partRect(Part.DELETE);
            final int face = hover == Part.DELETE ? 0xFF9C3543 : Hyb.KEY;
            Hyb.bevel(del[0], del[1], del[2], del[3], face, Hyb.KEY_HI, Hyb.KEY_LO, Hyb.KEY_EDGE, 1);
            Hyb.rect(del[0] + 2, del[1] + (del[3] - 2) / 2, del[2] - 4, 2, Hyb.INK);
        }
    }

    /**
     * Zoomed out, as the cards do: the shape with a rim that stays a screen pixel or two, the resource big inside and
     * its rate in a dark pill in the corner, at whole screen pixels per font pixel so it stays sharp. The name is in
     * the tooltip.
     */
    private void drawGlance(final DrawerModel m, final float z, final int edge) {
        final float zoom = session.graph()
            .getZoom();
        final float rim = Math.min(6, Math.max(1.5f, 1 / zoom));
        Hyb.beginBatch();
        DrawerPaint.shape(m.kind, 0, 0, W, H, 0, edge, DrawerPaint.SCALE);
        DrawerPaint.shape(m.kind, 0, 0, W, H, -rim, DrawerPaint.fill(m.kind), DrawerPaint.SCALE);
        Hyb.endBatch();
        final int side = DrawerPaint.side(m.kind);
        final float size = H - 14;
        if (m.isPower()) com.gtnhplanner.ui.card.RecipeCard.euIcon(8 + side, 7, size);
        else Hyb.icon(m.item, m.fluid, 8 + side, 7, size, z);
        final Fmt.RateUnit unit = session.rateUnit();
        final String rate = DrawerPaint.sign(m.kind, m.rate) + Fmt.brief(m.shown(m.rate, unit));
        final int color = m.rate <= 0 ? Hyb.MUTED : DrawerPaint.rateInk(m.kind, m.rate);
        // Two screen pixels per font pixel, or one far out: whole pixels at every wheel step, so it stays sharp.
        float cs = 1 / zoom;
        if (cs > 4) cs /= 2;
        while (cs > 1 && Hyb.width(rate) * cs + 2 * cs > W - 8) cs /= 2;
        final float pad = cs, tw = Hyb.width(rate) * cs, th = 8 * cs;
        final float py = H - 4 - th - 2 * pad, px = W - 4 - DrawerPaint.insetAt(m.kind, H - 4) - tw - 2 * pad;
        Hyb.rect(px, py, tw + 2 * pad, th + 2 * pad, 0xC0101114);
        Hyb.text(rate, px + pad, py + pad, cs, color);
        final BoardCanvas board = canvas();
        if (board != null)
            board.labelDrawer(m.label, m.drawer.getX(), m.drawer.getY(), W, H, m.kind == Drawer.Kind.SOURCE, m.rate);
    }

    public static String ruleLabel(final Drawer.Rule rule) {
        return switch (rule) {
            case ANY -> "~ Any";
            case AT_LEAST -> "≥ At least";
            case EXACTLY -> "= Exactly";
            case AT_MOST -> "≤ At most";
        };
    }

    /**
     * A rule's mark where a line of text at {@code y} would go, centred on that line: the font draws "~" at the very
     * top of its cell, so it comes down to the middle like the others.
     */
    public static void drawRuleMark(final Drawer.Rule rule, final float x, final float y, final int color) {
        Hyb.text(ruleMark(rule), x, y + (rule == Drawer.Rule.ANY ? 2.5f : 0), color);
    }

    public static String ruleMark(final Drawer.Rule rule) {
        return switch (rule) {
            case ANY -> "~";
            case AT_LEAST -> "≥";
            case EXACTLY -> "=";
            case AT_MOST -> "≤";
        };
    }

    /** The tooltip for the part under the mouse: what it is, its figures, what the mouse does. */
    public com.gtnhplanner.ui.popup.Tip tip() {
        if (model == null || !isHovering()) return null;
        final Part part = partAt(localX(), localY());
        final Fmt.RateUnit unit = session.rateUnit();
        final DrawerModel m = model;
        return switch (part) {
            case DELETE -> com.gtnhplanner.ui.popup.Tip.of("Delete this drawer")
                .action(com.gtnhplanner.ui.popup.Tip.Input.LEFT, "Delete");
            case BAR -> com.gtnhplanner.ui.popup.Tip.of(m.label)
                .sub(kindName(m.kind))
                .action(com.gtnhplanner.ui.popup.Tip.Input.DRAG, "Move")
                .action(com.gtnhplanner.ui.popup.Tip.Input.LEFT, "Select");
            case RULE -> m.kind == Drawer.Kind.TRASH ? com.gtnhplanner.ui.popup.Tip.of("Trash")
                .muted("It voids what comes in: it leaves the plan without being one of its outputs.")
                .action(com.gtnhplanner.ui.popup.Tip.Input.LEFT, "Keep it instead")
                : com.gtnhplanner.ui.popup.Tip.of("Rule")
                    .sub(ruleLabel(m.rule))
                    .muted(ruleMeaning(m.kind, m.rule))
                    .action(com.gtnhplanner.ui.popup.Tip.Input.LEFT, "Pick")
                    .action(com.gtnhplanner.ui.popup.Tip.Input.WHEEL, "Next")
                    .action(com.gtnhplanner.ui.popup.Tip.Input.MIDDLE, "Clear");
            case RATE -> {
                final com.gtnhplanner.ui.popup.Tip tip = com.gtnhplanner.ui.popup.Tip
                    .of(m.rule == Drawer.Rule.ANY ? "No rate yet" : "Target rate");
                if (m.rule != Drawer.Rule.ANY) tip.row(ruleLabel(m.rule), m.rate(m.target, unit));
                if (m.shortfall != null) tip.row("Reaches", m.rate(m.shortfall.reachable(), unit), Hyb.RED_INK);
                yield tip.action(com.gtnhplanner.ui.popup.Tip.Input.LEFT, "Type (2.5k, 1/3)")
                    .action(com.gtnhplanner.ui.popup.Tip.Input.WHEEL, "+1, Ctrl 10, Shift 100")
                    .action(com.gtnhplanner.ui.popup.Tip.Input.MIDDLE, "Clear");
            }
            default -> {
                final com.gtnhplanner.ui.popup.Tip tip = com.gtnhplanner.ui.popup.Tip.of(m.label)
                    .sub(kindName(m.kind))
                    .row(
                        m.kind == Drawer.Kind.SOURCE ? "Supplies" : m.kind == Drawer.Kind.BUFFER ? "Passes" : "Takes",
                        m.rate(m.rate, unit));
                if (m.kind == Drawer.Kind.BUFFER) tip.muted("Its makers put in what its users take out.");
                if (!m.linked) tip.note("Unconnected", com.gtnhplanner.ui.popup.Tip.WARN);
                if (m.shortfall != null) tip.note(
                    "Reaches only " + m.rate(m.shortfall.reachable(), unit)
                        + " of its "
                        + m.rate(m.shortfall.target(), unit)
                        + " target",
                    Hyb.RED_INK);
                yield tip.action(com.gtnhplanner.ui.popup.Tip.Input.DRAG, "Wire to a card")
                    .action(com.gtnhplanner.ui.popup.Tip.Input.KEY, "R, U");
            }
        };
    }

    /** What a rule asks of the plan, in a sentence. */
    private static String ruleMeaning(final Drawer.Kind kind, final Drawer.Rule rule) {
        if (kind == Drawer.Kind.BUFFER) return switch (rule) {
            case ANY -> "No target: the plan passes whatever its users take.";
            case AT_LEAST -> "The plan passes at least the rate through it.";
            case EXACTLY -> "The plan passes exactly the rate through it.";
            case AT_MOST -> "The plan passes no more than the rate through it.";
        };
        final String flow = kind == Drawer.Kind.SOURCE ? "draws from it" : "puts in it";
        return switch (rule) {
            case ANY -> "No target: the plan " + flow + " whatever it needs.";
            case AT_LEAST -> "The plan " + flow + " at least the rate.";
            case EXACTLY -> "The plan " + flow + " exactly the rate.";
            case AT_MOST -> "The plan " + flow + " no more than the rate.";
        };
    }

    static String kindName(final Drawer.Kind kind) {
        return switch (kind) {
            case SOURCE -> "Source";
            case PRODUCT -> "Product";
            case BYPRODUCT -> "Product";
            case TRASH -> "Trash";
            case BUFFER -> "Buffer";
        };
    }

    // endregion

    // region Controls

    /** The part a left press went down on: let go without moving, a click on it; moved, the drawer is carried. */
    private Part pressed;

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (model == null) return Result.IGNORE;
        if (mouseButton == 0) {
            pressed = partAt(localX(), localY());
            return Result.ACCEPT;
        }
        return act(partAt(localX(), localY()), mouseButton);
    }

    /** What a click on a part does: a left one when it is let go without moving, the others when they go down. */
    private Result act(final Part part, final int mouseButton) {
        final Drawer drawer = model.drawer;
        switch (part) {
            case DELETE -> {
                if (mouseButton == 0) session.deleteDrawer(drawer);
            }
            case RULE -> {
                // The middle button unsets the drawer: back to Any, no rate.
                if (mouseButton == 2) {
                    if (hasRule()) session.setDrawerRule(drawer, Drawer.Rule.ANY);
                } else if (mouseButton == 1 && hasRule()) stepRule(-1);
                else openRules();
            }
            case RATE -> {
                if (mouseButton == 2) {
                    if (hasRule()) session.setDrawerRule(drawer, Drawer.Rule.ANY);
                } else if (mouseButton == 0) {
                    if (canvas() != null) canvas().editDrawerRate(model);
                    else openRate();
                }
            }
            default -> {
                // The body: a click selects it (Shift adds); the right button opens its menu: its rule key's rows and
                // Delete drawer (docs/design/groups.md, Menus).
                if (mouseButton == 1) {
                    final List<PickList.Entry> rows = ruleRows(session, model);
                    rows.add(
                        new PickList.Entry(
                            null,
                            "Delete drawer",
                            "",
                            Hyb.RED_INK,
                            false,
                            () -> session.deleteDrawer(drawer)));
                    Popup.open(
                        session.panel(),
                        PickList.popup("gtnhplanner_drawer", null, rows, false, 120),
                        getContext().getAbsMouseX(),
                        getContext().getAbsMouseY());
                    return Result.SUCCESS;
                }
                if (mouseButton != 0) return Result.IGNORE;
                if (canvas() != null) canvas().clickSelect(drawerId);
                return Result.SUCCESS;
            }
        }
        Hyb.click();
        return Result.SUCCESS;
    }

    @Override
    public boolean onMouseScroll(final UpOrDown direction, final int amount) {
        if (model == null) return false;
        final int step = direction == UpOrDown.UP ? 1 : -1;
        switch (partAt(localX(), localY())) {
            case RULE -> {
                if (!hasRule()) return false;
                stepRule(step);
            }
            case RATE -> stepRate(step);
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * The wheel on the rate, as on the website: one at a time in the shown unit, ten with Ctrl, a hundred with Shift.
     */
    private void stepRate(final int step) {
        stepRate(session, model, step);
    }

    private void stepRule(final int step) {
        stepRule(session, model, step);
    }

    /** The wheel on a drawer's rate; the overview's rows take it too. */
    public static void stepRate(final BoardSession session, final DrawerModel model, final int step) {
        final Fmt.RateUnit unit = session.rateUnit();
        final boolean ctrl = org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_LCONTROL)
            || org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_RCONTROL);
        final boolean shift = org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_LSHIFT)
            || org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_RSHIFT);
        final Double wheeled = session.wheeledRate(model.drawer.getId());
        final double shown = wheeled != null ? model.shown(wheeled, unit)
            : model.rule == Drawer.Rule.ANY ? 0 : model.shown(model.target, unit);
        final double next = Math.max(0, Math.round(shown) + step * (shift ? 100 : ctrl ? 10 : 1));
        session.wheelDrawerRate(model.drawer, next / model.shown(1, unit));
    }

    /** The wheel on a drawer's rule: the next or previous rule. */
    public static void stepRule(final BoardSession session, final DrawerModel model, final int step) {
        final Drawer.Rule[] rules = Drawer.Rule.values();
        final int i = (model.rule.ordinal() + step + rules.length) % rules.length;
        session.setDrawerRule(model.drawer, rules[i]);
    }

    private void openRules() {
        final int[] r = partRect(Part.RULE);
        openRules(session.panel(), session, model, screenX(r[0]), screenY(r[1] + r[3] + 2));
    }

    private void openRate() {
        final int[] r = partRect(Part.RATE);
        openRate(session.panel(), session, model, screenX(r[0]), screenY(r[1] + r[3] + 2));
    }

    /**
     * The rule list for a drawer, and under it the row that turns a product into trash (or trash back into a product);
     * the overview's drawer rows open the same one.
     */
    public static void openRules(final ModularPanel panel, final BoardSession session, final DrawerModel model,
        final int screenX, final int screenY) {
        Popup.open(
            panel,
            PickList.popup("gtnhplanner_rule", null, ruleRows(session, model), false, 110),
            screenX,
            screenY);
    }

    /**
     * The rule rows (each rule, the current one ticked) and the product and trash row, for the rule key and the menu.
     */
    public static List<PickList.Entry> ruleRows(final BoardSession session, final DrawerModel model) {
        final List<PickList.Entry> rows = new ArrayList<>();
        if (model.kind.hasRule()) for (final Drawer.Rule rule : Drawer.Rule.values()) {
            rows.add(
                new PickList.Entry(
                    null,
                    ruleLabel(rule),
                    "",
                    Hyb.INK,
                    rule == model.rule,
                    () -> session.setDrawerRule(model.drawer, rule)));
        }
        // Product and trash take turns; a source and a buffer are what their wires make them.
        if (model.kind == Drawer.Kind.PRODUCT || model.kind == Drawer.Kind.TRASH) rows.add(
            new PickList.Entry(
                null,
                model.kind == Drawer.Kind.TRASH ? TO_PRODUCT : TO_TRASH,
                "",
                Hyb.MUTED,
                false,
                () -> session.cycleDrawer(model.drawer)));
        return rows;
    }

    /** The rate box for a drawer, in the board's unit; the overview's drawer rows open the same one. */
    public static void openRate(final ModularPanel panel, final BoardSession session, final DrawerModel model,
        final int screenX, final int screenY) {
        final Fmt.RateUnit unit = session.rateUnit();
        final double current = model.rule == Drawer.Rule.ANY ? 0 : model.shown(model.target, unit);
        final double perShown = model.shown(1, unit);
        Popup.open(
            panel,
            NumberPopup.create(
                "Rate in " + model.suffix(unit, true) + " (empty: no rule)",
                "2.5k, 1/3",
                "Type 2.5k, 1M or 1/3",
                current,
                0,
                Double.MAX_VALUE,
                v -> session.setDrawerRate(model.drawer, v / perShown)),
            screenX,
            screenY);
    }

    // endregion

    // region Moving

    @Override
    public boolean onDragStart(final int button) {
        if (button != 0 || model == null || canvas() == null) return false;
        // Pressed on its bar it may be moved, with the rest of the selection when it is selected; anywhere else it
        // draws a wire. Let go first, it is a click on what was pressed.
        final Part part = pressed != null ? pressed : partAt(localX(), localY());
        wiring = part != Part.BAR && part != Part.DELETE;
        if (wiring) canvas().beginDrawerWire(drawerId);
        else canvas().beginMove(drawerId);
        return true;
    }

    @Override
    public void onDrag(final int mouseButton, final long timeSinceLastClick) {
        if (canvas() != null && !wiring) canvas().dragMove();
    }

    @Override
    public void onDragEnd(final boolean successful) {
        final Part part = pressed;
        pressed = null;
        final boolean wire = wiring;
        wiring = false;
        if (canvas() == null) return;
        final boolean click = wire ? canvas().endDrawerWire(successful) : canvas().endMove(successful);
        if (!click || part == null || model == null) return;
        act(part, 0);
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
}
