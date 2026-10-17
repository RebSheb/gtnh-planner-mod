package com.gtnhplanner.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;

import com.cleanroommc.modularui.api.drawable.IDrawable;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.nei.NEIPlanConfig;
import com.gtnhplanner.ui.canvas.BoardCanvas;
import com.gtnhplanner.ui.card.CardModel;
import com.gtnhplanner.ui.card.PortSlot;
import com.gtnhplanner.ui.card.RecipeCard;
import com.gtnhplanner.ui.drawer.DrawerCard;
import com.gtnhplanner.ui.library.LibraryView;
import com.gtnhplanner.ui.popup.Popup;
import com.gtnhplanner.ui.popup.Tip;
import com.gtnhplanner.ui.power.PowerPicker;
import com.gtnhplanner.ui.theme.Fmt;
import com.gtnhplanner.ui.theme.Hyb;
import com.gtnhplanner.ui.theme.Icons;

import codechicken.nei.LayoutManager;
import codechicken.nei.NEIClientConfig;
import codechicken.nei.guihook.GuiContainerManager;

/**
 * The planner: a slim top bar over the board, with NEI's item list kept on the right. Solve mode only.
 */
public final class BoardScreen extends ModularScreen {

    private final BoardSession session;
    private final BoardCanvas canvas;
    private final LibraryDoor library;
    private final PowerPicker picker;
    /** The top bar's keys and the board's other parts, by name, for the tour to point at. */
    private final java.util.Map<String, IWidget> parts;

    private BoardScreen(final ModularPanel panel, final BoardSession session, final BoardCanvas canvas,
        final LibraryDoor library, final PowerPicker picker, final java.util.Map<String, IWidget> parts) {
        super(GtnhPlanner.MODID, panel);
        this.session = session;
        this.canvas = canvas;
        this.library = library;
        this.picker = picker;
        this.parts = parts;
        getContext().setSettings(new UISettings());
        getContext().getUISettings()
            .getRecipeViewerSettings()
            .enable();
    }

