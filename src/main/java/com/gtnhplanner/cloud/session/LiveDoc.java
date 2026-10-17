package com.gtnhplanner.cloud.session;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.cloud.map.CloudPlans;
import com.gtnhplanner.cloud.net.LiveClient;
import com.gtnhplanner.cloud.store.CloudCache;
import com.gtnhplanner.cloud.sync.LiveOp;
import com.gtnhplanner.cloud.sync.LiveOps;
import com.gtnhplanner.cloud.sync.LiveState;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.importer.ImportReport;
import com.gtnhplanner.library.PlanExport;

/**
 * One cloud plan on this device as the board holds it: its live copy ({@link LiveState}: the plan as the service last
 * confirmed it, with this player's edits not yet echoed on top), the board read from it and the plan that board stands
 * for ({@link CloudPlans.Read}), and its room.
 * <ul>
 * <li>A change the board commits is written against the plan the board stands for, so what it sends is only what the
 * player did, never a thing someone else changed meanwhile; the batch lands on the live copy, which merges it.</li>
 * <li>Each batch is on disk ({@link CloudCache#addPending}) before it is sent, and stays there until a copy of the plan
 * kept on disk holds it.</li>
 * <li>Batches from others land on the live copy at once and are read into the board when the session asks
 * ({@link #catchUp}), reading only what they touched.</li>
 * <li>Undo and redo apply the inverses of this player's own edits, as new edits.</li>
 * </ul>
 * Client thread only.
 */
public final class LiveDoc {

    /** The most bytes (UTF-8 JSON) of one whole ops frame: the service takes 256 KB a batch, this leaves headroom. */
    public static final int MAX_FRAME_BYTES = 240 * 1024;
    /** How often, at most, the confirmed plan is written to this device's copy while it changes. */
    static final long SAVE_EVERY_MS = 2_000;
    /** A batch the service called too fast is sent again after this long. */
    static final long RATE_RETRY_MS = 3_000;

    private final CloudSession session;
    private final String key;
    private LiveState state;
    /**
     * Whether the live copy stands on the plan as the account holds it (a snapshot, the account's copy, this device's
     * copy of either, or a plan made here): false for a plan known only by name so far, its body still to come.
     */
    private boolean known;
    /** Made on this device and not yet in the account: not joined until it is. */
    private boolean creating;
    private CloudPlans.Read read;
    /** The board's version when it was last written or read. */
    private long synced;
    /** Batches from others have landed that the board has not read yet. */
    private boolean behind;
    /** A snapshot came since the room was last joined: batches sent now are heard, and ones heard count. */
    private boolean inRoom;
    /** Nothing is sent before this time (millis), after the service said a batch came too fast. */
    private long holdUntil;

    private String role = "";
    @Nullable
    private LiveClient.Member owner;
    private List<LiveClient.Member> members = List.of();
    private List<LiveClient.Person> present = List.of();
    private final List<Consumer<LiveClient.Presence>> presenceListeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> peopleListeners = new CopyOnWriteArrayList<>();

    /** Batches being written to this device's copy, by cid: each is sent once its write is down. */
    private final Map<String, CompletableFuture<Void>> keeping = new HashMap<>();
    /** Pending batches not sent on this connection yet, oldest first. */
    private final ArrayDeque<String> unsent = new ArrayDeque<>();
    /** Own batches the service applied, and its seq for each: they leave the disk once a kept copy holds them. */
    private final List<Object[]> applied = new ArrayList<>();
    private long savedSeq = -1, savedAt;

    private LiveDoc(final CloudSession session, final String key, final LiveState state, final CloudPlans.Read read,
        final boolean known) {
        this.session = session;
        this.key = key;
        this.state = state;
        this.read = read;
        this.known = known;
        this.synced = read.graph()
            .version();
        read.graph()
            .setCloudKey(key);
    }

    /** A plan from this device's copy: the plan as last confirmed and the batches still waiting, read as a board. */
    static LiveDoc fromCopy(final CloudSession session, final String key, final CloudCache.Doc doc,
        final List<CloudCache.Batch> pending) {
        final LiveState state = restored(key, doc.seq(), doc.plan(), pending);
        final LiveDoc d = new LiveDoc(session, key, state, CloudPlans.read(state.view(), session.game()), true);
        d.savedSeq = doc.seq();
        for (final LiveState.Batch b : state.pending()) d.unsent.add(b.cid());
        return d;
    }

