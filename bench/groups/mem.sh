#!/usr/bin/env bash
# bench/groups/mem.sh — P-060: PSS of app + renderer while reading War and Peace (tools/mem_total.sh, as in S2).
# M9 (--release) adds P-061 (huge chapter) and P-062 (50 plates of the 200-image book).
source "$(dirname "$0")/../lib.sh"
$A shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
$A shell am force-stop "$PKG"; $A shell am start -W -n "$PKG/$ACT" >/dev/null; sleep 3
bench_cmd openPath "$WP"; sleep 6
for i in $(seq 1 20); do bench_cmd next; sleep 0.7; done; sleep 3
for RUN in 1 2 3; do
  # mem_total.sh prints "app_kb=.. renderer_kb=.. total_mb=NNN"
  emit P-060 "$(PKG=$PKG "$ROOT/tools/mem_total.sh" | awk '{ sub(/^total_mb=/, "", $3); print ($3 == "" ? "NA" : $3) }')"; sleep 2
done
bench_cmd back
