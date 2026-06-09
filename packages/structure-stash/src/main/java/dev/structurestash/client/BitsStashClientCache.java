package dev.structurestash.client;

import dev.structurestash.stash.BitsStash;
import mod.chiselsandbits.api.blockinformation.BlockInformation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client-side cached copy of the player's bits stash.
 * Updated via StashSyncPayload from the server.
 */
public final class BitsStashClientCache {

    private static Map<BlockInformation, Long> cached = new LinkedHashMap<>();
    private static Map<BlockInformation, Long> cachedModified = new LinkedHashMap<>();
    private static volatile boolean dirty = false;

    private BitsStashClientCache() {}

    public static void receive(BitsStash stash) {
        cached = new LinkedHashMap<>(stash.getAll());
        cachedModified = new LinkedHashMap<>(stash.getAllLastModified());
        dirty = true;
    }

    public static long getCount(BlockInformation info) {
        return cached.getOrDefault(info, 0L);
    }

    public static long getLastModified(BlockInformation info) {
        return cachedModified.getOrDefault(info, 0L);
    }

    public static Map<BlockInformation, Long> getAll() {
        return Collections.unmodifiableMap(cached);
    }

    public static Map<BlockInformation, Long> getAllLastModified() {
        return Collections.unmodifiableMap(cachedModified);
    }

    public static boolean isEmpty() {
        return cached.isEmpty();
    }

    public static void clear() {
        cached.clear();
        cachedModified.clear();
        dirty = true;
    }

    public static boolean isDirtyAndClear() {
        if (dirty) {
            dirty = false;
            return true;
        }
        return false;
    }
}
