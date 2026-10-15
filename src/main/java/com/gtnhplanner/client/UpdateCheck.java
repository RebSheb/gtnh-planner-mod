package com.gtnhplanner.client;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.Tags;

/**
 * Whether a newer GTNH Planner is out: the mod's releases on GitHub, asked once a game in the background, the newest
 * kept with its release page. The planner offers it when it opens ({@link com.gtnhplanner.ui.popup.UpdateOffer}).
 * Nothing is said when the check cannot reach GitHub.
 */
public final class UpdateCheck {

    private static final String RELEASES = "https://api.github.com/repos/jackwrichards/gtnh-planner-mod/releases?per_page=20";
    private static final int TIMEOUT_MS = 8000;
    private static final Pattern VERSION = Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-(alpha|beta|rc)\\.(\\d+))?");
    /** The pre-release stages, earliest first; a release with none comes after them all. */
    private static final List<String> STAGES = List.of("alpha", "beta", "rc");

    /** A release: its tag and its page. */
    public record Release(String tag, String url) {}

    @Nullable
    private static volatile Release latest;
    private static boolean started;

    private UpdateCheck() {}

    /** Asks GitHub for the releases, once, off the game's thread. */
    public static synchronized void start() {
        if (started) return;
        started = true;
        final Thread t = new Thread(UpdateCheck::check, "GTNH Planner update check");
        t.setDaemon(true);
        t.start();
    }

    /** The newest release, when it is newer than this jar; null when there is none (or it is not known yet). */
    @Nullable
    public static Release newer() {
        final Release r = latest;
        return r != null && newer(r.tag(), Tags.VERSION) ? r : null;
    }

    /** This jar's version, as its release is tagged (a dev build's own suffix left off). */
    public static String current() {
        final Matcher m = VERSION.matcher(Tags.VERSION);
        return m.find() ? m.group() : Tags.VERSION;
    }

    /** The dev harness: a release to offer as the newest, or ({@code null}) the real check's answer forgotten. */
    public static void fake(@Nullable final Release release) {
        latest = release;
    }

    private static void check() {
        try {
            final HttpURLConnection c = (HttpURLConnection) new URL(RELEASES).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "GTNH Planner/" + Tags.VERSION);
            try {
                if (c.getResponseCode() != 200) return;
                final String body;
                try (InputStream in = c.getInputStream()) {
                    body = new String(readAll(in), StandardCharsets.UTF_8);
                }
                latest = newest(body);
                if (latest != null) GtnhPlanner.LOG.info("Newest GTNH Planner release: {}", latest.tag());
            } finally {
                c.disconnect();
            }
        } catch (final Exception | LinkageError e) {
            GtnhPlanner.LOG.debug("Could not check for a newer GTNH Planner", e);
        }
    }

    /** The newest release in GitHub's list of them (drafts left out); null for none. */
    @Nullable
    static Release newest(final String json) {
        final JsonElement root = new JsonParser().parse(json);
        if (!root.isJsonArray()) return null;
        Release best = null;
        final JsonArray all = root.getAsJsonArray();
        for (final JsonElement e : all) {
            if (!e.isJsonObject()) continue;
            final JsonObject o = e.getAsJsonObject();
            if (o.has("draft") && o.get("draft")
                .getAsBoolean() || !o.has("tag_name") || !o.has("html_url")) continue;
            final Release r = new Release(
                o.get("tag_name")
                    .getAsString(),
                o.get("html_url")
                    .getAsString());
            if (parse(r.tag()) != null && (best == null || newer(r.tag(), best.tag()))) best = r;
        }
        return best;
    }

    /**
     * Whether version {@code a} is newer than {@code b}: major, minor and patch, then the stage (alpha, beta, rc, then
     * the release itself, above every pre-release of it) and its number. Anything that is not a version is never
     * newer. Jars before 0.2.0-beta.1 read only alphas, so to them a beta or rc is its release.
     */
    public static boolean newer(final String a, final String b) {
        final long[] x = parse(a), y = parse(b);
        if (x == null || y == null) return false;
        for (int i = 0; i < x.length; i++) if (x[i] != y[i]) return x[i] > y[i];
        return false;
    }

    @Nullable
    private static long[] parse(final String v) {
        final Matcher m = VERSION.matcher(v.trim());
        if (!m.find()) return null;
        return new long[] { Long.parseLong(m.group(1)), Long.parseLong(m.group(2)), Long.parseLong(m.group(3)),
            m.group(4) == null ? STAGES.size() : STAGES.indexOf(m.group(4)),
            m.group(5) == null ? 0 : Long.parseLong(m.group(5)) };
    }

    private static byte[] readAll(final InputStream in) throws java.io.IOException {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0;) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
