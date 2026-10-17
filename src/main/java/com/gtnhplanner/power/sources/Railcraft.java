package com.gtnhplanner.power.sources;

import static com.gtnhplanner.power.sources.Helpers.*;

import java.util.ArrayList;
import java.util.List;

import com.gtnhplanner.power.PowerData;
import com.gtnhplanner.power.PowerData.Fuel;
import com.gtnhplanner.power.PowerGroup;
import com.gtnhplanner.power.PowerModel;
import com.gtnhplanner.power.PowerModel.Flow;
import com.gtnhplanner.power.PowerSetting;
import com.gtnhplanner.power.PowerSetting.Option;
import com.gtnhplanner.power.PowerSource;

/**
 * The website's sources/railcraft.ts: Railcraft's Water Tank and its steam boiler, from Railcraft's source at the
 * version GTNH 2.9 ships (GTNewHorizons/Railcraft tag 9.17.26; research/railcraft-water-tank-and-boilers.md has the
 * links). Both are modelled at steady state: the tank as it fills, the boiler at full heat.
 */
public final class Railcraft {

    /** Seconds as "45 s", "12 min" or "2 h 15 min". */
    static String duration(final double seconds) {
        if (!Double.isFinite(seconds)) return "-";
        if (seconds < 60) return Math.round(seconds) + " s";
        final long minutes = Math.round(seconds / 60);
        if (minutes < 60) return minutes + " min";
        final long rest = minutes % 60;
        return rest == 0 ? (minutes / 60) + " h" : (minutes / 60) + " h " + rest + " min";
    }

    // region Water Tank

    /**
     * TileTankWater.updateEntity: every 8 ticks the master adds max(1, floor(10 x rainfall x m)) L, in float maths,
     * nothing in the Nether. m is 1 under open sky, 3 in rain, 0.5 in snow (a snowy biome's rain) and 0.5 under a roof
     * in any weather (GTNH's sky check: any of the 9 blocks above the top sees the sky). Two tanks may not touch.
     */
    private record Sky(String key, String label, float multiplier) {}

    private static final List<Sky> WATER_TANK_SKY = List.of(
        new Sky("clear", "Open sky, clear", 1f),
        new Sky("rain", "Open sky, raining", 3f),
        new Sky("snow", "Open sky, snowing", 0.5f),
        new Sky("roof", "Under a roof", 0.5f),
        new Sky("nether", "In the Nether", 0f));
    private static final double WATER_TANK_CAPACITY = 400_000;
    private static final int WATER_TANK_INTERVAL_TICKS = 8;

    /** Litres one fill adds: Java float steps, as the game's, so the floor lands where it does. */
    public static int waterTankFill(final double humidity, final String sky) {
        Sky entry = WATER_TANK_SKY.get(0);
        for (final Sky row : WATER_TANK_SKY) if (row.key()
            .equals(sky)) entry = row;
        if (entry.multiplier() == 0) return 0;
        float rate = 10f;
        rate *= (float) humidity;
        rate *= entry.multiplier();
        return Math.max(1, (int) Math.floor(rate));
    }

    private static PowerSource waterTank() {
        final List<Option> skies = new ArrayList<>();
        for (final Sky row : WATER_TANK_SKY) skies.add(new Option(row.key(), row.label()));
        return new PowerSource(
            "railcraft-water-tank",
            "Railcraft Water Tank",
            PowerGroup.OTHER,
            null,
            "A 3x3x3 tank that fills itself with water from the sky.",
            List.of(
                new PowerSetting.Number("humidity", "Biome humidity", 0, 2, 0.05, 0.8),
                new PowerSetting.Select("sky", "Sky", skies, "clear")),
            read -> {
                final int fill = waterTankFill(read.number("humidity"), read.select("sky"));
                final double perSecond = (fill * 20.0) / WATER_TANK_INTERVAL_TICKS;
                return new PowerModel(
                    0,
                    List.of(),
                    perSecond > 0 ? List.of(liters("Water", perSecond)) : List.of(),
                    List.of(
                        stat("Fill", fill + " L every 8 ticks"),
                        stat("Stores", formatAmount(WATER_TANK_CAPACITY) + " L"),
                        stat("Full in", perSecond > 0 ? duration(WATER_TANK_CAPACITY / perSecond) : "-")),
                    perSecond > 0 ? List.of() : List.of("Makes nothing in the Nether."));
            });
    }

