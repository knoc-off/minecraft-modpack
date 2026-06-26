package dev.structurestash.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.structurestash.StructureStash;
import dev.structurestash.client.gui.BitsStashScreen;
import dev.structurestash.compat.CnBCompat;
import dev.structurestash.item.BlueprintItem;
import dev.structurestash.item.BlueprintWandItem;
import dev.structurestash.item.ModDataComponents;
import dev.structurestash.item.ModItems;
import dev.structurestash.network.ConfirmBlueprintPlacePayload;
import dev.structurestash.network.StashRequestPayload;
import dev.structurestash.network.WandClickAirPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import net.neoforged.api.distmarker.Dist;

import java.util.Arrays;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = StructureStash.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public class BitsClientEvents {

    public static final KeyMapping OPEN_STASH = new KeyMapping(
        "key.structurestash.open_stash",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_B,
        "key.categories.structurestash"
    );

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) return;

        // Always drain the keybind so presses don't queue up, but only open the
        // bits stash when Chisels & Bits is present — the stash stores C&B bits,
        // so it has nothing to show without the mod.
        if (OPEN_STASH.consumeClick() && CnBCompat.isLoaded()) {
            PacketDistributor.sendToServer(new StashRequestPayload());
            mc.setScreen(new BitsStashScreen());
        }
    }

    /**
     * Intercept right-click when holding the wand and looking at air.
     * Computes the air block position at reach distance and sends it to the server.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onWandAirClick(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem()) return;

        var mc = Minecraft.getInstance();
        if (mc.player == null) return;

        ItemStack held = mc.player.getMainHandItem();
        if (!(held.getItem() instanceof BlueprintWandItem)) {
            held = mc.player.getOffhandItem();
            if (!(held.getItem() instanceof BlueprintWandItem)) return;
        }

        HitResult hit = mc.hitResult;
        if (hit != null && hit.getType() != HitResult.Type.MISS) return;

        event.setCanceled(true);
        event.setSwingHand(false);

        BlockPos airPos = WandSelectionRenderer.computeTargetPos(mc.player);
        PacketDistributor.sendToServer(new WandClickAirPayload(airPos));
    }

    /**
     * Intercept right-click when in confirm mode and holding the same blueprint.
     * Fires BEFORE useOn — cancelling prevents useOn from running (no ghost jump)
     * and works regardless of whether the player is looking at a block or air.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!BlueprintGhostRenderer.isConfirming()) return;
        if (!event.isUseItem()) return;

        var mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Check if holding the same blueprint (main or offhand)
        byte[] data = getBlueprintData(mc.player.getMainHandItem());
        if (data == null) data = getBlueprintData(mc.player.getOffhandItem());
        if (data == null || !Arrays.equals(data, BlueprintGhostRenderer.getLockedData())) return;

        // This right-click is a confirm attempt — cancel so useOn never fires
        event.setCanceled(true);
        event.setSwingHand(false);

        if (BlueprintGhostRenderer.isBlocked()) {
            mc.player.displayClientMessage(
                Component.literal("Placement blocked!").withStyle(ChatFormatting.RED), true);
            return;
        }

        // Confirm placement
        PacketDistributor.sendToServer(new ConfirmBlueprintPlacePayload(
            BlueprintGhostRenderer.getLockedAnchor(),
            BlueprintGhostRenderer.getLockedRotation().ordinal()));
        BlueprintGhostRenderer.cancelConfirm();
    }

    private static byte[] getBlueprintData(ItemStack stack) {
        if (!(stack.getItem() instanceof BlueprintItem)) return null;
        var bd = stack.get(ModDataComponents.BLUEPRINT_DATA.get());
        return bd != null ? bd.data() : null;
    }

    /**
     * Intercept Esc key (which opens PauseScreen) to cancel blueprint confirm mode instead.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (BlueprintGhostRenderer.isConfirming()
                && event.getNewScreen() instanceof PauseScreen) {
            event.setCanceled(true);
            BlueprintGhostRenderer.cancelConfirm();
        }
    }

    /**
     * Render HUD prompt when in blueprint confirm mode.
     */
    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!BlueprintGhostRenderer.isConfirming()) return;

        var mc = Minecraft.getInstance();
        GuiGraphics gfx = event.getGuiGraphics();
        var font = mc.font;
        int screenW = gfx.guiWidth();
        int y = gfx.guiHeight() - 59;

        Component line;
        if (BlueprintGhostRenderer.isBlocked()) {
            line = Component.literal("")
                .append(Component.literal("\u26A0 Blocked").withStyle(ChatFormatting.RED))
                .append(Component.literal("  \u2022  ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("[Esc]").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" Cancel").withStyle(ChatFormatting.GRAY));
        } else {
            line = Component.literal("")
                .append(Component.literal("[Right-click]").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" Place  \u2022  ").withStyle(ChatFormatting.GOLD))
                .append(Component.literal("[Esc]").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" Cancel").withStyle(ChatFormatting.GOLD));
        }

        int textW = font.width(line);
        int cx = screenW / 2;
        gfx.fill(cx - textW / 2 - 4, y - 2, cx + textW / 2 + 4, y + 11, 0x90000000);
        gfx.drawCenteredString(font, line, cx, y, 0xFFFFFFFF);
    }

    @EventBusSubscriber(modid = StructureStash.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static class ModBusEvents {
        @SubscribeEvent
        public static void registerKeys(RegisterKeyMappingsEvent event) {
            event.register(OPEN_STASH);
        }

        @SubscribeEvent
        public static void registerItemDecorations(RegisterItemDecorationsEvent event) {
            event.register(ModItems.BLUEPRINT.get(), new BlueprintItemDecorator());
        }
    }
}
