#!/usr/bin/env bash
#
# Run scripts/test.sh or scripts/boots.sh on a bigger machine, detached, so
# a laptop lid or a dropped ssh doesn't kill a 40-minute loop.
#
# Usage (from this repo, on any host with ssh to <host>):
#   scripts/remote.sh <host> start <script> [args...]   e.g. boots.sh @snap 20 --empty-world
#   scripts/remote.sh <host> sync                       copy the tree (and build) without running
#   scripts/remote.sh <host> log                        follow the running job
#   scripts/remote.sh <host> snap                       fresh live-world copy -> <host>:mcwork/inkwell
#
#   REF=main scripts/remote.sh ...   test that commit's pack instead of the
#                                    working tree (scripts/ always come from
#                                    the working tree, so old refs get the
#                                    current harness)
#
# "@snap" in the args is replaced by the host's mcwork/inkwell. Runs land in
# <host>:mcwork/runs/ (test.sh keeps failed ones).
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
host="${1:?usage: $0 <host> start|sync|log|snap ...}"; cmd="${2:?start|sync|log|snap}"; shift 2

case "$cmd" in
  snap)
    snap=$(ssh root@optiplex 'ls -d /srv/minecraft/.snapshots/* | tail -1')
    echo ">> $snap -> $host:mcwork/inkwell"
    # Streamed through here: the two hosts don't trust each other's keys.
    ssh root@optiplex "tar -C $snap -cf - inkwell | zstd -T0 -3" \
      | ssh "$host" 'mkdir -p mcwork && cd mcwork && rm -rf inkwell.new && mkdir inkwell.new && zstd -d | tar -x -C inkwell.new --strip-components=1 && rm -rf inkwell && mv inkwell.new inkwell'
    ;;
  log)
    ssh "$host" 'tail -n 50 -F mcwork/job.log'
    ;;
  start|sync)
    label=${REF:-work}
    src=$REPO
    if [ -n "${REF:-}" ]; then
      src=$(mktemp -d); trap 'rm -rf "$src"' EXIT
      git -C "$REPO" archive "$REF" | tar -x -C "$src"
      cp -r "$REPO/scripts/." "$src/scripts/"
    fi
    [ "$cmd" = sync ] || ! ssh "$host" 'pgrep -f "scripts/(test|boots).sh" >/dev/null' \
      || { echo "!! a job is already running on $host ($0 $host log)"; exit 1; }
    # No .git: the remote copy is a path flake, so uncommitted changes count.
    # .gitignore'd files stay home: the gradle builds alone are ~400 MiB.
    rsync -a --delete --delete-excluded --exclude .git/ --filter=':- .gitignore' \
      "$src/" "$host:mcwork/repo-$label/"
    if [ "$cmd" = sync ]; then
      ssh "$host" "cd mcwork/repo-$label && nix build --no-link .#server .#serverMods .#clientResources && nix develop -c true"
      exit
    fi
    args=$(printf '%q ' "$@"); args=${args//@snap/\$HOME/mcwork/inkwell}
    ssh "$host" "cd mcwork/repo-$label && echo '>> $label: $* ($(date))' > ../job.log && nohup setsid nix develop -c bash -c 'scripts/$args' >> ../job.log 2>&1 < /dev/null &"
    echo ">> started on $host; follow with: $0 $host log"
    ;;
  *) echo "unknown command $cmd" >&2; exit 2 ;;
esac
