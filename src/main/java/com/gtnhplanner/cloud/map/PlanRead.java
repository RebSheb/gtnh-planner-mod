package com.gtnhplanner.cloud.map;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhplanner.cloud.sync.LiveJson;
import com.gtnhplanner.data.Settings;
import com.gtnhplanner.data.flowchart.BoardGroup;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.GraphData;
import com.gtnhplanner.data.flowchart.Group;
import com.gtnhplanner.data.flowchart.Groups;
import com.gtnhplanner.data.flowchart.MachineGroup;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.data.flowchart.Serializer;
import com.gtnhplanner.data.flowchart.Tasks;
import com.gtnhplanner.data.flowchart.balancer.BalanceMode;
import com.gtnhplanner.importer.FfCards;
import com.gtnhplanner.importer.FfConverter;
import com.gtnhplanner.importer.FfDrawers;
import com.gtnhplanner.importer.FfHandle;
import com.gtnhplanner.importer.FfPlan;
import com.gtnhplanner.importer.FfPlan.FfEdge;
import com.gtnhplanner.importer.FfPlan.FfNode;
import com.gtnhplanner.importer.FfPlan.FfRecipe;
import com.gtnhplanner.importer.FfPlan.FfSection;
import com.gtnhplanner.importer.FfPlan.FfSlot;
import com.gtnhplanner.importer.FfPlan.FfStorage;
import com.gtnhplanner.importer.FfPlanParser;
import com.gtnhplanner.importer.FfSettings;
import com.gtnhplanner.importer.ImportReport;
import com.gtnhplanner.importer.ImportReport.Kind;
import com.gtnhplanner.importer.NodeMaker;
import com.gtnhplanner.library.PlanExport;

/**
 * A shared plan read onto a board ({@link CloudPlans#read}). Each thing whose game data still matches its website
 * fields (its {@code sum}) is the game's own again, exactly, at the place the plan gives it; anything the website made
 * or changed beyond its place is built the importer's way ({@link FfCards}) under its own id, keeping from its game
 * data what the website has no place for. What the game has no place for at all is left out of the board and kept
 * in the plan untouched. The website's boards (pockets) are the board's groups: each thing sits in the group its
 * {@code pocketId} names, at its position in that group's frame.
 */
final class PlanRead {

    /** One recipe of a card on the board: what its wires find their ports by. */
    private final class Section {

        private final Node node;
        /** A card taken over from the previous board: its JSON, read only if a wire needs its recipe. */
        @Nullable
        private final JsonObject card;
        @Nullable
        private FfRecipe recipe;
        @Nullable
        private Map<Integer, FfSlot> overrides;

        Section(final Node node, @Nullable final FfRecipe recipe, final Map<Integer, FfSlot> overrides) {
            this.node = node;
            this.card = null;
            this.recipe = recipe;
            this.overrides = overrides;
        }

        Section(final Node node, final JsonObject card) {
            this.node = node;
            this.card = card;
        }

        Node node() {
            return node;
        }

        @Nullable
        FfRecipe recipe() {
            read();
            return recipe;
        }

        Map<Integer, FfSlot> overrides() {
            read();
            return overrides;
        }

        private void read() {
            if (overrides != null) return;
            final FfNode n = card == null ? null : things.node(card);
            overrides = n == null ? Map.of() : n.overrides();
            recipe = n == null ? null : PlanRead.this.recipe(n.recipeId());
        }
    }

    private final JsonObject plan;
    private final CloudGame game;
    private final FfPlanParser.Things things;
    private final ImportReport report = new ImportReport();
    private final FfCards.Context ctx;
    private final FfCards.Ports ports;
    private final Graph graph;
    private final Basis basis = new Basis();
    /** Recipes read so far, by id (null for one that does not read): each is read when a card first needs it. */
    private final Map<String, FfRecipe> recipes = new HashMap<>();
    /** The cards on the board by plan id, each its recipes by section. */
    private final Map<String, Map<Integer, Section>> cards = new HashMap<>();
    private final Map<String, Drawer> drawers = new HashMap<>();
    private final Map<String, FfStorage> storages = new HashMap<>();
    /** Drawers the website made, by plan id: their resource key and name come from the first port they hold. */
    private final Map<String, Drawer> unkeyed = new LinkedHashMap<>();
    /** The groups on the board by their pocket's plan id. */
    private final Map<String, BoardGroup> groupsByPlan = new HashMap<>();
    /** Whether the plan's website fields are the ones its game data was written with. */
    private boolean metaMatches;
    /** Where each pocket's members are measured from on the board, by plan id (worked out on first ask). */
    private final Map<String, int[]> bases = new HashMap<>();

    /** The read this one follows, or null; its plan's things by key and its recipes by id. */
    @Nullable
    private final CloudPlans.Read previous;
    private final Map<String, JsonObject> before = new HashMap<>(), beforeRecipes = new HashMap<>();
    /** This plan's recipes by id, as JSON. */
    private final Map<String, JsonObject> recipeJson = new HashMap<>();
    /** Things that are the previous plan's very objects (a wire's ends too): what they were read as still holds. */
    private final Set<String> same = new HashSet<>();
    /** Board things taken over from the previous board, by game key: nothing of them is worked out again. */
    private final Set<String> kept = new HashSet<>();
    /** The wires and drawer links read, put on the board together once all are read. */
    private final List<Edge> newWires = new ArrayList<>();
    private final List<Graph.LoadedLink> newLinks = new ArrayList<>();

