package dev.paintcraft.core;

/**
 * Per-pixel PBR material data in LabPBR 1.3 format.
 * All values stored as unsigned bytes (0-255), packed into two ints
 * matching the normal and specular atlas layout that Iris/NeOculus expects.
 */
public record MaterialSample(
    int normalX,    // tangent-space X, 128 = flat (DirectX Y- convention)
    int normalY,    // tangent-space Y, 128 = flat
    int ao,         // material AO, 255 = no occlusion
    int height,     // parallax heightmap
    int smoothness, // 0 = rough sandpaper, 255 = mirror
    int f0,         // reflectance at normal incidence. 0-229 = dielectric, 230-255 = hardcoded metals
    int porosity,   // 0 = non-porous
    int emission    // 0 = none, 254 = full brightness
) {
    public static final MaterialSample DEFAULT = new MaterialSample(128, 128, 255, 0, 64, 10, 0, 0);
    public static final MaterialSample METAL = new MaterialSample(128, 128, 255, 0, 200, 230, 0, 0);
    public static final MaterialSample EMISSIVE = new MaterialSample(128, 128, 255, 0, 64, 10, 0, 254);

    /** Pack into LabPBR normal texture pixel: RGBA = normalX, normalY, ao, height. */
    public int packNormal() {
        return ((ao & 0xFF) << 24) | ((normalX & 0xFF) << 16) | ((normalY & 0xFF) << 8) | (height & 0xFF);
    }

    /** Pack into LabPBR specular texture pixel: RGBA = smoothness, f0, porosity, emission. */
    public int packSpecular() {
        return ((emission & 0xFF) << 24) | ((smoothness & 0xFF) << 16) | ((f0 & 0xFF) << 8) | (porosity & 0xFF);
    }

    public static MaterialSample unpackNormalSpecular(int normalPacked, int specularPacked) {
        return new MaterialSample(
            (normalPacked >> 16) & 0xFF,
            (normalPacked >> 8) & 0xFF,
            (normalPacked >> 24) & 0xFF,
            normalPacked & 0xFF,
            (specularPacked >> 16) & 0xFF,
            (specularPacked >> 8) & 0xFF,
            specularPacked & 0xFF,
            (specularPacked >> 24) & 0xFF
        );
    }

    /** Linearly interpolate all channels. Used by the color picker's barycentric blending. */
    public static MaterialSample lerp(MaterialSample a, MaterialSample b, MaterialSample c,
                                       float wa, float wb, float wc) {
        return new MaterialSample(
            clamp(wa * a.normalX + wb * b.normalX + wc * c.normalX),
            clamp(wa * a.normalY + wb * b.normalY + wc * c.normalY),
            clamp(wa * a.ao + wb * b.ao + wc * c.ao),
            clamp(wa * a.height + wb * b.height + wc * c.height),
            clamp(wa * a.smoothness + wb * b.smoothness + wc * c.smoothness),
            clamp(wa * a.f0 + wb * b.f0 + wc * c.f0),
            clamp(wa * a.porosity + wb * b.porosity + wc * c.porosity),
            clamp(wa * a.emission + wb * b.emission + wc * c.emission)
        );
    }

    private static int clamp(float v) {
        return Math.min(255, Math.max(0, Math.round(v)));
    }
}
