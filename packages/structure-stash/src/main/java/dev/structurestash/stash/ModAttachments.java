package dev.structurestash.stash;

import dev.structurestash.StructureStash;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

public class ModAttachments {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
        DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, StructureStash.MODID);

    public static final Supplier<AttachmentType<BitsStash>> BITS_STASH = ATTACHMENTS.register(
        "bits_stash", () -> AttachmentType.builder(BitsStash::new)
            .serialize(new BitsStashSerializer())
            .copyOnDeath()
            .build()
    );

    private static class BitsStashSerializer implements IAttachmentSerializer<CompoundTag, BitsStash> {
        @Override
        public BitsStash read(IAttachmentHolder holder, CompoundTag tag, HolderLookup.Provider registries) {
            return BitsStash.load(tag, registries);
        }

        @Override
        public CompoundTag write(BitsStash stash, HolderLookup.Provider registries) {
            return stash.save(registries);
        }
    }
}
