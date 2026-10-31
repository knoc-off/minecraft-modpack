#!/usr/bin/env bash
#
# Join a throwaway copy of a real server with a REAL modded client (the
# pack's client mods on NeoForge, launched by portablemc under Xvfb with
# software GL), optionally hand the player items first, and report whether
# the client stayed connected. This is the half test-server.sh can't see:
# failures that only happen on the client, like a packet the client can't
# decode ("Network Protocol Error").
#
# Usage (inside `nix develop`):
#   scripts/test-client.sh <datadir> [--as NAME] [--give 'ITEM[COMPONENTS]']... [--hold SECONDS]
#
#   <datadir>  a server dir (e.g. a copy of a btrbk snapshot); copied, never touched
#   --as NAME  offline username to join as (default: Tester). Using a real
#              player's name loads THEIR playerdata, inventory included --
#              offline-mode UUIDs differ from online ones, so the player's
#              .dat is copied over to the offline UUID first.
#   --give     /give to the player once logged in (repeatable), e.g.
#              --give 'minecraft:filled_map[minecraft:map_id=8]'
#   --hold N   how long the client must stay connected to pass (default 60)
#   --opt K:V  extra options.txt line for the client (repeatable), e.g.
#              --opt renderDistance:32 to match a player's settings
#   --slot N   select hotbar slot N (0-8) in the player's data before joining,
#              so the client joins holding that item
#
# Result: PASS / FAIL line, plus the client's disconnect report if any.
# Exit code 0 = stayed connected, 1 = disconnected or never joined.
#
# Needs ~10 GiB free: server -Xmx5G, client -Xmx4G.
set -euo pipefail
export LC_ALL=C

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
PORT=25566
RCON_PORT=25576
RCON_PASS=testpass

die() { echo "!! $*" >&2; exit 2; }

src="${1:-}"; shift || true
[ -d "$src/world" ] || die "usage: $0 <datadir with world/> [--as NAME] [--give ITEM]... [--hold SECONDS]"
name=Tester; hold=60; gives=(); opts=(); slot=
while [ $# -gt 0 ]; do
  case "$1" in
    --as) name="$2"; shift ;;
    --give) gives+=("$2"); shift ;;
    --hold) hold="$2"; shift ;;
    --opt) opts+=("$2"); shift ;;
    --slot) slot="$2"; shift ;;
    *) die "unknown arg $1" ;;
  esac
  shift
done
for c in rcon-cli portablemc xvfb-run java python3; do
  command -v $c >/dev/null || die "$c not on PATH -- run inside nix develop"
done
# A previous run killed mid-way (tool timeout, ^C on the parent) can leave
# its server holding these ports; the new server would then die at boot
# with a misleading error.
if (exec 3<>/dev/tcp/127.0.0.1/$PORT) 2>/dev/null || (exec 3<>/dev/tcp/127.0.0.1/$RCON_PORT) 2>/dev/null; then
  die "port $PORT or $RCON_PORT already in use -- a leftover test server? (pgrep -a java)"
fi

echo ">> building server + mods"
server=$(nix build --no-link --print-out-paths "$REPO#server")
mods=$(nix build --no-link --print-out-paths "$REPO#serverMods")
clientRes=$(nix build --no-link --print-out-paths "$REPO#clientResources")
loader=$(python3 -c 'import tomllib,sys; print(tomllib.load(open(sys.argv[1],"rb"))["versions"]["neoforge"])' "$REPO/pack/pack.toml")

work="$(cd "$src/.." && pwd)/client-runs/$(date +%Y%m%dT%H%M%S)"
mkdir -p "$work"
# Shared across runs: the vanilla jar, libraries and assets portablemc
# downloads are ~1 GiB and identical every time.
cache="$(cd "$src/.." && pwd)/client-cache"
mkdir -p "$cache"

