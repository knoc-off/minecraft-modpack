package dev.paintcraft.client.gui;

import dev.paintcraft.ModConfig;
import dev.paintcraft.PaintCraft;
import dev.paintcraft.client.EditorPrefs;
import dev.paintcraft.client.gui.widget.BlockListWidget;
import dev.paintcraft.client.gui.widget.ColorSquareWidget;
import dev.paintcraft.core.ColorFormat;
import dev.paintcraft.core.Decal;
import dev.paintcraft.core.DisplayTransform;
import dev.paintcraft.core.FaceFrame;
import dev.paintcraft.core.PixelGrid;
import dev.paintcraft.network.DecalCreatePayload;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
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
    private final FaceFrame storedFrame;      // canonical stored orientation (used for saving)
    private final DisplayTransform transform; // maps stored↔display (handles hFlip + rotation)
    private final int canvasW;
    private final int canvasH;
    private final UUID decalId;

    private int[] canvas; // mutable working buffer in DISPLAY orientation
    private int[] backgroundPixels;
    private final List<int[]> undoStack = new ArrayList<>();
    private final List<int[]> redoStack = new ArrayList<>();

    // Color state
    private int selectedColor = 0xFF000000;
    private final List<Integer> recentColors = new ArrayList<>();
    private final LinkedHashSet<Integer> pinnedColors = new LinkedHashSet<>();

    // Tools
     private PaintTool activeTool = PaintTool.PENCIL;
    private int brushSize = 1;
    private boolean painting = false;

    // Persistent block list
    private final List<Block> customBlocks = new ArrayList<>();

    // Image import path input (Ctrl+I)
    private net.minecraft.client.gui.components.EditBox pathInput;

    // Layout
    private int canvasX, canvasY, pixelSize;
    private BlockListWidget blockList;
    private ColorSquareWidget colorSquare;
    private boolean colorSquareSoft = true;

    // Canvas texture
    private DynamicTexture canvasTexture;
    private ResourceLocation canvasTextureLoc;
    private boolean canvasDirty = true;

    // Color bar layout
    private static final int COLOR_SWATCH_SIZE = 12;
    private static final int RECENTS_BAR_Y = 4;
    private static final int MAX_RECENTS = 16;

    /**
     * Full constructor for re-editing existing decals (with display transform).
     */
    public PaintScreen(BlockPos anchor, FaceFrame storedFrame, FaceFrame displayFrame,
                       DisplayTransform transform, int widthBlocks, int heightBlocks,
                       int[] existingPixels, UUID decalId, int[] backgroundPixels) {
        super(Component.literal("Paint"));
        this.anchor = anchor;
        this.storedFrame = storedFrame;
        this.transform = transform;
        this.canvasW = widthBlocks * Decal.PX_PER_BLOCK;
        this.canvasH = heightBlocks * Decal.PX_PER_BLOCK;
        this.decalId = decalId != null ? decalId : UUID.randomUUID();
        this.backgroundPixels = backgroundPixels;

        if (existingPixels != null && existingPixels.length == canvasW * canvasH) {
            this.canvas = Arrays.copyOf(existingPixels, existingPixels.length);
        } else {
            this.canvas = new int[canvasW * canvasH];
        }

        loadPrefs();
        initCanvasTexture();
    }

    /**
     * Convenience constructor for new decals (no rotation, identity transform).
     */
    public PaintScreen(BlockPos anchor, FaceFrame frame, int widthBlocks, int heightBlocks,
                       int[] backgroundPixels) {
        this(anchor, frame, frame, DisplayTransform.forEditor(frame, frame),
             widthBlocks, heightBlocks, null, null, backgroundPixels);
    }

    private void loadPrefs() {
        EditorPrefs prefs = EditorPrefs.load();
        this.customBlocks.addAll(prefs.resolveBlocks(DEFAULT_BLOCKS));
        this.recentColors.addAll(prefs.recentColors);
        this.selectedColor = prefs.selectedColor;
        this.colorSquareSoft = prefs.softMode;
        this.brushSize = Math.max(1, Math.min(16, prefs.brushSize));
        try { this.activeTool = PaintTool.valueOf(prefs.activeTool); }
        catch (IllegalArgumentException ignored) {}
        if (prefs.pinnedColors != null) {
            this.pinnedColors.addAll(prefs.pinnedColors);
        }
    }

    private void initCanvasTexture() {
        NativeImage canvasImage = new NativeImage(canvasW, canvasH, true);
        this.canvasTexture = new DynamicTexture(canvasImage);
        this.canvasTextureLoc = Minecraft.getInstance().getTextureManager()
            .register("paintcraft_canvas", this.canvasTexture);
    }

    // === Coordinate mapping ===
    // Canvas is in DISPLAY orientation. No transform needed for canvas access.
    // Background is in STORED orientation and needs transform.toDataX for access.

    /** Display pixel X (identity — canvas is display-oriented). */
    private int displayX(int px) {
        return px;
    }

    /** Map raw screen X to canvas pixel X (display space — no flip). */
    private int screenToPixelX(int screenX) {
        return (screenX - canvasX) / pixelSize;
    }

    @Override
    protected void init() {
        // Recreate canvas texture if released (e.g. returning from BlockSearchScreen)
        if (canvasTextureLoc == null) {
            initCanvasTexture();
            canvasDirty = true;
        }

        // Layout calculations — canvas fills left side, right column has all controls
        canvasX = 10;
        canvasY = 22;
        int rightColWidth = 120;
        int canvasArea = this.width - rightColWidth - 20;
        int availableH = this.height - canvasY - 6;
        pixelSize = Math.min(canvasArea / canvasW, availableH / canvasH);
        pixelSize = Math.max(pixelSize, 2);

        // Right column layout
        int colWidth = Math.min(this.width / 3, this.width - (canvasX + canvasW * pixelSize + 12) - 4);
        colWidth = Math.max(colWidth, 100);
        int colX = this.width - colWidth - 4;
        int curY = canvasY;

        // Color square
        int squareH = colWidth;
        colorSquare = new ColorSquareWidget(colX, curY, colWidth, squareH, this::onColorPicked);
        colorSquare.setSmooth(colorSquareSoft);
        colorSquare.rebuild(customBlocks);
        addRenderableWidget(colorSquare);
        curY += squareH + 2;

        // Full/Focused toggle button
        addRenderableWidget(Button.builder(
            Component.literal(colorSquareSoft ? "Full" : "Focused"),
            b -> {
                colorSquare.toggleMode();
                colorSquareSoft = colorSquare.isSmooth();
                b.setMessage(Component.literal(colorSquareSoft ? "Full" : "Focused"));
            })
            .bounds(colX, curY, colWidth, 14).build());
        curY += 18;

        // "+ Add Block" button
        addRenderableWidget(Button.builder(Component.literal("+ Add Block"), b -> openBlockSearch())
            .bounds(colX, curY, colWidth, 16).build());
        curY += 20;

        // Block list widget — fill space but leave room for tool/action buttons (62px)
        int blockListH = Math.max(30, this.height - curY - 68);
        blockList = new BlockListWidget(this.minecraft, colWidth, blockListH,
                                        curY, this::onBlockClicked, this::onBlockRemoved);
        blockList.setX(colX);
        blockList.setBlocks(customBlocks);
        addRenderableWidget(blockList);
        curY += blockListH + 4;

        // Tool buttons (in right column, below block list)
        int halfCol = colWidth / 2;
        addRenderableWidget(Button.builder(Component.literal("Pencil"), b -> activeTool = PaintTool.PENCIL)
            .bounds(colX, curY, halfCol - 1, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Eraser"), b -> activeTool = PaintTool.ERASER)
            .bounds(colX + halfCol + 1, curY, halfCol - 1, 16).build());
        curY += 20;

        int btnW = colWidth / 4;
        addRenderableWidget(Button.builder(Component.literal("+"), b -> brushSize = Math.min(brushSize + 1, 16))
            .bounds(colX, curY, btnW - 1, 16).build());
        addRenderableWidget(Button.builder(Component.literal("-"), b -> brushSize = Math.max(brushSize - 1, 1))
            .bounds(colX + btnW, curY, btnW - 1, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Undo"), b -> undo())
            .bounds(colX + btnW * 2, curY, btnW - 1, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Redo"), b -> redo())
            .bounds(colX + btnW * 3, curY, btnW - 1, 16).build());
        curY += 20;

        // Done / Discard (bottom of right column)
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> saveAndClose())
            .bounds(colX, curY, halfCol - 1, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Discard"), b -> discardAndClose())
            .bounds(colX + halfCol + 1, curY, halfCol - 1, 20).build());
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

    private void onBlockClicked(Block block) {
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        renderBackground(gfx, mouseX, mouseY, partialTick);

        // === Recents Bar (top, above canvas) ===
        renderColorBar(gfx, recentColors, canvasX, RECENTS_BAR_Y, mouseX, mouseY, "Recent");

        // === Canvas (single texture blit) ===
        if (canvasTextureLoc == null) return;
        if (canvasDirty) {
            updateCanvasTexture();
            canvasDirty = false;
        }
        int renderW = canvasW * pixelSize;
        int renderH = canvasH * pixelSize;
        gfx.blit(canvasTextureLoc, canvasX, canvasY, renderW, renderH, 0f, 0f, canvasW, canvasH, canvasW, canvasH);

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
     * Uses DisplayTransform for coordinate mapping.
     */
    private void updateCanvasTexture() {
        NativeImage img = canvasTexture.getPixels();
        if (img == null) return;

        for (int py = 0; py < canvasH; py++) {
            for (int screenPx = 0; screenPx < canvasW; screenPx++) {
                // Canvas is in display orientation — index directly by screen position
                int canvasIdx = py * canvasW + screenPx;
                // Background is in stored orientation — map via transform
                int bgIdx = py * canvasW + transform.toDataX(screenPx, canvasW);

                int pixelColor = canvas[canvasIdx];
                int color;
                if (ColorFormat.isOpaque(pixelColor)) {
                    color = pixelColor;
                } else if (backgroundPixels != null && ColorFormat.isOpaque(backgroundPixels[bgIdx])) {
                    color = backgroundPixels[bgIdx];
                } else {
                    boolean checker = ((screenPx / 2) + (py / 2)) % 2 == 0;
                    color = checker ? 0xFF666666 : 0xFF999999;
                }

                img.setPixelRGBA(screenPx, py, ColorFormat.argbToAbgr(color));
            }
        }

        canvasTexture.upload();
    }

    private void renderColorBar(GuiGraphics gfx, List<Integer> colors, int x, int y, int mouseX, int mouseY, String label) {
        gfx.drawString(this.font, label, x, y + 2, 0xAAAAAA);
        int offsetX = x + this.font.width(label) + 6;
        int maxX = this.width - 120;

        // Render pinned colors first with gold underline
        int i = 0;
        for (int pinColor : pinnedColors) {
            int sx = offsetX + i * (COLOR_SWATCH_SIZE + 2);
            if (sx + COLOR_SWATCH_SIZE > maxX) break;
            gfx.fill(sx, y, sx + COLOR_SWATCH_SIZE, y + COLOR_SWATCH_SIZE, pinColor);
            // Gold underline for pinned
            gfx.fill(sx, y + COLOR_SWATCH_SIZE, sx + COLOR_SWATCH_SIZE, y + COLOR_SWATCH_SIZE + 2, 0xFFFFAA00);
            if (pinColor == selectedColor) {
                gfx.renderOutline(sx - 1, y - 1, COLOR_SWATCH_SIZE + 2, COLOR_SWATCH_SIZE + 2, 0xFFFFFFFF);
            }
            i++;
        }

        // Render unpinned recent colors after
        for (int color : colors) {
            if (pinnedColors.contains(color)) continue; // already shown
            int sx = offsetX + i * (COLOR_SWATCH_SIZE + 2);
            if (sx + COLOR_SWATCH_SIZE > maxX) break;
            gfx.fill(sx, y, sx + COLOR_SWATCH_SIZE, y + COLOR_SWATCH_SIZE, color);
            if (color == selectedColor) {
                gfx.renderOutline(sx - 1, y - 1, COLOR_SWATCH_SIZE + 2, COLOR_SWATCH_SIZE + 2, 0xFFFFFFFF);
            }
            i++;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;

        // Color bar: left-click selects, right-click toggles pin
        if (clickColorBar((int) mouseX, (int) mouseY, canvasX, RECENTS_BAR_Y, button)) return true;

        int px = screenToPixelX((int) mouseX);
        int py = ((int) mouseY - canvasY) / pixelSize;
        if (px >= 0 && px < canvasW && py >= 0 && py < canvasH) {
            pushUndo();
            activeTool.draw(canvas, canvasW, canvasH, px, py, brushSize, selectedColor);
            canvasDirty = true;
            painting = true;
            return true;
        }

        return false;
    }

    private boolean clickColorBar(int mouseX, int mouseY, int barX, int barY, int button) {
        String label = "Recent";
        int offsetX = barX + this.font.width(label) + 6;
        if (mouseY < barY || mouseY >= barY + COLOR_SWATCH_SIZE + 2) return false;

        // Build ordered list: pinned first, then unpinned recents
        List<Integer> ordered = new ArrayList<>();
        ordered.addAll(pinnedColors);
        for (int c : recentColors) {
            if (!pinnedColors.contains(c)) ordered.add(c);
        }

        for (int i = 0; i < ordered.size(); i++) {
            int sx = offsetX + i * (COLOR_SWATCH_SIZE + 2);
            if (sx + COLOR_SWATCH_SIZE > this.width - 120) break;
            if (mouseX >= sx && mouseX < sx + COLOR_SWATCH_SIZE) {
                int color = ordered.get(i);
                if (button == 1) {
                    // Right-click: toggle pin
                    if (pinnedColors.contains(color)) {
                        pinnedColors.remove(color);
                    } else {
                        pinnedColors.add(color);
                    }
                } else {
                    // Left-click: select color
                    selectedColor = color;
                    addToRecents(color);
                }
                return true;
            }
        }
        return false;
    }

    private void addToRecents(int color) {
        recentColors.remove(Integer.valueOf(color));
        recentColors.add(0, color);
        // Trim excess, but never evict pinned colors
        while (recentColors.size() > MAX_RECENTS) {
            int last = recentColors.get(recentColors.size() - 1);
            if (pinnedColors.contains(last)) break; // don't evict pinned
            recentColors.remove(recentColors.size() - 1);
        }
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (painting) {
            int px = screenToPixelX((int) mouseX);
            int py = ((int) mouseY - canvasY) / pixelSize;
            if (px >= 0 && px < canvasW && py >= 0 && py < canvasH) {
                activeTool.draw(canvas, canvasW, canvasH, px, py, brushSize, selectedColor);
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
        int px = screenToPixelX((int) mouseX);
        int py = ((int) mouseY - canvasY) / pixelSize;
        if (px >= 0 && px < canvasW && py >= 0 && py < canvasH) {
            if (scrollY > 0) {
                brushSize = Math.min(brushSize + 1, 16);
            } else if (scrollY < 0) {
                brushSize = Math.max(brushSize - 1, 1);
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Path input field active — handle its keys first
        if (pathInput != null) {
            if (keyCode == 256) { // Escape
                removeWidget(pathInput);
                pathInput = null;
                return true;
            }
            if (keyCode == 257 || keyCode == 335) { // Enter / KP_Enter
                loadImageFromPath(pathInput.getValue().trim());
                removeWidget(pathInput);
                pathInput = null;
                return true;
            }
            return pathInput.keyPressed(keyCode, scanCode, modifiers) || super.keyPressed(keyCode, scanCode, modifiers);
        }

        if (hasControlDown()) {
            if (keyCode == 90 && !hasShiftDown()) { undo(); return true; }
            if (keyCode == 90 && hasShiftDown()) { redo(); return true; }
            if (keyCode == 89) { redo(); return true; }
            if (keyCode == 73) { showPathInput(); return true; } // Ctrl+I
            if (keyCode == 86) { pasteFromClipboard(); return true; } // Ctrl+V
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void pushUndo() {
        undoStack.add(Arrays.copyOf(canvas, canvas.length));
        if (undoStack.size() > 20) undoStack.remove(0);
        redoStack.clear();
    }

    private void undo() {
        if (undoStack.isEmpty()) return;
        redoStack.add(Arrays.copyOf(canvas, canvas.length));
        canvas = undoStack.remove(undoStack.size() - 1);
        canvasDirty = true;
    }

    private void redo() {
        if (redoStack.isEmpty()) return;
        undoStack.add(Arrays.copyOf(canvas, canvas.length));
        canvas = redoStack.remove(redoStack.size() - 1);
        canvasDirty = true;
    }

    private void loadAndImport(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (!(name.endsWith(".png") || name.endsWith(".jpg") ||
              name.endsWith(".jpeg") || name.endsWith(".bmp"))) {
            PaintCraft.LOGGER.warn("Unsupported image format: {}", name);
            return;
        }
        if (!java.nio.file.Files.isRegularFile(file)) {
            PaintCraft.LOGGER.warn("File not found: {}", file);
            return;
        }
        PaintCraft.LOGGER.info("Importing image: {}", file);
        try (NativeImage img = NativeImage.read(new FileInputStream(file.toFile()))) {
            pushUndo();
            importImage(img);
            canvasDirty = true;
            PaintCraft.LOGGER.info("Image imported ({}x{})", img.getWidth(), img.getHeight());
        } catch (Exception e) {
            PaintCraft.LOGGER.error("Failed to import image: {}", file, e);
        }
    }

    private void importImage(NativeImage img) {
        int srcW = img.getWidth();
        int srcH = img.getHeight();

        float scale = Math.min((float) canvasW / srcW, (float) canvasH / srcH);
        int dstW = Math.max(1, (int) (srcW * scale));
        int dstH = Math.max(1, (int) (srcH * scale));

        int offsetX = (canvasW - dstW) / 2;
        int offsetY = (canvasH - dstH) / 2;

        Arrays.fill(canvas, 0);

        for (int y = 0; y < dstH; y++) {
            for (int x = 0; x < dstW; x++) {
                int srcX = Math.min((int) (x / scale), srcW - 1);
                int srcY = Math.min((int) (y / scale), srcH - 1);
                int abgr = img.getPixelRGBA(srcX, srcY);
                int idx = (offsetY + y) * canvasW + (offsetX + x);
                if (idx >= 0 && idx < canvas.length) {
                    canvas[idx] = ColorFormat.abgrToArgb(abgr);
                }
            }
        }
    }

    private void showPathInput() {
        if (pathInput != null) return; // already showing
        int inputW = Math.min(this.width - 20, 400);
        int inputX = (this.width - inputW) / 2;
        int inputY = this.height - 30;
        pathInput = new net.minecraft.client.gui.components.EditBox(
            this.font, inputX, inputY, inputW, 16, Component.literal("File path"));
        pathInput.setMaxLength(512);
        pathInput.setHint(Component.literal("Paste image path here, press Enter"));
        addRenderableWidget(pathInput);
        setFocused(pathInput);
    }

    private void loadImageFromPath(String pathStr) {
        if (pathStr.isEmpty()) return;
        if (pathStr.startsWith("\"") && pathStr.endsWith("\"")) {
            pathStr = pathStr.substring(1, pathStr.length() - 1);
        }
        if (pathStr.startsWith("file://")) {
            pathStr = pathStr.substring(7);
        }
        loadAndImport(Path.of(pathStr));
    }

    private void pasteFromClipboard() {
        long window = Minecraft.getInstance().getWindow().getWindow();
        String text = org.lwjgl.glfw.GLFW.glfwGetClipboardString(window);
        if (text == null || text.isEmpty()) return;
        text = text.trim();
        String lower = text.toLowerCase();
        if (lower.endsWith(".png") || lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") || lower.endsWith(".bmp")) {
            loadImageFromPath(text);
        }
    }

    @Override
    public void onClose() {
        if (ModConfig.CONFIG.autoSaveOnExit.get()) {
            saveAndClose();
        } else {
            super.onClose();
        }
    }

    private void discardAndClose() {
        super.onClose();
    }

    private void saveAndClose() {
        // Transform display pixels back to stored orientation
        PixelGrid displayGrid = PixelGrid.wrap(canvasW, canvasH, canvas);
        PixelGrid stored = transform.toStored(displayGrid);

        DecalCreatePayload payload = new DecalCreatePayload(
            decalId, 0, anchor, storedFrame.normal(), storedFrame.up(),
            stored.width(), stored.height(), 1.0f, (byte) 0, stored.data()
        );
        PacketDistributor.sendToServer(payload);
        super.onClose();
    }

    @Override
    public void removed() {
        super.removed();
        EditorPrefs.from(customBlocks, recentColors, pinnedColors, selectedColor,
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
