package dev.structurestash.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.structurestash.StructureStash;
import dev.structurestash.client.StructureThumbnailRenderer.StructureGrid;
import dev.structurestash.item.BlueprintItem;
import dev.structurestash.item.ModDataComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.IItemDecorator;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BlueprintItemDecorator implements IItemDecorator {

    private static final int CHECK_COLOR = 0xFF44DD44;
    private static final int BG_COLOR = 0xC0000000;
    private static final int THUMB_SIZE = 16;
    private static final int MAX_CACHE = 64;

    private static final Map<Integer, ResourceLocation> thumbCache = new LinkedHashMap<>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, ResourceLocation> eldest) {
            if (size() > MAX_CACHE) {
                Minecraft.getInstance().getTextureManager().release(eldest.getValue());
                return true;
            }
            return false;
        }
    };

    /** Background thread for NBT decompression + structure parsing + C&B storage decoding. */
    private static final ExecutorService PREP_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "bp-thumb-prep");
        t.setDaemon(true);
        return t;
    });

    /** Keys currently being prepared on the background thread. */
    private static final Set<Integer> pendingKeys = ConcurrentHashMap.newKeySet();

    @Override
    public boolean render(GuiGraphics gfx, Font font, ItemStack stack, int xOffset, int yOffset) {
        if (!(stack.getItem() instanceof BlueprintItem)) return false;

        byte[] data = null;
        var bd = stack.get(ModDataComponents.BLUEPRINT_DATA.get());
        if (bd != null) data = bd.data();

        // Render structure thumbnail into the slot
        if (data != null && data.length > 0) {
            ResourceLocation thumbTex = getOrBuildThumb(data);
            if (thumbTex != null) {
                gfx.blit(thumbTex, xOffset, yOffset, 0, 0, 16, 16, 16, 16);
            }
        }

        // Render confirm checkmark on top
        if (BlueprintGhostRenderer.isConfirming()
                && data != null && data == BlueprintGhostRenderer.getLockedData()) {
            gfx.pose().pushPose();
            gfx.pose().translate(0, 0, 200);

            int bx = xOffset + 10;
            int by = yOffset + 10;
            gfx.fill(bx - 1, by - 1, bx + 7, by + 7, BG_COLOR);
            gfx.fill(bx + 0, by + 3, bx + 1, by + 4, CHECK_COLOR);
            gfx.fill(bx + 1, by + 4, bx + 2, by + 5, CHECK_COLOR);
            gfx.fill(bx + 2, by + 3, bx + 3, by + 4, CHECK_COLOR);
            gfx.fill(bx + 3, by + 2, bx + 4, by + 3, CHECK_COLOR);
            gfx.fill(bx + 4, by + 1, bx + 5, by + 2, CHECK_COLOR);
            gfx.fill(bx + 5, by + 0, bx + 6, by + 1, CHECK_COLOR);

            gfx.pose().popPose();
        }

        return false;
    }

    private static ResourceLocation getOrBuildThumb(byte[] data) {
        int key = Arrays.hashCode(data);
        ResourceLocation cached = thumbCache.get(key);
        if (cached != null) return cached;

        // Already submitted for background prep? Wait for it.
        if (pendingKeys.contains(key)) return null;
        pendingKeys.add(key);

        // Background: decompress NBT + parse structure + decode C&B storages
        byte[] dataCopy = data.clone();
        PREP_EXECUTOR.submit(() -> {
            try {
                CompoundTag root = NbtIo.readCompressed(
                    new ByteArrayInputStream(dataCopy), NbtAccounter.unlimitedHeap());
                HolderLookup.Provider registries = getRegistries();
                if (registries == null) { pendingKeys.remove(key); return; }
                StructureGrid grid = StructureThumbnailRenderer.prepareGrid(root, registries);
                if (grid == null) { pendingKeys.remove(key); return; }

                // Schedule FBO render on the render thread (one-shot)
                Minecraft.getInstance().execute(() -> {
                    try {
                        NativeImage image = new NativeImage(THUMB_SIZE, THUMB_SIZE, true);
                        StructureThumbnailRenderer.renderGridToImage(grid, image, THUMB_SIZE);
                        DynamicTexture dynTex = new DynamicTexture(image);
                        ResourceLocation loc = Minecraft.getInstance().getTextureManager()
                            .register("structurestash_bp_" + Integer.toHexString(key), dynTex);
                        thumbCache.put(key, loc);
                    } catch (Exception e) {
                        StructureStash.LOGGER.debug("Failed to render blueprint thumb: {}", e.getMessage());
                    } finally {
                        pendingKeys.remove(key);
                    }
                });
            } catch (Exception e) {
                pendingKeys.remove(key);
            }
        });

        return null; // nothing to show this frame; cache will have it on the next
    }

    private static HolderLookup.Provider getRegistries() {
        var mc = Minecraft.getInstance();
        if (mc.level != null) return mc.level.registryAccess();
        var server = mc.getSingleplayerServer();
        if (server != null) return server.registryAccess();
        return null;
    }
}
