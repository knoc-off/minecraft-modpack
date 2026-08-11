#!/usr/bin/env bash
#
# Bump the patch version of every local mod, drop the superseded jars from
# local-mods/, and rebuild + reinstall via scripts/build-mods.sh.
#
# Usage:
#   scripts/bump-mods.sh                bump all local mods, rebuild, install
#   scripts/bump-mods.sh --no-install   skip the Prism install step
#
# After this, commit local-mods/ (the new jars are added, old ones git rm'd).
#
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

for props in "$REPO"/packages/*/gradle.properties; do
  [ -f "$(dirname "$props")/build.gradle" ] || continue
  id="$(sed -n 's/^mod_id=//p' "$props")"
  old="$(sed -n 's/^mod_version=//p' "$props")"
  case "$old" in
    *.*.*) ;;
    *) echo "!! $id: unexpected version '$old' (want MAJOR.MINOR.PATCH)" >&2; exit 1 ;;
  esac
  new="${old%.*}.$(( ${old##*.} + 1 ))"
  sed -i "s/^mod_version=.*/mod_version=$new/" "$props"
  echo ">> $id: $old -> $new"

  stale="$REPO/local-mods/${id}-${old}.jar"
  if [ -f "$stale" ]; then
    git -C "$REPO" rm -q -f --ignore-unmatch -- "$stale" || rm -f "$stale"
    rm -f "$stale"
  fi
done

exec "$REPO/scripts/build-mods.sh" "$@"
