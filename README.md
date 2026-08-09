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

## deploy

git tag mods-v0.1.4
git push origin mods-v0.1.4

...
pin-local-mods mods-v0.1.4
git add pack/ nix/mods.nix
git commit -m "pin local mods to mods-v0.1.4"
git push origin main

git tag pack-v0.1.4
git push origin pack-v0.1.4
This triggers pack-release.yml, which refreshes the index and exports/publishes the .mrpack.
A few notes: your --tags flag pushes all local tags at once (fine here since you only have the two new ones, but worth knowing), and step 3 must come after step 2's commit lands on main — otherwise the export runs against the stale pins again, the exact bug we just found. Also mods-v0.1.4 and pack-v0.1.4 sharing the same version number is coincidental/cosmetic (they're independent tag namespaces on this repo) — not a hard requirement, just keep them roughly in sync for sanity.
