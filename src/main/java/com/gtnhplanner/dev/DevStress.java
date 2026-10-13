package com.gtnhplanner.dev;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.util.MathHelper;

import com.gtnhplanner.api.PlanAPI;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Plan;
import com.gtnhplanner.data.flowchart.Serializer;
import com.gtnhplanner.ui.BoardScreen;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.card.CardLayout;
import com.gtnhplanner.ui.card.CardModel;
import com.gtnhplanner.ui.card.RecipeCard;

/**
 * Stress plans for performance work (harness {@code stress}): the open plan tiled into a huge one, every machine of it
 * placed in the world, and a steady camera motion to sample frame times against. Client thread only.
 */
final class DevStress {

    private DevStress() {}

    /** The camera motion played every frame: none, pan, zoom (in and out) or yaw (the player turning). */
    private static String motion = "none";
    private static float speed = 1;
    private static long motionStart, lastFrame;
    private static float baseZoom = 1;

    static Map<String, Object> handle(final Map<String, String> q) {
        if (q.containsKey("copies"))
            return tile(Integer.parseInt(q.get("copies")), Integer.parseInt(q.getOrDefault("cols", "0")));
        if (q.containsKey("world")) return place(q);
        if (q.containsKey("animate")) {
            motion = q.get("animate");
            speed = Float.parseFloat(q.getOrDefault("speed", "1"));
            motionStart = System.nanoTime();
            lastFrame = 0;
            final BoardScreen screen = DevBoard.screen();
            if (screen != null) baseZoom = screen.session()
                .graph()
                .getZoom();
            return Map.of("animate", motion, "speed", speed);
        }
        if (q.containsKey("timesave")) return timeSave();
        if (q.containsKey("fit")) {
            final BoardScreen screen = DevBoard.screen();
            if (screen == null) throw new IllegalStateException("the planner is not open");
            screen.canvas()
                .frameAll();
            return Map.of("fit", true);
        }
        if (q.containsKey("fps")) {
            // The game's frame cap (260 and up: none), and vsync off, so frame times show the whole cost.
            final net.minecraft.client.settings.GameSettings s = Minecraft.getMinecraft().gameSettings;
            s.limitFramerate = Integer.parseInt(q.get("fps"));
            s.enableVsync = false;
            org.lwjgl.opengl.Display.setVSyncEnabled(false);
            return Map.of("limitFramerate", s.limitFramerate);
        }
        return report();
    }

    /** The open plan as numbers: cards, machines, wires, drawers, placed machines, and the save's size. */
    static Map<String, Object> report() {
        final Map<String, Object> m = new LinkedHashMap<>();
        final BoardScreen screen = DevBoard.screen();
        final Graph graph = screen != null ? screen.session()
            .graph() : Plan.getActiveGraph();
        m.put("plan", graph.getName());
        m.put("cards", graph.nodes.size());
        m.put(
            "wires",
            graph.getEdges()
                .size());
        m.put("drawers", graph.drawers.size());
        int links = 0;
        for (final Node n : graph.nodes.values()) links += n.worldLinks.size();
        m.put("placedMachines", links);
        if (screen != null) {
            double machines = 0;
            for (final CardModel model : screen.session()
                .models()
                .values()) machines += Math.ceil(model.machines);
            m.put("machines", (long) machines);
            m.put(
                "solving",
                screen.session()
                    .solving());
            m.put(
                "cardsBuilt",
                screen.canvas()
                    .cards()
                    .size());
            m.put("zoom", graph.getZoom());
        }
        m.put(
            "plans",
            Plan.getInstance()
                .getGraphs()
                .size());
        m.put("motion", motion);
        final Runtime rt = Runtime.getRuntime();
        m.put("heapUsedMb", (rt.totalMemory() - rt.freeMemory()) >> 20);
        m.put("heapMaxMb", rt.maxMemory() >> 20);
        return m;
    }

    /** What one edit costs besides the change: the undo snapshot's encodes, and saving every plan. */
    private static Map<String, Object> timeSave() {
        final Graph graph = Plan.getActiveGraph();
        final Map<String, Object> m = new LinkedHashMap<>();
        long t = System.nanoTime();
        final String encoded = Serializer.encode(graph);
        m.put("encodeGraphMs", ms(t));
        m.put("encodedKb", encoded.length() >> 10);
        t = System.nanoTime();
        final String plan = Serializer.encodePlan(Plan.getInstance());
        m.put("encodePlanMs", ms(t));
        m.put("planKb", plan.length() >> 10);
        t = System.nanoTime();
        final String debug = Serializer.encodePlanDebug(Plan.getInstance());
        m.put("encodePlanDebugMs", ms(t));
        m.put("planDebugKb", debug.length() >> 10);
        t = System.nanoTime();
        PlanAPI.save();
        m.put("saveMs", ms(t));
        t = System.nanoTime();
        PlanAPI.recordEdit(graph, () -> {});
        m.put("noOpEditMs", ms(t));
        t = System.nanoTime();
        Serializer.decode(encoded);
        m.put("decodeGraphMs", ms(t));
        return m;
    }