    public static BoardScreen create() {
        final BoardSession session = new BoardSession();
        final ModularPanel panel = ModularPanel.defaultPanel("gtnhplanner_board")
            .fullScreenInvisible()
            .left(0)
            // Clear of NEI's search box along the bottom.
            .marginBottom(22)
            .widthRelOffset(() -> {
                sizedPanelX = LayoutManager.itemPanel.x;
                sizedScreenW = Minecraft.getMinecraft().currentScreen.width;
                return (double) Math.max(120, sizedPanelX - 4) / sizedScreenW;
            }, 0);
        session.setPanel(panel);
        final BoardCanvas canvas = new BoardCanvas(session, panel);
        final OverviewRail rail = new OverviewRail(session, canvas);
        final java.util.Map<String, IWidget> parts = new java.util.HashMap<>();
        parts.put("rail", rail);
        final LibraryDoor library = new LibraryDoor();
        library.view = new LibraryView(session, () -> library.set(false), () -> {
            library.set(false);
            canvas.frameAllWhenBuilt();
        });
        session.setLibraryOpener(() -> {
            library.view.showPublic();
            library.set(true);
        });
        session.setMyPlansOpener(() -> {
            library.view.showMine();
            library.set(true);
        });
        session.setLibrarySearch((key, label) -> {
            library.view.showMaking(key, label);
            library.set(true);
        });

        // The top bar is the account's: the plan tabs take all the room the keys leave; the keys come in groups, a gap
        // between each: library, share and the account, then feedback, settings and help. Narrow, it gives way
        // (TopBar). What edits or views the board is in the toolbar along the top of the board.
        final TopBar topBar = new TopBar(named(parts, "tabs", new PlanTabs(session)));
        final BoardToolbar toolbar = new BoardToolbar();
        parts.put("toolbar", toolbar);
        // Over the board (and the library): the non-recipe machines, opened from their key.
        final PickerDoor pickerDoor = new PickerDoor();
        final PowerPicker picker = new PowerPicker(session, () -> {
            if (pickerDoor.picker.isEnabled()) com.gtnhplanner.ui.sound.Sfx.CLOSE.play();
            pickerDoor.picker.setEnabled(false);
        });
        pickerDoor.picker = picker;
        picker.setEnabled(false);
        final Runnable openPicker = () -> {
            library.set(false);
            com.gtnhplanner.ui.sound.Sfx.OPEN.play();
            picker.opened();
            picker.setEnabled(true);
        };
        session.setPowerPickerOpener(openPicker);
        toolbar.key(
            0,
            named(parts, "nonrecipe", boltKey("Non-recipe machines: generators, turbines, boilers, reactors", () -> {
                // The key opens the picker and closes it again.
                if (picker.isEnabled()) {
                    com.gtnhplanner.ui.sound.Sfx.CLOSE.play();
                    picker.setEnabled(false);
                } else openPicker.run();
            })),
            Hyb.width(BOLT_LABEL) + 22,
            18);
        toolbar.key(
            1,
            named(parts, "undo", iconKey(Picture.UNDO, session::canUndo, "Undo (Ctrl+Z)", session::undo)),
            18,
            18);
        toolbar.key(
            1,
            named(
                parts,
                "redo",
                iconKey(Picture.REDO, session::canRedo, "Redo (Ctrl+Shift+Z or Ctrl+Y)", session::redo)),
            18,
            18);
        toolbar.key(
            2,
            named(
                parts,
                "arrange",
                key(
                    canvas::arrangeLabel,
                    () -> true,
                    "Auto-arrange the plan (press again to stop)",
                    fit("Arrange"),
                    canvas::arrange)),
            () -> canvas.arranging() ? fit("Arranging 100%", "Stopping") : fit("Arrange"),
            () -> canvas.arranging() ? tight("Stopping", "100%") : tight("Arrange"));
        toolbar.key(
            2,
            named(
                parts,
                "fit",
                key(() -> "Fit", () -> true, "Zoom to fit the whole plan", fit("Fit"), canvas::frameAll)),
            fit("Fit"),
            tight("Fit"));
        toolbar.key(
            3,
            named(
                parts,
                "rate",
                key(
                    () -> session.rateUnit().suffix,
                    () -> true,
                    "Rate unit: per tick, second, minute or hour",
                    fit("/t", "/s", "/min", "/hr"),
                    () -> session.setRateUnit(
                        session.rateUnit()
                            .next()),
                    step -> session.setRateUnit(
                        session.rateUnit()
                            .step(step)))),
            fit("/t", "/s", "/min", "/hr"),
            tight("/t", "/s", "/min", "/hr"));
        toolbar.key(
            3,
            named(
                parts,
                "power",
                key(
                    () -> session.powerKey() == BoardSession.PowerKey.EU ? "EU/t" : "Amps",
                    () -> true,
                    "Show power as EU/t, or as amps at each machine's tier",
                    fit("EU/t", "Amps"),
                    session::togglePowerKey,
                    step -> session.togglePowerKey())),
            fit("EU/t", "Amps"),
            tight("EU/t", "Amps"));
        toolbar.key(
            3,
            named(
                parts,
                "peak",
                key(
                    () -> session.peakPower() ? "Peak" : "Avg",
                    () -> true,
                    "Average or peak power use (peak: every machine running at once)",
                    fit("Peak", "Avg"),
                    session::togglePeakPower,
                    step -> session.togglePeakPower())),
            fit("Peak", "Avg"),
            tight("Peak", "Avg"));
        topBar.key(
            0,
            named(
                parts,
                "invite",
                key(
                    () -> "Invite",
                    () -> session.live() != null,
                    "Invite people to edit this plan with you (sign in to invite)",
                    fit("Invite"),
                    () -> com.gtnhplanner.ui.popup.SharePanel.open(panel, session))),
            fit("Invite"),
            tight("Invite"));
        topBar.key(
            0,
            named(
                parts,
                "share",
                key(
                    () -> "Share",
                    () -> true,
                    "Share a copy of this plan: a link, a code, in chat, a file or a picture, or post it to Community",
                    fit("Share"),
                    () -> openShare(panel, session, canvas, parts.get("share")))),
            fit("Share"),
            tight("Share"));
        topBar.key(
            0,
            named(
                parts,
                "plans",
                key(
                    () -> "Plans",
                    () -> true,
                    "Plans: every plan you have, and the plans shared with you",
                    fit("Plans"),
                    () -> {
                        if (library.open && library.view.onMine()) {
                            library.set(false);
                            return;
                        }
                        library.view.showMine();
                        library.set(true);
                    })),
            fit("Plans"),
            tight("Plans"));
        topBar.key(
            0,
            named(
                parts,
                "community",
                key(() -> "Community", () -> true, "Community: plans other players posted", fit("Community"), () -> {
                    if (library.open && library.view.onPublic()) {
                        library.set(false);
                        return;
                    }
                    library.view.showPublic();
                    library.set(true);
                })),
            fit("Community"),
            tight("Community"));
        final ButtonWidget<?> account = named(parts, "account", accountKey(panel, session, parts));
        topBar.key(0, account, () -> accountWidth(true), () -> accountWidth(false));
        topBar.key(
            1,
            named(
                parts,
                "feedback",
                iconKey(
                    Picture.FEEDBACK,
                    () -> true,
                    "Feedback: report a bug, share an idea, or ask for help",
                    () -> com.gtnhplanner.ui.popup.FeedbackPanel.open(panel, session, canvas, null))),
            18,
            18);
        topBar.key(
            1,
            named(
                parts,
                "settings",
                iconKey(
                    Picture.GEAR,
                    () -> true,
                    "Settings",
                    () -> com.gtnhplanner.ui.popup.SettingsPanel.open(panel))),
            18,
            18);
        topBar.key(
            1,
            named(
                parts,
                "help",
                iconKey(Picture.HELP, () -> true, "Help: the tour, and every control", () -> openHelp(panel, parts))),
            18,
            18);

        final Flow column = Flow.column()
            .widthRel(1f)
            .heightRel(1f);
        column.child(topBar);
        // The overview rail is part of the layout, left of the board, and folds away to give the board its width.
        final Flow body = Flow.row()
            .widthRel(1f)
            .expanded()
            .collapseDisabledChild();
        body.child(rail.heightRel(1f));
        // The middle: the board, filling it; the toolbar's keys float over its top edge (added to the panel below).
        // Its width is what the overview leaves (a column that takes its width from its row cannot size a child by it).
        final Flow mid = Flow.column()
            .heightRel(1f)
            .width(
                () -> Math.max(0, panel.getArea().width - (rail.isEnabled() ? rail.getArea().width : 0)),
                com.cleanroommc.modularui.widget.sizer.Unit.Measure.PIXEL)
            .collapseDisabledChild();
        mid.child(
            canvas.widthRel(1f)
                .height(
                    () -> Math.max(0, panel.getArea().height - topBar.barHeight()),
                    com.cleanroommc.modularui.widget.sizer.Unit.Measure.PIXEL));
        body.child(mid);
        final com.gtnhplanner.ui.task.TaskBoardView tasks = new com.gtnhplanner.ui.task.TaskBoardView(session);
        tasks.heightRel(1f)
            .expanded()
            .setEnabled(false);
        body.child(tasks);
        parts.put("taskBoard", tasks);
        library.view.heightRel(1f)
            .expanded()
            .setEnabled(false);
        body.child(library.view);
        column.child(body);
        panel.child(column);
        // Under whatever heads the board: the toolbar of a plan, the header of a task board.
        final java.util.function.IntSupplier headH = () -> topBar.barHeight()
            + (session.showsTasks() ? com.gtnhplanner.ui.task.TaskBoardView.HEADER : toolbar.barHeight());
        final NoticeBar notices = new NoticeBar(session, canvas)
            .left(
                () -> (session.showsTasks() ? 0 : rail.currentWidth()) + 6,
                com.cleanroommc.modularui.widget.sizer.Unit.Measure.PIXEL)
            .right(6)
            .top(() -> headH.getAsInt() + 4, com.cleanroommc.modularui.widget.sizer.Unit.Measure.PIXEL);
        panel.child(notices);
        parts.put("notices", notices);
        final SelectionBar selectionBar = new SelectionBar(session, canvas, notices);
        panel.child(selectionBar);
        parts.put("selection", selectionBar);
        // The first time the planner opens: the tour, offered once in a note over the dimmed board.
        final TourOffer offer = new TourOffer();
        offer.left(0)
            .right(0)
            .top(topBar::barHeight, com.cleanroommc.modularui.widget.sizer.Unit.Measure.PIXEL)
            .bottom(0);
        parts.put("tourOffer", offer);
        // Under the top bar and the toolbar, so the non-recipe key is still there to close it.
        picker.left(0)
            .right(0)
            .top(
                () -> topBar.barHeight() + toolbar.barHeight(),
                com.cleanroommc.modularui.widget.sizer.Unit.Measure.PIXEL)
            .bottom(0);
        panel.child(picker);
        // The toolbar's keys float over the board's top edge, with no band: the board takes the whole middle column.
        toolbar
            .left(
                () -> rail.isEnabled() ? rail.currentWidth() : 0,
                com.cleanroommc.modularui.widget.sizer.Unit.Measure.PIXEL)
            .top(topBar::barHeight, com.cleanroommc.modularui.widget.sizer.Unit.Measure.PIXEL)
            .width(
                () -> Math.max(0, panel.getArea().width - (rail.isEnabled() ? rail.currentWidth() : 0)),
                com.cleanroommc.modularui.widget.sizer.Unit.Measure.PIXEL);
        panel.child(toolbar);
        // Over everything, so the board waits under it.
        panel.child(offer);
        library.body = body;
        library.session = session;
        library.board = List.of(rail);
        library.plan = List.of(mid, selectionBar, toolbar);
        library.notices = notices;
        library.tasks = tasks;
        parts.put("picker", picker);
        parts.put("libraryView", library.view);
        library.apply();
        return new BoardScreen(panel, session, canvas, library, picker, parts);
    }

    /** The account key's width: "Sign in" while signed out, else the face of the account. */
    private static int accountWidth(final boolean roomy) {
        if (!com.gtnhplanner.library.Account.signedIn()) return roomy ? fit("Sign in") : tight("Sign in");
        return Math.max(
            18,
            com.gtnhplanner.ui.task.TaskLook
                .faceWidth(com.gtnhplanner.ui.task.TaskLook.initials(com.gtnhplanner.library.Account.username())) + 6);
    }

