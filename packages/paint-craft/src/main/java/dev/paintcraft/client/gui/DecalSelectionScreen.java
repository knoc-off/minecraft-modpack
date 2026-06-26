package dev.paintcraft.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import dev.paintcraft.client.ClientBrushHandler;
import dev.paintcraft.core.ColorFormat;
import dev.paintcraft.core.Decal;
import dev.paintcraft.core.DisplayTransform;
import dev.paintcraft.core.FaceFrame;
import dev.paintcraft.core.PixelGrid;
import dev.paintcraft.network.DecalErasePayload;
import dev.paintcraft.network.DecalReorderPayload;
import dev.paintcraft.network.DecalSelectionPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Selection screen shown when multiple decals overlap at the same block face.
 * Displays thumbnails of each decal for the player to choose which to edit or erase.
 */
public class DecalSelectionScreen extends Screen {

    private static final int CARD_SIZE = 72;
    private static final int CARD_PADDING = 8;
    private static final int THUMB_SIZE = 56;
    private static final int LABEL_HEIGHT = 12;

    private final List<DecalSelectionPayload.Entry> entries;
    private final boolean eraseMode;
    private int gridX, gridY, cols;

    /** When true, clicking a card reorders it (left=bring to top, right=send to back) instead of editing. */
    private boolean reorderMode = false;
    private Button reorderButton;

    private record Thumbnail(DynamicTexture texture, ResourceLocation location,
                             int srcW, int srcH, int displayW, int displayH) {}
    private List<Thumbnail> thumbnails;

    public DecalSelectionScreen(List<DecalSelectionPayload.Entry> entries, boolean eraseMode) {
        super(Component.literal(eraseMode ? "Erase Canvas" : "Select Canvas"));
        this.entries = new ArrayList<>(entries);
        this.eraseMode = eraseMode;
    }

    @Override
    protected void init() {
        int totalW = this.width - 40;
        cols = Math.max(1, totalW / (CARD_SIZE + CARD_PADDING));
        int rows = (entries.size() + cols - 1) / cols;
        int gridW = cols * (CARD_SIZE + CARD_PADDING) - CARD_PADDING;
        int gridH = rows * (CARD_SIZE + CARD_PADDING + LABEL_HEIGHT) - CARD_PADDING;
        gridX = (this.width - gridW) / 2;
        gridY = Math.max(30, (this.height - gridH) / 2);

        buildThumbnails();

        // Reorder toggle (editing mode only — reordering during erase is meaningless).
        if (!eraseMode) {
            reorderButton = Button.builder(reorderLabel(), b -> {
                reorderMode = !reorderMode;
                b.setMessage(reorderLabel());
            }).bounds(this.width / 2 - 80, this.height - 28, 160, 20).build();
            addRenderableWidget(reorderButton);
        }
    }

    private Component reorderLabel() {
        return Component.literal(reorderMode ? "Reorder: ON" : "Reorder: off");
    }

    private void buildThumbnails() {
        releaseThumbnails();
        thumbnails = new ArrayList<>(entries.size());
        var tm = Minecraft.getInstance().getTextureManager();

        // Display orientation matches the editor: hFlip for negative-axis walls, and rotation to the
        // player's current facing for floor/ceiling faces (same as ClientBrushHandler.openExistingEditor).
        Direction playerDir = Minecraft.getInstance().player.getDirection();

        for (DecalSelectionPayload.Entry entry : entries) {
            FaceFrame storedFrame = new FaceFrame(entry.normal(), entry.up());
            FaceFrame displayFrame = entry.normal().getAxis().isVertical()
                ? FaceFrame.horizontal(entry.normal(), playerDir)
                : storedFrame;
            PixelGrid display = DisplayTransform.forEditor(storedFrame, displayFrame)
                .toDisplay(PixelGrid.wrap(entry.widthPx(), entry.heightPx(), entry.pixels()));

            int wPx = display.width();
            int hPx = display.height();
            int[] pixels = display.data();

            // Create full-resolution NativeImage with checkerboard baked in
            NativeImage image = new NativeImage(wPx, hPx, true);
            for (int y = 0; y < hPx; y++) {
                for (int x = 0; x < wPx; x++) {
                    int color = pixels[y * wPx + x];
                    int abgr;
                    if (((color >> 24) & 0xFF) > 0) {
                        abgr = ColorFormat.argbToAbgr(color);
                    } else {
                        boolean checker = ((x / 2) + (y / 2)) % 2 == 0;
                        abgr = checker ? ColorFormat.argbToAbgr(0xFF444444)
                                       : ColorFormat.argbToAbgr(0xFF666666);
                    }
                    image.setPixelRGBA(x, y, abgr);
                }
            }

            DynamicTexture texture = new DynamicTexture(image);
            ResourceLocation loc = tm.register("paintcraft_thumb", texture);

            // Compute display size — float scale supports downscaling large decals
            float scale = Math.min((float) THUMB_SIZE / wPx, (float) THUMB_SIZE / hPx);
            int displayW = Math.max(1, Math.round(wPx * scale));
            int displayH = Math.max(1, Math.round(hPx * scale));

            thumbnails.add(new Thumbnail(texture, loc, wPx, hPx, displayW, displayH));
        }
    }

