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
     *
     * <p>{@link #right()} is derived from {@code up.getClockWise(axis)}, which keys on the
     * normal's <em>axis</em> only and ignores its direction — so opposite faces share the same
     * world-space right vector (NORTH and SOUTH are both EAST). For a viewer the correct basis
     * is right-handed about the outward normal ({@code right × up == normal}), which holds only
     * for positive-direction normals. Every negative-direction normal (NORTH, WEST, DOWN) is
     * therefore mirrored relative to its viewer, and is flipped for display to compensate.
     */
    public boolean needsHFlip() {
        return normal.getAxisDirection() == Direction.AxisDirection.NEGATIVE;
    }

    // === Factories ===

    /**
     * World-locked frame for a face direction (walls=UP, floor/ceiling=NORTH), used as the
     * shared coordinate space for the renderer's per-face atlas cells.
     *
     * <p><b>Viewer-independent by design — never use this to orient content a player looks at.</b>
     * For vertical faces it pins "up" to world NORTH, whereas everything a player sees
     * (editor canvas, stamps, library assets) is oriented to the viewer. Normalising
     * viewer-facing pixels through this frame silently cancels the player's view rotation on
     * floors and ceilings; use {@link #displayFrameFor} / {@link #displayReference} instead.
     */
    private static final FaceFrame[] CELL = new FaceFrame[6];
    static {
        for (Direction d : Direction.values()) {
            CELL[d.ordinal()] = d.getAxis().isVertical()
                ? new FaceFrame(d, Direction.NORTH)
                : new FaceFrame(d, Direction.UP);
        }
    }

    /** Returns the world-locked cell/atlas frame for a face direction. See {@link #CELL}. */
    public static FaceFrame cellFrame(Direction face) {
        return CELL[face.ordinal()];
    }

    /**
     * The single editor-display policy: which frame a decal is presented in to a viewer.
     *
     * <ul>
     *   <li><b>Floor</b> ({@code UP} normal, looking down): screen-up is the direction the
     *       viewer is facing — content ahead of them projects to the top of the screen.</li>
     *   <li><b>Ceiling</b> ({@code DOWN} normal, looking up): screen-up is the
     *       <em>opposite</em> of the direction the viewer is facing. Pitching up rotates the
     *       camera about its right axis past vertical, which flips forward-vs-behind on screen —
     *       content ahead of the viewer projects to the <em>bottom</em>, and what's directly
     *       behind them ends up at the top.</li>
     *   <li><b>Wall</b> (horizontal normal): oriented to world-UP, so the canvas top is always
     *       gravity-up regardless of how the decal was rolled (e.g. by a Create contraption).</li>
     * </ul>
     *
     * <p>This is also the frame a decal is <em>created</em> in, so for any freshly authored or
     * stamped decal the stored frame and the display frame of its author coincide.
     *
     * <p>The transform from the stored frame to this display frame is computed generically by
     * {@link DisplayTransform#between}, so no caller needs to special-case face types.
     */
    public static FaceFrame displayFrameFor(Direction normal, Direction viewerFacing) {
        if (normal == Direction.DOWN) return new FaceFrame(normal, viewerFacing.getOpposite());
        return normal.getAxis().isVertical()
            ? new FaceFrame(normal, viewerFacing)
            : new FaceFrame(normal, Direction.UP);
    }

    /**
     * The frame this one presents as in the editor and on a stamp: walls normalise to world UP
     * (de-rolling contraption-rotated decals); floors and ceilings keep their own up exactly as
     * is — a frame's {@code up} on a vertical face already <em>is</em> a display orientation
     * (see {@link #displayFrameFor}), there's nothing further to derive from a viewer.
     *
     * <p>Must not delegate to {@code displayFrameFor(normal, up)}: that method's second
     * parameter means "the direction the viewer is facing", not "an up already chosen for
     * display" — feeding this frame's own {@code up} through it would reapply the ceiling's
     * facing→opposite flip a second time and silently rotate the result 180°.
     */
    public FaceFrame displayReference() {
        return normal.getAxis().isVertical() ? this : new FaceFrame(normal, Direction.UP);
    }
}
