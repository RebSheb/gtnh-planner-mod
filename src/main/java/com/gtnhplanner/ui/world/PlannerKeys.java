package com.gtnhplanner.ui.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

import com.gtnhplanner.ui.PlannerSettings;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * The planner's keys while playing, in Minecraft's Controls under GTNH Planner: the minimap (show, zoom, pan,
 * re-centre), the AR lens, and linking the machine under the crosshair to a card.
 */
public final class PlannerKeys {

    private static final String CATEGORY = "key.categories.neiflowchart";

    public static final KeyBinding MINIMAP = new KeyBinding("key.gtnhplanner.minimap", Keyboard.KEY_N, CATEGORY);
    public static final KeyBinding ZOOM_IN = new KeyBinding(
        "key.gtnhplanner.minimapZoomIn",
        Keyboard.KEY_RBRACKET,
        CATEGORY);
    public static final KeyBinding ZOOM_OUT = new KeyBinding(
        "key.gtnhplanner.minimapZoomOut",
        Keyboard.KEY_LBRACKET,
        CATEGORY);
    static final KeyBinding PAN_UP = new KeyBinding("key.gtnhplanner.minimapUp", Keyboard.KEY_UP, CATEGORY);
    static final KeyBinding PAN_DOWN = new KeyBinding("key.gtnhplanner.minimapDown", Keyboard.KEY_DOWN, CATEGORY);
    static final KeyBinding PAN_LEFT = new KeyBinding("key.gtnhplanner.minimapLeft", Keyboard.KEY_LEFT, CATEGORY);
    static final KeyBinding PAN_RIGHT = new KeyBinding("key.gtnhplanner.minimapRight", Keyboard.KEY_RIGHT, CATEGORY);
    static final KeyBinding RECENTRE = new KeyBinding("key.gtnhplanner.minimapRecentre", Keyboard.KEY_NONE, CATEGORY);
    public static final KeyBinding AR = new KeyBinding("key.gtnhplanner.ar", Keyboard.KEY_Y, CATEGORY);
    static final KeyBinding LINK = new KeyBinding("key.gtnhplanner.link", Keyboard.KEY_L, CATEGORY);
    public static final KeyBinding PIN_LIST = new KeyBinding("key.gtnhplanner.pinList", Keyboard.KEY_NONE, CATEGORY);
    public static final KeyBinding TASK_SHOW = new KeyBinding("key.gtnhplanner.taskShow", Keyboard.KEY_NONE, CATEGORY);

    /**
     * The keys held with sneak to adjust a placed machine and the plan over the world (R also turns the machine being
     * placed). They are read by their code ({@link #is}), never through the game's key table, which holds one binding a
     * key: each tick a key one of them shares with another binding is handed back to that one, so setting these never
     * takes a key from anything else.
     */
    public static final KeyBinding ROTATE = new KeyBinding("key.gtnhplanner.rotate", Keyboard.KEY_R, CATEGORY);
    static final KeyBinding PICK_UP = new KeyBinding("key.gtnhplanner.pickUp", Keyboard.KEY_G, CATEGORY);
    static final KeyBinding REMOVE = new KeyBinding("key.gtnhplanner.remove", Keyboard.KEY_DELETE, CATEGORY);
    public static final KeyBinding FOCUS = new KeyBinding("key.gtnhplanner.focus", Keyboard.KEY_U, CATEGORY);
    public static final KeyBinding LABELS = new KeyBinding("key.gtnhplanner.labels", Keyboard.KEY_I, CATEGORY);

    private static final KeyBinding[] BY_CODE = { ROTATE, PICK_UP, REMOVE, FOCUS, LABELS };

    /** Whether a key is the one a binding is set to; one set to none matches nothing. */
    static boolean is(final KeyBinding binding, final int key) {
        return key != Keyboard.KEY_NONE && binding.getKeyCode() == key;
    }

