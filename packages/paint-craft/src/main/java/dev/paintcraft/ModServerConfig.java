package dev.paintcraft;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Server-side config — synced to clients, stored per-world.
 * Controls limits that server admins need to tune.
 */
public final class ModServerConfig {

    public static final Config CONFIG;
    public static final ModConfigSpec CONFIG_SPEC;

    static {
        Pair<Config, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(Config::new);
        CONFIG = pair.getLeft();
        CONFIG_SPEC = pair.getRight();
    }

    private ModServerConfig() {}

    public static class Config {
        public final ModConfigSpec.IntValue maxDecalsPerChunk;
        public final ModConfigSpec.IntValue maxPlacementDistance;
        public final ModConfigSpec.IntValue maxCanvasSize;
        public final ModConfigSpec.DoubleValue maxDepth;
        public final ModConfigSpec.IntValue opPermissionLevel;

        Config(ModConfigSpec.Builder builder) {
            builder.push("limits");

            maxDecalsPerChunk = builder
                .comment("Maximum number of decals allowed per chunk.",
                         "Kept high so decals can be stacked freely; lower it only if server",
                         "storage or network overhead becomes a concern.")
                .defineInRange("maxDecalsPerChunk", 1024, 8, 16384);

            maxPlacementDistance = builder
                .comment("Maximum distance (in blocks) a player can be from a decal to place, edit, or erase it.")
                .defineInRange("maxPlacementDistance", 64, 8, 256);

            maxCanvasSize = builder
                .comment("Maximum canvas dimension in blocks (width and height).",
                         "A value of 16 allows up to 16x16 block canvases (256x256 pixels).")
                .defineInRange("maxCanvasSize", 16, 1, 32);

            maxDepth = builder
                .comment("Maximum projection depth in blocks.",
                         "Controls how far decals can wrap into recessed geometry.")
                .defineInRange("maxDepth", 3.0, 1.0, 16.0);

            opPermissionLevel = builder
                .comment("Server operator permission level required to bypass decal ownership checks.",
                         "Players at or above this level can edit/delete any decal regardless of author.")
                .defineInRange("opPermissionLevel", 2, 1, 4);

            builder.pop();
        }
    }
}
