package com.gtnhplanner.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * What of a crash report goes up in a report, on a real GTNH 2.9 crash report (the planner's own NEI tooltip crash, the
 * player and home folder renamed): 7 MB, nearly all of it the session's whole log, which used to fill the issue's crash
 * section with FML's startup lines and leave out the error.
 */
class ReportTextTest {

    private static String report;

    /** The redaction a report runs, for the renamed player and home folder. */
    private static final UnaryOperator<String> REDACT = t -> Reports
        .redact(t, "C:\\Users\\player", "player", "Player123");

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = ReportTextTest.class.getResourceAsStream("/crash-reports/gtnh-2.9-client.txt.gz")) {
            assertNotNull(in, "the crash report resource");
            report = new String(new GZIPInputStream(in).readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(report.length() > 5_000_000, "a GTNH-sized crash report");
    }

    @Test
    void theErrorItsStackAndThePlannersLineGoNotTheLog() {
        final String out = Reports.crash(report, Reports.CRASH_MAX, REDACT);
        assertTrue(out.length() <= Reports.CRASH_MAX, "length " + out.length());
        assertTrue(out.startsWith("---- Minecraft Crash Report ----"));
        assertTrue(out.contains("Description: Rendering screen"));
        assertTrue(
            out.contains("java.lang.NullPointerException: Cannot read field \"handler\" because \"ref\" is null"));
        assertTrue(out.contains("at com.gtnhplanner.nei.PlanButton.choices(PlanButton.java:37)"));
        assertTrue(out.contains("at java.lang.Thread.run(Thread.java:1583)"), "the whole stack");
        assertTrue(out.contains("\n\nGTNH Planner: 0.2.0-alpha.3"), "the planner's line, under the error");
        assertTrue(out.contains("-- Screen render details --"));
        assertTrue(out.contains("-- System Details --"));
        assertTrue(out.contains("Java Version: 21.0.7"));
        assertTrue(out.contains("Suspected Mods: GTNH Planner (gtnhplanner), NotEnoughItems (NotEnoughItems)"));
        assertTrue(out.contains("Mixins in Stacktrace:"));
        assertTrue(out.contains("JVM Flags: 3 total; -XX:HeapDumpPath="), "a list of flags is no list of names");
        assertTrue(out.contains(ReportText.LOG_HEADING));
        assertTrue(out.contains("/WARN]"), "the log's last warnings");
        assertTrue(out.contains("more like it)"), "NEI's warning for each item, told once");
    }

    @Test
    void theModListApisAndTheWholeLogStayOut() {
        final String out = Reports.crash(report, Reports.CRASH_MAX, REDACT);
        assertFalse(out.contains("UCHIJAAAA"), "the mod list");
        assertTrue(out.contains("mods left out)"));
        assertFalse(out.contains("from WirelessCraftingTerminal-1.12.16.jar"), "the API list");
        assertTrue(out.contains("APIs left out)"));
        assertFalse(out.contains("Set field public static"), "FML's trace lines, all #494 got");
        assertFalse(out.contains("/DEBUG]"));
        assertFalse(out.contains("/TRACE]"));
        assertFalse(out.contains("Complete log:"));
    }

    @Test
    void noNamesGo() {
        final String out = Reports.crash(report, Reports.CRASH_MAX, REDACT);
        assertFalse(out.contains("Player123"), "the player");
        assertFalse(out.contains("C:\\Users\\player"), "the home folder");
        assertTrue(out.contains("All players: 1 total\n"), "the count stays, the list goes");
        assertFalse(out.contains("EntityClientPlayerMP["));
        assertFalse(out.contains("EntityRabbit["), "the entity lists go too");
    }

    @Test
    void otherPlayersAndTheChatStayOut() {
        // On a server: other players in the level's list, and their chat (and a warning naming one) in the log.
        final String mp = report
            .replace(
                "All players: 1 total; [EntityClientPlayerMP['Player123'",
                "All players: 2 total; [EntityOtherPlayerMP['natnif36'/77, l='MpServer', x=1.0, y=2.0, z=3.0], "
                    + "EntityClientPlayerMP['Player123'")
            .replace(
                "\n[20:08:54] [Client thread/WARN] [GLSM/]",
                "\n[20:08:53] [Client thread/INFO] [Minecraft-Client/]: [CHAT] <[Nomad]natnif36> is there a way to change"
                    + " my biome back?\n[20:08:53] [Client thread/WARN] [Minecraft-Client/]: [CHAT] <[Nomad]oliviabei> "
                    + "i think something from witchery\n[20:08:54] [Client thread/WARN] [GLSM/]");
        assertTrue(mp.contains("natnif36") && mp.contains("oliviabei"));
        final String out = Reports.crash(mp, Reports.CRASH_MAX, REDACT);
        assertFalse(out.contains("natnif36"));
        assertFalse(out.contains("oliviabei"));
        assertFalse(out.contains("[CHAT]"));
        assertTrue(out.contains("All players: 2 total\n"));
        assertTrue(out.contains("RenderTileFloatingFlower"), "the warnings around the chat stay");
    }

    @Test
    void theIssueHoldsTheWholeCrashBesideTheLongestWords() {
        // The longest words the site takes (8000 characters, each @ written with a character more) and a full log.
        final String description = "@x".repeat(4000);
        int room = Reports.room(description);
        final String crash = Reports.crash(report, Math.min(Reports.CRASH_MAX, room), REDACT);
        room -= crash.length();
        final StringBuilder log = new StringBuilder();
        for (int i = 0; log.length() < 100_000; i++) log.append("[22:00:")
            .append(i % 60)
            .append("] [Client thread/INFO]: a line of the game's log that runs on for a while to fill it up ")
            .append(i)
            .append('\n');
        final String logPart = Reports.cut(log.toString(), Math.min(Reports.LOG_MAX, room));
        final String body = SiteIssue.body(description, logPart, crash);
        assertTrue(body.length() <= SiteIssue.BODY_MAX, "the site would cut it: " + body.length());
        assertTrue(body.contains("java.lang.NullPointerException: Cannot read field \"handler\""));
        assertTrue(body.contains("GTNH Planner: 0.2.0-alpha.3"));
        assertTrue(body.endsWith(SiteIssue.FOOT), "the versions at the end stay too");
    }

    @Test
    void aLongStackKeepsItsTopAndItsCause() {
        final StringBuilder s = new StringBuilder(
            "---- Minecraft Crash Report ----\nDescription: Rendering screen\n\njava.lang.StackOverflowError: deep\n");
        for (int i = 0; i < 2000; i++) s.append("\tat com.gtnhplanner.layout.Deep.dive(Deep.java:")
            .append(i)
            .append(")\n");
        s.append(
            "Caused by: java.lang.IllegalStateException: the root of it\n\tat com.gtnhplanner.Root.cause(Root.java:1)\n")
            .append("\t... 2000 more\n\n\n");
        s.append(ReportText.WALKTHROUGH)
            .append(", its code path and all known details is as follows:\n-- System Details --\nDetails:\n")
            .append("\tGTNH Planner: 0.2.0, plan 'p' (3 cards, 0 drawers), the planner open\n");
        final String out = ReportText.crash(s.toString(), 12_000);
        assertTrue(out.length() <= 12_000);
        assertTrue(out.contains("java.lang.StackOverflowError: deep"));
        assertTrue(out.contains("Deep.java:0)"));
        assertTrue(out.contains("lines left out)"));
        assertTrue(out.contains("Caused by: java.lang.IllegalStateException: the root of it"));
        assertTrue(out.contains("GTNH Planner: 0.2.0, plan 'p' (3 cards, 0 drawers), the planner open"));
    }

    @Test
    void aSmallCrashReportGoesWhole() {
        final String small = """
            ---- Minecraft Crash Report ----
            Description: Ticking screen

            java.lang.NullPointerException: oops
            \tat com.gtnhplanner.ui.BoardSession.tickBoard(BoardSession.java:1549)


            A detailed walkthrough of the error, its code path and all known details is as follows:
            ---------------------------------------------------------------------------------------

            -- System Details --
            Details:
            \tMinecraft Version: 1.7.10
            \tGTNH Planner: 0.2.0, the planner open
            """;
        assertEquals(
            String.join(
                "\n",
                "---- Minecraft Crash Report ----",
                "Description: Ticking screen",
                "",
                "java.lang.NullPointerException: oops",
                "\tat com.gtnhplanner.ui.BoardSession.tickBoard(BoardSession.java:1549)",
                "",
                "GTNH Planner: 0.2.0, the planner open",
                "",
                "-- System Details --",
                "Details:",
                "\tMinecraft Version: 1.7.10"),
            ReportText.crash(small, 24_000));
    }

    @Test
    void chatAndWhatCarriesItOnStayOutOfTheLog() {
        final List<String> log = List.of(
            "[22:32:31] [Client thread/INFO]: [CHAT] Connected",
            "[22:33:38] [Client thread/INFO]: [CHAT] <[Nomad]natnif36> is there a way",
            "to change my biome back?",
            "[22:35:00] [Client thread/WARN]: Failed to load texture: minecraft:textures/font/unicode_page_df.png",
            "java.io.FileNotFoundException: minecraft:textures/font/unicode_page_df.png",
            "\tat net.minecraft.client.resources.FallbackResourceManager.func_110536_a(SourceFile:51)",
            "[22:36:48] [Client thread/INFO]: [CHAT] <[Nomad]oliviabei> i think something from witchery",
            // A world of one's own (open to LAN too): its server logs the chat and who comes and goes.
            "[22:36:50] [Server thread/INFO]: <natnif36> yikes, grew some silverwood too close",
            "[22:36:51] [Server thread/INFO] [Minecraft-Server/]: * natnif36 waves",
            "[22:36:52] [Server thread/INFO]: natnif36[/<address>] logged in with entity id 7 at (1.5, 4.0, 2.5)",
            "[22:36:53] [Server thread/INFO]: natnif36 joined the game",
            "[22:36:54] [Server thread/INFO]: natnif36 lost connection: TextComponent{text='Disconnected'}",
            "[22:36:55] [Server thread/INFO]: natnif36 left the game",
            "[22:37:00] [Client thread/INFO]: Stopping!");
        assertEquals(
            List.of(
                "[22:35:00] [Client thread/WARN]: Failed to load texture: minecraft:textures/font/unicode_page_df.png",
                "java.io.FileNotFoundException: minecraft:textures/font/unicode_page_df.png",
                "\tat net.minecraft.client.resources.FallbackResourceManager.func_110536_a(SourceFile:51)",
                "[22:37:00] [Client thread/INFO]: Stopping!"),
            ReportText.withoutChat(log));
    }

    /** The issue as the site writes it (its src/lib/server/feedback.ts issueBody), with the plans in a comment. */
    private static final class SiteIssue {

        static final int BODY_MAX = 60_000;
        static final String FOOT = "\n---\nGTNH Planner 0.2.0-alpha.5 · GTNH 2.9.0-RC-1 · Java 26.0.1 · Linux 6.11.11-valve26-1-neptune-611-gb3afa9aff7ed";

        static String body(final String description, final String log, final String crash) {
            final String quiet = description.replaceAll("@(?=[\\w-])", "@\u200b")
                .replaceAll("#(?=\\d)", "#\u200b");
            return String.join(
                "\n",
                "**Bug** sent from the game, feeling: Unhappy",
                "",
                quiet,
                "",
                "_The plans are in the first comment._",
                "",
                "![The open plan](https://gtnhplanner.com/api/feedback/files/2c3dc897-cea1-4503-8ef9-7b231449e967/picture.png)",
                "",
                details("The end of the game log", log),
                "",
                details("The crash report", crash)) + FOOT;
        }

        private static String details(final String summary, final String text) {
            return "<details><summary>" + summary
                + "</summary>\n\n~~~~text\n"
                + text.replace("~~~~", "~~~\u200b~")
                + "\n~~~~\n\n</details>";
        }
    }
}
