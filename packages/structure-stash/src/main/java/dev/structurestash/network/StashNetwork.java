package dev.structurestash.network;

import dev.structurestash.StructureStash;
import dev.structurestash.client.ClientStashHandlers;
import dev.structurestash.compat.BlockNormalizer;
import dev.structurestash.item.BlueprintItem;
import dev.structurestash.item.BlueprintWandItem;
import dev.structurestash.item.ModDataComponents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class StashNetwork {

    private static final String PROTOCOL_VERSION = "4";

    private StashNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(StructureStash.MODID)
            .versioned(PROTOCOL_VERSION);

        registrar.playToClient(
            CapturedStructurePayload.TYPE,
            CapturedStructurePayload.STREAM_CODEC,
            StashNetwork::handleCapturedStructure
        );

        registrar.playToServer(
            ConfirmBlueprintPlacePayload.TYPE,
            ConfirmBlueprintPlacePayload.STREAM_CODEC,
            StashNetwork::handleConfirmPlacement
        );

        registrar.playToServer(
            WandClickAirPayload.TYPE,
            WandClickAirPayload.STREAM_CODEC,
            StashNetwork::handleWandClickAir
        );
    }

    // --- Server handlers ---

    private static void handleConfirmPlacement(ConfirmBlueprintPlacePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;

            // Player must be holding a blueprint in main or offhand
            ItemStack held = player.getMainHandItem();
            if (!(held.getItem() instanceof BlueprintItem)) {
                held = player.getOffhandItem();
                if (!(held.getItem() instanceof BlueprintItem)) return;
            }

            var bd = held.get(ModDataComponents.BLUEPRINT_DATA.get());
            byte[] data = bd != null ? bd.data() : null;
            if (data == null || data.length == 0) return;

            // Validate anchor within 64 blocks of player
            BlockPos anchor = payload.anchor();
            if (anchor.distSqr(player.blockPosition()) > 64 * 64) return;

            // Validate rotation ordinal
            Rotation[] rotations = Rotation.values();
            if (payload.rotationOrdinal() < 0 || payload.rotationOrdinal() >= rotations.length) return;
            Rotation rotation = rotations[payload.rotationOrdinal()];

            ServerLevel level = player.serverLevel();

            try {
                CompoundTag nbt = NbtIo.readCompressed(
                    new ByteArrayInputStream(data), NbtAccounter.unlimitedHeap());

                BlockNormalizer.normalize(nbt);

                StructureTemplate template = new StructureTemplate();
                template.load(level.registryAccess().lookupOrThrow(Registries.BLOCK), nbt);

                Vec3i size = template.getSize();
                if (size.equals(Vec3i.ZERO)) {
                    player.displayClientMessage(Component.literal("Invalid blueprint data"), true);
                    return;
                }

                // Determine non-air palette indices
                ListTag paletteList = nbt.getList("palette", Tag.TAG_COMPOUND);
                Set<Integer> nonAirIdx = new HashSet<>();
                for (int i = 0; i < paletteList.size(); i++) {
                    String name = paletteList.getCompound(i).getString("Name");
                    if (!name.equals("minecraft:air")
                            && !name.equals("minecraft:cave_air")
                            && !name.equals("minecraft:void_air")) {
                        nonAirIdx.add(i);
                    }
                }

                // Collision validation
                ListTag blocksList = nbt.getList("blocks", Tag.TAG_COMPOUND);
                for (int bi = 0; bi < blocksList.size(); bi++) {
                    CompoundTag entry = blocksList.getCompound(bi);
                    if (!nonAirIdx.contains(entry.getInt("state"))) continue;

                    ListTag pos = entry.getList("pos", Tag.TAG_INT);
                    BlockPos relPos = new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2));
                    BlockPos worldPos = anchor.offset(
                        StructureTemplate.transform(relPos, Mirror.NONE, rotation, BlockPos.ZERO));
                    BlockState existing = level.getBlockState(worldPos);
                    if (!existing.isAir() && !existing.canBeReplaced()) {
                        player.displayClientMessage(
                            Component.literal("Blocked at " + worldPos.toShortString() + "!"), true);
                        return;
                    }
                }

                // Place structure
                StructurePlaceSettings settings = new StructurePlaceSettings()
                    .setRotation(rotation)
                    .addProcessor(new BlockIgnoreProcessor(
                        List.of(Blocks.AIR, Blocks.CAVE_AIR, Blocks.VOID_AIR)));

                template.placeInWorld(level, anchor, anchor, settings,
                    RandomSource.create(), Block.UPDATE_ALL);

                held.shrink(1);
                player.displayClientMessage(Component.literal(
                    "Structure placed! ("
                        + size.getX() + "\u00D7" + size.getY() + "\u00D7" + size.getZ() + ")"), true);

            } catch (Exception e) {
                StructureStash.LOGGER.error("Failed to place blueprint structure", e);
                player.displayClientMessage(
                    Component.literal("Placement failed \u2014 see log"), true);
            }
        });
    }

    private static void handleWandClickAir(WandClickAirPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;

            // Find wand in main hand or offhand
            ItemStack wand = player.getMainHandItem();
            if (!(wand.getItem() instanceof BlueprintWandItem)) {
                wand = player.getOffhandItem();
                if (!(wand.getItem() instanceof BlueprintWandItem)) return;
            }

            // Validate position is within reasonable reach
            BlockPos pos = payload.pos();
            if (pos.distSqr(player.blockPosition()) > 10 * 10) return;

            BlueprintWandItem.handleWandUse(player, wand, pos);
        });
    }

    // --- Client handlers ---

    private static void handleCapturedStructure(CapturedStructurePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientStashHandlers.handleCapturedStructure(payload));
    }
}