    /** A live copy at {@code seq} with the batches still waiting on disk on top (undo starts afresh). */
    private static LiveState restored(final String key, final long seq, final JsonObject plan,
        final List<CloudCache.Batch> pending) {
        final JsonObject saved = new JsonObject();
        saved.addProperty("seq", seq);
        saved.add("confirmed", plan);
        final JsonArray batches = new JsonArray();
        for (final CloudCache.Batch b : pending) {
            final JsonObject o = new JsonObject();
            o.addProperty("cid", b.cid());
            o.add("ops", b.ops());
            o.add("inverse", new JsonArray());
            batches.add(o);
        }
        saved.add("pending", batches);
        final LiveState state = LiveState.restore(saved);
        if (state != null) return state;
        GtnhPlanner.LOG.warn("The waiting edits of {} did not read; it opens as last confirmed", key);
        return new LiveState(seq, plan);
    }

    /**
     * A plan known only by its name so far: an empty board, which can be edited (its edits wait, on disk too) until the
     * plan's body comes and they go on top of it.
     */
    static LiveDoc unknown(final CloudSession session, final String key, final String name,
        final List<CloudCache.Batch> pending) {
        final Graph g = new Graph(name);
        final CloudPlans.Written w = CloudPlans.write(g, session.game(), null, null);
        final LiveState state = restored(key, 0, w.plan(), pending);
        final LiveDoc d = new LiveDoc(
            session,
            key,
            state,
            pending.isEmpty() ? new CloudPlans.Read(g, w.basis(), new ImportReport(), w.plan())
                : CloudPlans.read(state.view(), session.game()),
            false);
        for (final LiveState.Batch b : state.pending()) d.unsent.add(b.cid());
        return d;
    }

    /** A plan made on this device from a board: the plan it starts as, to be created in the account. */
    static LiveDoc made(final CloudSession session, final String key, final Graph g) {
        g.setCloudKey(key);
        final CloudPlans.Written w = CloudPlans.write(g, session.game(), null, null);
        final LiveState state = new LiveState(0, w.plan());
        final LiveDoc d = new LiveDoc(
            session,
            key,
            state,
            new CloudPlans.Read(g, w.basis(), new ImportReport(), w.plan()),
            true);
        d.creating = true;
        return d;
    }

    // region What it is

    /** The doc key, {@code <ownerId>:<designId>}. */
    public String key() {
        return key;
    }

    public String ownerId() {
        return key.substring(0, key.indexOf(':'));
    }

    public String designId() {
        return key.substring(key.indexOf(':') + 1);
    }

    /** The board: the plan's slot graph. */
    public Graph graph() {
        return read.graph();
    }

    /** The plan as the board shows it once caught up: confirmed, with this player's waiting edits on top. */
    public JsonObject view() {
        return state.view();
    }

    public long seq() {
        return state.seq();
    }

    public boolean known() {
        return known;
    }

    public boolean creating() {
        return creating;
    }

    /** A plan this device made that the account does not have yet (as its copy on disk says). */
    void markMaking() {
        creating = true;
    }

    /** The board's version, which moves with every edit. */
    long boardVersion() {
        return read.graph()
            .version();
    }

    /**
     * The plan as the board is now, as the body to create it with (the account refused the first): the live copy
     * starts over on it, the waiting batches are in it already.
     */
    void rebuildBody() {
        for (final LiveState.Batch b : state.pending()) session.cache()
            .ack(key, b.cid());
        unsent.clear();
        final Graph g = read.graph();
        final CloudPlans.Written w = CloudPlans.write(g, session.game(), null, null);
        state = new LiveState(0, w.plan());
        read = new CloudPlans.Read(g, w.basis(), new ImportReport(), w.plan());
        synced = g.version();
        session.cache()
            .saveDocAsIs(key, 0, w.plan());
    }

    /** The account has it now: its room can be joined, and its waiting edits go from the snapshot. */
    void made() {
        creating = false;
    }

    /** Whether this player has edits the service has not applied yet. */
    public boolean waiting() {
        return !state.pending()
            .isEmpty();
    }

    /** Whether the board is behind the live copy: batches from others it has not read yet. */
    public boolean behind() {
        return behind;
    }

    /** In the room on this connection (a snapshot came). */
    public boolean inRoom() {
        return inRoom;
    }

    // endregion

    // region Who is in it (for presence and sharing)

    /** "owner" or "editor", as the room's snapshot said; empty before one came. */
    public String role() {
        return role;
    }

    /** Whose plan it is, as the room said; null before a snapshot came. */
    @Nullable
    public LiveClient.Member owner() {
        return owner;
    }

