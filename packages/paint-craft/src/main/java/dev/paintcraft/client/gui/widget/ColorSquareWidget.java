package dev.paintcraft.client.gui.widget;

import com.mojang.blaze3d.platform.NativeImage;
import dev.paintcraft.client.color.BlockColorCache;
import dev.paintcraft.client.color.Delaunay2D;
import dev.paintcraft.client.color.OkHsl;
import dev.paintcraft.core.ColorFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * A 2D color picker square that plots block-texture colors in OkHSL space
 * using Delaunay triangulation with barycentric color interpolation.
 *
 * Two modes:
 * - Smooth: Real seeds + achromatic boundary anchors → full coverage, neutral gray edges
 * - Raw: Real seeds only → shows only true block colors, edges may be clipped
 *
 * X axis = hue (0°–360°), Y axis = lightness (1 at top, 0 at bottom).
 * Clicking returns the exact barycentric-interpolated color at that pixel.
 */
public class ColorSquareWidget extends AbstractWidget {

    private static final int TEX_W = 128;
    private static final int TEX_H = 128;

    private final IntConsumer onColorPicked;
    private final DynamicTexture texture;
    private final ResourceLocation textureLoc;

    // Seeds used for triangulation (real + optional boundary anchors)
    private final List<SeedColor> seeds = new ArrayList<>();

    // Cached block list for rebuild on toggle
    private List<Block> lastBlocks = List.of();

    // Cached per-pixel color map for fast click lookup
    private int[] pixelColors;

    // Mode toggle: true = full (with boundary anchors), false = focused (zoomed to seeds)
    private boolean smooth = true;

    // View bounds for coordinate mapping (hue in degrees, lightness in [0,1])
    private float viewMinHue = 0f, viewMaxHue = 360f;
    private float viewMinL = 0f, viewMaxL = 1f;

    public ColorSquareWidget(int x, int y, int width, int height, IntConsumer onColorPicked) {
        super(x, y, width, height, Component.literal("Color Picker"));
        this.onColorPicked = onColorPicked;

        NativeImage image = new NativeImage(TEX_W, TEX_H, true);
        fillSolid(image, 0xFF1A1A1A);
        this.texture = new DynamicTexture(image);
        this.textureLoc = Minecraft.getInstance().getTextureManager()
            .register("paintcraft_colorpicker", this.texture);
    }

    public boolean isSmooth() { return smooth; }

    public void setSmooth(boolean smooth) {
        if (this.smooth != smooth) {
            this.smooth = smooth;
            rebuild(lastBlocks);
        }
    }

    public void toggleMode() {
        setSmooth(!smooth);
    }

    /**
     * Rebuild seeds from the current block set and repaint.
     * In full mode, adds achromatic boundary anchors for full coverage.
     * In focused mode, zooms viewport to the seed bounding box.
     */
    public void rebuild(List<Block> blocks) {
        lastBlocks = blocks;
        seeds.clear();

        Set<Integer> seen = new HashSet<>();
        for (Block block : blocks) {
            int[] colors = BlockColorCache.getColors(block);
            for (int c : colors) {
                if (seen.add(c)) {
                    OkHsl.HueLightness hl = OkHsl.fromArgb(c);
                    seeds.add(new SeedColor(hl.hue(), hl.lightness(), c,
                        linearR(c), linearG(c), linearB(c)));
                }
            }
        }

        if (smooth) {
            // Full mode: show entire color space
            viewMinHue = 0f; viewMaxHue = 360f;
            viewMinL = 0f; viewMaxL = 1f;
            addBoundaryAnchors();
        } else {
            // Focused mode: zoom to seed bounding box with padding
            if (!seeds.isEmpty()) {
                float minH = Float.MAX_VALUE, maxH = -Float.MAX_VALUE;
                float minL = Float.MAX_VALUE, maxL = -Float.MAX_VALUE;
                for (SeedColor s : seeds) {
                    minH = Math.min(minH, s.hue());
                    maxH = Math.max(maxH, s.hue());
                    minL = Math.min(minL, s.lightness());
                    maxL = Math.max(maxL, s.lightness());
                }
                float padH = Math.max(10f, (maxH - minH) * 0.1f);
                float padL = Math.max(0.05f, (maxL - minL) * 0.1f);
                viewMinHue = Math.max(0f, minH - padH);
                viewMaxHue = Math.min(360f, maxH + padH);
                viewMinL = Math.max(0f, minL - padL);
                viewMaxL = Math.min(1f, maxL + padL);
            } else {
                viewMinHue = 0f; viewMaxHue = 360f;
                viewMinL = 0f; viewMaxL = 1f;
            }
        }

        repaint();
    }