    // endregion

    // region Boiler

    /**
     * SteamBoiler at full heat. A cycle is 16 ticks on low pressure tanks, 8 on high; each burns N x (8 - 0.1N + 0.8 x
     * min(heatLevel, 1) + 4 x maxHeat/1000) fuel units and turns N x heatLevel litres of water into 160 L of steam
     * each. GTNH reads heatLevel as heat / 500 on both pressures (Railcraft a0dc844e), so high pressure at 1000 C makes
     * twice upstream's steam for the same fuel. A fuel unit is a furnace burn tick, or a liquid's heat per 1000 L.
     * Plain water only, and water poured into a hot empty boiler blows it up.
     */
    private record Size(String key, String label, int width, int tanks) {}

    private static final List<Size> BOILER_SIZES = List.of(
        new Size("1x1x1", "1x1x2, 1 tank", 1, 1),
        new Size("2x2x2", "2x2x3, 8 tanks", 2, 8),
        new Size("2x2x3", "2x2x4, 12 tanks", 2, 12),
        new Size("3x3x2", "3x3x3, 18 tanks", 3, 18),
        new Size("3x3x3", "3x3x4, 27 tanks", 3, 27),
        new Size("3x3x4", "3x3x5, 36 tanks", 3, 36));

    private record Pressure(String key, String label, int cycleTicks, double maxHeat) {}

    private static final List<Pressure> BOILER_PRESSURES = List
        .of(new Pressure("low", "Low pressure", 16, 500), new Pressure("high", "High pressure", 8, 1000));

    private record Solid(String name, double burnTicks) {}

    /**
     * The solid firebox burns whatever a furnace burns, at its furnace value (FuelPlugin): the workbook's furnace
     * table, with Railcraft's own 1000 for a blaze rod. Lava containers (1000 a bucket, worse than coal, leaving the
     * container) and wood are left out.
     */
    private static List<Solid> boilerSolids() {
        final List<Solid> out = new ArrayList<>();
        for (final Fuel entry : PowerData.get().boilerFuels.get("bronzeSolid")) out.add(
            new Solid(
                entry.name,
                "Blaze Rod".equals(entry.name) ? 1000 : entry.euPerItem != null ? entry.euPerItem : 0));
        return out;
    }

    private record Liquid(String name, String label, double heat) {}

    /**
     * FuelManager's boiler fuels in the pack, heat per 1000 L: Railcraft's own (creosote raised to 6400 by GTNH),
     * GT++'s bio diesel and EnderIO's IMC. Liquified coal is registered only if a "coal" fluid exists; GTNH has none.
     */
    private static final List<Liquid> BOILER_LIQUIDS = List.of(
        new Liquid("Creosote Oil", "Creosote Oil", 6400),
        new Liquid("Ethanol", "Bio Ethanol", 16_000),
        new Liquid("Biofuel", "Biofuel", 16_000),
        new Liquid("Bio Diesel", "Bio Diesel", 16_000),
        new Liquid("Hootch", "Hootch", 36_000),
        new Liquid("Diesel (Fuel)", "Diesel (Fuel)", 48_000),
        new Liquid("Blazing Pyrotheum", "Blazing Pyrotheum", 64_000),
        new Liquid("EIO Rocket Fuel", "Rocket Fuel", 112_000),
        new Liquid("Fire Water", "Fire Water", 120_000));

    /** Pollution a second per firebox block while burning (GT's MixinRailcraftBoilerPollution, Pollution.cfg). */
    private static final double BOILER_POLLUTION_PER_FIREBOX = 20;

    /** Ticks from 20 C to full heat while burning: dH/dt = 0.15 (4max/3 - H) / (N max). */
    public static double boilerWarmUpTicks(final int tanks, final double maxHeat) {
        final double ceiling = (maxHeat * 4) / 3;
        return ((maxHeat * tanks) / 0.15) * Math.log((ceiling - 20) / (ceiling - maxHeat));
    }

