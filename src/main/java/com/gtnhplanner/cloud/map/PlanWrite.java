package com.gtnhplanner.cloud.map;

import java.util.HashSet;
import java.util.Iterator;
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
import com.gtnhplanner.cloud.sync.LiveOps;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.importer.FfPlan;
import com.gtnhplanner.library.PlanExport;

/**
 * A board written as a shared plan ({@link CloudPlans#write}), against the plan it was read from. A thing nobody
 * changed is the plan's own, exactly; a moved one only moves; a changed one takes the fields its change touched (what
 * the game writes for it now against what it wrote for it when read) and carries the game's data for it; a new one is
 * written whole; one the board lost is gone, and the wires the game had no place for go with it. Everything the game
 * has no place for stays as it was.
 */
final class PlanWrite {

    /** The collections every plan has (the website's schema asks for them), written empty when there is nothing. */
    private static final List<String> ALWAYS = List
        .of("recipes", "nodes", "storages", "annotations", "pockets", "edges", "fuelProfiles");

    private final Graph graph;
    private final CloudGame game;
    private final JsonObject base;
    private final Basis basis;
    private final Basis next = new Basis();
    /** Recipes the board's cards changed or brought, by id. */
    private final Map<String, JsonObject> recipes = new LinkedHashMap<>();
    /** Plan ids of the cards and drawers the board lost. */
    private final Set<String> gone = new HashSet<>();
    /**
     * Plan ids of the boards the website never opened (no size; their members sit in a space of their own, which
     * {@link PlanRead} reads with the website's shift) that this write gives a size: their members are measured from
     * the frame from now on, so each is placed again.
     */
    private final Set<String> opened = new HashSet<>();

    PlanWrite(final Graph graph, final CloudGame game, @Nullable final JsonObject base, @Nullable final Basis basis) {
        this.graph = graph;
        this.game = game;
        this.base = base == null ? new JsonObject() : base;
        this.basis = basis == null ? new Basis() : basis;
        next.carried.putAll(this.basis.carried);
    }

    CloudPlans.Written run() {
        final Map<String, Fresh.Thing> fresh = Fresh.of(graph, game, basis);
        final JsonObject out = new JsonObject();
        meta(out);
        final Map<String, JsonObject> baseRecipes = new LinkedHashMap<>();
        for (final JsonObject r : PlanRead.objects(base, "recipes")) {
            final String id = PlanRead.str(r, "id");
            if (id != null) baseRecipes.putIfAbsent(id, r);
        }
        // The boards first: which of the website's never-opened ones this write gives a size decides where their
        // members are written.
        final List<Fresh.Thing> pocketsNow = new java.util.ArrayList<>();
        final JsonArray written = collection("pockets", fresh, baseRecipes, pocketsNow);
        // A board inside one that gained a size is measured from that frame now too.
        final JsonArray pockets = new JsonArray();
        for (int i = 0; i < written.size(); i++) {
            final Fresh.Thing now = pocketsNow.get(i);
            pockets.add(now != null && opened.contains(now.pocket()) ? placed(written.get(i), now) : written.get(i));
        }
        for (final String c : LiveOps.COLLECTIONS) {
            if ("recipes".equals(c)) continue;
            final JsonArray list = "pockets".equals(c) ? pockets : collection(c, fresh, baseRecipes, null);
            if (list.size() > 0 || base.has(c) || ALWAYS.contains(c)) out.add(c, list);
        }
        out.add("recipes", recipes(baseRecipes, out));
        return new CloudPlans.Written(out, next);
    }

    /**
     * One collection written: the plan's things in its order, then the board's new ones. {@code nows}, when given,
     * gets the board thing each written one is (null for one the board has no place for), and the boards that gain a
     * size go in {@link #opened}.
     */
    private JsonArray collection(final String c, final Map<String, Fresh.Thing> fresh,
        final Map<String, JsonObject> baseRecipes, @Nullable final List<Fresh.Thing> nows) {
        final JsonArray list = new JsonArray();
        for (final JsonElement e : LiveOps.collection(base, c)) {
            final String id = LiveJson.idOf(e);
            final Basis.Entry entry = id == null ? null : basis.entry(Basis.key(c, id));
            if (entry == null) {
                // Nothing the game has a place for: as it was, unless it is a wire to something the board lost.
                if (!"edges".equals(c) || !touchesGone(e.getAsJsonObject())) {
                    list.add(e);
                    if (nows != null) nows.add(null);
                }
                continue;
            }
            final Fresh.Thing now = fresh.remove(entry.gameKey());
            if (now == null) {
                if ("nodes".equals(c) || "storages".equals(c)) gone.add(id);
                continue;
            }
            JsonObject written = emit(id, e.getAsJsonObject(), entry, now, baseRecipes);
            if ("pockets".equals(c) && !e.getAsJsonObject()
                .has("size") && (written.has("size") || open(now))) {
                // A board standing open has a frame on the website (it rebases a never-opened one's members as it
                // opens it): written with the size the board has.
                if (!written.has("size")) {
                    written = LiveJson.shallow(written);
                    written.add(
                        "size",
                        LiveJson.copy(
                            now.site()
                                .get("size")));
                }
                opened.add(id);
            }
            list.add(written);
            if (nows != null) nows.add(now);
        }
        for (final Iterator<Fresh.Thing> it = fresh.values()
            .iterator(); it.hasNext();) {
            final Fresh.Thing now = it.next();
            if (!now.c()
                .equals(c)) continue;
            it.remove();
            list.add(add(now));
            if (nows != null) nows.add(now);
        }
        return list;
    }

