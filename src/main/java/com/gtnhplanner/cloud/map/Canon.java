package com.gtnhplanner.cloud.map;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * JSON in one spelling, whatever wrote it: keys sorted, numbers by their value ({@code 1}, {@code 1.0} and
 * {@code 1e0} all {@code 1}), the rest as JSON writes it. Two values the live engine counts equal
 * ({@link com.gtnhplanner.cloud.sync.LiveJson#equal}) spell the same here, so the spelling can be compared and hashed:
 * a thing's {@code sum} survives the website reading and writing it again.
 */
public final class Canon {

    private Canon() {}

    public static String of(@Nullable final JsonElement e) {
        final StringBuilder out = new StringBuilder();
        write(e, out);
        return out.toString();
    }

    private static void write(@Nullable final JsonElement e, final StringBuilder out) {
        if (e == null || e.isJsonNull()) {
            out.append("null");
            return;
        }
        if (e.isJsonPrimitive()) {
            final JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isNumber()) out.append(number(p.getAsDouble()));
            else if (p.isBoolean()) out.append(p.getAsBoolean());
            else out.append(p);
            return;
        }
        if (e.isJsonArray()) {
            out.append('[');
            boolean first = true;
            for (final JsonElement x : e.getAsJsonArray()) {
                if (!first) out.append(',');
                first = false;
                write(x, out);
            }
            out.append(']');
            return;
        }
        final JsonObject o = e.getAsJsonObject();
        final List<String> keys = new ArrayList<>();
        for (final Map.Entry<String, JsonElement> m : o.entrySet()) keys.add(m.getKey());
        Collections.sort(keys);
        out.append('{');
        boolean first = true;
        for (final String key : keys) {
            if (!first) out.append(',');
            first = false;
            out.append(new JsonPrimitive(key))
                .append(':');
            write(o.get(key), out);
        }
        out.append('}');
    }

    /** A number by its value: whole numbers without a point, the rest as Java spells the double. */
    private static String number(final double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return Double.toString(d);
    }

    /** A short hash of the canonical spelling: 16 hex digits of its SHA-256. */
    public static String sum(final JsonElement e) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(of(e).getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", digest[i] & 0xff));
            return hex.toString();
        } catch (final NoSuchAlgorithmException x) {
            throw new IllegalStateException(x);
        }
    }
}
