#!/usr/bin/env bash
#
# Boot a throwaway copy of a real server dir against this repo's mods, run the
# selected checks against that one server, and condense everything into
# summary.txt. Never touches <datadir> itself.
#
# Usage (inside `nix develop`):
#   scripts/test.sh <datadir> [checks...]
#
#   (no checks)        boot, idle 10s, stop: log counts + error fingerprints
#   --spots            force-load each spot in test/spots.txt, tps + entities
#   --profile          --spots plus a spark profile per spot (never uploaded)
#   --seconds N        how long each spot stays loaded (default 30)
#   --sprint TICKS     load every spot at once, `tick sprint TICKS` (24000 =
#                      one day), report ms/tick and errors raised meanwhile
#   --players SEL      join a REAL modded client (portablemc + Xvfb) as each
#                      selected player, with their own inventory and
#                      position; each must stay connected for --hold seconds.
#                      SEL: active[:DAYS] (default 30, counted back from the
#                      newest playerdata) | all | Name1,Name2
#   --hold S           seconds each player must stay connected (default 30)
#   --crash            last: kill -9 the server mid-save, reboot, re-read
#                      every spot and player file; reports corruption
#   --predeploy        = --spots --sprint 24000 --players active
#   --fresh-nether     drop world/DIM-1 in the copy, so nether spots measure
#                      fresh generation (rehearses a nether wipe)
#   --empty-world      copy everything but world/ (a new one generates):
#                      seconds instead of a 5 GiB copy, for boot-only loops
#
#   SKIP="glob ..."    jars to leave out of mods/, for bisecting a bad mod
#   SPOTS=file         spot list other than test/spots.txt
#   scripts/boots.sh   repeats a run N times, for failures that come and go
#
# Exit 0 only if: booted, no new crash reports, zero mixin failures / missing
# deps / missing registry entries / chunk-load problems, every player stayed
# connected, and no error fingerprint missing from test/baseline/summary.txt.
# To accept a new error on purpose, copy the run's summary.txt over the
# baseline and commit it.
#
# Output: <datadir>/../runs/<timestamp>/ (server/, client/, summary.txt,
# spark/). Compare two runs with scripts/test-diff.sh; open a .sparkprofile by
# dropping it on https://spark.lucko.me.
#
# Reading a diff: identical runs move ~0.5 ms/tick per spot, so only treat
# shifts of several ms, or ones across every spot, as real.
set -euo pipefail
# No locale archive in the devshell; C keeps grep and sort stable, which
# matters because summaries are diffed between runs.
export LC_ALL=C

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
SPOTS="${SPOTS:-$REPO/test/spots.txt}"
BASELINE="$REPO/test/baseline/summary.txt"
PORT=25566
RCON_PORT=25576
RCON_PASS=testpass

die() { echo "!! $*" >&2; exit 2; }

src="${1:-}"; shift || true
[ -d "$src/world" ] || die "usage: $0 <datadir with world/> [--spots] [--profile] [--sprint TICKS] [--players SEL] [--crash] [--predeploy] [--fresh-nether] [--empty-world]"
src="$(cd "$src" && pwd)"
spots=0; profile=0; seconds=30; sprint=0; players=; hold=30; crash=0; freshNether=0; emptyWorld=0
while [ $# -gt 0 ]; do
  case "$1" in
    --fresh-nether) freshNether=1 ;;
    --empty-world) emptyWorld=1 ;;
    --spots) spots=1 ;;
    --profile) spots=1; profile=1 ;;
    --seconds) seconds="$2"; shift ;;
    --sprint) sprint="$2"; shift ;;
    --players) players="$2"; shift ;;
    --hold) hold="$2"; shift ;;
    --crash) crash=1 ;;
    --predeploy) spots=1; sprint=24000; players=active ;;
    *) die "unknown arg $1" ;;
  esac
  shift
done

