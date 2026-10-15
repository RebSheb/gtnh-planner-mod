package com.gtnhplanner.data.flowchart.balancer;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.dev.SolveInputJson;
import com.gtnhplanner.harness.GtnhFlowLoader;

/**
 * Audits a recorded answer from its numbers alone, whoever solved it: rebuilds the point from the answer's machine
 * counts, wire flows and boundary flows, and checks it against the plan as the solver models it (every port balances,
 * every pin holds), then scores it on the solver's rules. {@code ./gradlew test -Pbench=audit --tests '*AnswerAudit*'
 * -Paudit=<dir of answers>[,<dir>...]}: one line per plan and answer set.
 */
@Tag("bench")
class AnswerAudit {

    @Test
    void audit() throws IOException {
        GtnhFlowLoader.ensureDefaultMachineProfile();
        final String dirs = System.getProperty("gtnhplanner.audit", "src/test/resources/solver-corpus/goldens");
        final Path corpus = Path.of("src/test/resources/solver-corpus");
        for (final String dir : dirs.split(",")) {
            System.out.println("[audit] ===== " + dir);
            try (var files = Files.list(Path.of(dir))) {
                for (final Path file : files.sorted()
                    .toList()) {
                    final String name = file.getFileName()
                        .toString()
                        .replace(".json", "");
                    final SolveInput input = inputOf(corpus, name);
                    if (input == null) continue;
                    final JsonObject answer = new JsonParser().parse(Files.readString(file))
                        .getAsJsonObject();
                    System.out.println("[audit] " + String.format("%-36s", name) + " " + audit(input, answer));
                }
            }
        }
    }

    private static SolveInput inputOf(final Path corpus, final String name) throws IOException {
        if (name.startsWith("platline-x")) return null;
        if (name.startsWith("gtnh-flow-")) {
            final var graph = GtnhFlowLoader.load(name.substring("gtnh-flow-".length()))
                .graph();
            return SolveInput.of(graph, graph.getBalanceMode(), null);
        }
        final Path json = corpus.resolve(name + ".json");
        try (InputStream in = Files.newInputStream(json)) {
            return SolveInputJson.read(
                new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject());
        }
    }

    private static String audit(final SolveInput input, final JsonObject answer) {
        if (!answer.has("solution") || answer.get("solution")
            .isJsonNull()) return "no answer";
        final JsonObject s = answer.getAsJsonObject("solution");
        final SolveContext ctx = new SolveContext(
            input,
            BalanceMode.AUTO.heuristics(),
            () -> false,
            Map.of(),
            BalanceMode.AUTO.pins(),
            true,
            Profiler.disabled());
        final ModelData model = ctx.model;
        final double[] extents = new double[model.machines.size()];
        final JsonObject counts = s.getAsJsonObject("machineCounts");
        for (int m = 0; m < extents.length; m++) {
            final ModelData.Machine md = model.machines.get(m);
            final JsonElement c = counts.get(
                md.spec.id()
                    .toString());
            extents[m] = c == null ? 0 : c.getAsDouble() * Numerics.TICKS_PER_SECOND / md.durTicks;
        }
        final double[] flows = new double[model.edges.size()];
        final JsonObject wires = s.getAsJsonObject("edgeFlowsPerSecond");
        for (int e = 0; e < flows.length; e++) {
            final JsonElement f = wires.get(
                model.edges.get(e)
                    .id()
                    .toString());
            flows[e] = f == null ? 0 : f.getAsDouble();
        }
        final double[] externals = new double[model.connectedPorts.size()];
        final Map<Long, Integer> portOf = new HashMap<>();
        for (int p = 0; p < externals.length; p++) {
            final ModelData.ConnectedPort cp = model.connectedPorts.get(p);
            portOf.put(ModelData.portKey(cp.machine(), cp.portIndex(), cp.input()), p);
        }
        for (final String list : new String[] { "gatedSources", "gatedSinks" }) {
            for (final JsonElement e : s.getAsJsonArray(list)) {
                final JsonObject ext = e.getAsJsonObject();
                final JsonObject port = ext.getAsJsonObject("port");
                final Integer m = model.machineIndex.get(
                    UUID.fromString(
                        port.get("nodeId")
                            .getAsString()));
                final Integer p = m == null ? null
                    : portOf.get(
                        ModelData.portKey(
                            m,
                            port.get("portIndex")
                                .getAsInt(),
                            port.get("input")
                                .getAsBoolean()));
                if (p != null) externals[p] = ext.get("ratePerSecond")
                    .getAsDouble();
            }
        }
        final String residual = ctx.validate(extents, flows, externals);
        int pinsBroken = 0;
        double minUnpinned = Double.MAX_VALUE;
        for (int m = 0; m < extents.length; m++) {
            if (!Double.isNaN(ctx.pinnedExtent[m])) {
                if (Math.abs(extents[m] - ctx.pinnedExtent[m]) > 1e-6 * Math.max(1, ctx.pinnedExtent[m])) pinsBroken++;
            } else {
                minUnpinned = Math.min(minUnpinned, extents[m]);
            }
        }
        final Set<Integer> support = ctx.gateSupport(externals);
        int imports = 0;
        for (final int g : support) if (model.gates.get(g)
            .input()) imports++;
        double raw = 0, inside = 0;
        for (final double x : externals) raw += x;
        for (final double f : flows) inside += f;
        return String.format(
            "%s pins %s | leftovers %3d imports %3d | weighted leaving %12.6g raw leaving %12.6g inside %12.6g | slowest unpinned %.3g crafts/s",
            residual == null ? "BALANCES" : "UNBALANCED (" + residual + ")",
            pinsBroken == 0 ? "held" : pinsBroken + " broken",
            support.size(),
            imports,
            ctx.normalizedQuantity(externals),
            raw,
            inside,
            minUnpinned == Double.MAX_VALUE ? 0 : minUnpinned);
    }
}
