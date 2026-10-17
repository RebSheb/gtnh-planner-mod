package com.gtnhplanner.ui.tutorial;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;

import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.nei.PlanMenu;
import com.gtnhplanner.ui.BoardScreen;
import com.gtnhplanner.ui.Planner;
import com.gtnhplanner.ui.PlannerSettings;
import com.gtnhplanner.ui.card.CardModel;
import com.gtnhplanner.ui.card.RecipeCard;
import com.gtnhplanner.ui.drawer.DrawerCard;
import com.gtnhplanner.ui.drawer.DrawerModel;
import com.gtnhplanner.ui.note.NoteCard;
import com.gtnhplanner.ui.tutorial.Targets.Target;
import com.gtnhplanner.ui.tutorial.Tour.Beat;
import com.gtnhplanner.ui.tutorial.Tour.Scene;
import com.gtnhplanner.ui.world.Minimap;
import com.gtnhplanner.ui.world.PlannerKeys;

import codechicken.nei.recipe.GuiCraftingRecipe;

/**
 * What the tour shows, in order, from a recipe found in NEI to the minimap. Each beat does the clicking first, at a
 * brisk pace, then one note says what it showed. Notes are plain and short: what the thing is, and how to use it.
 * docs/design/tutorial.md has the design.
 */
final class Script {

    private Script() {}

    /** The screen the first beat starts on: the planner (the player is usually there already). */
    static final Scene START = Scene.BOARD;

    static List<Beat> beats() {
        final List<Beat> out = new ArrayList<>();
        recipe(out);
        moving(out);
        numbers(out);
        machine(out);
        drawers(out);
        overview(out);
        sharing(out);
        forced(out);
        power(out);
        plans(out);
        note(out);
        world(out);
        tasks(out);
        end(out);
        return out;
    }

    private static Beat beat(final List<Beat> out) {
        final Beat b = new Beat();
        out.add(b);
        return b;
    }

    // region What the beats point at

    private static boolean boardOpen() {
        return Targets.board() != null;
    }

    private static boolean popupOpen() {
        return Targets.popup()
            .rect() != null;
    }

    /** A sticky note is being written on (keys typed now go to it, and Esc only ends the writing). */
    private static boolean writingNote() {
        final BoardScreen b = Targets.board();
        return b != null && b.canvas()
            .notes()
            .values()
            .stream()
            .anyMatch(NoteCard::editing);
    }

    /** A card making {@code what} (a recipe's, not a generator's). */
    private static Predicate<Node> making(final String what) {
        return n -> n.powerSource == null && n.outputs.stream()
            .anyMatch(
                p -> p.getDisplayName()
                    .toLowerCase(Locale.ROOT)
                    .contains(what));
    }

    private static final Predicate<Node> BENDER = making("iron plate");
    private static final Predicate<Node> ACID = making("sulfuric acid");
    private static final Predicate<Node> ELECTROLYZER = making("oxygen");
    private static final Predicate<Node> TOWER = making("benzene");
    private static final Predicate<Node> MIXER = making("diluted sulfuric acid");
    private static final Predicate<Node> TURBINE = n -> "gas-turbine".equals(n.powerSource);

    /** The cards {@code which} accepts, top left first. */
    private static List<Node> all(final Predicate<Node> which) {
        final List<Node> out = new ArrayList<>();
        final BoardScreen b = Targets.board();
        if (b == null) return out;
        for (final Node n : b.session()
            .graph()
            .getNodes()) if (which.test(n)) out.add(n);
        out.sort(Comparator.comparingInt((Node n) -> n.x + n.y));
        return out;
    }

    /**
     * A recipe and its clone, told apart by the ids noted as the clone was made; a beat started where the last left it
     * (no ids noted) takes the top left one for the first.
     */
    private static final class Pair {

        private final Predicate<Node> kind;
        private UUID first, second;
        /** The first, and its clone. */
        final Predicate<Node> one, two;

        Pair(final Predicate<Node> kind) {
            this.kind = kind;
            one = n -> kind.test(n) && n.id.equals(id(true));
            two = n -> kind.test(n) && n.id.equals(id(false));
        }

        /** Notes the one there is, before it is cloned. */
        void noteFirst() {
            final List<Node> now = all(kind);
            first = now.isEmpty() ? null : now.get(0).id;
            second = null;
        }

        /** Notes the clone: the one that is not the first. */
        void noteSecond() {
            for (final Node n : all(kind)) if (!n.id.equals(first)) second = n.id;
        }

        private UUID id(final boolean one) {
            final List<Node> now = all(kind);
            final UUID noted = one ? first : second;
            for (final Node n : now) if (n.id.equals(noted)) return noted;
            if (now.size() < (one ? 1 : 2)) return null;
            return one ? now.get(0).id : now.get(now.size() - 1).id;
        }

        int count() {
            return all(kind).size();
        }
    }

    private static final Pair BENDERS = new Pair(BENDER);

    /** The recipes share one machine. */
    private static boolean merged(final Predicate<Node> which) {
        final BoardScreen b = Targets.board();
        if (b == null) return false;
        for (final Node n : all(which)) if (b.session()
            .sharedOf(n.id) != null) return true;
        return false;
    }

    /** One of NEI's keys as the player has it bound ("gui.recipe": R, "gui.usage": U). */
    private static String neiKey(final String binding, final String fallback) {
        try {
            final String k = codechicken.nei.NEIClientConfig.getKeyName(binding);
            return k == null || k.isEmpty() ? fallback : k;
        } catch (final RuntimeException | LinkageError e) {
            return fallback;
        }
    }