    PlanRead(final JsonObject plan, final CloudGame game, @Nullable final CloudPlans.Read previous) {
        this.plan = plan;
        this.game = game;
        this.previous = previous;
        this.things = new FfPlanParser.Things(plan);
        this.ctx = new FfCards.Context(game.recipes(), game.maker(), things.solveMode, report);
        this.ports = new FfCards.Ports(game.maker());
        final String name = str(plan, "name");
        this.graph = new Graph(name == null ? "" : name);
        if (previous != null) {
            for (final String c : List.of("nodes", "storages", "edges", "annotations", "pockets")) {
                for (final JsonObject o : objects(previous.plan(), c)) {
                    final String id = str(o, "id");
                    if (id != null) before.put(Basis.key(c, id), o);
                }
            }
            for (final JsonObject r : objects(previous.plan(), "recipes")) {
                final String id = str(r, "id");
                if (id != null) beforeRecipes.putIfAbsent(id, r);
            }
        }
    }

    /** Whether a thing of this plan is the previous plan's very object. */
    private boolean unchanged(final String c, final String id, final JsonObject json) {
        return previous != null && before.get(Basis.key(c, id)) == json;
    }

    /**
     * Whether a thing's website fields are still the ones its game data was written with: told by the previous read
     * when it is the same object (a sum is a hash of the whole thing), else worked out. Remembered for the next read.
     */
    private boolean matches(final Ext ext, final JsonObject json) {
        final Boolean known = previous == null ? null : previous.basis().sums.get(json);
        final boolean m = known != null ? known : ext.matches(json);
        basis.sums.put(json, m);
        return m;
    }

    /** The plan's recipe by id, read on first ask; null when it has none or it does not read. */
    @Nullable
    private FfRecipe recipe(@Nullable final String id) {
        if (id == null) return null;
        if (recipes.containsKey(id)) return recipes.get(id);
        final JsonObject json = recipeJson.get(id);
        final FfRecipe r = json == null ? null : things.recipe(json);
        recipes.put(id, r);
        return r;
    }

    CloudPlans.Read run() {
        final JsonObject data = meta();
        for (final JsonObject r : objects(plan, "recipes")) {
            final String id = str(r, "id");
            if (id != null) recipeJson.putIfAbsent(id, r);
        }
        // The groups first: every thing's place is measured from its group's frame.
        pockets();
        for (final JsonObject n : objects(plan, "nodes")) node(n);
        final List<FfEdge> wires = new ArrayList<>();
        final List<JsonObject> wireJson = new ArrayList<>();
        for (final JsonObject e : objects(plan, "edges")) {
            final FfEdge edge = things.edge(e);
            if (edge == null) continue;
            wires.add(edge);
            wireJson.add(e);
        }
        final List<FfStorage> all = new ArrayList<>();
        for (final JsonObject s : objects(plan, "storages")) {
            final FfStorage storage = things.storage(s);
            if (storage != null) all.add(storage);
        }
        final Map<String, FfDrawers.Role> roles = FfDrawers.roles(
            new FfPlan(
                graph.getName(),
                things.solveMode,
                things.poolMode,
                things.planMode,
                List.of(),
                List.of(),
                wires,
                all,
                List.of(),
                null,
                List.of()));
        final Set<String> storageIds = new HashSet<>();
        for (final FfStorage s : all) storageIds.add(s.id());
        for (final JsonObject s : objects(plan, "storages")) storage(s, roles, wires, storageIds);
        for (int i = 0; i < wires.size(); i++) edge(wireJson.get(i), wires.get(i));
        // All at once: one by one, each wire and link looks through every other.
        graph.addEdgesLoaded(newWires);
        graph.linkDrawersLoaded(newLinks);
        // A drawer the website made that none of its wires reached is not on the board, as the importer leaves it.
        for (final Map.Entry<String, Drawer> d : unkeyed.entrySet()) {
            graph.removeDrawer(
                d.getValue()
                    .getId());
            drawers.remove(d.getKey());
            basis.byGame.remove(
                "drawer:" + d.getValue()
                    .getId());
            report.add(Kind.DROPPED, d.getKey(), "a drawer with no wires left in GTNH Planner; left out");
        }
        for (final JsonObject a : objects(plan, "annotations")) note(a);
        groups(data);
        picks(data);
        // As a load repairs groups: a group inside itself is lifted onto the board.
        Groups.sanitize(graph);
        tasks();
        if (previous != null) {
            sameThings();
            // A note the previous plan read as this same note is that note still. Wires and links are worked out
            // again (cheaply): which port a wire finds, and whether a later one takes it, hangs on more than its ends.
            for (final Map.Entry<String, String> m : basis.byGame.entrySet()) if (same.contains(m.getValue())
                && m.getKey()
                    .startsWith("note:")
                && m.getValue()
                    .equals(
                        previous.basis()
                            .thingOf(m.getKey())))
                kept.add(m.getKey());
        }
        basis.settle(Fresh.of(graph, game, basis, kept), previous == null ? null : previous.basis(), same, kept);
        final Fresh.Meta meta = Fresh.meta(graph, game, basis);
        basis.metaState = Canon.of(meta.game());
        basis.metaSite = meta.site();
        return new CloudPlans.Read(graph, basis, report, plan);
    }

