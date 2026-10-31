# Inkwell

Minecraft 1.21.1, NeoForge 21.1.248.

## Playing (fastest way)

1. copy <https://mc.niko.ink/pack/inkwell.zip>
2. Prism Launcher: "Add Instance" -> "Import" -> paste url
3. Launch.

Its pre-launch hook runs `packwiz-installer` against <https://mc.niko.ink/pack/pack.toml>, which pulls the mods

Server: `mc.niko.ink`.

## Adding or updating a Modrinth mod

```
cd pack && packwiz mr add <slug>     # or: packwiz update --all
gen-nix-from-packwiz                 # regenerate nix/mods.nix
```

## Before every deploy

Test the change against a fresh copy of the live world. On the host:

```
snap=$(ssh root@optiplex 'ls -d /srv/minecraft/.snapshots/* | tail -1')
rsync -a --delete root@optiplex:$snap/inkwell/ ~/workspaces/inkwell-test/srv/
```

Then, in `nix develop`:

```
scripts/test.sh ~/workspaces/inkwell-test/srv --predeploy    # ~10 min, must print PASS
```

`--predeploy` boots the server, loads the busy areas from `test/spots.txt`, sprints one in-game day, and joins a real modded client as every player active in the last 30 days. It fails on a crash, a mixin or registry error, damaged chunks, a player getting disconnected, or any error not already in `test/baseline/summary.txt`. When a new error is expected, copy the run's `summary.txt` over the baseline and commit it.

Each check also runs on its own (`--spots`, `--sprint N`, `--players NAME`, `--crash` for kill -9 mid-save; see the top of `scripts/test.sh`). `scripts/test-diff.sh` compares two runs.

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