    /**
     * The account key: signed out it reads "Sign in" and opens the sign-in form; signed in it is the account's face
     * (initials on their colour) and opens a menu with the username and Sign out.
     */
    private static ButtonWidget<?> accountKey(final ModularPanel panel, final BoardSession session,
        final java.util.Map<String, IWidget> parts) {
        final ButtonWidget<?> button = key(() -> "", () -> true, "Your account: sign in, or sign out", 18, () -> {
            if (!com.gtnhplanner.library.Account.signedIn()) {
                com.gtnhplanner.ui.library.AccountForms.signIn(panel, () -> {});
                return;
            }
            final List<com.gtnhplanner.ui.popup.PickList.Entry> rows = new ArrayList<>();
            rows.add(
                new com.gtnhplanner.ui.popup.PickList.Entry(
                    null,
                    com.gtnhplanner.library.Account.username(),
                    "",
                    Hyb.MUTED,
                    false,
                    () -> {}));
            rows.add(
                com.gtnhplanner.ui.popup.PickList.Entry.of(
                    "Sign out",
                    () -> com.gtnhplanner.ui.library.AccountForms
                        .signOut(text -> session.flash(com.gtnhplanner.data.flowchart.balancer.Severity.INFO, text))));
            final IWidget self = parts.get("account");
            com.gtnhplanner.ui.popup.Popup.open(
                panel,
                com.gtnhplanner.ui.popup.PickList.popup("gtnhplanner_account_menu", null, rows, false, 120),
                Integer.MAX_VALUE / 2,
                self.getArea().y + self.getArea().height + 4);
        });
        button.overlay((IDrawable) (ctx, x, y, w, h, theme) -> {
            final String who = com.gtnhplanner.library.Account.username();
            if (who == null) {
                Hyb.textCentered("Sign in", x + w / 2f, y + (h - 8) / 2f, 0xFF22D3EE);
                return;
            }
            final String id = com.gtnhplanner.library.Account.accountId();
            final String initials = com.gtnhplanner.ui.task.TaskLook.initials(who);
            final int fw = com.gtnhplanner.ui.task.TaskLook.faceWidth(initials);
            com.gtnhplanner.ui.task.TaskLook.face(
                who,
                com.gtnhplanner.ui.task.TaskLook.faceColour(id == null ? who : id),
                x + (w - fw) / 2f,
                y + (h - com.gtnhplanner.ui.task.TaskLook.FACE) / 2f);
        });
        return button;
    }

    /** Files a widget under a name, for the tour. */
    private static <W extends IWidget> W named(final java.util.Map<String, IWidget> parts, final String id,
        final W widget) {
        parts.put(id, widget);
        return widget;
    }

    /** A top-bar key or another part of the board, by name; null when there is no such part. */
    public IWidget key(final String id) {
        return parts.get(id);
    }

    /**
     * The GUI rectangle {x, y, w, h} of a smaller part of the board, by name: "rail:..." a row of the overview,
     * "tab:..." a plan tab; null when it is not showing.
     */
    public int[] partRect(final String id) {
        if (id.startsWith("rail:")) return ((OverviewRail) parts.get("rail")).rowRect(id.substring(5));
        if (id.startsWith("tourOffer:")) {
            final TourOffer o = (TourOffer) parts.get("tourOffer");
            return o.isEnabled() ? o.keyRect("tourOffer:take".equals(id)) : null;
        }
        if (id.startsWith("tab:")) return ((PlanTabs) parts.get("tabs")).tabRect(id.substring(4));
        if (id.startsWith("picker:")) {
            final PowerPicker p = (PowerPicker) parts.get("picker");
            if (!p.isEnabled()) return null;
            return "picker:sheet".equals(id) ? p.sheetRect() : p.tileRect(id.substring(7));
        }
        if (id.startsWith("library:")) return library.open ? library.view.partRect(id.substring(8)) : null;
        if ("selection".equals(id)) {
            final IWidget s = parts.get("selection");
            if (s == null || !s.canHover()) return null;
            final com.cleanroommc.modularui.widget.sizer.Area a = s.getArea();
            return new int[] { a.x, a.y, a.width, a.height };
        }
        if (id.startsWith("notice:")) {
            final IWidget n = parts.get("notices");
            return n instanceof final NoticeBar bar && bar.isEnabled() ? bar.keyRect(id.substring(7)) : null;
        }
        if ("notices".equals(id)) {
            final IWidget n = parts.get("notices");
            if (n == null || !n.isEnabled()) return null;
            final com.cleanroommc.modularui.widget.sizer.Area a = n.getArea();
            return a.height <= 0 ? null : new int[] { a.x, a.y, a.width, a.height };
        }
        return null;
    }

    /** Whether the library is up, in place of the board. */
    public boolean libraryOpen() {
        return library.open;
    }

    /**
     * The Share key's menu, under the key: the people who edit the open plan with the player (every plan in the
     * account;
     * greyed signed out), the open plan published to the library (Publish to
     * library, or Update in library and Publish as new post for one posted: a copy for everyone, never the plan
     * itself), its website link and its plan code to the clipboard, a plan file the website
     * opens, the plan opened on the website in the browser, a line in the game's chat that opens it
     * (client/ChatShare), and a picture of it. The website's Share menu has the same rows. Labels only, as narrow as
     * they are: each says what it does.
     */
    private static void openShare(final ModularPanel panel, final BoardSession session, final BoardCanvas canvas,
        final IWidget key) {
        final List<com.gtnhplanner.ui.popup.PickList.Entry> rows = new ArrayList<>();
        final int slot = session.activeSlot();
        // Copies only: people are invited with the Invite key. The website's Share menu has the same rows.
        rows.add(com.gtnhplanner.ui.popup.PickList.Entry.of("Copy plan link", session::copyPlanLink));
        rows.add(com.gtnhplanner.ui.popup.PickList.Entry.of("Copy plan code", () -> session.copyPlan(slot)));
        rows.add(com.gtnhplanner.ui.popup.PickList.Entry.of("Share in game chat", () -> session.shareInChat(slot)));
        rows.add(com.gtnhplanner.ui.popup.PickList.Entry.of("Export plan file", session::exportPlanFile));
        rows.add(
            com.gtnhplanner.ui.popup.PickList.Entry
                .of("Screenshot...", () -> com.gtnhplanner.ui.popup.ScreenshotPanel.open(panel, session, canvas)));
        rows.add(com.gtnhplanner.ui.popup.PickList.Entry.of("Open on website", session::openInWebsite));
        for (final com.gtnhplanner.ui.popup.PickList.Entry post : com.gtnhplanner.ui.library.AccountForms
            .shareRows(panel, session, session.graph()))
            rows.add(com.gtnhplanner.ui.popup.PickList.Entry.of(post.label(), post.action()));
        com.gtnhplanner.ui.popup.Popup.open(
            panel,
            com.gtnhplanner.ui.popup.PickList.popup("gtnhplanner_share_menu", null, rows, false, 0),
            key.getArea().x,
            key.getArea().y + key.getArea().height + 4);
    }

