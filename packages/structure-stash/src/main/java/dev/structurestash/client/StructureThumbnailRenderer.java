package dev.structurestash.client;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.structurestash.StructureStash;
import dev.structurestash.compat.CnBInterop;
import mod.chiselsandbits.api.block.storage.StateEntryStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * GPU-accelerated multi-block structure thumbnail renderer.
 * <p>
 * Two-phase pipeline for non-blocking rendering:
 * <ol>
 *   <li>{@link #prepareGrid} — parse structure NBT + decode C&amp;B voxel storages.
 *       <b>Thread-safe</b>: can run on a background thread.</li>
 *   <li>{@link #renderGridToImage} — FBO render using pre-decoded data.
 *       <b>Render-thread only</b>.</li>
 * </ol>
 * The convenience method {@link #renderToImage} calls both phases sequentially.
 */
@OnlyIn(Dist.CLIENT)
public final class StructureThumbnailRenderer {

    private StructureThumbnailRenderer() {}

    // ── Data ─────────────────────────────────────────────────────────

    /**
     * Pre-parsed structure grid with block states, raw NBT, pre-decoded
     * C&amp;B voxel storages, and camo render overrides for framed blocks.
     * Produced by {@link #prepareGrid}, consumed by {@link #renderGridToImage}.
     */
    public record StructureGrid(int sx, int sy, int sz,
                                 BlockState[][][] states,
                                 CompoundTag[][][] nbt,
                                 StateEntryStorage[][][] storages,
                                 BlockState[][][] camoOverrides) {}

    // ── Phase 1: Background-safe preparation ─────────────────────────

    /**
     * Parse structure NBT into a {@link StructureGrid} with pre-decoded C&amp;B
     * voxel storages. <b>Thread-safe</b>: uses only stateless codecs and
     * immutable registries — safe to call from a background thread.
     *
     * @param root       the StructureTemplate's root CompoundTag
     * @param registries registry access for codec context
     * @return parsed grid with storages pre-decoded, or null if invalid
     */
    @Nullable
    public static StructureGrid prepareGrid(CompoundTag root, HolderLookup.Provider registries) {
        ListTag sizeTag = root.getList("size", Tag.TAG_INT);
        int sx = sizeTag.getInt(0), sy = sizeTag.getInt(1), sz = sizeTag.getInt(2);
        if (sx <= 0 || sy <= 0 || sz <= 0) return null;

        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        ListTag paletteList = root.getList("palette", Tag.TAG_COMPOUND);
        List<BlockState> palette = new ArrayList<>();
        for (int i = 0; i < paletteList.size(); i++) {
            palette.add(BlockState.CODEC.parse(ops, paletteList.getCompound(i))
                .result().orElse(Blocks.AIR.defaultBlockState()));
        }

        BlockState[][][] states = new BlockState[sx][sy][sz];
        CompoundTag[][][] nbt = new CompoundTag[sx][sy][sz];
        StateEntryStorage[][][] storages = new StateEntryStorage[sx][sy][sz];
        BlockState[][][] camoOverrides = new BlockState[sx][sy][sz];

        ListTag blocksList = root.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocksList.size(); i++) {
            CompoundTag entry = blocksList.getCompound(i);
            ListTag posTag = entry.getList("pos", Tag.TAG_INT);
            int bx = posTag.getInt(0), by = posTag.getInt(1), bz = posTag.getInt(2);
            int stateIdx = entry.getInt("state");
            if (bx < 0 || bx >= sx || by < 0 || by >= sy || bz < 0 || bz >= sz) continue;
            if (stateIdx < 0 || stateIdx >= palette.size()) continue;

            BlockState state = palette.get(stateIdx);
            states[bx][by][bz] = state;
            if (CnBInterop.isChiseledBlock(state.getBlock()) && entry.contains("nbt")) {
                CompoundTag beNbt = entry.getCompound("nbt");
                nbt[bx][by][bz] = beNbt;
                // Pre-decode C&B voxel storage (the expensive CPU part)
                storages[bx][by][bz] = CnBInterop.decodeStorage(beNbt, registries);
            } else if (entry.contains("nbt")) {
                // Check for framed block camo — resolve render override on background thread
                CompoundTag beNbt = entry.getCompound("nbt");
                if (beNbt.contains("camo")) {
                    CompoundTag camoTag = beNbt.getCompound("camo");
                    if (camoTag.contains("state")) {
                        BlockState camoState = BlockState.CODEC.parse(ops, camoTag.getCompound("state"))
                            .result().orElse(null);
                        if (camoState != null && !camoState.isAir()) {
                            camoOverrides[bx][by][bz] = camoState;
                        }
                    }
                }
            }
        }

        return new StructureGrid(sx, sy, sz, states, nbt, storages, camoOverrides);
    }

    // ── Phase 2: Render-thread FBO render ────────────────────────────

    /**
     * Render a pre-parsed {@link StructureGrid} into a NativeImage via off-screen FBO.
     * Uses pre-decoded C&amp;B storages — no LZ4/codec work on the render thread.
     * <p><b>Must be called on the render thread.</b>
     *
     * @param grid   the pre-parsed structure grid (from {@link #prepareGrid})
     * @param target target NativeImage (pre-allocated at size×size)
     * @param size   thumbnail size in pixels
     */
    public static void renderGridToImage(StructureGrid grid, NativeImage target, int size) {
        RenderSystem.assertOnRenderThread();

        int sx = grid.sx, sy = grid.sy, sz = grid.sz;

        // Save GL state
        float savedFogStart = RenderSystem.getShaderFogStart();
        float savedFogEnd = RenderSystem.getShaderFogEnd();
        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());

        TextureTarget fbo = null;
        NativeImage readback = null;
        try {
            // Create off-screen FBO
            fbo = new TextureTarget(size, size, true, Minecraft.ON_OSX);
            fbo.setClearColor(0f, 0f, 0f, 0f);
            fbo.clear(Minecraft.ON_OSX);
            fbo.bindWrite(true);

            // Set up orthographic projection
            float maxExtent = (float) Math.sqrt(sx * sx + sz * sz) + sy * 0.6f;
            float halfView = maxExtent * 0.55f;
            if (halfView < 1f) halfView = 1f;

            Matrix4f proj = new Matrix4f().ortho(
                -halfView, halfView,
                -halfView, halfView,
                -1000f, 3000f
            );
            RenderSystem.setProjectionMatrix(proj, VertexSorting.ORTHOGRAPHIC_Z);

            // Model-view: isometric camera (matches block.json gui: [30, 225, 0])
            Matrix4fStack mv = RenderSystem.getModelViewStack();
            mv.pushMatrix();
            mv.identity();
            mv.translate(0f, 0f, -2000f);
            mv.rotateX((float) Math.toRadians(30));
            mv.rotateY((float) Math.toRadians(225));
            mv.translate(-sx / 2f, -sy / 2f, -sz / 2f);
            RenderSystem.applyModelViewMatrix();

            // Lighting and fog
            Lighting.setupFor3DItems();
            RenderSystem.setShaderFogStart(Float.MAX_VALUE);
            RenderSystem.setShaderFogEnd(Float.MAX_VALUE);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);

            // Render all blocks (using pre-decoded storages)
            renderBlocks(grid);

            // Unbind FBO
            fbo.unbindWrite();

            // Read back pixels
            readback = new NativeImage(size, size, false);
            RenderSystem.bindTexture(fbo.getColorTextureId());
            readback.downloadTexture(0, false);
            readback.flipY();

            // Copy into target with brightness correction (gamma lift)
            float gamma = 0.65f;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int pixel = readback.getPixelRGBA(x, y);
                    int a = (pixel >> 24) & 0xFF;
                    if (a == 0) { target.setPixelRGBA(x, y, 0); continue; }
                    int b = (int) (Math.pow(((pixel >> 16) & 0xFF) / 255.0, gamma) * 255.0);
                    int g = (int) (Math.pow(((pixel >> 8) & 0xFF) / 255.0, gamma) * 255.0);
                    int r = (int) (Math.pow((pixel & 0xFF) / 255.0, gamma) * 255.0);
                    target.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }
        } catch (Exception e) {
            StructureStash.LOGGER.error("Failed to render structure thumbnail via GPU", e);
        } finally {
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setShaderFogStart(savedFogStart);
            RenderSystem.setShaderFogEnd(savedFogEnd);
            RenderSystem.setProjectionMatrix(savedProj, VertexSorting.ORTHOGRAPHIC_Z);
            if (readback != null) readback.close();
            if (fbo != null) fbo.destroyBuffers();
            Minecraft.getInstance().getMainRenderTarget().bindWrite(true);
        }
    }

    // ── Convenience wrapper ──────────────────────────────────────────

    /**
     * Render a StructureTemplate NBT into the target NativeImage at the given size.
     * Combines {@link #prepareGrid} and {@link #renderGridToImage} in one call.
     * Must be called on the render thread.
     */
    public static void renderToImage(CompoundTag root, NativeImage target, int size,
                                      HolderLookup.Provider registries) {
        RenderSystem.assertOnRenderThread();
        StructureGrid grid = prepareGrid(root, registries);
        if (grid != null) renderGridToImage(grid, target, size);
    }

    // ── Block rendering ──────────────────────────────────────────────

    private static void renderBlocks(StructureGrid grid) {
        Minecraft mc = Minecraft.getInstance();
        BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        PoseStack poseStack = new PoseStack();

        for (int bx = 0; bx < grid.sx; bx++) {
            for (int by = 0; by < grid.sy; by++) {
                for (int bz = 0; bz < grid.sz; bz++) {
                    BlockState state = grid.states[bx][by][bz];
                    if (state == null || state.isAir()) continue;

                    poseStack.pushPose();
                    poseStack.translate(bx, by, bz);

                    StateEntryStorage storage = grid.storages[bx][by][bz];
                    if (storage != null) {
                        // C&B block with pre-decoded storage
                        renderChiseledBlock(poseStack, bufferSource, blockRenderer, state, storage);
                    } else {
                        // Use camo override for framed blocks (shows actual material texture)
                        BlockState renderState = grid.camoOverrides[bx][by][bz] != null
                            ? grid.camoOverrides[bx][by][bz] : state;
                        blockRenderer.renderSingleBlock(renderState, poseStack, bufferSource,
                            LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                    }

                    poseStack.popPose();
                }
            }
        }

        bufferSource.endBatch();
    }

    private static void renderChiseledBlock(PoseStack poseStack,
                                             MultiBufferSource.BufferSource bufferSource,
                                             BlockRenderDispatcher blockRenderer,
                                             BlockState state,
                                             StateEntryStorage storage) {
        CnBInterop.forEachEntityLayer(storage, (model, rt) -> {
            var consumer = bufferSource.getBuffer(rt);
            blockRenderer.getModelRenderer().renderModel(
                poseStack.last(), consumer, state, model,
                1f, 1f, 1f,
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        });
    }
}
