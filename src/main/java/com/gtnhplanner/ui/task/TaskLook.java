package com.gtnhplanner.ui.task;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.gtnhplanner.cloud.net.LiveClient;
import com.gtnhplanner.cloud.session.CloudSession;
import com.gtnhplanner.cloud.session.LiveDoc;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Tasks;
import com.gtnhplanner.library.Account;
import com.gtnhplanner.ui.live.Peers;
import com.gtnhplanner.ui.theme.Hyb;

/**
 * How the task board looks and who is writing: each status's name and colour, people's faces (their initials on a
 * colour of their own, as the website draws them), times as the plan writes them and as people read them.
 */
public final class TaskLook {

    private TaskLook() {}

    /** A column's name. */
    public static String name(final Tasks.Status s) {
        return switch (s) {
            case TODO -> "To do";
            case DOING -> "Doing";
            case BLOCKED -> "Blocked";
            case DONE -> "Done";
        };
    }

    /** A status's colour: grey to do, blue doing, red blocked, green done. */
    public static int colour(final Tasks.Status s) {
        return switch (s) {
            case TODO -> 0xFFA1A4AD;
            case DOING -> 0xFF60A5FA;
            case BLOCKED -> 0xFFE5484D;
            case DONE -> 0xFF4ADE80;
        };
    }

    /**
     * Words cut to fit a width at a word, ending in "..." when it had to; a first word too long for it is cut where it
     * must be.
     */
    public static String fitWords(final String s, final int maxWidth) {
        if (Hyb.width(s) <= maxWidth) return s;
        String out = "";
        for (final String w : s.split(" ")) {
            if (w.isEmpty()) continue;
            final String next = out.isEmpty() ? w : out + " " + w;
            if (Hyb.width(next + "...") > maxWidth) break;
            out = next;
        }
        return out.isEmpty() ? Hyb.fit(s, maxWidth) : out + "...";
    }

    /** A disc {@code d} across, in whole steps of the space it is drawn in. */
    public static void dot(final float x, final float y, final float d, final int colour) {
        Hyb.roundRect(x, y, d, d, d / 2, colour);
    }

    /** A task board's tab and tile wear this violet, a faint cast (about 9% of it) over the plain colour. */
    public static final int TINT = 0xFFA78BFA, TINT_FAINT = 0x17A78BFA;

    /** The checklist icon's width and height, one font line. */
    public static final int ICON_W = 7, ICON_H = 8;

    /**
     * The checklist icon (a clipboard with two lines on it), 7 by 8 pixels, flat like the power bolt: runs of
     * {x, y, width}.
     */
    private static final int[][] CHECKLIST = { { 2, 0, 3 }, { 0, 1, 7 }, { 0, 2, 1 }, { 6, 2, 1 }, { 0, 3, 1 },
        { 2, 3, 3 }, { 6, 3, 1 }, { 0, 4, 1 }, { 6, 4, 1 }, { 0, 5, 1 }, { 2, 5, 3 }, { 6, 5, 1 }, { 0, 6, 1 },
        { 6, 6, 1 }, { 0, 7, 7 } };

    /** Draws the checklist icon with its top left at {@code x}, {@code y}. */
    public static void checklist(final float x, final float y, final int colour) {
        for (final int[] r : CHECKLIST) Hyb.rect(x + r[0], y + r[1], r[2], 1, colour);
    }

    // region Priority and time in a column

    /** A priority's name. */
    public static String priorityName(final String p) {
        return switch (p) {
            case Tasks.HIGH -> "High";
            case Tasks.LOW -> "Low";
            default -> "Medium";
        };
    }

    /** A priority's colour: red-orange high, amber medium, blue low. */
    public static int priorityColour(final String p) {
        return switch (p) {
            case Tasks.HIGH -> 0xFFF2602F;
            case Tasks.LOW -> 0xFF4F9BF2;
            default -> 0xFFF5B83D;
        };
    }

    /** The priority icon's width and height. */
    public static final int PRIORITY_W = 7, PRIORITY_H = 6;

