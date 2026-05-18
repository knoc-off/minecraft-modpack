package dev.paintcraft.client;

import dev.paintcraft.item.StampData;
import dev.paintcraft.item.StampItem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.IItemDecorator;

/**
 * Renders a tiny thumbnail of the stamp's pixel data onto the item slot.
 * Only draws when the stamp has captured decal data.
 */
public class StampItemDecorator implements IItemDecorator {

    private static final int PREVIEW_SIZE = 12;
    private static final int OFFSET = 2; // offset from top-left of the 16x16 slot

    @Override
    public boolean render(GuiGraphics gfx, Font font, ItemStack stack, int xOffset, int yOffset) {
        if (!StampItem.isLoaded(stack)) return false;

        StampData data = StampItem.getData(stack);
        if (data == null) return false;

        int srcW = data.widthPx();
        int srcH = data.heightPx();
        int[] pixels = data.pixels();
        if (pixels == null || pixels.length != srcW * srcH) return false;

        // Determine preview dimensions maintaining aspect ratio
        float scale = Math.min((float) PREVIEW_SIZE / srcW, (float) PREVIEW_SIZE / srcH);
        int dstW = Math.max(1, (int) (srcW * scale));
        int dstH = Math.max(1, (int) (srcH * scale));

        // Center within the preview area
        int baseX = xOffset + OFFSET + (PREVIEW_SIZE - dstW) / 2;
        int baseY = yOffset + OFFSET + (PREVIEW_SIZE - dstH) / 2;

        // Draw using nearest-neighbor sampling, 1px per preview pixel
        for (int py = 0; py < dstH; py++) {
            for (int px = 0; px < dstW; px++) {
                int srcX = Math.min((int) (px / scale), srcW - 1);
                int srcY = Math.min((int) (py / scale), srcH - 1);
                int color = pixels[srcY * srcW + srcX];
                if ((color >>> 24) == 0) continue; // skip fully transparent
                gfx.fill(baseX + px, baseY + py, baseX + px + 1, baseY + py + 1, color);
            }
        }

        return false; // don't signal render state change
    }
}
