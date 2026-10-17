package com.gtnhplanner.importer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.data.properties.RecipeProperty;
import com.gtnhplanner.harness.GtnhFlowLoader;
import com.gtnhplanner.harness.TestIngredients;
import com.gtnhplanner.importer.FfPlan.FfRecipe;
import com.gtnhplanner.importer.FfPlan.FfSlot;
import com.gtnhplanner.importer.RecipeIndex.GameRecipe;
import com.gtnhplanner.importer.RecipeIndex.GameStack;

/**
 * A game for headless import tests: every FF recipe's in-game twin is the recipe itself, so every card matches, and
 * nodes are built from plain data with one port per resource, as GTNH Planner builds them. Port names carry
 * {@code kind|id}, which {@link #describe} reads back.
 */
public final class FakeGame implements RecipeIndex, NodeMaker {

    /** Recipe ids the game "does not have". */
    final Set<String> missing = new HashSet<>();
    /** The machine each node was asked to run on. */
    final Map<UUID, String> machines = new HashMap<>();
    /** Whether the game builds generators (power cards): a bare node naming its source. */
    boolean generators;
    /** The cells the game knows, by FF id: {its fluid, its litres, its empty cell}. Fluid tanks are made for these. */
    final Map<String, String[]> cells = new HashMap<>();

    @Override
    @Nullable
    public Node makeTank(final boolean emptying, final String cellId) {
        final String[] c = cells.get(cellId);
        if (c == null) return null;
        final Node node = new Node(UUID.randomUUID(), 0, 0);
        node.powerSource = com.gtnhplanner.power.FluidTank.ID;
        final Port<?> cell = TestIngredients.port("item|" + cellId, 1),
            fluid = TestIngredients.port("fluid|" + c[0], Double.parseDouble(c[1])),
            empty = TestIngredients.port("item|" + c[2], 1);
        if (emptying) {
            node.inputs.add(cell);
            node.outputs.add(fluid);
            node.outputs.add(empty);
        } else {
            node.inputs.add(fluid);
            node.inputs.add(empty);
            node.outputs.add(cell);
        }
        return node;
    }

    public FakeGame() {
        GtnhFlowLoader.ensureDefaultMachineProfile();
    }

    @Override
    @Nullable
    public Node makePower(final String sourceId, final Map<String, String> settings) {
        if (!generators) return null;
        final Node node = new Node(UUID.randomUUID(), 0, 0);
        node.powerSource = sourceId;
        node.powerSettings.putAll(settings);
        return node;
    }

    /**
     * Look recipes up only where the game's index would (importer.game.NeiRecipeIndex): by NEI's id for the recipe, a
     * crop's crop, the crafting and furnace pages by output, GregTech's maps by the map the plan names or the machine
     * it names; anything else has no recipe in game. Off, every recipe is found.
     */
    boolean routed;
    /** A game of another pack version: NEI's ids from the plan find nothing, so recipes are looked for by map. */
    boolean otherPack;
    /** How each recipe was found, by recipe id: "nei", "crop", "page", "map:<id>" or "machine". */
    final Map<String, String> paths = new LinkedHashMap<>();
    /** The website's machine each node was asked to run on, by its handler id. */
    final Map<UUID, String> handlerIds = new HashMap<>();

    @Override
    public Lookup find(final FfRecipe recipe) {
        if (missing.contains(recipe.id())) return Lookup.none("not in this game");
        if (!routed) return Lookup.of(List.of(twin(recipe)));
        if (recipe.neiRecipe() != null && !otherPack) {
            paths.put(recipe.id(), "nei");
            return Lookup.exact(twin(recipe));
        }
        final String path;
        if (recipe.isCrop()) path = "crop";
        else if ("crafting".equals(recipe.category()) || "furnace".equals(recipe.category())
            || "Furnace".equals(recipe.machineType())
            || recipe.machineType()
                .endsWith(" Crafting"))
            path = "page";
        else if (recipe.gregtech()) path = recipe.gameMapId() != null ? "map:" + recipe.gameMapId() : "machine";
        else return Lookup.none("no recipe like it in game");
        paths.put(recipe.id(), path);
        return Lookup.of(List.of(twin(recipe)));
    }

