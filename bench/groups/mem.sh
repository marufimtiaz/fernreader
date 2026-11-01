#!/usr/bin/env bash
# bench/groups/mem.sh — P-060: PSS of app + renderer while reading War and Peace (tools/mem_total.sh, as in S2).
# M4: the page counter lays out the whole book after an open; the renderer keeps that memory until it releases it,
# ~30 s later. Gated value = steady reading (after the release), M4.md question Q1; the reading right after the
# turns (during/after counting) is recorded in the note of each row.
# M9 (--release) adds P-061 (huge chapter) and P-062 (50 plates of the 200-image book).
source "$(dirname "$0")/../lib.sh"
$A shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
$A shell am force-stop "$PKG"; $A shell am start -W -n "$PKG/$ACT" >/dev/null; sleep 3
bench_cmd openPath "$WP"; sleep 6
for i in $(seq 1 20); do bench_cmd next; sleep 0.7; done; sleep 3
mb() { PKG=$PKG "$ROOT/tools/mem_total.sh" | awk '{ sub(/^total_mb=/, "", $3); print ($3 == "" ? "NA" : $3) }'; }
transient=$(mb)
sleep 35
for RUN in 1 2 3; do
  emit P-060 "$(mb)" "steady (35 s after the turns); right after the turns: ${transient} MB"; sleep 2
done
bench_cmd back
# --- M9 (--release): P-061 on the huge single chapter (C-062). Peak: PSS sampled from the open through the page
# count of all its parts (the renderer's high point) to 30 s after; steady: 35 s after 20 turns, as P-060. The chunk
# cache is cold on the first open after `pm clear` (chunking in the app process) and warm afterwards; the note says which.
if [ "$RELEASE" = 1 ]; then
  H=$B/generated/stress-huge-single-chapter.epub
  for RUN in 1 2 3; do
    $A shell am force-stop "$PKG"; $A shell am start -W -n "$PKG/$ACT" >/dev/null; sleep 3
    cache=$([ "$RUN" = 1 ] && echo "first open of the run" || echo "cache warm")
    bench_cmd openPath "$H"; peak=0; end=$(( $(date +%s) + 30 ))
    while [ "$(date +%s)" -lt "$end" ]; do m=$(mb); [ "${m:-0}" -gt "$peak" ] 2>/dev/null && peak=$m; done
    emit P-061.peak "$peak" "max PSS app+renderer over 30 s from the open ($cache)"
    for i in $(seq 1 20); do bench_cmd next; sleep 0.7; done; sleep 35
    emit P-061.steady "$(mb)" "35 s after 20 turns"
    bench_cmd back
  done
fi

