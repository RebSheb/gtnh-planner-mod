package com.gtnhplanner.cloud.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.Tags;
import com.gtnhplanner.library.CommunityApi;

/**
 * The REST side of cloud plans (docs/design/cloud-live.md): the account's plans and the ones shared with it, a plan's
 * body, creating, changing and deleting a plan, who may edit it, finding accounts, and who is on the same game server
 * (the live service's one HTTP route). Every call takes the session ({@code gtnh_session}) and is blocking: run it off
 * the client thread. The site is {@link CommunityApi#site()}.
 */
public final class CloudApi {

    private static final int CONNECT_MS = 8_000, READ_MS = 30_000;

    private CloudApi() {}

    // region Where the live service is

    private static volatile String liveOverride = clean(System.getProperty("gtnhplanner.live.url"));

    /** The live service's WebSocket address: the override if there is one, else derived from the site. */
    public static String live() {
        final String o = liveOverride;
        return o != null ? o : liveFor(CommunityApi.site());
    }

    /**
     * Points the game at another live service ({@code -Dgtnhplanner.live.url} does too); null or empty goes back to the
     * site's.
     */
    public static void useLive(@Nullable final String url) {
        liveOverride = clean(url);
    }

    /**
     * The live address for a site: {@code https://host} is {@code wss://host/live}; a local {@code http://} site
     * (the website's dev server) keeps its host and has the service one port up, {@code ws://localhost:3001/live}.
     */
    public static String liveFor(final String site) {
        final URI u = URI.create(site);
        final String host = u.getHost() == null ? "localhost" : u.getHost();
        final boolean secure = "https".equalsIgnoreCase(u.getScheme());
        final boolean local = host.equals("localhost") || host.equals("127.0.0.1")
            || host.equals("[::1]")
            || host.endsWith(".localhost");
        int port = u.getPort();
        if (!secure && local) port = (port < 0 ? 80 : port) + 1;
        return (secure ? "wss://" : "ws://") + host + (port < 0 ? "" : ":" + port) + "/live";
    }

