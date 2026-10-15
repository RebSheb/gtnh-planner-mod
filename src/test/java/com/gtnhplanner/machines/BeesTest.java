package com.gtnhplanner.machines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Bees on worked values from the game's code: Forestry's Bee.getFinalChance and produceStacks (two rolls a product, one
 * a specialty, each at most certain, once a 550-tick cycle) and GregTech's MTEIndustrialApiary (a queen's life an
 * operation, t = 8, its acceleration stopping at 100-tick operations, its draw).
 */
class BeesTest {

    /** Honey 30% and a specialty 15%, Normal speed, Normal lifespan (40 cycles, 60 in GTNH's HARD mode). */
    private static final Bees.Species BEE = new Bees.Species(
        "test.speciesTest",
        "Test",
        1.0f,
        40,
        false,
        List.of(
            new Bees.Product("forestry:beecombs", "Honey Comb", 1, 0.3f, false),
            new Bees.Product("gregtech:gt.comb@18", "Special Comb", 1, 0.15f, true)));

    private static Bees.Setup setup(final String... kv) {
        final Map<String, Object> s = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) s.put(kv[i], kv[i + 1]);
        return Bees.setup(s, BEE);
    }

    private static double out(final Bees.Run run, final String id) {
        return run.perCraft()
            .get(id);
    }

    @Test
    void getFinalChanceAsForestryWorksIt() {
        // 2.8 x 30^0.52 x 0.1^0.52 / 100: a plain Apiary's roll at 30%.
        assertEquals(0.0495748, Bees.finalChance(0.3f, 1f, -0.9f, 1f), 1e-6);
        // The Industrial Apiary's: 2.8 x 30^0.52 x 10^0.52 / 100.
        assertEquals(0.5435770, Bees.finalChance(0.3f, 1f, 2f, 8f), 1e-6);
        assertEquals(0, Bees.finalChance(0.3f, 1f, -9001f, 1f), "a term at or below 0 makes nothing");
    }

    @Test
    void anApiaryRollsEachProductTwiceACycle() {
        final Bees.Setup plain = setup();
        assertSame(Bees.APIARY, plain.housing(), "an Apiary by default");
        assertEquals(
            1.0f,
            plain.speed()
                .value(),
            "the species' own speed by default");
        final Bees.Run run = Bees.run(plain, BEE);
        assertEquals(550, run.ticks());
        assertEquals(0, run.euPerTick());
        // F(30%) + F(15%), its second species' half-chance roll.
        assertEquals(0.0841469, out(run, "forestry:beecombs"), 1e-6);
        // Three Proven Frames: p = -0.9 + 3, p + t = 3.1.
        final Bees.Setup framed = setup(
            Bees.FRAME + 1,
            "forestry:proven",
            Bees.FRAME + 2,
            "forestry:proven",
            Bees.FRAME + 3,
            "forestry:proven");
        assertEquals(3.1f, Bees.term(framed), 1e-6);
        assertEquals(0.5018179, out(Bees.run(framed, BEE), "forestry:beecombs"), 1e-6);
        // A Fast gene: x1.2^0.37 on every roll.
        assertEquals(0.0900192, out(Bees.run(setup(Bees.SPEED, "fast"), BEE), "forestry:beecombs"), 1e-6);
    }

    @Test
    void aForestryRollIsAtMostCertain() {
        final Bees.Species sure = new Bees.Species(
            "test.speciesSure",
            "Sure",
            2.0f,
            40,
            false,
            List.of(new Bees.Product("minecraft:sugar", "Sugar", 1, 1.0f, false)));
        final Map<String, Object> s = new HashMap<>();
        for (int i = 1; i <= 3; i++) s.put(Bees.FRAME + i, "magicbees:magic");
        final Bees.Setup magic = Bees.setup(s, sure);
        assertEquals(5.1f, Bees.productionModifier(magic), 1e-6);
        // F(100%) = 1.016 caps at 1; F(50%) = 0.709.
        assertEquals(
            1 + 0.7085574,
            Bees.run(magic, sure)
                .perCraft()
                .get("minecraft:sugar"),
            1e-6);
    }

    @Test
    void specialtiesNeedAJubilantBee() {
        final double jubilant = out(Bees.run(setup(), BEE), "gregtech:gt.comb@18");
        assertEquals(0.0345721, jubilant, 1e-6);
        assertEquals(0, out(Bees.run(setup(Bees.CLIMATE, "tolerated"), BEE), "gregtech:gt.comb@18"));
        assertEquals(
            0.0841469,
            out(Bees.run(setup(Bees.CLIMATE, "tolerated"), BEE), "forestry:beecombs"),
            1e-6,
            "a tolerated climate keeps its products");
        assertEquals(
            0,
            out(Bees.run(setup(Bees.CLIMATE, "wrong"), BEE), "forestry:beecombs"),
            "the wrong one stops it");
        final Bees.Species mega = new Bees.Species("t", "Mega", 1f, 40, true, BEE.products());
        assertFalse(Bees.jubilant(Bees.setup(Map.of(), mega), mega), "only a Mega Apiary pleases it");
        assertTrue(Bees.jubilant(setup(Bees.CLIMATE, "controlled"), BEE));
    }

    @Test
    void anIndustrialApiaryRunsAQueensLife() {
        final Bees.Setup ia = setup(Bees.HOUSING, "industrial-apiary");
        final Bees.Run run = Bees.run(ia, BEE);
        assertEquals(60f, run.cycles(), "40 cycles of life, 1.5 times over on HARD");
        assertEquals(33000, run.ticks());
        assertEquals(37, run.euPerTick());
        // (F(30%) + F(15%)) x 60 cycles, uncapped, t = 8.
        assertEquals(55.359138, out(run, "forestry:beecombs"), 1e-4);
        assertEquals(0.3790753 * 60, out(run, "gregtech:gt.comb@18"), 1e-4);
    }

    @Test
    void itsUpgradesAsGregTechAppliesThem() {
        // Acceleration x16 and four production upgrades: p = 4 x 1.2^4, energy 1.4^4.
        final Bees.Setup s = setup(
            Bees.HOUSING,
            "industrial-apiary",
            Bees.IA_SPEED,
            "speed-4",
            Bees.IA_PRODUCTION,
            "4");
        assertEquals(16.2944, Bees.term(s), 1e-4);
        final Bees.Run run = Bees.run(s, BEE);
        assertEquals(2062, run.ticks(), "33000 / 16");
        assertEquals(71.359091, out(run, "forestry:beecombs"), 1e-4);
        // (int) (37 x 1.4^4 x 16) + 32 x 4^3.
        assertEquals(4322, run.euPerTick());
        // x256: (int) (37 x 256) + 32 x 4^7; the upgraded one x14.75 energy and p = 17.2.
        assertEquals(533760, Bees.euPerTick(setup(Bees.HOUSING, "industrial-apiary", Bees.IA_SPEED, "speed-8")));
        final Bees.Setup upgraded = setup(
            Bees.HOUSING,
            "industrial-apiary",
            Bees.IA_SPEED,
            "speed-8-upgraded",
            Bees.IA_PRODUCTION,
            "3");
        assertEquals(
            664000,
            Bees.euPerTick(setup(Bees.HOUSING, "industrial-apiary", Bees.IA_SPEED, "speed-8-upgraded")));
        assertEquals(17.199268f + 8f, Bees.term(upgraded), 1e-5, "the upgraded acceleration's production wins");
    }

    @Test
    void aShortLifeStopsTheAcceleration() {
        final Bees.Species brief = new Bees.Species("t", "Brief", 1f, 10, false, BEE.products());
        final Map<String, Object> s = new HashMap<>();
        s.put(Bees.HOUSING, "industrial-apiary");
        s.put(Bees.IA_SPEED, "speed-8");
        final Bees.Run run = Bees.run(Bees.setup(s, brief), brief);
        // 15 cycles: 8250 ticks, so x256 stops at 82 (100 ticks), and the outputs come 256 / 82 = 3 times over.
        assertEquals(82, run.divisor());
        assertEquals(100, run.ticks());
        assertEquals(3, run.factor());
        assertEquals(15 * 0.9226523 * 3 / 100, out(run, "forestry:beecombs") / run.ticks(), 1e-6);
        final Bees.Run normal = Bees.run(Bees.setup(s, BEE), BEE);
        assertEquals(256, normal.divisor(), "a Normal lifespan reaches it");
        assertEquals(128, normal.ticks());
        assertEquals(1, normal.factor());
    }
}