for c in rcon-cli python3 ${players:+portablemc xvfb-run java}; do
  command -v $c >/dev/null || die "$c not on PATH -- run inside nix develop"
done
# A run killed mid-way (tool timeout, ^C on a parent) can leave its server
# holding these ports; the new one would then die at boot, misleadingly.
for p in $PORT $RCON_PORT; do
  if (exec 3<>/dev/tcp/127.0.0.1/$p) 2>/dev/null; then
    die "port $p already in use -- a leftover test server? (pgrep -a java)"
  fi
done
# A swapping JVM gives garbage timings, an OOM a misleading failure.
want_gb=6; [ -n "$players" ] && want_gb=10
avail_kb=$(awk '/MemAvailable/ {print $2}' /proc/meminfo)
[ "${FORCE:-0}" = 1 ] || [ "$avail_kb" -ge $((want_gb * 1024 * 1024)) ] \
  || die "only $((avail_kb / 1024)) MiB available, want $want_gb GiB (FORCE=1 to override)"
# Testing a stale world misses whatever players did since.
age_h=$(( ($(date +%s) - $(stat -c %Y "$src/world/level.dat")) / 3600 ))
if [ $age_h -ge 24 ]; then
  echo "!! $src is ${age_h}h old. Fresh copy, on the host:"
  echo "!!   snap=\$(ssh root@optiplex 'ls -d /srv/minecraft/.snapshots/* | tail -1')"
  echo "!!   rsync -a --delete root@optiplex:\$snap/inkwell/ $src/"
fi

echo ">> building server + mods"
server=$(nix build --no-link --print-out-paths "$REPO#server")
mods=$(nix build --no-link --print-out-paths "$REPO#serverMods")
clientRes=$(nix build --no-link --print-out-paths "$REPO#clientResources")
# Optional: older commits (e.g. testing main against a branch) don't have it.
noTurtles=$(nix build --no-link --print-out-paths "$REPO#noTurtles" 2>/dev/null || true)
loader=$(python3 -c 'import tomllib,sys; print(tomllib.load(open(sys.argv[1],"rb"))["versions"]["neoforge"])' "$REPO/pack/pack.toml")

# The client-only half of the pack never loads headless without --players;
# a static dependency check is the part of a client launch that's free.
echo ">> checking client mod dependencies"
clientDeps=$(python3 -B "$REPO/scripts/check_deps.py" "$clientRes/mods" "$REPO/pack" || true)

