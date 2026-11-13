#!/bin/bash
# tools/power/check.sh — the pre-release power check (C-114). Run with bash from the repo root or anywhere.
#
# Builds (unless --no-build) and installs the play benchmark build on the emulator, runs the power-audit scenarios
# (a) still page 60 s, (b) 20 turns, (c) library after Back and idle, (d) 180 s in the background, plus the debug
# build's disk-write pass over the same 20 turns, and compares the numbers with tools/power/limits.tsv.
#
#   bash tools/power/check.sh [--runs N] [--no-build] [--write-limits [--loosen]] [--out DIR]
#
#   --runs N        N runs (default 1); the check gates on the median per metric
#   --no-build      use the APKs already in app/build/outputs/apk/play (benchmark + debug)
#   --write-limits  set each limit to (max over the runs) * mult + add from limits.tsv, rounded up; only lowers a limit
#   --loosen        with --write-limits: also raise limits (say why in the commit)
#   --out DIR       where the raw snapshots go (default tools/power/check-results/<time>)
#
# Exit: 0 pass, 1 a metric over its limit (or missing), 2 environment invalid (slow-WebView state, software GPU,
# busy host, the app restarted): no verdict, fix the environment and run again; 3 setup error.
# Env: GRADLE (default "./gradlew -Dorg.gradle.jvmargs=-Xmx2g"; on the shared dev host use ~/.cache/trackA/gw.sh),
#      ADB, ANDROID_SERIAL (default emulator-5554), IDLE_WAIT (s to wait for an idle host, default 600), OWNER.
# Takes /tmp/epub-reader-device.lock for the device part only (not while building) and releases it on any exit.
set -u
D=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$D/../.." && pwd)
RUNS=1; BUILD=1; WRITE=0; LOOSEN=; OUTDIR=$D/check-results/$(date +%Y%m%d-%H%M%S)
while [ $# -gt 0 ]; do
  case "$1" in
    --runs) RUNS=$2; shift ;;
    --no-build) BUILD=0 ;;
    --write-limits) WRITE=1 ;;
    --loosen) LOOSEN=--loosen ;;
    --out) OUTDIR=$2; shift ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "check.sh: unknown option $1" >&2; exit 3 ;;
  esac
  shift
done
A=${ADB:-adb}
export ANDROID_SERIAL=${ANDROID_SERIAL:-emulator-5554}
APKDIR=$ROOT/app/build/outputs/apk/play
BENCH_APK=$APKDIR/benchmark/app-play-benchmark.apk
DEBUG_APK=$APKDIR/debug/app-play-debug.apk
LIMITS=$D/limits.tsv
PY="python3 -I $D/check.py"
T0=$(date +%s)

if [ $BUILD = 1 ]; then
  echo "== building :app:assemblePlayBenchmark :app:assemblePlayDebug"
  (cd "$ROOT" && ${GRADLE:-./gradlew -Dorg.gradle.jvmargs=-Xmx2g} :app:assemblePlayBenchmark :app:assemblePlayDebug -q) || { echo "build failed" >&2; exit 3; }
fi
for f in "$BENCH_APK" "$DEBUG_APK"; do [ -f "$f" ] || { echo "missing $f (build it, or drop --no-build)" >&2; exit 3; }; done

# --- environment that can be checked before taking the device
[ "$($A shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r')" = 1 ] || { echo "$ANDROID_SERIAL is not an emulator: the check runs on the emulator only (energy.sh is the phone run)" >&2; exit 3; }
GPU=$(bash "$ROOT/bench/emulator.sh" check 2>/dev/null | sed -n 's/^hw\.gpu\.mode = \([^ ]*\).*/\1/p')
source "$D/scenarios.sh"
$A shell ls "$BOOK" >/dev/null 2>&1 || { echo "$BOOK is not on the device: run bench/push_corpus.sh" >&2; exit 3; }

idle_now() { vmstat 5 7 | tail -6 | awk '{s+=$15} END {printf "%d", s/NR}'; }   # mean idle % over 30 s
echo "== waiting for an idle host (>= 80 % CPU idle over 30 s, at most ${IDLE_WAIT:-600} s)"
deadline=$(( $(date +%s) + ${IDLE_WAIT:-600} ))
while :; do
  idle=$(idle_now); echo "   host idle $idle %"
  [ "$idle" -ge 80 ] && break
  [ "$(date +%s)" -ge $deadline ] && { echo "RESULT: ENVIRONMENT INVALID (host busy: $idle % idle; a build or another device run?)"; exit 2; }
done