    private static boolean hasCard(final Predicate<Node> which) {
        return Targets.card(which) != null;
    }

    private static CardModel model(final Predicate<Node> which) {
        final RecipeCard c = Targets.card(which);
        return c == null ? null : c.model();
    }

    private static boolean hasDrawer(final String label) {
        return drawer(label) != null;
    }

    private static DrawerModel drawer(final String label) {
        final BoardScreen b = Targets.board();
        if (b == null) return null;
        for (final DrawerModel d : b.session()
            .drawerModels()
            .values())
            if (d.label.toLowerCase(Locale.ROOT)
                .contains(label.toLowerCase(Locale.ROOT))) return d;
        return null;
    }

    /** How many ports a drawer feeds or takes from. */
    private static int links(final String label) {
        final DrawerModel d = drawer(label);
        return d == null ? 0
            : d.drawer.getLinks()
                .size();
    }

    private static boolean solved() {
        final BoardScreen b = Targets.board();
        return b != null && !b.session()
            .solving();
    }

    /** NEI's entry by its name: exactly it, else a cell of it (this pack lists fluids by their cells). */
    private static Target neiNamed(final String name) {
        return Targets.neiItem(named(name), named(name + " cell"));
    }

    private static Predicate<ItemStack> named(final String name) {
        return s -> TourRecipes.name(s)
            .equals(name);
    }

    /**
     * Opens NEI's page of what makes something: its own entry in NEI's list, else its cell's. Looked up in NEI's whole
     * list, not the searched one, which a quick search may not have caught up with yet.
     */
    private static void recipesFor(final String name) {
        final List<ItemStack> items = codechicken.nei.ItemList.items;
        if (items == null) return;
        final int i = Targets.neiIndex(items, named(name), named(name + " cell"));
        if (i < 0) return;
        final ItemStack s = items.get(i)
            .copy();
        if (Planner.isPlanner(Minecraft.getMinecraft().currentScreen)) Planner.lookUp(s, false);
        else GuiCraftingRecipe.openRecipeGui("item", s);
    }

    private static Target planButton(final Supplier<TourRecipes.Found> recipe) {
        return () -> {
            final TourRecipes.Found f = recipe.get();
            return f == null ? null
                : Targets.planButton(f.handler(), f.recipe())
                    .rect();
        };
    }

    private static TourRecipes.Found plateRecipe() {
        return TourRecipes.onPage("bend", List.of("iron ingot"), "iron plate");
    }

    private static TourRecipes.Found acidRecipe() {
        return TourRecipes.onPage("large chemical", List.of("sulfur", "oxygen", "water"), "sulfuric acid");
    }

    private static TourRecipes.Found oxygenRecipe() {
        // The plain electrolyzer (not the industrial one), water itself in and oxygen gas out: not blue vitriol water,
        // nor oxygen in cells.
        return TourRecipes.onPage("electroly!industrial", List.of("=water"), "=oxygen");
    }

    private static TourRecipes.Found mixerRecipe() {
        return TourRecipes.onPage("mixer", List.of("=sugar", "=sulfuric acid"), "=diluted sulfuric acid");
    }

    private static TourRecipes.Found benzeneRecipe() {
        final TourRecipes.Found f = TourRecipes.onPage("distill", List.of("wood tar"), "benzene");
        return f != null ? f : TourRecipes.onPage("", List.of(), "benzene");
    }

    /** The plan open now: the plan menu's row for it. */
    private static final Target THIS_PLAN = () -> {
        final com.gtnhplanner.data.flowchart.Graph g = com.gtnhplanner.data.flowchart.Plan.getActiveGraph();
        return g == null ? null
            : Targets.planMenuRow(g.getName())
                .rect();
    };

    private static Target rail(final String id) {
        return Targets.boardPart("rail:" + id);
    }

    private static Target card(final Predicate<Node> which, final RecipeCard.Part part) {
        return Targets.card(which, part);
    }

    private static Target drawerPart(final String label, final DrawerCard.Part part) {
        return Targets.drawer(label, part);
    }

    /** A card's body near its bottom right corner: clear of its ports and keys, so a press there takes the card. */
    private static Target grip(final Predicate<Node> which) {
        return Targets.point(card(which, RecipeCard.Part.BODY), 0.96f, 0.95f);
    }

    /** Sets a number box: opens it, types, and presses Enter. */
    private static Beat setNumber(final Beat b, final Target box, final String value) {
        return b.opens(box)
            .type(Steps::focusedField, value)
            .pause(150)
            .commit();
    }

    /** As {@link #setNumber}, the number worked out as it is typed. */
    private static Beat setNumber(final Beat b, final Target box, final Supplier<String> value) {
        return b.opens(box)
            .typeOf(Steps::focusedField, value)
            .pause(150)
            .commit();
    }

    /** A port's rule set back to Any: its key, then Any in the key's menu. */
    private static Beat any(final Beat b, final Predicate<Node> which, final boolean output, final String name) {
        return b.opens(Targets.portControl(which, output, name, false))
            .click(Targets.popupRow("Any"));
    }

