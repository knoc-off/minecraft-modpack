package dev.paintcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.paintcraft.core.Decal;
import dev.paintcraft.item.StampData;
import dev.paintcraft.item.StampItem;
import dev.paintcraft.projection.ProjectionResolver;
import dev.paintcraft.projection.ProjectionVolume;
import dev.paintcraft.projection.ResolvedSurface;
import dev.paintcraft.projection.SurfaceFragment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.UUID;

/**
 * Renders a semi-transparent ghost preview of a loaded stamp at the player's crosshair target.
 */
public final class StampPreviewRenderer {

    private static BlockPos lastPos = null;
    private static Direction lastFace = null;
    private static Direction lastUp = null;
    private static DecalTexture previewTexture = null;
    private static ResolvedSurface previewResolved = null;
    private static Decal previewDecal = null;

    private StampPreviewRenderer() {}

    public static void render(PoseStack poseStack, MultiBufferSource bufferSource, Vec3 cameraPos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        ItemStack held = mc.player.getMainHandItem();
        if (!(held.getItem() instanceof StampItem) || !StampItem.isLoaded(held)) return;

        // Check crosshair target
        if (!(mc.hitResult instanceof BlockHitResult hit)) return;
        if (hit.getType() != HitResult.Type.BLOCK) return;

        BlockPos pos = hit.getBlockPos();
        Direction face = hit.getDirection();
        if (mc.level.getBlockState(pos).isAir()) return;

        Direction up = face.getAxis().isVertical() ? mc.player.getDirection() : Direction.UP;

        // Rebuild preview if target changed
        if (!pos.equals(lastPos) || face != lastFace || up != lastUp) {
            rebuildPreview(held, pos, face, up);
        }

        if (previewResolved == null || previewResolved.isEmpty() || previewTexture == null) return;

        // Render ghost fragments
        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        Matrix4f matrix = poseStack.last().pose();

        RenderType renderType = DecalRenderType.decal(previewTexture.location());
        VertexConsumer consumer = bufferSource.getBuffer(renderType);

        BlockAndTintGetter level = mc.level;
        Direction right = previewDecal.right();

        for (SurfaceFragment frag : previewResolved.fragments()) {
            renderGhostFragment(consumer, matrix, previewDecal, frag, level, right, up);
        }

        poseStack.popPose();
    }

    private static void rebuildPreview(ItemStack stack, BlockPos pos, Direction face, Direction up) {
        lastPos = pos;
        lastFace = face;
        lastUp = up;

        StampData data = StampItem.getData(stack);
        if (data == null) {
            previewResolved = null;
            return;
        }

        // Build a temporary decal for resolving
        previewDecal = new Decal(
            UUID.randomUUID(), 0, pos, face, up,
            data.widthPx(), data.heightPx(), 1.0f,
            data.pixels(), (byte) 0
        );

        // Resolve surface fragments
        previewResolved = ProjectionResolver.resolve(previewDecal, Minecraft.getInstance().level);

        // Always recreate texture to avoid dimension mismatches
        if (previewTexture != null) {
            previewTexture.close();
        }
        previewTexture = new DecalTexture(previewDecal);
    }

    private static void renderGhostFragment(VertexConsumer consumer, Matrix4f matrix,
                                             Decal decal, SurfaceFragment frag,
                                             BlockAndTintGetter level,
                                             Direction right, Direction up) {
        Direction normal = frag.faceNormal();
        float offset = 0.002f; // slight offset to avoid z-fighting
        float nx = normal.getStepX() * offset;
        float ny = normal.getStepY() * offset;
        float nz = normal.getStepZ() * offset;

        float[] v = frag.vertices();
        float[] uv = frag.uvs();

        // Compute lighting (same as DecalRenderer but with ghost alpha)
        float[] cornerAO = DecalLighting.computeCornerAO(level, frag.pos(), normal, right, up);
        int[] cornerLight = DecalLighting.computeCornerLight(level, frag.pos(), normal, right, up);
        float faceShade = level.getShade(normal, true);

        float bx = frag.pos().getX();
        float by = frag.pos().getY();
        float bz = frag.pos().getZ();
        float rx = right.getStepX(), ry = right.getStepY(), rz = right.getStepZ();
        float ux = up.getStepX(), uy = up.getStepY(), uz = up.getStepZ();
        float ox = bx + (rx < 0 ? 1 : 0) + (ux < 0 ? 1 : 0);
        float oy = by + (ry < 0 ? 1 : 0) + (uy < 0 ? 1 : 0);
        float oz = bz + (rz < 0 ? 1 : 0) + (uz < 0 ? 1 : 0);

        int ghostAlpha = 160; // semi-transparent

        for (int i = 0; i < 4; i++) {
            float vx = v[i * 3], vy = v[i * 3 + 1], vz = v[i * 3 + 2];

            float dx = vx - ox, dy = vy - oy, dz = vz - oz;
            float fracRight = Math.clamp(dx * rx + dy * ry + dz * rz, 0f, 1f);
            float fracUp = Math.clamp(dx * ux + dy * uy + dz * uz, 0f, 1f);

            float ao = DecalLighting.interpolateAO(cornerAO, fracRight, fracUp);
            int light = DecalLighting.interpolateLight(cornerLight, fracRight, fracUp);
            int shade = (int) (ao * faceShade * 255);

            consumer.addVertex(matrix, vx + nx, vy + ny, vz + nz)
                .setColor(shade, shade, shade, ghostAlpha)
                .setUv(uv[i * 2], uv[i * 2 + 1])
                .setLight(light);
        }
    }

    public static void invalidate() {
        lastPos = null;
        lastFace = null;
        lastUp = null;
        if (previewTexture != null) {
            previewTexture.close();
            previewTexture = null;
        }
        previewResolved = null;
        previewDecal = null;
    }
}
