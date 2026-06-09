package dev.paintcraft.compat.create;

import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.StructureTransform;
import dev.paintcraft.core.Decal;
import dev.paintcraft.network.DecalCreatePayload;
import dev.paintcraft.network.DecalDeletePayload;
import dev.paintcraft.storage.ChunkPaintStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side lifecycle for PaintCraft decals on Create contraptions.
 *
 * <ul>
 *   <li><b>Assembly</b> ({@link #onAssemble}): decals whose anchor block becomes part of the
 *       contraption are converted to contraption-local coordinates, stashed on the contraption,
 *       and removed from world storage (so they travel with the contraption and are not deleted
 *       by the block-change handler when the source blocks turn to air).</li>
 *   <li><b>Disassembly</b> ({@link #onDisassemble}): stashed decals are transformed by the
 *       contraption's {@link StructureTransform} (translation + rotation/mirror of the anchor and
 *       facings) and written back into world storage at their new positions.</li>
 * </ul>
 */
public final class ContraptionDecalSupport {

    public static final String TAG = "PaintCraftDecals";

    private ContraptionDecalSupport() {}

    /** Capture decals on blocks being assembled into the contraption. Runs at assembly. */
    public static void onAssemble(Contraption c, Level world, BlockPos offset) {
        if (world.isClientSide() || !(world instanceof ServerLevel level)) return;
        BlockPos anchor = c.anchor;
        if (anchor == null) return;
        Set<BlockPos> localPositions = c.getBlocks().keySet();
        if (localPositions.isEmpty()) return;

        // Map each contraption block's real world position -> its local position.
        Map<BlockPos, BlockPos> worldToLocal = new HashMap<>();
        Set<ChunkPos> chunks = new HashSet<>();
        for (BlockPos local : localPositions) {
            BlockPos wp = local.offset(anchor).offset(offset);
            worldToLocal.put(wp, local);
            chunks.add(new ChunkPos(wp));
        }

        ChunkPaintStorage storage = ChunkPaintStorage.get(level);
        List<CompoundTag> captured = ((PaintCraftContraption) c).paintcraft$decals();
        Set<UUID> seen = new HashSet<>();

        for (ChunkPos cp : chunks) {
            for (Decal d : storage.getDecalsInChunk(cp)) {
                BlockPos local = worldToLocal.get(d.anchor());
                if (local == null) continue;
                if (!seen.add(d.id())) continue;

                Decal localDecal = new Decal(d.id(), d.seqNo(), local, d.normal(), d.up(),
                    d.widthPx(), d.heightPx(), d.depth(), d.pixels(), d.flags());
                localDecal.setAuthor(d.author());
                localDecal.setZOverride(d.zOrder());
                captured.add(localDecal.save());

                storage.removeDecal(d.id());
                PacketDistributor.sendToPlayersTrackingChunk(
                    level, new ChunkPos(d.anchor()), new DecalDeletePayload(d.id()));
            }
        }
    }

    /** Restore captured decals into the world, transformed to their new positions. Runs at disassembly. */
    public static void onDisassemble(Contraption c, Level world, StructureTransform transform) {
        if (world.isClientSide() || !(world instanceof ServerLevel level)) return;
        List<CompoundTag> tags = ((PaintCraftContraption) c).paintcraft$decals();
        if (tags == null || tags.isEmpty()) return;

        ChunkPaintStorage storage = ChunkPaintStorage.get(level);
        List<Decal> placedDecals = new ArrayList<>();
        for (CompoundTag tag : tags) {
            Decal local = Decal.load(tag);

            BlockPos worldAnchor = transform.apply(local.anchor());
            Direction normal = transform.mirrorFacing(local.normal());
            Direction up = transform.mirrorFacing(local.up());
            if (transform.rotation != null) {
                normal = transform.rotateFacing(normal);
                up = transform.rotateFacing(up);
            }

            // Drop the decal if its anchor block failed to materialize (e.g. obstructed placement).
            if (level.getBlockState(worldAnchor)
                    .getShape(level, worldAnchor, CollisionContext.empty()).isEmpty()) {
                continue;
            }

            long seq = storage.nextSeqNo();
            Decal placed = new Decal(local.id(), seq, worldAnchor, normal, up,
                local.widthPx(), local.heightPx(), local.depth(), local.pixels(), local.flags());
            placed.setAuthor(local.author());
            storage.putDecal(placed);
            placedDecals.add(placed);
        }
        tags.clear();

        // Defer client notification by one tick: the blocks placed above are broadcast at the end
        // of this tick, so sending DecalCreate next tick guarantees the client resolves against
        // present blocks (otherwise it resolves an empty surface and never retries).
        if (!placedDecals.isEmpty()) {
            MinecraftServer server = level.getServer();
            server.tell(new TickTask(server.getTickCount() + 1, () -> {
                for (Decal placed : placedDecals) {
                    PacketDistributor.sendToPlayersTrackingChunk(
                        level, new ChunkPos(placed.anchor()), DecalCreatePayload.fromDecal(placed));
                }
            }));
        }
    }

    // --- NBT (called from the Contraption mixin; covers world-save and the entity spawn packet) ---

    public static void writeTo(CompoundTag nbt, List<CompoundTag> decals) {
        if (nbt == null || decals == null || decals.isEmpty()) return;
        ListTag list = new ListTag();
        list.addAll(decals);
        nbt.put(TAG, list);
    }

    public static List<CompoundTag> readFrom(CompoundTag nbt) {
        List<CompoundTag> out = new ArrayList<>();
        if (nbt == null || !nbt.contains(TAG, Tag.TAG_LIST)) return out;
        ListTag list = nbt.getList(TAG, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            out.add(list.getCompound(i));
        }
        return out;
    }
}
