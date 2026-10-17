package com.gtnhplanner.ui;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import net.minecraft.client.Minecraft;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.ui.theme.Fmt;

/**
 * The player's planner settings (the top bar's gear and its unit keys): how the board looks zoomed out and shows its
 * numbers, the minimap, and the in-world views of linked machines. Kept in
 * {@code config/gtnhplanner-settings.properties}, so they carry across worlds.
 */
public final class PlannerSettings {

    /** Where the minimap sits on the screen. */
    public enum Corner {

        TOP_RIGHT("Top right"),
        TOP_LEFT("Top left"),
        BOTTOM_RIGHT("Bottom right"),
        BOTTOM_LEFT("Bottom left");

        public final String label;

        Corner(final String label) {
            this.label = label;
        }
    }

    /** The minimap's sizes, in GUI pixels. */
    public static final int[] MINIMAP_SIZES = { 110, 160, 220, 300 };
    public static final String[] MINIMAP_SIZE_NAMES = { "Small", "Medium", "Large", "Huge" };
    /** The minimap's zoom steps (world units per screen pixel inverted: board pixels drawn per screen pixel). */
    /** The minimap's zoom steps: past the board's glance zoom (0.45) it draws whole cards, as the board does. */
    public static final float[] MINIMAP_ZOOMS = { 0.04f, 0.06f, 0.09f, 0.13f, 0.18f, 0.25f, 0.35f, 0.5f, 0.71f, 1f };
    /** How far away linked machines show their AR panels, in blocks. */
    public static final int[] AR_RANGES = { 16, 32, 64, 128 };
    /** The planner's sounds against the vanilla click's loudness (Normal matches it). */
    public static final String[] SOUND_NAMES = { "Off", "Quiet", "Normal", "Loud" };
    public static final float[] SOUND_GAINS = { 0f, 0.5f, 1f, 1.7f };

    private static Properties props;

    private PlannerSettings() {}

    // region The settings

    /** Board: names of drawers and machines over them when zoomed far out. */
    /** Whether a card shows its recipe's circuit as one more input (number and name), rather than on its machine. */
    public static boolean circuitAsInput() {
        return bool("card.circuitAsInput", false);
    }

    public static void setCircuitAsInput(final boolean on) {
        set("card.circuitAsInput", on);
    }

    /** Whether the board has offered the tour yet (once, the first time it opens). */
    public static boolean tourOffered() {
        return bool("tour.offered", false);
    }

    public static void setTourOffered(final boolean offered) {
        set("tour.offered", offered);
    }

    /** How a task board shows on this computer, by its plan id: "chain" for its chains, else its columns. */
    public static String taskView(final String planId) {
        return load().getProperty("tasks.view." + planId, "board");
    }

    public static void setTaskView(final String planId, final String view) {
        set("tasks.view." + planId, view);
    }

    /** The newest release the player was told of (and answered), so it is said once; "" for none. */
    public static String updateOffered() {
        return load().getProperty("update.offered", "");
    }

    public static void setUpdateOffered(final String tag) {
        set("update.offered", tag);
    }

    public static boolean zoomedOutNames() {
        return bool("board.zoomedOutNames", true);
    }

    public static void setZoomedOutNames(final boolean on) {
        set("board.zoomedOutNames", on);
    }

    public static boolean minimap() {
        return bool("minimap.on", false);
    }

    public static void setMinimap(final boolean on) {
        set("minimap.on", on);
    }

    public static int minimapSizeIndex() {
        return clamp(integer("minimap.size", 1), MINIMAP_SIZES.length);
    }

    public static void setMinimapSizeIndex(final int i) {
        set("minimap.size", clamp(i, MINIMAP_SIZES.length));
    }

    public static int minimapSize() {
        return MINIMAP_SIZES[minimapSizeIndex()];
    }

    /** How see-through the minimap is drawn, in percent, in steps of {@link #MINIMAP_SEE_THROUGH_STEP} up to 80. */
    public static final int MINIMAP_SEE_THROUGH_STEP = 10, MINIMAP_SEE_THROUGH_MAX = 80;

    public static int minimapSeeThrough() {
        return Math.max(0, Math.min(MINIMAP_SEE_THROUGH_MAX, integer("minimap.seeThrough", 0)));
    }

