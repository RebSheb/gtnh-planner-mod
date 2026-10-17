package com.gtnhplanner.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.balancer.BalanceMode;
import com.gtnhplanner.data.flowchart.balancer.Balancer;
import com.gtnhplanner.data.flowchart.balancer.External;
import com.gtnhplanner.data.flowchart.balancer.PortRef;
import com.gtnhplanner.data.flowchart.balancer.SolutionView;
import com.gtnhplanner.data.flowchart.balancer.SolveInput;
import com.gtnhplanner.data.flowchart.balancer.SolverMessage;
import com.gtnhplanner.dev.SolveInputJson;

/**
 * Buffer drawers in the solver ({@code docs/design/buffer-drawers.md}), on the corpus case {@code buffer-drawers},
 * three
 * islands whose answers are easy to work by hand. Every machine takes one second a craft, so extents are crafts a
 * second and, at one machine, counts.
 *
 * <ol>
 * <li>Two washers make dust (1 and 2 a craft) into buffer A, which feeds a mixer (3 a craft) and a press (1 a craft,
 * pinned at one machine). A holds exactly 12 dust/s: the mixer takes the other 11.</li>
 * <li>A boiler pinned at one machine makes 10 steam/s into buffer B, which a turbine (4 a craft) draws from. B asks for
 * at
 * least 20/s: it cannot be kept, and says what holds it back (the boiler's pin).</li>
 * <li>A pump pinned at one machine makes 10 water/s, wired straight to a tank filler (1 a craft) and into buffer C,
 * which
 * feeds a cooler (2 a craft); C passes at most 3/s and the filler, pinned at four, takes 4: the rest is surplus.</li>
 * </ol>
 *
 * The case's input is checked in as {@code /solver-corpus/buffer-drawers.json} for both planners' goldens (the website
 * solves it in {@code src/lib/solver/plan/__fixtures__}); {@link #inputIsTheCorpusCase} keeps the two the same, and
 * {@code -PwriteGoldens} writes it afresh.
 */
class BufferDrawerTest {

    private static final double EPS = 1e-6;

    static final UUID WASHER_A = id("washer-a"), WASHER_B = id("washer-b"), MIXER = id("mixer"), PRESS = id("press"),
        BOILER = id("boiler"), TURBINE = id("turbine"), PUMP = id("pump"), FILLER = id("filler"), COOLER = id("cooler");
    static final UUID BUFFER_A = id("buffer-a"), BUFFER_B = id("buffer-b"), BUFFER_C = id("buffer-c");
    static final UUID PUMP_TO_FILLER = id("pump-to-filler");