    /** A key held with sneak, as the settings name it: "Shift+U" (the sneak key as bound). */
    public static String held(final KeyBinding binding) {
        return PlacementKeys.keyName(Minecraft.getMinecraft().gameSettings.keyBindSneak.getKeyCode()) + "+"
            + label(binding);
    }

    /** A binding's key as people say it. */
    public static String label(final KeyBinding binding) {
        return keyLabel(binding.getKeyCode());
    }

    /** The game's key table and every binding, once found; null until then, or when they cannot be reached. */
    @javax.annotation.Nullable
    private static net.minecraft.util.IntHashMap table;
    @javax.annotation.Nullable
    private static java.util.List<KeyBinding> bindings;
    private static boolean unreachable;

    /**
     * Gives every key one of {@link #BY_CODE} holds in the game's key table back to the binding the game would have
     * there without it (the last other one set to that key), or empties it. Out of reach (it never is in this game),
     * ours keep their keys as any binding does.
     */
    /** For the dev harness: tries again, and says whether the key table is in reach. */
    public static boolean yieldsKeys() {
        unreachable = false;
        yieldKeys();
        return !unreachable;
    }

    @SuppressWarnings("unchecked")
    private static void yieldKeys() {
        if (unreachable) return;
        try {
            if (table == null) {
                table = cpw.mods.fml.relauncher.ReflectionHelper
                    .getPrivateValue(KeyBinding.class, null, "hash", "field_74514_b");
                bindings = cpw.mods.fml.relauncher.ReflectionHelper
                    .getPrivateValue(KeyBinding.class, null, "keybindArray", "field_74516_a");
            }
            for (final KeyBinding k : BY_CODE) {
                final int code = k.getKeyCode();
                if (code == Keyboard.KEY_NONE || table.lookup(code) != k) continue;
                KeyBinding other = null;
                for (final KeyBinding b : bindings) if (b.getKeyCode() == code && !java.util.Arrays.asList(BY_CODE)
                    .contains(b)) other = b;
                if (other != null) table.addKey(code, other);
                else table.removeObject(code);
            }
        } catch (final RuntimeException e) {
            unreachable = true;
            com.gtnhplanner.GtnhPlanner.LOG
                .warn("The game's key table is out of reach: the world keys take their keys", e);
        }
    }

    public PlannerKeys() {}

    /**
     * One of the minimap's keys as the player has it bound, written as people say it ("N", "]", "Up"), for text that
     * says which to press: "show" (show or hide it), "in", "out" (zoom), "up", "down", "left", "right" (move).
     */
    public static String minimapKey(final String what) {
        final KeyBinding k = switch (what) {
            case "show" -> MINIMAP;
            case "in" -> ZOOM_IN;
            case "out" -> ZOOM_OUT;
            case "up" -> PAN_UP;
            case "down" -> PAN_DOWN;
            case "left" -> PAN_LEFT;
            case "right" -> PAN_RIGHT;
            default -> null;
        };
        return k == null ? "?" : keyLabel(k.getKeyCode());
    }

    /** Whether the minimap moves with the arrow keys, as it does until they are bound to others. */
    public static boolean minimapOnArrows() {
        return PAN_UP.getKeyCode() == Keyboard.KEY_UP && PAN_DOWN.getKeyCode() == Keyboard.KEY_DOWN
            && PAN_LEFT.getKeyCode() == Keyboard.KEY_LEFT
            && PAN_RIGHT.getKeyCode() == Keyboard.KEY_RIGHT;
    }

    /** A key's name as people say it: [ and ] as themselves, Ctrl, Shift and Alt, else the game's. */
    public static String keyLabel(final int code) {
        return switch (code) {
            case Keyboard.KEY_LBRACKET -> "[";
            case Keyboard.KEY_RBRACKET -> "]";
            case Keyboard.KEY_MINUS -> "-";
            case Keyboard.KEY_EQUALS -> "=";
            case Keyboard.KEY_COMMA -> ",";
            case Keyboard.KEY_PERIOD -> ".";
            case Keyboard.KEY_SLASH -> "/";
            case Keyboard.KEY_NONE -> "(none)";
            default -> PlacementKeys.keyName(code);
        };
    }

