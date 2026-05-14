#!/usr/bin/env python3
"""Generate a Minecraft test harness mod for in-game regression testing.

Produces a complete Fabric or NeoForge mod project that:
  - Reads test-suite.json at server startup
  - Runs registry, recipe, tag, config, and custom checks
  - Writes test-results.json with structured pass/fail data
  - Optionally auto-stops the server after tests complete

The mod is generic -- test cases are defined in JSON, not compiled in.
Update test-suite.json without rebuilding the mod.

Usage:
  gen_test_mod.py <loader> <mc_version> <output_dir>

Loader: fabric | neoforge
"""

import json
import os
import sys
from pathlib import Path

MOD_ID = "modpack_test_harness"
MOD_NAME = "Modpack Test Harness"
GROUP = "com.modpacktest"
PACKAGE = "com.modpacktest.harness"

# ---------------------------------------------------------------------------
# Fabric sources
# ---------------------------------------------------------------------------

FABRIC_ENTRYPOINT = '''package {package};

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestHarnessMod implements DedicatedServerModInitializer {{
    public static final String MOD_ID = "{mod_id}";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeServer() {{
        LOGGER.info("[TestHarness] Registered. Will run tests on server start.");

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {{
            LOGGER.info("[TestHarness] Server started. Running test suite...");
            try {{
                TestRunner runner = new TestRunner(server);
                runner.run();
            }} catch (Exception e) {{
                LOGGER.error("[TestHarness] Test runner crashed", e);
            }}
        }});
    }}
}}
'''

FABRIC_MOD_JSON = '''{{
  "schemaVersion": 1,
  "id": "{mod_id}",
  "version": "1.0.0",
  "name": "{mod_name}",
  "description": "In-game regression test harness for modpack validation",
  "authors": ["modpack-skill"],
  "license": "MIT",
  "environment": "server",
  "entrypoints": {{
    "server": ["{package}.TestHarnessMod"]
  }},
  "depends": {{
    "fabricloader": ">=0.15.0",
    "minecraft": "{mc_version_range}",
    "fabric-api": "*"
  }}
}}
'''

FABRIC_BUILD_GRADLE = '''plugins {{
    id("fabric-loom") version "1.9-SNAPSHOT"
}}

version = "1.0.0"
group = "{group}"

repositories {{
    maven("https://maven.fabricmc.net/")
    mavenCentral()
}}

dependencies {{
    minecraft("com.mojang:minecraft:{mc_version}")
    mappings("net.fabricmc:yarn:{mc_version}+build.+:v2")
    modImplementation("net.fabricmc:fabric-loader:{loader_version}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:{fabric_api_version}")
}}

tasks.processResources {{
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {{
        expand("version" to project.version)
    }}
}}
'''

# ---------------------------------------------------------------------------
# NeoForge sources
# ---------------------------------------------------------------------------

NEOFORGE_ENTRYPOINT = '''package {package};

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

@Mod("{mod_id}")
public class TestHarnessMod {{
    private static final Logger LOGGER = LogUtils.getLogger();

    public TestHarnessMod() {{
        LOGGER.info("[TestHarness] Registered. Will run tests on server start.");
        NeoForge.EVENT_BUS.register(this);
    }}

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {{
        LOGGER.info("[TestHarness] Server started. Running test suite...");
        try {{
            TestRunner runner = new TestRunner(event.getServer());
            runner.run();
        }} catch (Exception e) {{
            LOGGER.error("[TestHarness] Test runner crashed", e);
        }}
    }}
}}
'''

NEOFORGE_MODS_TOML = '''modLoader = "javafml"
loaderVersion = "[4,)"
license = "MIT"

[[mods]]
modId = "{mod_id}"
version = "1.0.0"
displayName = "{mod_name}"
description = "In-game regression test harness for modpack validation"

[[dependencies."{mod_id}"]]
modId = "neoforge"
type = "required"
versionRange = "[21.0,)"
ordering = "NONE"
side = "BOTH"

[[dependencies."{mod_id}"]]
modId = "minecraft"
type = "required"
versionRange = "{mc_version_range}"
ordering = "NONE"
side = "BOTH"
'''

NEOFORGE_BUILD_GRADLE = '''plugins {{
    id 'net.neoforged.moddev' version '2.0.+'
}}

version = '1.0.0'
group = '{group}'

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

neoForge {{
    version = "{neoforge_version}"

    runs {{
        server {{
            server()
        }}
    }}

    mods {{
        "{mod_id}" {{
            sourceSet sourceSets.main
        }}
    }}
}}
'''

