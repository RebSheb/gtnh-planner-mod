package com.gtnhplanner.dev;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundCategory;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.input.Mouse;

import com.cleanroommc.modularui.api.IMuiScreen;
import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.screen.viewport.LocatedWidget;
import com.cleanroommc.modularui.widget.sizer.Area;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.ui.gt.MultiblockPictures;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Dev-only automation so the client can be driven from a shell: auto-loads a creative test world and serves a
 * localhost HTTP endpoint for screenshots, synthetic input and widget dumps. Only active in a deobfuscated dev
 * environment (override with {@code -Dgtnhplanner.dev=true|false}). See {@code docs/dev-harness.md}.
 */
public final class DevHarness {

    private static final int PORT = Integer.getInteger("gtnhplanner.dev.port", 25599);
    private static final String WORLD = System.getProperty("gtnhplanner.dev.world", "plannh-dev");
    private static final long REQUEST_TIMEOUT_SECONDS = 30;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .disableHtmlEscaping()
        .create();

    private final Minecraft mc = Minecraft.getMinecraft();
    /** Input steps, one per client tick, so the game sees each as a separate event batch. */
    private final Queue<Runnable> tickActions = new ConcurrentLinkedQueue<>();
    /** Work that needs a fully drawn frame, run at the end of the render tick. */
    private final Queue<Runnable> frameActions = new ConcurrentLinkedQueue<>();
    private boolean worldRequested;
    private volatile boolean ready;

    private DevHarness() {}

    public static void initIfDev() {
        if (!isEnabled()) return;
        final DevHarness harness = new DevHarness();
        FMLCommonHandler.instance()
            .bus()
            .register(harness);
        MinecraftForge.EVENT_BUS.register(harness);
        // First in line, so it sees every click NEI is handed on a planner screen.
        codechicken.nei.guihook.GuiContainerManager.inputHandlers.addFirst(DevNeiInput.INSTANCE);
        FMLCommonHandler.instance()
            .bus()
            .register(DevRecorder.INSTANCE);
        FMLCommonHandler.instance()
            .bus()
            .register(DevReplay.INSTANCE);
        if (cpw.mods.fml.common.Loader.isModLoaded("gregtech")) FMLCommonHandler.instance()
            .bus()
            .register(DevWorld.INSTANCE);
        harness.startServer();
    }

    /** A dev run: the deobfuscated workspace, or -Dgtnhplanner.dev=true. */
    public static boolean isEnabled() {
        final String prop = System.getProperty("gtnhplanner.dev");
        if (prop != null) return Boolean.parseBoolean(prop);
        return Boolean.TRUE.equals(Launch.blackboard.get("fml.deobfuscatedEnvironment"));
    }

    // region Game hooks

    @SubscribeEvent
    public void onGuiOpen(final GuiOpenEvent event) {
        if (event.gui instanceof final cpw.mods.fml.client.GuiConfirmation question && worldRequested && !ready) {
            // FML asking whether to open the test world without blocks and items it was saved with (a GregTech run's
            // world opened by a plain run, or after a mod update): Yes, as the button does. It backs the world up
            // first, and plans are kept outside the world. Left to wait, it blocks the start until mc.sh gives up.
            final cpw.mods.fml.common.StartupQuery query = cpw.mods.fml.relauncher.ReflectionHelper
                .getPrivateValue(cpw.mods.fml.client.GuiNotification.class, question, "query");
            GtnhPlanner.LOG.info(
                "[dev] Answering yes to: {}",
                query.getText()
                    .split("\n", 2)[0]);
            event.gui = null;
            query.setResult(true);
            query.finish();
            return;
        }
        if (!(event.gui instanceof GuiMainMenu) || worldRequested) return;
        worldRequested = true;
        // The harness drives an unfocused window; a pause menu would steal every screen.
        mc.gameSettings.pauseOnLostFocus = false;
        if (WORLD.isEmpty()) {
            ready = true;
            return;
        }
        tickActions.add(this::loadWorld);
    }

    @SubscribeEvent
    public void onClientTick(final TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        watchTheUser();
        if (!ready && mc.theWorld != null && mc.thePlayer != null) ready = true;
        // Input is read per tick but hover is worked out per frame: after a stall the game runs several ticks in one
        // frame, and a press queued right after a move would land on what was under the mouse before it. So at most one
        // step per drawn frame.
        if (!framedSinceStep) return;
        final Runnable action = tickActions.poll();
        if (action == null) return;
        framedSinceStep = false;
        action.run();
    }

    private volatile boolean framedSinceStep = true;

    // When the person at the keyboard last did something: the mouse moved, a button or a key went down. Input the
    // harness sends is not theirs, so anything within a second of it is left out.
    private volatile long lastUserMs = System.currentTimeMillis();
    private volatile long lastSyntheticMs;
    private int lastMouseX = -1, lastMouseY = -1;
    private boolean lastAnyDown;

    private void watchTheUser() {
        if (!org.lwjgl.opengl.Display.isActive()) return;
        final int x = org.lwjgl.input.Mouse.getX(), y = org.lwjgl.input.Mouse.getY();
        boolean down = org.lwjgl.input.Mouse.isButtonDown(0) || org.lwjgl.input.Mouse.isButtonDown(1)
            || org.lwjgl.input.Mouse.isButtonDown(2);
        for (int k = 1; k < 256 && !down; k++) down = org.lwjgl.input.Keyboard.isKeyDown(k);
        final boolean changed = x != lastMouseX || y != lastMouseY || down && !lastAnyDown;
        lastMouseX = x;
        lastMouseY = y;
        lastAnyDown = down;
        final long now = System.currentTimeMillis();
        if (changed && now - lastSyntheticMs > 1000) lastUserMs = now;
    }

