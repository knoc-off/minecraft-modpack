package dev.paintcraft.client.gui.widget;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Scrollable block list for the search popup. Renders already-added blocks as dimmed
 * and non-clickable so the user can see what's already in their palette.
 */
public class BlockSearchListWidget extends ObjectSelectionList<BlockSearchListWidget.Entry> {

    private final Consumer<Block> onBlockSelected;
    private Set<Block> alreadyAdded;

    public BlockSearchListWidget(Minecraft mc, int width, int height, int y,
                                 Consumer<Block> onBlockSelected, Set<Block> alreadyAdded) {
        super(mc, width, height, y, 20);
        this.onBlockSelected = onBlockSelected;
        this.alreadyAdded = alreadyAdded;
    }

    public void setBlocks(List<Block> blocks) {
        clearEntries();
        for (Block block : blocks) {
            addEntry(new Entry(block));
        }
    }

    /** Update which blocks are considered already-added (call after the list changes). */
    public void setAlreadyAdded(Set<Block> alreadyAdded) {
        this.alreadyAdded = alreadyAdded;
    }

    @Override
    public int getRowWidth() {
        return this.width - 12;
    }

    @Override
    protected int getScrollbarPosition() {
        return this.getX() + this.width - 6;
    }

    public class Entry extends ObjectSelectionList.Entry<Entry> {
        private final Block block;
        private final ItemStack displayStack;

        public Entry(Block block) {
            this.block = block;
            this.displayStack = new ItemStack(block);
        }

        @Override
        public void render(GuiGraphics gfx, int index, int top, int left, int width, int height,
                           int mouseX, int mouseY, boolean hovering, float partialTick) {
            boolean added = alreadyAdded.contains(block);
            int nameColor = added ? 0x666666 : 0xFFFFFF;

            // Block icon -- dim it with a dark overlay when already added
            gfx.renderFakeItem(displayStack, left + 1, top + 1);
            if (added) {
                gfx.fill(left + 1, top + 1, left + 17, top + 17, 0x88000000);
            }

            // Block name
            String name = block.getName().getString();
            if (name.length() > 14) name = name.substring(0, 13) + "...";
            gfx.drawString(Minecraft.getInstance().font, name, left + 20, top + 5, nameColor);

            // "added" badge
            if (added) {
                gfx.drawString(Minecraft.getInstance().font, "(added)", left + width - 44, top + 5, 0x555599);
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0) return false;
            if (alreadyAdded.contains(block)) return false; // non-clickable when already added
            onBlockSelected.accept(block);
            return true;
        }

        @Override
        public Component getNarration() {
            return alreadyAdded.contains(block)
                ? Component.literal(block.getName().getString() + " (already added)")
                : block.getName();
        }
    }
}
