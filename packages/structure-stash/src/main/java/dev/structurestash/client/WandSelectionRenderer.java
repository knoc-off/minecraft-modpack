package dev.structurestash.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.structurestash.StructureStash;
import dev.structurestash.item.BlueprintWandItem;
import dev.structurestash.item.ModDataComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Renders wireframe selection visualization for the Blueprint Wand.
 * <ul>
 *   <li>No point A set: single-block wireframe at the look-at position (white).</li>
 *   <li>Point A set: anchor marker at A (white) + growing bounding box to
 *       current look-at position (gold).</li>
 * </ul>
 */
@EventBusSubscriber(modid = StructureStash.MODID, value = Dist.CLIENT)
public class WandSelectionRenderer {

    private static final float WHITE_R = 1f, WHITE_G = 1f, WHITE_B = 1f, WHITE_A = 0.8f;
    private static final float GOLD_R = 1f, GOLD_G = 0.84f, GOLD_B = 0f, GOLD_A = 0.6f;
    private static final float RED_R = 1f, RED_G = 0.2f, RED_B = 0.2f, RED_A = 0.6f;
    private static final int MAX_SIDE = 16;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        var mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Check if holding wand in either hand
        ItemStack wand = getHeldWand(mc.player);
        if (wand == null) return;

        BlockPos posA = wand.get(ModDataComponents.WAND_POS_A.get());
        BlockPos target = computeTargetPos(mc.player);

        Vec3 cam = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        var bufferSource = mc.renderBuffers().bufferSource();
        VertexConsumer lines = bufferSource.getBuffer(RenderType.lines());

        if (posA == null) {
            // No selection yet — show single-block wireframe at crosshair target
            if (target != null) {
                renderBlockOutline(poseStack, lines, cam, target, WHITE_R, WHITE_G, WHITE_B, WHITE_A);
            }
        } else {
            // Point A is set — show anchor + growing selection box
            renderBlockOutline(poseStack, lines, cam, posA, WHITE_R, WHITE_G, WHITE_B, WHITE_A);

            if (target != null) {
                BlockPos min = new BlockPos(
                    Math.min(posA.getX(), target.getX()),
                    Math.min(posA.getY(), target.getY()),
                    Math.min(posA.getZ(), target.getZ()));
                BlockPos max = new BlockPos(
                    Math.max(posA.getX(), target.getX()),
                    Math.max(posA.getY(), target.getY()),
                    Math.max(posA.getZ(), target.getZ()));

                int sx = max.getX() - min.getX() + 1;
                int sy = max.getY() - min.getY() + 1;
                int sz = max.getZ() - min.getZ() + 1;
                boolean tooLarge = sx > MAX_SIDE || sy > MAX_SIDE || sz > MAX_SIDE;

                float r = tooLarge ? RED_R : GOLD_R;
                float g = tooLarge ? RED_G : GOLD_G;
                float b = tooLarge ? RED_B : GOLD_B;
                float a = tooLarge ? RED_A : GOLD_A;

                AABB box = new AABB(min.getX(), min.getY(), min.getZ(),
                    max.getX() + 1, max.getY() + 1, max.getZ() + 1);
                renderBox(poseStack, lines, cam, box, r, g, b, a);
            }
        }

        bufferSource.endBatch(RenderType.lines());
    }

    /**
     * Compute the block position the player is targeting.
     * If looking at a block, returns the clicked block position.
     * If looking at air, returns the air block at reach distance.
     */
    public static BlockPos computeTargetPos(Player player) {
        var mc = Minecraft.getInstance();
        HitResult hit = mc.hitResult;

        if (hit instanceof BlockHitResult blockHit && blockHit.getType() != HitResult.Type.MISS) {
            return blockHit.getBlockPos();
        }

        // Air target: compute position at reach distance
        double reach = player.blockInteractionRange();
        Vec3 eye = player.getEyePosition(1.0f);
        Vec3 look = player.getLookAngle();
        Vec3 target = eye.add(look.scale(reach));
        return BlockPos.containing(target);
    }

    private static ItemStack getHeldWand(Player player) {
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof BlueprintWandItem) return main;
        ItemStack off = player.getOffhandItem();
        if (off.getItem() instanceof BlueprintWandItem) return off;
        return null;
    }

    private static void renderBlockOutline(PoseStack poseStack, VertexConsumer buffer,
                                           Vec3 cam, BlockPos pos,
                                           float r, float g, float b, float a) {
        AABB box = new AABB(pos.getX(), pos.getY(), pos.getZ(),
            pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
        renderBox(poseStack, buffer, cam, box, r, g, b, a);
    }

    private static void renderBox(PoseStack poseStack, VertexConsumer buffer,
                                  Vec3 cam, AABB box,
                                  float r, float g, float b, float a) {
        LevelRenderer.renderLineBox(poseStack, buffer,
            box.minX - cam.x, box.minY - cam.y, box.minZ - cam.z,
            box.maxX - cam.x, box.maxY - cam.y, box.maxZ - cam.z,
            r, g, b, a);
    }
}
