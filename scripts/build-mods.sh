#!/usr/bin/env bash
#
# Build the local NeoForge mods (paint-craft, asset-shelf, structure-stash, ...)
# and drop the jars into local-mods/.
#
# local-mods/ is committed and is the single source of truth for these mods --
# there is no separate release step. Jars of a previous version of the same mod
# id are removed here; leaving two versions behind is a hard NeoForge load
# failure. Commit the result (the old jar shows as deleted, the new one as new).
#
# Usage:
#   scripts/build-mods.sh                 build all local mods
#   scripts/build-mods.sh paint-craft     build only the given mod(s)
#
set -euo pipefail

# pwd -P: the repo is reachable via a symlink (~/nixos -> /etc/nixos); giving
# gradle two paths to the same tree breaks its file watching and it then builds
# from a stale view of gradle.properties.
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"

MODS=("$@")
if [ "${#MODS[@]}" -eq 0 ]; then
  for d in "$REPO"/packages/*/; do
    [ -f "$d/build.gradle" ] && MODS+=("$(basename "$d")")
  done
fi

echo ">> Mods to build: ${MODS[*]}"

nix develop "$REPO" --command bash -c '
  set -euo pipefail
  repo="$1"; shift
  for m in "$@"; do
    echo ">> Building $m"
    ( cd "$repo/packages/$m" && ./gradlew build )
  done
' _ "$REPO" "${MODS[@]}"

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
  rm -f "$REPO/local-mods/${id}-"*.jar
  cp -f "$jar" "$REPO/local-mods/"
  echo ">> ${id}-${ver}.jar -> local-mods/"
done

echo ">> Done."
