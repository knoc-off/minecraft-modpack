package dev.paintcraft.compat;

import dev.assetshelf.client.gui.ModalExtension;
import net.minecraft.client.gui.GuiGraphics;

/**
 * PaintCraft's modal extension for the save/publish dialogs.
 * Shows the "PAINTCRAFT FIELDS" expandable card with cost/tier/attribution info.
 */
public class PaintCraftModalExtension implements ModalExtension {

    private static final int ACCENT = 0xFFC47840;
    private final boolean isPublish;

    public PaintCraftModalExtension(boolean isPublish) {
        this.isPublish = isPublish;
    }

    @Override
    public String headerLabel() {
        return "PAINTCRAFT FIELDS";
    }

    @Override
    public int tintColor() {
        return ACCENT;
    }

    @Override
    public int renderContent(GuiGraphics g, int x, int y, int width, int mouseX, int mouseY) {
        int color = 0xFF7A7468;
        int faint = 0xFF9E9890;

        if (isPublish) {
            g.drawString(net.minecraft.client.Minecraft.getInstance().font,
                "cost ingredients confirmed \u00B7", x, y, color, false);
            g.drawString(net.minecraft.client.Minecraft.getInstance().font,
                "per-stamp recipe attached", x, y + 10, color, false);
            return 22;
        } else {
            g.drawString(net.minecraft.client.Minecraft.getInstance().font,
                "cost ingredients \u00B7 tier \u00B7 attribution", x, y, color, false);
            g.drawString(net.minecraft.client.Minecraft.getInstance().font,
                "\u2191 the integrating mod provides these", x, y + 11, faint, false);
            return 23;
        }
    }

    @Override
    public boolean startExpanded() {
        return true;
    }
}
