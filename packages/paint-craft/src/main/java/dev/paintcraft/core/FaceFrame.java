package dev.paintcraft.core;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * An orthonormal coordinate frame on a block face.
 * Encapsulates (normal, up) and derives right, origin, and rotation math.
 * Validates orthogonality at construction — impossible to create an invalid frame.
 */
public record FaceFrame(Direction normal, Direction up) {

    public FaceFrame {
        if (up.getAxis() == normal.getAxis()) {
            throw new IllegalArgumentException(
                "up (" + up + ") must be on a different axis than normal (" + normal + ")");
        }
    }

    // === Direction derivation (single canonical source) ===

    public Direction right() {
        return up.getClockWise(normal.getAxis());
    }

    // === Vec3 basis vectors ===

    public Vec3 rightVec() { return Vec3.atLowerCornerOf(right().getNormal()); }
    public Vec3 upVec()    { return Vec3.atLowerCornerOf(up.getNormal()); }
    public Vec3 forwardVec() { return Vec3.atLowerCornerOf(normal.getNormal()).scale(-1); }

    // === Origin computation (THE one place — replaces 3 duplicated copies) ===

    /**
     * Compute the projection origin for a given anchor block.
     * This is the corner of the projection volume where u=0, v=0 on the face surface.
     */
    public Vec3 projectionOrigin(BlockPos anchor) {
        Vec3 orig = Vec3.atLowerCornerOf(anchor);

        // Offset to face surface for positive-axis normals
        if (normal.getAxisDirection() == Direction.AxisDirection.POSITIVE) {
            orig = orig.add(Vec3.atLowerCornerOf(normal.getNormal()));
        }

        // Shift +1 on any axis where right or up step negatively
        Direction r = right();
        if (r.getStepX() < 0) orig = orig.add(1, 0, 0);
        if (r.getStepY() < 0) orig = orig.add(0, 1, 0);
        if (r.getStepZ() < 0) orig = orig.add(0, 0, 1);
        if (up.getStepX() < 0) orig = orig.add(1, 0, 0);
        if (up.getStepY() < 0) orig = orig.add(0, 1, 0);
        if (up.getStepZ() < 0) orig = orig.add(0, 0, 1);

        return orig;
    }

    // === Rotation between frames on the same face ===

    /**
     * Count 90° clockwise steps (around the normal axis) from this frame's up to another's.
     * Both frames must share the same normal.
     */
    public int clockwiseStepsTo(FaceFrame other) {
        if (this.normal != other.normal) {
            throw new IllegalArgumentException(
                "Frames must share the same normal (got " + this.normal + " vs " + other.normal + ")");
        }
        if (this.up == other.up) return 0;
        Direction cur = this.up;
        for (int i = 1; i <= 3; i++) {
            cur = cur.getClockWise(normal.getAxis());
            if (cur == other.up) return i;
        }
        return 0; // should not happen for valid Direction values
    }

    // === hFlip detection ===

    /**
     * Whether this frame needs horizontal flip in the editor display.
     * True for wall faces with negative-direction normals (NORTH, WEST).
     */
    public boolean needsHFlip() {
        return normal.getAxis() != Direction.Axis.Y
            && normal.getAxisDirection() == Direction.AxisDirection.NEGATIVE;
    }

    // === Factories ===

    /** Canonical frame for a face direction (walls=UP, floor/ceiling=NORTH). */
    private static final FaceFrame[] CANONICAL = new FaceFrame[6];
    static {
        for (Direction d : Direction.values()) {
            CANONICAL[d.ordinal()] = d.getAxis().isVertical()
                ? new FaceFrame(d, Direction.NORTH)
                : wall(d);
        }
    }

    /** Returns the canonical frame for a face direction. Player-independent. */
    public static FaceFrame canonical(Direction face) {
        return CANONICAL[face.ordinal()];
    }

    /** Frame for a wall face (up is always world UP). */
    public static FaceFrame wall(Direction normal) {
        return new FaceFrame(normal, Direction.UP);
    }

    /** Frame for a floor/ceiling face with the given player-facing direction as up. */
    public static FaceFrame horizontal(Direction normal, Direction playerFacing) {
        return new FaceFrame(normal, playerFacing);
    }

    /** Convenience: picks wall() or horizontal() based on the face axis. */
    public static FaceFrame forFace(Direction face, Direction playerFacing) {
        return face.getAxis().isVertical()
            ? horizontal(face, playerFacing)
            : wall(face);
    }
}
