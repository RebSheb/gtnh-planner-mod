package com.gtnhplanner.importer;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.gtnhplanner.importer.FfPlan.FfEdge;
import com.gtnhplanner.importer.FfPlan.FfHandler;
import com.gtnhplanner.importer.FfPlan.FfNode;
import com.gtnhplanner.importer.FfPlan.FfNote;
import com.gtnhplanner.importer.FfPlan.FfRecipe;
import com.gtnhplanner.importer.FfPlan.FfSection;
import com.gtnhplanner.importer.FfPlan.FfSlot;
import com.gtnhplanner.importer.FfPlan.FfStorage;
import com.gtnhplanner.importer.FfPlan.FfTarget;

/**
 * Reads FF's plan JSON (FF's types.ts and schemas.ts) leniently: unknown keys are ignored, a missing field takes
 * FF's default, and an entry too broken to use is skipped rather than failing the plan. Then does the parts of FF's
 * load funnel (project-normalize.ts) that change what the plan means: pool mode implies solve mode, the old "OpV"
 * tier is UXV, legacy trash cans become trash drawers, and positions are flattened out of boards (flatten-boards.ts),
 * which come along as {@link FfPlan.FfBoard}s.
 */
public final class FfPlanParser {

    private static final Gson GSON = new Gson();
    /** FF's size for a new text note (board-grid.ts), for a note whose size is missing. */
    private static final double NOTE_WIDTH = 240, NOTE_HEIGHT = 80;
    /** FF's annotation kinds other than text; an unknown kind is counted as a drawing. */
    private static final List<String> DRAWINGS = List.of("box", "arrow", "zone", "image");

    private FfPlanParser() {}

    /** The plan in FF's JSON. */
    public static FfPlan parse(final String json) {
        final JsonObject root;
        try {
            root = GSON.fromJson(json, JsonObject.class);
        } catch (final RuntimeException e) {
            throw new FfImportException("That is not a plan: the JSON does not read.", e);
        }
        if (root == null) throw new FfImportException("That is not a plan: it is empty.");
        if (!root.has("recipes") || !root.has("nodes"))
            throw new FfImportException("That is not a plan: it has no recipes or cards.");
        return read(root);
    }

    static FfPlan read(final JsonObject root) {
        final List<String> notes = new ArrayList<>();
        final Double schema = num(root, "schemaVersion");
        if (schema != null && schema != 1) notes.add("Plan format " + fmt(schema) + " read as format 1.");

        final boolean pool = bool(root, "poolMode", false);
        final boolean solve = bool(root, "solveMode", false) || pool;
        final boolean planMode = bool(root, "planMode", false) && !pool;

        final Map<String, Board> boards = boards(root);
        final List<FfPlan.FfBoard> ffBoards = new ArrayList<>();
        for (final JsonObject p : objects(root, "pockets")) {
            final FfPlan.FfBoard board = board(p, boards);
            if (board != null) ffBoards.add(board);
        }
        final Map<String, String> boardOf = new HashMap<>();
        for (final String list : List.of("nodes", "storages", "annotations"))
            for (final JsonObject m : objects(root, list)) {
                final String id = str(m, "id"), in = str(m, "pocketId");
                if (id != null && in != null && boards.containsKey(in)) boardOf.put(id, in);
            }

        final List<FfRecipe> recipes = new ArrayList<>();
        for (final JsonObject r : objects(root, "recipes")) {
            final FfRecipe recipe = recipe(r);
            if (recipe != null) recipes.add(recipe);
        }
        final List<FfNode> nodes = new ArrayList<>();
        for (final JsonObject n : objects(root, "nodes")) {
            final FfNode node = node(n, boards);
            if (node != null) nodes.add(node);
        }
        final List<FfEdge> edges = new ArrayList<>();
        for (final JsonObject e : objects(root, "edges")) {
            final FfEdge edge = edge(e);
            if (edge != null) edges.add(edge);
        }
        final List<FfStorage> storages = new ArrayList<>();
        for (final JsonObject s : objects(root, "storages")) {
            final FfStorage storage = storage(s, boards);
            if (storage != null) storages.add(storage);
        }

        // Every annotation is a board note: text as a sticky note, the others as drawings.
        final List<FfNote> textNotes = new ArrayList<>();
        for (final JsonObject a : objects(root, "annotations")) {
            final String kind = str(a, "kind", "");
            if ("text".equals(kind)) textNotes.add(note(a, boards));
            else if (DRAWINGS.contains(kind)) textNotes.add(drawing(a, kind, boards));
            else notes.add("An annotation of an unknown kind left out.");
        }
        if (pool && (root.has("productionGroups") || root.has("poolResourceRules")))
            notes.add("Pool scopes (production groups and share/import rules) left out; every resource is one pool.");

        final JsonObject target = obj(root, "targetRate");
        final FfTarget targetRate = target == null ? null : target(target);

        final String name = str(root, "name", "Imported plan");
        final FfPlan plan = new FfPlan(
            name,
            solve,
            pool,
            planMode,
            recipes,
            nodes,
            edges,
            storages,
            textNotes,
            targetRate,
            notes,
            ffBoards,
            boardOf);
        return migrateTrashCans(plan);
    }

