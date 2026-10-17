package com.gtnhplanner.cloud.map;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.data.flowchart.BoardGroup;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Edge;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Group;
import com.gtnhplanner.data.flowchart.Groups;
import com.gtnhplanner.data.flowchart.MachineGroup;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.data.flowchart.Port;
import com.gtnhplanner.data.flowchart.Serializer;
import com.gtnhplanner.data.flowchart.Tasks;
import com.gtnhplanner.data.flowchart.balancer.SolveInput;
import com.gtnhplanner.dev.SolveInputJson;
import com.gtnhplanner.importer.FakeGame;
import com.gtnhplanner.importer.FfImport;

/**
 * Boards to map, headless: the solver corpus's real plans (their solve inputs, rebuilt as boards with every port named
 * as {@link FakeGame} names them) and the website's plans as the importer brings them in, each dressed with what a
 * player adds that the website has no place for.
 */
public final class Boards {

    /** The plans written from the game, under {@code /solver-corpus/}. */
    static final String[] CORPUS = { "alumina-line-shared-reactor", "benzene", "buffer-drawers", "full-platline",
        "glass", "hv-diesel", "mega-iv-nitrobenzene-power-plant", "mv", "oxygen-silicon-loop", "pa-cell-loop",
        "platline", "single-card-v2", "sulfuric-acid", "titanium-line-chembath", "titanium-line-tour" };

    /** The website's plans, under {@code /factory-flow/}. */
    static final String[] WEBSITE = { "alumina-line-shared-reactor", "biodiesel-demo", "early-baux-ti-w-boards",
        "high-amperage-reactor", "pa-cell-loop-plan", "passive-chemicals-collapsed-boards",
        "titanium-line-chembath.slim", "titanium-line-posted-alpha5" };

    private Boards() {}

    static JsonObject json(final String resource) {
        try (InputStream in = Boards.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalArgumentException(resource);
            return new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8))
                .getAsJsonObject();
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static JsonObject website(final String name) {
        return json("/factory-flow/" + name + ".json");
    }

    /** A website plan as the importer brings it in. */
    static Graph imported(final String name, final FakeGame game) {
        return FfImport.importFromText(FakeGame.fixture(name + ".json"), game, game)
            .graph();
    }

    /** A corpus plan as a board: its cards, wires, drawers, shared machines and spare shares, laid out in rows. */
    public static Graph corpus(final String name) {
        final Graph g = new Graph(name);
        corpusInto(g, name, json("/solver-corpus/" + name + ".json"), 0);
        return g;
    }

    /**
     * {@code copies} of a corpus plan on one board, each with ids of its own and below the one before: a plan of many
     * cards, for timing.
     */
    public static Graph corpusCopies(final String name, final int copies) {
        final String text = json("/solver-corpus/" + name + ".json").toString();
        final java.util.regex.Pattern uuid = java.util.regex.Pattern
            .compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        final Graph g = new Graph(name);
        for (int k = 0; k < copies; k++) {
            final java.util.regex.Matcher m = uuid.matcher(text);
            final StringBuilder out = new StringBuilder();
            while (m.find()) m.appendReplacement(
                out,
                UUID.nameUUIDFromBytes((k + ":" + m.group()).getBytes(StandardCharsets.UTF_8))
                    .toString());
            m.appendTail(out);
            corpusInto(
                g,
                name + " " + k,
                new JsonParser().parse(out.toString())
                    .getAsJsonObject(),
                k * 4000);
        }
        return g;
    }

