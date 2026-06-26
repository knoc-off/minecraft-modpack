package dev.structurestash.compat;

import com.mojang.serialization.Codec;
import mod.chiselsandbits.api.block.storage.StateEntryStorage;
import mod.chiselsandbits.api.blockinformation.BlockInformation;
import mod.chiselsandbits.api.serialization.CBCodecs;
import mod.chiselsandbits.api.util.constants.NbtConstants;
import mod.chiselsandbits.client.model.baked.chiseled.ChiselRenderType;
import mod.chiselsandbits.client.model.baked.chiseled.ChiseledBlockBakedModel;
import mod.chiselsandbits.client.model.baked.chiseled.ChiseledBlockBakedModelManager;
import mod.chiselsandbits.item.multistate.SingleBlockMultiStateItemStack;
import mod.chiselsandbits.multistate.snapshot.SimpleSnapshot;
import mod.chiselsandbits.registrars.ModBlocks;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Single point of contact for all Chisels &amp; Bits internal (non-API) access.
 * <p>
 * If C&amp;B refactors its internals, only this file needs updating.
 * All other structure-stash code should use only C&amp;B API types
 * ({@code mod.chiselsandbits.api.*}) plus the methods in this class.
 *
 * <h3>Internal imports confined to this file:</h3>
 * <ul>
 *   <li>{@code mod.chiselsandbits.client.model.baked.chiseled.*} — baked model generation</li>
 *   <li>{@code mod.chiselsandbits.multistate.snapshot.SimpleSnapshot} — snapshot wrapper</li>
 *   <li>{@code mod.chiselsandbits.item.multistate.SingleBlockMultiStateItemStack} — ItemStack creation</li>
 * </ul>
 */
public final class CnBInterop {

    private CnBInterop() {}

    // ── Block Identity ────────────────────────────────────────────────
    // Internal: ModBlocks

    /** Check if a block is C&B's chiseled block. Always false when C&B is absent. */
    public static boolean isChiseledBlock(Block block) {
        if (!CnBCompat.isLoaded()) return false;
        return block == ModBlocks.CHISELED_BLOCK.get();
    }

    /** Get the chiseled block item (for ItemStack creation). */
    public static Item getChiseledBlockItem() {
        return mod.chiselsandbits.registrars.ModItems.CHISELED_BLOCK.get();
    }

    // ── Storage Decode ────────────────────────────────────────────────
    // Uses only API types: CBCodecs.compressed(), StateEntryStorage.CODEC, NbtConstants.

    /**
     * Codec that decodes a {@link StateEntryStorage} from a C&B block entity's
     * {@code data} compound tag. Uses the API-level compressed codec and
     * storage codec — no dependency on internal Payload/StorageEngine classes.
     * <p>
     * Lazily initialised: building it touches C&B API classes, so it must not run
     * during class-load (which can happen on a server without C&B). It is only
     * ever reached through {@link #decodeStorage}, which is C&B-present-only.
     */
    private static Codec<StateEntryStorage> storageCodec;

    private static Codec<StateEntryStorage> storageCodec() {
        Codec<StateEntryStorage> c = storageCodec;
        if (c == null) {
            c = CBCodecs.compressed(
                StateEntryStorage.CODEC.fieldOf(NbtConstants.STORAGE).codec()
            ).fieldOf(NbtConstants.PAYLOAD).codec();
            storageCodec = c;
        }
        return c;
    }

    /**
     * Decode voxel storage from a C&B block entity's raw NBT.
     * Works with both structure template NBT and live block entity NBT.
     *
     * @param beNbt      the block entity's CompoundTag (must contain a "data" child)
     * @param registries registry access for codec context
     * @return the decoded storage, or null if decoding fails
     */
    public static @Nullable StateEntryStorage decodeStorage(CompoundTag beNbt,
                                                             HolderLookup.Provider registries) {
        if (!beNbt.contains(NbtConstants.DATA)) return null;
        CompoundTag dataTag = beNbt.getCompound(NbtConstants.DATA);
        try {
            var ops = registries.createSerializationContext(NbtOps.INSTANCE);
            return storageCodec().parse(ops, dataTag).result().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Rendering (client-only) ───────────────────────────────────────
    // Internal: SimpleSnapshot, ChiselRenderType, ChiseledBlockBakedModel,
    //           ChiseledBlockBakedModelManager

    /**
     * Build baked models for a C&B voxel storage, suitable for rendering
     * with {@code ModelRenderer.renderModel()}. Uses the block render layer.
     * <p><b>Client-only</b> — must not be called on the dedicated server.
     *
     * @param storage the voxel storage to build models for
     * @return list of baked models (one per active render layer), or empty if none
     */
    public static List<BakedModel> buildBakedModels(StateEntryStorage storage) {
        SimpleSnapshot snapshot = new SimpleSnapshot(storage);
        BlockInformation primaryState = snapshot.getStatics().getPrimaryState();
        List<BakedModel> models = new ArrayList<>();
        for (ChiselRenderType crt : ChiselRenderType.values()) {
            if (!crt.isRequiredForRendering(snapshot)) continue;
            RenderType rt = crt.layer;
            ChiseledBlockBakedModel model = ChiseledBlockBakedModelManager.getInstance()
                .get(snapshot, primaryState, crt, rt);
            if (!model.isEmpty()) models.add(model);
        }
        return models.isEmpty() ? Collections.emptyList() : models;
    }

    /**
     * Iterate the render layers of a C&B voxel storage, invoking a callback for
     * each non-empty (model, entityRenderType) pair. Uses entity render layers
     * suitable for offscreen / thumbnail rendering.
     * <p><b>Client-only</b> — must not be called on the dedicated server.
     *
     * @param storage  the voxel storage
     * @param callback receives (BakedModel, RenderType) for each active layer
     */
    public static void forEachEntityLayer(StateEntryStorage storage,
                                           BiConsumer<BakedModel, RenderType> callback) {
        SimpleSnapshot snapshot = new SimpleSnapshot(storage);
        BlockInformation primaryState = snapshot.getStatics().getPrimaryState();
        for (ChiselRenderType crt : ChiselRenderType.values()) {
            if (!crt.isRequiredForRendering(snapshot)) continue;
            RenderType rt = crt.entityLayer;
            ChiseledBlockBakedModel model = ChiseledBlockBakedModelManager.getInstance()
                .get(snapshot, primaryState, crt, rt);
            if (!model.isEmpty()) callback.accept(model, rt);
        }
    }

    // ── ItemStack Creation ────────────────────────────────────────────
    // Internal: SingleBlockMultiStateItemStack

    /**
     * Create a placeable chiseled block ItemStack from voxel storage data.
     *
     * @param chiseledBlockItem the chiseled block Item (pass the registered item instance)
     * @param storage           the voxel storage
     * @return the ItemStack, or {@link ItemStack#EMPTY} if creation fails
     */
    public static ItemStack createChiseledItemStack(Item chiseledBlockItem,
                                                     StateEntryStorage storage) {
        try {
            return new SingleBlockMultiStateItemStack(chiseledBlockItem, storage).toBlockStack();
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }
}