# ---------------------------------------------------------------------------
# Shared test runner (works on both loaders via Vanilla APIs)
# ---------------------------------------------------------------------------

TEST_RUNNER_JAVA = r'''package {package};

import com.google.gson.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.core.Registry;
import net.minecraft.world.item.crafting.RecipeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public class TestRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger("TestHarness");
    private final MinecraftServer server;
    private final List<TestResult> results = new ArrayList<>();

    public TestRunner(MinecraftServer server) {
        this.server = server;
    }

    public void run() {
        Path suiteFile = server.getServerDirectory().toPath().resolve("test-suite.json");
        if (!Files.exists(suiteFile)) {
            LOGGER.warn("[TestHarness] No test-suite.json found in server directory. Skipping.");
            return;
        }

        JsonObject suite;
        try (Reader reader = Files.newBufferedReader(suiteFile)) {
            suite = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (Exception e) {
            LOGGER.error("[TestHarness] Failed to read test-suite.json", e);
            return;
        }

        LOGGER.info("[TestHarness] Running test suite...");

        if (suite.has("registry_checks")) {
            runRegistryChecks(suite.getAsJsonArray("registry_checks"));
        }
        if (suite.has("recipe_checks")) {
            runRecipeChecks(suite.getAsJsonArray("recipe_checks"));
        }
        if (suite.has("tag_checks")) {
            runTagChecks(suite.getAsJsonArray("tag_checks"));
        }
        if (suite.has("mod_loaded_checks")) {
            runModLoadedChecks(suite.getAsJsonArray("mod_loaded_checks"));
        }
        if (suite.has("command_checks")) {
            runCommandChecks(suite.getAsJsonArray("command_checks"));
        }

        writeResults(suite);

        long passed = results.stream().filter(r -> r.passed).count();
        long failed = results.stream().filter(r -> !r.passed).count();
        LOGGER.info("[TestHarness] ===== RESULTS: {} passed, {} failed, {} total =====",
                passed, failed, results.size());

        for (TestResult r : results) {
            String icon = r.passed ? "PASS" : "FAIL";
            LOGGER.info("[TestHarness] [{}] {} - {}", icon, r.name, r.message);
        }

        boolean autoShutdown = suite.has("auto_shutdown") && suite.get("auto_shutdown").getAsBoolean();
        if (autoShutdown) {
            boolean allPassed = failed == 0;
            LOGGER.info("[TestHarness] Auto-shutdown enabled. All passed: {}. Stopping server.", allPassed);
            server.halt(false);
        }
    }

    // --- Registry checks ---

    private void runRegistryChecks(JsonArray checks) {
        for (JsonElement el : checks) {
            JsonObject check = el.getAsJsonObject();
            String type = check.get("type").getAsString();
            String id = check.get("id").getAsString();
            String expect = check.has("expect") ? check.get("expect").getAsString() : "exists";
            String testName = "registry:" + type + ":" + id;

            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) {
                results.add(new TestResult(testName, false, "Invalid resource location: " + id));
                continue;
            }

            boolean found = false;
            switch (type) {
                case "block":
                    found = BuiltInRegistries.BLOCK.containsKey(rl);
                    break;
                case "item":
                    found = BuiltInRegistries.ITEM.containsKey(rl);
                    break;
                case "entity":
                    found = BuiltInRegistries.ENTITY_TYPE.containsKey(rl);
                    break;
                case "block_entity":
                    found = BuiltInRegistries.BLOCK_ENTITY_TYPE.containsKey(rl);
                    break;
                case "enchantment":
                    // Enchantments moved to data-driven in 1.21+; check via registry
                    try {
                        found = BuiltInRegistries.ENCHANTMENT.containsKey(rl);
                    } catch (Exception e) {
                        found = false; // Registry may not exist on newer versions
                    }
                    break;
                case "particle":
                    found = BuiltInRegistries.PARTICLE_TYPE.containsKey(rl);
                    break;
                case "sound":
                    found = BuiltInRegistries.SOUND_EVENT.containsKey(rl);
                    break;
                default:
                    results.add(new TestResult(testName, false, "Unknown registry type: " + type));
                    continue;
            }

            boolean expectExists = expect.equals("exists");
            boolean passed = found == expectExists;
            String msg = found ? "Found in registry" : "Not in registry";
            if (!expectExists) msg = found ? "Found (expected missing)" : "Correctly absent";
            results.add(new TestResult(testName, passed, msg));
        }
    }

    // --- Recipe checks ---

    private void runRecipeChecks(JsonArray checks) {
        RecipeManager recipeManager = server.getRecipeManager();
        // Get all recipe IDs
        Set<String> recipeIds = new HashSet<>();
        recipeManager.getRecipes().forEach(recipe -> {
            recipeIds.add(recipe.id().location().toString());
        });

        for (JsonElement el : checks) {
            JsonObject check = el.getAsJsonObject();
            String testName;
            boolean passed;
            String msg;

            if (check.has("id")) {
                String id = check.get("id").getAsString();
                testName = "recipe:id:" + id;
                boolean found = recipeIds.contains(id);
                String expect = check.has("expect") ? check.get("expect").getAsString() : "exists";
                passed = found == expect.equals("exists");
                msg = found ? "Recipe found" : "Recipe not found";
            } else if (check.has("output")) {
                String output = check.get("output").getAsString();
                testName = "recipe:output:" + output;
                ResourceLocation outputRl = ResourceLocation.tryParse(output);
                boolean found = recipeManager.getRecipes().stream().anyMatch(recipe -> {
                    try {
                        return recipe.value().getResultItem(server.registryAccess())
                                .getItem().equals(BuiltInRegistries.ITEM.get(outputRl));
                    } catch (Exception e) {
                        return false;
                    }
                });
                String expect = check.has("expect") ? check.get("expect").getAsString() : "exists";
                passed = found == expect.equals("exists");
                msg = found ? "Recipe with output found" : "No recipe produces this item";
            } else {
                testName = "recipe:unknown";
                passed = false;
                msg = "Recipe check needs 'id' or 'output' field";
            }

            results.add(new TestResult(testName, passed, msg));
        }
    }

    // --- Tag checks ---

    @SuppressWarnings("unchecked")
    private void runTagChecks(JsonArray checks) {
        for (JsonElement el : checks) {
            JsonObject check = el.getAsJsonObject();
            String type = check.get("type").getAsString();
            String tag = check.get("tag").getAsString();
            String id = check.get("id").getAsString();
            String expect = check.has("expect") ? check.get("expect").getAsString() : "contains";
            String testName = "tag:" + type + ":" + tag + ":" + id;

            ResourceLocation tagRl = ResourceLocation.tryParse(tag);
            ResourceLocation itemRl = ResourceLocation.tryParse(id);
            if (tagRl == null || itemRl == null) {
                results.add(new TestResult(testName, false, "Invalid resource location"));
                continue;
            }

            boolean found = false;
            switch (type) {
                case "block":
                    var blockTag = TagKey.create(BuiltInRegistries.BLOCK.key(), tagRl);
                    var block = BuiltInRegistries.BLOCK.get(itemRl);
                    if (block != null) {
                        found = block.defaultBlockState().is(blockTag);
                    }
                    break;
                case "item":
                    var itemTag = TagKey.create(BuiltInRegistries.ITEM.key(), tagRl);
                    var item = BuiltInRegistries.ITEM.get(itemRl);
                    if (item != null) {
                        found = item.getDefaultInstance().is(itemTag);
                    }
                    break;
                default:
                    results.add(new TestResult(testName, false, "Tag checks support 'block' and 'item' types"));
                    continue;
            }

            boolean expectContains = expect.equals("contains");
            boolean passed = found == expectContains;
            String msg = found ? "Present in tag" : "Not in tag";
            results.add(new TestResult(testName, passed, msg));
        }
    }

    // --- Mod loaded checks ---

    private void runModLoadedChecks(JsonArray checks) {
        for (JsonElement el : checks) {
            JsonObject check = el.getAsJsonObject();
            String modId = check.get("mod_id").getAsString();
            String testName = "mod_loaded:" + modId;

            // Check via Fabric or NeoForge loader API
            boolean loaded = isModLoaded(modId);
            String expect = check.has("expect") ? check.get("expect").getAsString() : "loaded";
            boolean expectLoaded = expect.equals("loaded");
            boolean passed = loaded == expectLoaded;
            String msg = loaded ? "Mod is loaded" : "Mod is not loaded";
            results.add(new TestResult(testName, passed, msg));
        }
    }

    private boolean isModLoaded(String modId) {
        // Try Fabric loader first
        try {
            Class<?> fabricLoader = Class.forName("net.fabricmc.loader.api.FabricLoader");
            Object instance = fabricLoader.getMethod("getInstance").invoke(null);
            return (boolean) fabricLoader.getMethod("isModLoaded", String.class).invoke(instance, modId);
        } catch (Exception ignored) {}

        // Try NeoForge/Forge
        try {
            Class<?> modList = Class.forName("net.neoforged.fml.ModList");
            Object instance = modList.getMethod("get").invoke(null);
            return (boolean) modList.getMethod("isLoaded", String.class).invoke(instance, modId);
        } catch (Exception ignored) {}

        try {
            Class<?> modList = Class.forName("net.minecraftforge.fml.ModList");
            Object instance = modList.getMethod("get").invoke(null);
            return (boolean) modList.getMethod("isLoaded", String.class).invoke(instance, modId);
        } catch (Exception ignored) {}

        return false;
    }

    // --- Command checks ---

    private void runCommandChecks(JsonArray checks) {
        for (JsonElement el : checks) {
            JsonObject check = el.getAsJsonObject();
            String command = check.get("command").getAsString();
            String testName = "command:" + command;

            try {
                var source = server.createCommandSourceStack();
                int result = server.getCommands().getDispatcher().execute(command, source);
                boolean passed = result >= 0;
                if (check.has("expect_success")) {
                    passed = check.get("expect_success").getAsBoolean() == (result >= 0);
                }
                results.add(new TestResult(testName, passed, "Command returned " + result));
            } catch (Exception e) {
                boolean expectFail = check.has("expect_success") && !check.get("expect_success").getAsBoolean();
                results.add(new TestResult(testName, expectFail,
                        "Command threw: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
    }

    // --- Write results ---

    private void writeResults(JsonObject suite) {
        String filename = suite.has("results_file")
                ? suite.get("results_file").getAsString()
                : "test-results.json";

        JsonObject output = new JsonObject();
        output.addProperty("timestamp", Instant.now().toString());
        output.addProperty("total", results.size());
        output.addProperty("passed", results.stream().filter(r -> r.passed).count());
        output.addProperty("failed", results.stream().filter(r -> !r.passed).count());

        JsonArray resultsArray = new JsonArray();
        for (TestResult r : results) {
            JsonObject obj = new JsonObject();
            obj.addProperty("name", r.name);
            obj.addProperty("passed", r.passed);
            obj.addProperty("message", r.message);
            resultsArray.add(obj);
        }
        output.add("results", resultsArray);

        Path outPath = server.getServerDirectory().toPath().resolve(filename);
        try (Writer writer = Files.newBufferedWriter(outPath)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(output, writer);
            LOGGER.info("[TestHarness] Results written to {}", outPath);
        } catch (Exception e) {
            LOGGER.error("[TestHarness] Failed to write results", e);
        }
    }

    // --- Data class ---

    static class TestResult {
        final String name;
        final boolean passed;
        final String message;

        TestResult(String name, boolean passed, String message) {
            this.name = name;
            this.passed = passed;
            this.message = message;
        }
    }
}
'''

