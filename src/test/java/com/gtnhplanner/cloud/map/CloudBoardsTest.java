package com.gtnhplanner.cloud.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.cloud.sync.LiveJson;
import com.gtnhplanner.cloud.sync.LiveOp;
import com.gtnhplanner.cloud.sync.LiveOps;
import com.gtnhplanner.data.flowchart.BoardGroup;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Groups;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.library.PlanExport;

/**
 * Groups (the website's boards, {@code pockets}), every kind of drawing ({@code annotations}) and buffer drawers in a
 * shared plan: each with its own id both ways, the game's data on it, a place measured from its group's frame, and
 * each edit the fields it touched.
 */
class CloudBoardsTest {

    /** One open plan in the game: the board, the plan it shows and what that was read as. */
    private static final class Open {

        final TestGame game;
        Graph board;
        JsonObject plan;
        Basis basis;

        Open(final Graph g) {
            this(g, new TestGame());
        }

        Open(final Graph g, final TestGame with) {
            game = with.knowing(g);
            plan = CloudPlans.write(g, game, null, null)
                .plan();
            read(plan);
        }

        /** The plan as the website left it, read onto the board. */
        void read(final JsonObject p) {
            plan = p;
            final CloudPlans.Read r = CloudPlans.read(p, game);
            board = r.graph();
            basis = r.basis();
        }

        /** The board written against the plan: the batch it sends, which undoes and reads back as the board. */
        List<LiveOp> send(final String what) {
            final CloudPlans.Written w = CloudPlans.write(board, game, plan, basis);
            final List<LiveOp> ops = CloudPlans.diff(plan, w.plan());
            final JsonObject applied = LiveOps.apply(Derived.plan(plan), ops);
            assertTrue(LiveOps.plansEqual(applied, Derived.plan(w.plan())), what + ": the batch makes the plan");
            final List<LiveOp> undo = LiveOps.invert(Derived.plan(plan), ops);
            assertTrue(LiveOps.plansEqual(LiveOps.apply(applied, undo), Derived.plan(plan)), what + ": and undoes");
            final CloudPlans.Read r = CloudPlans.read(w.plan(), game.knowing(board));
            assertEquals(
                Canon.of(Boards.persisted(board)),
                Canon.of(Boards.persisted(r.graph())),
                what + ": " + CloudPlansTest.firstDifference(Boards.persisted(board), Boards.persisted(r.graph())));
            plan = w.plan();
            basis = w.basis();
            return ops;
        }

        BoardGroup group(final String name) {
            for (final BoardGroup g : board.boardGroups.values()) if (name.equals(g.getName())) return g;
            throw new IllegalArgumentException(name);
        }

        JsonObject thing(final String c, final String id) {
            for (final JsonElement e : LiveOps.collection(plan, c))
                if (id.equals(LiveJson.idOf(e))) return e.getAsJsonObject();
            throw new IllegalArgumentException(c + " " + id);
        }
    }

    private static Open dressed(final String corpus) {
        return new Open(Boards.dress(Boards.corpus(corpus)));
    }

    // region Groups

    @Test
    void aGroupIsAPocketItsMembersInItsFrame() {
        final Open o = dressed("platline");
        final BoardGroup outer = o.group("Ore line"), inner = o.group("Smelting");
        final JsonObject outerJson = o.thing(
            "pockets",
            outer.getId()
                .toString());
        // The game's own group under its UUID; the imported one keeps its UUID too, its website id in its data.
        final JsonObject innerJson = o.thing(
            "pockets",
            inner.getId()
                .toString());
        assertEquals("Ore line", PlanRead.str(outerJson, "name"));
        assertEquals(
            true,
            outerJson.get("expanded")
                .getAsBoolean());
        assertEquals(
            false,
            innerJson.get("expanded")
                .getAsBoolean());
        assertEquals(
            outer.getId()
                .toString(),
            PlanRead.str(innerJson, "parentPocketId"));
        assertEquals("blueprint", PlanRead.str(outerJson, "theme"));
        assertEquals(
            "pocket-imported-smelting",
            innerJson.getAsJsonObject("gtnhplanner")
                .getAsJsonObject("game")
                .get("ffId")
                .getAsString());
        assertTrue(outerJson.has("gtnhplanner"));
        // Positions from the frame's corner, at the site's scale: the inner group's corner in the outer's frame.
        assertEquals(
            PlanExport.position(inner.getX() - outer.getX(), inner.getY() - outer.getY()),
            innerJson.get("position"));
        final Node card = memberCard(o.board, inner);
        final JsonObject cardJson = o.thing("nodes", card.id.toString());
        assertEquals(
            inner.getId()
                .toString(),
            PlanRead.str(cardJson, "pocketId"));
        assertEquals(PlanExport.position(card.x - inner.getX(), card.y - inner.getY()), cardJson.get("position"));
        // The game's data says nothing of the group: the plan's pocketId does.
        assertFalse(
            cardJson.getAsJsonObject("gtnhplanner")
                .getAsJsonObject("game")
                .has("group"));
        assertEquals(
            o.basis.planId("pocket", inner.getId()),
            inner.getId()
                .toString());
    }