    private static final int[][] ARROW_UP = { { 3, 0, 1 }, { 2, 1, 3 }, { 1, 2, 5 }, { 0, 3, 7 }, { 2, 4, 3 },
        { 2, 5, 3 } };
    private static final int[][] ARROW_DOWN = { { 2, 0, 3 }, { 2, 1, 3 }, { 0, 2, 7 }, { 1, 3, 5 }, { 2, 4, 3 },
        { 3, 5, 1 } };
    private static final int[][] BAR = { { 0, 2, 7 }, { 0, 3, 7 } };

    /** A priority's icon, flat: an arrow up for high, a bar for medium, an arrow down for low. */
    public static void priority(final String p, final float x, final float y) {
        final int[][] runs = Tasks.HIGH.equals(p) ? ARROW_UP : Tasks.LOW.equals(p) ? ARROW_DOWN : BAR;
        final int c = priorityColour(p);
        for (final int[] r : runs) Hyb.rect(x + r[0], y + r[1], r[2], 1, c);
    }

    /** Whole days since a time the plan wrote; 0 when it does not read. */
    public static int days(@Nullable final String iso) {
        if (iso == null) return 0;
        try {
            return (int) Math.max(
                0,
                Duration.between(Instant.parse(iso), Instant.now())
                    .toDays());
        } catch (final RuntimeException e) {
            return 0;
        }
    }

    /** How many days a task has sat in its column. */
    public static int inColumnDays(final Tasks.Task t) {
        return days(t.sinceColumn());
    }

    /** The dots a card shows for its days in a column: one a day to four, none in Done. */
    public static int dotCount(final Tasks.Task t) {
        return t.status() == Tasks.Status.DONE ? 0 : Math.min(4, inColumnDays(t));
    }

    /** The dots' colour: grey, amber from a week, red from two. */
    public static int dotColour(final int days) {
        return days >= 14 ? 0xFFE5484D : days >= 7 ? 0xFFF5B83D : 0xFF6B6F7A;
    }

    /** Dots across, 3 by 3 with 2 between; returns their width. */
    public static int timeDots(final Tasks.Task t, final float x, final float y) {
        final int n = dotCount(t), c = dotColour(inColumnDays(t));
        for (int i = 0; i < n; i++) Hyb.rect(x + i * 5, y, 3, 3, c);
        return n == 0 ? 0 : n * 5 - 2;
    }

    /** "5 days", "1 day" or "under a day". */
    public static String span(final int days) {
        return days < 1 ? "under a day" : days + (days == 1 ? " day" : " days");
    }

    /** "In Doing for 5 days": the tooltip's line. */
    public static String inColumn(final Tasks.Task t) {
        return "In " + name(t.status()) + " for " + span(inColumnDays(t));
    }

    /** "In Doing 5 days": the task box's line. */
    public static String inColumnShort(final Tasks.Task t) {
        return "In " + name(t.status()) + " " + span(inColumnDays(t));
    }

    // endregion

    // region People

    /** What a signed-out player writes as, and is called. */
    public static final String LOCAL = "local", YOU = "You";

    /** The signed-in account's id, or {@link #LOCAL} signed out. */
    public static String me() {
        final String id = Account.userId();
        return id == null ? LOCAL : id;
    }

    /** The signed-in account's name, or "You" signed out. */
    public static String myName() {
        final String name = Account.username();
        return name == null ? YOU : name;
    }

    /** Whether a comment or task is the player's own: their account's, or written on this computer signed out. */
    public static boolean mine(@Nullable final String by) {
        return by != null && (by.equals(LOCAL) || by.equals(Account.userId()));
    }

    /**
     * Someone's name by account id: the player's own, else as a shared plan's room names its owner, members and who
     * is in it, else the name their comments on the plan carry; null if none.
     */
    @Nullable
    public static String nameOf(final Graph g, final String id) {
        if (id.equals(Account.userId())) return Account.username();
        if (id.equals(LOCAL)) return YOU;
        for (final Person p : people(g)) if (p.id()
            .equals(id)) return p.name();
        final LiveDoc d = live(g);
        if (d != null) for (final LiveClient.Person p : d.present()) if (p.id()
            .equals(id)) return p.username();
        for (final Tasks.Comment c : g.taskComments) if (c.by()
            .equals(id)
            && !c.byName()
                .isEmpty())
            return c.byName();
        return null;
    }

