package com.gtnhplanner.ui.popup;

import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.gtnhplanner.client.UpdateCheck;
import com.gtnhplanner.ui.PlannerSettings;
import com.gtnhplanner.ui.card.CardPaint;
import com.gtnhplanner.ui.sound.Sfx;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * A newer GTNH Planner is out ({@link UpdateCheck}): said once per version when the board opens, with a key to its
 * release page on GitHub. Either answer puts that version away.
 */
public final class UpdateOffer extends Widget<UpdateOffer> implements Interactable {

    private static final int W = 280, H = 94, PAD = 10, KEY_H = 18;
    private static final String OPEN = "Open the release page", LATER = "Not now";

    /** Offered this game already: once is enough, whatever the answer. */
    private static boolean offered;

    private final UpdateCheck.Release release;

    private UpdateOffer(final UpdateCheck.Release release) {
        this.release = release;
        size(W, H);
    }

    /** Offers the newer release, if there is one this player has not been told of. */
    public static void offer(final ModularPanel board) {
        final UpdateCheck.Release release = UpdateCheck.newer();
        if (offered || release == null
            || release.tag()
                .equals(PlannerSettings.updateOffered()))
            return;
        offered = true;
        final Popup popup = new Popup("gtnhplanner_update_offer", W, H) {

            @Override
            public void drawBackground(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
                Hyb.dropShadow(0, 0, W, H);
                CardPaint.surface(W, H);
                Hyb.rect(0, 0, 2, H, 0xFF22D3EE);
            }

            @Override
            public boolean closeOnOutOfBoundsClick() {
                return false;
            }
        };
        popup.child(new UpdateOffer(release).pos(0, 0));
        Popup.openCentred(board, popup, 1 / 3f);
    }

    /** The dev harness: offer again this game. */
    public static void again() {
        offered = false;
    }

    private int[] openRect() {
        final int w = Hyb.width(OPEN) + 20;
        return new int[] { W - PAD - w, H - PAD - KEY_H, w, KEY_H };
    }

    private int[] laterRect() {
        final int w = Hyb.width(LATER) + 16;
        return new int[] { openRect()[0] - 6 - w, H - PAD - KEY_H, w, KEY_H };
    }

    private static boolean in(final int[] r, final int x, final int y) {
        return x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final int mx = isHovering() ? getContext().getAbsMouseX() - getArea().x : -1,
            my = isHovering() ? getContext().getAbsMouseY() - getArea().y : -1;
        Hyb.text(Hyb.fit("GTNH Planner " + release.tag() + " is out", W - 2 * PAD), PAD, PAD, Hyb.INK);
        Hyb.text(Hyb.fit("You have " + UpdateCheck.current() + ".", W - 2 * PAD), PAD, PAD + 14, Hyb.MUTED);
        Hyb.text("The new jar is on its release page.", PAD, PAD + 26, Hyb.MUTED);
        final int[] later = laterRect(), open = openRect();
        final boolean laterHot = in(later, mx, my), openHot = in(open, mx, my);
        Hyb.bevel(
            later[0],
            later[1],
            later[2],
            later[3],
            laterHot ? Hyb.KEY_HOVER : Hyb.KEY,
            Hyb.KEY_HI,
            Hyb.KEY_LO,
            0,
            1);
        Hyb.textCentered(LATER, later[0] + later[2] / 2f, later[1] + 5, Hyb.INK);
        Hyb.rect(open[0], open[1], open[2], open[3], openHot ? 0xFF38E1F5 : 0xFF22D3EE);
        Hyb.rect(open[0], open[1], open[2], 1, 0x66FFFFFF);
        com.cleanroommc.modularui.drawable.GuiDraw
            .drawText(OPEN, open[0] + (open[2] - Hyb.width(OPEN)) / 2f, open[1] + 5, 1f, 0xFF0B1A1E, false);
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (mouseButton != 0) return Result.ACCEPT;
        final int mx = getContext().getAbsMouseX() - getArea().x, my = getContext().getAbsMouseY() - getArea().y;
        if (in(openRect(), mx, my)) {
            Hyb.click();
            PlannerSettings.setUpdateOffered(release.tag());
            if (getPanel() != null) getPanel().closeIfOpen();
            FeedbackPanel.browse(release.url());
        } else if (in(laterRect(), mx, my)) {
            PlannerSettings.setUpdateOffered(release.tag());
            if (getPanel() != null) getPanel().closeIfOpen();
            Sfx.CLOSE.play();
        }
        return Result.SUCCESS;
    }

    @Override
    public boolean canHover() {
        return true;
    }
}