    /**
     * Looks a thing up in NEI from the board (searched, hovered, R), turns to a recipe of it and adds that to the plan
     * open now with its plan button, through the plan menu and, when it asks, the machine menu.
     */
    private static Beat addFromNei(final Beat b, final String search, final String name, final String tab,
        final Supplier<TourRecipes.Found> recipe, final Predicate<Node> lands) {
        final Target tabKey = Targets.recipeTab(
            h -> h.getRecipeName()
                .toLowerCase(Locale.ROOT)
                .contains(tab));
        final Target button = planButton(recipe);
        return b.click(Targets.neiSearch())
            .type(Steps::neiSearch, search)
            .pause(200)
            .hover(neiNamed(name), 200)
            .key(neiKey("gui.recipe", "R"), () -> recipesFor(name))
            .until(() -> Targets.recipePage() != null, 4000)
            .pause(200)
            .when(() -> tabKey.rect() != null, Steps.click(tabKey))
            .run(() -> TourRecipes.open(recipe.get()))
            .until(() -> button.rect() != null, 3000)
            .click(button)
            .until(PlanMenu.INSTANCE::isOpen, 2000)
            .hover(THIS_PLAN, 200)
            .click(THIS_PLAN)
            .until(() -> PlanMenu.INSTANCE.isOpen() || hasCard(lands), 2500)
            .when(PlanMenu.INSTANCE::isOpen, Steps.click(Targets.planMenuRow("")))
            .until(() -> hasCard(lands), 5000);
    }

    // endregion

    // region From NEI to a card

    private static void recipe(final List<Beat> out) {
        final Target button = planButton(Script::plateRecipe);
        final Target benderTab = Targets.recipeTab(
            h -> h.getRecipeName()
                .toLowerCase(Locale.ROOT)
                .contains("bend"));
        beat(out).pause(250)
            .click(Targets.neiSearch())
            .type(Steps::neiSearch, "iron plate")
            .pause(200)
            .hover(neiNamed("iron plate"), 200)
            .key(neiKey("gui.recipe", "R"), () -> recipesFor("iron plate"))
            .until(() -> Targets.recipePage() != null, 4000)
            .pause(200)
            .when(() -> benderTab.rect() != null, Steps.click(benderTab))
            .run(() -> TourRecipes.open(plateRecipe()))
            .until(() -> button.rect() != null, 3000)
            .note(button, "This button *adds a recipe to a plan*.");

        final Target bending = Targets.planMenuRow("Bending");
        beat(out).click(button)
            .until(PlanMenu.INSTANCE::isOpen, 2000)
            .hover(Targets.planMenuRow("New plan"), 200)
            .click(Targets.planMenuRow("New plan"))
            .until(() -> bending.rect() != null || boardOpen(), 2000)
            .when(() -> bending.rect() != null, Steps.seq(Steps.hover(bending, 200), Steps.click(bending)))
            .until(() -> hasCard(BENDER), 5000)
            .rest()
            .noteBare(
                card(BENDER, RecipeCard.Part.BODY),
                "This is a *recipe in a plan*. It does nothing yet: nothing has told it how much to make.");
    }

    // endregion

    // region Getting around

    /**
     * Getting around: a note at the top pointing at nothing (a small mouse, then a key cap, on each line), and the
     * board's camera handed to the player to try it; the plan framed before each, and again as the next beat starts.
     */
    private static void moving(final List<Beat> out) {
        beat(out).add(Steps.fit())
            .pause(400)
            .rest()
            .noteAt(
                "{M} *Middle mouse* to pan.\n{W} *Scroll* to zoom.\n{L} *Left click* to pan, or drag things around.\nTry it, then press next.",
                0.5f,
                0.2f)
            .add(Steps.freeLook());

        beat(out).add(Steps.fit())
            .pause(400)
            .rest()
            .noteAt(
                "{K} *W A S D* or the *arrow keys* to pan.\n{K} *Page Up* and *Page Down*, or *+* and *-*, to zoom.\nTry it, then press next.",
                0.5f,
                0.2f)
            .add(Steps.freeLook());
    }

    // endregion

    // region Numbers to solve for

    private static void numbers(final List<Beat> out) {
        final Target plates = Targets.portControl(BENDER, true, "Iron Plate", true);
        // Framed again, however the player left the board.
        setNumber(
            beat(out).add(Steps.fit())
                .pause(400),
            plates,
            "10").until(() -> model(BENDER) != null && model(BENDER).machines > 0, 4000)
                .until(Script::solved, 3000)
                .rest()
                .note(
                    card(BENDER, RecipeCard.Part.BODY),
                    "You can *pin a number* and it calculates the other numbers.");

        final Target count = card(BENDER, RecipeCard.Part.MACHINES);
        setNumber(any(beat(out), BENDER, true, "Iron Plate"), count, "2")
            .until(() -> model(BENDER) != null && model(BENDER).pinned, 3000)
            .until(Script::solved, 3000)
            .rest()
            .note(count, "Or pin the *machine count*.");

        final Target ingots = Targets.portControl(BENDER, false, "Iron Ingot", true);
        setNumber(
            setNumber(beat(out), count, "").until(() -> model(BENDER) != null && !model(BENDER).pinned, 3000),
            ingots,
            "5").until(() -> model(BENDER) != null && model(BENDER).machines > 0, 4000)
                .until(Script::solved, 3000)
                .rest()
                .note(Targets.around(Targets.port(BENDER, false, "Iron Ingot"), ingots), "Or pin an *input*.");
    }

    // endregion

    // region The machine

