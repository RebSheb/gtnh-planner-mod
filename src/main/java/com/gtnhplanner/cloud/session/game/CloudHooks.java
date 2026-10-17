package com.gtnhplanner.cloud.session.game;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;

import com.gtnhplanner.Tags;
import com.gtnhplanner.api.PlanAPI;
import com.gtnhplanner.cloud.map.CloudGame;
import com.gtnhplanner.cloud.map.game.GameCloud;
import com.gtnhplanner.cloud.net.LiveClient;
import com.gtnhplanner.cloud.net.LiveSocket;
import com.gtnhplanner.cloud.session.CloudSession;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Plan;
import com.gtnhplanner.data.flowchart.balancer.Severity;
import com.gtnhplanner.library.Account;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.world.PlanSnapshot;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Cloud plans in the game: while an account is signed in the planner's plans are its cloud plans
 * ({@link CloudSession}), started the first time the planner needs them and stopped on sign-out; signed out, they are
 * the world's save, exactly as before. The session runs every client tick and is written to disk as the game closes.
 */
public final class CloudHooks {

    private CloudHooks() {}

    /** Wires it in, once, from the client proxy. */
    public static void init() {
        Plan.setCloudSource(CloudHooks::plans);
        Account.onChange(CloudHooks::accountChanged);
        FMLCommonHandler.instance()
            .bus()
            .register(new CloudHooks.Ticks());
        Runtime.getRuntime()
            .addShutdownHook(new Thread(() -> {
                final CloudSession s = CloudSession.current();
                if (s != null) s.flush();
                LiveSocket.shutdown();
            }, "GTNH Planner cloud on exit"));
    }

    /**
     * The signed-in account's plans, or null (the world's save is opened then) when signed out, or while the account's
     * site is not known to have cloud plans: that is asked in the background, and the plans load again once it has.
     * Client thread.
     */
    @Nullable
    private static Plan plans() {
        final CloudSession s = session();
        if (s == null) return null;
        if (!s.ready()) {
            s.checkReady(Plan::reload);
            return null;
        }
        return s.load();
    }

    /** The session of the account signed in, started now if it is not yet; null when signed out. */
    @Nullable
    public static CloudSession session() {
        final String id = Account.accountId();
        if (id == null) return null;
        final CloudSession s = CloudSession.current();
        if (s != null && s.accountId()
            .equals(id)) return s;
        return CloudSession.start(id, Account::token, Minecraft.getMinecraft().mcDataDir, new GameHost());
    }

    /** Signed in or out: the plans in hand are saved where they belong, and come from where they now do. */
    private static void accountChanged() {
        final CloudSession s = CloudSession.current();
        final String id = Account.accountId();
        Plan.reload();
        if (s != null && !s.accountId()
            .equals(id)) s.stop();
    }

    /** The session's tick, every client tick: network answers, edits out and in. */
    public static final class Ticks {

        @SubscribeEvent
        public void onTick(final TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            final CloudSession s = CloudSession.current();
            if (s == null) return;
            try {
                s.tick();
            } catch (final RuntimeException e) {
                com.gtnhplanner.GtnhPlanner.LOG.error("The cloud plans' tick failed", e);
            }
        }
    }

    /** The game as the session sees it. */
    private static final class GameHost implements CloudSession.Host {

        @Override
        public CloudGame game() {
            return new GameCloud();
        }

        @Override
        public LiveClient.Hello hello() {
            final Minecraft mc = Minecraft.getMinecraft();
            final String world = GameCloud.worldKey();
            return new LiveClient.Hello(
                Tags.VERSION,
                world.isEmpty() ? null : LiveClient.serverHash(world),
                mc.getSession() == null ? null
                    : mc.getSession()
                        .getUsername());
        }

        @Override
        public boolean busy(final Graph graph) {
            final BoardSession board = BoardSession.current();
            return board != null && board.busyWith(graph);
        }

        @Override
        public void replaced(final Graph old, final Graph next) {
            PlanSnapshot.regraph(old, next);
        }

        @Override
        @Nullable
        public Plan worldPlans() {
            return Plan.loadLocal();
        }

        @Override
        public boolean backUpWorld() {
            return PlanAPI.backUpNow("-before-upload");
        }

        @Override
        public boolean saveWorld(final Plan plans) {
            return PlanAPI.saveLocal(plans);
        }

        @Override
        public void shared(final String ownerName, final String planName, final String key) {
            final String text = ownerName + " shared '" + planName + "' with you";
            final BoardSession board = BoardSession.current();
            if (board != null) board.flash(Severity.INFO, text, "Open", () -> board.openPlanByKey(key), 30);
            else if (Minecraft.getMinecraft().thePlayer != null) Minecraft.getMinecraft().thePlayer
                .addChatMessage(com.gtnhplanner.client.OpenPlanCommand.line(text, key));
        }

        @Override
        public void notice(final String text) {
            final BoardSession board = BoardSession.current();
            if (board != null) board.flash(Severity.INFO, text);
            else if (Minecraft.getMinecraft().thePlayer != null)
                Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText(text));
        }
    }
}
