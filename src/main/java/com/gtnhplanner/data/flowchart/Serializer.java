package com.gtnhplanner.data.flowchart;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.data.MachineConfig;
import com.gtnhplanner.data.MachineProfile;
import com.gtnhplanner.data.MachineProfileRegistry;
import com.gtnhplanner.data.SettingDef;
import com.gtnhplanner.data.flowchart.balancer.BalanceMode;

import codechicken.nei.recipe.Recipe;

public final class Serializer {

    public static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .enableComplexMapKeySerialization()
        .registerTypeAdapter(GraphData.class, new GraphDataAdapter())
        .create();

    // ── Public API ──

    /**
     * Encodes a full graph to a compressed base64 string (gzip + json + base64).
     */
    @Nonnull
    public static String encode(final Graph graph) {
        return encode(graph, true);
    }

    /**
     * {@link #encode(Graph)} for a plan code or a chat link: screenshots stay as things but carry no picture, which
     * would make the code huge (saves, undo and the account's copy keep them).
     */
    @Nonnull
    public static String encodeShared(final Graph graph) {
        return encode(graph, false);
    }

    @Nonnull
    private static String encode(final Graph graph, final boolean pictures) {
        try {
            final String json = GSON.toJson(graphToJson(graph, pictures));
            final ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (final GZIPOutputStream gzip = new GZIPOutputStream(baos);
                final OutputStreamWriter writer = new OutputStreamWriter(gzip, StandardCharsets.UTF_8)) {
                writer.write(json);
            }
            return Base64.getEncoder()
                .encodeToString(baos.toByteArray());
        } catch (final Exception e) {
            throw new RuntimeException("Failed to encode flowchart", e);
        }
    }

    /**
     * Decodes a compressed base64 string back to a Graph.
     */
    @Nonnull
    public static Graph decode(final String data) {
        try {
            final byte[] bytes = Base64.getDecoder()
                .decode(data);
            final ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
            final StringBuilder json = new StringBuilder();
            try (final GZIPInputStream gzip = new GZIPInputStream(bais);
                final InputStreamReader reader = new InputStreamReader(gzip, StandardCharsets.UTF_8)) {
                final char[] buf = new char[4096];
                int len;
                while ((len = reader.read(buf)) != -1) {
                    json.append(buf, 0, len);
                }
            }
            final JsonObject root = GSON.fromJson(json.toString(), JsonObject.class);
            return jsonToGraph(root);
        } catch (final Exception e) {
            throw new RuntimeException("Failed to decode flowchart", e);
        }
    }

    // ── Plan serialization ──

    /**
     * Encodes a Plan (with all its graphs) to a JSON string. Graph bodies are stored compressed,
     * each with its slot name and summary section folds.
     */
    @Nonnull
    public static String encodePlan(final Plan plan) {
        return encodePlan(plan, false);
    }

    /**
     * The same plan, but with each slot's data written as readable JSON instead of the compressed
     * base64 {@code encodePlan} uses - for debugging a save.
     */
    @Nonnull
    public static String encodePlanDebug(final Plan plan) {
        return encodePlan(plan, true);
    }

    @Nonnull
    private static String encodePlan(final Plan plan, final boolean debug) {
        return GSON.toJson(planTree(plan, debug));
    }

    /**
     * The plan as a JSON tree, each slot's data encoded (only the slots changed since they were last encoded are
     * encoded again): cheap to build on the game thread, and turned into text with {@link #GSON} on any thread, since
     * it shares nothing the game changes.
     */
    @Nonnull
    public static JsonObject planTree(final Plan plan) {
        return planTree(plan, false);
    }

    @Nonnull
    private static JsonObject planTree(final Plan plan, final boolean debug) {
        final JsonObject root = GSON.toJsonTree(plan, Plan.class)
            .getAsJsonObject();

        final JsonArray arr = new JsonArray();
        for (final Graph graph : plan.getGraphs()) {
            final JsonObject slotObj = new JsonObject();
            slotObj.addProperty("name", graph.getName());
            if (graph.getLastOpen() > 0) slotObj.addProperty("lastOpen", graph.getLastOpen());
            if (!graph.isOpen()) slotObj.addProperty("open", false);
            if (graph.getPostId() != null) slotObj.addProperty("postId", graph.getPostId());
            if (graph.getCloudKey() != null) slotObj.addProperty("cloudKey", graph.getCloudKey());
            if (graph.getLocalId() != null) slotObj.addProperty("localId", graph.getLocalId());
            if (!graph.getDescription()
                .isEmpty()) slotObj.addProperty("description", graph.getDescription());
            if (graph.getIcon() != null) slotObj.addProperty("icon", graph.getIcon());
            // A slot that could not be read is written back as it was, unless it has been used since.
            final boolean keep = graph.getUnreadable() != null && graph.nodes.isEmpty()
                && graph.getDrawers()
                    .isEmpty();
            if (keep) slotObj.addProperty("data", graph.getUnreadable());
            else if (debug) slotObj.add("data", graphToJson(graph));
            else slotObj.addProperty("data", encodeKept(graph));
            arr.add(slotObj);
        }
        root.add("graphs", arr);
        return root;
    }

