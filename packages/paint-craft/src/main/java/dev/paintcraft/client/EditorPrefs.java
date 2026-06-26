package dev.paintcraft.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Persists paint editor preferences to config/paintcraft_editor.json.
 * Survives game restarts and editor session changes.
 */
public final class EditorPrefs {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "config/paintcraft_editor.json";

    public List<String> blocks = List.of();
    public List<Integer> recentColors = List.of();
    public List<Integer> pinnedColors = List.of();
    public int selectedColor = 0xFF000000;
    public boolean softMode = true;
    public int brushSize = 1;
    public int brushAlpha = 255;

    public static EditorPrefs load() {
        Path path = Minecraft.getInstance().gameDirectory.toPath().resolve(FILE_NAME);
        if (!Files.exists(path)) return new EditorPrefs();
        try (Reader r = Files.newBufferedReader(path)) {
            EditorPrefs prefs = GSON.fromJson(r, EditorPrefs.class);
            return prefs != null ? prefs : new EditorPrefs();
        } catch (Exception e) {
            return new EditorPrefs();
        }
    }

    public void save() {
        Path path = Minecraft.getInstance().gameDirectory.toPath().resolve(FILE_NAME);
        try {
            Files.createDirectories(path.getParent());
            try (Writer w = Files.newBufferedWriter(path)) {
                GSON.toJson(this, w);
            }
        } catch (Exception ignored) {
        }
    }

    /** Resolve saved block IDs to Block instances, falling back to defaults if empty/invalid. */
    public List<Block> resolveBlocks(List<Block> defaultBlocks) {
        if (blocks == null || blocks.isEmpty()) return new ArrayList<>(defaultBlocks);
        List<Block> result = new ArrayList<>();
        for (String id : blocks) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl != null && BuiltInRegistries.BLOCK.containsKey(rl)) {
                Block b = BuiltInRegistries.BLOCK.get(rl);
                if (b != Blocks.AIR) result.add(b);
            }
        }
        return result.isEmpty() ? new ArrayList<>(defaultBlocks) : result;
    }

    /** Snapshot current editor state into a saveable prefs object. */
    public static EditorPrefs from(List<Block> blocks, List<Integer> recents,
                                    Collection<Integer> pinned, int selectedColor,
                                    boolean softMode, int brushSize, int brushAlpha) {
        EditorPrefs p = new EditorPrefs();
        p.blocks = blocks.stream()
            .map(b -> BuiltInRegistries.BLOCK.getKey(b).toString())
            .toList();
        p.recentColors = new ArrayList<>(recents);
        p.pinnedColors = new ArrayList<>(pinned);
        p.selectedColor = selectedColor;
        p.softMode = softMode;
        p.brushSize = Math.max(1, Math.min(dev.paintcraft.ModConfig.CONFIG.maxBrushSize.get(), brushSize));
        p.brushAlpha = Math.max(1, Math.min(255, brushAlpha));
        return p;
    }
}
