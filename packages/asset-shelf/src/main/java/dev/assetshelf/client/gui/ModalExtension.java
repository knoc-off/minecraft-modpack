package dev.assetshelf.client.gui;

import net.minecraft.client.gui.GuiGraphics;


/**
 * Callback widget slot for mod-specific content in the SaveAssetScreen.
 * <p>
 * The implementing mod provides a renderable section that the modal
 * renders in a tinted inset card between the tag input and footer.
 * The card has a clickable header that toggles expand/collapse.
 */
public interface ModalExtension {

    /** Header text (e.g., "PAINTCRAFT FIELDS"). Uppercase by convention. */
    String headerLabel();

    /** Accent color (ARGB) used to tint the inset card background. */
    int tintColor();

    /**
     * Render the extension content into the given bounds.
     * Called only when the section is expanded.
     *
     * @param g      graphics context
     * @param x      left edge of content area (inside card padding)
     * @param y      top edge (below header line)
     * @param width  available width
     * @param mouseX mouse X
     * @param mouseY mouse Y
     * @return actual height consumed by the content
     */
    int renderContent(GuiGraphics g, int x, int y, int width, int mouseX, int mouseY);

    /** Handle mouse clicks within the content bounds. */
    default boolean mouseClicked(double mx, double my, int btn) { return false; }

    /** Handle key presses while extension has focus. */
    default boolean keyPressed(int key, int scan, int mods) { return false; }

    /** Whether the section starts expanded. Default: collapsed. */
    default boolean startExpanded() { return false; }
}
