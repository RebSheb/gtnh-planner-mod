package com.gtnhplanner.data.flowchart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Order keys: the website's recorded answers (shared vectors), and keys that always land between their neighbours. */
class OrderKeysTest {

    private static JsonObject vectors() throws Exception {
        try (Reader r = new InputStreamReader(
            OrderKeysTest.class.getResourceAsStream("/tasks/order-key-vectors.json"),
            StandardCharsets.UTF_8)) {
            return new JsonParser().parse(r)
                .getAsJsonObject();
        }
    }

    private static String str(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    @Test
    void everyPairGivesTheWebsitesKey() throws Exception {
        final JsonArray rows = vectors().getAsJsonArray("between");
        assertTrue(rows.size() > 50);
        for (final JsonElement e : rows) {
            final JsonObject row = e.getAsJsonObject();
            assertEquals(
                str(row, "expected"),
                OrderKeys.between(str(row, "before"), str(row, "after")),
                row.toString());
        }
    }

    @Test
    void everyRunGivesTheWebsitesKeys() throws Exception {
        for (final JsonElement e : vectors().getAsJsonArray("chains")) {
            final JsonObject c = e.getAsJsonObject();
            final String mode = str(c, "mode");
            String low = str(c, "start");
            String high = "between".equals(mode) ? str(c, "end") : null;
            final List<String> got = new ArrayList<>();
            for (int i = 0; i < c.get("n")
                .getAsInt(); i++) {
                final String key;
                if (mode.equals("after")) {
                    key = OrderKeys.between(low, null);
                    low = key;
                } else if (mode.equals("before")) {
                    key = OrderKeys.between(null, high);
                    high = key;
                } else {
                    key = OrderKeys.between(low, high);
                    high = key;
                }
                got.add(key);
            }
            final List<String> want = new ArrayList<>();
            for (final JsonElement k : c.getAsJsonArray("expected")) want.add(k.getAsString());
            assertEquals(want, got, c.toString());
        }
    }

    @Test
    void sortingIsByKeyThenId() throws Exception {
        for (final JsonElement e : vectors().getAsJsonArray("sorting")) {
            final JsonObject set = e.getAsJsonObject();
            final List<JsonObject> items = new ArrayList<>();
            for (final JsonElement i : set.getAsJsonArray("items")) items.add(i.getAsJsonObject());
            items.sort(OrderKeys.byOrder(o -> str(o, "order"), o -> str(o, "id")));
            final List<String> got = new ArrayList<>(), want = new ArrayList<>();
            for (final JsonObject o : items) got.add(str(o, "id"));
            for (final JsonElement k : set.getAsJsonArray("expected")) want.add(k.getAsString());
            assertEquals(want, got, set.toString());
        }
    }

    @Test
    void gameWrittenKeysStillAcceptInsertions() {
        final String between = OrderKeys.between("a0", "a1");
        assertTrue("a0".compareTo(between) < 0 && between.compareTo("a1") < 0, between);
        assertTrue(
            OrderKeys.between("a0", null)
                .compareTo("a0") > 0);
        assertTrue(
            OrderKeys.between(null, "a0")
                .compareTo("a0") < 0);
        assertEquals(List.of("V", "k"), OrderKeys.sequence(2));
    }

    @Test
    void aKeyAlwaysLandsBetween() {
        final Random random = new Random(7);
        final List<String> keys = new ArrayList<>(List.of(OrderKeys.FIRST));
        for (int n = 0; n < 2000; n++) {
            final int at = random.nextInt(keys.size() + 1);
            final String a = at == 0 ? null : keys.get(at - 1), b = at == keys.size() ? null : keys.get(at);
            final String k = OrderKeys.between(a, b);
            if (a != null) assertTrue(a.compareTo(k) < 0, a + " < " + k);
            if (b != null) assertTrue(k.compareTo(b) < 0, k + " < " + b);
            keys.add(at, k);
        }
    }
}
