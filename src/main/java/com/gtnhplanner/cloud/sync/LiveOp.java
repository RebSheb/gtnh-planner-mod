package com.gtnhplanner.cloud.sync;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * One op of an edit to a shared plan, as the wire carries it:
 *
 * <pre>
 * { o:"put",   c, id, v }       insert or replace the whole thing
 * { o:"patch", c, id, f, d? }   set top-level fields f, then delete fields d; nothing when the thing is gone
 * { o:"del",   c, id }          remove the thing; nothing when it is already gone
 * { o:"meta",  f, d? }          set / delete top-level plan fields; collection names are ignored
 * </pre>
 *
 * {@code c} is one of {@link LiveOps#COLLECTIONS}. An op holds its JSON as given; the engine copies what it keeps.
 *
 * @param c a collection; null on a meta op
 * @param v the whole thing, on a put
 * @param f fields to set, on a patch or meta (always there, maybe empty)
 * @param d fields to delete, on a patch or meta; null when there are none
 */
public record LiveOp(Kind o, @Nullable String c, @Nullable String id, @Nullable JsonObject v, @Nullable JsonObject f,
    @Nullable List<String> d) {

    public enum Kind {

        PUT("put"),
        PATCH("patch"),
        DEL("del"),
        META("meta");

        public final String wire;

        Kind(final String wire) {
            this.wire = wire;
        }

        @Nullable
        public static Kind of(final String wire) {
            for (final Kind k : values()) if (k.wire.equals(wire)) return k;
            return null;
        }
    }

    public static LiveOp put(final String c, final String id, final JsonObject v) {
        return new LiveOp(Kind.PUT, c, id, v, null, null);
    }

    public static LiveOp patch(final String c, final String id, final JsonObject f, @Nullable final List<String> d) {
        return new LiveOp(Kind.PATCH, c, id, null, f, d == null || d.isEmpty() ? null : d);
    }

    public static LiveOp del(final String c, final String id) {
        return new LiveOp(Kind.DEL, c, id, null, null, null);
    }

    public static LiveOp meta(final JsonObject f, @Nullable final List<String> d) {
        return new LiveOp(Kind.META, null, null, null, f, d == null || d.isEmpty() ? null : d);
    }

    /** The op as the wire spells it, keys in ops.ts's order. */
    public JsonObject toJson() {
        final JsonObject o = new JsonObject();
        o.addProperty("o", this.o.wire);
        if (this.o != Kind.META) {
            o.addProperty("c", c);
            o.addProperty("id", id);
        }
        if (this.o == Kind.PUT) o.add("v", v);
        if (this.o == Kind.PATCH || this.o == Kind.META) {
            o.add("f", f == null ? new JsonObject() : f);
            if (d != null && !d.isEmpty()) {
                final JsonArray names = new JsonArray();
                for (final String name : d) names.add(new JsonPrimitive(name));
                o.add("d", names);
            }
        }
        return o;
    }

    public static JsonArray toJson(final List<LiveOp> ops) {
        final JsonArray out = new JsonArray();
        for (final LiveOp op : ops) out.add(op.toJson());
        return out;
    }

    /**
     * An op as the JSON spells it, unchecked: a field the op may not touch is kept, and applying it ignores it as the
     * website's engine does. Null for an unknown kind, or a thing op without a string collection and id. Batches from
     * a socket are read with {@link LiveOps#read} instead, which refuses bad shapes.
     */
    @Nullable
    public static LiveOp fromJson(final JsonObject o) {
        final Kind kind = o.get("o") == null || !o.get("o")
            .isJsonPrimitive() ? null
                : Kind.of(
                    o.get("o")
                        .getAsString());
        if (kind == null) return null;
        final JsonObject f = o.get("f") != null && o.get("f")
            .isJsonObject() ? o.getAsJsonObject("f") : new JsonObject();
        final List<String> d = new ArrayList<>();
        if (o.get("d") != null && o.get("d")
            .isJsonArray()) {
            for (final JsonElement name : o.getAsJsonArray("d")) if (name.isJsonPrimitive()) d.add(name.getAsString());
        }
        if (kind == Kind.META) return meta(f, d);
        if (o.get("c") == null || o.get("id") == null
            || !o.get("c")
                .isJsonPrimitive()
            || !o.get("id")
                .isJsonPrimitive())
            return null;
        final String c = o.get("c")
            .getAsString(),
            id = o.get("id")
                .getAsString();
        return switch (kind) {
            case PUT -> o.get("v") != null && o.get("v")
                .isJsonObject() ? put(c, id, o.getAsJsonObject("v")) : null;
            case PATCH -> patch(c, id, f, d);
            default -> del(c, id);
        };
    }

    /** {@link #fromJson} over a list; null when any op does not read. */
    @Nullable
    public static List<LiveOp> fromJson(final JsonArray ops) {
        final List<LiveOp> out = new ArrayList<>();
        for (final JsonElement e : ops) {
            final LiveOp op = e.isJsonObject() ? fromJson(e.getAsJsonObject()) : null;
            if (op == null) return null;
            out.add(op);
        }
        return out;
    }

    /** The names to delete, never null. */
    public List<String> deletes() {
        return d == null ? List.of() : d;
    }

    @Override
    public String toString() {
        return toJson().toString();
    }

}
