package com.gtnhplanner.ui.task;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.world.WorldEvent;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.gtnhplanner.GtnhPlanner;
import com.gtnhplanner.api.PlanAPI;
import com.gtnhplanner.data.flowchart.Graph;
import com.gtnhplanner.data.flowchart.Plan;
import com.gtnhplanner.data.flowchart.TaskEdits;
import com.gtnhplanner.data.flowchart.Tasks;
import com.gtnhplanner.ui.Planner;
import com.gtnhplanner.ui.sound.Sfx;
import com.gtnhplanner.ui.theme.Hyb;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

/**
 * A task's screenshots: taking one (the planner steps aside, the world is drawn without the game's overlays and read
 * back, the planner comes back with the task open), keeping it (the full shot in the screenshots folder, a small JPEG
 * thumbnail in the task, as a data URL; it stays in saves and the account's copy, not in plan codes or chat links), and
 * drawing them (textures made from
 * the thumbnails, and from the full shot when it is on this computer).
 */
public final class TaskPictures {

    public static final TaskPictures INSTANCE = new TaskPictures();

    /** A thumbnail's width, and the most bytes its JPEG may take (it rides in the plan, and in every batch). */
    static final int THUMB_W = 240, THUMB_BYTES = 12_000;
    /** Frames drawn without the planner before the shot is read: the world settles after the screen goes. */
    private static final int SETTLE_FRAMES = 4;

    TaskPictures() {}

    // region Taking one

    /** A shot asked for: its task and board, frames drawn since the planner went, and the overlays as they were. */
    @Nullable
    Graph board;
    @Nullable
    String taskId;
    int frames;
    /** Whether the game's overlays were already hidden before the shot hid them. */
    boolean hidGui;
    /** Puts the game's overlays back (hidden or shown) when the shot is done or called off. */
    @Nullable
    private java.util.function.Consumer<Boolean> hud;
    /** Set once the shot is in: the planner comes back on the next tick. */
    boolean reopen;

    /** A shot asked for: it waits for the planner to go and the world to settle. */
    void begin(final Graph board, final String taskId, final boolean hidGui,
        final java.util.function.Consumer<Boolean> hud) {
        this.board = board;
        this.taskId = taskId;
        this.frames = 0;
        this.hidGui = hidGui;
        this.hud = hud;
        this.reopen = false;
    }

    /**
     * Calls off a shot still waiting (the world went, or the player left): the overlays come back, nothing is taken.
     */
    void cancel() {
        if (board == null && !reopen) return;
        if (board != null && hud != null) hud.accept(hidGui);
        board = null;
        taskId = null;
        frames = 0;
        hud = null;
        reopen = false;
    }

    @SubscribeEvent
    public void onWorldUnload(final WorldEvent.Unload event) {
        if (event.world != null && event.world.isRemote) cancel();
    }

    @SubscribeEvent
    public void onLoggedOut(final FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        cancel();
    }

    /** Whether a screenshot can be taken: in a world, with none being taken. */
    public static boolean canTake() {
        return Minecraft.getMinecraft().theWorld != null && INSTANCE.board == null && !INSTANCE.reopen;
    }

    /**
     * Takes a screenshot of the world for a task: the planner closes, the world is drawn without the hand and the
     * game's overlays, its pixels are read, and the planner opens again on the task with the shot on it.
     */
    public static void take(final Graph board, final String taskId) {
        if (!canTake()) {
            Sfx.DENY.play();
            return;
        }
        final Minecraft mc = Minecraft.getMinecraft();
        INSTANCE.begin(board, taskId, mc.gameSettings.hideGUI, hide -> mc.gameSettings.hideGUI = hide);
        mc.gameSettings.hideGUI = true;
        mc.displayGuiScreen(null);
    }

