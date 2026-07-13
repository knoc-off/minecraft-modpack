package dev.paintcraft.client.gui;

/**
 * Editor tools, GIMP/Photoshop-inspired. The glyph is the toolbar label (icons may replace it
 * later); the actual pixel manipulation lives in {@link PaintScreen} so it has direct access to
 * the canvas, per-stroke coverage buffer and per-tool settings.
 *
 * <ul>
 *   <li>PENCIL — hard replace with the selected color (crisp pixel art).</li>
 *   <li>BRUSH  — alpha-over blend with opacity build-up (a stroke tops out at its opacity).</li>
 *   <li>ERASER — removes alpha; hard (clear) or soft (reduce) with its own strength.</li>
 *   <li>FILL   — flood-fill the contiguous region matching the clicked pixel.</li>
 *   <li>LINE   — click-drag to draw a straight line, committed on release.</li>
 * </ul>
 */
public enum PaintTool {
    PENCIL("P", "Pencil"),
    BRUSH ("B", "Brush"),
    ERASER("E", "Eraser"),
    FILL  ("F", "Fill"),
    LINE  ("L", "Line");

    /** Short label shown on the toolbar button (placeholder until icons are added). */
    public final String glyph;
    /** Human-readable name for tooltips. */
    public final String displayName;

    PaintTool(String glyph, String displayName) {
        this.glyph = glyph;
        this.displayName = displayName;
    }

    /** True for the straight-line press/drag/release gesture. */
    public boolean isLine() { return this == LINE; }

    /** True for single-click actions (no drag stroke). */
    public boolean isClickAction() { return this == FILL; }

    /** True for tools whose options include a paint color (everything but the eraser). */
    public boolean usesColor() { return this != ERASER; }
}
