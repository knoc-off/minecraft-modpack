package dev.structurestash.network;

import dev.structurestash.StructureStash;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> Server: wand right-click targeting air (no block hit).
 * Carries the computed BlockPos at the player's reach distance.
 */
public record WandClickAirPayload(BlockPos pos) implements CustomPacketPayload {

    public static final Type<WandClickAirPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(StructureStash.MODID, "wand_click_air"));

    public static final StreamCodec<RegistryFriendlyByteBuf, WandClickAirPayload> STREAM_CODEC =
        StreamCodec.of(WandClickAirPayload::write, WandClickAirPayload::read);

    @Override
    public Type<WandClickAirPayload> type() { return TYPE; }

    private static void write(FriendlyByteBuf buf, WandClickAirPayload p) {
        buf.writeBlockPos(p.pos);
    }

    private static WandClickAirPayload read(FriendlyByteBuf buf) {
        return new WandClickAirPayload(buf.readBlockPos());
    }
}