    @Test
    void aGroupMovedInTheGameMovesOnlyItsPocket() {
        final Open o = dressed("platline");
        final BoardGroup outer = o.group("Ore line");
        // As the board carries a group: it and everything in it, however deep.
        move(o.board, outer.getId(), 37, -23);
        final List<LiveOp> ops = o.send("a group moved");
        assertEquals(1, ops.size(), ops.toString());
        assertEquals(
            "pockets",
            ops.getFirst()
                .c());
        assertEquals("[\"position\"]", fields(ops.getFirst()));
    }

    @Test
    void thingsGoInAndOutOfGroupsAsTheirPocketId() {
        final Open o = dressed("sulfuric-acid");
        final BoardGroup inner = o.group("Smelting");
        final Node card = outside(o.board);
        Groups.setGroupOf(o.board, card.id, inner.getId());
        List<LiveOp> ops = o.send("a card dropped into a group");
        assertEquals(1, ops.size(), ops.toString());
        assertEquals("[\"pocketId\",\"position\"]", fields(ops.getFirst()));
        Groups.setGroupOf(o.board, card.id, null);
        ops = o.send("and out again");
        assertEquals(1, ops.size(), ops.toString());
        assertEquals("[\"pocketId\",\"position\"]", fields(ops.getFirst()));
        assertNull(
            o.thing("nodes", card.id.toString())
                .get("pocketId"));
    }

    @Test
    void aGroupsOwnEditsAreItsPocketsFields() {
        final Open o = dressed("platline");
        final BoardGroup outer = o.group("Ore line"), inner = o.group("Smelting");
        outer.setName("Ore and dust");
        List<LiveOp> ops = o.send("a rename");
        assertEquals(1, ops.size(), ops.toString());
        assertEquals("[\"gtnhplanner\",\"name\"]", fields(ops.getFirst()));
        inner.setCollapsed(false);
        ops = o.send("opened");
        assertEquals("[\"expanded\",\"gtnhplanner\"]", fields(ops.getFirst()));
        outer.setColor("lime");
        ops = o.send("a colour");
        assertEquals("[\"colorTag\",\"gtnhplanner\"]", fields(ops.getFirst()));
        outer.setWidth(outer.getWidth() + 200);
        ops = o.send("a bigger frame");
        assertEquals("[\"gtnhplanner\",\"size\"]", fields(ops.getFirst()));

        // Ungrouped: the pocket goes, what it held is in the group it sat in, where it stands.
        final List<UUID> held = Groups.children(o.board, inner.getId());
        Groups.ungroup(o.board, inner.getId());
        ops = o.send("ungrouped");
        assertEquals(1, count(ops, LiveOp.Kind.DEL, "pockets"), ops.toString());
        assertEquals(held.size(), ops.size() - 1, ops.toString());
        for (final UUID id : held) assertEquals(outer.getId(), Groups.groupOf(o.board, id));

        // A new group round a drawing.
        final BoardGroup made = new BoardGroup();
        made.setName("Notes");
        made.setX(-700);
        made.setY(-700);
        made.setWidth(300);
        made.setHeight(200);
        o.board.boardGroups.put(made.getId(), made);
        final Note zone = drawing(o.board, Note.ZONE);
        Groups.setGroupOf(o.board, zone.getId(), made.getId());
        ops = o.send("a new group");
        assertEquals(1, count(ops, LiveOp.Kind.PUT, "pockets"), ops.toString());
        assertEquals(
            made.getId()
                .toString(),
            PlanRead.str(
                o.thing(
                    "annotations",
                    zone.getId()
                        .toString()),
                "pocketId"));
    }

