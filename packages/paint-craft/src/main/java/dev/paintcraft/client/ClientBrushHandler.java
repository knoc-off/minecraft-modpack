package dev.paintcraft.client;

import dev.paintcraft.client.gui.PaintScreen;
import dev.paintcraft.core.Decal;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;

import java.util.UUID;

public final class ClientBrushHandler {

    private static BlockPos pendingCorner = null;
    private static Direction pendingFace = null;

    private ClientBrushHandler() {}

    public static void openNewEditor(BlockPos pos, Direction face) {
        int[] background = BackgroundCapture.capture(
            Minecraft.getInstance().level, pos, face, 1, 1, 1.0f);
        Minecraft.getInstance().setScreen(new PaintScreen(pos, face, 1, 1, null, null, background));
    }

    public static void openExistingEditor(BlockPos anchor, Direction normal, int widthBlocks, int heightBlocks,
                                           int[] pixels, java.util.UUID decalId) {
        int[] background = BackgroundCapture.capture(
            Minecraft.getInstance().level, anchor, normal, widthBlocks, heightBlocks, 1.0f);
        Minecraft.getInstance().setScreen(new PaintScreen(anchor, normal, widthBlocks, heightBlocks, pixels, decalId, background));
    }

    public static void handleCornerClick(BlockPos pos, Direction face) {
        if (pendingCorner == null) {
            // First corner
            pendingCorner = pos;
            pendingFace = face;
            Minecraft.getInstance().player.displayClientMessage(
                Component.literal("First corner set. Shift+click the opposite corner."), true);
        } else {
            // Second corner — validate and open editor
            if (pendingFace != face) {
                // Different face direction — invalid, reset
                pendingCorner = null;
                pendingFace = null;
                Minecraft.getInstance().player.displayClientMessage(
                    Component.literal("Corners must be on the same face direction. Selection cleared."), true);
                return;
            }

            // Validate same plane (face-axis coordinate must match)
            if (!samePlane(pendingCorner, pos, face)) {
                pendingCorner = null;
                pendingFace = null;
                Minecraft.getInstance().player.displayClientMessage(
                    Component.literal("Corners must be on the same plane. Selection cleared."), true);
                return;
            }

            // Compute rectangle dimensions
            Direction up = face.getAxis().isVertical() ? Direction.NORTH : Direction.UP;
            Direction right = up.getClockWise(face.getAxis());

            int widthBlocks = extentAlongAxis(pendingCorner, pos, right.getAxis());
            int heightBlocks = extentAlongAxis(pendingCorner, pos, up.getAxis());

            // Cap at 8x8 blocks (128x128 pixels)
            if (widthBlocks > 8 || heightBlocks > 8) {
                pendingCorner = null;
                pendingFace = null;
                Minecraft.getInstance().player.displayClientMessage(
                    Component.literal("Canvas too large (max 8x8 blocks). Selection cleared."), true);
                return;
            }

            // Compute anchor (the corner at min-right, min-up position)
            BlockPos anchor = computeAnchor(pendingCorner, pos, face, right, up);

            // Open editor with background capture
            int[] background = BackgroundCapture.capture(
                Minecraft.getInstance().level, anchor, face, widthBlocks, heightBlocks);
            Minecraft.getInstance().setScreen(new PaintScreen(
                anchor, face, widthBlocks, heightBlocks, null, null, background
            ));

            // Clear state
            pendingCorner = null;
            pendingFace = null;
        }
    }

    public static void clearPendingCorner() {
        pendingCorner = null;
        pendingFace = null;
    }

    public static boolean hasPendingCorner() {
        return pendingCorner != null;
    }

    private static boolean samePlane(BlockPos a, BlockPos b, Direction face) {
        return switch (face.getAxis()) {
            case X -> a.getX() == b.getX();
            case Y -> a.getY() == b.getY();
            case Z -> a.getZ() == b.getZ();
        };
    }

    private static int extentAlongAxis(BlockPos a, BlockPos b, Direction.Axis axis) {
        return switch (axis) {
            case X -> Math.abs(a.getX() - b.getX()) + 1;
            case Y -> Math.abs(a.getY() - b.getY()) + 1;
            case Z -> Math.abs(a.getZ() - b.getZ()) + 1;
        };
    }

    private static BlockPos computeAnchor(BlockPos pos1, BlockPos pos2, Direction face,
                                           Direction right, Direction up) {
        int minX = Math.min(pos1.getX(), pos2.getX());
        int minY = Math.min(pos1.getY(), pos2.getY());
        int minZ = Math.min(pos1.getZ(), pos2.getZ());
        int maxX = Math.max(pos1.getX(), pos2.getX());
        int maxY = Math.max(pos1.getY(), pos2.getY());
        int maxZ = Math.max(pos1.getZ(), pos2.getZ());

        // For each axis: if the direction is positive, anchor at min; if negative, anchor at max.
        // The face-normal axis uses the clicked position.
        int ax = chooseComponent(right, up, Direction.Axis.X, minX, maxX, pos1.getX());
        int ay = chooseComponent(right, up, Direction.Axis.Y, minY, maxY, pos1.getY());
        int az = chooseComponent(right, up, Direction.Axis.Z, minZ, maxZ, pos1.getZ());

        // Override the face-normal axis with the clicked block position
        switch (face.getAxis()) {
            case X -> ax = pos1.getX();
            case Y -> ay = pos1.getY();
            case Z -> az = pos1.getZ();
        }

        return new BlockPos(ax, ay, az);
    }

    private static int chooseComponent(Direction right, Direction up, Direction.Axis axis, int min, int max, int fallback) {
        // Check if "right" direction affects this axis
        if (right.getAxis() == axis) {
            return right.getAxisDirection() == Direction.AxisDirection.POSITIVE ? min : max;
        }
        // Check if "up" direction affects this axis
        if (up.getAxis() == axis) {
            return up.getAxisDirection() == Direction.AxisDirection.POSITIVE ? min : max;
        }
        // This axis is the face normal axis — will be overridden
        return fallback;
    }
}
