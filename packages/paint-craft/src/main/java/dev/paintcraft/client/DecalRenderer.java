package dev.paintcraft.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import dev.paintcraft.ModConfig;
import dev.paintcraft.core.Decal;
import dev.paintcraft.core.FaceFrame;
import dev.paintcraft.projection.Projection;
import dev.paintcraft.projection.ProjectionState;
import dev.paintcraft.projection.ResolvedSurface;
import dev.paintcraft.projection.SurfaceFragment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class DecalRenderer {

    private static final Map<UUID, ResolvedEntry> resolvedCache = new ConcurrentHashMap<>();

    // Per-chunk VBOs — each chunk has an early set (opaque + beside-water) and a late set
    // (translucent-in-air).  Either buffer may be null when a chunk has no cells of that type.
    private static final Map<ChunkPos, ChunkVBO> chunkVBOs = new HashMap<>();
    private static final Set<ChunkPos> dirtyChunks = new HashSet<>();
    private static final Set<ChunkPos> pendingLightChunks = new HashSet<>();

    private static final Matrix4f viewMat = new Matrix4f();

    private static volatile boolean configReloaded = false;
    private static boolean irisMode = false;

    private DecalRenderer() {}

    /** Called when the client config reloads — forces a full re-mesh of all decal chunks. */
    public static void onConfigReloaded() {
        configReloaded = true;
    }

    // --- Public API ---

    public static void cacheResolved(UUID decalId, Decal decal,
                                     ResolvedSurface resolved, ProjectionState projState) {
        cacheResolved(decalId, decal, resolved, projState, null);
    }

    public static void cacheResolved(UUID decalId, Decal decal,
                                     ResolvedSurface resolved, ProjectionState projState,
                                     Set<BlockPos> changedBlocks) {
        ResolvedEntry existing = resolvedCache.get(decalId);
        if (existing != null) {
            if (changedBlocks != null) {
                for (SurfaceFragment frag : existing.surface().fragments()) {
                    dirtyChunks.add(new ChunkPos(frag.pos()));
                }
            } else {
                CellCompositor.markDecalDirty(decalId);
            }
        }

        AABB bounds = (existing != null) ? existing.bounds() : Projection.fromDecal(decal).toBoundingBox();
        resolvedCache.put(decalId, ResolvedEntry.create(decal, resolved, projState, bounds));

        ClientSpatialIndex.registerVolume(decalId, bounds.inflate(1.0));

        if (changedBlocks != null) {
            for (BlockPos pos : changedBlocks) {
                dirtyChunks.add(new ChunkPos(pos));
            }
            for (SurfaceFragment frag : resolved.fragments()) {
                dirtyChunks.add(new ChunkPos(frag.pos()));
            }
        } else {
            CellCompositor.markDecalDirty(decalId);
            for (SurfaceFragment frag : resolved.fragments()) {
                dirtyChunks.add(new ChunkPos(frag.pos()));
            }
        }
    }

    public static void invalidate(UUID decalId) {
        ClientSpatialIndex.removeVolume(decalId);
        ResolvedEntry entry = resolvedCache.remove(decalId);
        if (entry != null) {
            for (SurfaceFragment frag : entry.surface().fragments()) {
                dirtyChunks.add(new ChunkPos(frag.pos()));
            }
        }
    }

    public static void invalidateAll() {
        resolvedCache.clear();
        dirtyChunks.clear();
        pendingLightChunks.clear();
        for (ChunkVBO cvbo : chunkVBOs.values()) closeChunkVBO(cvbo);
        chunkVBOs.clear();
    }

    public static void markChunkDirty(ChunkPos chunk) {
        dirtyChunks.add(chunk);
    }

    public static void onChunkUnload(ChunkPos chunk) {
        CellCompositor.removeChunk(chunk);
        ChunkVBO cvbo = chunkVBOs.remove(chunk);
        if (cvbo != null) closeChunkVBO(cvbo);
    }

    public static ResolvedEntry getResolved(UUID decalId) {
        return resolvedCache.get(decalId);
    }

    public static Collection<ResolvedEntry> allResolved() {
        return resolvedCache.values();
    }

    // --- Render Entry Points ---

    /**
     * Update phase: detect block changes, flush compositor, rebuild dirty chunk VBOs.
     * Called once per frame before any draw calls (at AFTER_BLOCK_ENTITIES for vanilla,
     * AFTER_TRANSLUCENT_BLOCKS for Iris).
     */
    public static void update(Vec3 cameraPos, boolean shadersActive) {
        if (resolvedCache.isEmpty() && chunkVBOs.isEmpty()) return;

        long t0 = System.nanoTime();
        DebugOverlay.Stats dbg = DebugOverlay.stats();
        boolean profiling = DebugOverlay.isEnabled();
        if (profiling) {
            dbg.reset();
            dbg.totalDecals = resolvedCache.size();
        }

        // Config (re)loaded: re-mesh every chunk so reliefEnabled / relief params apply now.
        if (configReloaded) {
            configReloaded = false;
            dirtyChunks.addAll(chunkVBOs.keySet());
        }

        // Shader mode toggled: re-mesh so vertex colors switch (pre-baked AO vs white for Iris).
        if (shadersActive != irisMode) {
            irisMode = shadersActive;
            dirtyChunks.addAll(chunkVBOs.keySet());
        }

        // Process pending invalidations (server packets + ShapeWatcher events)
        DeferredInvalidator.flush();

        // Composite dirty cells; add their chunks to the dirty VBO set
        CellCompositor.flush();
        dirtyChunks.addAll(CellCompositor.lastFlushedChunks());

        // Rebuild dirty chunk VBOs, gated on light readiness
        if (!dirtyChunks.isEmpty() || !pendingLightChunks.isEmpty()) {
            LevelLightEngine lightEngine = Minecraft.getInstance().level.getLightEngine();

            for (ChunkPos chunk : dirtyChunks) {
                if (lightEngine.lightOnInSection(SectionPos.of(chunk, 0))) {
                    rebuildChunkVBO(chunk);
                } else {
                    pendingLightChunks.add(chunk);
                }
            }
            dirtyChunks.clear();

            var it = pendingLightChunks.iterator();
            while (it.hasNext()) {
                ChunkPos chunk = it.next();
                if (lightEngine.lightOnInSection(SectionPos.of(chunk, 0))) {
                    rebuildChunkVBO(chunk);
                    it.remove();
                }
            }
        }

        // Upload composite atlas after all cells are baked
        CellCompositor.upload();

        if (profiling) {
            dbg.rebuildTimeNanos = System.nanoTime() - t0;
            dbg.visibleDecals = CellCompositor.allCells().size();
            dbg.bakedBufferCount = chunkVBOs.size();
            ClientSpatialIndex.fillStats(dbg);
        }
    }

    /**
     * Draw the early set: opaque decals and translucent decals beside water.
     * Called at AFTER_BLOCK_ENTITIES (vanilla) so vanilla water renders over them correctly.
     */
    public static void drawEarly(Vec3 cameraPos, Frustum frustum,
                                  Matrix4f modelViewMatrix, Matrix4f projectionMatrix,
                                  boolean shadersActive) {
        drawGroup(ChunkVBO::early, DecalRenderType.decal(CellCompositor.atlasLocation()),
                cameraPos, frustum, modelViewMatrix, projectionMatrix, shadersActive);
    }

    /**
     * Draw the late set: translucent decals in open air, then the entity-block set
     * (BER blocks: decorated pot, chest...) with a no-depth-write render type so they
     * blend on top of the hollow BER geometry instead of culling it into a see-through hole.
     * Called at AFTER_TRANSLUCENT_BLOCKS so they composite over vanilla water correctly.
     */
    public static void drawLate(Vec3 cameraPos, Frustum frustum,
                                 Matrix4f modelViewMatrix, Matrix4f projectionMatrix,
                                 boolean shadersActive) {
        drawGroup(ChunkVBO::late, DecalRenderType.decal(CellCompositor.atlasLocation()),
                cameraPos, frustum, modelViewMatrix, projectionMatrix, shadersActive);
        drawGroup(ChunkVBO::entity, DecalRenderType.decalNoDepth(CellCompositor.atlasLocation()),
                cameraPos, frustum, modelViewMatrix, projectionMatrix, shadersActive);
    }

    private static void drawGroup(java.util.function.Function<ChunkVBO, VertexBuffer> selector,
                                 RenderType rt, Vec3 cameraPos, Frustum frustum,
                                 Matrix4f modelViewMatrix, Matrix4f projectionMatrix,
                                 boolean shadersActive) {
        if (chunkVBOs.isEmpty()) return;

        // Check whether this group has anything to draw at all
        boolean anyVbo = false;
        for (ChunkVBO cvbo : chunkVBOs.values()) {
            if (selector.apply(cvbo) != null) { anyVbo = true; break; }
        }
        if (!anyVbo) return;

        long t0 = System.nanoTime();
        DebugOverlay.Stats dbg = DebugOverlay.stats();
        boolean profiling = DebugOverlay.isEnabled();

        viewMat.set(modelViewMatrix)
            .translate((float) (-cameraPos.x), (float) (-cameraPos.y), (float) (-cameraPos.z));

        // Disable vanilla fog for our custom pass; Iris manages fog via shader pack.
        float savedFogStart = 0, savedFogEnd = 0;
        if (!shadersActive) {
            savedFogStart = RenderSystem.getShaderFogStart();
            savedFogEnd = RenderSystem.getShaderFogEnd();
            RenderSystem.setShaderFogStart(Float.MAX_VALUE);
            RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        }
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        // Both vanilla and Iris now use the same render type (vanilla translucent shader +
        // polygon offset). Iris routes it through gbuffers_water automatically.
        rt.setupRenderState();
        ShaderInstance shader = RenderSystem.getShader();
        shader.setDefaultUniforms(VertexFormat.Mode.QUADS, viewMat, projectionMatrix,
                Minecraft.getInstance().getWindow());
        shader.apply();

        int drawCalls = 0, culled = 0;
        double maxDistSq = ModConfig.CONFIG.renderDistance.get();
        maxDistSq *= maxDistSq;
        double noFrustumCullRadiusSq = ModConfig.CONFIG.frustumCullRadius.get();
        noFrustumCullRadiusSq *= noFrustumCullRadiusSq;

        for (ChunkVBO cvbo : chunkVBOs.values()) {
            VertexBuffer vbo = selector.apply(cvbo);
            if (vbo == null) continue;

            double distSq = cvbo.bounds().distanceToSqr(cameraPos);
            if (distSq > maxDistSq) { culled++; continue; }
            // Precomputed inflated bounds avoids per-frame AABB allocation (§3 cleanup)
            if (distSq > noFrustumCullRadiusSq && !frustum.isVisible(cvbo.inflatedBounds())) {
                culled++; continue;
            }

            vbo.bind();
            vbo.draw();
            drawCalls++;
        }

        shader.clear();
        VertexBuffer.unbind();
        rt.clearRenderState();

        if (!shadersActive) {
            RenderSystem.setShaderFogStart(savedFogStart);
            RenderSystem.setShaderFogEnd(savedFogEnd);
        }

        if (profiling) {
            dbg.renderTimeNanos += System.nanoTime() - t0;
            dbg.drawCalls += drawCalls;
            dbg.frustumCulled += culled;
        }
    }

    // --- Per-Chunk VBO Rebuild ---

    private static void rebuildChunkVBO(ChunkPos chunk) {
        Set<Long> spatialKeys = ClientSpatialIndex.getCellKeysInChunk(chunk);
        CellCompositor.syncWithSpatialIndex(chunk, spatialKeys);

        var allCells = CellCompositor.getCellsInChunk(chunk);
        ChunkVBO existing = chunkVBOs.get(chunk);

        if (allCells.isEmpty()) {
            if (existing != null) closeChunkVBO(existing);
            chunkVBOs.remove(chunk);
            return;
        }

        boolean relief = ModConfig.CONFIG.reliefEnabled.get();
        BlockAndTintGetter level = Minecraft.getInstance().level;

        // Split cells by pass. Entity-block cells (BER-drawn: decorated pot, chest, sign) go to
        // their own no-depth-write buffer so they don't cull the hollow BER geometry into a hole.
        List<CellCompositor.CellData> earlyCells = new ArrayList<>();
        List<CellCompositor.CellData> lateCells = new ArrayList<>();
        List<CellCompositor.CellData> entityCells = new ArrayList<>();
        for (CellCompositor.CellData cell : allCells) {
            if (cell.entityBlock()) entityCells.add(cell);
            else if (cell.latePass()) lateCells.add(cell);
            else earlyCells.add(cell);
        }

        // Bounds accumulated across ALL sets so frustum/distance culling uses the full chunk extent
        double[] bnds = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
                          -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };

        VertexBuffer newEarly = buildSetVbo(earlyCells, existing != null ? existing.early() : null,
                relief, level, bnds);
        VertexBuffer newLate  = buildSetVbo(lateCells,  existing != null ? existing.late()  : null,
                relief, level, bnds);
        // Entity-block decals are always flat (empty-model blocks never have relief), so build them
        // without relief regardless of the config toggle.
        VertexBuffer newEntity = buildSetVbo(entityCells, existing != null ? existing.entity() : null,
                false, level, bnds);

        if (newEarly == null && newLate == null && newEntity == null) {
            chunkVBOs.remove(chunk);
            return;
        }

        AABB bounds = new AABB(bnds[0], bnds[1], bnds[2], bnds[3], bnds[4], bnds[5]);
        chunkVBOs.put(chunk, new ChunkVBO(newEarly, newLate, newEntity, bounds, bounds.inflate(2.0)));
    }

    /**
     * Build and upload one vertex buffer for a set of cells.
     *
     * @param cells    cells to emit (all early or all late)
     * @param existing previous VBO to reuse the GL object from (may be null)
     * @param bnds     6-element bounds accumulator shared between early and late sets
     * @return the VBO, or null if the set produced no geometry
     */
    private static VertexBuffer buildSetVbo(List<CellCompositor.CellData> cells,
                                             VertexBuffer existing, boolean relief,
                                             BlockAndTintGetter level, double[] bnds) {
        if (cells.isEmpty()) {
            if (existing != null) existing.close();
            return null;
        }

        int estimatedQuads = cells.size() * (relief ? 16 : 2);
        int vertexBytes = estimatedQuads * 4 * DefaultVertexFormat.BLOCK.getVertexSize();

        try (ByteBufferBuilder byteBuffer = new ByteBufferBuilder(vertexBytes)) {
            BufferBuilder builder = new BufferBuilder(byteBuffer,
                    VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);

            for (CellCompositor.CellData cell : cells) {
                if (relief && cell.heights() != null) {
                    emitCellRelief(builder, level, cell, bnds);
                    continue;
                }

                BlockPos pos = cell.pos();
                Direction face = cell.face();
                int slotIndex = cell.slotIndex();

                FaceFrame frame = FaceFrame.canonical(face);
                Direction right = frame.right();
                Direction up = frame.up();

                float nx = face.getStepX(), ny = face.getStepY(), nz = face.getStepZ();

                Vec3 origin = frame.projectionOrigin(pos);
                float ox = (float) origin.x, oy = (float) origin.y, oz = (float) origin.z;
                float rx = right.getStepX(), ry = right.getStepY(), rz = right.getStepZ();
                float ux = up.getStepX(), uy = up.getStepY(), uz = up.getStepZ();

                float[] cornerAO = DecalLighting.computeCornerAO(level, pos, face, right, up);
                int[] cornerLight = DecalLighting.computeCornerLight(level, pos, face, right, up);
                float faceShade = level.getShade(face, true);

                List<SurfaceFragment> cellFragments = findCellFragments(pos, face);

                for (SurfaceFragment frag : cellFragments) {
                    float[] v = frag.vertices();

                    for (int vi = 0; vi < 4; vi++) {
                        float vx = v[vi * 3], vy = v[vi * 3 + 1], vz = v[vi * 3 + 2];

                        float dx = vx - ox, dy = vy - oy, dz = vz - oz;
                        float fracRight = Math.clamp(dx * rx + dy * ry + dz * rz, 0f, 1f);
                        float fracUp    = Math.clamp(dx * ux + dy * uy + dz * uz, 0f, 1f);

                        if (vx < bnds[0]) bnds[0] = vx;
                        if (vy < bnds[1]) bnds[1] = vy;
                        if (vz < bnds[2]) bnds[2] = vz;
                        if (vx > bnds[3]) bnds[3] = vx;
                        if (vy > bnds[4]) bnds[4] = vy;
                        if (vz > bnds[5]) bnds[5] = vz;

                        float ao = DecalLighting.interpolateAO(cornerAO, fracRight, fracUp);
                        int light = DecalLighting.interpolateLight(cornerLight, fracRight, fracUp);
                        int shade = irisMode ? 255 : (int) (ao * faceShade * 255);

                        float u  = CellCompositor.atlasU(slotIndex, fracRight);
                        float vv = CellCompositor.atlasV(slotIndex, 1.0f - fracUp);

                        builder.addVertex(vx, vy, vz)
                                .setColor(shade, shade, shade, 255)
                                .setUv(u, vv)
                                .setLight(light)
                                .setNormal(nx, ny, nz);
                    }
                }
            }

            MeshData mesh = builder.build();
            if (mesh != null) {
                VertexBuffer vbo = existing != null ? existing : new VertexBuffer(VertexBuffer.Usage.STATIC);
                vbo.bind();
                vbo.upload(mesh);
                VertexBuffer.unbind();
                return vbo;
            } else {
                if (existing != null) existing.close();
                return null;
            }
        }
    }

    private static List<SurfaceFragment> findCellFragments(BlockPos pos, Direction face) {
        long key = ClientSpatialIndex.packKey(pos, face);
        var refs = ClientSpatialIndex.getRefsAt(pos, face);
        for (var ref : refs) {
            ResolvedEntry entry = resolvedCache.get(ref.decalId());
            if (entry == null) continue;
            List<SurfaceFragment> matches = entry.fragmentIndex.getOrDefault(key, List.of());
            if (!matches.isEmpty()) return matches;
        }
        return List.of();
    }

    private static void closeChunkVBO(ChunkVBO cvbo) {
        if (cvbo.early() != null) cvbo.early().close();
        if (cvbo.late()  != null) cvbo.late().close();
        if (cvbo.entity() != null) cvbo.entity().close();
    }

    // --- Derived stack-height relief meshing ---

    private static void emitCellRelief(BufferBuilder builder, BlockAndTintGetter level,
                                       CellCompositor.CellData cell, double[] bnds) {
        byte[] h = cell.heights();
        int res = ModConfig.CONFIG.reliefHeightRes.get().cells;
        if (h.length != res * res) return;
        float t = (float) ModConfig.CONFIG.reliefLayerThickness.get().doubleValue();

        BlockPos pos = cell.pos();
        Direction face = cell.face();
        int slot = cell.slotIndex();

        FaceFrame frame = FaceFrame.canonical(face);
        Direction right = frame.right();
        Direction up = frame.up();

        float nx = face.getStepX(), ny = face.getStepY(), nz = face.getStepZ();
        Vec3 origin = frame.projectionOrigin(pos);
        double ox = origin.x, oy = origin.y, oz = origin.z;
        float rx = right.getStepX(), ry = right.getStepY(), rz = right.getStepZ();
        float ux = up.getStepX(), uy = up.getStepY(), uz = up.getStepZ();

        float[] cornerAO = DecalLighting.computeCornerAO(level, pos, face, right, up);
        int[] cornerLight = DecalLighting.computeCornerLight(level, pos, face, right, up);
        float faceShade = level.getShade(face, true);

        float inv = 1f / res;
        float[] cu = new float[4], cv = new float[4], cw = new float[4];

        // Per-cell surface depth (base) sampled from this block's VoxelShape, plus the
        // stack extrusion, gives the front offset along the normal for each grid cell.
        // baseW <= 0 (recessed surfaces sit inward from the full-block face plane), so a
        // single decal (hc==1) renders flush with the actual surface rather than the block
        // face — this also lets stairs/slabs split their decal across sub-block steps.
        List<AABB> selfBoxes = collisionBoxes(level, pos, face);
        float[] front = new float[res * res];
        for (int gv = 0; gv < res; gv++) {
            for (int gu = 0; gu < res; gu++) {
                int hc = h[gv * res + gu] & 0xFF;
                double fr = (gu + 0.5) * inv;
                double fu = 1.0 - (gv + 0.5) * inv;
                float baseW = -recession(selfBoxes, face, right, up, fr, fu);
                front[gv * res + gu] = baseW + Math.max(0, hc - 1) * t;
            }
        }

        for (int gv = 0; gv < res; gv++) {
            for (int gu = 0; gu < res; gu++) {
                int hc = h[gv * res + gu] & 0xFF;
                if (hc == 0) continue;
                float capW = front[gv * res + gu];

                float uR0 = gu * inv, uR1 = (gu + 1) * inv;
                float vUp1 = 1f - gv * inv;
                float vUp0 = 1f - (gv + 1) * inv;

                float wu = (gu + 0.5f) * inv;
                float wv = 1f - (gv + 0.5f) * inv;

                cu[0] = uR0; cv[0] = vUp0; cw[0] = capW;
                cu[1] = uR1; cv[1] = vUp0; cw[1] = capW;
                cu[2] = uR1; cv[2] = vUp1; cw[2] = capW;
                cu[3] = uR0; cv[3] = vUp1; cw[3] = capW;
                emitReliefQuad(builder, bnds, ox, oy, oz, rx, ry, rz, ux, uy, uz, nx, ny, nz,
                        slot, cornerAO, cornerLight, faceShade, 1.0f, cu, cv, cw, nx, ny, nz, false, 0f, 0f);

                float w0 = frontAt(level, cell, front, gu + 1, gv, res, right, up, face, t, inv);
                if (w0 < capW) {
                    cu[0] = uR1; cv[0] = vUp0; cw[0] = w0;
                    cu[1] = uR1; cv[1] = vUp1; cw[1] = w0;
                    cu[2] = uR1; cv[2] = vUp1; cw[2] = capW;
                    cu[3] = uR1; cv[3] = vUp0; cw[3] = capW;
                    emitReliefQuad(builder, bnds, ox, oy, oz, rx, ry, rz, ux, uy, uz, nx, ny, nz,
                            slot, cornerAO, cornerLight, faceShade, 0.75f, cu, cv, cw, rx, ry, rz, true, wu, wv);
                }
                w0 = frontAt(level, cell, front, gu - 1, gv, res, right, up, face, t, inv);
                if (w0 < capW) {
                    cu[0] = uR0; cv[0] = vUp0; cw[0] = w0;
                    cu[1] = uR0; cv[1] = vUp1; cw[1] = w0;
                    cu[2] = uR0; cv[2] = vUp1; cw[2] = capW;
                    cu[3] = uR0; cv[3] = vUp0; cw[3] = capW;
                    emitReliefQuad(builder, bnds, ox, oy, oz, rx, ry, rz, ux, uy, uz, nx, ny, nz,
                            slot, cornerAO, cornerLight, faceShade, 0.75f, cu, cv, cw, -rx, -ry, -rz, true, wu, wv);
                }
                w0 = frontAt(level, cell, front, gu, gv - 1, res, right, up, face, t, inv);
                if (w0 < capW) {
                    cu[0] = uR0; cv[0] = vUp1; cw[0] = w0;
                    cu[1] = uR1; cv[1] = vUp1; cw[1] = w0;
                    cu[2] = uR1; cv[2] = vUp1; cw[2] = capW;
                    cu[3] = uR0; cv[3] = vUp1; cw[3] = capW;
                    emitReliefQuad(builder, bnds, ox, oy, oz, rx, ry, rz, ux, uy, uz, nx, ny, nz,
                            slot, cornerAO, cornerLight, faceShade, 0.85f, cu, cv, cw, ux, uy, uz, true, wu, wv);
                }
                w0 = frontAt(level, cell, front, gu, gv + 1, res, right, up, face, t, inv);
                if (w0 < capW) {
                    cu[0] = uR0; cv[0] = vUp0; cw[0] = w0;
                    cu[1] = uR1; cv[1] = vUp0; cw[1] = w0;
                    cu[2] = uR1; cv[2] = vUp0; cw[2] = capW;
                    cu[3] = uR0; cv[3] = vUp0; cw[3] = capW;
                    emitReliefQuad(builder, bnds, ox, oy, oz, rx, ry, rz, ux, uy, uz, nx, ny, nz,
                            slot, cornerAO, cornerLight, faceShade, 0.65f, cu, cv, cw, -ux, -uy, -uz, true, wu, wv);
                }
            }
        }
    }

    /**
     * Front offset (along the face normal) of a grid cell = surface depth + stack extrusion.
     * In-range cells read the precomputed {@code selfFront} grid; out-of-range indices resolve
     * the neighbouring block's cell (mirroring the wrap logic used for stack heights) and sample
     * that block's own surface depth so relief steps close against the true adjacent surface.
     */
    private static float frontAt(BlockAndTintGetter level, CellCompositor.CellData cell,
                                 float[] selfFront, int gu, int gv, int res,
                                 Direction right, Direction up, Direction face, float t, float inv) {
        if (gu >= 0 && gu < res && gv >= 0 && gv < res) {
            return selfFront[gv * res + gu];
        }
        BlockPos p = cell.pos();
        int ngu = gu, ngv = gv;
        if (gu < 0)         { p = p.relative(right.getOpposite()); ngu = res - 1; }
        else if (gu >= res) { p = p.relative(right);               ngu = 0; }
        if (gv < 0)         { p = p.relative(up);                  ngv = res - 1; }
        else if (gv >= res) { p = p.relative(up.getOpposite());    ngv = 0; }

        CellCompositor.CellData nc = CellCompositor.getCell(p, face);
        int hc = 0;
        if (nc != null && nc.heights() != null && nc.heights().length == res * res) {
            if (ngu < 0) ngu = 0; else if (ngu >= res) ngu = res - 1;
            if (ngv < 0) ngv = 0; else if (ngv >= res) ngv = res - 1;
            hc = nc.heights()[ngv * res + ngu] & 0xFF;
        } else {
            if (ngu < 0) ngu = 0; else if (ngu >= res) ngu = res - 1;
            if (ngv < 0) ngv = 0; else if (ngv >= res) ngv = res - 1;
        }

        List<AABB> boxes = collisionBoxes(level, p, face);
        double fr = (ngu + 0.5) * inv;
        double fu = 1.0 - (ngv + 0.5) * inv;
        float baseW = -recession(boxes, face, right, up, fr, fu);
        return baseW + Math.max(0, hc - 1) * t;
    }

    /** Collision boxes (block-relative 0..1 coords) for the block at {@code pos}. */
    private static List<AABB> collisionBoxes(BlockAndTintGetter level, BlockPos pos, Direction face) {
        if (level == null) return List.of();
        BlockState st = level.getBlockState(pos);
        if (st.isAir()) return List.of();
        VoxelShape shape = st.getShape(level, pos, CollisionContext.empty());
        if (shape.isEmpty()) return List.of();
        return shape.toAabbs();
    }

    /**
     * How far the frontmost surface at face-fraction ({@code fr} along right, {@code fu} along up)
     * is recessed from the full-block face plane, in block units (>= 0). 0 = flush with the block
     * face (e.g. a full cube); 0.5 = a stair's recessed step. Returns 0 when no box covers the
     * point (falls back to the block face plane).
     */
    private static float recession(List<AABB> boxes, Direction face, Direction right, Direction up,
                                   double fr, double fu) {
        if (boxes.isEmpty()) return 0f;
        Direction.Axis nAxis = face.getAxis(), rAxis = right.getAxis(), uAxis = up.getAxis();
        boolean posFace = face.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        double coordR = right.getAxisDirection() == Direction.AxisDirection.POSITIVE ? fr : 1 - fr;
        double coordU = up.getAxisDirection() == Direction.AxisDirection.POSITIVE ? fu : 1 - fu;

        float best = Float.MAX_VALUE;
        for (AABB b : boxes) {
            if (coordR < minOn(b, rAxis) - 1e-6 || coordR > maxOn(b, rAxis) + 1e-6) continue;
            if (coordU < minOn(b, uAxis) - 1e-6 || coordU > maxOn(b, uAxis) + 1e-6) continue;
            double frontCoord = posFace ? maxOn(b, nAxis) : minOn(b, nAxis);
            double rec = posFace ? (1 - frontCoord) : frontCoord;
            if (rec < best) best = (float) rec;
        }
        return best == Float.MAX_VALUE ? 0f : Math.max(0f, best);
    }

    private static double minOn(AABB b, Direction.Axis a) {
        return a == Direction.Axis.X ? b.minX : a == Direction.Axis.Y ? b.minY : b.minZ;
    }

    private static double maxOn(AABB b, Direction.Axis a) {
        return a == Direction.Axis.X ? b.maxX : a == Direction.Axis.Y ? b.maxY : b.maxZ;
    }

    private static void emitReliefQuad(BufferBuilder builder, double[] bnds,
                                       double ox, double oy, double oz,
                                       float rx, float ry, float rz, float ux, float uy, float uz,
                                       float nx, float ny, float nz, int slot,
                                       float[] cornerAO, int[] cornerLight, float faceShade,
                                       float shadeMul, float[] cu, float[] cv, float[] cw,
                                       float emitNx, float emitNy, float emitNz,
                                       boolean flatUV, float flatU, float flatV) {
        for (int i = 0; i < 4; i++) {
            float uR = cu[i], vUp = cv[i], w = cw[i];
            double wx = ox + rx * uR + ux * vUp + nx * w;
            double wy = oy + ry * uR + uy * vUp + ny * w;
            double wz = oz + rz * uR + uz * vUp + nz * w;

            float ao = DecalLighting.interpolateAO(cornerAO, uR, vUp);
            int light = DecalLighting.interpolateLight(cornerLight, uR, vUp);
            int shade = irisMode ? 255 : (int) (ao * faceShade * shadeMul * 255f);
            if (shade > 255) shade = 255;

            float texU = flatUV ? CellCompositor.atlasU(slot, flatU)      : CellCompositor.atlasU(slot, uR);
            float texV = flatUV ? CellCompositor.atlasV(slot, 1f - flatV) : CellCompositor.atlasV(slot, 1f - vUp);

            builder.addVertex((float) wx, (float) wy, (float) wz)
                    .setColor(shade, shade, shade, 255)
                    .setUv(texU, texV)
                    .setLight(light)
                    .setNormal(emitNx, emitNy, emitNz);

            if (wx < bnds[0]) bnds[0] = wx;
            if (wy < bnds[1]) bnds[1] = wy;
            if (wz < bnds[2]) bnds[2] = wz;
            if (wx > bnds[3]) bnds[3] = wx;
            if (wy > bnds[4]) bnds[4] = wy;
            if (wz > bnds[5]) bnds[5] = wz;
        }
    }

    // --- Records ---

    /**
     * Per-chunk VBO pair.
     *
     * @param early        opaque + translucent-beside-water cells (drawn at AFTER_BLOCK_ENTITIES)
     * @param late         translucent-in-air cells (drawn at AFTER_TRANSLUCENT_BLOCKS)
     * @param entity       BER-block cells (decorated pot, chest...): flat, no-depth-write, drawn late
     * @param bounds       tight AABB of all geometry in this chunk (used for distance culling)
     * @param inflatedBounds bounds.inflate(2.0) — precomputed to avoid per-frame allocation
     */
    private record ChunkVBO(VertexBuffer early, VertexBuffer late, VertexBuffer entity,
                            AABB bounds, AABB inflatedBounds) {}

    public record ResolvedEntry(Decal decal, ResolvedSurface surface,
                                ProjectionState projState, AABB bounds,
                                Map<Long, List<SurfaceFragment>> fragmentIndex) {
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
}
