package dev.structurestash.network;

import dev.structurestash.StructureStash;
import mod.chiselsandbits.api.blockinformation.BlockInformation;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → Server: withdraw bits from stash into inventory.
 */
public record StashWithdrawPayload(
    BlockInformation blockInfo,
    int count
) implements CustomPacketPayload {

    public static final Type<StashWithdrawPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(StructureStash.MODID, "stash_withdraw"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StashWithdrawPayload> STREAM_CODEC =
        StreamCodec.of(StashWithdrawPayload::write, StashWithdrawPayload::read);

    @Override
    public Type<StashWithdrawPayload> type() { return TYPE; }

    private static void write(RegistryFriendlyByteBuf buf, StashWithdrawPayload p) {
        BlockInformation.STREAM_CODEC.encode(buf, p.blockInfo);
        buf.writeVarInt(p.count);
    }

    private static StashWithdrawPayload read(RegistryFriendlyByteBuf buf) {
        return new StashWithdrawPayload(
            BlockInformation.STREAM_CODEC.decode(buf),
            buf.readVarInt()
        );
    }
}
