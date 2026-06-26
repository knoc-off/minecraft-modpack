package dev.paintcraft.client;

import dev.paintcraft.core.ColorFormat;
import dev.paintcraft.core.Decal;
import dev.paintcraft.core.FaceFrame;
import dev.paintcraft.projection.Projection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Composites the decal layers sitting BENEATH the one being edited into the editor's captured block
 * background, so the paint screen shows the same stacked context you see in the world. The current
 * layer is excluded (it's drawn live as the editable layer); only siblings with a lower zOrder are
 * blended in, lowest first, exactly as they appear in-world (full opacity over the block).
 *
 * <p>Coordinates are matched to {@link BackgroundCapture}: each output pixel is mapped to its world
 * point on the face surface (display frame, V-flipped so row 0 = top), then each sibling is sampled
 * by projecting that world point into its own stored pixel grid. This is orientation-independent, so
 * rotated floor/ceiling siblings line up without any per-decal anchor gymnastics.
 */
public final class EditorUnderlay {

    private EditorUnderlay() {}

    private record Layer(Projection proj, int[] pixels, int wPx, int hPx, int wb, int hb) {}

    /**
     * Blend under-layers into {@code background} (mutated in place and returned).
     *
     * @param anchor       same anchor passed to {@link BackgroundCapture#capture} for this canvas
     * @param displayFrame the capture/display frame (face + display up)
     * @param currentId    the decal being edited, excluded from the underlay; null for a new decal
     *                     (then all siblings on this face are treated as under-layers)
     */
    public static int[] build(int[] background, BlockPos anchor, FaceFrame displayFrame,
                              int widthBlocks, int heightBlocks, float depth, UUID currentId) {
        int wPx = widthBlocks * Decal.PX_PER_BLOCK;
        int hPx = heightBlocks * Decal.PX_PER_BLOCK;

        Projection canvas = new Projection(displayFrame, anchor, widthBlocks, heightBlocks, depth);
        AABB canvasBounds = canvas.toBoundingBox().inflate(0.01);

        long zCur = Long.MAX_VALUE;
        if (currentId != null) {
            Decal cur = ClientDecalCache.get(currentId);
            if (cur != null) zCur = cur.zOrder();
        }

        // Collect under-layer siblings on the same face, sorted lowest zOrder first.
        List<Decal> siblings = new ArrayList<>();
        for (Decal d : ClientDecalCache.all()) {
            if (d.normal() != displayFrame.normal()) continue;
            if (currentId != null && d.id().equals(currentId)) continue;
            if (d.zOrder() >= zCur) continue;
            if (!Projection.fromDecal(d).toBoundingBox().intersects(canvasBounds)) continue;
            siblings.add(d);
        }
        if (siblings.isEmpty()) return background;
        siblings.sort(Comparator.comparingLong(Decal::zOrder));

        List<Layer> layers = new ArrayList<>(siblings.size());
        for (Decal d : siblings) {
            layers.add(new Layer(Projection.fromDecal(d), d.pixels(),
                d.widthPx(), d.heightPx(), d.widthBlocks(), d.heightBlocks()));
        }

        for (int oy = 0; oy < hPx; oy++) {
            // V-flip matches BackgroundCapture: output row 0 = top of face in world.
            float localV = (hPx - 0.5f - oy) / hPx * heightBlocks;
            for (int col = 0; col < wPx; col++) {
                float localU = (col + 0.5f) / wPx * widthBlocks;
                Vec3 world = canvas.localToWorld(localU, localV, 0.0);

                int acc = background[oy * wPx + col];
                for (Layer L : layers) {
                    Vec3 l = L.proj.worldToLocal(world);
                    if (l.x < 0 || l.x >= L.wb || l.y < 0 || l.y >= L.hb) continue;
                    int sx = (int) (l.x / L.wb * L.wPx);
                    // Stored decal pixels are top-down (row 0 = visual top), while local v is
                    // bottom-up — flip the row to match (see CellCompositor.blitDecalPixels).
                    int sy = L.hPx - 1 - (int) (l.y / L.hb * L.hPx);
                    if (sx < 0) sx = 0; else if (sx >= L.wPx) sx = L.wPx - 1;
                    if (sy < 0) sy = 0; else if (sy >= L.hPx) sy = L.hPx - 1;
                    int c = L.pixels[sy * L.wPx + sx];
                    if ((c >>> 24) != 0) {
                        acc = ColorFormat.alphaOver(c, acc);
                    }
                }
                background[oy * wPx + col] = acc;
            }
        }

        return background;
    }
}
