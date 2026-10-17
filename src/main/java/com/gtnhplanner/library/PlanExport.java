package com.gtnhplanner.library;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhplanner.data.flowchart.BoardGroup;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.data.properties.RecipeProperty;
import com.gtnhplanner.importer.FfPlan;
import com.gtnhplanner.importer.FfSettings;
import com.gtnhplanner.machines.web.Web;
import com.gtnhplanner.power.CustomRate;
import com.gtnhplanner.power.Energy;
import com.gtnhplanner.power.PowerModel;
import com.gtnhplanner.power.PowerRegistry;
import com.gtnhplanner.power.PowerSource;

/**
 * A plan as Factory Flow's project JSON (its {@code factoryProjectSchema}, version 1), for posting to the library:
 * each card's recipe written out in full (Factory Flow plans carry their recipes), the cards, the drawers, the wires
 * and the notes (its text annotations), in Solve mode, laid out at Factory Flow's scale. The game's side (ids, names,
 * solved counts) comes in through {@link World}, so this stays free of Minecraft and can be tested on its own.
 */
public final class PlanExport {

    /** One item or fluid as Factory Flow names it: {@code item} or {@code fluid}, its id, and its name. */
    public record Res(String kind, String id, String name) {}

    /** EU, as the site names it: a generator's output and an EU drawer's resource. */
    public static final Res EU = new Res("power", "eu", "EU");

    /** What the export needs from the game. */
    public interface World {

        /** A port's item or fluid, or null to leave it out (nothing the site could name). */
        @Nullable
        Res port(Port<?> port);

        /** A drawer's item or fluid, from its resource key. */
        @Nullable
        Res resource(String key);

        /** The machine a card runs on, by name. */
        String machine(Node node);

        /** How many machines the plan runs the card at (the solve's answer), 0 when unknown. */
        double machines(Node node);

        /** The card's tier name ("LV", "HV"...). */
        String tier(Node node);

        /** Whether the card's machine is a multiblock (its tier is then its hatches'). */
        default boolean multiblock(final Node node) {
            return node.machineConfig != null && Boolean.TRUE.equals(node.machineConfig.settings.get("gt_multiblock"));
        }

        /** The website's id for the card's machine among the recipe's handlers, or null for the recipe's first. */
        @Nullable
        default String handlerId(final Node node) {
            return null;
        }

        /** A crop card's crop; null on any other card. */
        @Nullable
        default Crop crop(final Node node) {
            return null;
        }

        /**
         * A GregTech card's recipe as the website's dataset carries it (machines/game/WebCards: its map, its machines
         * and their settings, the game's own overclocks), or null on any other card. Without it neither the game nor
         * the website could tell which recipe map the card is on.
         */
        @Nullable
        default Web.Recipe gregtech(final Node node) {
            return null;
        }

        /**
         * NEI's own id for the card's recipe, as a saved plan keeps it, so a game opening the post finds the very
         * recipe; null on a card with none (a generator).
         */
        @Nullable
        default JsonObject neiRecipe(final Node node) {
            return null;
        }

        /**
         * The name a card's setting pins are kept under on the board ({@code SettingPins.machine}): its machine's, a
         * bee card's species', a crop card's harvester's.
         */
        default String pinName(final Node node) {
            return machine(node);
        }

        /** A bee card's species; null on any other card. */
        @Nullable
        default com.gtnhplanner.machines.Bees.Species bee(final Node node) {
            return null;
        }
    }

    /** A CropsNH crop: its id ({@code cropsnh:sugarBeet}) and the facts the website models it from. */
    public record Crop(String id, com.gtnhplanner.machines.CropsNh.Stats stats) {}

    /**
     * The site's board px for ours: one to one. Its cards are ours since its one-mode board (320 wide, rows of two
     * cells, drawers 116 by 48), and it places every plan, old ones too, where its positions say.
     */
    private static final double SCALE_X = 1, SCALE_Y = 1;
    /** The colour tags the site's schema takes (its factoryNodeColorTagSchema); a note in any other goes untagged. */
    private static final Set<String> COLOR_TAGS = Set.of(
        "white",
        "orange",
        "magenta",
        "light_blue",
        "yellow",
        "lime",
        "pink",
        "gray",
        "light_gray",
        "cyan",
        "purple",
        "blue",
        "brown",
        "green",
        "red",
        "black",
        "scarlet",
        "amber",
        "emerald",
        "azure",
        "steel",
        "onyx");

    private PlanExport() {}

    /** An item or fluid as the face of a plan or a post: the site's {@code EntryIcon}, without its picture. */
    public static JsonObject icon(final Res r) {
        final JsonObject o = new JsonObject();
        o.addProperty("kind", r.kind());
        o.addProperty("resourceId", r.id());
        if (!r.name()
            .isEmpty()) o.addProperty("displayName", r.name());
        return o;
    }

    /**
     * One card as the site keeps it: its recipe (written out in full under {@code recipeId}), the node, and each port's
     * resource as the recipe names it (for the wires).
     */
    public record Card(JsonObject recipe, JsonObject node, Map<Port<?>, Res> ports) {}

    /**
     * A card at a machine count: {@code machines} is how many machines the plan runs it at (a fluid tank's crafts a
     * second, which the site counts a tick a craft).
     */
    public static Card card(final Node n, final String recipeId, final World world, final double machines) {
        final Map<Port<?>, Res> res = new HashMap<>();
        // The card's picks among the items a slot takes, by the recipe's input index: the site's overrides.
        final JsonObject picks = new JsonObject();
        final JsonObject recipe = recipe(n, recipeId, world, res, picks);
        final JsonObject node = node(n, recipeId, world, machines);
        if (picks.entrySet()
            .size() > 0) node.add("recipeInputOverrides", picks);
        final JsonObject rates = portRates(n, res);
        if (rates.entrySet()
            .size() > 0) node.add("portRates", rates);
        return new Card(recipe, node, res);
    }

