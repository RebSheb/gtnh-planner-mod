package com.gtnhplanner.ui.task;

import javax.annotation.Nullable;

import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;
import com.gtnhplanner.data.flowchart.Tasks;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.PlannerScale;
import com.gtnhplanner.ui.popup.Popup;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * A task's screenshot big, over the planner: the full shot when it is in this game's screenshots folder, else its
 * thumbnail (all another computer has). Any click closes it.
 */
final class PictureView extends Widget<PictureView> implements Interactable {

    private static final int EDGE = 4, CAPTION = 16;

    private final Tasks.Picture picture;
    private final int pw, ph;

    private PictureView(final Tasks.Picture picture, final int pw, final int ph) {
        this.picture = picture;
        this.pw = pw;
        this.ph = ph;
        size(pw + 2 * EDGE, ph + 2 * EDGE + CAPTION);
    }

    /** Opens a screenshot as big as the planner has room for, its shape kept. */
    static void open(final BoardSession session, @Nullable final Tasks.Picture p) {
        if (p == null) return;
        Hyb.click();
        final int[] room = Popup.room(session.panel());
        final float aspect = p.w() > 0 && p.h() > 0 ? p.w() / (float) p.h() : 16 / 9f;
        final int maxW = room[2] - 16 - 2 * EDGE, maxH = room[3] - 16 - 2 * EDGE - CAPTION;
        int w = maxW, h = Math.round(w / aspect);
        if (h > maxH) {
            h = maxH;
            w = Math.round(h * aspect);
        }
        final PictureView view = new PictureView(p, w, h);
        final Popup popup = new Popup("gtnhplanner_task_picture", w + 2 * EDGE, h + 2 * EDGE + CAPTION) {

            @Override
            public void drawBackground(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
                Hyb.rect(-getArea().x, -getArea().y, PlannerScale.width(), PlannerScale.height(), 0x90000000);
                super.drawBackground(context, widgetTheme);
            }
        };
        popup.child(view.pos(0, 0));
        Popup.openCentred(session.panel(), popup, 0.5f);
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        final TaskPictures.Texture full = TaskPictures.full(picture);
        TaskPictures.draw(full != null ? full : TaskPictures.thumb(picture), EDGE, EDGE, pw, ph);
        final String ago = TaskLook.ago(picture.takenAt());
        final String said = (ago.isEmpty() ? "Screenshot" : "Taken " + ago)
            + (full == null ? ". The full shot is on the computer that took it" : "");
        Hyb.text(
            Hyb.width(said) <= pw - 70 ? said : ago.isEmpty() ? "Screenshot" : "Taken " + ago,
            EDGE + 2,
            EDGE + ph + 5,
            Hyb.MUTED);
        Hyb.textRight("Click to close", EDGE + pw - 2, EDGE + ph + 5, 0xFF6A6C74);
    }

    @Override
    public boolean canHover() {
        return true;
    }

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (getPanel() != null) getPanel().closeIfOpen();
        com.gtnhplanner.ui.sound.Sfx.CLOSE.play();
        return Result.SUCCESS;
    }
}
