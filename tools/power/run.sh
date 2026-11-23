#!/bin/bash
# Power audit scenarios. Takes the device lock, releases it on any exit.
# APKDIR: the play APKs to measure (default: this worktree's). SCENARIOS: which to run (default all):
# a0 a b c d (always run), e a1 a2 a3 z, io (the debug build's /proc/<pid>/io pass). OWNER: the lock owner text.
set -u
D=$(cd "$(dirname "$0")" && pwd)
APKDIR=${APKDIR:-$(cd "$D/../.." && pwd)/app/build/outputs/apk/play}
SCENARIOS=" ${SCENARIOS:-a0 a b c d e a1 a2 a3 z io} "
want() { [[ "$SCENARIOS" == *" $1 "* ]]; }
export OUT=${OUT:-$D/results.txt}
: > $OUT
LOCK=/tmp/epub-reader-device.lock
until mkdir $LOCK 2>/dev/null; do echo "lock held by: $(cat $LOCK/owner 2>/dev/null)"; sleep 30; done
echo "${OWNER:-power-audit (research)} $(date '+%F %T')" > $LOCK/owner
cleanup() { adb shell dumpsys battery reset >/dev/null 2>&1; adb shell settings put system screen_off_timeout 2147483647; adb shell settings put global stay_on_while_plugged_in 1; rm -rf $LOCK; }
trap cleanup EXIT INT TERM
source $D/power.sh

BOOK=/sdcard/Books/gutenberg/moby-dick-epub2-noimages.epub
WP=/sdcard/Books/gutenberg/war-and-peace-epub3.epub
cmd() { adb shell am broadcast -a app.fernreader.BENCH -n "$PKG/app.fernreader.bench.BenchCommandReceiver" --es cmd "$1" --es arg "'${2:-}'" >/dev/null; }
swipe() { adb shell input swipe 900 1300 150 1300 120; }
ev() { adb logcat -d -s EPR:I | grep -E "EVT ($1) " | sed 's/.*EVT //'; }
wait_ev() { local end=$(( $(date +%s) + ${2:-60} )); until adb logcat -d -s EPR:I | grep -q "EVT $1 "; do [ $(date +%s) -ge $end ] && return 1; sleep 0.5; done; }
evlog() { echo "events: $(adb logcat -d -s EPR:I | grep -oE 'EVT (relocate|painted|scan_started|scan_complete|page_total_[a-z]+|app_foreground)' | sort | uniq -c | tr -s ' ' | tr '\n' ';')" | tee -a $OUT; adb logcat -d -s EPR:I | grep -E 'EVT (scan_complete|page_total_exact|page_total_estimate)' | sed 's/^/  /' | tee -a $OUT; }

setup() { # setup <apk>
  adb install -r "$1" >/dev/null
  adb shell pm clear $PKG >/dev/null
  adb shell appops set $PKG MANAGE_EXTERNAL_STORAGE allow
  adb shell settings put system screen_off_timeout 1800000
  adb shell svc power stayon false
  adb shell input keyevent 224   # wake
  adb shell wm dismiss-keyguard
  adb logcat -c
  adb shell am start -W -n $PKG/app.fernreader.MainActivity >/dev/null
  wait_ev scan_complete 60; sleep 5
}

echo "##### benchmark build" | tee -a $OUT
setup $APKDIR/benchmark/app-play-benchmark.apk
adb shell dumpsys battery unplug
adb shell dumpsys batterystats --reset >/dev/null

# baseline: emulator noise with the app on the library, before anything (disk, SF)
# a0: first open of the book, 60 s untouched (includes the page counter where it runs)
adb logcat -c
sc_begin "a0 first open $BOOK, 60 s untouched"
cmd openPath $BOOK
sleep 60
sc_end; evlog
# a: same page, untouched 60 s (counter done: C-108 c, with Book pages it no longer runs, so do not wait long for it)
wait_ev page_total_exact 10 || echo "no exact total" | tee -a $OUT
sleep 5; adb logcat -c
sc_begin "a still page 60 s"
sleep 60
sc_end; evlog
# b: 20 turns, one every 3 s
adb logcat -c
sc_begin "b 20 swipes, 3 s apart"
for i in $(seq 20); do swipe; sleep 3; done
sleep 2
sc_end; evlog
# c1: back to the library, first 60 s (includes the view-shown scan)
adb logcat -c
sc_begin "c1 library, first 60 s after Back"
adb shell input keyevent 4
sleep 60
sc_end; evlog
# c2: library idle, next 60 s
adb logcat -c
sc_begin "c2 library idle 60 s"
sleep 60
sc_end; evlog
# d: reopen (same book: stored position), read 5 turns, Home, 180 s in background
cmd openPath $BOOK; wait_ev painted_first 30; sleep 20
for i in 1 2 3 4 5; do swipe; sleep 3; done
sleep 3
adb logcat -c
adb shell dumpsys batterystats --reset >/dev/null
sc_begin "d background 180 s after reading"
adb shell input keyevent 3
sleep 180
sc_end; evlog
echo "--- batterystats (d)" >> $OUT
adb shell dumpsys batterystats $PKG | grep -E -i 'wake|job|alarm|sync|cpu:|Foreground|Background|Cached|Uid u0a|Estimated power' | head -40 >> $OUT
if want e; then
# e: return to the reader from the background, 30 s (foreground scan)
adb logcat -c
sc_begin "e back to the reader from Home, 30 s"
adb shell am start -n $PKG/app.fernreader.MainActivity >/dev/null
sleep 30
sc_end; evlog
fi
if want a1; then
# a1: War and Peace first open: page counter cost on a long book, 90 s
adb shell input keyevent 4; sleep 5
adb logcat -c
sc_begin "a1 first open $WP, 90 s untouched"
cmd openPath $WP
sleep 90
sc_end; evlog
fi
if want a2; then
# a2: one text-size step on War and Peace: recount, 60 s
adb logcat -c
sc_begin "a2 W&P text size +1 step, 60 s"
cmd fontStep 1
sleep 60
sc_end; evlog
fi
if want a3; then
# a3: reopen War and Peace (counts not stored): 60 s
adb shell input keyevent 4; sleep 5; cmd openPath $BOOK; sleep 10; adb shell input keyevent 4; sleep 5
adb logcat -c
sc_begin "a3 W&P reopened (same settings), 60 s"
cmd openPath $WP
sleep 60
sc_end; evlog
cmd fontStep -1; sleep 2

fi
if want z; then
# baseline: app force-stopped, 60 s (emulator disk/SF noise)
adb shell am force-stop $PKG
sc_begin "z baseline: app stopped, 60 s"
sleep 60
snap after | tee -a $OUT

fi
if want io; then
echo "##### debug build (per-app /proc/<pid>/io via run-as; CPU not representative)" | tee -a $OUT
export RUNAS=1
setup $APKDIR/debug/app-play-debug.apk
adb shell dumpsys battery unplug

adb shell am start -W -a android.intent.action.VIEW -d "file://$BOOK" -t application/epub+zip -n $PKG/app.fernreader.MainActivity >/dev/null
sleep 90
adb logcat -c
sc_begin "io-a still page 60 s (debug)"
sleep 60
sc_end; evlog
adb logcat -c
sc_begin "io-b 20 swipes, 3 s apart (debug)"
for i in $(seq 20); do swipe; sleep 3; done
sleep 2
sc_end; evlog
adb logcat -c
sc_begin "io-d background 180 s (debug)"
adb shell input keyevent 3
sleep 180
sc_end; evlog
fi
adb shell settings put system screen_off_timeout 2147483647
echo DONE | tee -a $OUT