    /**
     * The drawers, wires and notes that are the previous plan's very objects (a wire's two ends too, which name what
     * it carries): what they were read as still holds while the board writes them the same.
     */
    private void sameThings() {
        for (final String c : List.of("storages", "annotations", "pockets")) {
            for (final JsonObject o : objects(plan, c)) {
                final String id = str(o, "id");
                if (id != null && unchanged(c, id, o)) same.add(Basis.key(c, id));
            }
        }
        final Map<String, JsonObject> ends = new HashMap<>();
        for (final String c : List.of("nodes", "storages")) for (final JsonObject o : objects(plan, c)) {
            final String id = str(o, "id");
            if (id != null) ends.put(id, o);
        }
        for (final JsonObject e : objects(plan, "edges")) {
            final String id = str(e, "id"), source = str(e, "source"), target = str(e, "target");
            if (id == null || source == null || target == null || !unchanged("edges", id, e)) continue;
            if (sameEnd(source, ends) && sameEnd(target, ends)) same.add(Basis.key("edges", id));
        }
    }

    /** A wire's end: a drawer that is the previous plan's object, or a card taken over from the previous board. */
    private boolean sameEnd(final String id, final Map<String, JsonObject> ends) {
        final JsonObject now = ends.get(id);
        return now != null
            && (before.get(Basis.key("storages", id)) == now || kept.contains("node:" + PlanIds.uuid(id)));
    }

    // region The plan

    /** The plan's own fields and its game data (returned, empty when it has none). */
    private JsonObject meta() {
        final String description = str(plan, "description");
        graph.setDescription(description == null ? "" : description);
        final String view = str(plan, "primaryView");
        graph.setPrimaryView(Tasks.PLAN.equals(view) || Tasks.TASKS.equals(view) ? view : null);
        final Ext ext = Ext.of(plan);
        final JsonObject data = ext == null ? new JsonObject() : ext.game();
        if (data.has("balanceMode")) {
            try {
                graph.setBalanceMode(BalanceMode.valueOf(str(data, "balanceMode")));
            } catch (final RuntimeException ignored) {}
        }
        metaMatches = ext != null && ext.sum()
            .equals(metaSum(plan));
        // The icon by the key the game wrote, while the website's icon is still the one written with it.
        final JsonElement icon = plan.get("icon");
        if (metaMatches && str(data, "icon") != null) graph.setIcon(str(data, "icon"));
        else if (icon != null && icon.isJsonObject()) {
            final String kind = str(icon.getAsJsonObject(), "kind"), id = str(icon.getAsJsonObject(), "resourceId");
            if (kind != null && id != null) graph.setIcon(game.key(kind, id));
        }
        return data;
    }

    /**
     * The website fields the plan's game data is summed over: its icon, its setting pins and its leftover splits, the
     * plan fields the game keeps in its own spelling too.
     */
    static String metaSum(final JsonObject plan) {
        final JsonObject o = new JsonObject();
        for (final String f : List.of("icon", "settingPins", "spareShares")) if (plan.has(f)) o.add(f, plan.get(f));
        return Canon.sum(o);
    }

    /**
     * The setting pins and leftover splits, once the cards are on the board (the website names a machine by its
     * cards' handler): the game's own while the website's fields are the ones written with them; else the website's,
     * with the pins only the game can spell kept.
     */
    private void picks(final JsonObject data) {
        if (metaMatches) {
            Serializer.readSettingPins(data.get("settingPins"), graph);
            Serializer.readSpareShares(data.get("spareShares"), graph);
            return;
        }
        final Graph had = new Graph("");
        Serializer.readSettingPins(data.get("settingPins"), had);
        PlanPins.readPins(plan.get("settingPins"), had.settingPins, graph, game);
        PlanPins.readShares(plan.get("spareShares"), graph);
    }

    /**
     * The plan's groups and shared machines, as a load reads them: one that names a card no longer on the board is
     * put right by the board, as after a load.
     */
    private void groups(final JsonObject data) {
        final JsonElement saved = data.get("groups");
        if (saved == null || !saved.isJsonArray()) return;
        for (final JsonElement e : saved.getAsJsonArray()) {
            final Group group;
            try {
                group = (Group) Serializer.GSON.fromJson(e, GraphData.class);
            } catch (final RuntimeException x) {
                continue;
            }
            if (group == null || group.getId() == null) continue;
            graph.groups.put(group.getId(), group);
        }
    }

    /** The task board. */
    private void tasks() {
        for (final JsonObject o : objects(plan, "tasks")) {
            final Tasks.Task t = Tasks.task(o);
            if (t == null) continue;
            graph.tasks.add(t);
            basis.map("tasks", t.id(), "task:" + t.id());
        }
        for (final JsonObject o : objects(plan, "taskItems")) {
            final Tasks.Item i = Tasks.item(o);
            if (i == null) continue;
            graph.taskItems.add(i);
            basis.map("taskItems", i.id(), "item:" + i.id());
        }
        for (final JsonObject o : objects(plan, "taskComments")) {
            final Tasks.Comment c = Tasks.comment(o);
            if (c == null) continue;
            graph.taskComments.add(c);
            basis.map("taskComments", c.id(), "comment:" + c.id());
        }
    }

