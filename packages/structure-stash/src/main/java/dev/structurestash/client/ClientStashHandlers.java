package dev.structurestash.client;

import dev.assetshelf.api.AssetShelfApi;
import dev.assetshelf.api.AssetType;
import dev.structurestash.compat.StructureAssetType;
import dev.structurestash.network.CapturedStructurePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Client-side payload handler bodies for Structure Stash's network.
 * <p>
 * These methods touch client-only types ({@link Minecraft}, the Asset Shelf
 * client GUI, …). They are isolated in this client-package class so that
 * {@code StashNetwork} — which is loaded and verified on the dedicated server
 * during payload registration — never contains a reference to a client-only
 * type. {@code StashNetwork} delegates here via plain static calls, which are
 * only resolved/executed on the physical client.
 */
public final class ClientStashHandlers {

    private ClientStashHandlers() {}

    public static void handleCapturedStructure(CapturedStructurePayload payload) {
        var mc = Minecraft.getInstance();
        AssetType type = AssetShelfApi.getType(StructureAssetType.TYPE_ID);
        String typeName = type != null ? type.displayName().getString() : "Structures";
        int accent = type != null ? type.accentColor() : 0xFF6AAFCF;

        dev.assetshelf.client.gui.SaveAssetScreen.Builder builder =
            dev.assetshelf.client.gui.SaveAssetScreen.saveLocal(mc.screen)
                .assetTypeInfo(typeName, accent)
                .heroSubtitle("save captured structure to\nyour local library")
                .defaultName(payload.name())
                .defaultTags(java.util.List.of(payload.sizeX() + "x" + payload.sizeZ()))
                .onAction((name, description, tags) -> {
                    AssetShelfApi.saveLocal(StructureAssetType.TYPE_ID, payload.data(),
                        name, payload.sizeX(), payload.sizeZ(), tags);
                    if (mc.player != null)
                        mc.player.displayClientMessage(
                            Component.literal("Saved to library: " + name), true);
                });

        if (type != null) {
            builder.thumbnailFromBytes(payload.data(), type, payload.sizeX(), payload.sizeZ());
            var ext = type.createModalExtension(payload.data(), false);
            if (ext != null) builder.extension(ext);
        }

        mc.setScreen(builder.build());
    }
}
