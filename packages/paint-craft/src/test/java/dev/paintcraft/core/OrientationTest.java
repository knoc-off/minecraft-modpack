package dev.paintcraft.core;

import dev.paintcraft.item.StampData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Orientation contract for decal pixels.
 *
 * <p>Two distinct reference frames exist and must not be confused:
 * {@link FaceFrame#cellFrame} is world-locked (renderer atlas space), while
 * {@link FaceFrame#displayFrameFor} is viewer-relative (everything a player sees). Normalising
 * viewer-facing content through the former cancels the player's view rotation on floors and
 * ceilings — the regression these tests pin down.
 */
class OrientationTest {

    /** Non-square with unique values, so transposes and mirrors are both detectable. */
    private static PixelGrid sample() {
        int w = 3, h = 2;
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) px[i] = 0xFF000000 | (i + 1);
        return PixelGrid.wrap(w, h, px);
    }

    private static void assertGridEquals(PixelGrid expected, PixelGrid actual, String msg) {
        assertEquals(expected.width(), actual.width(), msg + " (width)");
        assertEquals(expected.height(), actual.height(), msg + " (height)");
        assertArrayEquals(expected.data(), actual.data(), msg + " (pixels)");
    }

    /** All 24 valid (normal, up) frames. */
    private static List<FaceFrame> allFrames() {
        List<FaceFrame> frames = new ArrayList<>();
        for (Direction n : Direction.values()) {
            for (Direction u : Direction.values()) {
                if (u.getAxis() != n.getAxis()) frames.add(new FaceFrame(n, u));
            }
        }
        return frames;
    }

    /** What a player facing {@code viewerFacing} sees of a decal stored in {@code stored}. */
    private static PixelGrid asSeenBy(FaceFrame stored, PixelGrid pixels, Direction viewerFacing) {
        return DisplayTransform
            .between(stored, FaceFrame.displayFrameFor(stored.normal(), viewerFacing))
            .toDisplay(pixels);
    }

    private static Decal decalWith(FaceFrame frame, PixelGrid pixels) {
        return new Decal(UUID.randomUUID(), 0, BlockPos.ZERO, frame.normal(), frame.up(),
            pixels.width(), pixels.height(), Decal.MAX_DEPTH, pixels.data().clone(), (byte) 0);
    }

    /**
     * The invariant the regression broke: a stamp placed by a player facing any direction, on any
     * face, reads back to that same player exactly as the stamp image. Before the fix this failed
     * for every floor/ceiling placement where the player was not facing NORTH.
     */
    @Test
    void placedStampReadsUprightToThePlacer() {
        PixelGrid stamp = sample();
        StampData data = new StampData(stamp.width(), stamp.height(), stamp.data().clone());

        for (Direction face : Direction.values()) {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                FaceFrame dest = FaceFrame.displayFrameFor(face, facing);
                PixelGrid stored = data.toStoredFor(dest);
                assertGridEquals(stamp, asSeenBy(dest, stored, facing),
                    "stamp on " + face + " placed facing " + facing);
            }
        }
    }

    /**
     * Full copy → place → view chain across every source frame, copy angle, destination face and
     * placement angle: what the placer sees must equal what the copier saw.
     */
    @Test
    void copyPlaceRoundTripPreservesWhatTheCopierSaw() {
        PixelGrid source = sample();

        for (FaceFrame srcFrame : allFrames()) {
            Decal decal = decalWith(srcFrame, source);
            for (Direction copyFacing : Direction.Plane.HORIZONTAL) {
                PixelGrid seenByCopier = asSeenBy(srcFrame, source, copyFacing);
                StampData stamp = StampData.fromDecal(decal, copyFacing);

                for (Direction destFace : Direction.values()) {
                    for (Direction placeFacing : Direction.Plane.HORIZONTAL) {
                        FaceFrame dest = FaceFrame.displayFrameFor(destFace, placeFacing);
                        PixelGrid seenByPlacer =
                            asSeenBy(dest, stamp.toStoredFor(dest), placeFacing);
                        assertGridEquals(seenByCopier, seenByPlacer,
                            srcFrame + " copied facing " + copyFacing
                                + " placed on " + destFace + " facing " + placeFacing);
                    }
                }
            }
        }
    }

    /**
     * Placing on a floor or ceiling must actually depend on where the player is looking. The bug
     * made these identical (world-locked to NORTH); this is the direct regression guard.
     */
    @Test
    void verticalFacePlacementRotatesWithTheViewer() {
        PixelGrid stamp = sample();
        StampData data = new StampData(stamp.width(), stamp.height(), stamp.data().clone());

        for (Direction face : List.of(Direction.UP, Direction.DOWN)) {
            int[] north = data.toStoredFor(FaceFrame.displayFrameFor(face, Direction.NORTH)).data();
            for (Direction facing : List.of(Direction.EAST, Direction.SOUTH, Direction.WEST)) {
                int[] other = data.toStoredFor(FaceFrame.displayFrameFor(face, facing)).data();
                assertFalse(java.util.Arrays.equals(north, other),
                    face + " stamp facing " + facing + " must differ from facing NORTH");
            }
        }
    }

    /**
     * Re-deriving for a display frame never rotates, so the footprint of a non-square stamp
     * matches the copied canvas instead of transposing against a fixed anchor.
     */
    @Test
    void placementPreservesDimensions() {
        PixelGrid stamp = sample();
        StampData data = new StampData(stamp.width(), stamp.height(), stamp.data().clone());

        for (Direction face : Direction.values()) {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                PixelGrid stored = data.toStoredFor(FaceFrame.displayFrameFor(face, facing));
                assertEquals(stamp.width(), stored.width(), face + " facing " + facing + " width");
                assertEquals(stamp.height(), stored.height(), face + " facing " + facing + " height");
            }
        }
    }

    /**
     * needsHFlip is exactly the left-handedness test: right() disagrees with the viewer's right
     * (up × normal) precisely for negative-direction normals — NORTH, WEST and DOWN.
     */
    @Test
    void needsHFlipMatchesHandedness() {
        for (FaceFrame frame : allFrames()) {
            Direction u = frame.up(), n = frame.normal();
            int rx = u.getStepY() * n.getStepZ() - u.getStepZ() * n.getStepY();
            int ry = u.getStepZ() * n.getStepX() - u.getStepX() * n.getStepZ();
            int rz = u.getStepX() * n.getStepY() - u.getStepY() * n.getStepX();
            Direction viewerRight = Direction.getNearest(rx, ry, rz);

            assertEquals(frame.right() != viewerRight, frame.needsHFlip(), "handedness of " + frame);
            assertEquals(n.getAxisDirection() == Direction.AxisDirection.NEGATIVE,
                frame.needsHFlip(), "negative-normal rule for " + frame);
        }
    }

    /** DisplayTransform must be exactly reversible for every frame pair sharing a normal. */
    @Test
    void displayTransformIsReversible() {
        PixelGrid source = sample();
        for (FaceFrame stored : allFrames()) {
            for (FaceFrame display : allFrames()) {
                if (display.normal() != stored.normal()) continue;
                DisplayTransform t = DisplayTransform.between(stored, display);
                assertGridEquals(source, t.toStored(t.toDisplay(source)),
                    "round trip " + stored + " -> " + display);
            }
        }
    }

    /**
     * The two reference frames agree on walls and disagree on floors and ceilings — which is why
     * the regression was invisible on walls. Pins the distinction so they cannot be merged.
     */
    @Test
    void cellFrameIsWorldLockedWhileDisplayReferenceIsViewerRelative() {
        for (FaceFrame frame : allFrames()) {
            if (frame.normal().getAxis().isVertical()) {
                assertEquals(frame, frame.displayReference(),
                    "vertical faces keep their own up: " + frame);
                if (frame.up() != Direction.NORTH) {
                    assertNotEquals(FaceFrame.cellFrame(frame.normal()), frame.displayReference(),
                        "cellFrame must not stand in for the display reference: " + frame);
                }
            } else {
                assertEquals(FaceFrame.cellFrame(frame.normal()), frame.displayReference(),
                    "walls normalise to world UP: " + frame);
            }
        }
    }
}
