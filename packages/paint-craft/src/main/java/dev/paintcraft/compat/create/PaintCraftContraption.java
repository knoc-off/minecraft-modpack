package dev.paintcraft.compat.create;

import net.minecraft.nbt.CompoundTag;

import java.util.List;

/**
 * Mixin-implemented accessor on Create's {@code Contraption}, carrying the
 * PaintCraft decals captured from the blocks that were assembled into the
 * contraption. Stored in contraption-local coordinates (anchor relative to the
 * contraption anchor) and serialized via {@code Decal.save()}.
 */
public interface PaintCraftContraption {

    /** Mutable list of captured decals (each a {@code Decal.save()} tag, local coords). */
    List<CompoundTag> paintcraft$decals();

    void paintcraft$setDecals(List<CompoundTag> decals);
}
