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

    /** Grid resolution (cells per block face) for derived stack-height relief. Must divide 32. */
    public enum HeightRes implements TranslatableEnum {
        PER_FACE(1),
        COARSE(8),
        MEDIUM(16),
        PER_PIXEL(32);

        public final int cells;

        HeightRes(int cells) {
            this.cells = cells;
        }

        @Override
        public Component getTranslatedName() {
            return Component.literal(cells + "x" + cells);
        }
    }

    public static class Config {
        // -- Rendering --
        public final ModConfigSpec.IntValue renderDistance;
        public final ModConfigSpec.EnumValue<AtlasSize> atlasSize;

        // -- Relief (derived stack-height extrusion) --
        public final ModConfigSpec.BooleanValue reliefEnabled;
        public final ModConfigSpec.EnumValue<HeightRes> reliefHeightRes;
        public final ModConfigSpec.DoubleValue reliefLayerThickness;
        public final ModConfigSpec.IntValue reliefMaxLayers;

        // -- Editor --
        public final ModConfigSpec.IntValue maxBrushSize;
        public final ModConfigSpec.IntValue undoStackDepth;

        // -- Stamp Preview --
        public final ModConfigSpec.IntValue ghostPreviewOpacity;

        // -- Advanced Rendering --
        public final ModConfigSpec.IntValue frustumCullRadius;

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

            reliefEnabled = builder
                .comment("Render decals as 3D relief: each painted spot is extruded outward by",
                         "the number of stacked layers covering it (derived, nothing stored).")
                .define("reliefEnabled", true);

            reliefHeightRes = builder
                .comment("Grid resolution (cells per block face) for relief height.",
                         "Lower = chunkier relief and far less geometry. Independent of texture resolution.")
                .defineEnum("reliefHeightRes", HeightRes.MEDIUM);

            reliefLayerThickness = builder
                .comment("World-space thickness (in blocks) added per stacked layer. 0.0625 = 1/16 block.")
                .defineInRange("reliefLayerThickness", 0.0625, 0.0, 0.5);

            reliefMaxLayers = builder
                .comment("Maximum stack height (in layers) used for relief, clamps total extrusion.")
                .defineInRange("reliefMaxLayers", 8, 1, 64);

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

            frustumCullRadius = builder
                .comment("Decal chunks whose closest point is within this many blocks of the camera",
                         "are never frustum-culled, even when outside the field of view.",
                         "Useful for stained-glass tinting: nearby windows keep rendering even when",
                         "you look slightly past them.  Chunks beyond this radius are frustum-culled",
                         "normally.  Set to 0 to frustum-cull everything; set to renderDistance to",
                         "disable frustum culling entirely.")
                .defineInRange("frustumCullRadius", 32, 0, 512);

            builder.pop();
        }
    }
}
