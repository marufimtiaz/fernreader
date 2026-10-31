#!/usr/bin/env bash
# bench/groups/action.sh — M2: P-030 TOC jump to "Second Epilogue" in War and Peace (recording: command marker →
# last large change), P-031 TOC panel open with 2000 entries (events toc_cmd → toc_shown).
# M4 adds reflow (P-020), rotation (P-022) and boundary turns (P-026); M9 (--release) adds rotation on the huge chapter.
source "$(dirname "$0")/../lib.sh"; require_ad
OUT=$ROOT/bench/results/video; mkdir -p "$OUT"
$A shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
$A shell am force-stop "$PKG"; $A shell am start -W -n "$PKG/$ACT" >/dev/null; sleep 3
for RUN in 1 2 3; do
  bench_cmd openPath "$WP"; sleep 6
  bench_cmd tocJump "book one"; sleep 3 # reset: the book resumes where the previous run ended (Second Epilogue)
  v="$OUT/P-030-$RUN.mp4"; $A logcat -c
  $AD record start "$v" --scope device --hide-touches >/dev/null 2>&1; sleep 1.5
  bench_cmd tocJump "second epilogue"; sleep 4
  $AD record stop >/dev/null 2>&1
  # The recording is kept, but headings on a white page change < 2 mean-abs-diff, so video_ms often reports NA/0 here;
  # the value is the event delta command -> first painted (web-side paint, a slight under-measure).
  emit P-030 "$(ev_delta cmd painted)" "events cmd->painted; video=$(video_ms "$v" | head -1)"
  bench_cmd back; sleep 2
done
bench_cmd openPath "$B/generated/stress-2000-chapters.epub"; sleep 6
for RUN in 1 2 3; do
  $A logcat -c; bench_cmd toc; sleep 2
  emit P-031 "$(ev_delta toc_cmd toc_shown)"
  bench_cmd back; sleep 1 # closes the panel
done
bench_cmd back
