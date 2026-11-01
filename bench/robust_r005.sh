#!/usr/bin/env bash
# bench/robust_r005.sh — R-005 (M9 Task 9.1): Back 1 s after opening the huge chapter; the library must be drawn within
# 200 ms. Measured in the app (benchmark build probes): the `back` bench command's arrival on the main thread -> the
# first frame drawn with the library (EVT home_frame since_back_key=<ms>). A real key can't be timed this way on API 33+
# (predictive back handles it before the window sees it; `input keyevent` itself takes ~0.2-0.5 s to start), and the
# command arrives on the same main thread, so a blocked main thread still shows in the value. Before each
# trial a small book is opened, so the huge one is never the warm session (every trial is a cold open). Median of 3.
# Also counts ANRs (am_anr) during the runs. Usage: bench/robust_r005.sh
RESULTS=/dev/null source "$(dirname "$0")/lib.sh"
HUGE=$B/generated/stress-huge-single-chapter.epub
SMALL=$B/generated/no-cover.epub
$A shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
$A shell am force-stop "$PKG"; $A logcat -b events -c
$A shell am start -W -n "$PKG/$ACT" >/dev/null; sleep 4
vals=()
for RUN in 1 2 3; do
  bench_cmd openPath "$SMALL"; sleep 3; bench_cmd back; sleep 1
  $A logcat -c
  bench_cmd openPath "$HUGE"; sleep 1
  bench_cmd back
  wait_ev home_frame 10 || { echo "  run $RUN: no home_frame"; vals+=(999999); continue; }
  v=$($A logcat -d -s EPR:I | awk '/EVT home_frame /{ for (i = 1; i <= NF; i++) if ($i ~ /^since_back_key=/) { sub(/since_back_key=/, "", $i); print $i } }' | head -1)
  echo "  run $RUN: Back -> library frame ${v} ms"
  vals+=("${v:-999999}")
  sleep 4 # the engine finishes or drops the huge load
done
anr=$($A logcat -b events -d | grep "am_anr" | grep -c "$PKG")
med=$(printf '%s\n' "${vals[@]}" | sort -n | sed -n 2p)
$A shell am force-stop "$PKG"
echo "R-005: Back -> library ${vals[*]} ms, median $med (Must 200); ANRs: $anr"
[ "$med" -le 200 ] && [ "$anr" -eq 0 ] && echo "R-005: PASS" || { echo "R-005: FAIL"; exit 1; }
