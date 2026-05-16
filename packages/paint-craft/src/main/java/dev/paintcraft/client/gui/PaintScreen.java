package dev.paintcraft.client.gui;

import dev.paintcraft.core.Decal;
import dev.paintcraft.network.DecalCreatePayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class PaintScreen extends Screen {

    private static final int[] PALETTE = {
        0xFF000000, 0xFF444444, 0xFF888888, 0xFFFFFFFF,
        0xFFFF0000, 0xFFFF8800, 0xFFFFFF00, 0xFF88FF00,
        0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFF8800FF,
        0xFFFF00FF, 0xFF884400, 0xFFDDBB88, 0xFFFFCCAA
    };

    private final BlockPos anchor;
    private final Direction normal;
    private final int widthBlocks;
    private final int heightBlocks;
    private final int canvasW;
    private final int canvasH;
    private final UUID decalId;

    private int[] pixels;
    private int[] backgroundPixels;
    private final List<int[]> undoStack = new ArrayList<>();
    private final List<int[]> redoStack = new ArrayList<>();

    private int selectedColor = 0xFF000000;
    private PaintTool activeTool = PaintTool.PENCIL;
    private int brushSize = 1;
    private boolean painting = false;

    // Layout
    private int canvasX, canvasY, pixelSize;

    public PaintScreen(BlockPos anchor, Direction normal, int widthBlocks, int heightBlocks,
                       int[] existingPixels, UUID decalId, int[] backgroundPixels) {
        super(Component.literal("Paint"));
        this.anchor = anchor;
        this.normal = normal;
        this.widthBlocks = widthBlocks;
        this.heightBlocks = heightBlocks;
        this.canvasW = widthBlocks * Decal.PX_PER_BLOCK;
        this.canvasH = heightBlocks * Decal.PX_PER_BLOCK;
        this.decalId = decalId != null ? decalId : UUID.randomUUID();
        this.backgroundPixels = backgroundPixels;

        if (existingPixels != null && existingPixels.length == canvasW * canvasH) {
            this.pixels = Arrays.copyOf(existingPixels, existingPixels.length);
        } else {
            this.pixels = new int[canvasW * canvasH];
        }
    }

    /** Constructor for new blank canvas */
    public PaintScreen(BlockPos anchor, Direction normal, int widthBlocks, int heightBlocks) {
        this(anchor, normal, widthBlocks, heightBlocks, null, null, null);
    }

    @Override
    protected void init() {
        // Compute canvas layout: scale pixels to fit screen
        int availableH = this.height - 80; // leave room for palette + buttons
        int availableW = this.width - 100; // leave room for tool buttons
        pixelSize = Math.min(availableW / canvasW, availableH / canvasH);
        pixelSize = Math.max(pixelSize, 2); // minimum 2px per pixel
        canvasX = (this.width - 60 - canvasW * pixelSize) / 2;
        canvasY = 36;

        // Tool buttons (right side)
        int toolX = canvasX + canvasW * pixelSize + 8;
        int toolY = canvasY;

        addRenderableWidget(Button.builder(Component.literal("Pencil"), b -> activeTool = PaintTool.PENCIL)
            .bounds(toolX, toolY, 50, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Eraser"), b -> activeTool = PaintTool.ERASER)
            .bounds(toolX, toolY + 20, 50, 16).build());

        addRenderableWidget(Button.builder(Component.literal("Size+"), b -> brushSize = Math.min(brushSize + 1, 4))
            .bounds(toolX, toolY + 50, 50, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Size-"), b -> brushSize = Math.max(brushSize - 1, 1))
            .bounds(toolX, toolY + 70, 50, 16).build());

        addRenderableWidget(Button.builder(Component.literal("Undo"), b -> undo())
            .bounds(toolX, toolY + 100, 50, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Redo"), b -> redo())
            .bounds(toolX, toolY + 120, 50, 16).build());

        // Done / Cancel buttons (bottom)
        int bottomY = canvasY + canvasH * pixelSize + 8;
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> saveAndClose())
            .bounds(this.width / 2 - 55, bottomY, 50, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
            .bounds(this.width / 2 + 5, bottomY, 50, 20).build());
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        renderBackground(gfx, mouseX, mouseY, partialTick);

        // Draw palette (top row)
        int palX = canvasX;
        int palY = canvasY - 20;
        for (int i = 0; i < PALETTE.length; i++) {
            int bx = palX + i * 14;
            gfx.fill(bx, palY, bx + 12, palY + 12, PALETTE[i]);
            if (PALETTE[i] == selectedColor) {
                // White border for selected
                gfx.renderOutline(bx - 1, palY - 1, 14, 14, 0xFFFFFFFF);
            }
        }

        // Draw canvas background (captured block textures or checkerboard fallback)
        for (int py = 0; py < canvasH; py++) {
            for (int px = 0; px < canvasW; px++) {
                int sx = canvasX + px * pixelSize;
                int sy = canvasY + py * pixelSize;

                if (backgroundPixels != null) {
                    int bgColor = backgroundPixels[py * canvasW + px];
                    if (((bgColor >> 24) & 0xFF) > 0) {
                        gfx.fill(sx, sy, sx + pixelSize, sy + pixelSize, bgColor);
                        continue;
                    }
                }
                // Fallback: checkerboard for transparent/missing areas
                boolean checker = ((px / 2) + (py / 2)) % 2 == 0;
                gfx.fill(sx, sy, sx + pixelSize, sy + pixelSize, checker ? 0xFF666666 : 0xFF999999);
            }
        }

        // Draw pixels
        for (int py = 0; py < canvasH; py++) {
            for (int px = 0; px < canvasW; px++) {
                int color = pixels[py * canvasW + px];
                if (((color >> 24) & 0xFF) <= 0) continue; // skip fully transparent
                int sx = canvasX + px * pixelSize;
                int sy = canvasY + py * pixelSize;
                gfx.fill(sx, sy, sx + pixelSize, sy + pixelSize, color);
            }
        }

        // Grid lines
        int gridColor = 0x40000000;
        for (int px = 0; px <= canvasW; px++) {
            int sx = canvasX + px * pixelSize;
            gfx.fill(sx, canvasY, sx + 1, canvasY + canvasH * pixelSize, gridColor);
        }
        for (int py = 0; py <= canvasH; py++) {
            int sy = canvasY + py * pixelSize;
            gfx.fill(canvasX, sy, canvasX + canvasW * pixelSize, sy + 1, gridColor);
        }

        // Cursor highlight
        int cursorPx = (mouseX - canvasX) / pixelSize;
        int cursorPy = (mouseY - canvasY) / pixelSize;
        if (cursorPx >= 0 && cursorPx < canvasW && cursorPy >= 0 && cursorPy < canvasH) {
            int radius = brushSize - 1;
            int hx0 = canvasX + Math.max(0, cursorPx - radius) * pixelSize;
            int hy0 = canvasY + Math.max(0, cursorPy - radius) * pixelSize;
            int hx1 = canvasX + (Math.min(canvasW - 1, cursorPx + radius) + 1) * pixelSize;
            int hy1 = canvasY + (Math.min(canvasH - 1, cursorPy + radius) + 1) * pixelSize;
            gfx.renderOutline(hx0, hy0, hx1 - hx0, hy1 - hy0, 0xFFFFFFFF);
        }

        // Tool info
        int toolX = canvasX + canvasW * pixelSize + 8;
        gfx.drawString(this.font, "Tool: " + activeTool.name(), toolX, canvasY + 145, 0xFFFFFF);
        gfx.drawString(this.font, "Size: " + brushSize, toolX, canvasY + 158, 0xFFFFFF);

        super.render(gfx, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;

        // Check palette click
        int palX = canvasX;
        int palY = canvasY - 20;
        for (int i = 0; i < PALETTE.length; i++) {
            int bx = palX + i * 14;
            if (mouseX >= bx && mouseX < bx + 12 && mouseY >= palY && mouseY < palY + 12) {
                selectedColor = PALETTE[i];
                return true;
            }
        }

        // Check canvas click
        int px = ((int) mouseX - canvasX) / pixelSize;
        int py = ((int) mouseY - canvasY) / pixelSize;
        if (px >= 0 && px < canvasW && py >= 0 && py < canvasH) {
            pushUndo();
            activeTool.draw(pixels, canvasW, canvasH, px, py, brushSize, selectedColor);
            painting = true;
            return true;
        }

        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (painting) {
            int px = ((int) mouseX - canvasX) / pixelSize;
            int py = ((int) mouseY - canvasY) / pixelSize;
            if (px >= 0 && px < canvasW && py >= 0 && py < canvasH) {
                activeTool.draw(pixels, canvasW, canvasH, px, py, brushSize, selectedColor);
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        painting = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Ctrl+Z undo, Ctrl+Shift+Z or Ctrl+Y redo
        if (hasControlDown()) {
            if (keyCode == 90 && !hasShiftDown()) { undo(); return true; } // Z
            if (keyCode == 90 && hasShiftDown()) { redo(); return true; }  // Shift+Z
            if (keyCode == 89) { redo(); return true; } // Y
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void pushUndo() {
        undoStack.add(Arrays.copyOf(pixels, pixels.length));
        if (undoStack.size() > 20) undoStack.remove(0);
        redoStack.clear();
    }

    private void undo() {
        if (undoStack.isEmpty()) return;
        redoStack.add(Arrays.copyOf(pixels, pixels.length));
        pixels = undoStack.remove(undoStack.size() - 1);
    }

    private void redo() {
        if (redoStack.isEmpty()) return;
        undoStack.add(Arrays.copyOf(pixels, pixels.length));
        pixels = redoStack.remove(redoStack.size() - 1);
    }

    private void saveAndClose() {
        // Determine up direction for the decal
        Direction up = normal.getAxis().isVertical() ? Direction.NORTH : Direction.UP;
        float depth = 1.0f;

        DecalCreatePayload payload = new DecalCreatePayload(
            decalId, 0, anchor, normal, up,
            canvasW, canvasH, depth, (byte) 0, pixels
        );
        PacketDistributor.sendToServer(payload);
        onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
