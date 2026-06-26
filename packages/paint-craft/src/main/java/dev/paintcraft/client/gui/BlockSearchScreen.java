package dev.paintcraft.client.gui;

import dev.paintcraft.client.color.BlockColorExtractor;
import dev.paintcraft.client.gui.widget.BlockSearchListWidget;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public class BlockSearchScreen extends Screen {

    // All valid paintable blocks, built once and reused. Sorted alphabetically.
    // This is safe to cache statically because block models are loaded once per game session
    // and do not change between screens; resource pack reloads create a new Minecraft instance.
    private static List<Block> allValidBlocks = null;

    private final Screen parent;
    private final Consumer<Block> onBlockSelected;
    private final Set<Block> alreadyAdded;

    private EditBox searchBox;
    private BlockSearchListWidget resultList;

    // Current filtered subset shown in resultList
    private final List<Block> filtered = new ArrayList<>();
    // Persists the search string across re-inits (e.g. window resize)
    private String lastQuery = "";

    public BlockSearchScreen(Screen parent, Consumer<Block> onBlockSelected, Set<Block> alreadyAdded) {
        super(Component.literal("Add Block"));
        this.parent = parent;
        this.onBlockSelected = onBlockSelected;
        this.alreadyAdded = alreadyAdded;
    }

    @Override
    protected void init() {
        ensureBlockList();

        int listX = 10;
        int listY = 40;
        int listW = this.width - 20;
        // Leave room for search box (14+18=32), list, then Done button (24px)
        int listH = this.height - 70;

        searchBox = new EditBox(this.font, listX, 14, listW, 18, Component.literal("Search blocks..."));
        searchBox.setMaxLength(64);
        searchBox.setHint(Component.literal("Search blocks..."));
        searchBox.setResponder(this::onSearchChanged);
        // Restore search text across re-inits (e.g. window resize)
        if (!lastQuery.isEmpty()) searchBox.setValue(lastQuery);
        addRenderableWidget(searchBox);

        resultList = new BlockSearchListWidget(this.minecraft, listW, listH, listY,
                                               this::onEntryClicked, alreadyAdded);
        addRenderableWidget(resultList);

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
            .bounds(this.width / 2 - 40, this.height - 24, 80, 16).build());

        // Apply whatever filter was active (handles re-init from resize)
        applyFilter(lastQuery);
    }

    private void onSearchChanged(String query) {
        lastQuery = query.trim().toLowerCase();
        applyFilter(lastQuery);
    }

    private void applyFilter(String query) {
        filtered.clear();
        for (Block block : allValidBlocks) {
            String name = block.getName().getString().toLowerCase();
            String key = BuiltInRegistries.BLOCK.getKey(block).getPath();
            if (query.isEmpty() || name.contains(query) || key.contains(query)) {
                filtered.add(block);
            }
        }
        resultList.setBlocks(filtered);
    }

    private void onEntryClicked(Block block) {
        // Add the block to the parent palette immediately and mark it in the local set so
        // the entry dims right away -- keeps the screen open for further picks.
        onBlockSelected.accept(block);
        alreadyAdded.add(block);
        // No setBlocks call needed: alreadyAdded is the same reference the widget holds,
        // so the next render will see the updated set automatically.
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    protected void renderBlurredBackground(float partialTick) {
        // Skip vanilla menu blur; renderMenuBackground() still draws the dark tint.
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        renderBackground(gfx, mouseX, mouseY, partialTick);
        gfx.drawString(this.font, "Add Block to Palette", 10, 4, 0xFFFFFF);
        super.render(gfx, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * Build the static all-valid-blocks list on first open. Iterates the full block registry
     * and uses BlockColorExtractor.hasValidTexture to exclude air, missing-texture, and
     * non-paintable blocks. Cost is one model lookup per block -- fast since models are baked.
     */
    private static void ensureBlockList() {
        if (allValidBlocks != null) return;
        allValidBlocks = BuiltInRegistries.BLOCK.stream()
            .filter(BlockColorExtractor::hasValidTexture)
            .sorted(Comparator.comparing(b -> b.getName().getString()))
            .collect(java.util.stream.Collectors.toList());
    }
}
