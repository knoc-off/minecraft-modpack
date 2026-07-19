package dev.paintcraft.core;

/**
 * Reversible UI presentation transform that maps between stored pixel orientation
 * and editor display orientation. Encapsulates hFlip (for negative-normal walls)
 * and rotation (for floor/ceiling re-edit from different angle).
 *
 * GUARANTEE: toStored(toDisplay(grid)) returns an equivalent grid to the input.
 */
public record DisplayTransform(boolean hFlip, int rotationCW) {

    public static final DisplayTransform IDENTITY = new DisplayTransform(false, 0);

    public DisplayTransform(boolean hFlip, int rotationCW) {
        this.hFlip = hFlip;
        this.rotationCW = ((rotationCW % 4) + 4) % 4;
    }

    /**
     * Compute the transform that maps a decal stored in {@code stored} orientation into the
     * {@code display} orientation. Purely geometric — works for any two frames that share a
     * normal (walls and floors alike); the caller picks the display frame via
     * {@link FaceFrame#displayFrameFor}.
     */
    public static DisplayTransform between(FaceFrame stored, FaceFrame display) {
        boolean flip = stored.needsHFlip();
        int rot = 0;
        if (stored.up() != display.up()) {
            // World CW steps from stored to display → negate for pixel rotation.
            rot = (4 - stored.clockwiseStepsTo(display)) % 4;
        }
        return new DisplayTransform(flip, rot);
    }

    /**
     * Transform stored pixels to display orientation (for showing in editor).
     * Apply rotation first, then flip.
     */
    public PixelGrid toDisplay(PixelGrid stored) {
        PixelGrid result = stored;
        if (rotationCW != 0) result = result.rotateCW(rotationCW);
        if (hFlip) result = result.flipH();
        return result;
    }

    /**
     * Transform display pixels back to stored orientation (for saving).
     * Exact inverse of toDisplay: undo flip first (self-inverse), then undo rotation.
     */
    public PixelGrid toStored(PixelGrid display) {
        PixelGrid result = display;
        if (hFlip) result = result.flipH();
        if (rotationCW != 0) result = result.rotateCW(4 - rotationCW);
        return result;
    }


    /** Map pixel X from display space to data space (for mouse interaction). */
    public int toDataX(int displayX, int width) {
        return hFlip ? width - 1 - displayX : displayX;
    }

    public boolean isIdentity() { return !hFlip && rotationCW == 0; }
}
