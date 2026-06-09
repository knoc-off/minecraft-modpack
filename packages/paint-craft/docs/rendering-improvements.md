# Rendering Performance Improvements

## Current Architecture (post-compositor rewrite)

The cell compositor flattens overlapping decals into per-`(BlockPos, face)` 16x16 textures,
rendered via a single static VBO in one draw call. This eliminated the z-tier system and
reduced draw calls from O(decals) to 1.

Remaining bottlenecks are in how changes propagate through the pipeline.

---

## Issue 0: Unnecessary recompositing on block changes (highest priority)

### Problem

When a block changes, `DeferredInvalidator` re-resolves the decal (correctly querying only
the changed block via incremental resolve). But `cacheResolved` then marks ALL cells of the
decal as dirty:

```
cacheResolved(decalId, decal, newSurface, projState)
  -> CellCompositor.markDecalDirty(decalId)  // marks ALL old cells dirty
  -> update resolvedCache
  -> CellCompositor.markDecalDirty(decalId)  // marks ALL new cells dirty
  -> vboDirty = true
```

A 20x20 mural touching 400 cells gets all 400 recomposited when a single block changes.
Each recomposite reads pixel data from every overlapping decal at that cell. Then the
entire VBO rebuilds.

The decal's pixels haven't changed. The geometry only changed at the block that was
modified. The other 399 cells are identical.

### Fix

Pass the changed `BlockPos` set through to `cacheResolved` so it only marks affected cells:

```java
// DecalRenderer
public static void cacheResolved(UUID decalId, Decal decal,
                                 ResolvedSurface resolved, ProjectionState projState,
                                 Set<BlockPos> changedBlocks) {
    ResolvedEntry existing = resolvedCache.get(decalId);

    if (changedBlocks != null && existing != null) {
        // Targeted: only mark cells at changed block positions
        markCellsAtPositions(decalId, existing, changedBlocks);
        resolvedCache.put(decalId, new ResolvedEntry(decal, resolved, projState, ...));
        markCellsAtPositions(decalId, resolvedCache.get(decalId), changedBlocks);
    } else {
        // Full: mark everything (initial resolve, pixel change, etc.)
        CellCompositor.markDecalDirty(decalId);
        resolvedCache.put(decalId, new ResolvedEntry(decal, resolved, projState, ...));
        CellCompositor.markDecalDirty(decalId);
    }
    vboDirty = true;
}

private static void markCellsAtPositions(UUID decalId, ResolvedEntry entry,
                                          Set<BlockPos> positions) {
    for (SurfaceFragment frag : entry.surface().fragments()) {
        if (positions.contains(frag.pos())) {
            long key = ClientSpatialIndex.packKey(frag.pos(), frag.faceNormal());
            CellCompositor.markCellDirty(key, frag.pos(), frag.faceNormal());
        }
    }
}
```

Thread `changedBlocks` from `DeferredInvalidator` through the call chain. Initial resolves
and pixel changes pass `null` for the full-dirty path.

### Cost reduction

Single block change in a 20x20 mural:
- Before: 400 cell composites + full VBO rebuild
- After: 1-2 cell composites + full VBO rebuild (issue 2 addresses the VBO part)

---

## Issue 1: VBO frustum culling is world-scale

### Problem

`compositeBounds` is a single AABB enclosing ALL cells across the entire world. If decals
exist in two chunks 200 blocks apart, the AABB spans 200 blocks and the frustum test
almost never culls. All vertices get submitted to the GPU even when most are off-screen.

### Fix: per-chunk VBOs

Replace the single global VBO with per-chunk VBOs. Each chunk that contains cells gets its
own `VertexBuffer` with its own AABB for frustum culling.

```java
// DecalRenderer
private static final Map<ChunkPos, ChunkVBO> chunkVBOs = new HashMap<>();

record ChunkVBO(VertexBuffer vbo, AABB bounds, boolean dirty) {}
```

Benefits:
- Frustum culling works at chunk granularity (effective)
- Only the affected chunk's VBO rebuilds on changes (solves issue 2)
- Natural cleanup on chunk unload
- Matches Minecraft's own chunk rendering model

Implementation outline:

1. `CellCompositor.CellData` already has `BlockPos pos` -- derive `ChunkPos` from it
2. When `vboDirty`, determine which chunks are dirty (track dirty chunks alongside
   dirty cells)
3. `rebuildVBO` becomes `rebuildChunkVBO(ChunkPos)` -- iterates only cells in that chunk
4. `renderAll` iterates chunk VBOs, frustum-culls each, draws survivors
5. Chunk unload event: destroy the chunk's VBO and free its cell slots

Draw call count goes from 1 to O(visible chunks with decals). In practice this is 5-20,
which is trivially cheap vs the frustum culling savings.

### Data structures

