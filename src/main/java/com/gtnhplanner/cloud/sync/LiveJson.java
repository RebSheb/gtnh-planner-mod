package com.gtnhplanner.cloud.sync;

import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * JSON as the live engine reads it (ops.ts's jsonEqual and cloneJson): equal by value, numbers by their value whatever
 * their spelling ({@code 1} equals {@code 1.0}), objects by their keys in any order, arrays in order. A plan is a
 * value: nothing in the engine changes one handed to it, and the plans it returns share what an edit did not touch, so
 * callers must not change them either. Gson 2.2.4's API only, which is what the game runs.
 */
public final class LiveJson {

    private LiveJson() {}

    /**
     * JSON equality. A key that is missing differs from one holding {@code null}, which is an ordinary value; Java null
     * stands for a missing value.
     */
    public static boolean equal(@Nullable final JsonElement a, @Nullable final JsonElement b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.isJsonNull() || b.isJsonNull()) return a.isJsonNull() && b.isJsonNull();
        if (a.isJsonPrimitive() || b.isJsonPrimitive()) {
            if (!a.isJsonPrimitive() || !b.isJsonPrimitive()) return false;
            final JsonPrimitive p = a.getAsJsonPrimitive(), q = b.getAsJsonPrimitive();
            if (p.isNumber() || q.isNumber()) return p.isNumber() && q.isNumber() && p.getAsDouble() == q.getAsDouble();
            if (p.isBoolean() || q.isBoolean())
                return p.isBoolean() && q.isBoolean() && p.getAsBoolean() == q.getAsBoolean();
            return p.getAsString()
                .equals(q.getAsString());
        }
        if (a.isJsonArray() || b.isJsonArray()) {
            if (!a.isJsonArray() || !b.isJsonArray()) return false;
            final JsonArray x = a.getAsJsonArray(), y = b.getAsJsonArray();
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!equal(x.get(i), y.get(i))) return false;
            return true;
        }
        final JsonObject x = a.getAsJsonObject(), y = b.getAsJsonObject();
        if (x.entrySet()
            .size()
            != y.entrySet()
                .size())
            return false;
        for (final Map.Entry<String, JsonElement> e : x.entrySet()) {
            if (!equal(e.getValue(), y.get(e.getKey()))) return false;
        }
        return true;
    }

    /** A deep copy: new objects and arrays all the way down (primitives and null are shared, being immutable). */
    @SuppressWarnings("unchecked")
    public static <T extends JsonElement> T copy(final T e) {
        if (e.isJsonObject()) {
            final JsonObject out = new JsonObject();
            for (final Map.Entry<String, JsonElement> m : e.getAsJsonObject()
                .entrySet()) out.add(m.getKey(), copy(m.getValue()));
            return (T) out;
        }
        if (e.isJsonArray()) {
            final JsonArray out = new JsonArray();
            for (final JsonElement x : e.getAsJsonArray()) out.add(copy(x));
            return (T) out;
        }
        return e;
    }

    /** A new object holding the same values: one level copied, the values shared. */
    public static JsonObject shallow(final JsonObject o) {
        final JsonObject out = new JsonObject();
        for (final Map.Entry<String, JsonElement> m : o.entrySet()) out.add(m.getKey(), m.getValue());
        return out;
    }

    /**
     * The thing's {@code id} when it is an object with a string id; null otherwise (such things are never addressed).
     */
    @Nullable
    public static String idOf(@Nullable final JsonElement thing) {
        if (thing == null || !thing.isJsonObject()) return null;
        final JsonElement id = thing.getAsJsonObject()
            .get("id");
        return id != null && id.isJsonPrimitive()
            && id.getAsJsonPrimitive()
                .isString() ? id.getAsString() : null;
    }
}
