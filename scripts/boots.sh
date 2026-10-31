#!/usr/bin/env bash
#
# Boot the same pack N times and tally how often it crashes. For failures
# that only show up in some boots (Create's "unbound value" during
# registration hit ~1 in 8), where one clean boot proves nothing.
#
# Usage (inside `nix develop`):
#   scripts/boots.sh <datadir> N [extra scripts/test.sh args]
#
# Each boot is a plain scripts/test.sh run, so crashed runs are kept in full
# under <datadir>/../runs/. Prints one line per boot and a tally:
#   CRASH  no boot or a new crash report (the thing being counted)
#   FAIL   booted, but test.sh failed for another reason (e.g. a new error
#          fingerprint; expected while the baseline predates new mods)
#   PASS
set -uo pipefail
export LC_ALL=C

src="${1:?usage: $0 <datadir> N [test.sh args]}"; n="${2:?N}"; shift 2
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
crash=0; fail=0; pass=0
for i in $(seq "$n"); do
  out=$("$here/test.sh" "$src" "$@" 2>&1)
  run=$(sed -n 's/^>> run: //p' <<<"$out" | tail -1)
  if grep -qE '^(booted: 0|crash-reports-new: [1-9])' <<<"$out"; then
    crash=$((crash + 1))
    # The innermost cause names the failure; the crash report has the rest.
    why=$(grep -ahm1 -E '^(Caused by: |\s*Exception message: )' "$run"/server/crash-reports/*.txt "$run/server.log" 2>/dev/null \
      | sed -E 's/^\s*(Caused by: |Exception message: )//' | cut -c1-160)
    echo "boot $i/$n CRASH $run ${why:-}"
  elif [ -n "$run" ] && [ -f "$run/PASS" ]; then
    pass=$((pass + 1)); echo "boot $i/$n PASS"
  else
    fail=$((fail + 1))
    echo "boot $i/$n FAIL ${run:-} $(grep -A1 '^FAIL:' <<<"$out" | tail -1 | sed 's/^ *//' | cut -c1-120)"
  fi
done
echo "tally: $crash crash, $fail fail, $pass pass of $n"
[ $crash = 0 ]
