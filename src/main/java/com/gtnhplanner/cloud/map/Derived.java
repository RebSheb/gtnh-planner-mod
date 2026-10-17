package com.gtnhplanner.cloud.map;

import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhplanner.cloud.sync.LiveJson;

/**
 * What a shared plan holds that is worked out from the rest of it, so it is never an edit: stripped from both plans
 * before every diff (docs/design/cloud-live.md, "Game mapping"), and left out of what the game writes. The list:
 * <ul>
 * <li>a power card recipe's readings, {@code recipes[].power.euPerTick}, {@code .stats} and {@code .warnings}: the
 * source's model at the card's settings, which both the game and the website work out again on load
 * ({@code sourceId} stays: it names the source).</li>
 * </ul>
 * Nothing the solve answers is written at all: a card's {@code machineCount} is the count it is set to (the posting
 * export writes the solve's answer there instead), and the solve's flows, scales and notes have no place in the plan.
 */
public final class Derived {

    /** The power readings, by their key under a recipe's {@code power}. */
    public static final List<String> POWER_READINGS = List.of("euPerTick", "stats", "warnings");

    private Derived() {}

    /** A recipe without what is worked out on load; the recipe itself when it has none of it. */
    public static JsonObject recipe(final JsonObject recipe) {
        final JsonElement power = recipe.get("power");
        if (power == null || !power.isJsonObject()) return recipe;
        boolean any = false;
        for (final String key : POWER_READINGS) any |= power.getAsJsonObject()
            .has(key);
        if (!any) return recipe;
        final JsonObject stripped = LiveJson.shallow(recipe);
        final JsonObject p = LiveJson.shallow(power.getAsJsonObject());
        for (final String key : POWER_READINGS) p.remove(key);
        stripped.add("power", p);
        return stripped;
    }

    /** A plan without what is worked out on load; untouched parts are shared with the plan given. */
    public static JsonObject plan(final JsonObject plan) {
        final JsonElement recipes = plan.get("recipes");
        if (recipes == null || !recipes.isJsonArray()) return plan;
        final JsonArray out = new JsonArray();
        boolean changed = false;
        for (final JsonElement r : recipes.getAsJsonArray()) {
            final JsonElement stripped = r.isJsonObject() ? recipe(r.getAsJsonObject()) : r;
            changed |= stripped != r;
            out.add(stripped);
        }
        if (!changed) return plan;
        final JsonObject copy = LiveJson.shallow(plan);
        copy.add("recipes", out);
        return copy;
    }
}
