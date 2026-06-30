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

    /**
     * GL polygon-offset layering shard.  Shifts decal geometry toward the camera in
     * depth-buffer-relative units, eliminating z-fighting at all distances.
     * Replaces the previous custom NDC-z bias trick which had quadratic blow-up and
     * caused decals to peek through whole blocks at range.
     */
    static final LayeringStateShard POLYGON_OFFSET_SHARD = new LayeringStateShard(
        "paintcraft_polygon_offset",
        () -> {
            RenderSystem.polygonOffset(-1f, -1f);
            RenderSystem.enablePolygonOffset();
        },
        () -> {
            RenderSystem.disablePolygonOffset();
            RenderSystem.polygonOffset(0f, 0f);
        }
    );

    private static final Map<ResourceLocation, RenderType> CACHE = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, RenderType> NO_DEPTH_CACHE = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, RenderType> GHOST_CACHE = new ConcurrentHashMap<>();

    private DecalRenderType() {
        super("paintcraft_decal", () -> {}, () -> {});
    }

    /**
     * Decal render type used for both vanilla and Iris paths.
     *
     * <p>Uses the vanilla translucent shader so Iris automatically routes our geometry
     * through its {@code gbuffers_water} pass.  Depth bias is provided by GL polygon
     * offset which is correct at all view distances (no quadratic NDC-z blowup).
     *
     * <p>Alpha discard threshold is 0.1 (vanilla translucent shader default).
     * Texels painted below ~10% alpha will be discarded — accepted regression.
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
                    .setShaderState(RENDERTYPE_TRANSLUCENT_SHADER)
                    .setTextureState(new TextureStateShard(tex, false, false))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(COLOR_DEPTH_WRITE)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setCullState(NO_CULL)
                    .setLightmapState(LIGHTMAP)
                    .setLayeringState(POLYGON_OFFSET_SHARD)
                    .createCompositeState(false)
            )
        );
    }

    /**
     * Decal render type for BlockEntityRenderer blocks (decorated pot, chest, sign...).
     *
     * <p>Identical to {@link #decal} but with a colour-only write mask: it depth-<em>tests</em>
     * (so it's still occluded by nearer geometry) but never writes depth. BER blocks contribute
     * no baked face to the chunk mesh and are drawn separately/hollow; a depth-writing decal would
     * cull that geometry and leave a see-through hole. Always drawn in the late pass, on top.
     */
    public static RenderType decalNoDepth(ResourceLocation texture) {
        return NO_DEPTH_CACHE.computeIfAbsent(texture, tex ->
            RenderType.create(
                "paintcraft_decal_no_depth",
                DefaultVertexFormat.BLOCK,
                VertexFormat.Mode.QUADS,
                1536,
                false,
                true,
                RenderType.CompositeState.builder()
                    .setShaderState(RENDERTYPE_TRANSLUCENT_SHADER)
                    .setTextureState(new TextureStateShard(tex, false, false))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setWriteMaskState(COLOR_WRITE)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .setCullState(NO_CULL)
                    .setLightmapState(LIGHTMAP)
                    .setLayeringState(POLYGON_OFFSET_SHARD)
                    .createCompositeState(false)
            )
        );
    }

    /**
     * Ghost/preview render type for stamp overlay.
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