echo ">> copying $src -> $work/server"
cp -a --reflink=auto "$src" "$work/server"
cd "$work/server"
# nix-minecraft deploys some files as store symlinks; a copy keeps them as
# read-only links that would block the edits below.
find . -maxdepth 1 -type l -delete
rm -rf mods logs/latest.log logs/debug.log world/session.lock
mkdir mods
for jar in "$mods"/*; do
  case "$(basename "$jar")" in readyplayerfun-*) continue ;; esac
  ln -s "$jar" mods/
done
echo "eula=true" > eula.txt
setprop() {
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
setprop enforce-secure-profile false
setprop max-tick-time -1

# Offline mode derives the UUID from the name, so a real player's online
# playerdata would be ignored. Copy it to the offline UUID so the client
# joins with that player's actual inventory and position.
offline_uuid=$(python3 - "$name" <<'EOF'
import hashlib, sys, uuid
h = bytearray(hashlib.md5(b"OfflinePlayer:" + sys.argv[1].encode()).digest())
h[6] = h[6] & 0x0f | 0x30; h[8] = h[8] & 0x3f | 0x80
print(uuid.UUID(bytes=bytes(h)))
EOF
)
online_uuid=$(python3 -c 'import json,sys; print(next((e["uuid"] for e in json.load(open("usercache.json")) if e["name"]==sys.argv[1]), ""))' "$name" 2>/dev/null || true)
if [ -n "$online_uuid" ] && [ -f "world/playerdata/$online_uuid.dat" ]; then
  echo ">> joining as $name with their playerdata ($online_uuid -> $offline_uuid)"
  cp "world/playerdata/$online_uuid.dat" "world/playerdata/$offline_uuid.dat"
fi
if [ -n "$slot" ]; then
  [ -f "world/playerdata/$offline_uuid.dat" ] || die "--slot needs existing playerdata for $name"
  # Gzipped NBT; SelectedItemSlot is a top-level Int. nbtlib is not in the
  # devshell, so fetch it ad hoc rather than make every shell pay for it.
  nix shell --impure --expr '(import <nixpkgs> {}).python3.withPackages (p: [p.nbtlib])' -c python3 - \
    "world/playerdata/$offline_uuid.dat" "$slot" <<'EOF'
import nbtlib, sys
from nbtlib.tag import Int
f = nbtlib.load(sys.argv[1]); f["SelectedItemSlot"] = Int(int(sys.argv[2])); f.save()
held = [str(i["id"]) for i in f["Inventory"] if int(i["Slot"]) == int(sys.argv[2])]
print(f">> holding slot {sys.argv[2]}: {held[0] if held else 'empty'}")
EOF
fi

rc() { rcon-cli --port $RCON_PORT --password $RCON_PASS -- "$@" </dev/null 2>&1 | sed -E 's/§.//g; s/\x1b\[[0-9;]*m//g' || true; }

echo ">> starting server"
"$server/bin/minecraft-server" -Xms2G -Xmx5G -XX:+UseG1GC -Djava.awt.headless=true \
  </dev/null >server.out 2>&1 &
spid=$!
cpid=
cleanup() {
  [ -n "$cpid" ] && pkill -P "$cpid" 2>/dev/null; [ -n "$cpid" ] && kill "$cpid" 2>/dev/null
  rc stop >/dev/null 2>&1; sleep 5; kill $spid 2>/dev/null || true
}
trap cleanup EXIT INT TERM

start=$(date +%s)
until grep -q 'Done (' logs/latest.log 2>/dev/null; do
  kill -0 $spid 2>/dev/null || die "server exited during boot (see $work/server/logs/latest.log)"
  [ $(( $(date +%s) - start )) -lt 900 ] || die "server boot timed out"
  sleep 2
done
echo ">> server up in $(( $(date +%s) - start ))s"

# The client game dir: the pack's client mods and default configs, the same
# set packwiz-installer gives a player.
game="$work/client"
mkdir -p "$game/mods"
cp -L "$clientRes"/mods/* "$game/mods/"
cp -r "$REPO/pack/config" "$game/"
chmod -R u+w "$game"
# No tutorial toasts, no narrator, low render distance: less to do on a
# software renderer before the join.
printf 'onboardAccessibility:false\nnarrator:0\nrenderDistance:4\nsimulationDistance:5\ntutorialStep:none\nskipMultiplayerWarning:true\njoinedFirstServer:true\n' > "$game/options.txt"
# Duplicate keys crash the client at startup, so --opt replaces a line
# rather than appending a second one.
for o in ${opts[@]+"${opts[@]}"}; do
  grep -v "^${o%%:*}:" "$game/options.txt" > "$game/options.txt.new" || true
  echo "$o" >> "$game/options.txt.new"; mv "$game/options.txt.new" "$game/options.txt"
done

echo ">> launching client as $name (NeoForge $loader, software GL under Xvfb)"
# portablemc's Mojang-provided JVM is dynamically linked against FHS paths,
# so it can't run on NixOS; hand it the devshell's JDK instead.
LIBGL_ALWAYS_SOFTWARE=1 xvfb-run -a -s "-screen 0 1280x720x24" \
  portablemc --main-dir "$cache" start --mc-dir "$game" --jvm "$(command -v java)" \
    --jvm-arg=-Xmx4G -u "$name" --join-server 127.0.0.1 --join-server-port $PORT \
    "neoforge::$loader" >"$work/client.out" 2>&1 &
cpid=$!

clog="$game/logs/latest.log"
start=$(date +%s)
until grep -q "$name joined the game" logs/latest.log 2>/dev/null; do
  kill -0 $cpid 2>/dev/null || break
  grep -qE "$name (lost connection|left the game)" logs/latest.log 2>/dev/null && break
  [ $(( $(date +%s) - start )) -lt 900 ] || break
  sleep 3
done
if ! grep -q "$name joined the game" logs/latest.log 2>/dev/null; then
  echo "FAIL: $name never joined"
  grep -aE "$name.*lost connection" logs/latest.log 2>/dev/null | tail -1 || true
  # A crash before the log is set up only reaches stdout.
  grep -aE '^(Exception|Caused by)|/ERROR\]' "$work/client.out" | head -5
  tail -20 "$clog" 2>/dev/null || true
  exit 1
fi
echo ">> $name joined after $(( $(date +%s) - start ))s"

for g in ${gives[@]+"${gives[@]}"}; do
  echo ">> give $name $g: $(rc give "$name" "$g")"
done

start=$(date +%s)
result=PASS
while [ $(( $(date +%s) - start )) -lt "$hold" ]; do
  if grep -qE "$name lost connection" logs/latest.log; then result=FAIL; break; fi
  kill -0 $cpid 2>/dev/null || { result=FAIL; break; }
  sleep 2
done

echo
if [ $result = PASS ]; then
  echo "PASS: $name stayed connected for ${hold}s"
else
  echo "FAIL: $name disconnected after $(( $(date +%s) - start ))s"
  grep -aE "$name lost connection" logs/latest.log | tail -1
  # The client writes a full report for packet errors; that names the mod.
  report=$(ls -t "$game"/debug/disconnect-*-client.txt 2>/dev/null | head -1 || true)
  if [ -n "$report" ]; then
    echo "--- $report"
    sed -n '1,60p' "$report"
  else
    grep -aE 'ERROR|Exception|Caused by|disconnect' "$clog" | tail -30
  fi
fi
echo ">> run: $work"
[ $result = PASS ]
