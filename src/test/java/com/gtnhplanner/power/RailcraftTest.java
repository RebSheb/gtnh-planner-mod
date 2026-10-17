package com.gtnhplanner.power;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.power.PowerModel.Flow;
import com.gtnhplanner.power.sources.Railcraft;

/**
 * The website's sources/railcraft.test.ts, case for case: Railcraft's Water Tank and boiler against Railcraft's source
 * (GTNH fork, tag 9.17.26). The same values on both sides keep plans moving between them unchanged.
 */
class RailcraftTest {

    static PowerModel compute(final String sourceId, final Map<String, String> settings) {
        final PowerSource source = PowerRegistry.get(sourceId);
        if (source == null) fail("No power source " + sourceId);
        return source.compute(settings);
    }

    static double flow(final PowerModel model, final String name) {
        final List<Flow> all = new ArrayList<>(model.inputs());
        all.addAll(model.outputs());
        for (final Flow flow : all) if (flow.name()
            .equals(name)) return flow.perSecond();
        return Double.NaN;
    }

    // ---- Railcraft Water Tank (TileTankWater.updateEntity)

    @Test
    void fillsEightLitresEveryEightTicksInAForest() {
        final PowerModel model = compute("railcraft-water-tank", Map.of());
        assertEquals(List.of(), model.inputs());
        assertEquals(List.of(new Flow("Water", 20, PowerModel.Unit.L)), model.outputs());
        assertEquals(0, model.euPerTick(), 0);
    }

    @Test
    void floorsEachFillInFloatMaths() {
        // 10 x 0.9 x 0.5 = 4.5 -> 4 L: 10 L/s, not the wiki's 11.25 (or 33.75 with rain).
        assertEquals(4, Railcraft.waterTankFill(0.9, "roof"));
        assertEquals(10, flow(compute("railcraft-water-tank", Map.of("humidity", "0.9", "sky", "roof")), "Water"));
        assertEquals(75, flow(compute("railcraft-water-tank", Map.of("humidity", "1", "sky", "rain")), "Water"));
        assertEquals(90, flow(compute("railcraft-water-tank", Map.of("humidity", "1.2", "sky", "rain")), "Water"));
        assertEquals(5, flow(compute("railcraft-water-tank", Map.of("humidity", "0.4", "sky", "snow")), "Water"));
    }

    @Test
    void neverLessThanOneLitreOutsideTheNetherAndNothingInIt() {
        assertEquals(2.5, flow(compute("railcraft-water-tank", Map.of("humidity", "0")), "Water"));
        assertEquals(2.5, flow(compute("railcraft-water-tank", Map.of("humidity", "0.05")), "Water"));
        final PowerModel nether = compute("railcraft-water-tank", Map.of("sky", "nether"));
        assertEquals(List.of(), nether.outputs());
        assertEquals(List.of("Makes nothing in the Nether."), nether.warnings());
    }

    @Test
    void listsUnderOtherAfterThePlanningCards() {
        final PowerSource tank = PowerRegistry.get("railcraft-water-tank");
        assertNotNull(tank);
        assertEquals(PowerGroup.OTHER, tank.group());
        final List<PowerSource> other = PowerRegistry.inGroup(PowerGroup.OTHER);
        assertEquals(List.of(CustomRate.SOURCE, FluidTank.SOURCE, tank), other);
        assertEquals("water", PowerResources.resolve("Water").id);
    }

    // ---- Railcraft Boiler (SteamBoiler at full heat)

    @Test
    void runsABigLowPressureBoilerOnCoal() {
        final PowerModel model = compute("railcraft-boiler", Map.of());
        assertEquals(7200, flow(model, "Steam"));
        assertEquals(45, flow(model, "Water"));
        // 1.25 x 36 x (10.8 - 3.6) = 324 fuel units a second; coal burns 1600.
        assertEquals(324.0 / 1600, flow(model, "Coal"), 1e-10);
        assertEquals(0, model.euPerTick(), 0);
    }

    @Test
    void givesGtnhsDoubledSteamOnHighPressure() {
        final PowerModel model = compute("railcraft-boiler", Map.of("pressure", "high", "size", "1x1x1"));
        assertEquals(800, flow(model, "Steam"));
        assertEquals(5, flow(model, "Water"));
        // 2.5 x (12.8 - 0.1) = 31.75 units a second.
        assertEquals(31.75 / 1600, flow(model, "Coal"), 1e-10);
    }

    @Test
    void burnsLiquidsAtTheirHeatPerBucket() {
        final PowerModel creosote = compute("railcraft-boiler", Map.of("pressure", "high", "fuel", "Creosote Oil"));
        // 2.5 x 36 x (12.8 - 3.6) = 828 units a second.
        assertEquals(129.375, flow(creosote, "Creosote Oil"), 1e-10);
        assertEquals("Liquid Fueled", creosote.stat("Firebox"));
        assertEquals(
            17.25,
            flow(compute("railcraft-boiler", Map.of("pressure", "high", "fuel", "Diesel (Fuel)")), "Diesel (Fuel)"),
            1e-10);
        assertEquals(
            100_000.0 / 36_000,
            flow(compute("railcraft-boiler", Map.of("size", "2x2x2", "fuel", "Hootch")), "Hootch"),
            1e-10);
    }

    @Test
    void countsABlazeRodAtRailcraftsThousand() {
        final PowerModel model = compute("railcraft-boiler", Map.of("size", "2x2x2", "fuel", "Blaze Rod"));
        assertEquals(0.1, flow(model, "Blaze Rod"), 1e-10);
    }

    @Test
    void statesWarmUpAndPollution() {
        assertEquals(4519, Railcraft.boilerWarmUpTicks(1, 500), 0.5);
        assertEquals(9141, Railcraft.boilerWarmUpTicks(1, 1000), 0.5);
        final PowerModel model = compute("railcraft-boiler", Map.of());
        assertEquals("2 h 16 min", model.stat("Warm-up"));
        assertEquals("180/s", model.stat("Pollution"));
    }

    @Test
    void resolvesEveryFuelItOffersAndSitsWithTheBoilers() {
        final PowerSource source = PowerRegistry.get("railcraft-boiler");
        assertNotNull(source);
        final PowerSetting.Select fuel = assertInstanceOf(PowerSetting.Select.class, source.setting("fuel"));
        for (final PowerSetting.Option option : fuel.options())
            assertNotNull(PowerResources.resolve(option.key()), option.key());
        assertEquals(PowerGroup.STEAM, source.group());
        // Right after the GT large boilers, before the exchangers.
        final List<String> steam = new ArrayList<>();
        for (final PowerSource s : PowerRegistry.inGroup(PowerGroup.STEAM)) steam.add(s.id());
        assertTrue(steam.indexOf("railcraft-boiler") == steam.indexOf("large-tungstensteel-boiler") + 1);
    }
}