    /**
     * A plan read one thing at a time, for a reader that keeps each thing's own id (the cloud plans): its boards are
     * known, so every position comes out flattened as {@link #parse} gives it. Nothing else of the load funnel runs:
     * a legacy trash can stays a card.
     */
    public static final class Things {

        private final Map<String, Board> boards;
        public final boolean solveMode, poolMode, planMode;

        public Things(final JsonObject root) {
            this.boards = boards(root);
            this.poolMode = bool(root, "poolMode", false);
            this.solveMode = bool(root, "solveMode", false) || poolMode;
            this.planMode = bool(root, "planMode", false) && !poolMode;
        }

        @Nullable
        public FfRecipe recipe(final JsonObject r) {
            return FfPlanParser.recipe(r);
        }

        @Nullable
        public FfNode node(final JsonObject n) {
            return FfPlanParser.node(n, boards);
        }

        @Nullable
        public FfStorage storage(final JsonObject s) {
            return FfPlanParser.storage(s, boards);
        }

        @Nullable
        public FfEdge edge(final JsonObject e) {
            return FfPlanParser.edge(e);
        }

        /** An annotation as a note: text as a sticky note, a box, zone, arrow or image as a drawing; else null. */
        @Nullable
        public FfNote note(final JsonObject a) {
            final String kind = str(a, "kind", "");
            if ("text".equals(kind)) return FfPlanParser.note(a, boards);
            return DRAWINGS.contains(kind) ? FfPlanParser.drawing(a, kind, boards) : null;
        }

        /** A board (FF's pocket); null without an id. */
        @Nullable
        public FfPlan.FfBoard board(final JsonObject p) {
            return FfPlanParser.board(p, boards);
        }

        /**
         * What a board adds to its own members' positions, its parent's left out: its frame's corner, or for a board
         * that never stood open (no size) the shift the website gives its members when it first opens; null for no
         * such board.
         */
        @Nullable
        public double[] shift(@Nullable final String boardId) {
            final Board b = boardId == null ? null : boards.get(boardId);
            return b == null ? null : new double[] { b.x(), b.y() };
        }

        /** The board a board sits on; null on the plan's own board or for no such board. */
        @Nullable
        public String parentOf(@Nullable final String boardId) {
            final Board b = boardId == null ? null : boards.get(boardId);
            return b == null ? null : b.parent();
        }

        /** Where a thing on a board is drawn from: what the board adds to its position (none off any board). */
        public double[] offset(@Nullable final String boardId) {
            return absolute(new double[] { 0, 0 }, boardId, boards);
        }
    }

