package dev.paintcraft.projection;

import dev.paintcraft.core.Decal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public record ProjectionVolume(
    Vec3 origin,
    Vec3 right,
    Vec3 up,
    Vec3 forward,
    float width,
    float height,
    float depth
) {
    public static ProjectionVolume from(Decal decal) {
        BlockPos anchor = decal.anchor();
        Direction normal = decal.normal();
        Direction decalUp = decal.up();
        Direction decalRight = decal.right();

        float w = decal.widthBlocks();
        float h = decal.heightBlocks();
        float d = decal.depth();

        Vec3 right = Vec3.atLowerCornerOf(decalRight.getNormal());
        Vec3 up = Vec3.atLowerCornerOf(decalUp.getNormal());
        Vec3 forward = Vec3.atLowerCornerOf(normal.getNormal()).scale(-1);

        Vec3 orig = Vec3.atLowerCornerOf(anchor);
        // offset origin to the face of the anchor block in the normal direction
        if (normal.getAxisDirection() == Direction.AxisDirection.POSITIVE) {
            orig = orig.add(Vec3.atLowerCornerOf(normal.getNormal()));
        }

        // Adjust origin so u=0,v=0 starts at the correct corner of the block face.
        // For directions with negative axis steps, shift origin +1 on that axis
        // so the projection volume covers the block face properly.
        if (decalRight.getStepX() < 0) orig = orig.add(1, 0, 0);
        if (decalRight.getStepY() < 0) orig = orig.add(0, 1, 0);
        if (decalRight.getStepZ() < 0) orig = orig.add(0, 0, 1);
        if (decalUp.getStepX() < 0) orig = orig.add(1, 0, 0);
        if (decalUp.getStepY() < 0) orig = orig.add(0, 1, 0);
        if (decalUp.getStepZ() < 0) orig = orig.add(0, 0, 1);

        return new ProjectionVolume(orig, right, up, forward, w, h, d);
    }

    public AABB toBoundingBox() {
        Vec3 c0 = origin;
        Vec3 c1 = origin.add(right.scale(width));
        Vec3 c2 = origin.add(up.scale(height));
        Vec3 c3 = origin.add(forward.scale(depth));
        Vec3 c4 = c1.add(up.scale(height)).add(forward.scale(depth));
        double minX = Math.min(Math.min(Math.min(c0.x, c1.x), Math.min(c2.x, c3.x)), c4.x);
        double minY = Math.min(Math.min(Math.min(c0.y, c1.y), Math.min(c2.y, c3.y)), c4.y);
        double minZ = Math.min(Math.min(Math.min(c0.z, c1.z), Math.min(c2.z, c3.z)), c4.z);
        double maxX = Math.max(Math.max(Math.max(c0.x, c1.x), Math.max(c2.x, c3.x)), c4.x);
        double maxY = Math.max(Math.max(Math.max(c0.y, c1.y), Math.max(c2.y, c3.y)), c4.y);
        double maxZ = Math.max(Math.max(Math.max(c0.z, c1.z), Math.max(c2.z, c3.z)), c4.z);
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Project a world-space point into decal-local 2D coordinates (u=right, v=up, w=depth). */
    public Vec3 worldToLocal(Vec3 worldPos) {
        Vec3 rel = worldPos.subtract(origin);
        double u = rel.dot(right);
        double v = rel.dot(up);
        double w = rel.dot(forward);
        return new Vec3(u, v, w);
    }

    /** Convert local (u,v) to pixel coordinates in the decal texture (for min bounds). */
    public int toPixelX(double u, int widthPx) {
        return Math.clamp((int) (u / width * widthPx), 0, widthPx - 1);
    }

    public int toPixelY(double v, int heightPx) {
        return Math.clamp((int) (v / height * heightPx), 0, heightPx - 1);
    }

    /** Last pixel column actually covered by a face ending at local u (for max bounds). */
    public int toPixelXMax(double u, int widthPx) {
        return Math.clamp((int) Math.ceil(u / width * widthPx) - 1, 0, widthPx - 1);
    }

    /** Last pixel row actually covered by a face ending at local v (for max bounds). */
    public int toPixelYMax(double v, int heightPx) {
        return Math.clamp((int) Math.ceil(v / height * heightPx) - 1, 0, heightPx - 1);
    }

    public boolean containsLocal(double u, double v, double w) {
        return u >= 0 && u <= width && v >= 0 && v <= height && w >= 0 && w <= depth;
    }

    /** Convert local (u,v,w) coordinates back to world position. */
    public Vec3 localToWorld(double u, double v, double w) {
        return origin.add(right.scale(u)).add(up.scale(v)).add(forward.scale(w));
    }
}