    private static void corpusInto(final Graph g, final String name, final JsonObject json, final int dy) {
        com.gtnhplanner.harness.GtnhFlowLoader.ensureDefaultMachineProfile();
        final SolveInput in = SolveInputJson.read(json);
        g.setBalanceMode(in.mode());
        int i = 0;
        for (final SolveInput.Machine m : in.machines()) {
            // Places off the board's grid too: the mapping must keep every pixel.
            final Node n = new Node(m.id(), 37 + i % 6 * 361, dy + 41 + i / 6 * 263);
            n.machineName = m.name();
            for (final SolveInput.PortIn p : m.inputs()) n.inputs.add(port(p));
            for (final SolveInput.PortIn p : m.outputs()) n.outputs.add(port(p));
            n.properties.put(FakeGame.DURATION, (int) Math.round(m.durationTicks()));
            n.properties.put(FakeGame.EU_PER_TICK, Math.round(m.energyPerTick()));
            n.machineConfig.setMachineCount(m.machineCount());
            n.setMachineCountFixed(m.countFixed());
            n.targetOutputRates.putAll(m.targetOutputRates());
            n.targetInputRates.putAll(m.targetInputRates());
            g.addNode(n);
            i++;
        }
        final List<Edge> edges = new ArrayList<>();
        for (final SolveInput.EdgeIn e : in.edges())
            edges.add(new Edge(e.id(), e.source(), e.target(), e.sourceOutput(), e.targetInput()));
        g.addEdgesLoaded(edges);
        int j = 0;
        for (final SolveInput.DrawerIn d : in.drawers()) {
            String key = "item:" + d.label() + ":0";
            if (!d.links()
                .isEmpty()) {
                final Drawer.Link l = d.links()
                    .getFirst();
                final Node n = g.nodes.get(l.nodeId());
                final List<Port<?>> side = d.kind()
                    .linksInputs() ? n.inputs : n.outputs;
                if (l.portIndex() < side.size()) key = new FakeGame().describe(side.get(l.portIndex()))
                    .key();
            }
            final Drawer drawer = new Drawer(d.id(), d.kind(), key);
            drawer.setLabel(d.label());
            drawer.setTarget(d.rule(), d.rate());
            drawer.setX(-303);
            drawer.setY(dy + 13 + j++ * 97);
            for (final Drawer.Link l : d.links()) drawer.addLink(l);
            // A buffer's users: the input ports it feeds.
            for (final Drawer.Link l : d.takers()) drawer.addPort(l, true);
            g.addDrawer(drawer);
        }
        for (final SolveInput.PoolIn p : in.pools()) {
            final MachineGroup group = new MachineGroup(
                UUID.nameUUIDFromBytes(
                    (name + ":pool:"
                        + p.machines()
                            .getFirst()).getBytes(StandardCharsets.UTF_8)));
            for (final UUID member : p.machines()) group.addSection(member);
            group.setMachineCapacity(p.capacity());
            group.setPinned(p.exact());
            g.groups.put(group.getId(), group);
        }
        for (final Map.Entry<String, Map<String, Double>> s : in.spares()
            .entrySet()) g.setSpareSplit(s.getKey(), s.getValue());
    }

    private static Port<?> port(final SolveInput.PortIn p) {
        return TestGame.port("item", p.name(), p.amount() * p.chance());
    }

