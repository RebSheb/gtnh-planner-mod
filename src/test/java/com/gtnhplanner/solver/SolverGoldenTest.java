package com.gtnhplanner.solver;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.gtnhplanner.data.flowchart.balancer.Balancer;
import com.gtnhplanner.harness.GtnhFlowLoader;

/**
 * Every corpus plan's whole answer (counts, flows, gates, externals, notes, alternatives, drawers, spares) is what it
 * was when its golden was written: the solver may get faster, never different. {@code -PwriteGoldens} writes the
 * goldens afresh from the solver as it is (do that only on a solver known to be right).
 */
class SolverGoldenTest {

    private static final boolean WRITE = Boolean.getBoolean("gtnhplanner.solver.writeGoldens");
    private static final Path GOLDENS = Path.of("src/test/resources/solver-corpus/goldens");

    @BeforeAll
    static void profile() {
        GtnhFlowLoader.ensureDefaultMachineProfile();
        // Answers that do not depend on how busy the machine is: the goldens are the whole search's.
    }

    @AfterAll
    static void timed() {}

    @TestFactory
    Stream<DynamicTest> answersStayTheSame() {
        return SolverCorpus.cases(4)
            .stream()
            .map(c -> DynamicTest.dynamicTest(c.name(), () -> check(c)));
    }

    /** The biggest case, Platline ten times over (490 cards): slow before the solver's speed-up, so asked for. */
    @Tag("bench")
    @TestFactory
    Stream<DynamicTest> bigAnswerStaysTheSame() {
        return SolverCorpus.cases(10)
            .stream()
            .filter(
                c -> c.name()
                    .equals("platline-x10"))
            .map(c -> DynamicTest.dynamicTest(c.name(), () -> check(c)));
    }

    static void check(final SolverCorpus.Case c) throws IOException {
        final long start = System.nanoTime();
        final Balancer.Answer answer = Balancer.solveWithAlternatives(c.input());
        final long ms = (System.nanoTime() - start) / 1_000_000;
        System.out.println(
            "[solver] " + c.name()
                + ": "
                + c.input()
                    .machines()
                    .size()
                + " machines, "
                + ms
                + " ms");
        final JsonElement digest = SolverCorpus.digest(answer);
        if (WRITE) {
            Files.createDirectories(GOLDENS);
            Files.writeString(
                GOLDENS.resolve(c.name() + ".json"),
                new GsonBuilder().setPrettyPrinting()
                    .serializeSpecialFloatingPointValues()
                    .create()
                    .toJson(digest));
            return;
        }
        final JsonElement golden;
        try (InputStream in = SolverGoldenTest.class
            .getResourceAsStream("/solver-corpus/goldens/" + c.name() + ".json")) {
            assertTrue(in != null, "no golden for " + c.name() + " (run with -PwriteGoldens)");
            golden = new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        final List<String> diff = SolverCorpus.differences(golden, digest);
        assertTrue(diff.isEmpty(), c.name() + " answers differently:\n  " + String.join("\n  ", diff));
    }
}
