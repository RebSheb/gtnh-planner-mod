package com.gtnhplanner.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a report takes out of the log and the crash report before it goes up as a public issue. */
class ReportsTest {

    @Test
    void namesFoldersAddressesAndTokensAreTakenOut() {
        final String log = String.join(
            "\n",
            "[10:00:01] [Client thread/INFO]: Setting user: Arodoid",
            "[10:00:02] [Client thread/INFO]: Loading C:\\Users\\jack\\AppData\\Roaming\\.minecraft\\mods\\a.jar",
            "[10:00:02] [Client thread/INFO]: and C:/Users/jack/AppData/Roaming/.minecraft/config",
            "[10:00:03] [Client thread/INFO]: Connecting to play.example.net, 25565",
            "[10:00:03] [Client thread/INFO]: peer 192.168.1.20:25565 joined",
            "[10:00:04] [main/INFO]: --accessToken 9f8e7d6c5b4a39281706 --version 1.7.10",
            "[10:00:05] [Client thread/INFO]: mail me at someone@example.com",
            "[10:00:06] [Client thread/INFO]: GT 5.09.54.205 loaded; jack's world; Jackson stays");
        final String out = Reports.redact(log, "C:\\Users\\jack", "jack", "Arodoid");
        assertTrue(out.contains("Setting user: <player>"));
        assertTrue(out.contains("Loading ~\\AppData"), out);
        assertTrue(out.contains("and ~/AppData"), out);
        assertTrue(out.contains("Connecting to <address>"));
        assertTrue(out.contains("peer <address> joined"));
        assertTrue(out.contains("--accessToken <hidden>"), out);
        assertTrue(out.contains("<email>"));
        assertTrue(out.contains("GT 5.09.54.205"), "version numbers are not addresses");
        assertTrue(out.contains("<user>'s world"));
        assertTrue(out.contains("Jackson stays"), "only the whole name");
        assertFalse(out.contains("Arodoid"));
    }

    @Test
    void theLogsEndIsReadFromTheEnd(@TempDir final File dir) throws IOException {
        final File f = new File(dir, "latest.log");
        final StringBuilder s = new StringBuilder();
        for (int i = 1; i <= 500; i++) s.append("line ")
            .append(i)
            .append('\n');
        Files.write(
            f.toPath(),
            s.toString()
                .getBytes(StandardCharsets.UTF_8));
        final String tail = Reports.tail(f, 3);
        assertEquals("line 498\nline 499\nline 500", tail.trim());
    }

    @Test
    void theChatNeverGoes(@TempDir final File dir) throws IOException {
        // As in #494: other players' names and words, in a public issue.
        final File f = new File(dir, "latest.log");
        final StringBuilder s = new StringBuilder();
        for (int i = 1; i <= 300; i++) {
            s.append("[22:00:00] [Client thread/INFO]: line ")
                .append(i)
                .append('\n');
            s.append("[22:33:38] [Client thread/INFO]: [CHAT] <[Nomad]natnif36> is there a way to change my biome\n");
            s.append("[22:36:48] [Client thread/INFO]: [CHAT] * [Villager]Arodoid is now AFK.\n");
        }
        Files.write(
            f.toPath(),
            s.toString()
                .getBytes(StandardCharsets.UTF_8));
        final String tail = Reports.tail(f, 200);
        assertFalse(tail.contains("[CHAT]"));
        assertFalse(tail.contains("natnif36"));
        assertEquals(200, tail.split("\n").length, "two hundred lines of log, not of chat");
        assertTrue(tail.endsWith("line 300"));
    }

    @Test
    void longTextKeepsItsEnd() {
        final String text = "x".repeat(70_000) + "THE ERROR";
        final String cut = Reports.cut(text, 50_000);
        assertEquals(50_000, cut.length());
        assertTrue(cut.endsWith("THE ERROR"));
        assertTrue(cut.startsWith("...\n"));
        assertEquals("short", Reports.cut("short", 50_000));
    }

    @Test
    void longWordsLeaveLessRoom() {
        assertEquals(Reports.ISSUE_MAX - Reports.ISSUE_FRAME, Reports.room(""));
        assertEquals(Reports.ISSUE_MAX - Reports.ISSUE_FRAME - 8, Reports.room("@a #1 "), "each @ and # takes two");
    }
}
