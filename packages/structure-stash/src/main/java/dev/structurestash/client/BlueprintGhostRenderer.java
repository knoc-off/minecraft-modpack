package dev.structurestash.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.structurestash.StructureStash;
import dev.structurestash.client.StructureThumbnailRenderer.StructureGrid;
import dev.structurestash.compat.CnBCompat;
import dev.structurestash.compat.CnBInterop;
import dev.structurestash.item.BlueprintItem;
import dev.structurestash.item.ModDataComponents;
import mod.chiselsandbits.api.block.storage.StateEntryStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * Renders a textured ghost preview when holding a Blueprint item.
 * Shows actual block textures at reduced alpha so the player can see
 * exactly what will be placed and in what orientation.
 *
 * <p>Supports a two-step confirm flow: right-click locks the ghost at a position,
 * right-click again with the same blueprint confirms placement. The ghost stays
 * visible even when the player switches to other items.</p>
 */
@EventBusSubscriber(modid = StructureStash.MODID, value = Dist.CLIENT)
public class BlueprintGhostRenderer {

    private static final float GHOST_ALPHA = 0.5f;

    // Cache: avoid re-parsing NBT and rebuilding models every frame
    private static byte[] cachedData;
    private static StructureGrid cachedGrid;
    @SuppressWarnings("unchecked")
    private static List<BakedModel>[][][] cachedChiselModels;

    // Confirm-mode state
    private static boolean confirming;
    private static BlockPos lockedAnchor;
    private static Rotation lockedRotation;
    private static byte[] lockedData;
    private static boolean blocked;

    // ── Public confirm API ───────────────────────────────────────────

    public static void lockConfirm(BlockPos anchor, Rotation rotation, byte[] data) {
        confirming = true;
        lockedAnchor = anchor;
        lockedRotation = rotation;
        lockedData = data;
    }

    public static void cancelConfirm() {
        confirming = false;
        lockedAnchor = null;
        lockedRotation = null;
        lockedData = null;
    }

    public static boolean isConfirming() { return confirming; }
    public static boolean isBlocked() { return blocked; }
    public static byte[] getLockedData() { return lockedData; }
    public static BlockPos getLockedAnchor() { return lockedAnchor; }
    public static Rotation getLockedRotation() { return lockedRotation; }

    // ── Rendering ────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        var mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Determine what data + position to render
        byte[] data;
        BlockPos anchor;
        Rotation rotation;

        if (confirming) {
            // Confirm mode: render locked ghost regardless of held item
            data = lockedData;
            anchor = lockedAnchor;
            rotation = lockedRotation;
        } else {
            // Normal mode: ghost follows crosshair, only when holding blueprint
            ItemStack held = mc.player.getMainHandItem();
            if (!(held.getItem() instanceof BlueprintItem)) {
                held = mc.player.getOffhandItem();
                if (!(held.getItem() instanceof BlueprintItem)) {
                    cachedData = null;
                    cachedGrid = null;
                    cachedChiselModels = null;
                    return;
                }
            }

            var bd = held.get(ModDataComponents.BLUEPRINT_DATA.get());
            data = bd != null ? bd.data() : null;
            if (data == null || data.length == 0) return;

            HitResult hit = mc.hitResult;
            if (!(hit instanceof BlockHitResult blockHit) || blockHit.getType() == HitResult.Type.MISS) return;

            anchor = blockHit.getBlockPos().relative(blockHit.getDirection());
            rotation = BlueprintItem.facingToRotation(mc.player.getDirection());
        }

        if (data == null || anchor == null || rotation == null) return;

        // Parse structure (cached)
        StructureGrid grid = getGrid(data);
        if (grid == null) return;

        Vec3 cam = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();

        BlockRenderDispatcher blockRenderer = mc.getBlockRenderer();
        var bufferSource = mc.renderBuffers().bufferSource();
        VertexConsumer rawBuffer = bufferSource.getBuffer(RenderType.translucent());
        GhostVertexConsumer ghostBuffer = new GhostVertexConsumer(rawBuffer, GHOST_ALPHA);

        boolean anyBlocked = false;