    /** The "?" key: the tour, or the list of every control. */
    private static void openHelp(final ModularPanel panel, final java.util.Map<String, IWidget> parts) {
        final List<com.gtnhplanner.ui.popup.PickList.Entry> rows = new ArrayList<>();
        rows.add(
            new com.gtnhplanner.ui.popup.PickList.Entry(
                null,
                "Take the tour",
                "3 minutes",
                Hyb.INK,
                false,
                com.gtnhplanner.ui.tutorial.Tutorial::start));
        rows.add(
            new com.gtnhplanner.ui.popup.PickList.Entry(
                null,
                "Controls and shortcuts",
                "",
                Hyb.INK,
                false,
                () -> showHelp(panel)));
        com.gtnhplanner.ui.popup.Popup.open(
            panel,
            com.gtnhplanner.ui.popup.PickList.popup("gtnhplanner_help_menu", null, rows, false, 190),
            Integer.MAX_VALUE / 2,
            parts.get("help")
                .getArea().y
                + parts.get("help")
                    .getArea().height
                + 4);
    }

    /**
     * Swaps the board (the overview, the canvas and the bars over it) for the library, and back; and the canvas and its
     * bars for the task board while the open plan is one (a plan of its own kind; it has no cards, so no overview).
     */
    private static final class LibraryDoor {

        LibraryView view;
        Flow body;
        BoardSession session;
        /** What shows whenever the library is shut (and the plan has cards), and what only a plan's board shows. */
        List<com.cleanroommc.modularui.widget.Widget<?>> board = List.of(), plan = List.of();
        /** The notices show with either kind of plan, and not over the library. */
        com.cleanroommc.modularui.widget.Widget<?> notices;
        com.gtnhplanner.ui.task.TaskBoardView tasks;
        boolean open;
        /** Whether the task board was showing when the widgets were last set. */
        boolean tasksShown;

        void set(final boolean open) {
            if (open == this.open) return;
            this.open = open;
            com.gtnhplanner.ui.sound.Sfx.PAGE.play();
            view.setEnabled(open);
            apply();
            if (open) view.opened();
        }

        /** Shows what is open: the library, else the plan's board or its task board, with the overview. */
        void apply() {
            tasksShown = session.showsTasks();
            final boolean taskBoard = !open && tasksShown;
            for (final com.cleanroommc.modularui.widget.Widget<?> w : board) w.setEnabled(!open && !tasksShown);
            for (final com.cleanroommc.modularui.widget.Widget<?> w : plan) w.setEnabled(!open && !taskBoard);
            notices.setEnabled(!open);
            tasks.setEnabled(taskBoard);
            body.scheduleResize();
            // The floating keys take their place from the overview's width, which only a plan has: lay them out again.
            if (body.isValid()) body.getPanel()
                .scheduleResize();
        }
    }

    /** A plan opened or switched to shows the view it opens on (or the one it was left on). */
    @Override
    public void onUpdate() {
        super.onUpdate();
        if (library.tasksShown != session.showsTasks()) library.apply();
        // The board out of sight (the task board, the library): no cursor on it for the others. Showing, it samples as
        // it draws.
        if (library.open || session.showsTasks() || !canvas.isEnabled()) {
            session.presenceOut()
                .off();
            session.sendPresence();
        }
    }

    /** Every gesture on the board, in one list. */
    private static void showHelp(final ModularPanel panel) {
        final List<com.gtnhplanner.ui.popup.PickList.Entry> rows = new ArrayList<>();
        final String[][] tips = { { "R or U over an item", "Show its recipes or uses in NEI; + adds a recipe" },
            { "Drag an item out of NEI", "Click the board to add a drawer for it" },
            { "Click an item in NEI's list", "Its recipes (left-click) or uses (right-click)" },
            { "Click a port", "Its recipes (left) or uses (right) in NEI; adding one wires it to the port" },
            { "Drag a port", "Drop on a card to wire it, or on empty board to add a drawer" },
            { "Right-click a wire", "Add a drawer on it, or delete it" }, { "Middle-click a rate", "Clear the rate" },
            { "Right-click empty board", "Add a sticky note, a box or an arrow" },
            { "Click a sticky note", "Write on it (Esc or a click elsewhere when done); drag it anywhere to move it" },
            { "Click a card or drawer", "Select it (Shift-click adds to the selection)" },
            { "Drag the board", "Pan (Shift-drag selects a box)" }, { "Drag a selected card", "Move the selection" },
            { "Ctrl+A", "Select everything" }, { "Delete", "Delete the selection (Esc deselects)" },
            { "Wheel", "Zoom; over a value, change it" }, { "WASD or arrows", "Pan (+/- or Page Up/Down zoom)" },
            { "Ctrl+C, Ctrl+X, Ctrl+V", "Copy, cut, paste the selection and its wires" },
            { "Ctrl+Z, Ctrl+Shift+Z", "Undo, redo" },
            { "Double-click an overview row", "Show the cards that make or use it" } };
        for (final String[] tip : tips)
            rows.add(new com.gtnhplanner.ui.popup.PickList.Entry(null, tip[0], tip[1], Hyb.INK, false, () -> {}));
        com.cleanroommc.modularui.screen.ModularPanel p = com.gtnhplanner.ui.popup.PickList
            .popup("gtnhplanner_help", "HOW THE BOARD WORKS", rows, false, 300, rows.size());
        com.gtnhplanner.ui.popup.Popup.open(panel, (com.gtnhplanner.ui.popup.Popup) p, 300, 24);
    }

    /** The top-bar keys' tooltips, drawn as board tips (ModularUI's own would be the game's purple ones). */
    private static final java.util.Map<IWidget, String> KEY_TIPS = new java.util.WeakHashMap<>();

    /** A top-bar key the wheel steps too: +1 up, -1 down, with a tick. */
    private static final class WheelKey extends ButtonWidget<WheelKey> {

        private final BooleanSupplier enabled;
        private final java.util.function.IntConsumer wheel;

        WheelKey(final BooleanSupplier enabled, final java.util.function.IntConsumer wheel) {
            this.enabled = enabled;
            this.wheel = wheel;
        }

        @Override
        public boolean onMouseScroll(final com.cleanroommc.modularui.api.UpOrDown direction, final int amount) {
            if (!enabled.getAsBoolean()) return false;
            final int step = direction == com.cleanroommc.modularui.api.UpOrDown.UP ? 1 : -1;
            wheel.accept(step);
            com.gtnhplanner.ui.sound.Sfx.TICK.play(step > 0 ? 1.12f : 0.9f);
            return true;
        }
    }

    /** A top-bar key in the card's key style: a label, a tooltip, greyed when it can do nothing. */
    private static ButtonWidget<?> key(final Supplier<String> label, final BooleanSupplier enabled,
        final String tooltip, final int width, final Runnable action) {
        return key(label, enabled, tooltip, width, action, null);
    }