    /** Who else may edit it. */
    public List<LiveClient.Member> members() {
        return members;
    }

    /** Who has it open now, this player's own connections included. */
    public List<LiveClient.Person> present() {
        return present;
    }

    /** Hears every presence frame for this plan: someone's cursor, selection or view. Client thread. */
    public void addPresenceListener(final Consumer<LiveClient.Presence> l) {
        presenceListeners.add(l);
    }

    public void removePresenceListener(final Consumer<LiveClient.Presence> l) {
        presenceListeners.remove(l);
    }

    /** Hears when who is in it, or who may edit it, changes. Client thread. */
    public void addPeopleListener(final Runnable l) {
        peopleListeners.add(l);
    }

    public void removePeopleListener(final Runnable l) {
        peopleListeners.remove(l);
    }

    /**
     * Tells the room where this player is: the cursor on the board (board coordinates; null off the board), the ids
     * selected (the plan's ids: {@link #planId}), and the view when it is to be shared. Throttled by the client.
     */
    public void sendPresence(@Nullable final int[] cursor, final List<String> selected,
        @Nullable final JsonObject view) {
        final LiveClient c = session.client();
        if (c == null || !inRoom) return;
        c.sendPresence(key, cursor == null ? null : at(cursor[0], cursor[1]), selected, view);
    }

    /** A board point as the plan's coordinates, as presence and the website use them. */
    public static JsonObject at(final int boardX, final int boardY) {
        return PlanExport.position(boardX, boardY);
    }

    /** A point in the plan's coordinates on the board: {x, y}. */
    public static int[] onBoard(final JsonObject at) {
        final double x = at.has("x") ? at.get("x")
            .getAsDouble() : 0, y = at.has("y")
                ? at.get("y")
                    .getAsDouble()
                : 0;
        return new int[] { PlanExport.gameX(x), PlanExport.gameY(y) };
    }

    /**
     * The plan's id of a board thing ({@code kind} {@code node}, {@code drawer}, {@code edge}, {@code note} or
     * {@code pocket}), as presence names a selection; {@code cloud.map.PlanIds.uuid} turns one back into the board's.
     */
    public String planId(final String kind, final java.util.UUID boardId) {
        return read.basis()
            .planId(kind, boardId);
    }

    private void peopleChanged() {
        for (final Runnable l : peopleListeners) l.run();
    }

    // endregion

    // region The board's edits

    /**
     * Sends what the board changed since it was last written or read: written against the plan the board stands for,
     * so only what the player did goes, cut into batches the service takes; each kept on disk, then sent. True when
     * anything went.
     */
    /**
     * What a frame carries besides its ops, measured on one with none (a long cid and base stand in for any), and a
     * margin; the ops then fit what is left of {@link #MAX_FRAME_BYTES}.
     */
    int maxOpBytes() {
        final JsonObject f = new JsonObject();
        f.addProperty("t", "ops");
        f.addProperty("doc", key);
        f.addProperty("base", Long.MAX_VALUE);
        f.addProperty("cid", "0123456789abcdef0123456789abcdef0123456789abcdef");
        f.add("ops", new JsonArray());
        return MAX_FRAME_BYTES - f.toString()
            .getBytes(java.nio.charset.StandardCharsets.UTF_8).length - 256;
    }

    /**
     * Tells the player, and the log, of ops too big to send (a huge card): they are kept out of the pending batches.
     */
    private void warnTooBig() {
        final List<LiveOp> big = state.takeTooBig();
        if (big.isEmpty()) return;
        for (final LiveOp op : big) GtnhPlanner.LOG.warn(
            "An edit to {} is too big for the live service ({} bytes of {} allowed) and was not sent: {} {}",
            key,
            LiveState.size(op),
            maxOpBytes(),
            op.toJson()
                .get("o"),
            op.toJson()
                .has("id")
                    ? op.toJson()
                        .get("id")
                    : "");
        session.notice("A card is too big to share and stays on this device only");
    }

    boolean commit() {
        final Graph g = read.graph();
        if (g.version() == synced) return false;
        final CloudPlans.Written w = CloudPlans.write(g, session.game(), read.plan(), read.basis());
        final List<LiveOp> ops = CloudPlans.diff(read.plan(), w.plan());
        synced = g.version();
        final boolean caughtUp = !behind;
        final List<LiveState.Batch> batches = ops.isEmpty() ? List.of() : state.local(ops, maxOpBytes());
        warnTooBig();
        // Caught up, the board is the live copy: what follows reads against its objects, sharing the most.
        read = read.after(w, caughtUp && !batches.isEmpty() ? state.view() : w.plan());
        for (final LiveState.Batch b : batches) keep(b);
        return !batches.isEmpty();
    }

