package dev.paintcraft.network;

import dev.paintcraft.PaintCraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Sent client→server to request "bring to front" or "send to back" for a decal.
 */
public record DecalReorderPayload(UUID id, boolean bringToFront) implements CustomPacketPayload {

    public static final Type<DecalReorderPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(PaintCraft.MODID, "decal_reorder"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DecalReorderPayload> STREAM_CODEC =
        StreamCodec.of(
            (buf, p) -> { buf.writeUUID(p.id); buf.writeBoolean(p.bringToFront); },
            buf -> new DecalReorderPayload(buf.readUUID(), buf.readBoolean())
        );

    @Override
    public Type<DecalReorderPayload> type() {
        return TYPE;
    }
}
