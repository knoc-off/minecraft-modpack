package dev.paintcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.paintcraft.core.Decal;
import dev.paintcraft.projection.ResolvedSurface;
import dev.paintcraft.projection.SurfaceFragment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.UUID;
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

        for (ResolvedEntry entry : resolvedCache.values()) {
            ResolvedSurface resolved = entry.resolved;
            if (resolved.isEmpty()) continue;

            Decal decal = entry.decal;
            double distSq = cameraPos.distanceToSqr(Vec3.atCenterOf(decal.anchor()));
            if (distSq > 128 * 128) continue;

            RenderType renderType = DecalRenderType.decal(entry.texture.location());
            VertexConsumer consumer = bufferSource.getBuffer(renderType);

            float baseEpsilon = (float) (0.001 + Math.sqrt(distSq) * 0.00002);

            for (SurfaceFragment frag : resolved.fragments()) {
                renderFragment(consumer, matrix, decal, frag, baseEpsilon);
            }
        }

        poseStack.popPose();
    }

    private static void renderFragment(VertexConsumer consumer, Matrix4f matrix,
                                        Decal decal, SurfaceFragment frag,
                                        float baseEpsilon) {
        Direction normal = frag.faceNormal();
        float offset = baseEpsilon + frag.zTier() * 0.00008f;
        float nx = normal.getStepX() * offset;
        float ny = normal.getStepY() * offset;
        float nz = normal.getStepZ() * offset;

        float[] v = frag.vertices();
        float[] uv = frag.uvs();

        int light = LevelRenderer.getLightColor(
            Minecraft.getInstance().level,
            frag.pos().relative(normal)
        );

        // Emit one textured quad for the entire fragment
        // Vertices: c0=(v[0..2]), c1=(v[3..5]), c2=(v[6..8]), c3=(v[9..11])
        // UVs: pre-computed per-vertex from projection space
        consumer.addVertex(matrix, v[0] + nx, v[1] + ny, v[2] + nz)
            .setColor(255, 255, 255, 255)
            .setUv(uv[0], uv[1])
            .setLight(light);

        consumer.addVertex(matrix, v[3] + nx, v[4] + ny, v[5] + nz)
            .setColor(255, 255, 255, 255)
            .setUv(uv[2], uv[3])
            .setLight(light);

        consumer.addVertex(matrix, v[6] + nx, v[7] + ny, v[8] + nz)
            .setColor(255, 255, 255, 255)
            .setUv(uv[4], uv[5])
            .setLight(light);

        consumer.addVertex(matrix, v[9] + nx, v[10] + ny, v[11] + nz)
            .setColor(255, 255, 255, 255)
            .setUv(uv[6], uv[7])
            .setLight(light);
    }

    private record ResolvedEntry(Decal decal, DecalTexture texture, ResolvedSurface resolved) {}
}