    /**
     * As above, and the wheel over it steps it too ({@code wheel} gets +1 up, -1 down), as the card's chips do: the
     * unit
     * keys go round their choices.
     */
    private static ButtonWidget<?> key(final Supplier<String> label, final BooleanSupplier enabled,
        final String tooltip, final int width, final Runnable action,
        @org.jetbrains.annotations.Nullable final java.util.function.IntConsumer wheel) {
        final ButtonWidget<?> button = wheel == null ? new ButtonWidget<>() : new WheelKey(enabled, wheel);
        button.size(width, 16)
            .background(
                (IDrawable) (ctx, x, y, w, h, theme) -> {
                    Hyb.bevel(x, y, w, h, Hyb.KEY, Hyb.KEY_HI, Hyb.KEY_LO, 0, 1);
                })
            .hoverBackground(
                (IDrawable) (ctx, x, y, w, h, theme) -> {
                    Hyb.bevel(x, y, w, h, Hyb.KEY_HOVER, Hyb.KEY_HI, Hyb.KEY_LO, 0, 1);
                })
            .overlay(
                (IDrawable) (ctx, x, y, w, h, theme) -> Hyb.textCentered(
                    fitted(label.get(), w - 4),
                    x + w / 2f,
                    y + (h - 8) / 2f,
                    enabled.getAsBoolean() ? Hyb.INK : 0xFF5A5C65))
            .clickSound(Hyb::click)
            .onMousePressed(b -> {
                // True makes ModularUI play the click: only when the key did something.
                if (!enabled.getAsBoolean()) return false;
                // A key that goes round its choices goes back one on a right-click, as the card's chips do.
                if (b == 1 && wheel != null) {
                    wheel.accept(-1);
                    return true;
                }
                if (b != 0) return false;
                action.run();
                return true;
            });
        KEY_TIPS.put(button, tooltip);
        return button;
    }

    /** The picker, reachable from its own close callback (made before it). */
    private static final class PickerDoor {

        PowerPicker picker;
    }

    /** The non-recipe machines key's name, beside its bolt while the bar has room for it. */
    private static final String BOLT_LABEL = "Non-recipe";

    /**
     * The non-recipe machines key: the power wing's amber bolt, then its name; the bolt alone when the bar is tight
     * (its tip names it).
     */
    private static ButtonWidget<?> boltKey(final String tooltip, final Runnable action) {
        final ButtonWidget<?> button = key(() -> "", () -> true, tooltip, Hyb.width(BOLT_LABEL) + 22, action);
        button.overlay((IDrawable) (ctx, x, y, w, h, theme) -> {
            if (w < Hyb.width(BOLT_LABEL) + 22) {
                PowerPicker.bolt(x + (w - 7) / 2, y + (h - 12) / 2, 0xFFFCD34D);
                return;
            }
            PowerPicker.bolt(x + 6, y + (h - 12) / 2, 0xFFFCD34D);
            Hyb.text(BOLT_LABEL, x + 16, y + (h - 8) / 2f, Hyb.INK);
        });
        return button;
    }

    /** A key's width for the widest label it shows: the same padding either side of every label. */
    private static int fit(final String... labels) {
        return widest(labels) + 12;
    }

    /** As {@link #fit}, as the keys sit when the bar is tight: less padding. */
    private static int tight(final String... labels) {
        return widest(labels) + 8;
    }

    private static int widest(final String... labels) {
        int w = 0;
        for (final String l : labels) w = Math.max(w, Hyb.width(l));
        return w;
    }

    /**
     * A key's label in its width: whole when it fits, else its last word (arrange's progress, "Arranging 42%", is
     * "42%" on a tight bar).
     */
    private static String fitted(final String label, final int room) {
        if (Hyb.width(label) <= room) return label;
        final int space = label.lastIndexOf(' ');
        return space < 0 ? label : label.substring(space + 1);
    }

    /** The pictures drawn on the small keys, from {@link Icons}. */
    private enum Picture {
        UNDO,
        REDO,
        FEEDBACK,
        GEAR,
        HELP
    }

    /** A small key with a picture on it (the game's font has none): undo, redo, feedback, settings, help. */
    private static ButtonWidget<?> iconKey(final Picture picture, final BooleanSupplier enabled, final String tooltip,
        final Runnable action) {
        final ButtonWidget<?> button = key(() -> "", enabled, tooltip, 18, action);
        button.overlay((IDrawable) (ctx, x, y, w, h, theme) -> {
            final int[] at = Icons.centred(x, y, w, h);
            final int c = enabled.getAsBoolean() ? Hyb.INK : 0xFF5A5C65;
            switch (picture) {
                case UNDO -> Icons.undo(at[0], at[1], c);
                case REDO -> Icons.redo(at[0], at[1], c);
                // The one picture in a colour of its own.
                case FEEDBACK -> Icons.bug(at[0], at[1], 0xFFE5484D);
                case GEAR -> Icons.gear(at[0], at[1], c);
                case HELP -> Icons.help(at[0], at[1], c);
            }
        });
        return button;
    }

    public BoardSession session() {
        return session;
    }

    public BoardCanvas canvas() {
        return canvas;
    }

    /**
     * The board's keys live on the screen, not the canvas: the panel only offers keys to the hovered widget. ModularUI
     * offers each key event twice (char and key paths), so the ones that must act once remember the last event. Undo
     * and
     * redo are NEI-configurable (Ctrl+Z, Ctrl+Shift+Z, Ctrl+Y); a focused text field keeps its own keys.
     */
    private long lastKeyEvent;

    /**
     * Pastes the last copy with its top-left at the mouse when it is over the board, else in the middle of the view.
     */
    private void paste() {
        final int mx = getContext().getAbsMouseX(), my = getContext().getAbsMouseY();
        final com.cleanroommc.modularui.widget.sizer.Area a = canvas.getArea();
        final boolean over = a.isInside(mx, my);
        final int sx = over ? mx : a.x + a.width / 2, sy = over ? my : a.y + a.height / 2;
        canvas.revealWhenBuilt(session.paste(Math.round(canvas.worldX(sx)), Math.round(canvas.worldY(sy))));
    }

    /**
     * Whether a widget (a text field) has the keyboard. The context returns an empty holder, never null, and keeps the
     * focus on a field whose popup has closed until something else takes it: that field is not typing.
     */
    public static boolean textFocused(final ModularScreen screen) {
        final com.cleanroommc.modularui.screen.viewport.LocatedWidget focused = screen.getContext()
            .getFocusedWidget();
        final IWidget w = focused == null ? null : focused.getElement();
        return w != null && w.isValid()
            && w.getPanel()
                .isOpen();
    }

