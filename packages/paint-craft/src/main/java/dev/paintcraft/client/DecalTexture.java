package dev.paintcraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.paintcraft.core.ColorFormat;
import dev.paintcraft.core.Decal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

public class DecalTexture implements AutoCloseable {

    private final DynamicTexture texture;
    private final ResourceLocation location;

    public DecalTexture(Decal decal) {
        NativeImage image = new NativeImage(decal.widthPx(), decal.heightPx(), true);
        writePixels(image, decal.pixels(), decal.widthPx());
        this.texture = new DynamicTexture(image);
        this.location = Minecraft.getInstance().getTextureManager()
            .register("paintcraft_decal", texture);
    }

    public void updatePixels(int[] argbPixels, int width) {
        NativeImage img = texture.getPixels();
        if (img == null) return;
        writePixels(img, argbPixels, width);
        texture.upload();
    }

    public ResourceLocation location() {
        return location;
    }

    @Override
    public void close() {
        Minecraft.getInstance().getTextureManager().release(location);
        texture.close();
    }

    private static void writePixels(NativeImage img, int[] argb, int width) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int c = argb[y * width + x];
                img.setPixelRGBA(x, y, ColorFormat.argbToAbgr(c));
            }
        }
    }
}
