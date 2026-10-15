package com.gtnhplanner.library;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text a report sends, cut down to what helps: a crash report's error, details and last warnings, and a log
 * without the chat. A GTNH crash report runs to megabytes, nearly all of it the session's whole log, so its end alone
 * says nothing about the crash; and the chat names other players and carries what they said, in a public issue.
 */
public final class ReportText {

    /** Where a crash report's walkthrough starts: the error and its stack come before it, the details after. */
    public static final String WALKTHROUGH = "A detailed walkthrough of the error";
    /** GTNH's crash reports end in the session's whole log, under this line. */
    static final String COMPLETE_LOG = "\nComplete log:";
    /** The planner's line in a crash report's details ({@code CrashReports} adds it). */
    static final String PLANNER = "GTNH Planner:";
    /** What heads the log's last warnings in the excerpt. */
    static final String LOG_HEADING = "The last warnings and errors in its log:";

    /** No line goes longer than this. */
    static final int LINE_MAX = 400;
    /** The log's last warnings and errors that go, each with at most this many lines of its stack. */
    private static final int LOG_ENTRIES = 30, ENTRY_LINES = 8;

    /** A row of the mod list: its states (UCHIJAAAA), then id{version} [name] (file). */
    private static final Pattern MOD_ROW = Pattern.compile("\\s+([ULCHIJADE]+)\\s+\\S.*\\{.*}.*");
    /** A row of the loaded APIs: * name (version) from file. */
    private static final Pattern API_ROW = Pattern.compile("\\s+\\* .+ from .+");
    /** A count and its list ("All players: 2 total; [EntityOtherPlayerMP['name'...]]"): the list names players. */
    private static final Pattern LISTED = Pattern.compile("(\\s*[A-Za-z][A-Za-z ]*: \\d+ total); \\[.*");
    /** A log line's start: [time] [thread/LEVEL]. */
    private static final Pattern ENTRY = Pattern.compile("\\[\\d{1,2}:\\d{2}:\\d{2}] \\[([^\\]]*)/([A-Z]+)].*");

    private ReportText() {}

    /**
     * A crash report in at most {@code max} characters, most telling first: the error and its stack, the planner's
     * line, the details (without the mod and API lists or the players' names) and the last warnings and errors of its
     * log. What has to go goes from the log, then the middle of the details, then the middle of the stack.
     */
    public static String crash(final String report, final int max) {
        final String text = report.replace("\r", "");
        final int walk = text.indexOf(WALKTHROUGH);
        final int logAt = text.indexOf(COMPLETE_LOG, Math.max(0, walk));
        final int detailsEnd = logAt >= 0 ? logAt : text.length();
        final int headEnd = walk >= 0 && walk < detailsEnd ? walk : detailsEnd;

        final List<String> head = clipped(
            lines(
                text.substring(0, headEnd)
                    .strip()));
        String planner = "";
        final List<String> details = new ArrayList<>();
        final Skipped skipped = new Skipped(details);
        for (final String line : lines(text.substring(headEnd, detailsEnd))) {
            final String t = line.strip();
            if (t.startsWith(WALKTHROUGH) || t.startsWith("-----") || t.startsWith("States: 'U'")) continue;
            if (t.startsWith(PLANNER)) {
                planner = clip(t);
                continue;
            }
            final Matcher mod = MOD_ROW.matcher(line);
            if (mod.matches() && mod.group(1)
                .indexOf('E') < 0) {
                skipped.add("mods");
                continue;
            }
            if (API_ROW.matcher(line)
                .matches()) {
                skipped.add("APIs");
                continue;
            }
            if (t.isEmpty() && (details.isEmpty() || details.get(details.size() - 1)
                .isEmpty())) continue;
            final Matcher listed = LISTED.matcher(line);
            skipped.flush();
            details.add(clip(listed.matches() ? listed.group(1) : line));
        }
        skipped.flush();
        while (!details.isEmpty() && details.get(details.size() - 1)
            .isEmpty()) details.remove(details.size() - 1);
        final List<String> log = logAt < 0 ? List.of() : warnings(text, logAt + COMPLETE_LOG.length());

        // Two blank lines between the parts, the log's heading, and room for each part's "left out" line.
        int left = max - planner.length() - LOG_HEADING.length() - 120;
        final int h = length(head), d = length(details), l = length(log);
        final String headText = keepEnds(head, Math.min(h, Math.max(left * 3 / 5, left - d - l)));
        left -= headText.length();
        final int detailsRoom = Math.min(d, left - Math.min(l, Math.max(left - d, left / 4)));
        final String detailsText = keepEnds(details, detailsRoom);
        final String logText = keepEnd(log, Math.min(l, left - detailsText.length()));

        final StringBuilder out = new StringBuilder(headText);
        if (!planner.isEmpty()) out.append("\n\n")
            .append(planner);
        if (!detailsText.isEmpty()) out.append("\n\n")
            .append(detailsText);
        if (!logText.isEmpty()) out.append("\n\n")
            .append(LOG_HEADING)
            .append('\n')
            .append(logText);
        return out.length() <= max ? out.toString() : out.substring(0, Math.max(0, max));
    }

