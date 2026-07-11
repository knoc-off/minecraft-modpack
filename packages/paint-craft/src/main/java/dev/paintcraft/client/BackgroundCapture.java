package dev.paintcraft.client;

import dev.paintcraft.client.compat.ChiseledBlockHelper;
import dev.paintcraft.core.Decal;
import dev.paintcraft.core.FaceFrame;
import dev.paintcraft.projection.Projection;
import dev.paintcraft.projection.ProjectionResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Captures the block textures visible in a decal's projection volume,
 * using the same depth-buffer projection as the renderer.
 * Provides depth shading so recessed surfaces appear darker.
 */
public final class BackgroundCapture {

    private static final RandomSource RANDOM = RandomSource.createNewThreadLocalInstance();

    private static final float SHADE_FACTOR = 0.5f; // 50% darker at max depth

    private static final boolean DIAG = true; // TEMP: per-pixel sampling-rate diagnostic

    private static final boolean HAS_CNB = net.neoforged.fml.ModList.get().isLoaded("chiselsandbits");

    private BackgroundCapture() {}

    /**
     * Result of a background capture.
     *
     * @param display depth-shaded pixels used to render the editor underlay
     * @param raw     unshaded pixels (same geometry, no depth darkening) used by the color picker
     */
    public record Captured(int[] display, int[] raw) {}

    public static Captured capture(BlockAndTintGetter level, BlockPos anchor, Direction face,
                                 Direction captureUp, int widthBlocks, int heightBlocks, float depth) {
        int wPx = widthBlocks * Decal.PX_PER_BLOCK;
        int hPx = heightBlocks * Decal.PX_PER_BLOCK;
        int[] background = new int[wPx * hPx];
        int[] raw = new int[wPx * hPx];

        // Build projection using FaceFrame (centralized origin computation)
        Direction up = face.getAxis().isVertical() ? captureUp : Direction.UP;
        FaceFrame frame = new FaceFrame(face, up);
        Projection vol = new Projection(frame, anchor, widthBlocks, heightBlocks, depth);

        // Collect face candidates (same as ProjectionResolver)
        AABB bounds = vol.toBoundingBox();
        List<ProjectionResolver.FaceCandidate> candidates = new ArrayList<>();

        BlockPos.betweenClosedStream(
            BlockPos.containing(bounds.minX - 1, bounds.minY - 1, bounds.minZ - 1),
            BlockPos.containing(bounds.maxX + 1, bounds.maxY + 1, bounds.maxZ + 1)
        ).forEach(pos -> {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) return;

            VoxelShape shape = state.getShape(level, pos, CollisionContext.empty());
            if (shape.isEmpty()) return;

            Vec3 blockOrigin = Vec3.atLowerCornerOf(pos);
            for (AABB box : shape.toAabbs()) {
                AABB worldBox = box.move(blockOrigin);
                ProjectionResolver.collectFaces(vol, worldBox, pos.immutable(), face, candidates);
            }
        });

        // Rasterize depth buffer + record winning face per pixel
        float[] depthBuf = new float[wPx * hPx];
        int[] winnerIdx = new int[wPx * hPx]; // index into candidates
        Arrays.fill(depthBuf, Float.MAX_VALUE);
        Arrays.fill(winnerIdx, -1);

        for (int ci = 0; ci < candidates.size(); ci++) {
            var c = candidates.get(ci);
            int px0 = toPixel(c.u0(), vol.width(), wPx);
            int py0 = toPixel(c.v0(), vol.height(), hPx);
            int px1 = toPixelMax(c.u1(), vol.width(), wPx);
            int py1 = toPixelMax(c.v1(), vol.height(), hPx);

            for (int py = py0; py <= py1; py++) {
                for (int px = px0; px <= px1; px++) {
                    int idx = py * wPx + px;
                    if (idx >= 0 && idx < depthBuf.length && c.depth() < depthBuf[idx]) {
                        depthBuf[idx] = c.depth();
                        winnerIdx[idx] = ci;
                    }
                }
            }
        }

        // Find max depth for shading normalization
        float maxDepth = 0;
        for (float d : depthBuf) {
            if (d < Float.MAX_VALUE && d > maxDepth) maxDepth = d;
        }
        if (maxDepth < 0.001f) maxDepth = 1.0f;

        boolean depthShading = dev.paintcraft.ModConfig.CONFIG.editorDepthShading.get();

