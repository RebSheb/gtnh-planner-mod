package com.gtnhplanner.dev;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.balancer.BalanceMode;
import com.gtnhplanner.data.flowchart.balancer.ChoiceKey;
import com.gtnhplanner.data.flowchart.balancer.PortRef;
import com.gtnhplanner.data.flowchart.balancer.SolveInput;

/**
 * A solve's input as JSON, both ways: the harness writes real plans' inputs from the game ({@code solvedump}), and the
 * solver's tests read them back to solve headlessly and check the answers stay the same. Everything the solve reads is
 * kept; a machine's recipe properties (only the per-run totals read them) are not.
 */
public final class SolveInputJson {

    private SolveInputJson() {}

    public static JsonObject write(final SolveInput in) {
        final JsonObject o = new JsonObject();
        o.addProperty(
            "mode",
            in.mode()
                .name());
        if (in.choice() != null) {
            final JsonArray anchors = new JsonArray();
            for (final PortRef r : in.choice()
                .gateAnchors()) anchors.add(portRef(r));
            o.add("choice", anchors);
        }
        final JsonArray machines = new JsonArray();
        for (final SolveInput.Machine m : in.machines()) {
            final JsonObject j = new JsonObject();
            j.addProperty(
                "id",
                m.id()
                    .toString());
            j.addProperty("name", m.name());
            j.addProperty("durationTicks", m.durationTicks());
            j.addProperty("energyPerTick", m.energyPerTick());
            j.addProperty("throughputFactor", m.throughputFactor());
            j.add("inputs", ports(m.inputs()));
            j.add("outputs", ports(m.outputs()));
            j.addProperty("countFixed", m.countFixed());
            j.addProperty("machineCount", m.machineCount());
            j.add("targetOutputRates", rates(m.targetOutputRates()));
            j.add("targetInputRates", rates(m.targetInputRates()));
            machines.add(j);
        }
        o.add("machines", machines);
        final JsonArray edges = new JsonArray();
        for (final SolveInput.EdgeIn e : in.edges()) {
            final JsonObject j = new JsonObject();
            j.addProperty(
                "id",
                e.id()
                    .toString());
            j.addProperty(
                "source",
                e.source()
                    .toString());
            j.addProperty("sourceOutput", e.sourceOutput());
            j.addProperty(
                "target",
                e.target()
                    .toString());
            j.addProperty("targetInput", e.targetInput());
            edges.add(j);
        }
        o.add("edges", edges);
        final JsonArray pools = new JsonArray();
        for (final SolveInput.PoolIn p : in.pools()) {
            final JsonObject j = new JsonObject();
            final JsonArray members = new JsonArray();
            for (final UUID id : p.machines()) members.add(new JsonPrimitive(id.toString()));
            j.add("machines", members);
            j.addProperty("capacity", p.capacity());
            j.addProperty("exact", p.exact());
            pools.add(j);
        }
        o.add("pools", pools);
        final JsonArray drawers = new JsonArray();
        for (final SolveInput.DrawerIn d : in.drawers()) {
            final JsonObject j = new JsonObject();
            j.addProperty(
                "id",
                d.id()
                    .toString());
            j.addProperty("label", d.label());
            j.addProperty(
                "kind",
                d.kind()
                    .name());
            j.addProperty(
                "rule",
                d.rule()
                    .name());
            j.addProperty("rate", d.rate());
            final JsonArray links = new JsonArray();
            for (final Drawer.Link l : d.links()) {
                final JsonObject link = new JsonObject();
                link.addProperty(
                    "node",
                    l.nodeId()
                        .toString());
                link.addProperty("port", l.portIndex());
                links.add(link);
            }
            j.add("links", links);
            drawers.add(j);
        }
        o.add("drawers", drawers);
        final JsonObject pins = new JsonObject();
        for (final Map.Entry<UUID, Double> e : in.extentPins()
            .entrySet())
            pins.addProperty(
                e.getKey()
                    .toString(),
                e.getValue());
        o.add("extentPins", pins);
        final JsonObject spares = new JsonObject();
        for (final Map.Entry<String, Map<String, Double>> e : in.spares()
            .entrySet()) {
            final JsonObject shares = new JsonObject();
            for (final Map.Entry<String, Double> s : e.getValue()
                .entrySet()) shares.addProperty(s.getKey(), s.getValue());
            spares.add(e.getKey(), shares);
        }
        o.add("spares", spares);
        return o;
    }