    /**
     * What a player adds that the website has no place for: a note, a plain group, setting pins, a place in the world,
     * an input's target rate, a setting only the game has, a pin list, and a task board; a description and an icon.
     * And what it has a place for that the importer does not make: groups (one in another, the inner one collapsed)
     * round a card, a drawer and the notes, and a drawing of every kind with all it can wear.
     */
    public static Graph dress(final Graph g) {
        final List<Node> nodes = new ArrayList<>(g.getNodes());
        final Node first = nodes.getFirst();
        first.worldLinks.add(new int[] { 0, 100, 64, -200, 2 });
        first.machineConfig.settings.put("tick_modifier", 150);
        first.machineConfig.settings.put("machine:circuit", "3");
        if (!first.inputs.isEmpty()) first.targetInputRates.put(0, 2.5);
        final Node last = nodes.getLast();
        last.x = 1234;
        last.y = -567;
        g.pinList.add(first.id);
        final Note note = new Note(UUID.nameUUIDFromBytes((g.getName() + ":note").getBytes(StandardCharsets.UTF_8)));
        note.setX(-101);
        note.setY(-77);
        note.setWidth(213);
        note.setHeight(131);
        note.setColor("lime");
        note.setFontSize(18);
        note.setJoined("Feed it\nfrom the ME system");
        g.notes.put(note.getId(), note);
        drawings(g);
        groups(g, first, note);
        final Group frame = Serializer.GSON.fromJson(
            "{\"id\":\"" + UUID.nameUUIDFromBytes((g.getName() + ":frame").getBytes(StandardCharsets.UTF_8))
                + "\",\"type\":\"group\",\"x\":5,\"y\":6,\"width\":700,\"height\":400,\"header\":\"Ore line\"}",
            Group.class);
        frame.getNodeIds()
            .add(first.id);
        g.groups.put(frame.getId(), frame);
        g.settingPins.put("item:gregtech:gt.blockmachines:1000", new ArrayList<>(List.of("voltage", "amp")));
        g.setDescription("Made in the game");
        g.setIcon("item:minecraft:iron_ingot:0");
        g.setPrimaryView(Tasks.PLAN);
        g.tasks.add(
            new Tasks.Task(
                "t1",
                "Build " + g.getName(),
                "Two lines first.",
                Tasks.Status.DOING,
                "a0",
                List.of("11111111-1111-4111-8111-111111111111"),
                List.of(new Tasks.Link("11111111-1111-4111-8111-111111111111:plan", first.id.toString())),
                "11111111-1111-4111-8111-111111111111",
                "2026-10-14T10:00:00.000Z",
                null));
        // A task waiting on the first: its blockedBy goes to the account and back.
        g.tasks.add(
            new Tasks.Task(
                "t1b",
                "Power " + g.getName(),
                null,
                Tasks.Status.TODO,
                "a1",
                List.of(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                List.of("t1")));
        g.taskItems.add(
            new Tasks.Item(
                "i1",
                "t1",
                "Place the machines",
                false,
                "a0",
                new Tasks.Link("11111111-1111-4111-8111-111111111111:plan", first.id.toString())));
        // A spot in a world and a screenshot go to the account as the plan's own fields.
        g.taskItems.add(
            new Tasks.Item(
                "i2",
                "t1",
                Tasks.LOCATION,
                "Ore field at 1 64 -2 in Overworld",
                true,
                "a1",
                null,
                new Tasks.Place(1, 64, -2, 0, "Overworld", "server:0123456789abcdef", "Ore field"),
                null,
                null));
        g.taskItems.add(
            new Tasks.Item(
                "i3",
                "t1",
                Tasks.PICTURE,
                "Screenshot",
                false,
                "a2",
                null,
                null,
                new Tasks.Picture("data:image/jpeg;base64,/9j/AA==", 1920, 1080, "2026-10-15T09:00:00.000Z", null),
                null));
        // A quest in the quest book: game only, carried under the thing so the website keeps it.
        g.taskItems.add(
            new Tasks.Item(
                "i4",
                "t1",
                Tasks.QUEST,
                "Steam Age",
                false,
                "a3",
                null,
                null,
                null,
                new Tasks.Quest(
                    "0f2a43a5-6c1e-4d7a-9b3b-6a8f3b0e2c11",
                    "Steam Age",
                    "item:IC2:itemToolBronzePickaxe:0",
                    "Tier 1 - Steam")));
        g.taskComments.add(
            new Tasks.Comment(
                "c1",
                "t1",
                "11111111-1111-4111-8111-111111111111",
                "ann",
                "2026-10-14T10:05:00Z",
                "On it"));
        return g;
    }

    private static UUID id(final Graph g, final String what) {
        return UUID.nameUUIDFromBytes((g.getName() + ":" + what).getBytes(StandardCharsets.UTF_8));
    }

    /** A box, a zone, an arrow and a picture, each with every field its kind keeps, at places off the grid. */
    static void drawings(final Graph g) {
        final Note box = new Note(id(g, "box"));
        box.setKind(Note.BOX);
        box.setX(-411);
        box.setY(-233);
        box.setWidth(377);
        box.setHeight(151);
        box.setColor("orange");
        box.setJoined("");
        box.setBorder("dashed");
        box.setBorderColor("red");
        box.setFillColor("light_blue");
        box.setFill("solid");
        box.setMarks("graph");
        g.notes.put(box.getId(), box);
        final Note zone = new Note(id(g, "zone"));
        zone.setKind(Note.ZONE);
        zone.setX(1503);
        zone.setY(-91);
        zone.setWidth(203);
        zone.setHeight(117);
        zone.setColor("lime");
        zone.setPoints(new ArrayList<>(List.of(new int[] { 0, 0 }, new int[] { 203, 13 }, new int[] { 97, 117 })));
        zone.setMarks("hatch");
        g.notes.put(zone.getId(), zone);
        final Note arrow = new Note(id(g, "arrow"));
        arrow.setKind(Note.ARROW);
        arrow.setX(-57);
        arrow.setY(-143);
        // A level arrow: no height.
        arrow.setWidth(163);
        arrow.setHeight(0);
        arrow.setColor("magenta");
        arrow.setArrowDirection("up-left");
        arrow.setArrowHeads("both");
        arrow.setArrowWidth(4);
        g.notes.put(arrow.getId(), arrow);
        final Note picture = new Note(id(g, "picture"));
        picture.setKind(Note.IMAGE);
        picture.setX(2011);
        picture.setY(733);
        picture.setWidth(160);
        picture.setHeight(90);
        picture.setColor("");
        picture.setJoined("The finished line");
        picture.setImageUrl("https://example.com/line.png");
        g.notes.put(picture.getId(), picture);
    }

    /**
     * Two groups, the website's pockets: an open one round a drawer and the box, and inside it a collapsed one, from
     * an imported plan (its website id kept), round the first card, the note and the arrow. Frames off the grid.
     */
    static void groups(final Graph g, final Node card, final Note note) {
        final BoardGroup outer = new BoardGroup(id(g, "outer"));
        outer.setName("Ore line");
        outer.setColor("orange");
        outer.setTheme("blueprint");
        outer.setPattern("dots");
        outer.setX(-523);
        outer.setY(-311);
        outer.setWidth(2741);
        outer.setHeight(1307);
        g.boardGroups.put(outer.getId(), outer);
        final BoardGroup inner = new BoardGroup(id(g, "inner"));
        inner.setName("Smelting");
        inner.setFfId("pocket-imported-smelting");
        inner.setCollapsed(true);
        inner.setX(-117);
        inner.setY(-199);
        inner.setWidth(613);
        inner.setHeight(421);
        g.boardGroups.put(inner.getId(), inner);
        Groups.setGroupOf(g, inner.getId(), outer.getId());
        Groups.setGroupOf(g, card.id, inner.getId());
        Groups.setGroupOf(g, note.getId(), inner.getId());
        Groups.setGroupOf(g, id(g, "arrow"), inner.getId());
        Groups.setGroupOf(g, id(g, "box"), outer.getId());
        if (!g.drawers.isEmpty()) Groups.setGroupOf(g, g.drawers.firstKey(), outer.getId());
    }

    /**
     * Everything a board saves that a shared plan carries: its save, the camera and the pin list (each player's own)
     * left out, and its description and icon.
     */
    public static JsonObject persisted(final Graph g) {
        final JsonObject o = Serializer.toJson(g);
        o.remove("zoom");
        o.remove("panX");
        o.remove("panY");
        o.remove("pinList");
        o.addProperty("description", g.getDescription());
        if (g.getIcon() != null) o.addProperty("icon", g.getIcon());
        return o;
    }
}
