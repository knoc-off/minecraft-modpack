package dev.paintcraft.client;

import dev.paintcraft.ModItems;
import dev.paintcraft.PaintCraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent;

/**
 * Client-side mod bus event handlers.
 * Handles events that fire on the MOD event bus (registration events).
 */
@EventBusSubscriber(modid = PaintCraft.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ClientModEvents {

    private ClientModEvents() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(IrisCompat::tryRegister);
    }

    @SubscribeEvent
    public static void onRegisterItemDecorations(RegisterItemDecorationsEvent event) {
        event.register(ModItems.STAMP.get(), new StampItemDecorator());
    }
}
