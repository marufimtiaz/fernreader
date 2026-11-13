#!/bin/bash
# tools/power/energy.sh — optional energy run of the power scenarios (a)-(d) on a real phone (C-114). Reports, does not
# gate. Records a Perfetto trace with the phone's power rails (per-component energy: display, CPU clusters, GPU, ...;
# Pixel 6 and later and some other phones report them) and battery counters, runs the same scenarios as check.sh, and
# prints the energy of each rail per scenario. docs/power-audit.md "Energy run on a phone" has the fish commands.
#
#   ANDROID_SERIAL=<ip:port> bash tools/power/energy.sh [--no-build]
#
# Env: PY (a Python with the `perfetto` package; default python3), BOOK_FILE (local EPUB to push; default the corpus's
#      Moby-Dick), GRADLE, CLEAR=1 (clear the app's data first; default keep).
# Measure over wireless adb, not USB: on USB the phone charges and the battery counters measure the charger.
set -u
D=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$D/../.." && pwd)
A=${ADB:-adb}
PY=${PY:-python3}
[ "${1:-}" = --no-build ] || { (cd "$ROOT" && ${GRADLE:-./gradlew -Dorg.gradle.jvmargs=-Xmx2g} :app:assemblePlayBenchmark -q) || exit 3; }
APK=$ROOT/app/build/outputs/apk/play/benchmark/app-play-benchmark.apk
[ -f "$APK" ] || { echo "missing $APK" >&2; exit 3; }
[ -n "${ANDROID_SERIAL:-}" ] || { echo "set ANDROID_SERIAL to the phone (adb devices)" >&2; exit 3; }
[ "$($A shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r')" = 1 ] && { echo "$ANDROID_SERIAL is an emulator: it has no power rails (use check.sh)" >&2; exit 3; }
BOOK_FILE=${BOOK_FILE:-$ROOT/corpus/gutenberg/moby-dick-epub2-noimages.epub}
[ -f "$BOOK_FILE" ] || { echo "missing $BOOK_FILE (set BOOK_FILE to any EPUB; use the same one for comparisons)" >&2; exit 3; }
export BOOK=/sdcard/Download/fern-power/$(basename "$BOOK_FILE")

if $A shell service list 2>/dev/null | grep -qi 'power.stats'; then echo "power stats HAL: present"
else echo "warning: no power stats HAL listed; the trace may have battery counters only"; fi
case "$($A shell dumpsys battery | awk -F': ' '/(USB|AC|Wireless) powered: true/ {print "yes"; exit}')" in
  yes) echo "warning: the phone is charging: battery counters will measure the charger (rails are still valid)" ;;
esac

OUT=$D/energy-results/$(date +%Y%m%d-%H%M%S); mkdir -p "$OUT"
source "$D/scenarios.sh"
$A shell mkdir -p /sdcard/Download/fern-power
$A push "$BOOK_FILE" "$BOOK" >/dev/null
TIMEOUT0=$($A shell settings get system screen_off_timeout | tr -d '\r')
TRACE=/data/misc/perfetto-traces/fern-energy.pftrace
PERFETTO_PID=
cleanup() {
  [ -n "$PERFETTO_PID" ] && $A shell kill "$PERFETTO_PID" 2>/dev/null
  $A shell settings put system screen_off_timeout "${TIMEOUT0:-60000}"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

CLEAR=${CLEAR:-0} app_setup "$APK" || { echo "install failed: a FernReader signed with another key is installed? Uninstall it first (its library data goes with it)." >&2; exit 3; }

printf '%s\n' 'buffers { size_kb: 65536 }' 'duration_ms: 1800000' \
  'data_sources { config { name: "android.power" android_power_config { battery_poll_ms: 1000 collect_power_rails: true battery_counters: BATTERY_COUNTER_CHARGE battery_counters: BATTERY_COUNTER_CURRENT } } }' \
  > "$OUT/config.pbtxt"
$A push "$OUT/config.pbtxt" /data/local/tmp/fern-energy.pbtxt >/dev/null
PERFETTO_PID=$($A shell "cat /data/local/tmp/fern-energy.pbtxt | perfetto --background --txt -c - -o $TRACE" | tr -d '\r' | tail -1)
echo "perfetto pid $PERFETTO_PID"; sleep 3

uptime_s() { $A shell cat /proc/uptime | awk '{print $1}'; }    # CLOCK_BOOTTIME, the trace's clock
sc_begin() { echo "   $1 ..."; START=$(uptime_s); }
sc_end() { echo "$1 $START $(uptime_s)" >> "$OUT/windows.txt"; }
run_scenarios || echo "stopped early: $ABORT"

$A shell kill "$PERFETTO_PID"; PERFETTO_PID=
for _ in $(seq 30); do $A shell pidof perfetto >/dev/null || break; sleep 1; done
$A pull $TRACE "$OUT/trace.pftrace" >/dev/null && $A shell rm -f $TRACE
echo "trace: $OUT/trace.pftrace (open it at https://ui.perfetto.dev); windows: $OUT/windows.txt"
$PY "$D/energy.py" "$OUT/trace.pftrace" "$OUT/windows.txt"