    /**
     * The plan's boards (FF's pockets) by id: what each adds to its members' positions. Members of a board that never
     * stood open (no size) are in a space of their own: the website moves their box a cell inside the frame, under its
     * title, when it first opens, and so does this.
     */
    private static Map<String, Board> boards(final JsonObject root) {
        final Map<String, double[]> ownSpace = new HashMap<>();
        for (final String list : List.of("nodes", "storages", "annotations"))
            for (final JsonObject m : objects(root, list)) {
                final String in = str(m, "pocketId");
                if (in == null) continue;
                final double[] at = position(m);
                ownSpace.merge(in, at, (a, b) -> new double[] { Math.min(a[0], b[0]), Math.min(a[1], b[1]) });
            }
        final Map<String, Board> boards = new HashMap<>();
        for (final JsonObject p : objects(root, "pockets")) {
            final String id = str(p, "id");
            if (id == null) continue;
            final double[] at = position(p);
            final double[] own = p.has("size") ? null : ownSpace.get(id);
            final double dx = own == null ? at[0] : at[0] + BOARD_PAD - own[0];
            final double dy = own == null ? at[1] : at[1] + BOARD_TITLE + BOARD_PAD - own[1];
            boards.put(id, new Board(str(p, "parentPocketId"), dx, dy));
        }
        return boards;
    }

    /** One board (FF's pocket), its frame's corner flattened as its members' positions are. */
    @Nullable
    private static FfPlan.FfBoard board(final JsonObject p, final Map<String, Board> boards) {
        final String id = str(p, "id");
        if (id == null) return null;
        final double[] at = absolute(position(p), str(p, "parentPocketId"), boards);
        final JsonObject size = obj(p, "size");
        return new FfPlan.FfBoard(
            id,
            str(p, "name", ""),
            str(p, "colorTag"),
            str(p, "parentPocketId"),
            at[0],
            at[1],
            size == null ? null : num(size, "width"),
            size == null ? null : num(size, "height"),
            bool(p, "expanded", false),
            str(p, "theme"),
            str(p, "pattern"));
    }

    // region Entries

    @Nullable
    private static FfRecipe recipe(final JsonObject r) {
        final String id = str(r, "id");
        if (id == null) return null;
        final JsonObject meta = obj(r, "metadata");
        final JsonObject source = obj(r, "source");
        Double special = num(r, "specialValue");
        if (special == null && meta != null) special = num(meta, "specialValue");
        final List<FfSlot> inputs = new ArrayList<>();
        for (final JsonObject s : objects(r, "inputs")) {
            final FfSlot slot = slot(s, true);
            if (slot != null) inputs.add(slot);
        }
        final List<FfSlot> outputs = new ArrayList<>();
        for (final JsonObject s : objects(r, "outputs")) {
            final FfSlot slot = slot(s, false);
            if (slot != null) outputs.add(slot);
        }
        final List<FfHandler> handlers = new ArrayList<>();
        for (final JsonObject h : objects(r, "machineHandlers")) {
            final String hid = str(h, "id");
            if (hid == null) continue;
            handlers.add(
                new FfHandler(
                    hid,
                    str(h, "label", str(h, "machineType", hid)),
                    str(h, "kind", ""),
                    bool(h, "perfectOverclock", false)));
        }
        final Double duration = num(r, "durationTicks");
        final Double eut = num(r, "eut");
        return new FfRecipe(
            id,
            str(r, "name", id),
            str(r, "kind", ""),
            str(r, "category", ""),
            str(r, "machineType", ""),
            str(r, "minimumTier", ""),
            duration == null ? 0 : (int) Math.round(duration),
            eut == null ? 0 : eut,
            special,
            str(r, "programmedCircuit"),
            inputs,
            outputs,
            handlers,
            meta == null ? null : str(meta, "recipeMapId"),
            source == null ? null : str(source, "rawRecipeId"),
            obj(r, "power") == null ? null : str(obj(r, "power"), "sourceId", ""),
            meta == null || obj(meta, "neiRecipe") == null ? null : obj(meta, "neiRecipe").toString());
    }

