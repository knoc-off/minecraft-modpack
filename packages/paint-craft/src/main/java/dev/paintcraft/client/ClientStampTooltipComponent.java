package dev.paintcraft.client;

import dev.paintcraft.item.StampTooltipComponent;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;

/**
 * Renders the stamp's pixel data as an image in the tooltip on hover.
 */
public class ClientStampTooltipComponent implements ClientTooltipComponent {

    private static final int MAX_SIZE = 64;
    private static final int PADDING = 2;

    private final int srcW, srcH;
    private final int[] pixels;
    private final int dstW, dstH;

    public ClientStampTooltipComponent(StampTooltipComponent data) {
        this.srcW = data.widthPx();
        this.srcH = data.heightPx();
        this.pixels = data.pixels();

        // Scale up to MAX_SIZE, maintaining aspect ratio, using integer scaling when possible
        int intScale = Math.max(1, Math.min(MAX_SIZE / srcW, MAX_SIZE / srcH));
        this.dstW = srcW * intScale;
        this.dstH = srcH * intScale;
    }

    @Override
    public int getHeight() {
        return dstH + PADDING;
    }

    @Override
    public int getWidth(Font font) {
        return dstW;
    }

    @Override
    public void renderImage(Font font, int x, int y, GuiGraphics gfx) {
        if (pixels == null || pixels.length != srcW * srcH) return;

        int scale = dstW / srcW;

        for (int sy = 0; sy < srcH; sy++) {
            for (int sx = 0; sx < srcW; sx++) {
                int color = pixels[sy * srcW + sx];
                if ((color >>> 24) == 0) continue;
                gfx.fill(x + sx * scale, y + sy * scale,
                         x + sx * scale + scale, y + sy * scale + scale, color);
            }
        }
    }
}