    private static void machine(final List<Beat> out) {
        final Target tier = card(BENDER, RecipeCard.Part.TIER);
        beat(out).click(tier)
            .pause(200)
            .click(tier)
            .until(Script::solved, 3000)
            .rest()
            .note(tier, "Set the machine's *tier* here.");

        beat(out).pause(150)
            .note(
                Targets.around(card(BENDER, RecipeCard.Part.POWER), card(BENDER, RecipeCard.Part.CIRCUIT)),
                "Power use is shown here, but machines *don't need to be wired to power*. The circuit is also shown.");

        beat(out).opens(card(BENDER, RecipeCard.Part.SETTINGS))
            .note(
                Targets.popup(),
                "This is the *settings panel*. If it has any settings, you can *pin* them to show them on the card.");
    }

    // endregion

    // region Drawers

    private static void drawers(final List<Beat> out) {
        // The ingot rate goes first, so the clone does not copy it.
        any(beat(out).escIf(Script::popupOpen), BENDER, false, "Iron Ingot").run(BENDERS::noteFirst)
            .opens(card(BENDER, RecipeCard.Part.ACTIONS))
            .hover(Targets.popupRow("Clone"), 200)
            .click(Targets.popupRow("Clone"))
            .until(() -> BENDERS.count() == 2, 3000)
            .run(BENDERS::noteSecond)
            .pause(300)
            .click(Targets.topKey("fit"))
            .pause(300)
            .rest()
            .note(card(BENDERS.two, RecipeCard.Part.BODY), "You can *clone* machines.");

        final Target plates = Targets.port(BENDERS.one, true, "Iron Plate");
        beat(out).drag(plates, Targets.shift(plates, 150, 0))
            .until(() -> hasDrawer("Iron Plate"), 3000)
            .rest()
            .note(
                drawerPart("Iron Plate", DrawerCard.Part.BODY),
                "Drag an *input* or an *output* to make it a *drawer*.");

        // Left of the machines, between them: where a source belongs, clear of the cards.
        final Target spot = Targets.shift(Targets.port(BENDERS.one, false, "Iron Ingot"), -150, 110);
        beat(out).click(Targets.neiSearch())
            .type(Steps::neiSearch, "iron ingot")
            .pause(200)
            .drag(neiNamed("iron ingot"), spot)
            .pause(150)
            .when(() -> codechicken.nei.ItemPanels.itemPanel.draggedStack != null, Steps.click(spot))
            .until(Script::popupOpen, 2500)
            .hover(Targets.popupRow("Add as a source"), 200)
            .click(Targets.popupRow("Add as a source"))
            .until(() -> hasDrawer("Iron Ingot"), 3000)
            .rest()
            .note(drawerPart("Iron Ingot", DrawerCard.Part.BODY), "Or bring one in from *NEI*.");

        // Both machines wired to both drawers (four wires), and the plates asked of them together.
        final Target ingotDrawer = drawerPart("Iron Ingot", DrawerCard.Part.BODY);
        setNumber(
            beat(out)
                .drag(Targets.port(BENDERS.two, true, "Iron Plate"), drawerPart("Iron Plate", DrawerCard.Part.BODY))
                .until(() -> links("Iron Plate") == 2, 3000)
                .drag(ingotDrawer, Targets.port(BENDERS.one, false, "Iron Ingot"))
                .until(() -> links("Iron Ingot") >= 1, 3000)
                .drag(ingotDrawer, Targets.port(BENDERS.two, false, "Iron Ingot"))
                .until(() -> links("Iron Ingot") == 2, 3000),
            drawerPart("Iron Plate", DrawerCard.Part.RATE),
            "20").until(Script::solved, 3000);
        setNumber(out.get(out.size() - 1), card(BENDERS.one, RecipeCard.Part.MACHINES), "7")
            .until(() -> model(BENDERS.one) != null && model(BENDERS.one).pinned, 3000)
            .until(Script::solved, 3000)
            .rest()
            .note(
                drawerPart("Iron Plate", DrawerCard.Part.RATE),
                "*Drag from a drawer* to wire it. Set the drawer's *rate* and it applies to every machine wired to it.");

        beat(out).click(Targets.topKey("arrange"))
            .until(
                () -> Targets.board() != null && !Targets.board()
                    .canvas()
                    .arranging(),
                20000)
            .pause(200)
            .click(Targets.topKey("fit"))
            .pause(300)
            .rest()
            .note(
                Targets.around(Targets.topKey("arrange"), Targets.topKey("fit")),
                "*Arrange* untangles the plan, and *Fit* brings all of it into view.");
    }

    // endregion

    // region The overview

    private static void overview(final List<Beat> out) {
        beat(out).note(
            rail("all"),
            "The *overview* lists everything in the plan. You can set your inputs and outputs from here too.");

        beat(out).note(
            Targets.around(
                rail("section:INPUTS"),
                rail("resource:Iron Ingot"),
                rail("section:OUTPUTS"),
                rail("resource:Iron Plate")),
            "{b:Blue} is always something coming into your plan, and {o:Orange} is always something leaving it. Nothing else is shown in those colours.");

        beat(out)
            .note(rail("block:MACHINES"), "Your *machines* are here too: set how many from here, as on their cards.");
    }

    // endregion

    // region One machine for two recipes, undo, and how numbers show

