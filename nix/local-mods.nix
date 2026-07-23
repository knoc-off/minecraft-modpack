# Local mods (asset-shelf, paint-craft, structure-stash), built by CI and
# published as GitHub Release assets (see .github/workflows/build.yml).
#
# Populated by tagging a release (`git tag mods-vX.Y.Z && git push --tags`),
# waiting for CI to publish the jars, then running the prefetch helper
# (`gen-nix-local-mods mods-vX.Y.Z`, in `nix develop`) to fill in the entries
# below.
#
# Until a release exists for a given mod, its entry is left commented out and
# `flake.nix` falls back to `local-mods/<name>.jar` on disk (see
# `scripts/build-mods.sh`) so local development keeps working.

{ pkgs }:

{
  # "assetshelf" = {
  #   jar = pkgs.fetchurl {
  #     url = "https://github.com/knoc-off/minecraft-modpack/releases/download/mods-vX.Y.Z/assetshelf-X.Y.Z.jar";
  #     hash = "sha256-...=";
  #     name = "assetshelf-X.Y.Z.jar";
  #   };
  # };
  # "paintcraft" = {
  #   jar = pkgs.fetchurl {
  #     url = "https://github.com/knoc-off/minecraft-modpack/releases/download/mods-vX.Y.Z/paintcraft-X.Y.Z.jar";
  #     hash = "sha256-...=";
  #     name = "paintcraft-X.Y.Z.jar";
  #   };
  # };
  # "structurestash" = {
  #   jar = pkgs.fetchurl {
  #     url = "https://github.com/knoc-off/minecraft-modpack/releases/download/mods-vX.Y.Z/structurestash-X.Y.Z.jar";
  #     hash = "sha256-...=";
  #     name = "structurestash-X.Y.Z.jar";
  #   };
  # };
}
