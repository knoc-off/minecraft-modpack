package dev.structurestash.item;

import dev.structurestash.StructureStash;
import dev.structurestash.compat.BlockNormalizer;
import dev.structurestash.network.CapturedStructurePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.ByteArrayOutputStream;

/**
 * Blueprint Wand — A/B region selection tool for capturing multi-block structures.
 * First click sets point A, second click sets point B and captures the region.
 * Shift+click clears selection.
 */
public class BlueprintWandItem extends Item {

    private static final int MAX_SIDE = 16;

    public BlueprintWandItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        if (ctx.getLevel().isClientSide()) return InteractionResult.SUCCESS;
        if (!(ctx.getPlayer() instanceof ServerPlayer player)) return InteractionResult.PASS;
        return handleWandUse(player, ctx.getItemInHand(), ctx.getClickedPos());
    }

    /**
     * Core wand logic shared by useOn (block click) and WandClickAirPayload (air click).
     */
    public static InteractionResult handleWandUse(ServerPlayer player, ItemStack wand, BlockPos clicked) {
        // Shift+click = clear
        if (player.isShiftKeyDown()) {
            wand.remove(ModDataComponents.WAND_POS_A.get());
            player.displayClientMessage(Component.literal("Selection cleared"), true);
            return InteractionResult.SUCCESS;
        }

        BlockPos posA = wand.get(ModDataComponents.WAND_POS_A.get());

        if (posA == null) {
            // First click: store point A
            wand.set(ModDataComponents.WAND_POS_A.get(), clicked);
            player.displayClientMessage(
                Component.literal("Point A: " + clicked.toShortString() + " — click Point B to capture"), true);
            return InteractionResult.SUCCESS;
        }

        // Second click: capture region
        wand.remove(ModDataComponents.WAND_POS_A.get());
        return captureRegion(player, posA, clicked);
    }

    private static InteractionResult captureRegion(ServerPlayer player, BlockPos posA, BlockPos posB) {
        ServerLevel level = player.serverLevel();

        // Calculate bounding box
        BlockPos min = new BlockPos(
            Math.min(posA.getX(), posB.getX()),
            Math.min(posA.getY(), posB.getY()),
            Math.min(posA.getZ(), posB.getZ()));
        BlockPos max = new BlockPos(
            Math.max(posA.getX(), posB.getX()),
            Math.max(posA.getY(), posB.getY()),
            Math.max(posA.getZ(), posB.getZ()));
        Vec3i size = new Vec3i(
            max.getX() - min.getX() + 1,
            max.getY() - min.getY() + 1,
            max.getZ() - min.getZ() + 1);

        // Validate size
        if (size.getX() > MAX_SIDE || size.getY() > MAX_SIDE || size.getZ() > MAX_SIDE) {
            player.displayClientMessage(
                Component.literal("Selection too large! Max " + MAX_SIDE + " blocks per side."), true);
            return InteractionResult.FAIL;
        }

        // Capture via StructureTemplate
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(level, min, size, false, Blocks.STRUCTURE_VOID);

        // Check we captured something meaningful
        Vec3i templateSize = template.getSize();
        if (templateSize.getX() == 0 && templateSize.getY() == 0 && templateSize.getZ() == 0) {
            player.displayClientMessage(Component.literal("Nothing to capture!"), true);
            return InteractionResult.FAIL;
        }

        // Serialize to byte[]
        try {
            CompoundTag nbt = template.save(new CompoundTag());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(nbt, baos);
            byte[] data = baos.toByteArray();

            // Count non-air blocks from the serialized NBT
            ListTag blocksList = nbt.getList("blocks", Tag.TAG_COMPOUND);
            ListTag paletteList = nbt.getList("palette", Tag.TAG_COMPOUND);
            int blockCount = 0;
            for (int i = 0; i < blocksList.size(); i++) {
                CompoundTag entry = blocksList.getCompound(i);
                int stateIdx = entry.getInt("state");
                if (stateIdx >= 0 && stateIdx < paletteList.size()) {
                    String blockName = paletteList.getCompound(stateIdx).getString("Name");
                    if (!blockName.equals("minecraft:air")) blockCount++;
                }
            }

            if (blockCount == 0) {
                player.displayClientMessage(Component.literal("No blocks captured in region!"), true);
                return InteractionResult.FAIL;
            }

            // Strip container inventories to prevent item duplication
            stripContainerContents(nbt);

            // Normalize illegal, decorative, and growth-stage blocks.
            // Also strips waterlogged property to prevent free water sources.
            BlockNormalizer.NormalizationResult norm = BlockNormalizer.normalize(nbt);

            // Re-serialize after stripping + normalization
            ByteArrayOutputStream baos2 = new ByteArrayOutputStream();
            NbtIo.writeCompressed(nbt, baos2);
            byte[] cleanData = baos2.toByteArray();

            // Send to client for local library save
            String name = "Blueprint " + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("MMM d HH:mm"));

            PacketDistributor.sendToPlayer(player,
                new CapturedStructurePayload(cleanData, name, size.getX(), size.getZ()));

            String captureMsg = "Captured " + blockCount + " blocks ("
                + size.getX() + "\u00D7" + size.getY() + "\u00D7" + size.getZ() + ")";
            if (norm.anyChanges()) captureMsg += " \u2014 " + norm.summary();
            player.displayClientMessage(Component.literal(captureMsg), true);

            StructureStash.LOGGER.info("Player {} captured multi-block structure ({} blocks)",
                player.getName().getString(), blockCount);
            return InteractionResult.SUCCESS;

        } catch (Exception e) {
            StructureStash.LOGGER.error("Failed to serialize captured structure", e);
            player.displayClientMessage(Component.literal("Capture failed — see log"), true);
            return InteractionResult.FAIL;
        }
    }

    /**
     * Strip exploitable data from captured structure NBT.
     * Removes container inventories, command block commands, spawner data, etc.
     */
    private static void stripContainerContents(CompoundTag structureNbt) {
        ListTag blocks = structureNbt.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            if (block.contains("nbt")) {
                CompoundTag beNbt = block.getCompound("nbt");
                // Containers
                beNbt.remove("Items");
                beNbt.remove("LootTable");
                beNbt.remove("LootTableSeed");
                // Command blocks
                beNbt.remove("Command");
                beNbt.remove("powered");
                beNbt.remove("auto");
                // Spawners
                beNbt.remove("SpawnData");
                beNbt.remove("SpawnPotentials");
                beNbt.remove("SpawnCount");
                beNbt.remove("SpawnRange");
                beNbt.remove("Delay");
                beNbt.remove("MinSpawnDelay");
                beNbt.remove("MaxSpawnDelay");
                beNbt.remove("MaxNearbyEntities");
                beNbt.remove("RequiredPlayerRange");
            }
        }
    }
}