    /**
     * The log's last warnings and errors and the planner's own lines, chat left out, each with a few lines of its
     * stack; a run of the same warning is one line and a count.
     */
    static List<String> warnings(final String text, final int from) {
        final Deque<Entry> kept = new ArrayDeque<>();
        Entry entry = null;
        String lastKey = "";
        int at = from;
        while (at < text.length()) {
            int end = text.indexOf('\n', at);
            if (end < 0) end = text.length();
            final String line = text.substring(at, end);
            at = end + 1;
            final Matcher m = ENTRY.matcher(line);
            if (m.matches()) {
                entry = null;
                final String level = m.group(2);
                final boolean telling = level.equals("WARN") || level.equals("ERROR")
                    || level.equals("FATAL")
                    || line.contains("[gtnhplanner/") && !level.equals("DEBUG") && !level.equals("TRACE");
                if (!telling || chat(line)) continue;
                // Who says it and how it starts, without when or on which thread: the same warning over and over (NEI's
                // for each item) is told once.
                final int said = m.end(2) + 1;
                final String key = line.substring(said, Math.min(line.length(), said + 48));
                if (key.equals(lastKey) && !kept.isEmpty()) {
                    kept.peekLast().more++;
                    continue;
                }
                lastKey = key;
                entry = new Entry(clip(line));
                kept.addLast(entry);
                if (kept.size() > LOG_ENTRIES) kept.removeFirst();
            } else if (entry != null && !line.isBlank()) {
                if (entry.lines.size() <= ENTRY_LINES) entry.lines.add(clip(line));
                else if (entry.lines.size() == ENTRY_LINES + 1) entry.lines.add("\t...");
            }
        }
        final List<String> out = new ArrayList<>();
        for (final Entry e : kept) {
            out.addAll(e.lines);
            if (e.more > 0) out.add("\t(" + e.more + " more like it)");
        }
        return out;
    }

    /** A line of the log kept, the lines that carry it on, and how many like it came straight after. */
    private static final class Entry {

        final List<String> lines = new ArrayList<>();
        int more;

        Entry(final String first) {
            lines.add(first);
        }
    }

    /**
     * Whether a log line is chat, or names a player coming or going: other players' names and words, never sent. A
     * client logs what it is shown as [CHAT]; a world of one's own also logs it from its server, as the server's
     * console has it ("&lt;name&gt; words", "name joined the game").
     */
    static boolean chat(final String line) {
        if (line.contains("[CHAT]")) return true;
        final Matcher m = MESSAGE.matcher(line);
        return m.matches() && PLAYERS.matcher(m.group(1))
            .matches();
    }

