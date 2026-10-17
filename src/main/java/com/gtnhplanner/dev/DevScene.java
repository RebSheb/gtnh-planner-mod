package com.gtnhplanner.dev;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.client.Minecraft;

import com.gtnhplanner.data.flowchart.Drawer;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Node;
import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.ui.BoardScreen;
import com.gtnhplanner.ui.BoardSession;
import com.gtnhplanner.ui.Planner;
import com.gtnhplanner.ui.card.CardModel;
import com.gtnhplanner.ui.live.Peers;

/**
 * Four stand-in people at work on the open plan, for footage ({@code call 'cloud?scene=1'}, {@code scene=0} stops).
 * Each runs a script of quick moves, short pauses and drags, the way people use a mouse: Ada tidies cards, Bob asks on
 * a sticky note for more electrolyzers, Cyrus turns counts, and Dee answers the note and adds them. Their cursors and
 * selections come through {@link Peers} as real people's do; their edits are made on the board directly. About 30
 * seconds, then they rest where they are.
 */
public final class DevScene {

    private static long started = -1;
    private static List<Actor> cast;

    private DevScene() {}

    public static void start() {
        started = System.currentTimeMillis();
        cast = null;
        DevPeople.set(0);
    }

    public static void stop() {
        started = -1;
        cast = null;
    }

    public static boolean active() {
        return started >= 0;
    }

    static void drive(final Peers peers) {
        final BoardSession board = BoardSession.current();
        final BoardScreen screen = Planner.screenOf(Minecraft.getMinecraft().currentScreen) instanceof final BoardScreen b
            ? b
            : null;
        if (board == null || screen == null) return;
        if (cast == null) cast = castFrom(board, screen);
        if (cast == null) return;
        final double t = (System.currentTimeMillis() - started) / 1000.0;
        for (int i = 0; i < cast.size(); i++) {
            final Actor a = cast.get(i);
            a.run(t);
            peers.fakeHeard(i, a.name, a.client, new int[] { Math.round(a.x), Math.round(a.y) }, a.selected);
        }
    }

    // region One person's script

    /** One step of a script: lasts {@code seconds}, moves the cursor and may change the plan as it goes. */
    private abstract static class Op {

        final double seconds;
        boolean begun, ended;

        Op(final double seconds) {
            this.seconds = seconds;
        }

        void begin(final Actor a) {}

        /** {@code k} runs 0 to 1 over the step. */
        abstract void frame(Actor a, double k);

        void end(final Actor a) {}
    }

    private static final class Actor {

        final String name, client;
        final List<Op> ops = new ArrayList<>();
        float x, y;
        List<UUID> selected = List.of();
        /** Where a slow fidget is centred while it waits. */
        float restX, restY;
        final double seed;

        Actor(final String name, final String client, final float x, final float y, final double seed) {
            this.name = name;
            this.client = client;
            this.x = restX = x;
            this.y = restY = y;
            this.seed = seed;
        }

        /** Only the step under way acts; one passed between two frames is finished once, as it would have been. */
        void run(final double t) {
            double at = 0;
            for (final Op op : ops) {
                if (t < at) return;
                if (!op.begun) {
                    op.begun = true;
                    op.begin(this);
                }
                final double endAt = at + op.seconds;
                if (t < endAt) {
                    op.frame(this, (t - at) / op.seconds);
                    return;
                }
                if (!op.ended) {
                    op.frame(this, 1);
                    op.ended = true;
                    op.end(this);
                }
                at = endAt;
            }
            // Past the script: a hand resting on the mouse.
            fidget(t);
        }

        void fidget(final double t) {
            x = restX + (float) (3 * Math.sin(t * 1.3 + seed) + 2 * Math.sin(t * 3.1 + 2 * seed));
            y = restY + (float) (2 * Math.sin(t * 1.7 + 3 * seed) + 2 * Math.sin(t * 2.3 + seed));
        }

        Actor wait(final double seconds) {
            final double s = seed;
            ops.add(new Op(seconds) {

                @Override
                void begin(final Actor a) {
                    a.restX = a.x;
                    a.restY = a.y;
                }

                @Override
                void frame(final Actor a, final double k) {
                    a.fidget(k * seconds + s);
                }
            });
            return this;
        }

