package dev.paintcraft.client.gui.widget;

import com.mojang.blaze3d.platform.NativeImage;
import dev.paintcraft.client.color.BlockColorCache;
import dev.paintcraft.client.color.Delaunay2D;
import dev.paintcraft.client.color.OkHsl;
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
 * - Hard: Delaunay of real block-texture seeds only (larger triangles, coarser blending)
 * - Soft: Real seeds + bridge seeds along Delaunay edges (denser triangles, finer gradients)
 *
 * X axis = hue (0°–360°), Y axis = lightness (1 at top, 0 at bottom).
 * Clicking returns the exact barycentric-interpolated color at that pixel.
 */
public class ColorSquareWidget extends AbstractWidget {

    private static final int TEX_W = 128;
    private static final int TEX_H = 128;

    // Bridge seed generation: subdivision step size in normalized [0,1]×[0,1] space
    private static final float STEP_SIZE = 0.04f;

    private final IntConsumer onColorPicked;
    private final DynamicTexture texture;
    private final ResourceLocation textureLoc;

    // Real seeds from block textures
    private final List<SeedColor> seeds = new ArrayList<>();
    // Synthetic bridge seeds generated along Delaunay edges
    private final List<SeedColor> bridgeSeeds = new ArrayList<>();

    // Cached triangulation for click/hover (reused after repaint)
    private List<SeedColor> activeSeeds = List.of();
    private float[] triXs, triYs;  // normalized coords of active seeds
    private int[] triIndices;       // triangle vertex index triplets
    private int triCount;           // number of triangles

    // Cached per-pixel color map for fast click lookup
    private int[] pixelColors;

    // Mode toggle: true = soft (real + bridge seeds), false = hard (real seeds only)
    private boolean soft = true;

    public ColorSquareWidget(int x, int y, int width, int height, IntConsumer onColorPicked) {
        super(x, y, width, height, Component.literal("Color Picker"));
        this.onColorPicked = onColorPicked;

        NativeImage image = new NativeImage(TEX_W, TEX_H, true);
        fillSolid(image, 0xFF1A1A1A);
        this.texture = new DynamicTexture(image);
        this.textureLoc = Minecraft.getInstance().getTextureManager()
            .register("paintcraft_colorpicker", this.texture);
    }

    public boolean isSoft() { return soft; }

    public void setSoft(boolean soft) {
        if (this.soft != soft) {
            this.soft = soft;
            if (!seeds.isEmpty()) repaint();
        }
    }

    public void toggleMode() {
        setSoft(!soft);
    }

    /**
     * Rebuild seeds from the current block set, generate bridge seeds, and repaint.
     */
    public void rebuild(List<Block> blocks) {
        seeds.clear();
        bridgeSeeds.clear();

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

        // Add boundary anchors so the Delaunay covers the full square
        addBoundaryAnchors();

        if (seeds.size() >= 3 && seeds.size() <= 300) {
            generateBridgeSeeds();
        }

        repaint();
    }

    /**
     * Inject synthetic anchor seeds at corners + edge midpoints of the color space.
     * Ensures the Delaunay convex hull covers the full [0°,360°] × [0,1] square.
     */
    private void addBoundaryAnchors() {
        float CHROMA = 0.12f;

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

        // Left edge midpoints (H=0°, saturated at that hue)
        addAnchorSeed(0f, 0.33f, OkHsl.toArgb(0f, 0.33f, CHROMA));
        addAnchorSeed(0f, 0.67f, OkHsl.toArgb(0f, 0.67f, CHROMA));

        // Right edge midpoints (H=360°, same hue as 0°)
        addAnchorSeed(360f, 0.33f, OkHsl.toArgb(0f, 0.33f, CHROMA));
        addAnchorSeed(360f, 0.67f, OkHsl.toArgb(0f, 0.67f, CHROMA));
    }

    private void addAnchorSeed(float hue, float lightness, int argb) {
        seeds.add(new SeedColor(hue, lightness, argb,
            linearR(argb), linearG(argb), linearB(argb)));
    }

    /**
     * Generate bridge seeds along Delaunay edges of the real seeds.
     * Uses the triangulation's natural neighbor edges instead of K-nearest.
     */
    private void generateBridgeSeeds() {
        int n = seeds.size();
        float[] xs = new float[n];
        float[] ys = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = seeds.get(i).hue / 360f;
            ys[i] = seeds.get(i).lightness;
        }

        Delaunay2D.Result result = Delaunay2D.triangulate(xs, ys, n);

        for (Delaunay2D.Edge edge : result.edges()) {
            SeedColor a = seeds.get(edge.a());
            SeedColor b = seeds.get(edge.b());

            // Distance in normalized space
            float dh = (a.hue - b.hue) / 360f;
            float dl = a.lightness - b.lightness;
            float dist = (float) Math.sqrt(dh * dh + dl * dl);

            int steps = (int) (dist / STEP_SIZE);
            if (steps < 2) continue;

            for (int s = 1; s < steps; s++) {
                float t = s / (float) steps;

                float h = a.hue + (b.hue - a.hue) * t;
                float l = a.lightness + (b.lightness - a.lightness) * t;

                // Linear RGB lerp
                float rLin = a.linR + (b.linR - a.linR) * t;
                float gLin = a.linG + (b.linG - a.linG) * t;
                float bLin = a.linB + (b.linB - a.linB) * t;

                int ri = clamp255(linearToSrgb(rLin) * 255f + 0.5f);
                int gi = clamp255(linearToSrgb(gLin) * 255f + 0.5f);
                int bi = clamp255(linearToSrgb(bLin) * 255f + 0.5f);
                int argb = 0xFF000000 | (ri << 16) | (gi << 8) | bi;

                bridgeSeeds.add(new SeedColor(h, l, argb, rLin, gLin, bLin));
            }
        }
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

