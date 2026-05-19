package dev.paintcraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.paintcraft.core.ColorFormat;
import dev.paintcraft.core.Decal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * A decal's texture data, backed by a slot in a shared {@link DecalAtlas}.
 * Standalone mode (own DynamicTexture) is available for one-off use like stamp previews.
 */
public class DecalTexture implements AutoCloseable {

    // Atlas-backed path
    private final DecalAtlas.Slot slot;

    // Standalone path (stamp preview, etc.)
    private final DynamicTexture standaloneTexture;
    private final ResourceLocation standaloneLocation;
    private final int width, height;

    /** Atlas-backed constructor (normal decals). */
    public DecalTexture(Decal decal) {
        this.width = decal.widthPx();
        this.height = decal.heightPx();
        this.slot = DecalAtlas.allocate(width, height);
        this.standaloneTexture = null;
        this.standaloneLocation = null;
        slot.write(decal.pixels(), width);
    }

    /** Standalone constructor for one-off textures (stamp preview). */
    public DecalTexture(int width, int height, int[] argbPixels) {
        this.width = width;
        this.height = height;
        this.slot = null;
        NativeImage image = new NativeImage(width, height, true);
        writePixelsToImage(image, argbPixels, width);
        this.standaloneTexture = new DynamicTexture(image);
        this.standaloneLocation = Minecraft.getInstance().getTextureManager()
            .register("paintcraft_decal", standaloneTexture);
    }

    public void updatePixels(int[] argbPixels, int width) {
        if (slot != null) {
            slot.write(argbPixels, width);
        } else if (standaloneTexture != null) {
            NativeImage img = standaloneTexture.getPixels();
            if (img == null) return;
            writePixelsToImage(img, argbPixels, width);
            standaloneTexture.upload();
        }
    }

    public ResourceLocation location() {
        return slot != null ? slot.atlasLocation() : standaloneLocation;
    }

    /** Null for standalone textures. */
    public DecalAtlas.Slot atlasSlot() {
        return slot;
    }

    public boolean isAtlasBacked() {
        return slot != null;
    }

    @Override
    public void close() {
        if (slot != null) {
            slot.free();
        }
        if (standaloneTexture != null) {
            Minecraft.getInstance().getTextureManager().release(standaloneLocation);
            standaloneTexture.close();
        }
    }

    private static void writePixelsToImage(NativeImage img, int[] argb, int width) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int c = argb[y * width + x];
                img.setPixelRGBA(x, y, ColorFormat.argbToAbgr(c));
            }
        }
    }
}