    public static void setMinimapSeeThrough(final int percent) {
        set("minimap.seeThrough", Math.max(0, Math.min(MINIMAP_SEE_THROUGH_MAX, percent)));
    }

    public static boolean minimapCircle() {
        return bool("minimap.circle", false);
    }

    public static void setMinimapCircle(final boolean circle) {
        set("minimap.circle", circle);
    }

    public static Corner minimapCorner() {
        try {
            return Corner.valueOf(load().getProperty("minimap.corner", Corner.BOTTOM_RIGHT.name()));
        } catch (final IllegalArgumentException e) {
            return Corner.TOP_RIGHT;
        }
    }

    public static void setMinimapCorner(final Corner corner) {
        load().setProperty("minimap.corner", corner.name());
        save();
    }

    public static int minimapZoomIndex() {
        return clamp(integer("minimap.zoom", 2), MINIMAP_ZOOMS.length);
    }

    public static void setMinimapZoomIndex(final int i) {
        set("minimap.zoom", clamp(i, MINIMAP_ZOOMS.length));
    }

    /** The minimap centres on the card of the linked machine under the crosshair. */
    public static boolean minimapFollows() {
        return bool("minimap.follow", true);
    }

    public static void setMinimapFollows(final boolean on) {
        set("minimap.follow", on);
    }

    /** The pin list: the plan's pinned machines on screen while playing. */
    public static boolean pinList() {
        return bool("pinList.on", true);
    }

    public static void setPinList(final boolean on) {
        set("pinList.on", on);
    }

    public static Corner pinListCorner() {
        try {
            return Corner.valueOf(load().getProperty("pinList.corner", Corner.TOP_RIGHT.name()));
        } catch (final IllegalArgumentException e) {
            return Corner.TOP_RIGHT;
        }
    }

    public static void setPinListCorner(final Corner corner) {
        load().setProperty("pinList.corner", corner.name());
        save();
    }

    /** One shared pipe for every placed machine moving the same item or fluid, instead of a run for each connection. */
    public static boolean sharedPipes() {
        return bool("world.sharedPipes", false);
    }

    public static void setSharedPipes(final boolean on) {
        set("world.sharedPipes", on);
    }

    /** The plan overlaid on the world: its placed cards over their blocks, with their wires. */
    public static boolean arLens() {
        return bool("world.ar", true);
    }

    public static void setArLens(final boolean on) {
        set("world.ar", on);
    }

    /** Focus: the plan over the world shows a machine's card above it while you look at its ghost. */
    public static boolean arFocus() {
        return bool("world.arFocus", true);
    }

    public static void setArFocus(final boolean on) {
        set("world.arFocus", on);
    }

    /** Labels: each machine of the plan over the world shows its name, tier, power and count in its ghost. */
    public static boolean arLabels() {
        return bool("world.arLabels", true);
    }

    public static void setArLabels(final boolean on) {
        set("world.arLabels", on);
    }

    /** What each wire over the world says it carries: on every wire, on the wires of the machine looked at, or none. */
    public static final String[] AR_WIRE_LABEL_NAMES = { "Always", "Where you look", "Off" };

    public static final int WIRE_LABELS_ALWAYS = 0, WIRE_LABELS_LOOKED = 1, WIRE_LABELS_OFF = 2;

    public static int arWireLabels() {
        return clamp(integer("world.arWireLabels", WIRE_LABELS_ALWAYS), AR_WIRE_LABEL_NAMES.length);
    }

    public static void setArWireLabels(final int i) {
        set("world.arWireLabels", clamp(i, AR_WIRE_LABEL_NAMES.length));
    }

    /**
     * How placed machines show in the world: see-through ghosts, solid blocks, or not at all (wires and labels only).
     */
    public static final String[] AR_GHOST_NAMES = { "See-through", "Solid", "Hidden" };

    public static final int GHOSTS_SEE_THROUGH = 0, GHOSTS_SOLID = 1, GHOSTS_HIDDEN = 2;

    public static int arGhosts() {
        return clamp(integer("world.arGhosts", GHOSTS_SEE_THROUGH), AR_GHOST_NAMES.length);
    }

    public static void setArGhosts(final int i) {
        set("world.arGhosts", clamp(i, AR_GHOST_NAMES.length));
    }

