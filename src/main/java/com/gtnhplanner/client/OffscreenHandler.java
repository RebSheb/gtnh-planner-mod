package com.gtnhplanner.client;

import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

import com.gtnhplanner.ui.BoardScreen;
import com.sbancuz.offscreen.api.OffscreenAPI;
import com.sbancuz.offscreen.window.Window;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;

/**
 * The planner in its own window. Offscreen's classes live only here, and {@link #init()} runs only when that mod is
 * loaded, so a pack without it never touches them.
 */
public final class OffscreenHandler {

    private static final KeyBinding TOGGLE = new KeyBinding(
        "key.gtnhplanner.secondscreen",
        Keyboard.KEY_F7,
        "key.categories.neiflowchart");

    private Window window;

    private OffscreenHandler() {}

    public static void init() {
        ClientRegistry.registerKeyBinding(TOGGLE);
        FMLCommonHandler.instance()
            .bus()
            .register(new OffscreenHandler());
    }

    @SubscribeEvent
    public void onKeyInput(final InputEvent.KeyInputEvent event) {
        if (!TOGGLE.isPressed()) return;
        if (OffscreenAPI.isOpen(window)) {
            OffscreenAPI.close(window);
            window = null;
            return;
        }
        window = OffscreenAPI.open(BoardScreen::create);
        OffscreenAPI.setTitle(window, "GTNH Planner");
    }
}