    /** A graph's last encoding, and the version, name and camera it was made at. */
    private record Kept(long version, String name, float zoom, float panX, float panY, String data) {}

    /** Each graph's last encoding: a save encodes again only the graphs that changed since ({@link #encodeKept}). */
    private static final Map<Graph, Kept> KEPT = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * {@link #encode} of {@code graph}, made again only when the graph changed since it was last made: its version
     * (which every edit moves), its name or its camera (which move without one).
     */
    private static String encodeKept(final Graph graph) {
        final Kept kept = KEPT.get(graph);
        if (kept != null && kept.version() == graph.version()
            && kept.name()
                .equals(graph.getName())
            && kept.zoom() == graph.getZoom()
            && kept.panX() == graph.getPanX()
            && kept.panY() == graph.getPanY()) return kept.data();
        final String data = encode(graph);
        KEPT.put(
            graph,
            new Kept(graph.version(), graph.getName(), graph.getZoom(), graph.getPanX(), graph.getPanY(), data));
        return data;
    }

    /**
     * Decodes a Plan (with all its graphs) from a JSON string.
     */
    @Nonnull
    public static Plan decodePlan(final String json) {
        final Plan plan = GSON.fromJson(json, Plan.class);

        // graphs need to be decoded
        final JsonObject root = GSON.fromJson(json, JsonObject.class);
        if (root.has("graphs")) {
            for (final JsonElement elem : root.getAsJsonArray("graphs")) {
                final JsonObject obj = elem.getAsJsonObject();
                final String name = obj.has("name") ? obj.get("name")
                    .getAsString() : "";
                // One unreadable chart costs that chart, not the save; the empty graph keeps slot
                // numbering in place.
                Graph graph;
                try {
                    graph = decode(
                        obj.get("data")
                            .getAsString());
                } catch (final RuntimeException e) {
                    GtnhPlanner.LOG
                        .error("Slot '{}' could not be read; its data is kept and read again later", name, e);
                    graph = new Graph(name);
                    graph.setUnreadable(
                        obj.get("data")
                            .getAsString());
                }
                graph.setName(name);
                if (obj.has("open")) graph.setOpen(
                    obj.get("open")
                        .getAsBoolean());
                if (obj.has("lastOpen")) graph.setLastOpen(
                    obj.get("lastOpen")
                        .getAsLong());
                if (obj.has("postId")) graph.setPostId(
                    obj.get("postId")
                        .getAsString());
                if (obj.has("cloudKey")) graph.setCloudKey(
                    obj.get("cloudKey")
                        .getAsString());
                if (obj.has("localId")) graph.setLocalId(
                    obj.get("localId")
                        .getAsString());
                if (obj.has("description")) graph.setDescription(
                    obj.get("description")
                        .getAsString());
                if (obj.has("icon")) graph.setIcon(
                    obj.get("icon")
                        .getAsString());
                // What was read is the graph's encoding until it changes: the first save after loading encodes only
                // what has been edited since, not every plan.
                if (graph.getUnreadable() == null) KEPT.put(
                    graph,
                    new Kept(
                        graph.version(),
                        graph.getName(),
                        graph.getZoom(),
                        graph.getPanX(),
                        graph.getPanY(),
                        obj.get("data")
                            .getAsString()));
                plan.getGraphs()
                    .add(graph);
            }
        }

        return plan;
    }

    /**
     * Renders a graph as a Mermaid.js flowchart (LR layout).
     */
    @Nonnull
    public static String toMermaid(final Graph graph) {
        final StringBuilder sb = new StringBuilder();
        sb.append("flowchart LR\n");

        // Map UUIDs to short mermaid-safe IDs
        for (final Node node : graph.getNodes()) {
            final String id = mermaidId(node.id);
            final String label = node.machineName.isEmpty() ? "?" : node.machineName;
            sb.append("    ")
                .append(id)
                .append("[\"")
                .append(escapeMermaid(label))
                .append("\"]\n");
        }

        for (final Edge edge : graph.getEdges()) {
            final String srcId = mermaidId(edge.sourceNodeId);
            final String dstId = mermaidId(edge.targetNodeId);
            final String label = edgeLabel(graph, edge);
            sb.append("    ")
                .append(srcId)
                .append(" -->");
            if (!label.isEmpty()) {
                sb.append("|\"")
                    .append(escapeMermaid(label))
                    .append("\"|");
            }
            sb.append(" ")
                .append(dstId)
                .append("\n");
        }

        for (final Note note : graph.getNotes()) {
            sb.append("    %% Note: ");
            for (String s : note.getText()) sb.append(escapeMermaid(s))
                .append("\n");
        }

        return sb.toString();
    }

    /** A graph as its save holds it, uncompressed: what {@link #encode} compresses. */
    @Nonnull
    public static JsonObject toJson(final Graph graph) {
        return graphToJson(graph);
    }

    /** A graph from {@link #toJson}. */
    @Nonnull
    public static Graph fromJson(final JsonObject root) {
        return jsonToGraph(root);
    }

    @Nonnull
    private static JsonObject graphToJson(final Graph graph) {
        return graphToJson(graph, true);
    }

    @Nonnull
    private static JsonObject graphToJson(final Graph graph, final boolean pictures) {
        final JsonObject root = new JsonObject();

        root.addProperty(
            "balanceMode",
            graph.getBalanceMode()
                .name());
        root.addProperty("zoom", graph.getZoom());
        root.addProperty("panX", graph.getPanX());
        root.addProperty("panY", graph.getPanY());
        root.addProperty("name", graph.getName());
        if (!graph.settingPins.isEmpty()) root.add("settingPins", settingPinsToJson(graph));
        if (!graph.pinList.isEmpty()) {
            final JsonArray pinList = new JsonArray();
            // Cards since deleted are left out.
            for (final UUID id : graph.pinList)
                if (graph.nodes.containsKey(id)) pinList.add(new com.google.gson.JsonPrimitive(id.toString()));
            if (pinList.size() > 0) root.add("pinList", pinList);
        }
        if (!graph.spareShares.isEmpty()) root.add("spareShares", spareSharesToJson(graph));

        final JsonArray nodesArray = new JsonArray();
        for (final Node node : graph.getNodes()) nodesArray.add(nodeToJson(node));
        root.add("nodes", nodesArray);

        final JsonArray edgesArray = new JsonArray();
        for (final Edge edge : graph.getEdges()) edgesArray.add(edgeToJson(edge));
        root.add("edges", edgesArray);

        final JsonArray notesArray = new JsonArray();
        for (Note note : graph.getNotes()) notesArray.add(GSON.toJsonTree(note));
        root.add("notes", notesArray);

        final JsonArray groupsArray = new JsonArray();
        for (Group group : graph.getGroups()) groupsArray.add(GSON.toJsonTree(group));
        root.add("groups", groupsArray);

        // Written only when there are: an older build reads the plan as it always did (its groups are lost there).
        if (!graph.boardGroups.isEmpty()) {
            final JsonArray boardGroups = new JsonArray();
            for (final BoardGroup g : graph.boardGroups.values()) boardGroups.add(boardGroupToJson(g));
            root.add("boardGroups", boardGroups);
        }

        final JsonArray drawersArray = new JsonArray();
        for (final Drawer drawer : graph.getDrawers()) drawersArray.add(GSON.toJsonTree(drawer, Drawer.class));
        root.add("drawers", drawersArray);

        // The task board, as the shared plan spells it; absent when the plan has none.
        Tasks.write(graph, root, pictures);

        return root;
    }

    @Nonnull
    private static Graph jsonToGraph(final JsonObject root) {
        final Graph graph = new Graph(
            root.get("name")
                .getAsString());

        if (root.has("balanceMode")) {
            try {
                graph.setBalanceMode(
                    BalanceMode.valueOf(
                        root.get("balanceMode")
                            .getAsString()));
            } catch (final IllegalArgumentException ignored) {}
        }
        graph.setZoom(
            root.get("zoom")
                .getAsFloat());
        graph.setPanX(
            root.get("panX")
                .getAsFloat());
        graph.setPanY(
            root.get("panY")
                .getAsFloat());

        final JsonArray nodesArray = root.getAsJsonArray("nodes");
        for (final JsonElement elem : nodesArray) graph.addNode(jsonToNode(elem.getAsJsonObject()));

        final JsonArray edgesArray = root.getAsJsonArray("edges");
        final List<Edge> loadedEdges = new ArrayList<>(edgesArray.size());
        for (final JsonElement elem : edgesArray) loadedEdges.add(jsonToEdge(elem.getAsJsonObject()));
        graph.addEdgesLoaded(loadedEdges);

        for (final JsonElement elem : root.getAsJsonArray("notes")) {
            final Note note = GSON.fromJson(elem, Note.class);
            graph.notes.put(note.getId(), note);
        }

        for (final JsonElement elem : root.getAsJsonArray("groups")) {
            // Through GraphData, not Group: the type field decides whether this is a plain group or
            // a machine group, and reading it as Group would drop the machine group's own fields.
            final Group group = (Group) GSON.fromJson(elem, GraphData.class);
            graph.groups.put(group.getId(), group);
        }

        // Absent from saves written before pins existed; those show each machine's defaults.
        readSettingPins(root.get("settingPins"), graph);

        if (root.has("pinList") && root.get("pinList")
            .isJsonArray()) {
            for (final JsonElement id : root.getAsJsonArray("pinList")) {
                try {
                    graph.pinList.add(UUID.fromString(id.getAsString()));
                } catch (final IllegalArgumentException ignored) {}
            }
        }

        readSpareShares(root.get("spareShares"), graph);

        // Absent from saves written before drawers existed; those load with none.
        if (root.has("drawers")) {
            final List<Drawer> loadedDrawers = new ArrayList<>();
            for (final JsonElement elem : root.getAsJsonArray("drawers")) {
                final Drawer drawer = readDrawer(elem);
                if (drawer != null) loadedDrawers.add(drawer);
            }
            graph.addDrawersLoaded(loadedDrawers);
        }

        // Absent from saves written before task boards existed; those load with none.
        Tasks.read(root, graph);

        if (root.has("boardGroups") && root.get("boardGroups")
            .isJsonArray()) {
            for (final JsonElement elem : root.getAsJsonArray("boardGroups")) {
                final BoardGroup g = readBoardGroup(elem);
                if (g != null) graph.boardGroups.put(g.getId(), g);
            }
        }
        Groups.sanitize(graph);

        return graph;
    }

    /** One board group as the save writes it (under {@code boardGroups}). */
    @Nonnull
    public static JsonObject boardGroupToJson(final BoardGroup group) {
        return GSON.toJsonTree(group)
            .getAsJsonObject();
    }

    /** One board group as the save wrote it ({@link #boardGroupToJson}), or null when it is malformed. */
    @Nullable
    public static BoardGroup readBoardGroup(@Nullable final JsonElement saved) {
        if (saved == null || !saved.isJsonObject()) return null;
        try {
            final BoardGroup g = GSON.fromJson(saved, BoardGroup.class);
            return g != null && g.getId() != null ? g : null;
        } catch (final RuntimeException ignored) {
            // A malformed group is left out; its members stay on the board.
            return null;
        }
    }

    /** One node as the save writes it: its id, place, recipe, settings, pins and world links. */
    @Nonnull
    public static JsonObject nodeToJson(final Node node) {
        final JsonObject obj = new JsonObject();
        obj.addProperty("id", node.id.toString());
        obj.addProperty("x", node.x);
        obj.addProperty("y", node.y);
        obj.addProperty("machine", node.machineName);
        if (node.isPower()) {
            // A non-recipe machine: its source and settings; its ports are rebuilt from them on load.
            obj.addProperty("power", node.powerSource);
            final JsonObject settings = new JsonObject();
            for (final Map.Entry<String, String> s : node.powerSettings.entrySet())
                settings.addProperty(s.getKey(), s.getValue());
            obj.add("powerSettings", settings);
        }
        // Null-tolerant on both sides: an unresolved recipe must not make the slot
        // unsaveable, and downstream treats a null recipeId as "handler unavailable".
        if (node.recipeId != null) {
            obj.add("recipeId", node.recipeId.toJsonObject());
        }
        obj.addProperty("handlerRecipeIndex", node.handlerRecipeIndex);
        obj.addProperty("extractorIndex", node.getExtractorIndex());
        obj.addProperty("machineCount", node.machineConfig.getMachineCount());
        if (node.isMachineCountFixed()) {
            obj.addProperty("machineCountFixed", true);
        }
        if (node.group != null) obj.addProperty("group", node.group.toString());
        if (!node.worldLinks.isEmpty()) {
            final JsonArray links = new JsonArray();
            for (final int[] l : node.worldLinks) {
                final JsonArray at = new JsonArray();
                for (final int v : l) at.add(new com.google.gson.JsonPrimitive(v));
                links.add(at);
            }
            obj.add("worldLinks", links);
        }
        if (!node.targetOutputRates.isEmpty()) {
            final JsonObject targets = new JsonObject();
            for (final Map.Entry<Integer, Double> t : node.targetOutputRates.entrySet()) {
                targets.addProperty(String.valueOf(t.getKey()), t.getValue());
            }
            obj.add("targets", targets);
        }
        if (!node.targetInputRates.isEmpty()) {
            final JsonObject targets = new JsonObject();
            for (final Map.Entry<Integer, Double> t : node.targetInputRates.entrySet()) {
                targets.addProperty(String.valueOf(t.getKey()), t.getValue());
            }
            obj.add("inputTargets", targets);
        }
        if (!node.inputPicks.isEmpty()) {
            final JsonObject picks = new JsonObject();
            for (final Map.Entry<Integer, String> p : node.inputPicks.entrySet())
                picks.addProperty(String.valueOf(p.getKey()), p.getValue());
            obj.add("inputPicks", picks);
        }

        obj.add("inputs", portListToJson(node.inputs));
        obj.add("outputs", portListToJson(node.outputs));

        if (node.machineConfig.hasAnyBoost()) {
            obj.add("machineConfig", machineConfigToJson(node.machineConfig));
        }
        return obj;
    }

    /**
     * One node as the save wrote it ({@link #nodeToJson}), its ports read again from its recipe (or its power source)
     * and its saved chances, settings and multipliers put back: as a load reads it.
     */
    @Nonnull
    public static Node jsonToNode(final JsonObject obj) {
        final UUID id = UUID.fromString(
            obj.get("id")
                .getAsString());
        final int x = obj.get("x")
            .getAsInt();
        final int y = obj.get("y")
            .getAsInt();
        final Node node = new Node(id, x, y);
        node.machineName = obj.get("machine")
            .getAsString();
        if (obj.has("recipeId")) {
            node.recipeId = Recipe.RecipeId.of(
                obj.get("recipeId")
                    .getAsJsonObject());
        }
        node.handlerRecipeIndex = obj.has("handlerRecipeIndex") ? obj.get("handlerRecipeIndex")
            .getAsInt() : 0;
        if (obj.has("power")) {
            node.powerSource = obj.get("power")
                .getAsString();
            if (obj.has("powerSettings")) {
                for (final Map.Entry<String, JsonElement> s : obj.getAsJsonObject("powerSettings")
                    .entrySet()) {
                    node.powerSettings.put(
                        s.getKey(),
                        s.getValue()
                            .getAsString());
                }
            }
        } else node.initExtractor();
        // Before the refresh, which sets each input to the item picked for it.
        if (obj.has("inputPicks") && obj.get("inputPicks")
            .isJsonObject()) {
            for (final Map.Entry<String, JsonElement> p : obj.getAsJsonObject("inputPicks")
                .entrySet()) {
                try {
                    node.inputPicks.put(
                        Integer.parseInt(p.getKey()),
                        p.getValue()
                            .getAsString());
                } catch (final RuntimeException ignored) {
                    // A malformed entry loses one pick, not the chart.
                }
            }
        }
        node.refresh();

        if (obj.has("machineCount")) {
            node.machineConfig.setMachineCount(
                obj.get("machineCount")
                    .getAsInt());
        }
        node.setMachineCountFixed(
            obj.has("machineCountFixed") && obj.get("machineCountFixed")
                .getAsBoolean());
        if (obj.has("group")) {
            try {
                node.group = UUID.fromString(
                    obj.get("group")
                        .getAsString());
            } catch (final RuntimeException ignored) {
                // A malformed group leaves the card on the board.
            }
        }
        readWorldLinks(obj.get("worldLinks"), node);
        // Read independently of every other key.
        readRates(obj.get("inputTargets"), node.targetInputRates);
        readRates(obj.get("targets"), node.targetOutputRates);

        if (obj.has("inputs")) {
            applySavedPortChances(obj.getAsJsonArray("inputs"), node.inputs);
        }
        if (obj.has("outputs")) {
            applySavedPortChances(obj.getAsJsonArray("outputs"), node.outputs);
        }

        if (obj.has("machineConfig")) {
            jsonToMachineConfig(obj.getAsJsonObject("machineConfig"), node.machineConfig);
        }
        node.machineConfig.seedRouteDefaults();
        // The node was refreshed before its settings were read: what they decide comes now (a modelled
        // machine's multipliers and radio hatch input, machines/game/MachineModels).
        if (!node.isPower()) Node.afterRefresh.accept(node);
        return node;
    }

    /** A node's saved world links ({@link #nodeToJson}'s {@code worldLinks}) onto it; nothing when there are none. */
    public static void readWorldLinks(@Nullable final JsonElement saved, final Node node) {
        node.worldLinks.addAll(readLinks(saved));
    }

    /** Saved world links ({@link #nodeToJson}'s {@code worldLinks}); none when there are none. */
    public static List<int[]> readLinks(@Nullable final JsonElement saved) {
        final List<int[]> out = new ArrayList<>();
        if (saved == null || !saved.isJsonArray()) return out;
        for (final JsonElement l : saved.getAsJsonArray()) {
            final JsonArray at = l.getAsJsonArray();
            // {dimension, x, y, z}, then the facing and a structure's size when there are.
            if (at.size() < 4 || at.size() > 6) continue;
            final int[] link = new int[at.size()];
            for (int k = 0; k < link.length; k++) link[k] = at.get(k)
                .getAsInt();
            out.add(link);
        }
        return out;
    }

    /** Saved target rates by port index ({@link #nodeToJson}'s {@code targets} and {@code inputTargets}). */
    public static void readRates(@Nullable final JsonElement saved, final Map<Integer, Double> into) {
        if (saved == null || !saved.isJsonObject()) return;
        for (final Map.Entry<String, JsonElement> t : saved.getAsJsonObject()
            .entrySet()) {
            try {
                into.put(
                    Integer.parseInt(t.getKey()),
                    t.getValue()
                        .getAsDouble());
            } catch (final NumberFormatException ignored) {
                // A malformed key loses one target, not the chart.
            }
        }
    }

    /** One wire as the save writes it. */
    @Nonnull
    public static JsonObject edgeToJson(final Edge edge) {
        final JsonObject obj = new JsonObject();
        obj.addProperty("id", edge.id.toString());
        obj.addProperty("src", edge.sourceNodeId.toString());
        obj.addProperty("dst", edge.targetNodeId.toString());
        obj.addProperty("srcOut", edge.sourceOutputIndex);
        obj.addProperty("dstIn", edge.targetInputIndex);
        return obj;
    }

    /** One wire as the save wrote it ({@link #edgeToJson}). */
    @Nonnull
    public static Edge jsonToEdge(final JsonObject obj) {
        final UUID id = UUID.fromString(
            obj.get("id")
                .getAsString());
        final UUID src = UUID.fromString(
            obj.get("src")
                .getAsString());
        final UUID dst = UUID.fromString(
            obj.get("dst")
                .getAsString());
        final int srcOut = obj.get("srcOut")
            .getAsInt();
        final int dstIn = obj.get("dstIn")
            .getAsInt();
        return new Edge(id, src, dst, srcOut, dstIn);
    }

    /** The settings each machine's cards show, as the save writes them ({@link Graph#settingPins}). */
    @Nonnull
    public static JsonObject settingPinsToJson(final Graph graph) {
        final JsonObject pins = new JsonObject();
        for (final java.util.Map.Entry<String, java.util.List<String>> e : graph.settingPins.entrySet()) {
            final JsonArray keys = new JsonArray();
            for (final String k : e.getValue()) keys.add(new com.google.gson.JsonPrimitive(k));
            pins.add(e.getKey(), keys);
        }
        return pins;
    }

    /** Reads {@link #settingPinsToJson} onto the graph; nothing when it is not there. */
    public static void readSettingPins(@Nullable final JsonElement saved, final Graph graph) {
        if (saved == null || !saved.isJsonObject()) return;
        for (final java.util.Map.Entry<String, JsonElement> e : saved.getAsJsonObject()
            .entrySet()) {
            if (!e.getValue()
                .isJsonArray()) continue;
            final java.util.List<String> keys = new java.util.ArrayList<>();
            for (final JsonElement k : e.getValue()
                .getAsJsonArray()) keys.add(k.getAsString());
            graph.settingPins.put(e.getKey(), keys);
        }
    }

    /** How each leftover is split, as the save writes it ({@link Graph#spareShares}). */
    @Nonnull
    public static JsonObject spareSharesToJson(final Graph graph) {
        final JsonObject spares = new JsonObject();
        for (final java.util.Map.Entry<String, java.util.Map<String, Double>> e : graph.spareShares.entrySet()) {
            final JsonObject shares = new JsonObject();
            for (final java.util.Map.Entry<String, Double> s : e.getValue()
                .entrySet()) shares.addProperty(s.getKey(), s.getValue());
            spares.add(e.getKey(), shares);
        }
        return spares;
    }

    /** Reads {@link #spareSharesToJson} onto the graph; nothing when it is not there. */
    public static void readSpareShares(@Nullable final JsonElement saved, final Graph graph) {
        if (saved == null || !saved.isJsonObject()) return;
        for (final java.util.Map.Entry<String, JsonElement> e : saved.getAsJsonObject()
            .entrySet()) {
            if (!e.getValue()
                .isJsonObject()) continue;
            final java.util.Map<String, Double> shares = new java.util.TreeMap<>();
            for (final java.util.Map.Entry<String, JsonElement> s : e.getValue()
                .getAsJsonObject()
                .entrySet()) {
                try {
                    shares.put(
                        s.getKey(),
                        s.getValue()
                            .getAsDouble());
                } catch (final RuntimeException ignored) {}
            }
            if (!shares.isEmpty()) graph.spareShares.put(e.getKey(), shares);
        }
    }

    /** One saved drawer, repaired; null when the entry is not a drawer at all (no id). */
    @Nullable
    public static Drawer readDrawer(final JsonElement elem) {
        final Drawer drawer = GSON.fromJson(elem, Drawer.class);
        if (drawer == null || drawer.getId() == null) return null;
        drawer.sanitize();
        return drawer;
    }

    // ── Port helpers ──

    @Nonnull
    private static JsonArray portListToJson(final List<Port<?>> ports) {
        final JsonArray arr = new JsonArray();
        for (final Port<?> port : ports) {
            final JsonObject obj = new JsonObject();
            obj.addProperty(
                "type",
                port.getType()
                    .getKey());
            obj.addProperty("chance", port.getChance());
            arr.add(obj);
        }
        return arr;
    }

    private static void applySavedPortChances(final JsonArray arr, final List<Port<?>> ports) {
        for (int i = 0; i < arr.size() && i < ports.size(); i++) {
            final JsonObject obj = arr.get(i)
                .getAsJsonObject();
            final String savedType = obj.has("type") ? obj.get("type")
                .getAsString() : "item";
            if (savedType.equals(
                ports.get(i)
                    .getType()
                    .getKey())) {
                ports.get(i)
                    .setChance(
                        obj.get("chance")
                            .getAsFloat());
            }
        }
    }

    // ── Machine config ──

    @Nonnull
    private static JsonObject machineConfigToJson(final MachineConfig cfg) {
        final JsonObject obj = new JsonObject();
        final MachineProfile profile = cfg.getProfile();

        if (!MachineProfileRegistry.defaultId()
            .equals(cfg.profileId)) {
            obj.addProperty("profile", cfg.profileId);
        }

        final JsonObject settingsObj = new JsonObject();
        for (final SettingDef<?> def : profile.settings()) {
            final Object val = cfg.settings.get(def.key);
            if (val == null) continue;
            if (val.equals(def.defaultValue)) continue;
            if (val instanceof final Boolean b) settingsObj.addProperty(def.key, b);
            else if (val instanceof final Integer i) settingsObj.addProperty(def.key, i);
            else if (val instanceof final String s) settingsObj.addProperty(def.key, s);
        }
        // A GregTech machine's own settings from the website's machine maths: its casings, pipes, modes and counts,
        // under the website's ids (machines/game/WebCards).
        for (final Map.Entry<String, Object> e : cfg.settings.entrySet()) if (e.getKey()
            .startsWith(MACHINE_SETTING) && e.getValue() != null)
            settingsObj.addProperty(
                e.getKey(),
                String.valueOf(e.getValue()));
        if (!settingsObj.entrySet()
            .isEmpty()) obj.add("settings", settingsObj);

        if (!cfg.inputConsumption.isEmpty()) {
            obj.add("inMul", multiplierArrayToJson(cfg.inputConsumption));
        }
        if (!cfg.outputProductivity.isEmpty()) {
            obj.add("outMul", multiplierArrayToJson(cfg.outputProductivity));
        }

        return obj;
    }

    private static void jsonToMachineConfig(final JsonObject obj, final MachineConfig cfg) {
        // "profile" is omitted for the default profile, but its settings are still written.
        if (obj.has("profile")) {
            cfg.profileId = obj.get("profile")
                .getAsString();
        }
        if (obj.has("settings")) readSettings(obj.getAsJsonObject("settings"), cfg.settings);

        migrateCoil(cfg);

        if (obj.has("inMul")) {
            jsonToMultiplierArray(obj.getAsJsonArray("inMul"), cfg.inputConsumption);
        }
        if (obj.has("outMul")) {
            jsonToMultiplierArray(obj.getAsJsonArray("outMul"), cfg.outputProductivity);
        }
    }

    /** Saved machine settings ({@code machineConfig.settings}) by key, as the types they were saved as. */
    public static void readSettings(final JsonObject saved, final Map<String, Object> into) {
        for (final Map.Entry<String, JsonElement> entry : saved.entrySet()) {
            final JsonElement el = entry.getValue();
            if (el.isJsonPrimitive()) {
                final var prim = el.getAsJsonPrimitive();
                if (prim.isBoolean()) into.put(entry.getKey(), prim.getAsBoolean());
                else if (prim.isNumber()) into.put(entry.getKey(), prim.getAsInt());
                else into.put(entry.getKey(), prim.getAsString());
            }
        }
    }

    /** A GregTech machine's own setting from the website's machine maths: {@code machine:<id>}. */
    private static final String MACHINE_SETTING = "machine:";

    /**
     * Plans from before the website's machine maths kept a coil as its heat ({@code machine_heat}): it becomes the
     * hottest coil at or under that heat, by the website's key ({@code coil}).
     */
    private static void migrateCoil(final MachineConfig cfg) {
        if (cfg.settings.containsKey("coil") || !(cfg.settings.get("machine_heat") instanceof final Number heat)
            || heat.intValue() <= 0) return;
        String key = null;
        for (final com.gtnhplanner.machines.web.HandlerData.Coil coil : com.gtnhplanner.machines.web.HandlerData
            .coils()) if (coil.heat() <= heat.intValue()) key = coil.key();
        if (key != null) cfg.settings.put("coil", key);
        cfg.settings.remove("machine_heat");
    }

    // ── Multiplier helpers ──

    @Nonnull
    private static JsonArray multiplierArrayToJson(final Map<Integer, Float> map) {
        final JsonArray arr = new JsonArray();
        for (final Map.Entry<Integer, Float> e : map.entrySet()) {
            final JsonObject entry = new JsonObject();
            entry.addProperty("index", e.getKey());
            entry.addProperty("multiplier", e.getValue());
            arr.add(entry);
        }
        return arr;
    }

    private static void jsonToMultiplierArray(final JsonArray arr, final Map<Integer, Float> out) {
        for (final JsonElement elem : arr) {
            final JsonObject entry = elem.getAsJsonObject();
            out.put(
                entry.get("index")
                    .getAsInt(),
                entry.get("multiplier")
                    .getAsFloat());
        }
    }

    // ── Mermaid helpers ──

    @Nonnull
    private static String mermaidId(final UUID uuid) {
        return "n" + uuid.toString()
            .replace("-", "")
            .substring(0, 8);
    }

    @Nonnull
    private static String escapeMermaid(final String s) {
        return s.replace("\"", "#quot;")
            .replace("\n", "<br/>");
    }

    @Nonnull
    private static String edgeLabel(final Graph graph, final Edge edge) {
        final Node src = graph.nodes.get(edge.sourceNodeId);
        if (src == null) return "";

        final int idx = edge.sourceOutputIndex;
        if (idx >= 0 && idx < src.outputs.size()) {
            final Port port = src.outputs.get(idx);
            return port.getDisplayName();
        }
        return "";
    }
}
