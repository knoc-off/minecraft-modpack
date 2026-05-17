package dev.paintcraft.client;

import dev.paintcraft.client.gui.PaintScreen;
import dev.paintcraft.core.Decal;
import dev.paintcraft.core.DisplayTransform;
import dev.paintcraft.core.FaceFrame;
import dev.paintcraft.core.PixelGrid;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;

import java.util.UUID;

public final class ClientBrushHandler {

    private ClientBrushHandler() {}

    private static final int MAX_CANVAS_SIZE = 16;

    // Multi-block corner selection state
    private static BlockPos pendingCorner = null;
    private static Direction pendingFace = null;

    public static void handleCornerClick(BlockPos pos, Direction face) {
        if (pendingCorner == null || pendingFace != face) {
            pendingCorner = pos;
            pendingFace = face;
            Minecraft.getInstance().player.displayClientMessage(
                Component.literal("Corner 1 set — shift+click another block on the same face"), true);
            return;
        }

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

        Direction playerDir = Minecraft.getInstance().player.getDirection();
        FaceFrame frame = FaceFrame.forFace(face, playerDir);
        Direction right = frame.right();
        Direction up = frame.up();

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

        BlockPos anchor = pos1.offset(
            right.getStepX() * Math.min(0, rightOff) + up.getStepX() * Math.min(0, upOff),
            right.getStepY() * Math.min(0, rightOff) + up.getStepY() * Math.min(0, upOff),
            right.getStepZ() * Math.min(0, rightOff) + up.getStepZ() * Math.min(0, upOff)
        );

        int[] background = BackgroundCapture.capture(
            Minecraft.getInstance().level, anchor, face, up, widthBlocks, heightBlocks, 1.0f);

        Minecraft.getInstance().setScreen(new PaintScreen(
            anchor, frame, widthBlocks, heightBlocks, background));
    }

    public static void clearPending() {
        pendingCorner = null;
        pendingFace = null;
    }

    public static void openNewEditor(BlockPos pos, Direction face) {
        Direction playerDir = Minecraft.getInstance().player.getDirection();
        FaceFrame frame = FaceFrame.forFace(face, playerDir);

        int[] background = BackgroundCapture.capture(
            Minecraft.getInstance().level, pos, face, frame.up(), 1, 1, 1.0f);

        Minecraft.getInstance().setScreen(new PaintScreen(pos, frame, 1, 1, background));
    }

    public static void openExistingEditor(BlockPos anchor, Direction normal, Direction storedUp,
                                           int widthBlocks, int heightBlocks,
                                           int[] pixels, UUID decalId) {
        FaceFrame storedFrame = new FaceFrame(normal, storedUp);

        // Display frame: for vertical faces, use player's current facing direction
        Direction playerDir = Minecraft.getInstance().player.getDirection();
        FaceFrame displayFrame = normal.getAxis().isVertical()
            ? FaceFrame.horizontal(normal, playerDir)
            : storedFrame;

        DisplayTransform transform = DisplayTransform.forEditor(storedFrame, displayFrame);

        // Transform pixels for display
        PixelGrid stored = new PixelGrid(widthBlocks * Decal.PX_PER_BLOCK,
                                          heightBlocks * Decal.PX_PER_BLOCK, pixels);
        PixelGrid display = transform.toDisplay(stored);

        // Compute background anchor for the display orientation
        BlockPos bgAnchor = anchor;
        if (!transform.isIdentity() && normal.getAxis().isVertical()) {
            int rotations = storedFrame.clockwiseStepsTo(displayFrame);
            if (rotations != 0) {
                Direction storedRight = storedFrame.right();
                bgAnchor = switch (rotations) {
                    case 1 -> anchor.offset(
                        storedUp.getStepX() * (heightBlocks - 1),
                        storedUp.getStepY() * (heightBlocks - 1),
                        storedUp.getStepZ() * (heightBlocks - 1));
                    case 2 -> anchor.offset(
                        storedRight.getStepX() * (widthBlocks - 1) + storedUp.getStepX() * (heightBlocks - 1),
                        storedRight.getStepY() * (widthBlocks - 1) + storedUp.getStepY() * (heightBlocks - 1),
                        storedRight.getStepZ() * (widthBlocks - 1) + storedUp.getStepZ() * (heightBlocks - 1));
                    case 3 -> anchor.offset(
                        storedRight.getStepX() * (widthBlocks - 1),
                        storedRight.getStepY() * (widthBlocks - 1),
                        storedRight.getStepZ() * (widthBlocks - 1));
                    default -> anchor;
                };
            }
        }

        int[] background = BackgroundCapture.capture(
            Minecraft.getInstance().level, bgAnchor, normal, displayFrame.up(),
            display.widthBlocks(), display.heightBlocks(), 1.0f);

        Minecraft.getInstance().setScreen(new PaintScreen(
            anchor, storedFrame, displayFrame, transform,
            display.widthBlocks(), display.heightBlocks(),
            display.data(), decalId, background));
    }
}
