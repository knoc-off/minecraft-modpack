package dev.paintcraft.item;

import dev.paintcraft.PaintCraft;
import dev.paintcraft.core.Decal;
import dev.paintcraft.network.DecalDeletePayload;
import dev.paintcraft.network.DecalSelectionPayload;
import dev.paintcraft.storage.ChunkPaintStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

public class EraserItem extends Item {

    public EraserItem(Properties props) {
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
        if (level.isClientSide) return InteractionResult.SUCCESS;

        if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer) {
            handleServer(serverLevel, serverPlayer, pos, face, player.isShiftKeyDown());
        }

        return InteractionResult.SUCCESS;
    }

    private void handleServer(ServerLevel level, ServerPlayer player, BlockPos pos, Direction face, boolean eraseAll) {
        ChunkPaintStorage storage = ChunkPaintStorage.get(level);
        List<Decal> overlapping = storage.getAllDecalsAt(pos, face);

        if (overlapping.isEmpty()) {
            player.displayClientMessage(Component.literal("No decals here"), true);
            return;
        }

        if (eraseAll) {
            // Shift+click: erase ALL decals at this face
            int count = overlapping.size();
            for (Decal decal : overlapping) {
                storage.removeDecal(decal.id());
                PacketDistributor.sendToPlayersTrackingChunk(
                    level, new ChunkPos(decal.anchor()),
                    new DecalDeletePayload(decal.id())
                );
            }
            player.displayClientMessage(
                Component.literal("Erased " + count + " decal" + (count > 1 ? "s" : "")), true);
            PaintCraft.LOGGER.debug("Eraser bulk-erased {} decals at {} face {}", count, pos, face);
        } else if (overlapping.size() == 1) {
            // Single decal: erase immediately
            Decal decal = overlapping.get(0);
            storage.removeDecal(decal.id());
            PacketDistributor.sendToPlayersTrackingChunk(
                level, new ChunkPos(decal.anchor()),
                new DecalDeletePayload(decal.id())
            );
            player.displayClientMessage(Component.literal("Erased decal"), true);
            PaintCraft.LOGGER.debug("Eraser removed decal {} at {} face {}", decal.id(), pos, face);
        } else {
            // Multiple decals: send selection screen in erase mode
            PacketDistributor.sendToPlayer(player, DecalSelectionPayload.forErase(overlapping));
            PaintCraft.LOGGER.debug("Eraser sent {} decals for erase selection at {} face {}", overlapping.size(), pos, face);
        }
    }
}
