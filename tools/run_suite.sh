#!/usr/bin/env bash
# The instrumented suite on emulator-5554, run detached on the device: `am instrument` over adb once lost its host
# connection mid-suite and gave no result while the tests went on, so here nohup on the device writes to a file, and
# this script polls, streams that file to the host and reports.
#
# Usage: tools/run_suite.sh [--no-corpus] [--no-warmup] [--out DIR] [--timeout SECONDS] [Class|Class#test|fqcn ...]
#   no classes       the full suite, CorpusPageCountTest included (the release run)
#   --no-corpus      without the long release check CorpusPageCountTest (it then needs its own run before a release)
#   --no-warmup      skip the touch and WebView warm-up (the scan launch stays)
#   --out DIR        where the files go (default ~/.cache/fernreader-suite/<time>): run.txt logcat.txt times.tsv
#   --timeout S      stop the run after S seconds (default 3300: the device lock's limit is 60 minutes)
# Build first: :app:assemblePlayDebug :app:assemblePlayDebugAndroidTest.
# Exit code: 0 all passed, 1 failures, 2 setup failed, 3 no result line (run cut off), 4 the device stayed unreachable.
#
# Steps: device lock, boot work finished, fresh install, warm-up, pm clear, all-files access, scan launch, the run, report.
# The warm-up comes before the clear because it reads a book: the suite must not start with a saved position.
set -u
cd "$(dirname "$0")/.."
ROOT=$PWD
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb} -s ${SERIAL:-emulator-5554}"
PKG=app.fernreader
TESTPKG=$PKG.test
RUNNER=$TESTPKG/androidx.test.runner.AndroidJUnitRunner
WARMUP=app.epubreader.SuiteWarmUp
CORPUS=app.epubreader.release.CorpusPageCountTest
LOCK=/tmp/epub-reader-device.lock
DEV=/data/local/tmp/fern-suite
APP_APK=$ROOT/app/build/outputs/apk/play/debug/app-play-debug.apk
TEST_APK=$ROOT/app/build/outputs/apk/androidTest/play/debug/app-play-debug-androidTest.apk

OUT= TIMEOUT=3300 WARM=1 NOCORPUS=0 CLASSES=
while [ $# -gt 0 ]; do
  case "$1" in
    --no-corpus) NOCORPUS=1 ;;
    --no-warmup) WARM=0 ;;
    --out) OUT=$2; shift ;;
    --timeout) TIMEOUT=$2; shift ;;
    -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) # Class or Class#test -> fully qualified
      name=${1%%#*}; rest=${1#"$name"}
      case "$name" in
        *.*) fq=$name ;;
        *) f=$(find app/src/androidTest -name "$name.kt" | head -1)
           [ -n "$f" ] || { echo "no test class $name" >&2; exit 2; }
           fq=$(sed -n 's/^package //p' "$f").$name ;;
      esac
      CLASSES=${CLASSES:+$CLASSES,}$fq$rest ;;
  esac
  shift
done
OUT=${OUT:-$HOME/.cache/fernreader-suite/$(date +%Y%m%d-%H%M%S)}
mkdir -p "$OUT"
for f in "$APP_APK" "$TEST_APK"; do [ -f "$f" ] || { echo "missing $f: build first" >&2; exit 2; }; done
newer=$(find app/src/androidTest app/src/main reader-web/src -type f -newer "$TEST_APK" 2>/dev/null | head -3)
[ -z "$newer" ] || echo "WARNING: sources newer than the test APK: $newer"

say() { echo "[$(date +%T)] $*"; }
HAVE_LOCK=0 RUN_LIVE=0
cleanup() {
  code=$?
  if [ "$RUN_LIVE" = 1 ]; then # killed or timed out: the device run must not outlive the lock
    $ADB shell "kill \$(cat $DEV/pid) \$(cat $DEV/logcat.pid)" >/dev/null 2>&1
    $ADB shell am force-stop $TESTPKG >/dev/null 2>&1; $ADB shell am force-stop $PKG >/dev/null 2>&1
  fi
  if [ "$HAVE_LOCK" = 1 ]; then rm -rf $LOCK; say "device lock released"; fi
  exit $code
}
trap cleanup EXIT
trap 'exit 143' TERM; trap 'exit 130' INT; trap 'exit 129' HUP

until mkdir $LOCK 2>/dev/null; do sleep 30; done
HAVE_LOCK=1
echo "Track A ($(git branch --show-current)) run_suite $(date '+%F %T')" > $LOCK/owner
say "device lock taken, head $(git rev-parse --short HEAD), files in $OUT"

# Boot background work: nothing is done until the boot finished and the device's load fell (5 minutes at most, it is
# only a warning then). Before the install, whose own dexopt would count.
[ "$($ADB shell getprop sys.boot_completed | tr -d '\r')" = 1 ] || { echo "device not booted" >&2; exit 2; }
waited=0
while :; do
  load=$($ADB shell cat /proc/loadavg | cut -d' ' -f1)
  awk "BEGIN{exit !($load < 2)}" && break
  [ $waited -ge 300 ] && { say "WARNING: device load still $load after 5 minutes, going on"; break; }
  sleep 5; waited=$((waited + 5))
