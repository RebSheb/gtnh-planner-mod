package com.gtnhplanner.cloud.map;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;

/**
 * The plan-wide picks both planners edit, between the board's spelling and the plan's top-level fields: setting pins
 * ({@code settingPins}: which settings a machine's cards show as chips) and leftover splits ({@code spareShares}).
 *
 * <p>
 * Setting pins: the board keys a machine by {@code machine:<its name>} and a power source by {@code power:<source>},
 * its settings as its sheet does ({@code machine:coil}, {@code machine:machine:<id>}, {@code power:<id>}); the website
 * keys a machine by its handler id and a power source the same, its settings by their own ids ({@code heatingCoil},
 * {@code <id>}). A modelled machine's settings (a bee's, a crop's, the TGS's, the vat's) are the website's own keys on
 * both sides: {@code machine:beeSpeedGene} here, {@code beeSpeedGene} there; the housing and the harvester are the
 * website's machine, not a setting. A pin the other side has no word for stays on its own side: a board key the website
 * cannot spell
 * rides in the game's data only, and a website machine with no card on the board is kept on the board as
 * {@code site:<its key>}, which goes back as it came.
 *
 * <p>
 * Leftover splits are keyed by the solver's ports, {@code <card>:<i|o>:<port>}, a leftover by its bearers' keys sorted
 * and joined by {@code |}: the board's cards by their UUIDs, the plan's by their plan ids (a website shared machine's
 * later recipe as {@code <card>#r<n>}).
 */
final class PlanPins {

    /** A website machine the board has no card of: its pins kept on the board under this prefix. */
    static final String SITE = "site:";
    private static final String MACHINE = "machine:", POWER = "power:", COIL = "machine:coil",
        SITE_COIL = "heatingCoil", OPTION = "machine:machine:";

    private PlanPins() {}

    // region Setting pins

    /** The board's setting pins as the website keys them; the ones it has no word for left out. */
    static JsonObject sitePins(final Graph g, final CloudGame game) {
        final Map<String, List<String>> out = new TreeMap<>();
        for (final Map.Entry<String, List<String>> e : g.settingPins.entrySet()) {
            final String key = e.getKey();
            if (key.startsWith(SITE)) {
                out.putIfAbsent(key.substring(SITE.length()), new ArrayList<>(e.getValue()));
                continue;
            }
            final boolean power = key.startsWith(POWER);
            final List<String> controls = new ArrayList<>();
            for (final String c : e.getValue()) {
                final String site = power ? sitePowerControl(c) : siteControl(c);
                if (site != null) controls.add(site);
            }
            for (final String machine : siteMachines(g, game, key)) out.putIfAbsent(machine, controls);
        }
        final JsonObject o = new JsonObject();
        for (final Map.Entry<String, List<String>> e : out.entrySet()) {
            final JsonArray keys = new JsonArray();
            for (final String k : e.getValue()) keys.add(new JsonPrimitive(k));
            o.add(e.getKey(), keys);
        }
        return o;
    }

    /** The website's keys for a board pin key: a power source as itself, a machine by its cards' handler ids. */
    private static Set<String> siteMachines(final Graph g, final CloudGame game, final String key) {
        final Set<String> out = new LinkedHashSet<>();
        if (key.startsWith(POWER)) out.add(key);
        else if (key.startsWith(MACHINE)) {
            final String name = key.substring(MACHINE.length());
            for (final Node n : g.getNodes()) {
                if (n.isPower()) continue;
                final String handler = com.gtnhplanner.library.PlanExport.siteHandler(game, n);
                if (handler != null && name.equals(game.pinName(n))) out.add(handler);
            }
        }
        return out;
    }

    /** A board setting pin as the website spells it; null for one it has no word for (a voltage, a reading). */
    @Nullable
    static String siteControl(final String control) {
        if (COIL.equals(control)) return SITE_COIL;
        if (control.startsWith(OPTION)) return control.substring(OPTION.length());
        if (control.startsWith(MACHINE) && modelKey(control.substring(MACHINE.length())))
            return control.substring(MACHINE.length());
        return null;
    }