    @Test
    void aCardMovedIntoAPocketOnTheWebsiteStaysTheGamesOwn() {
        final Open o = dressed("platline");
        final BoardGroup outer = o.group("Ore line");
        final Node card = outside(o.board);
        card.worldLinks.add(new int[] { 0, 1, 2, 3, 4 });
        card.machineConfig.settings.put("tick_modifier", 175);
        o.send("a placed machine and a setting of the game's");
        final String own = Canon.of(Fresh.nodeGame(card, o.game.world()));
        final JsonObject f = new JsonObject();
        f.addProperty(
            "pocketId",
            outer.getId()
                .toString());
        final JsonObject at = new JsonObject();
        at.addProperty("x", 60);
        at.addProperty("y", 100.5);
        f.add("position", at);
        final JsonObject edited = LiveOps.apply(o.plan, List.of(LiveOp.patch("nodes", card.id.toString(), f, null)));
        o.read(edited);
        final Node there = o.board.nodes.get(card.id);
        assertEquals(outer.getId(), there.group);
        assertEquals(outer.getX() + PlanExport.gameX(60), there.x);
        assertEquals(outer.getY() + PlanExport.gameY(100.5), there.y);
        assertEquals(
            own,
            Canon.of(Fresh.nodeGame(there, o.game.world())),
            "its game data read, not the importer's way");
        assertEquals(List.of(), o.send("written back"));
    }

    @Test
    void theWebsitesPocketsAreTheBoardsGroups() {
        for (final String name : List.of("early-baux-ti-w-boards", "passive-chemicals-collapsed-boards")) {
            final JsonObject site = Boards.website(name);
            final Open o = new Open(new Graph(name));
            o.read(site);
            final List<JsonObject> pockets = PlanRead.objects(site, "pockets");
            assertEquals(pockets.size(), o.board.boardGroups.size(), name);
            final Map<String, JsonObject> cards = new java.util.HashMap<>();
            for (final JsonObject n : PlanRead.objects(site, "nodes")) cards.put(PlanRead.str(n, "id"), n);
            for (final JsonObject p : pockets) {
                final String id = PlanRead.str(p, "id");
                final BoardGroup g = o.board.boardGroups.get(PlanIds.uuid(id));
                assertNotNull(g, name + " " + id);
                assertEquals(id, g.getFfId(), "the website's id, to post under");
                assertEquals(id, o.basis.planId("pocket", g.getId()));
                assertEquals(PlanRead.str(p, "name"), g.getName());
                assertEquals(
                    !p.get("expanded")
                        .getAsBoolean(),
                    g.isCollapsed());
                assertEquals(
                    PlanExport.gameX(
                        p.getAsJsonObject("position")
                            .get("x")
                            .getAsDouble()),
                    g.getX());
            }
            for (final Node n : o.board.getNodes()) {
                final JsonObject json = cards.get(o.basis.planId("node", n.id));
                if (json == null) continue;
                final String in = PlanRead.str(json, "pocketId");
                if (in == null) continue;
                final BoardGroup g = o.board.boardGroups.get(PlanIds.uuid(in));
                assertEquals(g.getId(), n.group, name);
                assertEquals(
                    g.getX() + PlanExport.gameX(
                        json.getAsJsonObject("position")
                            .get("x")
                            .getAsDouble()),
                    n.x,
                    name);
            }
            // Read and written back unchanged; a note the game puts in a website pocket names its id.
            assertEquals(List.of(), o.send(name + ": nothing"));
            final BoardGroup first = o.board.boardGroups.get(PlanIds.uuid(PlanRead.str(pockets.getFirst(), "id")));
            final Note note = new Note();
            note.setX(first.getX() + 20);
            note.setY(first.getY() + 40);
            note.setJoined("Here");
            note.setGroup(first.getId());
            o.board.notes.put(note.getId(), note);
            final List<LiveOp> ops = o.send(name + ": a note in a website pocket");
            assertEquals(1, ops.size(), ops.toString());
            assertEquals(
                PlanRead.str(pockets.getFirst(), "id"),
                PlanRead.str(
                    ops.getFirst()
                        .v(),
                    "pocketId"));
            assertEquals(
                PlanExport.position(20, 40),
                ops.getFirst()
                    .v()
                    .get("position"));
        }
    }

    /**
     * A board the website never opened (no size) keeps its members in a space of their own; opened in the game, it is
     * written with a size, and each member is written measured from its frame, where the board has it.
     */
    @Test
    void aBoardTheWebsiteNeverOpenedHasItsMembersPlacedInItsFrameOnceTheGameOpensIt() {
        final Open o = dressed("platline");
        final Node card = outside(o.board);
        final JsonObject old = new JsonParser()
            .parse("{\"id\":\"pocket-old\",\"name\":\"Old board\",\"position\":{\"x\":-700,\"y\":-200}}")
            .getAsJsonObject();
        final JsonObject f = new JsonObject();
        f.addProperty("pocketId", "pocket-old");
        f.add("position", PlanExport.position(0, 0));
        o.read(
            LiveOps.apply(
                o.plan,
                List.of(LiveOp.put("pockets", "pocket-old", old), LiveOp.patch("nodes", card.id.toString(), f, null))));
        final BoardGroup board = o.group("Old board");
        assertTrue(board.isCollapsed());
        final Node there = o.board.nodes.get(card.id);
        assertEquals(board.getX() + 20, there.x, "the shift the website gives its members when it opens it");
        assertEquals(board.getY() + 60, there.y);
        assertEquals(List.of(), o.send("read and written back"));
        board.setCollapsed(false);
        o.send("opened in the game");
        assertTrue(
            o.thing("pockets", "pocket-old")
                .has("size"));
        assertEquals(
            PlanExport.position(there.x - board.getX(), there.y - board.getY()),
            o.thing("nodes", card.id.toString())
                .get("position"),
            "measured from the frame now");
    }

