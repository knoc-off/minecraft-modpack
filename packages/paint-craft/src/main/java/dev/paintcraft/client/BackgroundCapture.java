package dev.paintcraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.paintcraft.core.Decal;
import dev.paintcraft.projection.ProjectionVolume;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Captures the block textures visible in a decal's projection volume,
 * using the same depth-buffer projection as the renderer.
 * Provides depth shading so recessed surfaces appear darker.
 */
public final class BackgroundCapture {

    private static final float SHADE_FACTOR = 0.5f; // 50% darker at max depth

    private BackgroundCapture() {}

    public static int[] capture(BlockAndTintGetter level, BlockPos anchor, Direction face,
                                 int widthBlocks, int heightBlocks, float depth) {
        int wPx = widthBlocks * Decal.PX_PER_BLOCK;
        int hPx = heightBlocks * Decal.PX_PER_BLOCK;
        int[] background = new int[wPx * hPx];

        // Build a ProjectionVolume matching what the renderer uses
        Direction up = face.getAxis().isVertical() ? Direction.NORTH : Direction.UP;
        Direction right = up.getClockWise(face.getAxis());

        Vec3 rightVec = Vec3.atLowerCornerOf(right.getNormal());
        Vec3 upVec = Vec3.atLowerCornerOf(up.getNormal());
        Vec3 forwardVec = Vec3.atLowerCornerOf(face.getNormal()).scale(-1);

        Vec3 orig = Vec3.atLowerCornerOf(anchor);
        if (face.getAxisDirection() == Direction.AxisDirection.POSITIVE) {
            orig = orig.add(Vec3.atLowerCornerOf(face.getNormal()));
        }
        if (right.getStepX() < 0) orig = orig.add(1, 0, 0);
        if (right.getStepY() < 0) orig = orig.add(0, 1, 0);
        if (right.getStepZ() < 0) orig = orig.add(0, 0, 1);
        if (up.getStepX() < 0) orig = orig.add(1, 0, 0);
        if (up.getStepY() < 0) orig = orig.add(0, 1, 0);
        if (up.getStepZ() < 0) orig = orig.add(0, 0, 1);

        ProjectionVolume vol = new ProjectionVolume(orig, rightVec, upVec, forwardVec,
            widthBlocks, heightBlocks, depth);

        // Collect face candidates (same as ProjectionResolver)
        AABB bounds = vol.toBoundingBox();
        List<FaceHit> candidates = new ArrayList<>();

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
                collectFaces(vol, worldBox, pos.immutable(), face, candidates);
            }
        });

        // Rasterize depth buffer + record winning face per pixel
        float[] depthBuf = new float[wPx * hPx];
        int[] winnerIdx = new int[wPx * hPx]; // index into candidates
        Arrays.fill(depthBuf, Float.MAX_VALUE);
        Arrays.fill(winnerIdx, -1);

        for (int ci = 0; ci < candidates.size(); ci++) {
            FaceHit c = candidates.get(ci);
            int px0 = toPixel(c.u0, vol.width(), wPx);
            int py0 = toPixel(c.v0, vol.height(), hPx);
            int px1 = toPixelMax(c.u1, vol.width(), wPx);
            int py1 = toPixelMax(c.v1, vol.height(), hPx);

            for (int py = py0; py <= py1; py++) {
                for (int px = px0; px <= px1; px++) {
                    int idx = py * wPx + px;
                    if (idx >= 0 && idx < depthBuf.length && c.depth < depthBuf[idx]) {
                        depthBuf[idx] = c.depth;
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

                FaceHit hit = candidates.get(winnerIdx[idx]);

                // Compute where this pixel falls within the face (0-1)
                float faceU = computeFaceU(hit, px, wPx, vol);
                float faceV = computeFaceV(hit, py, hPx, vol);

                // Sample the block's texture
                int color = sampleBlockTexture(level, hit.blockPos, hit.faceNormal, faceU, faceV);
                if (color == 0) continue;

                // Apply depth shading
                float brightness = 1.0f - (hit.depth / maxDepth) * SHADE_FACTOR;
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

    private static float computeFaceU(FaceHit hit, int px, int wPx, ProjectionVolume vol) {
        // pixel center in local projection coords
        float localU = (px + 0.5f) / wPx * vol.width();
        // position within the face's local U extent
        float faceWidth = hit.u1 - hit.u0;
        if (faceWidth < 0.001f) return 0.5f;
        return (localU - hit.u0) / faceWidth;
    }

    private static float computeFaceV(FaceHit hit, int py, int hPx, ProjectionVolume vol) {
        float localV = (py + 0.5f) / hPx * vol.height();
        float faceHeight = hit.v1 - hit.v0;
        if (faceHeight < 0.001f) return 0.5f;
        return (localV - hit.v0) / faceHeight;
    }

    private static int sampleBlockTexture(BlockAndTintGetter level, BlockPos pos,
                                           Direction face, float u, float v) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return 0;

        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        List<BakedQuad> quads = model.getQuads(state, face, RandomSource.create());
        if (quads.isEmpty()) return 0;

        BakedQuad quad = quads.get(0);
        TextureAtlasSprite sprite = quad.getSprite();
        NativeImage image = sprite.contents().getOriginalImage();
        int spriteW = sprite.contents().width();
        int spriteH = sprite.contents().height();

        // Clamp and sample
        int sx = Math.clamp((int)(u * spriteW), 0, spriteW - 1);
        int sy = Math.clamp((int)(v * spriteH), 0, spriteH - 1);
        int abgr = image.getPixelRGBA(sx, sy);

        // ABGR → ARGB
        int a = (abgr >> 24) & 0xFF;
        int b = (abgr >> 16) & 0xFF;
        int g = (abgr >> 8) & 0xFF;
        int r = abgr & 0xFF;

        // Apply tint
        if (quad.getTintIndex() >= 0) {
            int tint = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, quad.getTintIndex());
            r = r * ((tint >> 16) & 0xFF) / 255;
            g = g * ((tint >> 8) & 0xFF) / 255;
            b = b * (tint & 0xFF) / 255;
        }

        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static void collectFaces(ProjectionVolume vol, AABB worldBox,
                                      BlockPos blockPos, Direction projNormal,
                                      List<FaceHit> out) {
        for (Direction face : Direction.values()) {
            Vec3 faceNorm = Vec3.atLowerCornerOf(face.getNormal());
            Vec3 projNorm = Vec3.atLowerCornerOf(projNormal.getNormal());
            if (faceNorm.dot(projNorm) <= 0) continue;

            double fMinX = worldBox.minX, fMinY = worldBox.minY, fMinZ = worldBox.minZ;
            double fMaxX = worldBox.maxX, fMaxY = worldBox.maxY, fMaxZ = worldBox.maxZ;

            switch (face) {
                case UP    -> fMinY = fMaxY;
                case DOWN  -> fMaxY = fMinY;
                case NORTH -> fMaxZ = fMinZ;
                case SOUTH -> fMinZ = fMaxZ;
                case EAST  -> fMinX = fMaxX;
                case WEST  -> fMaxX = fMinX;
            }

            Vec3 corner0 = new Vec3(fMinX, fMinY, fMinZ);
            Vec3 corner1 = new Vec3(fMaxX, fMaxY, fMaxZ);
            Vec3 local0 = vol.worldToLocal(corner0);
            Vec3 local1 = vol.worldToLocal(corner1);

            double u0 = Math.min(local0.x, local1.x);
            double v0 = Math.min(local0.y, local1.y);
            double u1 = Math.max(local0.x, local1.x);
            double v1 = Math.max(local0.y, local1.y);
            double w = (local0.z + local1.z) * 0.5;

            u0 = Math.max(u0, 0);
            v0 = Math.max(v0, 0);
            u1 = Math.min(u1, vol.width());
            v1 = Math.min(v1, vol.height());
            if (w < 0 || w > vol.depth()) continue;
            if (u0 >= u1 || v0 >= v1) continue;

            out.add(new FaceHit(blockPos, face,
                (float) u0, (float) v0, (float) u1, (float) v1, (float) w));
        }
    }

    private static int toPixel(float val, float range, int pixels) {
        return Math.clamp((int)(val / range * pixels), 0, pixels - 1);
    }

    private static int toPixelMax(float val, float range, int pixels) {
        return Math.clamp((int) Math.ceil(val / range * pixels) - 1, 0, pixels - 1);
    }

    private record FaceHit(BlockPos blockPos, Direction faceNormal,
                           float u0, float v0, float u1, float v1, float depth) {}
}
