package dev.paintcraft.compat;

import com.mojang.blaze3d.platform.NativeImage;
import dev.assetshelf.api.AssetShelfApi;
import dev.assetshelf.api.AssetType;
import dev.assetshelf.api.ItemCost;
import dev.assetshelf.client.gui.ModalExtension;
import dev.paintcraft.ModItems;
import dev.paintcraft.PaintCraft;
import dev.paintcraft.core.ColorFormat;
import dev.paintcraft.core.Decal;
import dev.paintcraft.item.StampData;
import dev.paintcraft.item.StampItem;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
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
     */
    public static byte[] serializeCanvas(int widthPx, int heightPx, int[] pixels) {
        StampData data = new StampData(widthPx, heightPx, net.minecraft.core.Direction.UP, pixels.clone());
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

        /** Tunable cost multiplier. 1.0 = ceil(√blocks), 0.5 = half that, 2.0 = double. */
        private static final double COST_FACTOR = 1.0;

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

            int[] pixels = stamp.pixels();
            int opaqueBlocks = countOpaqueBlocks(stamp);

            // Every pixel votes for its nearest DyeColor
            int[] votes = new int[16];
            int opaquePixels = 0;

            for (int pixel : pixels) {
                if (((pixel >> 24) & 0xFF) < 128) continue;
                opaquePixels++;
                DyeColor dc = nearestDyeColor((pixel >> 16) & 0xFF, (pixel >> 8) & 0xFF, pixel & 0xFF);
                votes[dc.ordinal()]++;
            }

            if (opaquePixels == 0) return List.of();

            // Budget = ceil(√opaqueBlocks × COST_FACTOR)
            int totalBudget = Math.max(1, (int) Math.ceil(Math.sqrt(opaqueBlocks) * COST_FACTOR));

            // Distribute proportionally based on pixel vote share
            List<ItemCost> costs = new java.util.ArrayList<>();
            int dominantIdx = 0;

            for (int i = 0; i < 16; i++) {
                if (votes[i] > votes[dominantIdx]) dominantIdx = i;
            }

            for (int i = 0; i < 16; i++) {
                if (votes[i] == 0) continue;
                int dyes = Math.round((float) votes[i] / opaquePixels * totalBudget);
                if (dyes >= 1) {
                    costs.add(ItemCost.of(DyeItem.byColor(DyeColor.values()[i]), dyes));
                }
            }

            // Ensure at least 1 dye total
            if (costs.isEmpty()) {
                costs.add(ItemCost.of(DyeItem.byColor(DyeColor.values()[dominantIdx]), 1));
            }

            return costs;
        }

        private int countOpaqueBlocks(StampData stamp) {
            int[] pixels = stamp.pixels();
            int width = stamp.widthPx();
            int ppb = Decal.PX_PER_BLOCK;
            int blockW = stamp.widthBlocks();
            int blockH = stamp.heightBlocks();
            int count = 0;
            for (int by = 0; by < blockH; by++) {
                for (int bx = 0; bx < blockW; bx++) {
                    outer:
                    for (int y = by * ppb; y < (by + 1) * ppb; y++) {
                        for (int x = bx * ppb; x < (bx + 1) * ppb; x++) {
                            int idx = y * width + x;
                            if (idx < pixels.length && ((pixels[idx] >> 24) & 0xFF) >= 128) {
                                count++;
                                break outer;
                            }
                        }
                    }
                }
            }
            return count;
        }

        private static DyeColor nearestDyeColor(int r, int g, int b) {
            int[][] refs = {
                {249, 255, 254}, // WHITE
                {249, 128, 29},  // ORANGE
                {199, 78, 189},  // MAGENTA
                {58, 179, 218},  // LIGHT_BLUE
                {254, 216, 61},  // YELLOW
                {128, 199, 31},  // LIME
                {243, 139, 170}, // PINK
                {71, 79, 82},    // GRAY
                {157, 157, 151}, // LIGHT_GRAY
                {22, 156, 156},  // CYAN
                {137, 50, 184},  // PURPLE
                {60, 68, 170},   // BLUE
                {131, 84, 50},   // BROWN
                {94, 124, 22},   // GREEN
                {176, 46, 38},   // RED
                {29, 29, 33},    // BLACK
            };

            int bestDist = Integer.MAX_VALUE;
            int bestIdx = 0;
            for (int i = 0; i < refs.length; i++) {
                int dr = r - refs[i][0];
                int dg = g - refs[i][1];
                int db = b - refs[i][2];
                int dist = dr * dr + dg * dg + db * db;
                if (dist < bestDist) {
                    bestDist = dist;
                    bestIdx = i;
                }
            }
            return DyeColor.values()[bestIdx];
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
