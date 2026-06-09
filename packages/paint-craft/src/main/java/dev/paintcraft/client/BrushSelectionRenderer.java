package dev.paintcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.paintcraft.PaintCraft;
import dev.paintcraft.core.FaceFrame;
import dev.paintcraft.item.BrushItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

@EventBusSubscriber(modid = PaintCraft.MODID, value = Dist.CLIENT)
public final class BrushSelectionRenderer {

    private static final float WHITE_R = 1f, WHITE_G = 1f, WHITE_B = 1f, WHITE_A = 0.8f;
    private static final float GOLD_R = 1f, GOLD_G = 0.84f, GOLD_B = 0f, GOLD_A = 0.6f;
    private static final float RED_R = 1f, RED_G = 0.2f, RED_B = 0.2f, RED_A = 0.6f;

    private BrushSelectionRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        var mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (!(mc.player.getMainHandItem().getItem() instanceof BrushItem)
                && !(mc.player.getOffhandItem().getItem() instanceof BrushItem)) return;

        BlockPos corner = ClientBrushHandler.getPendingCorner();
        Direction face = ClientBrushHandler.getPendingFace();
        if (corner == null || face == null) return;

        BlockPos target = getTarget(mc.player, corner, face);

        Vec3 cam = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        var bufferSource = mc.renderBuffers().bufferSource();
        VertexConsumer lines = bufferSource.getBuffer(RenderType.lines());

        renderBlockOutline(poseStack, lines, cam, corner, WHITE_R, WHITE_G, WHITE_B, WHITE_A);

        if (target != null) {
            FaceFrame frame = FaceFrame.forFace(face, mc.player.getDirection());
            Direction right = frame.right();
            Direction up = frame.up();

            int dx = target.getX() - corner.getX();
            int dy = target.getY() - corner.getY();
            int dz = target.getZ() - corner.getZ();
            int rightOff = dx * right.getStepX() + dy * right.getStepY() + dz * right.getStepZ();
            int upOff = dx * up.getStepX() + dy * up.getStepY() + dz * up.getStepZ();

            int widthBlocks = Math.abs(rightOff) + 1;
            int heightBlocks = Math.abs(upOff) + 1;

            int nc1 = corner.getX() * face.getStepX() + corner.getY() * face.getStepY() + corner.getZ() * face.getStepZ();
            int nc2 = target.getX() * face.getStepX() + target.getY() * face.getStepY() + target.getZ() * face.getStepZ();
            int normalDiff = Math.abs(nc1 - nc2);

            boolean tooLarge = widthBlocks > ClientBrushHandler.maxCanvasSize()
                || heightBlocks > ClientBrushHandler.maxCanvasSize()
                || normalDiff > (int) ClientBrushHandler.maxDepth();

            float r = tooLarge ? RED_R : GOLD_R;
            float g = tooLarge ? RED_G : GOLD_G;
            float b = tooLarge ? RED_B : GOLD_B;
            float a = tooLarge ? RED_A : GOLD_A;

            AABB box = new AABB(
                Math.min(corner.getX(), target.getX()),
                Math.min(corner.getY(), target.getY()),
                Math.min(corner.getZ(), target.getZ()),
                Math.max(corner.getX(), target.getX()) + 1,
                Math.max(corner.getY(), target.getY()) + 1,
                Math.max(corner.getZ(), target.getZ()) + 1);
            renderBox(poseStack, lines, cam, box, r, g, b, a);
        }

        bufferSource.endBatch(RenderType.lines());
    }

    static BlockPos getTarget(Player player, BlockPos corner, Direction face) {
        var mc = Minecraft.getInstance();
        HitResult hit = mc.hitResult;

        if (hit instanceof BlockHitResult blockHit && blockHit.getType() != HitResult.Type.MISS) {
            return blockHit.getBlockPos();
        }

        return projectOntoFacePlane(player, corner, face);
    }

    static BlockPos projectOntoFacePlane(Player player, BlockPos corner, Direction face) {
        Vec3 normal = Vec3.atLowerCornerOf(face.getNormal());
        Vec3 planePoint = Vec3.atCenterOf(corner);
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 look = player.getLookAngle();

        double denom = normal.dot(look);
        if (Math.abs(denom) < 1e-6) return null;

        double t = normal.dot(planePoint.subtract(eye)) / denom;
        if (t < 0) return null;

        double reach = player.blockInteractionRange();
        if (t > reach * 2) return null;

        Vec3 intersection = eye.add(look.scale(t));
        return BlockPos.containing(intersection);
    }

    private static void renderBlockOutline(PoseStack ps, VertexConsumer buf,
                                           Vec3 cam, BlockPos pos,
                                           float r, float g, float b, float a) {
        renderBox(ps, buf, cam, new AABB(pos.getX(), pos.getY(), pos.getZ(),
            pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1), r, g, b, a);
    }

    private static void renderBox(PoseStack ps, VertexConsumer buf,
                                  Vec3 cam, AABB box,
                                  float r, float g, float b, float a) {
        LevelRenderer.renderLineBox(ps, buf,
            box.minX - cam.x, box.minY - cam.y, box.minZ - cam.z,
            box.maxX - cam.x, box.maxY - cam.y, box.maxZ - cam.z,
            r, g, b, a);
    }
}