    /** The slot holds another graph than the one read (something put it there): it is the board now. */
    void rebind(final Graph g) {
        read = new CloudPlans.Read(g, read.basis(), read.report(), read.plan());
        g.setCloudKey(key);
        synced = Long.MIN_VALUE;
    }

    /**
     * Reads what landed on the live copy into the board, the board's own changes sent first: a new graph for the slot,
     * the cards nobody touched carried over from the old one (which must not be used after). Null when there was
     * nothing to read.
     */
    @Nullable
    Graph catchUp() {
        if (!behind) return null;
        commit();
        final Graph old = read.graph();
        final CloudPlans.Read next = CloudPlans.read(state.view(), session.game(), read);
        next.graph()
            .takeDeviceStateOf(old);
        // The name is read only when the plan's own name moved: the website renames a plan in the account's list and
        // leaves the plan's name as it was, and the board's name (the list's) must not go back to that.
        if (java.util.Objects.equals(planName(state.view()), planName(read.plan()))) next.graph()
            .setName(old.getName());
        read = next;
        synced = next.graph()
            .version();
        behind = false;
        return next.graph();
    }

    @javax.annotation.Nullable
    static String planName(final JsonObject plan) {
        return plan.has("name") && plan.get("name")
            .isJsonPrimitive() ? plan.get("name")
                .getAsString() : null;
    }

    /**
     * The plan was renamed in the account's list from elsewhere (the website renames the list, not the plan): the
     * board takes the new name, unless the player's own rename is still on its way. Taking it is an edit, so the
     * plan's name follows.
     */
    void listRenamed(final String listName) {
        final Graph g = read.graph();
        if (listName.isBlank() || g.getName()
            .equals(listName)) return;
        // A rename of the player's own still waiting to go: it wins, and the list hears it.
        if (!java.util.Objects.equals(planName(state.view()), planName(state.confirmed()))) return;
        g.setName(listName);
    }

    public boolean canUndo() {
        return state.canUndo();
    }

    public boolean canRedo() {
        return state.canRedo();
    }

    /** Undoes this player's last own edit (as a new edit); false when there is none. Read in by the next catch-up. */
    boolean undo() {
        commit();
        final boolean did = applyOwn(state.undo(maxOpBytes()));
        warnTooBig();
        return did;
    }

    boolean redo() {
        commit();
        final boolean did = applyOwn(state.redo(maxOpBytes()));
        warnTooBig();
        return did;
    }

    private boolean applyOwn(final List<LiveState.Batch> batches) {
        if (batches.isEmpty()) return false;
        for (final LiveState.Batch b : batches) keep(b);
        behind = true;
        return true;
    }

    private void keep(final LiveState.Batch b) {
        keeping.put(
            b.cid(),
            session.cache()
                .addPending(key, new CloudCache.Batch(b.cid(), state.seq(), LiveOp.toJson(b.ops()))));
        unsent.add(b.cid());
    }

    /** Sends the waiting batches whose copies are on disk, oldest first, while in the room. */
    void send(final LiveClient client) {
        if (!inRoom || !client.ready() || System.currentTimeMillis() < holdUntil) return;
        while (!unsent.isEmpty()) {
            final String cid = unsent.peekFirst();
            final LiveState.Batch b = pendingBatch(cid);
            if (b == null) {
                unsent.pollFirst();
                continue;
            }
            final CompletableFuture<Void> k = keeping.get(cid);
            if (k != null && !k.isDone()) return;
            if (k != null && k.isCompletedExceptionally())
                GtnhPlanner.LOG.warn("An edit to {} could not be kept on disk; it is sent anyway", key);
            if (!client.sendOps(key, state.seq(), cid, LiveOp.toJson(b.ops()))) return;
            keeping.remove(cid);
            unsent.pollFirst();
        }
    }

    @Nullable
    private LiveState.Batch pendingBatch(final String cid) {
        for (final LiveState.Batch b : state.pending()) if (b.cid()
            .equals(cid)) return b;
        return null;
    }

    // endregion

    // region What the service says

    /** Joined (again): the next snapshot is where it stands. */
    void joining() {
        inRoom = false;
    }

    /**
     * Out of the room on this connection (a drop, a leave): it joins again from a snapshot. Who was in it is no longer
     * known, so no faces show until the room says again.
     */
    void leftRoom() {
        inRoom = false;
        if (!present.isEmpty()) {
            present = List.of();
            peopleChanged();
        }
    }