    @Nullable
    private static FfSlot slot(final JsonObject s, final boolean input) {
        final String id = str(s, "id");
        if (id == null || id.isEmpty()) return null;
        final Double amount = num(s, "amount");
        final Double chance = num(s, "chance");
        final List<String> alternatives = new ArrayList<>();
        for (final JsonObject a : objects(s, "alternatives")) {
            final String alt = str(a, "id");
            if (alt != null && !alt.isEmpty()) alternatives.add(alt);
        }
        return new FfSlot(
            str(s, "kind", "item"),
            id,
            amount == null || amount <= 0 ? 1 : amount,
            chance == null ? 1 : chance,
            !input || bool(s, "consumed", true),
            alternatives,
            str(s, "displayName", id));
    }

    @Nullable
    private static FfNode node(final JsonObject n, final Map<String, Board> boards) {
        final String id = str(n, "id"), recipeId = str(n, "recipeId");
        if (id == null || recipeId == null) return null;
        final double[] at = absolute(position(n), str(n, "pocketId"), boards);
        final Double count = num(n, "machineCount");
        final Double parallel = num(n, "parallel");
        final Double hatches = num(n, "energyHatches");
        String tier = str(n, "overclockTier", "");
        if ("OpV".equals(tier)) tier = "UXV";

        final Map<String, String> configTiers = new LinkedHashMap<>();
        final JsonObject config = obj(n, "machineConfigTiers");
        if (config != null) for (final Map.Entry<String, JsonElement> e : config.entrySet()) {
            final String v = asString(e.getValue());
            if (v != null) configTiers.put(e.getKey(), v);
        }
        final List<FfSection> extras = new ArrayList<>();
        for (final JsonObject x : objects(n, "extraRecipes")) {
            final String rid = str(x, "recipeId");
            if (rid != null) extras.add(new FfSection(rid, overrides(x)));
        }
        final JsonObject targetJson = obj(n, "targetOutput");
        final JsonObject custom = obj(n, "customRate");
        final List<String> supplies = new ArrayList<>();
        for (final JsonElement e : array(n, "hatchSupplies")) {
            final String v = asString(e);
            if (v != null) supplies.add(v);
        }
        // Plan mode's port-rate pins, by handle (a shared machine's section by its r<n>: prefix), units per second.
        final Map<String, Double> rates = new LinkedHashMap<>();
        final JsonObject portRates = obj(n, "portRates");
        if (portRates != null) for (final Map.Entry<String, JsonElement> e : portRates.entrySet()) {
            final Double v = e.getValue()
                .isJsonPrimitive()
                && e.getValue()
                    .getAsJsonPrimitive()
                    .isNumber() ? e.getValue()
                        .getAsDouble() : null;
            if (v != null && v > 0 && Double.isFinite(v)) rates.put(e.getKey(), v);
        }
        return new FfNode(
            id,
            recipeId,
            count == null ? 1 : count,
            parallel == null ? 1 : (int) Math.max(1, Math.round(parallel)),
            tier,
            hatches == null ? 0 : (int) Math.round(hatches),
            str(n, "energyHatchType"),
            num(n, "powerEuT"),
            str(n, "hatchVoltageTier"),
            num(n, "hatchAmps"),
            str(n, "powerInputMode"),
            str(n, "machineHandlerId"),
            str(n, "coilTier"),
            configTiers,
            overrides(n),
            extras,
            num(n, "solvePin"),
            targetJson == null ? null : target(targetJson),
            bool(n, "enabled", true),
            at[0],
            at[1],
            custom == null ? null : num(custom, "perSecond"),
            custom == null ? null : str(custom, "mode"),
            supplies,
            rates);
    }

