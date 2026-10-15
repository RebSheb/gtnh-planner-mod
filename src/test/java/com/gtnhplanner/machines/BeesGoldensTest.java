package com.gtnhplanner.machines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Bees against the website's own bee functions (passive-production.ts, machine-effects.ts), written by
 * tools/dev/bees: a card in each modelled housing on every speed gene, climate, frame set, aura and Industrial Apiary
 * upgrade. The website scales a base chance by (term / 0.1)^0.52 x speed^0.37 and gates a specialty on the climate; the
 * model's term (the housing's production modifier plus t), speed gene, frames, upgrades and jubilance must give the
 * same, and the Industrial Apiary the same acceleration. (Where the two part ways, how a chance becomes items and how
 * fast, is the game's, checked in BeesTest; docs/design/bees.md lists it.)
 */
class BeesGoldensTest {

    private static final String FILE = "/bee-goldens.jsonl.gz";

    /** A pure-bred bee with one product and one specialty, as the goldens' recipe has them. */
    private static final Bees.Species SPECIES = new Bees.Species(
        "gregtech.bee.speciesPulsatingiron",
        "Pulsating Iron",
        0.6f,
        30,
        false,
        List.of(
            new Bees.Product("forestry:beecombs@7", "Parched Comb", 1, 0.3f, false),
            new Bees.Product("gregtech:gt.comb@18", "Pulsating Iron Comb", 1, 0.15f, true)));

    @Test
    void matchesTheWebsite() throws Exception {
        final InputStream raw = BeesGoldensTest.class.getResourceAsStream(FILE);
        assertNotNull(raw, FILE);
        int cases = 0;
        final Map<String, Integer> byHousing = new HashMap<>();
        final List<String> mismatches = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(
            new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8))) {
            for (String line; (line = in.readLine()) != null;) {
                if (line.isEmpty()) continue;
                final JsonObject c = new JsonParser().parse(line)
                    .getAsJsonObject();
                cases++;
                byHousing.merge(
                    c.get("housing")
                        .getAsString(),
                    1,
                    Integer::sum);
                check(c, mismatches);
            }
        }
        assertTrue(cases > 20000, "cases: " + cases);
        assertEquals(3, byHousing.size(), "housings: " + byHousing);
        assertEquals(
            List.of(),
            mismatches.subList(0, Math.min(20, mismatches.size())),
            mismatches.size() + " mismatches");
    }

    private static void check(final JsonObject c, final List<String> mismatches) {
        final Map<String, Object> settings = new HashMap<>();
        for (final Map.Entry<String, JsonElement> e : c.getAsJsonObject("tiers")
            .entrySet())
            settings.put(
                e.getKey(),
                e.getValue()
                    .getAsString());
        final String housing = c.get("housing")
            .getAsString();
        settings.put(Bees.HOUSING, housing);
        final Bees.Setup s = Bees.setup(settings, SPECIES);
        final String at = housing + " " + c.getAsJsonObject("tiers");
        if (!s.housing()
            .key()
            .equals(housing)) mismatches.add(at + ": housing " + s.housing());

        // The website adds every frame's number as it is; the game stops an ExtraBees frame's boost at its ceiling
        // (BeeModifierLogic), which a Chocolate Frame meets after a Proven one in an aura-charged Magic Apiary. Only
        // there
        // may the two differ.
        double additive = Bees.term(s);
        if (!s.housing()
            .industrial()) {
            additive = (s.housing() == Bees.MAGIC_APIARY && s.aura() ? Bees.MAGIC_AURA_PRODUCTION
                : s.housing()
                    .production())
                + s.housing()
                    .t();
            boolean ceiling = false;
            for (final Bees.Frame f : s.frames()) if (f != null) {
                additive += f.value();
                ceiling |= f.rule() == Bees.FrameRule.CEILING && f.value() > 0;
            }
            if (Math.abs(additive - Bees.term(s)) > 1e-6 * Math.max(1, Math.abs(additive)) && !ceiling)
                mismatches.add(at + ": term " + Bees.term(s) + " against " + additive + " with no ceiling met");
        }

        // The website's multiplier on a base chance, from the model's term, gene and gates. A term of nothing (a
        // Gentle and two Necrotic frames in an Apiary) is a hair either side of 0 in floats and in doubles: no bees.
        final double term = additive;
        if (Math.abs(term) < 1e-5) return;
        final double scale = term <= 0 ? 0
            : Math.pow(term / 0.1, 0.52) * Math.pow(
                s.speed()
                    .value(),
                0.37);
        final double product = s.climate()
            .works() ? scale : 0;
        final double specialty = Bees.jubilant(s, SPECIES) ? scale : 0;
        near(at + " product", c.get("product"), product, mismatches);
        near(at + " specialty", c.get("specialty"), specialty, mismatches);

        // The Industrial Apiary's acceleration: the website's duration multiplier is one over it.
        final double duration = s.housing()
            .industrial()
                ? 1.0 / (1 << s.acceleration()
                    .speed())
                : 1;
        near(at + " duration", c.get("duration"), duration, mismatches);
    }

    private static void near(final String what, final JsonElement expected, final double actual,
        final List<String> mismatches) {
        final double want = expected.getAsDouble();
        // Floats on the game's side (0.3f, 17.199268f), doubles on the website's.
        if (Math.abs(want - actual) > 1e-6 * Math.max(1, Math.abs(want)))
            mismatches.add(what + ": website " + want + ", model " + actual);
    }
}