    /** The FF recipe as the game would list it. */
    static GameRecipe twin(final FfRecipe r) {
        final List<GameStack> in = new ArrayList<>(), out = new ArrayList<>();
        for (final FfSlot s : r.inputs()) in.add(new GameStack(s.kind(), s.ids(), s.amount(), 1, s.consumed()));
        for (final FfSlot s : r.outputs()) out.add(new GameStack(s.kind(), s.ids(), s.amount(), s.chance(), true));
        return new GameRecipe(
            "fake:" + r.id(),
            0,
            r.machineType(),
            in,
            out,
            r.durationTicks(),
            Math.round(r.eut()),
            r.specialValue() == null ? null : (int) Math.round(r.specialValue()));
    }

    /** The keys a GregTech card's numbers go under (GTProvider's), which the export reads. */
    public static final RecipeProperty<Integer> DURATION = RecipeProperty.<Integer>builder("duration_ticks", 0)
        .build();
    public static final RecipeProperty<Long> EU_PER_TICK = RecipeProperty.<Long>builder("gt.eu_per_tick", 0L)
        .build();

    @Override
    public Node make(final GameRecipe recipe, @Nullable final Machine machine) {
        final String machineLabel = machine == null ? null : machine.label();
        final Node node = new Node(UUID.randomUUID(), 0, 0);
        node.machineName = recipe.handlerName();
        if (machine != null && machine.handlerId() != null) handlerIds.put(node.id, machine.handlerId());
        if (recipe.durationTicks() != null) node.properties.put(DURATION, recipe.durationTicks());
        if (recipe.euPerTick() != null) node.properties.put(EU_PER_TICK, recipe.euPerTick());
        for (final Map.Entry<String, Double> e : merged(recipe.inputs(), true).entrySet()) {
            final Port<TestIngredients.TestIngredient> port = TestIngredients.port(e.getKey(), e.getValue());
            // A slot that takes several items (FF's alternatives) lists them all, as NEI does.
            for (final GameStack s : recipe.inputs()) {
                if (!(s.kind() + "|" + s.id()).equals(e.getKey())) continue;
                final List<TestIngredients.TestIngredient> items = new ArrayList<>();
                for (final String id : s.ids()) if (!id.startsWith("oredict:"))
                    items.add(new TestIngredients.TestIngredient(s.kind() + "|" + id, 1));
                if (items.size() > 1) port.setAlternatives(items);
                break;
            }
            node.inputs.add(port);
        }
        for (final Map.Entry<String, Double> e : merged(recipe.outputs(), false).entrySet())
            node.outputs.add(TestIngredients.port(e.getKey(), e.getValue()));
        if (machineLabel != null) machines.put(node.id, machineLabel);
        return node;
    }

    /** One port per resource, kept inputs left out, like GTProvider and Node.deduplicate. */
    private static Map<String, Double> merged(final List<GameStack> stacks, final boolean inputs) {
        final Map<String, Double> out = new LinkedHashMap<>();
        for (final GameStack s : stacks) {
            if (inputs && !s.consumed()) continue;
            out.merge(s.kind() + "|" + s.id(), s.amount(), Double::sum);
        }
        return out;
    }

    @Override
    public PortInfo describe(final Port<?> port) {
        final String name = TestIngredients.nameOf(port);
        final int bar = name.indexOf('|');
        final String kind = name.substring(0, bar), id = name.substring(bar + 1);
        return new PortInfo(kind, FfIds.toKey(kind, id), id, List.of(id));
    }

    @Override
    public void applySettings(final Node node, final Map<String, Object> settings) {
        node.machineConfig.settings.putAll(settings);
    }

    /** The port of a node holding an FF id, or -1. */
    int port(final Node node, final boolean output, final String id) {
        final List<Port<?>> ports = output ? node.outputs : node.inputs;
        for (int i = 0; i < ports.size(); i++) {
            if (FfIds.same(
                describe(ports.get(i)).ids()
                    .getFirst(),
                id)) return i;
        }
        return -1;
    }

    public static String fixture(final String name) {
        try (InputStream in = FakeGame.class.getResourceAsStream("/factory-flow/" + name)) {
            Objects.requireNonNull(in, "missing factory-flow fixture: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
