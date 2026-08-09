# bricks-building-extended

Minecraft 1.21.1, NeoForge 21.1.233.

## Playing (fastest way)

1. Grab the latest `.mrpack` from the [Releases page](https://github.com/knoc-off/minecraft-modpack/releases) (look for a `pack-v*` tag).
2. Import it:
   - **Prism Launcher**: "Add Instance" → "Import" → select the `.mrpack` file.
   - **Modrinth App**: drag-and-drop the `.mrpack` onto the launcher, or "Import" → "Modrinth pack".
3. Launch — mods download automatically on first run.

## Playing (Nix)

If you're on NixOS/nix-darwin/nix with flakes:

```
nix run github:knoc-off/minecraft-modpack#installPrism
```

Installs/updates a Prism Launcher instance from this flake directly.

## Development

```
nix develop
```

Drops you into a shell with `packwiz`, Java 21, and Gradle, and prints the available workflows (add/update mods, cut mod/pack releases, build/export).
