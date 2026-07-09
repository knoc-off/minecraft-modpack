package dev.paintcraft.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.paintcraft.ModConfig;
import dev.paintcraft.PaintCraft;
import dev.paintcraft.core.ColorFormat;
import dev.paintcraft.core.Decal;
import dev.paintcraft.core.FaceFrame;
import dev.paintcraft.projection.SurfaceFragment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

import java.util.*;

/**
 * Composites overlapping decal pixels into a single 16×16 texture per block face cell.
 * Contains a grid-based atlas for the composited textures.
 */
public final class CellCompositor {

    private static final int CELL_SIZE = Decal.PX_PER_BLOCK; // 16

    // Atlas dimensions — set from config on first init
    private static int atlasSize;
    private static int cellsPerRow;
    private static int maxCells;

    // --- Grid atlas ---
    private static NativeImage atlasImage;
    private static DynamicTexture atlasTexture;
    private static ResourceLocation atlasLocation;
    private static boolean atlasDirty;

    // Free-list: stack of available slot indices
    private static final Deque<Integer> freeSlots = new ArrayDeque<>();
    private static boolean atlasInitialized = false;
    private static boolean atlasFullWarned = false;

    // --- Cell tracking ---
    private static final Map<Long, CellData> cells = new HashMap<>();
    // Dirty cells: keyed by packed cell key, value carries (pos, face) to avoid
    // unpacking from the XOR-based key (which isn't invertible).
    private static final Map<Long, CellId> dirtyCells = new LinkedHashMap<>();

    // Chunk-level index: which cell keys belong to each chunk
    private static final Map<ChunkPos, Set<Long>> chunkCells = new HashMap<>();
    // Chunks that had cells composited this flush (for targeted VBO rebuild)
    private static final Set<ChunkPos> lastFlushedChunks = new HashSet<>();

    private CellCompositor() {}

    // --- Public API ---

    /**
     * Mark all cells touched by a decal as dirty.
     * Uses the resolved surface fragments to get (pos, face) data.
     */
    public static void markDecalDirty(UUID decalId) {
        DecalRenderer.ResolvedEntry entry = DecalRenderer.getResolved(decalId);
        if (entry == null) return;
        for (SurfaceFragment frag : entry.surface().fragments()) {
            long key = ClientSpatialIndex.packKey(frag.pos(), frag.faceNormal());
            dirtyCells.put(key, new CellId(frag.pos(), frag.faceNormal()));
        }
    }

    /**
     * Mark only cells at specific block positions as dirty.
     * Used for block-change re-resolve where pixels haven't changed,
     * only geometry at the changed positions.
     */
    public static void markCellsDirtyAt(UUID decalId, Set<BlockPos> positions) {
        DecalRenderer.ResolvedEntry entry = DecalRenderer.getResolved(decalId);
        if (entry == null) return;
        for (SurfaceFragment frag : entry.surface().fragments()) {
            if (positions.contains(frag.pos())) {
                long key = ClientSpatialIndex.packKey(frag.pos(), frag.faceNormal());
                dirtyCells.put(key, new CellId(frag.pos(), frag.faceNormal()));
            }
        }
    }

    /**
     * Composite all dirty cells. Called from the render thread.
     * Populates lastFlushedChunks for targeted VBO rebuild.
     */
    public static void flush() {
        lastFlushedChunks.clear();
        if (dirtyCells.isEmpty()) return;

        Map<Long, CellId> toProcess = new LinkedHashMap<>(dirtyCells);
        dirtyCells.clear();

        for (var entry : toProcess.entrySet()) {
            CellId cellId = entry.getValue();
            lastFlushedChunks.add(new ChunkPos(cellId.pos));
            compositeCell(entry.getKey(), cellId);
        }
    }

    /** Chunks that had cells recomposited in the last flush(). */
    public static Set<ChunkPos> lastFlushedChunks() {
        return lastFlushedChunks;
    }

    /** Get all cells in a specific chunk. */
    public static List<CellData> getCellsInChunk(ChunkPos chunk) {
        Set<Long> keys = chunkCells.get(chunk);
        if (keys == null || keys.isEmpty()) return List.of();
        List<CellData> result = new ArrayList<>(keys.size());
        for (long key : keys) {
            CellData cell = cells.get(key);
            if (cell != null) result.add(cell);
        }
        return result;
    }

    /** Get all chunks that have cells. */
    public static Set<ChunkPos> allChunks() {
        return chunkCells.keySet();
    }