    // endregion

    // region Cards

    private void node(final JsonObject json) {
        final String id = str(json, "id");
        if (id == null) return;
        final UUID uuid = PlanIds.uuid(id);
        final Node was = keptCard(json, id, uuid);
        final int[] at = place(json);
        final UUID group = group(json);
        if (was != null) {
            // Its own JSON is the same, but its group's frame may have moved.
            was.x = at[0];
            was.y = at[1];
            was.group = group;
            graph.addNode(was);
            basis.map("nodes", id, "node:" + uuid);
            kept.add("node:" + uuid);
            same.add(Basis.key("nodes", id));
            final Map<Integer, Section> sections = new HashMap<>();
            sections.put(0, new Section(was, json));
            cards.put(id, sections);
            return;
        }
        final FfNode card = things.node(json);
        if (card == null) return;
        final int x = at[0], y = at[1];
        final FfRecipe recipe = recipe(card.recipeId());
        final Ext ext = Ext.of(json);
        // A shared machine the website made is always its recipes the importer's way: its game data is its first's.
        final Node own = ext != null && matches(ext, json)
            && card.extraRecipes()
                .isEmpty() ? fromGame(ext.game(), uuid, x, y) : null;
        // A recipe this game's NEI does not know by its id (another pack version) reads with no ports: the importer
        // may still find it by what the plan says of it.
        final boolean portless = own != null && !own.isPower() && own.inputs.isEmpty() && own.outputs.isEmpty();
        final Map<Integer, Section> made = own == null || portless ? importCard(card, recipe, id, uuid, x, y)
            : Map.of();
        if (own != null && made.isEmpty()) {
            own.group = group;
            graph.addNode(own);
            basis.map("nodes", id, "node:" + uuid);
            final Map<Integer, Section> sections = new HashMap<>();
            sections.put(0, new Section(own, recipe, card.overrides()));
            cards.put(id, sections);
            return;
        }
        if (made.isEmpty()) return;
        final Node first = made.get(0)
            .node();
        if (ext != null) keepGameOnly(first, ext.game(), game);
        // Every recipe of a shared machine is in its card's group.
        for (final Section s : made.values()) {
            s.node().group = group;
            graph.addNode(s.node());
        }
        basis.map("nodes", id, "node:" + uuid);
        cards.put(id, made);
        if (made.size() > 1) {
            // A shared machine the website made: its later recipes and their group come with the card.
            final List<Node> nodes = new ArrayList<>();
            for (int i = 0; made.containsKey(i); i++) nodes.add(
                made.get(i)
                    .node());
            final MachineGroup g = FfCards.share(nodes, new MachineGroup(PlanIds.part(id, "shared")));
            graph.groups.put(g.getId(), g);
            basis.carry("group:" + g.getId(), "nodes", id);
            for (final Section s : made.values())
                if (s.node() != first) basis.carry("node:" + s.node().id, "nodes", id);
        }
    }

    /**
     * The previous board's card for a card that is the previous plan's very object, its recipe too, and was one card
     * there (not a shared machine the website made); else null.
     */
    @Nullable
    private Node keptCard(final JsonObject json, final String id, final UUID uuid) {
        if (previous == null || !unchanged("nodes", id, json)) return null;
        final JsonElement extra = json.get("extraRecipes");
        if (extra != null && extra.isJsonArray()
            && extra.getAsJsonArray()
                .size() > 0)
            return null;
        final String recipeId = str(json, "recipeId");
        if (recipeId != null && recipeJson.get(recipeId) != beforeRecipes.get(recipeId)) return null;
        final String gameKey = "node:" + uuid;
        if (!Basis.key("nodes", id)
            .equals(
                previous.basis()
                    .thingOf(gameKey))
            || previous.basis()
                .isCarried(gameKey))
            return null;
        return previous.graph().nodes.get(uuid);
    }

