Changes Summary
Phase 1: Dead Code Cleanup (~110 lines removed)

- Decal.java — Removed FLAG_EMISSIVE, singleFace(), multiBlock(), setPixels(), setPixel(), getPixel(), setFlags(), isEmissive(), right(), unused imports (IntArrayTag, ListTag). Updated ChunkPaintStorage caller to use d.frame().right().
- ColorFormat.java — Removed alpha(), red(), green(), blue(), argb()
- PixelGrid.java — Removed get(), mutableCopy(), isEmpty(), unused Arrays import
- Projection.java — Removed containsLocal(), frame(), origin()
- DisplayTransform.java — Removed toDisplayX()
- DecalTexture.java — Removed updatePixels(), isAtlasBacked(), dead width/height fields
- DecalRenderType.java — Removed flushAll(), unused MultiBufferSource import
- ClientDecalCache.java — Removed couldOverlap(), removeChunk(), unused imports
- ClientSpatialIndex.java — Removed getOverlapping()
- CellCompositor.java — Removed isEmpty()
- DebugOverlay.java — Removed 7 dead stats fields (tierGroups, maxTierDepth, culledDecals, distanceCulled, backfaceCulled, totalFragments, lightingLookups), setEnabled(), dead warning check, cleaned up display
- PaintScreen.java — Removed displayX() (identity function), inlined call sites
- BlockListWidget.java — Removed getBlocks(), removeBlock(), unused ArrayList import
- BlockSearchListWidget.java — Removed setAlreadyAdded()
- BlockSearchScreen.java — Removed clearCache()
- BlockColorCache.java — Removed clearAll()
- StampPreviewRenderer.java — Removed invalidate()
- ModNetwork.java — Removed unused Set, UUID imports
  Phase 2: Consolidate Duplications
- FaceFrame.java — Added canonical(Direction) with static CANONICAL array
- DecalRenderer.java & CellCompositor.java — Replaced private CANONICAL_FRAMES arrays with FaceFrame.canonical(face)
- PaletteCodec.java — Added writePixels(buf, pixels) and readPixels(buf) methods
- DecalCreatePayload.java, OpenEditorPayload.java, DecalSelectionPayload.java — Replaced 6 duplicated encode/decode blocks (~50 lines total) with one-liners
  Phase 3: ChunkPaintStorage API Cleanup
- ChunkPaintStorage.java — Removed fake ChunkPos parameter from get(), now just get(ServerLevel level). Updated all 9 call sites across 5 files.
- Added getDecalsNear(BlockPos) using chunk index (±1 neighbors, 9 chunks max)
- Rewrote getTopmostDecalAt() and getAllDecalsAt() to use chunk index instead of O(n) full scan
- Updated BlockChangeHandler to use chunk-scoped queries instead of allDecals()
  Phase 4: ResolvedSurface & SurfaceFragment Cleanup
- ResolvedSurface.java — Reduced from 5 fields to 1 (fragments). Removed backgroundPixels (always null), minDepth (never read), depthMap, candidates.
- ProjectionState.java — NEW: carries internal resolver state (depthMap, candidates) separately
- ProjectionResult.java — NEW: pairs ResolvedSurface + ProjectionState for resolver return values
- Updated ProjectionResolver, DeferredInvalidator, DecalRenderer, ClientDecalResolver, ModNetwork, StampPreviewRenderer
- SurfaceFragment.java — Changed u0/v0/u1/v1 from float to int, removed depth field. Removed (int) casts at consumer sites.
  Phase 5: VRAM Waste Fix (DecalAtlas Removed)
- ClientDecalCache.java — No longer creates DecalTexture per cached decal. Entry record removed; stores just Decal. Eliminates VRAM waste (potentially 64+ MB per atlas texture).
- DecalTexture.java — Simplified to standalone-only (no atlas-backed path). Removed atlasSlot().
- DecalAtlas.java — DELETED (zero remaining consumers)
- StampPreviewRenderer.java — Uses standalone DecalTexture(w, h, pixels) with direct 0→1 UVs
- DecalRenderer.java — Removed DecalTexture from ResolvedEntry, removed DecalAtlas.uploadAll() and DecalAtlas.fillStats() calls
- DebugOverlay.java — Removed atlas stats (atlasCount, atlasUtilization, atlasVramBytes)
- ClientEvents.java — Removed DecalAtlas.destroyAll() call
  Phase 6: Bug Fixes
- Depth round-trip bug — Threaded depth through ClientBrushHandler.openExistingEditor() → PaintScreen constructor → saveAndClose(). Previously always hardcoded to 1.0f, now preserves the original decal depth. Also fixed in DecalSelectionScreen.selectEntry().
- Delete handler position — Already resolved by Phase 3 (ChunkPos param removal)
  Phase 2.3: collectFaces Consolidation
- Deleted duplicate collectFaces + FaceHit from BackgroundCapture (~45 lines)
- BackgroundCapture now calls ProjectionResolver.collectFaces directly
- Made collectFaces and FaceCandidate public for cross-package access
