package dev.structurestash.network;

import dev.structurestash.StructureStash;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → Server: request stash sync (e.g., when opening stash UI or browser).
 */
public record StashRequestPayload() implements CustomPacketPayload {

    public static final Type<StashRequestPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(StructureStash.MODID, "stash_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StashRequestPayload> STREAM_CODEC =
        StreamCodec.of(StashRequestPayload::write, StashRequestPayload::read);

    @Override
    public Type<StashRequestPayload> type() { return TYPE; }

    private static void write(FriendlyByteBuf buf, StashRequestPayload p) {
        // No data
    }

    private static StashRequestPayload read(FriendlyByteBuf buf) {
        return new StashRequestPayload();
    }
}