    /** A drawer's resource as the site names it: EU, else what the game calls its resource key; null if nothing. */
    @Nullable
    public static Res resource(final Drawer d, final World world) {
        return Energy.KEY.equals(d.getResourceKey()) ? EU : world.resource(d.getResourceKey());
    }

    public static JsonObject project(final Graph g, final String name, final World world) {
        final JsonArray recipes = new JsonArray(), nodes = new JsonArray(), storages = new JsonArray(),
            edges = new JsonArray();
        // Each port's resource, once: edges and drawers name them as the recipes do.
        final Map<Port<?>, Res> res = new HashMap<>();
        for (final Node n : g.getNodes()) {
            final Card card = card(n, "gtnhplanner:" + n.id, world, world.machines(n));
            res.putAll(card.ports());
            recipes.add(card.recipe());
            inGroup(g, card.node(), n.group, n.x, n.y);
            nodes.add(card.node());
        }
        for (final Edge e : g.getEdges()) {
            final Node from = g.nodes.get(e.sourceNodeId), to = g.nodes.get(e.targetNodeId);
            if (from == null || to == null || e.sourceOutputIndex >= from.outputs.size()) continue;
            final Res r = res.get(from.outputs.get(e.sourceOutputIndex));
            if (r == null) continue;
            edges.add(edge(e.id.toString(), from.id.toString(), to.id.toString(), r));
        }
        for (final Drawer d : g.getDrawers()) {
            final Res r = resource(d, world);
            if (r == null) continue;
            final boolean source = d.getKind()
                .linksInputs();
            final JsonObject storage = storage(d, r);
            inGroup(g, storage, d.getGroup(), d.getX(), d.getY());
            storages.add(storage);
            for (final Drawer.Link link : d.getLinks()) {
                final Node n = g.nodes.get(link.nodeId());
                if (n == null) continue;
                final String id = d.getId() + ":" + link.nodeId() + ":" + link.portIndex();
                edges.add(
                    source ? edge(
                        id,
                        d.getId()
                            .toString(),
                        n.id.toString(),
                        r)
                        : edge(
                            id,
                            n.id.toString(),
                            d.getId()
                                .toString(),
                            r));
            }
            // A buffer's users: a wire from it to each, so the site reads it as fed and drawn from (a buffer).
            for (final Drawer.Link link : d.getTakers()) {
                final Node n = g.nodes.get(link.nodeId());
                if (n == null) continue;
                edges.add(
                    edge(
                        d.getId() + ":" + link.nodeId() + ":in:" + link.portIndex(),
                        d.getId()
                            .toString(),
                        n.id.toString(),
                        r));
            }
        }
        final JsonArray annotations = new JsonArray();
        for (final Note n : g.getNotes()) {
            final JsonObject note = note(n);
            inGroup(g, note, n.getGroup(), n.getX(), n.getY());
            annotations.add(note);
        }
        final JsonArray pockets = new JsonArray();
        for (final BoardGroup b : g.boardGroups.values()) pockets.add(pocket(g, b));
        final JsonObject p = new JsonObject();
        p.addProperty("schemaVersion", 1);
        p.addProperty(
            "id",
            UUID.randomUUID()
                .toString());
        p.addProperty("name", name);
        // The plan's face, which the website's plan card shows: its description and icon.
        if (!g.getDescription()
            .isEmpty()) p.addProperty("description", g.getDescription());
        final Res face = g.getIcon() == null ? null : world.resource(g.getIcon());
        if (face != null) p.add("icon", icon(face));
        p.addProperty("solveMode", true);
        // The one-mode board's plan: the site reads a buffer's rule as live only there.
        p.addProperty("planMode", true);
        p.add("recipes", recipes);
        p.add("nodes", nodes);
        p.add("storages", storages);
        p.add("annotations", annotations);
        p.add("pockets", pockets);
        p.add("edges", edges);
        p.add("fuelProfiles", new JsonArray());
        final JsonObject meta = new JsonObject();
        meta.addProperty("source", "GTNH Planner");
        p.add("metadata", meta);
        return p;
    }

    private static JsonObject recipe(final Node n, final String id, final World world, final Map<Port<?>, Res> res,
        final JsonObject picks) {
        if (CustomRate.is(n)) return customRateRecipe(n, id, world, res);
        if (com.gtnhplanner.power.FluidTank.is(n)) return tankRecipe(n, id, world, res);
        if (n.isPower()) return powerRecipe(n, id, world, res);
        final Crop crop = world.crop(n);
        if (crop != null) return withNeiRecipe(cropRecipe(n, id, crop, world, res), world.neiRecipe(n));
        final com.gtnhplanner.machines.Bees.Species bee = world.bee(n);
        if (bee != null) return beeRecipe(n, id, bee, world, res);
        final Web.Recipe site = world.gregtech(n);
        final JsonObject r = new JsonObject();
        r.addProperty("id", id);
        final String machine = world.machine(n);
        r.addProperty(
            "name",
            n.outputs.isEmpty() ? machine
                : n.outputs.get(0)
                    .getDisplayName());
        r.addProperty("kind", "gregtech_machine");
        final String mapId = site == null || site.metadata == null || !site.metadata.has("recipeMapId") ? null
            : site.metadata.get("recipeMapId")
                .getAsString();
        if (site != null) r.addProperty("category", SMELTING.equals(mapId) ? "furnace" : "gregtech");
        // The website's machine type is the recipe map's name; the machine itself is the node's handler.
        r.addProperty("machineType", site != null ? site.machineType : machine);
        r.addProperty("minimumTier", site != null && site.minimumTier != null ? site.minimumTier : world.tier(n));
        r.addProperty("durationTicks", Math.max(1, intProperty(n, "duration_ticks")));
        r.addProperty("eut", Math.max(0, longProperty(n, "gt.eu_per_tick")));
        if (site != null && site.specialValue != null) r.addProperty("specialValue", site.specialValue);
        if (site != null && site.programmedCircuit != null) r.addProperty("programmedCircuit", site.programmedCircuit);
        r.add("inputs", ports(n.inputs, false, world, res, picks));
        r.add("outputs", ports(n.outputs, true, world, res));
        if (site != null) gregtech(r, site);
        final JsonObject source = new JsonObject();
        if (site != null && site.source != null && site.source.recipeMap != null)
            source.addProperty("recipeMap", site.source.recipeMap);
        source.addProperty("exporter", "unknown");
        source.addProperty("sourceMod", "GTNH Planner");
        r.add("source", source);
        final JsonObject meta = new JsonObject();
        if (site != null && site.metadata != null)
            for (final Map.Entry<String, JsonElement> e : site.metadata.entrySet()) meta.add(e.getKey(), e.getValue());
        if (meta.entrySet()
            .size() > 0) r.add("metadata", meta);
        return withNeiRecipe(r, world.neiRecipe(n));
    }

