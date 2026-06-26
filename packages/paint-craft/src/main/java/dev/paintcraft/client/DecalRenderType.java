package dev.paintcraft.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DecalRenderType extends RenderStateShard {

    /**
     * Populated by ClientModEvents.onRegisterShaders — valid from first resource load onward.
     * Referenced lazily by the ShaderStateShard supplier so early RenderType creation is safe.
     */
    static ShaderInstance decalShader;

    /**
     * During each RenderType.setupRenderState() call, push the current depthBias config value
     * into the shader uniform. apply() (called after setupRenderState) then uploads it to the GPU.
     * Works for both the VBO path (DecalRenderer) and the MultiBufferSource path (contraptions).
     */
    private static final LayeringStateShard DEPTH_BIAS_SHARD = new LayeringStateShard(
        "paintcraft_depth_bias",
        () -> {
            if (decalShader != null) {
                var u = decalShader.getUniform("DepthBias");
                if (u != null) u.set((float) dev.paintcraft.ModConfig.CONFIG.depthBias.get().doubleValue());
            }
        },
        () -> {}
    );

    private static final Map<ResourceLocation, RenderType> CACHE = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, RenderType> GHOST_CACHE = new ConcurrentHashMap<>();

    private DecalRenderType() {
        super("paintcraft_decal", () -> {}, () -> {});
    }

    /**
     * Decal render type backed by our custom depth-bias shader.
     * Z-fighting is eliminated in the vertex shader (constant NDC-z offset),
     * so no glPolygonOffset or world-space normal offset is needed.
     */
    public static RenderType decal(ResourceLocation texture) {
        return CACHE.computeIfAbsent(texture, tex ->
            RenderType.create(
                "paintcraft_decal",
                DefaultVertexFormat.BLOCK,
                VertexFormat.Mode.QUADS,
                1536,
                false,
                true,
                RenderType.CompositeState.builder()
                    .setShaderState(new ShaderStateShard(() -> decalShader))
                    .setTextureState(new TextureStateShard(tex, false, false))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(COLOR_DEPTH_WRITE)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setCullState(NO_CULL)
                    .setLightmapState(LIGHTMAP)
                    .setLayeringState(DEPTH_BIAS_SHARD)
                    .createCompositeState(false)
            )
        );
    }

    /**
     * Ghost/preview render type for stamp overlay. Uses translucent blending
     * and default depth test so the ghost blends over existing geometry.
     */
    public static RenderType ghostDecal(ResourceLocation texture) {
        return GHOST_CACHE.computeIfAbsent(texture, tex ->
            RenderType.create(
                "paintcraft_ghost_decal",
                DefaultVertexFormat.BLOCK,
                VertexFormat.Mode.QUADS,
                1536,
                false,
                true,
                RenderType.CompositeState.builder()
                    .setShaderState(RENDERTYPE_TEXT_SHADER)
                    .setTextureState(new TextureStateShard(tex, false, false))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setLightmapState(LIGHTMAP)
                    .createCompositeState(false)
            )
        );
    }
}