        for (int bx = 0; bx < grid.sx(); bx++) {
            for (int by = 0; by < grid.sy(); by++) {
                for (int bz = 0; bz < grid.sz(); bz++) {
                    BlockState state = grid.states()[bx][by][bz];
                    if (state == null || state.isAir()) continue;

                    // Use camo override for framed blocks (shows actual material)
                    BlockState visualState = grid.camoOverrides()[bx][by][bz] != null
                        ? grid.camoOverrides()[bx][by][bz] : state;

                    // Rotate block position and block state to match player facing
                    BlockPos relPos = new BlockPos(bx, by, bz);
                    BlockPos rotated = StructureTemplate.transform(relPos, Mirror.NONE, rotation, BlockPos.ZERO);
                    BlockState rotatedState = visualState.rotate(rotation);

                    BlockPos worldPos = anchor.offset(rotated);

                    // Collision check (piggybacks on existing iteration)
                    if (confirming && mc.level != null) {
                        BlockState existing = mc.level.getBlockState(worldPos);
                        if (!existing.isAir() && !existing.canBeReplaced()) {
                            anyBlocked = true;
                        }
                    }
                    double rx = worldPos.getX() - cam.x;
                    double ry = worldPos.getY() - cam.y;
                    double rz = worldPos.getZ() - cam.z;

                    poseStack.pushPose();
                    poseStack.translate(rx, ry, rz);
                    // Slight scale to avoid z-fighting with existing world blocks
                    poseStack.translate(0.5, 0.5, 0.5);
                    poseStack.scale(1.001f, 1.001f, 1.001f);
                    poseStack.translate(-0.5, -0.5, -0.5);

                    if (CnBInterop.isChiseledBlock(state.getBlock()) && cachedChiselModels != null
                            && cachedChiselModels[bx][by][bz] != null) {
                        // C&B voxel data isn't rotated by BlockState.rotate() — apply
                        // rotation via PoseStack so the ghost preview matches placement.
                        if (rotation != Rotation.NONE) {
                            poseStack.translate(0.5, 0.5, 0.5);
                            poseStack.mulPose(Axis.YP.rotationDegrees(rotationToDegrees(rotation)));
                            poseStack.translate(-0.5, -0.5, -0.5);
                        }
                        renderChiseledGhost(poseStack, ghostBuffer, blockRenderer,
                            rotatedState, cachedChiselModels[bx][by][bz]);
                    } else {
                        renderVanillaGhost(poseStack, ghostBuffer, blockRenderer, rotatedState);
                    }

                    poseStack.popPose();
                }
            }
        }

        bufferSource.endBatch(RenderType.translucent());

        if (confirming) {
            blocked = anyBlocked;
        }
    }

    private static void renderVanillaGhost(PoseStack poseStack, VertexConsumer ghostBuffer,
                                           BlockRenderDispatcher blockRenderer, BlockState state) {
        BakedModel model = blockRenderer.getBlockModel(state);
        int color = Minecraft.getInstance().getBlockColors().getColor(state, null, null, 0);
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;

        blockRenderer.getModelRenderer().renderModel(
            poseStack.last(), ghostBuffer, state, model,
            r, g, b, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
    }

    private static void renderChiseledGhost(PoseStack poseStack, VertexConsumer ghostBuffer,
                                            BlockRenderDispatcher blockRenderer,
                                            BlockState state, List<BakedModel> models) {
        for (BakedModel model : models) {
            blockRenderer.getModelRenderer().renderModel(
                poseStack.last(), ghostBuffer, state, model,
                1f, 1f, 1f, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────

    private static float rotationToDegrees(Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> -90f;
            case CLOCKWISE_180 -> -180f;
            case COUNTERCLOCKWISE_90 -> 90f;
            default -> 0f;
        };
    }

    // ── Caching ──────────────────────────────────────────────────────

    private static StructureGrid getGrid(byte[] data) {
        if (data == cachedData && cachedGrid != null) return cachedGrid;

        cachedData = data;
        cachedGrid = null;
        cachedChiselModels = null;

        try {
            CompoundTag root = NbtIo.readCompressed(
                new ByteArrayInputStream(data), NbtAccounter.unlimitedHeap());
            HolderLookup.Provider registries = getRegistries();
            if (registries == null) return null;

            StructureGrid grid = StructureThumbnailRenderer.prepareGrid(root, registries);
            if (grid == null) return null;

            cachedGrid = grid;

            // Pre-build all C&B baked models so rendering is just a cheap renderModel() call.
            // Only attempt this when C&B is present — without it, grid.storages() is null
            // and there are no chiseled cells to bake.
            if (CnBCompat.isLoaded() && grid.storages() != null) {
                @SuppressWarnings("unchecked")
                List<BakedModel>[][][] models = new List[grid.sx()][grid.sy()][grid.sz()];
                for (int x = 0; x < grid.sx(); x++) {
                    for (int y = 0; y < grid.sy(); y++) {
                        for (int z = 0; z < grid.sz(); z++) {
                            StateEntryStorage storage = grid.storages()[x][y][z];
                            if (storage != null) {
                                List<BakedModel> blockModels = CnBInterop.buildBakedModels(storage);
                                if (!blockModels.isEmpty()) models[x][y][z] = blockModels;
                            }
                        }
                    }
                }
                cachedChiselModels = models;
            }

            return cachedGrid;
        } catch (Exception e) {
            StructureStash.LOGGER.debug("Failed to parse blueprint for ghost render", e);
            return null;
        }
    }

    private static HolderLookup.Provider getRegistries() {
        var mc = Minecraft.getInstance();
        if (mc.level != null) return mc.level.registryAccess();
        var server = mc.getSingleplayerServer();
        if (server != null) return server.registryAccess();
        return null;
    }
}
