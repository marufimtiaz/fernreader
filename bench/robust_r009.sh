#!/usr/bin/env bash
# bench/robust_r009.sh — R-009 kill loop (M9 Task 9.1): open War and Peace, then 20 times: turn two pages, kill the app
# (am force-stop) 0, 50, 200 or 500 ms after the second turn's relocate (cycling), start it again and reopen the book.
# The reopened position must be the last page reached or the one before (lag <= 1: only the turn in flight may be
# lost); the page before both turns (an app that saves only on close or pause) fails. Benchmark build; not a gate.
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
turn() { # one page forward; prints the CFI it reached, or NA
  local before; before=$(cfis | wc -l); bench_cmd next
  local end=$(( $(date +%s) + 5 ))
  until [ "$(cfis | wc -l)" -gt "$before" ]; do [ "$(date +%s)" -ge "$end" ] && { echo NA; return; }; sleep 0.05; done
  cfis | tail -1
}
DELAYS=(0 0.05 0.2 0.5)
for i in $(seq 1 "$N"); do
  $A logcat -c
  first=$(turn); second=$(turn)
  if [ "$first" = NA ] || [ "$second" = NA ]; then echo "  $i: a turn did not land"; fails=$((fails + 1)); continue; fi
  d=${DELAYS[$(( (i - 1) % 4 ))]}; sleep "$d"
  $A shell am force-stop "$PKG"
  got=$(reopen)
  if [ "$got" = "$second" ]; then lag0=$((lag0 + 1)); verdict="lag 0"
  elif [ "$got" = "$first" ]; then lag1=$((lag1 + 1)); verdict="lag 1 (the turn in flight was lost)"
  else fails=$((fails + 1)); verdict="FAIL (before=$here first=$first second=$second reopened=$got)"; fi
  echo "  $i (kill +${d}s): $verdict"
  here=$got
done
echo "R-009 kill loop: $N iterations, lag 0: $lag0, lag 1: $lag1, failures: $fails"
$A shell am force-stop "$PKG"
[ "$fails" -eq 0 ] && echo "R-009 kill loop: PASS" || { echo "R-009 kill loop: FAIL"; exit 1; }
