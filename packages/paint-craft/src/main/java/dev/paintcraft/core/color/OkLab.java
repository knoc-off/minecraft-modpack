package dev.paintcraft.core.color;

/**
 * OkLab color space conversion (Bjorn Ottosson). Pure math with no client or
 * Minecraft dependencies, so it is safe to use on both the client and the server
 * (e.g. for perceptual color matching in cost calculations).
 *
 * <p>Forward path only: sRGB → linear RGB → LMS → cube root → OkLab {@code (L, a, b)}.
 */
public final class OkLab {

    private OkLab() {}

    /** OkLab coordinates: perceptual lightness {@code L} and opponent axes {@code a}, {@code b}. */
    public record Lab(float L, float a, float b) {
        /** Squared Euclidean distance to another OkLab color (cheap nearest-color metric). */
        public float distSq(Lab o) {
            float dl = L - o.L, da = a - o.a, db = b - o.b;
            return dl * dl + da * da + db * db;
        }
    }

    /** Convert a packed ARGB int to OkLab. Alpha is ignored. */
    public static Lab fromArgb(int argb) {
        int ri = (argb >> 16) & 0xFF;
        int gi = (argb >> 8) & 0xFF;
        int bi = argb & 0xFF;

        // sRGB [0,255] → linear RGB [0,1]
        float r = srgbToLinear(ri / 255f);
        float g = srgbToLinear(gi / 255f);
        float b = srgbToLinear(bi / 255f);

        // linear RGB → LMS (approximate cone response)
        float l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b;
        float m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b;
        float s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b;

        // perceptual nonlinearity
        float l_ = (float) Math.cbrt(l);
        float m_ = (float) Math.cbrt(m);
        float s_ = (float) Math.cbrt(s);

        // LMS' → OkLab
        float L = 0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_;
        float A = 1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_;
        float B = 0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_;

        return new Lab(L, A, B);
    }

    private static float srgbToLinear(float c) {
        return c <= 0.04045f
            ? c / 12.92f
            : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
    }
}
