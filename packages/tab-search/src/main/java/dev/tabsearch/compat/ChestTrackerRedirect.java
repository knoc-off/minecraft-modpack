package dev.tabsearch.compat;

import dev.tabsearch.TabSearch;
import dev.tabsearch.TabSwitchState;
import dev.tabsearch.mixin.accessor.BlockTabAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;

/**
 * Fixes item/position mis-attribution when Inventory Tabs programmatically switches
 * container tabs -- see {@link dev.tabsearch.mixin.TabSwitchTracker} for the capture
 * mechanism and {@link TabSwitchState} for the state it writes to.
 *
 * <p>Registers on Chest Tracker's {@code DefaultProviderScreenClose.EVENT} at
 * {@code PRIORITY_PHASE}, which runs before the built-in handler (registered at
 * {@code FALLBACK_PHASE}) and can preempt it by returning a value result. When no
 * programmatic switch is in flight this always passes through, so normal play is
 * untouched.
 *
 * <p>Chest Tracker runs as a Fabric mod through Sinytra Connector, which remaps its
 * classes to official Mojang mappings only at classload time -- there's no on-disk
 * jar with those post-remap signatures to compile against. This class goes through
 * reflection instead of a compileOnly dependency, so it only ever touches the live,
 * already-remapped classes at runtime.
 */
public final class ChestTrackerRedirect {

    private static Boolean loaded;

    private static Object priorityPhase; // ResourceLocation
    private static Class<?> screenCloseListenerType; // DefaultProviderScreenClose functional interface
    private static Method eventRegister; // Event#register(ResourceLocation, T)
    private static Object eventInstance; // DefaultProviderScreenClose.EVENT
    private static MethodHandle resultPass;
    private static MethodHandle resultValue;
    private static MethodHandle contextGetItems;
    private static MethodHandle contextGetCustomTitle;
    private static MethodHandle locationMemoryKey;
    private static MethodHandle locationIsOverride;
    private static MethodHandle getLocationFor;
    private static MethodHandle getConnected; // static ConnectedBlocksGrabber.getConnected(Level, BlockState, BlockPos)
    private static MethodHandle builderCreate;
    private static MethodHandle builderWithCustomName;
    private static MethodHandle builderInContainer;
    private static MethodHandle builderOtherPositions;
    private static MethodHandle builderToResult;
    private static Class<?> cbsInterface; // ClientBlockSource

    private ChestTrackerRedirect() {}

    public static boolean isLoaded() {
        Boolean c = loaded;
        if (c == null) {
            c = ModList.get() != null && ModList.get().isLoaded("chesttracker") && InventoryTabsCompat.isLoaded() && resolve();
            loaded = c;
        }
        return c;
    }

