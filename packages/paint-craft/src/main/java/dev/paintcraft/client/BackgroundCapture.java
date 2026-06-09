package dev.paintcraft.client;

import com.mojang.blaze3d.platform.NativeImage;
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

    private static final boolean HAS_CNB = net.neoforged.fml.ModList.get().isLoaded("chiselsandbits");

    private BackgroundCapture() {}

    public static int[] capture(BlockAndTintGetter level, BlockPos anchor, Direction face,
                                 Direction captureUp, int widthBlocks, int heightBlocks, float depth) {
        int wPx = widthBlocks * Decal.PX_PER_BLOCK;
        int hPx = heightBlocks * Decal.PX_PER_BLOCK;
        int[] background = new int[wPx * hPx];

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

        // Sample textures for each pixel
        for (int py = 0; py < hPx; py++) {
            for (int px = 0; px < wPx; px++) {
                int idx = py * wPx + px;
                if (winnerIdx[idx] < 0) continue;

                var hit = candidates.get(winnerIdx[idx]);

                // Sample the block's texture using world-position-based UV
                int color = sampleBlockTexture(level, hit.blockPos(), hit.faceNormal(),
                    vol, hit.depth(), px, py, wPx, hPx);
                if (color == 0) continue;

                // Apply depth shading
                float brightness = 1.0f - (hit.depth() / maxDepth) * SHADE_FACTOR;
                int a = (color >> 24) & 0xFF;
                int r = (int)(((color >> 16) & 0xFF) * brightness);
                int g = (int)(((color >> 8) & 0xFF) * brightness);
                int b = (int)((color & 0xFF) * brightness);

                // V-flip: editor row 0 = top of face in world (matches renderer)
                int outY = hPx - 1 - py;
                background[outY * wPx + px] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }

        return background;
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
        List<BakedQuad> quads = model.getQuads(state, face, RANDOM, modelData, null);

        // Always include null-face (generic) quads matching our direction.
        // Non-full blocks (stairs, glass panes, fences) store interior/recessed
        // faces in the null bucket since they shouldn't be face-culled.
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

        if (quads.isEmpty()) return 0;

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
            float[] cornerU = new float[4], cornerV = new float[4];

            TextureAtlasSprite sprite = quad.getSprite();
            float spriteU0 = sprite.getU0();
            float spriteV0 = sprite.getV0();
            float spriteURange = sprite.getU1() - spriteU0;
            float spriteVRange = sprite.getV1() - spriteV0;

            for (int i = 0; i < 4; i++) {
                vx[i] = Float.intBitsToFloat(vertices[i * 8]);
                vy[i] = Float.intBitsToFloat(vertices[i * 8 + 1]);
                vz[i] = Float.intBitsToFloat(vertices[i * 8 + 2]);
                float atlasU = Float.intBitsToFloat(vertices[i * 8 + 4]);
                float atlasV = Float.intBitsToFloat(vertices[i * 8 + 5]);
                cornerU[i] = spriteURange > 0.0001f ? (atlasU - spriteU0) / spriteURange : 0f;
                cornerV[i] = spriteVRange > 0.0001f ? (atlasV - spriteV0) / spriteVRange : 0f;
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

            // Bilinearly interpolate sprite UV
            float interpU0 = cornerU[0] + (cornerU[1] - cornerU[0]) * s;
            float interpV0 = cornerV[0] + (cornerV[1] - cornerV[0]) * s;
            float interpU1 = cornerU[3] + (cornerU[2] - cornerU[3]) * s;
            float interpV1 = cornerV[3] + (cornerV[2] - cornerV[3]) * s;

            float finalU = interpU0 + (interpU1 - interpU0) * t;
            float finalV = interpV0 + (interpV1 - interpV0) * t;

            // Sample the sprite
            NativeImage image = sprite.contents().getOriginalImage();
            int spriteW = sprite.contents().width();
            int spriteH = sprite.contents().height();
            int sx = Math.clamp((int)(finalU * spriteW), 0, spriteW - 1);
            int sy = Math.clamp((int)(finalV * spriteH), 0, spriteH - 1);
            int abgr = image.getPixelRGBA(sx, sy);

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

    private static int toPixel(float val, float range, int pixels) {
        return Math.clamp((int)(val / range * pixels), 0, pixels - 1);
    }

    private static int toPixelMax(float val, float range, int pixels) {
        return Math.clamp((int) Math.ceil(val / range * pixels) - 1, 0, pixels - 1);
    }
}
