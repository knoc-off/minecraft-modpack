package dev.paintcraft.client;

import dev.paintcraft.client.compat.ChiseledBlockHelper;
import dev.paintcraft.core.Decal;
import dev.paintcraft.core.FaceFrame;
import dev.paintcraft.projection.Projection;
import dev.paintcraft.projection.ProjectionResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.*;

/**
 * Diagnostic version of BackgroundCapture that produces a text report
 * instead of pixels. Triggered by Ctrl+D in the paint editor.
 */
public final class BackgroundCaptureDebug {

    private static final RandomSource RANDOM = RandomSource.createNewThreadLocalInstance();
    private static final boolean HAS_CNB = net.neoforged.fml.ModList.get().isLoaded("chiselsandbits");

    private BackgroundCaptureDebug() {}

    public static String run(BlockAndTintGetter level, BlockPos anchor, Direction face,
                             Direction captureUp, int widthBlocks, int heightBlocks, float depth) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== PaintCraft Background Debug ===\n");
        sb.append("Anchor: ").append(anchor).append('\n');
        sb.append("Face: ").append(face).append('\n');
        sb.append("Dimensions: ").append(widthBlocks).append("x").append(heightBlocks)
          .append(" blocks (").append(widthBlocks * 16).append("x").append(heightBlocks * 16).append(" px)\n");
        sb.append("Depth: ").append(depth).append('\n');
        sb.append("C&B loaded: ").append(net.neoforged.fml.ModList.get().isLoaded("chiselsandbits")).append('\n');
        sb.append('\n');

        int wPx = widthBlocks * Decal.PX_PER_BLOCK;
        int hPx = heightBlocks * Decal.PX_PER_BLOCK;

        Direction up = face.getAxis().isVertical() ? captureUp : Direction.UP;
        FaceFrame frame = new FaceFrame(face, up);
        Projection vol = new Projection(frame, anchor, widthBlocks, heightBlocks, depth);

        sb.append("--- Projection ---\n");
        sb.append("Frame: normal=").append(frame.normal()).append(" up=").append(frame.up())
          .append(" right=").append(frame.right()).append('\n');
        AABB bounds = vol.toBoundingBox();
        sb.append("BoundingBox: [").append(fmt(bounds.minX)).append("..").append(fmt(bounds.maxX))
          .append(", ").append(fmt(bounds.minY)).append("..").append(fmt(bounds.maxY))
          .append(", ").append(fmt(bounds.minZ)).append("..").append(fmt(bounds.maxZ)).append("]\n\n");

        // Collect candidates
        List<ProjectionResolver.FaceCandidate> candidates = new ArrayList<>();
        Map<BlockPos, BlockState> blockStates = new HashMap<>();
        int blocksScanned = 0;
        int nonAirBlocks = 0;

        var minPos = BlockPos.containing(bounds.minX - 1, bounds.minY - 1, bounds.minZ - 1);
        var maxPos = BlockPos.containing(bounds.maxX + 1, bounds.maxY + 1, bounds.maxZ + 1);

        for (BlockPos pos : BlockPos.betweenClosed(minPos, maxPos)) {
            blocksScanned++;
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) continue;
            nonAirBlocks++;
            blockStates.put(pos.immutable(), state);

            VoxelShape shape = state.getShape(level, pos, CollisionContext.empty());
            if (shape.isEmpty()) continue;

