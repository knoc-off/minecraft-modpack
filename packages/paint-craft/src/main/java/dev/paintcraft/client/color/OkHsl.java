package dev.paintcraft.client.color;

import dev.paintcraft.core.color.OkLab;

/**
 * OkLab color space conversions for the paint editor color picker.
 *
 * Forward: sRGB → linear RGB → OkLab → polar (hue, lightness).
 * Inverse: polar (hue, lightness, chroma) → OkLab → linear RGB → sRGB.
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
        OkLab.Lab lab = OkLab.fromArgb(argb);

        // Polar: hue = atan2(b, a), lightness = L
        float hue = (float) Math.toDegrees(Math.atan2(lab.b(), lab.a()));
        if (hue < 0) hue += 360f;

        return new HueLightness(hue, lab.L());
    }

    /**
     * Convert OkLab polar coordinates (hue, lightness, chroma) to a packed ARGB int.
     * Out-of-gamut values are clamped to [0, 1] per channel.
     *
     * @param hueDeg   Hue in degrees [0, 360)
     * @param lightness OkLab L in [0, 1]
     * @param chroma   OkLab chroma (distance from neutral axis), typically 0–0.3
     */
    public static int toArgb(float hueDeg, float lightness, float chroma) {
        float hRad = (float) Math.toRadians(hueDeg);
        float L = lightness;
        float A = chroma * (float) Math.cos(hRad);
        float B = chroma * (float) Math.sin(hRad);

        // OkLab → LMS' (inverse of step 3)
        float l_ = L + 0.3963377774f * A + 0.2158037573f * B;
        float m_ = L - 0.1055613458f * A - 0.0638541728f * B;
        float s_ = L - 0.0894841775f * A - 1.2914855480f * B;

        // LMS' → LMS (cube, inverse of step 2)
        float l = l_ * l_ * l_;
        float m = m_ * m_ * m_;
        float s = s_ * s_ * s_;

        // LMS → linear RGB (inverse of step 1)
        float r = +4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s;
        float g = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s;
        float b = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s;

        // Gamut clamp + linear to sRGB
        int ri = clamp255(linearToSrgb(Math.max(0f, Math.min(1f, r))) * 255f + 0.5f);
        int gi = clamp255(linearToSrgb(Math.max(0f, Math.min(1f, g))) * 255f + 0.5f);
        int bi = clamp255(linearToSrgb(Math.max(0f, Math.min(1f, b))) * 255f + 0.5f);

        return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
    }

    private static float linearToSrgb(float c) {
        return c <= 0.0031308f
            ? c * 12.92f
            : 1.055f * (float) Math.pow(c, 1.0f / 2.4f) - 0.055f;
    }

    private static int clamp255(float v) {
        return Math.min(255, Math.max(0, (int) v));
    }
}
