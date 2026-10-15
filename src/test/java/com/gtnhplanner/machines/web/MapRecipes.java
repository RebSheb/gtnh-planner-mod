package com.gtnhplanner.machines.web;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonObject;

/** Test recipes and nodes on the game's maps, built from handlers.json as the game builds a card's (WebCards). */
final class MapRecipes {

    private MapRecipes() {}

    /** A recipe on a game map as WebCards.recipe builds it from the NEI recipe (the game's ladder left out). */
    static Web.Recipe recipe(final String mapId, final double eut, final double ticks, final double special) {
        final HandlerData.MapEntry entry = HandlerData.map(mapId);
        assertNotNull(entry, mapId);
        final Web.Recipe r = new Web.Recipe();
        r.id = mapId + ":test";
        r.name = "test";
        r.kind = "gregtech_machine";
        r.category = "gregtech";
        r.machineType = entry.name;
        r.durationTicks = ticks;
        r.eut = eut;
        r.minimumTier = "smelting".equals(mapId) ? "NONE" : HandlerData.recipeMinimumTier(eut);
        r.specialValue = special;
        r.nei = new Web.Nei();
        r.nei.additionalInfo = List.of("Special value: " + (long) special);
        r.source = new Web.Source();
        r.source.recipeMap = entry.name;
        r.metadata = new JsonObject();
        r.metadata.addProperty("recipeMapId", entry.id);
        r.metadata.addProperty("specialValue", special);
        r.inputs = List.of();
        r.outputs = List.of();
        r.machineConfigControls = HandlerData.recipeControls(entry, special);
        r.machineHandlers = HandlerData.handlers(entry, r.minimumTier, r.durationTicks, r.eut, r.machineConfigControls);
        return r;
    }

    /** One machine of {@code handlerId} on a hatch of {@code tier} with two amps, at these settings. */
    static Web.Node node(final String handlerId, final String tier, final Map<String, String> settings) {
        final Web.Node n = new Web.Node();
        n.overclockTier = tier;
        n.hatchVoltageTier = tier;
        n.hatchAmps = 2.0;
        n.powerInputMode = "amps";
        n.machineHandlerId = handlerId;
        n.machineConfigTiers = new LinkedHashMap<>(settings);
        n.parallel = 1.0;
        n.machineCount = 1.0;
        return n;
    }
}
