package com.gtnhplanner.cloud.session;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.cloud.map.CloudGame;
import com.gtnhplanner.cloud.map.CloudPlans;
import com.gtnhplanner.cloud.net.CloudApi;
import com.gtnhplanner.cloud.net.LiveClient;
import com.gtnhplanner.cloud.net.LiveSocket;
import com.gtnhplanner.cloud.store.CloudCache;
import com.gtnhplanner.cloud.store.Migration;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Plan;

/**
 * A signed-in account's plans on this device (docs/design/cloud-live.md): the planner's plans are the account's cloud
 * plans, its own and the ones shared with it, opened from this device's copy ({@link CloudCache}) so they open offline,
 * and kept in step with the account and with everyone editing them ({@link LiveDoc}, one each).
 * <ul>
 * <li>{@link #load} makes the planner's plans: every plan in the account's list and this device's copies, the tabs and
 * the open plan as this device left them.</li>
 * <li>{@link #save} is the planner's save: whatever the boards changed is sent; a plan new to the planner (made,
 * pasted, imported, shared in chat) becomes a new cloud plan once it holds anything; a plan gone from the planner is
 * deleted from the account (one shared with the player is left instead).</li>
 * <li>{@link #tick} runs every client tick: what the service sent, others' edits read into the boards once they are
 * at rest, rooms joined and left, edits sent, the list and plans fetched, and what failed tried again.</li>
 * <li>A world's own plans go to the account once ({@link Migration}); its save stays as it was.</li>
 * </ul>
 * One per account, made when the planner first needs it and stopped on sign-out. Everything but the network calls runs
 * on the client thread; those run on a thread of the session's own and answer back through {@link #tick}.
 */
public final class CloudSession {

    /** What the session needs from the game. */
    public interface Host {

        /** The game's side of the mapping, for the world the game is in now. Client thread. */
        CloudGame game();

        /** What the hello tells the live service: this game, and the server or world it is in. */
        LiveClient.Hello hello();

        /**
         * Whether the board is in the middle of a change to this plan (a wheel spin, something in hand): others' edits
         * wait until it is at rest, and nothing is sent half-made.
         */
        boolean busy(Graph graph);

        /** A plan's slot took a new graph in place of {@code old} (others' edits read in, an undo). */
        void replaced(Graph old, Graph next);

        /** Tells the player something, in one short sentence. */
        void notice(String text);

        /**
         * Another account added this one to a plan (or this player is told again after a reconnect): the board says so
         * with a key that opens it, or, with the planner shut, a chat line with a clickable [Open].
         */
        default void shared(final String ownerName, final String planName, final String key) {}

        /**
         * The player's plans in hand now (the tour's sandbox aside), or null when none are loaded: when they are no
         * longer the session's, it lets go of its own.
         */
        @Nullable
        default Plan inHand() {
            return Plan.player();
        }

        /** The session let go of the planner's plans (the world closed, or it stopped). */
        default void stopped() {}

        /** The world's own plans (its save), or null when it has none: what goes to the account, once. */
        @Nullable
        default Plan worldPlans() {
            return null;
        }

        /** Copies the world's save aside before its plans are keyed for the account; false when that failed. */
        default boolean backUpWorld() {
            return false;
        }

        /** Writes the world's plans back as its save, now keyed to their cloud plans; false when that failed. */
        default boolean saveWorld(final Plan plans) {
            return false;
        }
    }

    /** How the board shows where the account's plans stand. */
    public enum Status {
        /** Connected: edits go as they are made, and others' come in. */
        LIVE,
        /** Connecting, or connecting again after a drop. */
        CONNECTING,
        /** Not connected: edits are kept on this device and go when it is. */
        OFFLINE
    }

    private static final long LIST_EVERY_MS = 5 * 60_000, RETRY_MS_DEFAULT = 30_000, LEAVE_AFTER_MS = 10_000;
    /** The live service holds at most this many rooms for one connection. */
    private static final int ROOMS = 8;
    private long retryMs = RETRY_MS_DEFAULT;

    /** How long a failed call waits before it is tried again (tests shorten it). */
    public void retryEvery(final long ms) {
        retryMs = ms;
    }

    @Nullable
    private static CloudSession current;

    /** The session of the account signed in, once the planner has needed it; null otherwise. */
    @Nullable
    public static CloudSession current() {
        return current;
    }

    /**
     * Starts the session for an account (stopping another's): the account's id, the session token (asked on every
     * call), the folder this device's copies go under ({@code <game dir>}), and the game.
     */
    public static CloudSession start(final String accountId, final Supplier<String> token, final File gameDir,
        final Host host) {
        if (current != null) current.stop();
        current = new CloudSession(accountId, token, CloudCache.forAccount(gameDir, accountId), host);
        return current;
    }