# ---------------------------------------------------------------------------
# Shared files
# ---------------------------------------------------------------------------

SETTINGS_GRADLE = '''pluginManagement {{
    repositories {{
        maven("https://maven.fabricmc.net/")
        maven("https://maven.neoforged.net/releases")
        gradlePluginPortal()
    }}
}}

rootProject.name = "{mod_id}"
'''

GRADLE_PROPERTIES = """org.gradle.jvmargs=-Xmx1G
"""

# ---------------------------------------------------------------------------
# Example test suite
# ---------------------------------------------------------------------------

EXAMPLE_TEST_SUITE = {
    "description": "Modpack regression test suite. Edit this file to add/remove tests. The test harness mod reads it at server startup.",
    "auto_shutdown": True,
    "results_file": "test-results.json",
    "mod_loaded_checks": [
        {"mod_id": "fabric-api", "expect": "loaded", "_comment": "Fabric API must be present"},
    ],
    "registry_checks": [
        {"type": "block", "id": "minecraft:crafting_table", "expect": "exists", "_comment": "Sanity check"},
    ],
    "recipe_checks": [
        {"output": "minecraft:diamond_sword", "expect": "exists", "_comment": "Vanilla recipe sanity check"},
    ],
    "tag_checks": [
        {"type": "block", "tag": "minecraft:mineable/pickaxe", "id": "minecraft:stone", "expect": "contains"},
    ],
    "command_checks": [
        {"command": "seed", "expect_success": True, "_comment": "World is loaded"},
    ],
}


