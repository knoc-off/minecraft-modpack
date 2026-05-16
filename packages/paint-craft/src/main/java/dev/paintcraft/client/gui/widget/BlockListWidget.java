package dev.paintcraft.client.gui.widget;

import dev.paintcraft.client.color.BlockColorCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class BlockListWidget extends ObjectSelectionList<BlockListWidget.Entry> {

    private final Consumer<Block> onBlockClicked;

    public BlockListWidget(Minecraft mc, int width, int height, int y, Consumer<Block> onBlockClicked) {
        super(mc, width, height, y, 20);
        this.onBlockClicked = onBlockClicked;
    }

    public void setBlocks(List<Block> blocks) {
        clearEntries();
        for (Block block : blocks) {
            addEntry(new Entry(block));
        }
    }

    public void addBlock(Block block) {
        // Avoid duplicates
        for (int i = 0; i < getItemCount(); i++) {
            if (getEntry(i).block == block) return;
        }
        addEntry(new Entry(block));
    }

    public void removeBlock(Block block) {
        for (int i = 0; i < getItemCount(); i++) {
            if (getEntry(i).block == block) {
                removeEntry(getEntry(i));
                return;
            }
        }
    }

    public List<Block> getBlocks() {
        List<Block> result = new ArrayList<>();
        for (int i = 0; i < getItemCount(); i++) {
            result.add(getEntry(i).block);
        }
        return result;
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
            // Block icon
            gfx.renderFakeItem(displayStack, left + 1, top + 1);

            // Block name (truncated)
            String name = block.getName().getString();
            if (name.length() > 12) name = name.substring(0, 11) + "…";
            gfx.drawString(Minecraft.getInstance().font, name, left + 20, top + 5, 0xFFFFFF);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            onBlockClicked.accept(block);
            return true;
        }

        @Override
        public Component getNarration() {
            return block.getName();
        }
    }
}