    /**
     * Inject achromatic anchor seeds at corners + edge midpoints of the color space.
     * All anchors are neutral gray (chroma=0) at their respective lightness.
     * Ensures the Delaunay convex hull covers the full [0°,360°] × [0,1] square.
     */
    private void addBoundaryAnchors() {
        // 4 corners: black at bottom, white at top
        addAnchorSeed(0f, 0f, 0xFF000000);
        addAnchorSeed(360f, 0f, 0xFF000000);
        addAnchorSeed(0f, 1f, 0xFFFFFFFF);
        addAnchorSeed(360f, 1f, 0xFFFFFFFF);

        // Bottom edge midpoints (black)
        addAnchorSeed(120f, 0f, 0xFF000000);
        addAnchorSeed(240f, 0f, 0xFF000000);

        // Top edge midpoints (white)
        addAnchorSeed(120f, 1f, 0xFFFFFFFF);
        addAnchorSeed(240f, 1f, 0xFFFFFFFF);

        // Left edge midpoints (neutral gray, chroma=0)
        addAnchorSeed(0f, 0.33f, OkHsl.toArgb(0f, 0.33f, 0f));
        addAnchorSeed(0f, 0.67f, OkHsl.toArgb(0f, 0.67f, 0f));

        // Right edge midpoints (neutral gray, chroma=0)
        addAnchorSeed(360f, 0.33f, OkHsl.toArgb(0f, 0.33f, 0f));
        addAnchorSeed(360f, 0.67f, OkHsl.toArgb(0f, 0.67f, 0f));
    }

    private void addAnchorSeed(float hue, float lightness, int argb) {
        seeds.add(new SeedColor(hue, lightness, argb,
            linearR(argb), linearG(argb), linearB(argb)));
    }

    /**
     * Repaint using Delaunay triangulation + barycentric color interpolation.
     */
    private void repaint() {
        NativeImage img = texture.getPixels();
        if (img == null) return;

        if (seeds.isEmpty()) {
            fillSolid(img, 0xFF1A1A1A);
            pixelColors = null;
            texture.upload();
            return;
        }

        // Handle degenerate cases (< 3 seeds)
        if (seeds.size() < 3) {
            repaintNearestFallback(img);
            texture.upload();
            return;
        }

        int n = seeds.size();

        // Build normalized coordinate arrays (relative to view bounds)
        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = (seeds.get(i).hue() - viewMinHue) / (viewMaxHue - viewMinHue);
            ys[i] = (seeds.get(i).lightness() - viewMinL) / (viewMaxL - viewMinL);
        }

        // Triangulate
        Delaunay2D.Result result = Delaunay2D.triangulate(xs, ys, n);
        int[] triIndices = result.triangles();
        int triCount = result.numTriangles();

        // Pixel color buffer
        pixelColors = new int[TEX_W * TEX_H];
        boolean[] covered = new boolean[TEX_W * TEX_H];

