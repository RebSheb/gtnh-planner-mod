package com.gtnhplanner.cloud.sync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * The live edit engine, rule for rule the website's {@code src/lib/live/ops.ts}: both engines run the same vectors.
 *
 * <ul>
 * <li>A plan is a JSON object. Its arrays named in {@link #COLLECTIONS} are collections of things with a string
 * {@code id}; every other top-level field is meta. An absent collection (or one that is not an array) equals an empty
 * one.</li>
 * <li>Ids are unique inside a collection: lookups take the first thing with the id, and things without a string id
 * are carried along untouched and never addressed.</li>
 * <li>Collection order carries no meaning: a new thing is appended, a replaced or patched thing keeps its place, a
 * deleted one leaves its place.</li>
 * <li>A field set to its absent state is a delete ({@code d}), never {@code null}; {@code null} is an ordinary
 * value.</li>
 * </ul>
 */
public final class LiveOps {

    /** The wire protocol's version; changing {@link #COLLECTIONS} changes it. */
    public static final int PROTOCOL_VERSION = 1;

    /** The plan's collections, in the order a diff walks them (ops.ts's LIVE_COLLECTIONS). */
    public static final List<String> COLLECTIONS = List.of(
        "recipes",
        "nodes",
        "storages",
        "edges",
        "annotations",
        "pockets",
        "fuelProfiles",
        "productionGroups",
        "tasks",
        "taskItems",
        "taskComments");

    private static final Set<String> COLLECTION_SET = Set.copyOf(COLLECTIONS);

    /** The longest id and field name an op may carry (ops.ts's limits). */
    public static final int ID_MAX_LENGTH = 200, FIELD_NAME_MAX_LENGTH = 100;

    private LiveOps() {}

    public static boolean isCollection(@Nullable final String name) {
        return name != null && COLLECTION_SET.contains(name);
    }

    private static boolean isThingKey(final String key) {
        return !"id".equals(key);
    }

    private static boolean isMetaKey(final String key) {
        return !COLLECTION_SET.contains(key);
    }

    /** A collection's things, empty when it is absent or not an array. */
    public static List<JsonElement> collection(final JsonObject plan, final String c) {
        final JsonElement value = plan.get(c);
        if (value == null || !value.isJsonArray()) return List.of();
        final List<JsonElement> out = new ArrayList<>();
        for (final JsonElement e : value.getAsJsonArray()) out.add(e);
        return out;
    }

    // region Equality

    /**
     * Whether two plans are the same: meta fields JSON-equal, and each collection holding the same things by id in any
     * order (things without an id in the same order). How the test vectors compare plans.
     */
    public static boolean plansEqual(final JsonObject a, final JsonObject b) {
        final Set<String> metaKeys = new LinkedHashSet<>();
        for (final Map.Entry<String, JsonElement> e : a.entrySet()) metaKeys.add(e.getKey());
        for (final Map.Entry<String, JsonElement> e : b.entrySet()) metaKeys.add(e.getKey());
        for (final String key : metaKeys) {
            if (COLLECTION_SET.contains(key)) continue;
            if (!LiveJson.equal(a.get(key), b.get(key))) return false;
        }
        for (final String c : COLLECTIONS) {
            final List<JsonElement> left = collection(a, c), right = collection(b, c);
            if (left.size() != right.size()) return false;
            final Map<String, JsonElement> byId = new HashMap<>();
            final List<JsonElement> loose = new ArrayList<>();
            for (final JsonElement thing : right) {
                final String id = LiveJson.idOf(thing);
                if (id == null) loose.add(thing);
                else byId.put(id, thing);
            }
            int looseAt = 0;
            for (final JsonElement thing : left) {
                final String id = LiveJson.idOf(thing);
                if (id == null) {
                    if (looseAt >= loose.size() || !LiveJson.equal(thing, loose.get(looseAt))) return false;
                    looseAt++;
                } else if (!byId.containsKey(id) || !LiveJson.equal(thing, byId.get(id))) return false;
            }
        }
        return true;
    }

    // endregion

    // region Applying

    /** A slot a deleted thing leaves until {@link Working#finish}, so deletes never shift the index. */
    private static final JsonElement GONE = new JsonObject();

    /**
     * A plan under edit: one shallow copy of the plan and of each collection a batch touches, with an id index per
     * touched collection, so a batch costs one copy of what it touches, not one per op.
     */
    private static final class Working {

        final JsonObject plan;
        private final Map<String, List<JsonElement>> things = new LinkedHashMap<>();
        private final Map<String, Map<String, Integer>> index = new HashMap<>();

        Working(final JsonObject plan) {
            this.plan = LiveJson.shallow(plan);
        }

        /** The collection for writing; created (empty) only when absent and {@code create} is set; null otherwise. */
        @Nullable
        private List<JsonElement> open(final String c, final boolean create) {
            List<JsonElement> list = things.get(c);
            if (list != null) return list;
            final JsonElement existing = plan.get(c);
            final boolean array = existing != null && existing.isJsonArray();
            if (!array && !create) return null;
            list = new ArrayList<>();
            final Map<String, Integer> ids = new HashMap<>();
            if (array) for (final JsonElement thing : existing.getAsJsonArray()) {
                final String id = LiveJson.idOf(thing);
                if (id != null && !ids.containsKey(id)) ids.put(id, list.size());
                list.add(thing);
            }
            things.put(c, list);
            index.put(c, ids);
            // Holds the key's place in the plan; the finished array replaces it.
            plan.add(c, new JsonArray());
            return list;
        }

        @Nullable
        JsonObject find(final String c, final String id) {
            final List<JsonElement> list = open(c, false);
            if (list == null) return null;
            final Integer at = index.get(c)
                .get(id);
            return at == null ? null
                : list.get(at)
                    .getAsJsonObject();
        }

        void apply(final LiveOp op) {
            switch (op.o()) {
                case PUT -> {
                    final List<JsonElement> list = open(op.c(), true);
                    final JsonObject value = LiveJson.copy(op.v());
                    value.addProperty("id", op.id());
                    final Map<String, Integer> ids = index.get(op.c());
                    final Integer at = ids.get(op.id());
                    if (at == null) {
                        ids.put(op.id(), list.size());
                        list.add(value);
                    } else list.set(at, value);
                }
                case PATCH -> {
                    final List<JsonElement> list = open(op.c(), false);
                    if (list == null) return;
                    final Integer at = index.get(op.c())
                        .get(op.id());
                    if (at == null) return;
                    final JsonObject next = LiveJson.shallow(
                        list.get(at)
                            .getAsJsonObject());
                    writeFields(next, op.f(), op.d(), false);
                    list.set(at, next);
                }
                case DEL -> {
                    final List<JsonElement> list = open(op.c(), false);
                    if (list == null) return;
                    final Integer at = index.get(op.c())
                        .remove(op.id());
                    if (at == null) return;
                    list.set(at, GONE);
                }
                case META -> writeFields(plan, op.f(), op.d(), true);
            }
        }

        /** The finished plan, delete markers swept out. */
        JsonObject finish() {
            for (final Map.Entry<String, List<JsonElement>> e : things.entrySet()) {
                final JsonArray out = new JsonArray();
                for (final JsonElement thing : e.getValue()) if (thing != GONE) out.add(thing);
                plan.add(e.getKey(), out);
            }
            return plan;
        }
    }

    /** Sets {@code f} then deletes {@code d}, in place, for the keys a thing (or the meta) may have written. */
    private static void writeFields(final JsonObject target, @Nullable final JsonObject f,
        @Nullable final List<String> d, final boolean meta) {
        if (f != null) for (final Map.Entry<String, JsonElement> e : f.entrySet()) {
            if (meta ? !isMetaKey(e.getKey()) : !isThingKey(e.getKey())) continue;
            target.add(e.getKey(), LiveJson.copy(e.getValue()));
        }
        if (d != null) for (final String key : d) {
            if (key == null || (meta ? !isMetaKey(key) : !isThingKey(key))) continue;
            target.remove(key);
        }
    }

    /**
     * The plan after a batch, ops in order. Pure: neither the plan nor the ops are changed, and the result shares only
     * what the batch did not touch.
     */
    public static JsonObject apply(final JsonObject plan, final List<LiveOp> ops) {
        if (ops.isEmpty()) return plan;
        final Working working = new Working(plan);
        for (final LiveOp op : ops) working.apply(op);
        return working.finish();
    }

    // endregion

    // region Diffing

    /**
     * The fields that changed between two versions of a thing (or of the meta), as a patch's or meta's {@code f} and
     * {@code d} (sorted); null when none did. The {@code c} and {@code id} are filled in by the caller.
     */
    @Nullable
    private static LiveOp diffFields(final JsonObject before, final JsonObject after, final boolean meta) {
        final JsonObject f = new JsonObject();
        boolean changed = false;
        for (final Map.Entry<String, JsonElement> e : after.entrySet()) {
            final String key = e.getKey();
            if (meta ? !isMetaKey(key) : !isThingKey(key)) continue;
            if (!LiveJson.equal(before.get(key), e.getValue())) {
                f.add(key, LiveJson.copy(e.getValue()));
                changed = true;
            }
        }
        final List<String> d = new ArrayList<>();
        for (final Map.Entry<String, JsonElement> e : before.entrySet()) {
            final String key = e.getKey();
            if ((meta ? isMetaKey(key) : isThingKey(key)) && !after.has(key)) d.add(key);
        }
        Collections.sort(d);
        if (!changed && d.isEmpty()) return null;
        return LiveOp.meta(f, d);
    }

    private static Map<String, JsonObject> indexById(final List<JsonElement> things) {
        final Map<String, JsonObject> index = new LinkedHashMap<>();
        for (final JsonElement thing : things) {
            final String id = LiveJson.idOf(thing);
            if (id != null && !index.containsKey(id)) index.put(id, thing.getAsJsonObject());
        }
        return index;
    }

    /**
     * The batch that turns {@code before} into {@code after}: per collection (in {@link #COLLECTIONS} order) a put for
     * each thing only in {@code after} and a patch for each thing in both with any top-level field different (in
     * {@code after}'s order), a del for each thing only in {@code before} (in its order); then one meta for the changed
     * top-level fields. Batch order: every put, then every patch, then every del, then the meta. {@code d} lists names
     * sorted and is left out when empty; {@code f} is always there.
     */
    public static List<LiveOp> diff(final JsonObject before, final JsonObject after) {
        final List<LiveOp> puts = new ArrayList<>(), patches = new ArrayList<>(), dels = new ArrayList<>();
        for (final String c : COLLECTIONS) {
            final JsonElement oldValue = before.get(c), newValue = after.get(c);
            if (oldValue != null && oldValue == newValue) continue;
            final Map<String, JsonObject> oldIndex = indexById(collection(before, c)),
                newIndex = indexById(collection(after, c));
            for (final Map.Entry<String, JsonObject> e : newIndex.entrySet()) {
                final JsonObject old = oldIndex.get(e.getKey());
                if (old == null) puts.add(LiveOp.put(c, e.getKey(), LiveJson.copy(e.getValue())));
                else if (old != e.getValue()) {
                    final LiveOp fields = diffFields(old, e.getValue(), false);
                    if (fields != null) patches.add(LiveOp.patch(c, e.getKey(), fields.f(), fields.d()));
                }
            }
            for (final String id : oldIndex.keySet()) if (!newIndex.containsKey(id)) dels.add(LiveOp.del(c, id));
        }
        final List<LiveOp> ops = new ArrayList<>(puts);
        ops.addAll(patches);
        ops.addAll(dels);
        final LiveOp meta = diffFields(before, after, true);
        if (meta != null) ops.add(meta);
        return ops;
    }

    // endregion

    // region Inverting

    /**
     * Restores exactly the named fields of {@code old}: the values it had, deletes for those it lacked; null if none.
     */
    @Nullable
    private static LiveOp restore(final JsonObject old, final LiveOp op, final boolean meta) {
        final Set<String> names = new LinkedHashSet<>();
        if (op.f() != null) for (final Map.Entry<String, JsonElement> e : op.f()
            .entrySet()) names.add(e.getKey());
        if (op.d() != null) for (final String key : op.d()) if (key != null) names.add(key);
        final JsonObject f = new JsonObject();
        final List<String> d = new ArrayList<>();
        for (final String key : names) {
            if (meta ? !isMetaKey(key) : !isThingKey(key)) continue;
            if (old.has(key)) f.add(key, LiveJson.copy(old.get(key)));
            else d.add(key);
        }
        if (f.entrySet()
            .isEmpty() && d.isEmpty()) return null;
        Collections.sort(d);
        return meta ? LiveOp.meta(f, d) : LiveOp.patch(op.c(), op.id(), f, d);
    }

    /**
     * The batch that undoes {@code ops}, computed against {@code before}, the plan they were applied to. Each op is
     * inverted against the plan as it stood just before that op, and the inverses run in reverse order:
     * <ul>
     * <li>a put of a new thing becomes a del; a put over an existing thing, a put of the old thing;</li>
     * <li>a del becomes a put of the old thing; a del of nothing, nothing;</li>
     * <li>a patch becomes a patch restoring the old values of exactly the fields it named (deleting those that were
     * absent); a patch on nothing, nothing;</li>
     * <li>a meta likewise, on the plan's top-level fields.</li>
     * </ul>
     */
    public static List<LiveOp> invert(final JsonObject before, final List<LiveOp> ops) {
        final Working working = new Working(before);
        final List<LiveOp> inverse = new ArrayList<>();
        for (final LiveOp op : ops) {
            final LiveOp undo = invertOne(working, op);
            if (undo != null) inverse.add(undo);
            working.apply(op);
        }
        Collections.reverse(inverse);
        return inverse;
    }

    @Nullable
    private static LiveOp invertOne(final Working working, final LiveOp op) {
        return switch (op.o()) {
            case PUT -> {
                final JsonObject old = working.find(op.c(), op.id());
                yield old != null ? LiveOp.put(op.c(), op.id(), LiveJson.copy(old)) : LiveOp.del(op.c(), op.id());
            }
            case DEL -> {
                final JsonObject old = working.find(op.c(), op.id());
                yield old != null ? LiveOp.put(op.c(), op.id(), LiveJson.copy(old)) : null;
            }
            case PATCH -> {
                final JsonObject old = working.find(op.c(), op.id());
                yield old == null ? null : restore(old, op, false);
            }
            case META -> restore(working.plan, op, true);
        };
    }

    // endregion

    // region Reading ops off the wire

    /** A batch read off the wire: its ops, or the first problem with it. */
    public record Read(@Nullable List<LiveOp> ops, @Nullable String error) {}

    /**
     * Checks a batch's shape as it arrives (ops.ts's readOps): known op kinds, known collections, string ids, a put's
     * {@code v.id} equal to its id, no {@code id} in a patch, no collection name in a meta. The values themselves are
     * checked after applying, against the plan's schema.
     */
    public static Read read(@Nullable final JsonElement raw) {
        if (raw == null || !raw.isJsonArray()) return new Read(null, "ops must be an array");
        final List<LiveOp> ops = new ArrayList<>();
        final JsonArray array = raw.getAsJsonArray();
        for (int at = 0; at < array.size(); at++) {
            final String where = "op " + at;
            final JsonElement element = array.get(at);
            if (!element.isJsonObject()) return new Read(null, where + " is not an object");
            final JsonObject op = element.getAsJsonObject();
            final String kind = string(op.get("o"));
            if ("meta".equals(kind)) {
                final Fields fields = fields(op, true);
                if (fields.error() != null) return new Read(null, where + ": " + fields.error());
                ops.add(LiveOp.meta(fields.f(), fields.d()));
                continue;
            }
            final String c = string(op.get("c"));
            if (!isCollection(c)) return new Read(null, where + ": unknown collection");
            final String id = string(op.get("id"));
            if (id == null || id.isEmpty() || id.length() > ID_MAX_LENGTH) return new Read(null, where + ": bad id");
            if ("put".equals(kind)) {
                final JsonElement v = op.get("v");
                if (v == null || !v.isJsonObject() || !id.equals(LiveJson.idOf(v)))
                    return new Read(null, where + ": a put needs v with v.id equal to id");
                ops.add(LiveOp.put(c, id, v.getAsJsonObject()));
            } else if ("patch".equals(kind)) {
                final Fields fields = fields(op, false);
                if (fields.error() != null) return new Read(null, where + ": " + fields.error());
                ops.add(LiveOp.patch(c, id, fields.f(), fields.d()));
            } else if ("del".equals(kind)) ops.add(LiveOp.del(c, id));
            else return new Read(null, where + ": unknown op");
        }
        return new Read(ops, null);
    }

    /** A patch's or meta's fields as read: {@code f} and {@code d}, or what is wrong with them. */
    private record Fields(@Nullable JsonObject f, @Nullable List<String> d, @Nullable String error) {

        static Fields wrong(final String error) {
            return new Fields(null, null, error);
        }
    }

    private static Fields fields(final JsonObject op, final boolean meta) {
        final JsonElement f = op.get("f");
        if (f == null || !f.isJsonObject()) return Fields.wrong("f must be an object");
        for (final Map.Entry<String, JsonElement> e : f.getAsJsonObject()
            .entrySet()) {
            final String key = e.getKey();
            if ((meta ? !isMetaKey(key) : !isThingKey(key)) || key.length() > FIELD_NAME_MAX_LENGTH) return Fields
                .wrong("field " + quote(key.length() > 40 ? key.substring(0, 40) : key) + " cannot be set");
        }
        final JsonElement d = op.get("d");
        if (d == null) return new Fields(f.getAsJsonObject(), null, null);
        if (!d.isJsonArray()) return Fields.wrong("d must be a list of field names");
        final List<String> names = new ArrayList<>();
        for (final JsonElement name : d.getAsJsonArray()) {
            final String key = string(name);
            if (key == null || (meta ? !isMetaKey(key) : !isThingKey(key)))
                return Fields.wrong("d must be a list of field names");
            names.add(key);
        }
        return new Fields(f.getAsJsonObject(), names, null);
    }

    @Nullable
    private static String string(@Nullable final JsonElement e) {
        return e != null && e.isJsonPrimitive()
            && e.getAsJsonPrimitive()
                .isString() ? e.getAsString() : null;
    }

    /** {@code JSON.stringify} of a string, as the error messages quote field names. */
    static String quote(final String s) {
        return new JsonPrimitive(s).toString();
    }

    // endregion
}
