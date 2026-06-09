package dev.paintcraft.compat;

import dev.assetshelf.api.AssetShelfApi;
import dev.assetshelf.api.AssetType;
import dev.assetshelf.client.gui.SaveAssetScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.List;

/**
 * Opens the save-to-shelf modal for a painting from the editor.
 * Delegates entirely to asset-shelf's SaveAssetScreen.
 */
public final class SaveToLibraryScreen {

    private SaveToLibraryScreen() {}

    public static void open(Screen parent, int widthPx, int heightPx, int[] pixels) {
        Minecraft mc = Minecraft.getInstance();
        AssetType type = AssetShelfApi.getType(AssetShelfCompat.TYPE_ID);
        int accent = type != null ? type.accentColor() : 0xFFC47840;
        String typeName = type != null ? type.displayName().getString() : "Paintings";

        SaveAssetScreen.Builder builder = SaveAssetScreen.saveLocal(parent)
            .thumbnail(widthPx, heightPx, pixels)
            .assetTypeInfo(typeName, accent)
            .heroSubtitle("save the painting currently in\nyour editor to your local library")
            .defaultName("")
            .defaultTags(List.of(widthPx + "x" + heightPx))
            .onAction((name, description, tags) -> {
                AssetShelfCompat.saveFromEditor(widthPx, heightPx, pixels,
                    name, tags, mc);
            });

        // Add mod-specific extension
        if (type != null) {
            var ext = type.createModalExtension(
                AssetShelfCompat.serializeCanvas(widthPx, heightPx, pixels), false);
            if (ext != null) builder.extension(ext);
        }

        mc.setScreen(builder.build());
    }
}