done
say "device up $($ADB shell cut -d. -f1 /proc/uptime | tr -d '\r') s, load $load (waited $waited s)"

$ADB shell am force-stop $TESTPKG; $ADB shell am force-stop $PKG
$ADB shell rm -rf /sdcard/Books/X
$ADB uninstall $PKG >/dev/null 2>&1; $ADB uninstall $TESTPKG >/dev/null 2>&1
$ADB install -r -d "$APP_APK" | tail -1 | grep -q Success || { echo "install of the app failed" >&2; exit 2; }
$ADB install -r -d "$TEST_APK" | tail -1 | grep -q Success || { echo "install of the tests failed" >&2; exit 2; }
$ADB shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow

# One short step over plain adb; a failure stops the script, the suite would mean nothing after it.
step() {
  $ADB shell am instrument -w -r --no-window-animation -e class "$WARMUP#$1" $RUNNER > "$OUT/$1.txt" 2>&1
  grep -q '^OK (1 test)' "$OUT/$1.txt" || { echo "$1 failed:"; grep -A6 'stack=' "$OUT/$1.txt" | head -20; tail -3 "$OUT/$1.txt"; exit 2; }
  say "$1 done"
}
[ $WARM = 1 ] && step webview_page_and_touches
$ADB shell pm clear $PKG >/dev/null
$ADB shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow
step first_scan_finishes

ARGS=
[ -n "$CLASSES" ] && ARGS="-e class $CLASSES"
if [ -z "$CLASSES" ]; then
  NOT=$WARMUP; [ $NOCORPUS = 1 ] && NOT=$NOT,$CORPUS
  ARGS="-e notClass $NOT"
fi
$ADB shell "rm -rf $DEV; mkdir -p $DEV"
$ADB shell "nohup logcat -v epoch -T 1 -s TestRunner:I AndroidRuntime:E EPR:I EPRJS:W ActivityManager:I > $DEV/logcat.txt 2>&1 & echo \$! > $DEV/logcat.pid" </dev/null
$ADB shell "nohup sh -c 'echo \$\$ > $DEV/pid; exec am instrument -w -r --no-window-animation $ARGS $RUNNER' > $DEV/run.txt 2>&1 &" </dev/null
RUN_LIVE=1
START=$SECONDS
say "run started on the device: ${ARGS:-all}"

: > "$OUT/run.txt"
off=0 lost=0 seen=0 STATE=
while :; do
  sleep 10
  if chunk=$($ADB shell "tail -c +$((off + 1)) $DEV/run.txt; [ -d /proc/\$(cat $DEV/pid) ] && echo @@alive || echo @@gone" 2>/dev/null); then
    lost=0
    live=${chunk##*@@}; chunk=${chunk%@@*}
    printf '%s' "$chunk" >> "$OUT/run.txt"; off=$(stat -c %s "$OUT/run.txt")
    printf '%s' "$chunk" | awk '
      /^INSTRUMENTATION_STATUS: class=/ { sub(/.*\./, ""); c = $0 }
      /^INSTRUMENTATION_STATUS: test=/ { sub(/.*test=/, ""); t = $0 }
      /^INSTRUMENTATION_STATUS_CODE: -[12]$/ { print "  FAILED " c "#" t }'
    done=$(grep -c '^INSTRUMENTATION_STATUS_CODE: \(0\|-[1-4]\)$' "$OUT/run.txt")
    if [ "$done" -ne "$seen" ] && [ $((done / 10)) -ne $((seen / 10)) ]; then say "$done tests finished, $((SECONDS - START)) s"; fi
    seen=$done
    grep -q '^INSTRUMENTATION_CODE:' "$OUT/run.txt" && { STATE=ended; break; }
    case "$live" in gone*) STATE=died; break ;; esac
  else
    lost=$((lost + 1))
    [ $lost = 1 ] && say "device unreachable, still polling"
    [ $lost -ge 60 ] && { STATE=unreachable; break; }
  fi
  [ $((SECONDS - START)) -ge "$TIMEOUT" ] && { STATE=timeout; break; }
done
say "run $STATE after $((SECONDS - START)) s"

if [ "$STATE" = unreachable ]; then
  RUN_LIVE=0
  echo "NO RESULT: the device stayed unreachable for 10 minutes. The run may still be going on the device: $DEV/run.txt." >&2
  exit 4
fi
if [ "$STATE" = timeout ]; then
  echo "NO RESULT: stopped after the $TIMEOUT s timeout."
  $ADB shell "kill \$(cat $DEV/pid)" >/dev/null 2>&1
fi
$ADB shell "kill \$(cat $DEV/logcat.pid)" >/dev/null 2>&1
RUN_LIVE=0
$ADB pull $DEV/logcat.txt "$OUT/logcat.txt" >/dev/null || : > "$OUT/logcat.txt"
$ADB shell am force-stop $TESTPKG; $ADB shell am force-stop $PKG
python3 -I "$ROOT/tools/suite_report.py" "$OUT/run.txt" "$OUT/logcat.txt"
