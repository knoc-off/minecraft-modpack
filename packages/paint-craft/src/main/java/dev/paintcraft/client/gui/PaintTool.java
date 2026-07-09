package dev.paintcraft.client.gui;

public enum PaintTool {
    PENCIL {
        @Override
        public void draw(int[] pixels, int width, int height, int x, int y, int size, int color) {
            fillSquare(pixels, width, height, x, y, size, color);
        }
    },
    ERASER {
        @Override
        public void draw(int[] pixels, int width, int height, int x, int y, int size, int color) {
            // Soft-erase: the brush alpha (top byte of color) drives how much opacity to remove.
            // The remaining alpha is the inverted brush alpha; a full-opacity brush erases fully.
            softErase(pixels, width, height, x, y, size, 255 - (color >>> 24));
        }
    };

    public abstract void draw(int[] pixels, int width, int height, int x, int y, int size, int color);

    private static void fillSquare(int[] pixels, int width, int height, int x, int y, int size, int color) {
        int radius = size - 1;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int px = x + dx;
                int py = y + dy;
                if (px >= 0 && px < width && py >= 0 && py < height) {
                    pixels[py * width + px] = color;
                }
            }
        }
    }

    /**
     * Lower each covered pixel's alpha toward {@code target}, preserving RGB. The clamp only ever
     * reduces opacity, so the eraser never re-adds paint; a target of 0 clears the pixel entirely.
     */
    static void softErase(int[] pixels, int width, int height, int x, int y, int size, int target) {
        int radius = size - 1;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int px = x + dx;
                int py = y + dy;
                if (px >= 0 && px < width && py >= 0 && py < height) {
                    int idx = py * width + px;
                    int old = pixels[idx];
                    if ((old >>> 24) <= target) continue; // already this transparent or more
                    pixels[idx] = target == 0 ? 0x00000000 : (target << 24) | (old & 0x00FFFFFF);
                }
            }
        }
    }
}