    private static Map<Integer, FfSlot> overrides(final JsonObject holder) {
        final Map<Integer, FfSlot> out = new LinkedHashMap<>();
        final JsonObject o = obj(holder, "recipeInputOverrides");
        if (o == null) return out;
        for (final Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (!e.getValue()
                .isJsonObject()) continue;
            final int slot;
            try {
                slot = Integer.parseInt(e.getKey());
            } catch (final NumberFormatException ignored) {
                continue;
            }
            final FfSlot s = slot(
                e.getValue()
                    .getAsJsonObject(),
                true);
            if (s != null) out.put(slot, s);
        }
        return out;
    }

    @Nullable
    private static FfTarget target(final JsonObject t) {
        final String id = str(t, "resourceId");
        final Double rate = num(t, "amountPerSecond");
        if (id == null || rate == null) return null;
        return new FfTarget(str(t, "kind", "item"), id, rate);
    }

    @Nullable
    private static FfEdge edge(final JsonObject e) {
        final String id = str(e, "id", ""), source = str(e, "source"), target = str(e, "target");
        if (source == null || target == null) return null;
        final String sourceHandle = str(e, "sourceHandle"), targetHandle = str(e, "targetHandle");
        String kind = str(e, "resourceKind");
        String resource = str(e, "resourceId");
        if (kind == null || resource == null) {
            // Very old wires: the handle still names the resource.
            final FfHandle h = FfHandle.parse(sourceHandle != null ? sourceHandle : targetHandle);
            if (h == null) return null;
            if (kind == null) kind = h.kind();
            if (resource == null) resource = h.resourceId();
        }
        return new FfEdge(id, source, target, sourceHandle, targetHandle, kind, resource, obj(e, "crossForm") != null);
    }

    @Nullable
    private static FfStorage storage(final JsonObject s, final Map<String, Board> boards) {
        final String id = str(s, "id"), resource = str(s, "resourceId");
        if (id == null || resource == null) return null;
        final double[] at = absolute(position(s), str(s, "pocketId"), boards);
        return new FfStorage(
            id,
            str(s, "kind", "item"),
            resource,
            str(s, "displayName"),
            str(s, "drainMode"),
            str(s, "bufferMode"),
            num(s, "targetPerSecond"),
            str(s, "targetMode"),
            str(s, "poolTargetMode"),
            str(s, "poolSide"),
            at[0],
            at[1]);
    }

    /** A text note. It needs nothing else on the plan, so it is always kept: no id, no size or no text still read. */
    private static FfNote note(final JsonObject a, final Map<String, Board> boards) {
        final double[] at = absolute(position(a), str(a, "pocketId"), boards);
        final JsonObject size = obj(a, "size");
        final Double width = size == null ? null : num(size, "width");
        final Double height = size == null ? null : num(size, "height");
        final Double font = num(a, "fontSize");
        return new FfNote(
            str(a, "id", ""),
            at[0],
            at[1],
            width == null ? NOTE_WIDTH : width,
            height == null ? NOTE_HEIGHT : height,
            str(a, "colorTag"),
            str(a, "text", ""),
            font == null ? null : (int) Math.round(font));
    }

