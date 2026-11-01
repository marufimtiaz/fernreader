#!/usr/bin/env bash
# bench/robust_r009.sh — R-009 kill loop (M9 Task 9.1): open War and Peace, then 20 times: turn a page, kill the app
# (am force-stop) right after the turn's relocate, start it again and reopen the book. The reopened position may lag
# the last page reached by at most one page (the turn in flight). Benchmark build (bench commands); not a timing gate.
# Usage: bench/robust_r009.sh [iterations]   (prints one line per iteration and "R-009 kill loop: PASS|FAIL")
RESULTS=/dev/null source "$(dirname "$0")/lib.sh"
N=${1:-20}
cfis() { $A logcat -d -s EPR:I | awk '/EVT relocate /{ print $NF }'; }
reopen() { # start the app cold and open the book; prints the first relocate's CFI
  $A logcat -c
  $A shell am start -W -n "$PKG/$ACT" >/dev/null; sleep 2
  bench_cmd openPath "$WP"
  wait_ev relocate 20 || { echo "NA"; return; }
  sleep 1.5
  cfis | head -1
}
$A shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
$A shell am force-stop "$PKG"
here=$(reopen)
[ "$here" != NA ] || { echo "R-009: the book did not open"; exit 1; }
fails=0; lag0=0; lag1=0
for i in $(seq 1 "$N"); do
  $A logcat -c
  bench_cmd next
  if ! wait_ev relocate 5; then echo "  $i: no relocate after next"; fails=$((fails + 1)); continue; fi
  reached=$(cfis | tail -1)
  $A shell am force-stop "$PKG" # right after the page change
  got=$(reopen)
  if [ "$got" = "$reached" ]; then lag0=$((lag0 + 1)); verdict="lag 0"
  elif [ "$got" = "$here" ]; then lag1=$((lag1 + 1)); verdict="lag 1 (the turn in flight was lost)"
  else fails=$((fails + 1)); verdict="FAIL (before=$here reached=$reached reopened=$got)"; fi
  echo "  $i: $verdict"
  here=$got
done
echo "R-009 kill loop: $N iterations, lag 0: $lag0, lag 1: $lag1, failures: $fails"
$A shell am force-stop "$PKG"
[ "$fails" -eq 0 ] && echo "R-009 kill loop: PASS" || { echo "R-009 kill loop: FAIL"; exit 1; }
