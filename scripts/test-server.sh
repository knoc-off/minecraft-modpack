#!/usr/bin/env bash
#
# Boot a throwaway copy of a real server dir against this repo's server mods,
# force-load every spot in test/spots.txt, optionally spark-profile each one,
# and condense the run into summary.txt. Never touches <datadir> itself.
#
# Usage (inside `nix develop`, for rcon-cli):
#   scripts/test-server.sh <datadir> [--profile] [--seconds N]
#
#   <datadir>    a server dir, e.g. a writable snapshot of /srv/minecraft/inkwell
#   --profile    spark profile per spot, saved locally (never uploaded)
#   --seconds N  how long each spot stays loaded / profiled (default 30)
#
# Output: <datadir>/../runs/<timestamp>/ -- the full server dir after the run,
# plus summary.txt and spark/<spot>.sparkprofile. Compare two runs with
# scripts/test-diff.sh. Open a .sparkprofile by dropping it on
# https://spark.lucko.me -- the viewer parses it in the browser.
#
# Reading a diff: identical runs move ~0.5 ms/tick per spot, so only treat
# shifts of several ms, or ones across every spot, as real. The counts above
# the tps section should match exactly; any new error source is worth a look.
#
# Runs are ~5 GiB each once the server has rewritten chunks, so only the
# newest KEEP_RUNS (default 3) are kept; summaries worth keeping belong in
# test/baseline/ anyway.
set -euo pipefail
# The devshell has no locale archive, so grep's binary detection and sort's
# order would otherwise depend on whatever the caller exported. C keeps both
# stable, which matters because summaries are diffed between runs.
export LC_ALL=C

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
SPOTS="$REPO/test/spots.txt"
PORT=25566
RCON_PORT=25576
RCON_PASS=testpass

die() { echo "!! $*" >&2; exit 1; }

src="${1:-}"; shift || true
[ -d "$src/world" ] || die "usage: $0 <datadir with world/> [--profile] [--seconds N]"
profile=0; seconds=30
while [ $# -gt 0 ]; do
  case "$1" in
    --profile) profile=1 ;;
    --seconds) seconds="$2"; shift ;;
    *) die "unknown arg $1" ;;
  esac
  shift
done
command -v rcon-cli >/dev/null || die "rcon-cli not on PATH -- run inside nix develop"

# A swapping JVM produces garbage timings and an OOM produces a misleading
# failure, so refuse up front rather than profile either.
avail_kb=$(awk '/MemAvailable/ {print $2}' /proc/meminfo)
[ "${FORCE:-0}" = 1 ] || [ "$avail_kb" -ge $((6 * 1024 * 1024)) ] \
  || die "only $((avail_kb / 1024)) MiB available, want 6 GiB (FORCE=1 to override)"

echo ">> building server + mods"
server=$(nix build --no-link --print-out-paths "$REPO#server")
mods=$(nix build --no-link --print-out-paths "$REPO#serverMods")
clientMods="$(nix build --no-link --print-out-paths "$REPO#clientResources")/mods"

# The client-only half of the pack never loads headless; a static dependency
# check is the part of a client launch that can be done here.
echo ">> checking client mod dependencies"
clientDeps=$(python3 -B "$REPO/scripts/check_deps.py" "$clientMods" "$REPO/pack" || true)

