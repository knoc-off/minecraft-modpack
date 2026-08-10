# bricks-building-extended -- Minecraft modpack
#
# NeoForge 1.21.1. Mods come from two places: the 73 Modrinth-hosted ones are
# packwiz-managed (pack/mods/*.pw.toml -> nix/mods.nix), and the three local
# ones (asset-shelf, paint-craft, structure-stash) are committed jars under
# local-mods/. See the "Local mods" comment below for why they differ.
#
# QUICK START:
#   nix run .#installPrism     -- install/update Prism Launcher instance (client)
#   nix build .#clientMods     -- flat dir of all mod JARs (client/singleplayer)
#   nix build .#packSite       -- self-hosted packwiz remote (served by hetzner)
#   nix develop                -- dev shell with packwiz + tools
#
# Server is deployed via nixosModules.default (nix-minecraft).
{
  description = "bricks-building-extended -- Minecraft modpack";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    nix-minecraft.url = "github:Infinidoge/nix-minecraft";
  };

  outputs = { self, nixpkgs, nix-minecraft, ... }:
  let
    system = "x86_64-linux";
    pkgs = import nixpkgs {
      inherit system;
      overlays = [ nix-minecraft.overlay ];
      config.allowUnfree = true;
    };
    lib = pkgs.lib;

    # ── Pack metadata ──────────────────────────────────────────
    packMeta = {
      name = "bricks-building-extended";
      mcVersion = "1.21.1";
      loader = "neoforge";
      loaderVersion = "21.1.233";
    };

    mcVersionUnderscore = builtins.replaceStrings ["."] ["_"] packMeta.mcVersion;

    # ── Server package ─────────────────────────────────────────
    serverPackage = pkgs.neoforgeServers."neoforge-${mcVersionUnderscore}";

    # ── Mods ───────────────────────────────────────────────────
    # Each entry is { side = "both"|"client"|"server"; dest = "mods"|
    # "shaderpacks"|...; jar = <drv>; }. Modrinth-hosted only -- see the local
    # mods note below.
    # Regenerate after packwiz changes:
    #   gen-nix-from-packwiz   (in `nix develop`)
    mods = import ./nix/mods.nix { inherit pkgs; };

    # Directories packwiz sorts downloads into, by Modrinth project type.
    # Must match RESOURCE_DIRS in scripts/gen_nix_from_packwiz.py.
    resourceDirs = [ "mods" "shaderpacks" "resourcepacks" ];


    # ── Local mods (asset-shelf, paint-craft, structure-stash) ──
    # The committed jars in ./local-mods are the single source of truth. They
    # are NOT packwiz-managed and NOT in nix/mods.nix; gen_nix_from_packwiz.py
    # skips any mod id discovered under packages/*/, and the metadata clients
    # need is synthesised into the served pack by mkPackSite below.
    #
    # Why they are not fetched over the network like every other mod: the
    # served pack rewrites every download URL to point at the host serving it,
    # so a fetchurl for these would make building the pack require the very
    # artifact being built. Committing the jars (~500 KB total) breaks that
    # cycle and makes a flake rev fully determine the pack.
    #
    # To update one: scripts/build-mods.sh <mod>, then commit the new jar.
    # Delete the superseded jar in the same commit -- two versions of the same
    # mod id in the mods dir is a hard NeoForge load failure.
    localModsPath = ./local-mods;
    localModJarFiles = if builtins.pathExists localModsPath
      then lib.naturalSort (builtins.filter (f: lib.hasSuffix ".jar" f)
             (builtins.attrNames (builtins.readDir localModsPath)))
      else [];

    # entries whose `side` is in the given list and whose `dest` matches
    # → list of jar derivations
    jarsFor = sides: dest:
      lib.mapAttrsToList (_: m: m.jar)
        (lib.filterAttrs (_: m: m.dest == dest && builtins.elem m.side sides) mods);

    serverModJars = jarsFor [ "both" "server" ] "mods";

    localModJars =
      map (f: pkgs.runCommand f {} ''
        cp ${localModsPath + "/${f}"} $out
      '')
      localModJarFiles;

    # Tree shaped like .minecraft/: mods/, shaderpacks/, ... A flat farm would
    # do for jars alone, but a shaderpack only loads from shaderpacks/.
    # Local mods are mods, so they are only ever added to that dir.
    clientResourcesDir = pkgs.linkFarm "client-resources" (
      lib.concatMap (dest:
        map (jar: { name = "${dest}/${jar.name}"; path = jar; })
          (jarsFor [ "both" "client" "server" ] dest
            ++ lib.optionals (dest == "mods") localModJars)
      ) resourceDirs
    );

    # Flat: nix-minecraft symlinks this straight in as the server's mods/.
    serverModsDir = pkgs.linkFarmFromDrvs "server-mods" (serverModJars ++ localModJars);

    # ── Pack overlay files ─────────────────────────────────────
    # Plain files packwiz indexes out of pack/ and installs into .minecraft/:
    # config/ defaults and the multiplayer server list. Marked `preserve` in
    # pack/index.toml, so packwiz-installer writes them only when absent and
    # never overwrites a player's edits.
    packOverlayPaths = [ "config" "servers.dat" ];

    # ── Server properties ──────────────────────────────────────
    prodServerProperties = {
      enable-rcon = true;
      "rcon.port" = 25575;
      "rcon.password" = "testpass";
      server-port = 25565;
      online-mode = true;
      white-list = true;
      max-players = 20;
      difficulty = "normal";
      gamemode = "creative";
      motd = "${packMeta.name} server";
    };

    # ── JVM flags ──────────────────────────────────────────────
    jvmOpts = "-Xms3G -Xmx6G -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200";

    # ── packwiz-installer-bootstrap ────────────────────────────
    # Served alongside the pack so clients never need github.com either.
    packwizInstallerBootstrap = pkgs.fetchurl {
      url = "https://github.com/packwiz/packwiz-installer-bootstrap/releases/download/v0.0.3/packwiz-installer-bootstrap.jar";
      hash = "sha256-qPuyTcYEJ46X9GiOgtPZGjGLmO/AjV2/y8vKtkQ9EWw=";
    };

    # ── Self-hosted packwiz remote ─────────────────────────────
    # Builds a complete, standalone packwiz remote whose every download URL
    # points back at `baseUrl`. Clients (via packwiz-installer) then talk to
    # exactly one host -- no Modrinth, no GitHub -- and get incremental
    # updates instead of re-downloading the whole pack on every mod bump.
    #
    # The committed pack/ keeps its upstream Modrinth URLs on purpose: those
    # feed nix/mods.nix, and rewriting them in place would mean a build could
    # only fetch its mods from the server that is serving the build's output.
    # The rewrite therefore happens here, at build time, and only in $out.
    #
    # The three local mods have no committed .pw.toml at all (see the local
    # mods note above), so their metadata is synthesised here from the jars.
    mkPackSite = { baseUrl }:
      let
        # Strip trailing slash so ${baseUrl}/jars/... can't produce "//".
        base = lib.removeSuffix "/" baseUrl;

        # side is "both" for all local mods today; they are gameplay content
        # that has to exist on both ends to avoid a registry desync.
        localMetafile = f:
          let
            jar = localModsPath + "/${f}";
            modId = lib.head (builtins.match "(.+)-[0-9][0-9.]*\\.jar" f);
          in pkgs.writeText "${modId}.pw.toml" ''
            name = "${modId}"
            filename = "${f}"
            side = "both"

            [download]
            url = "${base}/jars/${f}"
            hash-format = "sha256"
            hash = "${builtins.hashFile "sha256" jar}"
          '';
      in
      pkgs.runCommand "${packMeta.name}-pack-site"
        {
          nativeBuildInputs = [ pkgs.packwiz pkgs.python3 pkgs.zip ];
        }
        ''
          # packwiz writes a settings file on first run
          export HOME="$(mktemp -d)"

          # The pack is assembled in a scratch dir that contains *only* the
          # packwiz files. `packwiz refresh` indexes every file under the pack
          # root, so if the jars were already in place it would list them as
          # plain pack files -- and clients would then fetch each mod twice,
          # once as an indexed file and once via its metafile. The jars are
          # copied in only after the index is final.
          work="$(mktemp -d)"
          cp -r ${./pack}/. "$work/"
          chmod -R u+w "$work"

          # Synthesised metadata for the committed local mods. install(1)
          # rather than cp: it sets the mode on write, so these land writable
          # despite coming straight out of the store.
          ${lib.concatMapStringsSep "\n" (f: ''
            install -m644 ${localMetafile f} "$work/mods/${lib.head (builtins.match "(.+)-[0-9][0-9.]*\\.jar" f)}.pw.toml"
          '') localModJarFiles}

          # Repoint every download at this host. Only the url line changes --
          # the hashes still describe the same bytes, so they stay valid.
          python3 - "$work" ${lib.escapeShellArgs resourceDirs} <<'PYEOF'
          import os, re, sys
          out = sys.argv[1]
          base = "${base}"
          for dest in sys.argv[2:]:
              destdir = os.path.join(out, dest)
              if not os.path.isdir(destdir):
                  continue
              for name in sorted(os.listdir(destdir)):
                  if not name.endswith(".pw.toml"):
                      continue
                  path = os.path.join(destdir, name)
                  src = open(path).read()
                  fn = re.search(r'^filename\s*=\s*"([^"]+)"', src, re.M)
                  if not fn:
                      raise SystemExit(f"{dest}/{name}: no filename field")
                  jar = fn.group(1)
                  new, n = re.subn(
                      r'^(\s*)url\s*=\s*"[^"]*"',
                      lambda m: f'{m.group(1)}url = "{base}/jars/{jar}"',
                      src, count=1, flags=re.M)
                  if n != 1:
                      raise SystemExit(f"{dest}/{name}: no url field to rewrite")
                  open(path, "w").write(new)
          PYEOF

          # Rebuild index.toml + pack.toml's index hash: the metafiles above
          # changed, and packwiz verifies both on the client.
          ( cd "$work" && packwiz --yes refresh )

          mkdir -p $out/jars
          cp -r "$work"/. $out/

          # Everything the pack references -- mod jars and shaderpack zips
          # alike -- served from one flat directory. Filenames already carry
          # versions, so a flat directory cannot collide.
          for f in ${clientResourcesDir}/*/*; do
            cp -L "$f" "$out/jars/"
          done

          # Fail loudly rather than serve a pack whose metafiles point at files
          # that aren't here -- a client would only find out mid-install.
          python3 - "$out" ${lib.escapeShellArgs resourceDirs} <<'PYEOF'
          import os, re, sys
          out = sys.argv[1]
          missing = []
          for dest in sys.argv[2:]:
              destdir = os.path.join(out, dest)
              if not os.path.isdir(destdir):
                  continue
              for name in sorted(os.listdir(destdir)):
                  if not name.endswith(".pw.toml"):
                      continue
                  src = open(os.path.join(destdir, name)).read()
                  jar = re.search(r'^filename\s*=\s*"([^"]+)"', src, re.M).group(1)
                  if not os.path.exists(os.path.join(out, "jars", jar)):
                      missing.append(f"{dest}/{jar}")
          if missing:
              raise SystemExit("files referenced but not served: " + ", ".join(missing))
          PYEOF

          cp ${packwizInstallerBootstrap} $out/packwiz-installer-bootstrap.jar

          # Importable Prism instance. Deliberately ships no jars -- the
          # pre-launch hook pulls them from this same host on first launch and
          # keeps them current on every launch after that.
          instdir=$(mktemp -d)
          cp -r ${prismInstanceThin}/. "$instdir/"
          chmod -R u+w "$instdir"
          ( cd "$instdir" && zip -qr $out/${packMeta.name}.zip . )
        '';

    # Thin Prism instance: no mods, just the loader definition plus the
    # packwiz-installer pre-launch hook that populates and updates them.
    mkPrismInstanceThin = { baseUrl }:
      pkgs.runCommand "${packMeta.name}-prism-thin" {} ''
        INST="$out/${packMeta.name}"
        mkdir -p "$INST/.minecraft"

        cat > "$INST/instance.cfg" <<'EOF'
        InstanceType=OneSix
        name=${packMeta.name}
        OverrideCommands=true
        PreLaunchCommand="$INST_JAVA" -jar packwiz-installer-bootstrap.jar ${lib.removeSuffix "/" baseUrl}/pack.toml
        EOF

        cat > "$INST/mmc-pack.json" <<'MMCEOF'
        ${builtins.toJSON {
          formatVersion = 1;
          components = [
            { cachedName = "Minecraft"; uid = "net.minecraft"; version = packMeta.mcVersion; }
            {
              cachedName = "NeoForge";
              uid = "net.neoforged";
              version = packMeta.loaderVersion;
            }
          ];
        }}
        MMCEOF

        cp ${packwizInstallerBootstrap} "$INST/.minecraft/packwiz-installer-bootstrap.jar"
      '';

    # Where the pack is published. Bump both hosts together when this changes.
    packBaseUrl = "https://mc.niko.ink/pack";
    prismInstanceThin = mkPrismInstanceThin { baseUrl = packBaseUrl; };
    packSite = mkPackSite { baseUrl = packBaseUrl; };

    # ── Prism Launcher instance (client) ───────────────────────
    prismInstance = pkgs.runCommand "${packMeta.name}-prism" {} ''
      INST="$out/${packMeta.name}"
      mkdir -p "$INST/.minecraft/mods" "$INST/.minecraft/config"

      cat > "$INST/instance.cfg" <<EOF
      InstanceType=OneSix
      name=${packMeta.name}
      EOF

      cat > "$INST/mmc-pack.json" <<'MMCEOF'
      ${builtins.toJSON {
        formatVersion = 1;
        components = [
          { cachedName = "Minecraft"; uid = "net.minecraft"; version = packMeta.mcVersion; }
          {
            cachedName = "NeoForge";
            uid = "net.neoforged";
            version = packMeta.loaderVersion;
          }
        ];
      }}
      MMCEOF

      for jar in ${clientResourcesDir}/mods/*; do
        ln -s "$jar" "$INST/.minecraft/mods/"
      done

      ${lib.concatMapStringsSep "\n" (dest: ''
        if [ -d ${clientResourcesDir}/${dest} ]; then
          mkdir -p "$INST/.minecraft/${dest}"
          ln -s ${clientResourcesDir}/${dest}/* "$INST/.minecraft/${dest}/"
        fi
      '') (lib.remove "mods" resourceDirs)}

      ${lib.concatMapStringsSep "\n" (p: ''
        cp -r ${./pack}/${p} "$INST/.minecraft/"
      '') packOverlayPaths}
    '';

    # ── Client resources (real files, shaped like .minecraft/) ─
    clientResources = pkgs.runCommand "client-resources" {} ''
      mkdir -p $out
      cp -rL ${clientResourcesDir}/. $out/
    '';

    # ── Install to Prism Launcher ──────────────────────────────
    installPrism = pkgs.writeShellScriptBin "${packMeta.name}-install-prism" ''
      set -euo pipefail
      PRISM_DIR="''${1:-''${XDG_DATA_HOME:-$HOME/.local/share}/PrismLauncher/instances}"
      DEST="$PRISM_DIR/${packMeta.name}"

      if [ -d "$DEST" ]; then
        echo "Instance exists at $DEST -- updating mods..."
        # mods/ is replaced wholesale: a leftover jar from a previous revision
        # is a hard NeoForge load failure, so nothing may survive here.
        rm -rf "$DEST/.minecraft/mods"
        mkdir -p "$DEST/.minecraft/mods"
        cp -L ${clientResourcesDir}/mods/* "$DEST/.minecraft/mods/"
        chmod -R u+w "$DEST/.minecraft/mods"

        # shaderpacks/ and resourcepacks/ are merged instead: players put
        # their own there and losing them on every update would be rude.
        ${lib.concatMapStringsSep "\n" (dest: ''
          if [ -d ${clientResourcesDir}/${dest} ]; then
            mkdir -p "$DEST/.minecraft/${dest}"
            cp -L ${clientResourcesDir}/${dest}/* "$DEST/.minecraft/${dest}/"
            chmod -R u+w "$DEST/.minecraft/${dest}"
          fi
        '') (lib.remove "mods" resourceDirs)}
      else
        echo "Creating new instance at $DEST"
        cp -rL --no-preserve=mode,ownership ${prismInstance}/${packMeta.name} "$DEST"
        chmod -R u+w "$DEST"
      fi
      echo "Done -- $(ls "$DEST/.minecraft/mods/" | wc -l) mods installed."
    '';

    # ── Full export bundle (client/singleplayer) ───────────────
    export = pkgs.runCommand "${packMeta.name}-export" {
      nativeBuildInputs = [ pkgs.gnutar pkgs.gzip ];
    } ''
      ROOT="$out/${packMeta.name}"
      mkdir -p "$ROOT"

      cp -rL ${clientResourcesDir}/. "$ROOT/"

      ${lib.concatMapStringsSep "\n" (p: ''
        cp -r ${./pack}/${p} "$ROOT/"
      '') packOverlayPaths}
      chmod -R u+w "$ROOT"

      cd "$out" && tar czf "${packMeta.name}.tar.gz" "${packMeta.name}/"
    '';

  in {
    # ── Packages ───────────────────────────────────────────────
    packages.${system} = {
      inherit prismInstance prismInstanceThin clientResources installPrism export packSite;
      default = clientResources;
    };

    # Build a pack site for a different host:
    #   inputs.minecraft-modpack.lib.mkPackSite { baseUrl = "https://..."; }
    lib = { inherit mkPackSite mkPrismInstanceThin; };

    # ── NixOS module (server, nix-minecraft) ───────────────────
    nixosModules.default = { ... }: {
      imports = [ nix-minecraft.nixosModules.minecraft-servers ];

      services.minecraft-servers = {
        enable = true;
        eula = true;

        servers.${packMeta.name} = {
          enable = true;
          package = serverPackage;
          jvmOpts = jvmOpts;

          symlinks = {
            "mods" = serverModsDir;
          };

          # `files`, not `symlinks`: these mods rewrite their own config on
          # startup, which a read-only store symlink cannot absorb. Copies are
          # re-applied on every service start, so the file here stays
          # authoritative and a hand-edit on the box is transient.
          files = {
            "config/DistantHorizons.toml" = ./server-config/DistantHorizons.toml;
          };

          serverProperties = prodServerProperties;
        };
      };
    };

    # ── Dev shell ──────────────────────────────────────────────
    devShells.${system}.default = pkgs.mkShell {
      packages = [
        pkgs.packwiz
        pkgs.python3
        pkgs.jdk21
        pkgs.gradle
        pkgs.jq
        pkgs.curl
        (pkgs.writeShellScriptBin "gen-nix-from-packwiz" ''
          exec ${pkgs.python3}/bin/python3 ${./scripts/gen_nix_from_packwiz.py} "$@"
        '')
      ];

      shellHook = ''
        echo ""
        echo "  ${packMeta.name} -- MC ${packMeta.mcVersion} + NeoForge"
        echo ""
        echo "  Workflow:"
        echo "    cd pack && packwiz mr add <slug>   add a mod"
        echo "    cd pack && packwiz github add <owner/repo> --regex <name>   add a GitHub-released mod"
        echo "    packwiz update --all               update all mods"
        echo "    gen-nix-from-packwiz               regenerate nix/mods.nix"
        echo ""
        echo "  Local mods (asset-shelf, paint-craft, structure-stash):"
        echo "    scripts/build-mods.sh <mod>         build + install locally"
        echo "    git add local-mods/<new>.jar && git rm local-mods/<old>.jar"
        echo "                                        the committed jar IS the release"
        echo ""
        echo "  Build/Run:"
        echo "    nix run .#installPrism             install to Prism Launcher"
        echo "    nix build .#clientMods             flat dir of mod JARs"
        echo "    nix build .#packSite               self-hosted packwiz remote"
        echo "    nix build .#export                 distributable tarball"
        echo "    server: import nixosModules.default on a NixOS host"
        echo ""
        echo "  Publishing (served at https://mc.niko.ink/pack):"
        echo "    commit + push, then in the nixos repo:"
        echo "      nix flake update minecraft-modpack"
        echo "      deploy optiplex AND hetzner from the same lock"
        echo ""
      '';
    };
  };
}