        // Determine active seeds for this mode
        activeSeeds = soft ? combinedSeeds() : new ArrayList<>(seeds);
        int n = activeSeeds.size();

        // Build normalized coordinate arrays
        triXs = new float[n];
        triYs = new float[n];
        for (int i = 0; i < n; i++) {
            triXs[i] = activeSeeds.get(i).hue / 360f;
            triYs[i] = activeSeeds.get(i).lightness;
        }

        // Triangulate
        Delaunay2D.Result result = Delaunay2D.triangulate(triXs, triYs, n);
        triIndices = result.triangles();
        triCount = result.numTriangles();

        // Pixel color buffer
        pixelColors = new int[TEX_W * TEX_H];
        boolean[] covered = new boolean[TEX_W * TEX_H];

        // Rasterize each triangle with barycentric interpolation
        for (int t = 0; t < triCount; t++) {
            int i0 = triIndices[t * 3];
            int i1 = triIndices[t * 3 + 1];
            int i2 = triIndices[t * 3 + 2];

            SeedColor s0 = activeSeeds.get(i0);
            SeedColor s1 = activeSeeds.get(i1);
            SeedColor s2 = activeSeeds.get(i2);

            // Triangle vertices in pixel space
            float px0 = triXs[i0] * TEX_W, py0 = (1f - triYs[i0]) * TEX_H;
            float px1 = triXs[i1] * TEX_W, py1 = (1f - triYs[i1]) * TEX_H;
            float px2 = triXs[i2] * TEX_W, py2 = (1f - triYs[i2]) * TEX_H;

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
                        img.setPixelRGBA(px, py, argbToAbgr(argb));
                    }
                }
            }
        }

        // Exterior fallback: uncovered pixels get nearest seed color
        for (int py = 0; py < TEX_H; py++) {
            for (int px = 0; px < TEX_W; px++) {
                int idx = py * TEX_W + px;
                if (!covered[idx]) {
                    float h = (px + 0.5f) / TEX_W * 360f;
                    float l = 1f - (py + 0.5f) / TEX_H;
                    SeedColor nearest = findNearestIn(h, l, activeSeeds);
                    int argb = nearest != null ? nearest.argb : 0xFF1A1A1A;
                    pixelColors[idx] = argb;
                    img.setPixelRGBA(px, py, argbToAbgr(argb));
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
                            img.setPixelRGBA(px, py, argbToAbgr(seed.argb));
                        } else {
                            img.setPixelRGBA(px, py, 0xFFFFFFFF); // white border (ABGR)
                        }
                    }
                }
            }
        }

        // Draw bridge seed dots in soft mode: 1×1, subtle 20% alpha
        if (soft) {
            for (SeedColor bridge : bridgeSeeds) {
                int px = hueToPixelX(bridge.hue);
                int py = lightnessToPixelY(bridge.lightness);
                if (px >= 0 && px < TEX_W && py >= 0 && py < TEX_H) {
                    img.setPixelRGBA(px, py, 0x33888888);
                }
            }
        }

        texture.upload();
    }

    /**
     * Fallback for < 3 seeds: simple nearest-seed fill.
     */
    private void repaintNearestFallback(NativeImage img) {
        List<SeedColor> all = seeds;
        pixelColors = new int[TEX_W * TEX_H];
        activeSeeds = new ArrayList<>(seeds);
        triCount = 0;

        for (int py = 0; py < TEX_H; py++) {
            float l = 1f - (py + 0.5f) / TEX_H;
            for (int px = 0; px < TEX_W; px++) {
                float h = (px + 0.5f) / TEX_W * 360f;
                SeedColor nearest = findNearestIn(h, l, all);
                int argb = nearest != null ? nearest.argb : 0xFF1A1A1A;
                int idx = py * TEX_W + px;
                pixelColors[idx] = argb;
                img.setPixelRGBA(px, py, argbToAbgr(argb));
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
                            img.setPixelRGBA(px, py, argbToAbgr(seed.argb));
                        } else {
                            img.setPixelRGBA(px, py, 0xFFFFFFFF);
                        }
                    }
                }
            }
        }
    }

    private List<SeedColor> combinedSeeds() {
        List<SeedColor> combined = new ArrayList<>(seeds.size() + bridgeSeeds.size());
        combined.addAll(seeds);
        combined.addAll(bridgeSeeds);
        return combined;
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
                // Map pixel back to screen coords for crosshair
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

    // --- Coordinate conversions ---

    private static int hueToPixelX(float hue) {
        return Math.min((int) (hue / 360f * TEX_W), TEX_W - 1);
    }

    private static int lightnessToPixelY(float lightness) {
        return Math.min((int) ((1f - lightness) * TEX_H), TEX_H - 1);
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

    private static int argbToAbgr(int argb) {
        int a = argb & 0xFF000000;
        int r = (argb >> 16) & 0xFF;
        int g = argb & 0x0000FF00;
        int b = (argb & 0xFF) << 16;
        return a | b | g | r;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput narration) {
        defaultButtonNarrationText(narration);
    }

    private record SeedColor(float hue, float lightness, int argb,
                             float linR, float linG, float linB) {}
}
