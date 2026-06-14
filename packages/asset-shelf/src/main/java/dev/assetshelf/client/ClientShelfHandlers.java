package dev.assetshelf.client;

import dev.assetshelf.client.gui.ShelfBrowserScreen;
import dev.assetshelf.network.BrowseResponsePayload;
import net.minecraft.client.Minecraft;

/**
 * Client-side payload handler bodies for Asset Shelf's network.
 * <p>
 * These methods touch client-only types ({@link Minecraft},
 * {@link ShelfBrowserScreen}). They are isolated in this client-package class so
 * that {@code ShelfNetwork} — which is loaded and verified on the dedicated
 * server during payload registration — never contains a reference to a
 * client-only type. {@code ShelfNetwork} delegates here via plain static calls,
 * which are only resolved/executed on the physical client.
 */
public final class ClientShelfHandlers {

    private ClientShelfHandlers() {}

    public static void handleBrowseResponse(BrowseResponsePayload payload) {
        var screen = Minecraft.getInstance().screen;
        if (screen instanceof ShelfBrowserScreen browser) {
            browser.receiveServerAssets(payload.entries(), payload.totalCount(), payload.page());
        }
    }
}
