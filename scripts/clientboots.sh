#!/usr/bin/env bash
#
# Launch the pack's client N times, no server, and count launches that lose
# a registry entry while loading mods ("unused register callback" /
# "unbound value"). That loss happens before any connection, so a server
# adds nothing but RAM and time; this does ~45s per launch, P at once.
#
# Usage (inside `nix develop`):
#   scripts/clientboots.sh N [-j P]        (P parallel clients, default 2)
#   FML_THREADS=N scripts/clientboots.sh ...   fml.toml maxThreads (1 = serial loading)
#
# Each client needs ~5 GiB RSS at -Xmx3G. Bad launches keep their logs under
# ~/mcwork/runs/clientboots-<time>/<i>/; the tally is the last line.
set -uo pipefail
export LC_ALL=C
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
n="${1:?usage: $0 N [-j P]}"; shift
par=2; [ "${1:-}" = -j ] && par="$2"
for c in portablemc xvfb-run java python3; do
  command -v $c >/dev/null || { echo "!! $c not on PATH -- run inside nix develop"; exit 2; }
done

clientRes=$(nix build --no-link --print-out-paths "$REPO#clientResources")
loader=$(python3 -c 'import tomllib,sys; print(tomllib.load(open(sys.argv[1],"rb"))["versions"]["neoforge"])' "$REPO/pack/pack.toml")
out="$HOME/mcwork/runs/clientboots-$(date +%Y%m%dT%H%M%S)"
cache="$HOME/mcwork/client-cache"   # vanilla + libraries, shared, ~1 GiB
mkdir -p "$out" "$cache"
echo ">> $n launches, $par at a time, FML_THREADS=${FML_THREADS:-default}: $out"

# The first launch fills the shared cache; parallel first launches would race.
[ -d "$cache/versions" ] || par=1

worker() { # w
  local w=$1 game="$out/game-$1" i
  mkdir -p "$game/mods"
  cp -L "$clientRes"/mods/* "$game/mods/"
  cp -r "$REPO/pack/config" "$game/"
  chmod -R u+w "$game"
  if [ -n "${FML_THREADS:-}" ]; then
    grep -v '^maxThreads' "$game/config/fml.toml" 2>/dev/null > "$game/fml.new" || true
    echo "maxThreads = $FML_THREADS" >> "$game/fml.new"; mv "$game/fml.new" "$game/config/fml.toml"
  fi
  printf 'onboardAccessibility:false\nnarrator:0\nrenderDistance:2\ntutorialStep:none\nskipMultiplayerWarning:true\n' > "$game/options.txt"
  for i in $(seq "$w" "$par" "$n"); do
    rm -rf "$game/logs" "$game/crash-reports"
    local t0; t0=$(date +%s)
    # set -m: own process group, so one kill stops xvfb, portablemc and the JVM.
    set -m
    LIBGL_ALWAYS_SOFTWARE=1 xvfb-run -a -s "-screen 0 1280x720x24" \
      portablemc --main-dir "$cache" start --mc-dir "$game" --jvm "$(command -v java)" \
        --jvm-arg=-Xmx3G -u "Boot$w" "neoforge::$loader" > "$game/client.out" 2>&1 &
    local pid=$!
    set +m
    local res="" log="$game/logs/latest.log"
    while :; do
      sleep 2
      if grep -aqE 'unused register callback|Trying to access unbound value' "$log" 2>/dev/null; then
        res="LOST $(grep -aoE 'for entry [^ ]+ \[[^]]+\]|unbound value: [^ ]+ [^ ]+' "$log" | head -1)"; break
      fi
      # Sound starts after the resource reload, long after registration.
      grep -aq 'Sound engine started' "$log" 2>/dev/null && { res=PASS; break; }
      kill -0 $pid 2>/dev/null || { res="DIED $(grep -ahm1 -E '^(Description|Caused by):' "$game"/crash-reports/*.txt 2>/dev/null | cut -c1-100)"; break; }
      [ $(( $(date +%s) - t0 )) -lt 400 ] || { res=TIMEOUT; break; }
    done
    kill -- -"$pid" 2>/dev/null; wait "$pid" 2>/dev/null
    echo "client $i/$n $res ($(( $(date +%s) - t0 ))s)"
    if [ "$res" != PASS ]; then
      mkdir -p "$out/$i"; cp -r "$game/logs" "$game/crash-reports" "$game/client.out" "$out/$i/" 2>/dev/null
    fi
  done
}

for w in $(seq 1 "$par"); do worker "$w" & done | tee "$out/results.txt"
wait
bad=$(grep -cv ' PASS ' "$out/results.txt")
echo "tally: $bad bad of $(wc -l < "$out/results.txt") ($(grep -c ' LOST ' "$out/results.txt") lost-registration)"
rm -rf "$out"/game-*
[ "$bad" = 0 ]
