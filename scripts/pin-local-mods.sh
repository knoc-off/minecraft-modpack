#!/usr/bin/env bash
#
# Re-pin the local mods (asset-shelf, paint-craft, structure-stash, ...) in
# pack/mods/*.pw.toml to the jars published under a given GitHub Release tag,
# then keep nix/mods.nix in sync.
#
# Deliberately does NOT use packwiz's `github add`/`update` auto-latest-release
# detection: this repo publishes two independent kinds of releases under the
# same GitHub repo (mods-vX.Y.Z for these jars, pack-vX.Y.Z for the .mrpack),
# and packwiz's github updater has no way to filter by tag prefix -- it just
# picks whichever release is most recently created on the tracked branch. If
# a pack-vX.Y.Z release happens to be newer than the latest mods-vX.Y.Z one,
# `packwiz update <mod>` silently reports "already up to date!" while leaving
# a stale pin (CheckUpdate's error is swallowed on the single-mod path).
#
# This script always targets an explicit tag you name instead, so a pin can
# never be pulled from the wrong release regardless of push order/timing.
# Jar filenames are discovered from the release's actual asset list (not
# reconstructed from local gradle.properties), so re-pinning an older tag or
# a version you haven't bumped locally still works correctly.
#
# Usage (in `nix develop`, needs packwiz + curl + jq on PATH):
#   scripts/pin-local-mods.sh mods-vX.Y.Z
#   pin-local-mods mods-vX.Y.Z              (devShell wrapper, same thing)
#
# After tagging + pushing a mods-vX.Y.Z release and waiting for CI to publish
# the jars, run this, review the diff, and commit.
#
set -euo pipefail

REPO="$(git rev-parse --show-toplevel)"
REMOTE_URL="$(git -C "$REPO" remote get-url origin)"
# git@github.com:owner/repo.git or https://github.com/owner/repo.git -> owner/repo
GH_REPO="$(echo "$REMOTE_URL" | sed -E 's#.*[:/]([^/]+/[^/]+)$#\1#; s#\.git$##')"

TAG="${1:?usage: pin-local-mods <release-tag>}"

CURL_AUTH=()
if [ -n "${GITHUB_TOKEN:-}" ]; then
  CURL_AUTH=(-H "Authorization: Bearer $GITHUB_TOKEN")
fi

echo ">> Fetching release $GH_REPO@$TAG"
RELEASE_JSON="$(curl -fsSL "${CURL_AUTH[@]}" \
  "https://api.github.com/repos/$GH_REPO/releases/tags/$TAG")" \
  || { echo "!! release '$TAG' not found on $GH_REPO" >&2; exit 1; }

# Discover local mods = package dirs that contain a build.gradle (same
# discovery scripts/build-mods.sh uses).
MODS=()
for d in "$REPO"/packages/*/; do
  [ -f "$d/build.gradle" ] && MODS+=("$(basename "$d")")
done

cd "$REPO/pack"
for m in "${MODS[@]}"; do
  props="$REPO/packages/$m/gradle.properties"
  id="$(sed -n 's/^mod_id=//p' "$props")"

  asset="$(echo "$RELEASE_JSON" | jq -r --arg re "^${id}-.*\\.jar\$" \
    '[.assets[] | select(.name | test($re))]')"
  count="$(echo "$asset" | jq 'length')"

  if [ "$count" -eq 0 ]; then
    echo "!! no asset matching ${id}-*.jar in release $TAG" >&2
    exit 1
  elif [ "$count" -gt 1 ]; then
    echo "!! multiple assets matching ${id}-*.jar in release $TAG:" >&2
    echo "$asset" | jq -r '.[].name' >&2
    exit 1
  fi

  url="$(echo "$asset" | jq -r '.[0].browser_download_url')"
  jar="$(echo "$asset" | jq -r '.[0].name')"

  echo ">> Pinning $id -> $jar"
  packwiz --yes url add "$m" "$url" --force --meta-name "$id"
done

packwiz refresh
echo ">> Regenerating nix/mods.nix"
python3 "$REPO/scripts/gen_nix_from_packwiz.py" "$REPO/pack" "$REPO/nix/mods.nix"

echo ">> Done. Review the diff, then commit."