    // endregion

    // region Drawings

    @Test
    void everyDrawingTheGameMakesComesBackAsItWas() {
        final Open o = dressed("glass");
        for (final String kind : List.of(Note.BOX, Note.ZONE, Note.ARROW, Note.IMAGE)) {
            final Note n = drawing(o.board, kind);
            final JsonObject json = o.thing(
                "annotations",
                n.getId()
                    .toString());
            assertEquals(kind, PlanRead.str(json, "kind"));
            assertTrue(json.has("gtnhplanner"), kind);
        }
        final JsonObject arrow = o.thing(
            "annotations",
            drawing(o.board, Note.ARROW).getId()
                .toString());
        assertEquals("both", PlanRead.str(arrow, "arrowHeads"));
        assertEquals(
            4,
            arrow.get("arrowWidth")
                .getAsInt());
        assertEquals(
            0,
            arrow.getAsJsonObject("size")
                .get("height")
                .getAsInt(),
            "a level arrow stays level");
        final JsonObject box = o.thing(
            "annotations",
            drawing(o.board, Note.BOX).getId()
                .toString());
        assertEquals(
            "dashed",
            box.getAsJsonObject("style")
                .get("border")
                .getAsString());
        assertEquals(
            "graph",
            box.getAsJsonObject("style")
                .get("marks")
                .getAsString());

        // Each edit of a drawing is its fields.
        final Note zone = drawing(o.board, Note.ZONE);
        zone.setFill("none");
        List<LiveOp> ops = o.send("a zone's fill");
        assertEquals("[\"gtnhplanner\",\"style\"]", fields(ops.getFirst()));
        final Note arrowNote = drawing(o.board, Note.ARROW);
        arrowNote.setArrowHeads(null);
        ops = o.send("one head");
        assertEquals(1, ops.size(), ops.toString());
        assertEquals("[\"arrowHeads\",\"gtnhplanner\"]", fields(ops.getFirst()));
    }

    @Test
    void theWebsitesDrawingsComeOnTheBoardUnderTheirIds() {
        final Open o = dressed("glass");
        final JsonObject plan = LiveJson.copy(o.plan);
        final String[] made = {
            "{\"id\":\"ann-box\",\"kind\":\"box\",\"colorTag\":\"red\",\"position\":{\"x\":95,\"y\":-200},"
                + "\"size\":{\"width\":240,\"height\":160},\"style\":{\"border\":\"dashed\",\"fill\":\"dots\"}}",
            "{\"id\":\"ann-zone\",\"kind\":\"zone\",\"position\":{\"x\":0,\"y\":0},\"size\":{\"width\":100,\"height\":60},"
                + "\"points\":[{\"x\":0,\"y\":0},{\"x\":100,\"y\":0},{\"x\":50,\"y\":60}]}",
            "{\"id\":\"ann-arrow\",\"kind\":\"arrow\",\"colorTag\":\"blue\",\"position\":{\"x\":10,\"y\":10},"
                + "\"size\":{\"width\":190,\"height\":0},\"arrowDirection\":\"down-left\",\"arrowWidth\":1}",
            "{\"id\":\"ann-image\",\"kind\":\"image\",\"text\":\"Base\",\"position\":{\"x\":500,\"y\":500},"
                + "\"size\":{\"width\":200,\"height\":120},\"imageUrl\":\"https://example.com/b.png\"}" };
        for (final String m : made) plan.getAsJsonArray("annotations")
            .add(new JsonParser().parse(m));
        o.read(plan);
        final Note box = o.board.notes.get(PlanIds.uuid("ann-box"));
        assertEquals(Note.BOX, box.kind());
        assertEquals("dashed", box.getBorder());
        assertEquals("tint", box.getFill(), "a legacy mark in the fill is a tint wearing it");
        assertEquals("dots", box.getMarks());
        assertEquals(
            3,
            o.board.notes.get(PlanIds.uuid("ann-zone"))
                .getPoints()
                .size());
        final Note arrow = o.board.notes.get(PlanIds.uuid("ann-arrow"));
        assertEquals(0, arrow.getHeight());
        assertEquals("down-left", arrow.getArrowDirection());
        final Note image = o.board.notes.get(PlanIds.uuid("ann-image"));
        assertTrue(image.isHidden(), "kept, never shown");
        assertEquals("ann-image", o.basis.planId("note", image.getId()));
        assertEquals(List.of(), o.send("read and written back"));
        arrow.setArrowWidth(2);
        final List<LiveOp> ops = o.send("a website arrow made thicker");
        assertEquals(1, ops.size(), ops.toString());
        assertEquals(
            "ann-arrow",
            ops.getFirst()
                .id());
        assertEquals("[\"arrowWidth\",\"gtnhplanner\"]", fields(ops.getFirst()));
    }

