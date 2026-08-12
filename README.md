# Inkwell

Minecraft 1.21.1, NeoForge 21.1.248.

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

## note

I am open to re-licencing under a more permissive licence in the future.
this was just done to get it over with for now.

## License

Licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE).

You may use, modify, and share this work freely for any noncommercial purpose.
Redistributing it for commercial gain — selling it, bundling it into a paid
product or paid modpack, or otherwise monetising it — requires a separate
commercial license. Contact <selby@niko.ink>.

This covers the mods and tooling authored here. It grants no rights over
third-party mods referenced by `pack/`, over Flywheel, or over Minecraft and
NeoForge themselves. See [NOTICE](NOTICE) for the full scope.