        // Rasterize each triangle with barycentric interpolation
        for (int t = 0; t < triCount; t++) {
            int i0 = triIndices[t * 3];
            int i1 = triIndices[t * 3 + 1];
            int i2 = triIndices[t * 3 + 2];

            SeedColor s0 = seeds.get(i0);
            SeedColor s1 = seeds.get(i1);
            SeedColor s2 = seeds.get(i2);

            // Triangle vertices in pixel space
            float px0 = xs[i0] * TEX_W, py0 = (1f - ys[i0]) * TEX_H;
            float px1 = xs[i1] * TEX_W, py1 = (1f - ys[i1]) * TEX_H;
            float px2 = xs[i2] * TEX_W, py2 = (1f - ys[i2]) * TEX_H;

            // Bounding box (clamped to texture)
            int minX = Math.max(0, (int) Math.floor(Math.min(px0, Math.min(px1, px2))));
            int maxX = Math.min(TEX_W - 1, (int) Math.ceil(Math.max(px0, Math.max(px1, px2))));
            int minY = Math.max(0, (int) Math.floor(Math.min(py0, Math.min(py1, py2))));
            int maxY = Math.min(TEX_H - 1, (int) Math.ceil(Math.max(py0, Math.max(py1, py2))));

            // Precompute barycentric denominator
            float denom = (py1 - py2) * (px0 - px2) + (px2 - px1) * (py0 - py2);
            if (Math.abs(denom) < 1e-6f) continue; // degenerate triangle

            float invDenom = 1f / denom;

            for (int py = minY; py <= maxY; py++) {
                for (int px = minX; px <= maxX; px++) {
                    float ppx = px + 0.5f;
                    float ppy = py + 0.5f;

                    // Barycentric coordinates
                    float w0 = ((py1 - py2) * (ppx - px2) + (px2 - px1) * (ppy - py2)) * invDenom;
                    float w1 = ((py2 - py0) * (ppx - px2) + (px0 - px2) * (ppy - py2)) * invDenom;
                    float w2 = 1f - w0 - w1;

                    if (w0 >= 0f && w1 >= 0f && w2 >= 0f) {
                        // Inside triangle — interpolate in linear RGB
                        float rLin = w0 * s0.linR + w1 * s1.linR + w2 * s2.linR;
                        float gLin = w0 * s0.linG + w1 * s1.linG + w2 * s2.linG;
                        float bLin = w0 * s0.linB + w1 * s1.linB + w2 * s2.linB;

                        int ri = clamp255(linearToSrgb(rLin) * 255f + 0.5f);
                        int gi = clamp255(linearToSrgb(gLin) * 255f + 0.5f);
                        int bi = clamp255(linearToSrgb(bLin) * 255f + 0.5f);
                        int argb = 0xFF000000 | (ri << 16) | (gi << 8) | bi;

                        int idx = py * TEX_W + px;
                        pixelColors[idx] = argb;
                        covered[idx] = true;
                        img.setPixelRGBA(px, py, ColorFormat.argbToAbgr(argb));
                    }
                }
            }
        }

        // Exterior fallback: uncovered pixels get nearest seed color
        for (int py = 0; py < TEX_H; py++) {
            for (int px = 0; px < TEX_W; px++) {
                int idx = py * TEX_W + px;
                if (!covered[idx]) {
                    float h = pixelXToHue(px);
                    float l = pixelYToLightness(py);
                    SeedColor nearest = findNearestIn(h, l, seeds);
                    int argb = nearest != null ? nearest.argb : 0xFF1A1A1A;
                    pixelColors[idx] = argb;
                    img.setPixelRGBA(px, py, ColorFormat.argbToAbgr(argb));
                }
            }
        }

