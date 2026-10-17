package com.gtnhplanner.client;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.importer.FfPlanCode;

/**
 * The website's "Open in the mod": a page on the website hands the running game a plan code over a tiny HTTP server on
 * the loopback address, the only way a browser reaches a Java process. It never opens anything itself: a plan that
 * arrives goes to {@code arrivals}, and the game asks the player first ({@link WebsiteInbox}).
 *
 * <p>
 * What it takes, and from whom:
 * <ul>
 * <li>Bound to 127.0.0.1 only, on the first free port of {@link #FIRST_PORT} to {@link #LAST_PORT} (the website tries
 * them all), so nothing off this computer reaches it.</li>
 * <li>{@code GET /ping} answers the mod's name and version; {@code POST /open} takes {@code {"code": "gtnh1...",
 * "name": "..."}} as {@code application/json}, at most {@link #MAX_BODY} bytes, a code that decodes to a JSON
 * object.</li>
 * <li>Only pages of the website's origins ({@code allowedOrigin}) are answered: their CORS and Private Network Access
 * preflights, and the requests themselves. A request from any other page, or with no Origin, is refused, so other
 * sites can neither read the ping nor send a plan. The Host must name the loopback address (no DNS rebinding).</li>
 * <li>One request a connection, read with a short timeout on one daemon thread.</li>
 * </ul>
 */
public final class WebsiteListener implements Closeable {

    /** The ports tried, in order: the website probes the same range. */
    public static final int FIRST_PORT = 25640, LAST_PORT = 25649;
    /** The largest body taken: a plan code of a few thousand cards, with room to spare. */
    public static final int MAX_BODY = 4 << 20;
    /** The longest name kept for the prompt. */
    static final int MAX_NAME = 64;

    private static final int MAX_HEADERS = 16 << 10;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final Pattern CODE = Pattern.compile("gtnh1\\.[A-Za-z0-9_-]+");
    private static final Pattern LOOPBACK_HOST = Pattern.compile("(127\\.0\\.0\\.1|localhost|\\[::1\\])(:\\d+)?");

    /** A plan the website sent: the name it goes by and its plan code. */
    public record Arrival(String name, String code) {}

    private final ServerSocket server;
    private final String version;
    private final Predicate<String> allowedOrigin;
    private final Consumer<Arrival> arrivals;
    private final Thread thread;
    private volatile boolean closed;

    private WebsiteListener(final ServerSocket server, final String version, final Predicate<String> allowedOrigin,
        final Consumer<Arrival> arrivals) {
        this.server = server;
        this.version = version;
        this.allowedOrigin = allowedOrigin;
        this.arrivals = arrivals;
        thread = new Thread(this::run, "GTNH Planner website link");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Listens on the first free port from {@code first} to {@code last}, or returns null when every one is taken (a
     * second game running, or other software on them).
     */
    @Nullable
    public static WebsiteListener start(final int first, final int last, final String version,
        final Predicate<String> allowedOrigin, final Consumer<Arrival> arrivals) {
        final InetAddress loopback = InetAddress.getLoopbackAddress();
        for (int port = first; port <= last; port++) {
            final ServerSocket socket;
            try {
                socket = new ServerSocket();
                // Never share a port with another listener: two games must not both answer one port.
                socket.setReuseAddress(false);
                socket.bind(new InetSocketAddress(loopback, port), 8);
            } catch (final IOException e) {
                continue;
            }
            return new WebsiteListener(socket, version, allowedOrigin, arrivals);
        }
        return null;
    }

    /** The website's own pages: the site, and a copy of it served on this computer for development. */
    public static boolean websiteOrigin(final String origin, final String site) {
        if (origin == null) return false;
        if (origin.equals("https://gtnhplanner.com") || origin.equals("https://www.gtnhplanner.com")) return true;
        if (origin.equals(originOf(site))) return true;
        return origin.matches("http://(localhost|127\\.0\\.0\\.1)(:\\d{1,5})?");
    }

    /** The scheme, host and port of a URL, as a browser sends them in Origin. */
    static String originOf(final String url) {
        final int scheme = url.indexOf("://");
        if (scheme < 0) return url;
        final int path = url.indexOf('/', scheme + 3);
        return path < 0 ? url : url.substring(0, path);
    }

    public int port() {
        return server.getLocalPort();
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (final IOException ignored) {}
    }

    private void run() {
        while (!closed) {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(READ_TIMEOUT_MS);
                handle(socket.getInputStream(), socket.getOutputStream());
            } catch (final SocketException e) {
                if (closed) return;
            } catch (final IOException | RuntimeException e) {
                // A bad request is that caller's problem; the listener carries on.
            }
        }
    }