        /** A quick move to a point: fast out, settling in, on a slight curve. */
        Actor move(final Supplier<float[]> to, final double seconds) {
            ops.add(new Op(seconds) {

                float fx, fy;

                @Override
                void begin(final Actor a) {
                    fx = a.x;
                    fy = a.y;
                }

                @Override
                void frame(final Actor a, final double k) {
                    final float[] p = to.get();
                    if (p == null) return;
                    final double e = 1 - Math.pow(1 - k, 3);
                    // A hand's arc: off the straight line in the middle, by a tenth of the way.
                    final double bow = Math.sin(Math.PI * e) * 0.1;
                    final float dx = p[0] - fx, dy = p[1] - fy;
                    a.x = (float) (fx + dx * e - dy * bow);
                    a.y = (float) (fy + dy * e + dx * bow);
                    a.restX = a.x;
                    a.restY = a.y;
                }
            });
            return this;
        }

        Actor select(final Supplier<List<UUID>> what) {
            return act(a -> a.selected = what.get());
        }

        Actor act(final java.util.function.Consumer<Actor> what) {
            ops.add(new Op(0.05) {

                @Override
                void frame(final Actor a, final double k) {}

                @Override
                void end(final Actor a) {
                    what.accept(a);
                }
            });
            return this;
        }

        /** Carries a card, drawer or note by {@code dx, dy}: the cursor holds it where it took it. */
        Actor drag(final BoardScreen screen, final Supplier<UUID> what, final int dx, final int dy,
            final double seconds) {
            ops.add(new Op(seconds) {

                UUID id;
                int ox, oy;
                float gx, gy;

                @Override
                void begin(final Actor a) {
                    id = what.get();
                    final int[] at = posOf(screen, id);
                    if (at == null) return;
                    ox = at[0];
                    oy = at[1];
                    gx = a.x - ox;
                    gy = a.y - oy;
                    a.selected = List.of(id);
                }

                @Override
                void frame(final Actor a, final double k) {
                    if (id == null) return;
                    final double e = k < 0.5 ? 4 * k * k * k : 1 - Math.pow(-2 * k + 2, 3) / 2;
                    final float px = (float) (ox + dx * e), py = (float) (oy + dy * e);
                    // The thing goes from grid spot to grid spot, as a carried one does; the hand goes smoothly.
                    screen.canvas()
                        .placeAt(id, BoardSession.snap(px), BoardSession.snap(py));
                    a.x = px + gx;
                    a.y = py + gy;
                    a.restX = a.x;
                    a.restY = a.y;
                }
            });
            return this;
        }

        /** Types into a note a letter at a time, the cursor resting where the writing is. */
        Actor type(final Supplier<Note> note, final String text, final double perLetter) {
            ops.add(new Op(text.length() * perLetter) {

                @Override
                void frame(final Actor a, final double k) {
                    final Note n = note.get();
                    if (n == null) return;
                    final String now = text.substring(0, (int) Math.round(k * text.length()));
                    if (!String.join(" ", n.getText())
                        .equals(now)) n.setText(new ArrayList<>(List.of(now)));
                    a.fidget(k * seconds * 2);
                }
            });
            return this;
        }
    }

    // endregion

    // region The cast

