package dev.paintcraft.client.compat.create;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.render.ClientContraption;
import com.simibubi.create.content.contraptions.render.ContraptionMatrices;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import dev.paintcraft.client.DecalRenderType;
import dev.paintcraft.client.DecalTexture;
import dev.paintcraft.compat.create.PaintCraftContraption;
import dev.paintcraft.core.Decal;
import dev.paintcraft.projection.ProjectionResolver;
import dev.paintcraft.projection.ResolvedSurface;
import dev.paintcraft.projection.SurfaceFragment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Renders PaintCraft decals that are riding a Create contraption.
 *
 * <p>Decals are resolved once (lazily) against the contraption's {@link VirtualRenderWorld}
 * in contraption-local coordinates, then drawn every frame through the contraption's
 * {@link ContraptionMatrices#getModel() model matrix} so they track translation and rotation
 * exactly like the contraption's own blocks. {@link SurfaceFragment#uvs()} already carry the
 * decal-texture coordinates, so each decal renders with its own texture (no atlas compositing).
 */
public final class ContraptionDecalRenderer {

    private static final Map<Contraption, Baked> CACHE = new WeakHashMap<>();
    private static final List<DecalTexture> TEXTURES = new ArrayList<>();

    private ContraptionDecalRenderer() {}

    public static void render(AbstractContraptionEntity entity, PoseStack poseStack, MultiBufferSource buffers) {
        Contraption c = entity.getContraption();
        if (!(c instanceof PaintCraftContraption pcc)) return;
        List<CompoundTag> tags = pcc.paintcraft$decals();
        if (tags.isEmpty()) return;

        ClientContraption client = c.getOrCreateClientContraptionLazy();
        VirtualRenderWorld renderWorld = client.getRenderLevel();
        ContraptionMatrices matrices = client.getMatrices();
        if (renderWorld == null || matrices == null) return;

        Baked baked = CACHE.get(c);
        if (baked == null) {
            baked = bake(tags, renderWorld);
            CACHE.put(c, baked);
        }
        if (baked.entries.isEmpty()) return;

        poseStack.pushPose();
        PoseStack.Pose model = matrices.getModel().last();
        poseStack.last().pose().mul(model.pose());
        poseStack.last().normal().mul(model.normal());

        // Light is sampled from the real level (the VirtualRenderWorld carries no light data),
        // mapping each fragment's local position to world space via the contraption light matrix.
        Matrix4f worldMatrix = matrices.getWorld();
        Level realLevel = Minecraft.getInstance().level;

        for (Baked.Entry entry : baked.entries) {
            VertexConsumer vc = buffers.getBuffer(DecalRenderType.decal(entry.texture.location()));
            for (SurfaceFragment frag : entry.fragments) {
                emitFragment(poseStack, vc, frag, worldMatrix, realLevel);
            }
        }

        poseStack.popPose();
    }

    private static Baked bake(List<CompoundTag> tags, VirtualRenderWorld renderWorld) {
        List<Decal> decals = new ArrayList<>(tags.size());
        for (CompoundTag tag : tags) decals.add(Decal.load(tag));
        // Lower zOrder first so higher-priority decals are drawn on top.
        decals.sort(Comparator.comparingLong(Decal::zOrder));

        List<Baked.Entry> entries = new ArrayList<>();
        for (Decal d : decals) {
            ResolvedSurface surface = ProjectionResolver.resolve(d, renderWorld).surface();
            if (surface.isEmpty()) continue;
            DecalTexture texture = new DecalTexture(d.widthPx(), d.heightPx(), d.pixels());
            TEXTURES.add(texture);
            entries.add(new Baked.Entry(texture, surface.fragments()));
        }
        return new Baked(entries);
    }

    private static void emitFragment(PoseStack poseStack, VertexConsumer vc, SurfaceFragment frag,
                                     Matrix4f worldMatrix, Level realLevel) {
        float[] v = frag.vertices();
        float[] uv = frag.uvs();
        Direction face = frag.faceNormal();

        int light = sampleWorldLight(worldMatrix, realLevel, frag.pos().relative(face));
        float shade = realLevel.getShade(face, true);
        int col = (int) (shade * 255);

        PoseStack.Pose pose = poseStack.last();
        float nx = face.getStepX(), ny = face.getStepY(), nz = face.getStepZ();

        for (int i = 0; i < 4; i++) {
            vc.addVertex(pose, v[i * 3], v[i * 3 + 1], v[i * 3 + 2])
                .setColor(col, col, col, 255)
                .setUv(uv[i * 2], uv[i * 2 + 1])
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
        }
    }

    /** Map a contraption-local block position to world space and sample the real level's light there. */
    private static int sampleWorldLight(Matrix4f worldMatrix, Level realLevel, BlockPos localPos) {
        Vector3f p = new Vector3f(localPos.getX() + 0.5f, localPos.getY() + 0.5f, localPos.getZ() + 0.5f);
        worldMatrix.transformPosition(p);
        return LevelRenderer.getLightColor(realLevel, BlockPos.containing(p.x, p.y, p.z));
    }

    /** Release cached GPU textures (called on client disconnect). */
    public static void clear() {
        CACHE.clear();
        for (DecalTexture texture : TEXTURES) texture.close();
        TEXTURES.clear();
    }

    private record Baked(List<Entry> entries) {
        private record Entry(DecalTexture texture, List<SurfaceFragment> fragments) {}
    }
}