    @SubscribeEvent
    public void onRenderTick(final TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || board == null) return;
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null || ++frames < SETTLE_FRAMES) return;
        BufferedImage shot = null;
        try {
            shot = grab(mc.displayWidth, mc.displayHeight);
        } catch (final RuntimeException | LinkageError e) {
            GtnhPlanner.LOG.warn("Could not read the screen for a task's screenshot", e);
        }
        mc.gameSettings.hideGUI = hidGui;
        final Graph g = board;
        final String id = taskId;
        board = null;
        taskId = null;
        hud = null;
        if (shot != null && g != null && id != null) attach(g, id, shot);
        if (g != null && id != null) {
            final Plan plan = Plan.getInstance();
            final int at = plan.getGraphs()
                .indexOf(g);
            if (at >= 0) plan.setActiveIndex(at);
            TaskPanel.openWhenShown(g, id);
        }
        reopen = true;
    }

    @SubscribeEvent
    public void onClientTick(final TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !reopen) return;
        reopen = false;
        if (Minecraft.getMinecraft().theWorld != null) Planner.open();
    }

    /** The frame just drawn, as the window shows it (the game's own frame buffer is still bound at a tick's end). */
    private static BufferedImage grab(final int w, final int h) {
        final ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 3);
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, w, h, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, buf);
        final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        final int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            // GL's rows run bottom up.
            final int base = (h - 1 - y) * w * 3;
            for (int x = 0; x < w; x++) {
                final int i = base + x * 3;
                row[x] = (buf.get(i) & 0xFF) << 16 | (buf.get(i + 1) & 0xFF) << 8 | buf.get(i + 2) & 0xFF;
            }
            img.setRGB(0, y, w, 1, row, 0, w);
        }
        return img;
    }

    /**
     * Puts a shot on a task: the full picture saved in the screenshots folder (in the background), its thumbnail on a
     * new picture thing at the bottom of the task's things.
     */
    static void attach(final Graph board, final String taskId, final BufferedImage shot) {
        final String file = fileName();
        final File dir = new File(Minecraft.getMinecraft().mcDataDir, "screenshots");
        final Thread saver = new Thread(() -> {
            try {
                dir.mkdirs();
                ImageIO.write(shot, "png", new File(dir, file));
            } catch (final IOException e) {
                GtnhPlanner.LOG.warn("Could not save a task's screenshot", e);
            }
        }, "GTNH Planner task screenshot");
        saver.setDaemon(true);
        saver.start();
        final String thumb;
        try {
            thumb = dataUrl(thumbnail(shot));
        } catch (final IOException e) {
            GtnhPlanner.LOG.warn("Could not make a task screenshot's thumbnail", e);
            Sfx.DENY.play();
            return;
        }
        if (TaskEdits.task(board, taskId) == null) return;
        final Tasks.Picture picture = new Tasks.Picture(thumb, shot.getWidth(), shot.getHeight(), TaskLook.now(), file);
        PlanAPI.recordEdit(
            board,
            () -> TaskEdits.addThing(
                board,
                UUID.randomUUID()
                    .toString(),
                taskId,
                Tasks.PICTURE,
                "Screenshot",
                null,
                null,
                picture));
        board.touchLayout();
        PlanAPI.save();
        Sfx.NOTE_STICK.play();
    }

    /** A name in the screenshots folder no other shot has, as the game names its own. */
    private static String fileName() {
        final String stamp = new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date());
        final File dir = new File(Minecraft.getMinecraft().mcDataDir, "screenshots");
        String name = "task_" + stamp + ".png";
        for (int i = 2; new File(dir, name).exists(); i++) name = "task_" + stamp + "_" + i + ".png";
        return name;
    }

    /** The shot made {@value #THUMB_W} pixels wide, halved step by step so it stays smooth. */
    static BufferedImage thumbnail(final BufferedImage shot) {
        BufferedImage img = shot;
        int w = img.getWidth(), h = img.getHeight();
        while (w / 2 >= THUMB_W) {
            w /= 2;
            h /= 2;
            img = scaled(img, w, h);
        }
        return scaled(img, THUMB_W, Math.max(1, Math.round(THUMB_W * shot.getHeight() / (float) shot.getWidth())));
    }

    private static BufferedImage scaled(final BufferedImage from, final int w, final int h) {
        final BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(from, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** A JPEG data URL of the picture, at the best quality that keeps it under {@value #THUMB_BYTES} bytes. */
    static String dataUrl(final BufferedImage img) throws IOException {
        return "data:image/jpeg;base64," + Base64.getEncoder()
            .encodeToString(fit(img).bytes());
    }

    /** A JPEG and the quality it was made at. */
    record Encoded(byte[] bytes, float quality) {}

    /**
     * The picture as a JPEG of at most {@value #THUMB_BYTES} bytes: the best quality that fits, stepping down; a
     * picture that is still too big at the lowest is made smaller and tried again.
     */
    static Encoded fit(final BufferedImage img) throws IOException {
        BufferedImage at = img;
        while (true) {
            byte[] bytes = null;
            float q = 0.85f;
            for (; q >= 0.1f; q -= 0.05f) {
                bytes = jpeg(at, q);
                if (bytes.length <= THUMB_BYTES) return new Encoded(bytes, q);
            }
            if (at.getWidth() <= 16) return new Encoded(bytes, q);
            at = scaled(at, at.getWidth() * 3 / 4, Math.max(1, at.getHeight() * 3 / 4));
        }
    }

    private static byte[] jpeg(final BufferedImage img, final float quality) throws IOException {
        final Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) throw new IOException("no JPEG writer");
        final ImageWriter writer = writers.next();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream);
            final ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            writer.write(null, new IIOImage(img, null, null), p);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    // endregion

    // region Drawing them

    /** A picture ready to draw: its texture and its size in pixels. */
    public record Texture(ResourceLocation loc, int w, int h) {}

    /** Textures made, by the thumbnail's data URL or the full shot's file; a picture that would not read is null. */
    private static final Map<String, Texture> MADE = new LinkedHashMap<>(16, 0.75f, true);
    private static final java.util.Set<String> FAILED = new java.util.HashSet<>();
    private static final int KEEP = 48;
    private static int made;

    /** A thumbnail's texture, made the first time it is drawn; null when it does not read as a picture. */
    @Nullable
    public static Texture thumb(final Tasks.Picture p) {
        return texture(p.thumb(), () -> {
            final int comma = p.thumb()
                .indexOf(',');
            if (comma < 0) return null;
            final byte[] bytes = Base64.getDecoder()
                .decode(
                    p.thumb()
                        .substring(comma + 1));
            return ImageIO.read(new ByteArrayInputStream(bytes));
        });
    }

    /** The full shot's texture when it is in this game's screenshots folder, else null. */
    @Nullable
    public static Texture full(final Tasks.Picture p) {
        if (p.file() == null || p.file()
            .contains("/")
            || p.file()
                .contains("\\"))
            return null;
        final File f = new File(new File(Minecraft.getMinecraft().mcDataDir, "screenshots"), p.file());
        if (!f.isFile()) return null;
        return texture("file:" + f.getAbsolutePath(), () -> ImageIO.read(f));
    }

    private interface Reader {

        @Nullable
        BufferedImage read() throws IOException;
    }

    @Nullable
    private static Texture texture(final String key, final Reader reader) {
        final Texture t = MADE.get(key);
        if (t != null || FAILED.contains(key)) return t;
        BufferedImage img;
        try {
            img = reader.read();
        } catch (final IOException | RuntimeException e) {
            img = null;
        }
        if (img == null) {
            FAILED.add(key);
            return null;
        }
        final DynamicTexture dynamic = new DynamicTexture(img);
        final ResourceLocation loc = new ResourceLocation("gtnhplanner", "task_picture_" + ++made);
        Minecraft.getMinecraft()
            .getTextureManager()
            .loadTexture(loc, dynamic);
        // Smooth when drawn smaller than it is (the game's own textures are left sharp).
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, dynamic.getGlTextureId());
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        final Texture made = new Texture(loc, img.getWidth(), img.getHeight());
        MADE.put(key, made);
        while (MADE.size() > KEEP) {
            final Iterator<Map.Entry<String, Texture>> oldest = MADE.entrySet()
                .iterator();
            final Texture gone = oldest.next()
                .getValue();
            oldest.remove();
            Minecraft.getMinecraft()
                .getTextureManager()
                .deleteTexture(gone.loc());
        }
        return made;
    }

    /** A picture in a rectangle, cropped to fill it (its middle kept), on a dark well while it has none. */
    public static void draw(@Nullable final Texture t, final float x, final float y, final float w, final float h) {
        if (t == null) {
            Hyb.rect(x, y, w, h, 0xFF16171A);
            return;
        }
        // Cropped to the rectangle's shape: the texture's middle part, by its coordinates.
        final float pic = t.w() / (float) t.h(), box = w / h;
        float u0 = 0, v0 = 0, u1 = 1, v1 = 1;
        if (pic > box) {
            final float keep = box / pic;
            u0 = (1 - keep) / 2;
            u1 = 1 - u0;
        } else if (pic < box) {
            final float keep = pic / box;
            v0 = (1 - keep) / 2;
            v1 = 1 - v0;
        }
        Minecraft.getMinecraft()
            .getTextureManager()
            .bindTexture(t.loc());
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1, 1, 1, 1);
        final net.minecraft.client.renderer.Tessellator tess = net.minecraft.client.renderer.Tessellator.instance;
        tess.startDrawingQuads();
        tess.addVertexWithUV(x, y + h, 0, u0, v1);
        tess.addVertexWithUV(x + w, y + h, 0, u1, v1);
        tess.addVertexWithUV(x + w, y, 0, u1, v0);
        tess.addVertexWithUV(x, y, 0, u0, v0);
        tess.draw();
    }

    // endregion
}