    /** The website's vanilla furnace map, whose recipes it files as furnace recipes, not GregTech's. */
    private static final String SMELTING = "smelting";

    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();

    /**
     * What the website knows a GregTech recipe by, beside its numbers: the machines that run it with their settings,
     * the recipe's own settings (a coil hot enough for it), and the game's overclocks at each tier. The website's
     * maths read these, so the post solves there as it does on the board.
     */
    private static void gregtech(final JsonObject r, final Web.Recipe site) {
        if (site.machineHandlers != null && !site.machineHandlers.isEmpty())
            r.add("machineHandlers", GSON.toJsonTree(site.machineHandlers));
        if (site.machineConfigControls != null && !site.machineConfigControls.isEmpty())
            r.add("machineConfigControls", GSON.toJsonTree(site.machineConfigControls));
        // The website reads a recipe's special value (a blast furnace recipe's heat) from NEI's lines, nowhere else.
        if (site.nei != null && site.nei.additionalInfo != null && !site.nei.additionalInfo.isEmpty()) {
            final JsonObject nei = new JsonObject();
            nei.add("additionalInfo", GSON.toJsonTree(site.nei.additionalInfo));
            r.add("nei", nei);
        }
        final Web.RuntimeCalculation calc = site.runtimeCalculation;
        if (calc == null || calc.variants == null || calc.variants.isEmpty()) return;
        final JsonObject runtime = new JsonObject();
        // The oracle's kind for GT's OverclockCalculator run per tier (machines/game/RuntimeVariants).
        runtime.addProperty("sourceKind", "gregtech-overclock-calculator");
        runtime.addProperty("status", calc.status != null ? calc.status : "computed");
        runtime.addProperty("oracleEligible", Boolean.TRUE.equals(calc.oracleEligible));
        if (calc.strict != null) runtime.addProperty("strict", calc.strict);
        final JsonArray variants = new JsonArray();
        for (final Web.RuntimeVariant v : calc.variants) {
            final JsonObject o = new JsonObject();
            o.addProperty("id", v.id);
            if (v.label != null) o.addProperty("label", v.label);
            if (v.machineHandlerId != null) o.addProperty("machineHandlerId", v.machineHandlerId);
            if (v.overclockTier != null) o.addProperty("overclockTier", v.overclockTier);
            o.addProperty("durationTicks", Math.max(1, Math.round(v.durationTicks)));
            o.addProperty("eut", Math.max(0, v.eut));
            if (v.parallel != null && v.parallel > 0) o.addProperty("parallel", v.parallel);
            if (v.outputs != null) {
                final JsonArray outs = new JsonArray();
                for (final Web.Resource out : v.outputs) {
                    if (out.id == null || out.id.isEmpty() || !(out.amount > 0)) continue;
                    final JsonObject x = new JsonObject();
                    x.addProperty("kind", out.kind);
                    x.addProperty("id", out.id);
                    x.addProperty("amount", out.amount);
                    if (out.chance != null) x.addProperty("chance", Math.max(0, Math.min(1, out.chance)));
                    outs.add(x);
                }
                o.add("outputs", outs);
            }
            variants.add(o);
        }
        runtime.add("variants", variants);
        r.add("runtimeCalculation", runtime);
    }

    /** The recipe with NEI's id for it in its metadata, where the website keeps what it does not read. */
    private static JsonObject withNeiRecipe(final JsonObject r, @Nullable final JsonObject neiRecipe) {
        if (neiRecipe == null) return r;
        JsonObject meta = r.getAsJsonObject("metadata");
        if (meta == null) {
            meta = new JsonObject();
            r.add("metadata", meta);
        }
        meta.add("neiRecipe", neiRecipe);
        return r;
    }

