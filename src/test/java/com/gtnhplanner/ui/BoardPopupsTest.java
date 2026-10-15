package com.gtnhplanner.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Every popup a card, drawer or note opens goes over the board's own panel ({@link BoardSession#panel()}), never the
 * widget's. The board builds its widgets afresh whenever a card changes shape or a card comes or goes, and a sheet or
 * menu still open on one it let go of would ask a widget that is on no panel, which ModularUI throws for: issue 492, a
 * boiler's fuel picked from its gear sheet after the other fuel had added a port. The widgets cannot be built without
 * the game, so this reads their sources.
 */
class BoardPopupsTest {

    private static final Path UI = Path.of("src/main/java/com/gtnhplanner/ui");

    @Test
    void boardWidgetsNeverAskForTheirOwnPanel() throws IOException {
        final List<String> found = new ArrayList<>();
        for (final String dir : List.of("card", "drawer", "note")) for (final Path file : sources(UI.resolve(dir))) {
            final List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) if (lines.get(i)
                .contains("getPanel()")) found.add(UI.relativize(file) + ":" + (i + 1));
        }
        assertEquals(List.of(), found, "open popups over session.panel(), the board's own panel");
    }

    private static List<Path> sources(final Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(
                f -> f.getFileName()
                    .toString()
                    .endsWith(".java"))
                .toList();
        }
    }
}
