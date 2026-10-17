package com.gtnhplanner.cloud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gtnhplanner.cloud.net.CloudApi;
import com.gtnhplanner.cloud.store.CloudCache;

/** What the cache keeps across a restart, and that a crash in the middle of a write costs nothing. */
class CloudCacheTest {

    @TempDir
    File root;

    /**
     * Every cache a test opened. Writes go on in the cache's writer thread after the call returns: one still making a
     * file while JUnit clears the folder away kept it from being deleted on Windows (the file open, or made again
     * after the clean-up emptied its folder). Each is closed (its writes waited for) after the test, before that.
     */
    private final List<CloudCache> opened = new ArrayList<>();

    private CloudCache open(final String account) {
        final CloudCache c = CloudCache.forAccount(root, account);
        opened.add(c);
        return c;
    }

    @AfterEach
    void closeEveryCache() {
        for (final CloudCache c : opened) c.close();
    }

    private static JsonObject plan(final String name) {
        final JsonObject p = new JsonObject();
        p.addProperty("name", name);
        return p;
    }

    private static CloudCache.Batch batch(final String cid) {
        final JsonArray ops = new JsonArray();
        final JsonObject op = new JsonObject();
        op.addProperty("o", "meta");
        ops.add(op);
        return new CloudCache.Batch(cid, 4, ops);
    }

    private static List<String> cids(final CloudCache c, final String key) {
        return c.pending(key)
            .stream()
            .map(CloudCache.Batch::cid)
            .toList();
    }

    @Test
    void thePlanAndItsSeqComeBackAfterARestart() {
        final CloudCache a = open("u1");
        assertNull(a.doc("u1:d1"));
        a.saveDoc("u1:d1", 12, plan("Smelter"));
        a.close();
        assertTrue(new File(root, "gtnhplanner/cloud/u1").isDirectory());
        final CloudCache b = open("u1");
        assertEquals(
            12,
            b.doc("u1:d1")
                .seq());
        assertEquals(
            "Smelter",
            b.doc("u1:d1")
                .plan()
                .get("name")
                .getAsString());
        assertNull(open("u2").doc("u1:d1"));
    }

    @Test
    void aBatchIsOnDiskWhenItsFutureIsDoneAndGoneWhenAcked() throws Exception {
        final CloudCache a = open("u1");
        a.addPending("u1:d1", batch("c1"))
            .get();
        a.addPending("u1:d1", batch("c2"))
            .get();
        // Not closed, not flushed: as a crash leaves it.
        final CloudCache crashed = open("u1");
        assertEquals(List.of("c1", "c2"), cids(crashed, "u1:d1"));
        assertEquals(
            4,
            crashed.pending("u1:d1")
                .get(0)
                .base());
        assertEquals(
            "meta",
            crashed.pending("u1:d1")
                .get(0)
                .ops()
                .get(0)
                .getAsJsonObject()
                .get("o")
                .getAsString());
        assertEquals(List.of("u1:d1"), crashed.keysWithPending());

        a.ack("u1:d1", "c1")
            .get();
        a.ack("u1:d1", "nope")
            .get();
        assertEquals(List.of("c2"), cids(open("u1"), "u1:d1"));
        a.ack("u1:d1", "c2")
            .get();
        final CloudCache empty = open("u1");
        assertTrue(
            empty.pending("u1:d1")
                .isEmpty());
        assertTrue(
            empty.keysWithPending()
                .isEmpty());
    }