    /**
     * Upload the atlas texture to the GPU if dirty.
     */
    public static void upload() {
        if (atlasDirty && atlasTexture != null) {
            atlasTexture.upload();
            atlasDirty = false;
        }
    }

    public static ResourceLocation atlasLocation() {
        ensureAtlas();
        return atlasLocation;
    }

    public static float atlasU(int slotIndex, float localU) {
        int col = slotIndex % cellsPerRow;
        return (col * CELL_SIZE + localU * CELL_SIZE) / (float) atlasSize;
    }

    public static float atlasV(int slotIndex, float localV) {
        int row = slotIndex / cellsPerRow;
        return (row * CELL_SIZE + localV * CELL_SIZE) / (float) atlasSize;
    }

    public static Collection<CellData> allCells() {
        return cells.values();
    }

    public static void clear() {
        cells.clear();
        dirtyCells.clear();
        chunkCells.clear();
        lastFlushedChunks.clear();
        freeSlots.clear();
        atlasFullWarned = false;
        if (atlasInitialized) {
            for (int i = 0; i < maxCells; i++) freeSlots.push(i);
        }
    }

    public static void destroyAll() {
        cells.clear();
        dirtyCells.clear();
        chunkCells.clear();
        lastFlushedChunks.clear();
        freeSlots.clear();
        atlasInitialized = false;
        atlasDirty = false;
        atlasFullWarned = false;
        if (atlasLocation != null) {
            Minecraft.getInstance().getTextureManager().release(atlasLocation);
            atlasLocation = null;
        }
        if (atlasTexture != null) {
            atlasTexture.close();
            atlasTexture = null;
        }
        atlasImage = null;
    }

    private static void removeCell(long cellKey, ChunkPos chunk) {
        CellData old = cells.remove(cellKey);
        if (old != null) {
            freeSlot(old.slotIndex);
            Set<Long> keys = chunkCells.get(chunk);
            if (keys != null) {
                keys.remove(cellKey);
                if (keys.isEmpty()) chunkCells.remove(chunk);
            }
        }
    }

    /** Remove all cells in a chunk. Called on chunk unload. */
    public static void removeChunk(ChunkPos chunk) {
        Set<Long> keys = chunkCells.remove(chunk);
        if (keys == null) return;
        for (long key : keys) {
            CellData cell = cells.remove(key);
            if (cell != null) freeSlot(cell.slotIndex);
        }
    }

    /** Check if a composited cell exists at (pos, face). */
    public static boolean hasCell(BlockPos pos, Direction face) {
        return cells.containsKey(ClientSpatialIndex.packKey(pos, face));
    }

    /** Get the cell at (pos, face), or null. */
    public static CellData getCell(BlockPos pos, Direction face) {
        return cells.get(ClientSpatialIndex.packKey(pos, face));
    }

    /**
     * Ensure a composited cell exists at (pos, face). If missing, composites it
     * on the spot. Used for lazy cell creation during VBO rebuild after
     * geometry-only updates.
     */
    public static CellData ensureCell(BlockPos pos, Direction face) {
        long key = ClientSpatialIndex.packKey(pos, face);
        CellData existing = cells.get(key);
        if (existing != null) return existing;
        compositeCell(key, new CellId(pos, face));
        return cells.get(key);
    }

    /**
     * Remove compositor cells in a chunk that have no spatial index refs.
     * Called after geometry-only updates to clean up orphaned cells.
     */
    public static void cleanupOrphanedCells(ChunkPos chunk) {
        Set<Long> keys = chunkCells.get(chunk);
        if (keys == null) return;
        List<Long> toRemove = new ArrayList<>();
        for (long key : keys) {
            CellData cell = cells.get(key);
            if (cell == null) continue;
            List<ClientSpatialIndex.DecalRef> refs =
                ClientSpatialIndex.getRefsAt(cell.pos(), cell.face());
            if (refs.isEmpty()) toRemove.add(key);
        }
        for (long key : toRemove) {
            CellData cell = cells.remove(key);
            if (cell != null) freeSlot(cell.slotIndex);
            keys.remove(key);
        }
        if (keys.isEmpty()) chunkCells.remove(chunk);
    }

