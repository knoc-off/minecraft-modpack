# Inkwell -- Minecraft modpack, NeoForge 1.21.1.
#
# QUICK START:
#   nix run .#installPrism     -- install/update Prism Launcher instance (client)
#   nix build .#clientMods     -- flat dir of all mod JARs (client/singleplayer)
#   nix build .#packSite       -- self-hosted packwiz remote (served by hetzner)
#   nix develop                -- dev shell with packwiz + tools
#
# Server is deployed via nixosModules.default (nix-minecraft).
{
  description = "Inkwell -- Minecraft modpack";

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

    # pack/pack.toml is the single source of truth for name/versions -- both
    # packwiz (which reads pack.toml directly) and this flake need to agree,
    # and duplicating them here invited exactly the drift that broke JEI
    # (pack.toml said 21.1.233, nix-minecraft's cached server was even
    # further behind at 21.1.228).
    packToml = builtins.fromTOML (builtins.readFile ./pack/pack.toml);

    packMeta = {
      name = packToml.name;
      mcVersion = packToml.versions.minecraft;
      loader = "neoforge";
      loaderVersion = packToml.versions.neoforge;
    };

    mcVersionUnderscore = builtins.replaceStrings ["."] ["_"] packMeta.mcVersion;
    loaderVersionUnderscore = builtins.replaceStrings ["."] ["_"] packMeta.loaderVersion;

    packIcon = ./assets/inkwell.png;

    # Minecraft's server list ping requires exactly 64x64.
    serverIcon = pkgs.runCommand "server-icon.png" {
      nativeBuildInputs = [ pkgs.imagemagick ];
    } ''
      magick ${packIcon} -resize 64x64! $out
    '';

    # Pinned to the exact build, not the unversioned "neoforge-1_21_1" alias
    # -- that alias tracks whatever nix-minecraft considers latest for the
    # MC version, which silently drifted behind packMeta.loaderVersion
    # before (server ran 21.1.228 while the client pack claimed 21.1.233).
    serverPackage = pkgs.neoforgeServers."neoforge-${mcVersionUnderscore}-${loaderVersionUnderscore}";

    # Each mod entry: { side = "both"|"client"|"server"; dest = "mods"|
    # "shaderpacks"|...; jar = <drv>; }. Modrinth-hosted only -- local mods
    # are handled separately below.
    # Regenerate after packwiz changes: gen-nix-from-packwiz (in `nix develop`)
    mods = import ./nix/mods.nix { inherit pkgs; };

    # Must match RESOURCE_DIRS in scripts/gen_nix_from_packwiz.py.
    resourceDirs = [ "mods" "shaderpacks" "resourcepacks" ];

    # Local mods (asset-shelf, paint-craft, structure-stash): committed jars
    # under ./local-mods are the source of truth, not packwiz-managed, and
    # not in nix/mods.nix -- gen_nix_from_packwiz.py skips anything under
    # packages/*/. Their pack metadata is synthesised in mkPackSite instead.
    #
    # They're committed rather than fetched because the served pack rewrites
    # every download URL to point at itself, which would make building the
    # pack depend on the artifact it's building.
    #
    # To update one: scripts/build-mods.sh <mod>, commit the new jar, and
    # delete the old one in the same commit -- two versions of one mod id is
    # a hard NeoForge load failure.
    localModsPath = ./local-mods;
    localModJarFiles = if builtins.pathExists localModsPath
      then lib.naturalSort (builtins.filter (f: lib.hasSuffix ".jar" f)
             (builtins.attrNames (builtins.readDir localModsPath)))
      else [];

    jarsFor = sides: dest:
      lib.mapAttrsToList (_: m: m.jar)
        (lib.filterAttrs (_: m: m.dest == dest && builtins.elem m.side sides) mods);

    serverModJars = jarsFor [ "both" "server" ] "mods";

    localModJars =
      map (f: pkgs.runCommand f {} ''
        cp ${localModsPath + "/${f}"} $out
      '')
      localModJarFiles;

    # Shaped like .minecraft/ (mods/, shaderpacks/, ...), not flat --
    # shaderpacks only load from shaderpacks/.
    clientResourcesDir = pkgs.linkFarm "client-resources" (
      lib.concatMap (dest:
        map (jar: { name = "${dest}/${jar.name}"; path = jar; })
          (jarsFor [ "both" "client" "server" ] dest
            ++ lib.optionals (dest == "mods") localModJars)
      ) resourceDirs
    );

    # Flat: nix-minecraft symlinks this straight in as the server's mods/.
    serverModsDir = pkgs.linkFarmFromDrvs "server-mods" (serverModJars ++ localModJars);

    # config/ defaults and the multiplayer server list, indexed by packwiz
    # out of pack/. Marked `preserve` in pack/index.toml so packwiz-installer
    # writes them only when absent, never overwriting a player's edits.
    # options.txt ships only the resource pack selection -- Minecraft fills in
    # every other key with its default on first launch and rewrites the file
    # whole on exit. It is listed here (rather than shipped as a mod config)
    # because enabling a resourcepack is a client *selection*, not mod state:
    # dropping the zip into resourcepacks/ leaves it inactive until listed.
    # Overlays land on fresh instances only -- installPrism's update branch
    # deliberately skips them -- so an existing instance keeps its own options.
    packOverlayPaths = [ "config" "servers.dat" "options.txt" ];

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

    jvmOpts = "-Xms3G -Xmx6G -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200";

    # Served alongside the pack so clients never need github.com either.
    packwizInstallerBootstrap = pkgs.fetchurl {
      url = "https://github.com/packwiz/packwiz-installer-bootstrap/releases/download/v0.0.3/packwiz-installer-bootstrap.jar";
      hash = "sha256-qPuyTcYEJ46X9GiOgtPZGjGLmO/AjV2/y8vKtkQ9EWw=";
    };

    # Builds a complete, standalone packwiz remote with every download URL
    # rewritten to `baseUrl`, so clients only ever talk to one host and get
    # incremental updates instead of the whole pack on every mod bump.
    #
    # pack/ itself keeps its upstream Modrinth URLs -- those feed
    # nix/mods.nix, so the rewrite happens here, at build time, into $out
    # only. Local mods have no committed .pw.toml (see above); their
    # metadata is synthesised here from the jars.
    mkPackSite = { baseUrl }:
      let
        base = lib.removeSuffix "/" baseUrl;

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
          export HOME="$(mktemp -d)"

          # Assembled in a scratch dir with only packwiz files: `packwiz
          # refresh` indexes everything under the pack root, so jars must be
          # added after the index is final or clients would double-fetch.
          work="$(mktemp -d)"
          cp -r ${./pack}/. "$work/"
          chmod -R u+w "$work"

          # install(1) sets the mode on write, so these land writable
          # despite coming straight out of the store.
          ${lib.concatMapStringsSep "\n" (f: ''
            install -m644 ${localMetafile f} "$work/mods/${lib.head (builtins.match "(.+)-[0-9][0-9.]*\\.jar" f)}.pw.toml"
          '') localModJarFiles}

          # Repoint every download URL at this host; hashes stay valid since
          # the bytes don't change.
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

          # index.toml + pack.toml's index hash must be rebuilt: the
          # metafiles above changed and packwiz verifies both on the client.
          ( cd "$work" && packwiz --yes refresh )

          mkdir -p $out/jars
          cp -r "$work"/. $out/

          # Flat: filenames already carry versions, so nothing can collide.
          for f in ${clientResourcesDir}/*/*; do
            cp -L "$f" "$out/jars/"
          done

          # Fail loudly here rather than serve a pack whose metafiles point
          # at files that aren't here -- a client would find out mid-install.
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

          # Ships no jars -- the pre-launch hook pulls them from this same
          # host on first launch and keeps them current after that.
          instdir=$(mktemp -d)
          cp -r ${prismInstanceThin}/. "$instdir/"
          chmod -R u+w "$instdir"
          ( cd "$instdir" && zip -qr $out/${packMeta.name}.zip . )
        '';

    # No mods, just the loader definition plus the packwiz-installer
    # pre-launch hook that populates and updates them.
    mkPrismInstanceThin = { baseUrl }:
      pkgs.runCommand "${packMeta.name}-prism-thin" {} ''
        INST="$out/${packMeta.name}"
        mkdir -p "$INST/.minecraft"

        cat > "$INST/instance.cfg" <<'EOF'
        InstanceType=OneSix
        name=${packMeta.name}
        iconKey=${packMeta.name}
        OverrideCommands=true
        PreLaunchCommand="$INST_JAVA" -jar packwiz-installer-bootstrap.jar ${lib.removeSuffix "/" baseUrl}/pack.toml
        EOF

        cp ${packIcon} "$INST/${packMeta.name}.png"

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

    # Bump both hosts together when this changes.
    packBaseUrl = "https://mc.niko.ink/pack";
    prismInstanceThin = mkPrismInstanceThin { baseUrl = packBaseUrl; };
    packSite = mkPackSite { baseUrl = packBaseUrl; };

    # Full Prism instance with mods already in place (vs. prismInstanceThin,
    # which fetches them on first launch).
    prismInstance = pkgs.runCommand "${packMeta.name}-prism" {} ''
      INST="$out/${packMeta.name}"
      mkdir -p "$INST/.minecraft/mods" "$INST/.minecraft/config"

      cat > "$INST/instance.cfg" <<EOF
      InstanceType=OneSix
      name=${packMeta.name}
      iconKey=${packMeta.name}
      EOF

      cp ${packIcon} "$INST/${packMeta.name}.png"

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

    clientResources = pkgs.runCommand "client-resources" {} ''
      mkdir -p $out
      cp -rL ${clientResourcesDir}/. $out/
    '';

    installPrism = pkgs.writeShellScriptBin "${packMeta.name}-install-prism" ''
      set -euo pipefail
      PRISM_DIR="''${1:-''${XDG_DATA_HOME:-$HOME/.local/share}/PrismLauncher/instances}"
      DEST="$PRISM_DIR/${packMeta.name}"

      # Icon has to be registered in Prism's global icon list -- a file in
      # the instance folder alone isn't picked up outside the GUI's zip
      # import flow, which this script doesn't go through.
      ICONS_DIR="$(dirname "$PRISM_DIR")/icons"
      mkdir -p "$ICONS_DIR"
      cp ${packIcon} "$ICONS_DIR/${packMeta.name}.png"

      if [ -d "$DEST" ]; then
        echo "Instance exists at $DEST -- updating mods..."

        # Replaced wholesale, same as mods/ below: this file is generated
        # (loader + MC version only), so there's no user state to preserve
        # -- Prism's own additions to it (LWJGL, cachedRequires, ...) are
        # resolved data it regenerates on next launch. Without this, a
        # loader bump never reaches an existing instance and surfaces later
        # as an opaque mod-load crash: JEI 19.44.0.401 wants
        # neoforge>=21.1.238 against an instance still pinned to 21.1.233.
        # instance.cfg is deliberately left alone -- that one does hold user
        # settings (window size, java args, pre-launch hook).
        cp -L ${prismInstance}/${packMeta.name}/mmc-pack.json "$DEST/mmc-pack.json"
        chmod u+w "$DEST/mmc-pack.json"

        # Replaced wholesale: a leftover jar from a previous revision is a
        # hard NeoForge load failure.
        rm -rf "$DEST/.minecraft/mods"
        mkdir -p "$DEST/.minecraft/mods"
        cp -L ${clientResourcesDir}/mods/* "$DEST/.minecraft/mods/"
        chmod -R u+w "$DEST/.minecraft/mods"

        # Merged instead of replaced: players keep their own shaderpacks
        # and resourcepacks across updates.
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
    packages.${system} = {
      inherit prismInstance prismInstanceThin clientResources installPrism export packSite;
      default = clientResources;
    };

    # Build a pack site for a different host:
    #   inputs.minecraft-modpack.lib.mkPackSite { baseUrl = "https://..."; }
    lib = { inherit mkPackSite mkPrismInstanceThin; };

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
            "server-icon.png" = serverIcon;
          };

          # `files`, not `symlinks`: these mods rewrite their own config at
          # startup, which a read-only store symlink can't absorb.
          #
          # `builtins.path` is required: a bare ./path here is already
          # inside the flake's own store source, so it interpolates without
          # store-path context and Nix never records it as a dependency --
          # the file then silently never reaches the target machine.
          files = {
            "config/DistantHorizons.toml" = builtins.path {
              path = ./server-config/DistantHorizons.toml;
              name = "DistantHorizons.toml";
            };
          };

          serverProperties = prodServerProperties;
        };
      };
    };

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
