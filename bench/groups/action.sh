#!/usr/bin/env bash
# bench/groups/action.sh — M2: P-030 TOC jump to "Second Epilogue" in War and Peace (recording: command marker →
# last large change), P-031 TOC panel open with 2000 entries (events toc_cmd → toc_shown).
# M4: reflow (P-020), rotation (P-022) and boundary turns (P-026, with the S2 blank-frame rule); M9 (--release) adds
# rotation on the huge chapter.
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

# --- M4 ---
ink_verdict() { # <video>: number of blank/partial frames (S2 rule: ink below half of the smaller of first and last frame)
  python3 -I "$ROOT/tools/blank_frames.py" "$1" | python3 -I -c '
import sys
ink = [float(l.split("ink=")[1]) for l in sys.stdin if "ink=" in l and not l.startswith("#")]
lo = min(ink[0], ink[-1]) / 2 if ink else 0
print(sum(1 for x in ink if x < lo) if ink else "NA")'
}
bench_cmd resetSettings; sleep 1
bench_cmd openPath "$WP"; sleep 6
bench_cmd tocJump "book one"; sleep 3
for i in 1 2 3; do bench_cmd next; sleep 1; done # a page full of text above the panel
# P-020: text size step with the display panel open; recording from the command marker to the last large change.
bench_cmd display; sleep 2
for RUN in 1 2 3; do
  step=$([ $((RUN % 2)) = 1 ] && echo 1 || echo -1)
  v="$OUT/P-020-$RUN.mp4"; $A logcat -c
  $AD record start "$v" --scope device --hide-touches >/dev/null 2>&1; sleep 1.5
  bench_cmd fontStep "$step"; sleep 2.5
  $AD record stop >/dev/null 2>&1
  emit P-020 "$(video_ms "$v")" "video: from the panel label's change (button feedback) to the reflowed page"
  emit P-020.cmd_painted "$(ev_delta cmd painted)" "events: text size command -> reflowed page painted (C-066)"
  sleep 6 # the page counter of the new layout finishes before the next step
done
bench_cmd hideDisplay; sleep 1
# P-022: rotation to landscape (app-requested, a real configuration change); recording from the marker to the last change.
# The page label must show the exact total of the current layout before the recording starts (M6 Q7, C-049): on runs
# 2-3 it used to change from "≈ N" to the total during the pre-roll, and the video clock started ~1 s before the rotation.
wait_ev page_total_exact 20 || echo "  (P-022: no page_total_exact before run 1)"
for RUN in 1 2 3; do
  v="$OUT/P-022-$RUN.mp4"; $A logcat -c
  $AD record start "$v" --scope device --hide-touches >/dev/null 2>&1; sleep 1.5
  bench_cmd orient land; sleep 3
  $AD record stop >/dev/null 2>&1
  emit P-022 "$(video_ms "$v")" "video; events config_changed->painted=$(ev_delta config_changed painted)"
  $A logcat -c; bench_cmd orient port; sleep 3
  wait_ev page_total_exact 20 || echo "  (P-022: no page_total_exact after run $RUN)"
done
bench_cmd orient free; sleep 2
# P-026: next from the last page of a chapter (the neighbour is preloaded); 5 turns per run, median per run.
for RUN in 1 2 3; do
  vals=(); blanks=0
  for k in 1 2 3 4 5; do
    bench_cmd lastPage; sleep 2
    v="$OUT/P-026-$RUN-$k.mp4"
    $AD record start "$v" --scope device --hide-touches >/dev/null 2>&1; sleep 1.5
    bench_cmd next; sleep 2
    $AD record stop >/dev/null 2>&1
    vals+=("$(video_ms "$v")")
    b=$(ink_verdict "$v"); [ "$b" = NA ] || blanks=$((blanks + b))
  done
  med=$(printf '%s\n' "${vals[@]}" | grep -E '^[0-9]+$' | sort -n | awk '{a[NR]=$1} END { if (NR) print a[int((NR+1)/2)]; else print "NA" }')
  emit P-026 "$med" "turns: ${vals[*]}"
  emit P-026.blank_frames "$blanks"
done
bench_cmd back
# --- M9 (--release): P-023 rotation on the huge single chapter (as P-022: app-requested orientation, recording from the
# marker to the last change), after its page count settled.
if [ "$RELEASE" = 1 ]; then
  $A logcat -c; bench_cmd openPath "$B/generated/stress-huge-single-chapter.epub"; sleep 8
  wait_ev page_total_exact 30 || echo "  (P-023: no page_total_exact before run 1)"
  for RUN in 1 2 3; do
    v="$OUT/P-023-$RUN.mp4"; $A logcat -c
    $AD record start "$v" --scope device --hide-touches >/dev/null 2>&1; sleep 1.5
    bench_cmd orient land; sleep 5
    $AD record stop >/dev/null 2>&1
    emit P-023 "$(video_ms "$v")" "video; events config_changed->painted=$(ev_delta config_changed painted)"
    $A logcat -c; bench_cmd orient port; sleep 3
    wait_ev page_total_exact 30 || echo "  (P-023: no page_total_exact after run $RUN)"
  done
  bench_cmd orient free; sleep 2; bench_cmd back
fi