        // Draw real seed dots: 3×3 with white outline
        for (SeedColor seed : seeds) {
            int cx = hueToPixelX(seed.hue);
            int cy = lightnessToPixelY(seed.lightness);
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int px = cx + dx;
                    int py = cy + dy;
                    if (px >= 0 && px < TEX_W && py >= 0 && py < TEX_H) {
                        if (dx == 0 && dy == 0) {
                            img.setPixelRGBA(px, py, ColorFormat.argbToAbgr(seed.argb));
                        } else {
                            img.setPixelRGBA(px, py, 0xFFFFFFFF); // white border (ABGR)
                        }
                    }
                }
            }
        }

        texture.upload();
    }

    /**
     * Fallback for < 3 seeds: simple nearest-seed fill.
     */
    private void repaintNearestFallback(NativeImage img) {
        pixelColors = new int[TEX_W * TEX_H];

        for (int py = 0; py < TEX_H; py++) {
            for (int px = 0; px < TEX_W; px++) {
                float h = pixelXToHue(px);
                float l = pixelYToLightness(py);
                SeedColor nearest = findNearestIn(h, l, seeds);
                int argb = nearest != null ? nearest.argb : 0xFF1A1A1A;
                int idx = py * TEX_W + px;
                pixelColors[idx] = argb;
                img.setPixelRGBA(px, py, ColorFormat.argbToAbgr(argb));
            }
        }

        // Seed dots
        for (SeedColor seed : seeds) {
            int cx = hueToPixelX(seed.hue);
            int cy = lightnessToPixelY(seed.lightness);
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int px = cx + dx;
                    int py = cy + dy;
                    if (px >= 0 && px < TEX_W && py >= 0 && py < TEX_H) {
                        if (dx == 0 && dy == 0) {
                            img.setPixelRGBA(px, py, ColorFormat.argbToAbgr(seed.argb));
                        } else {
                            img.setPixelRGBA(px, py, 0xFFFFFFFF);
                        }
                    }
                }
            }
        }
    }

    @Override
    protected void renderWidget(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        gfx.blit(textureLoc, getX(), getY(), 0, 0, width, height, width, height);
        gfx.renderOutline(getX() - 1, getY() - 1, width + 2, height + 2, 0xFF444444);

        // Hover crosshair
        if (isHovered() && pixelColors != null) {
            int px = screenToPixelX(mouseX);
            int py = screenToPixelY(mouseY);
            if (px >= 0 && px < TEX_W && py >= 0 && py < TEX_H) {
                int sx = getX() + (int)(px * (float) width / TEX_W);
                int sy = getY() + (int)(py * (float) height / TEX_H);
                gfx.fill(sx - 4, sy, sx + 5, sy + 1, 0xFFFFFFFF);
                gfx.fill(sx, sy - 4, sx + 1, sy + 5, 0xFFFFFFFF);
            }
        }
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        if (pixelColors == null) return;
        int px = screenToPixelX((int) mouseX);
        int py = screenToPixelY((int) mouseY);
        if (px >= 0 && px < TEX_W && py >= 0 && py < TEX_H) {
            int color = pixelColors[py * TEX_W + px];
            onColorPicked.accept(color);
        }
    }

    /** Release the DynamicTexture from TextureManager. Call from PaintScreen.removed(). */
    public void close() {
        Minecraft.getInstance().getTextureManager().release(textureLoc);
    }

    // --- Coordinate conversions (view-bounds aware) ---

    private int hueToPixelX(float hue) {
        float t = (hue - viewMinHue) / (viewMaxHue - viewMinHue);
        return Math.min(TEX_W - 1, Math.max(0, (int) (t * TEX_W)));
    }

    private int lightnessToPixelY(float lightness) {
        float t = 1f - (lightness - viewMinL) / (viewMaxL - viewMinL);
        return Math.min(TEX_H - 1, Math.max(0, (int) (t * TEX_H)));
    }

    private float pixelXToHue(int px) {
        return viewMinHue + (px + 0.5f) / TEX_W * (viewMaxHue - viewMinHue);
    }

    private float pixelYToLightness(int py) {
        return viewMaxL - (py + 0.5f) / TEX_H * (viewMaxL - viewMinL);
    }

    private int screenToPixelX(int screenX) {
        float t = (screenX - getX()) / (float) width;
        return Math.max(0, Math.min(TEX_W - 1, (int) (t * TEX_W)));
    }

    private int screenToPixelY(int screenY) {
        float t = (screenY - getY()) / (float) height;
        return Math.max(0, Math.min(TEX_H - 1, (int) (t * TEX_H)));
    }

    // --- Distance (for fallback nearest-seed) ---

    private static float dist2(float h, float l, SeedColor seed) {
        float dh = (seed.hue - h) / 360f;
        float dl = seed.lightness - l;
        return dh * dh + dl * dl;
    }

    private static SeedColor findNearestIn(float h, float l, List<SeedColor> list) {
        SeedColor best = null;
        float bestDist = Float.MAX_VALUE;
        for (SeedColor seed : list) {
            float dist = dist2(h, l, seed);
            if (dist < bestDist) {
                bestDist = dist;
                best = seed;
            }
        }
        return best;
    }

    // --- Color space helpers ---

    private static float srgbToLinear(float c) {
        return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
    }

    private static float linearToSrgb(float c) {
        return c <= 0.0031308f ? c * 12.92f : 1.055f * (float) Math.pow(c, 1.0f / 2.4f) - 0.055f;
    }

    private static float linearR(int argb) { return srgbToLinear(((argb >> 16) & 0xFF) / 255f); }
    private static float linearG(int argb) { return srgbToLinear(((argb >> 8) & 0xFF) / 255f); }
    private static float linearB(int argb) { return srgbToLinear((argb & 0xFF) / 255f); }

    private static int clamp255(float v) {
        return Math.min(255, Math.max(0, (int) v));
    }

    // --- Pixel format ---

    private static void fillSolid(NativeImage img, int abgr) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                img.setPixelRGBA(x, y, abgr);
            }
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        defaultButtonNarrationText(narration);
    }

    private record SeedColor(float hue, float lightness, int argb,
                             float linR, float linG, float linB) {}
}
