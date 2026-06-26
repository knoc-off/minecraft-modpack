#!/usr/bin/env bash
#
# Build the local NeoForge mods (paint-craft, asset-shelf, structure-stash, ...)
# and install them into the modpack.
#
#   1. gradle build (run inside `nix develop` so jdk21 + gradle are available)
#   2. copy each jar into local-mods/   (the flake's localModJars reads this dir)
#   3. nix run .#installPrism           (push into the live Prism Launcher instance)
#
# Usage:
#   scripts/build-mods.sh                 build + install ALL local mods
#   scripts/build-mods.sh paint-craft     build + install only the given mod(s)
#   scripts/build-mods.sh --no-install …  skip the Prism install step
#
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_TASK="build"
GRADLE_FLAGS="--offline"   # deps are cached; drop this if a clean fetch is needed

DO_INSTALL=1
SELECTED=()
for arg in "$@"; do
  case "$arg" in
    --no-install) DO_INSTALL=0 ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    *) SELECTED+=("$arg") ;;
  esac
done

# Discover local mods = package dirs that contain a build.gradle.
ALL_MODS=()
for d in "$REPO"/packages/*/; do
  [ -f "$d/build.gradle" ] && ALL_MODS+=("$(basename "$d")")
done

if [ "${#SELECTED[@]}" -gt 0 ]; then
  MODS=("${SELECTED[@]}")
else
  MODS=("${ALL_MODS[@]}")
fi

echo ">> Mods to build: ${MODS[*]}"

# ── 1. Build (single nix develop invocation for all mods) ──────────────────────
nix develop "$REPO" --command bash -c '
  set -euo pipefail
  repo="$1"; task="$2"; flags="$3"; shift 3
  for m in "$@"; do
    dir="$repo/packages/$m"
    [ -d "$dir" ] || { echo "!! no such mod dir: $dir" >&2; exit 1; }
    echo ">> Building $m ($task $flags)"
    ( cd "$dir" && ./gradlew $flags "$task" )
  done
' _ "$REPO" "$GRADLE_TASK" "$GRADLE_FLAGS" "${MODS[@]}"

# ── 2. Copy jars into local-mods/ ──────────────────────────────────────────────
mkdir -p "$REPO/local-mods"
for m in "${MODS[@]}"; do
  props="$REPO/packages/$m/gradle.properties"
  id="$(sed -n 's/^mod_id=//p' "$props")"
  ver="$(sed -n 's/^mod_version=//p' "$props")"
  jar="$REPO/packages/$m/build/libs/${id}-${ver}.jar"
  if [ ! -f "$jar" ]; then
    echo "!! expected jar not found: $jar" >&2
    exit 1
  fi
  cp -f "$jar" "$REPO/local-mods/"
  echo ">> Installed $(basename "$jar") -> local-mods/"
done

# ── 3. Push into the live Prism Launcher instance ──────────────────────────────
if [ "$DO_INSTALL" -eq 1 ]; then
  echo ">> Updating Prism Launcher instance (nix run .#installPrism)"
  nix run "$REPO#installPrism"
else
  echo ">> Skipping Prism install (--no-install). Jars are in local-mods/."
fi

echo ">> Done."
