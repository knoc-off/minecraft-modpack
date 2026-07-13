package dev.paintcraft.network;

import dev.paintcraft.PaintCraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: result of a {@link PasteChargeRequestPayload}. {@code success}
 * is true when the dye cost was consumed (or the player is in creative); the editor
 * then finalizes the pending paste. The {@code requestId} echoes the request so the
 * client can ignore stale replies.
 */
public record PasteChargeResultPayload(int requestId, boolean success) implements CustomPacketPayload {

    public static final Type<PasteChargeResultPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(PaintCraft.MODID, "paste_charge_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PasteChargeResultPayload> STREAM_CODEC =
        StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.requestId);
                buf.writeBoolean(p.success);
            },
            buf -> new PasteChargeResultPayload(buf.readVarInt(), buf.readBoolean())
        );

    @Override
    public Type<PasteChargeResultPayload> type() {
        return TYPE;
    }
}