```java
// Track which chunks are dirty
private static final Set<ChunkPos> dirtyChunks = new HashSet<>();

// Cell -> chunk mapping (derive from CellData.pos)
// No new storage needed: ChunkPos.of(cell.pos()) works directly

// Per-chunk VBO cache
private static final Map<ChunkPos, ChunkVBO> chunkVBOs = new HashMap<>();
```

### Render loop change

```java
public static void renderAll(Vec3 camera, Frustum frustum, ...) {
    CellCompositor.flush();
    CellCompositor.upload();

    // Rebuild only dirty chunk VBOs
    for (ChunkPos chunk : dirtyChunks) {
        rebuildChunkVBO(chunk);
    }
    dirtyChunks.clear();

    // Draw each chunk VBO with frustum culling
    for (var entry : chunkVBOs.entrySet()) {
        ChunkVBO cvbo = entry.getValue();
        if (!frustum.isVisible(cvbo.bounds)) continue;
        // bind, set uniforms, draw
        cvbo.vbo.bind();
        cvbo.vbo.draw();
    }
}
```

### Lifecycle

```java
// ClientEvents.onChunkUnload:
DecalRenderer.onChunkUnload(chunkPos);
  -> destroy ChunkVBO for that chunk
  -> CellCompositor frees slots for cells in that chunk
```

---

## Issue 2: Full VBO rebuild on any change (solved by issue 1)

Per-chunk VBOs naturally solve this. When a cell in chunk (3, 7) changes, only chunk
(3, 7)'s VBO rebuilds. All other chunk VBOs are untouched.

Without per-chunk VBOs, an alternative is to use `glBufferSubData` to patch the affected
cell's vertices within the global VBO. This requires stable vertex layout (each cell
always occupies the same byte range). More complex than per-chunk VBOs and less benefit.

Per-chunk VBOs are the recommended approach since they solve issues 1 and 2 simultaneously.

---

## Issue 3: Linear fragment scan in findCellFragments

### Problem

`findCellFragments(pos, face)` iterates ALL fragments of a resolved entry to find ones
matching `(pos, face)`. A 20x20 mural has ~400 fragments. During VBO rebuild, this is
called for every cell, making rebuild cost O(cells * fragments_per_decal).

### Fix: fragment index per resolved entry

Build a `Map<Long, List<SurfaceFragment>>` (keyed by `packKey(pos, face)`) when creating
the `ResolvedEntry`. The lookup becomes O(1).

```java
public record ResolvedEntry(
    Decal decal, ResolvedSurface surface, ProjectionState projState,
    AABB bounds, Map<Long, List<SurfaceFragment>> fragmentIndex
) {
    static ResolvedEntry create(Decal decal, ResolvedSurface surface,
                                 ProjectionState projState, AABB bounds) {
        Map<Long, List<SurfaceFragment>> index = new HashMap<>();
        for (SurfaceFragment frag : surface.fragments()) {
            long key = ClientSpatialIndex.packKey(frag.pos(), frag.faceNormal());
            index.computeIfAbsent(key, k -> new ArrayList<>()).add(frag);
        }
        return new ResolvedEntry(decal, surface, projState, bounds, index);
    }
}
```

Then `findCellFragments` becomes:

```java
private static List<SurfaceFragment> findCellFragments(BlockPos pos, Direction face) {
    long key = ClientSpatialIndex.packKey(pos, face);
    var refs = ClientSpatialIndex.getRefsAt(pos, face);
    for (var ref : refs) {
        ResolvedEntry entry = resolvedCache.get(ref.decalId());
        if (entry == null) continue;
        List<SurfaceFragment> matches = entry.fragmentIndex().getOrDefault(key, List.of());
        if (!matches.isEmpty()) return matches;
    }
    return List.of();
}
```

Cost: 8 bytes per fragment for the HashMap entry overhead. Trivial.

---

## Issue 4: Redundant isSolidRender checks (minor)

`compositeCell` checks `isSolidRender` (level 1) and `rebuildVBO` checks it again
(level 3) for the same cell. Remove the check from `rebuildVBO` since the compositor
already handles it -- if the face is occluded, the cell won't exist in `allCells()`.

---

## Implementation order

1. **Issue 0** (targeted dirty marking) -- highest impact, smallest change. 1 file
   modified (`DecalRenderer`), 1 file modified (`DeferredInvalidator` to thread
   `changedBlocks`). Immediately fixes the "block place/break recomposites everything"
   problem.

2. **Issue 3** (fragment index) -- prerequisite for efficient per-chunk VBO rebuild.
   Small change to `ResolvedEntry` construction and `findCellFragments`.

3. **Issues 1+2** (per-chunk VBOs) -- the big structural change. Replaces the single
   global VBO with per-chunk VBOs, adds chunk-level frustum culling, limits rebuild
   scope to affected chunks. Depends on issue 3 for efficient per-cell vertex building.

4. **Issue 4** (redundant check removal) -- trivial cleanup, do anytime.
