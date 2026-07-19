package dev.paintcraft.client.gui;


import dev.paintcraft.ModConfig;
import dev.paintcraft.PaintCraft;
import dev.paintcraft.client.BackgroundCaptureDebug;
import dev.paintcraft.client.EditorPrefs;
import dev.paintcraft.client.gui.widget.BlockListWidget;
import dev.paintcraft.client.gui.widget.ColorSquareWidget;
import dev.paintcraft.core.ColorFormat;
import dev.paintcraft.core.Decal;
import dev.paintcraft.core.DisplayTransform;
import dev.paintcraft.core.FaceFrame;
import dev.paintcraft.core.PixelGrid;
import dev.paintcraft.core.cost.PaintCost;
import dev.paintcraft.network.DecalCreatePayload;
import dev.paintcraft.network.PasteChargeRequestPayload;
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

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

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
    private int[] backgroundRawPixels; // unshaded copy for the color picker
    private final float depth;
    private final List<int[]> undoStack = new ArrayList<>();
    private final List<int[]> redoStack = new ArrayList<>();

    // Paste confirmation: after a paste the editor is locked until the player accepts
    // (paying the dye cost) or cancels (reverting). All other input is blocked meanwhile.
    private boolean pendingPaste = false;
    private boolean awaitingCharge = false; // charge request sent, waiting for server reply
    private int pasteRequestId = 0;
    private static int pasteRequestSeq = 0;
    private java.util.List<net.minecraft.world.item.ItemStack> pendingPasteCost = java.util.List.of();
    private boolean pendingPasteAffordable = false;
    private Button pasteAcceptButton;
    private Button pasteRejectButton;

    // Color state
    private int selectedColor = 0xFF000000;
    private final List<Integer> recentColors = new ArrayList<>();
    private final LinkedHashSet<Integer> pinnedColors = new LinkedHashSet<>();

    // Per-tool settings (size, opacity), persisted.
    public static final class ToolSettings {
        public int size;
        public int opacity;   // 1..255
        public ToolSettings(int size, int opacity) {
            this.size = size; this.opacity = opacity;
        }
    }
    private final EnumMap<PaintTool, ToolSettings> toolSettings = new EnumMap<>(PaintTool.class);

    // Mouse-button bindings and the tool whose options are shown / adjusted by scroll.
    private PaintTool mouse1Tool = PaintTool.BRUSH;
    private PaintTool mouse2Tool = PaintTool.ERASER;
    private PaintTool focusedTool = PaintTool.BRUSH;

    // Active stroke — middle click = eyedrop (never a stroke).
    private int paintingButton = -1;        // -1 idle, else the mouse button driving the stroke
    private PaintTool activeStrokeTool = null;
    private int[] strokeBase = null;        // canvas snapshot at stroke start (blend/line base)
    private byte[] strokeCoverage = null;   // per-texel max coverage this stroke (0..255)
    private int lineStartX, lineStartY;     // LINE gesture anchor

    // Left toolbar / options layout (computed in init). Two option groups: M1 tool and M2 tool.
    private int leftBarX, leftBarW, toolsY;
    private int[] opt1SizeRect = new int[4], opt1OpacityRect = new int[4];
    private int[] opt2SizeRect = new int[4], opt2OpacityRect = new int[4];
    private int draggingSlider = 0;         // 0 none, 1 size, 2 opacity
    private PaintTool sliderTool = null;    // tool whose slider is being dragged

    // Editor UX settings (scroll inversion, eyedropper opacity mode, remappable keybinds).
    private final EditorSettings settings = new EditorSettings();

    // Persistent block list
    private final List<Block> customBlocks = new ArrayList<>();

    // Layout
    private int canvasX, canvasY, pixelSize;
    // Canvas viewport: the fixed on-screen rect the (possibly larger) canvas is drawn into.
    private int viewW, viewH;
    // Pan offset in screen pixels (how far the canvas content is scrolled under the viewport).
    private int panX, panY, panMaxX, panMaxY;
    private static final int SCROLLBAR = 6;
    private int draggingBar = 0;   // 0 none, 1 vertical, 2 horizontal
    private double barGrabOffset;  // cursor-to-thumb-start offset captured on grab
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
                       float depth, int[] existingPixels, UUID decalId, int[] backgroundPixels,
                       int[] backgroundRawPixels) {
        super(Component.literal("Paint"));
        this.anchor = anchor;
        this.storedFrame = storedFrame;
        this.transform = transform;
        this.canvasW = widthBlocks * Decal.PX_PER_BLOCK;
        this.canvasH = heightBlocks * Decal.PX_PER_BLOCK;
        this.decalId = decalId != null ? decalId : UUID.randomUUID();
        this.depth = depth;
        this.backgroundPixels = backgroundPixels;
        this.backgroundRawPixels = backgroundRawPixels;

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
                       int[] backgroundPixels, int[] backgroundRawPixels) {
        this(anchor, frame, frame, DisplayTransform.between(frame, frame),
             widthBlocks, heightBlocks, Decal.MAX_DEPTH, null, null, backgroundPixels,
             backgroundRawPixels);
    }

    private void loadPrefs() {
        initToolSettings();
        EditorPrefs prefs = EditorPrefs.load();
        this.customBlocks.addAll(prefs.resolveBlocks(DEFAULT_BLOCKS));
        this.recentColors.addAll(prefs.recentColors);
        this.selectedColor = prefs.selectedColor;
        this.colorSquareSoft = prefs.softMode;
        if (prefs.pinnedColors != null) {
            this.pinnedColors.addAll(prefs.pinnedColors);
        }
        prefs.applyTools(toolSettings);
        prefs.applySettings(settings);
        this.mouse1Tool = prefs.resolveTool(prefs.mouse1Tool, PaintTool.BRUSH);
        this.mouse2Tool = prefs.resolveTool(prefs.mouse2Tool, PaintTool.ERASER);
        this.focusedTool = this.mouse1Tool;
    }

    private void initCanvasTexture() {
        NativeImage canvasImage = new NativeImage(canvasW, canvasH, true);
        this.canvasTexture = new DynamicTexture(canvasImage);
        this.canvasTextureLoc = Minecraft.getInstance().getTextureManager()
            .register("paintcraft_canvas", this.canvasTexture);
    }

    /** Map raw screen X to canvas pixel X (display space — no flip), honouring pan. */
    private int screenToPixelX(int screenX) {
        return Math.floorDiv(screenX - canvasX + panX, pixelSize);
    }

    /** Map raw screen Y to canvas pixel Y, honouring pan. */
    private int screenToPixelY(int screenY) {
        return Math.floorDiv(screenY - canvasY + panY, pixelSize);
    }

    /** Pan the viewport by whole blocks (arrow-key panning). */
    private void panBy(int dx, int dy) {
        int step = Math.max(pixelSize, Decal.PX_PER_BLOCK / 2 * pixelSize);
        panX = Math.max(0, Math.min(panX + dx * step, panMaxX));
        panY = Math.max(0, Math.min(panY + dy * step, panMaxY));
    }

    @Override
    protected void init() {
        // Recreate canvas texture if released (e.g. returning from BlockSearchScreen)
        if (canvasTextureLoc == null) {
            initCanvasTexture();
            canvasDirty = true;
        }

        // Layout calculations — left toolbar, then canvas, then the right control column.
        canvasY = 22;
        leftBarX = 4;
        leftBarW = 48;
        toolsY = canvasY;
        canvasX = leftBarX + leftBarW + 6;

        // Right control column is a fixed width; the canvas viewport fills the space between the
        // left toolbar and it. The canvas is scaled to fit the viewport (min 2px/texel), and pans
        // when it's still larger than the viewport at that floor.
        int colWidth = 120;
        int colX = this.width - colWidth - 4;
        viewW = Math.max(64, colX - canvasX - 10);
        viewH = Math.max(64, this.height - canvasY - 6);
        pixelSize = Math.min(viewW / canvasW, viewH / canvasH);
        pixelSize = Math.max(pixelSize, 2);
        // Reserve space for scrollbars when an axis overflows, then recompute pan maxima.
        int renderW = canvasW * pixelSize;
        int renderH = canvasH * pixelSize;
        boolean needV = renderH > viewH;
        boolean needH = renderW > viewW;
        panMaxX = Math.max(0, renderW - (viewW - (needV ? SCROLLBAR : 0)));
        panMaxY = Math.max(0, renderH - (viewH - (needH ? SCROLLBAR : 0)));
        panX = Math.max(0, Math.min(panX, panMaxX));
        panY = Math.max(0, Math.min(panY, panMaxY));

        // Tool option groups below the tool buttons — one per mouse tool, each with size + opacity
        // sliders. Group 1 (M1) then group 2 (M2). Sliders sit 9px below their label.
        int optY = toolsY + PaintTool.values().length * 18 + 12;
        opt1SizeRect    = new int[] { leftBarX, optY + 20,  leftBarW, 7 };
        opt1OpacityRect = new int[] { leftBarX, optY + 44,  leftBarW, 7 };
        opt2SizeRect    = new int[] { leftBarX, optY + 78,  leftBarW, 7 };
        opt2OpacityRect = new int[] { leftBarX, optY + 102, leftBarW, 7 };

        // Right column layout
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

        // Block list widget — fill remaining space, leaving room for action rows
        boolean hasShelf = net.neoforged.fml.ModList.get().isLoaded("assetshelf");
        int actionRowsH = hasShelf ? 74 : 54;
        int blockListH = Math.max(30, this.height - curY - actionRowsH);
        blockList = new BlockListWidget(this.minecraft, colWidth, blockListH,
                                        curY, this::onBlockClicked, this::onBlockRemoved);
        blockList.setX(colX);
        blockList.setBlocks(customBlocks);
        addRenderableWidget(blockList);
        curY += blockListH + 4;

        // Action row 1: [undo] [redo] [discard]
        int thirdCol = colWidth / 3;
        addRenderableWidget(Button.builder(Component.literal("undo"), b -> undo())
            .bounds(colX, curY, thirdCol - 1, 16).build());
        addRenderableWidget(Button.builder(Component.literal("redo"), b -> redo())
            .bounds(colX + thirdCol, curY, thirdCol - 1, 16).build());
        addRenderableWidget(Button.builder(Component.literal("discard"), b -> discardAndClose())
            .bounds(colX + thirdCol * 2, curY, colWidth - thirdCol * 2, 16).build());
        curY += 20;

        // Action row 2: [shelve] (only if asset-shelf loaded)
        if (hasShelf) {
            addRenderableWidget(Button.builder(Component.literal("shelve"), b -> saveToLibrary())
                .bounds(colX, curY, colWidth, 16).build());
            curY += 20;
        }

        // Action row 3: [settings]
        addRenderableWidget(Button.builder(Component.literal("settings"), b -> openSettings())
            .bounds(colX, curY, colWidth, 16).build());

        // Paste accept/reject overlay buttons — centered in the canvas viewport (always on-screen),
        // hidden until a paste is pending. Added last so they render on top of everything else.
        int pbCx = canvasX + viewW / 2;
        int pbY = canvasY + viewH / 2;
        pasteAcceptButton = Button.builder(Component.literal("\u2714 Accept"), b -> acceptPaste())
            .bounds(pbCx - 64, pbY, 62, 18).build();
        pasteRejectButton = Button.builder(Component.literal("\u2718 Cancel"), b -> rejectPaste())
            .bounds(pbCx + 2, pbY, 62, 18).build();
        addRenderableWidget(pasteAcceptButton);
        addRenderableWidget(pasteRejectButton);
        updatePasteButtons();
    }

    private void onColorPicked(int color) {
        selectedColor = color;
        addToRecents(color);
    }

    private void openBlockSearch() {
        Set<Block> alreadyAdded = new HashSet<>(customBlocks);
        minecraft.setScreen(new BlockSearchScreen(this, this::onBlockAdded, alreadyAdded));
    }

    private void openSettings() {
        minecraft.setScreen(new EditorSettingsScreen(this, settings));
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
        // Reserved for future use (e.g., pick color from block)
    }

    @Override
    protected void renderBlurredBackground(float partialTick) {
        // Skip vanilla menu blur; renderMenuBackground() still draws the dark tint.
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
        // Clip the canvas (and cursor highlight) to the viewport so large canvases don't spill.
        gfx.enableScissor(canvasX, canvasY, canvasX + viewW, canvasY + viewH);
        gfx.blit(canvasTextureLoc, canvasX - panX, canvasY - panY, renderW, renderH,
                 0f, 0f, canvasW, canvasH, canvasW, canvasH);

        // === Cursor highlight ===
        // During a stroke show only the acting tool. Idle: M1's footprint (white, priority) plus
        // M2's footprint (thin pink) when the two differ, so both radii are visible at once.
        int cursorPx = screenToPixelX((int) mouseX);
        int cursorPy = screenToPixelY((int) mouseY);
        if (cursorPx >= 0 && cursorPx < canvasW && cursorPy >= 0 && cursorPy < canvasH) {
            if (activeStrokeTool != null) {
                drawBrushOutline(gfx, cursorPx, cursorPy, activeStrokeTool, 0xFFFFFFFF);
            } else {
                if (mouse2Tool != mouse1Tool && sizeOf(mouse2Tool) != sizeOf(mouse1Tool)) {
                    drawBrushOutline(gfx, cursorPx, cursorPy, mouse2Tool, 0xC0E0708A);
                }
                drawBrushOutline(gfx, cursorPx, cursorPy, mouse1Tool, 0xFFFFFFFF);
            }
        }
        gfx.disableScissor();
        renderScrollbars(gfx, mouseX, mouseY);

        // === Left toolbar + tool options ===
        renderLeftBar(gfx, mouseX, mouseY);

        // === Selected color preview + readouts (bottom-left) ===
        int previewX = leftBarX;
        int previewY = this.height - 22;
        // Checkerboard behind the swatch so partial opacity reads correctly.
        for (int cy = 0; cy < 16; cy++) {
            for (int cx = 0; cx < 16; cx++) {
                boolean checker = ((cx / 4) + (cy / 4)) % 2 == 0;
                gfx.fill(previewX + cx, previewY + cy, previewX + cx + 1, previewY + cy + 1,
                    checker ? 0xFF999999 : 0xFF666666);
            }
        }
        gfx.fill(previewX, previewY, previewX + 16, previewY + 16, paintColorFor(focusedTool));
        gfx.renderOutline(previewX - 1, previewY - 1, 18, 18, 0xFFFFFFFF);
        gfx.drawString(this.font, focusedTool.displayName, previewX + 22, previewY + 4, 0xFFFFFF);
        if (Decal.SNAP > 1) {
            String mode = subPixelMode() ? "sub-px" : "16-grid";
            gfx.drawString(this.font, mode, previewX + 22, previewY - 8,
                subPixelMode() ? 0xFFD4A858 : 0xAAAAAA);
        }

        // Paste lock: dim the screen and prompt for accept/cancel. Anchored to the viewport
        // centre so it stays on-screen regardless of canvas size / pan.
        if (pendingPaste) {
            gfx.fill(0, 0, this.width, this.height, 0x99000000);
            String prompt = awaitingCharge
                ? "Charging\u2026"
                : (pendingPasteAffordable
                    ? "Accept the pasted image (pays dye) or cancel"
                    : "\u00A7cNot enough dye \u00A7r\u2014 cancel this paste");
            int cx = canvasX + viewW / 2;
            int py = canvasY + viewH / 2;
            drawCentered(gfx, prompt, cx, py - 24, 0xFFFFFFFF);
            if (!awaitingCharge) {
                drawCentered(gfx, "\u00A77[Enter] accept  \u00A77[Esc] cancel", cx, py - 12, 0xFFBBBBBB);
            }
        }

        super.render(gfx, mouseX, mouseY, partialTick);
    }

    private void drawCentered(GuiGraphics gfx, String text, int cx, int y, int color) {
        gfx.drawString(this.font, text, cx - this.font.width(text) / 2, y, color);
    }

    /** Draw scrollbars along the viewport edges when the canvas overflows an axis. */
    private void renderScrollbars(GuiGraphics gfx, int mouseX, int mouseY) {
        int renderW = canvasW * pixelSize;
        int renderH = canvasH * pixelSize;
        if (panMaxY > 0) { // vertical bar on the right edge
            int trackX = canvasX + viewW - SCROLLBAR;
            int trackH = viewH - (panMaxX > 0 ? SCROLLBAR : 0);
            gfx.fill(trackX, canvasY, trackX + SCROLLBAR, canvasY + trackH, 0x88000000);
            int thumbH = Math.max(12, (int) ((long) trackH * (viewH - (panMaxX > 0 ? SCROLLBAR : 0)) / renderH));
            int thumbY = canvasY + (int) ((long) (trackH - thumbH) * panY / panMaxY);
            boolean hov = mouseX >= trackX && mouseX < trackX + SCROLLBAR
                       && mouseY >= thumbY && mouseY < thumbY + thumbH;
            gfx.fill(trackX + 1, thumbY, trackX + SCROLLBAR, thumbY + thumbH,
                (draggingBar == 1 || hov) ? 0xFFD4A858 : 0xFFAAAAAA);
        }
        if (panMaxX > 0) { // horizontal bar on the bottom edge
            int trackY = canvasY + viewH - SCROLLBAR;
            int trackW = viewW - (panMaxY > 0 ? SCROLLBAR : 0);
            gfx.fill(canvasX, trackY, canvasX + trackW, trackY + SCROLLBAR, 0x88000000);
            int thumbW = Math.max(12, (int) ((long) trackW * (viewW - (panMaxY > 0 ? SCROLLBAR : 0)) / renderW));
            int thumbX = canvasX + (int) ((long) (trackW - thumbW) * panX / panMaxX);
            boolean hov = mouseY >= trackY && mouseY < trackY + SCROLLBAR
                       && mouseX >= thumbX && mouseX < thumbX + thumbW;
            gfx.fill(thumbX, trackY + 1, thumbX + thumbW, trackY + SCROLLBAR,
                (draggingBar == 2 || hov) ? 0xFFD4A858 : 0xFFAAAAAA);
        }
    }

    private int vTrackH() { return viewH - (panMaxX > 0 ? SCROLLBAR : 0); }
    private int hTrackW() { return viewW - (panMaxY > 0 ? SCROLLBAR : 0); }
    private int vThumbH() { return Math.max(12, (int) ((long) vTrackH() * vTrackH() / (canvasH * pixelSize))); }
    private int hThumbW() { return Math.max(12, (int) ((long) hTrackW() * hTrackW() / (canvasW * pixelSize))); }

    /** Begin dragging a scrollbar thumb (or page-jump on track click). Returns true if consumed. */
    private boolean clickScrollbar(double mx, double my) {
        if (panMaxY > 0) {
            int trackX = canvasX + viewW - SCROLLBAR, trackH = vTrackH();
            if (mx >= trackX && mx < trackX + SCROLLBAR && my >= canvasY && my < canvasY + trackH) {
                int thumbH = vThumbH();
                int thumbY = canvasY + (int) ((long) (trackH - thumbH) * panY / panMaxY);
                draggingBar = 1;
                barGrabOffset = (my >= thumbY && my < thumbY + thumbH) ? my - thumbY : thumbH / 2.0;
                dragScrollbar(mx, my);
                return true;
            }
        }
        if (panMaxX > 0) {
            int trackY = canvasY + viewH - SCROLLBAR, trackW = hTrackW();
            if (my >= trackY && my < trackY + SCROLLBAR && mx >= canvasX && mx < canvasX + trackW) {
                int thumbW = hThumbW();
                int thumbX = canvasX + (int) ((long) (trackW - thumbW) * panX / panMaxX);
                draggingBar = 2;
                barGrabOffset = (mx >= thumbX && mx < thumbX + thumbW) ? mx - thumbX : thumbW / 2.0;
                dragScrollbar(mx, my);
                return true;
            }
        }
        return false;
    }

    /** Update pan from the current cursor position while dragging a scrollbar thumb. */
    private void dragScrollbar(double mx, double my) {
        if (draggingBar == 1) {
            int trackH = vTrackH(), thumbH = vThumbH();
            int travel = Math.max(1, trackH - thumbH);
            double t = (my - barGrabOffset - canvasY) / travel;
            panY = (int) Math.max(0, Math.min(Math.round(t * panMaxY), panMaxY));
        } else if (draggingBar == 2) {
            int trackW = hTrackW(), thumbW = hThumbW();
            int travel = Math.max(1, trackW - thumbW);
            double t = (mx - barGrabOffset - canvasX) / travel;
            panX = (int) Math.max(0, Math.min(Math.round(t * panMaxX), panMaxX));
        }
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
                // Opaque base: the background block pixel if present, else the checker.
                int base;
                if (backgroundPixels != null && ColorFormat.isOpaque(backgroundPixels[bgIdx])) {
                    base = backgroundPixels[bgIdx];
                } else {
                    boolean checker = ((screenPx / 2) + (py / 2)) % 2 == 0;
                    base = checker ? 0xFF666666 : 0xFF999999;
                }
                // Alpha-blend the painted pixel over the base so translucency is WYSIWYG.
                int color = (pixelColor >>> 24) == 0 ? base : ColorFormat.alphaOver(pixelColor, base);

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

    /** Draw the left toolbar (tool buttons with L/R badges) and both mouse tools' option groups. */
    private void renderLeftBar(GuiGraphics gfx, int mouseX, int mouseY) {
        PaintTool[] tools = PaintTool.values();
        for (int i = 0; i < tools.length; i++) {
            PaintTool t = tools[i];
            int bx = leftBarX, by = toolsY + i * 18;
            boolean hover = mouseX >= bx && mouseX < bx + leftBarW && mouseY >= by && mouseY < by + 16;
            boolean focused = t == focusedTool;
            gfx.fill(bx, by, bx + leftBarW, by + 16, focused ? 0xFF4A4A4A : (hover ? 0xFF333333 : 0xFF1E1E1E));
            gfx.renderOutline(bx, by, leftBarW, 16, focused ? 0xFFD4A858 : 0xFF000000);
            int gw = this.font.width(t.glyph);
            gfx.drawString(this.font, t.glyph, bx + leftBarW / 2 - gw / 2, by + 4, 0xFFFFFFFF);
            if (t == mouse1Tool) gfx.drawString(this.font, "1", bx + 2, by + 4, 0xFF6FD36F);
            if (t == mouse2Tool) gfx.drawString(this.font, "2", bx + leftBarW - 7, by + 4, 0xFFE0708A);
        }

        // Option group for each mouse tool (directly editable without changing bindings).
        renderOptionGroup(gfx, "1", 0xFF6FD36F, mouse1Tool, opt1SizeRect, opt1OpacityRect);
        if (mouse2Tool != mouse1Tool) {
            renderOptionGroup(gfx, "2", 0xFFE0708A, mouse2Tool, opt2SizeRect, opt2OpacityRect);
        }
    }

    /** Draw one tool's option group: a header (badge + name) plus size and opacity sliders. */
    private void renderOptionGroup(GuiGraphics gfx, String badge, int badgeColor, PaintTool tool,
                                   int[] sizeRect, int[] opacityRect) {
        int maxSize = ModConfig.CONFIG.maxBrushSize.get();
        ToolSettings s = ts(tool);
        int headerY = sizeRect[1] - 19;
        gfx.drawString(this.font, badge, sizeRect[0], headerY, badgeColor);
        gfx.drawString(this.font, tool.displayName, sizeRect[0] + 8, headerY, 0xFFFFFFFF);
        if (tool == focusedTool) {
            gfx.renderOutline(sizeRect[0] - 1, headerY - 1, leftBarW + 1, 9, 0xFFD4A858);
        }
        gfx.drawString(this.font, "Size " + s.size, sizeRect[0], sizeRect[1] - 9, 0xFFAAAAAA);
        drawSlider(gfx, sizeRect, (s.size - 1) / (float) Math.max(1, maxSize - 1));
        int pct = Math.round(s.opacity / 255f * 100f);
        gfx.drawString(this.font, "Opac " + pct + "%", opacityRect[0], opacityRect[1] - 9, 0xFFAAAAAA);
        drawSlider(gfx, opacityRect, s.opacity / 255f);
    }

    private void drawSlider(GuiGraphics gfx, int[] r, float frac) {
        frac = Math.max(0f, Math.min(1f, frac));
        gfx.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], 0xFF555555);
        gfx.fill(r[0], r[1], r[0] + Math.round(r[2] * frac), r[1] + r[3], 0xFF4A90D9);
        int hx = r[0] + Math.round((r[2] - 1) * frac);
        gfx.fill(hx, r[1] - 1, hx + 1, r[1] + r[3] + 1, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (pendingPaste) {
            // Locked: only the accept/reject buttons respond; swallow everything else.
            if (!awaitingCharge) {
                if (pasteAcceptButton.mouseClicked(mouseX, mouseY, button)) return true;
                if (pasteRejectButton.mouseClicked(mouseX, mouseY, button)) return true;
            }
            return true;
        }
        if (super.mouseClicked(mouseX, mouseY, button)) return true;

        // Color bar: left-click selects, right-click toggles pin
        if (clickColorBar((int) mouseX, (int) mouseY, canvasX, RECENTS_BAR_Y, button)) return true;

        // Left toolbar: tool buttons + option sliders/toggle.
        if (clickLeftBar((int) mouseX, (int) mouseY, button)) return true;

        // Scrollbars (only present when the canvas overflows the viewport).
        if (button == 0 && clickScrollbar(mouseX, mouseY)) return true;

        int px = screenToPixelX((int) mouseX);
        int py = screenToPixelY((int) mouseY);
        if (px >= 0 && px < canvasW && py >= 0 && py < canvasH) {
            if (button == 2) {
                eyedrop(px, py); // middle click — eyedropper (always)
            } else {
                PaintTool tool = button == 0 ? mouse1Tool : button == 1 ? mouse2Tool : null;
                if (tool != null) beginStroke(tool, px, py, button);
            }
            return true;
        }

        return false;
    }

    /** Handle a click in the left toolbar/options column. Returns true if consumed. */
    private boolean clickLeftBar(int mx, int my, int button) {
        // Tool buttons (left-click binds Mouse1, right-click binds Mouse2; either focuses).
        PaintTool[] tools = PaintTool.values();
        for (int i = 0; i < tools.length; i++) {
            int by = toolsY + i * 18;
            if (mx >= leftBarX && mx < leftBarX + leftBarW && my >= by && my < by + 16) {
                if (button == 0) mouse1Tool = tools[i];
                else if (button == 1) mouse2Tool = tools[i];
                else return true;
                focusedTool = tools[i];
                return true;
            }
        }
        // Option-group sliders. Clicking a slider focuses that tool (for scroll/keyboard) without
        // changing mouse bindings. Group 1 = M1 tool; group 2 = M2 tool (if distinct).
        if (clickOptionGroup(mx, my, button, mouse1Tool, opt1SizeRect, opt1OpacityRect)) return true;
        if (mouse2Tool != mouse1Tool
                && clickOptionGroup(mx, my, button, mouse2Tool, opt2SizeRect, opt2OpacityRect)) return true;
        return false;
    }

    /** Hit-test one option group's sliders. Returns true if consumed. */
    private boolean clickOptionGroup(int mx, int my, int button, PaintTool tool,
                                     int[] sizeRect, int[] opacityRect) {
        if (inRect(mx, my, sizeRect)) {
            focusedTool = tool;
            draggingSlider = 1;
            sliderTool = tool;
            setSizeFromX(mx, tool, sizeRect);
            return true;
        }
        if (inRect(mx, my, opacityRect)) {
            focusedTool = tool;
            if (button == 1) { applyOpacity(tool, 255); }
            else { draggingSlider = 2; sliderTool = tool; setOpacityFromX(mx, tool, opacityRect); }
            return true;
        }
        return false;
    }

    private static boolean inRect(int mx, int my, int[] r) {
        return mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    private void setSizeFromX(int mx, PaintTool tool, int[] rect) {
        int max = ModConfig.CONFIG.maxBrushSize.get();
        float frac = (mx - rect[0]) / (float) rect[2];
        frac = Math.max(0f, Math.min(1f, frac));
        applySize(tool, 1 + Math.round(frac * (max - 1)));
    }

    private void setOpacityFromX(int mx, PaintTool tool, int[] rect) {
        float frac = (mx - rect[0]) / (float) rect[2];
        frac = Math.max(0f, Math.min(1f, frac));
        int v = Math.round(frac * 255f);
        if (v >= 250) v = 255; // snap to fully opaque near the top
        applyOpacity(tool, v);
    }

    /** Set a tool's size (clamped). When unified-size is on, applies to every tool. */
    private void applySize(PaintTool tool, int size) {
        size = Math.max(1, Math.min(ModConfig.CONFIG.maxBrushSize.get(), size));
        if (settings.unifiedSize) {
            for (ToolSettings s : toolSettings.values()) s.size = size;
        } else {
            ts(tool).size = size;
        }
    }

    /** Set a tool's opacity (clamped 1..255). When unified-opacity is on, applies to every tool. */
    private void applyOpacity(PaintTool tool, int opacity) {
        opacity = Math.max(1, Math.min(255, opacity));
        if (settings.unifiedOpacity) {
            for (ToolSettings s : toolSettings.values()) s.opacity = opacity;
        } else {
            ts(tool).opacity = opacity;
        }
    }

    /** Swatch order shown in the top bar: pinned (insertion order) then unpinned recents. */
    private List<Integer> orderedSwatches() {
        List<Integer> ordered = new ArrayList<>(pinnedColors);
        for (int c : recentColors) {
            if (!pinnedColors.contains(c)) ordered.add(c);
        }
        return ordered;
    }

    private boolean clickColorBar(int mouseX, int mouseY, int barX, int barY, int button) {
        String label = "Recent";
        int offsetX = barX + this.font.width(label) + 6;
        if (mouseY < barY || mouseY >= barY + COLOR_SWATCH_SIZE + 2) return false;

        List<Integer> ordered = orderedSwatches();

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

    private void eyedrop(int px, int py) {
        int canvasIdx = py * canvasW + px;
        int paintColor = canvas[canvasIdx];
        if (ColorFormat.isOpaque(paintColor)) {
            if (settings.eyedropperInheritOpacity) applyOpacity(focusedTool, paintColor >>> 24);
            selectedColor = 0xFF000000 | (paintColor & 0x00FFFFFF);
            addToRecents(selectedColor);
            return;
        }
        // Sample the unshaded background so the picked color is independent of the editor's
        // depth shading (falls back to the shaded buffer if no raw copy is available).
        int[] bgSource = backgroundRawPixels != null ? backgroundRawPixels : backgroundPixels;
        if (bgSource != null) {
            int bgIdx = py * canvasW + transform.toDataX(px, canvasW);
            if (bgIdx >= 0 && bgIdx < bgSource.length) {
                int bgColor = bgSource[bgIdx];
                if (ColorFormat.isOpaque(bgColor)) {
                    if (settings.eyedropperInheritOpacity) applyOpacity(focusedTool, bgColor >>> 24);
                    selectedColor = 0xFF000000 | (bgColor & 0x00FFFFFF);
                    addToRecents(selectedColor);
                }
            }
        }
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (pendingPaste) return true;
        if (draggingBar != 0) { dragScrollbar(mouseX, mouseY); return true; }
        if (draggingSlider != 0 && sliderTool != null) {
            boolean g1 = sliderTool == mouse1Tool;
            int[] sizeR = g1 ? opt1SizeRect : opt2SizeRect;
            int[] opacR = g1 ? opt1OpacityRect : opt2OpacityRect;
            if (draggingSlider == 1) setSizeFromX((int) mouseX, sliderTool, sizeR);
            else setOpacityFromX((int) mouseX, sliderTool, opacR);
            return true;
        }
        if (paintingButton >= 0) {
            int px = screenToPixelX((int) mouseX);
            int py = screenToPixelY((int) mouseY);
            if (px >= 0 && px < canvasW && py >= 0 && py < canvasH) {
                continueStroke(px, py);
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingSlider = 0;
        sliderTool = null;
        draggingBar = 0;
        if (paintingButton >= 0) endStroke();
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** True when free sub-pixel (32px) editing is active; otherwise edits snap to the 16px grid. */
    private boolean subPixelMode() {
        return Decal.SNAP <= 1 || hasShiftDown();
    }

    // ── Per-tool settings ────────────────────────────────────────────

    private void initToolSettings() {
        if (!toolSettings.isEmpty()) return;
        toolSettings.put(PaintTool.PENCIL, new ToolSettings(1, 255));
        toolSettings.put(PaintTool.BRUSH,  new ToolSettings(2, 200));
        toolSettings.put(PaintTool.ERASER, new ToolSettings(2, 255));
        toolSettings.put(PaintTool.FILL,   new ToolSettings(1, 255));
        toolSettings.put(PaintTool.LINE,   new ToolSettings(1, 255));
    }

    private ToolSettings ts(PaintTool t) { return toolSettings.get(t); }
    private int sizeOf(PaintTool t)     { return ts(t).size; }
    private int opacityOf(PaintTool t)  { return ts(t).opacity; }

    /** Effective paint color for a tool: selected RGB combined with that tool's opacity. */
    private int paintColorFor(PaintTool t) {
        return (opacityOf(t) << 24) | (selectedColor & 0x00FFFFFF);
    }

    // ── Stroke lifecycle ─────────────────────────────────────────────

    /** Begin a stroke (or perform a click action) with {@code tool} at texel (px, py). */
    private void beginStroke(PaintTool tool, int px, int py, int button) {
        pushUndo();
        if (tool.isClickAction()) { // FILL — one-shot, no drag state
            floodFill(px, py, tool);
            canvasDirty = true;
            return;
        }
        paintingButton = button;
        activeStrokeTool = tool;
        strokeBase = Arrays.copyOf(canvas, canvas.length);
        strokeCoverage = new byte[canvas.length];
        if (tool.isLine()) {
            lineStartX = px; lineStartY = py;
            stampFootprint(px, py, tool); // seed the anchor dab
        } else {
            stampFootprint(px, py, tool);
        }
        canvasDirty = true;
    }

    /** Continue the active stroke to texel (px, py). */
    private void continueStroke(int px, int py) {
        if (activeStrokeTool == null) return;
        if (activeStrokeTool.isLine()) {
            // Rebuild the whole line from the pre-stroke base each drag so it stays straight.
            System.arraycopy(strokeBase, 0, canvas, 0, canvas.length);
            Arrays.fill(strokeCoverage, (byte) 0);
            stampLine(lineStartX, lineStartY, px, py, activeStrokeTool);
        } else {
            stampFootprint(px, py, activeStrokeTool);
        }
        canvasDirty = true;
    }

    private void endStroke() {
        paintingButton = -1;
        activeStrokeTool = null;
        strokeBase = null;
        strokeCoverage = null;
    }

    // ── Pixel operations ─────────────────────────────────────────────

    /**
     * Stamp the brush footprint of {@code tool} centred on texel (px, py). In snapped mode
     * (default) each logical pixel fills a {@link Decal#SNAP}² texel block; Shift = free 32px.
     */
    private void stampFootprint(int px, int py, PaintTool tool) {
        int color = paintColorFor(tool);
        int radius = sizeOf(tool) - 1;
        if (subPixelMode()) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    int fx = px + dx, fy = py + dy;
                    if (fx >= 0 && fx < canvasW && fy >= 0 && fy < canvasH) {
                        dab(fy * canvasW + fx, tool, color);
                    }
                }
            }
            return;
        }
        int snap = Decal.SNAP;
        int lx = px / snap, ly = py / snap;
        for (int dly = -radius; dly <= radius; dly++) {
            for (int dlx = -radius; dlx <= radius; dlx++) {
                int bx0 = (lx + dlx) * snap, by0 = (ly + dly) * snap;
                for (int ty = 0; ty < snap; ty++) {
                    for (int tx = 0; tx < snap; tx++) {
                        int fx = bx0 + tx, fy = by0 + ty;
                        if (fx >= 0 && fx < canvasW && fy >= 0 && fy < canvasH) {
                            dab(fy * canvasW + fx, tool, color);
                        }
                    }
                }
            }
        }
    }

    /** Apply a single tool dab to one texel, honouring the per-stroke coverage buffer. */
    private void dab(int idx, PaintTool tool, int color) {
        switch (tool) {
            case PENCIL -> canvas[idx] = color; // hard replace
            case BRUSH, LINE -> {
                int op = opacityOf(tool);
                if (op >= 255) { canvas[idx] = color; break; }
                // Opacity build-up: a stroke tops out at its opacity by capping coverage,
                // then compositing over the pre-stroke base (never accumulates within a stroke).
                int cov = Math.max(strokeCoverage[idx] & 0xFF, op);
                strokeCoverage[idx] = (byte) cov;
                canvas[idx] = ColorFormat.alphaOver((cov << 24) | (color & 0x00FFFFFF), strokeBase[idx]);
            }
            case ERASER -> {
                // Opacity governs erase strength: 100% clears fully, lower values reduce alpha.
                int strength = opacityOf(PaintTool.ERASER);
                int cov = Math.max(strokeCoverage[idx] & 0xFF, strength);
                strokeCoverage[idx] = (byte) cov;
                int base = strokeBase[idx];
                int rem = (base >>> 24) * (255 - cov) / 255; // reduce alpha, keep RGB
                canvas[idx] = rem == 0 ? 0 : (rem << 24) | (base & 0x00FFFFFF);
            }
            default -> { }
        }
    }

    /** Draw a straight line of footprints between two texels (Bresenham). */
    private void stampLine(int x0, int y0, int x1, int y1, PaintTool tool) {
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            stampFootprint(x0, y0, tool);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    /** Flood-fill the contiguous region matching the clicked texel's exact color. */
    private void floodFill(int px, int py, PaintTool tool) {
        int start = py * canvasW + px;
        int target = canvas[start];
        int color = paintColorFor(tool);
        int op = opacityOf(tool);
        // Nothing to do if the region is already the resulting color.
        int result = op >= 255 ? color : ColorFormat.alphaOver(color, target);
        if (result == target) return;

        boolean[] visited = new boolean[canvas.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        visited[start] = true;
        while (!queue.isEmpty()) {
            int idx = queue.poll();
            int old = canvas[idx];
            canvas[idx] = op >= 255 ? color : ColorFormat.alphaOver(color, old);
            int x = idx % canvasW, y = idx / canvasW;
            if (x > 0            && !visited[idx - 1]      && canvas[idx - 1]      == target) { visited[idx - 1] = true;      queue.add(idx - 1); }
            if (x < canvasW - 1  && !visited[idx + 1]      && canvas[idx + 1]      == target) { visited[idx + 1] = true;      queue.add(idx + 1); }
            if (y > 0            && !visited[idx - canvasW] && canvas[idx - canvasW] == target) { visited[idx - canvasW] = true; queue.add(idx - canvasW); }
            if (y < canvasH - 1  && !visited[idx + canvasW] && canvas[idx + canvasW] == target) { visited[idx + canvasW] = true; queue.add(idx + canvasW); }
        }
    }

    /** Inclusive texel bounds {x0,x1,y0,y1} covered by the focused tool at (px,py), clamped. */
    private int[] brushTexelBounds(int px, int py, PaintTool tool) {
        int radius = sizeOf(tool) - 1;
        int x0, x1, y0, y1;
        if (subPixelMode()) {
            x0 = px - radius; x1 = px + radius;
            y0 = py - radius; y1 = py + radius;
        } else {
            int snap = Decal.SNAP;
            int lx = px / snap, ly = py / snap;
            x0 = (lx - radius) * snap;       x1 = (lx + radius) * snap + snap - 1;
            y0 = (ly - radius) * snap;       y1 = (ly + radius) * snap + snap - 1;
        }
        return new int[] {
            Math.max(0, x0), Math.min(canvasW - 1, x1),
            Math.max(0, y0), Math.min(canvasH - 1, y1)
        };
    }

    /** Draw a tool's brush footprint outline at a texel, in screen space (pan-adjusted). */
    private void drawBrushOutline(GuiGraphics gfx, int px, int py, PaintTool tool, int color) {
        int[] b = brushTexelBounds(px, py, tool);
        int hx0 = canvasX - panX + b[0] * pixelSize;
        int hx1 = canvasX - panX + (b[1] + 1) * pixelSize;
        int hy0 = canvasY - panY + b[2] * pixelSize;
        int hy1 = canvasY - panY + (b[3] + 1) * pixelSize;
        gfx.renderOutline(hx0, hy0, hx1 - hx0, hy1 - hy0, color);
    }

    /** True when the configured scroll-opacity modifier key is currently held. */
    private boolean scrollOpacityModifierDown() {
        return switch (settings.scrollOpacityModifier) {
            case ALT -> hasAltDown();
            case SHIFT -> hasShiftDown();
            case CTRL -> hasControlDown();
        };
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {        if (pendingPaste) return true;
        int px = screenToPixelX((int) mouseX);
        int py = screenToPixelY((int) mouseY);
        if (px >= 0 && px < canvasW && py >= 0 && py < canvasH) {
            ToolSettings s = ts(focusedTool);
            double dir = settings.invertScroll ? -scrollY : scrollY;
            if (scrollOpacityModifierDown()) {
                // Modifier+scroll adjusts the focused tool's opacity in 16 discrete levels (~6%..100%).
                int level = Math.round(s.opacity / 255f * 16f);
                level = Math.max(1, Math.min(16, level + (dir > 0 ? 1 : -1)));
                applyOpacity(focusedTool, Math.round(level / 16f * 255f));
            } else if (dir > 0) {
                applySize(focusedTool, s.size + 1);
            } else if (dir < 0) {
                applySize(focusedTool, s.size - 1);
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (pendingPaste) {
            // Locked to the paste dialog: Enter accepts (if affordable), Esc cancels.
            if (keyCode == 257 || keyCode == 335) { acceptPaste(); return true; } // Enter / numpad Enter
            if (keyCode == 256) { rejectPaste(); return true; }                    // Escape
            return true; // swallow everything else
        }
        if (hasControlDown()) {
            if (keyCode == 90 && !hasShiftDown()) { undo(); return true; }
            if (keyCode == 90 && hasShiftDown()) { redo(); return true; }
            if (keyCode == 89) { redo(); return true; }
            if (keyCode == 86) { pasteFromClipboard(Decal.SNAP > 1 && !hasShiftDown()); return true; } // Ctrl+V snapped, Ctrl+Shift+V full
            if (keyCode == 68) { copyDebug(); return true; } // Ctrl+D
            if (keyCode == 80) { dumpCanvasPng(); return true; } // Ctrl+Shift+P — dump PNGs
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        // Remappable single-key editor actions (take priority over the number-key swatch shortcut).
        EditorAction action = settings.actionForKey(keyCode);
        if (action != null) { runAction(action); return true; }
        // Number keys 1-9 select the corresponding top-bar swatch (pinned first, then recents).
        if (keyCode >= 49 && keyCode <= 57) {
            List<Integer> ordered = orderedSwatches();
            int idx = keyCode - 49;
            if (idx < ordered.size()) { selectedColor = ordered.get(idx); return true; }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void runAction(EditorAction action) {
        switch (action) {
            case SELECT_PENCIL -> selectTool(PaintTool.PENCIL);
            case SELECT_BRUSH  -> selectTool(PaintTool.BRUSH);
            case SELECT_ERASER -> selectTool(PaintTool.ERASER);
            case SELECT_FILL   -> selectTool(PaintTool.FILL);
            case SELECT_LINE   -> selectTool(PaintTool.LINE);
            case INCREASE_SIZE -> applySize(focusedTool, sizeOf(focusedTool) + 1);
            case DECREASE_SIZE -> applySize(focusedTool, sizeOf(focusedTool) - 1);
            case INCREASE_OPACITY -> adjustOpacityLevel(focusedTool, 1);
            case DECREASE_OPACITY -> adjustOpacityLevel(focusedTool, -1);
            case SWAP_MOUSE_TOOLS -> {
                PaintTool tmp = mouse1Tool; mouse1Tool = mouse2Tool; mouse2Tool = tmp;
                focusedTool = mouse1Tool;
            }
            case PAN_UP    -> panBy(0, -1);
            case PAN_DOWN  -> panBy(0, 1);
            case PAN_LEFT  -> panBy(-1, 0);
            case PAN_RIGHT -> panBy(1, 0);
            case M2_INCREASE_SIZE -> applySize(mouse2Tool, sizeOf(mouse2Tool) + 1);
            case M2_DECREASE_SIZE -> applySize(mouse2Tool, sizeOf(mouse2Tool) - 1);
        }
    }

    /** Bind a tool to Mouse1 and focus it (mirrors left-clicking its toolbar icon). */
    private void selectTool(PaintTool t) {
        mouse1Tool = t;
        focusedTool = t;
    }

    private void adjustOpacityLevel(PaintTool tool, int delta) {
        int level = Math.round(opacityOf(tool) / 255f * 16f);
        level = Math.max(1, Math.min(16, level + delta));
        applyOpacity(tool, Math.round(level / 16f * 255f));
    }

    /**
     * Diagnostic: write the exact on-screen canvas (post-composite) and the raw captured
     * background to PNGs in the game directory, so we can inspect the true pixels rather
     * than reason about them. Triggered by Ctrl+Shift+P.
     */
    private void dumpCanvasPng() {
        Path dir = Minecraft.getInstance().gameDirectory.toPath();
        try {
            // 1) Exactly what's blitted to screen (background + paint composited)
            canvasTexture.getPixels().writeToFile(dir.resolve("paintcraft-canvas-dump.png"));

            // 2) Raw captured background, pre-composite (stored orientation)
            if (backgroundPixels != null) {
                try (NativeImage bg = new NativeImage(canvasW, canvasH, false)) {
                    for (int y = 0; y < canvasH; y++) {
                        for (int x = 0; x < canvasW; x++) {
                            bg.setPixelRGBA(x, y,
                                ColorFormat.argbToAbgr(backgroundPixels[y * canvasW + x]));
                        }
                    }
                    bg.writeToFile(dir.resolve("paintcraft-background-dump.png"));
                }
            }
            String msg = "Dumped canvas PNGs (" + canvasW + "x" + canvasH + ") to " + dir;
            PaintCraft.LOGGER.info("[dump] {}", msg);
            Minecraft.getInstance().player.displayClientMessage(Component.literal(msg), false);

            // 3) The winning face sprite's getOriginalImage() — the suspected stale HD source.
            dumpAnchorSprite(dir);
        } catch (Exception e) {
            PaintCraft.LOGGER.error("[dump] failed", e);
        }
    }

    /**
     * Dumps the raw sprite image that BackgroundCapture samples for the anchor block's face,
     * plus its dimensions, so we can see whether getOriginalImage() is a stale HD texture
     * while the world renders vanilla from the GPU atlas.
     */
    private void dumpAnchorSprite(Path dir) {
        try {
            var mc = Minecraft.getInstance();
            var state = mc.level.getBlockState(anchor);
            var model = mc.getBlockRenderer().getBlockModel(state);
            var random = net.minecraft.util.RandomSource.createNewThreadLocalInstance();
            var quads = model.getQuads(state, storedFrame.normal(), random,
                net.neoforged.neoforge.client.model.data.ModelData.EMPTY, null);
            if (quads.isEmpty()) {
                PaintCraft.LOGGER.info("[dump] anchor {} face {} has no quads", anchor, storedFrame.normal());
                return;
            }
            var quadSprite = quads.get(0).getSprite();
            var name = quadSprite.contents().name();
            var atlas = mc.getModelManager().getAtlas(
                net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS);
            var live = atlas.getSprite(name);
            var lc = live.contents();
            NativeImage orig = lc.getOriginalImage();
            String info = String.format(
                "sprite=%s logical=%dx%d origImage=%s atlasUV=[%.4f,%.4f]x[%.4f,%.4f]",
                name, lc.width(), lc.height(),
                orig == null ? "null" : (orig.getWidth() + "x" + orig.getHeight()),
                live.getU0(), live.getU1(), live.getV0(), live.getV1());
            PaintCraft.LOGGER.info("[dump] anchor sprite: {}", info);
            Minecraft.getInstance().player.displayClientMessage(Component.literal(info), false);
            if (orig != null) {
                orig.writeToFile(dir.resolve("paintcraft-sprite-dump.png"));
            }
        } catch (Exception e) {
            PaintCraft.LOGGER.error("[dump] anchor sprite failed", e);
        }
    }

    private void pushUndo() {
        undoStack.add(Arrays.copyOf(canvas, canvas.length));
        if (undoStack.size() > ModConfig.CONFIG.undoStackDepth.get()) undoStack.remove(0);
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

    // ── Paste confirmation ───────────────────────────────────────────

    /** Enter the locked paste-confirm state after an image is imported into the canvas. */
    private void beginPendingPaste() {
        pendingPaste = true;
        awaitingCharge = false;
        pendingPasteCost = PaintCost.dyeCost(canvas);
        pendingPasteAffordable = minecraft != null && minecraft.player != null
            && (minecraft.player.getAbilities().instabuild || PaintCost.canAfford(minecraft.player, canvas));
        updatePasteButtons();
    }

    /** Accept the paste: request the server to charge its dye cost. */
    private void acceptPaste() {
        if (!pendingPaste || awaitingCharge || !pendingPasteAffordable) return;
        pasteRequestId = ++pasteRequestSeq;
        awaitingCharge = true;
        updatePasteButtons();
        PacketDistributor.sendToServer(new PasteChargeRequestPayload(pasteRequestId, canvas.clone()));
    }

    /** Cancel the paste: revert to the pre-paste canvas (snapshot pushed before importing). */
    private void rejectPaste() {
        if (!pendingPaste || awaitingCharge) return;
        undo();
        clearPendingPaste();
    }

    /** Server reply to a paste-charge request. */
    public void onPasteChargeResult(int requestId, boolean success) {
        if (!pendingPaste || requestId != pasteRequestId) return;
        awaitingCharge = false;
        if (success) {
            clearPendingPaste(); // paid — keep the pasted canvas
        } else {
            // Rare (accept is gated on affordability); re-check and stay locked.
            pendingPasteAffordable = minecraft != null && minecraft.player != null
                && PaintCost.canAfford(minecraft.player, canvas);
            updatePasteButtons();
        }
    }

    private void clearPendingPaste() {
        pendingPaste = false;
        awaitingCharge = false;
        pendingPasteCost = java.util.List.of();
        updatePasteButtons();
    }

    /** Toggle the accept/reject buttons and lock/unlock the rest of the editor widgets. */
    private void updatePasteButtons() {
        if (pasteAcceptButton == null || pasteRejectButton == null) return;
        pasteAcceptButton.visible = pendingPaste;
        pasteRejectButton.visible = pendingPaste;
        pasteAcceptButton.active = pendingPaste && !awaitingCharge && pendingPasteAffordable;
        pasteRejectButton.active = pendingPaste && !awaitingCharge;
        pasteAcceptButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(pasteCostComponent()));

        // Grey out every other widget while a paste is pending.
        for (net.minecraft.client.gui.components.events.GuiEventListener child : children()) {
            if (child == pasteAcceptButton || child == pasteRejectButton) continue;
            if (child instanceof net.minecraft.client.gui.components.AbstractWidget w) {
                w.active = !pendingPaste;
            }
        }
    }

    private net.minecraft.network.chat.Component pasteCostComponent() {
        if (pendingPasteCost.isEmpty()) {
            return net.minecraft.network.chat.Component.literal("Paste cost: free");
        }
        net.minecraft.network.chat.MutableComponent c =
            net.minecraft.network.chat.Component.literal("Paste cost:");
        for (net.minecraft.world.item.ItemStack s : pendingPasteCost) {
            c.append(net.minecraft.network.chat.Component.literal(
                "\n  " + s.getCount() + "\u00D7 " + s.getHoverName().getString()));
        }
        if (!pendingPasteAffordable) {
            c.append(net.minecraft.network.chat.Component.literal("\n\u00A7cNot enough dye"));
        }
        return c;
    }

    private void loadAndImport(Path file, boolean snap) {
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
            importImage(img, snap);
            canvasDirty = true;
            beginPendingPaste();
            PaintCraft.LOGGER.info("Image imported ({}x{})", img.getWidth(), img.getHeight());
        } catch (Exception e) {
            PaintCraft.LOGGER.error("Failed to import image: {}", file, e);
        }
    }

    private void importImage(NativeImage img, boolean snap) {
        int srcW = img.getWidth();
        int srcH = img.getHeight();

        if (!snap || Decal.SNAP <= 1) {
            // Full-resolution center-fit (Ctrl+Shift+V)
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
                    if (idx >= 0 && idx < canvas.length)
                        canvas[idx] = ColorFormat.abgrToArgb(abgr);
                }
            }
            return;
        }

        // Snapped: center-fit into the logical 16px-per-block canvas, then expand each
        // logical pixel to a SNAP×SNAP texel block — matching exactly the grid the brush uses.
        // Colors are reduced via area-average with premultiplied alpha to avoid dark halos.
        int s = Decal.SNAP;
        int lcw = canvasW / s;   // logical canvas width  (widthBlocks  * 16)
        int lch = canvasH / s;   // logical canvas height (heightBlocks * 16)
        float scale = Math.min((float) lcw / srcW, (float) lch / srcH);
        int ldstW = Math.max(1, (int) (srcW * scale));
        int ldstH = Math.max(1, (int) (srcH * scale));
        int loffX = (lcw - ldstW) / 2;
        int loffY = (lch - ldstH) / 2;

        Arrays.fill(canvas, 0);

        for (int ly = 0; ly < ldstH; ly++) {
            for (int lx = 0; lx < ldstW; lx++) {
                // Source pixel region covering this logical cell
                int ix0 = Math.max(0, (int) (lx / scale));
                int iy0 = Math.max(0, (int) (ly / scale));
                int ix1 = Math.min(srcW - 1, (int) ((lx + 1) / scale));
                int iy1 = Math.min(srcH - 1, (int) ((ly + 1) / scale));

                // Premultiplied-alpha area average (NativeImage is ABGR)
                long sumA = 0, sumR = 0, sumG = 0, sumB = 0;
                int count = 0;
                for (int sy = iy0; sy <= iy1; sy++) {
                    for (int sx = ix0; sx <= ix1; sx++) {
                        int abgr = img.getPixelRGBA(sx, sy);
                        int a = (abgr >>> 24) & 0xFF;
                        int b = (abgr >>> 16) & 0xFF;
                        int g = (abgr >>>  8) & 0xFF;
                        int r =  abgr         & 0xFF;
                        sumA += a;
                        sumR += r * a;
                        sumG += g * a;
                        sumB += b * a;
                        count++;
                    }
                }

                int argb;
                if (count == 0 || sumA == 0) {
                    argb = 0;
                } else {
                    int a = (int) (sumA / count);
                    int r = (int) (sumR / sumA);
                    int g = (int) (sumG / sumA);
                    int b = (int) (sumB / sumA);
                    argb = (a << 24) | (r << 16) | (g << 8) | b;
                }

                // Fill the SNAP×SNAP texel block for this logical pixel
                int tx0 = (loffX + lx) * s;
                int ty0 = (loffY + ly) * s;
                for (int ty = 0; ty < s; ty++) {
                    for (int tx = 0; tx < s; tx++) {
                        int idx = (ty0 + ty) * canvasW + (tx0 + tx);
                        if (idx >= 0 && idx < canvas.length)
                            canvas[idx] = argb;
                    }
                }
            }
        }
    }

    private void pasteFromClipboard(boolean snap) {
        // 1. Try wl-paste (Wayland) / xclip (X11) — Linux subprocess approach
        for (String mime : new String[]{ "image/png", "image/jpeg" }) {
            byte[] bytes = readSubprocessClipboardBytes(mime);
            if (bytes != null) { loadFromImageBytes(bytes, mime, snap); return; }
        }

        // 2. Try AWT system clipboard — works on Windows, macOS, and X11
        byte[] awtBytes = readAwtClipboardBytes();
        if (awtBytes != null) { loadFromImageBytes(awtBytes, "awt", snap); return; }

        // 3. Fall back: clipboard text that looks like an image file path
        long window = Minecraft.getInstance().getWindow().getWindow();
        String text = org.lwjgl.glfw.GLFW.glfwGetClipboardString(window);
        if (text == null || text.isEmpty()) {
            PaintCraft.LOGGER.info("[paste] Clipboard empty / no image / no path");
            return;
        }
        text = text.trim();
        String lower = text.toLowerCase();
        if (lower.endsWith(".png") || lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") || lower.endsWith(".bmp")) {
            loadImageFromPath(text, snap);
        } else {
            PaintCraft.LOGGER.info("[paste] Clipboard text doesn't look like image path: {}",
                text.length() > 80 ? text.substring(0, 80) + "…" : text);
        }
    }

    private void loadImageFromPath(String pathStr, boolean snap) {
        if (pathStr.isEmpty()) return;
        if (pathStr.startsWith("\"") && pathStr.endsWith("\"")) {
            pathStr = pathStr.substring(1, pathStr.length() - 1);
        }
        if (pathStr.startsWith("file://")) {
            pathStr = pathStr.substring(7);
        }
        loadAndImport(Path.of(pathStr), snap);
    }

    private void loadFromImageBytes(byte[] bytes, String source, boolean snap) {
        try (NativeImage img = NativeImage.read(new ByteArrayInputStream(bytes))) {
            pushUndo();
            importImage(img, snap);
            canvasDirty = true;
            beginPendingPaste();
            PaintCraft.LOGGER.info("[paste] OK — {}×{} from clipboard ({})", img.getWidth(), img.getHeight(), source);
        } catch (Exception e) {
            PaintCraft.LOGGER.error("[paste] Got {} bytes from {} but NativeImage failed: {}", bytes.length, source, e.getMessage());
        }
    }

    /** Tries wl-paste (Wayland) then xclip (X11) for the given MIME type. */
    private static byte[] readSubprocessClipboardBytes(String mime) {
        String[][] cmds = {
            { "wl-paste", "--no-newline", "--type", mime },
            { "xclip", "-selection", "clipboard", "-t", mime, "-o" },
        };
        for (String[] cmd : cmds) {
            try {
                Process proc = new ProcessBuilder(cmd)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
                byte[] bytes = proc.getInputStream().readAllBytes(); // read before waitFor to avoid deadlock
                if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                    proc.destroyForcibly();
                    PaintCraft.LOGGER.info("[paste] {} timed out for {}", cmd[0], mime);
                    continue;
                }
                PaintCraft.LOGGER.info("[paste] {} exit={} bytes={} mime={}", cmd[0], proc.exitValue(), bytes.length, mime);
                if (proc.exitValue() == 0 && bytes.length > 8) return bytes;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                PaintCraft.LOGGER.debug("[paste] {} unavailable for {}: {}", cmd[0], mime, e.getMessage());
            }
        }
        return null;
    }

    /** Reads image data from the AWT system clipboard and encodes it as PNG bytes. Works on Windows, macOS, X11. */
    private static byte[] readAwtClipboardBytes() {
        try {
            java.awt.datatransfer.Clipboard cb = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
            if (!cb.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.imageFlavor)) return null;
            java.awt.Image awtImg = (java.awt.Image) cb.getData(java.awt.datatransfer.DataFlavor.imageFlavor);
            java.awt.image.BufferedImage bi;
            if (awtImg instanceof java.awt.image.BufferedImage bimg) {
                bi = bimg;
            } else {
                int w = awtImg.getWidth(null), h = awtImg.getHeight(null);
                bi = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics g = bi.createGraphics();
                g.drawImage(awtImg, 0, 0, null);
                g.dispose();
            }
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(bi, "PNG", baos);
            byte[] bytes = baos.toByteArray();
            PaintCraft.LOGGER.info("[paste] AWT clipboard: {} bytes", bytes.length);
            return bytes.length > 8 ? bytes : null;
        } catch (Exception e) {
            PaintCraft.LOGGER.debug("[paste] AWT clipboard unavailable: {}", e.getMessage());
            return null;
        }
    }

    private void copyDebug() {
        String debug = BackgroundCaptureDebug.run(
            Minecraft.getInstance().level, anchor, storedFrame.normal(), storedFrame.up(),
            canvasW / Decal.PX_PER_BLOCK, canvasH / Decal.PX_PER_BLOCK, depth);
        long window = Minecraft.getInstance().getWindow().getWindow();
        org.lwjgl.glfw.GLFW.glfwSetClipboardString(window, debug);
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(
                Component.literal("Debug copied to clipboard"), true);
        }
    }

    @Override
    public void onClose() {
        if (pendingPaste) { rejectPaste(); return; } // cancel the pending paste instead of closing
        saveAndClose();
    }

    private void discardAndClose() {
        super.onClose();
    }

    private void saveAndClose() {
        // Transform display pixels back to stored orientation
        PixelGrid displayGrid = PixelGrid.wrap(canvasW, canvasH, canvas);
        PixelGrid stored = transform.toStored(displayGrid);

        DecalCreatePayload payload = new DecalCreatePayload(
            decalId, 0, 0, anchor, storedFrame.normal(), storedFrame.up(),
            stored.width(), stored.height(), depth, (byte) 0, stored.data()
        );
        PacketDistributor.sendToServer(payload);
        super.onClose();
    }

    private void saveToLibrary() {
        if (!net.neoforged.fml.ModList.get().isLoaded("assetshelf")) {
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.displayClientMessage(
                    Component.literal("Asset Shelf mod not installed"), true);
            }
            return;
        }
        // Open save dialog with name input + preview
        PixelGrid displayGrid = PixelGrid.wrap(canvasW, canvasH, canvas);
        PixelGrid stored = transform.toStored(displayGrid);
        dev.paintcraft.compat.SaveToLibraryScreen.open(
            this, stored.width(), stored.height(), stored.data());
    }

    @Override
    public void removed() {
        super.removed();
        EditorPrefs.from(customBlocks, recentColors, pinnedColors, selectedColor,
                         colorSquareSoft, mouse1Tool, mouse2Tool, toolSettings, settings).save();
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
