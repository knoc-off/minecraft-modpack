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

    /**
     * Straight-alpha "over" compositing of ARGB colors: {@code fg} over {@code bg}.
     * {@code fg} is the higher-priority (front) layer.
     */
    public static int alphaOver(int fg, int bg) {
        int fa = (fg >>> 24) & 0xFF;
        if (fa == 0xFF) return fg;   // opaque front fully hides back
        if (fa == 0)    return bg;   // transparent front: back shows through
        int ba = (bg >>> 24) & 0xFF;
        int inv = 255 - fa;
        int oa = fa + ba * inv / 255;
        if (oa == 0) return 0;
        int baInv = ba * inv / 255;
        int fr = (fg >> 16) & 0xFF, fgC = (fg >> 8) & 0xFF, fb = fg & 0xFF;
        int br = (bg >> 16) & 0xFF, bgC = (bg >> 8) & 0xFF, bb = bg & 0xFF;
        int or = (fr * fa + br * baInv) / oa;
        int og = (fgC * fa + bgC * baInv) / oa;
        int ob = (fb * fa + bb * baInv) / oa;
        return (oa << 24) | (or << 16) | (og << 8) | ob;
    }
}
