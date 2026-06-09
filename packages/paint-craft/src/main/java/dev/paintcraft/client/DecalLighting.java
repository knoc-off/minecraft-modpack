package dev.paintcraft.client;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Computes per-corner ambient occlusion and lightmap values for decal faces,
 * matching vanilla Minecraft's smooth lighting behavior.
 */
public final class DecalLighting {

    private DecalLighting() {}

    /**
     * Compute per-corner AO brightness for a block face.
     * Returns [BL, BR, TR, TL] where BL = min-right + min-up corner.
     *
     * @param right the decal's "right" direction (perpendicular to face, in face plane)
     * @param up    the decal's "up" direction (perpendicular to face, in face plane)
     */
    public static float[] computeCornerAO(BlockAndTintGetter level, BlockPos pos,
                                           Direction face, Direction right, Direction up) {
        BlockPos facePos = pos.relative(face);
        Direction rightOpp = right.getOpposite();
        Direction upOpp = up.getOpposite();

        // Corner 0 (BL): min-right, min-up → d1=rightOpp, d2=upOpp
        float ao0 = cornerAO(level, facePos, rightOpp, upOpp);
        // Corner 1 (BR): max-right, min-up → d1=right, d2=upOpp
        float ao1 = cornerAO(level, facePos, right, upOpp);
        // Corner 2 (TR): max-right, max-up → d1=right, d2=up
        float ao2 = cornerAO(level, facePos, right, up);
        // Corner 3 (TL): min-right, max-up → d1=rightOpp, d2=up
        float ao3 = cornerAO(level, facePos, rightOpp, up);

        return new float[]{ao0, ao1, ao2, ao3};
    }

    /**
     * Compute per-corner packed lightmap values for a block face.
     * Returns [BL, BR, TR, TL] matching the same corner order as computeCornerAO.
     */
    public static int[] computeCornerLight(BlockAndTintGetter level, BlockPos pos,
                                            Direction face, Direction right, Direction up) {
        BlockPos facePos = pos.relative(face);
        Direction rightOpp = right.getOpposite();
        Direction upOpp = up.getOpposite();

        int center = LevelRenderer.getLightColor(level, facePos);

        int light0 = cornerLight(level, facePos, rightOpp, upOpp, center);
        int light1 = cornerLight(level, facePos, right, upOpp, center);
        int light2 = cornerLight(level, facePos, right, up, center);
        int light3 = cornerLight(level, facePos, rightOpp, up, center);

        return new int[]{light0, light1, light2, light3};
    }

    /**
     * Bilinearly interpolate a packed lightmap value from 4 corner values.
     * fracRight/fracUp are 0-1 within the block face.
     */
    public static int interpolateLight(int[] corners, float fracRight, float fracUp) {
        // Interpolate sky and block light channels separately
        // Sky light is in bits 20-23, block light in bits 4-7
        float sky = bilerp(
            (corners[0] >> 20) & 0xF, (corners[1] >> 20) & 0xF,
            (corners[2] >> 20) & 0xF, (corners[3] >> 20) & 0xF,
            fracRight, fracUp
        );
        float block = bilerp(
            (corners[0] >> 4) & 0xF, (corners[1] >> 4) & 0xF,
            (corners[2] >> 4) & 0xF, (corners[3] >> 4) & 0xF,
            fracRight, fracUp
        );
        return ((int) sky) << 20 | ((int) block) << 4;
    }

    /**
     * Bilinearly interpolate AO from 4 corner values.
     */
    public static float interpolateAO(float[] corners, float fracRight, float fracUp) {
        return bilerp(corners[0], corners[1], corners[2], corners[3], fracRight, fracUp);
    }

    private static float cornerAO(BlockAndTintGetter level, BlockPos facePos,
                                   Direction d1, Direction d2) {
        BlockPos p1 = facePos.relative(d1);
        BlockPos p2 = facePos.relative(d2);
        BlockPos diag = p1.relative(d2);

        BlockState s1 = level.getBlockState(p1);
        BlockState s2 = level.getBlockState(p2);

        float center = 1.0f; // facePos is always air (the space in front of the face)
        float b1 = s1.getShadeBrightness(level, p1);
        float b2 = s2.getShadeBrightness(level, p2);

        // If both cardinal neighbors are opaque, the diagonal is occluded
        boolean opaque1 = s1.isSolidRender(level, p1);
        boolean opaque2 = s2.isSolidRender(level, p2);

        float bd;
        if (opaque1 && opaque2) {
            bd = center; // occluded diagonal uses center brightness
        } else {
            bd = level.getBlockState(diag).getShadeBrightness(level, diag);
        }

        return (center + b1 + b2 + bd) * 0.25f;
    }

    private static int cornerLight(BlockAndTintGetter level, BlockPos facePos,
                                    Direction d1, Direction d2, int center) {
        BlockPos p1 = facePos.relative(d1);
        BlockPos p2 = facePos.relative(d2);
        BlockPos diag = p1.relative(d2);

        int l1 = LevelRenderer.getLightColor(level, p1);
        int l2 = LevelRenderer.getLightColor(level, p2);
        int ld = LevelRenderer.getLightColor(level, diag);

        // If both cardinal neighbors are opaque, diagonal is occluded (use center instead)
        boolean opaque1 = level.getBlockState(p1).isSolidRender(level, p1);
        boolean opaque2 = level.getBlockState(p2).isSolidRender(level, p2);

        if (opaque1 && opaque2) {
            return blendLight(center, l1, l2, center);
        }
        return blendLight(center, l1, l2, ld);
    }

    /**
     * Average 4 packed lightmap values, handling sky and block channels separately.
     * Zero values (from opaque block interiors) are replaced with center before averaging.
     */
    private static int blendLight(int center, int l1, int l2, int ld) {
        if (l1 == 0) l1 = center;
        if (l2 == 0) l2 = center;
        if (ld == 0) ld = center;
        return (center + l1 + l2 + ld) >> 2 & 0xFF00FF;
    }

    private static float bilerp(float bl, float br, float tr, float tl,
                                 float fracRight, float fracUp) {
        float bottom = bl + (br - bl) * fracRight;
        float top = tl + (tr - tl) * fracRight;
        return bottom + (top - bottom) * fracUp;
    }
}
