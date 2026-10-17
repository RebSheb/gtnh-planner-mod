package com.gtnhplanner.cloud.map;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.gtnhplanner.data.Settings;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.harness.TestIngredients;
import com.gtnhplanner.importer.FakeGame;
import com.gtnhplanner.importer.NodeMaker;
import com.gtnhplanner.importer.RecipeIndex;
import com.gtnhplanner.library.PlanExport;

/**
 * The game for headless mapping tests: {@link FakeGame}'s recipes and nodes (every port named {@code kind|id}), the
 * export's names read off those, and, standing in for NEI, a registry of each card's ports by its id: a card read back
 * from its game data gets the ports NEI would give its recipe.
 */
public final class TestGame implements CloudGame {

    final FakeGame fake = new FakeGame();
    /** The world placements are kept under, as the game names it. */
    public String world = "";
    /** The website's machine (handler) id of each card; none unless a test names them. */
    public java.util.function.Function<Node, String> handlers = n -> null;

    /** What NEI would read for each card: its ports and recipe numbers, by card id. */
    private final Map<UUID, Node> nei = new HashMap<>();

    /** Every card of a board becomes one NEI knows, so it reads back with its ports. */
    public TestGame knowing(final Graph g) {
        for (final Node n : g.getNodes()) nei.put(n.id, n);
        return this;
    }

    @Override
    public RecipeIndex recipes() {
        return fake;
    }

    @Override
    public String world() {
        return world;
    }

    @Override
    public NodeMaker maker() {
        return fake;
    }

    @Override
    public void rebuilt(final Node node) {
        final Node like = nei.get(node.id);
        if (like == null) return;
        for (final Port<?> p : like.inputs) node.inputs.add(p.withAlternative(p.getValue()));
        for (final Port<?> p : like.outputs) node.outputs.add(p.withAlternative(p.getValue()));
        node.properties.putAll(like.properties);
    }

    @Override
    @Nullable
    public PlanExport.Res port(final Port<?> port) {
        final NodeMaker.PortInfo info = fake.describe(port);
        return new PlanExport.Res(
            info.kind(),
            info.ids()
                .getFirst(),
            info.label());
    }

    /** A drawer's key ({@code item:<id>:<meta>}, {@code fluid:<id>}, as FakeGame keys its ports) as the site's id. */
    @Override
    @Nullable
    public PlanExport.Res resource(final String key) {
        if (key.startsWith("fluid:")) return new PlanExport.Res("fluid", key.substring(6), key.substring(6));
        if (!key.startsWith("item:")) return null;
        final String rest = key.substring(5);
        final int colon = rest.lastIndexOf(':');
        if (colon < 0) return null;
        final String meta = rest.substring(colon + 1), name = rest.substring(0, colon);
        final String id = "0".equals(meta) ? name : name + "@" + meta;
        return new PlanExport.Res("item", id, id);
    }

    @Override
    public String machine(final Node node) {
        return node.machineName == null ? "Machine" : node.machineName;
    }

    @Override
    @Nullable
    public String handlerId(final Node node) {
        return handlers.apply(node);
    }

    @Override
    public double machines(final Node node) {
        return node.machineConfig.getMachineCount();
    }

    @Override
    public String tier(final Node node) {
        return node.machineConfig.settings.get(Settings.VOLTAGE.key()) instanceof final String t ? t : "LV";
    }

    /** A port as FakeGame names it. */
    static Port<?> port(final String kind, final String id, final double perCraft) {
        return TestIngredients.port(kind + "|" + id, perCraft);
    }

    static List<String> names(final List<Port<?>> ports) {
        return ports.stream()
            .map(TestIngredients::nameOf)
            .toList();
    }
}