    /**
     * Sync compositor cells with the spatial index for a chunk.
     * Creates cells that have spatial refs but no compositor cell (lazy creation).
     * Removes cells that have no spatial refs (orphan cleanup).
     */
    public static void syncWithSpatialIndex(ChunkPos chunk, Set<Long> spatialKeys) {
        Set<Long> compositorKeys = chunkCells.getOrDefault(chunk, Set.of());

        // Create missing cells (spatial index has refs, compositor doesn't)
        for (long key : spatialKeys) {
            if (!cells.containsKey(key)) {
                CellId cellId = findCellId(key);
                if (cellId != null) {
                    dirtyCells.putIfAbsent(key, cellId);
                }
            }
        }

        // Flush any newly dirtied cells
        if (!dirtyCells.isEmpty()) {
            flush();
        }

        // Remove orphaned cells (compositor has cell, spatial index doesn't)
        if (!compositorKeys.isEmpty()) {
            List<Long> orphans = new ArrayList<>();
            for (long key : compositorKeys) {
                if (!spatialKeys.contains(key)) {
                    orphans.add(key);
                }
            }
            for (long key : orphans) {
                CellData cell = cells.remove(key);
                if (cell != null) freeSlot(cell.slotIndex);
                compositorKeys.remove(key);
            }
            if (compositorKeys.isEmpty()) chunkCells.remove(chunk);
        }
    }

    /** Recover CellId from a cell key — O(1) via spatial index reverse lookup. */
    private static CellId findCellId(long key) {
        CellData existing = cells.get(key);
        if (existing != null) return new CellId(existing.pos(), existing.face());
        ClientSpatialIndex.CellLocation loc = ClientSpatialIndex.getCellLocation(key);
        if (loc != null) return new CellId(loc.pos(), loc.face());
        // Fallback: scan resolved entries (covers keys registered before the reverse index existed)
        for (var entry : DecalRenderer.allResolved()) {
            List<SurfaceFragment> frags = entry.fragmentIndex().get(key);
            if (frags != null && !frags.isEmpty()) {
                SurfaceFragment frag = frags.get(0);
                return new CellId(frag.pos(), frag.faceNormal());
            }
        }
        return null;
    }

    // --- Internals ---

    private static void ensureAtlas() {
        if (atlasInitialized) return;
        atlasSize = ModConfig.CONFIG.atlasSize.get().pixels;
        cellsPerRow = atlasSize / CELL_SIZE;
        maxCells = cellsPerRow * cellsPerRow;
        PaintCraft.LOGGER.info("Initializing decal atlas: {}x{} ({} cells)", atlasSize, atlasSize, maxCells);
        atlasImage = new NativeImage(atlasSize, atlasSize, true);
        atlasTexture = new DynamicTexture(atlasImage);
        atlasLocation = Minecraft.getInstance().getTextureManager()
            .register("paintcraft_composite", atlasTexture);
        for (int i = maxCells - 1; i >= 0; i--) freeSlots.push(i);
        atlasInitialized = true;
    }

    private static int allocateSlot() {
        ensureAtlas();
        if (freeSlots.isEmpty()) {
            if (!atlasFullWarned) {
                PaintCraft.LOGGER.warn("Composite atlas full ({} cells) — some decals may not render. "
                    + "Increase atlasSize in paintcraft-client.toml config.", maxCells);
                atlasFullWarned = true;
            }
            return -1;
        }
        atlasFullWarned = false;
        return freeSlots.pop();
    }

    private static void freeSlot(int slotIndex) {
        // Zero out the slot pixels
        int sx = (slotIndex % cellsPerRow) * CELL_SIZE;
        int sy = (slotIndex / cellsPerRow) * CELL_SIZE;
        for (int y = 0; y < CELL_SIZE; y++) {
            for (int x = 0; x < CELL_SIZE; x++) {
                atlasImage.setPixelRGBA(sx + x, sy + y, 0);
            }
        }
        atlasDirty = true;
        freeSlots.push(slotIndex);
    }