        // Sample textures for each pixel from the live GPU block-atlas snapshot (see
        // AtlasImageCache) — the same pixels the world renderer samples.
        for (int py = 0; py < hPx; py++) {
            for (int px = 0; px < wPx; px++) {
                int idx = py * wPx + px;
                if (winnerIdx[idx] < 0) continue;

                var hit = candidates.get(winnerIdx[idx]);

                // Sample the block's texture using world-position-based UV
                int color = sampleBlockTexture(level, hit.blockPos(), hit.faceNormal(),
                    vol, hit.depth(), px, py, wPx, hPx);
                if (color == 0) continue;

                // Apply depth shading (recessed surfaces darker) unless disabled in config.
                float brightness = depthShading ? 1.0f - (hit.depth() / maxDepth) * SHADE_FACTOR : 1.0f;
                int a = (color >> 24) & 0xFF;
                int r = (int)(((color >> 16) & 0xFF) * brightness);
                int g = (int)(((color >> 8) & 0xFF) * brightness);
                int b = (int)((color & 0xFF) * brightness);

                // V-flip: editor row 0 = top of face in world (matches renderer)
                int outY = hPx - 1 - py;
                background[outY * wPx + px] = (a << 24) | (r << 16) | (g << 8) | b;
                // Raw (unshaded) copy for the color picker.
                raw[outY * wPx + px] = color;
            }
        }