    // endregion

    // region Setting pins and leftover splits

    @Test
    void settingPinsAreThePlansOwnFieldInTheWebsitesWords() {
        final Graph g = Boards.dress(Boards.corpus("platline"));
        final Node card = outside(g);
        final String name = card.machineName == null ? "Machine" : card.machineName;
        g.settingPins.put(
            "machine:" + name,
            new ArrayList<>(List.of("machine:coil", "machine:voltage", "machine:machine:circuit")));
        g.settingPins.put("power:steam-boiler", new ArrayList<>(List.of("power:fuel", "reading:output")));
        final TestGame game = new TestGame();
        game.handlers = n -> n.isPower() ? null : "handler:" + (n.machineName == null ? "Machine" : n.machineName);
        final Open o = new Open(g, game);
        final String handler = "handler:" + name;
        JsonObject pins = o.plan.getAsJsonObject("settingPins");
        assertEquals(
            "[\"heatingCoil\",\"circuit\"]",
            pins.get(handler)
                .toString(),
            "the website's words: " + pins);
        assertEquals(
            "[\"fuel\"]",
            pins.get("power:steam-boiler")
                .toString());
        assertFalse(
            pins.has("item:gregtech:gt.blockmachines:1000"),
            "a key the website cannot spell stays in the game's data");

        // The website takes the coil off and pins a machine the board has no card of.
        final JsonObject edited = new JsonObject();
        edited.add(handler, new JsonParser().parse("[\"circuit\"]"));
        edited.add("power:steam-boiler", new JsonParser().parse("[\"fuel\"]"));
        edited.add("handler:elsewhere", new JsonParser().parse("[\"mode\"]"));
        final JsonObject f = new JsonObject();
        f.add("settingPins", edited);
        o.read(LiveOps.apply(o.plan, List.of(LiveOp.meta(f, null))));
        assertEquals(
            List.of("machine:machine:circuit", "machine:voltage"),
            o.board.settingPins.get("machine:" + name),
            "what only the game spells kept");
        assertEquals(List.of("power:fuel", "reading:output"), o.board.settingPins.get("power:steam-boiler"));
        assertEquals(List.of("mode"), o.board.settingPins.get("site:handler:elsewhere"));
        assertEquals(List.of("voltage", "amp"), o.board.settingPins.get("item:gregtech:gt.blockmachines:1000"));
        assertEquals(List.of(), o.send("read and written back"));

        // The game pins the coil again: the plan's field, the website's other machine still there.
        o.board.settingPins.get("machine:" + name)
            .add("machine:coil");
        o.send("the coil pinned");
        pins = o.plan.getAsJsonObject("settingPins");
        assertEquals(
            "[\"circuit\",\"heatingCoil\"]",
            pins.get(handler)
                .toString());
        assertEquals(
            "[\"mode\"]",
            pins.get("handler:elsewhere")
                .toString());
    }

    /** A bee's, a crop's, the TGS's and the vat's settings are the website's own keys on both sides. */
    @Test
    void aModelledMachinesSettingPinsAreTheWebsitesKeys() {
        assertEquals("beeSpeedGene", PlanPins.siteControl("machine:beeSpeedGene"));
        assertEquals("cropGrowthStat", PlanPins.siteControl("machine:cropGrowthStat"));
        assertEquals("tgsHeight", PlanPins.siteControl("machine:tgsHeight"));
        assertEquals("bioVatGlass", PlanPins.siteControl("machine:bioVatGlass"));
        assertEquals("heatingCoil", PlanPins.siteControl("machine:coil"));
        assertEquals("mode", PlanPins.siteControl("machine:machine:mode"));
        assertNull(PlanPins.siteControl("machine:beeHousing"), "the housing is the website's machine");
        assertNull(PlanPins.siteControl("machine:cropHarvester"), "and so is the harvester");
        assertNull(PlanPins.siteControl("machine:voltage"));
        for (final String c : List
            .of("beeSpeedGene", "cropGrowthStat", "tgsHeight", "bioVatGlass", "heatingCoil", "mode"))
            assertEquals(c, PlanPins.siteControl(PlanPins.boardControl(c)), c);
    }

