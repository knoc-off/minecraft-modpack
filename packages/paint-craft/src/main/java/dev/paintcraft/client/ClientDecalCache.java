package dev.paintcraft.client;

import dev.paintcraft.core.Decal;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ClientDecalCache {

    private static final Map<UUID, Decal> cache = new ConcurrentHashMap<>();

    private ClientDecalCache() {}

    public static void put(Decal decal) {
        cache.put(decal.id(), decal);
    }

    public static void remove(UUID id) {
        cache.remove(id);
        ClientDecalResolver.remove(id);
    }

    public static Decal get(UUID id) {
        return cache.get(id);
    }

    public static Collection<Decal> all() {
        return cache.values();
    }

    public static void clear() {
        cache.clear();
        ClientDecalResolver.clear();
    }
}
