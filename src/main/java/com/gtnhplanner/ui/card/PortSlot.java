package com.gtnhplanner.ui.card;

import net.minecraft.item.ItemStack;

import org.jetbrains.annotations.Nullable;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.IDraggable;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.integration.recipeviewer.RecipeViewerIngredientProvider;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widget.sizer.Area;
import com.gtnhplanner.ui.canvas.BoardCanvas;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * One port row (NEI slot, name and rate) as its own widget, so NEI's R/U and the item tooltip see exactly this stack.
 * Drawing is
 * done by the card; this widget only answers "what is under the mouse".
 */
public final class PortSlot extends Widget<PortSlot>
    implements RecipeViewerIngredientProvider, Interactable, IDraggable {

    private final RecipeCard card;
    /** Which recipe on the card the port is on: 0, or a later one on a shared machine. */
    public final int section;
    public final boolean output;
    public final int index;

    PortSlot(final RecipeCard card, final int section, final boolean output, final int index) {
        this.card = card;
        this.section = section;
        this.output = output;
        this.index = index;
        if (card.layout() != null) place(card.layout());
    }

    /**
     * The whole row (icon, name, rate), and its extra row when it has one, is the handle: drag a wire from anywhere on
     * it, click it for NEI's recipes.
     */
    void place(final CardLayout layout) {
        size(CardLayout.RAIL_W, layout.rowH(section, output, index));
        pos(CardLayout.railX(output), layout.rowY(section, output, index));
    }

    public CardModel.PortView view() {
        final CardModel model = card.modelOf(section);
        if (model == null) return null;
        final java.util.List<CardModel.PortView> ports = output ? model.outputs : model.inputs;
        return index < ports.size() ? ports.get(index) : null;
    }

    public RecipeCard card() {
        return card;
    }

    /** The node the port belongs to: its recipe's, which on a shared machine is not the card's own. */
    public java.util.UUID node() {
        return card.sectionId(section);
    }

    /** Far out the ports are not drawn: they take no hover, clicks or drags, and the card under them does. */
    @Override
    public boolean canHover() {
        return card.session()
            .graph()
            .getZoom() > RecipeCard.GLANCE_ZOOM;
    }

    /** The stack for tooltips. Unlike {@link #getStackForRecipeViewer()} it never arms an NEI lookup. */
    public ItemStack stack() {
        final CardModel.PortView view = view();
        return view == null ? null : view.lookupStack();
    }

    // region Dragging a wire out of the port

    private boolean moving;
    /**
     * The button whose press on this port the release now ending belongs to, and it did not become a drag; -1 for none.
     * A release alone is no click: pressing NEI's + closes its page onto the board, and that release lands on whatever
     * port is under it.
     */
    private int pressed = -1;

    private BoardCanvas canvas() {
        // A card the board has just rebuilt away has no parent to ask for (ModularUI throws): it is on no board.
        return card.isValid() && card.getParent() instanceof final BoardCanvas c ? c : null;
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (com.gtnhplanner.ui.world.LinkTarget.active()) {
            if (mouseButton == 0) card.chooseForLink();
            return Result.ACCEPT;
        }
        // Left: its recipes, or a drag for a wire; right: its uses. As NEI's own lists.
        pressed = mouseButton == 0 || mouseButton == 1 ? mouseButton : -1;
        pressedOnExtra = onExtraRow();
        return pressed >= 0 ? Result.ACCEPT : Result.IGNORE;
    }

    /** Whether the press was on the port's extra row (what leaves or comes in there with no drawer), not its own. */
    private boolean pressedOnExtra;
    /** Whether the drag under way carries that leftover rather than a wire. */
    private boolean carrying;

    /**
     * Whether the mouse is on the port's number (the upper half of its own row's words): a click there sets the port's
     * rate. Not on a power port, whose draw is the machine's power.
     */
    public boolean overRate() {
        return overKey() || overBox();
    }

    /**
     * The wheel on the port's rate box steps its rate, as a drawer's: one at a time in the shown unit, ten with Ctrl,
     * a hundred with Shift, from what it runs at now when it has none. On its rule key it switches Any and = (= at the
     * rate it runs at now).
     */
    @Override
    public boolean onMouseScroll(final UpOrDown direction, final int amount) {
        final BoardCanvas canvas = canvas();
        final CardModel.PortView view = view();
        final CardModel model = canvas == null || view == null ? null
            : canvas.session()
                .model(node());
        if (model == null || canvas.edit()
            .active()) return false;
        final com.gtnhplanner.ui.BoardSession session = canvas.session();
        final boolean key = overKey();
        if (!key && !overBox()) return !canvas.carryingSpare() && stepItem(session, model, direction);
        final Double set = session.portTarget(model.node, output, index);
        if (key) {
            session.setPortTarget(model.node, output, index, set != null ? 0 : view.perSecond());
            return true;
        }
        final com.gtnhplanner.ui.theme.Fmt.RateUnit unit = session.rateUnit();
        final boolean ctrl = org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_LCONTROL)
            || org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_RCONTROL);
        final boolean shift = org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_LSHIFT)
            || org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_RSHIFT);
        final Double wheeled = session.wheeledPortRate(model.node, output, index);
        final double now = (wheeled != null ? wheeled : set != null ? set : view.perSecond()) * unit.perSecond;
        final int step = (direction == UpOrDown.UP ? 1 : -1) * (shift ? 100 : ctrl ? 10 : 1);
        final double next = Math.max(0, Math.round(now) + step);
        session.wheelPortRate(model.node, output, index, next / unit.perSecond);
        return true;
    }

    /**
     * The wheel on an input whose recipe slot takes several items (an ore dictionary slot, GregTech's alternatives):
     * the next or the one before, in NEI's order, as the website's wheel over a slot. False on any other port, whose
     * wheel stays the board's.
     */
    private boolean stepItem(final com.gtnhplanner.ui.BoardSession session, final CardModel model,
        final UpOrDown direction) {
        if (output || index >= model.node.inputs.size()) return false;
        final com.gtnhplanner.data.flowchart.Port<?> port = model.node.inputs.get(index);
        if (!port.hasChoices()) return false;
        final java.util.List<?> items = port.getAlternatives();
        final int at = Math.max(0, port.choiceIndex());
        final int step = direction == UpOrDown.UP ? 1 : -1;
        final Object next = items.get(Math.floorMod(at + step, items.size()));
        com.gtnhplanner.ui.sound.Sfx.TICK.play(step > 0 ? 1.12f : 0.9f);
        session.pickInput(model.node, index, port.keyOf(next));
        return true;
    }

    /** Whether the port can be set to another item: an input whose slot takes several. */
    public boolean hasChoices() {
        final java.util.UUID id = node();
        final com.gtnhplanner.data.flowchart.Node n = id == null ? null
            : card.session()
                .graph().nodes.get(id);
        return !output && n != null
            && index < n.inputs.size()
            && n.inputs.get(index)
                .hasChoices();
    }

    /** Whether the mouse is on the port's rule key (Any or =). */
    public boolean overKey() {
        return overControl(CardLayout.keyX(output), CardLayout.CTRL_KEY);
    }

    /** Whether the mouse is on the port's rate box. */
    public boolean overBox() {
        return overControl(CardLayout.boxX(output), CardLayout.CTRL_BOX);
    }

    private boolean overControl(final int cx, final int cw) {
        final CardLayout layout = card.layout();
        final BoardCanvas canvas = canvas();
        final CardModel model = card.model();
        if (layout == null || canvas == null || model == null) return false;
        final CardModel.PortView view = view();
        if (view == null || !card.showsControls(section, view)) return false;
        final float x = canvas.worldX(getContext().getAbsMouseX()) - model.node.x;
        final float y = canvas.worldY(getContext().getAbsMouseY()) - model.node.y;
        final int cy = CardLayout.ctrlY(layout.rowY(section, output, index));
        return x >= cx - 1 && x < cx + cw + 1 && y >= cy - 1 && y < cy + CardLayout.CTRL_H + 1;
    }

    /** Whether the mouse is on the port's extra row (its spare), not its own row. */
    public boolean overExtraRow() {
        return onExtraRow();
    }

    private boolean onExtraRow() {
        final CardLayout layout = card.layout();
        final BoardCanvas canvas = canvas();
        // The card's own node places it (a shared machine's other recipes keep positions of their own).
        final CardModel model = card.model();
        if (layout == null || canvas == null || model == null || !layout.hasExtra(section, output, index)) return false;
        final float y = canvas.worldY(getContext().getAbsMouseY()) - model.node.y;
        return y >= layout.rowY(section, output, index) + CardLayout.ROW;
    }

    @Override
    public boolean onMouseRelease(final int mouseButton) {
        if (mouseButton != pressed) return false;
        pressed = -1;
        final BoardCanvas canvas = canvas();
        if (canvas != null) {
            Hyb.click();
            // The extra row's click (either button) is its spare's menu: where else it could go, or a drawer for it.
            if (pressedOnExtra) canvas.openSpareMenu(node(), output, index);
            else canvas.clickPort(node(), output, index, mouseButton == 1);
        }
        return true;
    }

    @Override
    public boolean onDragStart(final int button) {
        final BoardCanvas canvas = canvas();
        // ModularUI starts drags on whatever is under the mouse, hovered or not: far out, where the ports are not
        // drawn,
        // a press on the card would start a wire here and end as a click on the port (its recipes in NEI).
        if (canvas == null || !canHover()) return false;
        // The extra row carries its leftover to another place that could bear it: all of it, or half with the right
        // button. A leftover with nowhere else to go drags as the port does.
        if (pressedOnExtra && (button == 0 || button == 1)
            && canvas.beginSpareCarry(node(), output, index, button == 1)) {
            pressed = -1;
            carrying = true;
            return true;
        }
        if (button != 0) return false;
        pressed = -1;
        canvas.beginPortDrag(node(), output, index, overKey() ? 1 : overBox() ? 2 : 0);
        return true;
    }

    @Override
    public void onDrag(final int mouseButton, final long timeSinceLastClick) {}

    @Override
    public void onDragEnd(final boolean successful) {
        final BoardCanvas canvas = canvas();
        if (canvas == null) return;
        if (carrying) {
            carrying = false;
            canvas.endSpareCarry(successful);
        } else canvas.endPortDrag(successful);
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

    @Override
    public ItemStack getStackForRecipeViewer() {
        // AE2 and NEI can ask after the screen is gone.
        if (!isValid()) return null;
        // NEI asks this when R or U is pressed: remember the port so the recipe added next wires into it.
        card.session()
            .armLookup(node(), output, index);
        return stack();
    }
}