    @Override
    public boolean onKeyPressed(final char typedChar, final int keyCode) {
        // A tab in hand: Esc puts it back; Ctrl+Shift+Left or Right moves the open plan's tab a place.
        if (parts.get("tabs") instanceof final PlanTabs planTabs) {
            if (keyCode == org.lwjgl.input.Keyboard.KEY_ESCAPE && planTabs.cancel()) return true;
            if ((keyCode == org.lwjgl.input.Keyboard.KEY_LEFT || keyCode == org.lwjgl.input.Keyboard.KEY_RIGHT)
                && net.minecraft.client.gui.GuiScreen.isCtrlKeyDown()
                && net.minecraft.client.gui.GuiScreen.isShiftKeyDown()
                && !textFocused(this)
                && planTabs.moveActive(keyCode == org.lwjgl.input.Keyboard.KEY_LEFT ? -1 : 1)) return true;
        }
        // Esc closes the open popup (a menu, a number box), not the whole planner.
        if (keyCode == org.lwjgl.input.Keyboard.KEY_ESCAPE
            && getPanelManager().getTopMostPanel() instanceof Popup popup) {
            getContext().removeFocus();
            popup.closeIfOpen();
            com.gtnhplanner.ui.sound.Sfx.CLOSE.play();
            return true;
        }
        // The non-recipe machines: Esc closes them, even from the search; the board's keys wait meanwhile.
        if (picker.isEnabled()) {
            if (keyCode == org.lwjgl.input.Keyboard.KEY_ESCAPE) {
                getContext().removeFocus();
                picker.setEnabled(false);
                return true;
            }
            return super.onKeyPressed(typedChar, keyCode);
        }
        // The library: Esc closes its pane, then the library; the board's keys wait until it is closed.
        if (library.open) {
            if (keyCode == org.lwjgl.input.Keyboard.KEY_ESCAPE && !textFocused(this)) {
                if (!library.view.escape()) library.set(false);
                return true;
            }
            return super.onKeyPressed(typedChar, keyCode);
        }
        // The task board: only undo and redo of the board's keys, which act on the plan out of sight.
        if (session.showsTasks()) {
            if (!textFocused(this) && undoKeys()) return true;
            return super.onKeyPressed(typedChar, keyCode);
        }
        // Linking a machine from the world: Esc gives up and goes back to the world.
        if (com.gtnhplanner.ui.world.LinkTarget.active() && keyCode == org.lwjgl.input.Keyboard.KEY_ESCAPE
            && !textFocused(this)) {
            com.gtnhplanner.ui.world.LinkTarget.cancel();
            return super.onKeyPressed(typedChar, keyCode);
        }
        if (!textFocused(this)) {
            // Delete or Backspace removes the selection; Esc clears it (and only closes the planner when nothing is
            // selected).
            if ((keyCode == org.lwjgl.input.Keyboard.KEY_DELETE || keyCode == org.lwjgl.input.Keyboard.KEY_BACK)
                && session.hasSelection()) {
                session.deleteSelected();
                return true;
            }
            if (keyCode == org.lwjgl.input.Keyboard.KEY_A && net.minecraft.client.gui.GuiScreen.isCtrlKeyDown()) {
                session.selectAll();
                return true;
            }
            if (keyCode == org.lwjgl.input.Keyboard.KEY_ESCAPE && session.hasSelection()) {
                session.clearSelection();
                return true;
            }
            // +, -, Page Up and Page Down step the zoom, as on the website (the pan keys are read by the canvas).
            final int zoom = switch (keyCode) {
                case org.lwjgl.input.Keyboard.KEY_EQUALS, org.lwjgl.input.Keyboard.KEY_ADD, org.lwjgl.input.Keyboard.KEY_PRIOR -> 1;
                case org.lwjgl.input.Keyboard.KEY_MINUS, org.lwjgl.input.Keyboard.KEY_SUBTRACT, org.lwjgl.input.Keyboard.KEY_NEXT -> -1;
                default -> 0;
            };
            if (zoom != 0 && codechicken.nei.LayoutManager.getInputFocused() == null) {
                // A key the tour presses comes with no keyboard event of its own: each is a press.
                final long event = org.lwjgl.input.Keyboard.getEventNanoseconds();
                if (event != lastKeyEvent || com.gtnhplanner.ui.tutorial.Pointer.typing) {
                    lastKeyEvent = event;
                    canvas.zoomKey(zoom);
                }
                return true;
            }
            // Ctrl+G: a group round the selection (docs/design/groups.md).
            if (net.minecraft.client.gui.GuiScreen.isCtrlKeyDown() && keyCode == org.lwjgl.input.Keyboard.KEY_G) {
                final long event = org.lwjgl.input.Keyboard.getEventNanoseconds();
                if (event == lastKeyEvent) return true;
                lastKeyEvent = event;
                if (session.hasSelection()) canvas.groups()
                    .group(new java.util.ArrayList<>(session.selection()));
                return true;
            }
            // Ctrl+C, Ctrl+X, Ctrl+V: the selection with its wires, pasted at the mouse (or the middle of the view).
            if (net.minecraft.client.gui.GuiScreen.isCtrlKeyDown()
                && (keyCode == org.lwjgl.input.Keyboard.KEY_C || keyCode == org.lwjgl.input.Keyboard.KEY_X
                    || keyCode == org.lwjgl.input.Keyboard.KEY_V)) {
                final long event = org.lwjgl.input.Keyboard.getEventNanoseconds();
                if (event == lastKeyEvent) return true;
                lastKeyEvent = event;
                if (keyCode == org.lwjgl.input.Keyboard.KEY_V) paste();
                else if (session.copySelection() && keyCode == org.lwjgl.input.Keyboard.KEY_X) session.deleteSelected();
                return true;
            }
            if (undoKeys()) return true;
        }
        return super.onKeyPressed(typedChar, keyCode);
    }

    /** Undo and redo, as NEI's settings bind them: true when the key was one of them. */
    private boolean undoKeys() {
        final boolean undo = NEIClientConfig.isKeyHashDown(NEIPlanConfig.ConfigUndoKey.KEY);
        if (!undo && !NEIClientConfig.isKeyHashDown(NEIPlanConfig.ConfigRedoKey.KEY)
            && !NEIClientConfig.isKeyHashDown(NEIPlanConfig.ConfigRedoAltKey.KEY)) return false;
        // ModularUI offers each key event twice (char and key paths); act once per event.
        final long event = org.lwjgl.input.Keyboard.getEventNanoseconds();
        if (event != lastKeyEvent) {
            lastKeyEvent = event;
            if (undo) session.undo();
            else session.redo();
        }
        return true;
    }

    /**
     * An item dragged out of NEI's list is carried until the next click, which NEI turns into a drop through its GUI
     * handlers. On a ModularUI screen the board takes that click first and NEI never sees it, so the board drops the
     * item itself: on the canvas it offers a drawer or its recipes there; anywhere else in the planner the carry just
     * ends, as it does off a container.
     */
    @Override
    public boolean onMousePressed(final int mouseButton) {
        final ItemStack carried = codechicken.nei.ItemPanels.itemPanel.draggedStack;
        final int mx = getContext().getAbsMouseX(), my = getContext().getAbsMouseY();
        // Any other press ends a middle pan whose release went missing.
        if (mouseButton != 2) canvas.endMiddlePan();
        // The middle button pans from anywhere on the board, cards included.
        if (mouseButton == 2 && carried == null
            && canvas.getArea()
                .isInside(mx, my)
            && !(getPanelManager().getTopMostPanel() instanceof Popup)) {
            canvas.beginMiddlePan();
            return true;
        }
        if (carried == null || !getMainPanel().getArea()
            .isInside(mx, my)) return super.onMousePressed(mouseButton);
        codechicken.nei.ItemPanels.itemPanel.draggedStack = null;
        if (mouseButton == 0 && canvas.getArea()
            .isInside(mx, my) && !(getPanelManager().getTopMostPanel() instanceof Popup)) {
            final ItemStack one = carried.copy();
            one.stackSize = 1;
            canvas.dropNeiItem(one, mx, my);
        }
        return true;
    }