            Vec3 blockOrigin = Vec3.atLowerCornerOf(pos);
            for (AABB box : shape.toAabbs()) {
                AABB worldBox = box.move(blockOrigin);
                ProjectionResolver.collectFaces(vol, worldBox, pos.immutable(), face, candidates);
            }
        }

        sb.append("--- Block Scan ---\n");
        sb.append("Blocks scanned: ").append(blocksScanned).append('\n');
        sb.append("Non-air blocks: ").append(nonAirBlocks).append('\n');
        sb.append("Face candidates: ").append(candidates.size()).append('\n');

        // Count unique block positions in candidates
        Set<BlockPos> candidateBlocks = new HashSet<>();
        for (var c : candidates) candidateBlocks.add(c.blockPos());
        sb.append("Unique blocks with candidates: ").append(candidateBlocks.size()).append('\n');
        sb.append('\n');

        // List first 20 candidates
        sb.append("--- Candidates (first 20) ---\n");
        for (int i = 0; i < Math.min(candidates.size(), 20); i++) {
            var c = candidates.get(i);
            BlockState st = blockStates.getOrDefault(c.blockPos(), null);
            sb.append('#').append(i).append(": pos=").append(c.blockPos())
              .append(" face=").append(c.faceNormal())
              .append(" depth=").append(fmt(c.depth()))
              .append(" u=[").append(fmt(c.u0())).append("-").append(fmt(c.u1())).append("]")
              .append(" v=[").append(fmt(c.v0())).append("-").append(fmt(c.v1())).append("]")
              .append(" state=").append(st != null ? st.toString() : "?")
              .append('\n');
        }
        sb.append('\n');

        // Rasterize depth buffer
        float[] depthBuf = new float[wPx * hPx];
        int[] winnerIdx = new int[wPx * hPx];
        Arrays.fill(depthBuf, Float.MAX_VALUE);
        Arrays.fill(winnerIdx, -1);

        for (int ci = 0; ci < candidates.size(); ci++) {
            var c = candidates.get(ci);
            int px0 = Math.clamp((int)(c.u0() / vol.width() * wPx), 0, wPx - 1);
            int py0 = Math.clamp((int)(c.v0() / vol.height() * hPx), 0, hPx - 1);
            int px1 = Math.clamp((int) Math.ceil(c.u1() / vol.width() * wPx) - 1, 0, wPx - 1);
            int py1 = Math.clamp((int) Math.ceil(c.v1() / vol.height() * hPx) - 1, 0, hPx - 1);

            for (int py = py0; py <= py1; py++) {
                for (int px = px0; px <= px1; px++) {
                    int idx = py * wPx + px;
                    if (idx >= 0 && idx < depthBuf.length && c.depth() < depthBuf[idx]) {
                        depthBuf[idx] = c.depth();
                        winnerIdx[idx] = ci;
                    }
                }
            }
        }

        // Pixel stats
        int pixelsWithCandidate = 0;
        for (int i = 0; i < wPx * hPx; i++) {
            if (winnerIdx[i] >= 0) pixelsWithCandidate++;
        }

        sb.append("--- Pixel Stats ---\n");
        sb.append("Total pixels: ").append(wPx * hPx).append('\n');
        sb.append("Pixels with depth hit: ").append(pixelsWithCandidate).append('\n');
        sb.append('\n');

        // Detailed sample for center pixel
        int centerPx = wPx / 2, centerPy = hPx / 2;
        sb.append("--- Sample Detail: Center pixel [").append(centerPx).append(", ").append(centerPy).append("] ---\n");
        appendPixelDebug(sb, level, vol, candidates, winnerIdx, centerPx, centerPy, wPx, hPx);
        sb.append('\n');

        // Find first C&B block and dump its details
        {
            sb.append("--- Modded Block Details (C&B etc.) ---\n");
            boolean foundCnb = false;
            for (int i = 0; i < candidates.size() && !foundCnb; i++) {
                var c = candidates.get(i);
                BlockState st = blockStates.getOrDefault(c.blockPos(), null);
                if (st != null && st.toString().contains("chiselsandbits")) {
                    foundCnb = true;
                    sb.append("C&B block at ").append(c.blockPos()).append(" state=").append(st).append('\n');

                    // Block entity check
                    BlockEntity be = level.getBlockEntity(c.blockPos());
                    sb.append("  BlockEntity present: ").append(be != null).append('\n');
                    if (be != null) {
                        sb.append("  BlockEntity class: ").append(be.getClass().getName()).append('\n');
                    }

                    // Model and quads
                    BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(st);
                    sb.append("  Model class: ").append(model.getClass().getName()).append('\n');

                    List<BakedQuad> vanillaQuads = model.getQuads(st, c.faceNormal(), RANDOM);
                    sb.append("  Vanilla getQuads(").append(c.faceNormal()).append("): ").append(vanillaQuads.size()).append(" quads\n");

                    ModelData modelData = be != null ? be.getModelData() : ModelData.EMPTY;
                    sb.append("  NeoForge ModelData: ").append(modelData == ModelData.EMPTY ? "EMPTY" : modelData.toString()).append('\n');
                    List<BakedQuad> dataQuads = model.getQuads(st, c.faceNormal(), RANDOM, modelData, null);
                    sb.append("  NeoForge data-aware getQuads(").append(c.faceNormal()).append(", null RT): ").append(dataQuads.size()).append(" quads\n");

                    // Scena/C&B direct path (bypass delegate)
                    List<BakedQuad> scenaQuads = HAS_CNB
                        ? ChiseledBlockHelper.getDataAwareQuads(level, c.blockPos(), st, model, c.faceNormal(), RANDOM)
                        : List.of();
                    sb.append("  Scena direct path getQuads(").append(c.faceNormal()).append("): ").append(scenaQuads.size()).append(" quads\n");

                    List<BakedQuad> allQuads = !scenaQuads.isEmpty() ? scenaQuads : (!dataQuads.isEmpty() ? dataQuads : vanillaQuads);
                    for (int q = 0; q < Math.min(allQuads.size(), 10); q++) {
                        BakedQuad quad = allQuads.get(q);
                        int[] verts = quad.getVertices();
                        sb.append("  Quad #").append(q).append(": sprite=").append(quad.getSprite().contents().name());
                        sb.append(" verts=[");
                        for (int vi = 0; vi < 4; vi++) {
                            float vx = Float.intBitsToFloat(verts[vi * 8]);
                            float vy = Float.intBitsToFloat(verts[vi * 8 + 1]);
                            float vz = Float.intBitsToFloat(verts[vi * 8 + 2]);
                            if (vi > 0) sb.append(", ");
                            sb.append("(").append(fmt(vx)).append(",").append(fmt(vy)).append(",").append(fmt(vz)).append(")");
                        }
                        sb.append("]\n");
                    }
                    if (allQuads.size() > 10) {
                        sb.append("  ... and ").append(allQuads.size() - 10).append(" more quads\n");
                    }

                    // Find a pixel that hits this C&B block and dump its s/t analysis
                    for (int idx = 0; idx < winnerIdx.length; idx++) {
                        if (winnerIdx[idx] >= 0 && candidates.get(winnerIdx[idx]).blockPos().equals(c.blockPos())) {
                            int samplePx = idx % wPx;
                            int samplePy = idx / wPx;
                            sb.append("\n  --- Sample pixel [").append(samplePx).append(", ").append(samplePy).append("] hitting this C&B block ---\n");
                            appendPixelDebug(sb, level, vol, candidates, winnerIdx, samplePx, samplePy, wPx, hPx);
                            break;
                        }
                    }
                }
            }
            if (!foundCnb) {
                sb.append("No modded blocks (C&B etc.) found in projection volume\n");
            }
        }

        return sb.toString();
    }

    private static void appendPixelDebug(StringBuilder sb, BlockAndTintGetter level,
                                          Projection vol, List<ProjectionResolver.FaceCandidate> candidates,
                                          int[] winnerIdx, int px, int py, int wPx, int hPx) {
        int idx = py * wPx + px;
        if (idx < 0 || idx >= winnerIdx.length) {
            sb.append("  (out of bounds)\n");
            return;
        }
        if (winnerIdx[idx] < 0) {
            sb.append("  No depth hit (no face candidate covers this pixel)\n");
            return;
        }

        var hit = candidates.get(winnerIdx[idx]);
        sb.append("  Winner: candidate #").append(winnerIdx[idx]).append('\n');
        sb.append("  BlockPos: ").append(hit.blockPos()).append(" face=").append(hit.faceNormal())
          .append(" depth=").append(fmt(hit.depth())).append('\n');

        BlockState state = level.getBlockState(hit.blockPos());
        sb.append("  State: ").append(state).append('\n');

        // Compute world position
        float localU = (px + 0.5f) / wPx * vol.width();
        float localV = (py + 0.5f) / hPx * vol.height();
        Vec3 worldPos = vol.localToWorld(localU, localV, hit.depth());
        float bx = (float)(worldPos.x - hit.blockPos().getX());
        float by = (float)(worldPos.y - hit.blockPos().getY());
        float bz = (float)(worldPos.z - hit.blockPos().getZ());
        sb.append("  World pos: ").append(fmtVec(worldPos)).append('\n');
        sb.append("  Block-local: (").append(fmt(bx)).append(", ").append(fmt(by)).append(", ").append(fmt(bz)).append(")\n");

        // Get quads
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(state);
        List<BakedQuad> quads = model.getQuads(state, hit.faceNormal(), RANDOM);
        sb.append("  Vanilla quads: ").append(quads.size()).append('\n');

        // Try null-face (generic) quads filtered by direction
        List<BakedQuad> nullQuads = model.getQuads(state, null, RANDOM);
        long nullMatching = nullQuads.stream().filter(q -> q.getDirection() == hit.faceNormal()).count();
        sb.append("  Null-face quads: ").append(nullQuads.size())
          .append(" (").append(nullMatching).append(" matching ").append(hit.faceNormal()).append(")\n");

        // Try NeoForge data-aware path
        BlockEntity be = level.getBlockEntity(hit.blockPos());
        ModelData modelData = be != null ? be.getModelData() : ModelData.EMPTY;
        List<BakedQuad> dataQuads = model.getQuads(state, hit.faceNormal(), RANDOM, modelData, null);
        sb.append("  NeoForge data-aware quads: ").append(dataQuads.size()).append('\n');

        // Try Scena direct path
        if (HAS_CNB) {
            List<BakedQuad> scenaQuads = ChiseledBlockHelper.getDataAwareQuads(
                level, hit.blockPos(), state, model, hit.faceNormal(), RANDOM);
            sb.append("  Scena direct quads: ").append(scenaQuads.size()).append('\n');
            if (scenaQuads.size() > quads.size()) quads = scenaQuads;
        }
        // Use null-face filtered quads if face-specific is empty
        if (quads.isEmpty() && nullMatching > 0) {
            quads = nullQuads.stream().filter(q -> q.getDirection() == hit.faceNormal()).toList();
        }
        if (dataQuads.size() > quads.size()) quads = dataQuads;

        if (quads.isEmpty()) {
            sb.append("  NO QUADS AVAILABLE — pixel will be transparent\n");
            return;
        }

        // Analyze each quad's s,t for this sample point
        sb.append("  Quad s/t analysis (sample point vs each quad):\n");
        int matchIdx = -1;
        for (int q = 0; q < quads.size(); q++) {
            BakedQuad quad = quads.get(q);
            int[] verts = quad.getVertices();
            float[] vx = new float[4], vy = new float[4], vz = new float[4];
            for (int i = 0; i < 4; i++) {
                vx[i] = Float.intBitsToFloat(verts[i * 8]);
                vy[i] = Float.intBitsToFloat(verts[i * 8 + 1]);
                vz[i] = Float.intBitsToFloat(verts[i * 8 + 2]);
            }

            float e1x = vx[1]-vx[0], e1y = vy[1]-vy[0], e1z = vz[1]-vz[0];
            float e2x = vx[3]-vx[0], e2y = vy[3]-vy[0], e2z = vz[3]-vz[0];
            float dx = bx-vx[0], dy = by-vy[0], dz = bz-vz[0];
            float e1len2 = e1x*e1x + e1y*e1y + e1z*e1z;
            float e2len2 = e2x*e2x + e2y*e2y + e2z*e2z;
            float s = e1len2 > 0.0001f ? (dx*e1x + dy*e1y + dz*e1z) / e1len2 : 0.5f;
            float t = e2len2 > 0.0001f ? (dx*e2x + dy*e2y + dz*e2z) / e2len2 : 0.5f;

            boolean inBounds = s >= -0.01f && s <= 1.01f && t >= -0.01f && t <= 1.01f;
            String marker = inBounds ? " <<< MATCH" : "";

            if (q < 15 || inBounds) {
                sb.append("    #").append(q).append(": s=").append(fmt(s)).append(" t=").append(fmt(t));
                sb.append(" v0=(").append(fmt(vx[0])).append(",").append(fmt(vy[0])).append(",").append(fmt(vz[0])).append(")");
                sb.append(" sprite=").append(quad.getSprite().contents().name());
                sb.append(marker).append('\n');
            }

            if (inBounds && matchIdx < 0) matchIdx = q;
        }

        if (quads.size() > 15 && matchIdx < 0) {
            sb.append("    ... (").append(quads.size() - 15).append(" more, none matched)\n");
        }

        if (matchIdx >= 0) {
            sb.append("  RESULT: Matched quad #").append(matchIdx).append('\n');
        } else {
            sb.append("  RESULT: NO QUAD MATCHED — pixel will be transparent (using clamped quad #0)\n");
        }
    }

    private static String fmt(float v) { return String.format("%.3f", v); }
    private static String fmt(double v) { return String.format("%.3f", v); }
    private static String fmtVec(Vec3 v) {
        return "(" + fmt(v.x) + ", " + fmt(v.y) + ", " + fmt(v.z) + ")";
    }
}