    private final String accountId;
    private final Supplier<String> token;
    private final CloudCache cache;
    private final Host host;
    private final ExecutorService net = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "GTNH Planner cloud");
        t.setDaemon(true);
        return t;
    });
    /** Answers from the network thread, run on the client thread by {@link #tick}. */
    private final Queue<Runnable> answers = new ConcurrentLinkedQueue<>();

    @Nullable
    private LiveClient client;
    @Nullable
    private LiveClient.Hello said;
    /** What the hello says for the world the plans were loaded in (asked once a load: it reads the disk). */
    @Nullable
    private LiveClient.Hello hello;
    @Nullable
    private String liveUrl;
    private final LiveClient.Listener listener = new Listener();
    private long lastLive;
    /** When the connection was last made (a try under way since counts as connecting for a while). */
    private long connectedAt;

    @Nullable
    private CloudGame game;
    /** The planner's plans while this session made them; null when none are loaded. */
    @Nullable
    private Plan plan;
    private final Map<String, LiveDoc> docs = new LinkedHashMap<>();
    @Nullable
    private CloudApi.Library library;
    private long listAt = -LIST_EVERY_MS;
    private boolean listing;
    /** The list came from the account this session (not just this device's copy of it): plans may go up against it. */
    private boolean listFresh;
    /** Plans the player let go of (deleted, or left when shared) still to be told to the account. */
    private final Set<String> letGo = new LinkedHashSet<>();
    /** When a room was last wanted, by doc key: one unwanted a while is left. */
    private final Map<String, Long> wanted = new HashMap<>();
    /** Network work under way or failed lately, by what it is for: tried again after a while. */
    private final Map<String, Long> tried = new HashMap<>();
    private final Set<String> busyCalls = new HashSet<>();
    /** Plans whose room refused this game, by doc key: not joined again until the account's list has been asked. */
    private final Set<String> refusedRooms = new HashSet<>();
    /** The plan's name as last told to the account's list, by doc key. */
    private final Map<String, String> named = new HashMap<>();
    /** What was last kept of this device's own state (tabs, pins), so it is written only when it changes. */
    @Nullable
    private JsonObject deviceKept;
    private final Map<String, List<String>> pinsKept = new HashMap<>();
    private boolean migrated;
    /** Plans the player was told were shared with them this session: a repeated frame for one is ignored. */
    private final Set<String> announced = new HashSet<>();

    private CloudSession(final String accountId, final Supplier<String> token, final CloudCache cache,
        final Host host) {
        this.accountId = accountId;
        this.token = token;
        this.cache = cache;
        this.host = host;
        final JsonElement gone = cache.device()
            .get("letGo");
        if (gone != null && gone.isJsonArray())
            for (final JsonElement e : gone.getAsJsonArray()) letGo.add(e.getAsString());
    }

    public String accountId() {
        return accountId;
    }

    CloudCache cache() {
        return cache;
    }

    CloudGame game() {
        if (game == null) game = host.game();
        return game;
    }

    /** The live connection, once one was needed; null before. Presence and sharing go through it. */
    @Nullable
    public LiveClient client() {
        return client;
    }

    /** The plan behind a board graph, or null when it is not one of this account's cloud plans. */
    @Nullable
    public LiveDoc doc(@Nullable final Graph graph) {
        if (graph == null || graph.getCloudKey() == null) return null;
        final LiveDoc d = docs.get(graph.getCloudKey());
        return d != null && d.graph() == graph ? d : null;
    }

    /** The plan by doc key, or null. */
    @Nullable
    public LiveDoc doc(final String key) {
        return docs.get(key);
    }

    /**
     * Whose a plan is when it is shared with the player (the owner's name, as the list gives it); null for the
     * player's own.
     */
    @Nullable
    public String sharedBy(final Graph graph) {
        final String key = graph.getCloudKey();
        if (key == null || key.startsWith(accountId + ":")) return null;
        if (library != null) for (final CloudApi.Design d : library.shared()) if (d.key()
            .equals(key))
            return d.ownerName()
                .isEmpty() ? "someone" : d.ownerName();
        return "someone";
    }

    /**
     * Whether this account's plans are cloud plans on this device: its site was seen to have the live service once. A
     * site without one (one not rolled out yet, a stand-in) leaves the world's plans open, signed in or not.
     */
    public boolean ready() {
        return cache.device()
            .has("cloud");
    }

    private boolean checking;

    /**
     * Asks, once a session, whether the account's site has cloud plans (its live service answers, and the list comes);
     * when it has, this device keeps that and {@code then} runs (the planner switches over).
     */
    public void checkReady(final Runnable then) {
        if (checking || ready()) return;
        checking = true;
        call("ready", () -> CloudApi.hasLive(token.get()) ? CloudApi.library(token.get(), accountId) : null, lib -> {
            if (lib == null) return;
            library = lib;
            cache.saveList(lib);
            final JsonObject device = cache.device();
            device.addProperty("cloud", true);
            cache.saveDevice(device);
            then.run();
        }, e -> {});
    }

    /** Whether a plan is one shared with the player (theirs to leave, not to delete). */
    public boolean shared(final Graph graph) {
        final String key = graph.getCloudKey();
        return key != null && !key.startsWith(accountId + ":");
    }

    // region The planner's plans

    /**
     * The planner's plans for this account: every plan in its list and this device's copies, as this device left
     * them (which have tabs, the open one, the order). Plans not on this device yet are empty until they come. The
     * world's own plans go to the account once, in the background. Client thread.
     */
    public Plan load() {
        letGoOfPlans();
        game = host.game();
        hello = host.hello();
        final Plan p = Plan.cloud(accountId);
        plan = p;
        migrated = false;
        library = cache.list();
        final JsonObject device = cache.device();
        final List<String> keys = new ArrayList<>();
        for (final JsonElement e : array(device, "order")) keys.add(e.getAsString());
        if (library != null) {
            for (final CloudApi.Design d : library.own()) if (!d.deleted()) keys.add(d.key());
            for (final CloudApi.Design d : library.shared()) keys.add(d.key());
        }
        for (final String k : cache.keysWithPending()) keys.add(k);
        final Set<String> open = new HashSet<>();
        for (final JsonElement e : array(device, "open")) open.add(e.getAsString());
        final JsonObject lastOpen = device.has("lastOpen") && device.get("lastOpen")
            .isJsonObject() ? device.getAsJsonObject("lastOpen") : new JsonObject();
        final Set<String> making = new HashSet<>();
        for (final JsonElement e : array(device, "making")) making.add(e.getAsString());
        for (final String key : new LinkedHashSet<>(keys)) {
            if (letGo.contains(key) || !key.contains(":")) continue;
            if (!listed(key) && !making.contains(key) && cache.doc(key) == null) continue;
            final LiveDoc d = open(key, making.contains(key));
            if (d == null) continue;
            final Graph g = d.graph();
            g.setOpen(open.contains(key));
            if (lastOpen.has(key)) g.setLastOpen(
                lastOpen.get(key)
                    .getAsLong());
            g.pinList.clear();
            for (final String id : cache.pins(key)) {
                try {
                    g.pinList.add(UUID.fromString(id));
                } catch (final IllegalArgumentException ignored) {}
            }
            pinsKept.put(key, cache.pins(key));
            p.getGraphs()
                .add(g);
        }
        final String active = device.has("active") ? device.get("active")
            .getAsString() : null;
        int index = 0;
        for (int i = 0; i < p.getGraphs()
            .size(); i++)
            if (Objects.equals(
                p.getGraphs()
                    .get(i)
                    .getCloudKey(),
                active)) index = i;
        if (!p.getGraphs()
            .isEmpty()) {
            p.setActiveIndex(index);
            p.getGraphs()
                .get(index)
                .setOpen(true);
        }
        // An account with no plans yet still opens on one, empty; the world's plans take its place when they arrive.
        standIn = null;
        standInHeld = false;
        if (p.getGraphs()
            .isEmpty()) {
            standInHeld = true;
            standIn = new Graph("Plan 1");
            p.getGraphs()
                .add(standIn);
            p.setActiveIndex(0);
        }
        deviceKept = deviceState(p);
        listAt = -LIST_EVERY_MS;
        return p;
    }

    /** The empty plan the planner opened on while the account had none, until the world's plans arrive. */
    @Nullable
    private Graph standIn;
    /** Whether the stand-in still waits for the world's plans (settled once they have gone up, or there are none). */
    private boolean standInHeld;

    /**
     * The world's plans arrived: the empty stand-in goes (unless the player has put something on it, which makes it a
     * plan of its own), and the plan the world had open opens in its place.
     */
    private void replaceStandIn(final Plan local, final List<Migration.Item> items) {
        final Graph s = standIn;
        standIn = null;
        standInHeld = false;
        if (s == null || plan == null || s.getCloudKey() != null || Migration.eligible(s)) return;
        final int at = plan.getGraphs()
            .indexOf(s);
        if (at < 0 || plan.getGraphs()
            .size() < 2) return;
        final int worldAt = local.getActiveIndex();
        final Graph worldOpen = worldAt >= 0 && worldAt < local.getGraphs()
            .size() ? local.getGraphs()
                .get(worldAt) : null;
        Graph open = null;
        for (final Migration.Item item : items) {
            final LiveDoc d = docs.get(item.key());
            if (d == null) continue;
            if (open == null || item.graph() == worldOpen) open = d.graph();
        }
        final boolean wasOpen = plan.getActiveIndex() == at;
        plan.removeSlot(at);
        final int next = open == null ? -1
            : plan.getGraphs()
                .indexOf(open);
        if (wasOpen && next >= 0) plan.setActiveIndex(next);
    }

    /** A plan's doc from this device's copy, else known by name until its body comes. */
    @Nullable
    private LiveDoc open(final String key, final boolean making) {
        final LiveDoc had = docs.get(key);
        if (had != null) return had;
        final CloudCache.Doc copy = cache.doc(key);
        final LiveDoc d;
        try {
            if (copy != null) d = LiveDoc.fromCopy(this, key, copy, cache.pending(key));
            else d = LiveDoc.unknown(this, key, listedName(key), cache.pending(key));
        } catch (final RuntimeException e) {
            GtnhPlanner.LOG.error("Could not read the plan {}; it stays in the account", key, e);
            return null;
        }
        if (making && copy != null) d.markMaking();
        docs.put(key, d);
        // The list's name, which the board takes (the website renames the list alone, so the plan's own may be older);
        // a plan the list does not have yet goes by its own until the list says otherwise.
        if (design(key) != null) d.listRenamed(listedName(key));
        named.put(
            key,
            design(key) != null ? listedName(key)
                : d.graph()
                    .getName());
        return d;
    }

    /**
     * The planner's save while signed in: every board change goes to its plan, each plan new to the planner with
     * anything on it becomes a cloud plan, each plan gone from the planner is let go of, and this device's tabs and
     * pins are kept. Client thread.
     */
    public void save(final Plan p) {
        if (p != plan) return;
        final Map<String, Graph> slots = new HashMap<>();
        for (final Graph g : new ArrayList<>(p.getGraphs())) {
            final String key = g.getCloudKey();
            if (key == null || !docs.containsKey(key)) {
                if (makeable(g)) slots.put(make(g), g);
                continue;
            }
            slots.put(key, g);
        }
        for (final LiveDoc d : new ArrayList<>(docs.values())) {
            final Graph g = slots.get(d.key());
            if (g == null) {
                if (deleting.remove(d.key())) letGo(d);
                else {
                    // Gone from the planner without the player deleting it: kept, on this device and in the account.
                    GtnhPlanner.LOG.warn(
                        "'{}' left the planner unasked; it stays in the account",
                        d.graph()
                            .getName());
                    d.keepCopy(true);
                    docs.remove(d.key());
                }
                continue;
            }
            if (g != d.graph()) d.rebind(g);
            if (!host.busy(g)) commit(d);
        }
        keepDevice();
    }

    /**
     * Whether a plan new to the planner goes to the account now: every plan is in the account from the moment it
     * exists, empty ones too (a new plan, a new task board, one pasted or imported). Not a slot that failed to read,
     * and
     * not the empty plan the planner opened on while the world's plans have not arrived: they take its place.
     */
    private boolean makeable(final Graph g) {
        if (g.getUnreadable() != null && g.nodes.isEmpty()) return false;
        if (g == standIn && standInHeld && !Migration.eligible(g)) return false;
        return true;
    }

    /**
     * The world's plans have been dealt with (or there are none): the empty plan the planner opened on is a plan of the
     * account like any other, made now.
     */
    private void releaseStandIn() {
        if (!standInHeld) return;
        standInHeld = false;
        if (plan != null && host.inHand() == plan) save(plan);
    }

    /** Plans the player is deleting (or leaving, shared), by doc key: the next save lets go of them. */
    private final Set<String> deleting = new HashSet<>();

    /**
     * The player is deleting a plan (leaving it, when it is shared with them): call before taking its slot away. The
     * account is told; a plan only taken off the planner any other way stays in the account.
     */
    public void deleting(final Graph graph) {
        if (graph.getCloudKey() != null) deleting.add(graph.getCloudKey());
    }

    /**
     * A board new to the planner becomes a new cloud plan of the account's: kept here at once, sent when online. Its
     * key.
     */
    private String make(final Graph g) {
        final String key = accountId + ":"
            + UUID.randomUUID()
                .toString();
        final LiveDoc d = LiveDoc.made(this, key, g);
        docs.put(key, d);
        cache.saveDocAsIs(key, 0, d.confirmed());
        named.put(key, g.getName());
        keepDevice();
        return key;
    }

    /**
     * A plan the player deleted or left: off this device at once; the account is told (an own plan deleted, a shared
     * one left), again later if it cannot be now. A plan only made here goes without a word to the account.
     */
    private void letGo(final LiveDoc d) {
        docs.remove(d.key());
        if (client != null) client.leave(d.key());
        cache.forget(d.key());
        pinsKept.remove(d.key());
        if (!d.creating()) {
            letGo.add(d.key());
            if (library != null) {
                library = new CloudApi.Library(without(library.own(), d.key()), without(library.shared(), d.key()));
                // This device's copy of the list too: the next load must not bring it back for the list to drop again.
                cache.saveList(library);
            }
        }
        keepDevice();
    }

    private static List<CloudApi.Design> without(final List<CloudApi.Design> l, final String key) {
        final List<CloudApi.Design> out = new ArrayList<>();
        for (final CloudApi.Design d : l) if (!d.key()
            .equals(key)) out.add(d);
        return out;
    }

    /** Takes a plan off the planner that the account no longer has for this player (deleted, or unshared). */
    private void dropped(final LiveDoc d, final String why) {
        GtnhPlanner.LOG.info(
            "[cloud] {}: '{}' ({}) leaves the planner: {}",
            accountId,
            d.graph()
                .getName(),
            d.key(),
            why);
        docs.remove(d.key());
        cache.forget(d.key());
        pinsKept.remove(d.key());
        if (plan == null) return;
        final int i = plan.getGraphs()
            .indexOf(d.graph());
        if (i < 0) return;
        final String name = d.graph()
            .getName();
        if (plan.getGraphs()
            .size() == 1)
            plan.getGraphs()
                .add(new Graph("Plan 1"));
        plan.removeSlot(i);
        host.notice("'" + name + "' " + why);
        keepDevice();
    }

    /** This device's own state of the plans: their order, which have tabs, the open one, when each was open. */
    private JsonObject deviceState(final Plan p) {
        final JsonObject o = new JsonObject();
        final JsonArray order = new JsonArray(), open = new JsonArray(), making = new JsonArray(),
            gone = new JsonArray();
        final JsonObject lastOpen = new JsonObject();
        for (final Graph g : p.getGraphs()) {
            final String key = g.getCloudKey();
            if (key == null || !docs.containsKey(key)) continue;
            order.add(new com.google.gson.JsonPrimitive(key));
            if (g.isOpen()) open.add(new com.google.gson.JsonPrimitive(key));
            if (g.getLastOpen() > 0) lastOpen.addProperty(key, g.getLastOpen());
            if (docs.get(key)
                .creating()) making.add(new com.google.gson.JsonPrimitive(key));
        }
        for (final String key : letGo) gone.add(new com.google.gson.JsonPrimitive(key));
        o.add("order", order);
        o.add("open", open);
        o.add("lastOpen", lastOpen);
        o.add("making", making);
        o.add("letGo", gone);
        o.addProperty("cloud", true);
        final int active = p.getActiveIndex();
        if (active >= 0 && active < p.getGraphs()
            .size()
            && p.getGraphs()
                .get(active)
                .getCloudKey() != null)
            o.addProperty(
                "active",
                p.getGraphs()
                    .get(active)
                    .getCloudKey());
        return o;
    }

    private void keepDevice() {
        if (plan == null) return;
        final JsonObject now = deviceState(plan);
        if (!now.equals(deviceKept)) {
            cache.saveDevice(now);
            deviceKept = now;
        }
        for (final LiveDoc d : docs.values()) {
            final List<String> pins = new ArrayList<>();
            for (final UUID id : d.graph().pinList) pins.add(id.toString());
            if (!pins.equals(pinsKept.get(d.key()))) {
                cache.setPins(d.key(), pins);
                pinsKept.put(d.key(), pins);
            }
        }
    }

    // endregion

    // region Undo, redo, reading in

    /** Undoes the player's last own edit to this plan; false when it is not a cloud plan or has none. */
    public boolean undo(final Graph graph) {
        final LiveDoc d = doc(graph);
        if (d == null || !d.undo()) return false;
        catchUp(d);
        return true;
    }

    public boolean redo(final Graph graph) {
        final LiveDoc d = doc(graph);
        if (d == null || !d.redo()) return false;
        catchUp(d);
        return true;
    }

    /** Sends what a board changed (timed for {@code call perf}: {@code cloud.write}). */
    private void commit(final LiveDoc d) {
        final long t = System.nanoTime();
        try {
            if (d.commit() && com.gtnhplanner.dev.DevPerf.on())
                com.gtnhplanner.dev.DevPerf.time("cloud.write", System.nanoTime() - t);
        } catch (final RuntimeException e) {
            // The board keeps the change; the next commit tries again.
            GtnhPlanner.LOG.error(
                "Could not write the changes to '{}'",
                d.graph()
                    .getName(),
                e);
        }
    }

    /** Reads what landed on a plan into its board now, and puts the new board in its slot. */
    private void catchUp(final LiveDoc d) {
        final Graph old = d.graph();
        final Graph next;
        try {
            final long t = System.nanoTime();
            next = d.catchUp();
            if (next != null && com.gtnhplanner.dev.DevPerf.on())
                com.gtnhplanner.dev.DevPerf.time("cloud.read", System.nanoTime() - t);
        } catch (final RuntimeException e) {
            GtnhPlanner.LOG.error("Could not read the changes to '{}' into the board", old.getName(), e);
            rejoin(d);
            return;
        }
        if (next == null) return;
        if (plan != null) {
            final int i = plan.getGraphs()
                .indexOf(old);
            if (i >= 0) plan.getGraphs()
                .set(i, next);
        }
        host.replaced(old, next);
    }

    // endregion

    // region Renaming, copying, sharing's list

    /** The name the account's list has for a plan; the plan's own name is in its body and goes as an edit. */
    private String listedName(final String key) {
        if (library != null) {
            for (final CloudApi.Design d : library.own()) if (d.key()
                .equals(key)) return d.name();
            for (final CloudApi.Design d : library.shared()) if (d.key()
                .equals(key)) return d.name();
        }
        return "Plan";
    }

    private boolean listed(final String key) {
        if (library == null) return false;
        for (final CloudApi.Design d : library.own()) if (!d.deleted() && d.key()
            .equals(key)) return true;
        for (final CloudApi.Design d : library.shared()) if (d.key()
            .equals(key)) return true;
        return false;
    }

    @Nullable
    private CloudApi.Design design(final String key) {
        if (library == null) return null;
        for (final CloudApi.Design d : library.own()) if (d.key()
            .equals(key)) return d;
        for (final CloudApi.Design d : library.shared()) if (d.key()
            .equals(key)) return d;
        return null;
    }

    /** Fetches the account's list now (when the library opens on My plans), in the background. */
    public void refreshList() {
        listAt = -LIST_EVERY_MS;
    }

    // endregion

    // region The live service

    /**
     * The connection, made and started on first need; made again when the game's world (what the hello says) or the
     * live
     * service's address changed.
     */
    private LiveClient connection() {
        if (hello == null) hello = host.hello();
        final String url = CloudApi.live();
        if (client != null && hello.equals(said) && url.equals(liveUrl)) return client;
        if (client != null) client.stop();
        said = hello;
        liveUrl = url;
        client = new LiveClient(java.net.URI.create(url), token, hello);
        client.addListener(listener);
        for (final LiveDoc d : docs.values()) d.leftRoom();
        client.start();
        connectedAt = System.currentTimeMillis();
        return client;
    }

    /** Joins a plan's room again for a fresh snapshot (batches were missed, or one did not read). */
    void rejoin(final LiveDoc d) {
        if (client == null) return;
        client.leave(d.key());
        client.join(d.key());
        d.joining();
    }

    void notice(final String text) {
        host.notice(text);
    }

    /** Where the account's plans stand, for the board's top bar. */
    public Status status() {
        if (client != null && client.ready()) return Status.LIVE;
        // A while after the last time it was live, or since it began trying, it is still only connecting.
        if (client == null || System.currentTimeMillis() - Math.max(lastLive, connectedAt) < 15_000)
            return Status.CONNECTING;
        return Status.OFFLINE;
    }

    /** Whether any plan has edits that have not reached the account yet. */
    public boolean editsWaiting() {
        for (final LiveDoc d : docs.values()) if (d.waiting() || d.creating()) return true;
        return false;
    }

    private final class Listener implements LiveClient.Listener {

        @Override
        public void onState(final LiveSocket.State state, final String reason) {
            if (state != LiveSocket.State.OPEN) for (final LiveDoc d : docs.values()) d.leftRoom();
            if (LiveSocket.AUTH.equals(reason))
                GtnhPlanner.LOG.warn("The live service did not take this sign-in; plans are kept here");
        }

        @Override
        public void onWelcome(final LiveClient.Person you) {
            lastLive = System.currentTimeMillis();
            // A new connection: every room joined again from a snapshot, and the list may have moved.
            for (final LiveDoc d : docs.values()) d.joining();
            listAt = -LIST_EVERY_MS;
            tried.keySet()
                .removeIf(k -> k.startsWith("make:") || k.startsWith("letgo:"));
        }

        @Override
        public void onSnapshot(final LiveClient.Snapshot s) {
            final LiveDoc d = docs.get(s.doc());
            if (d != null) d.snapshot(s);
        }

        @Override
        public void onOps(final LiveClient.OpsBatch batch) {
            final LiveDoc d = docs.get(batch.doc());
            if (d != null) d.ops(batch);
        }

        @Override
        public void onRefused(final String doc, final String cid, final String code, final String message) {
            final LiveDoc d = docs.get(doc);
            if (d != null) d.refused(cid, code);
            GtnhPlanner.LOG.warn("The live service refused an edit to {} ({}: {})", doc, code, message);
        }

        @Override
        public void onPresence(final LiveClient.Presence presence) {
            final LiveDoc d = docs.get(presence.doc());
            if (d != null) d.presence(presence);
        }

        @Override
        public void onPresent(final String doc, final List<LiveClient.Person> present) {
            final LiveDoc d = docs.get(doc);
            if (d != null) d.present(present);
        }

        @Override
        public void onMembers(final String doc, final List<LiveClient.Member> members) {
            final LiveDoc d = docs.get(doc);
            if (d != null) d.members(members);
        }

        @Override
        public void onShared(final String doc, final LiveClient.Member owner, final String name) {
            // The list at once: the plan joins the planner (no tab) and the Shared with me shelf has it.
            listAt = -LIST_EVERY_MS;
            if (doc.isEmpty() || doc.startsWith(accountId + ":") || !announced.add(doc)) return;
            // Shared again after it was left: it comes back with the list.
            if (letGo.remove(doc)) keepDevice();
            // Each time it is told: the player hears it once per time they are added.
            host.shared(
                owner.username()
                    .isEmpty() ? "Someone" : owner.username(),
                name.isEmpty() ? "a plan" : name,
                doc);
        }

        @Override
        public void onUnshared(final String doc) {
            listAt = -LIST_EVERY_MS;
            announced.remove(doc);
            final LiveDoc d = docs.get(doc);
            if (d != null && !d.creating()
                && !d.ownerId()
                    .equals(accountId))
                dropped(d, "is no longer shared with you");
        }

        @Override
        public void onKicked(final String doc, final String reason) {
            final LiveDoc d = docs.get(doc);
            if (d == null || d.creating()) return;
            switch (reason) {
                case "unshared" -> dropped(d, "is no longer shared with you");
                case "deleted" -> dropped(d, "was deleted");
                default -> {
                    // A join refused ("forbidden", "not_found"): not joined again, and never dropped on that alone;
                    // the account's list, asked now, says whether the plan is still the player's.
                    refusedRooms.add(doc);
                    listAt = -LIST_EVERY_MS;
                }
            }
        }
    }

    // endregion

    // region Every tick

    /**
     * Runs every client tick: the network's answers, what the service sent, every plan's edits sent and others' read in
     * (boards at rest only), rooms joined and left, the list and plans fetched, and what failed tried again.
     */
    public void tick() {
        for (Runnable r; (r = answers.poll()) != null;) {
            try {
                r.run();
            } catch (final RuntimeException e) {
                GtnhPlanner.LOG.warn("A cloud answer failed", e);
            }
        }
        if (client != null) {
            client.poll();
            if (client.ready()) lastLive = System.currentTimeMillis();
        }
        if (plan == null) return;
        if (host.inHand() != plan) {
            letGoOfPlans();
            return;
        }
        final long now = System.currentTimeMillis();
        for (final LiveDoc d : new ArrayList<>(docs.values())) {
            if (host.busy(d.graph())) continue;
            commit(d);
            if (d.behind()) catchUp(d);
        }
        rooms(now);
        for (final LiveDoc d : docs.values()) {
            if (client != null) d.send(client);
            d.keepCopy(false);
        }
        background(now);
    }

    /**
     * The rooms this game is in: the open plan's, and every plan with edits still to send (oldest first), at most
     * {@link #ROOMS}; one no longer wanted is left after a while.
     */
    private void rooms(final long now) {
        final List<LiveDoc> want = new ArrayList<>();
        final int at = plan == null ? -1 : plan.getActiveIndex();
        final LiveDoc open = at < 0 || at >= plan.getGraphs()
            .size() ? null
                : doc(
                    plan.getGraphs()
                        .get(at));
        if (open != null && !open.creating() && !refusedRooms.contains(open.key())) want.add(open);
        for (final LiveDoc d : docs.values())
            if (want.size() < ROOMS && d != open && !d.creating() && d.waiting() && !refusedRooms.contains(d.key()))
                want.add(d);
        final LiveClient c = connection();
        final Set<String> joined = c.joined();
        for (final LiveDoc d : want) {
            wanted.put(d.key(), now);
            if (!joined.contains(d.key())) {
                c.join(d.key());
                d.joining();
            }
        }
        for (final String key : joined) {
            final LiveDoc d = docs.get(key);
            final Long last = wanted.get(key);
            if (d != null && (d.waiting() || last != null && now - last < LEAVE_AFTER_MS)) continue;
            c.leave(key);
            wanted.remove(key);
            if (d != null) d.leftRoom();
        }
    }

    /** The list, plans' bodies, plans to make, plans let go of and names to tell the account: one call at a time. */
    private void background(final long now) {
        if (!busyCalls.isEmpty()) return;
        if (!listing && now - listAt >= LIST_EVERY_MS) {
            listing = true;
            listAt = now;
            call("list", () -> CloudApi.library(token.get(), accountId), this::listed, e -> listing = false);
            return;
        }
        for (final LiveDoc d : docs.values()) {
            if (d.creating() && gaveUpOn(d)) continue;
            if (d.creating() && due("make:" + d.key(), now)) {
                makeInAccount(d);
                return;
            }
        }
        for (final String key : letGo) {
            if (!due("letgo:" + key, now)) continue;
            tellLetGo(key);
            return;
        }
        for (final LiveDoc d : docs.values()) {
            if (d.known() || d.creating() || !due("fetch:" + d.key(), now)) continue;
            call(
                "fetch:" + d.key(),
                () -> CloudApi.plan(token.get(), d.ownerId(), d.designId()),
                f -> d.fetched(f.seq(), f.plan()),
                e -> {});
            return;
        }
        for (final LiveDoc d : docs.values()) {
            final String name = d.graph()
                .getName();
            if (d.creating() || name.isBlank() || name.equals(named.get(d.key())) || !due("name:" + d.key(), now))
                continue;
            rename(d, name);
            return;
        }
        if (!migrated && listFresh && plan != null) {
            migrated = true;
            migrate();
        }
    }

    private boolean due(final String what, final long now) {
        final Long at = tried.get(what);
        return at == null || now - at >= retryMs;
    }

    /** Runs a network call off the client thread; its answer, or its failure, comes back through {@link #tick}. */
    private <T> void call(final String what, final Call<T> work, final Consumer<T> done,
        final Consumer<Exception> failed) {
        busyCalls.add(what);
        tried.put(what, System.currentTimeMillis());
        net.execute(() -> {
            try {
                final T answer = work.run();
                answers.add(() -> {
                    busyCalls.remove(what);
                    tried.remove(what);
                    done.accept(answer);
                });
            } catch (final IOException | RuntimeException e) {
                answers.add(() -> {
                    busyCalls.remove(what);
                    if (e instanceof final CloudApi.Refused r && r.status == 401)
                        GtnhPlanner.LOG.warn("The account's sign-in ran out ({})", what);
                    else GtnhPlanner.LOG.debug("Cloud call {} failed", what, e);
                    failed.accept(e instanceof final IOException io ? io : new IOException(e));
                });
            }
        });
    }

    private interface Call<T> {

        T run() throws IOException;
    }

    /** The account's list came: plans new to this device join the planner (no tab), plans gone from it leave. */
    private void listed(final CloudApi.Library lib) {
        listing = false;
        library = lib;
        listFresh = true;
        // The list says which refused rooms are gone (dropped below) and which are worth joining again.
        refusedRooms.clear();
        cache.saveList(lib);
        if (plan == null) return;
        // The list's names: one renamed elsewhere since it was last heard is the board's now; a plan renamed here and
        // not yet in the list is renamed in the list.
        for (final CloudApi.Design d : lib.own()) if (!d.deleted()) listName(d.key(), d.name());
        for (final CloudApi.Design d : lib.shared()) listName(d.key(), d.name());
        final List<String> arrived = new ArrayList<>();
        for (final CloudApi.Design d : lib.own()) if (!d.deleted()) arrived.add(d.key());
        for (final CloudApi.Design d : lib.shared()) arrived.add(d.key());
        for (final String key : arrived) {
            if (docs.containsKey(key) || letGo.contains(key)) continue;
            final LiveDoc d = open(key, false);
            if (d == null) continue;
            d.graph()
                .setOpen(false);
            plan.getGraphs()
                .add(d.graph());
        }
        for (final LiveDoc d : new ArrayList<>(docs.values())) {
            if (d.creating() || listed(d.key())) continue;
            // Edits not yet in the account are never thrown away on the list's word alone: the room decides.
            if (d.waiting()) continue;
            dropped(
                d,
                d.ownerId()
                    .equals(accountId) ? "was deleted" : "is no longer shared with you");
        }
        keepDevice();
    }

    /**
     * The list's name for a plan: when it moved since last heard and the board still has the old one, the board takes
     * it (the website renames the list alone).
     */
    private void listName(final String key, final String listName) {
        final String was = named.put(key, listName);
        final LiveDoc d = docs.get(key);
        if (d != null && was != null
            && !was.equals(listName)
            && d.graph()
                .getName()
                .equals(was))
            d.listRenamed(listName);
    }

    /** Plans the account refused to create twice, by doc key, with the board's version then: kept here only. */
    private final Map<String, Long> madeLocal = new HashMap<>();
    /** Refusals of a plan's create, by doc key, since it was last rebuilt. */
    private final Map<String, Integer> makeRefusals = new HashMap<>();
    /** Plans the player was told of this session, by doc key. */
    private final Set<String> makeTold = new HashSet<>();

    /** Whether a plan the account refused is left alone: until the player edits it, which allows one more try. */
    private boolean gaveUpOn(final LiveDoc d) {
        final Long at = madeLocal.get(d.key());
        if (at == null) return false;
        d.commit();
        if (at == d.boardVersion()) return true;
        madeLocal.remove(d.key());
        makeRefusals.put(d.key(), 1);
        d.rebuildBody();
        tried.remove("make:" + d.key());
        return false;
    }

    /** The site refusing a create for good: any 4xx but sign-in, no access, too slow and too fast. */
    private static boolean refusal(final Exception e) {
        return e instanceof final CloudApi.Refused r && r.status >= 400
            && r.status < 500
            && r.status != 401
            && r.status != 403
            && r.status != 408
            && r.status != 429;
    }

    /** The account refused a plan's create: made again from the board once, then kept here only. */
    private void createRefused(final LiveDoc d, final Exception e) {
        GtnhPlanner.LOG.warn("The account refused to create the plan {}: {}", d.key(), e.getMessage());
        d.commit();
        if (makeRefusals.merge(d.key(), 1, Integer::sum) == 1) {
            d.rebuildBody();
            tried.remove("make:" + d.key());
            return;
        }
        madeLocal.put(d.key(), d.boardVersion());
        if (makeTold.add(d.key())) host.notice("This plan could not be saved to your account");
    }

    /** Creates a plan made here in the account, with the body it started as; its edits since follow once joined. */
    private void makeInAccount(final LiveDoc d) {
        final String now = Instant.now()
            .truncatedTo(ChronoUnit.MILLIS)
            .toString();
        final Graph g = d.graph();
        final CloudApi.Upsert body = new CloudApi.Upsert(
            g.getName()
                .isBlank() ? "Plan" : g.getName(),
            null,
            null,
            !g.isOpen(),
            false,
            null,
            now,
            now,
            now,
            d.confirmed());
        call("make:" + d.key(), () -> CloudApi.put(token.get(), d.designId(), body), put -> {
            // Live already: an earlier try went, its answer lost.
            d.made();
            named.put(d.key(), body.name());
            if (put.design() != null && library != null) {
                final List<CloudApi.Design> own = new ArrayList<>(library.own());
                // The answer names no owner: it is this account's, under the key the plan already has.
                own.add(
                    put.design()
                        .ownedBy(accountId));
                library = new CloudApi.Library(own, library.shared());
            }
            makeRefusals.remove(d.key());
            keepDevice();
        }, e -> { if (refusal(e)) createRefused(d, e); });
    }

    /** Tells the account a plan was let go of: an own plan deleted, a shared one left. */
    private void tellLetGo(final String key) {
        final String owner = key.substring(0, key.indexOf(':')), id = key.substring(key.indexOf(':') + 1);
        call("letgo:" + key, () -> {
            if (owner.equals(accountId)) CloudApi.delete(token.get(), id);
            else CloudApi.removeMember(token.get(), id, accountId, owner);
            return Boolean.TRUE;
        }, ok -> {
            letGo.remove(key);
            keepDevice();
        }, e -> {
            // Gone already: nothing left to tell.
            if (e instanceof final CloudApi.Refused r && (r.status == 404 || r.status == 403)) {
                letGo.remove(key);
                keepDevice();
            }
        });
    }

    /** Tells the account's list a plan's new name (its body has it already, as an edit). */
    private void rename(final LiveDoc d, final String name) {
        final CloudApi.Design was = design(d.key());
        final String now = Instant.now()
            .truncatedTo(ChronoUnit.MILLIS)
            .toString();
        final CloudApi.Upsert body = was == null ? new CloudApi.Upsert(
            name,
            null,
            null,
            !d.graph()
                .isOpen(),
            false,
            null,
            now,
            now,
            now,
            null)
            : new CloudApi.Upsert(
                name,
                was.icon(),
                was.folderId(),
                was.closed(),
                was.favorite(),
                was.order(),
                was.createdAt(),
                now,
                was.planUpdatedAt(),
                null);
        final String owner = d.ownerId()
            .equals(accountId) ? null : d.ownerId();
        call(
            "name:" + d.key(),
            () -> CloudApi.put(token.get(), d.designId(), owner, body),
            put -> { named.put(d.key(), name); },
            e -> {});
    }

    /**
     * The world's own plans to the account, once: backed up, keyed and saved first; then sent; each that went joins the
     * planner and the player hears it once. One that did not go stays in the world's save and goes next time.
     */
    private void migrate() {
        final Plan local = host.worldPlans();
        if (local == null || library == null) {
            releaseStandIn();
            return;
        }
        final Set<String> known = new HashSet<>();
        for (final CloudApi.Design d : library.own()) known.add(d.id());
        final Function<Graph, JsonObject> write = g -> CloudPlans.write(g, game(), null, null)
            .plan();
        if (Migration.prepareless(local, accountId, known)) {
            releaseStandIn();
            return;
        }
        if (!host.backUpWorld()) {
            GtnhPlanner.LOG.warn("The world's plans could not be backed up; they stay here for now");
            return;
        }
        final List<Migration.Item> items;
        try {
            items = Migration.prepare(local, accountId, known, write);
        } catch (final RuntimeException e) {
            GtnhPlanner.LOG.error("Could not ready the world's plans for the account", e);
            return;
        }
        if (items.isEmpty() || !host.saveWorld(local)) {
            releaseStandIn();
            return;
        }
        call("migrate", () -> Migration.upload(items, token.get(), null), result -> {
            int arrived = 0;
            for (final Migration.Item item : items) {
                if (!result.uploaded()
                    .contains(item.key()) || docs.containsKey(item.key()) || plan == null) continue;
                cache.saveDocAsIs(item.key(), 0, item.plan());
                final LiveDoc d = open(item.key(), false);
                if (d == null) continue;
                d.graph()
                    .setOpen(!item.closed());
                d.graph()
                    .setLastOpen(item.lastOpen());
                plan.getGraphs()
                    .add(d.graph());
                arrived++;
            }
            for (final Migration.Failure f : result.failed())
                GtnhPlanner.LOG.warn("'{}' did not go to the account yet: {}", f.name(), f.reason());
            if (arrived > 0) {
                replaceStandIn(local, items);
                host.notice("Your plans from this world are now in your account");
            }
            releaseStandIn();
            listAt = -LIST_EVERY_MS;
            keepDevice();
        }, e -> GtnhPlanner.LOG.warn("The world's plans did not go to the account yet", e));
    }

    // endregion

    // region Stopping

    /**
     * Lets go of the planner's plans (the world closed, or the account's session stops): everything kept on disk,
     * every room left. The plans come back from this device's copies on the next load.
     */
    private void letGoOfPlans() {
        for (final LiveDoc d : docs.values()) {
            d.keepCopy(true);
            if (client != null) client.leave(d.key());
        }
        docs.clear();
        wanted.clear();
        plan = null;
        game = null;
        host.stopped();
    }

    /** Ends the session (signed out, or another account signed in): kept on disk, disconnected. */
    public void stop() {
        if (plan != null && host.inHand() == plan) save(plan);
        letGoOfPlans();
        if (client != null) client.stop();
        client = null;
        net.shutdownNow();
        cache.close();
        if (current == this) current = null;
    }

    /** Waits for this device's copies to be on disk (the game is closing). */
    public void flush() {
        cache.flush();
    }

    // endregion

    private static JsonArray array(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }
}