run="$(cd "$src/.." && pwd)/runs/$(date +%Y%m%dT%H%M%S)"
mkdir -p "$(dirname "$run")"
# Timestamped names sort chronologically; drop all but the newest few before
# adding another, so a long iteration session can't fill the disk.
ls -d "$(dirname "$run")"/*/ 2>/dev/null | sort | head -n -$(( ${KEEP_RUNS:-3} - 1 )) \
  | while read -r old; do echo ">> pruning old run $old"; rm -rf "$old"; done || true
echo ">> copying $src -> $run"
# Reflink makes this instant on btrfs; plain cp is the slow fallback.
cp -a --reflink=auto "$src" "$run"
cd "$run"
mkdir -p spark
rm -f logs/latest.log logs/debug.log

# A real dir of symlinks rather than the store dir itself, so single jars can
# be left out. Ready Player Fun stops the world ticking once nobody has been
# online for a while (max 360s), and a headless test never has anyone online,
# so with it every spot would profile a frozen world.
rm -rf mods; mkdir mods
for jar in "$mods"/*; do
  case "$(basename "$jar")" in readyplayerfun-*) continue ;; esac
  ln -s "$jar" mods/
done

rm -f eula.txt; echo "eula=true" > eula.txt
setprop() { # key value
  grep -v "^$1=" server.properties > server.properties.new || true
  echo "$1=$2" >> server.properties.new
  mv server.properties.new server.properties
}
setprop server-ip 127.0.0.1
setprop server-port $PORT
setprop query.port $PORT
setprop enable-rcon true
setprop rcon.port $RCON_PORT
setprop rcon.password $RCON_PASS
setprop online-mode false
setprop white-list false
# The watchdog would kill the server during a deliberately heavy spot.
setprop max-tick-time -1

# `--` matters: without it rcon-cli eats spark's own flags (--thread,
# --save-to-file) as its options and never sends the command.
rc() { rcon-cli --port $RCON_PORT --password $RCON_PASS -- "$@" </dev/null 2>&1 | sed -E 's/§.//g; s/\x1b\[[0-9;]*m//g' || true; }

# spark replies asynchronously, after RCON has already returned its (empty)
# response, so its output never reaches us. `neoforge tps` is synchronous.
# Its commands still execute; only the replies are lost.
tps() { rc neoforge tps | grep -iE 'overall|overworld|nether' | tr -s ' \n' ' ' || true; }

echo ">> starting server (log: $run/logs/latest.log)"
start=$(date +%s)
"$server/bin/minecraft-server" -Xms2G -Xmx5G -XX:+UseG1GC -Djava.awt.headless=true \
  </dev/null >server.out 2>&1 &
pid=$!
trap 'kill $pid 2>/dev/null || true' EXIT

until grep -q 'Done (' logs/latest.log 2>/dev/null; do
  kill -0 $pid 2>/dev/null || { echo "!! server exited during boot"; break; }
  [ $(( $(date +%s) - start )) -lt 900 ] || { echo "!! boot timed out after 15 min"; break; }
  sleep 2
done
booted=0; grep -q 'Done (' logs/latest.log 2>/dev/null && booted=1
boot_s=$(( $(date +%s) - start ))

: > spots.out
if [ $booted = 1 ]; then
  echo ">> booted in ${boot_s}s"
  # Baseline with nothing but spawn loaded, so spot numbers have a reference.
  sleep 10
  echo "idle $(tps)" >> spots.out

  grep -vE '^\s*(#|$)' "$SPOTS" | while read -r dim x z r label _; do
    b=$(( r * 16 ))
    echo ">> $label ($dim $x $z r=$r)"
    rc execute in "$dim" run forceload add $((x - b)) $((z - b)) $((x + b)) $((z + b)) >/dev/null
    # Loading itself (disk, chunk upgrade, contraption assembly) is not the
    # steady state players see, so let it settle before measuring.
    sleep 10
    if [ $profile = 1 ]; then rc spark profiler start --thread '*' --interval 2 >/dev/null; fi
    sleep "$seconds"
    # Entity counts explain most per-spot differences (mob farms, item
    # piles), and `execute if entity` answers synchronously over RCON. Counted
    # world-wide: the previous spot has been unloaded, so this is spawn plus
    # this spot.
    ents() { rc execute if entity "@e[$1]" | grep -oE 'count: [0-9]+' | grep -oE '[0-9]+' || echo 0; }
    echo "$label $(tps) items=$(ents type=minecraft:item) others=$(ents type=!minecraft:item)" >> spots.out
    if [ $profile = 1 ]; then
      # Marker rather than counting files: spark picks its own filename, and
      # an unmatched glob under pipefail would abort the whole run.
      touch .profile-marker
      rc spark profiler stop --save-to-file --comment "$label" >/dev/null
      saved=""
      for _ in $(seq 60); do
        saved=$(find config/spark -name '*.sparkprofile' -newer .profile-marker | head -1)
        [ -n "$saved" ] && break
        sleep 1
      done
      if [ -n "$saved" ]; then
        # spark may still be writing when the file first appears.
        sleep 2
        mv "$saved" "spark/$label.sparkprofile"
      else
        echo "!! no profile saved for $label"
      fi
    fi
    rc execute in "$dim" run forceload remove $((x - b)) $((z - b)) $((x + b)) $((z + b)) >/dev/null
  done

  rc spark health show --memory > health.txt || true
  rc save-all flush >/dev/null || true
  rc stop >/dev/null || true
fi

for _ in $(seq 120); do kill -0 $pid 2>/dev/null || break; sleep 1; done
kill $pid 2>/dev/null && echo "!! server did not stop, killed"
trap - EXIT

log=logs/latest.log
# -a: mod output puts non-UTF-8 bytes in the log, and grep then treats it as
# binary and prints nothing for -o.
count() { grep -acE "$1" "$log" 2>/dev/null || true; }
# Deliberately terse: this is what gets diffed between runs and read by eye.
# Every line is either a count or a short list, never raw log volume.
{
  echo "booted: $booted (${boot_s}s)"
  echo "mods: $(ls mods | wc -l)"
  echo "client-deps: $(echo "$clientDeps" | tail -1)"
  echo "$clientDeps" | grep -E '^(MISSING|VERSION)' || true
  echo "crash-reports: $(ls crash-reports 2>/dev/null | wc -l || true) total, new: $(find crash-reports -newer eula.txt -type f 2>/dev/null | wc -l || true)"
  echo "errors: $(count '/ERROR\]')"
  echo "warns: $(count '/WARN\]')"
  echo "mixin-failures: $(count 'Mixin apply for mod .* failed|InvalidInjectionException|InvalidMixinException')"
  echo "missing-deps: $(count 'Missing or unsupported mandatory dependencies')"
  echo "missing-registry: $(count 'Missing registr|Unidentified mapping|missing mapping')"
  echo "cant-keep-up: $(count "Can't keep up")"
  # What force-loading the spots is for: block entities or entities whose
  # data the new jars can't read. These are silently dropped from the world
  # on the next save, so they must be zero (or understood) before deploying.
  echo "chunk-load-problems: $(count 'Skipping BlockEntity|Tried to load a DUMMY block entity|Failed to (load|read) chunk|Couldn.t load chunk|Exception loading (block )?entit|Failed to create block entity|is in the wrong location')"
  echo
  echo "# error sources (logger -> count)"
  grep -aoE '/ERROR\] \[[^]]+\]' "$log" 2>/dev/null | sort | uniq -c | sort -rn | head -15 || true
  echo
  echo "# tps per spot (neoforge tps, after settling)"
  cat spots.out
  if [ $profile = 1 ] && [ -n "$(find spark -name '*.sparkprofile' | head -1)" ]; then
    echo
    # Below the tps section on purpose: test-diff.sh compares only what is
    # above it exactly, and these shares move a few percent run to run.
    echo "# server-thread cost per spot (scripts/spark_top.py)"
    python3 "$REPO/scripts/spark_top.py" --top 5 spark/*.sparkprofile || true
  fi
} > summary.txt

echo
cat summary.txt
echo
echo ">> run: $run"
