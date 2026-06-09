package dev.assetshelf.client;

import dev.assetshelf.client.gui.ShelfBrowserScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

public final class ShelfClientEvents {

    public static final KeyMapping OPEN_BROWSER = new KeyMapping(
        "key.assetshelf.open_browser",
        GLFW.GLFW_KEY_O,
        "key.categories.assetshelf"
    );

    private ShelfClientEvents() {}

    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_BROWSER);
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        if (OPEN_BROWSER.consumeClick()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen == null) {
                mc.setScreen(new ShelfBrowserScreen());
            }
        }
    }
}
