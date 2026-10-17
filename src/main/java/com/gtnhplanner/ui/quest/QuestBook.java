package com.gtnhplanner.ui.quest;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;

import org.lwjgl.opengl.GL11;

import com.gtnhplanner.GtnhPlanner;

import betterquesting.api.api.ApiReference;
import betterquesting.api.api.QuestingAPI;
import betterquesting.api.properties.NativeProps;
import betterquesting.api.questing.IQuest;
import betterquesting.api.questing.IQuestLine;
import betterquesting.api.utils.BigItemStack;
import betterquesting.api2.client.gui.GuiScreenCanvas;
import betterquesting.api2.client.gui.controls.IPanelButton;
import betterquesting.api2.client.gui.controls.PanelButtonQuest;
import betterquesting.api2.client.gui.events.PEventBroadcaster;
import betterquesting.api2.client.gui.events.PanelEvent;
import betterquesting.api2.client.gui.events.types.PEventButton;
import betterquesting.api2.client.gui.panels.IGuiCanvas;
import betterquesting.api2.client.gui.panels.IGuiPanel;
import betterquesting.api2.utils.QuestTranslation;
import betterquesting.client.gui2.GuiHome;
import betterquesting.client.gui2.GuiQuest;
import betterquesting.client.gui2.GuiQuestLines;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * The game's quest book (BetterQuesting) as tasks use it. Only ever loaded when BetterQuesting is ({@code TaskQuests}
 * checks first), so nothing here is reached without it; every call into it is guarded, so a version that differs
 * says "no quest book" rather than crashing.
 *
 * <p>
 * Picking: the real quest book opens on its quest lines, a banner says it is picking, and the quest the player clicks
 * goes to the task instead of opening. The book's quest buttons tell their listeners first (a {@link PEventButton},
 * which we hear before the book does, as we listen from the moment it opens); the book then opens the quest's page,
 * which we turn away and go back to the planner. Closing the book (Esc, its back key) goes back without one.
 */
public final class QuestBook {

    public static final QuestBook INSTANCE = new QuestBook();

    private QuestBook() {}

    /** What the quest book says of a quest: its name, icon, the line it is in, and whether the player has done it. */
    public record Look(String name, @Nullable ItemStack icon, @Nullable String line, boolean done) {}

    /** The quest by its id; null when the book has none such (or could not be read). */
    @Nullable
    public static Look look(final String id) {
        try {
            final UUID uuid = UUID.fromString(id);
            final IQuest quest = QuestingAPI.getAPI(ApiReference.QUEST_DB)
                .get(uuid);
            if (quest == null) return null;
            final Minecraft mc = Minecraft.getMinecraft();
            final boolean done = mc.thePlayer != null && quest.isComplete(QuestingAPI.getQuestingUUID(mc.thePlayer));
            return new Look(strip(QuestTranslation.translateQuestName(uuid, quest)), icon(quest), line(uuid), done);
        } catch (final IllegalArgumentException e) {
            return null;
        } catch (final RuntimeException | LinkageError e) {
            warn("read a quest", e);
            return null;
        }
    }

    @Nullable
    private static ItemStack icon(final IQuest quest) {
        final BigItemStack big = quest.getProperty(NativeProps.ICON);
        if (big == null) return null;
        final ItemStack stack = big.getBaseStack();
        return stack == null || stack.getItem() == null ? null : stack;
    }

    /** The name of a quest line the quest is in; null when it is in none. */
    @Nullable
    private static String line(final UUID quest) {
        for (final Map.Entry<UUID, IQuestLine> e : QuestingAPI.getAPI(ApiReference.LINE_DB)
            .entrySet()) {
            if (e.getValue() != null && e.getValue()
                .containsKey(quest)) return strip(QuestTranslation.translateQuestLineName(e.getKey(), e.getValue()));
        }
        return null;
    }

    /** The book's names carry the game's colour codes: words only. */
    private static String strip(final String s) {
        return s == null ? ""
            : s.replaceAll("§.", "")
                .trim();
    }

    // region Picking and opening

    /** What the book was opened for. */
    private enum Mode {
        NONE,
        PICK,
        VIEW
    }

    private Mode mode = Mode.NONE;
    /** Where a pick goes, or a view goes back to; nothing while the book is not ours. */
    @Nullable
    private Consumer<String> picked;
    @Nullable
    private Runnable back;
    /** The quest a click in the book named, before the book tried to open it. */
    @Nullable
    private UUID clicked;
    /** Hears the book's quest buttons while picking; the book forgets its listeners whenever a screen opens. */
    private final Consumer<PanelEvent> listener = this::onPanelEvent;

    /**
     * Opens the quest book to pick a quest: {@code picked} gets the id of the quest clicked, then {@code back}
     * runs; closing the book runs {@code back} alone. False when the book would not open.
     */
    public static boolean pick(final Consumer<String> picked, final Runnable back) {
        final Minecraft mc = Minecraft.getMinecraft();
        try {
            final GuiScreen book = GuiHome.bookmark instanceof final GuiQuestLines lines ? lines
                : new GuiQuestLines(null);
            if (book instanceof final GuiScreenCanvas canvas) canvas.setPreviousScreen(null);
            INSTANCE.mode = Mode.PICK;
            INSTANCE.picked = picked;
            INSTANCE.back = back;
            INSTANCE.clicked = null;
            mc.displayGuiScreen(book);
            return true;
        } catch (final RuntimeException | LinkageError e) {
            warn("open the quest book", e);
            INSTANCE.end();
            return false;
        }
    }

