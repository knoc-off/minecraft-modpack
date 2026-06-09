package dev.paintcraft.mixin.compat;

import mod.chiselsandbits.block.entities.ChiseledBlockEntity;
import mod.chiselsandbits.voxelshape.SingleBlockVoxelShapeCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fixes a bug in Chisel & Bits where the client-side VoxelShape cache is never
 * invalidated after async deserialization completes. Without this fix,
 * getShape() permanently returns Shapes.block() (the loading-phase fallback)
 * even after the correct voxel data is available.
 */
@Pseudo
@Mixin(value = ChiseledBlockEntity.class, remap = false)
public abstract class ChiseledBlockEntityMixin {

    @Shadow private SingleBlockVoxelShapeCache voxelShapeCache;

    @Inject(method = "updateModelDataIfInLoadedChunk", at = @At("HEAD"), remap = false)
    private void paintcraft$resetShapeCache(CallbackInfo ci) {
        voxelShapeCache.reset();
    }
}
