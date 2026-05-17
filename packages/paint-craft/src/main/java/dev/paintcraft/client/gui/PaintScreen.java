package dev.paintcraft.client.gui;

import dev.paintcraft.client.ClientBrushHandler;
import dev.paintcraft.client.EditorPrefs;
import dev.paintcraft.client.gui.widget.BlockListWidget;
import dev.paintcraft.client.gui.widget.ColorSquareWidget;
import dev.paintcraft.core.Decal;
import dev.paintcraft.network.DecalCreatePayload;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.*;

public class PaintScreen extends Screen {

    // Default starter blocks shown before the user adds their own
    private static final List<Block> DEFAULT_BLOCKS = List.of(
        Blocks.STONE, Blocks.OAK_PLANKS, Blocks.DIRT, Blocks.COBBLESTONE,
        Blocks.SAND, Blocks.BRICKS, Blocks.OAK_LOG, Blocks.IRON_BLOCK,
        Blocks.GOLD_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.DEEPSLATE,
        Blocks.TERRACOTTA, Blocks.WHITE_WOOL, Blocks.BLACK_WOOL
    );

    private final BlockPos anchor;
    private final Direction normal;
    private final Direction up;
    private final int widthBlocks;
    private final int heightBlocks;
    private final int canvasW;
    private final int canvasH;
    private final UUID decalId;
    private final boolean hFlip; // flip display horizontally for negative-normal faces
    private final Direction storedUp; // original up direction for saving (never changes)
    private final int displayRotation; // CW steps applied for display (reversed on save)

    private int[] pixels;
    private int[] backgroundPixels;
    private final List<int[]> undoStack = new ArrayList<>();
    private final List<int[]> redoStack = new ArrayList<>();

    // Color state
    private int selectedColor = 0xFF000000;
    private final List<Integer> recentColors = new ArrayList<>(); // last N colors used

    // Tools
    private PaintTool activeTool = PaintTool.PENCIL;
    private int brushSize = 1;
    private boolean painting = false;

    // Persistent block list -- survives re-init when returning from BlockSearchScreen
    private final List<Block> customBlocks = new ArrayList<>();

    // Layout
    private int canvasX, canvasY, pixelSize;
    private BlockListWidget blockList;
    private ColorSquareWidget colorSquare;
    private boolean colorSquareSoft = true; // persists mode across re-inits

    // Canvas texture (rendered as a single blit instead of per-pixel fills)
    private DynamicTexture canvasTexture;
    private ResourceLocation canvasTextureLoc;
    private boolean canvasDirty = true;

    // Color bar layout
    private static final int COLOR_SWATCH_SIZE = 12;
    private static final int RECENTS_BAR_Y = 4;
    private static final int MAX_RECENTS = 16;

    public PaintScreen(BlockPos anchor, Direction normal, Direction up, int widthBlocks, int heightBlocks,
                       int[] existingPixels, UUID decalId, int[] backgroundPixels,
                       Direction storedUp, int displayRotation) {
        super(Component.literal("Paint"));
        this.anchor = anchor;
        this.normal = normal;
        this.up = up;
        this.widthBlocks = widthBlocks;
        this.heightBlocks = heightBlocks;
        this.canvasW = widthBlocks * Decal.PX_PER_BLOCK;
        this.canvasH = heightBlocks * Decal.PX_PER_BLOCK;
        this.decalId = decalId != null ? decalId : UUID.randomUUID();
        this.hFlip = normal.getAxis() != Direction.Axis.Y
                     && normal.getAxisDirection() == Direction.AxisDirection.NEGATIVE;
        this.backgroundPixels = backgroundPixels;
        this.storedUp = storedUp;
        this.displayRotation = displayRotation;

        if (existingPixels != null && existingPixels.length == canvasW * canvasH) {
            this.pixels = Arrays.copyOf(existingPixels, existingPixels.length);
        } else {
            this.pixels = new int[canvasW * canvasH];
        }

        // Load persisted editor preferences
        EditorPrefs prefs = EditorPrefs.load();
        this.customBlocks.addAll(prefs.resolveBlocks(DEFAULT_BLOCKS));
        this.recentColors.addAll(prefs.recentColors);
        this.selectedColor = prefs.selectedColor;
        this.colorSquareSoft = prefs.softMode;
        this.brushSize = Math.max(1, Math.min(4, prefs.brushSize));
        try { this.activeTool = PaintTool.valueOf(prefs.activeTool); }
        catch (IllegalArgumentException ignored) {}

        // Create canvas texture (single GPU texture, blitted each frame instead of per-pixel fills)
        NativeImage canvasImage = new NativeImage(canvasW, canvasH, true);
        this.canvasTexture = new DynamicTexture(canvasImage);
        this.canvasTextureLoc = Minecraft.getInstance().getTextureManager()
            .register("paintcraft_canvas", this.canvasTexture);
    }