    /**
     * A box, zone, arrow or image, with the fields it is drawn from; none of them is needed, so none is ever left out.
     */
    private static FfNote drawing(final JsonObject a, final String kind, final Map<String, Board> boards) {
        final double[] at = absolute(position(a), str(a, "pocketId"), boards);
        final JsonObject size = obj(a, "size");
        final Double width = size == null ? null : num(size, "width");
        final Double height = size == null ? null : num(size, "height");
        final JsonObject style = obj(a, "style");
        String fill = style == null ? null : str(style, "fill");
        String marks = style == null ? null : str(style, "marks");
        // The legacy spellings: a mark in the fill is a tint wearing it, paper is solid.
        if (fill != null && List.of("dots", "grid", "graph", "ruled", "hatch")
            .contains(fill)) {
            if (marks == null) marks = fill;
            fill = "tint";
        } else if ("paper".equals(fill)) fill = "solid";
        List<double[]> points = null;
        if ("zone".equals(kind)) {
            points = new ArrayList<>();
            for (final JsonObject pt : objects(a, "points")) {
                final Double px = num(pt, "x"), py = num(pt, "y");
                points.add(new double[] { px == null ? 0 : px, py == null ? 0 : py });
            }
        }
        return new FfNote(
            str(a, "id", ""),
            at[0],
            at[1],
            width == null ? NOTE_WIDTH : width,
            height == null ? NOTE_HEIGHT : height,
            str(a, "colorTag"),
            str(a, "text", ""),
            null,
            new FfPlan.FfDrawing(
                kind,
                str(a, "arrowDirection"),
                points,
                str(a, "imageUrl"),
                style == null ? null : str(style, "border"),
                style == null ? null : str(style, "borderColor"),
                style == null ? null : str(style, "fillColor"),
                fill,
                marks,
                str(a, "arrowHeads"),
                (int) Math.round(num(a, "arrowWidth") == null ? 0 : num(a, "arrowWidth"))));
    }

    // endregion

    // region Load funnel

    /** FF's board title strip and the room it fits round its members (board-grid.ts), in FF px. */
    private static final double BOARD_TITLE = 40, BOARD_PAD = 20;

    /** A board frame: what its members' positions are measured from (FF's flatten-boards.ts, legacy boards rebased). */
    private record Board(@Nullable String parent, double x, double y) {}

    private static double[] absolute(final double[] at, @Nullable final String boardId,
        final Map<String, Board> boards) {
        double x = at[0], y = at[1];
        final Set<String> seen = new HashSet<>();
        for (String id = boardId; id != null && seen.add(id);) {
            final Board board = boards.get(id);
            if (board == null) break;
            x += board.x();
            y += board.y();
            id = board.parent();
        }
        return new double[] { x, y };
    }

    /**
     * FF's migrateTrashCansToDrawers: every wire into a legacy trash can becomes a wire into a trash drawer of that
     * wire's resource, one drawer per resource per can; the cans and their recipes go.
     */
    private static FfPlan migrateTrashCans(final FfPlan plan) {
        final Set<String> trashRecipes = new HashSet<>();
        for (final FfRecipe r : plan.recipes()) if (r.isTrashCan()) trashRecipes.add(r.id());
        if (trashRecipes.isEmpty()) return plan;
        final Map<String, FfRecipe> recipes = plan.recipesById();
        final Map<String, FfNode> nodes = new HashMap<>();
        for (final FfNode n : plan.nodes()) nodes.put(n.id(), n);
        final List<FfStorage> storages = new ArrayList<>(plan.storages());
        final List<FfEdge> edges = new ArrayList<>(plan.edges());
        final Set<String> cans = new HashSet<>();
        for (final FfNode can : plan.nodes()) {
            if (!trashRecipes.contains(can.recipeId())) continue;
            cans.add(can.id());
            final Map<String, String> drawerByResource = new HashMap<>();
            for (int i = 0; i < edges.size(); i++) {
                final FfEdge e = edges.get(i);
                if (!e.target()
                    .equals(can.id())) continue;
                final String key = e.resourceKind() + ":" + e.resourceId();
                String drawerId = drawerByResource.get(key);
                if (drawerId == null) {
                    drawerId = "trash-" + can.id() + "-" + drawerByResource.size();
                    String name = null;
                    final FfNode feeder = nodes.get(e.source());
                    final FfRecipe feederRecipe = feeder == null ? null : recipes.get(feeder.recipeId());
                    if (feederRecipe != null) for (final FfSlot out : feederRecipe.outputs()) {
                        if (out.kind()
                            .equals(e.resourceKind())
                            && out.id()
                                .equals(e.resourceId())) {
                            name = out.displayName();
                            break;
                        }
                    }
                    storages.add(
                        new FfStorage(
                            drawerId,
                            e.resourceKind(),
                            e.resourceId(),
                            name,
                            "trash",
                            null,
                            null,
                            null,
                            null,
                            null,
                            can.x(),
                            can.y() + drawerByResource.size() * 100));
                    drawerByResource.put(key, drawerId);
                }
                edges.set(
                    i,
                    new FfEdge(
                        e.id(),
                        e.source(),
                        drawerId,
                        e.sourceHandle(),
                        "input:" + e.resourceKind() + ":" + encode(e.resourceId()),
                        e.resourceKind(),
                        e.resourceId(),
                        e.crossForm()));
            }
        }
        final List<FfNode> keptNodes = new ArrayList<>();
        for (final FfNode n : plan.nodes()) if (!cans.contains(n.id())) keptNodes.add(n);
        edges.removeIf(e -> cans.contains(e.source()) || cans.contains(e.target()));
        final List<FfRecipe> keptRecipes = new ArrayList<>();
        for (final FfRecipe r : plan.recipes()) if (!r.isTrashCan()) keptRecipes.add(r);
        final List<String> notes = new ArrayList<>(plan.notes());
        if (!cans.isEmpty()) notes.add(
            cans.size() + " old trash can" + (cans.size() == 1 ? "" : "s") + " turned into trash drawers, as FF does.");
        return new FfPlan(
            plan.name(),
            plan.solveMode(),
            plan.poolMode(),
            plan.planMode(),
            keptRecipes,
            keptNodes,
            edges,
            storages,
            plan.textNotes(),
            plan.targetRate(),
            notes,
            plan.boards(),
            plan.boardOf());
    }

