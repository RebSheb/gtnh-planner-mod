package com.gtnhplanner.ui.popup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiPlayerInfo;
import net.minecraft.util.EnumChatFormatting;

import com.cleanroommc.modularui.api.UpOrDown;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widget.ParentWidget;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.cloud.net.CloudApi;
import com.gtnhplanner.cloud.net.LiveClient;
import com.gtnhplanner.cloud.session.LiveDoc;
import com.gtnhplanner.library.Account;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.PlannerScale;
import com.gtnhplanner.ui.card.CardPaint;
import com.gtnhplanner.ui.live.Peers;
import com.gtnhplanner.ui.sound.Sfx;
import com.gtnhplanner.ui.task.TaskLook;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * Share with people (the top bar's Share menu, for a cloud plan): a box in the middle of the screen in the card's look.
 * Who can edit the plan (its owner, then its members: the owner removes them, a member leaves), then, for the owner,
 * ways to add someone: the signed-in players in this game first (the live service's nearby, asked with the names on the
 * tab list), then a search of every account as the name is typed. Network calls run in the background; the site's own
 * words say what went wrong, in one line.
 */
public final class SharePanel extends ParentWidget<SharePanel> implements Interactable {

    private static final int MAX_W = 300, TITLE = 22, PAD = 10, ROW = 16, KEY_H = 14, LABEL = 12, FIELD_H = 14;
    /** Where the people who can edit start, under the title and the line that says who adds them. */
    private static final int PEOPLE_Y = TITLE + 22 + LABEL;
    /** The box's size, fitted to the planner when it opened. */
    private final int boxW, boxH;
    /**
     * Rows each list shows before it scrolls (fewer when the planner is short: the lists scroll), and where the lists
     * after the first go.
     */
    private final int peopleRows, gameRows, foundRows, gameY, fieldY, foundY;
    private static final int LABEL_INK = 0xFF7A7C84, ROW_HOVER = 0xFF2A2C31, CONTROL_HOVER = 0xFF33363C;
    /** How long the name being typed must rest before it is searched for. */
    private static final long SEARCH_AFTER_MS = 300;
    /** The site's own rule for a name searched for: letters, digits, - and _. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,24}");

    private static final ExecutorService NET = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "GTNH Planner sharing");
        t.setDaemon(true);
        return t;
    });

    private final BoardSession session;
    private final LiveDoc doc;
    private final TextFieldWidget field;
    private final Runnable onPeople = this::fromRoom;

    @Nullable
    private LiveClient.Member owner;
    private List<LiveClient.Member> members;
    /** The players in this game who are signed in; null while they are looked for. */
    @Nullable
    private List<CloudApi.Player> inGame;
    private List<CloudApi.Account> found = List.of();
    /** The name as typed, when it was last changed, and the name last searched for. */
    private String typed = "", searched = "";
    private long typedAt;
    private boolean searching;
    /** Accounts being added or removed. */
    private final Set<String> busy = new HashSet<>();
    @Nullable
    private String error;
    private int peopleScroll, gameScroll, foundScroll;
    private boolean focusedOnce;

    private enum Kind {
        CLOSE,
        ADD,
        REMOVE,
        LEAVE,
        LOOK_AGAIN
    }

    private record Hit(Kind kind, int x0, int y0, int x1, int y1, @Nullable Object data) {

        boolean contains(final int x, final int y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }
    }

    private final List<Hit> hits = new ArrayList<>();

    private SharePanel(final BoardSession session, final LiveDoc doc, final int roomW, final int roomH) {
        this.session = session;
        this.doc = doc;
        owner = doc.owner();
        members = doc.members();
        boxW = Math.min(MAX_W, roomW);
        // The lists' rows: as many as wanted, fewer while the box would be taller than the room. Only the owner has
        // the lists that add people.
        final boolean adds = owns();
        final int[] rows = { 5, 3, 4 };
        final int[][] fewer = { { 2, 2 }, { 0, 3 }, { 1, 1 }, { 0, 2 } };
        for (final int[] step : fewer) {
            while (rows[step[0]] > step[1] && height(rows, adds) > roomH) rows[step[0]]--;
        }
        peopleRows = rows[0];
        gameRows = rows[1];
        foundRows = rows[2];
        gameY = PEOPLE_Y + peopleRows * ROW + 10 + LABEL;
        fieldY = gameY + gameRows * ROW + 10 + LABEL;
        foundY = fieldY + FIELD_H + 4;
        boxH = height(rows, adds);
        size(boxW, boxH);
        final String[] text = { "" };
        field = new TextFieldWidget().value(new StringValue.Dynamic(() -> text[0], s -> text[0] = s == null ? "" : s));
        field.setMaxLength(24)
            .hintText("Type a name")
            .pos(PAD, fieldY)
            .size(boxW - 2 * PAD, FIELD_H);
        field.setEnabled(owns());
        child(field);
    }

    /** The box's height with {@code rows} rows in its lists (people, this game, found); the owner's has all three. */
    private static int height(final int[] rows, final boolean adds) {
        final int people = PEOPLE_Y + rows[0] * ROW;
        if (!adds) return people + 24;
        return people + 10 + LABEL + rows[1] * ROW + 10 + LABEL + FIELD_H + 4 + rows[2] * ROW + 24;
    }

    /** Opens the box for the open plan, when it is a cloud plan: in the middle of the planner, fitted to it. */
    public static void open(final ModularPanel parent, final BoardSession session) {
        final LiveDoc doc = session.live();
        if (doc == null) return;
        final int[] room = Popup.room(parent);
        final SharePanel share = new SharePanel(session, doc, room[2] - 8, room[3] - 8);
        final int w = share.boxW, h = share.boxH;
        final Popup popup = new Popup("gtnhplanner_share", w, h) {

            @Override
            public void drawBackground(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
                Hyb.rect(-getArea().x, -getArea().y, PlannerScale.width(), PlannerScale.height(), 0x60000000);
                Hyb.dropShadow(0, 0, w, h);
                CardPaint.surface(w, h);
            }
        };
        popup.child(share.pos(0, 0));
        popup.onCloseAction(share::closing);
        doc.addPeopleListener(share.onPeople);
        share.refresh();
        if (share.owns()) share.lookAround();
        Popup.openCentred(parent, popup, 0.5f);
    }

    private void closing() {
        doc.removePeopleListener(onPeople);
    }

    /** Whether the player owns the plan: only the owner adds and removes people. */
    private boolean owns() {
        final String me = Account.userId();
        return me != null && me.equals(doc.ownerId());
    }

    // region The network

    /** Runs a call in the background; its answer, or why it failed, on the client thread while the box is open. */
    private <T> void call(final Call<T> work, final Consumer<T> done, final Consumer<String> failed) {
        final String token = Account.token();
        if (token == null) {
            failed.accept("Sign in to share");
            return;
        }
        NET.execute(() -> {
            try {
                final T answer = work.run(token);
                onClient(() -> {
                    if (isValid()) done.accept(answer);
                });
            } catch (final IOException | RuntimeException e) {
                GtnhPlanner.LOG.debug("Sharing: a call failed", e);
                final String why = e instanceof final CloudApi.Refused r ? refused(r) : "Your account could not be reached";
                onClient(() -> {
                    if (isValid()) failed.accept(why);
                });
            }
        });
    }

    private interface Call<T> {

        T run(String token) throws IOException;
    }

    private static void onClient(final Runnable r) {
        Minecraft.getMinecraft()
            .func_152344_a(r);
    }

    /** What the site said no with, as one plain line. */
    private static String refused(final CloudApi.Refused r) {
        if (r.status == 401) return "Your sign-in ran out";
        if (r.status == 429) return "Too many tries so wait a moment";
        final String said = r.getMessage();
        if (said == null || said.isBlank() || said.startsWith("the site answered")) return "That did not work";
        final String line = Character.toUpperCase(said.charAt(0)) + said.substring(1);
        return line.endsWith(".") ? line.substring(0, line.length() - 1) : line;
    }

    /** Who may edit the plan, as the site has it now. */
    private void refresh() {
        if (doc.creating()) return;
        final String ownerId = owns() ? null : doc.ownerId();
        call(t -> CloudApi.sharing(t, doc.designId(), ownerId), s -> {
            if (!s.ownerName()
                .isEmpty()) owner = new LiveClient.Member(doc.ownerId(), s.ownerName());
            members = List.copyOf(s.members());
        }, why -> error = why);
    }

    /** The room says who may edit it now (someone shared or unshared it). */
    private void fromRoom() {
        if (doc.owner() != null) owner = doc.owner();
        members = doc.members();
    }

    /**
     * The signed-in players in this game, asked with the names on the tab list. The service limits how often: asked
     * when the box opens and when the player asks again, never on a timer.
     */
    private void lookAround() {
        final List<String> names = tabList();
        if (names.isEmpty()) {
            inGame = List.of();
            return;
        }
        inGame = null;
        call(t -> CloudApi.nearby(t, names), players -> inGame = players, why -> {
            inGame = List.of();
            error = why;
        });
    }

    /** The players on the tab list, as Minecraft names them (the player too: the service leaves them out). */
    private static List<String> tabList() {
        final Minecraft mc = Minecraft.getMinecraft();
        final List<String> names = new ArrayList<>();
        if (mc.thePlayer == null || mc.thePlayer.sendQueue == null) return names;
        for (final GuiPlayerInfo p : mc.thePlayer.sendQueue.playerInfoList) {
            if (names.size() >= CloudApi.NEARBY_NAMES) break;
            final String name = EnumChatFormatting.getTextWithoutFormattingCodes(p.name);
            if (name != null && !name.isBlank()) names.add(name.trim());
        }
        return names;
    }

    private void add(final String id, final String username) {
        if (doc.creating()) {
            error = "The plan is still being made in your account";
            return;
        }
        if (!busy.add(id)) return;
        error = null;
        call(t -> {
            CloudApi.addMember(t, doc.designId(), id, null);
            return Boolean.TRUE;
        }, ok -> {
            busy.remove(id);
            if (!isMember(id)) {
                final List<LiveClient.Member> next = new ArrayList<>(members);
                next.add(new LiveClient.Member(id, username));
                members = List.copyOf(next);
            }
            Sfx.TOGGLE_ON.play();
            refresh();
        }, why -> {
            busy.remove(id);
            error = why;
        });
    }

    private void remove(final String id) {
        if (!busy.add(id)) return;
        error = null;
        call(t -> {
            CloudApi.removeMember(t, doc.designId(), id, null);
            return Boolean.TRUE;
        }, ok -> {
            busy.remove(id);
            final List<LiveClient.Member> next = new ArrayList<>(members);
            next.removeIf(
                m -> m.id()
                    .equals(id));
            members = List.copyOf(next);
            Sfx.TOGGLE_OFF.play();
            refresh();
        }, why -> {
            busy.remove(id);
            error = why;
        });
    }

    /** Leaving is the tab's own Leave plan: asked first, then the plan goes from the planner. */
    private void leave() {
        final ModularPanel p = getPanel();
        final int x = getArea().x + boxW / 2 - 75, y = getArea().y + boxH / 3;
        if (p != null) p.closeIfOpen();
        session.confirmDelete(session.activeSlot(), session.panel(), x, y);
    }

    private boolean isMember(final String id) {
        if (owner != null && owner.id()
            .equals(id)) return true;
        for (final LiveClient.Member m : members) if (m.id()
            .equals(id)) return true;
        return false;
    }

    // endregion

    @Override
    public void onUpdate() {
        super.onUpdate();
        // The plan left the planner (unshared, deleted) or another opened: this box is about none of it.
        if (session.live() != doc) {
            final ModularPanel p = getPanel();
            if (p != null) p.closeIfOpen();
            return;
        }
        if (!focusedOnce && isValid() && owns()) {
            focusedOnce = true;
            getContext().focus(field);
        }
        // The name typed, searched for once it has rested a moment.
        final String now = field.getText()
            .trim();
        if (!now.equals(typed)) {
            typed = now;
            typedAt = System.currentTimeMillis();
        }
        if (!typed.equals(searched) && System.currentTimeMillis() - typedAt >= SEARCH_AFTER_MS) {
            searched = typed;
            foundScroll = 0;
            if (!NAME.matcher(typed)
                .matches()) {
                found = List.of();
                searching = false;
                return;
            }
            final String q = typed;
            searching = true;
            call(t -> CloudApi.search(t, q), accounts -> {
                // Only the answer for what is typed now.
                if (!q.equals(searched)) return;
                found = accounts;
                searching = false;
            }, why -> {
                searching = false;
                error = why;
            });
        }
    }

    // region Drawing

    private int mouseX() {
        return getContext().getAbsMouseX() - getArea().x;
    }

    private int mouseY() {
        return getContext().getAbsMouseY() - getArea().y;
    }

    @Override
    public boolean canHover() {
        return true;
    }

    @Override
    public void draw(final ModularGuiContext context, final WidgetThemeEntry<?> widgetTheme) {
        hits.clear();
        final boolean hovering = isHovering();
        final int mx = hovering ? mouseX() : -1, my = hovering ? mouseY() : -1;
        final String me = Account.userId();
        final boolean owns = owns();

        Hyb.text("Plan together", PAD, (TITLE - 8) / 2f + 1, Hyb.INK);
        final int[] close = { boxW - 4 - 14, (TITLE - 14) / 2, 14, 14 };
        final boolean closeHot = in(close, mx, my);
        if (closeHot) Hyb.rect(close[0], close[1], close[2], close[3], CONTROL_HOVER);
        cross(close[0] + close[2] / 2f, close[1] + close[3] / 2f, closeHot ? Hyb.INK : Hyb.MUTED);
        hits.add(new Hit(Kind.CLOSE, close[0], close[1], close[0] + close[2], close[1] + close[3], null));
        Hyb.rect(1, TITLE, boxW - 2, 1, CardPaint.HAIR);
        final String says = owns ? "People you add can edit this plan with you" : "Only the owner can add people";
        Hyb.text(
            Hyb.width(says) <= boxW - 2 * PAD ? says : owns ? "People you add can edit it" : says,
            PAD,
            TITLE + 8,
            Hyb.MUTED);

        // Who can edit it: the owner, then the members.
        Hyb.text("WHO CAN EDIT", PAD, PEOPLE_Y - LABEL + 2, LABEL_INK);
        final List<LiveClient.Member> people = new ArrayList<>();
        if (owner != null) people.add(owner);
        for (final LiveClient.Member m : members) if (owner == null || !owner.id()
            .equals(m.id())) people.add(m);
        peopleScroll = clampScroll(peopleScroll, people.size(), peopleRows);
        for (int i = 0; i < peopleRows && i + peopleScroll < people.size(); i++) {
            final LiveClient.Member m = people.get(i + peopleScroll);
            final int y = PEOPLE_Y + i * ROW;
            final boolean isOwner = owner != null && owner.id()
                .equals(m.id()), mine = m.id()
                    .equals(me);
            person(m.username() + (mine ? " (you)" : ""), m.username(), m.id(), y, mx, my);
            if (isOwner) Hyb.textRight("Owner", boxW - PAD, y + 4, Hyb.MUTED);
            else if (busy.contains(m.id())) Hyb.textRight("...", boxW - PAD, y + 4, Hyb.MUTED);
            else if (owns) key("Remove", Kind.REMOVE, m.id(), y, mx, my);
            else if (mine) key("Leave", Kind.LEAVE, m.id(), y, mx, my);
        }
        if (people.isEmpty()) Hyb.text("Looking...", PAD, PEOPLE_Y + 4, Hyb.MUTED);

        if (owns) {
            // The signed-in players in this game.
            Hyb.text("IN THIS GAME", PAD, gameY - LABEL + 2, LABEL_INK);
            // Asked again only when the player says so: the service limits how often.
            if (inGame != null) {
                final String again = "Look again";
                final int ax = boxW - PAD - Hyb.width(again), ay = gameY - LABEL + 2;
                final boolean hot = mx >= ax && mx < boxW - PAD && my >= ay - 2 && my < ay + 9;
                Hyb.text(again, ax, ay, hot ? 0xFFFFFFFF : 0xFF9FD9E6);
                hits.add(new Hit(Kind.LOOK_AGAIN, ax, ay - 2, boxW - PAD, ay + 9, null));
            }
            if (inGame == null) Hyb.text("Looking for players...", PAD, gameY + 4, Hyb.MUTED);
            else if (inGame.isEmpty()) Hyb.text("Nobody else here is signed in", PAD, gameY + 4, Hyb.MUTED);
            else {
                gameScroll = clampScroll(gameScroll, inGame.size(), gameRows);
                for (int i = 0; i < gameRows && i + gameScroll < inGame.size(); i++) {
                    final CloudApi.Player p = inGame.get(i + gameScroll);
                    final int y = gameY + i * ROW;
                    final int nameEnd = person(p.username(), p.username(), p.id(), y, mx, my);
                    if (!p.mcName()
                        .isEmpty()
                        && !p.mcName()
                            .equalsIgnoreCase(p.username()))
                        Hyb.text(p.mcName(), nameEnd + 5, y + 4, Hyb.MUTED);
                    addKey(p.id(), p.username(), y, mx, my);
                }
            }

            // Every account, by the start of its name.
            Hyb.text("FIND AN ACCOUNT", PAD, fieldY - LABEL + 2, LABEL_INK);
            if (searching && found.isEmpty()) Hyb.text("Searching...", PAD, foundY + 4, Hyb.MUTED);
            else if (found.isEmpty() && !searched.isEmpty() && searched.equals(typed))
                Hyb.text("No one by that name", PAD, foundY + 4, Hyb.MUTED);
            foundScroll = clampScroll(foundScroll, found.size(), foundRows);
            for (int i = 0; i < foundRows && i + foundScroll < found.size(); i++) {
                final CloudApi.Account a = found.get(i + foundScroll);
                final int y = foundY + i * ROW;
                person(a.username(), a.username(), a.id(), y, mx, my);
                addKey(a.id(), a.username(), y, mx, my);
            }
        }
        // What went wrong, in one line: the site's own words when they fit, never cut.
        if (error != null)
            Hyb.text(Hyb.width(error) <= boxW - 2 * PAD ? error : "That did not work", PAD, boxH - 14, Hyb.AMBER_INK);
    }

    /** A person's row: lit under the mouse, their face and what they are called. Returns where the label ends. */
    private int person(final String label, final String name, final String id, final int y, final int mx,
        final int my) {
        if (my >= y && my < y + ROW && mx > 0 && mx < boxW) Hyb.rect(PAD - 4, y, boxW - 2 * PAD + 8, ROW, ROW_HOVER);
        final int face = TaskLook.face(name, Peers.colourOf(id), PAD, y + 2);
        Hyb.text(label, PAD + face + 5, y + 4, Hyb.INK);
        return PAD + face + 5 + Hyb.width(label);
    }

    /** A row's Add key, or that they are in already, or on their way. */
    private void addKey(final String id, final String username, final int y, final int mx, final int my) {
        if (isMember(id)) Hyb.textRight("Can edit", boxW - PAD, y + 4, Hyb.MUTED);
        else if (busy.contains(id)) Hyb.textRight("Adding...", boxW - PAD, y + 4, Hyb.MUTED);
        else key("Add", Kind.ADD, new String[] { id, username }, y, mx, my);
    }

    /** A small key at a row's right end. */
    private void key(final String label, final Kind kind, final Object data, final int y, final int mx, final int my) {
        final int w = Hyb.width(label) + 10, x = boxW - PAD - w, ky = y + (ROW - KEY_H) / 2;
        final boolean hot = mx >= x && mx < x + w && my >= ky && my < ky + KEY_H;
        Hyb.bevel(x, ky, w, KEY_H, hot ? Hyb.KEY_HOVER : Hyb.KEY, Hyb.KEY_HI, Hyb.KEY_LO, 0, 1);
        Hyb.text(label, x + 5, ky + 3, kind == Kind.REMOVE || kind == Kind.LEAVE ? Hyb.RED_INK : Hyb.INK);
        hits.add(new Hit(kind, x, ky, x + w, ky + KEY_H, data));
    }

    private static int clampScroll(final int scroll, final int size, final int rows) {
        return Math.max(0, Math.min(scroll, size - rows));
    }

    private static boolean in(final int[] r, final int x, final int y) {
        return x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
    }

    private static void cross(final float cx, final float cy, final int colour) {
        for (int d = -3; d <= 3; d++) {
            Hyb.rect(cx + d - 0.5f, cy + d - 0.5f, 1, 1, colour);
            Hyb.rect(cx + d - 0.5f, cy - d - 0.5f, 1, 1, colour);
        }
    }

    // endregion

    // region Input

    @Override
    public Result onMousePressed(final int mouseButton) {
        if (mouseButton != 0) return Result.ACCEPT;
        final int mx = mouseX(), my = mouseY();
        for (final Hit h : new ArrayList<>(hits)) {
            if (!h.contains(mx, my)) continue;
            switch (h.kind()) {
                case CLOSE -> {
                    final ModularPanel p = getPanel();
                    if (p != null) p.closeIfOpen();
                    Sfx.CLOSE.play();
                }
                case ADD -> {
                    Hyb.click();
                    final String[] who = (String[]) h.data();
                    add(who[0], who[1]);
                }
                case REMOVE -> {
                    Hyb.click();
                    remove((String) h.data());
                }
                case LEAVE -> {
                    Hyb.click();
                    leave();
                }
                case LOOK_AGAIN -> {
                    Hyb.click();
                    error = null;
                    lookAround();
                }
            }
            return Result.SUCCESS;
        }
        // A press anywhere else ends the typing.
        if (!field.isHovering()) getContext().removeFocus();
        return Result.ACCEPT;
    }

    @Override
    public boolean onMouseScroll(final UpOrDown direction, final int amount) {
        final int my = mouseY(), step = direction == UpOrDown.UP ? -1 : 1;
        if (my >= PEOPLE_Y && my < PEOPLE_Y + peopleRows * ROW) peopleScroll = Math.max(0, peopleScroll + step);
        else if (my >= gameY && my < gameY + gameRows * ROW) gameScroll = Math.max(0, gameScroll + step);
        else if (my >= foundY && my < foundY + foundRows * ROW) foundScroll = Math.max(0, foundScroll + step);
        return true;
    }

    // endregion
}
