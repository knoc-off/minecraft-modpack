package dev.paintcraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.paintcraft.core.ColorFormat;
import dev.paintcraft.core.Decal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

public class DecalTexture implements AutoCloseable {

    private final DecalAtlas.Slot slot;

    // Standalone path for stamp preview
    private final DynamicTexture standaloneTexture;
    private final ResourceLocation standaloneLocation;
    private final int width, height;

    /** Atlas-backed constructor. Writes albedo + materials to the shared atlas. */
    public DecalTexture(Decal decal) {
        this.width = decal.widthPx();
        this.height = decal.heightPx();
        this.slot = DecalAtlas.allocate(width, height);
        this.standaloneTexture = null;
        this.standaloneLocation = null;
        slot.writeAlbedo(decal.pixels(), width);
        slot.writeNormals(decal.normals(), width);
        slot.writeSpecular(decal.specular(), width);
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
            slot.writeAlbedo(argbPixels, width);
        } else if (standaloneTexture != null) {
            NativeImage img = standaloneTexture.getPixels();
            if (img == null) return;
            writePixelsToImage(img, argbPixels, width);
            standaloneTexture.upload();
        }
    }

    public void updateNormals(int[] packedNormals, int width) {
        if (slot != null) {
            slot.writeNormals(packedNormals, width);
        }
    }

    public void updateSpecular(int[] packedSpecular, int width) {
        if (slot != null) {
            slot.writeSpecular(packedSpecular, width);
        }
    }

    public ResourceLocation location() {
        return slot != null ? slot.atlasLocation() : standaloneLocation;
    }

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