    private static boolean resolve() {
        // Tracks how far resolution got before any failure, so the warning below names
        // the exact reflective lookup that broke instead of just a generic stack trace.
        java.util.List<String> resolved = new java.util.ArrayList<>();
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();

            Class<?> phasesClass = Class.forName("red.jackf.chesttracker.api.EventPhases");
            priorityPhase = lookup.findStaticGetter(phasesClass, "PRIORITY_PHASE", ResourceLocation.class).invoke();
            resolved.add("EventPhases.PRIORITY_PHASE");

            screenCloseListenerType = Class.forName("red.jackf.chesttracker.api.providers.defaults.DefaultProviderScreenClose");
            Class<?> eventClass = Class.forName("net.fabricmc.fabric.api.event.Event");
            eventInstance = lookup.findStaticGetter(screenCloseListenerType, "EVENT", eventClass).invoke();
            resolved.add("DefaultProviderScreenClose.EVENT");
            // Event<T>#register(ResourceLocation, T) erases to (ResourceLocation, Object) --
            // looking this up against the concrete impl class or with the unerased listener
            // type both fail with NoSuchMethodException.
            eventRegister = eventClass.getMethod("register", ResourceLocation.class, Object.class);
            resolved.add("Event.register");

            Class<?> resultHolderClass = Class.forName("red.jackf.jackfredlib.api.base.ResultHolder");
            resultPass = lookup.findStatic(resultHolderClass, "pass", MethodType.methodType(resultHolderClass));
            resultValue = lookup.findStatic(resultHolderClass, "value", MethodType.methodType(resultHolderClass, Object.class));
            resolved.add("ResultHolder.pass/value");

            Class<?> contextClass = Class.forName("red.jackf.chesttracker.api.providers.context.ScreenCloseContext");
            contextGetItems = lookup.findVirtual(contextClass, "getItems", MethodType.methodType(List.class));
            contextGetCustomTitle = lookup.findVirtual(contextClass, "getCustomTitle", MethodType.methodType(Optional.class));
            resolved.add("ScreenCloseContext.getItems/getCustomTitle");

            Class<?> locationClass = Class.forName("red.jackf.chesttracker.api.providers.MemoryLocation");
            locationMemoryKey = lookup.findVirtual(locationClass, "memoryKey", MethodType.methodType(ResourceLocation.class));
            locationIsOverride = lookup.findVirtual(locationClass, "isOverride", MethodType.methodType(boolean.class));
            resolved.add("MemoryLocation.memoryKey/isOverride");

            cbsInterface = Class.forName("red.jackf.chesttracker.api.ClientBlockSource");
            Class<?> providerUtilsClass = Class.forName("red.jackf.chesttracker.api.providers.ProviderUtils");
            getLocationFor = lookup.findStatic(providerUtilsClass, "getLocationFor", MethodType.methodType(Optional.class, cbsInterface));
            resolved.add("ProviderUtils.getLocationFor");

            Class<?> grabberClass = Class.forName("red.jackf.whereisit.api.search.ConnectedBlocksGrabber");
            getConnected = lookup.findStatic(grabberClass, "getConnected", MethodType.methodType(List.class, Level.class, BlockState.class, BlockPos.class));
            resolved.add("ConnectedBlocksGrabber.getConnected");

            Class<?> builderClass = Class.forName("red.jackf.chesttracker.api.providers.MemoryBuilder");
            builderCreate = lookup.findStatic(builderClass, "create", MethodType.methodType(builderClass, List.class));
            builderWithCustomName = lookup.findVirtual(builderClass, "withCustomName", MethodType.methodType(builderClass, net.minecraft.network.chat.Component.class));
            builderInContainer = lookup.findVirtual(builderClass, "inContainer", MethodType.methodType(builderClass, Block.class));
            builderOtherPositions = lookup.findVirtual(builderClass, "otherPositions", MethodType.methodType(builderClass, List.class));
            builderToResult = lookup.findVirtual(builderClass, "toResult", MethodType.methodType(Class.forName("red.jackf.chesttracker.api.providers.defaults.DefaultProviderScreenClose$Result"), ResourceLocation.class, BlockPos.class));
            resolved.add("MemoryBuilder.*");

            TabSearch.LOGGER.info("Chest Tracker tab-switch fix: resolved all {} reflection targets", resolved.size());
            return true;
        } catch (Throwable t) {
            TabSearch.LOGGER.warn("Chest Tracker tab-switch fix disabled: reflection setup failed after resolving {}", resolved, t);
            return false;
        }
    }

    /** Call once, guarded by {@link #isLoaded()}, after both Chest Tracker and Inventory Tabs are confirmed present. */
    public static void register() {
        try {
            Object listener = Proxy.newProxyInstance(
                ChestTrackerRedirect.class.getClassLoader(),
                new Class<?>[]{screenCloseListenerType},
                ChestTrackerRedirect::createMemory
            );
            eventRegister.invoke(eventInstance, priorityPhase, listener);
            TabSearch.LOGGER.info("Chest Tracker tab-switch fix registered");
        } catch (Throwable t) {
            TabSearch.LOGGER.warn("Failed to register Chest Tracker tab-switch fix", t);
        }
    }

    private static Object createMemory(Object proxy, Method method, Object[] args) throws Throwable {
        // provider = args[0] (ServerProvider), context = args[1] (ScreenCloseContext) -- both untyped here.
        Object context = args[1];

        Object outgoing = TabSwitchState.consumeOutgoingTab();
        if (!(outgoing instanceof BlockTabAccessor blockTab)) {
            // No programmatic switch in flight, or the outgoing tab was an ItemTab (a held
            // item/shulker has no world position to redirect to) -- let the default handler
            // run, which records under whatever lastSource actually is.
            TabSearch.LOGGER.debug("[tabsearch] screen close: no block-tab redirect (outgoing={})",
                outgoing == null ? "none" : outgoing.getClass().getSimpleName());
            return resultPass.invoke();
        }

        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return resultPass.invoke();
        }

        BlockPos pos = blockTab.getPos();
        BlockState state = level.getBlockState(pos);
        Object cbs = cachedBlockSource(level, pos, state);

        @SuppressWarnings("unchecked")
        Optional<Object> location = (Optional<Object>) getLocationFor.invoke(cbs);
        if (location.isEmpty() || (boolean) locationIsOverride.invoke(location.get())) {
            TabSearch.LOGGER.debug("[tabsearch] screen close: redirecting to {} but no memory location found, passing through", pos);
            return resultPass.invoke();
        }

        @SuppressWarnings("unchecked")
        List<BlockPos> connected = (List<BlockPos>) getConnected.invoke(level, state, pos);
        BlockPos rootPos = connected.get(0);
        List<BlockPos> otherPositions = connected.subList(1, connected.size());

        ResourceLocation key = (ResourceLocation) locationMemoryKey.invoke(location.get());

        @SuppressWarnings("unchecked")
        List<ItemStack> items = (List<ItemStack>) contextGetItems.invoke(context);
        @SuppressWarnings("unchecked")
        Optional<net.minecraft.network.chat.Component> customTitle = (Optional<net.minecraft.network.chat.Component>) contextGetCustomTitle.invoke(context);

        Object builder = builderCreate.invoke(items);
        builder = builderWithCustomName.invoke(builder, customTitle.orElse(null));
        builder = builderInContainer.invoke(builder, state.getBlock());
        builder = builderOtherPositions.invoke(builder, otherPositions);
        Object result = builderToResult.invoke(builder, key, rootPos);

        TabSearch.LOGGER.debug("[tabsearch] screen close: redirected {} item(s) from outgoing tab to {}", items.size(), rootPos);
        return resultValue.invoke(result);
    }

    /** Builds a ClientBlockSource proxy for an explicit position, bypassing InteractionTracker.lastSource. */
    private static Object cachedBlockSource(Level level, BlockPos pos, BlockState state) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "x" -> pos.getX();
            case "y" -> pos.getY();
            case "z" -> pos.getZ();
            case "pos" -> pos;
            case "blockState" -> state;
            case "blockEntity" -> level.getBlockEntity(pos);
            case "level" -> level;
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "ClientBlockSource[" + pos + "]";
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return Proxy.newProxyInstance(ChestTrackerRedirect.class.getClassLoader(), new Class<?>[]{cbsInterface}, handler);
    }
}