    private static void sharing(final List<Beat> out) {
        // A card clicked without moving it is selected; Shift adds the next to it; two that one machine can run
        // offer to combine.
        final Target selection = Targets.boardPart("selection");
        beat(out).click(Targets.point(card(BENDERS.one, RecipeCard.Part.BODY), 0.9f, 0.85f))
            .pause(150)
            .shiftClick(Targets.point(card(BENDERS.two, RecipeCard.Part.BODY), 0.9f, 0.85f))
            .until(() -> selection.rect() != null, 2000)
            .hover(selection, 250)
            .click(selection)
            .until(() -> merged(BENDER), 3000)
            .rest()
            .note(
                card(BENDER, RecipeCard.Part.BODY),
                "Hold *Shift* to select more than one. Then you can *merge* machines, to run two recipes on one machine.");

        beat(out).clickUntil(Targets.topKey("undo"), () -> !merged(BENDER), 3)
            .rest()
            .note(Targets.around(Targets.topKey("undo"), Targets.topKey("redo")), "*Undo* and *redo* are up here.");

        // Each key is turned all the way round, back to where it was.
        final Target rate = Targets.topKey("rate"), power = Targets.topKey("power"), peak = Targets.topKey("peak");
        final Beat units = beat(out);
        for (int i = 0; i < 4; i++) units.click(rate)
            .pause(120);
        units.click(power)
            .pause(250)
            .click(power)
            .pause(120)
            .click(peak)
            .pause(250)
            .click(peak)
            .rest()
            .note(Targets.around(rate, power, peak), "These buttons change *the way numbers are shown*.");
    }

    // endregion

    // region Forced inputs and outputs

    /**
     * What flows at a port of a card, a second, its Forced part with it (what the plan brings in or sends out there
     * anyway); 0 when there is no such card or port.
     */
    private static double flow(final Predicate<Node> which, final boolean output, final String name) {
        final CardModel m = model(which);
        if (m == null) return 0;
        for (final CardModel.PortView p : output ? m.outputs : m.inputs) if (p.name()
            .toLowerCase(Locale.ROOT)
            .contains(name.toLowerCase(Locale.ROOT))) return p.perSecond() + p.extra();
        return 0;
    }

    /** Twice as many electrolyzers as the reactor needs for the mixer: they make too much oxygen. */
    private static String tooManyElectrolyzers() {
        final CardModel e = model(ELECTROLYZER);
        final double each = e == null || e.machines <= 0 ? 0 : flow(ELECTROLYZER, true, "Oxygen") / e.machines;
        final double needed = flow(ACID, false, "Oxygen");
        return String.valueOf(each <= 0 ? 10 : Math.max(6, 2 * (long) Math.ceil(needed / each - 1e-6)));
    }

    private static void forced(final List<Beat> out) {
        final Target acid = Targets.portControl(ACID, true, "Sulfuric Acid", true);
        setNumber(
            addFromNei(beat(out), "sulfuric acid", "sulfuric acid", "large chemical", Script::acidRecipe, ACID).rest(),
            acid,
            "20").until(() -> model(ACID) != null && model(ACID).machines > 0, 4000)
                .until(Script::solved, 3000)
                .rest()
                .note(card(ACID, RecipeCard.Part.BODY), "Add as many recipes to a plan as you like.");

        // A click on a port is NEI's page of what makes it; a recipe added from there lands wired into the port.
        final Target button = planButton(Script::oxygenRecipe);
        beat(out).click(Targets.port(ACID, false, "Oxygen"))
            .until(() -> Targets.recipePage() != null, 4000)
            .pause(200)
            .run(() -> TourRecipes.open(oxygenRecipe()))
            .until(() -> button.rect() != null, 3000)
            .click(button)
            .until(PlanMenu.INSTANCE::isOpen, 2000)
            .hover(THIS_PLAN, 200)
            .click(THIS_PLAN)
            .until(() -> PlanMenu.INSTANCE.isOpen() || hasCard(ELECTROLYZER), 2500)
            .when(PlanMenu.INSTANCE::isOpen, Steps.click(Targets.planMenuRow("")))
            .until(() -> hasCard(ELECTROLYZER), 5000)
            .until(Script::solved, 3000)
            .rest()
            .note(
                card(ELECTROLYZER, RecipeCard.Part.BODY),
                "Click an input to find *what makes it*. A recipe added from there comes in *already wired*.");

        // One electrolyzer pinned: it cannot keep up with the acid set, so the rest of the oxygen has to come in.
        setNumber(beat(out), card(ELECTROLYZER, RecipeCard.Part.MACHINES), "1")
            .until(() -> model(ELECTROLYZER) != null && model(ELECTROLYZER).pinned, 3000)
            .until(Script::solved, 3000)
            .until(
                () -> Targets.forcedRow(ACID, false, "Oxygen")
                    .rect() != null,
                4000)
            // The reactor whole on the board, its Forced row in sight.
            .until(() -> card(ACID, RecipeCard.Part.BODY).rect() != null, 2000)
            .rest()
            .note(
                Targets.forcedRow(ACID, false, "Oxygen"),
                "The acid is set and one electrolyzer is pinned, but it can't keep up. The plan has to bring in the rest of the oxygen anyway: a *forced input*.");

        // Right-click an output: NEI's page of what uses it, and the recipe added from there lands wired in. The mixer
        // takes the acid, so the reactor runs free between a pinned electrolyzer and a pinned mixer.
        final Target mixButton = planButton(Script::mixerRecipe);
        beat(out).add(Steps.click(Targets.port(ACID, true, "Sulfuric Acid"), 1))
            .until(() -> Targets.recipePage() != null, 4000)
            .pause(200)
            .run(() -> TourRecipes.open(mixerRecipe()))
            .until(() -> mixButton.rect() != null, 3000)
            .click(mixButton)
            .until(PlanMenu.INSTANCE::isOpen, 2000)
            .hover(THIS_PLAN, 200)
            .click(THIS_PLAN)
            .until(() -> PlanMenu.INSTANCE.isOpen() || hasCard(MIXER), 2500)
            .when(PlanMenu.INSTANCE::isOpen, Steps.click(Targets.planMenuRow("")))
            .until(() -> hasCard(MIXER), 5000)
            .until(Script::solved, 3000)
            .rest()
            .note(card(MIXER, RecipeCard.Part.BODY), "*Right-click* an output to find *what uses it*.");

        // The mixer pinned low and the electrolyzer high: the oxygen is more than the mixer's acid needs, so something
        // has to leave, as oxygen at the electrolyzer or as acid at the reactor. The slider shares it between them.
        final Beat split = beat(out);
        setNumber(split, card(MIXER, RecipeCard.Part.MACHINES), "1").until(Script::solved, 3000)
            // The cards show the new answer a frame or two after it comes: the reactor's oxygen is read from them.
            .pause(600);
        setNumber(split, card(ELECTROLYZER, RecipeCard.Part.MACHINES), Script::tooManyElectrolyzers)
            .until(() -> rail("spare:Oxygen").rect() != null, 4000)
            .until(Script::solved, 3000)
            .rest()
            .note(
                rail("spare:Oxygen"),
                "Electrolyzer pinned high, mixer pinned low: more oxygen is made than used. The excess leaves the plan as a *forced output*.");

        // The slider, slowly to each end and back to the middle: all of the excess leaving as oxygen, then all of it
        // as acid (the reactor running harder), then half and half.
        final Target oxygenBar = rail("sparebar:Oxygen:FILL:0"), acidBar = rail("sparebar:Oxygen:FILL:1");
        beat(out).click(Targets.point(oxygenBar, 0.97f, 0.5f))
            .until(Script::solved, 3000)
            .pause(1000)
            .click(Targets.point(acidBar, 0.97f, 0.5f))
            .until(Script::solved, 3000)
            .pause(1000)
            .click(Targets.point(oxygenBar, 0.5f, 0.5f))
            .until(Script::solved, 3000)
            .rest()
            .note(
                rail("spare:Oxygen"),
                "The excess can leave at more than one machine. The *slider* sets the split: oxygen out of the electrolyzer, or extra acid out of the reactor. *Auto* hands it back to the solver.");
    }

