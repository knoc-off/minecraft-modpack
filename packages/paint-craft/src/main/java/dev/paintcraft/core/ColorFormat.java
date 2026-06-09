package dev.paintcraft.core;

/**
 * Single source of truth for pixel color format conversions.
 * Internal format: ARGB (0xAARRGGBB)
 * NativeImage format: ABGR (0xAABBGGRR)
 */
public final class ColorFormat {

    private ColorFormat() {}

    public static int argbToAbgr(int argb) {
        int a = argb & 0xFF000000;
        int r = (argb >> 16) & 0xFF;
        int g = argb & 0x0000FF00;
        int b = (argb & 0xFF) << 16;
        return a | b | g | r;
    }

    public static int abgrToArgb(int abgr) {
        return argbToAbgr(abgr); // R↔B swap is symmetric
    }

    public static boolean isOpaque(int argb) { return (argb >>> 24) != 0; }
}