    /**
     * A crop, as the website's crop farm cards carry it: the Crop Farm's recipe for that crop, its facts under
     * {@code metadata.cropsNh} (getCropsNhStats reads them; the drops are its rates at the site's baseline, which the
     * card's settings scale), and the crop's id where the site's own dataset keeps it.
     */
    private static JsonObject cropRecipe(final Node n, final String id, final Crop crop, final World world,
        final Map<Port<?>, Res> res) {
        final com.gtnhplanner.machines.CropsNh.Stats s = crop.stats();
        final JsonObject r = new JsonObject();
        r.addProperty("id", id);
        r.addProperty(
            "name",
            "Crop Farm" + (n.outputs.isEmpty() ? ""
                : ": " + n.outputs.get(0)
                    .getDisplayName()));
        r.addProperty("kind", "crop_produce");
        r.addProperty("category", "cropsnh-crop");
        r.addProperty("machineType", CROP_FARM);
        r.addProperty("minimumTier", "NONE");
        r.addProperty("durationTicks", Math.max(1, intProperty(n, "duration_ticks")));
        r.addProperty("eut", 0);
        r.add("inputs", new JsonArray());
        r.add("outputs", ports(n.outputs, true, world, res));
        final JsonObject source = new JsonObject();
        source.addProperty("recipeMap", CROP_FARM);
        source.addProperty("sourceMod", "GTNH Planner");
        source.addProperty("rawRecipeId", "cropsnh:" + crop.id());
        r.add("source", source);
        final JsonObject stats = new JsonObject();
        stats.addProperty("cropId", crop.id());
        stats.addProperty("tier", s.tier());
        stats.addProperty("growthPoints", s.growthPoints());
        stats.addProperty("dropChance", s.dropChance());
        stats.addProperty("growthCycleTicks", s.growthCycleTicks());
        stats.addProperty("growthMultiplier", s.growthMultiplier());
        if (s.machineOnly()) stats.addProperty("machineOnly", true);
        if (s.minSeedBedTier() > 0) stats.addProperty("minSeedBedTier", s.minSeedBedTier());
        if (s.subSoil() != null) {
            final JsonArray requirements = new JsonArray();
            requirements.add(new com.google.gson.JsonPrimitive(s.subSoil()));
            stats.add("requirements", requirements);
        }
        final JsonArray drops = new JsonArray();
        for (final com.gtnhplanner.machines.CropsNh.Drop d : s.drops()) {
            final JsonObject drop = new JsonObject();
            drop.addProperty("id", d.id());
            drop.addProperty("stackSize", d.stackSize());
            drop.addProperty("weight", d.weight());
            drops.add(drop);
        }
        stats.add("drops", drops);
        final JsonObject meta = new JsonObject();
        meta.add("cropsNh", stats);
        r.add("metadata", meta);
        return r;
    }

    /** The website's crop farm: its recipe map and machine type, which mark a recipe as a crop's. */
    private static final String CROP_FARM = "Crop Farm";

    /**
     * A bee, as the website's bee cards carry it: its species' produce at the base chances its dataset keeps (one bee
     * cycle, the bee an input never used up, specialties as byproducts), which the card's housing and settings scale.
     * The plain Apiary is not one of the site's bee machines, so the recipe brings it as its first.
     */
    private static JsonObject beeRecipe(final Node n, final String id, final com.gtnhplanner.machines.Bees.Species bee,
        final World world, final Map<Port<?>, Res> res) {
        // Each port's resource, for the wires; the outputs are the species' own, product by product.
        for (final Port<?> p : n.outputs) {
            final Res r = world.port(p);
            if (r != null) res.put(p, r);
        }
        final JsonObject r = new JsonObject();
        r.addProperty("id", id);
        r.addProperty("name", bee.name() + " Produce");
        r.addProperty("kind", "bee_produce");
        r.addProperty("category", "forestry-bee");
        r.addProperty("machineType", BEE_PRODUCE);
        r.addProperty("minimumTier", "NONE");
        r.addProperty("durationTicks", com.gtnhplanner.machines.Bees.CYCLE_TICKS);
        r.addProperty("eut", 0);
        final JsonArray inputs = new JsonArray();
        final JsonObject species = new JsonObject();
        species.addProperty("kind", "item");
        species.addProperty("id", com.gtnhplanner.machines.Bees.websiteId(bee.uid()));
        species.addProperty("amount", 1);
        species.addProperty("displayName", bee.name() + " Bee");
        species.addProperty("consumed", false);
        inputs.add(species);
        r.add("inputs", inputs);
        final JsonArray outputs = new JsonArray();
        for (final com.gtnhplanner.machines.Bees.Product p : bee.products()) {
            final JsonObject o = new JsonObject();
            o.addProperty("kind", "item");
            o.addProperty("id", p.id());
            o.addProperty("amount", p.stack());
            o.addProperty("displayName", p.name());
            o.addProperty("chance", (double) p.chance());
            if (p.specialty()) o.addProperty("byproduct", true);
            outputs.add(o);
        }
        r.add("outputs", outputs);
        final JsonArray handlers = new JsonArray();
        final JsonObject apiary = new JsonObject();
        apiary.addProperty("id", com.gtnhplanner.machines.Bees.APIARY.key());
        apiary.addProperty("label", com.gtnhplanner.machines.Bees.APIARY.label());
        apiary.addProperty("machineType", com.gtnhplanner.machines.Bees.APIARY.label());
        apiary.addProperty("minimumTier", "NONE");
        apiary.addProperty("eut", 0);
        apiary.addProperty("kind", "single");
        handlers.add(apiary);
        r.add("machineHandlers", handlers);
        final JsonObject source = new JsonObject();
        source.addProperty("recipeMap", BEE_PRODUCE);
        source.addProperty("sourceMod", "GTNH Planner");
        source.addProperty("rawRecipeId", bee.uid());
        r.add("source", source);
        final JsonObject meta = new JsonObject();
        meta.addProperty("speciesUid", bee.uid());
        meta.addProperty("speciesName", bee.name());
        r.add("metadata", meta);
        return r;
    }

    /** The website's bee recipes: their recipe map and machine type. */
    private static final String BEE_PRODUCE = "Bee Produce";

