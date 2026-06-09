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

    private DecalRenderer() {}

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

        int estimatedQuads = chunkCells.size() * 2;
        int vertexBytes = estimatedQuads * 4 * DefaultVertexFormat.BLOCK.getVertexSize();

        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

        BlockAndTintGetter level = Minecraft.getInstance().level;

        try (ByteBufferBuilder byteBuffer = new ByteBufferBuilder(vertexBytes)) {
            BufferBuilder builder = new BufferBuilder(byteBuffer,
                VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);

            for (CellCompositor.CellData cell : chunkCells) {
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

                        if (worldX < minX) minX = worldX;
                        if (worldY < minY) minY = worldY;
                        if (worldZ < minZ) minZ = worldZ;
                        if (worldX > maxX) maxX = worldX;
                        if (worldY > maxY) maxY = worldY;
                        if (worldZ > maxZ) maxZ = worldZ;

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
                chunkVBOs.put(chunk, new ChunkVBO(vbo, new AABB(minX, minY, minZ, maxX, maxY, maxZ)));
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
