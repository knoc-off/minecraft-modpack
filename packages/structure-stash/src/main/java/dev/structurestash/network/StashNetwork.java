package dev.structurestash.network;

import dev.structurestash.StructureStash;
import dev.structurestash.client.ClientStashHandlers;
import dev.structurestash.compat.BlockNormalizer;
import dev.structurestash.item.BlueprintItem;
import dev.structurestash.item.BlueprintWandItem;
import dev.structurestash.item.ModDataComponents;
import dev.structurestash.stash.BitsStash;
import mod.chiselsandbits.api.blockinformation.BlockInformation;
import mod.chiselsandbits.api.chiseling.eligibility.IEligibilityManager;
import mod.chiselsandbits.api.item.bit.IBitItem;
import mod.chiselsandbits.api.item.bit.IBitItemManager;
import mod.chiselsandbits.api.item.chiseled.IChiseledBlockItem;
import mod.chiselsandbits.api.item.multistate.IMultiStateItemStack;
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
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockIgnoreProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class StashNetwork {

    private static final String PROTOCOL_VERSION = "4";

    private StashNetwork() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(StructureStash.MODID)
            .versioned(PROTOCOL_VERSION);

        registrar.playToClient(
            StashSyncPayload.TYPE,
            StashSyncPayload.STREAM_CODEC,
            StashNetwork::handleStashSync
        );

        registrar.playToServer(
            StashRequestPayload.TYPE,
            StashRequestPayload.STREAM_CODEC,
            StashNetwork::handleStashRequest
        );

        registrar.playToServer(
            StashDepositPayload.TYPE,
            StashDepositPayload.STREAM_CODEC,
            StashNetwork::handleDeposit
        );

        registrar.playToServer(
            StashWithdrawPayload.TYPE,
            StashWithdrawPayload.STREAM_CODEC,
            StashNetwork::handleWithdraw
        );

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

    private static void handleStashRequest(StashRequestPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                syncToClient(player);
            }
        });
    }

    private static void handleDeposit(StashDepositPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                int slot = payload.inventorySlot();
                if (slot < 0 || slot >= player.getInventory().getContainerSize()) return;

                ItemStack stack = player.getInventory().getItem(slot);
                if (stack.isEmpty()) return;

                BitsStash stash = BitsStash.get(player);
                boolean deposited;

                if (payload.fullStack()) {
                    deposited = tryDeposit(stash, stack);
                } else {
                    // Single-item deposit: split off 1, try deposit, merge back if failed
                    ItemStack single = stack.copyWithCount(1);
                    deposited = tryDeposit(stash, single);
                    if (deposited) {
                        stack.shrink(1);
                    }
                }

                if (deposited) {
                    player.getInventory().setItem(slot, stack);
                    syncToClient(player);
                } else {
                    player.displayClientMessage(
                        Component.literal("Cannot deposit this item into the bits stash"), true);
                }
            }
        });
    }

    private static void handleWithdraw(StashWithdrawPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                BlockInformation info = payload.blockInfo();
                int requested = Math.min(payload.count(), 64 * 36); // cap to full inventory
                if (requested <= 0 || info.isAir()) return;

                BitsStash stash = BitsStash.get(player);
                long have = stash.getCount(info);
                int actual = (int) Math.min(requested, have);
                if (actual <= 0) {
                    player.displayClientMessage(Component.literal("No bits of that type in stash"), true);
                    return;
                }

                // Create bit items in stacks of 64
                int remaining = actual;
                while (remaining > 0) {
                    int stackSize = Math.min(64, remaining);
                    ItemStack bitStack = IBitItemManager.getInstance().create(info, stackSize);
                    if (bitStack.isEmpty()) break;
                    if (!player.getInventory().add(bitStack)) {
                        player.drop(bitStack, false);
                    }
                    remaining -= stackSize;
                }

                int withdrawn = actual - remaining;
                stash.consume(info, withdrawn);
                syncToClient(player);

                String name = info.blockState().getBlock().getName().getString();
                player.displayClientMessage(
                    Component.literal("Withdrew " + withdrawn + " " + name + " bits"), true);
            }
        });
    }

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

    /**
     * Try to deposit an item stack into the stash. Modifies the stack in place.
     * Returns true if anything was deposited.
     */
    private static boolean tryDeposit(BitsStash stash, ItemStack stack) {
        // Check if it's a C&B bit item
        if (stack.getItem() instanceof IBitItem bitItem) {
            BlockInformation bitInfo = bitItem.getBlockInformation(stack);
            if (bitInfo != null && !bitInfo.isAir()) {
                stash.add(bitInfo, stack.getCount());
                stack.setCount(0);
                return true;
            }
        }

        // Check if it's a chiseled block item
        if (stack.getItem() instanceof IChiseledBlockItem cbi) {
            IMultiStateItemStack msStack = cbi.createItemStack(stack);
            // Stream all entries and count by type
            msStack.stream().forEach(entry -> {
                BlockInformation info = entry.getBlockInformation();
                if (!info.isAir()) {
                    stash.add(info, 1);
                }
            });
            stack.shrink(1);
            return true;
        }

        // Check if it's a regular block eligible for chiseling (becomes 4096 bits)
        if (stack.getItem() instanceof BlockItem blockItem) {
            Block block = blockItem.getBlock();
            BlockState state = block.defaultBlockState();
            BlockInformation info = new BlockInformation(state, Optional.empty());
            if (IEligibilityManager.getInstance().canBeChiseled(info)) {
                stash.add(info, 4096L * stack.getCount());
                stack.setCount(0);
                return true;
            }
        }

        return false;
    }

    // --- Client handlers ---

    private static void handleStashSync(StashSyncPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientStashHandlers.handleStashSync(payload));
    }

    private static void handleCapturedStructure(CapturedStructurePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientStashHandlers.handleCapturedStructure(payload));
    }

    // --- Utilities ---

    public static void syncToClient(ServerPlayer player) {
        BitsStash stash = BitsStash.get(player);
        PacketDistributor.sendToPlayer(player, new StashSyncPayload(stash));
    }
}
