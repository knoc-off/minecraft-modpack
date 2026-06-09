package dev.paintcraft.mixin.compat;

import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.StructureTransform;
import dev.paintcraft.compat.create.ContraptionDecalSupport;
import dev.paintcraft.compat.create.PaintCraftContraption;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Carries PaintCraft decals with a Create contraption: captures them on assembly,
 * (de)serializes them with the contraption (world-save + client spawn packet), and
 * restores them to the world on disassembly.
 */
@Pseudo
@Mixin(value = Contraption.class, remap = false)
public abstract class ContraptionMixin implements PaintCraftContraption {

    @Unique
    private List<CompoundTag> paintcraft$decals = new ArrayList<>();

    @Override
    public List<CompoundTag> paintcraft$decals() {
        if (paintcraft$decals == null) paintcraft$decals = new ArrayList<>();
        return paintcraft$decals;
    }

    @Override
    public void paintcraft$setDecals(List<CompoundTag> decals) {
        this.paintcraft$decals = (decals != null) ? decals : new ArrayList<>();
    }

    @Inject(method = "removeBlocksFromWorld", at = @At("HEAD"), remap = false)
    private void paintcraft$capture(Level world, BlockPos offset, CallbackInfo ci) {
        ContraptionDecalSupport.onAssemble((Contraption) (Object) this, world, offset);
    }

    @Inject(method = "addBlocksToWorld", at = @At("TAIL"), remap = false)
    private void paintcraft$restore(Level world, StructureTransform transform, CallbackInfo ci) {
        ContraptionDecalSupport.onDisassemble((Contraption) (Object) this, world, transform);
    }

    @Inject(method = "writeNBT", at = @At("RETURN"), remap = false)
    private void paintcraft$write(HolderLookup.Provider registries, boolean spawnPacket,
                                  CallbackInfoReturnable<CompoundTag> cir) {
        ContraptionDecalSupport.writeTo(cir.getReturnValue(), paintcraft$decals());
    }

    @Inject(method = "readNBT", at = @At("TAIL"), remap = false)
    private void paintcraft$read(Level world, CompoundTag nbt, boolean spawnData, CallbackInfo ci) {
        paintcraft$setDecals(ContraptionDecalSupport.readFrom(nbt));
    }
}
