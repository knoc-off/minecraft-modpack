package dev.paintcraft;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.TranslatableEnum;
import org.apache.commons.lang3.tuple.Pair;

public final class ModConfig {

    public static final Config CONFIG;
    public static final ModConfigSpec CONFIG_SPEC;

    static {
        Pair<Config, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Config::new);
        CONFIG = pair.getLeft();
        CONFIG_SPEC = pair.getRight();
    }

    private ModConfig() {}

    public enum AtlasSize implements TranslatableEnum {
        SMALL(1024),
        MEDIUM(2048),
        LARGE(4096),
        EXTRA_LARGE(8192);

        public final int pixels;

        AtlasSize(int pixels) {
            this.pixels = pixels;
        }

        @Override
        public Component getTranslatedName() {
            return Component.literal(pixels + "px");
        }
    }

    public static class Config {
        // -- Rendering --
        public final ModConfigSpec.IntValue renderDistance;
        public final ModConfigSpec.EnumValue<AtlasSize> atlasSize;

        // -- Editor --
        public final ModConfigSpec.IntValue maxBrushSize;
        public final ModConfigSpec.IntValue undoStackDepth;

        // -- Stamp Preview --
        public final ModConfigSpec.IntValue ghostPreviewOpacity;

        // -- Advanced Rendering --
        public final ModConfigSpec.DoubleValue depthBias;

        Config(ModConfigSpec.Builder builder) {
            builder.push("rendering");

            renderDistance = builder
                .comment("Maximum distance (in blocks) at which decals are rendered.",
                         "Decals beyond this distance are skipped during rendering.")
                .defineInRange("renderDistance", 256, 32, 512);

            atlasSize = builder
                .comment("Atlas texture resolution for composited decal cells.",
                         "Higher values use more VRAM but support more simultaneous decals.",
                         "At 32px/cell: SMALL=1K cells, MEDIUM=4K, LARGE=16K, EXTRA_LARGE=65K.",
                         "Requires game restart to take effect.")
                .gameRestart()
                .defineEnum("atlasSize", AtlasSize.LARGE);

            ghostPreviewOpacity = builder
                .comment("Opacity of the stamp placement preview (0=invisible, 255=fully opaque).")
                .defineInRange("ghostPreviewOpacity", 160, 0, 255);

            builder.pop();

            builder.push("editor");

            maxBrushSize = builder
                .comment("Maximum brush size in pixels (controlled by scroll wheel in the editor).")
                .defineInRange("maxBrushSize", 16, 4, 128);

            undoStackDepth = builder
                .comment("Maximum number of undo steps in the paint editor.")
                .defineInRange("undoStackDepth", 50, 10, 200);

            builder.pop();

            builder.push("advanced");

            depthBias = builder
                .comment("Clip-space depth bias applied to decals to prevent Z-fighting with block surfaces.",
                         "This is a constant NDC-z offset applied in the vertex shader, independent of distance.",
                         "No world-space geometry is moved — zero parallax, zero peeking around edges.",
                         "Increase if decals flicker; decrease if they wrongly occlude geometry in front of them.")
                .defineInRange("depthBias", 0.0002, 0.0, 0.01);

            builder.pop();
        }
    }
}