# ---------------------------------------------------------------------------
# Generator
# ---------------------------------------------------------------------------

def mc_version_range(mc_version: str) -> str:
    parts = mc_version.split(".")
    if len(parts) >= 2:
        major_minor = ".".join(parts[:2])
        return f"{major_minor}.x"
    return mc_version


def render_template(template: str, vars: dict) -> str:
    """Replace {placeholder} without tripping on Java curly braces.
    Handles templates written for .format() that use {{ and }} for literal braces."""
    result = template
    for key, value in vars.items():
        result = result.replace("{" + key + "}", str(value))
    result = result.replace("{{", "{").replace("}}", "}")
    return result


def generate_fabric_mod(mc_version: str, output_dir: str):
    src = Path(output_dir) / "src" / "main"
    java_dir = src / "java" / "com" / "modpacktest" / "harness"
    res_dir = src / "resources"

    java_dir.mkdir(parents=True, exist_ok=True)
    res_dir.mkdir(parents=True, exist_ok=True)

    fmt = {
        "package": PACKAGE,
        "mod_id": MOD_ID,
        "mod_name": MOD_NAME,
        "group": GROUP,
        "mc_version": mc_version,
        "mc_version_range": mc_version_range(mc_version),
        "loader_version": "0.16.14",
        "fabric_api_version": "0.90.0+1.20.1",
    }

    write(java_dir / "TestHarnessMod.java", render_template(FABRIC_ENTRYPOINT, fmt))
    write(java_dir / "TestRunner.java", render_template(TEST_RUNNER_JAVA, fmt))
    write(res_dir / "fabric.mod.json", render_template(FABRIC_MOD_JSON, fmt))
    write(Path(output_dir) / "build.gradle.kts", render_template(FABRIC_BUILD_GRADLE, fmt))
    write(Path(output_dir) / "settings.gradle.kts", render_template(SETTINGS_GRADLE, fmt))
    write(Path(output_dir) / "gradle.properties", GRADLE_PROPERTIES)


