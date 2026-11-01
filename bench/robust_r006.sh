#!/usr/bin/env bash
# bench/robust_r006.sh — R-006 (M9 Task 9.1): read each stress book for 50 page turns on the reference emulator; the app
# must not die (no am_proc_died / am_kill / am_anr / lowmemorykiller for the package, same pid throughout). Also records
# the peak PSS of app + renderer (tools/mem_total.sh) per book for P-061/P-062 (release checks, Task 9.2).
# Benchmark build (bench commands). Usage: bench/robust_r006.sh [turns]
RESULTS=/dev/null source "$(dirname "$0")/lib.sh"
TURNS=${1:-50}
mb() { PKG=$PKG "$ROOT/tools/mem_total.sh" | awk '{ sub(/^total_mb=/, "", $3); print ($3 == "" ? 0 : $3) }'; }
$A shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
fails=0
for book in ${BOOKS:-stress-huge-single-chapter stress-2000-chapters stress-200-images}; do
  # the previous process's own am_proc_died (from force-stop) arrives a little later: wait before clearing the log
  $A shell am force-stop "$PKG"; sleep 1.5; $A logcat -c; $A logcat -b events -c
  $A shell am start -W -n "$PKG/$ACT" >/dev/null; sleep 3
  pid=$($A shell pidof "$PKG" | tr -d '\r')
  t0=$(date +%s)
  bench_cmd openPath "$B/generated/$book.epub"
  wait_ev painted 120 || echo "  $book: no first paint within 120 s"
  open_s=$(( $(date +%s) - t0 ))
  peak=$(mb)
  for i in $(seq 1 "$TURNS"); do
    bench_cmd next; sleep 0.6
    if [ $((i % 10)) = 0 ]; then m=$(mb); [ "$m" -gt "$peak" ] && peak=$m; fi
  done
  sleep 2
  now=$($A shell pidof "$PKG" | tr -d '\r')
  deaths=$($A logcat -b events -d | grep -E "am_proc_died|am_kill|am_anr|am_crash" | grep -v "force-stop" | grep -c "$PKG")
  lmk=$($A logcat -d | grep -i "lowmemorykiller\|lmkd" | grep -c "$PKG")
  turns=$($A logcat -d -s EPR:I | grep -c "EVT relocate ")
  if [ "$pid" = "$now" ] && [ "$deaths" -eq 0 ] && [ "$lmk" -eq 0 ]; then v=PASS; else v=FAIL; fails=$((fails + 1)); fi
  [ "$v" = FAIL ] && $A logcat -b events -d | grep -E "am_proc_died|am_kill|am_anr|am_crash" | grep "$PKG" | sed 's/^/    /'
  echo "  $book: $v (pid $pid -> $now, deaths $deaths, lmk $lmk, relocates $turns, first paint ${open_s} s, peak PSS ${peak} MB)"
  bench_cmd back; sleep 1
done
$A shell am force-stop "$PKG"
[ "$fails" -eq 0 ] && echo "R-006: PASS" || { echo "R-006: FAIL"; exit 1; }
