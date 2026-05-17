package dev.paintcraft.client;

import dev.paintcraft.client.gui.PaintScreen;
import dev.paintcraft.core.Decal;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;

import java.util.UUID;

public final class ClientBrushHandler {

    private ClientBrushHandler() {}

    /** Canonical up used by BackgroundCapture for floor/ceiling faces. */
    private static final Direction CANONICAL_UP = Direction.NORTH;
    private static final int MAX_CANVAS_SIZE = 16;

    // Multi-block corner selection state
    private static BlockPos pendingCorner = null;
    private static Direction pendingFace = null;

    public static void handleCornerClick(BlockPos pos, Direction face) {
        if (pendingCorner == null || pendingFace != face) {
            // First corner (or face changed — reset)
            pendingCorner = pos;
            pendingFace = face;
            Minecraft.getInstance().player.displayClientMessage(
                Component.literal("Corner 1 set — shift+click another block on the same face"), true);
            return;
        }

        // Second corner — validate and open editor
        BlockPos pos1 = pendingCorner;
        BlockPos pos2 = pos;
        clearPending();

        // Validate same face plane
        int normalCoord1 = pos1.getX() * face.getStepX() + pos1.getY() * face.getStepY() + pos1.getZ() * face.getStepZ();
        int normalCoord2 = pos2.getX() * face.getStepX() + pos2.getY() * face.getStepY() + pos2.getZ() * face.getStepZ();
        if (normalCoord1 != normalCoord2) {
            Minecraft.getInstance().player.displayClientMessage(
                Component.literal("Corners must be on the same face plane"), true);
            return;
        }

        // Compute rectangle
        Direction up = face.getAxis().isVertical()
            ? Minecraft.getInstance().player.getDirection()
            : Direction.UP;
        Direction right = up.getClockWise(face.getAxis());

        int dx = pos2.getX() - pos1.getX();
        int dy = pos2.getY() - pos1.getY();
        int dz = pos2.getZ() - pos1.getZ();
        int rightOff = dx * right.getStepX() + dy * right.getStepY() + dz * right.getStepZ();
        int upOff = dx * up.getStepX() + dy * up.getStepY() + dz * up.getStepZ();

        int widthBlocks = Math.abs(rightOff) + 1;
        int heightBlocks = Math.abs(upOff) + 1;

        if (widthBlocks > MAX_CANVAS_SIZE || heightBlocks > MAX_CANVAS_SIZE) {
            Minecraft.getInstance().player.displayClientMessage(
                Component.literal("Canvas too large (max " + MAX_CANVAS_SIZE + "x" + MAX_CANVAS_SIZE + ")"), true);
            return;
        }

        // Anchor = min corner (shift pos1 toward min-right, min-up)
        BlockPos anchor = pos1.offset(
            right.getStepX() * Math.min(0, rightOff) + up.getStepX() * Math.min(0, upOff),
            right.getStepY() * Math.min(0, rightOff) + up.getStepY() * Math.min(0, upOff),
            right.getStepZ() * Math.min(0, rightOff) + up.getStepZ() * Math.min(0, upOff)
        );

        openMultiBlockEditor(anchor, face, up, widthBlocks, heightBlocks);
    }

    public static void clearPending() {
        pendingCorner = null;
        pendingFace = null;
    }

    private static void openMultiBlockEditor(BlockPos anchor, Direction face, Direction displayUp,
                                              int widthBlocks, int heightBlocks) {
        int[] background = BackgroundCapture.capture(
            Minecraft.getInstance().level, anchor, face, widthBlocks, heightBlocks, 1.0f);

        // Rotate background for floor/ceiling
        if (face.getAxis().isVertical()) {
            int rotations = clockwiseSteps(CANONICAL_UP, displayUp, face);
            if (rotations != 0) {
                int bgW = widthBlocks * Decal.PX_PER_BLOCK;
                int bgH = heightBlocks * Decal.PX_PER_BLOCK;
                background = rotatePixels(background, bgW, bgH, -rotations);
            }
        }

        Minecraft.getInstance().setScreen(new PaintScreen(
            anchor, face, displayUp, widthBlocks, heightBlocks, null, null, background));
    }

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
