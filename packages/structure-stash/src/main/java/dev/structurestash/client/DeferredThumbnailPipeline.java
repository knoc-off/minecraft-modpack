package dev.structurestash.client;

import dev.structurestash.StructureStash;
import dev.structurestash.client.StructureThumbnailRenderer.StructureGrid;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages background preparation of structure thumbnails.
 * <p>
 * Usage:
 * <ol>
 *   <li>Call {@link #submit} to start background NBT parsing.</li>
 *   <li>Each frame, call {@link #poll} on the render thread to retrieve completed preparations.</li>
 *   <li>Render the returned {@link StructureGrid} via
 *       {@link StructureThumbnailRenderer#renderGridToImage}.</li>
 * </ol>
 */
@OnlyIn(Dist.CLIENT)
public class DeferredThumbnailPipeline {

    public record PreparedJob(long key, StructureGrid grid) {}

    private final ExecutorService prepThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "thumb-prep");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentLinkedQueue<PreparedJob> readyQueue = new ConcurrentLinkedQueue<>();
    private final Set<Long> inflight = ConcurrentHashMap.newKeySet();
    private volatile int generation;

    /**
     * Submit compressed structure data for background preparation.
     * Duplicate submissions for the same key are ignored.
     *
     * @param key            cache key for deduplication
     * @param compressedData raw compressed NBT bytes (will be defensively copied)
     * @param registries     registry access for codec context (immutable, thread-safe)
     */
    public void submit(long key, byte[] compressedData, HolderLookup.Provider registries) {
        if (!inflight.add(key)) return;
        byte[] copy = compressedData.clone();
        int gen = this.generation;
        prepThread.submit(() -> {
            try {
                if (gen != this.generation) return;
                CompoundTag root = NbtIo.readCompressed(
                    new ByteArrayInputStream(copy), NbtAccounter.unlimitedHeap());
                StructureGrid grid = StructureThumbnailRenderer.prepareGrid(root, registries);
                if (grid != null && gen == this.generation) {
                    readyQueue.add(new PreparedJob(key, grid));
                }
            } catch (Exception e) {
                StructureStash.LOGGER.debug("Background thumb prep failed for key {}: {}",
                    Long.toHexString(key), e.getMessage());
            } finally {
                inflight.remove(key);
            }
        });
    }

    /**
     * Poll one completed preparation. Returns null if none ready.
     * Call on the render thread.
     */
    @Nullable
    public PreparedJob poll() {
        return readyQueue.poll();
    }

    /**
     * Cancel all pending and ready jobs. In-flight background tasks will complete
     * harmlessly — the generation counter prevents stale results from being enqueued.
     */
    public void cancelAll() {
        generation++;
        readyQueue.clear();
        inflight.clear();
    }

    /** Any jobs still in flight or ready to render? */
    public boolean hasPending() {
        return !inflight.isEmpty() || !readyQueue.isEmpty();
    }

    /** Shutdown the background executor (for mod unload). */
    public void shutdown() {
        prepThread.shutdownNow();
    }
}