    private static List<Actor> castFrom(final BoardSession board, final BoardScreen screen) {
        final Graph g = board.graph();
        final List<Node> nodes = new ArrayList<>(g.getNodes());
        if (nodes.size() < 4) return null;
        final Node electrolyzer = pick(board, nodes, "electrolyzer", null);
        final Node distillery = pick(board, nodes, "distillery", null);
        final Node turbine = pick(board, nodes, "turbine", null);
        final Node reactor = pick(board, nodes, "chemical", null);
        final Node tank = pick(board, nodes, "tank", null);
        final float[] box = bounds(nodes);
        // The camera on the busy left of the plan, close enough to read the cards.
        final List<UUID> shown = new ArrayList<>();
        for (final Node n : nodes) if (n.x < electrolyzer.x + 1300) shown.add(n.id);
        screen.canvas()
            .frame(shown);
        // The notes go in the free board under the distillery.
        final int noteX = BoardSession.snap(distillery.x + 200), noteY = BoardSession.snap(distillery.y + 360);
        final Note[] notes = new Note[2];
        final Drawer steam = drawerNear(g, turbine);

        final List<Actor> out = new ArrayList<>();

        // Ada: tidies. Carries the turbine over, its drawer after it, then lines a tank up.
        final Actor ada = new Actor("Ada", "web", box[2] - 80, box[3] + 60, 0.3);
        ada.wait(0.6)
            .move(() -> head(turbine), 0.55)
            .wait(0.25)
            .drag(screen, () -> turbine.id, 260, 40, 0.9)
            .wait(0.5);
        if (steam != null) ada.move(() -> grip(steam), 0.45)
            .wait(0.2)
            .drag(screen, steam::getId, 260, 40, 0.7)
            .wait(0.6);
        ada.move(() -> head(tank), 0.6)
            .wait(0.3)
            .drag(screen, () -> tank.id, 0, -30, 0.5)
            .wait(1.4)
            .move(() -> centre(reactor), 0.5)
            .select(() -> List.of(reactor.id))
            .wait(2.2)
            .move(() -> head(turbine), 0.6)
            .wait(0.2)
            .drag(screen, () -> turbine.id, -120, 0, 0.6)
            .wait(1.5)
            .select(List::of)
            .move(() -> new float[] { box[0] + 200, box[3] + 40 }, 0.6);
        out.add(ada);

        // Bob: asks for more electrolyzers on a note over them, then nudges it closer.
        final Actor bob = new Actor("Bob", "game", box[0] - 40, box[1] - 80, 1.1);
        bob.wait(1.0)
            .move(() -> new float[] { noteX + 20, noteY + 20 }, 0.6)
            .wait(0.35)
            .act(a -> {
                notes[0] = note(g, noteX, noteY, "yellow");
                a.selected = List.of(notes[0].getId());
            })
            .type(() -> notes[0], "Can we get two more electrolyzers here?", 0.07)
            .wait(0.6)
            .select(List::of)
            .move(() -> centre(electrolyzer), 0.5)
            .select(() -> List.of(electrolyzer.id))
            .wait(2.0)
            .move(() -> new float[] { noteX + 60, noteY + 10 }, 0.5)
            .wait(0.2)
            .drag(screen, () -> notes[0].getId(), 0, 40, 0.5)
            .wait(3.0)
            .move(() -> count(distillery), 0.6)
            .select(() -> List.of(distillery.id))
            .wait(0.4)
            .act(a -> board.pin(distillery, machines(board, distillery) + 1))
            .wait(2.5);
        out.add(bob);

        // Cyrus: turns the distillery up, then moves a reactor to give it room.
        final Actor cyrus = new Actor("Cyrus_Ironworks", "web", box[2] + 40, box[1] + 60, 2.2);
        cyrus.wait(2.4)
            .move(() -> count(distillery), 0.6)
            .select(() -> List.of(distillery.id))
            .wait(0.5)
            .act(a -> board.pin(distillery, machines(board, distillery) + 1))
            .wait(0.45)
            .act(a -> board.pin(distillery, machines(board, distillery) + 1))
            .wait(1.2)
            .move(() -> head(reactor), 0.55)
            .wait(0.2)
            .drag(screen, () -> reactor.id, 0, 60, 0.6)
            .wait(1.8)
            .select(List::of)
            .move(() -> new float[] { box[2] - 300, box[1] - 20 }, 0.7)
            .wait(3.0)
            .move(() -> head(reactor), 0.6)
            .wait(0.2)
            .drag(screen, () -> reactor.id, 0, -60, 0.5)
            .wait(2.0);
        out.add(cyrus);

        // Dee: reads Bob's note, answers it under it, adds the two electrolyzers.
        final Actor dee = new Actor("Dee", "game", box[0] + 300, box[3] + 20, 3.4);
        dee.wait(4.2)
            .move(() -> new float[] { noteX + 120, noteY + 70 }, 0.8)
            .wait(1.6)
            .move(() -> new float[] { noteX + 320, noteY + 40 }, 0.4)
            .act(a -> {
                notes[1] = note(g, noteX + 300, noteY + 20, "lime");
                a.selected = List.of(notes[1].getId());
            })
            .type(() -> notes[1], "On it. Adding them now.", 0.07)
            .wait(0.4)
            .move(() -> count(electrolyzer), 0.7)
            .select(() -> List.of(electrolyzer.id))
            .wait(0.4)
            .act(a -> board.pin(electrolyzer, machines(board, electrolyzer) + 1))
            .wait(0.5)
            .act(a -> board.pin(electrolyzer, machines(board, electrolyzer) + 1))
            .wait(2.0)
            .select(List::of)
            .move(() -> new float[] { noteX + 420, noteY + 90 }, 0.6)
            .wait(3.0);
        out.add(dee);
        return out;
    }

