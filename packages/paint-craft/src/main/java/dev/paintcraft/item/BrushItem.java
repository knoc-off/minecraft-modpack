package dev.paintcraft.item;

import dev.paintcraft.ModServerConfig;
import dev.paintcraft.PaintCraft;
import dev.paintcraft.core.Decal;
import dev.paintcraft.core.FaceFrame;
import dev.paintcraft.network.DecalSelectionPayload;
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
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
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
                dev.paintcraft.client.ClientBrushHandler.handleCornerClick(pos, face);
            }
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

        ChunkPaintStorage storage = ChunkPaintStorage.get(level);
        List<Decal> overlapping = storage.getAllDecalsAt(pos, face);

        if (overlapping.isEmpty()) {
            // New 1x1: send blank editor signal
            FaceFrame frame = FaceFrame.forFace(face, player.getDirection());
            Direction up = frame.up();
            OpenEditorPayload payload = OpenEditorPayload.blank(
                UUID.randomUUID(), pos, face, up,
                Decal.PX_PER_BLOCK, Decal.PX_PER_BLOCK, ModServerConfig.CONFIG.maxDepth.get().floatValue()
            );
            PacketDistributor.sendToPlayer(player, payload);
            PaintCraft.LOGGER.debug("Sent blank editor to client at {} face {}", pos, face);
        } else if (overlapping.size() == 1) {
            // Single decal: open editor directly
            PacketDistributor.sendToPlayer(player, OpenEditorPayload.fromDecal(overlapping.get(0)));
            PaintCraft.LOGGER.debug("Sent decal {} to client for editing", overlapping.get(0).id());
        } else {
            // Multiple decals: send selection screen
            PacketDistributor.sendToPlayer(player, DecalSelectionPayload.from(overlapping));
            PaintCraft.LOGGER.debug("Sent {} overlapping decals for selection at {} face {}", overlapping.size(), pos, face);
        }
    }
}
