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
            fillSquare(pixels, width, height, x, y, size, 0x00000000);
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
}