    @Override
    public boolean onMouseRelease(final int mouseButton) {
        if (mouseButton == 2) canvas.endMiddlePan();
        final boolean taken = super.onMouseRelease(mouseButton);
        // A task card or checklist line let go off its view: it is put down all the same.
        library.tasks.screenReleased(mouseButton);
        com.gtnhplanner.ui.task.TaskPanel.screenReleased(mouseButton);
        return taken;
    }

    @Override
    public void onOpen() {
        super.onOpen();
        session.reopen();
        session.disarmPending();
        // After a crash the planner had a hand in: whether to send the report (not over the tour's first offer).
        // Else a newer release, once per version. A plan the website sent comes first: the player just asked for it.
        if (PlannerSettings.tourOffered() && !com.gtnhplanner.ui.tutorial.Tutorial.active()) {
            if (com.gtnhplanner.ui.popup.IncomingOffer.offer(getMainPanel())) return;
            if (com.gtnhplanner.client.CrashReports.pending() != null)
                com.gtnhplanner.ui.popup.CrashOffer.offer(getMainPanel(), session, canvas);
            else com.gtnhplanner.ui.popup.UpdateOffer.offer(getMainPanel());
        }
    }

    @Override
    public void onClose() {
        session.close();
        super.onClose();
    }

    /**
     * Where NEI's item list was, and how wide the screen, when the board last measured its width against it. ModularUI
     * measures as the screen opens, before NEI has laid itself out for the board (back from a recipe page it still
     * holds the recipe page's layout), so FlowchartLayoutStyle has the board measure again whenever the list is
     * elsewhere.
     */
    private static int sizedPanelX = -1, sizedScreenW = -1;

    /** Whether the board's width was measured against NEI's item list where it is now. */
    public static boolean sizedFor(final int panelX, final int screenW) {
        return panelX == sizedPanelX && screenW == sizedScreenW;
    }

    /**
     * The board lays out on the screen measured in its own pixels ({@link PlannerScale}), a step or two smaller than
     * the game's that NEI keeps; it keeps clear of NEI's search box, which is measured in the game's.
     */
    @Override
    public void onResize(final int width, final int height) {
        getMainPanel().marginBottom(PlannerScale.fromGame(22));
        super.onResize(PlannerScale.boardWidth(), PlannerScale.boardHeight());
    }

    /** Drawn scaled to the game's GUI pixels; NEI draws between this and the foreground, unscaled. */
    @Override
    public void drawScreen() {
        final float k = PlannerScale.ratio();
        org.lwjgl.opengl.GL11.glPushMatrix();
        org.lwjgl.opengl.GL11.glScalef(k, k, 1);
        super.drawScreen();
        org.lwjgl.opengl.GL11.glPopMatrix();
    }

    @Override
    public void drawForeground() {
        final float k = PlannerScale.ratio();
        org.lwjgl.opengl.GL11.glPushMatrix();
        org.lwjgl.opengl.GL11.glScalef(k, k, 1);
        super.drawForeground();
        drawLinkBanner();
        drawPortTooltip();
        org.lwjgl.opengl.GL11.glPopMatrix();
    }

