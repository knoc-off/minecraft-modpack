package dev.paintcraft.client.compat.create;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.render.ClientContraption;
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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders PaintCraft decals that are riding a Create contraption.
 *
 * <p>Decals are resolved once (lazily) against the contraption's {@link VirtualRenderWorld}
 * in contraption-local coordinates, then drawn every frame in world space so they track the
 * contraption's translation and rotation. {@link SurfaceFragment#uvs()} already carry the
 * decal-texture coordinates, so each decal renders with its own texture (no atlas compositing).
 *
 * <p>Rendering happens from a {@code RenderLevelStageEvent} (AFTER_BLOCK_ENTITIES / after Flywheel
 * has drawn the contraption's instanced blocks), <em>not</em> from a contraption entity-render
 * mixin. Drawing during the entity stage put the decal on screen <em>before</em> Flywheel drew the
 * blocks, so the opaque blocks painted over it — no depth bias could fix a color overdraw by
 * later-drawn geometry. Drawing after the blocks (exactly like PaintCraft's static world decals)
 * composites the decal on top. The camera-relative transform is reconstructed from the entity's
 * interpolated position and {@code applyLocalTransforms}, using the same frame-frozen partial tick
 * Flywheel uses.
 */
public final class ContraptionDecalRenderer {

    private static final Map<Contraption, Baked> CACHE = new WeakHashMap<>();
    private static final List<DecalTexture> TEXTURES = new ArrayList<>();

    /** Keys of one-shot diagnostic messages already emitted, so logging never spams per-frame. */
    private static final Set<String> DIAG_SEEN = ConcurrentHashMap.newKeySet();

    /** Log {@code msg} at most once per distinct {@code key} (per game session). */
    private static void diag(String key, String msg, Object... args) {
        if (DIAG_SEEN.add(key)) {
            dev.paintcraft.PaintCraft.LOGGER.info("[contraption-decal] " + msg, args);
        }
    }

    /** Clear one-shot diagnostic state (e.g. on disconnect) so a fresh session logs again. */
    public static void resetDiag() {
        DIAG_SEEN.clear();
    }

    private ContraptionDecalRenderer() {}

    /**
     * Draw the decals of every loaded contraption. Called from the level-stage render event after
     * the contraption blocks are on screen. No frustum culling — contraption counts are small.
     */
    public static void renderAll(PoseStack poseStack, MultiBufferSource buffers, Vec3 cameraPos, float partialTicks) {
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        for (Entity e : level.entitiesForRendering()) {
            if (e instanceof AbstractContraptionEntity ce && ce.isAliveOrStale() && ce.isReadyForRender()) {
                render(ce, poseStack, buffers, cameraPos, partialTicks);
            }
        }
    }

    private static void render(AbstractContraptionEntity entity, PoseStack poseStack,
                               MultiBufferSource buffers, Vec3 cameraPos, float partialTicks) {
        int cid = System.identityHashCode(entity);
        Contraption c = entity.getContraption();
        if (!(c instanceof PaintCraftContraption pcc)) {
            diag("not-pcc-" + cid, "entity {} contraption is not a PaintCraftContraption ({}) — no decal data attached",
                cid, c == null ? "null" : c.getClass().getName());
            return;
        }
        List<CompoundTag> tags = pcc.paintcraft$decals();
        if (tags.isEmpty()) {
            diag("no-tags-" + cid, "contraption {} has no decal tags stored", cid);
            return;
        }

        ClientContraption client = c.getOrCreateClientContraptionLazy();
        VirtualRenderWorld renderWorld = client.getRenderLevel();
        if (renderWorld == null) {
            diag("null-world-" + cid, "contraption {} renderWorld is null", cid);
            return;
        }

        Baked baked = CACHE.get(c);
        if (baked == null) {
            baked = bake(tags, renderWorld);
            CACHE.put(c, baked);
            diag("baked-" + cid, "contraption {} baked {} decal(s) -> {} entries (of {} tags)",
                cid, tags.size(), baked.entries.size(), tags.size());
        }
        if (baked.entries.isEmpty()) {
            diag("empty-baked-" + cid, "contraption {} baked ZERO surface entries from {} tags — "
                + "ProjectionResolver resolved no fragments", cid, tags.size());
            return;
        }

        // Interpolated entity world position this frame.
        double ex = Mth.lerp(partialTicks, entity.xOld, entity.getX());
        double ey = Mth.lerp(partialTicks, entity.yOld, entity.getY());
        double ez = Mth.lerp(partialTicks, entity.zOld, entity.getZ());

        poseStack.pushPose();
        // Rebuild the camera-relative model transform the entity render pipeline would have had:
        // camera-origin pose -> translate to the entity's world position (minus camera) -> local
        // contraption rotation/animation. partialTicks is the frame-frozen tick that matches Flywheel.
        poseStack.translate(ex - cameraPos.x, ey - cameraPos.y, ez - cameraPos.z);
        entity.applyLocalTransforms(poseStack, partialTicks);

        Matrix4f finalPose = poseStack.last().pose();
        int totalFrags = 0;
        for (Baked.Entry e : baked.entries) totalFrags += e.fragments.size();
        diag("render-" + cid, "contraption {} rendering {} entries / {} frags; pose translation=({}, {}, {})",
            cid, baked.entries.size(), totalFrags,
            finalPose.m30(), finalPose.m31(), finalPose.m32());

        // Light is sampled from the real level (the VirtualRenderWorld carries no light data),
        // mapping each fragment's local position to world space. The contraption "world" matrix is a
        // pure translation to the entity position (Create's ContraptionMatrices does the same); we
        // rebuild it here rather than reading client.getMatrices(), which is cleared after the entity
        // stage and would be identity by the time this stage-event fires.
        Matrix4f worldMatrix = new Matrix4f().translation((float) ex, (float) ey, (float) ez);
        Level realLevel = Minecraft.getInstance().level;

        for (Baked.Entry entry : baked.entries) {
            VertexConsumer vc = buffers.getBuffer(DecalRenderType.decalNoDepth(entry.texture.location()));
            for (SurfaceFragment frag : entry.fragments) {
                emitFragment(poseStack, vc, frag, worldMatrix, realLevel);
            }
            diag("emit-" + cid + "-" + entry.texture.location(),
                "contraption {} emitted {} frags for texture {}", cid, entry.fragments.size(), entry.texture.location());
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
