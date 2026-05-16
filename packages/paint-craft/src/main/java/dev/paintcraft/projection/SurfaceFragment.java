package dev.paintcraft.projection;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public record SurfaceFragment(
    BlockPos pos,
    Direction faceNormal,
    // quad corners in world space (4 vertices, 3 floats each)
    float[] vertices,
    // per-vertex texture UVs (4 vertices, 2 floats each: u0,v0, u1,v1, u2,v2, u3,v3)
    float[] uvs,
    // UV rect in decal pixel space (u0, v0, u1, v1 as pixel indices)
    float u0, float v0, float u1, float v1,
    // depth from projection plane, for occlusion and z-ordering
    float depth,
    // z-tier among overlapping decals at this position, assigned during resolve
    int zTier
) {
    public SurfaceFragment withZTier(int tier) {
        return new SurfaceFragment(pos, faceNormal, vertices, uvs, u0, v0, u1, v1, depth, tier);
    }

    public static float[] makeQuad(
        float x0, float y0, float z0,
        float x1, float y1, float z1,
        float x2, float y2, float z2,
        float x3, float y3, float z3
    ) {
        return new float[] { x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3 };
    }
}
