package dev.paintcraft.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.regex.Pattern;

/**
 * Debug overlay for PaintCraft performance profiling.
 * Toggled via /paintcraft debug command.
 */
public final class DebugOverlay {

    private static boolean enabled = false;
    private static final Stats STATS = new Stats();

    // Smoothed render time (exponential moving average)
    private static long smoothedRenderNanos = 0;
    private static final float EMA_ALPHA = 0.1f;
    private static final Pattern FORMAT_CODE = Pattern.compile("§.");

    private DebugOverlay() {}

    public static boolean isEnabled() { return enabled; }
    public static void toggle() { enabled = !enabled; }

    /** Mutable stats object — zeroed each frame, populated during renderAll(). */
    public static Stats stats() { return STATS; }

    /** Get current stats as plain text for clipboard. */
    public static String getStatsText() {
        long raw = STATS.renderTimeNanos;
        return String.join("\n",
            "[PaintCraft Debug]",
            String.format("Decals: %d  Visible: %d", STATS.totalDecals, STATS.visibleDecals),
            String.format("Culled: %d frustum", STATS.frustumCulled),
            String.format("Draw calls: %d", STATS.drawCalls),
            String.format("Render: %.2f ms (avg %.2f ms)", raw / 1_000_000.0, smoothedRenderNanos / 1_000_000.0),
            String.format("Rebuild: %.2f ms  Baked VBOs: %d", STATS.rebuildTimeNanos / 1_000_000.0, STATS.bakedBufferCount),
            String.format("Spatial cells: %d  Hottest: %d overlaps", STATS.spatialCells, STATS.hottestCell)
        );
    }

    public static void render(GuiGraphics gfx) {
        if (!enabled) return;

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;

        // Update smoothed render time
        long raw = STATS.renderTimeNanos;
        if (smoothedRenderNanos == 0) {
            smoothedRenderNanos = raw;
        } else {
            smoothedRenderNanos = (long) (EMA_ALPHA * raw + (1 - EMA_ALPHA) * smoothedRenderNanos);
        }

        int x = 4;
        int y = 4;
        int lineH = 10;
        int bgColor = 0x90000000;
        int textColor = 0xFFFFFFFF;
        int badColor = 0xFFFF5555;

        String[] lines = {
            "§e[PaintCraft Debug]",
            String.format("Decals: %d  Visible: %d",
                STATS.totalDecals, STATS.visibleDecals),
            String.format("Culled: %d frustum", STATS.frustumCulled),
            String.format("Draw calls: %d", STATS.drawCalls),
            String.format("Render: %.2f ms (avg %.2f ms)",
                raw / 1_000_000.0, smoothedRenderNanos / 1_000_000.0),
            String.format("Rebuild: %.2f ms  Baked VBOs: %d",
                STATS.rebuildTimeNanos / 1_000_000.0, STATS.bakedBufferCount),
            String.format("Spatial cells: %d  Hottest: %d overlaps",
                STATS.spatialCells, STATS.hottestCell),
        };

        // Draw background
        int maxW = 0;
        for (String line : lines) {
            maxW = Math.max(maxW, font.width(FORMAT_CODE.matcher(line).replaceAll("")));
        }
        gfx.fill(x - 2, y - 2, x + maxW + 4, y + lines.length * lineH + 2, bgColor);

        // Draw text
        for (int i = 0; i < lines.length; i++) {
            gfx.drawString(font, lines[i], x, y + i * lineH, textColor, false);
        }

        // Warn indicators
        if (STATS.drawCalls > 50) {
            gfx.drawString(font, "§c  (HIGH draw calls!)", x + font.width("Draw calls: " + STATS.drawCalls), y + 3 * lineH, badColor, false);
        }
    }

    public static class Stats {
        public int totalDecals;
        public int visibleDecals;
        public int frustumCulled;
        public int drawCalls;
        public long renderTimeNanos;
        public int spatialCells;
        public int hottestCell;
        public long rebuildTimeNanos;
        public int bakedBufferCount;

        public void reset() {
            totalDecals = visibleDecals = frustumCulled = drawCalls = 0;
            renderTimeNanos = 0;
            spatialCells = hottestCell = 0;
        }
    }
}
