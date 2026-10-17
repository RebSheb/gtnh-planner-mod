package com.gtnhplanner.ui.live;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.gtnhplanner.cloud.map.PlanIds;
import com.gtnhplanner.cloud.net.LiveClient;
import com.gtnhplanner.cloud.session.CloudSession;
import com.gtnhplanner.cloud.session.LiveDoc;

/**
 * The other people in the open plan, as the board shows them (docs/design/cloud-live.md, presence): each connection's
 * cursor, gliding to where it was last heard and fading once it has been quiet a while; what it has selected, as the
 * board's ids; and one face per account for the top bar. Fed by the open plan's room ({@link LiveDoc}), and in a dev
 * game by stand-ins too ({@code call 'cloud?fake=N'}, dev/DevPeople). This account's own other connections (a browser,
 * another game) are left out, as the website leaves them out. Client thread.
 */
public final class Peers {

    /** How long a cursor stays after it was last heard, then how long it takes to fade away. */
    public static final long QUIET_MS = 9_000, FADE_MS = 1_500;
    /** About how long a cursor takes to glide to where it was last heard: a little less than the time between two. */
    private static final float GLIDE_SECONDS = 0.04f;
    /** At most this many selected things are read from one person, the service's own limit. */
    private static final int MAX_SELECTED = 500;

    /** One connection in the plan: someone's game or browser. */
    public static final class Peer {

        public final String conn, who;
        private String username = "", client = "";
        private int colour;
        private boolean cursor;
        /** Where it was last heard and where it is drawn now, in board units. */
        private float tx, ty, x, y;
        private long heardAt;
        private List<UUID> selected = List.of();

        Peer(final String conn, final String who) {
            this.conn = conn;
            this.who = who;
        }

        public String username() {
            return username;
        }

        /** "game" or "web". */
        public String client() {
            return client;
        }

        public int colour() {
            return colour;
        }

        /** Whether its cursor is on the board. */
        public boolean hasCursor() {
            return cursor;
        }

        public float x() {
            return x;
        }

        public float y() {
            return y;
        }

        /** How much of its cursor shows: 1 lately heard, falling to 0 once quiet for {@link #QUIET_MS}. */
        public float shown(final long now) {
            final long quiet = now - heardAt;
            if (quiet <= QUIET_MS) return 1;
            return Math.max(0, 1 - (quiet - QUIET_MS) / (float) FADE_MS);
        }

        /** What it has selected, as the board's ids, in its order. */
        public List<UUID> selected() {
            return selected;
        }
    }

    /** An account in the plan, as its face shows it: in game, in a browser, or both. */
    public record Face(String id, String username, int colour, boolean inGame, boolean inBrowser) {}

    private final Map<String, Peer> byConn = new LinkedHashMap<>();
    @Nullable
    private LiveDoc doc;
    private final Consumer<LiveClient.Presence> onPresence = this::heard;
    private final Runnable onPeople = this::people;
    private List<Face> faces = List.of();
    /** Whether any peer has a cursor or a selection to draw: none, and the board draws nothing for them. */
    private boolean anything;
    private long steppedAt;

    // region Following the open plan

    /** Listens to the open plan's room (null: none, a plan on this computer): its people, and where they look. */
    public void follow(@Nullable final LiveDoc next) {
        if (next == doc) return;
        if (doc != null) {
            doc.removePresenceListener(onPresence);
            doc.removePeopleListener(onPeople);
        }
        byConn.values()
            .removeIf(p -> !fake(p));
        doc = next;
        if (doc != null) {
            doc.addPresenceListener(onPresence);
            doc.addPeopleListener(onPeople);
        }
        people();
    }

    /** Stops listening (the board closed). */
    public void stop() {
        follow(null);
    }

    /** Who is in the room changed: anyone gone takes their cursor and selection along. */
    private void people() {
        final Set<String> here = new HashSet<>();
        if (doc != null) {
            final String me = me();
            for (final LiveClient.Person p : doc.present()) {
                if (p.id()
                    .equals(me)) continue;
                here.add(p.conn());
                final Peer peer = byConn.computeIfAbsent(p.conn(), c -> new Peer(c, p.id()));
                peer.username = p.username();
                peer.client = p.client();
                peer.colour = colour(p.color(), p.id());
            }
        }
        byConn.values()
            .removeIf(p -> !fake(p) && !here.contains(p.conn));
        changed();
    }

    /** The account signed in, whose own connections are not drawn. */
    @Nullable
    private static String me() {
        final CloudSession s = CloudSession.current();
        return s == null ? null : s.accountId();
    }

    /** A presence frame: where someone's cursor is (null off the board) and what they have selected. */
    private void heard(final LiveClient.Presence p) {
        final Peer peer = byConn.get(p.conn());
        // As the website: someone the room has not said is here yet is not drawn.
        if (peer == null) return;
        final int[] at = p.cursor() == null ? null : LiveDoc.onBoard(p.cursor());
        place(peer, at, ids(p.sel()));
    }

    /** The board's ids of a presence frame's selection (the plan's ids). */
    private static List<UUID> ids(final JsonArray sel) {
        final List<UUID> out = new ArrayList<>();
        for (final JsonElement e : sel) {
            if (out.size() >= MAX_SELECTED) break;
            if (e.isJsonPrimitive()) out.add(PlanIds.uuid(e.getAsString()));
        }
        return out;
    }