    @Nullable
    private static String clean(@Nullable final String url) {
        if (url == null || url.isBlank()) return null;
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** The live service's HTTP address for a route under {@code /live}: ws is http, wss is https. */
    static String liveHttp(final String route) {
        final String ws = live();
        return (ws.startsWith("wss:") ? "https:" + ws.substring(4) : "http:" + ws.substring(3)) + route;
    }

    // endregion

    /** An account, as the site names one. */
    public record Account(String id, String username) {}

    /**
     * A plan in the account's list, its own or shared; {@code ownerId} is blank for the account's own until it is told
     * who it is ({@link #library}). {@code key()} is the doc key the live service and the cache know it by.
     */
    public record Design(String ownerId, String ownerName, String id, String name, @Nullable JsonObject icon,
        @Nullable String folderId, boolean closed, boolean favorite, @Nullable Integer order, String createdAt,
        String updatedAt, String planUpdatedAt, @Nullable String deletedAt, List<LiveClient.Member> members,
        boolean shared) {

        public String key() {
            return ownerId + ":" + id;
        }

        /** The same design known to be {@code owner}'s (a PUT's answer names no owner). */
        public Design ownedBy(final String owner) {
            return new Design(
                owner,
                ownerName,
                id,
                name,
                icon,
                folderId,
                closed,
                favorite,
                order,
                createdAt,
                updatedAt,
                planUpdatedAt,
                deletedAt,
                members,
                shared);
        }

        public boolean deleted() {
            return deletedAt != null;
        }

        JsonObject toJson() {
            final JsonObject o = new JsonObject();
            o.addProperty("ownerId", ownerId);
            o.addProperty("ownerName", ownerName);
            o.addProperty("id", id);
            o.addProperty("name", name);
            o.add("icon", icon == null ? JsonNull.INSTANCE : icon);
            o.addProperty("folderId", folderId);
            o.addProperty("closed", closed);
            o.addProperty("favorite", favorite);
            o.addProperty("order", order);
            o.addProperty("createdAt", createdAt);
            o.addProperty("updatedAt", updatedAt);
            o.addProperty("planUpdatedAt", planUpdatedAt);
            o.addProperty("deletedAt", deletedAt);
            final JsonArray m = new JsonArray();
            for (final LiveClient.Member x : members) {
                final JsonObject mo = new JsonObject();
                mo.addProperty("id", x.id());
                mo.addProperty("username", x.username());
                m.add(mo);
            }
            o.add("members", m);
            o.addProperty("shared", shared);
            return o;
        }

        /** Reads a design as the site sent it or as {@link #toJson} wrote it. */
        static Design fromJson(final JsonObject o, final String defaultOwnerId, final boolean shared) {
            final String owner = o.has("ownerId") ? string(o, "ownerId") : defaultOwnerId;
            return new Design(
                owner,
                string(o, "ownerName"),
                string(o, "id"),
                string(o, "name"),
                object(o, "icon"),
                nullable(o, "folderId"),
                bool(o, "closed"),
                bool(o, "favorite"),
                o.has("order") && o.get("order")
                    .isJsonPrimitive() ? o.get("order")
                        .getAsInt() : null,
                string(o, "createdAt"),
                string(o, "updatedAt"),
                string(o, "planUpdatedAt"),
                nullable(o, "deletedAt"),
                readMembers(o.get("members")),
                o.has("shared") ? bool(o, "shared") : shared);
        }
    }

    /** The account's plans (deleted ones included, as tombstones) and the plans shared with it. */
    public record Library(List<Design> own, List<Design> shared) {

        public JsonObject toJson() {
            final JsonObject o = new JsonObject();
            o.add("own", designs(own));
            o.add("shared", designs(shared));
            return o;
        }

        public static Library fromJson(final JsonObject o) {
            return new Library(read(o.get("own"), "", false), read(o.get("shared"), "", true));
        }

        private static JsonArray designs(final List<Design> l) {
            final JsonArray a = new JsonArray();
            for (final Design d : l) a.add(d.toJson());
            return a;
        }
    }

    private static List<Design> read(@Nullable final JsonElement e, final String ownerId, final boolean shared) {
        final List<Design> out = new ArrayList<>();
        if (e != null && e.isJsonArray()) for (final JsonElement d : e.getAsJsonArray()) {
            if (d.isJsonObject()) out.add(Design.fromJson(d.getAsJsonObject(), ownerId, shared));
        }
        return out;
    }

    /** A plan's body, the list entry it came with, the seq the body is at, and the caller's role in it. */
    public record Fetched(@Nullable Design design, JsonObject plan, long seq, String role) {

        public Fetched(@Nullable final Design design, final JsonObject plan) {
            this(design, plan, 0, "");
        }
    }

    /** What the site takes to create or change a plan; {@code plan} null is a change to the rest alone. */
    public record Upsert(String name, @Nullable JsonObject icon, @Nullable String folderId, boolean closed,
        boolean favorite, @Nullable Integer order, String createdAt, String updatedAt, String planUpdatedAt,
        @Nullable JsonObject plan) {

        JsonObject toJson() {
            final JsonObject o = new JsonObject();
            o.addProperty("name", name);
            o.add("icon", icon == null ? JsonNull.INSTANCE : icon);
            o.addProperty("folderId", folderId);
            o.addProperty("closed", closed);
            o.addProperty("favorite", favorite);
            o.addProperty("order", order);
            o.add("communityPlanId", JsonNull.INSTANCE);
            o.addProperty("createdAt", createdAt);
            o.addProperty("updatedAt", updatedAt);
            o.addProperty("planUpdatedAt", planUpdatedAt);
            if (plan != null) o.add("plan", plan);
            return o;
        }
    }

    /**
     * How a save went: {@code live} when the live service holds the plan (409): the body was not taken and edits go
     * through the socket, though the rest (name, folder, order, favorite, closed) can still be sent without a plan.
     * {@code behind} when the account's copy is newer, which is what {@code design} then holds.
     */
    public record Put(@Nullable Design design, boolean behind, boolean live) {}

    /** Thrown when the site says no; {@link #status} says how. */
    public static final class Refused extends IOException {

        public final int status;

        Refused(final int status, final String reason) {
            super(reason);
            this.status = status;
        }
    }

    // region Calls

    /** Who the session is. Null when the session has run out; throws when the site does not say which account. */
    @Nullable
    public static Account me(final String token) throws IOException {
        final Reply r = send("GET", CommunityApi.site() + "/api/community/auth/me", null, token, 401);
        if (r.status == 401) return null;
        final JsonElement user = json(r.body).get("user");
        if (user == null || !user.isJsonObject()) return null;
        final String id = string(user.getAsJsonObject(), "id");
        if (id.isBlank()) throw new IOException("the site did not say which account this is");
        return new Account(id, string(user.getAsJsonObject(), "username"));
    }

    /** The account's plans and the shared ones. {@code accountId} stamps the account's own as owned by it. */
    public static Library library(final String token, final String accountId) throws IOException {
        if (accountId.isBlank()) throw new IOException("the site did not say which account this is");
        final JsonObject root = json(send("GET", CommunityApi.site() + "/api/library", null, token).body);
        return new Library(read(root.get("designs"), accountId, false), read(root.get("shared"), "", true));
    }

    /** A plan's body, as its owner or as a member (the owner is {@code ownerId}). */
    public static Fetched plan(final String token, final String ownerId, final String designId) throws IOException {
        final JsonObject root = json(
            send(
                "GET",
                CommunityApi.site() + "/api/library/designs/" + enc(designId) + "?owner=" + enc(ownerId),
                null,
                token).body);
        final JsonElement plan = root.get("plan");
        if (plan == null || !plan.isJsonObject()) throw new IOException("the site sent no plan");
        final JsonElement d = root.get("design"), seq = root.get("seq");
        return new Fetched(
            d != null && d.isJsonObject() ? Design.fromJson(d.getAsJsonObject(), ownerId, false) : null,
            plan.getAsJsonObject(),
            seq != null && seq.isJsonPrimitive() ? seq.getAsLong() : 0,
            string(root, "role"));
    }

    /**
     * Creates or changes the account's own plan {@code designId} (a UUID the caller makes). A 409 with
     * {@code {"live":true}} is not an error: it comes back as {@link Put#live()}.
     */
    public static Put put(final String token, final String designId, final Upsert body) throws IOException {
        return put(token, designId, null, body);
    }

    /**
     * As {@link #put(String, String, Upsert)}, on {@code ownerId}'s plan (null: the account's own). A member may change
     * a shared plan's body and name only; the rest stays the owner's.
     */
    public static Put put(final String token, final String designId, @Nullable final String ownerId, final Upsert body)
        throws IOException {
        final Reply r = send(
            "PUT",
            CommunityApi.site() + "/api/library/designs/"
                + enc(designId)
                + (ownerId == null ? "" : "?owner=" + enc(ownerId)),
            body.toJson()
                .toString(),
            token,
            409);
        if (r.status == 409) {
            final JsonObject o = tryJson(r.body);
            if (o != null && bool(o, "live")) return new Put(null, false, true);
            throw new Refused(409, reason(409, r.body));
        }
        final JsonObject root = json(r.body);
        return new Put(
            object(root, "design") == null ? null : Design.fromJson(object(root, "design"), "", false),
            bool(root, "behind"),
            false);
    }

    /** Deletes the account's own plan (the site keeps a tombstone). */
    public static void delete(final String token, final String designId) throws IOException {
        send("DELETE", CommunityApi.site() + "/api/library/designs/" + enc(designId), null, token);
    }

    /** Who may edit a plan ({@code ownerId} null: the account's own). */
    public static List<LiveClient.Member> members(final String token, final String designId,
        @Nullable final String ownerId) throws IOException {
        final String body = send("GET", membersUrl(designId, null, ownerId), null, token).body;
        return readMembers(arrayIn(body, "members"));
    }

    /** Whose a plan is, the caller's role in it ("owner" or "editor"), and who else may edit it. */
    public record Sharing(String ownerId, String ownerName, String role, List<LiveClient.Member> members) {}

    /** A plan's sharing as the site has it now ({@code ownerId} null: the account's own). */
    public static Sharing sharing(final String token, final String designId, @Nullable final String ownerId)
        throws IOException {
        final JsonObject root = json(send("GET", membersUrl(designId, null, ownerId), null, token).body);
        return new Sharing(
            string(root, "ownerId"),
            string(root, "ownerName"),
            string(root, "role"),
            readMembers(root.get("members")));
    }

    /** Lets an account edit a plan (owner only; {@code ownerId} null: the account's own). */
    public static void addMember(final String token, final String designId, final String userId,
        @Nullable final String ownerId) throws IOException {
        send("PUT", membersUrl(designId, userId, ownerId), "{}", token);
    }

    /**
     * Takes an account off a plan: the owner removing someone, or a member leaving ({@code userId} is their own; give
     * {@code ownerId} then, so the site knows whose plan).
     */
    public static void removeMember(final String token, final String designId, final String userId,
        @Nullable final String ownerId) throws IOException {
        send("DELETE", membersUrl(designId, userId, ownerId), null, token);
    }

    private static String membersUrl(final String designId, @Nullable final String userId,
        @Nullable final String ownerId) {
        return CommunityApi.site() + "/api/library/designs/"
            + enc(designId)
            + "/members"
            + (userId == null ? "" : "/" + enc(userId))
            + (ownerId == null ? "" : "?owner=" + enc(ownerId));
    }

    /** Up to ten accounts whose names start with {@code prefix}. */
    public static List<Account> search(final String token, final String prefix) throws IOException {
        return accounts(
            arrayIn(
                send("GET", CommunityApi.site() + "/api/accounts/search?q=" + enc(prefix), null, token).body,
                "accounts"));
    }

    /** An account playing in this game, and the Minecraft name it plays as. */
    public record Player(String id, String username, String mcName) {}

    /** At most this many Minecraft names go with one {@link #nearby} call. */
    public static final int NEARBY_NAMES = 100;

    /**
     * Signed-in accounts on the same game server as this one, among the Minecraft {@code names} given: the players
     * this game sees on its tab list (the service answers only for names the asker can see), the first
     * {@value #NEARBY_NAMES}; none given, none found, and the service is not asked. It limits how often it is asked:
     * ask when the player wants to know, never on a timer. The hello names this game's player ({@code game.mcName}).
     */
    public static List<Player> nearby(final String token, final List<String> names) throws IOException {
        final StringBuilder q = new StringBuilder();
        int n = 0;
        for (final String name : names) {
            if (name == null || name.isBlank()) continue;
            if (n++ == NEARBY_NAMES) break;
            if (q.length() > 0) q.append(',');
            q.append(name.trim());
        }
        if (q.length() == 0) return new ArrayList<>();
        final JsonElement found = arrayIn(
            send("GET", liveHttp("/nearby") + "?names=" + enc(q.toString()), null, token).body,
            "accounts");
        final List<Player> out = new ArrayList<>();
        if (found != null && found.isJsonArray()) for (final JsonElement e : found.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            final JsonObject o = e.getAsJsonObject();
            out.add(new Player(string(o, "id"), string(o, "username"), string(o, "mcName")));
        }
        return out;
    }

    /**
     * Whether the site runs the live service ({@code GET /live/health}): a site without one has no cloud plans, so the
     * game keeps the world's plans. Throws when the service could not be asked at all.
     */
    public static boolean hasLive(final String token) throws IOException {
        final Reply r = send("GET", liveHttp("/health"), null, token, 404, 502, 503);
        return r.status == 200;
    }

    // endregion

    // region JSON

    private static List<LiveClient.Member> readMembers(@Nullable final JsonElement e) {
        final List<LiveClient.Member> out = new ArrayList<>();
        if (e != null && e.isJsonArray()) for (final JsonElement m : e.getAsJsonArray()) {
            if (m.isJsonObject()) out
                .add(new LiveClient.Member(string(m.getAsJsonObject(), "id"), string(m.getAsJsonObject(), "username")));
        }
        return out;
    }

    private static List<Account> accounts(@Nullable final JsonElement e) {
        final List<Account> out = new ArrayList<>();
        for (final LiveClient.Member m : readMembers(e)) out.add(new Account(m.id(), m.username()));
        return out;
    }

    /** An array the site sent either bare or under {@code key} (or the first array in the object). */
    @Nullable
    private static JsonElement arrayIn(final String body, final String key) throws IOException {
        final JsonElement root;
        try {
            root = new JsonParser().parse(body);
        } catch (final RuntimeException e) {
            throw new IOException("the site sent something unreadable", e);
        }
        if (root.isJsonArray()) return root;
        if (!root.isJsonObject()) return null;
        final JsonObject o = root.getAsJsonObject();
        if (o.has(key)) return o.get(key);
        for (final java.util.Map.Entry<String, JsonElement> en : o.entrySet()) if (en.getValue()
            .isJsonArray()) return en.getValue();
        return null;
    }

    private static String string(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    @Nullable
    private static String nullable(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static boolean bool(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    @Nullable
    private static JsonObject object(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static JsonObject json(final String body) throws IOException {
        final JsonObject o = tryJson(body);
        if (o == null) throw new IOException("the site sent something unreadable");
        return o;
    }

    @Nullable
    private static JsonObject tryJson(final String body) {
        try {
            return new JsonParser().parse(body)
                .getAsJsonObject();
        } catch (final RuntimeException e) {
            return null;
        }
    }

    // endregion

    private record Reply(int status, String body) {}

    private static Reply send(final String method, final String url, @Nullable final String json, final String token,
        final int... allowed) throws IOException {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(CONNECT_MS);
        c.setReadTimeout(READ_MS);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "GTNH Planner/" + Tags.VERSION + " (GT New Horizons planner mod)");
        c.setRequestProperty("Cookie", "gtnh_session=" + token);
        if (json != null) {
            final byte[] out = json.getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setFixedLengthStreamingMode(out.length);
            try (java.io.OutputStream o = c.getOutputStream()) {
                o.write(out);
            }
        }
        try {
            final int status = c.getResponseCode();
            final InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            final String body = in == null ? "" : read(in);
            if (status >= 400) {
                for (final int a : allowed) if (a == status) return new Reply(status, body);
                throw new Refused(status, reason(status, body));
            }
            return new Reply(status, body);
        } finally {
            c.disconnect();
        }
    }

    /** Why the site said no: its own words when it gave them ({@code {"error": ...}}), else the status. */
    private static String reason(final int status, final String body) {
        final JsonObject o = tryJson(body);
        if (o != null && o.has("error")
            && o.get("error")
                .isJsonPrimitive())
            return o.get("error")
                .getAsString();
        return switch (status) {
            case 401 -> "not signed in";
            case 403 -> "that isn't yours";
            case 404 -> "not found";
            case 409 -> "someone is editing it right now";
            case 413 -> "that plan is too big";
            case 429 -> "too many tries; wait a little";
            default -> "the site answered " + status;
        };
    }

    private static String read(final InputStream in) throws IOException {
        try (InputStream s = in) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buf = new byte[16384];
            for (int n; (n = s.read(buf)) > 0;) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String enc(final String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (final java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