    /** Opens a quest's page in the book; closing it runs {@code back}. False when the book would not open it. */
    public static boolean open(final String id, final Runnable back) {
        try {
            final UUID uuid = UUID.fromString(id);
            if (QuestingAPI.getAPI(ApiReference.QUEST_DB)
                .get(uuid) == null) return false;
            INSTANCE.mode = Mode.VIEW;
            INSTANCE.picked = null;
            INSTANCE.back = back;
            Minecraft.getMinecraft()
                .displayGuiScreen(new GuiQuest(null, uuid));
            return true;
        } catch (final IllegalArgumentException e) {
            return false;
        } catch (final RuntimeException | LinkageError e) {
            warn("open a quest", e);
            INSTANCE.end();
            return false;
        }
    }

    /** Whether the book is open for a pick (for the dev harness). */
    public static boolean picking() {
        return INSTANCE.mode == Mode.PICK;
    }

    private void end() {
        mode = Mode.NONE;
        picked = null;
        back = null;
        clicked = null;
    }

    private void onPanelEvent(final PanelEvent event) {
        if (mode != Mode.PICK || !(event instanceof final PEventButton press)) return;
        try {
            final IPanelButton button = press.getButton();
            if (button instanceof final PanelButtonQuest quest && quest.getStoredValue() != null)
                clicked = quest.getStoredValue()
                    .getKey();
        } catch (final RuntimeException | LinkageError e) {
            warn("read the quest clicked", e);
        }
    }

    /** Runs after the book's own handler, which forgets every listener as a screen opens. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onGuiOpen(final GuiOpenEvent event) {
        if (mode == Mode.NONE || event.isCanceled()) return;
        try {
            final GuiScreen gui = event.gui;
            if (mode == Mode.PICK && gui instanceof final GuiQuest page) {
                // A quest was clicked: it goes to the task, and the planner comes back instead of its page.
                final UUID id = clicked != null ? clicked : questOf(page);
                final Consumer<String> to = picked;
                final Runnable then = back;
                end();
                event.gui = null;
                if (to != null && id != null) to.accept(id.toString());
                if (then != null) then.run();
                return;
            }
            if (gui != null && gui.getClass()
                .getName()
                .startsWith("betterquesting.")) {
                // Still in the book (its search, its bookmarks, a quest's own screens): listen again.
                if (mode == Mode.PICK) PEventBroadcaster.INSTANCE.register(listener, PEventButton.class);
                return;
            }
            // The book closed: back to the planner when it closed to the world, else let the new screen be.
            final Runnable then = back;
            end();
            if (gui == null && then != null) then.run();
        } catch (final RuntimeException | LinkageError e) {
            warn("follow the quest book", e);
            end();
        }
    }

    /** The quest a quest page shows, read from the page itself (should the click not have been heard). */
    @Nullable
    private static UUID questOf(final GuiQuest page) {
        try {
            final Field f = GuiQuest.class.getDeclaredField("questID");
            f.setAccessible(true);
            return f.get(page) instanceof final UUID id ? id : null;
        } catch (final ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** The banner over the book while picking: what a click does, and how to go back. */
    @SubscribeEvent
    public void onDraw(final GuiScreenEvent.DrawScreenEvent.Post event) {
        if (mode != Mode.PICK || !(event.gui instanceof GuiQuestLines)) return;
        final Minecraft mc = Minecraft.getMinecraft();
        final FontRenderer font = mc.fontRenderer;
        final ScaledResolution res = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        final String line = "Click a quest to add it to your task. Esc goes back.";
        final int w = font.getStringWidth(line) + 16, h = 16;
        final int x = (res.getScaledWidth() - w) / 2, y = 4;
        GL11.glPushMatrix();
        GL11.glTranslatef(0, 0, 400);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        // A soft pulse on its edge, so it reads as a mode the book is in.
        final double t = (System.currentTimeMillis() % 1600) / 1600.0;
        final int a = 0x90 + (int) (0x6F * (0.5 + 0.5 * Math.sin(t * 2 * Math.PI)));
        Gui.drawRect(x - 1, y - 1, x + w + 1, y + h + 1, a << 24 | 0x22D3EE);
        Gui.drawRect(x, y, x + w, y + h, 0xF0101418);
        font.drawStringWithShadow(line, x + 8, y + 4, 0xFFFFFFFF);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glPopMatrix();
    }

    /**
     * While picking, every quest the book shows can be picked, a locked one too (a task often plans what comes next);
     * the book itself lets only the ones the player may open be clicked.
     */
    @SubscribeEvent
    public void onDrawPre(final GuiScreenEvent.DrawScreenEvent.Pre event) {
        if (mode != Mode.PICK || !(event.gui instanceof final GuiQuestLines book)) return;
        try {
            enable(book.getChildren());
        } catch (final RuntimeException | LinkageError e) {
            warn("let locked quests be picked", e);
        }
    }

    private static void enable(final List<IGuiPanel> panels) {
        for (final IGuiPanel p : panels) {
            if (p instanceof final PanelButtonQuest quest) {
                if (!quest.isActive()) quest.setActive(true);
            } else if (p instanceof final IGuiCanvas canvas) enable(canvas.getChildren());
        }
    }

    // endregion

    private static boolean warned;

    private static void warn(final String what, final Throwable e) {
        if (warned) return;
        warned = true;
        GtnhPlanner.LOG.warn("Could not " + what + " in the quest book", e);
    }
}
