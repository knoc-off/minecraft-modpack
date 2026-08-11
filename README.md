# Inkwell

Minecraft 1.21.1, NeoForge 21.1.233.

## Playing (fastest way)

1. Download <https://mc.niko.ink/pack/inkwell.zip>
2. Prism Launcher: "Add Instance" -> "Import from zip" -> select the file.
3. Launch.

Its pre-launch hook runs `packwiz-installer` against <https://mc.niko.ink/pack/pack.toml>, which pulls the mods

Server: `mc.niko.ink`.

## Adding or updating a Modrinth mod

```
cd pack && packwiz mr add <slug>     # or: packwiz update --all
gen-nix-from-packwiz                 # regenerate nix/mods.nix
```

### Updating a local mod

```
scripts/build-mods.sh paint-craft
<remove old and commit new>
```