    /**
     * A generator, as the site's power cards carry it (buildPowerRecipe): a one-second custom recipe, flows per second
     * exact, EU first among the outputs, and the source with its readings; the site rebuilds it from the source id and
     * the node's settings when it loads the plan.
     */
    private static JsonObject powerRecipe(final Node n, final String id, final World world,
        final Map<Port<?>, Res> res) {
        final JsonObject r = new JsonObject();
        final PowerSource source = PowerRegistry.get(n.powerSource);
        final String name = source != null ? source.name() : n.machineName;
        r.addProperty("id", id);
        r.addProperty("name", name);
        r.addProperty("kind", "custom");
        r.addProperty("category", "power-source");
        r.addProperty("machineType", name);
        r.addProperty("minimumTier", "NONE");
        r.addProperty("durationTicks", 20);
        r.addProperty("eut", 0);
        r.add("inputs", ports(n.inputs, false, world, res));
        r.add("outputs", ports(n.outputs, true, world, res));
        if (source != null) r.addProperty("notes", source.blurb());
        final JsonObject from = new JsonObject();
        from.addProperty("recipeMap", "power-source");
        r.add("source", from);
        final JsonObject power = new JsonObject();
        power.addProperty("sourceId", n.powerSource);
        final PowerModel model = n.powerModel;
        power.addProperty("euPerTick", model == null ? 0 : model.euPerTick());
        final JsonArray stats = new JsonArray();
        if (model != null) for (final PowerModel.Stat s : model.stats()) {
            final JsonObject line = new JsonObject();
            line.addProperty("label", s.label());
            line.addProperty("value", s.value());
            stats.add(line);
        }
        power.add("stats", stats);
        if (model != null && !model.warnings()
            .isEmpty()) {
            final JsonArray warnings = new JsonArray();
            for (final String w : model.warnings()) warnings.add(new com.google.gson.JsonPrimitive(w));
            power.add("warnings", warnings);
        }
        r.add("power", power);
        return r;
    }

    /**
     * A custom rate card, as the site's own (custom-rate.ts): a one-second recipe of its one slot, the amount its rate
     * a second, named after what it holds; the dial rides on the node. The site wants an amount above nothing, so a
     * dial at zero posts the least it takes.
     */
    private static JsonObject customRateRecipe(final Node n, final String id, final World world,
        final Map<Port<?>, Res> res) {
        final JsonObject r = new JsonObject();
        final JsonArray inputs = ports(n.inputs, false, world, res), outputs = ports(n.outputs, true, world, res);
        for (final JsonArray side : List.of(inputs, outputs)) for (final com.google.gson.JsonElement e : side) {
            final JsonObject slot = e.getAsJsonObject();
            slot.remove("byproduct");
            if (!(slot.get("amount")
                .getAsDouble() > 0)) slot.addProperty("amount", 0.001);
        }
        final com.google.gson.JsonElement held = outputs.size() > 0 ? outputs.get(0)
            : inputs.size() > 0 ? inputs.get(0) : null;
        r.addProperty("id", id);
        r.addProperty("name", held == null ? "Custom Rate" : "Custom Rate: " + slotName(held.getAsJsonObject()));
        r.addProperty("kind", "custom");
        r.addProperty("category", "custom-rate");
        r.addProperty("machineType", "Custom Rate");
        r.addProperty("minimumTier", "NONE");
        r.addProperty("durationTicks", 20);
        r.addProperty("eut", 0);
        r.add("inputs", inputs);
        r.add("outputs", outputs);
        r.addProperty("notes", "Wire any port to this and it adopts that resource.");
        final JsonObject from = new JsonObject();
        from.addProperty("recipeMap", "custom-rate");
        r.add("source", from);
        return r;
    }

    /**
     * A fluid tank, as the website's own Tank (its free mirror of a fluid Canner recipe, map {@code planner.tank}): the
     * same cell, fluid and empty cell, at no power, a tick a craft.
     */
    private static JsonObject tankRecipe(final Node n, final String id, final World world,
        final Map<Port<?>, Res> res) {
        final JsonObject r = new JsonObject();
        final JsonArray inputs = ports(n.inputs, false, world, res), outputs = ports(n.outputs, true, world, res);
        final JsonArray fluidSide = com.gtnhplanner.power.FluidTank.emptying(n) ? outputs : inputs;
        String fluid = "";
        for (final com.google.gson.JsonElement e : fluidSide) if ("fluid".equals(
            e.getAsJsonObject()
                .get("kind")
                .getAsString()))
            fluid = slotName(e.getAsJsonObject());
        r.addProperty("id", id);
        r.addProperty("name", fluid.isEmpty() ? "Tank" : "Tank: " + fluid);
        r.addProperty("kind", "custom");
        r.addProperty("machineType", "Tank");
        r.addProperty("minimumTier", "NONE");
        r.addProperty("durationTicks", 1);
        r.addProperty("eut", 0);
        r.add("inputs", inputs);
        r.add("outputs", outputs);
        final JsonObject from = new JsonObject();
        from.addProperty("recipeMap", "Tank");
        from.addProperty("sourceMod", "GTNH Planner");
        r.add("source", from);
        final JsonObject meta = new JsonObject();
        meta.addProperty("recipeMapId", FfPlan.TANK_MAP);
        r.add("metadata", meta);
        return r;
    }

    private static JsonArray ports(final List<Port<?>> ports, final boolean outputs, final World world,
        final Map<Port<?>, Res> res) {
        return ports(ports, outputs, world, res, null);
    }

