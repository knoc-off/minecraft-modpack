package dev.paintcraft.client.gui;

import dev.paintcraft.client.ClientBrushHandler;
import dev.paintcraft.core.Decal;
import dev.paintcraft.network.DecalSelectionPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Selection screen shown when multiple decals overlap at the same block face.
 * Displays thumbnails of each decal for the player to choose which to edit.
 */
public class DecalSelectionScreen extends Screen {

    private static final int CARD_SIZE = 72;
    private static final int CARD_PADDING = 8;
    private static final int THUMB_SIZE = 56;
    private static final int LABEL_HEIGHT = 12;

    private final List<DecalSelectionPayload.Entry> entries;
    private int gridX, gridY, cols;

    public DecalSelectionScreen(List<DecalSelectionPayload.Entry> entries) {
        super(Component.literal("Select Canvas"));
        this.entries = entries;
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
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        renderBackground(gfx, mouseX, mouseY, partialTick);

        // Title
        gfx.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);
        gfx.drawCenteredString(this.font, Component.literal("Click a canvas to edit it"),
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
            int bgColor = hovered ? 0xFF4488CC : 0xFF333333;
            gfx.fill(cardX, cardY, cardX + CARD_SIZE, cardY + CARD_SIZE, bgColor);
            gfx.fill(cardX + 1, cardY + 1, cardX + CARD_SIZE - 1, cardY + CARD_SIZE - 1, 0xFF1A1A1A);

            // Render thumbnail
            renderThumbnail(gfx, entry, cardX + (CARD_SIZE - THUMB_SIZE) / 2,
                cardY + (CARD_SIZE - THUMB_SIZE) / 2);

            // Label below card
            int wBlocks = entry.widthPx() / Decal.PX_PER_BLOCK;
            int hBlocks = entry.heightPx() / Decal.PX_PER_BLOCK;
            String label = wBlocks + "×" + hBlocks;
            if (i == 0) label += " (top)";
            gfx.drawCenteredString(this.font, label, cardX + CARD_SIZE / 2,
                cardY + CARD_SIZE + 2, 0xCCCCCC);
        }
    }

    private void renderThumbnail(GuiGraphics gfx, DecalSelectionPayload.Entry entry,
                                  int thumbX, int thumbY) {
        int wPx = entry.widthPx();
        int hPx = entry.heightPx();
        int[] pixels = entry.pixels();

        // Compute scale to fit within THUMB_SIZE
        int scale = Math.max(1, Math.min(THUMB_SIZE / wPx, THUMB_SIZE / hPx));
        int renderW = wPx * scale;
        int renderH = hPx * scale;
        int ox = thumbX + (THUMB_SIZE - renderW) / 2;
        int oy = thumbY + (THUMB_SIZE - renderH) / 2;

        // Checkerboard background for transparency
        for (int py = 0; py < hPx; py++) {
            for (int px = 0; px < wPx; px++) {
                boolean checker = ((px / 2) + (py / 2)) % 2 == 0;
                gfx.fill(ox + px * scale, oy + py * scale,
                    ox + px * scale + scale, oy + py * scale + scale,
                    checker ? 0xFF444444 : 0xFF666666);
            }
        }

        // Draw pixels
        for (int py = 0; py < hPx; py++) {
            for (int px = 0; px < wPx; px++) {
                int color = pixels[py * wPx + px];
                if (((color >> 24) & 0xFF) <= 0) continue;
                gfx.fill(ox + px * scale, oy + py * scale,
                    ox + px * scale + scale, oy + py * scale + scale, color);
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (int i = 0; i < entries.size(); i++) {
                int col = i % cols;
                int row = i / cols;
                int cardX = gridX + col * (CARD_SIZE + CARD_PADDING);
                int cardY = gridY + row * (CARD_SIZE + CARD_PADDING + LABEL_HEIGHT);

                if (mouseX >= cardX && mouseX < cardX + CARD_SIZE
                    && mouseY >= cardY && mouseY < cardY + CARD_SIZE) {
                    selectEntry(entries.get(i));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void selectEntry(DecalSelectionPayload.Entry entry) {
        onClose();
        ClientBrushHandler.openExistingEditor(
            entry.anchor(), entry.normal(), entry.up(),
            entry.widthPx() / Decal.PX_PER_BLOCK,
            entry.heightPx() / Decal.PX_PER_BLOCK,
            entry.pixels(), entry.id()
        );
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
