package com.gtnhplanner.client;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

import com.google.gson.JsonPrimitive;
import com.gtnhplanner.GtnhPlanner;

/**
 * Opens a web address in the player's own browser, off the game's thread (a browser can take a moment to answer).
 * AWT's Desktop where it works; else the system's opener (Windows' URL handler, macOS's open, xdg-open); else LWJGL's.
 *
 * <p>
 * A long address (a plan code in it) is never handed over as it is: Windows passes it on a command line, which cuts
 * off at a few thousand characters on some setups. It goes in a small page in the temp folder that sends the browser
 * on to it, and that page's short file address is what is opened.
 */
public final class Browser {

    /** Longer addresses go through the page: every opener takes this many characters. */
    static final int DIRECT_MAX = 2000;
    /** The longest address a browser takes (Firefox's limit; Chrome's is twice it). */
    public static final int LINK_MAX = 1_000_000;

    private Browser() {}

    /** Opens {@code url} in the browser. False when it is too long for any browser ({@link #LINK_MAX}). */
    public static boolean open(final String url) {
        if (url.length() > LINK_MAX) return false;
        final Thread t = new Thread(() -> {
            try {
                launch(url.length() <= DIRECT_MAX ? url : page(url));
            } catch (final IOException | RuntimeException e) {
                GtnhPlanner.LOG.warn("Could not open the browser", e);
            }
        }, "GTNH Planner browser");
        t.setDaemon(true);
        t.start();
        return true;
    }

    /** The page that sends the browser on to {@code url}, written to the temp folder; its file address. */
    private static String page(final String url) throws IOException {
        final File f = File.createTempFile("gtnhplanner-open-", ".html");
        f.deleteOnExit();
        Files.write(f.toPath(), redirectPage(url).getBytes(StandardCharsets.UTF_8));
        return f.toURI()
            .toString();
    }

    /** A page that goes straight on to {@code url}, with a link for a browser that runs no scripts. */
    static String redirectPage(final String url) {
        final String js = new JsonPrimitive(url).toString()
            .replace("<", "\\u003c");
        final String attr = url.replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;");
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>GTNH Planner</title>"
            + "<meta name=\"referrer\" content=\"no-referrer\"></head><body>"
            + "<script>location.replace("
            + js
            + ");</script><noscript><a href=\""
            + attr
            + "\">Open the plan</a></noscript></body></html>";
    }

    private static void launch(final String url) throws IOException {
        if (desktop(url)) return;
        final String os = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            else if (os.contains("mac")) new ProcessBuilder("open", url).start();
            else new ProcessBuilder("xdg-open", url).start();
        } catch (final IOException e) {
            org.lwjgl.Sys.openURL(url);
        }
    }

    /** AWT's way, where there is a desktop to ask; false when there is none or it refused. */
    private static boolean desktop(final String url) {
        try {
            if (java.awt.GraphicsEnvironment.isHeadless() || !java.awt.Desktop.isDesktopSupported()) return false;
            final java.awt.Desktop d = java.awt.Desktop.getDesktop();
            if (!d.isSupported(java.awt.Desktop.Action.BROWSE)) return false;
            d.browse(URI.create(url));
            return true;
        } catch (final Exception | LinkageError e) {
            return false;
        }
    }
}
