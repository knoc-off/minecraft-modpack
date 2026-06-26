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

    /** Recover CellId from a cell key by looking up existing CellData or spatial index. */
    private static CellId findCellId(long key) {
        CellData existing = cells.get(key);
        if (existing != null) return new CellId(existing.pos(), existing.face());
        // Search through spatial index refs to find the pos/face
        // The key encodes pos + face, but we can't invert it.
        // Instead, scan resolved entries for a fragment with this key.
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

        // Level 1: Skip if face is hidden by an adjacent opaque block
        net.minecraft.world.level.Level level = net.minecraft.client.Minecraft.getInstance().level;
        if (level != null) {
            BlockPos neighbor = pos.relative(face);
            if (level.getBlockState(neighbor).isSolidRender(level, neighbor)) {
                removeCell(cellKey, chunk);
                return;
            }
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

        // Walk from HIGHEST priority to LOWEST, accumulating with alpha-over: each
        // lower decal is composited *under* what's already been written. A texel that
        // has reached full opacity is final — once every texel is opaque we can stop.
        for (int i = refs.size() - 1; i >= 0 && opaquePixels < maxPixels; i--) {
            ClientSpatialIndex.DecalRef ref = refs.get(i);
            DecalRenderer.ResolvedEntry entry = DecalRenderer.getResolved(ref.decalId());
            if (entry == null) continue;

            Decal decal = entry.decal();

            // Compute rotation from decal frame to canonical frame
            FaceFrame decalFrame = decal.frame();
            int stepsToCanon = decalFrame.clockwiseStepsTo(canonFrame);
            int cwSteps = (4 - stepsToCanon) % 4;

            // Iterate ALL matching fragments (a stair block may have multiple sub-faces)
            for (SurfaceFragment frag : entry.surface().fragments()) {
                if (!frag.pos().equals(pos) || frag.faceNormal() != face) continue;
                opaquePixels += blitDecalPixels(composite, decal.pixels(), decal.widthPx(), decal.heightPx(), frag, cwSteps);
            }
        }

        // Level 2: Skip if composite is entirely empty (all transparent)
        boolean anyContent = false;
        for (int c : composite) {
            if (c != 0) { anyContent = true; break; }
        }
        if (!anyContent) {
            removeCell(cellKey, chunk);
            return;
        }

        // Allocate or reuse atlas slot
        CellData cell = cells.get(cellKey);
        if (cell == null) {
            int slotIndex = allocateSlot();
            if (slotIndex < 0) return; // atlas full — skip gracefully
            cell = new CellData(pos, face, slotIndex);
            cells.put(cellKey, cell);
            chunkCells.computeIfAbsent(chunk, k -> new HashSet<>()).add(cellKey);
        }
        writeToAtlas(cell.slotIndex, composite);
    }

    /**
     * Blit one decal's fragment pixels into the composite using alpha-over.
     * The composite already holds the accumulated higher-priority layers, so each
     * incoming texel is composited *under* it (existing OVER incoming).
     *
     * @return the number of texels that became fully opaque as a result of this blit
     *         (used to drive the all-opaque early exit in {@link #compositeCell}).
     */
    private static int blitDecalPixels(int[] composite, int[] pixels, int srcWidth,
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
                int existing = composite[idx];
                if ((existing >>> 24) == 0xFF) continue; // already opaque — nothing below shows

                int blended = alphaOver(existing, color);
                composite[idx] = blended;
                if ((blended >>> 24) == 0xFF) newlyOpaque++;
            }
        }
        return newlyOpaque;
    }

    /**
     * Straight-alpha "over" compositing of ARGB colors: {@code fg} over {@code bg}.
     * {@code fg} is the higher-priority (front) layer.
     */
    private static int alphaOver(int fg, int bg) {
        int fa = (fg >>> 24) & 0xFF;
        if (fa == 0xFF) return fg;   // opaque front fully hides back
        if (fa == 0)    return bg;   // transparent front: back shows through
        int ba = (bg >>> 24) & 0xFF;
        int inv = 255 - fa;
        int oa = fa + ba * inv / 255;
        if (oa == 0) return 0;
        int baInv = ba * inv / 255;
        int fr = (fg >> 16) & 0xFF, fgC = (fg >> 8) & 0xFF, fb = fg & 0xFF;
        int br = (bg >> 16) & 0xFF, bgC = (bg >> 8) & 0xFF, bb = bg & 0xFF;
        int or = (fr * fa + br * baInv) / oa;
        int og = (fgC * fa + bgC * baInv) / oa;
        int ob = (fb * fa + bb * baInv) / oa;
        return (oa << 24) | (or << 16) | (og << 8) | ob;
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

    public record CellData(BlockPos pos, Direction face, int slotIndex) {}
}