    /** The card whose machine's name holds {@code word}; else any not yet given out. */
    /** The left-most card whose machine's name holds {@code word}; else one picked by the word. */
    private static Node pick(final BoardSession board, final List<Node> nodes, final String word, final Node not) {
        Node best = null;
        for (final Node n : nodes) {
            final CardModel m = board.model(n.id);
            if (m != null && m.machineName != null
                && m.machineName.toLowerCase(Locale.ROOT)
                    .contains(word)
                && (best == null || n.x < best.x)) best = n;
        }
        return best != null ? best : nodes.get(Math.abs(word.hashCode()) % nodes.size());
    }

    private static Drawer drawerNear(final Graph g, final Node n) {
        Drawer best = null;
        double far = Double.MAX_VALUE;
        for (final Drawer d : g.getDrawers()) {
            final double dist = Math.hypot(d.getX() - n.x, d.getY() - n.y);
            if (dist < far) {
                far = dist;
                best = d;
            }
        }
        return far < 260 ? best : null;
    }

    private static float[] bounds(final List<Node> nodes) {
        final float[] b = { Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
        for (final Node n : nodes) {
            b[0] = Math.min(b[0], n.x);
            b[1] = Math.min(b[1], n.y);
            b[2] = Math.max(b[2], n.x + 320);
            b[3] = Math.max(b[3], n.y + 170);
        }
        return b;
    }

    private static float[] head(final Node n) {
        return new float[] { n.x + 70, n.y + 10 };
    }

    private static float[] centre(final Node n) {
        return new float[] { n.x + 160, n.y + 80 };
    }

    /** Over the card's machine count, under its picture. */
    private static float[] count(final Node n) {
        return new float[] { n.x + 195, n.y + 118 };
    }

    private static float[] grip(final Drawer d) {
        return new float[] { d.getX() + 40, d.getY() + 18 };
    }

    private static int[] posOf(final BoardScreen screen, final UUID id) {
        final Graph g = BoardSession.current()
            .graph();
        final Node n = g.nodes.get(id);
        if (n != null) return new int[] { n.x, n.y };
        final Drawer d = g.getDrawer(id);
        if (d != null) return new int[] { d.getX(), d.getY() };
        final Note note = g.notes.get(id);
        return note == null ? null : new int[] { note.getX(), note.getY() };
    }

    private static double machines(final BoardSession board, final Node n) {
        final CardModel m = board.model(n.id);
        return Math.max(1, Math.round(m == null ? 1 : m.machines));
    }

    private static Note note(final Graph g, final int x, final int y, final String colour) {
        final Note note = new Note();
        note.setX(x);
        note.setY(y);
        note.setColor(colour);
        // Big enough to read on a board zoomed out to see the plan.
        note.setWidth(270);
        note.setHeight(120);
        note.setFontSize(24);
        com.gtnhplanner.api.PlanAPI.recordEdit(g, () -> g.notes.put(note.getId(), note));
        g.touchLayout();
        com.gtnhplanner.ui.sound.Sfx.NOTE_STICK.play();
        return note;
    }

    // endregion
}