    private void releaseThumbnails() {
        if (thumbnails != null) {
            var tm = Minecraft.getInstance().getTextureManager();
            for (Thumbnail t : thumbnails) {
                tm.release(t.location);
                t.texture.close();
            }
            thumbnails = null;
        }
    }

    @Override
    protected void renderBlurredBackground(float partialTick) {
        // Skip vanilla menu blur; renderMenuBackground() still draws the dark tint.
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        renderBackground(gfx, mouseX, mouseY, partialTick);

        // Title
        gfx.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);
        String subtitle = eraseMode ? "Click a canvas to erase it"
            : reorderMode ? "Left-click = bring to top, right-click = send to back"
            : "Click a canvas to edit it";
        gfx.drawCenteredString(this.font, Component.literal(subtitle),
            this.width / 2, 20, 0xAAAAAA);

        for (int i = 0; i < entries.size(); i++) {
            DecalSelectionPayload.Entry entry = entries.get(i);
            int col = i % cols;
            int row = i / cols;
            int cardX = gridX + col * (CARD_SIZE + CARD_PADDING);
            int cardY = gridY + row * (CARD_SIZE + CARD_PADDING + LABEL_HEIGHT);

            boolean hovered = mouseX >= cardX && mouseX < cardX + CARD_SIZE
                && mouseY >= cardY && mouseY < cardY + CARD_SIZE;

            // Card background
            int hoverColor = eraseMode ? 0xFFCC4444 : reorderMode ? 0xFF44AA66 : 0xFF4488CC;
            int bgColor = hovered ? hoverColor : 0xFF333333;
            gfx.fill(cardX, cardY, cardX + CARD_SIZE, cardY + CARD_SIZE, bgColor);
            gfx.fill(cardX + 1, cardY + 1, cardX + CARD_SIZE - 1, cardY + CARD_SIZE - 1, 0xFF1A1A1A);

            // Render thumbnail (single blit)
            if (thumbnails != null && i < thumbnails.size()) {
                Thumbnail thumb = thumbnails.get(i);
                int thumbX = cardX + (CARD_SIZE - THUMB_SIZE) / 2;
                int thumbY = cardY + (CARD_SIZE - THUMB_SIZE) / 2;
                int ox = thumbX + (THUMB_SIZE - thumb.displayW) / 2;
                int oy = thumbY + (THUMB_SIZE - thumb.displayH) / 2;
                gfx.blit(thumb.location, ox, oy, thumb.displayW, thumb.displayH,
                    0f, 0f, thumb.srcW, thumb.srcH, thumb.srcW, thumb.srcH);
            }

            // Label below card
            int wBlocks = entry.widthPx() / Decal.PX_PER_BLOCK;
            int hBlocks = entry.heightPx() / Decal.PX_PER_BLOCK;
            String label = wBlocks + "×" + hBlocks;
            if (i == 0) label += " (top)";
            gfx.drawCenteredString(this.font, label, cardX + CARD_SIZE / 2,
                cardY + CARD_SIZE + 2, 0xCCCCCC);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int idx = cardAt(mouseX, mouseY);
        if (idx >= 0) {
            if (reorderMode && !eraseMode) {
                if (button == 0) { reorder(idx, true); return true; }
                if (button == 1) { reorder(idx, false); return true; }
            } else if (button == 0) {
                selectEntry(entries.get(idx));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** Returns the entry index under the cursor, or -1. */
    private int cardAt(double mouseX, double mouseY) {
        for (int i = 0; i < entries.size(); i++) {
            int col = i % cols;
            int row = i / cols;
            int cardX = gridX + col * (CARD_SIZE + CARD_PADDING);
            int cardY = gridY + row * (CARD_SIZE + CARD_PADDING + LABEL_HEIGHT);
            if (mouseX >= cardX && mouseX < cardX + CARD_SIZE
                && mouseY >= cardY && mouseY < cardY + CARD_SIZE) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Reorder a card: ask the server to bring it to top (or send to back), then optimistically move
     * it within the local lists so the UI (incl. the "(top)" label) updates instantly. The server's
     * bring-to-front assigns the new max zOrder, matching the top-first display order.
     */
    private void reorder(int idx, boolean toFront) {
        DecalSelectionPayload.Entry entry = entries.get(idx);
        PacketDistributor.sendToServer(new DecalReorderPayload(entry.id(), toFront));
        entries.remove(idx);
        Thumbnail th = (thumbnails != null) ? thumbnails.remove(idx) : null;
        if (toFront) {
            entries.add(0, entry);
            if (th != null) thumbnails.add(0, th);
        } else {
            entries.add(entry);
            if (th != null) thumbnails.add(th);
        }
    }

    private void selectEntry(DecalSelectionPayload.Entry entry) {
        onClose();
        if (eraseMode) {
            PacketDistributor.sendToServer(new DecalErasePayload(entry.id()));
        } else {
            ClientBrushHandler.openExistingEditor(
                entry.anchor(), entry.normal(), entry.up(),
                entry.widthPx() / Decal.PX_PER_BLOCK,
                entry.heightPx() / Decal.PX_PER_BLOCK,
                entry.depth(), entry.pixels(), entry.id()
            );
        }
    }

    @Override
    public void removed() {
        super.removed();
        releaseThumbnails();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
