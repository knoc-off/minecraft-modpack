package dev.paintcraft.client.gui;

import dev.paintcraft.client.color.BlockColorCache;
import dev.paintcraft.client.gui.widget.BlockListWidget;
import dev.paintcraft.core.Decal;
import dev.paintcraft.network.DecalCreatePayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

public class PaintScreen extends Screen {

    // Default starter blocks
    private static final Block[] DEFAULT_BLOCKS = {
        Blocks.STONE, Blocks.OAK_PLANKS, Blocks.DIRT, Blocks.COBBLESTONE,
        Blocks.SAND, Blocks.BRICKS, Blocks.OAK_LOG, Blocks.IRON_BLOCK,
        Blocks.GOLD_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.DEEPSLATE,
        Blocks.TERRACOTTA, Blocks.WHITE_WOOL, Blocks.BLACK_WOOL
    };

    private final BlockPos anchor;
    private final Direction normal;
    private final Direction up;
    private final int widthBlocks;
    private final int heightBlocks;
    private final int canvasW;
    private final int canvasH;
    private final UUID decalId;
    private final boolean hFlip; // flip display horizontally for negative-normal faces

    private int[] pixels;
    private int[] backgroundPixels;
    private final List<int[]> undoStack = new ArrayList<>();
    private final List<int[]> redoStack = new ArrayList<>();

    // Color state
    private int selectedColor = 0xFF000000;
    private final List<Integer> blockColors = new ArrayList<>(); // accumulated from selected blocks
    private final List<Integer> recentColors = new ArrayList<>(); // last N colors used

    // Tools
    private PaintTool activeTool = PaintTool.PENCIL;
    private int brushSize = 1;
    private boolean painting = false;

    // Layout
    private int canvasX, canvasY, pixelSize;
    private BlockListWidget blockList;

    // Color bar layout
    private static final int COLOR_SWATCH_SIZE = 12;
    private static final int COLOR_BAR_Y = 4;
    private static final int RECENTS_BAR_Y = 20;
    private static final int MAX_RECENTS = 16;

    public PaintScreen(BlockPos anchor, Direction normal, Direction up, int widthBlocks, int heightBlocks,
                       int[] existingPixels, UUID decalId, int[] backgroundPixels) {
        super(Component.literal("Paint"));
        this.anchor = anchor;
        this.normal = normal;
        this.up = up;
        this.widthBlocks = widthBlocks;
        this.heightBlocks = heightBlocks;
        this.canvasW = widthBlocks * Decal.PX_PER_BLOCK;
        this.canvasH = heightBlocks * Decal.PX_PER_BLOCK;
        this.decalId = decalId != null ? decalId : UUID.randomUUID();
        this.hFlip = normal.getAxisDirection() == Direction.AxisDirection.NEGATIVE;
        this.backgroundPixels = backgroundPixels;

        if (existingPixels != null && existingPixels.length == canvasW * canvasH) {
            this.pixels = Arrays.copyOf(existingPixels, existingPixels.length);
        } else {
            this.pixels = new int[canvasW * canvasH];
        }
    }

    public PaintScreen(BlockPos anchor, Direction normal, Direction up, int widthBlocks, int heightBlocks,
                       int[] existingPixels, UUID decalId) {
        this(anchor, normal, up, widthBlocks, heightBlocks, existingPixels, decalId, null);
    }

    public PaintScreen(BlockPos anchor, Direction normal, Direction up, int widthBlocks, int heightBlocks) {
        this(anchor, normal, up, widthBlocks, heightBlocks, null, null, null);
    }

    /** Map pixel X to display X (flips for positive-normal faces so editor matches player view) */
    private int displayX(int px) {
        return hFlip ? canvasW - 1 - px : px;
    }

    /** Map raw screen X to pixel X (reverse of displayX) */
    private int screenToPixelX(int screenX) {
        int raw = (screenX - canvasX) / pixelSize;
        return hFlip ? canvasW - 1 - raw : raw;
    }

