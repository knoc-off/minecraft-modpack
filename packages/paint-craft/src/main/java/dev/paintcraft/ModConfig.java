package dev.paintcraft;

import net.neoforged.neoforge.common.ModConfigSpec;
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

    public static class Config {
        public final ModConfigSpec.BooleanValue autoSaveOnExit;
        public final ModConfigSpec.IntValue renderDistance;

        Config(ModConfigSpec.Builder builder) {
            builder.push("general");

            autoSaveOnExit = builder
                .comment("Automatically save the canvas when closing the editor with Escape")
                .define("autoSaveOnExit", true);

            renderDistance = builder
                .comment("Maximum distance (in blocks) at which decals are rendered")
                .defineInRange("renderDistance", 256, 64, 512);

            builder.pop();
        }
    }
}
