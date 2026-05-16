package dev.paintcraft.item;

import dev.paintcraft.PaintCraft;
import dev.paintcraft.client.ClientBrushHandler;
import dev.paintcraft.core.Decal;
import dev.paintcraft.network.OpenEditorPayload;
import dev.paintcraft.storage.ChunkPaintStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Optional;
import java.util.UUID;

public class BrushItem extends Item {

    public BrushItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;

        BlockPos pos = context.getClickedPos();
        Direction face = context.getClickedFace();

        if (level.getBlockState(pos).isAir()) return InteractionResult.PASS;

        if (level.isClientSide) {
            if (player.isShiftKeyDown()) {
                // Multi-block corner selection (client-side only)
                ClientBrushHandler.handleCornerClick(pos, face);
            }
            // Non-shift: wait for server to send OpenEditorPayload
            return InteractionResult.SUCCESS;
        }

        if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
            handleServer(serverLevel, serverPlayer, pos, face, player.isShiftKeyDown());
        }

        return InteractionResult.SUCCESS;
    }

    private void handleServer(ServerLevel level, ServerPlayer player, BlockPos pos, Direction face, boolean forceNew) {
        if (forceNew) {
            // Multi-block corner selection is handled entirely client-side
            return;
        }

        ChunkPaintStorage storage = ChunkPaintStorage.get(level, new ChunkPos(pos));
        Optional<Decal> existing = storage.getTopmostDecalAt(pos, face);

        if (existing.isPresent()) {
            // Edit existing: send decal data to open editor
            PacketDistributor.sendToPlayer(player, OpenEditorPayload.fromDecal(existing.get()));
            PaintCraft.LOGGER.debug("Sent decal {} to client for editing", existing.get().id());
        } else {
            // New 1x1: send blank editor signal
            Direction up = face.getAxis().isVertical() ? Direction.NORTH : Direction.UP;
            OpenEditorPayload payload = OpenEditorPayload.blank(
                UUID.randomUUID(), pos, face, up,
                Decal.PX_PER_BLOCK, Decal.PX_PER_BLOCK, 1.0f
            );
            PacketDistributor.sendToPlayer(player, payload);
            PaintCraft.LOGGER.debug("Sent blank editor to client at {} face {}", pos, face);
        }
    }
}
