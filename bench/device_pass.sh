#!/usr/bin/env bash
# bench/device_pass.sh — the scripted parts of the real-device pass (docs/real-device-pass.md), for any phone over adb.
#
# Usage: bench/device_pass.sh -s <adb-serial> <step...|all>
#   setup      install check, all-files access, corpus copied to /sdcard/Books (nothing deleted), one scan
#   info       model, Android version, refresh rate, WebView version (recorded with every result)
#   frames     C-060 / P-027: 30 swipes on War and Peace; p90 frame time (dumpsys gfxinfo) ≤ one refresh interval
#   anim       C-025 / P-025: 5 recorded swipe turns; animation length (≤ 300 ms) and blank/partial frames (0)
#   p020       C-031 / C-066: text size step with the panel open; video ms and command → painted from app events
#   p002       C-024 / P-002: hot start (Home, then am start -W), 5 runs, median ≤ 250 ms
#   vertical   C-046: vertical-writing book: screenshots of two pages and the page label events
#   talkback   C-070: huge chapter with TalkBack on: open → painted, and how long one accessibility dump takes
#   toc        P-031 scroll: 2000-entry contents panel, 10 flings, gfxinfo p90
#   library    P-063 scroll: 1000 seeded books (`seedLibrary 1000`), 20 flings in the grid, gfxinfo p90; seeds removed
#   all        every step above except talkback (it changes an accessibility setting; run it on its own)
#
# Needs the benchmark build on the phone (bench commands and app events): ./gradlew :app:assemblePlayBenchmark, then
# adb -s <serial> install -r -d app/build/outputs/apk/play/benchmark/app-play-benchmark.apk. Host tools: adb,
# python3, ffmpeg/ffprobe (video steps). Results: bench/results/device-<serial>-<UTC time>/summary.txt (+ videos,
# screenshots, raw gfxinfo). Nothing here needs agent-device or root. C-036 (keyboard on API 26–29) is manual.
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SERIAL=""
[ "${1:-}" = -s ] && { SERIAL=$2; shift 2; }
[ -n "$SERIAL" ] && [ $# -gt 0 ] || { sed -n '2,20p' "$0"; exit 2; }
A=${ADB:-$(command -v adb || echo "$HOME/Android/Sdk/platform-tools/adb")}
export ANDROID_SERIAL=$SERIAL # every adb call below talks to this phone
PKG=${PKG:-app.fernreader}
ACT=${ACT:-app.epubreader.MainActivity}
OUT=$ROOT/bench/results/device-${SERIAL//[^A-Za-z0-9]/_}-$(date -u +%Y%m%dT%H%M%SZ); mkdir -p "$OUT"
SUM=$OUT/summary.txt
B=/sdcard/Books
WP=$B/gutenberg/war-and-peace-epub3.epub

say() { echo "$*" | tee -a "$SUM"; }
bench_cmd() { $A shell am broadcast -a app.fernreader.BENCH -n "$PKG/app.epubreader.bench.BenchCommandReceiver" --es cmd "$1" --es arg "'${2:-}'" >/dev/null; }
ev_delta() {
  $A logcat -d -s EPR:I | awk -v a="EVT $1 " -v b="EVT $2 " '
    function t() { match($0, /t=[0-9]+/); return substr($0, RSTART+2, RLENGTH-2) }
    index($0, a) && s=="" { s=t() } index($0, b) && s!="" && e=="" { e=t() }
    END { if (s!="" && e!="") print e-s; else print "NA" }'
}
median() { sort -n | awk '{a[NR]=$1} END { if (NR) print a[int((NR+1)/2)]; else print "NA" }'; }
size() { $A shell wm size | awk -F'[ x]' '/Override/ {w=$3; h=$4} /Physical/ && !w {w=$3; h=$4} END {print w, h}'; }
read -r W H <<<"$(size)"
refresh_hz() {
  [ -n "${REFRESH_HZ:-}" ] && { echo "$REFRESH_HZ"; return; }
  $A shell dumpsys display | grep -oE '(renderFrameRate|mRefreshRate|refreshRate|fps)[=: ]+[0-9]+(\.[0-9]+)?' | head -1 | grep -oE '[0-9]+(\.[0-9]+)?$'
}
start_app() { $A shell am force-stop "$PKG"; $A shell am start -W -n "$PKG/$ACT" >/dev/null; sleep 3; }
swipe_left() { $A shell input swipe $((W * 85 / 100)) $((H / 2)) $((W * 15 / 100)) $((H / 2)) 150; }
fling_up() { $A shell input swipe $((W / 2)) $((H * 80 / 100)) $((W / 2)) $((H * 25 / 100)) 120; }
# gfx <label>: the app's frame stats since the last reset, p50/p90/p99 and the janky share.
gfx() {
  $A shell dumpsys gfxinfo "$PKG" > "$OUT/gfxinfo-$1.txt"
  awk '/Total frames rendered/ {f=$4} /Janky frames:/ && !j {j=$3" "$4} /50th percentile/ && !p50 {p50=$3} /90th percentile/ && !p90 {p90=$3} /99th percentile/ && !p99 {p99=$3}
       END { printf "frames=%s janky=%s p50=%s p90=%s p99=%s\n", f, j, p50, p90, p99 }' "$OUT/gfxinfo-$1.txt"
}
p90_ms() { sed -n 's/.*p90=\([0-9]*\)ms.*/\1/p'; }
verdict() { # <p90 ms> <hz>: PASS when p90 ≤ one refresh interval
  awk -v p="$1" -v hz="$2" 'BEGIN { if (p == "" || hz == "") { print "NA"; exit } lim = 1000 / hz; printf "%s (limit %.1f ms at %s Hz)\n", (p <= lim ? "PASS" : "FAIL"), lim, hz }'
}

step_setup() {
  $A shell pm path "$PKG" >/dev/null || { say "setup: $PKG is not installed (install the benchmark APK first)"; return 1; }
  $A shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
  # Only adds /sdcard/Books/{gutenberg,epub3-samples,generated} (spec corpus.md); unlike bench/push_corpus.sh it
  # deletes nothing on the phone.
  $A shell mkdir -p $B
  $A push "$ROOT/corpus/gutenberg" "$ROOT/corpus/epub3-samples" "$ROOT/corpus/generated" $B/ >/dev/null
  start_app; bench_cmd rescan; sleep 20
  say "setup: corpus pushed, all-files access allowed, scan done"
}

step_info() {
  say "device: $($A shell getprop ro.product.manufacturer | tr -d '\r') $($A shell getprop ro.product.model | tr -d '\r'), Android $($A shell getprop ro.build.version.release | tr -d '\r') (API $($A shell getprop ro.build.version.sdk | tr -d '\r'))"
  say "screen: ${W}x${H}, refresh: $(refresh_hz) Hz (override with REFRESH_HZ=…)"
  say "webview: $($A shell dumpsys webviewupdate | grep -m1 -E 'Current WebView package' | sed 's/^ *//')"
  say "app: $($A shell dumpsys package "$PKG" | grep -m1 versionName | sed 's/^ *//')"
}

step_frames() {
  start_app; bench_cmd resetSettings; sleep 1
  bench_cmd openPath "$WP"; sleep 8
  bench_cmd tocJump "book one"; sleep 4
  $A shell dumpsys gfxinfo "$PKG" reset >/dev/null
  for i in $(seq 1 30); do swipe_left; sleep 1.2; done
  local g; g=$(gfx frames)
  say "C-060 frames (30 swipes, War and Peace): $g"
  say "  verdict: $(verdict "$(echo "$g" | p90_ms)" "$(refresh_hz)")"
  bench_cmd back
}

step_anim() {
  start_app; bench_cmd openPath "$WP"; sleep 8
  bench_cmd tocJump "book one"; sleep 4
  local ms=() blanks=0
  for k in 1 2 3 4 5; do
    $A shell screenrecord --time-limit 4 "/sdcard/epr-anim-$k.mp4" & local rec=$!
    sleep 1.2; swipe_left; wait $rec
    $A pull "/sdcard/epr-anim-$k.mp4" "$OUT/anim-$k.mp4" >/dev/null; $A shell rm "/sdcard/epr-anim-$k.mp4"
    ms+=("$(bash "$ROOT/tools/spec-tools/summarize_open.sh" "$OUT/anim-$k.mp4" | sed 's/.*open_ms=//')")
    local b; b=$(python3 -I "$ROOT/tools/blank_frames.py" "$OUT/anim-$k.mp4" | python3 -I -c '
import sys
ink = [float(l.split("ink=")[1]) for l in sys.stdin if "ink=" in l and not l.startswith("#")]
lo = min(ink[0], ink[-1]) / 2 if ink else 0
print(sum(1 for x in ink if x < lo) if ink else "NA")')
    [ "$b" = NA ] || blanks=$((blanks + b))
  done
  say "C-025 animation: first change -> last change per turn (ms): ${ms[*]}; median $(printf '%s\n' "${ms[@]}" | grep -E '^[0-9]+$' | median) (limit 300)"
  say "  blank/partial frames over 5 turns: $blanks (must be 0); videos in $OUT/anim-*.mp4: watch them once"
  bench_cmd back
}

step_p020() {
  start_app; bench_cmd resetSettings; sleep 1
  bench_cmd openPath "$WP"; sleep 8
  bench_cmd tocJump "book one"; sleep 3
  bench_cmd display; sleep 2
  local vid=() evs=()
  for run in 1 2 3; do
    local step; step=$([ $((run % 2)) = 1 ] && echo 1 || echo -1)
    $A logcat -c
    $A shell screenrecord --time-limit 4 "/sdcard/epr-p020-$run.mp4" & local rec=$!
    sleep 1.2; bench_cmd fontStep "$step"; wait $rec
    $A pull "/sdcard/epr-p020-$run.mp4" "$OUT/p020-$run.mp4" >/dev/null; $A shell rm "/sdcard/epr-p020-$run.mp4"
    vid+=("$(bash "$ROOT/tools/spec-tools/summarize_open.sh" "$OUT/p020-$run.mp4" | sed 's/.*open_ms=//')")
    evs+=("$(ev_delta cmd painted)")
    sleep 6
  done
  say "C-031/C-066 P-020: video (first change -> last change) ms: ${vid[*]}, median $(printf '%s\n' "${vid[@]}" | grep -E '^[0-9]+$' | median)"
  say "  events command -> page painted ms: ${evs[*]}, median $(printf '%s\n' "${evs[@]}" | grep -E '^[0-9]+$' | median) (limit 100)"
  bench_cmd hideDisplay; bench_cmd resetSettings; bench_cmd back
}

step_p002() {
  start_app
  local v=()
  for run in 1 2 3 4 5; do
    $A shell input keyevent KEYCODE_HOME; sleep 2
    v+=("$($A shell am start -W -n "$PKG/$ACT" | awk '/TotalTime/ {print $2}' | tr -d '\r')")
    sleep 2
  done
  say "C-024 P-002 hot start ms: ${v[*]}, median $(printf '%s\n' "${v[@]}" | median) (limit 250)"
}

step_vertical() {
  start_app; bench_cmd openPath "$B/epub3-samples/kusamakura-japanese-vertical-writing.epub"; sleep 8
  $A logcat -c
  $A exec-out screencap -p > "$OUT/vertical-1.png"
  swipe_left; sleep 2
  $A exec-out screencap -p > "$OUT/vertical-2.png"
  $A shell input swipe $((W * 15 / 100)) $((H / 2)) $((W * 85 / 100)) $((H / 2)) 150; sleep 2
  $A exec-out screencap -p > "$OUT/vertical-3.png"
  say "C-046 vertical: screenshots vertical-1..3.png (columns right to left; one swipe each way). Page label events:"
  $A logcat -d -s EPR:I | grep -E 'EVT (page_total_estimate|page_total_exact|count_mismatch)' | tail -5 | sed 's/^/    /' | tee -a "$SUM"
  bench_cmd back
}

step_talkback() {
  local svc; svc=$($A shell pm list packages | grep -qE 'com.google.android.marvin.talkback' && echo "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService")
  [ -n "$svc" ] || { say "C-070: TalkBack is not installed on this phone; turn on the screen reader by hand and repeat the steps in docs/real-device-pass.md"; return; }
  local before; before=$($A shell settings get secure enabled_accessibility_services | tr -d '\r')
  $A shell settings put secure enabled_accessibility_services "$svc"; $A shell settings put secure accessibility_enabled 1; sleep 3
  start_app; $A logcat -c
  bench_cmd openPath "$B/generated/stress-huge-single-chapter.epub"; sleep 15
  local open; open=$(ev_delta cmd painted)
  local t0 t1; t0=$(date +%s%N); $A shell uiautomator dump /sdcard/epr-a11y.xml >/dev/null 2>&1; t1=$(date +%s%N)
  say "C-070 TalkBack on, huge chapter: command -> painted $open ms; one accessibility dump of the screen took $(( (t1 - t0) / 1000000 )) ms"
  say "  now judge by hand (docs/real-device-pass.md, C-070): swipe to the next items, double-tap to turn pages"
  if [ "$before" = null ] || [ -z "$before" ]; then $A shell settings delete secure enabled_accessibility_services; $A shell settings put secure accessibility_enabled 0
  else $A shell settings put secure enabled_accessibility_services "$before"; fi
  say "  TalkBack setting restored to: ${before:-off}"
  bench_cmd back
}

step_toc() {
  start_app; bench_cmd openPath "$B/generated/stress-2000-chapters.epub"; sleep 8
  $A logcat -c; bench_cmd toc; sleep 2
  say "P-031 TOC open (events toc_cmd -> toc_shown): $(ev_delta toc_cmd toc_shown) ms (limit 200)"
  $A shell dumpsys gfxinfo "$PKG" reset >/dev/null
  for i in $(seq 1 10); do fling_up; sleep 0.8; done
  local g; g=$(gfx toc)
  say "P-031 TOC scroll (10 flings): $g; $(verdict "$(echo "$g" | p90_ms)" "$(refresh_hz)")"
  bench_cmd back; sleep 1; bench_cmd back
}

step_library() {
  start_app; $A logcat -c
  bench_cmd seedLibrary 1000
  local i=0; until $A logcat -d -s EPR:I | grep -q 'EVT seeded '; do sleep 1; i=$((i + 1)); [ $i -lt 60 ] || break; done
  start_app # fresh library screen with 1000 more books
  $A exec-out screencap -p > "$OUT/library-top.png"
  $A shell dumpsys gfxinfo "$PKG" reset >/dev/null
  for i in $(seq 1 20); do fling_up; sleep 0.8; done
  local g; g=$(gfx library)
  $A exec-out screencap -p > "$OUT/library-scrolled.png"
  say "P-063 library scroll (1000 seeded books, 20 flings): $g; $(verdict "$(echo "$g" | p90_ms)" "$(refresh_hz)")"
  say "  screenshots library-top.png, library-scrolled.png: covers shown (no blank placeholders after scrolling stops)"
  bench_cmd seedLibrary 0; sleep 3
}

say "device pass $(date -u +%FT%TZ), serial $SERIAL, package $PKG"
steps=("$@"); [ "$1" = all ] && steps=(info frames anim p020 p002 vertical toc library)
for s in "${steps[@]}"; do
  type "step_$s" >/dev/null 2>&1 || { echo "unknown step: $s" >&2; exit 2; }
  echo "== $s"; "step_$s"
done
say "results: $OUT"
