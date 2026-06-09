package dev.paintcraft.item;

import net.minecraft.world.inventory.tooltip.TooltipComponent;

public record StampTooltipComponent(int widthPx, int heightPx, int[] pixels) implements TooltipComponent {
}