    // endregion

    // region Products, trash and power

    /** The tower's output the trash beats drag out, fixed when they start: its name. */
    private static String side;

    /** The tower's first output that is neither benzene nor wired yet; null for none. */
    private static String sideOutput() {
        final CardModel m = model(TOWER);
        if (m == null) return null;
        for (final CardModel.PortView p : m.outputs) if (!p.wired() && !p.name()
            .toLowerCase(Locale.ROOT)
            .contains("benzene")) return p.name();
        return null;
    }

    private static Target sidePart(final DrawerCard.Part part) {
        return () -> side == null ? null
            : Targets.drawer(side, part)
                .rect();
    }

    /** The trash drawer on the board, by its name: the side output, for a beat started where the last left it. */
    private static String trashed() {
        final BoardScreen b = Targets.board();
        if (b == null) return null;
        for (final DrawerModel d : b.session()
            .drawerModels()
            .values()) if (d.kind == com.gtnhplanner.data.flowchart.Drawer.Kind.TRASH) return d.label;
        return null;
    }

    private static boolean sideIs(final com.gtnhplanner.data.flowchart.Drawer.Kind kind) {
        final DrawerModel d = side == null ? null : drawer(side);
        return d != null && d.kind == kind;
    }

    private static void power(final List<Beat> out) {
        final Target port = () -> side == null ? null
            : Targets.port(TOWER, true, side)
                .rect();
        addFromNei(beat(out), "benzene", "benzene", "distill", Script::benzeneRecipe, TOWER)
            .run(() -> side = sideOutput())
            .drag(port, Targets.freeNear(port, true, 70, 0))
            .until(() -> side != null && hasDrawer(side), 3000)
            .pause(200)
            .opens(sidePart(DrawerCard.Part.RULE))
            .click(Targets.popupRow(DrawerCard.TO_TRASH))
            .until(() -> sideIs(com.gtnhplanner.data.flowchart.Drawer.Kind.TRASH), 2000)
            .until(Script::solved, 3000)
            .rest()
            .note(
                sidePart(DrawerCard.Part.RULE),
                "This key's menu also makes a product drawer *trash*: it voids what comes in, and leaves your outputs.");
        beat(out).run(() -> { if (side == null || !hasDrawer(side)) side = trashed(); })
            .when(
                () -> !sideIs(com.gtnhplanner.data.flowchart.Drawer.Kind.PRODUCT),
                Steps.seq(
                    Steps.clickOpens(sidePart(DrawerCard.Part.RULE), 0),
                    Steps.click(Targets.popupRow(DrawerCard.TO_PRODUCT))))
            .until(() -> sideIs(com.gtnhplanner.data.flowchart.Drawer.Kind.PRODUCT), 2000)
            .until(Script::solved, 3000)
            .rest()
            .note(
                sidePart(DrawerCard.Part.BODY),
                "And back to a *product*. On *Any* it takes whatever comes out; set a rate and the plan works toward it.");

        beat(out).click(Targets.topKey("nonrecipe"))
            .until(
                () -> Targets.boardPart("picker:sheet")
                    .rect() != null,
                2000)
            .note(
                Targets.boardPart("picker:sheet"),
                "This section is for *non-recipe machines*, mostly power generation.");

        // The turbine at HV burns the tower's benzene; everything else the tower takes and makes gets a drawer, and the
        // turbine's power one too, so the whole plan runs. The tower pinned to one says how many turbines that feeds.
        final Target turbineTier = card(TURBINE, RecipeCard.Part.TIER);
        final Beat b = beat(out).hover(Targets.boardPart("picker:Gas Turbine"), 200)
            .click(Targets.boardPart("picker:Gas Turbine"))
            .until(() -> hasCard(TURBINE), 3000)
            .click(turbineTier)
            .pause(200)
            .click(turbineTier)
            .drag(Targets.port(TOWER, true, "Benzene"), card(TURBINE, RecipeCard.Part.BODY))
            .pause(200);
        drawerFor(b, Targets.port(TURBINE, true, "EU"), true, "EU");
        for (final String made : new String[] { "Creosote", "Phenol", "Toluene", "Dimethylbenzene" })
            drawerFor(b, Targets.port(TOWER, true, made), true, made);
        drawerFor(b, Targets.port(TOWER, false, "Wood Tar"), false, "Wood Tar");
        setNumber(b, card(TOWER, RecipeCard.Part.MACHINES), "1")
            .until(() -> model(TOWER) != null && model(TOWER).pinned, 3000)
            .until(Script::solved, 3000)
            .until(() -> card(TURBINE, RecipeCard.Part.BODY).rect() != null, 2000)
            .rest()
            .note(
                card(TURBINE, RecipeCard.Part.BODY),
                "You can also *plan power*. This distillation tower makes enough benzene to run this many HV gas turbines.");
    }

