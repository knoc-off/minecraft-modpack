package dev.paintcraft.client;

import dev.paintcraft.item.StampData;
import dev.paintcraft.item.StampItem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.IItemDecorator;

/**
 * Renders a tiny thumbnail of the stamp's pixel data in the bottom-right
 * corner of the item slot, on top of the item icon.
 */
public class StampItemDecorator implements IItemDecorator {

    private static final int PREVIEW_SIZE = 8;

    @Override
    public boolean render(GuiGraphics gfx, Font font, ItemStack stack, int xOffset, int yOffset) {
        if (!StampItem.isLoaded(stack)) return false;

        StampData data = StampItem.getData(stack);
        if (data == null) return false;

        int srcW = data.widthPx();
        int srcH = data.heightPx();
        int[] pixels = data.pixels();
        if (pixels == null || pixels.length != srcW * srcH) return false;

        // Scale to fit PREVIEW_SIZE, maintaining aspect ratio
        float scale = Math.min((float) PREVIEW_SIZE / srcW, (float) PREVIEW_SIZE / srcH);
        int dstW = Math.max(1, (int) (srcW * scale));
        int dstH = Math.max(1, (int) (srcH * scale));

        // Bottom-right corner of the 16x16 slot
        int baseX = xOffset + 16 - dstW;
        int baseY = yOffset + 16 - dstH;

        // Render above the item icon (z=200 is above items at z=150)
        gfx.pose().pushPose();
        gfx.pose().translate(0, 0, 200);

        for (int py = 0; py < dstH; py++) {
            for (int px = 0; px < dstW; px++) {
                int srcX = Math.min((int) (px / scale), srcW - 1);
                int srcY = Math.min((int) (py / scale), srcH - 1);
                int color = pixels[srcY * srcW + srcX];
                if ((color >>> 24) == 0) continue;
                gfx.fill(baseX + px, baseY + py, baseX + px + 1, baseY + py + 1, color);
            }
        }

        gfx.pose().popPose();
        return false;
    }
}
