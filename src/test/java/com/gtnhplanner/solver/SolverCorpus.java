package com.gtnhplanner.solver;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.balancer.Balancer;
import com.gtnhplanner.data.flowchart.balancer.Note;
import com.gtnhplanner.data.flowchart.balancer.SolutionView;
import com.gtnhplanner.data.flowchart.balancer.SolveInput;
import com.gtnhplanner.dev.SolveInputJson;
import com.gtnhplanner.harness.GtnhFlowLoader;

/**
 * The solver's corpus: real plans' solve inputs written from the game ({@code solvedump}), the gtnh-flow charts, and
 * big plans made by tiling a real one; each solve's whole answer as a JSON digest (everything the board reads), and a
 * comparison of two digests that allows floating-point noise and nothing else.
 */
final class SolverCorpus {

    private SolverCorpus() {}

    /** One case: a name and its input. */
    record Case(String name, SolveInput input) {}

    /** The plans written from the game, under {@code /solver-corpus/}. */
    static final String[] PLANS = { "alumina-line-shared-reactor", "benzene", "full-platline", "glass", "hv-diesel",
        "mega-iv-nitrobenzene-power-plant", "mv", "oxygen-silicon-loop", "pa-cell-loop", "platline", "single-card-v2",
        "sulfuric-acid", "titanium-line-chembath", "titanium-line-tour", "buffer-drawers" };

    /** Every case: the plans, the gtnh-flow charts, and Platline tiled {@code tiles} times for each listed. */
    static List<Case> cases(final int... tiles) {
        final List<Case> out = new ArrayList<>();
        for (final String plan : PLANS) out.add(new Case(plan, plan(plan)));
        for (final String chart : GtnhFlowLoader.CORPUS) out.add(new Case("gtnh-flow-" + chart, chart(chart)));
        final SolveInput platline = plan("platline");
        for (final int k : tiles) out.add(new Case("platline-x" + k, tile(platline, k)));
        return out;
    }

    /**
     * A gtnh-flow chart, in its own mode and with no stored choice:
     * {@link SolveInput#of(com.gtnhplanner.data.flowchart.Graph)}
     * would read the choice from the open plan, which other tests leave set.
     */
    /**
     * {@code k} copies of a plan made one island: a shared machine spanning one machine of every copy, capped far above
     * anything they run, so the answer is the copies' and only the model is one. What islands cannot split.
     */
    static SolveInput joined(final SolveInput in, final int k) {
        final SolveInput tiled = tile(in, k);
        final List<UUID> members = new ArrayList<>();
        final int per = in.machines()
            .size();
        for (int c = 0; c < k; c++) members.add(
            tiled.machines()
                .get(c * per)
                .id());
        final List<SolveInput.PoolIn> pools = new ArrayList<>(tiled.pools());
        pools.add(new SolveInput.PoolIn(members, 1_000_000));
        return new SolveInput(
            tiled.mode(),
            tiled.choice(),
            tiled.machines(),
            tiled.edges(),
            pools,
            tiled.drawers(),
            tiled.extentPins(),
            tiled.spares());
    }

    static SolveInput chart(final String name) {
        final com.gtnhplanner.data.flowchart.Graph graph = GtnhFlowLoader.load(name)
            .graph();
        return SolveInput.of(graph, graph.getBalanceMode(), null);
    }

