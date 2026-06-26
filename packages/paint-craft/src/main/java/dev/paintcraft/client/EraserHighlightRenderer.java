package dev.paintcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.paintcraft.ModConfig;
import dev.paintcraft.PaintCraft;
import dev.paintcraft.core.Decal;
import dev.paintcraft.core.FaceFrame;
import dev.paintcraft.item.EraserItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * While the eraser is held, outlines decal borders so the player can see what's
 * painted and what a click would erase.
 *
 * <ul>
 *   <li>All nearby decals get a faint white outline (discoverability).</li>
 *   <li>The decal(s) a click would delete get a bright red outline:
 *       the topmost decal on the targeted face, or — with shift held — every
 *       overlapping decal there (mirroring {@link EraserItem}'s erase-all).</li>
 * </ul>
 */
@EventBusSubscriber(modid = PaintCraft.MODID, value = Dist.CLIENT)
public final class EraserHighlightRenderer {

    private static final float FAINT_R = 1f, FAINT_G = 1f, FAINT_B = 1f, FAINT_A = 0.25f;
    private static final float DELETE_R = 1f, DELETE_G = 0.2f, DELETE_B = 0.2f, DELETE_A = 1.0f;

    // Push the outline slightly off the surface (along the face normal) to avoid z-fighting.
    private static final double SURFACE_OFFSET = 1.0 / 64.0;

    private EraserHighlightRenderer() {}

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        var mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (!(mc.player.getMainHandItem().getItem() instanceof EraserItem)
                && !(mc.player.getOffhandItem().getItem() instanceof EraserItem)) return;

        Set<UUID> toDelete = computeDeleteSet(mc.player.isShiftKeyDown());

        Vec3 cam = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        Matrix4f matrix = poseStack.last().pose();
        var bufferSource = mc.renderBuffers().bufferSource();
        VertexConsumer lines = bufferSource.getBuffer(RenderType.lines());

        double maxDistSq = ModConfig.CONFIG.renderDistance.get();
        maxDistSq *= maxDistSq;

        for (DecalRenderer.ResolvedEntry entry : DecalRenderer.allResolved()) {
            if (entry.bounds().distanceToSqr(cam) > maxDistSq) continue;
            boolean delete = toDelete.contains(entry.decal().id());
            drawDecalBorder(lines, matrix, cam, entry.decal(),
                delete ? DELETE_R : FAINT_R,
                delete ? DELETE_G : FAINT_G,
                delete ? DELETE_B : FAINT_B,
                delete ? DELETE_A : FAINT_A);
        }

        bufferSource.endBatch(RenderType.lines());
    }

    /** The decals a click would erase, matching EraserItem's server logic. */
    private static Set<UUID> computeDeleteSet(boolean shift) {
        var mc = Minecraft.getInstance();
        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult blockHit) || blockHit.getType() == HitResult.Type.MISS) {
            return Set.of();
        }

        BlockPos pos = blockHit.getBlockPos();
        Direction face = blockHit.getDirection();
        List<ClientSpatialIndex.DecalRef> refs = ClientSpatialIndex.getRefsAt(pos, face);
        if (refs.isEmpty()) return Set.of();

        if (shift) {
            Set<UUID> all = new HashSet<>();
            for (ClientSpatialIndex.DecalRef ref : refs) all.add(ref.decalId());
            return all;
        }
        // refs are sorted ascending by zOverride; topmost is the highest = last element.
        return Set.of(refs.get(refs.size() - 1).decalId());
    }

    private static void drawDecalBorder(VertexConsumer buf, Matrix4f matrix, Vec3 cam,
                                        Decal decal, float r, float g, float b, float a) {
        FaceFrame frame = decal.frame();
        Vec3 origin = frame.projectionOrigin(decal.anchor());
        Vec3 right = frame.rightVec();
        Vec3 up = frame.upVec();

        double w = decal.widthPx() / (double) Decal.PX_PER_BLOCK;
        double h = decal.heightPx() / (double) Decal.PX_PER_BLOCK;

        Direction n = decal.normal();
        double ox = origin.x + n.getStepX() * SURFACE_OFFSET - cam.x;
        double oy = origin.y + n.getStepY() * SURFACE_OFFSET - cam.y;
        double oz = origin.z + n.getStepZ() * SURFACE_OFFSET - cam.z;

        // Four corners of the front-face rectangle (camera-relative).
        Vec3 c0 = new Vec3(ox, oy, oz);
        Vec3 c1 = c0.add(right.scale(w));
        Vec3 c2 = c1.add(up.scale(h));
        Vec3 c3 = c0.add(up.scale(h));

        drawLine(buf, matrix, c0, c1, r, g, b, a);
        drawLine(buf, matrix, c1, c2, r, g, b, a);
        drawLine(buf, matrix, c2, c3, r, g, b, a);
        drawLine(buf, matrix, c3, c0, r, g, b, a);
    }

    private static void drawLine(VertexConsumer buf, Matrix4f matrix, Vec3 from, Vec3 to,
                                 float r, float g, float b, float a) {
        Vec3 dir = to.subtract(from).normalize();
        float nx = (float) dir.x, ny = (float) dir.y, nz = (float) dir.z;
        buf.addVertex(matrix, (float) from.x, (float) from.y, (float) from.z)
            .setColor(r, g, b, a).setNormal(nx, ny, nz);
        buf.addVertex(matrix, (float) to.x, (float) to.y, (float) to.z)
            .setColor(r, g, b, a).setNormal(nx, ny, nz);
    }
}
