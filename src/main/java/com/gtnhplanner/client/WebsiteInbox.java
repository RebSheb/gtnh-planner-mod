package com.gtnhplanner.client;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.Tags;
import com.gtnhplanner.importer.FfConverter;
import com.gtnhplanner.importer.ImportReport;
import com.gtnhplanner.importer.game.FactoryFlowImport;
import com.gtnhplanner.library.CommunityApi;
import com.gtnhplanner.ui.BoardScreen;
import com.gtnhplanner.ui.Planner;

/**
 * Plans the website sends with its "Open in the mod" ({@link WebsiteListener}): never opened unasked. The newest one
 * waits here, and the game asks about it: at once when the planner is open (a prompt over the board), else with a chat
 * line whose Open and Not now answer it, and again when the planner next opens. Open adds it as a new plan through
 * the importer and shows it.
 *
 * <p>
 * The listener runs on the client only (the client proxy starts it; a dedicated server never loads this) and goes
 * with the game.
 */
public final class WebsiteInbox {

    /** A plan waiting for an answer, and the key its chat line's Open must name (so no other line can answer it). */
    public record Pending(WebsiteListener.Arrival arrival, String key) {}

    @Nullable
    private static volatile WebsiteListener listener;
    @Nullable
    private static Pending pending;

    private WebsiteInbox() {}

    /** Starts listening, on the first free port of the range; logs where, or that every port is taken. */
    public static void start() {
        final WebsiteListener l = WebsiteListener.start(
            WebsiteListener.FIRST_PORT,
            WebsiteListener.LAST_PORT,
            Tags.VERSION,
            origin -> WebsiteListener.websiteOrigin(origin, CommunityApi.site()),
            arrival -> Minecraft.getMinecraft()
                .func_152344_a(() -> arrived(arrival)));
        listener = l;
        if (l == null) {
            GtnhPlanner.LOG.info(
                "Open in the mod: ports {}-{} are all taken, so the website cannot send plans to this game",
                WebsiteListener.FIRST_PORT,
                WebsiteListener.LAST_PORT);
            return;
        }
        GtnhPlanner.LOG.info("Open in the mod: listening on 127.0.0.1:{}", l.port());
        Runtime.getRuntime()
            .addShutdownHook(new Thread(l::close, "GTNH Planner website link close"));
    }

    /** The port the website reaches this game on, or -1 when it is not listening. */
    public static int port() {
        final WebsiteListener l = listener;
        return l == null ? -1 : l.port();
    }

    /** The plan waiting for an answer, or null. */
    @Nullable
    public static Pending pending() {
        return pending;
    }

    /** A plan came in (on the client thread): it waits, and the player is asked. */
    static void arrived(final WebsiteListener.Arrival arrival) {
        pending = new Pending(
            arrival,
            UUID.randomUUID()
                .toString()
                .substring(0, 8));
        final Minecraft mc = Minecraft.getMinecraft();
        final BoardScreen board = (BoardScreen) Planner.screenOf(mc.currentScreen);
        if (board != null) {
            // Never over the tour's own plans: it is asked when the planner next opens.
            if (!com.gtnhplanner.ui.tutorial.Tutorial.active())
                com.gtnhplanner.ui.popup.IncomingOffer.offer(board.getMainPanel());
            return;
        }
        if (mc.thePlayer != null) mc.thePlayer.addChatMessage(line(pending));
    }

    /** The chat line while playing: the plan's name, Open and Not now. */
    private static IChatComponent line(final Pending p) {
        final IChatComponent out = new ChatComponentText("The website sent the plan ");
        out.getChatStyle()
            .setColor(EnumChatFormatting.GRAY);
        final IChatComponent name = new ChatComponentText(
            p.arrival()
                .name());
        name.getChatStyle()
            .setColor(EnumChatFormatting.WHITE);
        out.appendSibling(name);
        out.appendSibling(new ChatComponentText(" "));
        out.appendSibling(
            key("[Open]", EnumChatFormatting.AQUA, "Add it to your plans and open it", "open " + p.key()));
        out.appendSibling(new ChatComponentText(" "));
        out.appendSibling(key("[Not now]", EnumChatFormatting.GRAY, "Leave it", "drop " + p.key()));
        return out;
    }

    private static IChatComponent key(final String label, final EnumChatFormatting colour, final String tip,
        final String args) {
        final IChatComponent k = new ChatComponentText(label);
        k.getChatStyle()
            .setColor(colour)
            .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText(tip)))
            .setChatClickEvent(
                new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + WebsiteCommand.COMMAND_NAME + " " + args));
        return k;
    }

    /**
     * Opens the waiting plan when {@code key} is its key (null: whichever waits, from the prompt): adds it as a new
     * plan and shows the planner on it.
     *
     * @return null once open, or what went wrong
     */
    @Nullable
    public static String open(@Nullable final String key) {
        final Pending p = pending;
        if (p == null || key != null && !key.equals(p.key())) return "That plan is gone: send it again";
        pending = null;
        final FfConverter.Result result;
        try {
            result = FactoryFlowImport.importAsSlot(
                p.arrival()
                    .code());
        } catch (final RuntimeException e) {
            GtnhPlanner.LOG.warn("Could not open the plan the website sent", e);
            return "That plan could not be read";
        }
        // On the board already, it follows the new plan by itself (as a paste does); else the planner opens on it.
        final BoardScreen open = (BoardScreen) Planner.screenOf(Minecraft.getMinecraft().currentScreen);
        final BoardScreen board = open != null ? open : Planner.open();
        com.gtnhplanner.ui.sound.Sfx.SWEEP.play();
        final int missing = result.report()
            .entries(ImportReport.Kind.UNMATCHED)
            .size();
        if (missing > 0) board.session()
            .flash(
                com.gtnhplanner.data.flowchart.balancer.Severity.WARN,
                "Opened '" + result.graph()
                    .getName()
                    + "': "
                    + result.report()
                        .summary());
        return null;
    }

    /** Leaves the waiting plan (when {@code key} is its key; null: whichever waits). */
    public static void drop(@Nullable final String key) {
        final Pending p = pending;
        if (p != null && (key == null || key.equals(p.key()))) pending = null;
    }
}
