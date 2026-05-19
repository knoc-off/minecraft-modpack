package dev.paintcraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.jetbrains.annotations.Nullable;

/**
 * A DynamicTexture that carries companion normal and specular DynamicTextures.
 * Iris/NeOculus discovers PBR companions by looking up the texture's exact class
 * in PBRTextureLoaderRegistry. By using a distinct subclass, we can register a
 * loader specifically for PaintCraft's atlas textures without interfering with
 * other DynamicTextures.
 */
public class PBRDynamicTexture extends DynamicTexture {

    private @Nullable DynamicTexture normalCompanion;
    private @Nullable DynamicTexture specularCompanion;

    public PBRDynamicTexture(NativeImage image) {
        super(image);
    }

    public void setNormalCompanion(@Nullable DynamicTexture normalCompanion) {
        this.normalCompanion = normalCompanion;
    }

    public void setSpecularCompanion(@Nullable DynamicTexture specularCompanion) {
        this.specularCompanion = specularCompanion;
    }

    public @Nullable DynamicTexture getNormalCompanion() {
        return normalCompanion;
    }

    public @Nullable DynamicTexture getSpecularCompanion() {
        return specularCompanion;
    }

    @Override
    public void close() {
        super.close();
        if (normalCompanion != null) {
            normalCompanion.close();
            normalCompanion = null;
        }
        if (specularCompanion != null) {
            specularCompanion.close();
            specularCompanion = null;
        }
    }
}
