package dev.paintcraft.compat;

import com.mojang.blaze3d.platform.NativeImage;
import dev.assetshelf.api.AssetShelfApi;
import dev.assetshelf.api.AssetType;
import dev.assetshelf.api.ItemCost;
import dev.assetshelf.client.gui.ModalExtension;
import dev.paintcraft.ModItems;
import dev.paintcraft.PaintCraft;
import dev.paintcraft.core.ColorFormat;
import dev.paintcraft.core.cost.PaintCost;
import dev.paintcraft.item.StampData;
import dev.paintcraft.item.StampItem;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;

/**
 * PaintCraft's integration with Asset Shelf.
 * Registers the painting AssetType so Asset Shelf can store, browse, and give stamps.
 */
public final class AssetShelfCompat {

    public static final ResourceLocation TYPE_ID =
        ResourceLocation.fromNamespaceAndPath(PaintCraft.MODID, "painting");

    private AssetShelfCompat() {}

    public static void register() {
        AssetShelfApi.register(new PaintingAssetType());
        PaintCraft.LOGGER.info("Registered PaintCraft painting type with Asset Shelf");
    }

    /**
     * Called from SaveToLibraryScreen to save the current canvas locally.
     */
    public static void saveFromEditor(int widthPx, int heightPx, int[] pixels,
                                       String name, List<String> tags, Minecraft minecraft) {
        byte[] assetData = serializeCanvas(widthPx, heightPx, pixels);
        if (assetData.length == 0) return;

        AssetShelfApi.saveLocal(TYPE_ID, assetData, name, widthPx, heightPx, tags);

        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(
                Component.literal("Saved to library: " + name), true);
        }
    }

    /**
     * Serialize current canvas pixels into the asset format (StampData NBT bytes).
     * Pixels must be in display orientation — the editor canvas as-is, not stored orientation.
     */
    public static byte[] serializeCanvas(int widthPx, int heightPx, int[] pixels) {
        StampData data = new StampData(widthPx, heightPx, pixels.clone());
        net.minecraft.nbt.CompoundTag tag = data.save();
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            net.minecraft.nbt.NbtIo.writeCompressed(tag, baos);
            return baos.toByteArray();
        } catch (Exception e) {
            PaintCraft.LOGGER.error("Failed to serialize canvas for Asset Shelf", e);
            return new byte[0];
        }
    }

    private static class PaintingAssetType implements AssetType {

        @Override
        public ResourceLocation id() {
            return TYPE_ID;
        }

        @Override
        public Component displayName() {
            return Component.literal("Paintings");
        }

        @Override
        public int accentColor() {
            return 0xFFC47840; // warm orange
        }

        @Override
        public ResourceLocation icon() {
            return ResourceLocation.fromNamespaceAndPath("paintcraft", "textures/gui/icon.png");
        }

        @Override
        @Nullable
        public ModalExtension createModalExtension(byte[] data, boolean isPublish) {
            return new PaintCraftModalExtension(isPublish);
        }

        @Override
        public void renderThumbnail(byte[] data, NativeImage target, int size) {
            StampData stamp = deserialize(data);
            if (stamp == null) return;

            int srcW = stamp.widthPx();
            int srcH = stamp.heightPx();
            int[] pixels = stamp.pixels();

            // Scale to fit target with nearest-neighbor
            float scale = Math.min((float) size / srcW, (float) size / srcH);
            int dstW = Math.max(1, Math.round(srcW * scale));
            int dstH = Math.max(1, Math.round(srcH * scale));
            int offsetX = (size - dstW) / 2;
            int offsetY = (size - dstH) / 2;

            for (int y = 0; y < dstH; y++) {
                for (int x = 0; x < dstW; x++) {
                    int srcX = Math.min((int) (x / scale), srcW - 1);
                    int srcY = Math.min((int) (y / scale), srcH - 1);
                    int color = pixels[srcY * srcW + srcX];
                    if (((color >> 24) & 0xFF) == 0) {
                        // Transparent: checkerboard
                        boolean check = (((offsetX + x) / 4) + ((offsetY + y) / 4)) % 2 == 0;
                        color = check ? 0xFF444444 : 0xFF666666;
                    }
                    target.setPixelRGBA(offsetX + x, offsetY + y, ColorFormat.argbToAbgr(color));
                }
            }
        }

        @Override
        public void onUse(ServerPlayer player, byte[] data) {
            StampData stamp = deserialize(data);
            if (stamp == null) {
                player.displayClientMessage(Component.literal("Invalid painting data"), true);
                return;
            }

            ItemStack stack = new ItemStack(ModItems.STAMP.get());
            StampItem.setData(stack, stamp);

            if (!player.getInventory().add(stack)) {
                // Inventory full — drop at feet
                player.drop(stack, false);
            }
            player.displayClientMessage(
                Component.literal("Received stamp: " + stamp.widthBlocks() + "×" + stamp.heightBlocks()), true);
        }

        @Override
        public List<ItemCost> computeCost(byte[] data) {
            StampData stamp = deserialize(data);
            if (stamp == null) return List.of();

            List<ItemCost> costs = new java.util.ArrayList<>();
            for (ItemStack dye : PaintCost.dyeCost(stamp.pixels())) {
                costs.add(new ItemCost(dye));
            }
            return costs;
        }

        private static StampData deserialize(byte[] raw) {
            if (raw == null || raw.length == 0) return null;
            try {
                java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(raw);
                net.minecraft.nbt.CompoundTag tag = net.minecraft.nbt.NbtIo.readCompressed(bais,
                    net.minecraft.nbt.NbtAccounter.unlimitedHeap());
                return StampData.load(tag);
            } catch (Exception e) {
                PaintCraft.LOGGER.error("Failed to deserialize painting asset", e);
                return null;
            }
        }
    }
}
