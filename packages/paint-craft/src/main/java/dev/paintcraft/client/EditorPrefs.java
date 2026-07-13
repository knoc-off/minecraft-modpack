package dev.paintcraft.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.paintcraft.ModConfig;
import dev.paintcraft.client.gui.EditorAction;
import dev.paintcraft.client.gui.EditorSettings;
import dev.paintcraft.client.gui.PaintTool;
import dev.paintcraft.client.gui.PaintScreen.ToolSettings;
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
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    // Tool state: mouse bindings + per-tool [size, opacity].
    public String mouse1Tool = "BRUSH";
    public String mouse2Tool = "ERASER";
    public Map<String, int[]> tools;

    // Editor UX settings.
    public boolean invertScroll = false;
    public boolean eyedropperInheritOpacity = true;
    public String scrollOpacityModifier = "ALT";
    public boolean unifiedSize = true;
    public boolean unifiedOpacity = false;
    public Map<String, Integer> keybinds;

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

    /** Parse a saved tool name, falling back to {@code def} for missing/invalid values. */
    public PaintTool resolveTool(String name, PaintTool def) {
        if (name == null) return def;
        try {
            return PaintTool.valueOf(name);
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    /** Merge saved per-tool settings into {@code target}, clamping to valid ranges. */
    public void applyTools(EnumMap<PaintTool, ToolSettings> target) {
        if (tools == null) return;
        int maxSize = ModConfig.CONFIG.maxBrushSize.get();
        for (Map.Entry<String, int[]> e : tools.entrySet()) {
            ToolSettings s = target.get(resolveTool(e.getKey(), null));
            int[] v = e.getValue();
            if (s == null || v == null || v.length < 2) continue; // tolerate legacy length-3 arrays
            s.size = Math.max(1, Math.min(maxSize, v[0]));
            s.opacity = Math.max(1, Math.min(255, v[1]));
        }
    }

    /** Merge saved editor UX settings (scroll/eyedropper/keybinds) into {@code target}. */
    public void applySettings(EditorSettings target) {
        target.invertScroll = invertScroll;
        target.eyedropperInheritOpacity = eyedropperInheritOpacity;
        target.unifiedSize = unifiedSize;
        target.unifiedOpacity = unifiedOpacity;
        if (scrollOpacityModifier != null) {
            try {
                target.scrollOpacityModifier =
                    EditorSettings.ScrollModifier.valueOf(scrollOpacityModifier);
            } catch (IllegalArgumentException ignored) {
                // Unknown value from an older/newer config — keep the default.
            }
        }
        if (keybinds != null) {
            for (Map.Entry<String, Integer> e : keybinds.entrySet()) {
                try {
                    if (e.getValue() != null) {
                        target.keybinds.put(EditorAction.valueOf(e.getKey()), e.getValue());
                    }
                } catch (IllegalArgumentException ignored) {
                    // Unknown action name from an older/newer config — skip.
                }
            }
        }
    }

    /** Snapshot current editor state into a saveable prefs object. */
    public static EditorPrefs from(List<Block> blocks, List<Integer> recents,
                                    Collection<Integer> pinned, int selectedColor,
                                    boolean softMode, PaintTool mouse1Tool, PaintTool mouse2Tool,
                                    EnumMap<PaintTool, ToolSettings> settings, EditorSettings editorSettings) {
        EditorPrefs p = new EditorPrefs();
        p.blocks = blocks.stream()
            .map(b -> BuiltInRegistries.BLOCK.getKey(b).toString())
            .toList();
        p.recentColors = new ArrayList<>(recents);
        p.pinnedColors = new ArrayList<>(pinned);
        p.selectedColor = selectedColor;
        p.softMode = softMode;
        p.mouse1Tool = mouse1Tool.name();
        p.mouse2Tool = mouse2Tool.name();
        p.tools = new LinkedHashMap<>();
        for (Map.Entry<PaintTool, ToolSettings> e : settings.entrySet()) {
            ToolSettings s = e.getValue();
            p.tools.put(e.getKey().name(), new int[] { s.size, s.opacity });
        }
        p.invertScroll = editorSettings.invertScroll;
        p.eyedropperInheritOpacity = editorSettings.eyedropperInheritOpacity;
        p.scrollOpacityModifier = editorSettings.scrollOpacityModifier.name();
        p.unifiedSize = editorSettings.unifiedSize;
        p.unifiedOpacity = editorSettings.unifiedOpacity;
        p.keybinds = new LinkedHashMap<>();
        for (Map.Entry<EditorAction, Integer> e : editorSettings.keybinds.entrySet()) {
            p.keybinds.put(e.getKey().name(), e.getValue());
        }
        return p;
    }
}
