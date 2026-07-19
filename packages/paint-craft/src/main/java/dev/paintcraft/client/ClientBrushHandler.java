package dev.paintcraft.client;

import dev.paintcraft.client.gui.PaintScreen;
import dev.paintcraft.core.Decal;
import dev.paintcraft.core.DisplayTransform;
import dev.paintcraft.core.FaceFrame;
import dev.paintcraft.core.PixelGrid;
import dev.paintcraft.ModServerConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;

import java.util.UUID;

public final class ClientBrushHandler {

    private ClientBrushHandler() {}

    static int maxCanvasSize() {
        try { return ModServerConfig.CONFIG.maxCanvasSize.get(); }
        catch (IllegalStateException e) { return 16; }
    }

    static float maxDepth() {
        try { return ModServerConfig.CONFIG.maxDepth.get().floatValue(); }
        catch (IllegalStateException e) { return Decal.MAX_DEPTH; }
    }

    // Multi-block corner selection state
    private static BlockPos pendingCorner = null;
    private static Direction pendingFace = null;

    public static BlockPos getPendingCorner() { return pendingCorner; }
    public static Direction getPendingFace()  { return pendingFace; }

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

        // Validate parallel planes within depth range
        int normalCoord1 = pos1.getX() * face.getStepX() + pos1.getY() * face.getStepY() + pos1.getZ() * face.getStepZ();
        int normalCoord2 = pos2.getX() * face.getStepX() + pos2.getY() * face.getStepY() + pos2.getZ() * face.getStepZ();
        int normalDiff = Math.abs(normalCoord1 - normalCoord2);
        if (normalDiff > (int) maxDepth()) {
            Minecraft.getInstance().player.displayClientMessage(
                Component.literal("Corners too far apart (max " + (int) maxDepth() + " blocks depth)"), true);
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

        if (widthBlocks > maxCanvasSize() || heightBlocks > maxCanvasSize()) {
            Minecraft.getInstance().player.displayClientMessage(
                Component.literal("Canvas too large (max " + maxCanvasSize() + "x" + maxCanvasSize() + ")"), true);
            return;
        }

        // Anchor at closest-to-viewer plane so all surfaces are at positive depth
        int maxNormalCoord = Math.max(normalCoord1, normalCoord2);
        int normalShift = maxNormalCoord - normalCoord1;

        BlockPos anchor = pos1.offset(
            right.getStepX() * Math.min(0, rightOff) + up.getStepX() * Math.min(0, upOff)
                + face.getStepX() * normalShift,
            right.getStepY() * Math.min(0, rightOff) + up.getStepY() * Math.min(0, upOff)
                + face.getStepY() * normalShift,
            right.getStepZ() * Math.min(0, rightOff) + up.getStepZ() * Math.min(0, upOff)
                + face.getStepZ() * normalShift
        );

        BackgroundCapture.Captured bg = BackgroundCapture.capture(
            Minecraft.getInstance().level, anchor, face, up, widthBlocks, heightBlocks, maxDepth());
        EditorUnderlay.build(bg.display(), bg.raw(), anchor, frame, widthBlocks, heightBlocks, maxDepth(), null);

        Minecraft.getInstance().setScreen(new PaintScreen(
            anchor, frame, widthBlocks, heightBlocks, bg.display(), bg.raw()));
    }

    public static void clearPending() {
        pendingCorner = null;
        pendingFace = null;
    }

    public static void openNewEditor(BlockPos pos, Direction face) {
        Direction playerDir = Minecraft.getInstance().player.getDirection();
        FaceFrame frame = FaceFrame.forFace(face, playerDir);

        BackgroundCapture.Captured bg = BackgroundCapture.capture(
            Minecraft.getInstance().level, pos, face, frame.up(), 1, 1, maxDepth());
        EditorUnderlay.build(bg.display(), bg.raw(), pos, frame, 1, 1, maxDepth(), null);

        Minecraft.getInstance().setScreen(new PaintScreen(pos, frame, 1, 1, bg.display(), bg.raw()));
    }

    public static void openExistingEditor(BlockPos anchor, Direction normal, Direction storedUp,
                                           int widthBlocks, int heightBlocks,
                                           float depth, int[] pixels, UUID decalId) {
        FaceFrame storedFrame = new FaceFrame(normal, storedUp);

        // Single display policy: floors reorient to the player, walls reorient to world-UP.
        Direction playerDir = Minecraft.getInstance().player.getDirection();
        FaceFrame displayFrame = FaceFrame.displayFrameFor(normal, playerDir);

        DisplayTransform transform = DisplayTransform.between(storedFrame, displayFrame);

        // Transform pixels for display
        PixelGrid stored = new PixelGrid(widthBlocks * Decal.PX_PER_BLOCK,
                                          heightBlocks * Decal.PX_PER_BLOCK, pixels);
        PixelGrid display = transform.toDisplay(stored);

        // Compute background anchor for the display orientation. Runs for any rotated display
        // (rolled walls included); rotations==0 falls through to the unchanged anchor.
        BlockPos bgAnchor = anchor;
        if (!transform.isIdentity()) {
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

        BackgroundCapture.Captured bg = BackgroundCapture.capture(
            Minecraft.getInstance().level, bgAnchor, normal, displayFrame.up(),
            display.widthBlocks(), display.heightBlocks(), depth);
        EditorUnderlay.build(bg.display(), bg.raw(), bgAnchor, displayFrame,
            display.widthBlocks(), display.heightBlocks(), depth, decalId);

        Minecraft.getInstance().setScreen(new PaintScreen(
            anchor, storedFrame, displayFrame, transform,
            display.widthBlocks(), display.heightBlocks(),
            depth, display.data(), decalId, bg.display(), bg.raw()));
    }
}