    private void place(final Peer peer, @Nullable final int[] at, final List<UUID> selected) {
        peer.heardAt = System.currentTimeMillis();
        if (at == null) peer.cursor = false;
        else {
            // Back on the board: it starts where it is, not gliding in from where it left.
            if (!peer.cursor) {
                peer.x = at[0];
                peer.y = at[1];
            }
            peer.cursor = true;
            peer.tx = at[0];
            peer.ty = at[1];
        }
        if (!selected.equals(peer.selected)) peer.selected = List.copyOf(selected);
        changed();
    }

    private void changed() {
        boolean any = false;
        for (final Peer p : byConn.values()) any |= p.cursor || !p.selected.isEmpty();
        anything = any;
        final Map<String, Face> byAccount = new LinkedHashMap<>();
        for (final Peer p : byConn.values()) {
            final Face had = byAccount.get(p.who);
            final boolean game = "game".equals(p.client), web = !game;
            byAccount.put(
                p.who,
                had == null ? new Face(p.who, p.username, p.colour, game, web)
                    : new Face(had.id(), had.username(), had.colour(), had.inGame() || game, had.inBrowser() || web));
        }
        final List<Face> next = List.copyOf(byAccount.values());
        if (!next.equals(faces)) faces = next;
    }

    // endregion

    // region What the board draws

    /** Nothing to draw: nobody here, or nobody pointing at or holding anything. */
    public boolean quiet() {
        return !anything;
    }

    public Collection<Peer> all() {
        return Collections.unmodifiableCollection(byConn.values());
    }

    /** One face per account, in the order they came. */
    public List<Face> faces() {
        return faces;
    }

    /** Moves every cursor on toward where it was last heard; the dev game's stand-ins move too. Once a frame. */
    public void step() {
        final long now = System.nanoTime();
        final float seconds = steppedAt == 0 ? 0 : Math.min(0.25f, (now - steppedAt) / 1e9f);
        steppedAt = now;
        if (com.gtnhplanner.dev.DevPeople.wanted() || hasFakes()) com.gtnhplanner.dev.DevPeople.drive(this);
        if (!anything) return;
        final float k = 1 - (float) Math.exp(-seconds / GLIDE_SECONDS);
        for (final Peer p : byConn.values()) {
            if (!p.cursor) continue;
            p.x += (p.tx - p.x) * k;
            p.y += (p.ty - p.y) * k;
            if (Math.abs(p.tx - p.x) < 0.05f && Math.abs(p.ty - p.y) < 0.05f) {
                p.x = p.tx;
                p.y = p.ty;
            }
        }
    }

    // endregion

    // region Stand-ins, for the dev game

    private static final String FAKE = "fake:";

    private static boolean fake(final Peer p) {
        return p.conn.startsWith(FAKE);
    }

    private boolean hasFakes() {
        for (final Peer p : byConn.values()) if (fake(p)) return true;
        return false;
    }

    /** A stand-in heard from: its name, client, cursor (null off the board) and selection. Dev only. */
    public void fakeHeard(final int index, final String name, final String client, @Nullable final int[] cursor,
        final List<UUID> selected) {
        final String id = "fake-" + index;
        final Peer p = byConn.computeIfAbsent(FAKE + index, c -> new Peer(c, id));
        p.username = name;
        p.client = client;
        p.colour = colourOf(id);
        place(p, cursor, selected);
    }

    /** Whether a stand-in is here, by its number. */
    public boolean hasFake(final int index) {
        return byConn.containsKey(FAKE + index);
    }

    /** The stand-ins from {@code from} on go. */
    public void dropFakes(final int from) {
        byConn.values()
            .removeIf(p -> fake(p) && Integer.parseInt(p.conn.substring(FAKE.length())) >= from);
        changed();
    }

    // endregion

    // region Colours

    /** The website's twelve, one per account (its protocol.ts LIVE_COLORS): readable on the dark board. */
    private static final int[] COLOURS = { 0xFFE5484D, 0xFFF76B15, 0xFFFFC53D, 0xFF46A758, 0xFF12A594, 0xFF00A2C7,
        0xFF0090FF, 0xFF3E63DD, 0xFF8E4EC6, 0xFFD6409F, 0xFFA18072, 0xFF868E2B };

    /** An account's colour as the service gives it: FNV-1a over its id, the same everywhere (protocol.ts colorFor). */
    public static int colourOf(final String accountId) {
        int hash = 0x811c9dc5;
        for (int i = 0; i < accountId.length(); i++) {
            hash ^= accountId.charAt(i);
            hash *= 0x01000193;
        }
        return COLOURS[Integer.remainderUnsigned(hash, COLOURS.length)];
    }

    /** A colour the service sent ({@code #rrggbb} or {@code #rgb}), else the account's own. */
    static int colour(@Nullable final String css, final String accountId) {
        if (css != null && css.startsWith("#")) {
            try {
                final String hex = css.substring(1);
                if (hex.length() == 6) return 0xFF000000 | Integer.parseInt(hex, 16);
                if (hex.length() == 3) {
                    final int v = Integer.parseInt(hex, 16);
                    final int r = v >> 8 & 0xF, g = v >> 4 & 0xF, b = v & 0xF;
                    return 0xFF000000 | r * 17 << 16 | g * 17 << 8 | b * 17;
                }
            } catch (final NumberFormatException ignored) {}
        }
        return colourOf(accountId);
    }

    // endregion
}
