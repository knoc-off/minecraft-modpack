package dev.paintcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.paintcraft.core.Decal;
import dev.paintcraft.core.FaceFrame;
import dev.paintcraft.projection.ResolvedSurface;
import dev.paintcraft.projection.SurfaceFragment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class DecalRenderer {

    private static final Map<UUID, ResolvedEntry> resolvedCache = new ConcurrentHashMap<>();

    private DecalRenderer() {}

    public static void cacheResolved(UUID decalId, Decal decal, DecalTexture texture, ResolvedSurface resolved) {
        resolvedCache.put(decalId, new ResolvedEntry(decal, texture, resolved));
    }

    public static void invalidate(UUID decalId) {
        resolvedCache.remove(decalId);
    }

    public static void invalidateAll() {
        resolvedCache.clear();
    }

    public static void renderAll(PoseStack poseStack, MultiBufferSource bufferSource, Vec3 cameraPos) {
        if (resolvedCache.isEmpty()) return;

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        Matrix4f matrix = poseStack.last().pose();

        // Sort by zOrder ascending: lower zOrder (back) renders first,
        // higher zOrder (front) renders last and overwrites in color buffer
        List<ResolvedEntry> sorted = new ArrayList<>(resolvedCache.values());
        sorted.sort(Comparator.comparingLong(e -> e.decal.zOrder()));

        for (ResolvedEntry entry : sorted) {
            ResolvedSurface resolved = entry.resolved;
            if (resolved.isEmpty()) continue;

            Decal decal = entry.decal;
            double distSq = cameraPos.distanceToSqr(Vec3.atCenterOf(decal.anchor()));
            int renderDist = dev.paintcraft.ModConfig.CONFIG.renderDistance.get();
            if (distSq > (long) renderDist * renderDist) continue;

            float baseEpsilon = 0.0001f;

            // Group fragments by their individual z-tier so each group gets
            // the correct polygon offset (prevents Z-fighting when a multi-block
            // canvas has different tiers on different blocks)
            Map<Integer, List<SurfaceFragment>> byTier = new TreeMap<>();
            for (SurfaceFragment frag : resolved.fragments()) {
                byTier.computeIfAbsent(frag.zTier(), k -> new ArrayList<>()).add(frag);
            }

            for (var tierGroup : byTier.entrySet()) {
                RenderType renderType = DecalRenderType.decal(entry.texture.location(), tierGroup.getKey());
                VertexConsumer consumer = bufferSource.getBuffer(renderType);
                for (SurfaceFragment frag : tierGroup.getValue()) {
                    renderFragment(consumer, matrix, decal, frag, baseEpsilon);
                }
            }
        }

        poseStack.popPose();
    }

    private static void renderFragment(VertexConsumer consumer, Matrix4f matrix,
                                        Decal decal, SurfaceFragment frag,
                                        float baseEpsilon) {
        Direction normal = frag.faceNormal();
        float offset = baseEpsilon;
        float nx = normal.getStepX() * offset;
        float ny = normal.getStepY() * offset;
        float nz = normal.getStepZ() * offset;

        float[] v = frag.vertices();
        float[] uv = frag.uvs();

        BlockAndTintGetter level = Minecraft.getInstance().level;
        FaceFrame frame = decal.frame();
        Direction right = frame.right();
        Direction up = frame.up();

        // Compute per-corner AO and lightmap for this block face
        float[] cornerAO = DecalLighting.computeCornerAO(level, frag.pos(), normal, right, up);
        int[] cornerLight = DecalLighting.computeCornerLight(level, frag.pos(), normal, right, up);

        // Face shade multiplier (vanilla directional shading)
        float faceShade = level.getShade(normal, true);

        // Block origin for computing fractional vertex positions within the face
        float bx = frag.pos().getX();
        float by = frag.pos().getY();
        float bz = frag.pos().getZ();
        float rx = right.getStepX(), ry = right.getStepY(), rz = right.getStepZ();
        float ux = up.getStepX(), uy = up.getStepY(), uz = up.getStepZ();

        // For negative-step directions, the face starts at block+1 on that axis
        // so we shift the origin to match where fracRight/fracUp = 0
        float ox = bx + (rx < 0 ? 1 : 0) + (ux < 0 ? 1 : 0);
        float oy = by + (ry < 0 ? 1 : 0) + (uy < 0 ? 1 : 0);
        float oz = bz + (rz < 0 ? 1 : 0) + (uz < 0 ? 1 : 0);

        // Emit 4 vertices with per-vertex AO + light
        for (int i = 0; i < 4; i++) {
            float vx = v[i * 3], vy = v[i * 3 + 1], vz = v[i * 3 + 2];

            // Fractional position within the block face (0-1)
            float dx = vx - ox, dy = vy - oy, dz = vz - oz;
            float fracRight = dx * rx + dy * ry + dz * rz;
            float fracUp = dx * ux + dy * uy + dz * uz;
            fracRight = Math.clamp(fracRight, 0f, 1f);
            fracUp = Math.clamp(fracUp, 0f, 1f);

            float ao = DecalLighting.interpolateAO(cornerAO, fracRight, fracUp);
            int light = DecalLighting.interpolateLight(cornerLight, fracRight, fracUp);

            int shade = (int) (ao * faceShade * 255);

            consumer.addVertex(matrix, vx + nx, vy + ny, vz + nz)
                .setColor(shade, shade, shade, 255)
                .setUv(uv[i * 2], uv[i * 2 + 1])
                .setLight(light);
        }
    }

    private record ResolvedEntry(Decal decal, DecalTexture texture, ResolvedSurface resolved) {}
}
