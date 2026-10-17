package com.gtnhplanner.dev;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.live.Peers;

/**
 * Stand-in people on the open plan for the dev game ({@code call 'cloud?fake=N'}, {@code fake=0} clears): presence
 * seen without a live service. Each wanders over the plan's cards on a path of its own, heard ten times a second as a
 * real one is, and every few seconds selects one or two cards or drawers; the third goes quiet for a while now and
 * then,
 * so its cursor fades. Half are in game, half in a browser.
 */
public final class DevPeople {

    private static final String[] NAMES = { "Ada", "Bob", "Cyrus_Ironworks", "Dee", "Eve", "Finn", "Gus", "Hal" };

    private static int count;
    private static long started;
    private static final long[] heard = new long[64];

    private DevPeople() {}

    /** How many stand-ins the open plan has (0: none). */
    public static void set(final int n) {
        count = Math.max(0, Math.min(heard.length, n));
        started = System.currentTimeMillis();
        java.util.Arrays.fill(heard, 0);
    }

    public static int count() {
        return count;
    }

    /** Whether any are wanted. */
    public static boolean wanted() {
        return count > 0 || DevScene.active();
    }

    /** Moves the stand-ins on, as the board draws: each heard at most ten times a second. */
    public static void drive(final Peers peers) {
        if (DevScene.active()) {
            DevScene.drive(peers);
            return;
        }
        peers.dropFakes(count);
        if (count == 0) return;
        final BoardSession board = BoardSession.current();
        if (board == null) return;
        final Graph g = board.graph();
        final float[] box = bounds(g);
        final long now = System.currentTimeMillis();
        final double t = (now - started) / 1000.0;
        final List<UUID> things = things(g);
        for (int i = 0; i < count; i++) {
            if (now - heard[i] < 100 && peers.hasFake(i)) continue;
            // The third goes quiet for 14 s of every 24: its cursor fades, then comes back.
            if (i == 2 && (t % 24) > 10 && peers.hasFake(i)) continue;
            heard[i] = now;
            final double a = 0.31 + 0.13 * i, b = 0.23 + 0.07 * i, phase = i * 1.7;
            final float cx = (box[0] + box[2]) / 2, cy = (box[1] + box[3]) / 2;
            final float rx = Math.max(120, (box[2] - box[0]) / 2), ry = Math.max(80, (box[3] - box[1]) / 2);
            final int x = Math.round(cx + rx * (float) Math.sin(a * t + phase));
            final int y = Math.round(cy + ry * (float) Math.sin(b * t + 2 * phase));
            final List<UUID> sel = new ArrayList<>();
            if (!things.isEmpty()) {
                // A new pick every few seconds; every other one takes two.
                final int pick = (int) (t / (3 + i)) + 7 * i;
                sel.add(things.get(Math.floorMod(pick, things.size())));
                if (pick % 2 == 0 && things.size() > 1) sel.add(things.get(Math.floorMod(pick + 1, things.size())));
            }
            peers.fakeHeard(
                i,
                NAMES[i % NAMES.length] + (i < NAMES.length ? "" : String.valueOf(i)),
                i % 2 == 0 ? "game" : "web",
                new int[] { x, y },
                sel);
        }
    }

    /** The plan's cards and drawers, in a steady order. */
    private static List<UUID> things(final Graph g) {
        final List<UUID> out = new ArrayList<>();
        for (final Node n : g.getNodes()) out.add(n.id);
        for (final Drawer d : g.getDrawers()) out.add(d.getId());
        return out;
    }

    /** Where the plan's cards are {min x, min y, max x, max y}; round the middle of nothing for an empty plan. */
    private static float[] bounds(final Graph g) {
        final float[] b = { Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
        for (final Node n : g.getNodes()) {
            b[0] = Math.min(b[0], n.x);
            b[1] = Math.min(b[1], n.y);
            b[2] = Math.max(b[2], n.x + 320);
            b[3] = Math.max(b[3], n.y + 200);
        }
        if (b[0] > b[2]) return new float[] { -200, -150, 200, 150 };
        return b;
    }
}