    private static void compositeCell(long cellKey, CellId cellId) {
        BlockPos pos = cellId.pos;
        Direction face = cellId.face;
        ChunkPos chunk = new ChunkPos(pos);

        // Level 1: Skip if face is hidden by an adjacent opaque block; also detect adjacent water.
        net.minecraft.world.level.Level level = net.minecraft.client.Minecraft.getInstance().level;
        boolean adjacentWater = false;
        boolean entityBlock = false;
        if (level != null) {
            // Blocks with no baked model (RenderShape != MODEL) are drawn by a BlockEntityRenderer
            // — decorated pot, chest, sign, banner. Their decals must not write depth (see CellData).
            entityBlock = level.getBlockState(pos).getRenderShape()
                != net.minecraft.world.level.block.RenderShape.MODEL;
            BlockPos neighbor = pos.relative(face);
            net.minecraft.world.level.block.state.BlockState neighborState = level.getBlockState(neighbor);
            if (neighborState.isSolidRender(level, neighbor)) {
                removeCell(cellKey, chunk);
                return;
            }
            adjacentWater = !level.getFluidState(neighbor).isEmpty();
        }

        List<ClientSpatialIndex.DecalRef> refs = ClientSpatialIndex.getRefsAt(pos, face);
        if (refs.isEmpty()) {
            removeCell(cellKey, chunk);
            return;
        }

        FaceFrame canonFrame = FaceFrame.canonical(face);
        int[] composite = new int[CELL_SIZE * CELL_SIZE]; // starts transparent (0)
        int opaquePixels = 0;
        int maxPixels = CELL_SIZE * CELL_SIZE;

        boolean relief = ModConfig.CONFIG.reliefEnabled.get();
        int[] layerCount = relief ? new int[CELL_SIZE * CELL_SIZE] : null;
        // Per-decal coverage mask: a single decal may resolve to several overlapping surface
        // fragments on a geometrically complex block (e.g. a pot's rim + interior + wall tops).
        // We must count stack height per DECAL, not per fragment, otherwise one decal inflates
        // the derived height and the relief extrudes as if many layers were stacked.
        boolean[] decalCovered = relief ? new boolean[CELL_SIZE * CELL_SIZE] : null;

        // Layer cap: only composite the top-N decals by z-order (highest priority first).
        // Deeper decals stay in data but are not rendered — keeps compositing cost bounded.
        int n = ModConfig.CONFIG.reliefMaxLayers.get();
        int floor = Math.max(0, refs.size() - n);

        // Walk from HIGHEST priority to LOWEST. With relief we must visit all capped layers
        // to count height; without we exit early once every texel is opaque.
        for (int i = refs.size() - 1; i >= floor && (relief || opaquePixels < maxPixels); i--) {
            ClientSpatialIndex.DecalRef ref = refs.get(i);
            DecalRenderer.ResolvedEntry entry = DecalRenderer.getResolved(ref.decalId());
            if (entry == null) continue;

            Decal decal = entry.decal();

            FaceFrame decalFrame = decal.frame();
            int stepsToCanon = decalFrame.clockwiseStepsTo(canonFrame);
            int cwSteps = (4 - stepsToCanon) % 4;

            if (decalCovered != null) Arrays.fill(decalCovered, false);
            for (SurfaceFragment frag : entry.surface().fragments()) {
                if (!frag.pos().equals(pos) || frag.faceNormal() != face) continue;
                opaquePixels += blitDecalPixels(composite, decalCovered, decal.pixels(), decal.widthPx(), decal.heightPx(), frag, cwSteps);
            }
            // Merge this decal's coverage into the height grid: exactly one layer per decal per
            // texel, regardless of how many overlapping fragments the decal produced here.
            if (layerCount != null) {
                for (int k = 0; k < layerCount.length; k++) {
                    if (decalCovered[k]) layerCount[k]++;
                }
            }
        }

        // Level 2: Skip if composite is entirely empty (all transparent).
        boolean anyContent = false;
        boolean translucent = false;
        for (int c : composite) {
            int alpha = c >>> 24;
            if (alpha > 0) anyContent = true;
            if (alpha > 0 && alpha < 0xFF) translucent = true;
            if (anyContent && translucent) break;
        }
        if (!anyContent) {
            removeCell(cellKey, chunk);
            return;
        }

        byte[] heights = relief ? downsampleHeights(layerCount) : null;

        CellData existing = cells.get(cellKey);
        int slotIndex;
        if (existing == null) {
            slotIndex = allocateSlot();
            if (slotIndex < 0) return;
            chunkCells.computeIfAbsent(chunk, k -> new HashSet<>()).add(cellKey);
        } else {
            slotIndex = existing.slotIndex();
        }
        cells.put(cellKey, new CellData(pos, face, slotIndex, heights, translucent, adjacentWater, entityBlock));
        writeToAtlas(slotIndex, composite);
    }