        return new Captured(background, raw);
    }

    private static int sampleBlockTexture(BlockAndTintGetter level, BlockPos pos,
                                           Direction face, Projection vol, float depth,
                                           int px, int py, int wPx, int hPx) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return 0;

        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);

        // Use NeoForge's data-aware getQuads — works for vanilla blocks (ModelData.EMPTY fallback)
        // and modded blocks with custom model data (C&B, etc.)
        BlockEntity be = level.getBlockEntity(pos);
        ModelData modelData = be != null ? be.getModelData() : ModelData.EMPTY;
        // Seed the RNG per block position exactly like the world renderer
        // (ModelBlockRenderer). Vanilla blocks such as stone/grass/sand use random texture
        // rotation; without a stable per-block seed, every pixel here would get a different
        // rotation of the 16px texture, remixing it into fake sub-pixel "HD" detail.
        RANDOM.setSeed(state.getSeed(pos));
        List<BakedQuad> quads = model.getQuads(state, face, RANDOM, modelData, null);

        // Always include null-face (generic) quads matching our direction.
        // Non-full blocks (stairs, glass panes, fences) store interior/recessed
        // faces in the null bucket since they shouldn't be face-culled.
        RANDOM.setSeed(state.getSeed(pos));
        List<BakedQuad> nullQuads = model.getQuads(state, null, RANDOM, modelData, null);
        if (!nullQuads.isEmpty()) {
            List<BakedQuad> combined = new ArrayList<>(quads);
            for (BakedQuad q : nullQuads) {
                if (q.getDirection() == face) combined.add(q);
            }
            quads = combined;
        }

        // Fallback for C&B: Scena doesn't bridge into NeoForge's ModelData,
        // so extract the resolved model directly from Scena's IBlockModelData
        if (quads.isEmpty() && HAS_CNB) {
            quads = ChiseledBlockHelper.getDataAwareQuads(level, pos, state, model, face, RANDOM);
        }

        if (quads.isEmpty()) {
            // No baked geometry: this block is drawn by a BlockEntityRenderer
            // (decorated pot, chest, sign, banner, shulker box, bed, conduit...).
            // Its block model is empty, so getQuads returns nothing and the face would
            // render transparent. Fall back to the block's particle sprite so the painted
            // face shows a solid block-shaped fill instead of a hole. We can't reproduce
            // the BER's animated geometry here, but a terracotta-coloured surface beats
            // an invisible one.
            return sampleParticleFallback(model, modelData, state, level, pos, face,
                                          vol, depth, px, py, wPx, hPx);
        }

        // Compute the world position of this pixel from the projection volume
        float localU = (px + 0.5f) / wPx * vol.width();
        float localV = (py + 0.5f) / hPx * vol.height();
        Vec3 worldPos = vol.localToWorld(localU, localV, depth);

        // Block-local sample position
        float bx = (float)(worldPos.x - pos.getX());
        float by = (float)(worldPos.y - pos.getY());
        float bz = (float)(worldPos.z - pos.getZ());

        // Find the quad that contains the sample point by checking edge-projected s,t bounds.
        // For standard blocks (1 quad per face), this matches immediately.
        // For C&B blocks (many small quads), this finds the correct bit-quad.
        for (BakedQuad quad : quads) {
            int[] vertices = quad.getVertices();
            float[] vx = new float[4], vy = new float[4], vz = new float[4];
            float[] au = new float[4], av = new float[4];

            for (int i = 0; i < 4; i++) {
                vx[i] = Float.intBitsToFloat(vertices[i * 8]);
                vy[i] = Float.intBitsToFloat(vertices[i * 8 + 1]);
                vz[i] = Float.intBitsToFloat(vertices[i * 8 + 2]);
                au[i] = Float.intBitsToFloat(vertices[i * 8 + 4]);
                av[i] = Float.intBitsToFloat(vertices[i * 8 + 5]);
            }

            // Edge vectors from vertex 0
            float e1x = vx[1]-vx[0], e1y = vy[1]-vy[0], e1z = vz[1]-vz[0];
            float e2x = vx[3]-vx[0], e2y = vy[3]-vy[0], e2z = vz[3]-vz[0];

            // Delta from v0 to sample point
            float dx = bx-vx[0], dy = by-vy[0], dz = bz-vz[0];

            // Project onto edges
            float e1len2 = e1x*e1x + e1y*e1y + e1z*e1z;
            float e2len2 = e2x*e2x + e2y*e2y + e2z*e2z;
            if (e1len2 < 0.0001f || e2len2 < 0.0001f) continue;

            float s = (dx*e1x + dy*e1y + dz*e1z) / e1len2;
            float t = (dx*e2x + dy*e2y + dz*e2z) / e2len2;

            // Check if sample point is within this quad (with small tolerance)
            if (s < -0.01f || s > 1.01f || t < -0.01f || t > 1.01f) continue;

            s = Math.clamp(s, 0f, 1f);
            t = Math.clamp(t, 0f, 1f);

            // Bilinearly interpolate the quad's raw atlas UV, then sample the live GPU atlas
            // snapshot at that coordinate. This is exactly what the world renderer samples, so
            // the editor matches in-world regardless of any stale/HD CPU sprite source.
            float iU0 = au[0] + (au[1] - au[0]) * s;
            float iV0 = av[0] + (av[1] - av[0]) * s;
            float iU1 = au[3] + (au[2] - au[3]) * s;
            float iV1 = av[3] + (av[2] - av[3]) * s;

            float finalU = iU0 + (iU1 - iU0) * t;
            float finalV = iV0 + (iV1 - iV0) * t;

            int abgr = AtlasImageCache.sampleABGR(finalU, finalV);

            // DIAGNOSTIC: log the per-pixel sampling rate for the center row. If consecutive
            // px land on the same atlas texel in pairs -> clean 2x doubling; if every px lands
            // on a new texel -> 2x over-sampling (the source of the fake "HD" detail).
            if (DIAG && py == hPx / 2 && px < 12) {
                int aw = AtlasImageCache.width(), ah = AtlasImageCache.height();
                int tx = (int) (finalU * aw), ty = (int) (finalV * ah);
                dev.paintcraft.PaintCraft.LOGGER.info(
                    "[diag] px={} py={} s={} t={} finalU={} finalV={} atlas={}x{} texel=({},{}) abgr=0x{}",
                    px, py, String.format("%.4f", s), String.format("%.4f", t),
                    String.format("%.5f", finalU), String.format("%.5f", finalV),
                    aw, ah, tx, ty, Integer.toHexString(abgr));
            }

            int a = (abgr >> 24) & 0xFF;
            int b = (abgr >> 16) & 0xFF;
            int g = (abgr >> 8) & 0xFF;
            int r = abgr & 0xFF;

            if (quad.getTintIndex() >= 0) {
                int tint = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, quad.getTintIndex());
                r = r * ((tint >> 16) & 0xFF) / 255;
                g = g * ((tint >> 8) & 0xFF) / 255;
                b = b * (tint & 0xFF) / 255;
            }

            return (a << 24) | (r << 16) | (g << 8) | b;
        }

        return 0; // no quad contained the sample point
    }

    /**
     * Fallback sampling for BlockEntityRenderer blocks (decorated pots, chests, signs...)
     * whose block model has no baked quads. Maps the block-local face position onto the
     * block's particle sprite and samples the live GPU atlas, giving a solid fill instead
     * of a transparent face.
     */
    private static int sampleParticleFallback(BakedModel model, ModelData modelData,
                                              BlockState state, BlockAndTintGetter level, BlockPos pos,
                                              Direction face, Projection vol, float depth,
                                              int px, int py, int wPx, int hPx) {
        TextureAtlasSprite sprite = model.getParticleIcon(modelData);
        if (sprite == null) return 0;

        float localU = (px + 0.5f) / wPx * vol.width();
        float localV = (py + 0.5f) / hPx * vol.height();
        Vec3 worldPos = vol.localToWorld(localU, localV, depth);

        float bx = (float)(worldPos.x - pos.getX());
        float by = (float)(worldPos.y - pos.getY());
        float bz = (float)(worldPos.z - pos.getZ());

        // Tangent axes of the sampled face → sprite u,v
        float fu, fv;
        switch (face.getAxis()) {
            case X -> { fu = bz; fv = by; }
            case Z -> { fu = bx; fv = by; }
            default -> { fu = bx; fv = bz; }
        }
        fu = Math.clamp(fu, 0f, 1f);
        fv = Math.clamp(fv, 0f, 1f);

        float su = sprite.getU0() + (sprite.getU1() - sprite.getU0()) * fu;
        float sv = sprite.getV0() + (sprite.getV1() - sprite.getV0()) * fv;

        int abgr = AtlasImageCache.sampleABGR(su, sv);
        int a = (abgr >> 24) & 0xFF;
        int b = (abgr >> 16) & 0xFF;
        int g = (abgr >> 8) & 0xFF;
        int r = abgr & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int toPixel(float val, float range, int pixels) {
        return Math.clamp((int)(val / range * pixels), 0, pixels - 1);
    }

    private static int toPixelMax(float val, float range, int pixels) {
        return Math.clamp((int) Math.ceil(val / range * pixels) - 1, 0, pixels - 1);
    }
}