    private static String encode(final String id) {
        try {
            return URLEncoder.encode(id, "UTF-8")
                .replace("+", "%20");
        } catch (final UnsupportedEncodingException e) {
            return id;
        }
    }

    // endregion

    // region Lenient JSON

    private static double[] position(final JsonObject o) {
        final JsonObject p = obj(o, "position");
        if (p == null) return new double[] { 0, 0 };
        final Double x = num(p, "x"), y = num(p, "y");
        return new double[] { x == null ? 0 : x, y == null ? 0 : y };
    }

    @Nullable
    private static JsonObject obj(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static List<JsonElement> array(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        final List<JsonElement> out = new ArrayList<>();
        if (e == null || !e.isJsonArray()) return out;
        final JsonArray a = e.getAsJsonArray();
        for (final JsonElement x : a) if (x != null && !x.isJsonNull()) out.add(x);
        return out;
    }

    private static List<JsonObject> objects(final JsonObject o, final String key) {
        final List<JsonObject> out = new ArrayList<>();
        for (final JsonElement e : array(o, key)) if (e.isJsonObject()) out.add(e.getAsJsonObject());
        return out;
    }

    @Nullable
    private static String asString(@Nullable final JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) return null;
        return e.getAsString();
    }

    @Nullable
    private static String str(final JsonObject o, final String key) {
        return asString(o.get(key));
    }

    private static String str(final JsonObject o, final String key, final String fallback) {
        final String s = str(o, key);
        return s == null ? fallback : s;
    }

    @Nullable
    private static Double num(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return null;
        final JsonPrimitive p = e.getAsJsonPrimitive();
        try {
            if (p.isNumber() || p.isString()) {
                final double d = p.getAsDouble();
                return Double.isFinite(d) ? d : null;
            }
        } catch (final NumberFormatException ignored) {}
        return null;
    }

    private static boolean bool(final JsonObject o, final String key, final boolean fallback) {
        final JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) return fallback;
        final JsonPrimitive p = e.getAsJsonPrimitive();
        return p.isBoolean() ? p.getAsBoolean() : fallback;
    }

    private static String fmt(final double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    // endregion
}