LOCK=/tmp/epub-reader-device.lock
until mkdir $LOCK 2>/dev/null; do echo "   device lock held by: $(cat $LOCK/owner 2>/dev/null)"; sleep 30; done
echo "${OWNER:-power-check (C-114)} $(date '+%F %T')" > $LOCK/owner
VMSTAT=
cleanup() {
  [ -n "$VMSTAT" ] && kill $VMSTAT 2>/dev/null
  $A shell dumpsys battery reset >/dev/null 2>&1
  $A shell settings put system screen_off_timeout 2147483647
  $A shell settings put global stay_on_while_plugged_in 1
  rm -rf $LOCK
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# --- measurement
pid_app() { $A shell pidof $PKG | tr -d '\r'; }
pid_rend() { $A shell ps -A -o PID,NAME | awk '$2 ~ /^app\.fernreader:.*sandboxed/ {print $1}' | tr '\n' ' '; }
ticks() { [ -n "$1" ] && $A shell "cat /proc/$1/stat 2>/dev/null" | awk '{ sub(/^.*\) /, ""); print $12+$13 }'; }
# "t <tid> <comm> <voluntary> <nonvoluntary> <ticks>" per thread of the app process
threads() { [ -n "$1" ] && $A shell "for t in /proc/$1/task/*; do echo t \${t##*/} \$(tr ' ' _ < \$t/comm) \$(grep -E '^(non)?voluntary_ctxt' \$t/status | awk '{print \$2}' | tr '\n' ' ') \$(cut -d')' -f2 \$t/stat | awk '{print \$12+\$13}'); done" 2>/dev/null; }
io_bytes() { $A shell "run-as $PKG cat /proc/$1/io" 2>/dev/null | awk '/^write_bytes/ {print $2}'; }
gfx_frames() { $A shell dumpsys gfxinfo $PKG | awk -F': ' '/Total frames rendered/ {print $2+0; exit}'; }
gfx_p50() { $A shell dumpsys gfxinfo $PKG | awk -F': ' '/^50th percentile/ {print $2+0; exit}'; }
snap() { # snap <file> [io]
  local pa pr rt=0 p
  pa=$(pid_app); pr=$(pid_rend)
  for p in $pr; do rt=$(( rt + $(ticks $p || echo 0) )); done
  { echo "pid ${pa:-0}"; echo "app_ticks $(ticks "$pa" || echo 0)"; echo "rend_ticks $rt"; threads "$pa"
    [ "${2:-}" = io ] && echo "io_write_bytes $(io_bytes "$pa")"; } > "$1"
  [ -z "$FIRST_PID" ] && FIRST_PID=$pa
  [ "$pa" = "$FIRST_PID" ] || echo "pid_changed 1" >> "$RUN/env"
}
sc_begin() { echo "   $1 ..."; $A shell dumpsys gfxinfo $PKG reset >/dev/null; snap "$RUN/$1.before" $IO; }
sc_end() {
  snap "$RUN/$1.after" $IO
  echo "frames $(gfx_frames)" >> "$RUN/$1.after"
  [ "$1" = b ] && echo "b_p50_ms $(gfx_p50)" >> "$RUN/env"
}
SLOW_MS=$($PY const SLOW_FRAME_MS)
# Time a known redraw first: three turns; the median frame tells the slow-WebView state (every WebView draw 115-270 ms
# instead of under 1 ms, per app process, docs/power-audit.md "Power check") before 10 minutes are spent measuring.
sc_probe() {
  $A shell dumpsys gfxinfo $PKG reset >/dev/null
  swipe; sleep 2; swipe; sleep 2; swipe; sleep 2
  local p; p=$(gfx_p50)
  echo "probe_p50_ms ${p:-999}" >> "$RUN/env"; echo "probe_frames $(gfx_frames)" >> "$RUN/env"
  echo "   redraw probe: median frame ${p:-?} ms"
  [ "${p:-999}" -le "$SLOW_MS" ] || { ABORT=slow_webview; return 1; }
}

RUNDIRS=()
for i in $(seq "$RUNS"); do
  RUN=$OUTDIR/run$i; mkdir -p "$RUN"; RUNDIRS+=("$RUN"); FIRST_PID=; IO=
  echo "== run $i/$RUNS -> $RUN"
  echo "gpu_mode ${GPU:-?}" > "$RUN/env"
  app_setup "$BENCH_APK" || exit 3
  vmstat 5 > "$RUN/vmstat" & VMSTAT=$!       # host idle while measuring (installs excluded)
  $A shell dumpsys battery unplug
  if run_scenarios; then
    # disk writes: the debug build (run-as can read /proc/<pid>/io; a user build's shell cannot), same 20 turns
    FIRST_PID=; IO=io
    EVENTS=0 app_setup "$DEBUG_APK" || exit 3
    $A shell am start -W -a android.intent.action.VIEW -d "file://$BOOK" -t application/epub+zip -n $PKG/app.epubreader.MainActivity >/dev/null
    sleep 30
    sc_begin bio; for _ in $(seq $TURNS); do swipe; sleep 3; done; sleep 7; sc_end bio   # + the 5 s position write
  else
    echo "abort $ABORT" >> "$RUN/env"
  fi
  kill $VMSTAT 2>/dev/null; VMSTAT=
  echo "host_idle_pct $(awk 'NR>3 {s+=$15; n++} END {printf "%d", n ? s/n : 0}' "$RUN/vmstat")" >> "$RUN/env"
  $A shell dumpsys battery reset >/dev/null
  [ -n "$ABORT" ] && break    # environment invalid: no use running again
done
cleanup; trap - EXIT

echo "== $(( ($(date +%s) - T0) / 60 )) min; raw data in $OUTDIR"
[ "$RUNS" -gt 1 ] && $PY noise "${RUNDIRS[@]}"
if [ $WRITE = 1 ]; then
  $PY write-limits "$LIMITS" "${RUNDIRS[@]}" $LOOSEN; code=$?
  [ $code = 0 ] && git -C "$ROOT" --no-pager diff --stat -- "$LIMITS"
  exit $code
fi
$PY check "$LIMITS" "${RUNDIRS[@]}"
