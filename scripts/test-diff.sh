#!/usr/bin/env bash
#
# Compare two scripts/test.sh summaries. Everything above the timing sections
# (counts, error sources, fingerprints, players, crash) is compared exactly;
# ms/tick per spot and the sprint are shown side by side, since timing noise
# between runs makes an exact diff meaningless.
#
# Usage: scripts/test-diff.sh <run or summary.txt A> <run or summary.txt B>
set -euo pipefail

sum() { [ -d "$1" ] && echo "$1/summary.txt" || echo "$1"; }
a=$(sum "$1"); b=$(sum "$2")
exact() { sed -E '/^# (tps|sprint|server-thread)/,$d' "$1"; }

echo "# counts, errors, players, crash (- A, + B)"
# diff exits 1 whenever there is a difference, which pipefail would turn into
# the "identical" branch.
changes=$(diff <(exact "$a") <(exact "$b") | grep -E '^[<>]' \
  | sed 's/^</-/; s/^>/+/' || true)
echo "${changes:-(identical)}"

echo
echo "# overall ms/tick per spot: A -> B"
ms() { sed -n '/^# tps/,/^$/p' "$1" | grep 'Overall:' \
  | sed -E 's/^([^ ]+) .*Overall: [0-9.]+ TPS \(([0-9.]+) ms\/tick\).*/\1 \2/'; }
join <(ms "$a" | sort) <(ms "$b" | sort) \
  | awk '{ d = $3 - $2; printf "%-28s %7.2f -> %7.2f  (%+.2f)\n", $1, $2, $3, d }'

echo
echo "# sprint ms/tick: A -> B"
sp() { grep -m1 '^result:' "$1" | grep -oE '[0-9.]+ ms per tick' | grep -oE '^[0-9.]+' || echo "-"; }
echo "$(sp "$a") -> $(sp "$b")"