    /** A website setting pin as the board spells it. */
    static String boardControl(final String control) {
        if (SITE_COIL.equals(control)) return COIL;
        return modelKey(control) ? MACHINE + control : OPTION + control;
    }

    /**
     * Whether a setting key is a modelled machine's (machines/: {@code Bees}, {@code CropsNh},
     * {@code TreeGrowthSimulator}, {@code BacterialVat}), kept under the website's own key on both sides; the bee's
     * housing and the crop's harvester are the website's machine instead.
     */
    private static boolean modelKey(final String key) {
        return com.gtnhplanner.importer.FfSettings.modelled(key) && !com.gtnhplanner.machines.Bees.HOUSING.equals(key)
            && !com.gtnhplanner.machines.CropsNh.HARVESTER.equals(key);
    }

    @Nullable
    private static String sitePowerControl(final String control) {
        return control.startsWith(POWER) ? control.substring(POWER.length()) : null;
    }

    /**
     * The website's setting pins onto the board ({@code site}), what only the board spells kept from the game's data
     * ({@code had}): a pin key the website cannot spell, and on a machine both have the settings the website has no
     * word for.
     */
    static void readPins(@Nullable final JsonElement site, final Map<String, List<String>> had, final Graph g,
        final CloudGame game) {
        final Map<String, List<String>> pins = new LinkedHashMap<>();
        if (site != null && site.isJsonObject()) for (final Map.Entry<String, JsonElement> e : site.getAsJsonObject()
            .entrySet()) {
                if (!e.getValue()
                    .isJsonArray()) continue;
                final List<String> controls = new ArrayList<>();
                for (final JsonElement c : e.getValue()
                    .getAsJsonArray()) if (c.isJsonPrimitive()) controls.add(c.getAsString());
                final String key = e.getKey();
                final List<String> boardKeys = boardMachines(g, game, key);
                if (boardKeys.isEmpty()) {
                    pins.putIfAbsent(SITE + key, controls);
                    continue;
                }
                final boolean power = key.startsWith(POWER);
                final List<String> board = new ArrayList<>();
                for (final String c : controls) board.add(power ? POWER + c : boardControl(c));
                for (final String b : boardKeys) pins.putIfAbsent(b, new ArrayList<>(board));
            }
        for (final Map.Entry<String, List<String>> e : had.entrySet()) {
            final String key = e.getKey();
            final List<String> now = pins.get(key);
            if (now == null) {
                // A key the website cannot spell is the board's alone; one it can and left out was taken off there.
                if (!key.startsWith(SITE) && siteMachines(g, game, key).isEmpty()) pins.put(key, e.getValue());
                continue;
            }
            final boolean power = key.startsWith(POWER);
            for (final String c : e.getValue())
                if ((power ? sitePowerControl(c) : siteControl(c)) == null && !now.contains(c)) now.add(c);
        }
        g.settingPins.clear();
        g.settingPins.putAll(pins);
    }

    /** The board's keys for a website pin key: a power source as itself, a handler id by its cards' machine names. */
    private static List<String> boardMachines(final Graph g, final CloudGame game, final String key) {
        final Set<String> out = new LinkedHashSet<>();
        if (key.startsWith(POWER)) out.add(key);
        else for (final Node n : g.getNodes()) {
            if (n.isPower() || !key.equals(com.gtnhplanner.library.PlanExport.siteHandler(game, n))) continue;
            final String name = game.pinName(n);
            if (name != null) out.add(MACHINE + name);
        }
        return new ArrayList<>(out);
    }

    // endregion

    // region Leftover splits

    /** The board's leftover splits with its cards named as the plan names them. */
    static JsonObject siteShares(final Graph g, final Basis names) {
        return shares(g.spareShares, card -> planCard(g, names, card));
    }