    /** Whether a board (a pocket) stands open in the game. */
    private static boolean open(final Fresh.Thing pocket) {
        final JsonElement expanded = pocket.site()
            .get("expanded");
        return expanded != null && expanded.isJsonPrimitive()
            && expanded.getAsBoolean()
            && pocket.site()
                .has("size");
    }

    /** A written thing placed again where the board has it (a copy when it is the plan's own object). */
    private static JsonObject placed(final JsonElement written, final Fresh.Thing now) {
        final JsonObject o = LiveJson.shallow(written.getAsJsonObject());
        place(o, now);
        return o;
    }

    // region The plan's own fields

    /**
     * The plan's fields: the plan's own, the ones the board changed taken from it, and the board's game data for the
     * whole plan when that changed. A new plan gets an id.
     */
    private void meta(final JsonObject out) {
        for (final Map.Entry<String, JsonElement> e : base.entrySet())
            if (!LiveOps.isCollection(e.getKey())) out.add(e.getKey(), e.getValue());
        final Fresh.Meta now = Fresh.meta(graph, game, basis);
        merge(out, basis.metaSite == null ? new JsonObject() : basis.metaSite, now.site());
        // The plan's game data as the plan had it while the board's did not change (none, on a plan the website made).
        final String state = Canon.of(now.game());
        if (!state.equals(basis.metaState)) {
            final JsonObject ext = new JsonObject();
            ext.addProperty("v", 1);
            ext.add("game", now.game());
            ext.addProperty("sum", PlanRead.metaSum(out));
            out.add("gtnhplanner", ext);
        }
        if (!out.has("id")) out.addProperty(
            "id",
            UUID.randomUUID()
                .toString());
        next.metaState = state;
        next.metaSite = now.site();
    }

    // endregion

    // region Things

    /**
     * A thing the plan has (under {@code id}, which a drawer's link the website made keeps) and the board holds, as the
     * board has it now.
     */
    private JsonObject emit(final String id, final JsonObject thing, final Basis.Entry entry, final Fresh.Thing now,
        final Map<String, JsonObject> baseRecipes) {
        next.map(now.c(), id, now.gameKey());
        final boolean moved = now.placed() && (!entry.at(now) || opened.contains(now.pocket()));
        if (now.state()
            .equals(entry.state())) {
            next.things.put(
                Basis.key(now.c(), id),
                new Basis.Entry(
                    entry.gameKey(),
                    entry.state(),
                    entry.site(),
                    entry.recipe(),
                    now.x(),
                    now.y(),
                    now.pocket()));
            final Boolean sum = basis.sums.get(thing);
            if (!moved) {
                if (sum != null) next.sums.put(thing, sum);
                return thing;
            }
            final JsonObject o = LiveJson.shallow(thing);
            place(o, now);
            return o;
        }
        next.things.put(Basis.key(now.c(), id), Basis.Entry.of(now));
        final JsonObject o = LiveJson.shallow(thing);
        merge(o, entry.site(), now.site());
        if (moved) place(o, now);
        if ("nodes".equals(now.c())) recipe(o, entry.recipe(), now, baseRecipes);
        if (now.game() != null) o.add("gtnhplanner", Fresh.extension(now.game(), o));
        return o;
    }

    /** A thing the board has that the plan does not: written whole. */
    private JsonObject add(final Fresh.Thing now) {
        next.map(now.c(), now.id(), now.gameKey());
        next.things.put(Basis.key(now.c(), now.id()), Basis.Entry.of(now));
        final JsonObject o = LiveJson.copy(now.site());
        if (now.placed()) place(o, now);
        if (now.recipe() != null) recipes.put(ownRecipe(now.id()), withId(now.recipe(), ownRecipe(now.id())));
        if (now.game() != null) o.add("gtnhplanner", Fresh.extension(now.game(), o));
        return o;
    }

