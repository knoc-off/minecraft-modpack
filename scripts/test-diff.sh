#!/usr/bin/env bash
#
# Compare two test-server.sh summaries. Counts and logger names are compared
# exactly; ms/tick per spot is shown side by side, since timing noise between
# runs makes an exact diff meaningless.
#
# Usage: scripts/test-diff.sh <run or summary.txt A> <run or summary.txt B>
set -euo pipefail

sum() { [ -d "$1" ] && echo "$1/summary.txt" || echo "$1"; }
a=$(sum "$1"); b=$(sum "$2")

echo "# counts and error sources (- A, + B)"
# diff exits 1 whenever there is a difference, which pipefail would turn into
# the "identical" branch.
changes=$(diff <(sed '/^# tps/,$d' "$a") <(sed '/^# tps/,$d' "$b") | grep -E '^[<>]' \
  | sed 's/^</-/; s/^>/+/' || true)
echo "${changes:-(identical)}"

echo
echo "# overall ms/tick per spot: A -> B"
ms() { sed -n '/^# tps/,$p' "$1" | grep -v '^#' \
  | sed -E 's/^([^ ]+) .*Overall: [0-9.]+ TPS \(([0-9.]+) ms\/tick\).*/\1 \2/'; }
join <(ms "$a" | sort) <(ms "$b" | sort) \
  | awk '{ d = $3 - $2; printf "%-28s %7.2f -> %7.2f  (%+.2f)\n", $1, $2, $3, d }'