    /** While placing a card on a block from the world: which block, and what to do, at the bottom of the board. */
    private void drawLinkBanner() {
        final com.gtnhplanner.ui.world.LinkTarget.Block b = com.gtnhplanner.ui.world.LinkTarget.target();
        if (b == null) return;
        final String what = "Place a card at " + b.x()
            + ", "
            + b.y()
            + ", "
            + b.z()
            + (b.item() == null ? ""
                : ", in front of the " + b.item()
                    .getDisplayName());
        final String how = "Click a card, in this plan or another (tabs, Plans). Esc cancels.";
        final String note = com.gtnhplanner.ui.world.LinkTarget.note();
        final int w = Math.max(Math.max(Hyb.width(what) + 24, Hyb.width(how) + 24), Hyb.width(note) + 24) + 8;
        final int h = note.isEmpty() ? 30 : 41;
        final com.cleanroommc.modularui.widget.sizer.Area a = canvas.getArea();
        final float x = a.x + (a.width - w) / 2f, y = a.y + a.height - h - 10;
        Hyb.rect(x - 1, y - 1, w + 2, h + 2, Hyb.SELECTION);
        Hyb.rect(x, y, w, h, 0xF0141416);
        if (b.item() != null) Hyb.item(b.item(), x + 6, y + 7, 16, 0);
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_LIGHTING);
        Hyb.text(what, x + 28, y + 5, Hyb.INK);
        Hyb.text(how, x + 28, y + 17, Hyb.MUTED);
        if (!note.isEmpty()) Hyb.text(note, x + 28, y + 29, Hyb.RED_INK);
    }

    /**
     * The board's tooltips, in Factory Flow's panel: NEI skips its own on ModularUI screens whenever a widget is
     * hovered, so the board draws them in the foreground pass. A multiblock's power chips answer with the power panel,
     * and a card far out with its reveal.
     */
    /** Every tip waits until the mouse has rested on its thing a moment ({@link Tip#WAIT_MS}). */
    private final Tip.Wait tipWait = new Tip.Wait();

    private void drawPortTooltip() {
        // While something is in hand (a wire, a drawer picked up, a card carried) the board shows where it can go: no
        // tips over it.
        if (canvas.inHand()) return;
        final Object hovered = getContext().getHovered();
        // While a popup is open, only it explains itself: a tip from the board would cover it.
        final ModularPanel top = getPanelManager().getTopMostPanel();
        // The thing the tip is about: a widget, an overview row or a tab (by what they say), a wire on the board.
        final Object target = hovered == null || hovered == canvas ? canvas.wireKeyUnderMouse()
            : hovered instanceof final OverviewRail overview ? overview.hoverLines()
                : hovered instanceof final PlanTabs tabs ? tabs.hoverLines() : hovered;
        if (!tipWait.ready(target)) return;
        if (top instanceof Popup && !(hovered instanceof final IWidget w && w.getPanel() == top)) return;
        final int mx = getContext().getAbsMouseX(), my = getContext().getAbsMouseY();
        // NEI's item list draws over anything past the planner's right edge: tips stay left of it.
        final int right = getMainPanel().getArea().x + getMainPanel().getArea().width;
        final int bottom = getMainPanel().getArea().y + getMainPanel().getArea().height;
        final Tip tip;
        if (hovered instanceof final RecipeCard card) {
            if (card.drawPower(right, bottom)) return;
            // Zoomed out, a card answers with its own panel: name, numbers, ins and outs.
            if (card.drawReveal(mx, my, right, bottom)) return;
            tip = card.tip();
        } else if (hovered instanceof final DrawerCard drawer) {
            tip = drawer.tip();
        } else if (hovered instanceof final com.gtnhplanner.ui.note.NoteCard note) {
            tip = note.tip();
        } else if (hovered instanceof final com.gtnhplanner.ui.group.GroupCard group) {
            tip = group.tip();
        } else if (hovered instanceof final PlanTabs tabs) {
            tip = Tip.ofLines(tabs.hoverLines());
        } else if (hovered instanceof final OverviewRail overview) {
            final Tip railTip = overview.hoverTip();
            if (railTip == null) return;
            // Beside the rail, so the tip never covers the row it is about.
            railTip.draw(
                overview.getArea().x + overview.getArea().width + 4,
                Math.max(2, Math.min(my - 12, bottom - railTip.height())));
            return;
        } else if (hovered == null || hovered == canvas) {
            tip = Tip.ofLines(canvas.wireLines());
        } else if (hovered instanceof final SelectionBar bar) {
            tip = bar.overGroupKey() ? Tip.of("Group these")
                .muted("A frame round the selection. Drag things in and out of it.")
                .action(Tip.Input.KEY, "Ctrl+G")
                : Tip.of("Combine into one shared machine")
                    .muted("The selected recipes run on one machine and share its settings.");
        } else if (hovered instanceof final PortSlot slot) {
            tip = portTip(slot);
        } else if (hovered instanceof final com.gtnhplanner.ui.card.SocketSlot socket) {
            tip = socket.supply ? Tip.of("Supply any")
                .muted("Supply side: wire a machine input (or a drawer) here, and this card supplies it at the rate you set.")
                .action(Tip.Input.LEFT, "Drag to a machine's input")
                : Tip.of("Drain any")
                    .muted("Request side: wire a machine output (or a drawer) here, and this card drains it at the rate you set.")
                    .action(Tip.Input.LEFT, "Drag to a machine's output");
        } else if (hovered instanceof final IWidget w && KEY_TIPS.containsKey(w)) {
            tip = Tip.ofLines(java.util.Arrays.asList(KEY_TIPS.get(w).split("\n")));
        } else {
            return;
        }
        if (tip != null) tip.drawNear(mx, my, right, bottom);
    }

    /**
     * A port's spare row: what leaves (or comes in) there with no drawer, and where else it could; or that it has
     * nowhere else.
     */
    private Tip spareTip(final PortSlot slot, final CardModel.PortView view) {
        BoardSession.SpareLine found = null;
        BoardSession.SpareBearer here = null;
        for (final BoardSession.SpareLine line : session.spares())
            for (final BoardSession.SpareBearer b : line.bearers())
                for (final com.gtnhplanner.data.flowchart.balancer.PortRef ref : b.ports()) if (ref.nodeId()
                    .equals(slot.node()) && ref.input() != view.output()
                    && ref.portIndex() == view.index()) {
                        found = line;
                        here = b;
                    }
        // Its whole story, as the overview tells it: what was pinned, why it leaves or comes in, who else could.
        if (found != null) return SpareTips.situation(
            session,
            found,
            found.bearers()
                .size() > 1 ? "Choose who takes it" : null,
            found.bearers()
                .size() > 1);
        final Tip tip = Tip.of((view.output() ? "Forced output: " : "Forced input: ") + view.name())
            .row(
                view.output() ? "Ships out here" : "Comes in here",
                Fmt.rate(view.extra(), session.rateUnit(), view.isFluid()));
        if (found == null || found.bearers()
            .size() < 2)
            return tip
                .muted(view.output() ? "Only this machine can ship it out." : "Only this machine can take it in.");
        final StringBuilder others = new StringBuilder();
        for (final BoardSession.SpareBearer b : found.bearers()) {
            if (b == here) continue;
            if (others.length() > 0) others.append(", ");
            others.append(b.name())
                .append(" at ")
                .append(b.machine());
        }
        return tip.muted((view.output() ? "Could also ship out as " : "Could also come in as ") + others + ".")
            .action(Tip.Input.LEFT, "Choose who takes it")
            .action(Tip.Input.DRAG, "Hand it to another card");
    }

    /** A port: what it is, its rate, NEI's own lines about the stack, and what the mouse and keys do with it. */
    private Tip portTip(final PortSlot slot) {
        final CardModel.PortView view = slot.view();
        if (view == null) return null;
        if (view.hasExtra() && slot.overExtraRow()) return spareTip(slot, view);
        if (slot.overKey()) {
            final CardModel model = session.model(slot.node());
            final boolean set = model != null && session.portTarget(model.node, view.output(), view.index()) != null;
            return Tip.of(set ? "Exactly" : "Any")
                .muted(
                    set ? "Set: the machine runs so this " + (view.output() ? "output gives" : "input takes")
                        + " exactly its rate."
                        : "The plan decides this " + (view.output() ? "output's" : "input's") + " rate.")
                .action(Tip.Input.LEFT, set ? "Let the plan decide" : "Set it to what it is now");
        }
        if (slot.overRate()) {
            final CardModel model = session.model(slot.node());
            final Double set = model == null ? null : session.portTarget(model.node, view.output(), view.index());
            return Tip.of(view.name())
                .sub(set != null ? "Rate set" : "Rate: the plan decides")
                .row(view.output() ? "Gives" : "Takes", Fmt.rate(view.perSecond(), session.rateUnit(), view.isFluid()))
                .muted(
                    "Set it and the machine runs so this " + (view.output() ? "output gives" : "input takes")
                        + " exactly that.")
                .action(Tip.Input.LEFT, set != null ? "Change its rate" : "Set its rate")
                .action(Tip.Input.DRAG, "Connect");
        }
        // Unwired and running: a forced input or output, told as the overview's are.
        if (RecipeCard.isOpen(view)) {
            final CardModel model = session.model(slot.node());
            return actions(SpareTips.open(session, slot.node(), model == null ? "" : model.machineName, view), slot);
        }
        final ItemStack stack = slot.stack();
        final boolean chanced = view.output() && view.chance() < 0.9999f;
        final Tip tip = Tip.of(view.name())
            .sub(view.output() ? "Output" : "Input")
            .row(
                view.output() ? chanced ? "Average output" : "Produced" : "Consumed",
                Fmt.rate(view.perSecond(), session.rateUnit(), view.isFluid()));
        if (chanced) tip.row("Chance", Fmt.compact(view.chance() * 100) + "%");
        if (stack != null) {
            // NEI's lines past the name: a formula, a material's notes, the mod. A few at most.
            final List<String> nei = GuiContainerManager.itemDisplayNameMultiline(stack, null, true);
            for (int i = 1; i < Math.min(nei.size(), 5); i++) tip.note(nei.get(i), Tip.SUBTLE);
        }
        return actions(tip, slot);
    }

    /** What the mouse does with a port; the wheel changes the item of an input whose slot takes several. */
    private Tip actions(final Tip tip, final PortSlot slot) {
        final com.gtnhplanner.data.flowchart.Node node = slot.hasChoices() ? session.graph().nodes.get(slot.node())
            : null;
        if (node != null) tip.muted(
            "Takes any of " + node.inputs.get(slot.index)
                .getAlternatives()
                .size() + " items.");
        tip.action(Tip.Input.LEFT, "Recipes: what makes it")
            .action(Tip.Input.RIGHT, "Uses: what uses it");
        if (node != null) tip.action(Tip.Input.WHEEL, "Change item");
        return tip.action(Tip.Input.DRAG, "Connect")
            .action(Tip.Input.KEY, "R, U");
    }
}