    public static void register() {
        for (final KeyBinding k : new KeyBinding[] { MINIMAP, ZOOM_IN, ZOOM_OUT, PAN_UP, PAN_DOWN, PAN_LEFT, PAN_RIGHT,
            RECENTRE, AR, LINK, PIN_LIST, TASK_SHOW, ROTATE, PICK_UP, REMOVE, FOCUS, LABELS })
            ClientRegistry.registerKeyBinding(k);
    }

    /** The presses: show or hide, zoom a step, re-centre, the AR lens, linking. */
    @SubscribeEvent
    public void onKey(final InputEvent.KeyInputEvent event) {
        if (Minecraft.getMinecraft().currentScreen != null) return;
        while (MINIMAP.isPressed()) {
            PlannerSettings.setMinimap(!PlannerSettings.minimap());
            (PlannerSettings.minimap() ? com.gtnhplanner.ui.sound.Sfx.TOGGLE_ON
                : com.gtnhplanner.ui.sound.Sfx.TOGGLE_OFF).play();
        }
        // The plan over the world on and off, with sneak held or not (Shift + Y in the keys strip).
        while (AR.isPressed()) {
            PlannerSettings.setArLens(!PlannerSettings.arLens());
            (PlannerSettings.arLens() ? com.gtnhplanner.ui.sound.Sfx.TOGGLE_ON
                : com.gtnhplanner.ui.sound.Sfx.TOGGLE_OFF).play();
            WorldView.say(PlannerSettings.arLens() ? "Plan over the world: on" : "Plan over the world: off");
        }
        while (LINK.isPressed()) LinkTarget.beginForCrosshair();
        while (PIN_LIST.isPressed()) {
            PlannerSettings.setPinList(!PlannerSettings.pinList());
            (PlannerSettings.pinList() ? com.gtnhplanner.ui.sound.Sfx.TOGGLE_ON
                : com.gtnhplanner.ui.sound.Sfx.TOGGLE_OFF).play();
        }
        while (TASK_SHOW.isPressed()) {
            PlannerSettings.setTaskShow((PlannerSettings.taskShow() + 1) % PlannerSettings.TASK_SHOW_NAMES.length);
            com.gtnhplanner.ui.sound.Sfx.TOGGLE_ON.play();
            WorldView.say("Task locations: " + PlannerSettings.TASK_SHOW_NAMES[PlannerSettings.taskShow()]);
        }
        // Placing a machine takes [ and ] for its size.
        if (!PlannerSettings.minimap() || LinkPicker.active()) {
            while (ZOOM_IN.isPressed() || ZOOM_OUT.isPressed()) {}
            return;
        }
        while (ZOOM_IN.isPressed()) PlannerSettings.setMinimapZoomIndex(PlannerSettings.minimapZoomIndex() + 1);
        while (ZOOM_OUT.isPressed()) PlannerSettings.setMinimapZoomIndex(PlannerSettings.minimapZoomIndex() - 1);
        while (RECENTRE.isPressed()) Minimap.INSTANCE.recentre();
    }

    /** Panning goes on while the keys are held, at a steady speed on screen whatever the zoom. */
    @SubscribeEvent
    public void onTick(final TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) yieldKeys();
        if (event.phase != TickEvent.Phase.END || Minecraft.getMinecraft().currentScreen != null
            || !PlannerSettings.minimap()) return;
        final int dx = (PAN_RIGHT.getIsKeyPressed() ? 1 : 0) - (PAN_LEFT.getIsKeyPressed() ? 1 : 0);
        final int dy = (PAN_DOWN.getIsKeyPressed() ? 1 : 0) - (PAN_UP.getIsKeyPressed() ? 1 : 0);
        if (dx != 0 || dy != 0) Minimap.INSTANCE.pan(dx, dy);
    }
}