    /**
     * Tiles the open plan: everything on the board copied {@code copies - 1} more times in a grid of {@code cols}
     * columns (0: square), pasted as one edit.
     */
    private static Map<String, Object> tile(final int copies, int cols) {
        final BoardScreen screen = DevBoard.screen();
        if (screen == null) throw new IllegalStateException("the planner is not open");
        final BoardSession session = screen.session();
        final Graph graph = session.graph();
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for (final Node n : graph.nodes.values()) {
            final RecipeCard card = screen.canvas()
                .cards()
                .get(n.id);
            final int h = card != null && card.layout() != null ? card.layout().height : 300;
            x0 = Math.min(x0, n.x);
            y0 = Math.min(y0, n.y);
            x1 = Math.max(x1, n.x + CardLayout.W);
            y1 = Math.max(y1, n.y + h);
        }
        for (final Drawer d : graph.drawers.values()) {
            x0 = Math.min(x0, d.getX());
            y0 = Math.min(y0, d.getY());
            x1 = Math.max(x1, d.getX() + 120);
            y1 = Math.max(y1, d.getY() + 120);
        }
        if (x0 == Integer.MAX_VALUE) throw new IllegalStateException("the plan is empty");
        if (cols <= 0) cols = (int) Math.ceil(Math.sqrt(copies));
        final int stepX = (x1 - x0) + 400, stepY = (y1 - y0) + 400;
        final long t = System.nanoTime();
        session.selectAll();
        session.copySelection();
        final List<int[]> origins = new ArrayList<>();
        for (int i = 1; i < copies; i++) origins.add(new int[] { x0 + (i % cols) * stepX, y0 + (i / cols) * stepY });
        session.pasteAt(origins);
        session.clearSelection();
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("pasteMs", ms(t));
        m.put("copies", copies);
        m.putAll(report());
        return m;
    }

    /**
     * Places every machine of the open plan in the world (its count rounded up, at most {@code max} a card) on a grid
     * east of the player, {@code spacing} blocks apart, {@code perRow} to a row; {@code world=clear} takes them all
     * out. One edit.
     */
    private static Map<String, Object> place(final Map<String, String> q) {
        final BoardScreen screen = DevBoard.screen();
        if (screen == null) throw new IllegalStateException("the planner is not open");
        final BoardSession session = screen.session();
        final Graph graph = session.graph();
        final long t = System.nanoTime();
        if ("clear".equals(q.get("world"))) {
            PlanAPI.recordEdit(graph, () -> { for (final Node n : graph.nodes.values()) n.worldLinks.clear(); });
            graph.touchLayout();
            PlanAPI.save();
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("clearMs", ms(t));
            m.putAll(report());
            return m;
        }
        final int spacing = Integer.parseInt(q.getOrDefault("spacing", "8"));
        final int max = Integer.parseInt(q.getOrDefault("max", "1000000"));
        final int total;
        {
            int count = 0;
            for (final CardModel model : session.models()
                .values()) count += Math.min(max, Math.max(1, (int) Math.ceil(model.machines)));
            total = count;
        }
        final int perRow = Integer
            .parseInt(q.getOrDefault("perRow", String.valueOf((int) Math.ceil(Math.sqrt(total)))));
        final Minecraft mc = Minecraft.getMinecraft();
        final int dim = mc.theWorld.provider.dimensionId;
        final int bx = MathHelper.floor_double(mc.thePlayer.posX) + 4,
            by = MathHelper.floor_double(mc.thePlayer.boundingBox.minY),
            bz = MathHelper.floor_double(mc.thePlayer.posZ) - (perRow * spacing) / 2;
        PlanAPI.recordEdit(graph, () -> {
            int i = 0;
            for (final CardModel model : session.models()
                .values()) {
                final Node n = model.node;
                n.worldLinks.clear();
                final int count = Math.min(max, Math.max(1, (int) Math.ceil(model.machines)));
                for (int k = 0; k < count; k++, i++) n.worldLinks
                    .add(new int[] { dim, bx + (i / perRow) * spacing, by, bz + (i % perRow) * spacing, 1 });
            }
        });
        graph.touchLayout();
        PlanAPI.save();
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("placeMs", ms(t));
        m.put("from", new int[] { bx, by, bz });
        m.put("rows", (total + perRow - 1) / perRow);
        m.put("perRow", perRow);
        m.putAll(report());
        return m;
    }

    /** Every frame: moves the camera as {@code animate} asked, so frame times are sampled under a steady motion. */
    static void frame() {
        if ("none".equals(motion)) return;
        final long now = System.nanoTime();
        final float dt = lastFrame == 0 ? 0 : (now - lastFrame) / 1e9f;
        lastFrame = now;
        final float age = (now - motionStart) / 1e9f;
        final Minecraft mc = Minecraft.getMinecraft();
        switch (motion) {
            case "yaw" -> {
                if (mc.thePlayer == null) return;
                mc.thePlayer.rotationYaw += 30 * speed * dt;
                mc.thePlayer.prevRotationYaw = mc.thePlayer.rotationYaw;
            }
            case "pan", "zoom" -> {
                final BoardScreen screen = DevBoard.screen();
                if (screen == null) return;
                final Graph graph = screen.session()
                    .graph();
                if (motion.equals("pan")) {
                    // A slow circle, so the view stays over the same part of the plan.
                    final double a = age * 0.6 * speed;
                    graph.setPanX(graph.getPanX() + (float) (Math.cos(a) * 60 * speed * dt));
                    graph.setPanY(graph.getPanY() + (float) (Math.sin(a) * 60 * speed * dt));
                } else graph.setZoom(baseZoom * (float) Math.pow(2, Math.sin(age * 0.8 * speed)));
            }
            default -> {}
        }
    }

    private static double ms(final long since) {
        return Math.round((System.nanoTime() - since) / 1e4) / 100.0;
    }

}