    /**
     * The room's whole plan: the live copy stands on it, every waiting batch goes again (the service applies each
     * once), the board reads it, and it is kept on disk.
     */
    void snapshot(final LiveClient.Snapshot s) {
        role = s.role();
        owner = s.owner();
        members = List.copyOf(s.members());
        present = List.copyOf(s.present());
        final List<LiveState.Batch> again = state.resync(s.seq(), s.plan());
        known = true;
        creating = false;
        inRoom = true;
        unsent.clear();
        for (final LiveState.Batch b : again) unsent.add(b.cid());
        behind = true;
        keepCopy(true);
        peopleChanged();
    }

    /** The plan's body from the account's copy (a plan known only by name until now): the board reads it. */
    void fetched(final long seq, final JsonObject plan) {
        if (known) return;
        state.resync(seq, plan);
        known = true;
        behind = true;
        keepCopy(true);
    }

    /** A batch the room applied: someone else's lands on the live copy; this player's own leaves the waiting ones. */
    void ops(final LiveClient.OpsBatch b) {
        if (!inRoom) return;
        final LiveOps.Read r = LiveOps.read(b.ops());
        final LiveState.Batch sent = b.cid()
            .isEmpty() ? null : pendingBatch(b.cid());
        if (r.ops() == null) {
            GtnhPlanner.LOG.warn("A batch for {} did not read ({}); joining again", key, r.error());
            session.rejoin(this);
            return;
        }
        final LiveState.Received got = state.receive(
            b.seq(),
            b.cid()
                .isEmpty() ? null : b.cid(),
            r.ops());
        if (got.own()) {
            applied.add(new Object[] { b.cid(), b.seq() });
            // The service may have corrected what it was sent (the schema's output, a task's createdBy): the echo is
            // what it kept, so the board reads it.
            if (sent != null && b.ops()
                .size() > 0
                && !LiveOp.toJson(sent.ops())
                    .equals(b.ops()))
                behind = true;
        } else behind = true;
        if (got.gap()) session.rejoin(this);
    }

    /**
     * The room refused one of this player's batches. Too many too fast: it stays pending and goes again after a
     * moment. Anything else (too big, a bad op): it never happened, and the board goes back to the live copy.
     */
    void refused(final String cid, final String code) {
        if ("rate".equals(code)) {
            if (pendingBatch(cid) == null) return;
            holdUntil = System.currentTimeMillis() + RATE_RETRY_MS;
            // Back in the line in the order the batches were made.
            final java.util.Set<String> again = new java.util.HashSet<>(unsent);
            again.add(cid);
            unsent.clear();
            for (final LiveState.Batch p : state.pending()) if (again.contains(p.cid())) unsent.add(p.cid());
            return;
        }
        if (!state.refuse(cid)) return;
        keeping.remove(cid);
        unsent.remove(cid);
        session.cache()
            .ack(key, cid);
        behind = true;
        session.notice("An edit could not be saved");
    }

    void presence(final LiveClient.Presence p) {
        for (final Consumer<LiveClient.Presence> l : presenceListeners) l.accept(p);
    }

    void present(final List<LiveClient.Person> people) {
        present = List.copyOf(people);
        peopleChanged();
    }

    void members(final List<LiveClient.Member> list) {
        members = List.copyOf(list);
        peopleChanged();
    }

    // endregion

    // region This device's copy

    /**
     * Keeps the confirmed plan on disk when it moved (at most every {@link #SAVE_EVERY_MS} unless {@code now}), then
     * lets go of the own batches it holds: a crash never leaves a batch neither in the copy nor waiting.
     */
    void keepCopy(final boolean now) {
        // A plan known by name only has nothing confirmed to keep: its stand-in body is not the plan.
        if (!known) return;
        final long seq = state.seq();
        if (seq != savedSeq && (now || System.currentTimeMillis() - savedAt >= SAVE_EVERY_MS)) {
            session.cache()
                .saveDocAsIs(key, seq, state.confirmed());
            savedSeq = seq;
            savedAt = System.currentTimeMillis();
        }
        for (final Iterator<Object[]> it = applied.iterator(); it.hasNext();) {
            final Object[] a = it.next();
            if ((Long) a[1] > savedSeq) continue;
            session.cache()
                .ack(key, (String) a[0]);
            it.remove();
        }
    }

    /** The live copy as it stands, for a copy made by "Make a copy" or anything else that needs the JSON. */
    JsonObject confirmed() {
        return state.confirmed();
    }

    // endregion
}