    /**
     * Drags a port out to a drawer of its own, on empty board beside it; a port with one already (the output the
     * trash and product beats used) keeps it, as a second would be refused.
     */
    private static void drawerFor(final Beat b, final Target port, final boolean output, final String label) {
        b.when(
            () -> !hasDrawer(label),
            Steps.seq(
                Steps.drag(port, Targets.freeNear(port, output, output ? 70 : -70, 0)),
                Steps.until(() -> hasDrawer(label), 2500)));
    }

    // endregion

    // region Plans and Community

    private static void plans(final List<Beat> out) {
        beat(out).opens(Targets.boardPart("tab:+"))
            .note(Targets.around(Targets.topKey("tabs"), Targets.popup()), "Here's how you make a *new plan*.");

        beat(out).escIf(Script::popupOpen)
            .rest()
            .note(
                Targets.topKey("invite"),
                "Sign in and your plans save as you edit, on any computer. *Invite* someone and you build the plan together, live.");

        beat(out).click(Targets.topKey("plans"))
            .until(Script::libraryOpen, 2000)
            .pause(400)
            .rest()
            .note(Targets.topKey("plans"), "*Plans* has all your plans, and the ones people shared with you.");

        beat(out).escIf(Script::popupOpen)
            .click(Targets.topKey("community"))
            .until(
                () -> Targets.board() != null && Targets.board()
                    .libraryOpen(),
                2000)
            .click(Targets.boardPart("library:shelf:public"))
            .until(Script::publicLoaded, 8000)
            .click(Targets.boardPart("library:search"))
            .type(() -> text -> {
                final BoardScreen b = Targets.board();
                if (b != null && b.key("libraryView") instanceof final com.gtnhplanner.ui.library.LibraryView v)
                    v.searchField()
                        .setText(text);
            }, "oil")
            .pause(400)
            .rest()
            .note(
                Targets.boardPart("library:all"),
                "*Community* is where people post plans. You can browse them here.");
    }

    private static boolean libraryOpen() {
        return Targets.board() != null && Targets.board()
            .libraryOpen();
    }

    private static boolean publicLoaded() {
        final BoardScreen b = Targets.board();
        return b != null && b.key("libraryView") instanceof final com.gtnhplanner.ui.library.LibraryView v
            && v.publicLoaded();
    }

    // endregion

    // region Sticky notes

    private static void note(final List<Beat> out) {
        final Target empty = Targets.emptyBoard(200, 140);
        final Target note = Targets.note(null, NoteCard.Part.BODY);
        final Target grip = Targets.note(null, NoteCard.Part.RESIZE);
        final Target bigger = Targets.note(null, NoteCard.Part.BIGGER);
        // The whole plan in view first, so there is empty board to write on.
        beat(out).when(Script::libraryOpen, Steps.click(Targets.topKey("community")))
            .until(() -> !libraryOpen(), 2000)
            .click(Targets.topKey("fit"))
            .pause(400)
            .wheel(empty, 3)
            .pause(150)
            .opensMenu(empty)
            .click(Targets.popupRow("Add sticky note"))
            .until(() -> note.rect() != null, 2000)
            .until(Script::writingNote, 1500)
            .when(Script::writingNote, Steps.keys("Hydrogen comes from the electrolyzers by the door."))
            .pause(200)
            .escIf(Script::writingNote)
            .pause(150)
            .drag(Targets.point(note, 0.4f, 0.3f), Targets.shift(Targets.point(note, 0.4f, 0.3f), 30, 20))
            .drag(Targets.point(grip, 0.7f, 0.7f), Targets.shift(Targets.point(grip, 0.7f, 0.7f), 40, 10))
            .opensMenu(Targets.point(note, 0.5f, 0.6f))
            .click(Targets.popupRow("Colour"))
            .until(
                () -> Targets.popupRow("Lime")
                    .rect() != null,
                2000)
            .click(Targets.popupRow("Lime"))
            .click(bigger)
            .pause(150)
            .click(bigger)
            .pause(150)
            .click(bigger)
            .rest()
            .note(note, "Annotations are possible with *sticky notes*. Right-click anywhere to make one.");
    }

