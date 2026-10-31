#!/usr/bin/env bash
#
# Run scripts/test.sh / scripts/boots.sh on a bigger machine, detached, so a
# laptop lid or a dropped ssh doesn't kill an hour-long loop.
#
# Usage (from this repo, on any host with ssh to <host>):
#   scripts/remote.sh <host> sync                    copy the tree and build, no run
#   scripts/remote.sh <host> start <script> [args]   queue a job, e.g. boots.sh @snap 20 --empty-world
#   scripts/remote.sh <host> status                  jobs done/running/queued, one line each
#   scripts/remote.sh <host> log                     follow the running job's full output
#   scripts/remote.sh <host> snap                    fresh live-world copy -> <host>:mcwork/inkwell
#
#   REF=main ...      test that commit's pack instead of the working tree
#                     (scripts/ always come from the working tree, so old refs
#                     get the current harness)
#   ENV="K=V ..."     environment for the job (e.g. SKIP=..., FML_THREADS=1)
#
# Jobs run one at a time in the order queued. "@snap" in the args is the
# host's mcwork/inkwell. Each job logs to <host>:mcwork/jobs/<n>.log; runs
# land in mcwork/runs/ (test.sh keeps failed ones).
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
host="${1:?usage: $0 <host> sync|start|status|log|snap ...}"; cmd="${2:?sync|start|status|log|snap}"; shift 2
# The remote login shell may not be bash (desktop's is fish).
rsh() { ssh "$host" "bash -c $(printf %q "$1")"; }

# Remote worker: runs queued jobs in order until the queue is empty.
WORKER='
cd ~/mcwork/jobs
exec 9>worker.lock; flock -n 9 || exit 0
while j=$(ls *.job 2>/dev/null | sort -n | head -1); [ -n "$j" ]; do
  n=${j%.job}; mv "$j" "$n.running"
  { echo ">> $(head -1 "$n.running") ($(date))"; bash "$n.running"; echo ">> exit $? ($(date))"; } > "$n.log" 2>&1
  mv "$n.running" "$n.done"
done'

case "$cmd" in
  snap)
    snap=$(ssh root@optiplex 'ls -d /srv/minecraft/.snapshots/* | tail -1')
    echo ">> $snap -> $host:mcwork/inkwell"
    # Streamed through here: the two hosts don't trust each other's keys.
    ssh root@optiplex "tar -C $snap -cf - inkwell | zstd -T0 -3" \
      | rsh 'mkdir -p mcwork && cd mcwork && rm -rf inkwell.new && mkdir inkwell.new && zstd -d | tar -x -C inkwell.new --strip-components=1 && rm -rf inkwell && mv inkwell.new inkwell'
    ;;
  log)
    rsh 'cd mcwork/jobs && tail -n 40 -F "$(ls -t *.log | head -1)"'
    ;;
  status)
    ssh "$host" bash -s <<'EOF'
cd ~/mcwork/jobs 2>/dev/null || { echo "no jobs"; exit; }
for f in $(ls *.done *.running *.job 2>/dev/null | sort -n); do
  n=${f%.*}; st=${f##*.}; desc=$(head -1 "$f" | sed 's/^# //')
  tally=$(grep -aE '^(tally|PASS$|FAIL:)' "$n.log" 2>/dev/null | tail -1)
  prog=$(grep -acE '^(boot [0-9]|client [0-9]|>> player .*: (PASS|FAIL))' "$n.log" 2>/dev/null)
  bad=$(grep -acE '^(boot [0-9].* CRASH|client [0-9]+/[0-9]+ [A-Z]+ |>> player .*: FAIL)' "$n.log" 2>/dev/null | head -1)
  bad=$(( bad - $(grep -acE '^client [0-9]+/[0-9]+ PASS' "$n.log" 2>/dev/null) ))
  case $st in running) tally="${tally:-$prog done, $bad bad so far}" ;; esac
  printf '%-3s %-8s %-60s %s\n' "$n" "$st" "${desc:0:60}" "$tally"
done
EOF
    ;;
  start|sync)
    label=${REF:-work}
    src=$REPO
    if [ -n "${REF:-}" ]; then
      src=$(mktemp -d); trap 'rm -rf "$src"' EXIT
      git -C "$REPO" archive "$REF" | tar -x -C "$src"
      cp -r "$REPO/scripts/." "$src/scripts/"
    fi
    # No .git: the remote copy is a path flake, so uncommitted changes count.
    # .gitignore'd files stay home: the gradle builds alone are ~400 MiB.
    # A queued job reads its copy only when it starts, so one copy per job.
    n=$(rsh 'mkdir -p mcwork/jobs && cd mcwork/jobs && echo $(( $(ls | grep -oE "^[0-9]+" | sort -n | tail -1) + 1 ))')
    dir="mcwork/repo-$n-$label"
    rsync -a --delete --delete-excluded --exclude .git/ --filter=':- .gitignore' "$src/" "$host:$dir/"
    if [ "$cmd" = sync ]; then
      rsh "cd $dir && nix build --no-link .#server .#serverMods .#clientResources && nix develop -c true"
      exit
    fi
    args=$(printf '%q ' "$@"); args=${args//@snap/\$HOME/mcwork/inkwell}
    printf '# %s %s %s\ncd ~/%s && %s nix develop -c bash -c %q\n' \
      "$label" "${ENV:-}" "$*" "$dir" "${ENV:+env $ENV}" "scripts/$args" \
      | rsh "cat > mcwork/jobs/$n.job"
    rsh "nohup setsid bash -c $(printf %q "$WORKER") >/dev/null 2>&1 < /dev/null &"
    echo ">> queued job $n on $host ($label: ${ENV:+$ENV }$*); $0 $host status"
    ;;
  *) echo "unknown command $cmd" >&2; exit 2 ;;
esac