    @SubscribeEvent
    public void onRenderTick(final TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            DevPerf.frame();
            DevStress.frame();
        }
        if (event.phase != TickEvent.Phase.END) return;
        framedSinceStep = true;
        // Only what was queued before this frame; actions may re-queue themselves for the next one.
        for (int i = frameActions.size(); i > 0; i--) {
            final Runnable action = frameActions.poll();
            if (action != null) action.run();
        }
    }

    private void loadWorld() {
        WorldSettings settings = null;
        if (mc.getSaveLoader()
            .getWorldInfo(WORLD) == null) {
            // A random seed, as a new world gets: with seed 0 the full GTNH pack fails to start the world (a ruin on
            // Ross128b fills a chest with an enchanted book it cannot enchant).
            settings = new WorldSettings(
                new java.util.Random().nextLong(),
                WorldSettings.GameType.CREATIVE,
                false,
                false,
                WorldType.FLAT);
            settings.enableCommands();
        }
        GtnhPlanner.LOG.info("[dev] Loading test world '{}'", WORLD);
        mc.displayGuiScreen(null);
        mc.launchIntegratedServer(WORLD, WORLD, settings);
    }

    // endregion

    // region HTTP server

    private void startServer() {
        try {
            // Several games at once (agents' dev games, the full pack): when the port is taken and none was asked
            // for, the next free one of ten, said in the log and in plannh-dev.port.
            HttpServer bound = null;
            int port = PORT;
            final boolean asked = System.getProperty("gtnhplanner.dev.port") != null;
            for (int p = PORT; p < PORT + (asked ? 1 : 10) && bound == null; p++) {
                try {
                    bound = HttpServer.create(new InetSocketAddress("127.0.0.1", p), 0);
                    port = p;
                } catch (final java.net.BindException taken) {
                    if (asked || p == PORT + 9) throw taken;
                }
            }
            final HttpServer server = bound;
            Files.writeString(new File(mc.mcDataDir, "plannh-dev.port").toPath(), String.valueOf(port));
            server.setExecutor(Executors.newSingleThreadExecutor(r -> {
                final Thread t = new Thread(r, "GTNH Planner-DevHarness");
                t.setDaemon(true);
                return t;
            }));
            server.createContext("/", this::handle);
            server.start();
            GtnhPlanner.LOG.info("[dev] Harness listening on http://127.0.0.1:{}/", port);
            // Killing the gradle run task does not kill the game; tools/dev/mc.sh uses this as a fallback.
            final File pidFile = new File(mc.mcDataDir, "plannh-dev.pid");
            // "pid@host". Not ProcessHandle: below Java 25 the jar runs downgraded, where it asks WMIC, which
            // Windows 11 no longer has.
            final String name = java.lang.management.ManagementFactory.getRuntimeMXBean()
                .getName();
            Files.writeString(pidFile.toPath(), name.substring(0, Math.max(0, name.indexOf('@'))));
            pidFile.deleteOnExit();
        } catch (final IOException e) {
            GtnhPlanner.LOG.error("[dev] Harness failed to start on port {}", PORT, e);
        }
    }

    private void handle(final HttpExchange exchange) throws IOException {
        int code = 200;
        Object body;
        try {
            final Map<String, String> q = query(exchange);
            body = route(
                exchange.getRequestURI()
                    .getPath(),
                q);
        } catch (final IllegalArgumentException e) {
            code = 400;
            body = error(e.getMessage());
        } catch (final Exception e) {
            code = 500;
            body = error(e.toString());
            GtnhPlanner.LOG.warn("[dev] Request failed", e);
        }
        final byte[] bytes = GSON.toJson(body)
            .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders()
            .set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private Object route(final String path, final Map<String, String> q) throws Exception {
        switch (path) {
            case "/":
            case "/help":
                return help();
            case "/status":
                return onClient(this::status);
            case "/open":
                requireWorld();
                return onClient(() -> {
                    openFlowchart();
                    return ok();
                });
            case "/close":
                return onClient(() -> {
                    mc.displayGuiScreen(null);
                    return ok();
                });
            case "/screenshot":
                return screenshot(q.getOrDefault("name", "harness-" + System.currentTimeMillis() + ".png"), q);
            case "/widgets":
                return onClient(this::widgets);
            case "/move":
                return input(List.of(() -> moveTo(intArg(q, "x"), intArg(q, "y"))));
            case "/click":
                return click(q);
            case "/drag":
                return drag(q);
            case "/release":
                return input(List.of(() -> SyntheticInput.button(intArg(q, "button", 0), false)));
            case "/scroll":
                return scroll(q);
            case "/key":
                return key(q);
            case "/type":
                return type(q);
            case "/cmd":
                requireWorld();
                return onClient(() -> {
                    final String cmd = arg(q, "c");
                    final int handled = ClientCommandHandler.instance.executeCommand(mc.thePlayer, cmd);
                    if (handled == 0) mc.thePlayer.sendChatMessage(cmd);
                    return ok();
                });
            case "/addrecipe":
                requireWorld();
                return onClient(
                    () -> DevRecipes.addRecipe(
                        arg(q, "output"),
                        q.getOrDefault("handler", ""),
                        q.getOrDefault("input", ""),
                        intArg(q, "x", 200),
                        intArg(q, "y", 200)));
            case "/frame":
                return onClient(() -> {
                    final ModularScreen mui = muiScreen();
                    final Map<String, Object> r = new LinkedHashMap<>();
                    if (mui == null) return error("no ModularUI screen");
                    try {
                        mui.onFrameUpdate();
                    } catch (final Throwable t) {
                        GtnhPlanner.LOG.warn("[dev] frame update failed", t);
                        r.put("error", t.toString());
                    }
                    final IWidget hovered = mui.getContext()
                        .getHovered();
                    r.put("hovered", hovered == null ? null : describe(hovered));
                    final List<String> below = new ArrayList<>();
                    for (final IWidget w : mui.getContext()
                        .getAllBelowMouse()) below.add(describe(w));
                    r.put("belowMouse", below);
                    final List<String> panelList = new ArrayList<>();
                    for (final ModularPanel p : mui.getPanelManager()
                        .getOpenPanels()) {
                        for (final com.cleanroommc.modularui.screen.viewport.LocatedWidget lw : p
                            .getAllHoveringList(false)) {
                            panelList.add(p.getName() + ": " + describe((IWidget) lw.getElement()));
                        }
                    }
                    r.put("panelHovering", panelList);
                    return r;
                });
            case "/board":
                return onClient(DevBoard::board);
            case "/totals":
                // The open plan's overview as numbers, to compare a plan's solve with the website's: inputs and outputs
                // a second, EU/t used and made, a line per card (machines, EU/t), and whether it is still solving.
                return onClient(() -> {
                    final com.gtnhplanner.ui.BoardScreen screen = DevBoard.screen();
                    if (screen == null) return error("the planner is not open");
                    final com.gtnhplanner.ui.BoardSession s = screen.session();
                    final com.gtnhplanner.ui.BoardSession.Totals t = s.totals();
                    final Map<String, Object> r = new LinkedHashMap<>();
                    r.put(
                        "plan",
                        s.graph()
                            .getName());
                    r.put("solving", s.solving());
                    r.put("cards", s.graph().nodes.size());
                    r.put("euPerTick", t.euPerTick());
                    r.put("euMade", t.euMade());
                    final java.util.function.Function<List<com.gtnhplanner.ui.BoardSession.TotalLine>, List<Object>> lines = ls -> {
                        final List<Object> out = new ArrayList<>();
                        for (final com.gtnhplanner.ui.BoardSession.TotalLine l : ls)
                            out.add(Map.of("key", l.key(), "name", l.label(), "perSecond", l.amount()));
                        return out;
                    };
                    r.put("inputs", lines.apply(t.inputs()));
                    r.put("outputs", lines.apply(t.outputs()));
                    final List<Object> machines = new ArrayList<>();
                    for (final com.gtnhplanner.ui.BoardSession.MachineLine m : t.machines()) machines.add(
                        Map.of(
                            "node",
                            String.valueOf(m.nodeId()),
                            "name",
                            m.name(),
                            "machines",
                            m.machines(),
                            "euPerTick",
                            m.euPerTick(),
                            "tier",
                            String.valueOf(m.tier())));
                    r.put("machines", machines);
                    return r;
                });
            case "/tasks":
                // The task board: whether the open plan is one, each column's cards with their GUI rects, and the task
                // box open; open=<task id> opens a task's box, scroll=<y> scrolls its page. For setting up a board to
                // look at: newboard=<name> makes a task board and opens it; add=<title>[&status=doing] makes a task;
                // task=<id> (or the task just added) with comment=<text>&by=<id>&name=<name>[&ago=<minutes>] writes a
                // comment as someone else, assign=<id> puts someone on it, block=<id> or unblock=<id> sets what it
                // waits on. view=board|chain switches the board's view; select=<id> selects a task in the chain view.
                return onClient(() -> {
                    final com.gtnhplanner.ui.BoardScreen screen = DevBoard.screen();
                    if (screen == null) return error("the planner is not open");
                    final com.gtnhplanner.ui.BoardSession session = screen.session();
                    // demo=1: a team's board a few weeks into a build, made from the open plan (dev/DevTaskBoard).
                    if (q.containsKey("demo")) DevTaskBoard.fill(session);
                    if (q.containsKey("newboard")) session.addTaskBoard(true)
                        .setName(q.get("newboard"));
                    final com.gtnhplanner.data.flowchart.Graph g = session.graph();
                    String made = null;
                    if (q.containsKey("add")) {
                        final String id = java.util.UUID.randomUUID()
                            .toString();
                        final com.gtnhplanner.data.flowchart.Tasks.Status status = com.gtnhplanner.data.flowchart.Tasks.Status
                            .of(q.getOrDefault("status", "todo"));
                        session.editTasks(
                            () -> com.gtnhplanner.data.flowchart.TaskEdits.add(
                                g,
                                id,
                                q.get("add"),
                                status == null ? com.gtnhplanner.data.flowchart.Tasks.Status.TODO : status,
                                List.of(),
                                com.gtnhplanner.ui.task.TaskLook.me(),
                                com.gtnhplanner.ui.task.TaskLook.now()));
                        made = id;
                    }
                    final String task = q.containsKey("task") ? q.get("task") : made;
                    if (task != null && q.containsKey("comment")) {
                        final long ago = Long.parseLong(q.getOrDefault("ago", "0"));
                        final String at = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                            .withZone(java.time.ZoneOffset.UTC)
                            .format(
                                java.time.Instant.now()
                                    .minusSeconds(ago * 60));
                        session.editTasks(
                            () -> com.gtnhplanner.data.flowchart.TaskEdits.addComment(
                                g,
                                java.util.UUID.randomUUID()
                                    .toString(),
                                task,
                                q.getOrDefault("by", com.gtnhplanner.ui.task.TaskLook.me()),
                                q.getOrDefault("name", com.gtnhplanner.ui.task.TaskLook.myName()),
                                at,
                                q.get("comment")));
                    }
                    if (task != null && q.containsKey("assign")) {
                        final com.gtnhplanner.data.flowchart.Tasks.Task t = com.gtnhplanner.data.flowchart.TaskEdits
                            .task(g, task);
                        if (t != null && !t.assignees()
                            .contains(q.get("assign"))) {
                            final List<String> next = new ArrayList<>(t.assignees());
                            next.add(q.get("assign"));
                            session.editTasks(
                                () -> com.gtnhplanner.data.flowchart.TaskEdits
                                    .put(g, com.gtnhplanner.data.flowchart.TaskEdits.withAssignees(t, next)));
                        }
                    }
                    if (task != null && (q.containsKey("priority") || q.containsKey("age"))) {
                        // priority=high|medium|low and age=<days in its column> (movedAt put back that far).
                        final com.gtnhplanner.data.flowchart.Tasks.Task t = com.gtnhplanner.data.flowchart.TaskEdits
                            .task(g, task);
                        if (t != null) {
                            final String since = q.containsKey("age")
                                ? java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                                    .withZone(java.time.ZoneOffset.UTC)
                                    .format(
                                        java.time.Instant.now()
                                            .minusSeconds((long) (Double.parseDouble(q.get("age")) * 86400)))
                                : t.movedAt();
                            session.editTasks(
                                () -> com.gtnhplanner.data.flowchart.TaskEdits.put(
                                    g,
                                    new com.gtnhplanner.data.flowchart.Tasks.Task(
                                        t.id(),
                                        t.title(),
                                        t.text(),
                                        t.status(),
                                        t.order(),
                                        t.assignees(),
                                        t.links(),
                                        t.createdBy(),
                                        t.createdAt(),
                                        t.doneAt(),
                                        q.containsKey("priority") ? q.get("priority") : t.priority(),
                                        since,
                                        t.blockedBy())));
                        }
                    }
                    if (task != null && q.containsKey("block")) {
                        // block=<id>: the task waits on that one (refused, as the box refuses it, if it would loop);
                        // unblock=<id> takes it off.
                        session.editTasks(
                            () -> com.gtnhplanner.data.flowchart.TaskEdits.addBlocker(g, task, q.get("block")));
                    }
                    if (task != null && q.containsKey("unblock")) session.editTasks(
                        () -> com.gtnhplanner.data.flowchart.TaskEdits.removeBlocker(g, task, q.get("unblock")));
                    final com.gtnhplanner.ui.task.TaskBoardView boardView = (com.gtnhplanner.ui.task.TaskBoardView) screen
                        .key("taskBoard");
                    if (q.containsKey("view")) boardView.showView(q.get("view"));
                    if (q.containsKey("select")) boardView.selectInChain(q.get("select"));
                    if (q.containsKey("open")) com.gtnhplanner.ui.task.TaskPanel.open(session, q.get("open"));
                    if (q.containsKey("scroll")) com.gtnhplanner.ui.task.TaskPanel.scrollTo(intArg(q, "scroll"));
                    final Map<String, Object> m = new LinkedHashMap<>();
                    m.put(
                        "view",
                        screen.session()
                            .showsTasks() ? "tasks" : "plan");
                    if (made != null) m.put("made", made);
                    m.put("board", ((com.gtnhplanner.ui.task.TaskBoardView) screen.key("taskBoard")).describe());
                    m.put("panel", com.gtnhplanner.ui.task.TaskPanel.describe());
                    return m;
                });
            case "/pin":
                // Pins card N (the board report's order) at a count, or unpins it with no count or 0: for setting up a
                // plan to look at without clicking every card.
                return onClient(() -> {
                    DevBoard.pin(Integer.parseInt(q.get("card")), Double.parseDouble(q.getOrDefault("count", "0")));
                    return ok();
                });
            case "/view":
                return onClient(() -> {
                    DevBoard.view(
                        Float.parseFloat(q.getOrDefault("zoom", "1")),
                        Float.parseFloat(q.getOrDefault("panX", "0")),
                        Float.parseFloat(q.getOrDefault("panY", "0")));
                    return ok();
                });
            case "/film":
                // Filming: the tour's cursor builds a polyethylene line for the trailer (ui/tutorial/Film); stopped and
                // followed as the tour is (tutorial?stop=1, tutorial).
                requireWorld();
                return onClient(() -> {
                    com.gtnhplanner.ui.tutorial.Tutorial.startFilm();
                    return Map.of("where", com.gtnhplanner.ui.tutorial.Tutorial.where());
                });
            case "/rebuild":
                // Filming: a finished plan (file=<plan JSON, code or link>, from the repo root) built again by the
                // tour's cursor (ui/tutorial/Rebuild); seed= picks the take; nei=0 adds each card straight from its
                // port,
                // no NEI page (for time-lapses). Followed and stopped as the tour is.
                requireWorld(); {
                java.io.File f = new java.io.File(q.get("file"));
                if (!f.isAbsolute()) f = new java.io.File(new java.io.File(mc.mcDataDir, "../.."), q.get("file"));
                final String text = java.nio.file.Files.readString(f.toPath());
                final long seed = Long.parseLong(q.getOrDefault("seed", "1"));
                return onClient(() -> {
                    final com.gtnhplanner.importer.FfConverter.Result result = com.gtnhplanner.importer.game.FactoryFlowImport
                        .importFromText(text);
                    com.gtnhplanner.ui.tutorial.Tutorial.startRebuild(result.graph(), seed, !"0".equals(q.get("nei")));
                    return Map.of(
                        "plan",
                        result.graph()
                            .getName(),
                        "cards",
                        result.graph()
                            .getNodes()
                            .size(),
                        "drawers",
                        result.graph()
                            .getDrawers()
                            .size(),
                        "where",
                        com.gtnhplanner.ui.tutorial.Tutorial.where());
                });
            }
            case "/replay":
                // Filming: rebuild the open plan a card at a time (DevReplay). replay?ms=400[&order=left][&frame=recent
                // &recent=6]; replay?stop=1; replay alone says how far it is.
                if (q.containsKey("stop")) return onClient(DevReplay.INSTANCE::stop);
                if (!q.containsKey("ms")) return onClient(DevReplay.INSTANCE::status);
                return onClient(
                    () -> DevReplay.INSTANCE.start(
                        Long.parseLong(q.get("ms")),
                        !"left".equals(q.get("order")),
                        !"recent".equals(q.get("frame")),
                        Integer.parseInt(q.getOrDefault("recent", "6"))));
            case "/planpicture": {
                // The Share key's Screenshot..., straight to a PNG: planpicture[?detail=simple][&name=1][&flows=1].
                final java.util.concurrent.CompletableFuture<Object> done = new java.util.concurrent.CompletableFuture<>();
                onClient(() -> {
                    final com.gtnhplanner.ui.BoardScreen b = DevBoard.screen();
                    if (b == null) {
                        done.complete(error("open a plan on the board first"));
                        return ok();
                    }
                    final com.gtnhplanner.ui.canvas.PlanPicture.Options o = new com.gtnhplanner.ui.canvas.PlanPicture.Options(
                        "simple".equals(q.get("detail")) ? com.gtnhplanner.ui.canvas.PlanPicture.Detail.SIMPLE
                            : com.gtnhplanner.ui.canvas.PlanPicture.Detail.DETAILED,
                        "1".equals(q.get("name")),
                        "1".equals(q.get("flows")));
                    com.gtnhplanner.ui.canvas.PlanPicture.take(
                        b.canvas(),
                        o,
                        image -> com.gtnhplanner.ui.canvas.PlanPicture.save(
                            image,
                            b.session()
                                .graph()
                                .getName(),
                            file -> done.complete(
                                Map.of(
                                    "path",
                                    file.getAbsolutePath(),
                                    "width",
                                    image.getWidth(),
                                    "height",
                                    image.getHeight())),
                            why -> done.complete(error(why))),
                        why -> done.complete(error(why)));
                    return ok();
                });
                return done.get(60, java.util.concurrent.TimeUnit.SECONDS);
            }
            case "/minimap":
                // Filming: minimap?centre=1 draws the minimap in the middle of the screen, centre=0 back in its corner;
                // on=, size= (the settings' size step), zoom= (its zoom step) and circle= set the gear's minimap rows.
                return onClient(() -> {
                    if (q.containsKey("centre"))
                        com.gtnhplanner.ui.world.Minimap.devCentred = !"0".equals(q.get("centre"));
                    if (q.containsKey("on")) com.gtnhplanner.ui.PlannerSettings.setMinimap(!"0".equals(q.get("on")));
                    if (q.containsKey("size"))
                        com.gtnhplanner.ui.PlannerSettings.setMinimapSizeIndex(Integer.parseInt(q.get("size")));
                    if (q.containsKey("zoom"))
                        com.gtnhplanner.ui.PlannerSettings.setMinimapZoomIndex(Integer.parseInt(q.get("zoom")));
                    if (q.containsKey("circle"))
                        com.gtnhplanner.ui.PlannerSettings.setMinimapCircle("1".equals(q.get("circle")));
                    return Map.of(
                        "centre",
                        com.gtnhplanner.ui.world.Minimap.devCentred,
                        "on",
                        com.gtnhplanner.ui.PlannerSettings.minimap(),
                        "size",
                        com.gtnhplanner.ui.PlannerSettings.minimapSize());
                });
            case "/newworld": {
                // Filming somewhere real: leaves this world for a creative one of normal terrain (made when new, with
                // seed= if given): newworld?name=plannh-trailer&seed=123. Plans are per world.
                final String name = arg(q, "name");
                final long seed = q.containsKey("seed") ? Long.parseLong(q.get("seed"))
                    : new java.util.Random().nextLong();
                return onClient(() -> {
                    // Two calls: the first leaves this world; starting the next before the last server has stopped
                    // trips mods' world data (AE2's), so the second, made out of the world, starts it.
                    if (mc.theWorld != null) {
                        mc.theWorld.sendQuittingDisconnectingPacket();
                        mc.loadWorld(null);
                        mc.displayGuiScreen(new net.minecraft.client.gui.GuiMainMenu());
                        return Map.of("left", true, "next", "call newworld again in a few seconds");
                    }
                    WorldSettings settings = null;
                    if (mc.getSaveLoader()
                        .getWorldInfo(name) == null) {
                        settings = new WorldSettings(
                            seed,
                            WorldSettings.GameType.CREATIVE,
                            true,
                            false,
                            WorldType.DEFAULT);
                        settings.enableCommands();
                    }
                    mc.displayGuiScreen(null);
                    mc.launchIntegratedServer(name, name, settings);
                    return Map.of("world", name, "seed", seed);
                });
            }
            case "/seethrough":
                // The gear's See-through (0 solid to 100), for recording: seethrough?percent=0; none: only reads it.
                return onClient(() -> {
                    if (q.containsKey("percent"))
                        com.gtnhplanner.ui.PlannerSettings.setSeeThrough(Integer.parseInt(q.get("percent")));
                    return Map.of("percent", com.gtnhplanner.ui.PlannerSettings.seeThrough());
                });
            case "/sound":
                // The master volume, 0 to 1 (mc.sh starts the game at PLANNH_DEV_SOUND); no volume: just report it.
                return onClient(() -> {
                    if (q.containsKey("volume")) {
                        final float v = Math.max(0f, Math.min(1f, Float.parseFloat(q.get("volume"))));
                        mc.gameSettings.setSoundLevel(SoundCategory.MASTER, v);
                        mc.gameSettings.saveOptions();
                    }
                    final Map<String, Object> r = new LinkedHashMap<>();
                    r.put("volume", mc.gameSettings.getSoundLevel(SoundCategory.MASTER));
                    return r;
                });
            case "/window":
                // The window's size in screen pixels (w, h), for a launcher that opens it small and ignores
                // --width/--height (the full pack's Prism instance), and the GUI scale setting (scale, 0 Auto; the
                // open screen is laid out again at it, not saved to options.txt); neither: just report them.
                return onClient(() -> {
                    if (q.containsKey("w") && q.containsKey("h")) {
                        try {
                            org.lwjgl.opengl.Display
                                .setDisplayMode(new org.lwjgl.opengl.DisplayMode(intArg(q, "w"), intArg(q, "h")));
                        } catch (final org.lwjgl.LWJGLException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    if (q.containsKey("scale")) {
                        mc.gameSettings.guiScale = intArg(q, "scale");
                        if (mc.currentScreen != null) {
                            final ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
                            mc.currentScreen.setWorldAndResolution(mc, sr.getScaledWidth(), sr.getScaledHeight());
                        }
                    }
                    // The planner's Size (the gear's: 0 Tiny to 3 Large), laid out again at once.
                    if (q.containsKey("size")) com.gtnhplanner.ui.popup.SettingsPanel.setSize(intArg(q, "size"));
                    final ScaledResolution sr = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
                    final Map<String, Object> r = new LinkedHashMap<>();
                    r.put("width", mc.displayWidth);
                    r.put("height", mc.displayHeight);
                    r.put("guiScale", mc.gameSettings.guiScale);
                    r.put("guiWidth", sr.getScaledWidth());
                    r.put("guiHeight", sr.getScaledHeight());
                    r.put("plannerSize", com.gtnhplanner.ui.PlannerSettings.size());
                    r.put("plannerScale", com.gtnhplanner.ui.PlannerScale.board());
                    return r;
                });
            case "/sfx":
                // The planner's sounds played lately, newest first (the dev game is muted, so this is how a test
                // hears them); play=<name or enum> plays one, at pitch=.
                return onClient(() -> {
                    if (q.containsKey("play")) {
                        final String want = q.get("play");
                        for (final com.gtnhplanner.ui.sound.Sfx s : com.gtnhplanner.ui.sound.Sfx.values()) if (s.name()
                            .equalsIgnoreCase(want)
                            || s.id()
                                .equals(want))
                            s.play(Float.parseFloat(q.getOrDefault("pitch", "1")));
                    }
                    final Map<String, Object> r = new LinkedHashMap<>();
                    r.put("recent", com.gtnhplanner.ui.sound.Sfx.recent());
                    return r;
                });
            case "/chat":
                // The chat's newest lines (lines=N, default 5) with what clicking each part runs; share=1 first
                // shares the open plan there, as the Share menu's "Share in chat" does (client/ChatShare).
                requireWorld();
                return onClient(() -> {
                    final Map<String, Object> r = new LinkedHashMap<>();
                    if ("1".equals(q.get("share"))) {
                        final String problem = com.gtnhplanner.client.ChatShare
                            .send(com.gtnhplanner.api.PlanAPI.getActiveGraph());
                        r.put("shared", problem == null ? "ok" : problem);
                    }
                    r.put("lines", chatLines(Integer.parseInt(q.getOrDefault("lines", "5"))));
                    return r;
                });
            case "/library":
                // The site the library reads and posts to: url=<base> (tools/dev/mock-library.mjs for a local
                // stand-in).
                if (q.containsKey("url")) com.gtnhplanner.library.CommunityApi.useSite(q.get("url"));
                // live=<ws url> points the live service elsewhere; live= (empty) goes back to the site's.
                if (q.containsKey("live")) com.gtnhplanner.cloud.net.CloudApi.useLive(q.get("live"));
                return onClient(() -> {
                    final Map<String, Object> r = new LinkedHashMap<>();
                    r.put("site", com.gtnhplanner.library.CommunityApi.site());
                    r.put("live", com.gtnhplanner.cloud.net.CloudApi.live());
                    r.put("signedInAs", com.gtnhplanner.library.Account.username());
                    return r;
                });
            case "/cloud":
                // The account's cloud plans on this device: where they stand, and each plan's copy and room.
                // fake=N puts N stand-in people on the open plan (moving cursors, selections; dev/DevPeople), fake=0
                // takes them off.
                return onClient(() -> {
                    final Map<String, Object> r = new LinkedHashMap<>();
                    if (q.containsKey("fake")) DevPeople.set(intArg(q, "fake"));
                    // scene=1: four stand-ins at work on the plan, for footage (dev/DevScene); scene=0 stops them.
                    if (q.containsKey("scene")) {
                        if (intArg(q, "scene") > 0) DevScene.start();
                        else DevScene.stop();
                    }
                    final com.gtnhplanner.ui.BoardSession board = com.gtnhplanner.ui.BoardSession.current();
                    if (board != null) {
                        final List<String> here = new ArrayList<>();
                        for (final com.gtnhplanner.ui.live.Peers.Peer who : board.peers()
                            .all())
                            here.add(
                                who.username() + " ("
                                    + who.client()
                                    + (who.hasCursor() ? ", at " + Math.round(who.x()) + "," + Math.round(who.y()) : "")
                                    + ", "
                                    + who.selected()
                                        .size()
                                    + " selected)");
                        r.put("peers", here);
                    }
                    r.put("fake", DevPeople.count());
                    final com.gtnhplanner.cloud.session.CloudSession s = com.gtnhplanner.cloud.session.CloudSession
                        .current();
                    final com.gtnhplanner.data.flowchart.Plan p = com.gtnhplanner.data.flowchart.Plan.player();
                    r.put("signedInAs", com.gtnhplanner.library.Account.username());
                    r.put("account", com.gtnhplanner.library.Account.accountId());
                    r.put("cloudPlans", p != null && p.isCloud());
                    if (s == null) return r;
                    r.put("status", s.status());
                    r.put("editsWaiting", s.editsWaiting());
                    final List<Map<String, Object>> plans = new ArrayList<>();
                    if (p != null) for (final com.gtnhplanner.data.flowchart.Graph g : p.getGraphs()) {
                        final Map<String, Object> m = new LinkedHashMap<>();
                        m.put("name", g.getName());
                        m.put("key", g.getCloudKey());
                        m.put("tab", g.isOpen());
                        m.put("cards", g.nodes.size());
                        final com.gtnhplanner.cloud.session.LiveDoc d = s.doc(g);
                        if (d != null) {
                            m.put("seq", d.seq());
                            m.put("waiting", d.waiting());
                            m.put("making", d.creating());
                            m.put("known", d.known());
                            m.put("inRoom", d.inRoom());
                            m.put("role", d.role());
                            m.put("sharedBy", s.sharedBy(g));
                            final List<String> present = new ArrayList<>();
                            for (final com.gtnhplanner.cloud.net.LiveClient.Person who : d.present())
                                present.add(who.username() + " (" + who.client() + ")");
                            m.put("present", present);
                            m.put("canUndo", d.canUndo());
                        }
                        plans.add(m);
                    }
                    r.put("plans", plans);
                    return r;
                });
            case "/nei":
                // NEI's recipes for item=<modid:name[:meta] or ore name>; uses=1 for its uses; planner=0 from the
                // inventory (the planner closed); tab=<part of a tab's name> to open on that tab.
                requireWorld();
                return onClient(
                    () -> DevRecipes.openNei(
                        arg(q, "item"),
                        "1".equals(q.get("uses")),
                        !"0".equals(q.get("planner")),
                        q.getOrDefault("tab", "")));
            case "/website":
                // The website's "Open in the mod" (client/WebsiteInbox): the port it listens on and the plan waiting;
                // answer=open or answer=drop answers it as the prompt would. link=1 opens the open plan's website link
                // in the browser, as the Share menu's "Open in website" does; code=1 returns that link instead.
                return onClient(() -> {
                    final Map<String, Object> r = new LinkedHashMap<>();
                    if ("open".equals(q.get("answer")))
                        r.put("opened", String.valueOf(com.gtnhplanner.client.WebsiteInbox.open(null)));
                    if ("drop".equals(q.get("answer"))) com.gtnhplanner.client.WebsiteInbox.drop(null);
                    final com.gtnhplanner.ui.BoardScreen board = DevBoard.screen();
                    if (board != null && "1".equals(q.get("link"))) board.session()
                        .openInWebsite();
                    if (board != null && "1".equals(q.get("code"))) {
                        board.session()
                            .copyPlanLink();
                        r.put("link", net.minecraft.client.gui.GuiScreen.getClipboardString());
                    }
                    r.put("port", com.gtnhplanner.client.WebsiteInbox.port());
                    final com.gtnhplanner.client.WebsiteInbox.Pending p = com.gtnhplanner.client.WebsiteInbox.pending();
                    r.put(
                        "pending",
                        p == null ? null
                            : p.arrival()
                                .name());
                    return r;
                });
            case "/update":
                // The update offer: latest=<tag>[&url=] fakes the newest release and offers it again on the next open.
                return onClient(() -> {
                    if (q.containsKey("latest")) com.gtnhplanner.client.UpdateCheck.fake(
                        new com.gtnhplanner.client.UpdateCheck.Release(
                            q.get("latest"),
                            q.getOrDefault(
                                "url",
                                "https://github.com/jackwrichards/gtnh-planner-mod/releases/tag/" + q.get("latest"))));
                    if ("1".equals(q.get("clear"))) com.gtnhplanner.client.UpdateCheck.fake(null);
                    if ("1".equals(q.get("reset"))) com.gtnhplanner.ui.PlannerSettings.setUpdateOffered("");
                    if (!"1".equals(q.get("clear"))) com.gtnhplanner.ui.popup.UpdateOffer.again();
                    final Map<String, Object> m = new LinkedHashMap<>();
                    final com.gtnhplanner.client.UpdateCheck.Release r = com.gtnhplanner.client.UpdateCheck.newer();
                    m.put("current", com.gtnhplanner.client.UpdateCheck.current());
                    m.put("newer", r == null ? null : r.tag() + " " + r.url());
                    m.put("offered", com.gtnhplanner.ui.PlannerSettings.updateOffered());
                    return m;
                });
            case "/tutorial":
                requireWorld();
                return onClient(() -> {
                    // quiet=1: filming, no notes or spotlight drawn (quiet=0 back).
                    if (q.containsKey("quiet")) com.gtnhplanner.ui.tutorial.Tutorial.quiet = "1".equals(q.get("quiet"));
                    if ("1".equals(q.get("stop"))) com.gtnhplanner.ui.tutorial.Tutorial.stop();
                    else if ("1".equals(q.get("next"))) com.gtnhplanner.ui.tutorial.Tutorial.nextBeat();
                    else if (q.containsKey("step"))
                        com.gtnhplanner.ui.tutorial.Tutorial.startAt(Integer.parseInt(q.get("step")) - 1);
                    else if ("1".equals(q.get("start"))) com.gtnhplanner.ui.tutorial.Tutorial.start();
                    final Map<String, Object> r = new LinkedHashMap<>();
                    if (q.containsKey("neitype")) {
                        final codechicken.nei.SearchField f = codechicken.nei.LayoutManager.searchField;
                        final List<Object> got = new ArrayList<>();
                        f.setFocus(true);
                        got.add("focused " + f.focused());
                        for (final char c : q.get("neitype")
                            .toCharArray())
                            got.add(
                                f.handleKeyPress(
                                    org.lwjgl.input.Keyboard.getKeyIndex(String.valueOf(Character.toUpperCase(c))),
                                    c) + " -> [" + f.text() + "]");
                        r.put("neitype", got);
                    }
                    if ("1".equals(q.get("export"))) {
                        try {
                            final java.io.File f = com.gtnhplanner.ui.tutorial.Tutorial.exportStarts();
                            r.put("exported", f == null ? null : f.getPath());
                        } catch (final java.io.IOException e) {
                            r.put("exported", e.toString());
                        }
                    }
                    if (q.containsKey("neilist")) {
                        final List<Object> names = new ArrayList<>();
                        for (final net.minecraft.item.ItemStack s : codechicken.nei.ItemPanels.itemPanel.getGrid()
                            .getItems()) {
                            if (names.size() >= 12) break;
                            names.add(
                                net.minecraft.item.Item.itemRegistry.getNameForObject(
                                    s.getItem()) + ":" + s.getItemDamage() + " '" + s.getDisplayName() + "'");
                        }
                        r.put("neilist", names);
                    }
                    r.put("where", com.gtnhplanner.ui.tutorial.Tutorial.where());
                    return r;
                });
            case "/recipeinfo":
                requireWorld();
                return onClient(
                    () -> DevRecipes
                        .recipeInfo(arg(q, "output"), q.getOrDefault("handler", ""), q.getOrDefault("input", "")));
            case "/gtmachines":
                return onClient(() -> {
                    try {
                        return DevMachines
                            .list(q.getOrDefault("q", ""), "1".equals(q.get("all")), "1".equals(q.get("art")));
                    } catch (final LinkageError e) {
                        return error("GregTech is not loaded (start with PLANNH_GTNH=1)");
                    }
                });
            case "/crops":
                // Every crop's drops against its card's outputs (the full pack); items=<ids> lists ore names anywhere.
                return onClient(() -> {
                    if (q.containsKey("items")) return DevCrops.items(q.get("items"));
                    try {
                        return DevCrops.report();
                    } catch (final LinkageError e) {
                        return error("CropsNH is not loaded (the full pack has it)");
                    }
                });
            case "/machinemath":
                // Every card of the active plan as the website's machine maths see it (machines/web).
                return onClient(() -> q.containsKey("maps") ? DevMachineMath.maps() : DevMachineMath.report());
            case "/machinedata":
                // The website's side files for the TGS and the Bacterial Vat, read from this game (the full pack).
                return onClient(() -> {
                    try {
                        return DevMachineData.export(new File(q.getOrDefault("dir", "machine-data")));
                    } catch (final LinkageError e) {
                        return error("GregTech is not loaded (start with PLANNH_GTNH=1)");
                    } catch (final java.io.IOException e) {
                        return error("Could not write: " + e.getMessage());
                    }
                });
            case "/solvedump":
                // Every plan's solve input as JSON (dev/SolveInputJson), for the solver's headless corpus: dir=<path>
                // (from
                // the repo root when relative), max=<cards> leaves out plans bigger than that (default 2000).
                requireWorld(); {
                java.io.File dir = new java.io.File(arg(q, "dir"));
                if (!dir.isAbsolute()) dir = new java.io.File(new java.io.File(mc.mcDataDir, "../.."), arg(q, "dir"));
                final java.io.File out = dir;
                final int max = intArg(q, "max", 2000);
                return onClient(() -> {
                    out.mkdirs();
                    final List<String> written = new ArrayList<>();
                    final List<com.gtnhplanner.data.flowchart.Graph> graphs = com.gtnhplanner.data.flowchart.Plan
                        .getInstance()
                        .getGraphs();
                    for (int i = 0; i < graphs.size(); i++) {
                        final com.gtnhplanner.data.flowchart.Graph g = graphs.get(i);
                        if (g.nodes.isEmpty() || g.nodes.size() > max) continue;
                        final String name = String.format(
                            "%02d-%s.json",
                            i,
                            g.getName()
                                .replaceAll("[^A-Za-z0-9]+", "-"));
                        try {
                            java.nio.file.Files.writeString(
                                new java.io.File(out, name).toPath(),
                                new com.google.gson.GsonBuilder().setPrettyPrinting()
                                    .create()
                                    .toJson(
                                        SolveInputJson
                                            .write(com.gtnhplanner.data.flowchart.balancer.SolveInput.of(g))));
                        } catch (final java.io.IOException e) {
                            return error(String.valueOf(e.getMessage()));
                        }
                        written.add(name + " (" + g.nodes.size() + " cards)");
                    }
                    return Map.of("dir", out.getAbsolutePath(), "written", written);
                });
            }
            case "/stress":
                // Stress plans: copies=N tiles the open plan, world=1 places every machine, animate=pan|zoom|yaw|none
                // moves the camera every frame, timesave=1 times one edit's encodes and save; alone, a report.
                requireWorld();
                return onClient(() -> DevStress.handle(q));
            case "/perf":
                // Frame times: perf?start=1 begins sampling, perf ends it and reports (fps, percentiles, sections).
                if (q.containsKey("start")) {
                    DevPerf.start();
                    return ok();
                }
                return DevPerf.stop();
            case "/record":
                // Demo videos: record?start=<name>&fps=24&width=1280[&threads=3&quality=0.92&cursor=0] writes
                // frames to recordings/<name>/; record?stop=1.
                if (q.containsKey("start")) return onClient(
                    () -> DevRecorder.INSTANCE.start(
                        q.get("start"),
                        Integer.parseInt(q.getOrDefault("fps", "24")),
                        Integer.parseInt(q.getOrDefault("width", "1280")),
                        Integer.parseInt(q.getOrDefault("threads", "3")),
                        Float.parseFloat(q.getOrDefault("quality", "0.92")),
                        !"0".equals(q.get("cursor"))));
                return DevRecorder.INSTANCE.stop();
            case "/look":
                // Turn the player: yaw (0 south, 90 west, 180 north, 270 east) and pitch (down positive).
                requireWorld();
                return onClient(() -> {
                    final net.minecraft.entity.player.EntityPlayer p = net.minecraft.client.Minecraft
                        .getMinecraft().thePlayer;
                    // Neither given: only says where the player is and looks, to turn them back after.
                    if (q.containsKey("yaw") || q.containsKey("pitch")) {
                        p.rotationYaw = p.prevRotationYaw = Float.parseFloat(q.getOrDefault("yaw", "0"));
                        p.rotationPitch = p.prevRotationPitch = Float.parseFloat(q.getOrDefault("pitch", "0"));
                    }
                    return Map
                        .of("yaw", p.rotationYaw, "pitch", p.rotationPitch, "x", p.posX, "y", p.posY, "z", p.posZ);
                });
            case "/keybind":
                // Sets a binding as Controls does (name=key.gtnhplanner.focus&code=24), or none given lists the
                // planner's: each binding's key, and which binding the game's key table runs for that key.
                return onClient(() -> {
                    final net.minecraft.client.settings.KeyBinding[] all = mc.gameSettings.keyBindings;
                    if (q.containsKey("name"))
                        for (final net.minecraft.client.settings.KeyBinding k : all) if (k.getKeyDescription()
                            .equals(q.get("name"))) {
                                mc.gameSettings.setOptionKeyBinding(k, Integer.parseInt(q.get("code")));
                                net.minecraft.client.settings.KeyBinding.resetKeyBindingArrayAndHash();
                            }
                    final Map<String, Object> out = new java.util.LinkedHashMap<>();
                    if (q.containsKey("yield")) out.put("yields", com.gtnhplanner.ui.world.PlannerKeys.yieldsKeys());
                    if (q.containsKey("probe")) {
                        // Which binding a press of that key runs: the one the game's key table holds for it.
                        for (final net.minecraft.client.settings.KeyBinding k : all) while (k.isPressed()) {}
                        net.minecraft.client.settings.KeyBinding.onTick(Integer.parseInt(q.get("probe")));
                        final List<String> runs = new ArrayList<>();
                        for (final net.minecraft.client.settings.KeyBinding k : all)
                            if (k.isPressed()) runs.add(k.getKeyDescription());
                        out.put("runs", runs);
                        return out;
                    }
                    for (final net.minecraft.client.settings.KeyBinding k : all) {
                        if (!k.getKeyCategory()
                            .equals("key.categories.neiflowchart") && !q.containsKey("all")) continue;
                        final List<String> sharing = new ArrayList<>();
                        for (final net.minecraft.client.settings.KeyBinding o : all)
                            if (o != k && o.getKeyCode() == k.getKeyCode() && k.getKeyCode() != 0)
                                sharing.add(o.getKeyDescription());
                        out.put(
                            k.getKeyDescription(),
                            org.lwjgl.input.Keyboard.getKeyName(k.getKeyCode())
                                + (sharing.isEmpty() ? "" : " (shared with " + String.join(", ", sharing) + ")"));
                    }
                    return out;
                });
            case "/worldplace":
                // The plan over the world set up for a look: card=N placed at x,y,z[&facing][&add=1], pick=N starts
                // placing card N as its place key does, clear=1 takes the open plan out of the world.
                requireWorld();
                return onClient(() -> DevBoard.worldPlace(q));
            case "/machine":
                // A GregTech machine in the dev world, powered and fed so it runs (for the AR lens).
                requireWorld();
                return onClient(() -> {
                    try {
                        return DevWorld.machine(q);
                    } catch (final LinkageError e) {
                        return error("GregTech is not loaded (start with PLANNH_GTNH=1)");
                    }
                });
            case "/addpower":
                // Power sources: list=1 lists their ids and names; source=<id> adds one to the open plan at x, y.
                requireWorld();
                return onClient(() -> {
                    if (q.containsKey("list")) {
                        final List<String> ids = new ArrayList<>();
                        for (final com.gtnhplanner.power.PowerSource s : com.gtnhplanner.power.PowerRegistry.sources())
                            ids.add(s.id() + " = " + s.name());
                        return Map.of("sources", ids);
                    }
                    final com.gtnhplanner.ui.BoardSession board = com.gtnhplanner.ui.BoardSession.current();
                    if (board == null) return error("open the planner first");
                    final com.gtnhplanner.data.flowchart.Node node = board.addPower(arg(q, "source"), Map.of());
                    node.x = intArg(q, "x", 0);
                    node.y = intArg(q, "y", 0);
                    return Map.of("id", String.valueOf(node.id));
                });
            case "/cards":
                // Card designs side by side (today's and the trial), for the plan last open.
                return onClient(() -> {
                    mc.displayGuiScreen(
                        new CardGallery(
                            Integer.parseInt(q.getOrDefault("from", "0")),
                            Integer.parseInt(q.getOrDefault("count", "3")),
                            Float.parseFloat(q.getOrDefault("scale", "0.8")),
                            q.getOrDefault("match", "")));
                    return ok();
                });
            case "/neiinput":
                // What NEI's input hooks saw on the planner since last asked: presses, releases, drags.
                return Map.of("seen", DevNeiInput.INSTANCE.take());
            case "/power":
                // The power sources against this game: flows that become no port, fluids without icons, machines.
                return onClient(DevPower::check);
            case "/slots":
                // Plan slots: list them; add=<name> opens a new one; switch=<i>; delete=<i>.
                requireWorld();
                return onClient(() -> {
                    final com.gtnhplanner.data.flowchart.Plan plan = com.gtnhplanner.data.flowchart.Plan.getInstance();
                    com.gtnhplanner.data.flowchart.Plan.getActiveGraph();
                    if (q.containsKey("add")) {
                        plan.getGraphs()
                            .add(new com.gtnhplanner.data.flowchart.Graph(q.get("add")));
                        plan.setActiveIndex(
                            plan.getGraphs()
                                .size() - 1);
                    } else if (q.containsKey("switch")) {
                        final int i = intArg(q, "switch");
                        if (i >= 0 && i < plan.getGraphs()
                            .size()) plan.setActiveIndex(i);
                    } else if (q.containsKey("delete")) {
                        plan.removeSlot(intArg(q, "delete"));
                    }
                    com.gtnhplanner.api.PlanAPI.save();
                    final Map<String, Object> m = new LinkedHashMap<>();
                    final List<String> names = new ArrayList<>();
                    for (final com.gtnhplanner.data.flowchart.Graph g : plan.getGraphs()) names.add(g.getName());
                    m.put("slots", names);
                    m.put("active", plan.getActiveIndex());
                    return m;
                });
            case "/clearplan":
                requireWorld();
                return onClient(DevRecipes::clearPlan);
            case "/notice":
                // A notice along the top of the open board, as the planner raises one: text=<sentence>[&level=info|
                // warn|error]. For seeing how notices read on a narrow board.
                return onClient(() -> {
                    final com.gtnhplanner.ui.BoardScreen board = DevBoard.screen();
                    if (board == null) throw new IllegalStateException("the planner is not open");
                    board.session()
                        .flash(
                            com.gtnhplanner.data.flowchart.balancer.Severity.valueOf(
                                q.getOrDefault("level", "info")
                                    .toUpperCase(java.util.Locale.ROOT)),
                            arg(q, "text"));
                    return ok();
                });
            case "/exportff":
                // Writes the open plan as the site's project JSON, as posting it would send: file=<path> (from the repo
                // root when relative).
                requireWorld(); {
                java.io.File f = new java.io.File(arg(q, "file"));
                if (!f.isAbsolute()) f = new java.io.File(new java.io.File(mc.mcDataDir, "../.."), arg(q, "file"));
                final java.io.File out = f;
                return onClient(() -> {
                    final com.gtnhplanner.ui.BoardScreen board = DevBoard.screen();
                    if (board == null) return error("the planner is not open");
                    final com.gtnhplanner.ui.BoardSession session = board.session();
                    final com.google.gson.JsonObject plan = com.gtnhplanner.library.Posting.write(
                        session.graph(),
                        session.graph()
                            .getName(),
                        id -> {
                            final com.gtnhplanner.ui.card.CardModel m = session.model(id);
                            return m == null ? null : m.machines;
                        });
                    out.getParentFile()
                        .mkdirs();
                    try {
                        java.nio.file.Files.writeString(
                            out.toPath(),
                            new com.google.gson.GsonBuilder().setPrettyPrinting()
                                .create()
                                .toJson(plan));
                    } catch (final java.io.IOException e) {
                        return error(String.valueOf(e.getMessage()));
                    }
                    final Map<String, Object> m = new LinkedHashMap<>();
                    m.put("file", out.getAbsolutePath());
                    return m;
                });
            }
            case "/importff":
                // Imports a Factory Flow plan as a new slot: file=<path> (absolute, or from the repo root) or text=.
                requireWorld(); {
                final String text;
                if (q.containsKey("file")) {
                    java.io.File f = new java.io.File(q.get("file"));
                    if (!f.isAbsolute()) f = new java.io.File(new java.io.File(mc.mcDataDir, "../.."), q.get("file"));
                    text = java.nio.file.Files.readString(f.toPath());
                } else text = arg(q, "text");
                return onClient(() -> {
                    final com.gtnhplanner.importer.FfConverter.Result result;
                    try {
                        result = com.gtnhplanner.importer.game.FactoryFlowImport.importAsSlot(text);
                    } catch (final RuntimeException e) {
                        return error(String.valueOf(e.getMessage()));
                    }
                    final boolean wasOpen = com.gtnhplanner.ui.Planner.isPlanner(mc.currentScreen);
                    if (wasOpen) {
                        mc.displayGuiScreen(null);
                        openFlowchart();
                    }
                    final Map<String, Object> m = new LinkedHashMap<>();
                    m.put(
                        "name",
                        result.graph()
                            .getName());
                    m.put(
                        "summary",
                        result.report()
                            .summary());
                    final List<String> entries = new ArrayList<>();
                    for (final com.gtnhplanner.importer.ImportReport.Entry e : result.report()
                        .entries()) entries.add(e.toString());
                    m.put("report", entries);
                    return m;
                });
            }
            case "/iconatlas":
                // Writes the icon-shadow atlas to run/client/screenshots/<name, default icon-atlas.png>.
                return onFrame(() -> {
                    final java.awt.image.BufferedImage image = com.gtnhplanner.ui.theme.IconShadows.atlasImage();
                    if (image == null) return error("no atlas yet: nothing has asked for an icon shadow");
                    final java.io.File out = new java.io.File(
                        mc.mcDataDir,
                        "screenshots/" + q.getOrDefault("name", "icon-atlas.png"));
                    try {
                        javax.imageio.ImageIO.write(image, "png", out);
                    } catch (final java.io.IOException e) {
                        return error(e.toString());
                    }
                    final Map<String, Object> m = new LinkedHashMap<>();
                    m.put("file", out.getAbsolutePath());
                    m.put("cells", com.gtnhplanner.ui.theme.IconShadows.cells());
                    return m;
                });
            case "/structurepic":
                requireWorld();
                return onFrame(() -> structurePicture(q));
            case "/quit":
                frameActions.add(mc::shutdown);
                return ok();
            default:
                throw new IllegalArgumentException("unknown endpoint " + path + ", see /help");
        }
    }

    private static Map<String, Object> help() {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put(
            "endpoints",
            List.of(
                "/status - ready flag, current screen, display and GUI sizes, mouse position (GUI coords)",
                "/open - open the GTNH Planner flowchart; /close - close the current screen",
                "/screenshot?name=x.png[&x&y&w&h] - save the next frame (optionally a GUI-coord crop), returns the path",
                "/widgets - dump the ModularUI widget tree with GUI-coordinate areas",
                "/move?x&y, /click?x&y&button&count&mods, /drag?x1&y1&x2&y2&steps&button&mods, /scroll?x&y&amount - GUI coords",
                "/key?code[&char][&mods=ctrl,shift,alt] - LWJGL2 key code, /type?text - text into the focused field",
                "/cmd?c=/time set day - run a command as the player",
                "/addrecipe?output=dustRutile[&handler=blast][&input=ilmenite][&x&y] - put a real recipe on the board (with the board open: placed like NEI's +)",
                "/recipeinfo?output[&handler][&input] - what NEI and GTNH Planner see in a recipe (stacks, ports), read-only",
                "/nei?item[&uses=1][&planner=0][&tab] - open NEI's recipes for an item, over the planner or (planner=0) from the inventory",
                "/sound[?volume=0..1] - the master volume, set or read",
                "/library[?url=<base>][&live=<ws url>] - the site the library uses (tools/dev/mock-library.mjs is a local stand-in) and its live service",
                "/cloud[?fake=N] - signed in: the account's cloud plans on this device (status, each plan's seq, edits waiting, room, who is in it); fake=N puts N stand-in people on the open plan (0 clears), peers lists who the board shows",
                "/chat[?lines=N][&share=1] - the chat's newest lines and what clicking each part runs; share=1 shares the open plan there first",
                "/gtmachines?q=turbine[&all=1][&art=1] - GregTech multiblocks (all=1: every machine) as the game names them; art=1 adds the bundled picture each resolves to",
                "/machinedata?dir=<path> - writes the website's TGS, Bacterial Vat and radio hatch side files from this game (run in the full pack)",
                "/machinemath - every card of the active plan as the website's machine maths see it: machine, handler, settings, ticks, EU/t, parallels",
                "/crops[?items=<id,id>] - every CropsNH crop's drops against its card's outputs: lost drops, amounts, drops sharing an ore name (full pack); items= lists ore names",
                "/slots[?add=name | switch=i | delete=i] - list, open, switch or delete plan slots",
                "/clearplan - empty the active board (one undoable edit)",
                "/notice?text=<sentence>[&level=info|warn|error] - a notice along the top of the open board",
                "/window[?w&h][&scale][&size=0..3] - the window's size, the GUI scale and the planner's Size",
                "/board - open board as data: view, and per card its state and every control's GUI rect (cx, cy)",
                "/view?zoom&panX&panY - set the board view (defaults 1, 0, 0)",
                "/structurepic?meta=1000 - (re)build that GT multiblock's card picture, save it as screenshots/structure-<meta>.png; no meta lists the controllers",
                "/tutorial[?start=1 | chapter=N&beat=M | next=1 | stop=1] - the tour: start it, jump to a beat, hurry"
                    + " the current beat, stop it; returns where it is",
                "/quit - ask the client to quit (may hang on a confirm dialog with GT; mc.sh stop kills)"));
        return m;
    }

    // endregion

    // region Endpoint implementations

    private Map<String, Object> status() {
        final Map<String, Object> m = new LinkedHashMap<>();
        final ScaledResolution sr = scaled();
        m.put("ready", ready);
        m.put("inWorld", mc.theWorld != null);
        m.put(
            "screen",
            mc.currentScreen == null ? null
                : mc.currentScreen.getClass()
                    .getName());
        final ModularScreen mui = muiScreen();
        m.put(
            "muiScreen",
            mui == null ? null
                : mui.getClass()
                    .getName());
        if (mui != null) {
            final IWidget hovered = mui.getContext()
                .getHovered();
            final LocatedWidget focused = mui.getContext()
                .getFocusedWidget();
            m.put("hovered", hovered == null ? null : describe(hovered));
            m.put("focused", focused == null || focused.getElement() == null ? null : describe(focused.getElement()));
            final List<Object> panels = new ArrayList<>();
            for (final ModularPanel p : mui.getPanelManager()
                .getOpenPanels()) {
                panels.add(
                    p.getName() + " enabled="
                        + p.isEnabled()
                        + " anyHovered="
                        + p.isAnyHovered()
                        + " valid="
                        + p.isValid());
            }
            m.put("panels", panels);
            m.put(
                "muiMouseX",
                mui.getContext()
                    .getAbsMouseX());
            m.put(
                "muiMouseY",
                mui.getContext()
                    .getAbsMouseY());
        }
        m.put("windowActive", org.lwjgl.opengl.Display.isActive());
        // Seconds since the person last moved the mouse or pressed something in the game window.
        m.put("idleSeconds", (System.currentTimeMillis() - lastUserMs) / 1000);
        // Minecraft keeps "N fps, M chunk updates" in its debug string.
        m.put(
            "fps",
            Integer.parseInt(
                mc.debug.replaceFirst(" fps.*", "")
                    .trim()));
        m.put("displayWidth", mc.displayWidth);
        m.put("displayHeight", mc.displayHeight);
        m.put("guiWidth", sr.getScaledWidth());
        m.put("guiHeight", sr.getScaledHeight());
        m.put("guiScale", sr.getScaleFactor());
        m.put("mouseX", Mouse.getX() * sr.getScaledWidth() / mc.displayWidth);
        m.put("mouseY", sr.getScaledHeight() - Mouse.getY() * sr.getScaledHeight() / mc.displayHeight - 1);
        return m;
    }

    /** Full frame, or with x/y/w/h (GUI coordinates) just that region, at native resolution. */
    private Object screenshot(final String name, final Map<String, String> q) throws Exception {
        if (!name.matches("[A-Za-z0-9._-]+\\.png")) throw new IllegalArgumentException("name must be like foo.png");
        final boolean crop = q.containsKey("x");
        final int cx = crop ? intArg(q, "x") : 0, cy = crop ? intArg(q, "y") : 0;
        final int cw = crop ? intArg(q, "w") : 0, ch = crop ? intArg(q, "h") : 0;
        // Skip one frame so anything queued just before this request has been drawn.
        final CompletableFuture<Object> done = new CompletableFuture<>();
        frameActions.add(() -> frameActions.add(() -> {
            try {
                ScreenShotHelper
                    .saveScreenshot(mc.mcDataDir, name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
                final File file = new File(new File(mc.mcDataDir, "screenshots"), name);
                if (crop) {
                    final int s = scaled().getScaleFactor();
                    final BufferedImage full = ImageIO.read(file);
                    final int x = Math.max(0, cx * s), y = Math.max(0, cy * s);
                    final int w = Math.min(full.getWidth() - x, cw * s), h = Math.min(full.getHeight() - y, ch * s);
                    ImageIO.write(full.getSubimage(x, y, w, h), "png", file);
                }
                final Map<String, Object> m = ok();
                m.put("path", file.getCanonicalPath());
                done.complete(m);
            } catch (final Throwable t) {
                done.completeExceptionally(t);
            }
        }));
        return done.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Rebuilds one multiblock's recipe-card picture (replacing the cached one, so an open board shows it) and saves its
     * pixels; without {@code meta}, lists the controllers that can have one.
     */
    private Map<String, Object> structurePicture(final Map<String, String> q) {
        if (!MultiblockPictures.available()) {
            return error("multiblock pictures need GregTech, BlockRenderer6343 and GL 3.0 (start with PLANNH_GTNH=1)");
        }
        if (!q.containsKey("meta")) {
            final Map<String, Object> m = ok();
            m.put("controllers", MultiblockPictures.controllers());
            return m;
        }
        final MultiblockPictures.Build build = MultiblockPictures.rebuild(intArg(q, "meta"));
        final Map<String, Object> m = ok();
        m.put("meta", build.meta());
        m.put("name", build.name());
        m.put("status", build.status());
        m.put("size", List.of(build.sizeX(), build.sizeY(), build.sizeZ()));
        m.put("blocks", build.blocks());
        m.put("buildMs", build.buildMillis());
        m.put("renderMs", build.renderMillis());
        if (build.argb() != null) {
            final int side = MultiblockPictures.SIZE;
            final BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, side, side, build.argb(), 0, side);
            final File dir = new File(mc.mcDataDir, "screenshots");
            final File file = new File(dir, "structure-" + build.meta() + ".png");
            try {
                Files.createDirectories(dir.toPath());
                ImageIO.write(image, "png", file);
                m.put("path", file.getCanonicalPath());
            } catch (final IOException e) {
                m.put("pathError", e.toString());
            }
        }
        return m;
    }

    private Object widgets() {
        final ModularScreen screen = muiScreen();
        if (screen == null) throw new IllegalArgumentException("current screen is not a ModularUI screen");
        final Map<String, Object> main = dumpWidget(screen.getMainPanel(), 0);
        // Popups (menus, number boxes) are panels of their own, above the main one.
        final List<Object> popups = new ArrayList<>();
        for (final ModularPanel p : screen.getPanelManager()
            .getOpenPanels()) if (p != screen.getMainPanel()) popups.add(dumpWidget(p, 0));
        if (!popups.isEmpty()) main.put("popups", popups);
        return main;
    }

    /** A widget's area in the game's GUI pixels, as the harness's input takes them (the board lays out in its own). */
    private static Area gameArea(final IWidget widget) {
        final Area a = widget.getArea();
        if (!com.gtnhplanner.ui.Planner.isPlanner(Minecraft.getMinecraft().currentScreen)) return a;
        final int x = com.gtnhplanner.ui.PlannerScale.toGame(a.x()), y = com.gtnhplanner.ui.PlannerScale.toGame(a.y());
        return new Area(
            x,
            y,
            com.gtnhplanner.ui.PlannerScale.toGame(a.x() + a.w()) - x,
            com.gtnhplanner.ui.PlannerScale.toGame(a.y() + a.h()) - y);
    }

    private static String describe(final IWidget widget) {
        final Area a = gameArea(widget);
        return widget.getClass()
            .getSimpleName() + " @"
            + a.x()
            + ","
            + a.y()
            + " "
            + a.w()
            + "x"
            + a.h();
    }

    private static Map<String, Object> dumpWidget(final IWidget widget, final int depth) {
        final Map<String, Object> m = new LinkedHashMap<>();
        final Area a = gameArea(widget);
        m.put(
            "type",
            widget.getClass()
                .getSimpleName());
        if (widget.getName() != null) m.put("name", widget.getName());
        m.put("x", a.x());
        m.put("y", a.y());
        m.put("w", a.w());
        m.put("h", a.h());
        if (!widget.isEnabled()) m.put("enabled", false);
        final List<IWidget> children = widget.getChildren();
        if (!children.isEmpty() && depth < 32) {
            final List<Object> out = new ArrayList<>();
            for (final IWidget child : children) out.add(dumpWidget(child, depth + 1));
            m.put("children", out);
        }
        return m;
    }

    private void openFlowchart() {
        com.gtnhplanner.ui.Planner.open();
    }

    private Object click(final Map<String, String> q) throws Exception {
        final int x = intArg(q, "x"), y = intArg(q, "y"), button = intArg(q, "button", 0);
        final int count = Math.max(1, intArg(q, "count", 1));
        final List<Integer> mods = mods(q);
        final List<Runnable> actions = new ArrayList<>();
        for (final int m : mods) actions.add(() -> SyntheticInput.key(m, 0, true));
        actions.add(() -> moveTo(x, y));
        actions.add(() -> {});
        actions.add(() -> {});
        // Consecutive ticks are 50ms apart, well inside any double-click window.
        for (int i = 0; i < count; i++) {
            actions.add(() -> SyntheticInput.button(button, true));
            actions.add(() -> SyntheticInput.button(button, false));
        }
        for (final int m : mods) actions.add(() -> SyntheticInput.key(m, 0, false));
        return input(actions);
    }

    private Object drag(final Map<String, String> q) throws Exception {
        final int x1 = intArg(q, "x1"), y1 = intArg(q, "y1"), x2 = intArg(q, "x2"), y2 = intArg(q, "y2");
        final int steps = Math.max(1, intArg(q, "steps", 10)), button = intArg(q, "button", 0);
        final List<Integer> mods = mods(q);
        final List<Runnable> actions = new ArrayList<>();
        for (final int m : mods) actions.add(() -> SyntheticInput.key(m, 0, true));
        actions.add(() -> moveTo(x1, y1));
        // Two drawn frames before the press, so it lands on what is under the mouse now (after a hot-swap stall the
        // first frame can still show the old hover).
        actions.add(() -> {});
        actions.add(() -> {});
        actions.add(() -> SyntheticInput.button(button, true));
        for (int i = 1; i <= steps; i++) {
            final int sx = x1 + (x2 - x1) * i / steps, sy = y1 + (y2 - y1) * i / steps;
            actions.add(() -> moveTo(sx, sy));
        }
        // hold=1 keeps the button down at the end (a screenshot mid-drag), until /release.
        if (!"1".equals(q.get("hold"))) {
            actions.add(() -> SyntheticInput.button(button, false));
            for (final int m : mods) actions.add(() -> SyntheticInput.key(m, 0, false));
        }
        return input(actions);
    }

    /** mods=ctrl,shift,alt: LWJGL left-hand key codes to hold around an input. */
    private static List<Integer> mods(final Map<String, String> q) {
        final List<Integer> mods = new ArrayList<>();
        for (final String m : q.getOrDefault("mods", "")
            .split(",")) {
            switch (m.trim()) {
                case "ctrl" -> mods.add(29);
                case "shift" -> mods.add(42);
                case "alt" -> mods.add(56);
                default -> {}
            }
        }
        return mods;
    }

    private Object scroll(final Map<String, String> q) throws Exception {
        final int x = intArg(q, "x"), y = intArg(q, "y"), amount = intArg(q, "amount", 1);
        return input(List.of(() -> moveTo(x, y), () -> SyntheticInput.wheel(amount)));
    }

    private Object key(final Map<String, String> q) throws Exception {
        final int code = intArg(q, "code");
        final String ch = q.getOrDefault("char", "");
        final int codepoint = ch.isEmpty() ? 0 : ch.codePointAt(0);
        final List<Integer> mods = mods(q);
        final List<Runnable> steps = new ArrayList<>();
        for (final int m : mods) steps.add(() -> SyntheticInput.key(m, 0, true));
        steps.add(() -> SyntheticInput.key(code, codepoint, true));
        // hold=ms keeps it down that long (a step a tick, 50 ms each), for keys read while held.
        for (int i = 0; i < intArg(q, "hold", 0) / 50; i++) {
            steps.add(SyntheticInput::reassertHeld);
        }
        steps.add(() -> SyntheticInput.key(code, codepoint, false));
        for (final int m : mods) steps.add(() -> SyntheticInput.key(m, 0, false));
        return input(steps);
    }

    private Object type(final Map<String, String> q) throws Exception {
        final String text = arg(q, "text");
        return input(List.of(() -> SyntheticInput.text(text)));
    }

    /** Converts GUI coordinates to window pixels and injects a motion event there. */
    private void moveTo(final int guiX, final int guiY) {
        final int scale = scaled().getScaleFactor();
        // Aim at the middle of the GUI pixel so integer division in the game lands back on guiX/guiY.
        SyntheticInput.moveTo(guiX * scale + scale / 2, guiY * scale + scale / 2);
    }

    // endregion

    // region Helpers

    private Object input(final List<Runnable> steps) throws Exception {
        if (!SyntheticInput.available()) {
            throw new IllegalStateException("synthetic input needs lwjgl3ify: launch with runClient25");
        }
        final CompletableFuture<Object> done = new CompletableFuture<>();
        for (final Runnable step : steps) tickActions.add(() -> {
            try {
                lastSyntheticMs = System.currentTimeMillis();
                step.run();
            } catch (final Throwable t) {
                done.completeExceptionally(t);
            }
        });
        tickActions.add(() -> done.complete(ok()));
        return done.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private <T> T onClient(final Supplier<T> task) throws Exception {
        final CompletableFuture<T> done = new CompletableFuture<>();
        tickActions.add(() -> {
            try {
                done.complete(task.get());
            } catch (final Throwable t) {
                done.completeExceptionally(t);
            }
        });
        return done.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /** Like {@link #onClient}, but at the end of a rendered frame (for offscreen GL work). */
    private <T> T onFrame(final Supplier<T> task) throws Exception {
        final CompletableFuture<T> done = new CompletableFuture<>();
        frameActions.add(() -> {
            try {
                done.complete(task.get());
            } catch (final Throwable t) {
                done.completeExceptionally(t);
            }
        });
        return done.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void requireWorld() {
        if (mc.thePlayer == null) throw new IllegalArgumentException("not in a world yet, poll /status for ready");
    }

    private ModularScreen muiScreen() {
        final GuiScreen screen = mc.currentScreen;
        return screen instanceof IMuiScreen muiScreen ? muiScreen.getScreen() : null;
    }

    private ScaledResolution scaled() {
        return new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
    }

    private static Map<String, String> query(final HttpExchange exchange) throws IOException {
        final Map<String, String> out = new LinkedHashMap<>();
        final String raw = exchange.getRequestURI()
            .getRawQuery();
        if (raw == null) return out;
        for (final String part : raw.split("&")) {
            final int eq = part.indexOf('=');
            final String k = eq < 0 ? part : part.substring(0, eq);
            final String v = eq < 0 ? "" : part.substring(eq + 1);
            out.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
        }
        return out;
    }

    private static String arg(final Map<String, String> q, final String name) {
        final String v = q.get(name);
        if (v == null) throw new IllegalArgumentException("missing parameter '" + name + "'");
        return v;
    }

    private static int intArg(final Map<String, String> q, final String name) {
        try {
            return Integer.parseInt(arg(q, name));
        } catch (final NumberFormatException e) {
            throw new IllegalArgumentException("parameter '" + name + "' must be an integer");
        }
    }

    private static int intArg(final Map<String, String> q, final String name, final int fallback) {
        return q.containsKey(name) ? intArg(q, name) : fallback;
    }

    /** The chat's newest {@code n} lines, newest first: each one's text and the commands its parts run when clicked. */
    private List<Map<String, Object>> chatLines(final int n) {
        final List<?> lines;
        try {
            Field field;
            try {
                field = net.minecraft.client.gui.GuiNewChat.class.getDeclaredField("chatLines");
            } catch (final NoSuchFieldException obfuscated) {
                field = net.minecraft.client.gui.GuiNewChat.class.getDeclaredField("field_146252_h");
            }
            field.setAccessible(true);
            lines = (List<?>) field.get(mc.ingameGUI.getChatGUI());
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        final List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, lines.size()); i++) {
            final net.minecraft.util.IChatComponent line = ((net.minecraft.client.gui.ChatLine) lines.get(i))
                .func_151461_a();
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("text", line.getUnformattedText());
            final List<String> clicks = new ArrayList<>();
            for (final Object part : line) {
                final net.minecraft.event.ClickEvent click = ((net.minecraft.util.IChatComponent) part).getChatStyle()
                    .getChatClickEvent();
                if (click != null) clicks.add(click.getValue());
            }
            m.put("clicks", clicks);
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> ok() {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    private static Map<String, Object> error(final String message) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", message);
        return m;
    }

    // endregion

    /**
     * Feeds events into lwjgl3ify's emulated LWJGL2 input queues, the same path real SDL input takes, so the game
     * cannot tell them apart and the user's real cursor is never touched. Reflection only: lwjgl3ify and LWJGL's SDL
     * bindings exist on the Java 17+ run classpath, not the compile classpath.
     */
    private static final class SyntheticInput {

        private static boolean resolved;
        private static Class<?> motionEventClass;
        private static Method addMoveEvent, addButtonEvent, addWheelEvent, lwjglToSdlButton, pixelScale;
        private static Method addRawKeyEvent, addCharEvent, injectTextEvent;
        private static java.lang.reflect.Constructor<?> keyEventCtor, textEventCtor;
        private static Object keyPress, keyRelease;
        private static Field buttonFlags;
        private static int lastX, lastY;

        static synchronized boolean available() {
            if (!resolved) {
                resolved = true;
                try {
                    final Class<?> mouse = Class.forName("org.lwjglx.input.Mouse");
                    final Class<?> keyboard = Class.forName("org.lwjglx.input.Keyboard");
                    final Class<?> keyEvent = Class.forName("org.lwjglx.input.Keyboard$KeyEvent");
                    final Class<?> keyState = Class.forName("org.lwjglx.input.Keyboard$KeyState");
                    motionEventClass = Class.forName("org.lwjgl.sdl.SDL_MouseMotionEvent");
                    addMoveEvent = mouse.getMethod("addMoveEvent", motionEventClass);
                    addButtonEvent = mouse.getMethod("addButtonEvent", int.class, boolean.class);
                    addWheelEvent = mouse.getMethod("addWheelEvent", double.class);
                    lwjglToSdlButton = mouse.getMethod("lwjglToSdlMouseButton", int.class);
                    buttonFlags = mouse.getField("sdlMouseButtonFlags");
                    pixelScale = Class.forName("org.lwjglx.opengl.Display")
                        .getMethod("getPixelScaleFactor");
                    addRawKeyEvent = keyboard.getMethod("addRawKeyEvent", keyEvent);
                    final Class<?> inputEvents = Class.forName("me.eigenraven.lwjgl3ify.api.InputEvents");
                    final Class<?> textEvent = Class.forName("me.eigenraven.lwjgl3ify.api.InputEvents$TextEvent");
                    injectTextEvent = inputEvents.getMethod("injectTextEvent", textEvent);
                    addCharEvent = keyboard.getMethod("addCharEvent", int.class, int.class);
                    textEventCtor = textEvent.getConstructor(String.class);
                    keyEventCtor = keyEvent.getConstructor(int.class, int.class, int.class, keyState, long.class);
                    keyPress = keyState.getField("PRESS")
                        .get(null);
                    keyRelease = keyState.getField("RELEASE")
                        .get(null);
                } catch (final ReflectiveOperationException e) {
                    GtnhPlanner.LOG.warn("[dev] Synthetic input unavailable: {}", e.toString());
                    addMoveEvent = null;
                }
            }
            return addMoveEvent != null;
        }

        /** @param px window pixels from the left, @param py window pixels from the top */
        static void moveTo(final int px, final int py) {
            try {
                final float scale = (float) pixelScale.invoke(null);
                final Object event = motionEventClass.getMethod("calloc")
                    .invoke(null);
                motionEventClass.getMethod("x", float.class)
                    .invoke(event, px / scale);
                motionEventClass.getMethod("y", float.class)
                    .invoke(event, py / scale);
                // Relative to where the cursor really is: the game recentres it when a screen opens, behind our back.
                final int curX = org.lwjgl.input.Mouse.getX();
                final int curY = Minecraft.getMinecraft().displayHeight - 1 - org.lwjgl.input.Mouse.getY();
                motionEventClass.getMethod("xrel", float.class)
                    .invoke(event, (px - curX) / scale);
                motionEventClass.getMethod("yrel", float.class)
                    .invoke(event, (py - curY) / scale);
                addMoveEvent.invoke(null, event);
                motionEventClass.getMethod("free")
                    .invoke(event);
                lastX = px;
                lastY = py;
                reassertHeld();
            } catch (final ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        static void button(final int button, final boolean down) {
            try {
                // isButtonDown() reads the SDL button mask rather than the event queue, so keep both in step.
                final int sdl = (byte) lwjglToSdlButton.invoke(null, button);
                final int mask = 1 << (sdl - 1);
                final int flags = buttonFlags.getInt(null);
                buttonFlags.setInt(null, down ? flags | mask : flags & ~mask);
                addButtonEvent.invoke(null, button, down);
                reassertHeld();
            } catch (final ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        static void wheel(final int amount) {
            try {
                addWheelEvent.invoke(null, (double) amount);
            } catch (final ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        /** SDL scancodes of synthetic keys pressed and not yet released. */
        private static final java.util.Set<Integer> HELD = new java.util.HashSet<>();

        static void key(final int code, final int codepoint, final boolean down) {
            try {
                addRawKeyEvent.invoke(
                    null,
                    keyEventCtor.newInstance(code, code, codepoint, down ? keyPress : keyRelease, System.nanoTime()));
                // isKeyDown() (Ctrl and Shift checks) reads SDL's key state array, not the event queue: keep it in
                // step.
                final Class<?> keyboard = Class.forName("org.lwjglx.input.Keyboard");
                final java.lang.reflect.Field state = keyboard.getDeclaredField("sdlKeyPressedArray");
                state.setAccessible(true);
                final java.nio.ByteBuffer pressed = (java.nio.ByteBuffer) state.get(null);
                final int scancode = (int) Class.forName("org.lwjglx.input.KeyCodes")
                    .getMethod("lwjglToSdlScancode", int.class)
                    .invoke(null, code);
                if (down) HELD.add(scancode);
                else HELD.remove(scancode);
                if (pressed != null && scancode > 0 && scancode < pressed.limit())
                    pressed.put(scancode, (byte) (down ? 1 : 0));
                reassertHeld();
            } catch (final ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        /** SDL refreshes its key state array as it pumps events, so re-assert every key still held (modifiers). */
        static void reassertHeld() {
            if (HELD.isEmpty()) return;
            try {
                final java.lang.reflect.Field state = Class.forName("org.lwjglx.input.Keyboard")
                    .getDeclaredField("sdlKeyPressedArray");
                state.setAccessible(true);
                final java.nio.ByteBuffer pressed = (java.nio.ByteBuffer) state.get(null);
                if (pressed != null)
                    for (final int held : HELD) if (held > 0 && held < pressed.limit()) pressed.put(held, (byte) 1);
            } catch (final ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        /**
         * Mirrors Lwjgl3ifyEventLoop#handleTextEvent: an lwjgl3ify text event (vanilla text fields listen for these)
         * followed by one LWJGL2 char event per character (GuiScreen#keyTyped and ModularUI read those).
         */
        static void text(final String text) {
            try {
                injectTextEvent.invoke(null, textEventCtor.newInstance(text));
                for (final int c : text.chars()
                    .toArray()) addCharEvent.invoke(null, c, c);
            } catch (final ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