    // endregion

    // region In the world: placing, pins and the minimap

    /** The board's zoom now; 1 when it is not open. */
    private static float zoom() {
        final BoardScreen b = Targets.board();
        return b == null ? 1
            : b.session()
                .graph()
                .getZoom();
    }

    private static void world(final List<Beat> out) {
        // Zoomed far out the cards show no keys: in close enough to press the reactor's pin key first.
        beat(out).escIf(Script::popupOpen)
            .when(() -> zoom() <= RecipeCard.GLANCE_ZOOM + 0.15f, Steps.wheel(card(ACID, RecipeCard.Part.BODY), 4))
            .pause(300)
            .opens(card(ACID, RecipeCard.Part.PLACE))
            .note(
                Targets.popup(),
                "*Place in the world* puts a machine over the spot you'll build it. *Pin to the screen* keeps it in a corner while you play.");

        // The keys as the player has them bound.
        final boolean arrows = PlannerKeys.minimapOnArrows();
        final String move = arrows ? "The arrow keys move it"
            : PlannerKeys.minimapKey("up") + ", "
                + PlannerKeys.minimapKey("left")
                + ", "
                + PlannerKeys.minimapKey("down")
                + " and "
                + PlannerKeys.minimapKey("right")
                + " move it";
        final String in = PlannerKeys.minimapKey("in"), outKey = PlannerKeys.minimapKey("out");
        final Target pins = () -> Rect.of(com.gtnhplanner.ui.world.PinList.bounds());
        beat(out).when(
            () -> Targets.popupRow("Pin to the screen")
                .rect() != null,
            Steps.click(Targets.popupRow("Pin to the screen")))
            .pause(200)
            .escIf(Script::popupOpen)
            .opens(Targets.topKey("settings"))
            .click(Targets.settingsSection("Minimap"))
            .clickUntil(Targets.popupRow("Show the minimap"), () -> "On".equals(Targets.setting("Show the minimap")), 2)
            .clickUntil(Targets.settingChoice("Size", "Large"), () -> "Large".equals(Targets.setting("Size")), 2)
            .clickUntil(Targets.settingChoice("Shape", "Square"), () -> "Square".equals(Targets.setting("Shape")), 2)
            .clickUntil(
                Targets.settingChoice("Position", "Top right"),
                () -> "Top right".equals(Targets.setting("Position")),
                2)
            .click(Targets.settingsSection("Pin list"))
            .clickUntil(
                Targets.popupRow("Show the pin list"),
                () -> "On".equals(Targets.setting("Show the pin list")),
                2)
            .clickUntil(
                Targets.settingChoice("Position", "Bottom right"),
                () -> "Bottom right".equals(Targets.setting("Position")),
                2)
            .pause(200)
            .esc()
            .rest()
            .key(
                "Esc",
                () -> Minecraft.getMinecraft()
                    .displayGuiScreen(new TourScreen()))
            .until(() -> Minecraft.getMinecraft().currentScreen instanceof TourScreen, 2000)
            .note(
                Targets.minimap(),
                "While you play, the *minimap* shows your plan and the *pin list* your pinned machines. Set both up under the gear. "
                    + move
                    + ", "
                    + outKey
                    + " and "
                    + in
                    + " zoom, and "
                    + PlannerKeys.minimapKey("show")
                    + " hides it.")
            .holdKey(arrows ? "→" : PlannerKeys.minimapKey("right"), 450, () -> Minimap.INSTANCE.pan(1, 0))
            .pause(200)
            .holdKey(arrows ? "←" : PlannerKeys.minimapKey("left"), 450, () -> Minimap.INSTANCE.pan(-1, 0))
            .pause(200)
            .key(in, () -> PlannerSettings.setMinimapZoomIndex(PlannerSettings.minimapZoomIndex() + 1))
            .pause(400)
            .key(outKey, () -> PlannerSettings.setMinimapZoomIndex(PlannerSettings.minimapZoomIndex() - 1))
            .pause(200)
            .run(Minimap.INSTANCE::recentre);

    }

    // endregion

    // region Task boards

    private static void tasks(final List<Beat> out) {
        // Back on the board: the reactor goes on a task, on a new task board.
        final Target open = Targets.boardPart("notice:Open");
        beat(out).run(com.gtnhplanner.ui.Planner::open)
            .until(() -> Targets.board() != null, 3000)
            .escIf(Script::popupOpen)
            .opens(card(ACID, RecipeCard.Part.ACTIONS))
            .click(Targets.popupRow("Add to a task"))
            .until(
                () -> Targets.popupRow("New task board")
                    .rect() != null,
                2000)
            .click(Targets.popupRow("New task board"))
            .until(() -> open.rect() != null, 3000)
            .rest()
            .note(
                Targets.boardPart("notices"),
                "*Add to a task* puts machines on a task board, with how many to build.");

        beat(out).click(open)
            .until(Script::popupOpen, 3000)
            .pause(300)
            .rest()
            .note(
                Targets.popup(),
                "A *task* holds notes, a checklist, comments and who is on it. Drag it across the columns as the work goes.");
    }

    // endregion

    // region The end

    private static void end(final List<Beat> out) {
        // At the Feedback key, the task's box put away.
        beat(out).escIf(Script::popupOpen)
            .rest()
            .note(
                Targets.topKey("feedback"),
                "That's everything! Found a bug, or have an idea? *Feedback* sends it from the game. Questions about your own setup do well in GTNH Planner's thread on the *GT New Horizons Discord*.");
    }

    // endregion
}