    /**
     * A changed card's recipe. Unchanged, the card keeps the one it has. Changed, a recipe of the card's own takes the
     * fields that changed; a recipe the card shares with others (or one the website made) is left to them, and the
     * card gets a recipe of its own.
     */
    private void recipe(final JsonObject card, @Nullable final JsonObject was, final Fresh.Thing now,
        final Map<String, JsonObject> baseRecipes) {
        if (now.recipe() == null || LiveJson.equal(was, now.recipe())) return;
        final String own = ownRecipe(now.id());
        final JsonObject had = baseRecipes.get(PlanRead.str(card, "recipeId"));
        if (own.equals(PlanRead.str(card, "recipeId")) && had != null && was != null) {
            final JsonObject merged = LiveJson.shallow(had);
            merge(merged, was, now.recipe());
            recipes.put(own, merged);
            return;
        }
        recipes.put(own, withId(now.recipe(), own));
        card.addProperty("recipeId", own);
    }

    private static String ownRecipe(final String cardId) {
        return FfPlan.GAME_RECIPE_PREFIX + cardId;
    }

    private static JsonObject withId(final JsonObject recipe, final String id) {
        final JsonObject o = LiveJson.copy(recipe);
        o.addProperty("id", id);
        return o;
    }

    /**
     * The recipes: the plan's, in its order (one a card changed as changed), but not one only the board's cards used
     * that none uses now; then the new ones. A recipe no card used is kept: it is the website's business.
     */
    private JsonArray recipes(final Map<String, JsonObject> baseRecipes, final JsonObject out) {
        final Set<String> used = used(out), usedBefore = used(base);
        final JsonArray list = new JsonArray();
        for (final JsonElement r : LiveOps.collection(base, "recipes")) {
            final String id = LiveJson.idOf(r);
            if (id == null) {
                list.add(r);
                continue;
            }
            final JsonObject changed = recipes.remove(id);
            if (changed != null) list.add(changed);
            else if (used.contains(id) || !usedBefore.contains(id)) list.add(r);
        }
        for (final JsonObject r : recipes.values()) list.add(r);
        return list;
    }

    /** The recipes a plan's cards run, their shared machines' later recipes too. */
    private static Set<String> used(final JsonObject plan) {
        final Set<String> ids = new HashSet<>();
        for (final JsonObject n : PlanRead.objects(plan, "nodes")) {
            final String id = PlanRead.str(n, "recipeId");
            if (id != null) ids.add(id);
            for (final JsonObject x : PlanRead.objects(n, "extraRecipes")) {
                final String extra = PlanRead.str(x, "recipeId");
                if (extra != null) ids.add(extra);
            }
        }
        return ids;
    }

    /** Whether a wire runs to a card or drawer the board lost. */
    private boolean touchesGone(final JsonObject edge) {
        final String source = PlanRead.str(edge, "source"), target = PlanRead.str(edge, "target");
        return source != null && gone.contains(source) || target != null && gone.contains(target);
    }

    // endregion

    /**
     * Sets on {@code target} each field {@code now} has that differs from {@code was}, and deletes each field
     * {@code was} has that {@code now} lacks: the fields a change touched, over whatever else the target holds.
     */
    private static void merge(final JsonObject target, final JsonObject was, final JsonObject now) {
        for (final Map.Entry<String, JsonElement> e : now.entrySet()) {
            if ("id".equals(e.getKey())) continue;
            if (!LiveJson.equal(was.get(e.getKey()), e.getValue())) target.add(e.getKey(), LiveJson.copy(e.getValue()));
        }
        for (final Map.Entry<String, JsonElement> e : was.entrySet())
            if (!now.has(e.getKey()) && !"id".equals(e.getKey())) target.remove(e.getKey());
    }

    /**
     * A thing's place, written: the pocket it is in (none on the board) and its position at the site's scale, in that
     * pocket's frame. Each position is measured from its own pocket's corner in the board's pixels, so a group moved
     * moves only its pocket, and every one comes back to the pixel ({@code PlanExport.gameX}).
     */
    private static void place(final JsonObject o, final Fresh.Thing now) {
        o.add("position", PlanExport.position(now.x(), now.y()));
        final String field = Fresh.pocketField(now.c());
        if (now.pocket() != null) o.addProperty(field, now.pocket());
        else o.remove(field);
    }
}
