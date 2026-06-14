package dev.paintcraft.client;

import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client-only registration of the mod's config screen factory.
 * <p>
 * Kept in a separate class so that the dedicated server never links the
 * client-only {@link ConfigurationScreen}/{@link IConfigScreenFactory} types.
 */
public final class PaintCraftClientConfig {

    private PaintCraftClientConfig() {}

    public static void register(ModContainer modContainer) {
        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
            (mc, parent) -> new ConfigurationScreen(modContainer, parent));
    }
}