    @Override
    protected void init() {
        // Layout calculations
        int blockListWidth = 110;
        int canvasArea = this.width - blockListWidth - 20;
        int availableH = this.height - 80;
        pixelSize = Math.min(canvasArea / canvasW, availableH / canvasH);
        pixelSize = Math.max(pixelSize, 2);
        canvasX = 10;
        canvasY = 38;

        // Block list widget (right side)
        int listX = canvasX + canvasW * pixelSize + 12;
        int listWidth = this.width - listX - 4;
        blockList = new BlockListWidget(this.minecraft, listWidth, this.height - 70, canvasY, this::onBlockClicked);
        blockList.setX(listX);
        addRenderableWidget(blockList);

        // Populate with default blocks
        List<Block> defaults = new ArrayList<>(Arrays.asList(DEFAULT_BLOCKS));
        blockList.setBlocks(defaults);

        // Pre-populate block colors from defaults
        for (Block b : defaults) {
            addBlockColors(b);
        }

        // Tool buttons (below canvas)
        int toolY = canvasY + canvasH * pixelSize + 6;
        addRenderableWidget(Button.builder(Component.literal("Pencil"), b -> activeTool = PaintTool.PENCIL)
            .bounds(canvasX, toolY, 44, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Eraser"), b -> activeTool = PaintTool.ERASER)
            .bounds(canvasX + 48, toolY, 44, 16).build());
        addRenderableWidget(Button.builder(Component.literal("+"), b -> brushSize = Math.min(brushSize + 1, 4))
            .bounds(canvasX + 96, toolY, 16, 16).build());
        addRenderableWidget(Button.builder(Component.literal("-"), b -> brushSize = Math.max(brushSize - 1, 1))
            .bounds(canvasX + 114, toolY, 16, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Undo"), b -> undo())
            .bounds(canvasX + 140, toolY, 36, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Redo"), b -> redo())
            .bounds(canvasX + 180, toolY, 36, 16).build());

        // Done / Cancel (bottom)
        int bottomY = toolY + 22;
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> saveAndClose())
            .bounds(canvasX, bottomY, 50, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
            .bounds(canvasX + 56, bottomY, 50, 20).build());
    }

    private void onBlockClicked(Block block) {
        addBlockColors(block);
    }

    private void addBlockColors(Block block) {
        int[] colors = BlockColorCache.getColors(block);
        for (int c : colors) {
            if (!blockColors.contains(c)) {
                blockColors.add(c);
            }
        }
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        renderBackground(gfx, mouseX, mouseY, partialTick);

        // === Block Color Bar (top) ===
        int barX = canvasX;
        renderColorBar(gfx, blockColors, barX, COLOR_BAR_Y, mouseX, mouseY, "Block Colors");

        // === Recents Bar ===
        renderColorBar(gfx, recentColors, barX, RECENTS_BAR_Y, mouseX, mouseY, "Recent");

        // === Canvas background (block textures or checkerboard) ===
        for (int py = 0; py < canvasH; py++) {
            for (int px = 0; px < canvasW; px++) {
                int sx = canvasX + displayX(px) * pixelSize;
                int sy = canvasY + py * pixelSize;

                // Try background texture first
                if (backgroundPixels != null) {
                    int bgColor = backgroundPixels[py * canvasW + px];
                    if (((bgColor >> 24) & 0xFF) > 0) {
                        gfx.fill(sx, sy, sx + pixelSize, sy + pixelSize, bgColor);
                        continue;
                    }
                }
                // Fallback: checkerboard for transparent areas
                boolean checker = ((px / 2) + (py / 2)) % 2 == 0;
                gfx.fill(sx, sy, sx + pixelSize, sy + pixelSize, checker ? 0xFF666666 : 0xFF999999);
            }
        }

        // === Draw pixels ===
        for (int py = 0; py < canvasH; py++) {
            for (int px = 0; px < canvasW; px++) {
                int color = pixels[py * canvasW + px];
                if (((color >> 24) & 0xFF) <= 0) continue;
                int sx = canvasX + displayX(px) * pixelSize;
                int sy = canvasY + py * pixelSize;
                gfx.fill(sx, sy, sx + pixelSize, sy + pixelSize, color);
            }
        }

        // === Grid lines ===
        int gridColor = 0x40000000;
        for (int px = 0; px <= canvasW; px++) {
            int sx = canvasX + px * pixelSize;
            gfx.fill(sx, canvasY, sx + 1, canvasY + canvasH * pixelSize, gridColor);
        }
        for (int py = 0; py <= canvasH; py++) {
            int sy = canvasY + py * pixelSize;
            gfx.fill(canvasX, sy, canvasX + canvasW * pixelSize, sy + 1, gridColor);
        }

        // === Cursor highlight ===
        int cursorPx = screenToPixelX((int) mouseX);
        int cursorPy = (mouseY - canvasY) / pixelSize;
        if (cursorPx >= 0 && cursorPx < canvasW && cursorPy >= 0 && cursorPy < canvasH) {
            int radius = brushSize - 1;
            int minPx = Math.max(0, cursorPx - radius);
            int maxPx = Math.min(canvasW - 1, cursorPx + radius);
            int hx0 = canvasX + Math.min(displayX(minPx), displayX(maxPx)) * pixelSize;
            int hx1 = canvasX + (Math.max(displayX(minPx), displayX(maxPx)) + 1) * pixelSize;
            int hy0 = canvasY + Math.max(0, cursorPy - radius) * pixelSize;
            int hy1 = canvasY + (Math.min(canvasH - 1, cursorPy + radius) + 1) * pixelSize;
            gfx.renderOutline(hx0, hy0, hx1 - hx0, hy1 - hy0, 0xFFFFFFFF);
        }

        // === Selected color preview ===
        int previewX = canvasX + canvasW * pixelSize + 12;
        int previewY = this.height - 28;
        gfx.fill(previewX, previewY, previewX + 16, previewY + 16, selectedColor);
        gfx.renderOutline(previewX - 1, previewY - 1, 18, 18, 0xFFFFFFFF);
        gfx.drawString(this.font, activeTool.name() + " " + brushSize, previewX + 22, previewY + 4, 0xFFFFFF);

        super.render(gfx, mouseX, mouseY, partialTick);
    }

    private void renderColorBar(GuiGraphics gfx, List<Integer> colors, int x, int y, int mouseX, int mouseY, String label) {
        gfx.drawString(this.font, label, x, y + 2, 0xAAAAAA);
        int offsetX = x + this.font.width(label) + 6;
        for (int i = 0; i < colors.size(); i++) {
            int sx = offsetX + i * (COLOR_SWATCH_SIZE + 2);
            if (sx + COLOR_SWATCH_SIZE > this.width - 120) break; // don't overflow into block list
            int color = colors.get(i);
            gfx.fill(sx, y, sx + COLOR_SWATCH_SIZE, y + COLOR_SWATCH_SIZE, color);
            if (color == selectedColor) {
                gfx.renderOutline(sx - 1, y - 1, COLOR_SWATCH_SIZE + 2, COLOR_SWATCH_SIZE + 2, 0xFFFFFFFF);
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;

        // Check block color bar click
        if (clickColorBar(blockColors, (int) mouseX, (int) mouseY, canvasX, COLOR_BAR_Y)) return true;

        // Check recents bar click
        if (clickColorBar(recentColors, (int) mouseX, (int) mouseY, canvasX, RECENTS_BAR_Y)) return true;

        // Check canvas click
        int px = screenToPixelX((int) mouseX);
        int py = ((int) mouseY - canvasY) / pixelSize;
        if (px >= 0 && px < canvasW && py >= 0 && py < canvasH) {
            pushUndo();
            activeTool.draw(pixels, canvasW, canvasH, px, py, brushSize, selectedColor);
            painting = true;
            return true;
        }

        return false;
    }

    private boolean clickColorBar(List<Integer> colors, int mouseX, int mouseY, int barX, int barY) {
        String label = colors == blockColors ? "Block Colors" : "Recent";
        int offsetX = barX + this.font.width(label) + 6;
        if (mouseY >= barY && mouseY < barY + COLOR_SWATCH_SIZE) {
            for (int i = 0; i < colors.size(); i++) {
                int sx = offsetX + i * (COLOR_SWATCH_SIZE + 2);
                if (mouseX >= sx && mouseX < sx + COLOR_SWATCH_SIZE) {
                    selectedColor = colors.get(i);
                    addToRecents(selectedColor);
                    return true;
                }
            }
        }
        return false;
    }

    private void addToRecents(int color) {
        recentColors.remove(Integer.valueOf(color));
        recentColors.add(0, color);
        if (recentColors.size() > MAX_RECENTS) {
            recentColors.remove(recentColors.size() - 1);
        }
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (painting) {
            int px = screenToPixelX((int) mouseX);
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
        if (hasControlDown()) {
            if (keyCode == 90 && !hasShiftDown()) { undo(); return true; }
            if (keyCode == 90 && hasShiftDown()) { redo(); return true; }
            if (keyCode == 89) { redo(); return true; }
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
        DecalCreatePayload payload = new DecalCreatePayload(
            decalId, 0, anchor, normal, up,
            canvasW, canvasH, 1.0f, (byte) 0, pixels
        );
        PacketDistributor.sendToServer(payload);
        onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