    /** One request: read, checked, answered. Package-private for the tests. */
    void handle(final InputStream in, final OutputStream out) throws IOException {
        final String head = readHead(in);
        if (head == null) {
            respond(out, 400, null, null, "{\"error\":\"bad request\"}");
            return;
        }
        final String[] lines = head.split("\r\n");
        final String[] start = lines[0].split(" ");
        if (start.length != 3) {
            respond(out, 400, null, null, "{\"error\":\"bad request\"}");
            return;
        }
        final String method = start[0];
        String path = start[1];
        final int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        final Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            final int colon = lines[i].indexOf(':');
            if (colon <= 0) continue;
            headers.put(
                lines[i].substring(0, colon)
                    .trim()
                    .toLowerCase(Locale.ROOT),
                lines[i].substring(colon + 1)
                    .trim());
        }
        // A page of another site that resolves its own name to 127.0.0.1 (DNS rebinding) sends its own Host.
        final String host = headers.get("host");
        if (host == null || !LOOPBACK_HOST.matcher(host.toLowerCase(Locale.ROOT))
            .matches()) {
            respond(out, 403, null, null, "{\"error\":\"host\"}");
            return;
        }
        final String origin = headers.get("origin");
        if (origin == null || !allowedOrigin.test(origin)) {
            respond(out, 403, null, null, "{\"error\":\"origin\"}");
            return;
        }
        if ("OPTIONS".equals(method)) {
            final Map<String, String> extra = new HashMap<>();
            extra.put("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            extra.put("Access-Control-Allow-Headers", "Content-Type");
            extra.put("Access-Control-Max-Age", "600");
            if ("true".equalsIgnoreCase(headers.get("access-control-request-private-network")))
                extra.put("Access-Control-Allow-Private-Network", "true");
            respond(out, 204, origin, extra, null);
            return;
        }
        if ("GET".equals(method) && "/ping".equals(path)) {
            final JsonObject o = new JsonObject();
            o.addProperty("app", "gtnhplanner");
            o.addProperty("version", version);
            respond(out, 200, origin, null, o.toString());
            return;
        }
        if (!"/open".equals(path)) {
            respond(out, 404, origin, null, "{\"error\":\"not found\"}");
            return;
        }
        if (!"POST".equals(method)) {
            respond(out, 405, origin, null, "{\"error\":\"method\"}");
            return;
        }
        // JSON only: a form or a plain-text post would skip the browser's preflight.
        final String type = headers.getOrDefault("content-type", "")
            .toLowerCase(Locale.ROOT);
        if (!type.startsWith("application/json")) {
            respond(out, 415, origin, null, "{\"error\":\"json only\"}");
            return;
        }
        final long length;
        try {
            length = Long.parseLong(headers.getOrDefault("content-length", "-1"));
        } catch (final NumberFormatException e) {
            respond(out, 411, origin, null, "{\"error\":\"length\"}");
            return;
        }
        if (length < 0 || headers.containsKey("transfer-encoding")) {
            respond(out, 411, origin, null, "{\"error\":\"length\"}");
            return;
        }
        if (length > MAX_BODY) {
            respond(out, 413, origin, null, "{\"error\":\"too big\"}");
            return;
        }
        final byte[] body = in.readNBytes((int) length);
        if (body.length != length) {
            respond(out, 400, origin, null, "{\"error\":\"cut short\"}");
            return;
        }
        final Arrival arrival;
        try {
            arrival = arrival(new String(body, StandardCharsets.UTF_8));
        } catch (final RuntimeException e) {
            respond(out, 400, origin, null, "{\"error\":\"not a plan\"}");
            return;
        }
        arrivals.accept(arrival);
        respond(out, 202, origin, null, "{\"status\":\"asked\"}");
    }

