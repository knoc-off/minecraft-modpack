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
import org.joml.Matrix4f;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class DecalRenderer {

    private static final Map<UUID, ResolvedEntry> resolvedCache = new ConcurrentHashMap<>();

    // Per-chunk VBOs
    private static final Map<ChunkPos, ChunkVBO> chunkVBOs = new HashMap<>();
    private static final Set<ChunkPos> dirtyChunks = new HashSet<>();

    // Chunks waiting for light engine to confirm data is applied
    private static final Set<ChunkPos> pendingLightChunks = new HashSet<>();

    // Client-side block change detection: cached states at decal surface positions
    private static final Map<BlockPos, BlockState> watchedBlockStates = new HashMap<>();
    private static final Map<BlockPos, Set<UUID>> watchPosToDecals = new HashMap<>();
    private static boolean watchListDirty = true;

    private static final Matrix4f viewMat = new Matrix4f();

    // Set off-thread when the client config is (re)loaded; consumed on the render thread in
    // renderAll() to re-mesh all decal chunks so toggling reliefEnabled takes effect immediately.
    private static volatile boolean configReloaded = false;

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
        watchListDirty = true;
        // Dirty OLD fragment chunks before overwriting (so removed faces get VBO rebuilt)
        ResolvedEntry existing = resolvedCache.get(decalId);
        if (existing != null) {
            if (changedBlocks != null) {
                // Block change: dirty old fragment chunks so orphans are cleaned up
                for (SurfaceFragment frag : existing.surface().fragments()) {
                    dirtyChunks.add(new ChunkPos(frag.pos()));
                }
            } else {
                CellCompositor.markDecalDirty(decalId);
            }
        }

        AABB bounds = (existing != null) ? existing.bounds() : Projection.fromDecal(decal).toBoundingBox();
        resolvedCache.put(decalId, ResolvedEntry.create(decal, resolved, projState, bounds));

        // Index the full projection volume (inflated to match the resolver's bounds±1 scan) so a
        // block update anywhere inside it re-resolves this decal, even if it currently has no
        // fragment at the changed position. Makes disassembly/late-block cases order-independent.
        ClientSpatialIndex.registerVolume(decalId, bounds.inflate(1.0));

        // Dirty NEW fragment chunks
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
        watchListDirty = true;
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
        watchedBlockStates.clear();
        watchPosToDecals.clear();
        watchListDirty = true;
        for (ChunkVBO cvbo : chunkVBOs.values()) {
            cvbo.vbo.close();
        }
        chunkVBOs.clear();
    }

    public static void markChunkDirty(ChunkPos chunk) {
        dirtyChunks.add(chunk);
    }

    public static void onChunkUnload(ChunkPos chunk) {
        CellCompositor.removeChunk(chunk);
        ChunkVBO cvbo = chunkVBOs.remove(chunk);
        if (cvbo != null) cvbo.vbo.close();
    }

    public static ResolvedEntry getResolved(UUID decalId) {
        return resolvedCache.get(decalId);
    }

    public static Collection<ResolvedEntry> allResolved() {
        return resolvedCache.values();
    }

    // --- Client-Side Block Change Detection ---

    /**
     * Detect block changes at decal surface positions. Called every frame BEFORE
     * DeferredInvalidator.flush() so local player actions are detected instantly
     * without waiting for the server round-trip invalidation packet.
     */
    private static void detectLocalBlockChanges(Level level) {
        if (watchedBlockStates.isEmpty()) return;

        Set<BlockPos> changed = null;
        for (var entry : watchedBlockStates.entrySet()) {
            BlockState current = level.getBlockState(entry.getKey());
            if (!current.equals(entry.getValue())) {
                if (changed == null) changed = new HashSet<>();
                changed.add(entry.getKey());
                entry.setValue(current);
            }
        }

        if (changed == null) return;

        // Find affected decals and trigger immediate invalidation
        Set<UUID> affected = new HashSet<>();
        for (BlockPos pos : changed) {
            Set<UUID> ids = watchPosToDecals.get(pos);
            if (ids != null) affected.addAll(ids);
        }
        if (!affected.isEmpty()) {
            DeferredInvalidator.invalidate(affected, changed);
        }
    }

    /**
     * Rebuild the watch list of block positions to monitor for changes.
     * Watches each fragment's block position + the block one step toward the projector
     * (so we detect both "surface broken" and "block placed in front").
     */
    private static void rebuildWatchList(Level level) {
        watchedBlockStates.clear();
        watchPosToDecals.clear();
        watchListDirty = false;

        for (var entry : resolvedCache.entrySet()) {
            UUID id = entry.getKey();
            ResolvedEntry resolved = entry.getValue();
            Direction toward = resolved.decal().normal().getOpposite(); // toward projector

            for (SurfaceFragment frag : resolved.surface().fragments()) {
                addWatch(level, frag.pos(), id);
                addWatch(level, frag.pos().relative(toward), id);
            }
        }
    }

    private static void addWatch(Level level, BlockPos pos, UUID decalId) {
        watchedBlockStates.computeIfAbsent(pos, p -> level.getBlockState(p));
        watchPosToDecals.computeIfAbsent(pos, k -> new HashSet<>()).add(decalId);
    }

    // --- Render Entry Point ---

    public static void renderAll(Vec3 cameraPos, Frustum frustum,
                                  Matrix4f modelViewMatrix, Matrix4f projectionMatrix) {
        if (resolvedCache.isEmpty() && chunkVBOs.isEmpty()) return;

        long t0 = System.nanoTime();
        DebugOverlay.Stats dbg = DebugOverlay.stats();
        boolean profiling = DebugOverlay.isEnabled();
        if (profiling) dbg.reset();

        Level level = Minecraft.getInstance().level;

        // Config (re)loaded: re-mesh every decal chunk so reliefEnabled / relief params apply now.
        if (configReloaded) {
            configReloaded = false;
            dirtyChunks.addAll(chunkVBOs.keySet());
        }

        // 0a. Detect client-side block changes (instant, no server round-trip)
        detectLocalBlockChanges(level);

        // 0b. Process pending invalidations (server packets + local detections)
        DeferredInvalidator.flush();

        // 0c. Rebuild watch list if resolved cache changed
        if (watchListDirty) {
            rebuildWatchList(level);
        }

        // 1. Composite dirty cells (also populates lastFlushedChunks)
        CellCompositor.flush();

        // Add compositor's flushed chunks to our dirty set
        dirtyChunks.addAll(CellCompositor.lastFlushedChunks());

        // 2. Rebuild dirty chunk VBOs, gated on light readiness
        if (!dirtyChunks.isEmpty() || !pendingLightChunks.isEmpty()) {
            LevelLightEngine lightEngine = Minecraft.getInstance().level.getLightEngine();

            // Gate dirty chunks — if light not ready, defer to pending
            for (ChunkPos chunk : dirtyChunks) {
                if (lightEngine.lightOnInSection(SectionPos.of(chunk, 0))) {
                    rebuildChunkVBO(chunk);
                } else {
                    pendingLightChunks.add(chunk);
                }
            }
            dirtyChunks.clear();

            // Promote pending chunks whose light is now ready
            var it = pendingLightChunks.iterator();
            while (it.hasNext()) {
                ChunkPos chunk = it.next();
                if (lightEngine.lightOnInSection(SectionPos.of(chunk, 0))) {
                    rebuildChunkVBO(chunk);
                    it.remove();
                }
            }
        }

        // 3. Upload composite atlas
        CellCompositor.upload();

        if (chunkVBOs.isEmpty()) return;

        // 4. Set up shared render state
        viewMat.set(modelViewMatrix)
            .translate((float) (-cameraPos.x), (float) (-cameraPos.y), (float) (-cameraPos.z));

        float savedFogStart = RenderSystem.getShaderFogStart();
        float savedFogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        var window = Minecraft.getInstance().getWindow();
        RenderType rt = DecalRenderType.decal(CellCompositor.atlasLocation());
        rt.setupRenderState();
        ShaderInstance shader = RenderSystem.getShader();
        shader.setDefaultUniforms(VertexFormat.Mode.QUADS, viewMat, projectionMatrix, window);
        shader.apply();

        // 5. Draw each chunk VBO with frustum + distance culling
        int drawCalls = 0;
        int culled = 0;
        double maxDistSq = ModConfig.CONFIG.renderDistance.get();
        maxDistSq *= maxDistSq;
        for (var entry : chunkVBOs.entrySet()) {
            ChunkVBO cvbo = entry.getValue();
            if (!frustum.isVisible(cvbo.bounds)) {
                culled++;
                continue;
            }
            // Distance cull: skip chunks whose bounds are entirely beyond render distance
            double closestDistSq = cvbo.bounds.distanceToSqr(cameraPos);
            if (closestDistSq > maxDistSq) {
                culled++;
                continue;
            }
            cvbo.vbo.bind();
            cvbo.vbo.draw();
            drawCalls++;
        }

        shader.clear();
        VertexBuffer.unbind();
        rt.clearRenderState();

        RenderSystem.setShaderFogStart(savedFogStart);
        RenderSystem.setShaderFogEnd(savedFogEnd);

        if (profiling) {
            dbg.renderTimeNanos = System.nanoTime() - t0;
            dbg.drawCalls = drawCalls;
            dbg.frustumCulled = culled;
            dbg.totalDecals = resolvedCache.size();
            dbg.visibleDecals = CellCompositor.allCells().size();
            dbg.bakedBufferCount = chunkVBOs.size();
            ClientSpatialIndex.fillStats(dbg);
        }
    }

    // --- Per-Chunk VBO Rebuild ---

    private static void rebuildChunkVBO(ChunkPos chunk) {
        // Sync compositor with spatial index: create missing cells, remove orphans
        Set<Long> spatialKeys = ClientSpatialIndex.getCellKeysInChunk(chunk);
        CellCompositor.syncWithSpatialIndex(chunk, spatialKeys);

        var chunkCells = CellCompositor.getCellsInChunk(chunk);
        if (chunkCells.isEmpty()) {
            ChunkVBO old = chunkVBOs.remove(chunk);
            if (old != null) old.vbo.close();
            return;
        }

        boolean relief = ModConfig.CONFIG.reliefEnabled.get();
        int estimatedQuads = chunkCells.size() * (relief ? 16 : 2);
        int vertexBytes = estimatedQuads * 4 * DefaultVertexFormat.BLOCK.getVertexSize();

        // bounds: { minX, minY, minZ, maxX, maxY, maxZ }
        double[] bnds = { Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
                          -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE };

        BlockAndTintGetter level = Minecraft.getInstance().level;

        try (ByteBufferBuilder byteBuffer = new ByteBufferBuilder(vertexBytes)) {
            BufferBuilder builder = new BufferBuilder(byteBuffer,
                VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);

            for (CellCompositor.CellData cell : chunkCells) {
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
                        float fracUp = Math.clamp(dx * ux + dy * uy + dz * uz, 0f, 1f);

                        float worldX = vx;
                        float worldY = vy;
                        float worldZ = vz;

                        if (worldX < bnds[0]) bnds[0] = worldX;
                        if (worldY < bnds[1]) bnds[1] = worldY;
                        if (worldZ < bnds[2]) bnds[2] = worldZ;
                        if (worldX > bnds[3]) bnds[3] = worldX;
                        if (worldY > bnds[4]) bnds[4] = worldY;
                        if (worldZ > bnds[5]) bnds[5] = worldZ;

                        float ao = DecalLighting.interpolateAO(cornerAO, fracRight, fracUp);
                        int light = DecalLighting.interpolateLight(cornerLight, fracRight, fracUp);
                        int shade = (int) (ao * faceShade * 255);

                        float u = CellCompositor.atlasU(slotIndex, fracRight);
                        float vv = CellCompositor.atlasV(slotIndex, 1.0f - fracUp);

                        builder.addVertex(worldX, worldY, worldZ)
                            .setColor(shade, shade, shade, 255)
                            .setUv(u, vv)
                            .setLight(light)
                            .setNormal(nx, ny, nz);
                    }
                }
            }

            MeshData mesh = builder.build();
            if (mesh != null) {
                ChunkVBO existing = chunkVBOs.get(chunk);
                VertexBuffer vbo;
                if (existing != null) {
                    vbo = existing.vbo;
                } else {
                    vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
                }
                vbo.bind();
                vbo.upload(mesh);
                VertexBuffer.unbind();
                chunkVBOs.put(chunk, new ChunkVBO(vbo, new AABB(bnds[0], bnds[1], bnds[2], bnds[3], bnds[4], bnds[5])));
            } else {
                ChunkVBO old = chunkVBOs.remove(chunk);
                if (old != null) old.vbo.close();
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

    // --- Derived stack-height relief meshing ---

    /**
     * Emit 3D relief geometry for one cell from its derived height grid: a top "cap" quad per
     * painted grid cell (extruded outward by stackHeight × thickness), plus side walls wherever a
     * neighbour grid cell (including across block boundaries) is lower. Geometry is generated in the
     * canonical face's local (right, up) space over the full block face plane.
     */
    private static void emitCellRelief(BufferBuilder builder, BlockAndTintGetter level,
                                       CellCompositor.CellData cell, double[] bnds) {
        byte[] h = cell.heights();
        int res = ModConfig.CONFIG.reliefHeightRes.get().cells;
        if (h.length != res * res) return; // res changed since composite; will rebuild on recomposite
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

        // reusable corner buffers (uR, vUp, depth)
        float[] cu = new float[4], cv = new float[4], cw = new float[4];

        for (int gv = 0; gv < res; gv++) {
            for (int gu = 0; gu < res; gu++) {
                int hc = h[gv * res + gu] & 0xFF;
                if (hc == 0) continue;
                // Layer 0 (the first layer, directly on the block) has zero thickness so it looks
                // exactly as before; only layer 1+ extrudes. depth(h) = max(0, h-1) * thickness.
                float capW = Math.max(0, hc - 1) * t;

                float uR0 = gu * inv, uR1 = (gu + 1) * inv;
                float vUp1 = 1f - gv * inv;        // top edge of this grid row (toward +up)
                float vUp0 = 1f - (gv + 1) * inv;  // bottom edge

                // Walls sample this cell's OWN centre texel (always opaque) rather than the texel at
                // the shared edge — otherwise the boundary texel rounds onto the transparent
                // neighbour under NEAREST filtering and the whole wall is alpha-discarded.
                float wu = (gu + 0.5f) * inv;
                float wv = 1f - (gv + 0.5f) * inv;

                // Cap (top face): per-texel UV. NO_CULL so winding is forgiving.
                cu[0] = uR0; cv[0] = vUp0; cw[0] = capW;
                cu[1] = uR1; cv[1] = vUp0; cw[1] = capW;
                cu[2] = uR1; cv[2] = vUp1; cw[2] = capW;
                cu[3] = uR0; cv[3] = vUp1; cw[3] = capW;
                emitReliefQuad(builder, bnds, ox, oy, oz, rx, ry, rz, ux, uy, uz, nx, ny, nz,
                    slot, cornerAO, cornerLight, faceShade, 1.0f, cu, cv, cw, nx, ny, nz, false, 0f, 0f);

                // Right wall (+right) where right neighbour is lower
                float w0 = Math.max(0, heightAt(cell, gu + 1, gv, res, right, up, face) - 1) * t;
                if (w0 < capW) {
                    cu[0] = uR1; cv[0] = vUp0; cw[0] = w0;
                    cu[1] = uR1; cv[1] = vUp1; cw[1] = w0;
                    cu[2] = uR1; cv[2] = vUp1; cw[2] = capW;
                    cu[3] = uR1; cv[3] = vUp0; cw[3] = capW;
                    emitReliefQuad(builder, bnds, ox, oy, oz, rx, ry, rz, ux, uy, uz, nx, ny, nz,
                        slot, cornerAO, cornerLight, faceShade, 0.75f, cu, cv, cw, rx, ry, rz, true, wu, wv);
                }
                // Left wall (-right)
                w0 = Math.max(0, heightAt(cell, gu - 1, gv, res, right, up, face) - 1) * t;
                if (w0 < capW) {
                    cu[0] = uR0; cv[0] = vUp0; cw[0] = w0;
                    cu[1] = uR0; cv[1] = vUp1; cw[1] = w0;
                    cu[2] = uR0; cv[2] = vUp1; cw[2] = capW;
                    cu[3] = uR0; cv[3] = vUp0; cw[3] = capW;
                    emitReliefQuad(builder, bnds, ox, oy, oz, rx, ry, rz, ux, uy, uz, nx, ny, nz,
                        slot, cornerAO, cornerLight, faceShade, 0.75f, cu, cv, cw, -rx, -ry, -rz, true, wu, wv);
                }
                // Top wall (+up): grid neighbour gv-1
                w0 = Math.max(0, heightAt(cell, gu, gv - 1, res, right, up, face) - 1) * t;
                if (w0 < capW) {
                    cu[0] = uR0; cv[0] = vUp1; cw[0] = w0;
                    cu[1] = uR1; cv[1] = vUp1; cw[1] = w0;
                    cu[2] = uR1; cv[2] = vUp1; cw[2] = capW;
                    cu[3] = uR0; cv[3] = vUp1; cw[3] = capW;
                    emitReliefQuad(builder, bnds, ox, oy, oz, rx, ry, rz, ux, uy, uz, nx, ny, nz,
                        slot, cornerAO, cornerLight, faceShade, 0.85f, cu, cv, cw, ux, uy, uz, true, wu, wv);
                }
                // Bottom wall (-up): grid neighbour gv+1
                w0 = Math.max(0, heightAt(cell, gu, gv + 1, res, right, up, face) - 1) * t;
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

    /** Stack height at a grid cell, falling back to the adjacent block's cell at the borders. */
    private static int heightAt(CellCompositor.CellData cell, int gu, int gv, int res,
                                Direction right, Direction up, Direction face) {
        byte[] h = cell.heights();
        if (gu >= 0 && gu < res && gv >= 0 && gv < res) {
            return h[gv * res + gu] & 0xFF;
        }
        BlockPos p = cell.pos();
        int ngu = gu, ngv = gv;
        if (gu < 0)        { p = p.relative(right.getOpposite()); ngu = res - 1; }
        else if (gu >= res) { p = p.relative(right);              ngu = 0; }
        if (gv < 0)         { p = p.relative(up);                 ngv = res - 1; } // above top
        else if (gv >= res) { p = p.relative(up.getOpposite());   ngv = 0; }       // below bottom

        CellCompositor.CellData nc = CellCompositor.getCell(p, face);
        if (nc == null || nc.heights() == null || nc.heights().length != res * res) return 0;
        if (ngu < 0) ngu = 0; else if (ngu >= res) ngu = res - 1;
        if (ngv < 0) ngv = 0; else if (ngv >= res) ngv = res - 1;
        return nc.heights()[ngv * res + ngu] & 0xFF;
    }

    /** Emit one relief quad (cap or wall) with per-corner lighting + atlas UVs, updating bounds. */
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
            int shade = (int) (ao * faceShade * shadeMul * 255f);
            if (shade > 255) shade = 255;

            // Walls (flatUV) sample the owning cell's centre texel so they never hit the transparent
            // neighbour texel at the shared edge; caps sample per-corner for full detail.
            float texU = flatUV ? CellCompositor.atlasU(slot, flatU) : CellCompositor.atlasU(slot, uR);
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

    private record ChunkVBO(VertexBuffer vbo, AABB bounds) {}

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
