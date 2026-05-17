package dev.paintcraft.client;

import dev.paintcraft.client.gui.PaintScreen;
import dev.paintcraft.core.Decal;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.UUID;

public final class ClientBrushHandler {

    private ClientBrushHandler() {}

    /** Canonical up used by BackgroundCapture for floor/ceiling faces. */
    private static final Direction CANONICAL_UP = Direction.NORTH;

    public static void openNewEditor(BlockPos pos, Direction face) {
        Direction displayUp = face.getAxis().isVertical()
            ? Minecraft.getInstance().player.getDirection()
            : Direction.UP;

        int[] background = BackgroundCapture.capture(
            Minecraft.getInstance().level, pos, face, 1, 1, 1.0f);

        // Rotate background from canonical orientation to player orientation.
        // Negate: pixel rotation is opposite to the world-space direction rotation.
        if (face.getAxis().isVertical()) {
            int rotations = clockwiseSteps(CANONICAL_UP, displayUp, face);
            if (rotations != 0) {
                background = rotatePixels(background, Decal.PX_PER_BLOCK, Decal.PX_PER_BLOCK, -rotations);
            }
        }

        Minecraft.getInstance().setScreen(new PaintScreen(pos, face, displayUp, 1, 1, null, null, background));
    }

    public static void openExistingEditor(BlockPos anchor, Direction normal, Direction storedUp,
                                           int widthBlocks, int heightBlocks,
                                           int[] pixels, UUID decalId) {
        Direction displayUp = storedUp;
        int[] displayPixels = pixels;
        int displayW = widthBlocks;
        int displayH = heightBlocks;

        if (normal.getAxis().isVertical()) {
            Direction playerDir = Minecraft.getInstance().player.getDirection();
            int rotations = clockwiseSteps(storedUp, playerDir, normal);
            if (rotations != 0) {
                int wPx = widthBlocks * Decal.PX_PER_BLOCK;
                int hPx = heightBlocks * Decal.PX_PER_BLOCK;
                displayPixels = rotatePixels(pixels, wPx, hPx, -rotations);
                displayUp = playerDir;
                if (rotations % 2 == 1) {
                    displayW = heightBlocks;
                    displayH = widthBlocks;
                }
            }
        }

        // Background is captured in canonical orientation, rotate to match display
        int[] background = BackgroundCapture.capture(
            Minecraft.getInstance().level, anchor, normal, displayW, displayH, 1.0f);

        if (normal.getAxis().isVertical()) {
            int bgRotations = clockwiseSteps(CANONICAL_UP, displayUp, normal);
            if (bgRotations != 0) {
                int bgW = displayW * Decal.PX_PER_BLOCK;
                int bgH = displayH * Decal.PX_PER_BLOCK;
                background = rotatePixels(background, bgW, bgH, -bgRotations);
            }
        }

        Minecraft.getInstance().setScreen(new PaintScreen(
            anchor, normal, displayUp, displayW, displayH, displayPixels, decalId, background));
    }

    /**
     * Count 90° clockwise steps (around the face normal axis) from 'from' to 'to'.
     */
    private static int clockwiseSteps(Direction from, Direction to, Direction normal) {
        if (from == to) return 0;
        Direction cur = from;
        for (int i = 1; i <= 3; i++) {
            cur = cur.getClockWise(normal.getAxis());
            if (cur == to) return i;
        }
        return 0;
    }

    /**
     * Rotate a pixel array by the given number of 90° clockwise steps.
     */
    static int[] rotatePixels(int[] src, int w, int h, int rotations) {
        rotations = ((rotations % 4) + 4) % 4;
        if (rotations == 0) return src;

        int[] result = src;
        int curW = w, curH = h;

        for (int r = 0; r < rotations; r++) {
            int newW = curH;
            int newH = curW;
            int[] rotated = new int[newW * newH];
            // 90° CW: rotated[x * newW + (newW - 1 - y)] = src[y * curW + x]
            for (int y = 0; y < curH; y++) {
                for (int x = 0; x < curW; x++) {
                    rotated[x * newW + (newW - 1 - y)] = result[y * curW + x];
                }
            }
            result = rotated;
            curW = newW;
            curH = newH;
        }
        return result;
    }
}
