package com.gtnhplanner.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.gtnhplanner.importer.FfPlanCode;

/**
 * The website's "Open in the mod" listener: what it answers, and above all what it refuses. Requests are fed straight
 * to the handler, and one test talks to it over a real socket.
 */
class WebsiteListenerTest {

    private static final String SITE = "https://gtnhplanner.com";
    private static final String PLAN = "{\"version\":1,\"name\":\"Steel line\",\"nodes\":[],\"edges\":[]}";

    private final List<WebsiteListener.Arrival> arrived = new ArrayList<>();

    private WebsiteListener listener() {
        final WebsiteListener l = WebsiteListener.start(
            WebsiteListener.FIRST_PORT,
            WebsiteListener.LAST_PORT,
            "test",
            origin -> WebsiteListener.websiteOrigin(origin, SITE),
            arrived::add);
        assertNotNull(l, "a port in the range is free");
        return l;
    }

    private static String send(final WebsiteListener l, final String request) throws IOException {
        final InputStream in = new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8));
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        l.handle(in, out);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static String post(final String origin, final String type, final String body) {
        final int length = body.getBytes(StandardCharsets.UTF_8).length;
        return "POST /open HTTP/1.1\r\nHost: 127.0.0.1:25640\r\n" + (origin == null ? "" : "Origin: " + origin + "\r\n")
            + "Content-Type: "
            + type
            + "\r\nContent-Length: "
            + length
            + "\r\n\r\n"
            + body;
    }

    private static String body(final String code, final String name) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}";
    }

    @Test
    void aPlanCodeTheModWritesIsOneTheWebsiteFormatDecodes() {
        final String code = FfPlanCode.encode(PLAN);
        assertTrue(code.startsWith("gtnh1."));
        assertTrue(
            code.substring(6)
                .matches("[A-Za-z0-9_-]+"),
            "URL-safe base64 without padding");
        assertEquals(PLAN, FfPlanCode.decode(code));
        assertEquals(PLAN, FfPlanCode.decode(FfPlanCode.link(SITE, code)));
        assertEquals(SITE + "/#p=" + code, FfPlanCode.link(SITE, code));
    }

    @Test
    void theWebsitesPlanGoesToTheInboxAndNothingOpens() throws IOException {
        try (WebsiteListener l = listener()) {
            final String answer = send(l, post(SITE, "application/json", body(FfPlanCode.encode(PLAN), "My plan")));
            assertTrue(answer.startsWith("HTTP/1.1 202"), answer);
            assertTrue(answer.contains("Access-Control-Allow-Origin: " + SITE), answer);
            assertEquals(1, arrived.size());
            assertEquals(
                "My plan",
                arrived.get(0)
                    .name());
        }
    }

    @Test
    void aPlanWithNoNameGoesByItsOwn() throws IOException {
        try (WebsiteListener l = listener()) {
            send(l, post(SITE, "application/json", "{\"code\":\"" + FfPlanCode.encode(PLAN) + "\"}"));
            assertEquals(
                "Steel line",
                arrived.get(0)
                    .name());
        }
    }

    @Test
    void otherSitesAndNoOriginAreRefused() throws IOException {
        try (WebsiteListener l = listener()) {
            final String code = FfPlanCode.encode(PLAN);
            for (final String origin : new String[] { "https://evil.example", "https://gtnhplanner.com.evil.example",
                "http://gtnhplanner.com", "null", "https://localhost" }) {
                final String answer = send(l, post(origin, "application/json", body(code, "x")));
                assertTrue(answer.startsWith("HTTP/1.1 403"), origin + ": " + answer);
                assertFalse(answer.contains("Access-Control-Allow-Origin"), origin);
            }
            assertTrue(
                send(l, post(null, "application/json", body(code, "x"))).startsWith("HTTP/1.1 403"),
                "no Origin");
            final String ping = send(
                l,
                "GET /ping HTTP/1.1\r\nHost: 127.0.0.1:25640\r\nOrigin: https://evil.example\r\n\r\n");
            assertTrue(ping.startsWith("HTTP/1.1 403"), "another site cannot read the ping");
            assertTrue(arrived.isEmpty());
        }
    }

    @Test
    void aHostOtherThanLoopbackIsRefused() throws IOException {
        try (WebsiteListener l = listener()) {
            final String answer = send(
                l,
                post(SITE, "application/json", body(FfPlanCode.encode(PLAN), "x"))
                    .replace("Host: 127.0.0.1:25640", "Host: rebind.evil.example:25640"));
            assertTrue(answer.startsWith("HTTP/1.1 403"), answer);
            assertTrue(arrived.isEmpty());
        }
    }

    @Test
    void onlyJsonIsTaken() throws IOException {
        try (WebsiteListener l = listener()) {
            for (final String type : new String[] { "text/plain", "application/x-www-form-urlencoded",
                "multipart/form-data; boundary=x" }) {
                final String answer = send(l, post(SITE, type, body(FfPlanCode.encode(PLAN), "x")));
                assertTrue(answer.startsWith("HTTP/1.1 415"), type + ": " + answer);
            }
            assertTrue(arrived.isEmpty());
        }
    }

    @Test
    void notAPlanIsRefused() throws IOException {
        try (WebsiteListener l = listener()) {
            for (final String b : new String[] { "{}", "[]", "{\"code\":\"gtnh1.!!\"}", "{\"code\":\"gtnh1.AAAA\"}",
                "{\"code\":\"" + FfPlanCode.encode("[1,2]") + "\"}", "not json", "{\"code\":123}" }) {
                final String answer = send(l, post(SITE, "application/json", b));
                assertTrue(answer.startsWith("HTTP/1.1 400"), b + ": " + answer);
            }
            assertTrue(arrived.isEmpty());
        }
    }

    @Test
    void aBodyOverTheCapIsRefusedUnread() throws IOException {
        try (WebsiteListener l = listener()) {
            final String head = "POST /open HTTP/1.1\r\nHost: localhost:25641\r\nOrigin: " + SITE
                + "\r\nContent-Type: application/json\r\nContent-Length: "
                + (WebsiteListener.MAX_BODY + 1)
                + "\r\n\r\n";
            assertTrue(send(l, head).startsWith("HTTP/1.1 413"));
            final String chunked = "POST /open HTTP/1.1\r\nHost: localhost\r\nOrigin: " + SITE
                + "\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n";
            assertTrue(send(l, chunked).startsWith("HTTP/1.1 411"));
        }
    }

    @Test
    void preflightsAnswerCorsAndPrivateNetworkAccessForTheWebsiteOnly() throws IOException {
        try (WebsiteListener l = listener()) {
            final String ok = send(
                l,
                "OPTIONS /open HTTP/1.1\r\nHost: 127.0.0.1:25640\r\nOrigin: " + SITE
                    + "\r\nAccess-Control-Request-Method: POST\r\nAccess-Control-Request-Headers: content-type"
                    + "\r\nAccess-Control-Request-Private-Network: true\r\n\r\n");
            assertTrue(ok.startsWith("HTTP/1.1 204"), ok);
            assertTrue(ok.contains("Access-Control-Allow-Origin: " + SITE));
            assertTrue(ok.contains("Access-Control-Allow-Private-Network: true"));
            assertTrue(ok.contains("Access-Control-Allow-Headers: Content-Type"));
            final String dev = send(
                l,
                "OPTIONS /ping HTTP/1.1\r\nHost: 127.0.0.1:25640\r\nOrigin: http://localhost:3231\r\n\r\n");
            assertTrue(dev.startsWith("HTTP/1.1 204"), "the website served locally for development");
            assertFalse(dev.contains("Private-Network"), "only when asked");
            final String evil = send(
                l,
                "OPTIONS /open HTTP/1.1\r\nHost: 127.0.0.1:25640\r\nOrigin: https://evil.example"
                    + "\r\nAccess-Control-Request-Private-Network: true\r\n\r\n");
            assertTrue(evil.startsWith("HTTP/1.1 403"), evil);
            assertFalse(evil.contains("Access-Control-Allow"));
        }
    }

    @Test
    void namesAreCleanedForThePrompt() {
        assertEquals("Red plan", WebsiteListener.cleanName("§cRed\u0000 plan\n"));
        assertEquals("Untitled plan", WebsiteListener.cleanName("  § "));
        assertEquals(
            WebsiteListener.MAX_NAME,
            WebsiteListener.cleanName("x".repeat(500))
                .length());
    }

    @Test
    void pingsOverARealSocketAndTriesTheNextPortWhenOneIsTaken() throws IOException {
        try (ServerSocket taken = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            final int first = taken.getLocalPort();
            try (WebsiteListener l = WebsiteListener.start(
                first,
                first + 5,
                "1.2.3",
                origin -> WebsiteListener.websiteOrigin(origin, SITE),
                arrived::add)) {
                assertNotNull(l);
                assertTrue(l.port() > first, "skipped the taken port");
                try (Socket s = new Socket(InetAddress.getLoopbackAddress(), l.port())) {
                    final OutputStream out = s.getOutputStream();
                    out.write(
                        ("GET /ping HTTP/1.1\r\nHost: 127.0.0.1:" + l.port() + "\r\nOrigin: " + SITE + "\r\n\r\n")
                            .getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    final String answer = new String(
                        s.getInputStream()
                            .readAllBytes(),
                        StandardCharsets.UTF_8);
                    assertTrue(answer.startsWith("HTTP/1.1 200"), answer);
                    assertTrue(answer.contains("\"app\":\"gtnhplanner\""));
                    assertTrue(answer.contains("\"version\":\"1.2.3\""));
                }
            }
        }
        assertNull(WebsiteListener.start(1, 0, "x", o -> true, a -> {}), "an empty range listens nowhere");
    }

    @Test
    void theRedirectPageGoesOnToTheLinkAndCannotBeBrokenOutOf() {
        final String link = SITE + "/#p=gtnh1.abc_-";
        final String page = Browser.redirectPage(link);
        assertTrue(page.contains("location.replace(\"" + link + "\")"), page);
        final String hostile = Browser.redirectPage("https://x/#</script><script>alert(1)</script>");
        assertFalse(hostile.contains("</script><script>alert"), hostile);
    }

    @Test
    void theModsOwnExportOfARealPlanMakesACodeThatComesBack() throws IOException {
        final Path fixture = Path.of("src/test/resources/factory-flow/titanium-line-posted-alpha5.json");
        final String json = Files.readString(fixture);
        final String code = FfPlanCode.encode(json);
        assertEquals(json, FfPlanCode.decode(code));
        // Over the 2,000 a command line is trusted with: it goes through the redirect page.
        assertTrue(code.length() > Browser.DIRECT_MAX && code.length() < Browser.LINK_MAX, "" + code.length());
    }
}