    private static UUID id(final String name) {
        return UUID.nameUUIDFromBytes(("buffer-drawers:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static SolveInput.PortIn port(final double amount, final int resource, final String name) {
        return new SolveInput.PortIn(amount, 1f, 1, resource, name, false);
    }

    private static SolveInput.Machine machine(final UUID id, final String name, final List<SolveInput.PortIn> inputs,
        final List<SolveInput.PortIn> outputs, final int pinnedCount) {
        return new SolveInput.Machine(
            id,
            name,
            20,
            30,
            1,
            inputs,
            outputs,
            pinnedCount > 0,
            Math.max(1, pinnedCount),
            Map.of(),
            Map.of());
    }

    /** The corpus case, machines and drawers in id order as a snapshot has them. */
    static SolveInput input() {
        final SolveInput.PortIn ore = port(1, 0, "Ore"), dust1 = port(1, 1, "Dust"), dust2 = port(2, 1, "Dust");
        final List<SolveInput.Machine> machines = new java.util.ArrayList<>(
            List.of(
                machine(WASHER_A, "Washer A", List.of(ore), List.of(dust1), 0),
                machine(WASHER_B, "Washer B", List.of(ore), List.of(dust2), 0),
                machine(MIXER, "Mixer", List.of(port(3, 1, "Dust")), List.of(port(1, 2, "Mix")), 0),
                machine(PRESS, "Press", List.of(port(1, 1, "Dust")), List.of(port(1, 3, "Plate")), 1),
                machine(BOILER, "Boiler", List.of(port(1, 4, "Water")), List.of(port(10, 5, "Steam")), 1),
                machine(TURBINE, "Turbine", List.of(port(4, 5, "Steam")), List.of(), 0),
                machine(PUMP, "Pump", List.of(), List.of(port(10, 6, "Coolant")), 1),
                machine(FILLER, "Filler", List.of(port(1, 6, "Coolant")), List.of(port(1, 7, "Cell")), 4),
                machine(COOLER, "Cooler", List.of(port(2, 6, "Coolant")), List.of(port(1, 8, "Ice")), 0)));
        machines.sort(
            (a, b) -> a.id()
                .compareTo(b.id()));
        final List<SolveInput.DrawerIn> drawers = new java.util.ArrayList<>(
            List.of(
                new SolveInput.DrawerIn(
                    BUFFER_A,
                    "Dust",
                    Drawer.Kind.BUFFER,
                    Drawer.Rule.EXACTLY,
                    12,
                    List.of(new Drawer.Link(WASHER_A, 0), new Drawer.Link(WASHER_B, 0)),
                    List.of(new Drawer.Link(MIXER, 0), new Drawer.Link(PRESS, 0))),
                new SolveInput.DrawerIn(
                    BUFFER_B,
                    "Steam",
                    Drawer.Kind.BUFFER,
                    Drawer.Rule.AT_LEAST,
                    20,
                    List.of(new Drawer.Link(BOILER, 0)),
                    List.of(new Drawer.Link(TURBINE, 0))),
                new SolveInput.DrawerIn(
                    BUFFER_C,
                    "Coolant",
                    Drawer.Kind.BUFFER,
                    Drawer.Rule.AT_MOST,
                    3,
                    List.of(new Drawer.Link(PUMP, 0)),
                    List.of(new Drawer.Link(COOLER, 0)))));
        drawers.sort(
            (a, b) -> a.id()
                .compareTo(b.id()));
        return new SolveInput(
            BalanceMode.AUTO,
            null,
            machines,
            List.of(new SolveInput.EdgeIn(PUMP_TO_FILLER, PUMP, 0, FILLER, 0)),
            List.of(),
            drawers,
            Map.of());
    }

    private static SolutionView solve() {
        final Balancer.Answer answer = Balancer.solveWithAlternatives(input());
        if (answer instanceof final Balancer.Answer.Solved solved) return solved.solution();
        throw new AssertionError("no answer: " + answer);
    }

    @Test
    void anExactBufferSizesWhatFeedsAndDrawsFromIt() {
        final SolutionView s = solve();
        assertEquals(12, s.drawers.rate(BUFFER_A), EPS, "buffer A passes exactly its rate");
        assertTrue(s.drawers.met(BUFFER_A));
        assertEquals(11.0 / 3, s.extentsPerSecond.get(MIXER), EPS, "the mixer takes what the pinned press leaves");
        assertEquals(1, s.extentsPerSecond.get(PRESS), EPS);
        final double made = s.extentsPerSecond.get(WASHER_A) + 2 * s.extentsPerSecond.get(WASHER_B);
        assertEquals(12, made, EPS, "the washers make exactly what passes through");
        assertEquals(
            11,
            s.drawers.portRate(BUFFER_A, new PortRef(MIXER, 0, true)),
            EPS,
            "per port: what the mixer takes out");
        double in = 0;
        for (final External e : s.drawers.ports()
            .get(BUFFER_A))
            if (!e.port()
                .input()) in += e.ratePerSecond();
        assertEquals(12, in, EPS, "the makers' ports add up to the throughput");
    }

    @Test
    void aBufferRuleThePinsCannotKeepIsReportedWithItsLimit() {
        final SolutionView s = solve();
        assertFalse(s.drawers.met(BUFFER_B));
        assertEquals(10, s.drawers.rate(BUFFER_B), EPS, "all the pinned boiler makes goes through");
        assertEquals(2.5, s.extentsPerSecond.get(TURBINE), EPS);
        assertEquals(
            List.of(BOILER),
            s.drawers.shortfall(BUFFER_B)
                .limitingNodes(),
            "the boiler's pin holds it back");
        assertTrue(
            s.notes.stream()
                .anyMatch(n -> n.containsMessage(SolverMessage.DRAWER_UNMET)));
    }

    @Test
    void anAtMostBufferCapsWhatPassesAndTheRestIsSurplusAtTheMaker() {
        final SolutionView s = solve();
        assertTrue(s.drawers.rate(BUFFER_C) <= 3 + EPS, "at most 3 through C");
        assertEquals(4, s.extentsPerSecond.get(FILLER), EPS, "the filler pinned at four takes 4 straight off the wire");
        final double surplus = s.gatedSinks.stream()
            .filter(
                e -> e.port()
                    .nodeId()
                    .equals(PUMP))
            .mapToDouble(External::ratePerSecond)
            .sum();
        assertEquals(10 - 4 - s.drawers.rate(BUFFER_C), surplus, EPS, "the rest leaves at the pump, a forced output");
        assertFalse(s.edgeFlowsPerSecond.containsKey(BUFFER_C), "the buffer's own edges are not the plan's wires");
        assertEquals(1, s.edgeFlowsPerSecond.size(), "only the drawn wire is listed");
    }

    @Test
    void aBufferWithRuleAnyAnswersAsPlainWires() {
        final SolveInput withBuffer = input();
        // Buffer A as Any, the press's pin setting the scale: the same as wiring each washer to each user.
        final List<SolveInput.DrawerIn> drawers = withBuffer.drawers()
            .stream()
            .map(
                d -> d.id()
                    .equals(BUFFER_A)
                        ? new SolveInput.DrawerIn(
                            d.id(),
                            d.label(),
                            d.kind(),
                            Drawer.Rule.ANY,
                            0,
                            d.links(),
                            d.takers())
                        : d)
            .toList();
        final SolveInput any = new SolveInput(
            BalanceMode.AUTO,
            null,
            withBuffer.machines(),
            withBuffer.edges(),
            List.of(),
            drawers,
            Map.of());
        final List<SolveInput.EdgeIn> wires = new java.util.ArrayList<>(withBuffer.edges());
        for (final UUID from : List.of(WASHER_A, WASHER_B)) for (final UUID to : List.of(MIXER, PRESS))
            wires.add(new SolveInput.EdgeIn(id(from + ">" + to), from, 0, to, 0));
        final SolveInput plain = new SolveInput(
            BalanceMode.AUTO,
            null,
            withBuffer.machines(),
            wires,
            List.of(),
            drawers.stream()
                .filter(
                    d -> !d.id()
                        .equals(BUFFER_A))
                .toList(),
            Map.of());
        final SolutionView a = ((Balancer.Answer.Solved) Balancer.solveWithAlternatives(any)).solution();
        final SolutionView b = ((Balancer.Answer.Solved) Balancer.solveWithAlternatives(plain)).solution();
        for (final UUID m : List.of(WASHER_A, WASHER_B, MIXER, PRESS))
            assertEquals(b.extentsPerSecond.get(m), a.extentsPerSecond.get(m), EPS, "machine " + m);
        assertEquals(b.openGates, a.openGates);
    }

    @Test
    void inputIsTheCorpusCase() throws IOException {
        final String json = new GsonBuilder().setPrettyPrinting()
            .create()
            .toJson(SolveInputJson.write(input()));
        if (Boolean.getBoolean("gtnhplanner.solver.writeGoldens")) {
            Files.writeString(Path.of("src/test/resources/solver-corpus/buffer-drawers.json"), json + "\n");
            return;
        }
        try (InputStream in = BufferDrawerTest.class.getResourceAsStream("/solver-corpus/buffer-drawers.json")) {
            assertTrue(in != null, "no corpus input (run with -PwriteGoldens)");
            assertEquals(
                new JsonParser().parse(json),
                new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8)));
        }
    }
}
