package dev.paintcraft.projection;

import dev.paintcraft.core.Decal;
import dev.paintcraft.core.FaceFrame;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public final class ProjectionResolver {

    private ProjectionResolver() {}

    public static ResolvedSurface resolve(Decal decal, BlockGetter level) {
        Projection vol = Projection.fromDecal(decal);
        AABB bounds = vol.toBoundingBox();
        int wPx = decal.widthPx();
        int hPx = decal.heightPx();

        // per-pixel depth buffer: closest surface depth at each pixel. Float.MAX_VALUE = no hit.
        float[] depthBuf = new float[wPx * hPx];
        Arrays.fill(depthBuf, Float.MAX_VALUE);

        // collect raw candidate faces from all blocks in the volume
        List<FaceCandidate> candidates = new ArrayList<>();

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
                collectFaces(vol, worldBox, pos.immutable(), decal.normal(), candidates);
            }
        });

        // rasterize candidates into depth buffer, keep the closest hit per pixel
        // each candidate owns a rectangular pixel region
        for (FaceCandidate c : candidates) {
            int px0 = vol.toPixelX(c.u0, wPx);
            int py0 = vol.toPixelY(c.v0, hPx);
            int px1 = vol.toPixelXMax(c.u1, wPx);
            int py1 = vol.toPixelYMax(c.v1, hPx);

            for (int py = py0; py <= py1; py++) {
                for (int px = px0; px <= px1; px++) {
                    int idx = py * wPx + px;
                    if (idx >= 0 && idx < depthBuf.length && c.depth < depthBuf[idx]) {
                        depthBuf[idx] = c.depth;
                    }
                }
            }
        }

        // find minimum depth for shadow normalization
        float minDepth = Float.MAX_VALUE;
        for (float d : depthBuf) {
            if (d < minDepth) minDepth = d;
        }
        if (minDepth == Float.MAX_VALUE) minDepth = 0;

        // build final fragments from candidates + depth buffer
        List<SurfaceFragment> fragments = buildFragments(candidates, depthBuf, vol, wPx, hPx);

        // background pixels and full depth map left for client-side capture pass
        return new ResolvedSurface(fragments, null, depthBuf, minDepth, candidates);
    }

    /**
     * Incrementally re-resolve a decal after specific blocks changed.
     * Only queries block state for the changed positions, reuses candidates
     * from unchanged blocks. Falls back to full resolve if previous data
     * is unavailable or the change is too large.
     *
     * Cost: O(changedBlocks + candidates * affectedPixels) vs
     *       O(allBlocksInAABB + candidates * allPixels) for full resolve.
     */
    public static ResolvedSurface resolveIncremental(
            Decal decal, BlockGetter level,
            ResolvedSurface previous,
            Set<BlockPos> changedBlocks) {

        if (previous == null || previous.candidates().isEmpty()) {
            return resolve(decal, level);
        }

        Projection vol = Projection.fromDecal(decal);
        int wPx = decal.widthPx();
        int hPx = decal.heightPx();

        // Determine which pixel region is affected by the old candidates from changed blocks
        int dirtyPxMinX = wPx, dirtyPyMinY = hPx, dirtyPxMaxX = -1, dirtyPyMaxY = -1;

        // Remove candidates from changed blocks, track their pixel footprint
        List<FaceCandidate> survivors = new ArrayList<>();
        for (FaceCandidate c : previous.candidates()) {
            if (changedBlocks.contains(c.blockPos())) {
                int px0 = vol.toPixelX(c.u0, wPx);
                int py0 = vol.toPixelY(c.v0, hPx);
                int px1 = vol.toPixelXMax(c.u1, wPx);
                int py1 = vol.toPixelYMax(c.v1, hPx);
                dirtyPxMinX = Math.min(dirtyPxMinX, px0);
                dirtyPyMinY = Math.min(dirtyPyMinY, py0);
                dirtyPxMaxX = Math.max(dirtyPxMaxX, px1);
                dirtyPyMaxY = Math.max(dirtyPyMaxY, py1);
            } else {
                survivors.add(c);
            }
        }

        // Query ONLY the changed blocks for new candidates
        List<FaceCandidate> newCandidates = new ArrayList<>();
        for (BlockPos pos : changedBlocks) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            VoxelShape shape = state.getShape(level, pos, CollisionContext.empty());
            if (shape.isEmpty()) continue;
            Vec3 blockOrigin = Vec3.atLowerCornerOf(pos);
            for (AABB box : shape.toAabbs()) {
                AABB worldBox = box.move(blockOrigin);
                collectFaces(vol, worldBox, pos.immutable(), decal.normal(), newCandidates);
            }
        }

        // Expand dirty region to include new candidates' footprints
        for (FaceCandidate c : newCandidates) {
            int px0 = vol.toPixelX(c.u0, wPx);
            int py0 = vol.toPixelY(c.v0, hPx);
            int px1 = vol.toPixelXMax(c.u1, wPx);
            int py1 = vol.toPixelYMax(c.v1, hPx);
            dirtyPxMinX = Math.min(dirtyPxMinX, px0);
            dirtyPyMinY = Math.min(dirtyPyMinY, py0);
            dirtyPxMaxX = Math.max(dirtyPxMaxX, px1);
            dirtyPyMaxY = Math.max(dirtyPyMaxY, py1);
        }

        // Clamp dirty region
        dirtyPxMinX = Math.max(0, dirtyPxMinX);
        dirtyPyMinY = Math.max(0, dirtyPyMinY);
        dirtyPxMaxX = Math.min(wPx - 1, dirtyPxMaxX);
        dirtyPyMaxY = Math.min(hPx - 1, dirtyPyMaxY);

        if (dirtyPxMaxX < dirtyPxMinX || dirtyPyMaxY < dirtyPyMinY) {
            // No pixel region affected: changed blocks were outside the projection
            return previous;
        }

        // Merge candidate lists
        List<FaceCandidate> allCandidates = new ArrayList<>(survivors.size() + newCandidates.size());
        allCandidates.addAll(survivors);
        allCandidates.addAll(newCandidates);

        // Clone depth buffer, reset only the dirty region
        float[] depthBuf = Arrays.copyOf(previous.depthMap(), previous.depthMap().length);
        for (int py = dirtyPyMinY; py <= dirtyPyMaxY; py++) {
            for (int px = dirtyPxMinX; px <= dirtyPxMaxX; px++) {
                depthBuf[py * wPx + px] = Float.MAX_VALUE;
            }
        }

        // Re-rasterize ALL candidates into ONLY the dirty region
        // (survivors might have pixels in the dirty region that were previously
        // occluded by removed candidates, so they need to compete for depth again)
        for (FaceCandidate c : allCandidates) {
            int px0 = Math.max(vol.toPixelX(c.u0, wPx), dirtyPxMinX);
            int py0 = Math.max(vol.toPixelY(c.v0, hPx), dirtyPyMinY);
            int px1 = Math.min(vol.toPixelXMax(c.u1, wPx), dirtyPxMaxX);
            int py1 = Math.min(vol.toPixelYMax(c.v1, hPx), dirtyPyMaxY);

            for (int py = py0; py <= py1; py++) {
                for (int px = px0; px <= px1; px++) {
                    int idx = py * wPx + px;
                    if (c.depth < depthBuf[idx]) {
                        depthBuf[idx] = c.depth;
                    }
                }
            }
        }

        // Rebuild minDepth
        float minDepth = Float.MAX_VALUE;
        for (float d : depthBuf) {
            if (d < minDepth) minDepth = d;
        }
        if (minDepth == Float.MAX_VALUE) minDepth = 0;

        // Rebuild fragments from the full candidate list + updated depth buffer
        List<SurfaceFragment> fragments = buildFragments(allCandidates, depthBuf, vol, wPx, hPx);

        return new ResolvedSurface(fragments, null, depthBuf, minDepth, allCandidates);
    }

    private static List<SurfaceFragment> buildFragments(
            List<FaceCandidate> candidates, float[] depthBuf,
            Projection vol, int wPx, int hPx) {
        List<SurfaceFragment> fragments = new ArrayList<>();
        for (FaceCandidate c : candidates) {
            int px0 = vol.toPixelX(c.u0, wPx);
            int py0 = vol.toPixelY(c.v0, hPx);
            int px1 = vol.toPixelXMax(c.u1, wPx);
            int py1 = vol.toPixelYMax(c.v1, hPx);

            int regionW = px1 - px0 + 1;
            int regionH = py1 - py0 + 1;
            if (regionW <= 0 || regionH <= 0) continue;

            boolean[] owned = new boolean[regionW * regionH];
            boolean ownsAny = false;
            for (int ry = 0; ry < regionH; ry++) {
                for (int rx = 0; rx < regionW; rx++) {
                    int idx = (py0 + ry) * wPx + (px0 + rx);
                    if (idx >= 0 && idx < depthBuf.length
                        && Math.abs(depthBuf[idx] - c.depth) < 0.001f) {
                        owned[ry * regionW + rx] = true;
                        ownsAny = true;
                    }
                }
            }
            if (!ownsAny) continue;

            List<int[]> rects = mergeOwnedRects(owned, regionW, regionH);

            for (int[] rect : rects) {
                int subPx0 = px0 + rect[0];
                int subPy0 = py0 + rect[1];
                int subPx1 = px0 + rect[2];
                int subPy1 = py0 + rect[3];

                float[] verts = buildSubQuadVertices(vol, c.depth, subPx0, subPy0, subPx1, subPy1, wPx, hPx);
                float[] uvs = new float[8];
                for (int i = 0; i < 4; i++) {
                    Vec3 wp = new Vec3(verts[i * 3], verts[i * 3 + 1], verts[i * 3 + 2]);
                    Vec3 local = vol.worldToLocal(wp);
                    uvs[i * 2] = (float) (local.x / vol.width());
                    uvs[i * 2 + 1] = 1.0f - (float) (local.y / vol.height());
                }

                fragments.add(new SurfaceFragment(
                    c.blockPos, c.faceNormal, verts, uvs,
                    subPx0, subPy0, subPx1, subPy1,
                    c.depth, 0
                ));
            }
        }
        return fragments;
    }

    private static void collectFaces(Projection vol, AABB worldBox,
                                     BlockPos blockPos, Direction projNormal,
                                     List<FaceCandidate> out) {
        // for each face of this AABB, check if it faces the projector
        for (Direction face : Direction.values()) {
            // face must oppose the projection direction:
            // projNormal points INTO the surface, face normal points outward.
            // we want faces whose outward normal opposes the projection forward.
            // projection forward = -projNormal, so face.normal dot (-projNormal) < 0
            // simplifies to: face == projNormal (the face facing the projector)
            // WAIT: we want faces that point TOWARD the projector.
            // projector looks along `forward` = -normal.
            // a face points toward the projector if faceNormal dot forward < 0,
            // i.e., faceNormal dot (-normal) < 0, i.e., faceNormal dot normal > 0.
            // so: face == projNormal.
            // but also: we want to catch faces perpendicular to depth axis (the front-facing ones).
            // Actually let's think simply:
            // the projection normal points FROM the projector INTO the wall.
            // A face is visible to the projector if the face's outward normal
            // points back toward the projector, i.e., it has a component opposing the proj forward.
            // proj forward = scale(-1, projNormal). Face visible if face.normal . projForward < 0
            // => face.normal . (-projNormal) < 0 => face.normal . projNormal > 0.
            Vec3 faceNorm = Vec3.atLowerCornerOf(face.getNormal());
            Vec3 projNorm = Vec3.atLowerCornerOf(projNormal.getNormal());
            if (faceNorm.dot(projNorm) <= 0) continue;

            // compute the face rect in world space
            double fMinX, fMinY, fMinZ, fMaxX, fMaxY, fMaxZ;
            fMinX = worldBox.minX; fMinY = worldBox.minY; fMinZ = worldBox.minZ;
            fMaxX = worldBox.maxX; fMaxY = worldBox.maxY; fMaxZ = worldBox.maxZ;

            switch (face) {
                case UP    -> { fMinY = fMaxY; }
                case DOWN  -> { fMaxY = fMinY; }
                case NORTH -> { fMaxZ = fMinZ; }
                case SOUTH -> { fMinZ = fMaxZ; }
                case EAST  -> { fMinX = fMaxX; }
                case WEST  -> { fMaxX = fMinX; }
            }

            // project the face corners into decal-local coords
            Vec3 corner0 = new Vec3(fMinX, fMinY, fMinZ);
            Vec3 corner1 = new Vec3(fMaxX, fMaxY, fMaxZ);
            Vec3 local0 = vol.worldToLocal(corner0);
            Vec3 local1 = vol.worldToLocal(corner1);

            double u0 = Math.min(local0.x, local1.x);
            double v0 = Math.min(local0.y, local1.y);
            double u1 = Math.max(local0.x, local1.x);
            double v1 = Math.max(local0.y, local1.y);
            double w  = (local0.z + local1.z) * 0.5; // depth of this face

            // clip to projection bounds
            u0 = Math.max(u0, 0);
            v0 = Math.max(v0, 0);
            u1 = Math.min(u1, vol.width());
            v1 = Math.min(v1, vol.height());
            if (w < 0 || w > vol.depth()) continue;
            if (u0 >= u1 || v0 >= v1) continue;

            out.add(new FaceCandidate(
                blockPos, face,
                (float) u0, (float) v0, (float) u1, (float) v1,
                (float) w,
                fMinX, fMinY, fMinZ, fMaxX, fMaxY, fMaxZ
            ));
        }
    }

    private static float[] buildQuadVertices(Projection vol, FaceCandidate c) {
        // reconstruct world-space quad from the (potentially clipped) face
        // the face is flat on one axis (determined by faceNormal)
        float x0 = (float) c.worldMinX, y0 = (float) c.worldMinY, z0 = (float) c.worldMinZ;
        float x1 = (float) c.worldMaxX, y1 = (float) c.worldMaxY, z1 = (float) c.worldMaxZ;

        return switch (c.faceNormal) {
            case UP, DOWN -> SurfaceFragment.makeQuad(
                x0, y0, z0,  x1, y0, z0,  x1, y0, z1,  x0, y0, z1
            );
            case NORTH, SOUTH -> SurfaceFragment.makeQuad(
                x0, y0, z0,  x1, y0, z0,  x1, y1, z0,  x0, y1, z0
            );
            case EAST, WEST -> SurfaceFragment.makeQuad(
                x0, y0, z0,  x0, y1, z0,  x0, y1, z1,  x0, y0, z1
            );
        };
    }

    record FaceCandidate(
        BlockPos blockPos,
        Direction faceNormal,
        float u0, float v0, float u1, float v1,
        float depth,
        double worldMinX, double worldMinY, double worldMinZ,
        double worldMaxX, double worldMaxY, double worldMaxZ
    ) {}

    /**
     * Build world-space quad vertices for a sub-region of pixels within the projection volume.
     * Uses localToWorld to convert pixel edges back to world positions at the given depth.
     */
    private static float[] buildSubQuadVertices(Projection vol, float depth,
                                                 int subPx0, int subPy0, int subPx1, int subPy1,
                                                 int wPx, int hPx) {
        double u0 = (double) subPx0 / wPx * vol.width();
        double u1 = (double) (subPx1 + 1) / wPx * vol.width();
        double v0 = (double) subPy0 / hPx * vol.height();
        double v1 = (double) (subPy1 + 1) / hPx * vol.height();

        Vec3 c0 = vol.localToWorld(u0, v0, depth);
        Vec3 c1 = vol.localToWorld(u1, v0, depth);
        Vec3 c2 = vol.localToWorld(u1, v1, depth);
        Vec3 c3 = vol.localToWorld(u0, v1, depth);

        return SurfaceFragment.makeQuad(
            (float) c0.x, (float) c0.y, (float) c0.z,
            (float) c1.x, (float) c1.y, (float) c1.z,
            (float) c2.x, (float) c2.y, (float) c2.z,
            (float) c3.x, (float) c3.y, (float) c3.z
        );
    }

    /**
     * Merge a boolean ownership grid into maximal rectangles using greedy row-merge.
     * Returns list of int[4] = {x0, y0, x1, y1} (inclusive pixel coords within the region).
     */
    private static List<int[]> mergeOwnedRects(boolean[] owned, int width, int height) {
        List<int[]> rects = new ArrayList<>();
        boolean[] used = new boolean[width * height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!owned[y * width + x] || used[y * width + x]) continue;

                // Find the widest horizontal run starting at (x, y)
                int x1 = x;
                while (x1 + 1 < width && owned[y * width + x1 + 1] && !used[y * width + x1 + 1]) {
                    x1++;
                }

                // Extend downward: find how many consecutive rows have the same run [x..x1]
                int y1 = y;
                outer:
                while (y1 + 1 < height) {
                    for (int cx = x; cx <= x1; cx++) {
                        if (!owned[(y1 + 1) * width + cx] || used[(y1 + 1) * width + cx]) {
                            break outer;
                        }
                    }
                    y1++;
                }

                // Mark all pixels in this rect as used
                for (int ry = y; ry <= y1; ry++) {
                    for (int rx = x; rx <= x1; rx++) {
                        used[ry * width + rx] = true;
                    }
                }

                rects.add(new int[]{x, y, x1, y1});
            }
        }

        return rects;
    }
}