    /**
     * A side's ports as the recipe's slots. An input that can be set to another item writes the recipe's own item, the
     * others it takes as its {@code alternatives}, and, when the card is set to another, that one into {@code picks}
     * under the slot's index (the site's {@code recipeInputOverrides}); wires name the item it is set to.
     */
    private static JsonArray ports(final List<Port<?>> ports, final boolean outputs, final World world,
        final Map<Port<?>, Res> res, @Nullable final JsonObject picks) {
        final JsonArray out = new JsonArray();
        for (final Port<?> p : ports) {
            if (p.fromModel) continue;
            final Res r = p.getValue() instanceof Energy ? EU : world.port(p);
            if (r == null) continue;
            res.put(p, r);
            if (picks != null && !outputs && p.hasChoices() && Double.isNaN(p.getExactAmount())) {
                final Res own = world.port(p.withAlternative(p.own()));
                if (own != null) {
                    final int amount = Math.max(1, p.getAmount());
                    final JsonObject o = resource(own);
                    o.addProperty("amount", amount);
                    if (p.getAmount() <= 0) o.addProperty("consumed", false);
                    final JsonArray alternatives = new JsonArray();
                    for (final Object alt : p.getAlternatives()) {
                        final Res a = world.port(p.withAlternative(alt));
                        if (a != null && a.kind()
                            .equals(own.kind())
                            && !a.id()
                                .equals(own.id()))
                            alternatives.add(resource(a));
                    }
                    if (alternatives.size() > 0) o.add("alternatives", alternatives);
                    if (!r.id()
                        .equals(own.id())) {
                        final JsonObject pick = resource(r);
                        pick.addProperty("amount", amount);
                        picks.add(String.valueOf(out.size()), pick);
                    }
                    out.add(o);
                    continue;
                }
            }
            if (!Double.isNaN(p.getExactAmount())) {
                // A generator's exact rate per second (its craft is a second); EU rides as a byproduct, as on the site.
                final JsonObject o = resource(r);
                o.addProperty("amount", p.amount());
                if (r == EU) o.addProperty("byproduct", true);
                out.add(o);
                continue;
            }
            // The site wants an amount above nothing; a port that is not used up (a catalyst) still counts one.
            final int amount = Math.max(1, p.getAmount());
            final JsonObject o = resource(r);
            o.addProperty("amount", amount);
            if (outputs && p.getChance() > 0 && p.getChance() < 1) o.addProperty("chance", p.getChance());
            if (!outputs && p.getAmount() <= 0) o.addProperty("consumed", false);
            out.add(o);
        }
        return out;
    }

    /**
     * A card's port rates as plan mode's pins ({@code portRates}): each port set to a rate, by the handle the site
     * names its row by ({@code output:item:<id>}, an input by the item it is set to), the rate a second. A port the
     * site has no row for (none the export names, or a second port of one resource) is left out.
     */
    static JsonObject portRates(final Node n, final Map<Port<?>, Res> res) {
        final JsonObject o = new JsonObject();
        rates(o, n.targetOutputRates, n.outputs, "output", res);
        rates(o, n.targetInputRates, n.inputs, "input", res);
        return o;
    }

    private static void rates(final JsonObject o, final Map<Integer, Double> rates, final List<Port<?>> ports,
        final String side, final Map<Port<?>, Res> res) {
        for (final Map.Entry<Integer, Double> e : rates.entrySet()) {
            final Double rate = e.getValue();
            if (rate == null || !(rate > 0) || !Double.isFinite(rate) || e.getKey() < 0 || e.getKey() >= ports.size())
                continue;
            final Res r = res.get(ports.get(e.getKey()));
            if (r == null) continue;
            final String handle = side + ":" + r.kind() + ":" + enc(r.id());
            if (!o.has(handle)) o.addProperty(handle, rate);
        }
    }

    private static JsonObject node(final Node n, final String recipeId, final World world, final double machines) {
        final JsonObject o = new JsonObject();
        o.addProperty("id", n.id.toString());
        o.addProperty("recipeId", recipeId);
        // A fluid tank runs a craft a second here, a tick there (its Tank map): the count is in its crafts.
        final double count = Math.max(0, machines) / (com.gtnhplanner.power.FluidTank.is(n) ? 20.0 : 1.0);
        o.addProperty("machineCount", count);
        o.addProperty("parallel", 1);
        o.addProperty("overclockTier", CustomRate.is(n) || com.gtnhplanner.power.FluidTank.is(n) ? "NONE" : n.isPower() ? "LV" : world.tier(n));
        o.addProperty("enabled", true);
        if (CustomRate.is(n)) {
            // The dial, where the site keeps it: a second's worth, and which way it runs.
            final JsonObject dial = new JsonObject();
            dial.addProperty("perSecond", CustomRate.perSecond(n));
            dial.addProperty("mode", CustomRate.supply(n) ? CustomRate.SUPPLY : CustomRate.REQUEST);
            o.add("customRate", dial);
        } else if (n.isPower() && !com.gtnhplanner.power.FluidTank.is(n)) {
            // A power card's settings, where the site keeps them.
            final JsonObject settings = new JsonObject();
            for (final Map.Entry<String, String> s : n.powerSettings.entrySet())
                settings.addProperty(s.getKey(), s.getValue());
            o.add("machineConfigTiers", settings);
        } else if (n.machineConfig != null) {
            final Map<String, Object> cfg = n.machineConfig.settings;
            if (world.multiblock(n)) {
                // The hatches, as the website keeps a multiblock's power: tier and amps.
                final String tier = world.tier(n);
                final double amps = cfg.get("amp") instanceof final Number a ? Math.max(1, a.doubleValue()) : 1;
                o.addProperty("hatchVoltageTier", tier);
                o.addProperty("hatchAmps", amps);
                o.addProperty("powerInputMode", "amps");
                o.addProperty("powerEuT", com.gtnhplanner.machines.web.Tiers.maxEuT(tier) * amps);
            }
            if (cfg.get(FfSettings.COIL) instanceof final String coil && !coil.isEmpty()) o.addProperty("coilTier", coil);
            if (cfg.get(FfSettings.HATCH_TYPE) instanceof final String type && !type.isEmpty())
                o.addProperty("energyHatchType", type);
            // A crop's harvester and a bee's housing are the website's machine for them; our settings.
            final boolean crop = world.crop(n) != null;
            final com.gtnhplanner.machines.Bees.Species bee = crop ? null : world.bee(n);
            final String handler = siteHandler(world, n);
            if (handler != null) o.addProperty("machineHandlerId", handler);
            // A bee's housing has no voltage to pick: the Industrial Apiary's own tier, else none.
            if (bee != null) o.addProperty(
                "overclockTier",
                com.gtnhplanner.machines.Bees.INDUSTRIAL.key()
                    .equals(handler) ? "MV" : "NONE");
            // The machine's options under the website's own ids: the modelled machines' (machines/) as they are kept,
            // every other machine's from machine:<id>. The website's speed gene unset is Normal, ours the species'.
            final JsonObject settings = new JsonObject();
            if (bee != null) settings.addProperty(
                com.gtnhplanner.machines.Bees.SPEED,
                com.gtnhplanner.machines.Bees.setup(cfg, bee)
                    .speed()
                    .key());
            for (final Map.Entry<String, Object> s : cfg.entrySet()) {
                if (!(s.getValue() instanceof final String v) || v.isEmpty()) continue;
                if (com.gtnhplanner.machines.CropsNh.HARVESTER.equals(s.getKey())
                    || com.gtnhplanner.machines.Bees.HOUSING.equals(s.getKey())) continue;
                if (FfSettings.modelled(s.getKey())) settings.addProperty(s.getKey(), v);
                else if (s.getKey()
                    .startsWith(FfSettings.MACHINE))
                    settings.addProperty(
                        s.getKey()
                            .substring(FfSettings.MACHINE.length()),
                        v);
            }
            if (!settings.entrySet()
                .isEmpty()) o.add("machineConfigTiers", settings);
        }
        if (n.isMachineCountFixed()) o.addProperty("solvePin", count);
        o.add("position", position(n.x, n.y));
        return o;
    }

