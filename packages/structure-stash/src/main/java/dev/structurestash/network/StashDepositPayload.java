package dev.structurestash.network;

import dev.structurestash.StructureStash;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → Server: deposit an item into the stash.
 * fullStack=true deposits the entire stack, false deposits 1 item.
 */
public record StashDepositPayload(int inventorySlot, boolean fullStack) implements CustomPacketPayload {

    public static final Type<StashDepositPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(StructureStash.MODID, "stash_deposit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StashDepositPayload> STREAM_CODEC =
        StreamCodec.of(StashDepositPayload::write, StashDepositPayload::read);

    @Override
    public Type<StashDepositPayload> type() { return TYPE; }

    private static void write(RegistryFriendlyByteBuf buf, StashDepositPayload p) {
        buf.writeVarInt(p.inventorySlot);
        buf.writeBoolean(p.fullStack);
    }

    private static StashDepositPayload read(RegistryFriendlyByteBuf buf) {
        return new StashDepositPayload(buf.readVarInt(), buf.readBoolean());
    }
}