    public static SolveInput read(final JsonObject o) {
        final BalanceMode mode = BalanceMode.valueOf(
            o.get("mode")
                .getAsString());
        ChoiceKey choice = null;
        if (o.has("choice") && !o.get("choice")
            .isJsonNull()) {
            final List<PortRef> anchors = new ArrayList<>();
            for (final JsonElement a : o.getAsJsonArray("choice")) anchors.add(portRef(a.getAsJsonObject()));
            choice = new ChoiceKey(List.copyOf(anchors));
        }
        final List<SolveInput.Machine> machines = new ArrayList<>();
        for (final JsonElement e : o.getAsJsonArray("machines")) {
            final JsonObject j = e.getAsJsonObject();
            machines.add(
                new SolveInput.Machine(
                    UUID.fromString(
                        j.get("id")
                            .getAsString()),
                    j.get("name")
                        .getAsString(),
                    j.get("durationTicks")
                        .getAsDouble(),
                    j.get("energyPerTick")
                        .getAsDouble(),
                    j.get("throughputFactor")
                        .getAsInt(),
                    ports(j.getAsJsonArray("inputs")),
                    ports(j.getAsJsonArray("outputs")),
                    j.get("countFixed")
                        .getAsBoolean(),
                    j.get("machineCount")
                        .getAsInt(),
                    rates(j.getAsJsonObject("targetOutputRates")),
                    Map.of(),
                    rates(j.getAsJsonObject("targetInputRates"))));
        }
        final List<SolveInput.EdgeIn> edges = new ArrayList<>();
        for (final JsonElement e : o.getAsJsonArray("edges")) {
            final JsonObject j = e.getAsJsonObject();
            edges.add(
                new SolveInput.EdgeIn(
                    UUID.fromString(
                        j.get("id")
                            .getAsString()),
                    UUID.fromString(
                        j.get("source")
                            .getAsString()),
                    j.get("sourceOutput")
                        .getAsInt(),
                    UUID.fromString(
                        j.get("target")
                            .getAsString()),
                    j.get("targetInput")
                        .getAsInt()));
        }
        final List<SolveInput.PoolIn> pools = new ArrayList<>();
        for (final JsonElement e : o.getAsJsonArray("pools")) {
            final JsonObject j = e.getAsJsonObject();
            final List<UUID> members = new ArrayList<>();
            for (final JsonElement m : j.getAsJsonArray("machines")) members.add(UUID.fromString(m.getAsString()));
            pools.add(
                new SolveInput.PoolIn(
                    members,
                    j.get("capacity")
                        .getAsInt(),
                    j.get("exact")
                        .getAsBoolean()));
        }
        final List<SolveInput.DrawerIn> drawers = new ArrayList<>();
        for (final JsonElement e : o.getAsJsonArray("drawers")) {
            final JsonObject j = e.getAsJsonObject();
            final List<Drawer.Link> links = new ArrayList<>();
            for (final JsonElement l : j.getAsJsonArray("links")) {
                final JsonObject link = l.getAsJsonObject();
                links.add(
                    new Drawer.Link(
                        UUID.fromString(
                            link.get("node")
                                .getAsString()),
                        link.get("port")
                            .getAsInt()));
            }
            drawers.add(
                new SolveInput.DrawerIn(
                    UUID.fromString(
                        j.get("id")
                            .getAsString()),
                    j.get("label")
                        .getAsString(),
                    Drawer.Kind.valueOf(
                        j.get("kind")
                            .getAsString()),
                    Drawer.Rule.valueOf(
                        j.get("rule")
                            .getAsString()),
                    j.get("rate")
                        .getAsDouble(),
                    links));
        }
        final Map<UUID, Double> pins = new LinkedHashMap<>();
        for (final Map.Entry<String, JsonElement> e : o.getAsJsonObject("extentPins")
            .entrySet())
            pins.put(
                UUID.fromString(e.getKey()),
                e.getValue()
                    .getAsDouble());
        final Map<String, Map<String, Double>> spares = new LinkedHashMap<>();
        for (final Map.Entry<String, JsonElement> e : o.getAsJsonObject("spares")
            .entrySet()) {
            final Map<String, Double> shares = new LinkedHashMap<>();
            for (final Map.Entry<String, JsonElement> s : e.getValue()
                .getAsJsonObject()
                .entrySet())
                shares.put(
                    s.getKey(),
                    s.getValue()
                        .getAsDouble());
            spares.put(e.getKey(), shares);
        }
        return new SolveInput(mode, choice, machines, edges, pools, drawers, pins, spares);
    }

    private static JsonArray ports(final List<SolveInput.PortIn> ports) {
        final JsonArray out = new JsonArray();
        for (final SolveInput.PortIn p : ports) {
            final JsonObject j = new JsonObject();
            j.addProperty("amount", p.amount());
            j.addProperty("chance", p.chance());
            j.addProperty("multiplier", p.multiplier());
            j.addProperty("resource", p.resource());
            j.addProperty("name", p.name());
            j.addProperty("free", p.free());
            out.add(j);
        }
        return out;
    }

    private static List<SolveInput.PortIn> ports(final JsonArray ports) {
        final List<SolveInput.PortIn> out = new ArrayList<>();
        for (final JsonElement e : ports) {
            final JsonObject j = e.getAsJsonObject();
            out.add(
                new SolveInput.PortIn(
                    j.get("amount")
                        .getAsDouble(),
                    j.get("chance")
                        .getAsFloat(),
                    j.get("multiplier")
                        .getAsDouble(),
                    j.get("resource")
                        .getAsInt(),
                    j.get("name")
                        .getAsString(),
                    j.get("free")
                        .getAsBoolean()));
        }
        return out;
    }

    private static JsonObject rates(final Map<Integer, Double> rates) {
        final JsonObject o = new JsonObject();
        for (final Map.Entry<Integer, Double> e : rates.entrySet()) o.addProperty(
            e.getKey()
                .toString(),
            e.getValue());
        return o;
    }

    private static Map<Integer, Double> rates(final JsonObject o) {
        final Map<Integer, Double> out = new LinkedHashMap<>();
        if (o == null) return out;
        for (final Map.Entry<String, JsonElement> e : o.entrySet()) out.put(
            Integer.parseInt(e.getKey()),
            e.getValue()
                .getAsDouble());
        return out;
    }

    private static JsonObject portRef(final PortRef r) {
        final JsonObject j = new JsonObject();
        j.addProperty(
            "node",
            r.nodeId()
                .toString());
        j.addProperty("port", r.portIndex());
        j.addProperty("input", r.input());
        return j;
    }

    private static PortRef portRef(final JsonObject j) {
        return new PortRef(
            UUID.fromString(
                j.get("node")
                    .getAsString()),
            j.get("port")
                .getAsInt(),
            j.get("input")
                .getAsBoolean());
    }

}