def generate_neoforge_mod(mc_version: str, output_dir: str):
    src = Path(output_dir) / "src" / "main"
    java_dir = src / "java" / "com" / "modpacktest" / "harness"
    meta_dir = src / "resources" / "META-INF"

    java_dir.mkdir(parents=True, exist_ok=True)
    meta_dir.mkdir(parents=True, exist_ok=True)

    fmt = {
        "package": PACKAGE,
        "mod_id": MOD_ID,
        "mod_name": MOD_NAME,
        "group": GROUP,
        "mc_version": mc_version,
        "mc_version_range": f"[{mc_version},)",
        "neoforge_version": "21.1.172",
    }

    write(java_dir / "TestHarnessMod.java", render_template(NEOFORGE_ENTRYPOINT, fmt))
    write(java_dir / "TestRunner.java", render_template(TEST_RUNNER_JAVA, fmt))
    write(meta_dir / "neoforge.mods.toml", render_template(NEOFORGE_MODS_TOML, fmt))
    write(Path(output_dir) / "build.gradle", render_template(NEOFORGE_BUILD_GRADLE, fmt))
    write(Path(output_dir) / "settings.gradle", render_template(SETTINGS_GRADLE, fmt))
    write(Path(output_dir) / "gradle.properties", GRADLE_PROPERTIES)


def write(path: Path, content: str):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content)


def main():
    if len(sys.argv) < 4:
        print("Usage: gen_test_mod.py <fabric|neoforge> <mc_version> <output_dir>")
        print()
        print("Generates a test harness mod project. Build with:")
        print("  cd <output_dir> && ./gradlew build")
        print()
        print("Place test-suite.json in the server directory before booting.")
        print("The mod reads it on SERVER_STARTED and writes test-results.json.")
        sys.exit(1)

    loader = sys.argv[1].lower()
    mc_version = sys.argv[2]
    output_dir = sys.argv[3]

    if loader == "fabric":
        generate_fabric_mod(mc_version, output_dir)
    elif loader in ("neoforge", "forge"):
        generate_neoforge_mod(mc_version, output_dir)
    else:
        print(f"Unknown loader: {loader}. Use 'fabric' or 'neoforge'.")
        sys.exit(1)

    # Write example test suite
    suite_path = Path(output_dir) / "test-suite.example.json"
    with open(suite_path, "w") as f:
        json.dump(EXAMPLE_TEST_SUITE, f, indent=2)

    print(f"Generated {loader} test harness mod at {output_dir}/")
    print(f"  Entrypoint: {PACKAGE}.TestHarnessMod")
    print(f"  Test runner: {PACKAGE}.TestRunner")
    print(f"  Example suite: {suite_path}")
    print()
    print("Next steps:")
    print(f"  1. cd {output_dir} && ./gradlew build")
    print(f"  2. Copy build/libs/{MOD_ID}-1.0.0.jar into your mods/ folder")
    print(f"  3. Place test-suite.json in the server directory")
    print(f"  4. Boot the server -- tests run automatically")
    print(f"  5. Read test-results.json for structured results")


if __name__ == "__main__":
    main()