    /** A drawer as the site's storage of resource {@code r}: a source, a product or trash, with its rule. */
    public static JsonObject storage(final Drawer d, final Res r) {
        final JsonObject o = new JsonObject();
        o.addProperty(
            "id",
            d.getId()
                .toString());
        o.addProperty("kind", r.kind());
        o.addProperty("resourceId", r.id());
        o.addProperty("displayName", r.name());
        // A drain says what it is (trash voids what it takes); a buffer's role is its wires' (fed and drawn from), its
        // rule a product's, on what passes through.
        if (!d.getKind()
            .linksInputs() && !d.isBuffer())
            o.addProperty("drainMode", d.getKind() == Drawer.Kind.TRASH ? "trash" : "product");
        final String mode = switch (d.getRule()) {
            case AT_LEAST -> "at-least";
            case AT_MOST -> "at-most";
            case EXACTLY -> "exact";
            case ANY -> null;
        };
        // A source's rule on what it supplies, which the site writes as a negative rate.
        if (mode != null && d.getRate() > 0) {
            o.addProperty(
                "targetPerSecond",
                d.getKind()
                    .linksInputs() ? -d.getRate() : d.getRate());
            o.addProperty("targetMode", mode);
        }
        o.add("position", position(d.getX(), d.getY()));
        return o;
    }

    /**
     * A note as the site's text annotation, placed and sized as a card is, to the pixel: the website's board draws a
     * shared plan's sizes as they are (a plan it loads from a file or a post grows each to whole cells, which only adds
     * room).
     */
    public static JsonObject note(final Note n) {
        final JsonObject o = new JsonObject();
        o.addProperty(
            "id",
            n.getId()
                .toString());
        o.addProperty("kind", n.kind());
        final boolean drawing = n.isDrawing();
        if (COLOR_TAGS.contains(n.colorTag()) && (!drawing || n.getColor() != null && !n.getColor()
            .isEmpty())) o.addProperty("colorTag", n.colorTag());
        if (!drawing || !n.joined()
            .isEmpty()) o.addProperty("text", n.joined());
        o.add("position", position(n.getX(), n.getY()));
        final JsonObject size = new JsonObject();
        final boolean arrow = Note.ARROW.equals(n.kind());
        final int minW = arrow ? 0 : drawing ? Note.DRAWING_MIN : Note.MIN_W,
            minH = arrow ? 0 : drawing ? Note.DRAWING_MIN : Note.MIN_H;
        // An arrow may run level: an axis of 0 stays 0.
        size.addProperty("width", arrow ? axis(n.getWidth() * SCALE_X) : cells(Math.max(minW, n.getWidth()) * SCALE_X));
        size.addProperty(
            "height",
            arrow ? axis(n.getHeight() * SCALE_Y) : cells(Math.max(minH, n.getHeight()) * SCALE_Y));
        o.add("size", size);
        if (!drawing) {
            o.addProperty("fontSize", n.fontSizeOrDefault());
            return o;
        }
        if (Note.ARROW.equals(n.kind()) && n.getArrowDirection() != null)
            o.addProperty("arrowDirection", n.getArrowDirection());
        if (Note.ARROW.equals(n.kind()) && n.getArrowHeads() != null) o.addProperty("arrowHeads", n.getArrowHeads());
        if (Note.ARROW.equals(n.kind()) && n.getArrowWidth() > 0)
            o.addProperty("arrowWidth", Math.min(8, n.getArrowWidth()));
        if (Note.IMAGE.equals(n.kind()) && n.getImageUrl() != null) o.addProperty("imageUrl", n.getImageUrl());
        if (Note.ZONE.equals(n.kind()) && n.getPoints() != null) {
            final JsonArray points = new JsonArray();
            for (final int[] p : n.getPoints()) {
                final JsonObject pt = new JsonObject();
                pt.addProperty("x", Math.round(p[0] * SCALE_X));
                pt.addProperty("y", Math.round(p[1] * SCALE_Y));
                points.add(pt);
            }
            o.add("points", points);
        }
        final JsonObject style = new JsonObject();
        if (n.getBorder() != null) style.addProperty("border", n.getBorder());
        if (n.getBorderColor() != null && COLOR_TAGS.contains(n.getBorderColor()))
            style.addProperty("borderColor", n.getBorderColor());
        if (n.getFillColor() != null && COLOR_TAGS.contains(n.getFillColor()))
            style.addProperty("fillColor", n.getFillColor());
        if (n.getFill() != null) style.addProperty("fill", n.getFill());
        if (n.getMarks() != null) style.addProperty("marks", n.getMarks());
        if (!style.entrySet()
            .isEmpty()) o.add("style", style);
        return o;
    }

