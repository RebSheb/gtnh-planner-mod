package com.gtnhplanner.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.cloud.net.CloudApi;
import com.gtnhplanner.library.CommunityApi;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** The cloud plan routes against a stand-in for the site on this machine: paths, session, bodies and errors. */
class CloudApiTest {

    private record Seen(String method, String path, String cookie, String body) {}

    private HttpServer server;
    private final List<Seen> seen = new ArrayList<>();
    /** "METHOD path?query" to status and body. */
    private final Map<String, String[]> answers = new ConcurrentHashMap<>();
    private final String site = CommunityApi.site();
    private final String live = CloudApi.live();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::answer);
        server.start();
        CommunityApi.useSite(
            "http://127.0.0.1:" + server.getAddress()
                .getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
        CommunityApi.useSite(site);
        CloudApi.useLive(null);
    }

    private void answer(final HttpExchange ex) throws IOException {
        final String path = ex.getRequestURI()
            .toString();
        synchronized (seen) {
            seen.add(
                new Seen(
                    ex.getRequestMethod(),
                    path,
                    ex.getRequestHeaders()
                        .getFirst("Cookie"),
                    new String(
                        ex.getRequestBody()
                            .readAllBytes(),
                        StandardCharsets.UTF_8)));
        }
        final String[] a = answers
            .getOrDefault(ex.getRequestMethod() + " " + path, new String[] { "404", "{\"error\":\"nothing here\"}" });
        final byte[] out = a[1].getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders()
            .add("Content-Type", "application/json");
        ex.sendResponseHeaders(Integer.parseInt(a[0]), out.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(out);
        }
    }

    private Seen last() {
        synchronized (seen) {
            return seen.get(seen.size() - 1);
        }
    }

    private void on(final String route, final int status, final String body) {
        answers.put(route, new String[] { String.valueOf(status), body });
    }

    private static CloudApi.Upsert upsert(final JsonObject plan) {
        return new CloudApi.Upsert(
            "Smelter",
            null,
            null,
            false,
            false,
            null,
            "2026-01-01T00:00:00.000Z",
            "2026-01-02T00:00:00.000Z",
            "2026-01-02T00:00:00.000Z",
            plan);
    }

    @Test
    void theLiveAddressFollowsTheSite() {
        assertEquals("wss://gtnhplanner.com/live", CloudApi.liveFor("https://gtnhplanner.com"));
        assertEquals("wss://example.org:8443/live", CloudApi.liveFor("https://example.org:8443"));
        assertEquals("ws://localhost:3001/live", CloudApi.liveFor("http://localhost:3000"));
        assertEquals("ws://127.0.0.1:8790/live", CloudApi.liveFor("http://127.0.0.1:8789"));
        assertEquals("ws://staging.test/live", CloudApi.liveFor("http://staging.test"));
        assertTrue(
            CloudApi.live()
                .startsWith("ws://127.0.0.1:"));
        CloudApi.useLive("ws://elsewhere:9/live/");
        assertEquals("ws://elsewhere:9/live", CloudApi.live());
        CloudApi.useLive("");
        assertTrue(
            CloudApi.live()
                .startsWith("ws://127.0.0.1:"));
        assertNotNull(live);
    }

    @Test
    void whoAmI() throws IOException {
        on("GET /api/community/auth/me", 200, "{\"user\":{\"id\":\"u1\",\"username\":\"ada\"}}");
        final CloudApi.Account me = CloudApi.me("tok");
        assertEquals("u1", me.id());
        assertEquals("ada", me.username());
        assertEquals("gtnh_session=tok", last().cookie());
        on("GET /api/community/auth/me", 200, "{\"user\":null}");
        assertNull(CloudApi.me("tok"));
    }

    @Test
    void anExpiredSessionIsNullAndAnAccountWithoutAnIdIsAnError() throws IOException {
        on("GET /api/community/auth/me", 401, "{\"error\":\"no\"}");
        assertNull(CloudApi.me("tok"));
        on("GET /api/community/auth/me", 200, "{\"user\":{\"username\":\"ada\"}}");
        assertThrows(IOException.class, () -> CloudApi.me("tok"));
        assertThrows(IOException.class, () -> CloudApi.library("tok", ""));
    }

    @Test
    void aSharedPlansMembersAreAskedForByOwner() throws IOException {
        on("GET /api/library/designs/d1/members?owner=u9", 200, "{\"members\":[]}");
        assertTrue(
            CloudApi.members("tok", "d1", "u9")
                .isEmpty());
        on("PUT /api/library/designs/d1/members/u3?owner=u9", 200, "{}");
        CloudApi.addMember("tok", "d1", "u3", "u9");
        assertEquals("/api/library/designs/d1/members/u3?owner=u9", last().path());
    }

    @Test
    void theLibraryHoldsOwnAndSharedPlans() throws IOException {
        on("GET /api/library", 200, """
            {"designs":[{"id":"d1","name":"Mine","icon":{"kind":"item","resourceId":"x"},"folderId":null,"closed":true,
              "favorite":false,"order":3,"createdAt":"c","updatedAt":"u","planUpdatedAt":"p","deletedAt":null,
              "members":[{"id":"u2","username":"bob"}]},
             {"id":"d2","name":"Gone","closed":false,"deletedAt":"t"}],
             "folders":[],
             "shared":[{"ownerId":"u9","ownerName":"cy","id":"s1","name":"Theirs","updatedAt":"u","planUpdatedAt":"p",
              "members":[{"id":"u1","username":"ada"}]}]}""");
        final CloudApi.Library lib = CloudApi.library("tok", "u1");
        assertEquals(
            2,
            lib.own()
                .size());
        final CloudApi.Design mine = lib.own()
            .get(0);
        assertEquals("u1:d1", mine.key());
        assertEquals("Mine", mine.name());
        assertTrue(mine.closed());
        assertEquals(3, mine.order());
        assertEquals(
            "item",
            mine.icon()
                .get("kind")
                .getAsString());
        assertEquals(
            "bob",
            mine.members()
                .get(0)
                .username());
        assertFalse(mine.shared());
        assertTrue(
            lib.own()
                .get(1)
                .deleted());
        final CloudApi.Design theirs = lib.shared()
            .get(0);
        assertEquals("u9:s1", theirs.key());
        assertEquals("cy", theirs.ownerName());
        assertTrue(theirs.shared());
        // The cache keeps it as JSON and gets the same thing back.
        final CloudApi.Library again = CloudApi.Library.fromJson(lib.toJson());
        assertEquals(
            lib.own()
                .get(0),
            again.own()
                .get(0));
        assertEquals(
            theirs,
            again.shared()
                .get(0));
    }

    @Test
    void aPlanComesWithItsOwnerNamed() throws IOException {
        on(
            "GET /api/library/designs/s1?owner=u9",
            200,
            "{\"design\":{\"id\":\"s1\",\"name\":\"Theirs\"},\"plan\":{\"nodes\":[]}}");
        final CloudApi.Fetched f = CloudApi.plan("tok", "u9", "s1");
        assertEquals(
            "u9:s1",
            f.design()
                .key());
        assertTrue(
            f.plan()
                .has("nodes"));
        on("GET /api/library/designs/s2?owner=u9", 404, "{\"error\":\"Design not found.\"}");
        final CloudApi.Refused gone = assertThrows(CloudApi.Refused.class, () -> CloudApi.plan("tok", "u9", "s2"));
        assertEquals(404, gone.status);
        assertEquals("Design not found.", gone.getMessage());
    }

    @Test
    void savingSendsTheWholeUpsertAndReadsTheAnswer() throws IOException {
        on(
            "PUT /api/library/designs/d1",
            200,
            "{\"design\":{\"id\":\"d1\",\"name\":\"Smelter\",\"updatedAt\":\"u\"},\"behind\":false}");
        final JsonObject plan = new JsonObject();
        plan.addProperty("version", 1);
        final CloudApi.Put put = CloudApi.put("tok", "d1", upsert(plan));
        assertFalse(put.live());
        assertFalse(put.behind());
        assertEquals(
            "Smelter",
            put.design()
                .name());
        final Seen s = last();
        assertEquals("PUT", s.method());
        assertEquals("gtnh_session=tok", s.cookie());
        final JsonObject body = new JsonParser().parse(s.body())
            .getAsJsonObject();
        assertEquals(
            "Smelter",
            body.get("name")
                .getAsString());
        assertEquals(
            1,
            body.getAsJsonObject("plan")
                .get("version")
                .getAsInt());
        assertTrue(
            body.get("folderId")
                .isJsonNull());

        on("PUT /api/library/designs/d1", 200, "{\"design\":{\"id\":\"d1\",\"name\":\"Newer\"},\"behind\":true}");
        assertTrue(
            CloudApi.put("tok", "d1", upsert(null))
                .behind());
        assertFalse(
            new JsonParser().parse(last().body())
                .getAsJsonObject()
                .has("plan"));
    }

    @Test
    void aLivePlanRefusesItsBodyAsItsOwnResult() throws IOException {
        on("PUT /api/library/designs/d1", 409, "{\"live\":true,\"error\":\"live\"}");
        final CloudApi.Put put = CloudApi.put("tok", "d1", upsert(new JsonObject()));
        assertTrue(put.live());
        assertNull(put.design());
        on(
            "PUT /api/library/designs/d1",
            409,
            "{\"error\":\"Your library is full (500 designs). Delete some first.\"}");
        final CloudApi.Refused full = assertThrows(
            CloudApi.Refused.class,
            () -> CloudApi.put("tok", "d1", upsert(new JsonObject())));
        assertEquals(409, full.status);
        assertTrue(
            full.getMessage()
                .startsWith("Your library is full"));
    }

    @Test
    void errorsCarryTheSitesWordsOrTheStatus() {
        on("PUT /api/library/designs/d1", 429, "not json");
        assertEquals(
            "too many tries; wait a little",
            assertThrows(CloudApi.Refused.class, () -> CloudApi.put("tok", "d1", upsert(null))).getMessage());
        on("DELETE /api/library/designs/d1", 401, "");
        assertEquals(
            "not signed in",
            assertThrows(CloudApi.Refused.class, () -> CloudApi.delete("tok", "d1")).getMessage());
        on("DELETE /api/library/designs/d1", 500, "");
        assertEquals(
            "the site answered 500",
            assertThrows(CloudApi.Refused.class, () -> CloudApi.delete("tok", "d1")).getMessage());
    }

    @Test
    void deleting() throws IOException {
        on("DELETE /api/library/designs/d1", 200, "{\"ok\":true}");
        CloudApi.delete("tok", "d1");
        assertEquals("DELETE", last().method());
    }

    @Test
    void members() throws IOException {
        on("GET /api/library/designs/d1/members", 200, "{\"members\":[{\"id\":\"u2\",\"username\":\"bob\"}]}");
        assertEquals(
            "bob",
            CloudApi.members("tok", "d1", null)
                .get(0)
                .username());
        on("PUT /api/library/designs/d1/members/u3", 200, "{}");
        CloudApi.addMember("tok", "d1", "u3", null);
        assertEquals("PUT", last().method());
        on("DELETE /api/library/designs/d1/members/u3", 200, "{}");
        CloudApi.removeMember("tok", "d1", "u3", null);
        on("DELETE /api/library/designs/d1/members/u1?owner=u9", 200, "{}");
        CloudApi.removeMember("tok", "d1", "u1", "u9");
        assertEquals("/api/library/designs/d1/members/u1?owner=u9", last().path());
        on("PUT /api/library/designs/d1/members/u4", 403, "{\"error\":\"Only the owner can share this.\"}");
        assertEquals(
            "Only the owner can share this.",
            assertThrows(CloudApi.Refused.class, () -> CloudApi.addMember("tok", "d1", "u4", null)).getMessage());
    }

    @Test
    void findingAccounts() throws IOException {
        on("GET /api/accounts/search?q=a%26b", 200, "{\"accounts\":[{\"id\":\"u2\",\"username\":\"a&bc\"}]}");
        final List<CloudApi.Account> found = CloudApi.search("tok", "a&b");
        assertEquals(1, found.size());
        assertEquals(
            "a&bc",
            found.get(0)
                .username());
        on("GET /api/accounts/search?q=z", 200, "[]");
        assertTrue(
            CloudApi.search("tok", "z")
                .isEmpty());
    }

    @Test
    void nearbyAsksTheLiveServicesHttpWithTheNamesThisGameSees() throws IOException {
        CloudApi.useLive(
            "ws://127.0.0.1:" + server.getAddress()
                .getPort() + "/live");
        on(
            "GET /live/nearby?names=Dee_Mines%2CAda",
            200,
            "{\"accounts\":[{\"id\":\"u5\",\"username\":\"dee\",\"mcName\":\"Dee_Mines\"}]}");
        final List<CloudApi.Player> near = CloudApi.nearby("tok", List.of("Dee_Mines", " ", "Ada"));
        assertEquals(new CloudApi.Player("u5", "dee", "Dee_Mines"), near.get(0));
        assertEquals("gtnh_session=tok", last().cookie());
        // At most a hundred names go.
        final List<String> many = new ArrayList<>();
        for (int i = 0; i < 150; i++) many.add("p" + i);
        on("GET /live/nearby?names=" + String.join("%2C", many.subList(0, 100)), 200, "{\"accounts\":[]}");
        assertTrue(
            CloudApi.nearby("tok", many)
                .isEmpty());
        final String asked = java.net.URLDecoder.decode(
            last().path()
                .substring("/live/nearby?names=".length()),
            StandardCharsets.UTF_8);
        assertEquals(100, asked.split(",").length);
        assertTrue(asked.endsWith(",p99"));
    }

    @Test
    void aPlansSharing() throws IOException {
        on(
            "GET /api/library/designs/d1/members?owner=u9",
            200,
            "{\"ownerId\":\"u9\",\"ownerName\":\"zed\",\"role\":\"editor\",\"members\":[{\"id\":\"u1\",\"username\":\"ada\"}]}");
        final CloudApi.Sharing s = CloudApi.sharing("tok", "d1", "u9");
        assertEquals("zed", s.ownerName());
        assertEquals("editor", s.role());
        assertEquals(
            "ada",
            s.members()
                .get(0)
                .username());
    }
}
