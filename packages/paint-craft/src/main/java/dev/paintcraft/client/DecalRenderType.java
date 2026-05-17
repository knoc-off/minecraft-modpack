package dev.paintcraft.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DecalRenderType extends RenderStateShard {

    private record Key(ResourceLocation texture, int tier) {}

    private static final Map<Key, RenderType> CACHE = new ConcurrentHashMap<>();

    private DecalRenderType() {
        super("paintcraft_decal", () -> {}, () -> {});
    }

    /**
     * Get a decal render type for the given texture and z-tier.
     * Each tier gets progressively more polygon offset (10 additional units per tier),
     * ensuring higher-tier decals always pass the depth test against lower-tier ones
     * regardless of quad size differences or GPU rasterization jitter.
     */
    public static RenderType decal(ResourceLocation texture, int tier) {
        return CACHE.computeIfAbsent(new Key(texture, tier), k -> {
            float units = -(10.0f + k.tier * 10.0f);
            LayeringStateShard layering = new LayeringStateShard(
                "paintcraft_polygon_offset_" + k.tier,
                () -> {
                    RenderSystem.polygonOffset(-1.0f, units);
                    RenderSystem.enablePolygonOffset();
                },
                () -> {
                    RenderSystem.polygonOffset(0.0f, 0.0f);
                    RenderSystem.disablePolygonOffset();
                }
            );

            return RenderType.create(
                "paintcraft_decal_t" + k.tier,
                DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
                VertexFormat.Mode.QUADS,
                1536,
                false,
                true,
                RenderType.CompositeState.builder()
                    .setShaderState(RENDERTYPE_TEXT_SHADER)
                    .setTextureState(new TextureStateShard(k.texture, false, false))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setLightmapState(LIGHTMAP)
                    .setLayeringState(layering)
                    .createCompositeState(false)
            );
        });
    }

    /** Convenience for tier-0 decals (most common case). */
    public static RenderType decal(ResourceLocation texture) {
        return decal(texture, 0);
    }
}
