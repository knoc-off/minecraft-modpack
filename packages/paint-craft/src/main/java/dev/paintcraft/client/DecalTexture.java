package dev.paintcraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.paintcraft.core.ColorFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * A standalone GPU texture for decal pixel data.
 * Used for stamp ghost preview rendering.
 */
public class DecalTexture implements AutoCloseable {

    private final DynamicTexture texture;
    private final ResourceLocation location;

    public DecalTexture(int width, int height, int[] argbPixels) {
        NativeImage image = new NativeImage(width, height, true);
        writePixelsToImage(image, argbPixels, width);
        this.texture = new DynamicTexture(image);
        this.location = Minecraft.getInstance().getTextureManager()
            .register("paintcraft_decal", texture);
    }

    public ResourceLocation location() {
        return location;
    }

    @Override
    public void close() {
        Minecraft.getInstance().getTextureManager().release(location);
        texture.close();
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