    /** Someone who may be given a task: an account and its name. */
    public record Person(String id, String name) {}

    /**
     * Who may be given a task on a cloud plan: its owner and its members (the player among them), as its room last
     * said; the player alone before it has. Empty for a plan on this computer.
     */
    public static List<Person> people(final Graph g) {
        final LiveDoc d = live(g);
        if (d == null) return List.of();
        final java.util.Map<String, Person> out = new java.util.LinkedHashMap<>();
        if (d.owner() != null) out.put(
            d.owner()
                .id(),
            new Person(
                d.owner()
                    .id(),
                d.owner()
                    .username()));
        for (final LiveClient.Member m : d.members()) out.putIfAbsent(m.id(), new Person(m.id(), m.username()));
        final String me = Account.userId();
        if (me != null) out.putIfAbsent(me, new Person(me, myName()));
        return List.copyOf(out.values());
    }

    /** The cloud plan behind a board, or null. */
    @Nullable
    private static LiveDoc live(final Graph g) {
        final CloudSession s = CloudSession.current();
        return s == null ? null : s.doc(g);
    }

    /** Up to two initials: a name's first letter, or the first letters of its first two words. */
    public static String initials(@Nullable final String name) {
        if (name == null || name.isBlank()) return "?";
        final String[] words = name.trim()
            .split("[\\s_.\\-]+");
        final StringBuilder out = new StringBuilder();
        for (final String w : words) {
            if (w.isEmpty()) continue;
            out.appendCodePoint(w.codePointAt(0));
            if (out.length() >= 2 || words.length < 2) break;
        }
        return out.toString()
            .toUpperCase(Locale.ROOT);
    }

    /**
     * A face's colour by account id: the one the live service gives the account, so a person is the same colour on the
     * task board, their cursor and the website.
     */
    public static int faceColour(final String id) {
        return Peers.colourOf(id);
    }

    public static final int FACE = 12;

    /** A face's width: room for its initials. */
    public static int faceWidth(final String initials) {
        return Math.max(FACE, Hyb.width(initials) + 3);
    }

    /** Someone's face, flat: their initials on their colour. Returns its width. */
    public static int face(final Graph g, final String id, final float x, final float y) {
        return face(nameOf(g, id), faceColour(id), x, y);
    }

    /** A face for a name, on a colour. Returns its width. */
    public static int face(@Nullable final String name, final int colour, final float x, final float y) {
        final String in = initials(name);
        final int w = faceWidth(in);
        Hyb.rect(x + 1, y, w - 2, FACE, colour);
        Hyb.rect(x, y + 1, w, FACE - 2, colour);
        Hyb.text(in, x + (w - Hyb.width(in) + 1) / 2f, y + 2, 1f, Hyb.contrastInk(colour), false);
        return w;
    }

    /** Faces side by side, each overlapping the last a little; returns how wide they run. */
    public static int faces(final Graph g, final List<String> ids, final float x, final float y) {
        float at = x;
        for (final String id : ids) at += face(g, id, at, y) - 2;
        return ids.isEmpty() ? 0 : Math.round(at - x + 2);
    }

    // endregion

    // region Time

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneOffset.UTC);

    /** Now, as the plan writes a time: ISO in UTC to the millisecond, as the website's {@code toISOString}. */
    public static String now() {
        return ISO.format(Instant.now());
    }

    /** "just now", "5m ago", "3h ago", "12d ago" from a time the plan wrote; "" when it does not read. */
    public static String ago(@Nullable final String iso) {
        if (iso == null) return "";
        try {
            final long m = Math.max(
                0,
                Duration.between(Instant.parse(iso), Instant.now())
                    .toMinutes());
            if (m < 1) return "just now";
            if (m < 60) return m + "m ago";
            if (m < 60 * 24) return m / 60 + "h ago";
            if (m < 60 * 24 * 60) return m / (60 * 24) + "d ago";
            return m / (60 * 24 * 30) + "mo ago";
        } catch (final RuntimeException e) {
            return "";
        }
    }

    // endregion
}
