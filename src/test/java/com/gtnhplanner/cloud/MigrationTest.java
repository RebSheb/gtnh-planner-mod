package com.gtnhplanner.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.cloud.store.Migration;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Note;
import com.gtnhplanner.data.flowchart.Plan;
import com.gtnhplanner.data.flowchart.Serializer;
import com.gtnhplanner.library.CommunityApi;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Local plans going up as cloud plans once each, with the save left as it was but for the slot's key. */
class MigrationTest {

    private HttpServer server;
    private final String site = CommunityApi.site();
    /** The ids the stand-in holds, and every PUT it took, in order. */
    private final Set<String> held = new HashSet<>();
    private final List<String> puts = new ArrayList<>();
    private final List<JsonObject> bodies = new ArrayList<>();
    private String refuse = "";

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
    }

    private void answer(final HttpExchange ex) throws IOException {
        final String path = ex.getRequestURI()
            .getPath();
        final String body = new String(
            ex.getRequestBody()
                .readAllBytes(),
            StandardCharsets.UTF_8);
        int status = 200;
        String out = "{}";
        if (path.equals("/api/library")) {
            final StringBuilder sb = new StringBuilder("{\"designs\":[");
            for (final String id : held) sb.append(sb.length() > 12 ? "," : "")
                .append("{\"id\":\"")
                .append(id)
                .append("\",\"name\":\"x\"}");
            out = sb.append("],\"shared\":[]}")
                .toString();
        } else if (path.startsWith("/api/library/designs/") && ex.getRequestMethod()
            .equals("PUT")) {
                final JsonObject o = new JsonParser().parse(body)
                    .getAsJsonObject();
                if (o.get("name")
                    .getAsString()
                    .equals(refuse)) {
                    status = 400;
                    out = "{\"error\":\"The plan is not valid.\"}";
                } else {
                    final String id = path.substring("/api/library/designs/".length());
                    held.add(id);
                    puts.add(id);
                    bodies.add(o);
                    out = "{\"design\":{\"id\":\"" + id + "\",\"name\":\"x\"},\"behind\":false}";
                }
            }
        final byte[] b = out.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(b);
        }
    }

    private static Graph plan(final String name) {
        final Graph g = new Graph(name);
        g.notes.put(UUID.randomUUID(), new Note());
        return g;
    }

    private static Plan plansOf(final Graph... graphs) {
        final Plan p = Serializer.decodePlan("{}");
        p.getGraphs()
            .clear();
        for (final Graph g : graphs) p.getGraphs()
            .add(g);
        return p;
    }

    private static JsonObject mapped(final Graph g) {
        final JsonObject o = new JsonObject();
        o.addProperty("name", g.getName());
        return o;
    }

    @Test
    void eachPlanGoesUpOnceAndKeepsItsKey() throws IOException {
        final Graph a = plan("Smelter"), b = plan("Reactor"), empty = new Graph("Slot 3");
        b.setOpen(false);
        final Plan plan = plansOf(a, b, empty);
        final AtomicInteger backups = new AtomicInteger(), saves = new AtomicInteger();

        final Migration.Result r = Migration.run(
            plan,
            "u1",
            "tok",
            MigrationTest::mapped,
            () -> backups.incrementAndGet() > 0,
            () -> saves.incrementAndGet() > 0);

        assertNotNull(r);
        assertEquals(
            2,
            r.uploaded()
                .size());
        assertTrue(
            r.failed()
                .isEmpty());
        assertEquals(1, backups.get());
        assertEquals(1, saves.get());
        assertEquals(2, puts.size());
        assertNull(empty.getCloudKey());
        assertEquals("u1:" + puts.get(0), a.getCloudKey());
        assertEquals("u1:" + puts.get(1), b.getCloudKey());
        assertEquals(
            "Smelter",
            bodies.get(0)
                .get("name")
                .getAsString());
        assertFalse(
            bodies.get(0)
                .get("closed")
                .getAsBoolean());
        assertTrue(
            bodies.get(1)
                .get("closed")
                .getAsBoolean());
        assertEquals(
            "Smelter",
            bodies.get(0)
                .getAsJsonObject("plan")
                .get("name")
                .getAsString());

        // Again: nothing waits, nothing is sent, no backup is taken.
        assertNull(
            Migration.run(
                plan,
                "u1",
                "tok",
                MigrationTest::mapped,
                () -> backups.incrementAndGet() > 0,
                () -> saves.incrementAndGet() > 0));
        assertEquals(2, puts.size());
        assertEquals(1, backups.get());
        assertTrue(
            Migration.waiting(plan)
                .isEmpty());
    }

    @Test
    void aPlanTheSiteRefusedGoesAgainUnderTheSameIdAndTheRestAreNotRepeated() throws IOException {
        final Graph good = plan("Good"), bad = plan("Bad");
        final Plan plan = plansOf(good, bad);
        refuse = "Bad";
        final Migration.Result first = Migration.run(plan, "u1", "tok", MigrationTest::mapped, () -> true, () -> true);
        assertEquals(
            1,
            first.uploaded()
                .size());
        assertEquals(
            "Bad",
            first.failed()
                .get(0)
                .name());
        assertEquals(
            "The plan is not valid.",
            first.failed()
                .get(0)
                .reason());
        assertNotNull(bad.getCloudKey());
        final String keyBefore = bad.getCloudKey();

        refuse = "";
        final Migration.Result second = Migration.run(plan, "u1", "tok", MigrationTest::mapped, () -> true, () -> true);
        assertEquals(List.of(keyBefore), second.uploaded());
        assertEquals(2, puts.size());
        assertEquals(
            1,
            puts.stream()
                .filter(id -> keyBefore.endsWith(id))
                .count());
        assertEquals(2, new HashSet<>(puts).size());
    }

    @Test
    void aFailedBackupOrSaveSendsNothingAndAFailedBackupGivesNoKeys() throws IOException {
        final Graph a = plan("Smelter");
        final Plan plan = plansOf(a);
        final Migration.Result noBackup = Migration
            .run(plan, "u1", "tok", MigrationTest::mapped, () -> false, () -> true);
        assertNotNull(noBackup.aborted());
        assertNull(a.getCloudKey());
        assertTrue(puts.isEmpty());

        final Migration.Result noSave = Migration
            .run(plan, "u1", "tok", MigrationTest::mapped, () -> true, () -> false);
        assertNotNull(noSave.aborted());
        assertTrue(puts.isEmpty());
        assertTrue(
            noSave.uploaded()
                .isEmpty());
    }

    @Test
    void aPlanThatThrowsIsThatPlansFailureAndTheRestGo() {
        final Graph bad = plan("Bad"), good = plan("Good");
        // No id: building the request throws, for that plan alone.
        final Migration.Item broken = new Migration.Item(bad, null, "u1:d1", mapped(bad), "Bad", false, 0);
        final Migration.Item fine = new Migration.Item(good, "d2", "u1:d2", mapped(good), "Good", false, 0);
        final Migration.Result r = Migration.upload(List.of(broken, fine), "tok", null);
        assertEquals(List.of("u1:d2"), r.uploaded());
        assertEquals(
            "Bad",
            r.failed()
                .get(0)
                .name());
        assertNotNull(
            r.failed()
                .get(0)
                .reason());
    }

    @Test
    void anotherAccountsKeyIsLeftAlone() {
        final Graph g = plan("Theirs");
        g.setCloudKey("u9:abc");
        final Plan plan = plansOf(g);
        assertTrue(
            Migration.prepare(plan, "u1", Set.of(), MigrationTest::mapped)
                .isEmpty());
        assertEquals("u9:abc", g.getCloudKey());
    }

    @Test
    void aTaskBoardWithOnlyTasksIsSent() {
        final Graph board = new Graph("Task board");
        board.tasks.add(
            new com.gtnhplanner.data.flowchart.Tasks.Task(
                "t1",
                "Build it",
                null,
                com.gtnhplanner.data.flowchart.Tasks.Status.TODO,
                "V",
                List.of(),
                List.of(),
                null,
                null,
                null));
        assertTrue(Migration.eligible(board));
        assertEquals(List.of(board), Migration.waiting(plansOf(board)));
    }

    @Test
    void aSlotThatCouldNotBeReadIsNotSent() {
        final Graph g = new Graph("Broken");
        g.setUnreadable("garbled");
        assertTrue(
            Migration.waiting(plansOf(g))
                .isEmpty());
    }

    @Test
    void theSaveIsOtherwiseUntouchedAndTheKeyIsKept() {
        final Graph a = plan("Smelter");
        a.setDescription("about it");
        final Plan plan = plansOf(a);
        final JsonObject before = Serializer.planTree(plan);

        Migration.prepare(plan, "u1", Set.of(), MigrationTest::mapped);
        final JsonObject after = Serializer.planTree(plan);

        final JsonObject slotBefore = before.getAsJsonArray("graphs")
            .get(0)
            .getAsJsonObject();
        final JsonObject slotAfter = after.getAsJsonArray("graphs")
            .get(0)
            .getAsJsonObject();
        assertTrue(slotAfter.has("cloudKey"));
        slotAfter.remove("cloudKey");
        assertEquals(slotBefore, slotAfter);
        before.remove("graphs");
        after.remove("graphs");
        assertEquals(before, after);

        final String key = a.getCloudKey();
        final Plan loaded = Serializer.decodePlan(Serializer.encodePlan(plan));
        assertEquals(
            key,
            loaded.getGraphs()
                .get(0)
                .getCloudKey());
        assertEquals(
            "about it",
            loaded.getGraphs()
                .get(0)
                .getDescription());
        // A save from before the key existed reads as never sent.
        final JsonObject old = Serializer.planTree(plansOf(plan("Old")));
        assertFalse(
            old.toString()
                .contains("cloudKey"));
        assertNull(
            Serializer.decodePlan(old.toString())
                .getGraphs()
                .get(0)
                .getCloudKey());
    }
}
