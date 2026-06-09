package dev.structurestash.stash;

import mod.chiselsandbits.api.blockinformation.BlockInformation;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-player storage for C&B bits. Maps BlockInformation → count.
 * Tracks lastModified per entry for "Recent" sort.
 * Lives as a NeoForge player data attachment.
 */
public class BitsStash {

    private final Map<BlockInformation, Long> bits = new LinkedHashMap<>();
    private final Map<BlockInformation, Long> lastModified = new LinkedHashMap<>();

    public BitsStash() {}

    public long getCount(BlockInformation info) {
        return bits.getOrDefault(info, 0L);
    }

    public long getLastModified(BlockInformation info) {
        return lastModified.getOrDefault(info, 0L);
    }

    public void add(BlockInformation info, long count) {
        if (count <= 0 || info.isAir()) return;
        bits.merge(info, count, (a, b) -> {
            long r = a + b;
            return r < 0 ? Long.MAX_VALUE : r; // saturating add — clamp on overflow
        });
        lastModified.put(info, System.currentTimeMillis());
    }

    public boolean consume(BlockInformation info, long count) {
        if (count <= 0) return true;
        long have = bits.getOrDefault(info, 0L);
        if (have < count) return false;
        long remaining = have - count;
        if (remaining == 0) {
            bits.remove(info);
            lastModified.remove(info);
        } else {
            bits.put(info, remaining);
            lastModified.put(info, System.currentTimeMillis());
        }
        return true;
    }

    public Map<BlockInformation, Long> getAll() {
        return Collections.unmodifiableMap(bits);
    }

    public Map<BlockInformation, Long> getAllLastModified() {
        return Collections.unmodifiableMap(lastModified);
    }

    public long totalBits() {
        long total = 0;
        for (long v : bits.values()) total += v;
        return total;
    }

    public boolean isEmpty() {
        return bits.isEmpty();
    }

    public int uniqueTypes() {
        return bits.size();
    }

    // --- NBT Serialization ---

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag root = new CompoundTag();
        ListTag list = new ListTag();
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        for (var entry : bits.entrySet()) {
            CompoundTag tag = new CompoundTag();
            Tag encoded = BlockInformation.CODEC.encodeStart(ops, entry.getKey())
                .getOrThrow();
            tag.put("info", encoded);
            tag.putLong("count", entry.getValue());
            tag.putLong("modified", lastModified.getOrDefault(entry.getKey(), 0L));
            list.add(tag);
        }
        root.put("entries", list);
        return root;
    }

    public static BitsStash load(CompoundTag root, HolderLookup.Provider registries) {
        BitsStash stash = new BitsStash();
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        ListTag list = root.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            BlockInformation info = BlockInformation.CODEC.parse(ops, tag.get("info"))
                .result().orElse(null);
            if (info != null && !info.isAir()) {
                long count = tag.getLong("count");
                if (count <= 0) continue; // skip corrupt/invalid entries
                stash.bits.put(info, count);
                stash.lastModified.put(info, tag.contains("modified") ? tag.getLong("modified") : 0L);
            }
        }
        return stash;
    }

    // --- Network Sync ---

    public void writeToBuf(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(bits.size());
        for (var entry : bits.entrySet()) {
            BlockInformation.STREAM_CODEC.encode(buf, entry.getKey());
            buf.writeVarLong(entry.getValue());
            buf.writeVarLong(lastModified.getOrDefault(entry.getKey(), 0L));
        }
    }

    public static BitsStash readFromBuf(RegistryFriendlyByteBuf buf) {
        BitsStash stash = new BitsStash();
        int size = buf.readVarInt();
        for (int i = 0; i < size; i++) {
            BlockInformation info = BlockInformation.STREAM_CODEC.decode(buf);
            long count = buf.readVarLong();
            long modified = buf.readVarLong();
            if (!info.isAir() && count > 0) {
                stash.bits.put(info, count);
                stash.lastModified.put(info, modified);
            }
        }
        return stash;
    }

    public static BitsStash get(ServerPlayer player) {
        return player.getData(ModAttachments.BITS_STASH.get());
    }
}
