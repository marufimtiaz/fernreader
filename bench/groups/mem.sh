#!/usr/bin/env bash
# bench/groups/mem.sh — P-060: PSS of app + renderer while reading War and Peace (tools/mem_total.sh, as in S2).
# M4: the page counter lays out the whole book after an open; the renderer keeps that memory until it releases it,
# ~30 s later. Gated value = steady reading (after the release), M4.md question Q1; the reading right after the
# turns (during/after counting) is recorded in the note of each row.
# M9 (--release) adds P-061 (huge chapter) and P-062 (50 plates of the 200-image book).
source "$(dirname "$0")/../lib.sh"
$A shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
$A shell am force-stop "$PKG"
# a previous process's WebView renderer exits a moment after the app: it must not be counted (it inflated P-060/P-061)
for _ in $(seq 1 40); do [ -z "$($A shell "ps -A -o NAME | grep $PKG" | tr -d '\r')" ] && break; sleep 0.5; done
$A shell am start -W -n "$PKG/$ACT" >/dev/null; sleep 3
bench_cmd openPath "$WP"; sleep 6
for i in $(seq 1 20); do bench_cmd next; sleep 0.7; done; sleep 3
mb() { PKG=$PKG "$ROOT/tools/mem_total.sh" | awk '{ sub(/^total_mb=/, "", $3); print ($3 == "" ? "NA" : $3) }'; }
transient=$(mb)
sleep 35
for RUN in 1 2 3; do
  emit P-060 "$(mb)" "steady (35 s after the turns); right after the turns: ${transient} MB"; sleep 2
done
bench_cmd back
# --- M9 (--release): P-061 on the huge single chapter (C-062). Every peak run is a first open: `pm clear` drops the
# chunk cache (the open chunks the chapter in the app process), then the page counter counts all its parts (the
# renderer's high point). Peak: PSS sampled from the open until 30 s after; a run without valid samples or without the
# exact page total inside the window is NA (a FAIL). Steady: 35 s after 20 turns, as P-060.
if [ "$RELEASE" = 1 ]; then
  H=$B/generated/stress-huge-single-chapter.epub
  for RUN in 1 2 3; do
    $A shell am force-stop "$PKG"; $A shell pm clear "$PKG" >/dev/null
    # the previous run's processes (its WebView renderer exits a moment after the app) must not be counted in this run
    for _ in $(seq 1 40); do [ -z "$($A shell "ps -A -o NAME | grep $PKG" | tr -d '\r')" ] && break; sleep 0.5; done
    $A shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
    $A shell am start -W -n "$PKG/$ACT" >/dev/null; sleep 4
    $A logcat -c; bench_cmd openPath "$H"; peak=0; samples=0; end=$(( $(date +%s) + 30 ))
    while [ "$(date +%s)" -lt "$end" ]; do
      m=$(mb); [[ $m =~ ^[0-9]+$ ]] && [ "$m" -gt 0 ] || continue
      samples=$((samples + 1)); [ "$m" -gt "$peak" ] && peak=$m
    done
    counted=$($A logcat -d -s EPR:I | grep -c "EVT page_total_exact ")
    if [ "$samples" = 0 ] || [ "$counted" = 0 ]; then val=NA; else val=$peak; fi
    emit P-061.peak "$val" "first open (chunk cache cleared): max PSS app+renderer over 30 s, $samples samples, exact total seen: $counted"
    for i in $(seq 1 20); do bench_cmd next; sleep 0.7; done; sleep 35
    emit P-061.steady "$(mb)" "35 s after 20 turns"
    bench_cmd back
  done
fi
