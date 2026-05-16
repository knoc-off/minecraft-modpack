package dev.paintcraft.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Function;

public final class DecalRenderType extends RenderStateShard {

    private static final Function<ResourceLocation, RenderType> DECAL = Util.memoize(
        tex -> RenderType.create(
            "paintcraft_decal",
            DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
            VertexFormat.Mode.QUADS,
            1536,
            false,
            true,
            RenderType.CompositeState.builder()
                .setShaderState(RENDERTYPE_TEXT_SHADER)
                .setTextureState(new RenderStateShard.TextureStateShard(tex, false, false))
                .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                .setCullState(NO_CULL)
                .setLightmapState(LIGHTMAP)
                .setLayeringState(POLYGON_OFFSET_LAYERING)
                .createCompositeState(false)
        )
    );

    private DecalRenderType() {
        super("paintcraft_decal", () -> {}, () -> {});
    }

    public static RenderType decal(ResourceLocation texture) {
        return DECAL.apply(texture);
    }
}
