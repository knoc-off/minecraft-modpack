package dev.paintcraft.item;

import dev.paintcraft.ModServerConfig;
import dev.paintcraft.PaintCraft;
import dev.paintcraft.core.Decal;
import dev.paintcraft.core.FaceFrame;
import dev.paintcraft.network.DecalCreatePayload;
import dev.paintcraft.storage.ChunkPaintStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class StampItem extends Item {

    public StampItem(Properties props) {
        super(props);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;

        BlockPos pos = context.getClickedPos();
        Direction face = context.getClickedFace();
        ItemStack stack = context.getItemInHand();

        if (level.getBlockState(pos).isAir()) return InteractionResult.PASS;

        if (isLoaded(stack)) {
            // Place the stamp
            if (level.isClientSide) return InteractionResult.SUCCESS;
            placeStamp(stack, (ServerLevel) level, (ServerPlayer) player, pos, face);
        } else {
            // Copy from existing decal
            if (level.isClientSide) return InteractionResult.SUCCESS;
            copyDecal(stack, (ServerLevel) level, (ServerPlayer) player, pos, face);
        }

        return InteractionResult.SUCCESS;
    }

    private void placeStamp(ItemStack stack, ServerLevel level, ServerPlayer player, BlockPos pos, Direction face) {
        StampData data = getData(stack);
        if (data == null) return;

        // Auto-orient: same logic as new decal creation
        FaceFrame frame = FaceFrame.forFace(face, player.getDirection());
        Direction up = frame.up();

        // Create decal via the normal pipeline
        DecalCreatePayload payload = new DecalCreatePayload(
            UUID.randomUUID(), 0, pos, face, up,
            data.widthPx(), data.heightPx(), ModServerConfig.CONFIG.maxDepth.get().floatValue(), (byte) 0, data.pixels()
        );

        // Store server-side and broadcast to all tracking players (including placer)
        ChunkPaintStorage storage = ChunkPaintStorage.get(level);
        long seqNo = storage.nextSeqNo();
        Decal decal = new Decal(
            payload.id(), seqNo, pos, face, up,
            data.widthPx(), data.heightPx(), ModServerConfig.CONFIG.maxDepth.get().floatValue(), data.pixels().clone(), (byte) 0
        );
        decal.setAuthor(player.getGameProfile().getName());
        storage.putDecal(decal);

        // Broadcast to tracking players
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingChunk(
            level, new ChunkPos(pos), DecalCreatePayload.fromDecal(decal)
        );

        // Consume the stamp
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }

        player.displayClientMessage(Component.literal("Placed canvas"), true);
        PaintCraft.LOGGER.debug("Stamp placed decal {} at {} face {}", decal.id(), pos, face);
    }

    private void copyDecal(ItemStack stack, ServerLevel level, ServerPlayer player, BlockPos pos, Direction face) {
        ChunkPaintStorage storage = ChunkPaintStorage.get(level);
        Optional<Decal> existing = storage.getTopmostDecalAt(pos, face);

        if (existing.isEmpty()) {
            player.displayClientMessage(Component.literal("No canvas to copy here"), true);
            return;
        }

        Decal decal = existing.get();
        StampData data = StampData.fromDecal(decal);
        if (stack.getCount() > 1) {
            ItemStack single = stack.split(1);
            setData(single, data);
            if (!player.getInventory().add(single)) {
                player.drop(single, false);
            }
        } else {
            setData(stack, data);
        }

        player.displayClientMessage(
            Component.literal("Copied " + data.widthBlocks() + "×" + data.heightBlocks() + " canvas"), true);
        PaintCraft.LOGGER.debug("Stamp copied decal {} ({}x{})", decal.id(), data.widthBlocks(), data.heightBlocks());
    }

    @Override
    public Optional<TooltipComponent> getTooltipImage(ItemStack stack) {
        if (!isLoaded(stack)) return Optional.empty();
        StampData data = getData(stack);
        if (data == null) return Optional.empty();
        return Optional.of(new StampTooltipComponent(data.widthPx(), data.heightPx(), data.pixels()));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        if (isLoaded(stack)) {
            StampData data = getData(stack);
            if (data != null) {
                tooltip.add(Component.literal("Canvas: " + data.widthBlocks() + "×" + data.heightBlocks() + " blocks"));
            }
        } else {
            tooltip.add(Component.literal("Empty — click a canvas to copy"));
        }
    }

    public static boolean isLoaded(ItemStack stack) {
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom == null) return false;
        CompoundTag tag = custom.copyTag();
        return tag.contains("stamp");
    }

    public static StampData getData(ItemStack stack) {
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom == null) return null;
        CompoundTag tag = custom.copyTag();
        if (!tag.contains("stamp")) return null;
        return StampData.load(tag.getCompound("stamp"));
    }

    public static void setData(ItemStack stack, StampData data) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put("stamp", data.save()));
    }
}