    /**
     * The website's machine for a card: a crop's harvester and a bee's housing (our settings), else the handler the
     * card's recipe map gives it; null for none.
     */
    @Nullable
    public static String siteHandler(final World world, final Node n) {
        if (n.machineConfig != null) {
            final Map<String, Object> cfg = n.machineConfig.settings;
            if (world.crop(n) != null)
                return cfg.get(com.gtnhplanner.machines.CropsNh.HARVESTER) instanceof final String h && !h.isEmpty() ? h
                    : com.gtnhplanner.machines.CropsNh.MANAGER;
            final com.gtnhplanner.machines.Bees.Species bee = world.bee(n);
            if (bee != null) return com.gtnhplanner.machines.Bees.setup(cfg, bee)
                .housing()
                .key();
        }
        return world.handlerId(n);
    }

    /** A length to the pixel, possibly none (a level arrow). */
    private static int axis(final double length) {
        return (int) Math.round(length);
    }

    /** A length to the pixel, at least one. */
    private static int cells(final double length) {
        return Math.max(1, (int) Math.round(length));
    }

    /** A wire of resource {@code r} between two things the site knows by these ids. */
    public static JsonObject edge(final String id, final String source, final String target, final Res r) {
        final JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("source", source);
        o.addProperty("target", target);
        o.addProperty("sourceHandle", "output:" + r.kind() + ":" + enc(r.id()));
        o.addProperty("targetHandle", "input:" + r.kind() + ":" + enc(r.id()));
        o.addProperty("resourceKind", r.kind());
        o.addProperty("resourceId", r.id());
        return o;
    }

    /** A slot's name as the site shows it: its display name, else its id. */
    private static String slotName(final JsonObject slot) {
        return (slot.has("displayName") ? slot.get("displayName") : slot.get("id")).getAsString();
    }

    private static JsonObject resource(final Res r) {
        final JsonObject o = new JsonObject();
        o.addProperty("kind", r.kind());
        o.addProperty("id", r.id());
        if (!r.name()
            .isEmpty()) o.addProperty("displayName", r.name());
        return o;
    }

    /**
     * A member of a group, as the website keeps one (docs/design/groups.md): {@code pocketId} its board's id, its
     * position measured from the board's frame corner.
     */
    private static void inGroup(final Graph g, final JsonObject o, @Nullable final UUID group, final int x,
        final int y) {
        final BoardGroup b = group == null ? null : g.boardGroups.get(group);
        if (b == null) return;
        o.addProperty("pocketId", b.webId());
        o.add("position", position(x - b.getX(), y - b.getY()));
    }

    /**
     * A group as the website's board ({@code FactoryPocket}): its frame (corner from its parent's, size to the pixel,
     * as the website now fits a group's frame too), open or not, its colour and the paper it came with.
     */
    public static JsonObject pocket(final Graph g, final BoardGroup b) {
        final JsonObject o = new JsonObject();
        o.addProperty("id", b.webId());
        // The site's boards always have a name.
        o.addProperty(
            "name",
            b.getName() == null || b.getName()
                .isBlank() ? "Group" : b.getName());
        if (b.getColor() != null && COLOR_TAGS.contains(b.getColor())) o.addProperty("colorTag", b.getColor());
        final BoardGroup parent = b.getParent() == null ? null : g.boardGroups.get(b.getParent());
        if (parent != null) o.addProperty("parentPocketId", parent.webId());
        o.add(
            "position",
            parent == null ? position(b.getX(), b.getY())
                : position(b.getX() - parent.getX(), b.getY() - parent.getY()));
        o.addProperty("expanded", !b.isCollapsed());
        final JsonObject size = new JsonObject();
        size.addProperty("width", cells(b.getWidth() * SCALE_X));
        size.addProperty("height", cells(b.getHeight() * SCALE_Y));
        o.add("size", size);
        if (b.getTheme() != null) o.addProperty("theme", b.getTheme());
        if (b.getPattern() != null) o.addProperty("pattern", b.getPattern());
        return o;
    }

    /** A place on the board at the site's scale; {@link #gameX} and {@link #gameY} turn it back exactly. */
    public static JsonObject position(final int x, final int y) {
        final JsonObject o = new JsonObject();
        o.addProperty("x", Math.round(x * SCALE_X));
        o.addProperty("y", Math.round(y * SCALE_Y));
        return o;
    }

    /**
     * The board's x for the site's: the inverse of {@link #position}, exact for every board place; a site place between
     * two of the board's pixels reads as the nearest.
     */
    public static int gameX(final double siteX) {
        return (int) Math.round(siteX / SCALE_X);
    }

    /** The board's y for the site's; see {@link #gameX}. */
    public static int gameY(final double siteY) {
        return (int) Math.round(siteY / SCALE_Y);
    }

    private static int intProperty(final Node n, final String key) {
        return (int) longProperty(n, key);
    }

    private static long longProperty(final Node n, final String key) {
        for (final Map.Entry<RecipeProperty<?>, Object> e : n.properties.entrySet()) {
            if (key.equals(
                e.getKey()
                    .getKey())
                && e.getValue() instanceof final Number num) return num.longValue();
        }
        return 0;
    }

    /** {@code encodeURIComponent}, as the website writes handles. */
    private static String enc(final String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name())
                .replace("+", "%20")
                .replace("%21", "!")
                .replace("%27", "'")
                .replace("%28", "(")
                .replace("%29", ")")
                .replace("%7E", "~");
        } catch (final java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