    private static PowerSource boiler() {
        final List<Solid> solids = boilerSolids();
        final List<Option> sizes = new ArrayList<>();
        for (final Size row : BOILER_SIZES) sizes.add(new Option(row.key(), row.label()));
        final List<Option> pressures = new ArrayList<>();
        for (final Pressure row : BOILER_PRESSURES) pressures.add(new Option(row.key(), row.label()));
        // One fuel list: a solid needs the Solid Fueled Firebox, a liquid the Liquid Fueled one.
        final List<Option> fuels = new ArrayList<>();
        for (final Solid row : solids) fuels.add(new Option(row.name(), row.name()));
        for (final Liquid row : BOILER_LIQUIDS) fuels.add(new Option(row.name(), row.label()));
        return new PowerSource(
            "railcraft-boiler",
            "Railcraft Boiler",
            PowerGroup.STEAM,
            "ULV",
            "Up to 28,800 L/s of steam, after a long warm-up.",
            List.of(
                new PowerSetting.Select("size", "Size", sizes, "3x3x4"),
                new PowerSetting.Select("pressure", "Tanks", pressures, "low"),
                new PowerSetting.Select("fuel", "Fuel", fuels, "Coal")),
            read -> {
                Size size = BOILER_SIZES.get(0);
                for (final Size row : BOILER_SIZES) if (row.key()
                    .equals(read.select("size"))) size = row;
                Pressure pressure = BOILER_PRESSURES.get(0);
                for (final Pressure row : BOILER_PRESSURES) if (row.key()
                    .equals(read.select("pressure"))) pressure = row;
                final int n = size.tanks();
                final double cyclesPerSecond = 20.0 / pressure.cycleTicks();
                final double heatLevel = pressure.maxHeat() / 500;
                final double fuelPerCycle = n
                    * (8 - 0.1 * n + 0.8 * Math.min(heatLevel, 1) + (4 * pressure.maxHeat()) / 1000);
                final double fuelPerSecond = fuelPerCycle * cyclesPerSecond;
                final double waterPerSecond = n * heatLevel * cyclesPerSecond;
                final double steamPerSecond = waterPerSecond * 160;

                final String fuelName = read.select("fuel");
                Liquid liquid = null;
                for (final Liquid row : BOILER_LIQUIDS) if (row.name()
                    .equals(fuelName)) liquid = row;
                Solid solid = null;
                if (liquid == null) {
                    solid = solids.get(0);
                    for (final Solid row : solids) if (row.name()
                        .equals(fuelName)) solid = row;
                }
                final List<Flow> inputs = new ArrayList<>();
                if (liquid != null) {
                    inputs.add(liters(liquid.name(), (fuelPerSecond * 1000) / liquid.heat()));
                } else if (solid.burnTicks() > 0) {
                    inputs.add(items(solid.name(), fuelPerSecond / solid.burnTicks()));
                }
                inputs.add(liters("Water", waterPerSecond));

                final List<PowerModel.Stat> stats = new ArrayList<>();
                stats.add(stat("Steam", formatAmount(steamPerSecond / 20) + " L/t"));
                stats.add(stat("Firebox", liquid != null ? "Liquid Fueled" : "Solid Fueled"));
                stats.add(stat("Warm-up", duration(boilerWarmUpTicks(n, pressure.maxHeat()) / 20)));
                stats.add(
                    stat("Pollution", formatAmount(BOILER_POLLUTION_PER_FIREBOX * size.width() * size.width()) + "/s"));
                if (solid != null && solid.burnTicks() > 0) {
                    stats.add(stat("One item burns", formatAmount(solid.burnTicks() / fuelPerSecond) + "s"));
                }
                return new PowerModel(0, inputs, List.of(liters("Steam", steamPerSecond)), stats);
            });
    }

    // endregion

    /** The Other column's sources: the Water Tank. */
    public static List<PowerSource> otherSources() {
        return List.of(waterTank());
    }

    /** Beside the GT boilers (SteamMakers): the boiler. */
    public static List<PowerSource> steamSources() {
        return List.of(boiler());
    }

    private Railcraft() {}
}
