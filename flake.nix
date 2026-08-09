# bricks-building-extended -- Minecraft modpack
#
# NeoForge 1.21.1 — all mods (including the local ones: asset-shelf,
# paint-craft, structure-stash) are packwiz-managed; see pack/mods/*.pw.toml
# and nix/mods.nix.
#
# QUICK START:
#   nix run .#installPrism     -- install/update Prism Launcher instance (client)
#   nix build .#clientMods     -- flat dir of all mod JARs (client/singleplayer)
#   nix build .#export         -- distributable tarball
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
    # Each entry is { side = "both"|"client"|"server"; jar = <drv>; }.
    # Regenerate after packwiz changes:
    #   gen-nix-from-packwiz   (in `nix develop`)
    mods = import ./nix/mods.nix { inherit pkgs; };

    # mods whose `side` is in the given list → list of jar derivations
    jarsForSides = sides:
      lib.mapAttrsToList (_: m: m.jar)
        (lib.filterAttrs (_: m: builtins.elem m.side sides) mods);

    allModJars    = jarsForSides [ "both" "client" "server" ];
    serverModJars = jarsForSides [ "both" "server" ];

    # ── Local mods (asset-shelf, paint-craft, structure-stash) ──
    # Pinned via packwiz like any other mod (pack/mods/*.pw.toml, added with
    # `packwiz github add`, tracking GitHub Release assets) — see `mods`
    # above. For local dev iteration before a release exists, or while
    # testing an unreleased change, scripts/build-mods.sh drops a jar into
    # ./local-mods/ (gitignored); any jar there whose filename isn't already
    # pinned via packwiz is picked up here as a fallback/override.
    pinnedJarNames = lib.mapAttrsToList (_: m: m.jar.name) mods;

    localModsPath = ./local-mods;
    localModsDirJars = if builtins.pathExists localModsPath
      then builtins.filter (f: lib.hasSuffix ".jar" f)
             (builtins.attrNames (builtins.readDir localModsPath))
      else [];

    localModJars =
      map (f: pkgs.runCommand f {} ''
        cp ${localModsPath + "/${f}"} $out
      '')
      (builtins.filter (f: !(lib.elem f pinnedJarNames)) localModsDirJars);

    # Full set (client / singleplayer) and server-safe subset.
    modsDir       = pkgs.linkFarmFromDrvs "mods" (allModJars ++ localModJars);
    serverModsDir = pkgs.linkFarmFromDrvs "server-mods" (serverModJars ++ localModJars);

    # ── Configs (optional) ─────────────────────────────────────
    configsPath = ./configs;
    hasConfigs = builtins.pathExists configsPath;

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

      for jar in ${modsDir}/*; do
        ln -s "$jar" "$INST/.minecraft/mods/"
      done

      ${lib.optionalString hasConfigs ''
        cp -r ${configsPath}/* "$INST/.minecraft/config/"
      ''}
    '';

    # ── Client mods (flat dir of real JARs) ────────────────────
    clientMods = pkgs.runCommand "client-mods" {} ''
      mkdir -p $out
      for jar in ${modsDir}/*; do
        cp -L "$jar" "$out/"
      done
    '';

    # ── Install to Prism Launcher ──────────────────────────────
    installPrism = pkgs.writeShellScriptBin "${packMeta.name}-install-prism" ''
      set -euo pipefail
      PRISM_DIR="''${1:-''${XDG_DATA_HOME:-$HOME/.local/share}/PrismLauncher/instances}"
      DEST="$PRISM_DIR/${packMeta.name}"

      if [ -d "$DEST" ]; then
        echo "Instance exists at $DEST — updating mods..."
        rm -rf "$DEST/.minecraft/mods"
        mkdir -p "$DEST/.minecraft/mods"
        cp -L ${modsDir}/* "$DEST/.minecraft/mods/"
        chmod -R u+w "$DEST/.minecraft/mods"
      else
        echo "Creating new instance at $DEST"
        cp -rL --no-preserve=mode,ownership ${prismInstance}/${packMeta.name} "$DEST"
        chmod -R u+w "$DEST"
      fi
      echo "Done — $(ls "$DEST/.minecraft/mods/" | wc -l) mods installed."
    '';

    # ── Full export bundle (client/singleplayer) ───────────────
    export = pkgs.runCommand "${packMeta.name}-export" {
      nativeBuildInputs = [ pkgs.gnutar pkgs.gzip ];
    } ''
      ROOT="$out/${packMeta.name}"
      mkdir -p "$ROOT"/{mods,config}

      for jar in ${modsDir}/*; do
        cp "$jar" "$ROOT/mods/"
      done

      ${lib.optionalString hasConfigs ''
        cp -r ${configsPath}/* "$ROOT/config/"
      ''}

      cd "$out" && tar czf "${packMeta.name}.tar.gz" "${packMeta.name}/"
    '';

  in {
    # ── Packages ───────────────────────────────────────────────
    packages.${system} = {
      inherit prismInstance clientMods installPrism export;
      default = clientMods;
    };

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
          } // lib.optionalAttrs hasConfigs {
            "config" = configsPath;
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
        echo "    scripts/build-mods.sh               build + install locally (dev iteration)"
        echo "    git tag mods-vX.Y.Z && git push --tags   cut a release (CI builds + publishes jars)"
        echo "    cd pack && packwiz update <mod>     re-pin to the new release (in \`nix develop\`)"
        echo ""
        echo "  Build/Run:"
        echo "    nix run .#installPrism             install to Prism Launcher"
        echo "    nix build .#clientMods             flat dir of mod JARs"
        echo "    nix build .#export                 distributable tarball"
        echo "    server: import nixosModules.default on a NixOS host"
        echo ""
      '';
    };
  };
}
