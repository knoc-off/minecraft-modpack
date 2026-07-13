package dev.paintcraft.network;

import dev.paintcraft.PaintCraft;
import dev.paintcraft.core.PaletteCodec;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: request to charge the dye cost of a pasted image before it is
 * accepted in the editor. The server recomputes the cost authoritatively, deducts
 * dye from the player's inventory (unless in creative), and replies with a
 * {@link PasteChargeResultPayload} carrying the same {@code requestId}.
 */
public record PasteChargeRequestPayload(int requestId, int[] pixels) implements CustomPacketPayload {

    public static final Type<PasteChargeRequestPayload> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(PaintCraft.MODID, "paste_charge_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PasteChargeRequestPayload> STREAM_CODEC =
        StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.requestId);
                PaletteCodec.writePixels(buf, p.pixels);
            },
            buf -> new PasteChargeRequestPayload(buf.readVarInt(), readPixels(buf))
        );

    private static int[] readPixels(FriendlyByteBuf buf) {
        return PaletteCodec.readPixels(buf);
    }

    @Override
    public Type<PasteChargeRequestPayload> type() {
        return TYPE;
    }
}