    /** The list of the world's keys in the corner, under the minimap. */
    public static boolean arKeys() {
        return bool("world.arKeys", true);
    }

    public static void setArKeys(final boolean on) {
        set("world.arKeys", on);
    }

    public static int arRangeIndex() {
        return clamp(integer("world.arRange", 1), AR_RANGES.length);
    }

    public static void setArRangeIndex(final int i) {
        set("world.arRange", clamp(i, AR_RANGES.length));
    }

    public static int arRange() {
        return AR_RANGES[arRangeIndex()];
    }

    // endregion

    // region Task locations in the world

    /** What shows of a task's locations without Show being pressed: all, those within the distance, or none. */
    public static final String[] TASK_SHOW_NAMES = { "Always", "When near", "Never" };

    public static final int TASK_ALWAYS = 0, TASK_NEAR = 1, TASK_NEVER = 2;
    /** How near is near, in blocks. */
    public static final int[] TASK_DISTANCES = { 16, 32, 64, 128 };

    public static int taskShow() {
        return clamp(integer("world.taskShow", TASK_NEAR), TASK_SHOW_NAMES.length);
    }

    public static void setTaskShow(final int i) {
        set("world.taskShow", clamp(i, TASK_SHOW_NAMES.length));
    }

    public static int taskDistanceIndex() {
        return clamp(integer("world.taskNear", 1), TASK_DISTANCES.length);
    }

    public static void setTaskDistanceIndex(final int i) {
        set("world.taskNear", clamp(i, TASK_DISTANCES.length));
    }

    public static int taskDistance() {
        return TASK_DISTANCES[taskDistanceIndex()];
    }

    public static boolean taskBeam() {
        return bool("world.taskBeam", true);
    }

    public static void setTaskBeam(final boolean on) {
        set("world.taskBeam", on);
    }

    public static boolean taskLabel() {
        return bool("world.taskLabel", true);
    }

    public static void setTaskLabel(final boolean on) {
        set("world.taskLabel", on);
    }

    // endregion

    // region The way numbers are shown (the top bar's keys)

    /** The board's rate unit: per tick, second, minute or hour. */
    public static Fmt.RateUnit rateUnit() {
        try {
            return Fmt.RateUnit.valueOf(load().getProperty("board.rateUnit", Fmt.RateUnit.SECOND.name()));
        } catch (final IllegalArgumentException e) {
            return Fmt.RateUnit.SECOND;
        }
    }

    public static void setRateUnit(final Fmt.RateUnit unit) {
        set("board.rateUnit", unit.name());
    }

    /** Power shown as amps at each machine's tier rather than EU/t. */
    public static boolean powerAsAmps() {
        return bool("board.powerAmps", false);
    }

    public static void setPowerAsAmps(final boolean amps) {
        set("board.powerAmps", amps);
    }

    /** Power summed with every machine running at once rather than on average. */
    public static boolean peakPower() {
        return bool("board.peakPower", false);
    }

    public static void setPeakPower(final boolean peak) {
        set("board.peakPower", peak);
    }

    // endregion

    // region The overview, as the player left it

    /** The overview's sections folded before the player folds any: Internal, the long tail. */
    public static final Set<String> OVERVIEW_FOLDED_AT_FIRST = Set.of("INTERNAL");

    /** Whether the overview is open beside the board, or folded to a strip. */
    public static boolean overviewOpen() {
        return bool("overview.open", true);
    }

    public static void setOverviewOpen(final boolean open) {
        set("overview.open", open);
    }

    /** The overview's sections folded to their bars, by title (INPUTS, OUTPUTS, INTERNAL, MACHINES). */
    public static Set<String> overviewFolded() {
        final String saved = load().getProperty("overview.folded");
        if (saved == null) return new TreeSet<>(OVERVIEW_FOLDED_AT_FIRST);
        final Set<String> out = new TreeSet<>();
        for (final String title : saved.split(",")) if (!title.isBlank()) out.add(title.trim());
        return out;
    }

    public static void setOverviewFolded(final Set<String> folded) {
        set("overview.folded", String.join(",", new TreeSet<>(folded)));
    }

    // endregion

    // region Size

