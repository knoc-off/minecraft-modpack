package dev.structurestash.network;

import dev.structurestash.StructureStash;
import dev.structurestash.stash.BitsStash;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → Client: full stash sync.
 */
public record StashSyncPayload(BitsStash stash) implements CustomPacketPayload {

    public static final Type<StashSyncPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(StructureStash.MODID, "stash_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StashSyncPayload> STREAM_CODEC =
        StreamCodec.of(StashSyncPayload::write, StashSyncPayload::read);

    @Override
    public Type<StashSyncPayload> type() { return TYPE; }

    private static void write(RegistryFriendlyByteBuf buf, StashSyncPayload p) {
        p.stash.writeToBuf(buf);
    }

    private static StashSyncPayload read(RegistryFriendlyByteBuf buf) {
        return new StashSyncPayload(BitsStash.readFromBuf(buf));
    }
}
