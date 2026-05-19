package dev.paintcraft.client.color;

import dev.paintcraft.core.MaterialSample;

/**
 * Generates tangent-space normal maps from albedo textures by treating
 * luminance as a heightmap and computing the Sobel gradient.
 * Produces LabPBR DirectX-convention normals (Y- / top-down).
 */
public final class NormalMapGenerator {

    private static final float STRENGTH = 2.0f;

    private NormalMapGenerator() {}

    /**
     * Given an ARGB pixel array (w x h), produce an array of packed LabPBR normal values.
     * Each output int = (ao << 24) | (normalX << 16) | (normalY << 8) | height
     */
    public static int[] generate(int[] argb, int w, int h) {
        float[] heightmap = new float[w * h];
        for (int i = 0; i < argb.length; i++) {
            heightmap[i] = luminance(argb[i]);
        }

        int[] normals = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // Sobel gradient with wrapping (textures tile)
                float tl = sample(heightmap, x - 1, y - 1, w, h);
                float t  = sample(heightmap, x,     y - 1, w, h);
                float tr = sample(heightmap, x + 1, y - 1, w, h);
                float l  = sample(heightmap, x - 1, y,     w, h);
                float r  = sample(heightmap, x + 1, y,     w, h);
                float bl = sample(heightmap, x - 1, y + 1, w, h);
                float b  = sample(heightmap, x,     y + 1, w, h);
                float br = sample(heightmap, x + 1, y + 1, w, h);

                float dx = (tr + 2 * r + br) - (tl + 2 * l + bl);
                float dy = (bl + 2 * b + br) - (tl + 2 * t + tr);

                float nx = -dx * STRENGTH;
                float ny = dy * STRENGTH; // LabPBR DirectX convention: Y inverted
                float nz = 1.0f;
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                nx /= len;
                ny /= len;

                int encodedX = Math.round(nx * 127.0f + 128.0f);
                int encodedY = Math.round(ny * 127.0f + 128.0f);
                int heightVal = Math.round(heightmap[y * w + x] * 255.0f);

                encodedX = Math.clamp(encodedX, 0, 255);
                encodedY = Math.clamp(encodedY, 0, 255);
                heightVal = Math.clamp(heightVal, 0, 255);

                normals[y * w + x] = (255 << 24) | (encodedX << 16) | (encodedY << 8) | heightVal;
            }
        }
        return normals;
    }

    private static float sample(float[] map, int x, int y, int w, int h) {
        x = ((x % w) + w) % w;
        y = ((y % h) + h) % h;
        return map[y * w + x];
    }

    private static float luminance(int argb) {
        float r = ((argb >> 16) & 0xFF) / 255.0f;
        float g = ((argb >> 8) & 0xFF) / 255.0f;
        float b = (argb & 0xFF) / 255.0f;
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }
}