    /**
     * The planner's size against NEI's, as an index into these names. Normal is a GUI scale step below NEI's (never
     * below 2, where a step smaller is too small to read); Large is NEI's own; Small and Tiny are a step and two below
     * Normal.
     */
    public static final String[] SIZE_NAMES = { "Tiny", "Small", "Normal", "Large" };
    public static final int SIZE_NORMAL = 2, SIZE_LARGE = 3;

    public static int size() {
        return clamp(integer("board.planSize", SIZE_NORMAL), SIZE_NAMES.length);
    }

    public static void setSize(final int index) {
        set("board.planSize", clamp(index, SIZE_NAMES.length));
    }

    /** The planner's GUI scale on a game whose scale is {@code game}. */
    public static int boardScale(final int game) {
        final int size = size();
        if (size == SIZE_LARGE) return game;
        final int normal = Math.max(Math.min(game, 2), game - 1);
        return Math.max(1, normal - (SIZE_NORMAL - size));
    }

    // endregion

    // region Background

    /** The see-through slider's step, in percent, and where it starts. */
    public static final int SEE_THROUGH_STEP = 5, DEFAULT_SEE_THROUGH = 30;
    /**
     * The old four steps (Solid, Mostly solid, Half, See-through), read once into the percent that replaced them;
     * See-through comes over as all the way, as it was meant to show the game behind.
     */
    private static final int[] OLD_SEE_THROUGH = { 0, 15, 40, 100 };

    /**
     * How see-through the board's and the overview's backgrounds are, in percent: 0 solid, 100 nothing but the game
     * behind the planner (its cards and text stay solid).
     */
    public static int seeThrough() {
        final int fallback = load().getProperty("board.background") == null ? DEFAULT_SEE_THROUGH
            : OLD_SEE_THROUGH[clamp(integer("board.background", 0), OLD_SEE_THROUGH.length)];
        return Math.max(0, Math.min(100, integer("board.seeThrough", fallback)));
    }

    public static void setSeeThrough(final int percent) {
        load().remove("board.background");
        set("board.seeThrough", Math.max(0, Math.min(100, percent)));
    }

    /** How solid the backgrounds are drawn, 0 to 1. */
    public static float backgroundAlpha() {
        return 1f - seeThrough() / 100f;
    }

    // endregion

    // region Sound

    public static int soundIndex() {
        return clamp(integer("sound.level", 2), SOUND_GAINS.length);
    }

    public static void setSoundIndex(final int i) {
        set("sound.level", clamp(i, SOUND_GAINS.length));
    }

    public static float soundGain() {
        return SOUND_GAINS[soundIndex()];
    }

    // endregion

    // region Storage

    private static boolean bool(final String key, final boolean fallback) {
        final String v = load().getProperty(key);
        return v == null ? fallback : Boolean.parseBoolean(v);
    }

    private static int integer(final String key, final int fallback) {
        try {
            return Integer.parseInt(load().getProperty(key, Integer.toString(fallback)));
        } catch (final NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(final int i, final int length) {
        return Math.max(0, Math.min(length - 1, i));
    }

    private static void set(final String key, final Object value) {
        load().setProperty(key, String.valueOf(value));
        save();
    }

    /** The file a test keeps them in instead of the game's config folder; null in the game. */
    private static File testFile;

    /** Keeps them in {@code f} from now on and forgets what was read, as a game started afresh would (for tests). */
    static synchronized void useFile(final File f) {
        testFile = f;
        props = null;
    }

    private static File file() {
        if (testFile != null) return testFile;
        return new File(Minecraft.getMinecraft().mcDataDir, "config/gtnhplanner-settings.properties");
    }

    private static synchronized Properties load() {
        if (props != null) return props;
        props = new Properties();
        final File f = file();
        if (!f.isFile()) return props;
        try (Reader in = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            props.load(in);
        } catch (final IOException e) {
            GtnhPlanner.LOG.warn("Could not read {}", f, e);
        }
        return props;
    }

    private static synchronized void save() {
        final File f = file(), dir = f.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        try (Writer out = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
            props.store(out, "GTNH Planner settings");
        } catch (final IOException e) {
            GtnhPlanner.LOG.warn("Could not write {}", f, e);
        }
    }

    // endregion
}
