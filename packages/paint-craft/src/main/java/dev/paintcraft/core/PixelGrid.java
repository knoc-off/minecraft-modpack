package dev.paintcraft.core;


/**
 * Immutable pixel buffer with typed dimensions.
 * Format: ARGB, row-major (y * width + x), pixel (0,0) = top-left.
 * All transforms return new instances — the source grid is never modified.
 */
public final class PixelGrid {

    private final int width;
    private final int height;
    private final int[] data;

    /** Create an empty (transparent) grid. */
    public PixelGrid(int width, int height) {
        this(width, height, new int[width * height]);
    }

    /**
     * Create a grid from existing pixel data.
     * Takes ownership of the array — caller must not mutate it after construction.
     */
    public PixelGrid(int width, int height, int[] data) {
        if (data.length != width * height) {
            throw new IllegalArgumentException(
                "data length " + data.length + " != " + width + "×" + height + " (" + (width * height) + ")");
        }
        this.width = width;
        this.height = height;
        this.data = data;
    }

    public int width()  { return width; }
    public int height() { return height; }
    public int widthBlocks()  { return width / Decal.PX_PER_BLOCK; }
    public int heightBlocks() { return height / Decal.PX_PER_BLOCK; }
    /**
     * Returns the backing array. Read-only contract: do not mutate.
     */
    public int[] data() { return data; }

    // === Immutable transforms ===

    /** Rotate the grid by N × 90° clockwise. Returns a new grid (dimensions may swap). */
    public PixelGrid rotateCW(int steps) {
        steps = ((steps % 4) + 4) % 4;
        if (steps == 0) return this;

        int[] curData = data;
        int curW = width, curH = height;

        for (int r = 0; r < steps; r++) {
            int newW = curH, newH = curW;
            int[] rotated = new int[newW * newH];
            for (int y = 0; y < curH; y++) {
                for (int x = 0; x < curW; x++) {
                    rotated[x * newW + (newW - 1 - y)] = curData[y * curW + x];
                }
            }
            curData = rotated;
            curW = newW;
            curH = newH;
        }

        return new PixelGrid(curW, curH, curData);
    }

    /** Flip horizontally (mirror along vertical axis). Returns a new grid. */
    public PixelGrid flipH() {
        int[] flipped = new int[data.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                flipped[y * width + (width - 1 - x)] = data[y * width + x];
            }
        }
        return new PixelGrid(width, height, flipped);
    }

    /**
     * Wrap an existing mutable array into an immutable grid. Takes ownership.
     * Equivalent to the constructor but reads more clearly at call sites.
     */
    public static PixelGrid wrap(int width, int height, int[] data) {
        return new PixelGrid(width, height, data);
    }
}
