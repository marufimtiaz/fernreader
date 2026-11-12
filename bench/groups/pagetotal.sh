#!/usr/bin/env bash
# bench/groups/pagetotal.sh — M4: P-021 War and Peace, layout change (text size step) -> exact book-wide page total
# (events cmd -> page_total_exact), and the "≈ N" estimate's error against that total (page_total_estimate).
# 3 runs, +1 step each from 100 % (C-108 c: each a new layout; a layout counted before is read back, not counted).
# Screen pages shown: the counter runs only then (C-108 c).
source "$(dirname "$0")/../lib.sh"
$A shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
$A shell am force-stop "$PKG"; $A shell am start -W -n "$PKG/$ACT" >/dev/null; sleep 3
bench_cmd resetSettings; sleep 1
bench_cmd pageNumbers screen; sleep 1
bench_cmd openPath "$WP"; sleep 10 # first layout counted, section stats cached
ev_val() { $A logcat -d -s EPR:I | awk -v e="EVT $1 " 'index($0, e) { match($0, /total=[0-9]+/); v=substr($0, RSTART+6, RLENGTH-6) } END { print (v == "" ? "NA" : v) }'; }
for RUN in 1 2 3; do
  step=1
  $A logcat -c
  bench_cmd fontStep "$step"; sleep 9
  emit P-021 "$(ev_delta cmd page_total_exact)" "fontStep $step"
  est=$(ev_val page_total_estimate); ex=$(ev_val page_total_exact)
  emit P-021.estimate_error_pct "$(awk -v a="$est" -v b="$ex" 'BEGIN { if (a ~ /^[0-9]+$/ && b ~ /^[0-9]+$/ && b > 0) { d = (a - b) / b * 100; if (d < 0) d = -d; printf "%.2f", d } else print "NA" }')" "estimate=$est exact=$ex"
done
bench_cmd resetSettings; sleep 1; bench_cmd back
