# Inkwell

Minecraft 1.21.1, NeoForge 21.1.233.

## Playing (fastest way)

1. Download <https://mc.niko.ink/pack/inkwell.zip>
2. Prism Launcher: "Add Instance" -> "Import from zip" -> select the file.
3. Launch.

The instance ships no mods. Its pre-launch hook runs `packwiz-installer`
against <https://mc.niko.ink/pack/pack.toml>, which pulls the mods on first
launch and then keeps them in sync on every launch after that -- when the pack
changes, you just press play. Everything (mods, installer) comes from
mc.niko.ink; no Modrinth or GitHub account or access is needed.

Server: `mc.niko.ink`.

## Playing (Nix)

If you're on NixOS/nix-darwin/nix with flakes:

```
nix run github:knoc-off/minecraft-modpack#installPrism
```

Installs/updates a Prism Launcher instance from this flake directly. This is a
static install -- it does not self-update; re-run it to pick up changes.

## Development

```
nix develop
```

Drops you into a shell with `packwiz`, Java 21, and Gradle, and prints the
available workflows.

### Adding or updating a Modrinth mod

```
cd pack && packwiz mr add <slug>     # or: packwiz update --all
gen-nix-from-packwiz                 # regenerate nix/mods.nix
```

Commit `pack/` and `nix/mods.nix` together.

### Updating a local mod (asset-shelf, paint-craft, structure-stash)

The jars under `local-mods/` are committed and are the single source of truth
-- there is no release step.

```
scripts/build-mods.sh paint-craft
git add local-mods/paintcraft-<new>.jar
git rm  local-mods/paintcraft-<old>.jar
```

Both in the same commit: two jars with the same mod id in the mods dir is a
hard NeoForge load failure.

These jars are not packwiz-managed and are not in `nix/mods.nix`. The metadata
clients need is synthesised when the pack site is built. The reason they are
committed rather than fetched: the served pack rewrites every download URL to
point at the host serving it, so fetching them over the network would mean
building the pack requires the artifact being built.

## Publishing

```
git push                                    # this repo
# then in the nixos repo:
nix flake update minecraft-modpack
# deploy optiplex AND hetzner from the same lock
```

Both the server's mods and the published pack come from the same pinned input.
Deploying only hetzner publishes mods the server doesn't have, and NeoForge
disconnects clients whose mod set doesn't match during registry sync.

## Layout

| path | what |
| --- | --- |
| `pack/` | packwiz pack -- the 73 Modrinth mods |
| `nix/mods.nix` | generated from `pack/`; Modrinth mods only |
| `local-mods/` | committed jars for the three local mods |
| `packages/` | Gradle sources for the local mods |
| `flake.nix` | `packSite`, `clientMods`, `installPrism`, NixOS server module |
