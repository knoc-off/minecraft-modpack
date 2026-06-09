package dev.structurestash.network;

import dev.structurestash.StructureStash;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → Server: confirm blueprint placement at a previously previewed position.
 * Sent when the player right-clicks a second time with the same blueprint item.
 */
public record ConfirmBlueprintPlacePayload(
    BlockPos anchor,
    int rotationOrdinal
) implements CustomPacketPayload {

    public static final Type<ConfirmBlueprintPlacePayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(StructureStash.MODID, "confirm_blueprint_place"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ConfirmBlueprintPlacePayload> STREAM_CODEC =
        StreamCodec.of(ConfirmBlueprintPlacePayload::write, ConfirmBlueprintPlacePayload::read);

    @Override
    public Type<ConfirmBlueprintPlacePayload> type() { return TYPE; }

    private static void write(FriendlyByteBuf buf, ConfirmBlueprintPlacePayload p) {
        buf.writeBlockPos(p.anchor);
        buf.writeVarInt(p.rotationOrdinal);
    }

    private static ConfirmBlueprintPlacePayload read(FriendlyByteBuf buf) {
        return new ConfirmBlueprintPlacePayload(
            buf.readBlockPos(),
            buf.readVarInt()
        );
    }
}