    /** A card from its game data, as a load reads it; null when that does not read. */
    @Nullable
    private Node fromGame(final JsonObject data, final UUID uuid, final int x, final int y) {
        final JsonObject saved = LiveJson.copy(data);
        saved.addProperty("id", uuid.toString());
        saved.addProperty("x", x);
        saved.addProperty("y", y);
        try {
            final Node node = Serializer.jsonToNode(saved);
            Fresh.readWorlds(data, node, game.world());
            game.rebuilt(node);
            return node;
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /**
     * A card the importer's way, its recipes by section (none when the game has no place for it): the first takes the
     * card's id, a shared machine's later ones ids made from it.
     */
    private Map<Integer, Section> importCard(final FfNode card, @Nullable final FfRecipe recipe, final String id,
        final UUID uuid, final int x, final int y) {
        final Map<Integer, Section> made = new HashMap<>();
        if (recipe == null) {
            report.add(Kind.UNMATCHED, card.recipeId(), "the plan does not carry this card's recipe");
            return made;
        }
        Node single = null;
        if (recipe.isCustomRate()) single = customRate(card, recipe, x, y);
        else if (!card.enabled()) report.add(Kind.DROPPED, recipe.name(), "a switched-off card; left out");
        else if (recipe.power()) single = FfCards.power(ctx, card, recipe, x, y);
        else if (recipe.isPlannerTank()) single = FfCards.tank(ctx, card, recipe, x, y);
        if (single != null) {
            single.id = uuid;
            made.put(0, new Section(single, recipe, card.overrides()));
            return made;
        }
        if (recipe.isCustomRate() || !card.enabled() || recipe.power()) return made;
        final List<FfSection> sections = new ArrayList<>();
        sections.add(new FfSection(card.recipeId(), card.overrides()));
        sections.addAll(card.extraRecipes());
        for (int i = 0; i < sections.size(); i++) {
            final FfRecipe r = recipe(
                sections.get(i)
                    .recipeId());
            if (r == null) {
                report.add(
                    Kind.UNMATCHED,
                    sections.get(i)
                        .recipeId(),
                    "the plan does not carry this recipe");
                continue;
            }
            final Node node = FfCards.section(
                ctx,
                ports,
                card,
                r,
                sections.get(i)
                    .overrides(),
                i,
                r.name(),
                x,
                y);
            // The card is its first recipe: without it the game has no place for the card.
            if (node == null && i == 0) return made;
            if (node == null) continue;
            node.id = i == 0 ? uuid : PlanIds.part(id, "r" + i);
            made.put(
                i,
                new Section(
                    node,
                    r,
                    sections.get(i)
                        .overrides()));
        }
        return made;
    }

    /** A custom rate card as one, holding the resource its recipe names; null when the game builds none. */
    @Nullable
    private Node customRate(final FfNode card, final FfRecipe recipe, final int x, final int y) {
        if (!card.enabled()) {
            report.add(Kind.DROPPED, "Custom rate card", "a switched-off card; left out");
            return null;
        }
        final boolean supply = !recipe.outputs()
            .isEmpty();
        final FfSlot slot = supply ? recipe.outputs()
            .getFirst()
            : recipe.inputs()
                .isEmpty() ? null
                    : recipe.inputs()
                        .getFirst();
        if (slot == null) return null;
        final String key = game.key(slot.kind(), slot.id());
        if (key == null) return null;
        final double perSecond = card.customRatePerSecond() != null ? card.customRatePerSecond() : slot.amount();
        final Node node = game.maker()
            .makeCustomRate(FfCards.customRateSettings(supply, perSecond, key));
        if (node == null) return null;
        node.x = x;
        node.y = y;
        final Double count = things.solveMode ? card.solvePin() : Double.valueOf(card.machineCount());
        if (count != null && count > 0) {
            node.machineConfig.setMachineCount((int) Math.max(1, Math.round(count)));
            node.setMachineCountFixed(true);
        }
        return node;
    }

    /**
     * What a card's game data has that the website has no place for, onto a card built from the website's fields:
     * its placements by world, the rates of ports the website has no row for (the rest are its port rates), an
     * unpinned card's count, and the machine settings the website does not keep.
     */
    private static void keepGameOnly(final Node node, final JsonObject data, final CloudGame game) {
        Fresh.readWorlds(data, node, game.world());
        final Map<Integer, Double> ins = new LinkedHashMap<>(), outs = new LinkedHashMap<>();
        Serializer.readRates(data.get("inputTargets"), ins);
        Serializer.readRates(data.get("targets"), outs);
        keepUnnamed(node.targetInputRates, node.inputs, ins, game);
        keepUnnamed(node.targetOutputRates, node.outputs, outs, game);
        final JsonElement count = data.get("machineCount");
        if (!node.isMachineCountFixed() && !data.has("machineCountFixed")
            && count != null
            && count.isJsonPrimitive()
            && count.getAsJsonPrimitive()
                .isNumber())
            node.machineConfig.setMachineCount(Math.max(1, count.getAsInt()));
        final JsonElement cfg = data.get("machineConfig");
        if (cfg == null || !cfg.isJsonObject()
            || !cfg.getAsJsonObject()
                .has("settings"))
            return;
        final Map<String, Object> saved = new LinkedHashMap<>();
        Serializer.readSettings(
            cfg.getAsJsonObject()
                .getAsJsonObject("settings"),
            saved);
        // The website does not keep these, so it cannot have changed them: the game's stand. The count is the card's.
        for (final Map.Entry<String, Object> s : saved.entrySet()) {
            if (!FfSettings.carried(s.getKey()) && !Settings.MACHINES.key()
                .equals(s.getKey())) node.machineConfig.settings.put(s.getKey(), s.getValue());
        }
    }

    /**
     * The game's rates of the ports the website has no row for, kept: a port the export cannot name, or a second port
     * of one resource (the website's port rates are by resource). The rest are the website's to set.
     */
    private static void keepUnnamed(final Map<Integer, Double> on,
        final List<com.gtnhplanner.data.flowchart.Port<?>> ports, final Map<Integer, Double> had,
        final CloudGame game) {
        final Set<String> seen = new HashSet<>();
        for (int i = 0; i < ports.size(); i++) {
            final com.gtnhplanner.data.flowchart.Port<?> p = ports.get(i);
            final PlanExport.Res r = p.fromModel ? null
                : p.getValue() instanceof com.gtnhplanner.power.Energy ? PlanExport.EU : game.port(p);
            final boolean named = r != null && seen.add(r.kind() + ":" + r.id());
            if (!named && had.containsKey(i) && !on.containsKey(i)) on.put(i, had.get(i));
        }
    }

    // endregion

    // region Drawers and wires

    private void storage(final JsonObject json, final Map<String, FfDrawers.Role> roles, final List<FfEdge> wires,
        final Set<String> storageIds) {
        final String id = str(json, "id");
        final FfStorage s = id == null ? null : things.storage(json);
        if (s == null) return;
        final UUID uuid = PlanIds.uuid(id);
        FfDrawers.Role role = roles.getOrDefault(id, FfDrawers.Role.IDLE);
        // A buffer wired to another drawer or by a cell wire has no drawer on the board (the importer joins its wires
        // instead): it stays in the plan as it is, the game's own or not.
        if (role == FfDrawers.Role.BUFFER && !FfDrawers.keepsBuffer(id, wires, storageIds)) {
            report.add(Kind.DROPPED, id, "a buffer drawer wired to another drawer or by a cell wire; left in the plan");
            return;
        }
        final int[] at = place(json);
        final int x = at[0], y = at[1];
        final Ext ext = Ext.of(json);
        final Drawer had = ext == null ? null : drawerFromGame(ext.game(), uuid, x, y);
        Drawer d = had != null && matches(ext, json) ? had : null;
        if (d == null) {
            Drawer.Kind kind = FfDrawers.kindOf(role);
            // A drawer of the game's with no wires is still the kind it was.
            if (kind == null && had != null && role == FfDrawers.Role.IDLE) {
                kind = had.getKind();
                role = kind == Drawer.Kind.SOURCE ? FfDrawers.Role.SOURCE
                    : kind == Drawer.Kind.TRASH ? FfDrawers.Role.TRASH : FfDrawers.Role.PRODUCT;
            }
            if (kind == null) {
                report.add(Kind.DROPPED, id, "an unwired drawer; left in the plan");
                return;
            }
            // The game's own key while the website still names the same resource; else the first port it holds gives
            // it.
            final boolean same = had != null && sameResource(had, s);
            final String key = same ? had.getResourceKey() : game.key(s.kind(), s.resourceId());
            d = new Drawer(uuid, kind, key == null ? "" : key);
            d.setLabel(s.displayName() == null ? "" : s.displayName());
            if (had != null) d.setHeader(had.getHeader());
            d.setX(x);
            d.setY(y);
            // A buffer's rule is live only in a one-mode plan, where a board enforced it.
            final FfDrawers.Target target = FfDrawers
                .target(s, role, role == FfDrawers.Role.BUFFER ? things.planMode : things.solveMode);
            d.setTarget(target.rule(), target.rate());
            if (!same) unkeyed.put(id, d);
        }
        d.setGroup(group(json));
        graph.addDrawer(d);
        drawers.put(id, d);
        storages.put(id, s);
        basis.map("storages", id, "drawer:" + uuid);
    }

    /** Whether the website's storage names the resource the game's drawer holds. */
    private boolean sameResource(final Drawer d, final FfStorage s) {
        final PlanExport.Res r = Fresh.resource(d, game);
        return r.kind()
            .equals(s.kind()) && com.gtnhplanner.importer.FfIds.same(r.id(), s.resourceId());
    }

    @Nullable
    private static Drawer drawerFromGame(final JsonObject data, final UUID uuid, final int x, final int y) {
        final JsonObject saved = LiveJson.copy(data);
        saved.addProperty("id", uuid.toString());
        saved.addProperty("x", x);
        saved.addProperty("y", y);
        saved.add("links", new JsonArray());
        saved.add("takers", new JsonArray());
        try {
            return Serializer.readDrawer(saved);
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /**
     * A wire: between two cards a wire on the board, between a drawer and a card one of the drawer's links. Its game
     * data gives its ports while it matches; else, as the importer does, the ports holding its resource.
     */
    private void edge(final JsonObject json, final FfEdge e) {
        final String id = str(json, "id");
        if (id == null) return;
        final Map<Integer, Section> from = cards.get(e.source()), to = cards.get(e.target());
        final Drawer feeds = drawers.get(e.source()), takes = drawers.get(e.target());
        final Ext ext = Ext.of(json);
        if (ext != null && matches(ext, json) && fromGame(id, e, ext.game(), from, to, feeds, takes)) return;
        if (e.crossForm()) {
            report
                .add(Kind.WIRE, e.resourceId(), "a loose cell wire left in the plan; GTNH Planner needs a tank there");
            return;
        }
        final Section a = from == null ? null : from.get(FfHandle.sectionOf(e.sourceHandle())),
            b = to == null ? null : to.get(FfHandle.sectionOf(e.targetHandle()));
        if (a != null && b != null) {
            final int out = port(a, true, e, null), in = port(b, false, e, null);
            if (out < 0 || in < 0) report.add(Kind.WIRE, e.resourceId(), "a wire that found no port; left in the plan");
            else wire(id, a.node(), b.node(), out, in);
            return;
        }
        // A drawer's link, on the side the wire says: from the drawer it feeds a card's input (a source, or a buffer's
        // user), into it a card's output feeds it (a product, trash, or a buffer's maker). Trash feeds nothing.
        final boolean feeding = feeds != null && b != null && feeds.takesSide(true);
        final boolean taking = !feeding && takes != null && a != null && takes.takesSide(false);
        if (!feeding && !taking) return;
        final String drawerId = feeding ? e.source() : e.target();
        final Section card = feeding ? b : a;
        final int port = port(
            card,
            !feeding,
            e,
            storages.get(drawerId)
                .resourceId());
        if (port >= 0) link(id, drawerId, feeding ? feeds : takes, card.node(), port, feeding);
    }

    /** The port of a card a wire's end names (its handle, its resource, a drawer's resource); -1 when none. */
    private int port(final Section s, final boolean output, final FfEdge e, @Nullable final String drawerResource) {
        return FfCards.port(
            ports,
            s.node(),
            s.recipe(),
            s.overrides(),
            output,
            output ? e.sourceHandle() : e.targetHandle(),
            e,
            drawerResource);
    }

    /** A wire by the ports its game data names; false when they are not on the board. */
    private boolean fromGame(final String id, final FfEdge e, final JsonObject data,
        @Nullable final Map<Integer, Section> from, @Nullable final Map<Integer, Section> to,
        @Nullable final Drawer feeds, @Nullable final Drawer takes) {
        final Node a = from == null || !from.containsKey(0) ? null
            : from.get(0)
                .node(),
            b = to == null || !to.containsKey(0) ? null
                : to.get(0)
                    .node();
        if (a != null && b != null) {
            final int out = integer(data, "srcOut"), in = integer(data, "dstIn");
            if (out < 0 || out >= a.outputs.size() || in < 0 || in >= b.inputs.size()) return false;
            wire(id, a, b, out, in);
            return true;
        }
        final int port = integer(data, "port");
        if (feeds != null && b != null && feeds.takesSide(true) && port >= 0 && port < b.inputs.size()) {
            link(id, e.source(), feeds, b, port, true);
            return true;
        }
        if (takes != null && a != null && takes.takesSide(false) && port >= 0 && port < a.outputs.size()) {
            link(id, e.target(), takes, a, port, false);
            return true;
        }
        return false;
    }

    private void wire(final String id, final Node from, final Node to, final int out, final int in) {
        final UUID uuid = PlanIds.uuid(id);
        newWires.add(new Edge(uuid, from.id, to.id, out, in));
        basis.map("edges", id, "edge:" + uuid);
    }

    /**
     * A drawer's link to a port on its side ({@code input}: an input the drawer feeds); a drawer the website made takes
     * its resource from the first port it holds.
     */
    private void link(final String id, @Nullable final String drawerId, final Drawer d, final Node node, final int port,
        final boolean input) {
        final Drawer.Link link = new Drawer.Link(node.id, port);
        newLinks.add(new Graph.LoadedLink(d.getId(), link, input));
        basis.map("edges", id, Fresh.linkKey(d, link, input));
        if (drawerId != null && unkeyed.remove(drawerId) != null) {
            final NodeMaker.PortInfo info = ports.describe(node, !input)
                .get(port);
            d.setResourceKey(info.key());
            if (d.getLabel()
                .isEmpty()) d.setLabel(info.label());
        }
    }

    // endregion

    // region Notes

    /** An annotation as a note: a sticky note, or a box, zone, arrow or picture (kept, never shown). */
    private void note(final JsonObject json) {
        final String id = str(json, "id");
        if (id == null) return;
        final UUID uuid = PlanIds.uuid(id);
        final int[] at = place(json);
        final int x = at[0], y = at[1];
        final Ext ext = Ext.of(json);
        Note note = null;
        if (ext != null && matches(ext, json)) {
            final JsonObject saved = LiveJson.copy(ext.game());
            saved.addProperty("id", uuid.toString());
            saved.addProperty("x", x);
            saved.addProperty("y", y);
            try {
                note = Serializer.GSON.fromJson(saved, Note.class);
            } catch (final RuntimeException ignored) {}
        }
        if (note == null) {
            final FfPlan.FfNote n = things.note(json);
            if (n == null) return;
            note = FfConverter.note(n, uuid, x, y);
        }
        note.setGroup(group(json));
        graph.notes.put(uuid, note);
        basis.map("annotations", id, "note:" + uuid);
    }

    // endregion

    // region Groups and places

    /**
     * The website's boards (pockets) as the board's groups, before anything is put in them: one whose game data still
     * matches is the game's own group again; any other is built from the website's fields, keeping the website's id to
     * post under, and the size the game had while the website's is the one the game wrote. Then each takes its place,
     * nested in the pocket its {@code parentPocketId} names.
     */
    private void pockets() {
        final List<JsonObject> list = objects(plan, "pockets");
        for (final JsonObject json : list) {
            final String id = str(json, "id");
            if (id == null || groupsByPlan.containsKey(id)) continue;
            final FfPlan.FfBoard b = things.board(json);
            if (b == null) continue;
            final UUID uuid = PlanIds.uuid(id);
            final Ext ext = Ext.of(json);
            BoardGroup g = ext != null && matches(ext, json) ? groupFromGame(ext.game(), uuid) : null;
            if (g == null) {
                g = FfConverter.group(
                    b,
                    uuid,
                    0,
                    0,
                    b.name()
                        .isBlank() ? "Group" : b.name());
                if (!uuid.toString()
                    .equals(id)) g.setFfId(id);
                final BoardGroup was = ext == null ? null : groupFromGame(ext.game(), uuid);
                if (was != null) keepGameOnly(g, was, json);
            }
            graph.boardGroups.put(uuid, g);
            groupsByPlan.put(id, g);
            basis.map("pockets", id, "pocket:" + uuid);
        }
        for (final JsonObject json : list) {
            final String id = str(json, "id");
            final BoardGroup g = id == null ? null : groupsByPlan.get(id);
            if (g == null) continue;
            final BoardGroup parent = groupsByPlan.get(str(json, Fresh.PARENT_POCKET));
            g.setParent(parent == null || parent == g ? null : parent.getId());
            final int[] at = place(json);
            g.setX(at[0]);
            g.setY(at[1]);
        }
    }

    /** A group from its game data, as a load reads it; null when that does not read. */
    @Nullable
    private static BoardGroup groupFromGame(final JsonObject data, final UUID uuid) {
        final JsonObject saved = LiveJson.copy(data);
        saved.addProperty("id", uuid.toString());
        return Serializer.readBoardGroup(saved);
    }

    /**
     * What a group's game data has that the website's board does not, onto a group built from the website's fields:
     * the website id it was imported with, and its exact size while the website's is still the one the game wrote
     * for it (the website keeps sizes to whole cells).
     */
    private void keepGameOnly(final BoardGroup g, final BoardGroup was, final JsonObject json) {
        if (g.getFfId() == null) g.setFfId(was.getFfId());
        if (LiveJson.equal(
            PlanExport.pocket(graph, was)
                .get("size"),
            json.get("size"))) {
            g.setWidth(was.getWidth());
            g.setHeight(was.getHeight());
        }
    }

    /** The group a thing is in: the one its pocket ({@code pocketId}) became; null on the board. */
    @Nullable
    private UUID group(final JsonObject json) {
        final BoardGroup g = groupsByPlan.get(str(json, Fresh.POCKET));
        return g == null ? null : g.getId();
    }

    /**
     * Where a thing (or a pocket's frame) is on the board: its position, at the board's scale, from where the pocket it
     * sits in has its members ({@link #base}). A game's own pocket has them at its frame's corner, so a thing the game
     * wrote comes back to the pixel.
     */
    private int[] place(final JsonObject json) {
        final JsonElement p = json.get("position");
        double x = 0, y = 0;
        if (p != null && p.isJsonObject()) {
            x = number(p.getAsJsonObject(), "x");
            y = number(p.getAsJsonObject(), "y");
        }
        final String in = str(json, json.has(Fresh.PARENT_POCKET) ? Fresh.PARENT_POCKET : Fresh.POCKET);
        final int[] base = base(in, new HashSet<>());
        return new int[] { base[0] + PlanExport.gameX(x), base[1] + PlanExport.gameY(y) };
    }

    /**
     * Where a pocket's members are measured from on the board: the pocket it sits in's, plus its own corner (for a
     * board the website never opened, no size, the shift the website gives its members when it opens it); the board's
     * corner for none, an unknown pocket, or one inside itself.
     */
    private int[] base(@Nullable final String pocketId, final Set<String> seen) {
        if (pocketId == null || !seen.add(pocketId)) return new int[] { 0, 0 };
        final int[] known = bases.get(pocketId);
        if (known != null) return known;
        final double[] shift = things.shift(pocketId);
        if (shift == null) return new int[] { 0, 0 };
        final int[] parent = base(things.parentOf(pocketId), seen);
        final int[] at = { parent[0] + PlanExport.gameX(shift[0]), parent[1] + PlanExport.gameY(shift[1]) };
        bases.put(pocketId, at);
        return at;
    }

    // endregion

    // region The game data a thing carries

    /**
     * A thing's {@code gtnhplanner}: the game's own data for it and the sum of the website fields it was written with.
     */
    record Ext(JsonObject game, String sum) {

        @Nullable
        static Ext of(final JsonObject thing) {
            final JsonElement e = thing.get("gtnhplanner");
            if (e == null || !e.isJsonObject()) return null;
            final JsonObject o = e.getAsJsonObject();
            final JsonElement v = o.get("v"), game = o.get("game"), sum = o.get("sum");
            if (v == null || !v.isJsonPrimitive()
                || !v.getAsJsonPrimitive()
                    .isNumber()
                || v.getAsInt() != 1) return null;
            if (game == null || !game.isJsonObject() || sum == null || !sum.isJsonPrimitive()) return null;
            return new Ext(game.getAsJsonObject(), sum.getAsString());
        }

        /** Whether the thing's website fields are still the ones its game data was written with. */
        boolean matches(final JsonObject thing) {
            return sum.equals(Fresh.sum(thing));
        }
    }

    // endregion

    // region Lenient JSON

    static List<JsonObject> objects(final JsonObject o, final String key) {
        final List<JsonObject> out = new ArrayList<>();
        final JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray()) return out;
        for (final JsonElement x : e.getAsJsonArray()) if (x != null && x.isJsonObject()) out.add(x.getAsJsonObject());
        return out;
    }

    @Nullable
    static String str(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive()
            && e.getAsJsonPrimitive()
                .isString() ? e.getAsString() : null;
    }

    private static int integer(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive()
            && e.getAsJsonPrimitive()
                .isNumber() ? e.getAsInt() : -1;
    }

    private static double number(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive()
            && e.getAsJsonPrimitive()
                .isNumber() ? e.getAsDouble() : 0;
    }

    // endregion
}
