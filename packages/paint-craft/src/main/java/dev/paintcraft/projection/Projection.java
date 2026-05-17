package dev.paintcraft.projection;

import dev.paintcraft.core.Decal;
import dev.paintcraft.core.FaceFrame;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A projection volume for mapping between world-space and decal-local coordinates.
 * Constructed from a validated FaceFrame — impossible to have an inconsistent origin.
 * Replaces the old ProjectionVolume with centralized, validated origin computation.
 */
public final class Projection {

    private final FaceFrame frame;
    private final Vec3 origin;
    private final Vec3 right, up, forward;
    private final float width, height, depth;

    public Projection(FaceFrame frame, BlockPos anchor, int widthBlocks, int heightBlocks, float depth) {
        this.frame = frame;
        this.origin = frame.projectionOrigin(anchor);
        this.right = frame.rightVec();
        this.up = frame.upVec();
        this.forward = frame.forwardVec();
        this.width = widthBlocks;
        this.height = heightBlocks;
        this.depth = Math.min(depth, Decal.MAX_DEPTH);
    }

    public static Projection fromDecal(Decal decal) {
        return new Projection(
            decal.frame(), decal.anchor(),
            decal.widthBlocks(), decal.heightBlocks(), decal.depth()
        );
    }

    // === Coordinate conversion ===

    /** Project a world-space point into decal-local (u=right, v=up, w=depth). */
    public Vec3 worldToLocal(Vec3 worldPos) {
        Vec3 rel = worldPos.subtract(origin);
        double u = rel.dot(right);
        double v = rel.dot(up);
        double w = rel.dot(forward);
        return new Vec3(u, v, w);
    }

    /** Convert local (u, v, w) back to world position. */
    public Vec3 localToWorld(double u, double v, double w) {
        return origin.add(right.scale(u)).add(up.scale(v)).add(forward.scale(w));
    }

    // === Pixel conversion ===

    /** Local u → pixel column (min bound, clamped). */
    public int toPixelX(double u, int widthPx) {
        return Math.clamp((int) (u / width * widthPx), 0, widthPx - 1);
    }

    /** Local v → pixel row (min bound, clamped). */
    public int toPixelY(double v, int heightPx) {
        return Math.clamp((int) (v / height * heightPx), 0, heightPx - 1);
    }

    /** Last pixel column covered by a face ending at local u (max bound, inclusive). */
    public int toPixelXMax(double u, int widthPx) {
        return Math.clamp((int) Math.ceil(u / width * widthPx) - 1, 0, widthPx - 1);
    }

    /** Last pixel row covered by a face ending at local v (max bound, inclusive). */
    public int toPixelYMax(double v, int heightPx) {
        return Math.clamp((int) Math.ceil(v / height * heightPx) - 1, 0, heightPx - 1);
    }

    // === Geometry ===

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

    public boolean containsLocal(double u, double v, double w) {
        return u >= 0 && u <= width && v >= 0 && v <= height && w >= 0 && w <= depth;
    }

    // === Accessors ===

    public FaceFrame frame() { return frame; }
    public Vec3 origin() { return origin; }
    public float width() { return width; }
    public float height() { return height; }
    public float depth() { return depth; }
}
