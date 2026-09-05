package dev.tabsearch.compat;

import dev.tabsearch.TabSearch;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Optional;

/**
 * Detection gate and memory lookup bridge for the Chest Tracker integration.
 *
 * <p>Chest Tracker runs as a Fabric mod through Sinytra Connector, which remaps its
 * classes from Fabric intermediary names to official Mojang mappings only at
 * classload time. There is no jar on disk with those post-remap signatures to
 * compile against, so this bridge goes through reflection instead of a compileOnly
 * dependency -- it only ever touches the live, already-remapped classes at runtime,
 * so it works regardless of which Chest Tracker build (official NeoForge port vs.
 * Fabric-via-Connector) happens to be installed.
 *
 * <p>Guard every call other than {@link #isLoaded()} with {@code isLoaded()} first --
 * see EmiCompat for why.
 */
public final class ChestTrackerCompat {

    private static Boolean loaded;
    private static MethodHandle getLoadedBank; // MemoryBankAccess.INSTANCE::getLoaded, bound
    private static MethodHandle getMemory; // MemoryBank#getMemory(Level, BlockPos)
    private static MethodHandle memoryItems; // Memory#items()

    private ChestTrackerCompat() {}

    public static boolean isLoaded() {
        Boolean c = loaded;
        if (c == null) {
            c = ModList.get() != null && ModList.get().isLoaded("chesttracker") && resolve();
            loaded = c;
        }
        return c;
    }

    private static boolean resolve() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();

            Class<?> accessClass = Class.forName("red.jackf.chesttracker.api.memory.MemoryBankAccess");
            Object instance = lookup.findStaticGetter(accessClass, "INSTANCE", accessClass).invoke();
            MethodHandle getLoadedRaw = lookup.findVirtual(accessClass, "getLoaded", MethodType.methodType(Optional.class));
            getLoadedBank = getLoadedRaw.bindTo(instance);

            Class<?> bankClass = Class.forName("red.jackf.chesttracker.api.memory.MemoryBank");
            getMemory = lookup.findVirtual(bankClass, "getMemory", MethodType.methodType(Optional.class, Level.class, BlockPos.class));

            Class<?> memoryClass = Class.forName("red.jackf.chesttracker.api.memory.Memory");
            memoryItems = lookup.findVirtual(memoryClass, "items", MethodType.methodType(List.class));

            return true;
        } catch (Throwable t) {
            TabSearch.LOGGER.warn("Chest Tracker integration disabled: incompatible API (mod version mismatch?)", t);
            return false;
        }
    }

    /**
     * Looks up remembered items for a block-tab position, trying every multiblock
     * position (e.g. both halves of a double chest) and returning the union.
     * Empty when nothing has been remembered for any of these positions -- callers
     * must treat that as "unknown", not "confirmed empty".
     */
    @SuppressWarnings("unchecked")
    public static Optional<List<ItemStack>> memoryItems(Level level, BlockPos pos, List<BlockPos> multiblockPositions) {
        try {
            Optional<Object> bank = (Optional<Object>) getLoadedBank.invoke();
            if (bank.isEmpty()) {
                return Optional.empty();
            }
            Object bankObj = bank.get();
            Optional<Object> memory = (Optional<Object>) getMemory.invoke(bankObj, level, pos);
            if (memory.isEmpty() && multiblockPositions != null) {
                for (BlockPos other : multiblockPositions) {
                    memory = (Optional<Object>) getMemory.invoke(bankObj, level, other);
                    if (memory.isPresent()) break;
                }
            }
            if (memory.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of((List<ItemStack>) memoryItems.invoke(memory.get()));
        } catch (Throwable t) {
            TabSearch.LOGGER.warn("Chest Tracker memory lookup failed", t);
            return Optional.empty();
        }
    }
}
