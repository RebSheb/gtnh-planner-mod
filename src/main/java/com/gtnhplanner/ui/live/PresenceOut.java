package com.gtnhplanner.ui.live;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;
import com.gtnhplanner.cloud.session.LiveDoc;
import com.gtnhplanner.data.flowchart.Graph;

/**
 * Where this player is on the open cloud plan, told to the others in it: the cursor in the plan's coordinates while the
 * mouse is on the board (none when it leaves the board or the planner closes), the plan's ids of what is selected, and
 * the view (the plan's point in the middle of the board, and the zoom). At most ten a second, and only when something
 * changed. The board samples the mouse as it draws; the screen's update says when the board is not showing. Client
 * thread.
 */
public final class PresenceOut {

    /** The least time between two, as the website sends them. */
    private static final long GAP_MS = 100;
    private static final int MAX_SELECTED = 500;

    /** The plan the last went to, and whether it was in its room then (joined again: told again). */
    @Nullable
    private LiveDoc doc;
    private boolean inRoom;
    /** What was last sent: nothing yet when {@link #sentAt} is 0. */
    private boolean sentCursor;
    private int sentX, sentY;
    private List<UUID> sentSelection = List.of();
    private float sentViewX = Float.NaN, sentViewY, sentZoom;
    private long sentAt;

    /** The mouse as last sampled: on the board or not, and the board point under it. */
    private boolean on;
    private int x, y;
    private float viewX = Float.NaN, viewY, zoom;

    /** The mouse on the board at board point ({@code x}, {@code y}), or off it; the view's middle and zoom. */
    public void sample(final boolean onBoard, final int x, final int y, final float viewX, final float viewY,
        final float zoom) {
        this.on = onBoard;
        this.x = x;
        this.y = y;
        this.viewX = viewX;
        this.viewY = viewY;
        this.zoom = zoom;
    }

    /** The board is not showing (the task board or the library is up): no cursor. */
    public void off() {
        on = false;
    }

    /**
     * Sends what changed, when the last went long enough ago: to {@code live} (null: the open plan is on this computer
     * only). A plan left for another is told the player has gone.
     */
    public void update(@Nullable final LiveDoc live, final Graph graph, final Set<UUID> selection) {
        if (live != doc) {
            leave();
            doc = live;
        }
        if (doc == null) return;
        final long now = System.currentTimeMillis();
        if (now - sentAt < GAP_MS) return;
        final boolean room = doc.inRoom(), rejoined = room && !inRoom;
        inRoom = room;
        if (!room) return;
        final boolean changed = rejoined || sentAt == 0
            || on != sentCursor
            || on && (x != sentX || y != sentY)
            || viewX != sentViewX
            || viewY != sentViewY
            || zoom != sentZoom
            || !sameSelection(selection);
        if (!changed) return;
        sentCursor = on;
        sentX = x;
        sentY = y;
        sentViewX = viewX;
        sentViewY = viewY;
        sentZoom = zoom;
        sentSelection = List.copyOf(selection);
        sentAt = now;
        doc.sendPresence(on ? new int[] { x, y } : null, planIds(doc, graph, sentSelection), view());
    }

    /**
     * The player has gone from the plan told last (the planner closed, another plan opened): no cursor, nothing
     * selected. The next update tells the plan open then afresh.
     */
    public void leave() {
        if (doc != null && sentAt != 0 && doc.inRoom()) doc.sendPresence(null, List.of(), null);
        doc = null;
        inRoom = false;
        sentAt = 0;
        sentCursor = false;
        sentSelection = List.of();
        sentViewX = Float.NaN;
    }

    private boolean sameSelection(final Set<UUID> selection) {
        if (selection.size() != sentSelection.size()) return false;
        int i = 0;
        for (final UUID id : selection) if (!id.equals(sentSelection.get(i++))) return false;
        return true;
    }

    /** The view as presence carries it: the plan's point in the middle of the board, and the board's zoom. */
    @Nullable
    private JsonObject view() {
        if (Float.isNaN(viewX)) return null;
        final JsonObject v = LiveDoc.at(Math.round(viewX), Math.round(viewY));
        v.addProperty("zoom", zoom);
        return v;
    }

    /** The plan's ids of the selected cards, drawers, notes and groups. */
    private static List<String> planIds(final LiveDoc doc, final Graph g, final List<UUID> selection) {
        final List<String> out = new ArrayList<>();
        for (final UUID id : selection) {
            if (out.size() >= MAX_SELECTED) break;
            final String kind = g.nodes.containsKey(id) ? "node"
                : g.drawers.containsKey(id) ? "drawer"
                    : g.notes.containsKey(id) ? "note" : g.boardGroups.containsKey(id) ? "pocket" : null;
            if (kind != null) out.add(doc.planId(kind, id));
        }
        return out;
    }
}