    /** The plan in a request's body: its code checked by decoding it, and its name, cleaned for the prompt. */
    static Arrival arrival(final String body) {
        final JsonElement parsed = new JsonParser().parse(body);
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("not an object");
        final JsonObject o = parsed.getAsJsonObject();
        final String code = o.has("code") && o.get("code")
            .isJsonPrimitive() ? o.get("code")
                .getAsString() : "";
        if (!CODE.matcher(code)
            .matches()) throw new IllegalArgumentException("not a plan code");
        final JsonElement plan = new JsonParser().parse(FfPlanCode.decode(code));
        if (!plan.isJsonObject()) throw new IllegalArgumentException("not a plan");
        String name = o.has("name") && o.get("name")
            .isJsonPrimitive() ? o.get("name")
                .getAsString() : "";
        final JsonObject p = plan.getAsJsonObject();
        if (name.isBlank() && p.has("name")
            && p.get("name")
                .isJsonPrimitive())
            name = p.get("name")
                .getAsString();
        return new Arrival(cleanName(name), code);
    }

    /** A name fit to show: no control characters or the game's colour codes, one line, not too long. */
    static String cleanName(final String name) {
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < name.length() && b.length() < MAX_NAME; i++) {
            final char c = name.charAt(i);
            if (c == '§') {
                // A colour code is the sign and the character after it.
                i++;
                continue;
            }
            if (Character.isISOControl(c)) continue;
            b.append(c);
        }
        final String out = b.toString()
            .trim();
        return out.isEmpty() ? "Untitled plan" : out;
    }

    /** The request line and headers, up to the blank line; null when there is none within the limit. */
    @Nullable
    private static String readHead(final InputStream in) throws IOException {
        final ByteArrayOutputStream head = new ByteArrayOutputStream(512);
        // The last four bytes read, newest lowest: the head ends at CR LF CR LF.
        int last = 0;
        while (head.size() < MAX_HEADERS) {
            final int b = in.read();
            if (b < 0) return null;
            head.write(b);
            last = (last << 8) | b;
            if (last == 0x0D0A0D0A) {
                final String s = head.toString(StandardCharsets.ISO_8859_1.name());
                return s.substring(0, s.length() - 4);
            }
        }
        return null;
    }

    private static void respond(final OutputStream out, final int status, @Nullable final String origin,
        @Nullable final Map<String, String> extra, @Nullable final String json) throws IOException {
        final StringBuilder b = new StringBuilder("HTTP/1.1 ").append(status)
            .append(' ')
            .append(reason(status))
            .append("\r\n");
        if (origin != null) {
            b.append("Access-Control-Allow-Origin: ")
                .append(origin)
                .append("\r\n");
            b.append("Vary: Origin\r\n");
        }
        if (extra != null) for (final Map.Entry<String, String> e : extra.entrySet()) b.append(e.getKey())
            .append(": ")
            .append(e.getValue())
            .append("\r\n");
        final byte[] body = json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8);
        if (json != null) b.append("Content-Type: application/json\r\n");
        b.append("Cache-Control: no-store\r\n");
        b.append("Content-Length: ")
            .append(body.length)
            .append("\r\nConnection: close\r\n\r\n");
        out.write(
            b.toString()
                .getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static String reason(final int status) {
        return switch (status) {
            case 200 -> "OK";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 411 -> "Length Required";
            case 413 -> "Payload Too Large";
            case 415 -> "Unsupported Media Type";
            default -> "Error";
        };
    }
}