    @Test
    void aWriteThatDiedHalfwayLeavesTheLastGoodFile() throws IOException {
        final CloudCache a = open("u1");
        a.saveDoc("u1:d1", 3, plan("Good"));
        a.addPending("u1:d1", batch("c1"));
        a.close();
        final File dir = new File(root, "gtnhplanner/cloud/u1/docs/u1_d1");
        // The writer was killed after starting the next copy: only the .new files are torn.
        Files.write(
            new File(dir, "plan.json.new").toPath(),
            "{\"seq\":9,\"plan\":{\"na".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "pending.json.new").toPath(), "{".getBytes(StandardCharsets.UTF_8));
        final CloudCache b = open("u1");
        assertEquals(
            3,
            b.doc("u1:d1")
                .seq());
        assertEquals(
            1,
            b.pending("u1:d1")
                .size());
        assertFalse(new File(dir, "plan.json.new").exists());
        // A torn real file (a disk that lied) is no copy rather than a crash.
        Files.write(new File(dir, "plan.json").toPath(), "{\"seq\":".getBytes(StandardCharsets.UTF_8));
        assertNull(open("u1").doc("u1:d1"));
        assertEquals(
            1,
            open("u1").pending("u1:d1")
                .size());
    }

    @Test
    void writesLandInTheOrderTheyWereMade() {
        final CloudCache a = open("u1");
        for (int i = 0; i < 50; i++) a.saveDoc("u1:d1", i, plan("v" + i));
        a.flush();
        assertEquals(
            49,
            open("u1").doc("u1:d1")
                .seq());
        final File[] left = new File(root, "gtnhplanner/cloud/u1/docs/u1_d1").listFiles((d, n) -> n.endsWith(".new"));
        assertEquals(0, left.length);
    }

    @Test
    void pinsAndTheListAreKeptPerPlan() throws Exception {
        final CloudCache a = open("u1");
        a.setPins("u1:d1", List.of("n1", "n2"))
            .get();
        assertTrue(
            a.pins("u1:d2")
                .isEmpty());
        final CloudApi.Library lib = CloudApi.Library.fromJson(
            new JsonParser()
                .parse(
                    "{\"own\":[{\"id\":\"d1\",\"ownerId\":\"u1\",\"name\":\"Smelter\","
                        + "\"members\":[{\"id\":\"u2\",\"username\":\"bob\"}]}],\"shared\":[]}")
                .getAsJsonObject());
        a.saveList(lib)
            .get();
        final CloudCache b = open("u1");
        assertEquals(List.of("n1", "n2"), b.pins("u1:d1"));
        assertEquals(
            "Smelter",
            b.list()
                .own()
                .get(0)
                .name());
        assertEquals(
            "bob",
            b.list()
                .own()
                .get(0)
                .members()
                .get(0)
                .username());
        assertNull(open("u3").list());
    }

    @Test
    void forgettingAPlanRemovesItsFiles() throws Exception {
        final CloudCache a = open("u1");
        a.saveDoc("u1:d1", 1, plan("x"));
        a.addPending("u1:d1", batch("c1"));
        a.forget("u1:d1")
            .get();
        assertNull(a.doc("u1:d1"));
        assertFalse(new File(root, "gtnhplanner/cloud/u1/docs/u1_d1").exists());
    }

    @Test
    void aBlankAccountHasNoCache() {
        assertThrows(IllegalArgumentException.class, () -> open(""));
        assertThrows(IllegalArgumentException.class, () -> open("  "));
        assertThrows(IllegalArgumentException.class, () -> open(".."));
    }

    @Test
    void aForgottenPlanDoesNotComeBackFromTheFiles() throws Exception {
        final CloudCache a = open("u1");
        a.saveDoc("u1:d1", 1, plan("x"));
        a.addPending("u1:d1", batch("c1"));
        a.flush();
        final CloudCache b = open("u1");
        b.forget("u1:d1");
        assertNull(b.doc("u1:d1"));
        assertTrue(
            b.pending("u1:d1")
                .isEmpty());
        assertTrue(
            b.keysWithPending()
                .isEmpty());
    }

    @Test
    void pendingKeysIncludeWhatIsOnlyInMemoryAndWhatIsOnlyOnDisk() {
        final CloudCache a = open("u1");
        a.addPending("u1:d1", batch("c1"));
        a.flush();
        final CloudCache b = open("u1");
        assertEquals(List.of("u1:d1"), b.keysWithPending());
        b.addPending("u1:d3", batch("c3"));
        assertEquals(java.util.Set.of("u1:d1", "u1:d3"), new java.util.HashSet<>(b.keysWithPending()));
        // Written before the folder is cleared away, or the writer races the clean-up.
        b.flush();
    }

    @Test
    void aMalformedFileIsSkippedNotThrown() throws IOException {
        final File d = new File(root, "gtnhplanner/cloud/u1/docs/u1_d1");
        d.mkdirs();
        Files.writeString(new File(d, "plan.json").toPath(), "{\"key\":\"u1:d1\",\"seq\":\"x\",\"plan\":{}}");
        Files.writeString(new File(d, "pending.json").toPath(), "{\"key\":\"u1:d1\",\"batches\":[{\"cid\":\"c\"}]}");
        final CloudCache a = open("u1");
        assertNull(a.doc("u1:d1"));
        assertTrue(
            a.pending("u1:d1")
                .isEmpty());
        assertTrue(
            a.pins("u1:d1")
                .isEmpty());
    }

    @Test
    void aPlanKeptAsItIsReadsBackFromDisk() {
        final CloudCache a = open("u1");
        a.addPending("u1:d1", batch("c1"));
        a.saveDocAsIs("u1:d1", 7, plan("Kept"));
        a.ack("u1:d1", "c1");
        a.close();
        final CloudCache b = open("u1");
        assertEquals(
            7,
            b.doc("u1:d1")
                .seq());
        assertEquals(
            "Kept",
            b.doc("u1:d1")
                .plan()
                .get("name")
                .getAsString());
        assertTrue(
            b.pending("u1:d1")
                .isEmpty(),
            "written in the order asked");
        b.close();
    }

    @Test
    void callersCannotChangeWhatTheCacheHolds() {
        final CloudCache a = open("u1");
        final JsonObject p = plan("Smelter");
        a.saveDoc("u1:d1", 1, p);
        p.addProperty("name", "changed");
        a.doc("u1:d1")
            .plan()
            .addProperty("name", "changed too");
        assertEquals(
            "Smelter",
            a.doc("u1:d1")
                .plan()
                .get("name")
                .getAsString());
        final CloudCache.Batch b = batch("c1");
        a.addPending("u1:d1", b);
        b.ops()
            .add(new JsonObject());
        a.pending("u1:d1")
            .get(0)
            .ops()
            .add(new JsonObject());
        assertEquals(
            1,
            a.pending("u1:d1")
                .get(0)
                .ops()
                .size());
    }
}