    /** The website's leftover splits onto the board, its cards named by the board's ids. */
    static void readShares(@Nullable final JsonElement site, final Graph g) {
        g.spareShares.clear();
        if (site == null || !site.isJsonObject()) return;
        final Map<String, Map<String, Double>> read = new TreeMap<>();
        for (final Map.Entry<String, JsonElement> e : site.getAsJsonObject()
            .entrySet()) {
            if (!e.getValue()
                .isJsonObject()) continue;
            final Map<String, Double> shares = new TreeMap<>();
            for (final Map.Entry<String, JsonElement> s : e.getValue()
                .getAsJsonObject()
                .entrySet()) {
                if (s.getValue()
                    .isJsonPrimitive()
                    && s.getValue()
                        .getAsJsonPrimitive()
                        .isNumber())
                    shares.put(
                        s.getKey(),
                        s.getValue()
                            .getAsDouble());
            }
            read.put(e.getKey(), shares);
        }
        final JsonObject board = shares(read, PlanPins::boardCard);
        for (final Map.Entry<String, JsonElement> e : board.entrySet()) {
            final Map<String, Double> shares = new TreeMap<>();
            for (final Map.Entry<String, JsonElement> s : e.getValue()
                .getAsJsonObject()
                .entrySet())
                shares.put(
                    s.getKey(),
                    s.getValue()
                        .getAsDouble());
            g.spareShares.put(e.getKey(), shares);
        }
    }

    /** Splits with every bearer key's card renamed, each leftover's key made again from its renamed bearers. */
    private static JsonObject shares(final Map<String, Map<String, Double>> from, final Function<String, String> card) {
        final Map<String, Map<String, Double>> out = new TreeMap<>();
        for (final Map.Entry<String, Map<String, Double>> e : from.entrySet()) {
            final List<String> bearers = new ArrayList<>();
            for (final String b : e.getKey()
                .split("\\|", -1)) bearers.add(bearer(b, card));
            bearers.sort(String::compareTo);
            final Map<String, Double> shares = new TreeMap<>();
            for (final Map.Entry<String, Double> s : e.getValue()
                .entrySet()) shares.put(bearer(s.getKey(), card), s.getValue());
            out.put(String.join("|", bearers), shares);
        }
        final JsonObject o = new JsonObject();
        for (final Map.Entry<String, Map<String, Double>> e : out.entrySet()) {
            final JsonObject shares = new JsonObject();
            for (final Map.Entry<String, Double> s : e.getValue()
                .entrySet()) shares.addProperty(s.getKey(), s.getValue());
            o.add(e.getKey(), shares);
        }
        return o;
    }

    /** A bearer key {@code <card>:<i|o>:<port>} with its card renamed; as it is when it is no such key. */
    private static String bearer(final String key, final Function<String, String> card) {
        final int side = Math.max(key.lastIndexOf(":i:"), key.lastIndexOf(":o:"));
        return side <= 0 ? key : card.apply(key.substring(0, side)) + key.substring(side);
    }

    /** A board card's id as the plan names it: its plan id, a website shared machine's later recipe {@code #r<n>}. */
    private static String planCard(final Graph g, final Basis names, final String boardId) {
        final UUID u;
        try {
            u = UUID.fromString(boardId);
        } catch (final IllegalArgumentException e) {
            return boardId;
        }
        if (!g.nodes.containsKey(u)) return boardId;
        final String id = names.id("node", u);
        if (!names.isCarried("node:" + u)) return id;
        for (int i = 1; i < 256; i++) if (PlanIds.part(id, "r" + i)
            .equals(u)) return id + "#r" + i;
        return id;
    }

    /** A plan's card id as the board names it: the UUID it was given, a later recipe's by its section. */
    private static String boardCard(final String planId) {
        final int at = planId.lastIndexOf("#r");
        if (at > 0) {
            try {
                final int section = Integer.parseInt(planId.substring(at + 2));
                if (section > 0) return PlanIds.part(planId.substring(0, at), "r" + section)
                    .toString();
            } catch (final NumberFormatException ignored) {}
        }
        return PlanIds.uuid(planId)
            .toString();
    }

    // endregion
}