    /** A log line's words, after [time] [thread/LEVEL] and the debug log's [logger]. */
    private static final Pattern MESSAGE = Pattern.compile("\\[[^\\]]*] \\[[^\\]]*](?: \\[[^\\]]*])?: (.*)");
    /** A server's console line about a player: what they said, their coming and going. */
    private static final Pattern PLAYERS = Pattern.compile(
        "<[^>]{1,64}> .*|\\* \\S+ .*|\\S+ (?:joined|left) the game|\\S+\\[[^\\]]*] logged in with entity id.*"
            + "|\\S+ lost connection: .*");

    /** Log lines without the chat: each chat line goes, and any lines that carry it on. */
    public static List<String> withoutChat(final List<String> lines) {
        final List<String> out = new ArrayList<>(lines.size());
        boolean inChat = false;
        for (final String line : lines) {
            if (ENTRY.matcher(line)
                .matches()) inChat = chat(line);
            else if (chat(line)) inChat = true;
            if (!inChat) out.add(line);
        }
        return out;
    }

    /** Text cut to {@code max} characters at a line's end, keeping its start, saying how much went. */
    public static String keepStart(final String text, final int max) {
        if (text.length() <= max) return text;
        final String note = "\n... (the rest left out)";
        if (max <= note.length()) return text.substring(0, Math.max(0, max));
        int cut = text.lastIndexOf('\n', max - note.length());
        if (cut <= 0) cut = max - note.length();
        return text.substring(0, cut) + note;
    }

    /** Lines in at most {@code max} characters: the first and the last, with a line saying how many went between. */
    static String keepEnds(final List<String> lines, final int max) {
        if (max <= 0 || lines.isEmpty()) return "";
        if (length(lines) <= max) return String.join("\n", lines);
        final int room = max - 40;
        if (room <= 0) return keepStart(String.join("\n", lines), max);
        final List<String> first = new ArrayList<>(), last = new ArrayList<>();
        int used = 0, a = 0, b = lines.size() - 1;
        while (a <= b && used + lines.get(a)
            .length() + 1 <= room * 2 / 3)
            used += lines.get(a++)
                .length() + 1;
        while (b >= a && used + lines.get(b)
            .length() + 1 <= room)
            used += lines.get(b--)
                .length() + 1;
        for (int i = 0; i < a; i++) first.add(lines.get(i));
        for (int i = b + 1; i < lines.size(); i++) last.add(lines.get(i));
        first.add("\t... (" + (b - a + 1) + " lines left out)");
        first.addAll(last);
        return String.join("\n", first);
    }

    /** The last lines in at most {@code max} characters, with a line saying how many came before. */
    static String keepEnd(final List<String> lines, final int max) {
        if (max <= 0 || lines.isEmpty()) return "";
        if (length(lines) <= max) return String.join("\n", lines);
        final int room = max - 40;
        if (room <= 0) return "";
        int used = 0, b = lines.size() - 1;
        while (b >= 0 && used + lines.get(b)
            .length() + 1 <= room)
            used += lines.get(b--)
                .length() + 1;
        final List<String> out = new ArrayList<>();
        out.add("... (" + (b + 1) + " earlier lines left out)");
        out.addAll(lines.subList(b + 1, lines.size()));
        return String.join("\n", out);
    }

    /** What the lines take joined. */
    private static int length(final List<String> lines) {
        int n = Math.max(0, lines.size() - 1);
        for (final String l : lines) n += l.length();
        return n;
    }

    private static List<String> lines(final String text) {
        return new ArrayList<>(List.of(text.split("\n", -1)));
    }

    private static List<String> clipped(final List<String> lines) {
        lines.replaceAll(ReportText::clip);
        return lines;
    }

    private static String clip(final String line) {
        return line.length() <= LINE_MAX ? line : line.substring(0, LINE_MAX - 3) + "...";
    }

    /** Runs of rows left out (the mod list, the APIs), written as one line where they were. */
    private static final class Skipped {

        private final List<String> into;
        private String what;
        private int count;

        Skipped(final List<String> into) {
            this.into = into;
        }

        void add(final String kind) {
            if (!kind.equals(what)) flush();
            what = kind;
            count++;
        }

        void flush() {
            if (count > 0) into.add("        (" + count + " " + what + " left out)");
            what = null;
            count = 0;
        }
    }
}