    @Test
    void leftoverSplitsAreThePlansOwnFieldUnderItsIds() {
        final Open o = new Open(new Graph("biodiesel"));
        o.read(Boards.website("biodiesel-demo"));
        final List<Node> cards = new ArrayList<>(o.board.getNodes());
        final Node a = cards.get(0), b = cards.get(1);
        final String ka = a.id + ":o:0", kb = b.id + ":i:0";
        // A leftover is its bearers' keys, sorted and joined.
        final String leftover = ka.compareTo(kb) < 0 ? ka + "|" + kb : kb + "|" + ka;
        o.board.setSpareSplit(leftover, Map.of(ka, 0.25, kb, 0.75));
        o.send("a split made");
        final String pa = o.basis.planId("node", a.id) + ":o:0", pb = o.basis.planId("node", b.id) + ":i:0";
        assertFalse(pa.startsWith(a.id.toString()), "a website card is named by its own id");
        final List<String> bearers = new ArrayList<>(List.of(pa, pb));
        bearers.sort(String::compareTo);
        final JsonObject split = o.plan.getAsJsonObject("spareShares")
            .getAsJsonObject(String.join("|", bearers));
        assertNotNull(
            split,
            o.plan.get("spareShares")
                .toString());
        assertEquals(
            0.75,
            split.get(pb)
                .getAsDouble());

        // The website moves it all to the first card: the board has it under its own ids.
        final JsonObject moved = new JsonObject();
        final JsonObject shares = new JsonObject();
        shares.addProperty(pa, 1.0);
        shares.addProperty(pb, 0.0);
        moved.add(String.join("|", bearers), shares);
        final JsonObject f = new JsonObject();
        f.add("spareShares", moved);
        o.read(LiveOps.apply(o.plan, List.of(LiveOp.meta(f, null))));
        assertEquals(Map.of(ka, 1.0, kb, 0.0), o.board.spareShares.get(leftover));
        assertEquals(List.of(), o.send("read and written back"));
    }

    // endregion

    // region Drawers the website edits

    @Test
    void aSourcesRuleAndTrashAreTheWebsitesToo() {
        final Open o = dressed("platline");
        Drawer source = null, product = null;
        for (final Drawer d : o.board.getDrawers()) {
            if (d.getKind() == Drawer.Kind.SOURCE && source == null) source = d;
            if (d.getKind() == Drawer.Kind.PRODUCT && product == null) product = d;
        }
        assertNotNull(source);
        assertNotNull(product);
        source.setTarget(Drawer.Rule.AT_MOST, 5);
        product.setKind(Drawer.Kind.TRASH);
        o.send("a source's rule and a product made trash");
        JsonObject s1 = o.thing(
            "storages",
            source.getId()
                .toString());
        assertEquals(
            -5.0,
            s1.get("targetPerSecond")
                .getAsDouble(),
            "the site writes a source's rate negative");
        assertEquals("at-most", PlanRead.str(s1, "targetMode"));
        assertEquals(
            "trash",
            PlanRead.str(
                o.thing(
                    "storages",
                    product.getId()
                        .toString()),
                "drainMode"));

        // The website sets the source's rule: the board has it.
        final JsonObject f = new JsonObject();
        f.addProperty("targetPerSecond", -8);
        f.addProperty("targetMode", "exact");
        o.read(
            LiveOps.apply(
                o.plan,
                List.of(
                    LiveOp.patch(
                        "storages",
                        source.getId()
                            .toString(),
                        f,
                        null))));
        final Drawer there = o.board.drawers.get(source.getId());
        assertEquals(Drawer.Rule.EXACTLY, there.getRule());
        assertEquals(8.0, there.getRate());
        assertEquals(
            Drawer.Kind.TRASH,
            o.board.drawers.get(product.getId())
                .getKind());
        assertEquals(List.of(), o.send("read and written back"));
    }

    // endregion

    // region Port rates

