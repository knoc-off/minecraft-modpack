package dev.paintcraft.network;

import dev.paintcraft.PaintCraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record DecalErasePayload(UUID id) implements CustomPacketPayload {

    public static final Type<DecalErasePayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(PaintCraft.MODID, "decal_erase"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DecalErasePayload> STREAM_CODEC =
        StreamCodec.of(
            (buf, p) -> buf.writeUUID(p.id),
            buf -> new DecalErasePayload(buf.readUUID())
        );

    @Override
    public Type<DecalErasePayload> type() {
        return TYPE;
    }
}