    public PaintScreen(BlockPos anchor, Direction normal, Direction up, int widthBlocks, int heightBlocks,
                       int[] existingPixels, UUID decalId) {
        this(anchor, normal, up, widthBlocks, heightBlocks, existingPixels, decalId, null, up, 0);
    }

    public PaintScreen(BlockPos anchor, Direction normal, Direction up, int widthBlocks, int heightBlocks) {
        this(anchor, normal, up, widthBlocks, heightBlocks, null, null, null, up, 0);
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
        // Recreate canvas texture if released (e.g. returning from BlockSearchScreen)
        if (canvasTextureLoc == null) {
            NativeImage canvasImage = new NativeImage(canvasW, canvasH, true);
            this.canvasTexture = new DynamicTexture(canvasImage);
            this.canvasTextureLoc = Minecraft.getInstance().getTextureManager()
                .register("paintcraft_canvas", this.canvasTexture);
            canvasDirty = true;
        }

        // Layout calculations
        // Reserve space: top (canvasY) + bottom (tool row 16 + gap 6 + done row 20 + gap 6 + margin 4 = 52)
        canvasX = 10;
        canvasY = 22;
        int rightColWidth = 120;
        int canvasArea = this.width - rightColWidth - 20;
        int bottomSpace = 52;
        int availableH = this.height - canvasY - bottomSpace;
        pixelSize = Math.min(canvasArea / canvasW, availableH / canvasH);
        pixelSize = Math.max(pixelSize, 2);

        // Right column layout (capped at 1/3 screen width, right-aligned)
        int colWidth = Math.min(this.width / 3, this.width - (canvasX + canvasW * pixelSize + 12) - 4);
        int colX = this.width - colWidth - 4;
        int curY = canvasY;

        // Color square (hue x lightness picker, 1:1 aspect ratio)
        // Always recreated here since removed() destroys it on screen transitions
        int squareH = colWidth;
        colorSquare = new ColorSquareWidget(colX, curY, colWidth, squareH, this::onColorPicked);
        colorSquare.setSmooth(colorSquareSoft);
        colorSquare.rebuild(customBlocks);
        addRenderableWidget(colorSquare);
        curY += squareH + 2;

        // Smooth/Raw toggle button
        addRenderableWidget(Button.builder(
            Component.literal(colorSquareSoft ? "Smooth" : "Raw"),
            b -> {
                colorSquare.toggleMode();
                colorSquareSoft = colorSquare.isSmooth();
                b.setMessage(Component.literal(colorSquareSoft ? "Smooth" : "Raw"));
            })
            .bounds(colX, curY, colWidth, 14).build());
        curY += 18;

        // "+ Add Block" button
        addRenderableWidget(Button.builder(Component.literal("+ Add Block"), b -> openBlockSearch())
            .bounds(colX, curY, colWidth, 16).build());
        curY += 20;

        // Block list widget
        blockList = new BlockListWidget(this.minecraft, colWidth, this.height - curY - 10,
                                        curY, this::onBlockClicked, this::onBlockRemoved);
        blockList.setX(colX);
        blockList.setBlocks(customBlocks);
        addRenderableWidget(blockList);

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

    private void onColorPicked(int color) {
        selectedColor = color;
        addToRecents(color);
    }

    private void openBlockSearch() {
        Set<Block> alreadyAdded = new HashSet<>(customBlocks);
        minecraft.setScreen(new BlockSearchScreen(this, this::onBlockAdded, alreadyAdded));
    }

    private void onBlockAdded(Block block) {
        if (customBlocks.contains(block)) return;
        customBlocks.add(block);
        if (blockList != null) blockList.addBlock(block);
        if (colorSquare != null) colorSquare.rebuild(customBlocks);
    }

    private void onBlockRemoved(Block block) {
        customBlocks.remove(block);
        if (blockList != null) blockList.setBlocks(customBlocks);
        if (colorSquare != null) colorSquare.rebuild(customBlocks);
    }

    // Clicking a block in the right-column list is now a no-op for color purposes
    // since the square already shows all blocks' colors. Keep the handler as it may
    // be useful in the future (e.g. highlighting that block's dots on the square).
    private void onBlockClicked(Block block) {
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        renderBackground(gfx, mouseX, mouseY, partialTick);

        // === Recents Bar (top, above canvas) ===
        renderColorBar(gfx, recentColors, canvasX, RECENTS_BAR_Y, mouseX, mouseY, "Recent");

        // === Canvas (single texture blit — updated only when pixels change) ===
        if (canvasTextureLoc == null) return;
        if (canvasDirty) {
            updateCanvasTexture();
            canvasDirty = false;
        }
        int renderW = canvasW * pixelSize;
        int renderH = canvasH * pixelSize;
        gfx.blit(canvasTextureLoc, canvasX, canvasY, renderW, renderH, 0f, 0f, canvasW, canvasH, canvasW, canvasH);

        // === Grid lines (cheap overlay) ===
        int gridColor = 0x40000000;
        for (int px = 0; px <= canvasW; px++) {
            int sx = canvasX + px * pixelSize;
            gfx.fill(sx, canvasY, sx + 1, canvasY + renderH, gridColor);
        }
        for (int py = 0; py <= canvasH; py++) {
            int sy = canvasY + py * pixelSize;
            gfx.fill(canvasX, sy, canvasX + renderW, sy + 1, gridColor);
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

    /**
     * Composites background + painted pixels into the canvas DynamicTexture.
     * Only called when canvasDirty is true (on draw/undo/redo).
     */
    private void updateCanvasTexture() {
        NativeImage img = canvasTexture.getPixels();
        if (img == null) return;

        for (int py = 0; py < canvasH; py++) {
            for (int screenPx = 0; screenPx < canvasW; screenPx++) {
                // Apply flip: texture is written in display order
                int dataPx = hFlip ? canvasW - 1 - screenPx : screenPx;
                int idx = py * canvasW + dataPx;

                // Painted pixel takes priority
                int pixelColor = pixels[idx];
                int color;
                if (((pixelColor >> 24) & 0xFF) > 0) {
                    color = pixelColor;
                } else if (backgroundPixels != null && ((backgroundPixels[idx] >> 24) & 0xFF) > 0) {
                    color = backgroundPixels[idx];
                } else {
                    // Checkerboard for transparent areas
                    boolean checker = ((dataPx / 2) + (py / 2)) % 2 == 0;
                    color = checker ? 0xFF666666 : 0xFF999999;
                }

                img.setPixelRGBA(screenPx, py, argbToAbgr(color));
            }
        }

        canvasTexture.upload();
    }

    private static int argbToAbgr(int argb) {
        int a = argb & 0xFF000000;
        int r = (argb >> 16) & 0xFF;
        int g = argb & 0x0000FF00;
        int b = (argb & 0xFF) << 16;
        return a | b | g | r;
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

        // Check recents bar click
        if (clickColorBar(recentColors, (int) mouseX, (int) mouseY, canvasX, RECENTS_BAR_Y)) return true;

        // Check canvas click
        int px = screenToPixelX((int) mouseX);
        int py = ((int) mouseY - canvasY) / pixelSize;
        if (px >= 0 && px < canvasW && py >= 0 && py < canvasH) {
            pushUndo();
            activeTool.draw(pixels, canvasW, canvasH, px, py, brushSize, selectedColor);
            canvasDirty = true;
            painting = true;
            return true;
        }

        return false;
    }

    private boolean clickColorBar(List<Integer> colors, int mouseX, int mouseY, int barX, int barY) {
        String label = "Recent";
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
                canvasDirty = true;
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
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // Scroll over canvas adjusts brush size
        int px = screenToPixelX((int) mouseX);
        int py = ((int) mouseY - canvasY) / pixelSize;
        if (px >= 0 && px < canvasW && py >= 0 && py < canvasH) {
            if (scrollY > 0) {
                brushSize = Math.min(brushSize + 1, 4);
            } else if (scrollY < 0) {
                brushSize = Math.max(brushSize - 1, 1);
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
        canvasDirty = true;
    }

    private void redo() {
        if (redoStack.isEmpty()) return;
        undoStack.add(Arrays.copyOf(pixels, pixels.length));
        pixels = redoStack.remove(redoStack.size() - 1);
        canvasDirty = true;
    }

    private void saveAndClose() {
        int[] savePixels = pixels;
        int saveW = canvasW, saveH = canvasH;
        if (displayRotation != 0) {
            // Reverse the display rotation to get back to stored orientation
            savePixels = ClientBrushHandler.rotatePixels(pixels, canvasW, canvasH, displayRotation);
            if (displayRotation % 2 == 1) {
                saveW = canvasH;
                saveH = canvasW;
            }
        }
        DecalCreatePayload payload = new DecalCreatePayload(
            decalId, 0, anchor, normal, storedUp,
            saveW, saveH, 1.0f, (byte) 0, savePixels
        );
        PacketDistributor.sendToServer(payload);
        onClose();
    }

    @Override
    public void removed() {
        super.removed();
        // Persist editor preferences
        EditorPrefs.from(customBlocks, recentColors, selectedColor,
                         colorSquareSoft, activeTool.name(), brushSize).save();
        if (colorSquare != null) {
            colorSquare.close();
            colorSquare = null;
        }
        if (canvasTextureLoc != null) {
            Minecraft.getInstance().getTextureManager().release(canvasTextureLoc);
            canvasTextureLoc = null;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