    static SolveInput plan(final String name) {
        try (InputStream in = SolverCorpus.class.getResourceAsStream("/solver-corpus/" + name + ".json")) {
            if (in == null) throw new IllegalArgumentException("no corpus plan " + name);
            return SolveInputJson.read(
                new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject());
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The input {@code k} times over, each copy its own machines, wires and drawers: the dev harness's stress plans.
     */
    static SolveInput tile(final SolveInput in, final int k) {
        final List<SolveInput.Machine> machines = new ArrayList<>();
        final List<SolveInput.EdgeIn> edges = new ArrayList<>();
        final List<SolveInput.PoolIn> pools = new ArrayList<>();
        final List<SolveInput.DrawerIn> drawers = new ArrayList<>();
        final Map<UUID, Double> pins = new LinkedHashMap<>();
        final Map<String, Map<String, Double>> spares = new LinkedHashMap<>();
        for (int c = 0; c < k; c++) {
            final int copy = c;
            final java.util.function.UnaryOperator<UUID> id = u -> copy == 0 ? u
                : UUID.nameUUIDFromBytes((copy + ":" + u).getBytes(StandardCharsets.UTF_8));
            for (final SolveInput.Machine m : in.machines()) machines.add(
                new SolveInput.Machine(
                    id.apply(m.id()),
                    m.name(),
                    m.durationTicks(),
                    m.energyPerTick(),
                    m.throughputFactor(),
                    m.inputs(),
                    m.outputs(),
                    m.countFixed(),
                    m.machineCount(),
                    m.targetOutputRates(),
                    m.properties(),
                    m.targetInputRates()));
            for (final SolveInput.EdgeIn e : in.edges()) edges.add(
                new SolveInput.EdgeIn(
                    id.apply(e.id()),
                    id.apply(e.source()),
                    e.sourceOutput(),
                    id.apply(e.target()),
                    e.targetInput()));
            for (final SolveInput.PoolIn p : in.pools()) pools.add(
                new SolveInput.PoolIn(
                    p.machines()
                        .stream()
                        .map(id)
                        .toList(),
                    p.capacity(),
                    p.exact()));
            for (final SolveInput.DrawerIn d : in.drawers()) drawers.add(
                new SolveInput.DrawerIn(
                    id.apply(d.id()),
                    d.label(),
                    d.kind(),
                    d.rule(),
                    d.rate(),
                    d.links()
                        .stream()
                        .map(l -> new Drawer.Link(id.apply(l.nodeId()), l.portIndex()))
                        .toList(),
                    d.takers()
                        .stream()
                        .map(l -> new Drawer.Link(id.apply(l.nodeId()), l.portIndex()))
                        .toList()));
            for (final Map.Entry<UUID, Double> e : in.extentPins()
                .entrySet()) pins.put(id.apply(e.getKey()), e.getValue());
            for (final Map.Entry<String, Map<String, Double>> e : in.spares()
                .entrySet()) {
                final Map<String, Double> shares = new LinkedHashMap<>();
                for (final Map.Entry<String, Double> s : e.getValue()
                    .entrySet()) shares.put(remap(s.getKey(), id), s.getValue());
                spares.put(remap(e.getKey(), id), shares);
            }
        }
        return new SolveInput(in.mode(), null, machines, edges, pools, drawers, pins, spares);
    }

    /** A spare key ("node:i:port") with its node renamed for a copy; any other key as it is. */
    private static String remap(final String key, final java.util.function.UnaryOperator<UUID> id) {
        final int at = key.indexOf(':');
        if (at != 36) return key;
        try {
            return id.apply(UUID.fromString(key.substring(0, at))) + key.substring(at);
        } catch (final IllegalArgumentException e) {
            return key;
        }
    }

    /** The whole answer the board reads, as JSON: the solve's own timing left out. */
    static JsonElement digest(final Balancer.Answer answer) {
        return of(answer, 0);
    }

    private static JsonElement of(final Object o, final int depth) {
        if (depth > 40) throw new IllegalStateException("digest too deep: " + o.getClass());
        if (o == null) return JsonNull.INSTANCE;
        if (o instanceof final Number n) return new JsonPrimitive(n.doubleValue());
        if (o instanceof final Boolean b) return new JsonPrimitive(b);
        if (o instanceof final CharSequence s) return new JsonPrimitive(s.toString());
        if (o instanceof final Enum<?> e) return new JsonPrimitive(e.name());
        if (o instanceof UUID) return new JsonPrimitive(o.toString());
        if (o instanceof final Drawer.Link l) {
            final JsonObject j = new JsonObject();
            j.addProperty("node", l.nodeId().toString());
            j.addProperty("port", l.portIndex());
            return j;
        }
        if (o instanceof final Note n) {
            final JsonObject j = new JsonObject();
            j.add("message", of(n.message(), depth + 1));
            j.add("args", of(n.args(), depth + 1));
            return j;
        }
        if (o instanceof final Map<?, ?> m) {
            final TreeMap<String, Object> sorted = new TreeMap<>();
            for (final Map.Entry<?, ?> e : m.entrySet()) sorted.put(String.valueOf(e.getKey()), e.getValue());
            final JsonObject j = new JsonObject();
            for (final Map.Entry<String, Object> e : sorted.entrySet()) j.add(e.getKey(), of(e.getValue(), depth + 1));
            return j;
        }
        if (o instanceof final Set<?> s) {
            final List<JsonElement> items = new ArrayList<>();
            for (final Object x : s) items.add(of(x, depth + 1));
            items.sort((a, b) -> a.toString().compareTo(b.toString()));
            final JsonArray j = new JsonArray();
            for (final JsonElement e : items) j.add(e);
            return j;
        }
        if (o instanceof final Collection<?> c) {
            final JsonArray j = new JsonArray();
            for (final Object x : c) j.add(of(x, depth + 1));
            return j;
        }
        if (o.getClass().isArray()) {
            final JsonArray j = new JsonArray();
            for (int i = 0; i < java.lang.reflect.Array.getLength(o); i++)
                j.add(of(java.lang.reflect.Array.get(o, i), depth + 1));
            return j;
        }
        if (o.getClass().isRecord()) {
            final JsonObject j = new JsonObject();
            for (final RecordComponent rc : o.getClass().getRecordComponents()) {
                try {
                    j.add(rc.getName(), of(rc.getAccessor().invoke(o), depth + 1));
                } catch (final ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
            return j;
        }
        if (o instanceof final SolutionView v) {
            final JsonObject j = new JsonObject();
            for (final Field f : SolutionView.class.getFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getName().equals("wallMillis")) continue;
                try {
                    j.add(f.getName(), of(f.get(v), depth + 1));
                } catch (final IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
            j.add("spares", of(v.spares(), depth + 1));
            return j;
        }
        // Anything else of ours (a record the build may have made a plain class): its fields.
        if (o.getClass().getName().startsWith("com.gtnhplanner.")) {
            final JsonObject j = new JsonObject();
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass())
                for (final Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
                    try {
                        f.setAccessible(true);
                        j.add(f.getName(), of(f.get(o), depth + 1));
                    } catch (final ReflectiveOperationException | RuntimeException e) {
                        throw new IllegalStateException("cannot read " + f, e);
                    }
                }
            return j;
        }
        throw new IllegalStateException("no digest for " + o.getClass());
    }

    /**
     * Where two digests differ beyond floating-point noise, each as "path: expected / actual"; empty when they agree.
     */
    static List<String> differences(final JsonElement expected, final JsonElement actual) {
        final List<String> out = new ArrayList<>();
        compare("", expected, actual, out);
        return out;
    }

    private static String abbreviate(final JsonElement e) {
        final String s = e.toString();
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }

    private static void compare(final String path, final JsonElement a, final JsonElement b, final List<String> out) {
        if (out.size() > 20) return;
        if (a.isJsonObject() && b.isJsonObject()) {
            final JsonObject x = a.getAsJsonObject(), y = b.getAsJsonObject();
            final java.util.TreeSet<String> keys = new java.util.TreeSet<>();
            for (final Map.Entry<String, JsonElement> e : x.entrySet()) keys.add(e.getKey());
            for (final Map.Entry<String, JsonElement> e : y.entrySet()) keys.add(e.getKey());
            for (final String k : keys) {
                if (!x.has(k) || !y.has(k)) out.add(path + "/" + k + ": " + x.get(k) + " / " + y.get(k));
                else compare(path + "/" + k, x.get(k), y.get(k), out);
            }
            return;
        }
        if (a.isJsonArray() && b.isJsonArray()) {
            final JsonArray x = a.getAsJsonArray(), y = b.getAsJsonArray();
            if (x.size() != y.size()) {
                out.add(path + ": " + x.size() + " items / " + y.size() + ": " + abbreviate(x) + " / " + abbreviate(y));
                return;
            }
            for (int i = 0; i < x.size(); i++) compare(path + "[" + i + "]", x.get(i), y.get(i), out);
            return;
        }
        if (a.isJsonPrimitive() && b.isJsonPrimitive()
            && a.getAsJsonPrimitive()
                .isNumber()
            && b.getAsJsonPrimitive()
                .isNumber()) {
            final double p = a.getAsDouble(), q = b.getAsDouble();
            if (p == q || Math.abs(p - q) <= 1e-6 * Math.max(1, Math.max(Math.abs(p), Math.abs(q)))) return;
            out.add(path + ": " + p + " / " + q);
            return;
        }
        if (!a.equals(b)) out.add(path + ": " + a + " / " + b);
    }
}
