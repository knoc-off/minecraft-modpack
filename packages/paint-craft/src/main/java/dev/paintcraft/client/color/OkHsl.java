package dev.paintcraft.client.color;

/**
 * Minimal OkLab conversion for extracting perceptually uniform hue and lightness
 * from sRGB colors. Used to place block colors on a 2D color picker square.
 *
 * Conversion chain: sRGB → linear RGB → OkLab → polar (hue, lightness).
 * We only need hue and lightness for plotting; saturation is not computed.
 */
public final class OkHsl {

    private OkHsl() {}

    /**
     * Result record holding hue in degrees [0, 360) and lightness [0, 1].
     * For achromatic colors (grays), hue is 0 by convention.
     */
    public record HueLightness(float hue, float lightness) {}

    /**
     * Convert a packed ARGB int to its OkLab-derived hue and lightness.
     * Alpha channel is ignored.
     */
    public static HueLightness fromArgb(int argb) {
        // Unpack sRGB [0,255]
        int ri = (argb >> 16) & 0xFF;
        int gi = (argb >> 8) & 0xFF;
        int bi = argb & 0xFF;

        // sRGB to linear RGB [0,1]
        float r = srgbToLinear(ri / 255f);
        float g = srgbToLinear(gi / 255f);
        float b = srgbToLinear(bi / 255f);

        // Linear RGB to OkLab via the two-step matrix method (Bjorn Ottosson)
        // Step 1: RGB to LMS (approximate cone response)
        float l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b;
        float m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b;
        float s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b;

        // Step 2: cube root (perceptual nonlinearity)
        float l_ = cbrtf(l);
        float m_ = cbrtf(m);
        float s_ = cbrtf(s);

        // Step 3: LMS' to OkLab
        float L = 0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_;
        float A = 1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_;
        float B = 0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_;

        // Polar: hue = atan2(B, A), lightness = L
        float hue = (float) Math.toDegrees(Math.atan2(B, A));
        if (hue < 0) hue += 360f;

        return new HueLightness(hue, L);
    }

    private static float srgbToLinear(float c) {
        return c <= 0.04045f
            ? c / 12.92f
            : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
    }

    private static float cbrtf(float x) {
        return (float) Math.cbrt(x);
    }
}