    @Test
    void portRatesArePlanModesPinsBothWays() {
        final Open o = dressed("platline");
        final Node card = outside(o.board);
        card.targetOutputRates.clear();
        card.targetInputRates.clear();
        card.targetOutputRates.put(0, 3.5);
        if (!card.inputs.isEmpty()) card.targetInputRates.put(0, 1.25);
        List<LiveOp> ops = o.send("rates set on a card");
        assertEquals(1, ops.size(), ops.toString());
        assertTrue(fields(ops.getFirst()).contains("\"portRates\""), ops.toString());
        final PlanExport.Res out = o.game.port(card.outputs.getFirst());
        final String outHandle = "output:" + out.kind()
            + ":"
            + java.net.URLEncoder.encode(out.id(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        final JsonObject rates = o.thing("nodes", card.id.toString())
            .getAsJsonObject("portRates");
        assertEquals(
            3.5,
            rates.get(outHandle)
                .getAsDouble(),
            rates.toString());

        // The website sets the rate: the card has it, the input's stays.
        final JsonObject changed = LiveJson.copy(rates);
        changed.addProperty(outHandle, 7);
        final JsonObject f = new JsonObject();
        f.add("portRates", changed);
        o.read(LiveOps.apply(o.plan, List.of(LiveOp.patch("nodes", card.id.toString(), f, null))));
        Node there = o.board.nodes.get(card.id);
        assertEquals(7.0, there.targetOutputRates.get(0));
        if (!card.inputs.isEmpty()) assertEquals(1.25, there.targetInputRates.get(0));
        assertEquals(List.of(), o.send("read and written back"));

        // ...and takes every rate off: none comes back from the game's data.
        o.read(
            LiveOps.apply(
                o.plan,
                List.of(LiveOp.patch("nodes", card.id.toString(), new JsonObject(), List.of("portRates")))));
        there = o.board.nodes.get(card.id);
        assertTrue(there.targetOutputRates.isEmpty(), there.targetOutputRates.toString());
        assertTrue(there.targetInputRates.isEmpty(), there.targetInputRates.toString());
        assertEquals(List.of(), o.send("rates gone"));
    }

    // endregion

    // region Buffers

    @Test
    void aBufferIsItsStorageItsMakersWiresAndAWireToEachUser() {
        final Open o = new Open(Boards.corpus("buffer-drawers"));
        final List<Drawer> buffers = new ArrayList<>();
        for (final Drawer d : o.board.getDrawers()) if (d.isBuffer()) buffers.add(d);
        assertEquals(3, buffers.size());
        for (final Drawer d : buffers) {
            final JsonObject s = o.thing(
                "storages",
                d.getId()
                    .toString());
            assertFalse(s.has("drainMode"), "its role is its wires'");
            for (final Drawer.Link l : d.getLinks()) {
                final JsonObject wire = o.thing("edges", d.getId() + ":" + l.nodeId() + ":" + l.portIndex());
                assertEquals(
                    l.nodeId()
                        .toString(),
                    PlanRead.str(wire, "source"));
                assertEquals(
                    d.getId()
                        .toString(),
                    PlanRead.str(wire, "target"));
            }
            for (final Drawer.Link l : d.getTakers()) {
                final JsonObject wire = o.thing("edges", d.getId() + ":" + l.nodeId() + ":in:" + l.portIndex());
                assertEquals(
                    d.getId()
                        .toString(),
                    PlanRead.str(wire, "source"));
                assertEquals(
                    l.nodeId()
                        .toString(),
                    PlanRead.str(wire, "target"));
            }
        }
        assertTrue(
            o.plan.get("planMode")
                .getAsBoolean(),
            "a buffer's rule is live in the game's plans");
    }

    @Test
    void aDrawerTheWebsiteWiresBothWaysIsABuffer() {
        final Open o = new Open(Boards.corpus("buffer-drawers"));
        final Drawer buffer = o.board.getDrawers()
            .iterator()
            .next();
        final Drawer.Link user = buffer.getTakers()
            .getFirst();
        final String userWire = buffer.getId() + ":" + user.nodeId() + ":in:" + user.portIndex();
        // The website takes the users' wires away: what is left is fed only, a product.
        final List<LiveOp> cut = new ArrayList<>();
        for (final Drawer.Link l : buffer.getTakers())
            cut.add(LiveOp.del("edges", buffer.getId() + ":" + l.nodeId() + ":in:" + l.portIndex()));
        final JsonObject wire = LiveJson.copy(o.thing("edges", userWire));
        o.read(LiveOps.apply(o.plan, cut));
        assertEquals(
            Drawer.Kind.PRODUCT,
            o.board.drawers.get(buffer.getId())
                .getKind());
        assertEquals(List.of(), o.send("a product now"));
        // ...and wires one user back, a wire of its own: a buffer again, with that user.
        wire.addProperty("id", "wire-1");
        wire.remove("gtnhplanner");
        o.read(LiveOps.apply(o.plan, List.of(LiveOp.put("edges", "wire-1", wire))));
        final Drawer again = o.board.drawers.get(buffer.getId());
        assertTrue(again.isBuffer());
        assertEquals(List.of(user), again.getTakers());
        assertEquals(List.of(), o.send("a buffer again"));
        // A user taken off in the game is that wire gone, under the website's id.
        o.board.unlinkDrawerPort(again.getId(), user, true);
        final List<LiveOp> ops = o.send("its user unlinked");
        assertEquals(1, count(ops, LiveOp.Kind.DEL, "edges"), ops.toString());
        assertTrue(
            ops.stream()
                .anyMatch(op -> op.o() == LiveOp.Kind.DEL && "wire-1".equals(op.id())),
            ops.toString());
    }

    @Test
    void aSourceThatBecomesABufferKeepsItsWires() {
        final Open o = new Open(Boards.dress(Boards.corpus("platline")));
        Drawer source = null;
        for (final Drawer d : o.board.getDrawers()) if (d.getKind() == Drawer.Kind.SOURCE && !d.getLinks()
            .isEmpty()) {
                source = d;
                break;
            }
        assertNotNull(source);
        // A card's output made a maker: the source is a buffer, its links its users.
        Node maker = null;
        for (final Node n : o.board.getNodes())
            if (!n.outputs.isEmpty() && o.board.drawerAt(n.id, 0, false) == null) maker = n;
        assertNotNull(maker);
        o.board.linkDrawerPort(source.getId(), new Drawer.Link(maker.id, 0), false);
        assertTrue(source.isBuffer());
        final List<LiveOp> ops = o.send("a maker on a source");
        assertEquals(0, count(ops, LiveOp.Kind.DEL, "edges"), "the users' wires stay: " + ops);
        assertEquals(1, count(ops, LiveOp.Kind.PUT, "edges"), ops.toString());
    }

    @Test
    void aBufferWiredToAnotherDrawerStaysInThePlan() {
        final Open o = new Open(Boards.corpus("buffer-drawers"));
        final Drawer buffer = o.board.getDrawers()
            .iterator()
            .next();
        final Drawer other = new ArrayList<>(o.board.getDrawers()).get(1);
        final JsonObject wire = PlanExport.edge(
            "drawer-to-drawer",
            buffer.getId()
                .toString(),
            other.getId()
                .toString(),
            Fresh.fromKey("item:x:0", "X"));
        final JsonObject plan = LiveOps.apply(o.plan, List.of(LiveOp.put("edges", "drawer-to-drawer", wire)));
        o.read(plan);
        assertNull(o.board.drawers.get(buffer.getId()), "not on the board");
        final CloudPlans.Written w = CloudPlans.write(o.board, o.game, plan, o.basis);
        assertEquals(List.of(), CloudPlans.diff(plan, w.plan()), "and kept as it was");
    }

    // endregion

    // region Helpers

    /** Moves a group and everything in it, as the board carries one. */
    private static void move(final Graph g, final UUID group, final int dx, final int dy) {
        final BoardGroup b = g.boardGroups.get(group);
        b.setX(b.getX() + dx);
        b.setY(b.getY() + dy);
        for (final UUID id : Groups.descendants(g, group)) {
            final Node n = g.nodes.get(id);
            if (n != null) {
                n.x += dx;
                n.y += dy;
            }
            final Drawer d = g.drawers.get(id);
            if (d != null) {
                d.setX(d.getX() + dx);
                d.setY(d.getY() + dy);
            }
            final Note note = g.notes.get(id);
            if (note != null) {
                note.setX(note.getX() + dx);
                note.setY(note.getY() + dy);
            }
            final BoardGroup inner = g.boardGroups.get(id);
            if (inner != null) {
                inner.setX(inner.getX() + dx);
                inner.setY(inner.getY() + dy);
            }
        }
    }

    private static Node memberCard(final Graph g, final BoardGroup b) {
        for (final Node n : g.getNodes()) if (b.getId()
            .equals(n.group)) return n;
        throw new IllegalStateException("no card in " + b.getName());
    }

    /** A card in no group (and in no shared machine). */
    private static Node outside(final Graph g) {
        final Set<UUID> shared = new java.util.HashSet<>();
        for (final com.gtnhplanner.data.flowchart.Group group : g.getGroups())
            if (group instanceof final com.gtnhplanner.data.flowchart.MachineGroup m) shared.addAll(m.getSections());
        for (final Node n : g.getNodes()) if (n.group == null && !shared.contains(n.id)) return n;
        throw new IllegalStateException("every card is in a group");
    }

    private static Note drawing(final Graph g, final String kind) {
        for (final Note n : g.getNotes()) if (kind.equals(n.kind())) return n;
        throw new IllegalStateException(kind);
    }

    private static int count(final List<LiveOp> ops, final LiveOp.Kind kind, final String c) {
        int n = 0;
        for (final LiveOp op : ops) if (op.o() == kind && c.equals(op.c())) n++;
        return n;
    }

    private static String fields(final LiveOp op) {
        final List<String> names = new ArrayList<>();
        for (final Map.Entry<String, JsonElement> e : op.f()
            .entrySet()) names.add(e.getKey());
        names.addAll(op.deletes());
        names.sort(String::compareTo);
        return new com.google.gson.Gson().toJson(names);
    }

    // endregion
}
