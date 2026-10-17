package com.gtnhplanner.cloud.map;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;

/**
 * What a shared plan was read as: for each of its things the board thing it became, that thing's state then and the
 * website fields the game would have written for it. Writing the board back against the same plan uses it to tell what
 * the player changed, so a thing nobody changed is written exactly as the plan had it (a website card stays the
 * website's), and a changed one changes only the fields its change touched. Things the game has no place for are not
 * here, and are written back untouched. Kept beside the board while the plan is open; each write gives the next one.
 */
public final class Basis {

    /**
     * One thing as the board holds it.
     *
     * @param gameKey what it is on the board ({@code node:<uuid>}, {@code link:<drawer>:<node>:<port>}, ...)
     * @param state   the game's own data for it, in one spelling ({@link Canon}), place left out
     * @param site    the website fields the game writes for it, place left out
     * @param recipe  a card's recipe as the game writes it; null on anything else
     * @param x       its place, when it has one: where it is in its group's frame (on the board, in none)
     * @param pocket  the plan id of the group (pocket) it is in; null on the board
     */
    public record Entry(String gameKey, String state, JsonObject site, @Nullable JsonObject recipe, int x, int y,
        @Nullable String pocket) {

        static Entry of(final Fresh.Thing t) {
            return new Entry(t.gameKey(), t.state(), t.site(), t.recipe(), t.x(), t.y(), t.pocket());
        }

        /** Whether a thing is where this entry has it. */
        boolean at(final Fresh.Thing t) {
            return x == t.x() && y == t.y() && java.util.Objects.equals(pocket, t.pocket());
        }
    }

    /** By thing (collection and id, {@link #key}). */
    final Map<String, Entry> things = new LinkedHashMap<>();
    /** By board thing, the plan thing it is. */
    final Map<String, String> byGame = new HashMap<>();
    /**
     * Board things a website card brings beyond itself (a shared machine's later recipes and their group), by game key:
     * the card's thing. They are written only as part of the card.
     */
    final Map<String, String> carried = new HashMap<>();
    /**
     * Whether each thing of the plan read still matched the sum its game data was written with, by the very object:
     * a later read of a plan sharing the object need not hash it again.
     */
    final Map<JsonObject, Boolean> sums = new IdentityHashMap<>();
    /** The plan-wide game data then, in one spelling, and the plan's website fields the game would have written. */
    @Nullable
    String metaState;
    @Nullable
    JsonObject metaSite;

    /** A thing's key: its collection and id. */
    static String key(final String c, final String id) {
        return c + "\n" + id;
    }

    static String idOfKey(final String key) {
        return key.substring(key.indexOf('\n') + 1);
    }

    /** Reading: the plan thing became this board thing. A board thing two plan things became is the last one's. */
    void map(final String c, final String id, final String gameKey) {
        byGame.put(gameKey, key(c, id));
    }

    void carry(final String gameKey, final String c, final String ownerId) {
        carried.put(gameKey, key(c, ownerId));
    }

    /**
     * Reading done: each plan thing's entry from what the board now writes for its board thing. A board thing that is
     * gone (a later wire took its ports) leaves its plan thing to be kept untouched, as one the game has no place for.
     */
    void settle(final Map<String, Fresh.Thing> fresh) {
        settle(fresh, null, Set.of(), Set.of());
    }

    /**
     * As {@link #settle(Map)}, following an earlier read ({@code previous}): a thing in {@code same} (the earlier
     * plan's very object) keeps the entry it had there while its state and place are the same, and a board thing in
     * {@code kept} (taken over from the earlier board, so left out of {@code fresh}) keeps its entry whole. Nothing of
     * those is worked out again.
     */
    void settle(final Map<String, Fresh.Thing> fresh, @Nullable final Basis previous, final Set<String> same,
        final Set<String> kept) {
        for (final Iterator<Map.Entry<String, String>> it = byGame.entrySet()
            .iterator(); it.hasNext();) {
            final Map.Entry<String, String> m = it.next();
            final Entry was = previous == null || !same.contains(m.getValue()) ? null
                : previous.things.get(m.getValue());
            final boolean usable = was != null && was.gameKey()
                .equals(m.getKey());
            final Fresh.Thing t = fresh.get(m.getKey());
            if (t == null) {
                if (usable && kept.contains(m.getKey())) things.put(m.getValue(), was);
                else it.remove();
            } else if (usable && was.state()
                .equals(t.state()) && was.at(t)) things.put(m.getValue(), was);
            else things.put(m.getValue(), Entry.of(t));
        }
    }

    @Nullable
    Entry entry(final String key) {
        return things.get(key);
    }

    @Nullable
    String thingOf(final String gameKey) {
        return byGame.get(gameKey);
    }

    /**
     * The plan id of a board thing ({@code node}, {@code drawer}, {@code edge}, {@code note}, {@code pocket}), as
     * presence names what is selected: the one it was read from, else its UUID. {@link PlanIds#uuid} turns one back.
     */
    public String planId(final String kind, final UUID uuid) {
        return id(kind, uuid);
    }

    /**
     * The plan id of a board thing ({@code node}, {@code drawer}, {@code edge}, {@code note}, {@code pocket}): the one
     * it was read from (a carried thing's card's), else its UUID.
     */
    String id(final String kind, final UUID uuid) {
        final String gameKey = kind + ":" + uuid;
        String key = byGame.get(gameKey);
        if (key == null) key = carried.get(gameKey);
        return key == null ? uuid.toString() : idOfKey(key);
    }

    boolean isCarried(final String gameKey) {
        return carried.containsKey(gameKey);
    }

    /** How many things of the plan the board holds. */
    public int size() {
        return things.size();
    }
}