runs="$(dirname "$src")/runs"
run="$runs/$(date +%Y%m%dT%H%M%S)"
mkdir -p "$runs"
# Runs are ~5 GiB once the server rewrites chunks; keep the newest few
# passing ones. Failed runs are evidence and stay until deleted by hand.
ls -d "$runs"/*/ 2>/dev/null | sort | while read -r d; do [ -f "$d/PASS" ] && echo "$d"; done \
  | head -n -$(( ${KEEP_RUNS:-3} - 1 )) \
  | while read -r old; do echo ">> pruning old run $old"; rm -rf "$old"; done || true
mkdir -p "$run/spark"
echo ">> copying $src -> $run/server"
# Reflink makes this instant on btrfs; plain cp is the slow fallback.
if [ $emptyWorld = 1 ]; then
  mkdir -p "$run/server"
  for f in "$src"/* "$src"/.[!.]*; do
    [ -e "$f" ] || continue
    case "$(basename "$f")" in world|crash-reports|logs) continue ;; esac
    cp -a --reflink=auto "$f" "$run/server/"
  done
else
  cp -a --reflink=auto "$src" "$run/server"
fi
cd "$run/server"
# nix-minecraft deploys some files as store symlinks; copied, they stay
# read-only links that would block the edits below.
find . -maxdepth 1 -type l -delete
rm -rf mods logs/latest.log logs/debug.log world/session.lock
# A real dir of symlinks so single jars can be left out. Ready Player Fun
# freezes the world once nobody has been online for a while, and most of
# this run has nobody online.
mkdir mods
for jar in "$mods"/*; do
  base=$(basename "$jar")
  case "$base" in readyplayerfun-*) continue ;; esac
  for pat in ${SKIP:-}; do
    # shellcheck disable=SC2254
    case "$base" in $pat) continue 2 ;; esac
  done
  ln -s "$jar" mods/
done
# Same as the deployed server's nix-minecraft `files` entry.
rm -rf world/datapacks/no-turtles
if [ -n "$noTurtles" ]; then
  mkdir -p world/datapacks
  cp -r --no-preserve=mode "$noTurtles" world/datapacks/no-turtles
fi
[ $freshNether = 1 ] && rm -rf world/DIM-1
echo "eula=true" > eula.txt   # also the "new crash report" reference time
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
# The watchdog would kill the server during a deliberately heavy spot.
setprop max-tick-time -1

# `--` matters: without it rcon-cli eats spark's own flags as its options.
rc() { rcon-cli --port $RCON_PORT --password $RCON_PASS -- "$@" </dev/null 2>&1 | sed -E 's/§.//g; s/\x1b\[[0-9;]*m//g' || true; }
# spark replies asynchronously, after RCON has returned, so its output never
# reaches us (its commands still run). `neoforge tps` is synchronous.
tps() { rc neoforge tps | grep -iE 'overall|overworld|nether' | tr -s ' \n' ' ' || true; }
ents() { rc execute if entity "@e[$1]" | grep -oE 'count: [0-9]+' | grep -oE '[0-9]+' || echo 0; }
spot_lines() { grep -vE '^\s*(#|$)' "$SPOTS" || true; }
forceload() { # add|remove dim x z r
  local b=$(( $5 * 16 ))
  rc execute in "$2" run forceload "$1" $(($3 - b)) $(($4 - b)) $(($3 + b)) $(($4 + b)) >/dev/null
}

spid=; cpid=
start_server() {
  # Otherwise a reboot sees the previous boot's "Done (" and returns early.
  rm -f logs/latest.log
  "$server/bin/minecraft-server" -Xms2G -Xmx5G -XX:+UseG1GC -Djava.awt.headless=true \
    </dev/null >>server.out 2>&1 &
  spid=$!
  local t0=$(date +%s)
  until grep -q 'Done (' logs/latest.log 2>/dev/null; do
    kill -0 $spid 2>/dev/null || return 1
    [ $(( $(date +%s) - t0 )) -lt 900 ] || return 1
    sleep 2
  done
  boot_s=$(( $(date +%s) - t0 ))
}
stop_client() {
  # The client runs in its own process group (launched under set -m), so
  # one signal stops xvfb-run, Xvfb, portablemc and the game JVM together.
  [ -n "$cpid" ] || return 0
  kill -- -"$cpid" 2>/dev/null || true
  wait "$cpid" 2>/dev/null || true
  cpid=
}
stop_server() {
  rc stop >/dev/null 2>&1 || true
  for _ in $(seq 120); do kill -0 $spid 2>/dev/null || return 0; sleep 1; done
  echo "!! server did not stop, killed"; kill -9 $spid 2>/dev/null || true
}
cleanup() { stop_client; [ -n "$spid" ] && kill -0 $spid 2>/dev/null && stop_server; true; }
trap cleanup EXIT INT TERM

echo ">> starting server (log: $run/server/logs/latest.log)"
booted=0; boot_s=0
if start_server; then booted=1; else echo "!! server failed to boot"; fi
: > "$run/spots.out"; : > "$run/sprint.out"; : > "$run/players.out"; : > "$run/crash.out"

if [ $booted = 1 ]; then
  echo ">> booted in ${boot_s}s"
  # Reference with nothing but spawn loaded.
  sleep 10
  [ $spots = 1 ] && echo "idle $(tps)" >> "$run/spots.out"

  # --- spots: one at a time, steady-state tps and entity counts ---
  if [ $spots = 1 ]; then
    while read -r dim x z r label _; do
      echo ">> spot $label ($dim $x $z r=$r)"
      forceload add "$dim" "$x" "$z" "$r"
      # Loading (disk, chunk upgrades, contraption assembly) isn't the
      # steady state players see; let it settle first.
      sleep 10
      [ $profile = 1 ] && rc spark profiler start --thread '*' --interval 2 >/dev/null
      sleep "$seconds"
      # World-wide counts: the previous spot is unloaded by now.
      echo "$label $(tps) items=$(ents type=minecraft:item) others=$(ents type=!minecraft:item)" >> "$run/spots.out"
      if [ $profile = 1 ]; then
        # Marker, not a file count: spark picks its own filename.
        touch .profile-marker
        rc spark profiler stop --save-to-file --comment "$label" >/dev/null
        saved=""
        for _ in $(seq 60); do
          saved=$(find config/spark -name '*.sparkprofile' -newer .profile-marker | head -1)
          [ -n "$saved" ] && break
          sleep 1
        done
        if [ -n "$saved" ]; then sleep 2; mv "$saved" "$run/spark/$label.sparkprofile"
        else echo "!! no profile saved for $label"; fi
      fi
      forceload remove "$dim" "$x" "$z" "$r"
    done < <(spot_lines)
  fi

  # --- sprint: every spot loaded, as many ticks as the CPU allows ---
  if [ "$sprint" -gt 0 ]; then
    echo ">> sprint $sprint ticks with every spot loaded"
    while read -r dim x z r _; do forceload add "$dim" "$x" "$z" "$r"; done < <(spot_lines)
    sleep 15
    e0=$(grep -ac '/ERROR\]' logs/latest.log || true)
    ents0="items=$(ents type=minecraft:item) others=$(ents type=!minecraft:item)"
    t0=$(date +%s%N)
    rc tick sprint "$sprint" >/dev/null
    # The "Sprint completed" reply comes after RCON has returned, so poll
    # `tick query`, which is synchronous, until the sprint is over.
    sprint_ok=0
    for _ in $(seq 1800); do
      sleep 1
      if ! rc tick query | grep -qi sprint; then sprint_ok=1; break; fi
    done
    t1=$(date +%s%N)
    [ $sprint_ok = 1 ] || rc tick sprint stop >/dev/null
    ms=$(( (t1 - t0) / 1000000 ))
    e1=$(grep -ac '/ERROR\]' logs/latest.log || true)
    {
      echo "ticks: $sprint$([ $sprint_ok = 1 ] || echo ' (STOPPED after 30 min)')"
      # The server's own figure is exact; the wall clock includes polling.
      grep -a 'Sprint completed' logs/latest.log | tail -1 | sed -E 's/.*Sprint completed with /result: /' || true
      awk -v ms=$ms 'BEGIN { printf "wall: %.1fs\n", ms/1000 }'
      echo "entities: before $ents0 after items=$(ents type=minecraft:item) others=$(ents type=!minecraft:item)"
      echo "errors-during: $((e1 - e0))"
    } >> "$run/sprint.out"
    while read -r dim x z r _; do forceload remove "$dim" "$x" "$z" "$r"; done < <(spot_lines)
  fi

  # --- players: a real client per player, their own data ---
  if [ -n "$players" ]; then
    # uuid name for every playerdata file whose owner usercache knows.
    # (Offline-mode leftovers from earlier tests aren't in it.)
    mapfile -t picked < <(python3 - "$players" <<'EOF'
import json, os, sys
sel = sys.argv[1]
names = {e["uuid"]: e["name"] for e in json.load(open("usercache.json"))}
files = {f[:-4]: os.path.getmtime(f"world/playerdata/{f}")
         for f in os.listdir("world/playerdata") if f.endswith(".dat")}
# A name can own several files (online and offline-mode UUIDs from
# different periods); test the one that was played most recently.
best = {}
for u, m in files.items():
    if u in names and (names[u] not in best or m > files[best[names[u]]]):
        best[names[u]] = u
known = {u: files[u] for u in best.values()}
if sel == "all":
    keep = known
elif sel.startswith("active"):
    days = float(sel.split(":")[1]) if ":" in sel else 30
    # Relative to the newest save, not to now, so an older copy still
    # picks the players who were active when it was taken.
    newest = max(known.values())
    keep = {u: m for u, m in known.items() if newest - m <= days * 86400}
else:
    want = set(sel.split(","))
    keep = {u: m for u, m in known.items() if names[u] in want}
    for missing in want - {names[u] for u in keep}:
        print(f"MISSING {missing}")
for u in sorted(keep, key=lambda u: names[u].lower()):
    print(u, names[u])
EOF
)
    # Client game dir, reused for every player: the pack's client mods and
    # default configs, the same set packwiz-installer gives a player.
    game="$run/client"
    cache="$(dirname "$src")/client-cache"   # ~1 GiB of vanilla/libs, shared
    mkdir -p "$game/mods" "$cache"
    cp -L "$clientRes"/mods/* "$game/mods/"
    cp -r "$REPO/pack/config" "$game/"
    chmod -R u+w "$game"
    # Low render distance and no first-run prompts: less for software GL to
    # do before the join. Duplicate keys crash the client, so write once.
    printf 'onboardAccessibility:false\nnarrator:0\nrenderDistance:4\nsimulationDistance:5\ntutorialStep:none\nskipMultiplayerWarning:true\njoinedFirstServer:true\n' > "$game/options.txt"

    for line in "${picked[@]}"; do
      read -r uuid name <<<"$line"
      if [ "$uuid" = MISSING ]; then echo "player $name FAIL (no playerdata)" >> "$run/players.out"; continue; fi
      # Offline mode derives the UUID from the name, so copy the player's
      # online data there to join with their inventory and position.
      off=$(python3 -c 'import hashlib,sys,uuid; h=bytearray(hashlib.md5(b"OfflinePlayer:"+sys.argv[1].encode()).digest()); h[6]=h[6]&0x0f|0x30; h[8]=h[8]&0x3f|0x80; print(uuid.UUID(bytes=bytes(h)))' "$name")
      [ "$uuid" = "$off" ] || cp "world/playerdata/$uuid.dat" "world/playerdata/$off.dat"
      echo ">> player $name: launching client"
      mark=$(wc -l < logs/latest.log)
      newlog() { tail -n +$((mark + 1)) logs/latest.log; }
      rm -rf "$game/debug" "$game/logs"
      # set -m puts the background job in its own process group (no setsid
      # in the devshell), so stop_client can kill the whole tree.
      set -m
      LIBGL_ALWAYS_SOFTWARE=1 xvfb-run -a -s "-screen 0 1280x720x24" \
        portablemc --main-dir "$cache" start --mc-dir "$game" --jvm "$(command -v java)" \
          --jvm-arg=-Xmx4G -u "$name" --join-server 127.0.0.1 --join-server-port $PORT \
          "neoforge::$loader" >"$run/client-$name.out" 2>&1 &
      cpid=$!
      set +m
      t0=$(date +%s); result=; why=
      # Refused during login (configuration phase), the server names the
      # player only as "GameProfile[...name=X,...] lost connection", so
      # match that too, and the client's own reason, which is the useful one.
      refused() {
        newlog | grep -qE "($name|name=$name,.*) (lost connection|left the game)" && return 0
        grep -aq 'Client disconnected with reason' "$game/logs/latest.log" 2>/dev/null
      }
      until newlog | grep -q "$name joined the game"; do
        if ! kill -0 $cpid 2>/dev/null; then result=FAIL; why="client exited before joining"; break; fi
        if refused; then
          result=FAIL
          why="refused: $(grep -a 'Client disconnected with reason' "$game/logs/latest.log" 2>/dev/null | tail -1 | sed -E 's/.*with reason: //' | cut -c1-160)"
          break
        fi
        # A client normally gets in within ~60s on software GL.
        if [ $(( $(date +%s) - t0 )) -ge 300 ]; then result=FAIL; why="no join after 5 min"; break; fi
        sleep 3
      done
      if [ -z "$result" ]; then
        echo ">> player $name joined after $(( $(date +%s) - t0 ))s, holding ${hold}s"
        t0=$(date +%s); result=PASS
        while [ $(( $(date +%s) - t0 )) -lt "$hold" ]; do
          if newlog | grep -q "$name lost connection"; then result=FAIL; why="disconnected after $(( $(date +%s) - t0 ))s"; break; fi
          kill -0 $cpid 2>/dev/null || { result=FAIL; why="client died after $(( $(date +%s) - t0 ))s"; break; }
          sleep 2
        done
      fi
      # Kick first: killing a connected client makes the server log a
      # "Connection reset by peer" error that would read as a regression.
      rc kick "$name" >/dev/null; sleep 2
      stop_client
      echo "player $name $result${why:+ ($why)}" >> "$run/players.out"
      echo ">> player $name: $result${why:+ ($why)}"
      if [ $result = FAIL ]; then
        # The client writes a full report for packet errors; it names the mod.
        report=$(ls -t "$game"/debug/disconnect-*-client.txt 2>/dev/null | head -1 || true)
        { echo "### $name"; newlog | grep -aE "$name lost connection" | tail -1 || true
          if [ -n "$report" ]; then sed -n '1,60p' "$report"
          else grep -aE '^(Exception|Caused by)|/ERROR\]' "$run/client-$name.out" | head -10 || true
               grep -aE 'ERROR|Exception|Caused by|disconnect' "$game/logs/latest.log" 2>/dev/null | tail -20 || true; fi
        } >> "$run/player-failures.txt"
        cp -r "$game/logs" "$run/client-logs-$name" 2>/dev/null || true
      fi
      sleep 3   # let the server save and drop the player before the next
    done
  fi

  rc spark health show --memory > "$run/health.txt" || true
  if [ $crash = 0 ]; then
    rc save-all flush >/dev/null || true
    stop_server
  fi
fi
cp logs/latest.log "$run/server.log" 2>/dev/null || true
log="$run/server.log"

# --- crash: kill -9 mid-save, reboot, read everything back ---
if [ $booted = 1 ] && [ $crash = 1 ]; then
  echo ">> crash: kill -9 during save-all"
  # Make the save non-trivial: load every spot, then save while it runs.
  while read -r dim x z r _; do forceload add "$dim" "$x" "$z" "$r"; done < <(spot_lines)
  sleep 15
  rc save-all flush >/dev/null &
  sleep 0.5
  kill -9 $spid; wait $spid 2>/dev/null || true
  spid=
  rm -f world/session.lock
  echo ">> crash: rebooting"
  if start_server; then
    # Forceloads persist in the world; wait for them, then read chunks.
    while read -r dim x z r _; do forceload add "$dim" "$x" "$z" "$r"; done < <(spot_lines)
    sleep 20
    stop_server
    cp logs/latest.log "$run/crash.log"
    echo "reboot: ok" >> "$run/crash.out"
    echo "chunk-load-problems: $(grep -acE 'Skipping BlockEntity|Failed to (load|read) chunk|Couldn.t load chunk|Exception loading (block )?entit|is in the wrong location|Chunk file at .* is in the wrong location|Error reading chunk|corrupt' "$run/crash.log" || true)" >> "$run/crash.out"
  else
    echo "reboot: FAILED" >> "$run/crash.out"
  fi
  # A truncated gzip is what a torn write of an .dat looks like.
  bad=$(python3 - <<'EOF'
import gzip, os
bad = 0
for d in ("world/playerdata", "world/data"):
    for f in os.listdir(d):
        if f.endswith(".dat"):
            try: gzip.open(os.path.join(d, f)).read()
            except Exception: bad += 1; print(f"unreadable {d}/{f}", flush=True)
print(bad)
EOF
)
  echo "$bad" | grep '^unreadable' >> "$run/crash.out" || true
  echo "unreadable-dat: $(echo "$bad" | tail -1)" >> "$run/crash.out"
fi
trap - EXIT

# -a: mod output puts non-UTF-8 bytes in the log, and grep then treats it as
# binary and prints nothing for -o.
count() { grep -acE "$1" "$log" 2>/dev/null || true; }
error_fingerprints() { # log
  awk '
    function flush() {
      if (head != "") {
        gsub(/[a-z0-9_.-]+:[a-z0-9_.\/-]+/, "<id>", head); gsub(/[0-9]+/, "N", head)
        print substr(head, 1, 140) (frame != "" ? " @ " frame : "")
      }
      head = ""; frame = ""; gotex = 0; inerr = 0; nframes = 0; lastmod = ""
    }
    /^\[[^]]*\] \[[^]]*\/ERROR\]/ {
      flush()
      msg = $0; sub(/^\[[^]]*\] \[[^]]*\] \[[^]]*\]: /, "", msg)
      head = msg; inerr = 1; next
    }
    /^\[/ { flush(); next }
    # The innermost exception line names the failure better than the log
    # message ("Exception caught during firing event: ..."), so it replaces it.
    inerr && !gotex && /^(Caused by: )?[a-zA-Z_.$]+(Exception|Error|Throwable)/ {
      head = $0; sub(/^Caused by: /, "", head); gotex = 1; next
    }
    # First two frames from different mod jars (thrower <- caller), e.g.
    # "chunky ChunkyProvider.get <- distanthorizons ChunkyAccessor...":
    # TRANSFORMER/<modid>@<ver>/<class>.<method>(
    inerr && nframes < 2 && match($0, /TRANSFORMER\/[a-z0-9_]+@[^\/]+\/[A-Za-z0-9_.$]+/) {
      f = substr($0, RSTART + 12, RLENGTH - 12)
      mod = f; sub(/@.*/, "", mod)
      if (mod != "minecraft" && mod != "neoforge" && mod != lastmod) {
        sub(/@[^\/]+\//, " ", f); sub(/^[^ ]+ ([a-z0-9_]+\.)+/, mod " ", f)
        frame = frame (nframes ? " <- " : "") f; nframes++; lastmod = mod
      }
    }
    END { flush() }
  ' "$1" 2>/dev/null
}
newcrash=$(find crash-reports -newer eula.txt -type f 2>/dev/null | wc -l || true)
# Deliberately terse: this is what gets diffed between runs and read by eye.
# Everything above "# tps" is compared exactly by test-diff.sh, so it holds
# only counts and pass/fail, never timings.
{
  echo "booted: $booted"
  echo "mods: $(ls mods | wc -l)"
  echo "client-deps: $(echo "$clientDeps" | tail -1)"
  echo "$clientDeps" | grep -E '^(MISSING|VERSION)' || true
  echo "crash-reports-new: $newcrash"
  echo "errors: $(count '/ERROR\]')"
  echo "warns: $(count '/WARN\]')"
  echo "mixin-failures: $(count 'Mixin apply for mod .* failed|InvalidInjectionException|InvalidMixinException')"
  echo "missing-deps: $(count 'Missing or unsupported mandatory dependencies')"
  echo "missing-registry: $(count 'Missing registr|Unidentified mapping|missing mapping')"
  echo "cant-keep-up: $(count "Can't keep up")"
  # Block entities or entities the new jars can't read get silently dropped
  # on the next save, so these must be zero (or understood) before deploying.
  echo "chunk-load-problems: $(count 'Skipping BlockEntity|Tried to load a DUMMY block entity|Failed to (load|read) chunk|Couldn.t load chunk|Exception loading (block )?entit|Failed to create block entity|is in the wrong location')"
  echo
  echo "# error sources (logger -> count)"
  grep -aoE '/ERROR\] \[[^]]+\]' "$log" 2>/dev/null | sort | uniq -c | sort -rn | head -15 || true
  echo
  # The logger alone hides who failed: a mod exception thrown inside an event
  # listener is logged by the event bus (DH 3.3.3 calling into a missing
  # Chunky showed up only as "EventBus x1" and got baselined). Each ERROR
  # plus its stack trace becomes "exception: message @ mod frames", with ids
  # and numbers masked so the same failure matches across runs.
  echo "# error fingerprints (exception @ first mod frame -> count)"
  error_fingerprints "$log" | sort | uniq -c | sort -rn | head -40 || true
  if [ -s "$run/players.out" ]; then echo; echo "# players"; cat "$run/players.out"; fi
  if [ -s "$run/crash.out" ]; then echo; echo "# crash (kill -9 mid-save, reboot)"; cat "$run/crash.out"; fi
  if [ -s "$run/spots.out" ]; then echo; echo "# tps per spot (neoforge tps, after settling)"; cat "$run/spots.out"; fi
  if [ -s "$run/sprint.out" ]; then echo; echo "# sprint (every spot loaded)"; cat "$run/sprint.out"; fi
  if [ $profile = 1 ] && [ -n "$(find "$run/spark" -name '*.sparkprofile' | head -1)" ]; then
    echo
    echo "# server-thread cost per spot (scripts/spark_top.py)"
    python3 "$REPO/scripts/spark_top.py" --top 5 "$run"/spark/*.sparkprofile || true
  fi
} > "$run/summary.txt"

# --- verdict ---
fails=()
[ $booted = 1 ] || fails+=("server did not boot")
[ "$newcrash" = 0 ] || fails+=("$newcrash new crash report(s) in $run/server/crash-reports")
for k in mixin-failures missing-deps missing-registry chunk-load-problems; do
  v=$(grep -m1 "^$k: " "$run/summary.txt" | awk '{print $2}')
  [ "${v:-0}" = 0 ] || fails+=("$k: $v")
done
grep -q ' FAIL' "$run/players.out" && fails+=("player(s) failed to stay connected: see $run/player-failures.txt")
if [ -s "$run/crash.out" ]; then
  grep -q 'reboot: FAILED' "$run/crash.out" && fails+=("crash: server did not reboot")
  grep -qE '^(chunk-load-problems|unreadable-dat): [1-9]' "$run/crash.out" && fails+=("crash: data damaged, see summary")
fi
grep -q "^sprint.*STOPPED" "$run/sprint.out" && fails+=("sprint did not finish in 30 min")
fp() { sed -n '/^# error fingerprints/,/^$/p' "$1" | grep -E '^ +[0-9]+ ' | sed -E 's/^ +[0-9]+ //' | sort -u || true; }
if [ -f "$BASELINE" ]; then
  # Only meaningful against a baseline from the same checks: --players
  # adds client-triggered errors, --sprint whatever the farms throw.
  newfp=$(comm -13 <(fp "$BASELINE") <(fp "$run/summary.txt"))
  if [ -n "$newfp" ]; then
    fails+=("error fingerprints not in test/baseline/summary.txt:")
    while IFS= read -r l; do fails+=("  $l"); done <<<"$newfp"
  fi
fi

echo
cat "$run/summary.txt"
echo
echo ">> run: $run"
if [ ${#fails[@]} = 0 ]; then
  touch "$run/PASS"
  echo "PASS"
else
  echo "FAIL:"; printf '  %s\n' "${fails[@]}"
  exit 1
fi