    /** Max-pool the per-texel layer count (CELL_SIZE²) into a height grid (res²), clamped. */
    private static byte[] downsampleHeights(int[] layerCount) {
        int res = ModConfig.CONFIG.reliefHeightRes.get().cells;
        int maxLayers = ModConfig.CONFIG.reliefMaxLayers.get();
        int factor = CELL_SIZE / res; // CELL_SIZE (32) is divisible by all HeightRes values
        byte[] heights = new byte[res * res];
        for (int gv = 0; gv < res; gv++) {
            for (int gu = 0; gu < res; gu++) {
                int max = 0;
                for (int dy = 0; dy < factor; dy++) {
                    int cy = gv * factor + dy;
                    for (int dx = 0; dx < factor; dx++) {
                        int v = layerCount[cy * CELL_SIZE + (gu * factor + dx)];
                        if (v > max) max = v;
                    }
                }
                if (max > maxLayers) max = maxLayers;
                heights[gv * res + gu] = (byte) max;
            }
        }
        return heights;
    }

    /**
     * Blit one decal's fragment pixels into the composite using alpha-over.
     * The composite already holds the accumulated higher-priority layers, so each
     * incoming texel is composited *under* it (existing OVER incoming).
     *
     * @return the number of texels that became fully opaque as a result of this blit
     *         (used to drive the all-opaque early exit in {@link #compositeCell}).
     */
    private static int blitDecalPixels(int[] composite, boolean[] decalCovered, int[] pixels, int srcWidth,
                                         int srcHeight, SurfaceFragment frag, int cwSteps) {
        int px0 = frag.u0(), py0 = frag.v0();
        int px1 = frag.u1(), py1 = frag.v1();
        int newlyOpaque = 0;

        for (int py = py0; py <= py1; py++) {
            // Fragment v0/v1 are depth-buffer coordinates (py=0 at face bottom, local.y=0).
            // Pixel array has py=0 at visual top (face top). Flip to read correct row.
            int arrayPy = (srcHeight - 1) - py;
            for (int px = px0; px <= px1; px++) {
                int color = pixels[arrayPy * srcWidth + px];
                if ((color >>> 24) == 0) continue; // skip fully transparent source

                int cx = px % CELL_SIZE;
                int cy = arrayPy % CELL_SIZE;

                // Rotate into canonical frame orientation
                if (cwSteps != 0) {
                    for (int r = 0; r < cwSteps; r++) {
                        int tmp = cx;
                        cx = (CELL_SIZE - 1) - cy;
                        cy = tmp;
                    }
                }

                int idx = cy * CELL_SIZE + cx;

                // Stack height: every non-transparent source layer counts, even occluded ones.
                // Mark coverage here (before the opaque-skip) so a decal fully hidden by a
                // higher one still contributes its single layer. Merged into layerCount once
                // per decal by the caller.
                if (decalCovered != null) decalCovered[idx] = true;

                int existing = composite[idx];
                if ((existing >>> 24) == 0xFF) continue; // already opaque — nothing below shows

                int blended = ColorFormat.alphaOver(existing, color);
                composite[idx] = blended;
                if ((blended >>> 24) == 0xFF) newlyOpaque++;
            }
        }
        return newlyOpaque;
    }

    private static void writeToAtlas(int slotIndex, int[] argbPixels) {
        ensureAtlas();
        int sx = (slotIndex % cellsPerRow) * CELL_SIZE;
        int sy = (slotIndex / cellsPerRow) * CELL_SIZE;
        for (int y = 0; y < CELL_SIZE; y++) {
            for (int x = 0; x < CELL_SIZE; x++) {
                int c = argbPixels[y * CELL_SIZE + x];
                atlasImage.setPixelRGBA(sx + x, sy + y, ColorFormat.argbToAbgr(c));
            }
        }
        atlasDirty = true;
    }

    // --- Data types ---

    record CellId(BlockPos pos, Direction face) {}

    /**
     * A composited face cell.
     *
     * @param heights      derived stack-height grid (reliefHeightRes², row-major). {@code null} when relief disabled.
     * @param translucent  true if any composited texel has alpha between 1 and 254 (i.e., the result is not fully opaque).
     * @param adjacentWater true if the block immediately in front of this face ({@code pos.relative(face)}) is a fluid.
     * @param entityBlock  true if the block has no baked model and is drawn by a BlockEntityRenderer
     *                     (decorated pot, chest, sign...). These need a no-depth-write decal so the
     *                     separately-rendered, hollow BER geometry isn't depth-culled into a see-through hole.
     */
    public record CellData(BlockPos pos, Direction face, int slotIndex, byte[] heights,
                            boolean translucent, boolean adjacentWater, boolean entityBlock) {
        /**
         * True when this cell should be drawn in the late translucent pass (after water).
         * Translucent cells beside water draw in the early pass so water blends over them.
         */
        public boolean latePass() { return translucent && !adjacentWater; }
    }
}
