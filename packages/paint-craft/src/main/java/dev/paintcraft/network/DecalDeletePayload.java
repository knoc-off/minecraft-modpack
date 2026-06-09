package dev.paintcraft.network;

import dev.paintcraft.PaintCraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record DecalDeletePayload(UUID id) implements CustomPacketPayload {

    public static final Type<DecalDeletePayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(PaintCraft.MODID, "decal_delete"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DecalDeletePayload> STREAM_CODEC =
        StreamCodec.of(
            (buf, p) -> buf.writeUUID(p.id),
            buf -> new DecalDeletePayload(buf.readUUID())
        );

    @Override
    public Type<DecalDeletePayload> type() {
        return TYPE;
    }
}
