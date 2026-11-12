#!/bin/bash
# Per-thread attribution: CPU (benchmark build) and write syscalls (debug build, run-as).
set -u
D=$(cd "$(dirname "$0")" && pwd)
APKDIR=$(cd "$D/../.." && pwd)/app/build/outputs/apk/play
OUT=${OUT:-$D/threads.txt}; : > $OUT
LOCK=/tmp/epub-reader-device.lock
until mkdir $LOCK 2>/dev/null; do echo "lock held by: $(cat $LOCK/owner 2>/dev/null)"; sleep 30; done
echo "power-audit (research) $(date '+%F %T')" > $LOCK/owner
cleanup() { adb shell settings put system screen_off_timeout 2147483647; adb shell settings put global stay_on_while_plugged_in 1; rm -rf $LOCK; }
trap cleanup EXIT INT TERM
PKG=app.fernreader
BOOK=/sdcard/Books/gutenberg/moby-dick-epub2-noimages.epub
swipe() { adb shell input swipe 900 1300 150 1300 120; }
# tstat <pid> [runas]: "tid comm ticks [wchar syscw write_bytes]" per thread
tcpu() { adb shell "for t in /proc/$1/task/*; do echo \$(basename \$t) \$(cat \$t/comm | tr ' ' _) \$(cut -d')' -f2 \$t/stat | awk '{print \$12+\$13}'); done" 2>/dev/null; }
tio() { adb shell "run-as $PKG sh -c 'for t in /proc/$1/task/*; do echo \$(basename \$t) \$(cat \$t/comm | tr \" \" _) \$(grep -E \"^(wchar|syscw|write_bytes)\" \$t/io | cut -d\" \" -f2 | tr \"\\n\" \" \"); done'" 2>/dev/null; }
diff_cpu() { join <(sort $1) <(sort $2) | awk '{d=$5-$3; if (d>0) a[$2]+=d} END {for (k in a) printf "  %-28s %6.2f s\n", k, a[k]/100}' | sort -k2 -nr | head -15; }
diff_io() { join <(sort $1) <(sort $2) | awk '{w=$7-$3; c=$8-$4; b=$9-$5; if (w>0||b>0) {W[$2]+=w; C[$2]+=c; B[$2]+=b}} END {for (k in W) printf "  %-28s wchar=%9d syscw=%6d write_bytes=%8d\n", k, W[k], C[k], B[k]}' | sort -t= -k2 -nr | head -12; }
open_book() { adb shell am start -W -a android.intent.action.VIEW -d "file://$BOOK" -t application/epub+zip -n $PKG/app.epubreader.MainActivity >/dev/null; }
adb shell input keyevent 224; adb shell wm dismiss-keyguard

# debug build is installed now: write attribution
open_book; sleep 20
p=$(adb shell pidof $PKG | tr -d '\r')
tio $p > $D/io0; sleep 30; tio $p > $D/io1
for i in $(seq 10); do swipe; sleep 3; done; tio $p > $D/io2
{ echo "=== debug: writes per thread, 30 s still page"; diff_io $D/io0 $D/io1; echo "=== debug: writes per thread, 10 swipes"; diff_io $D/io1 $D/io2; } | tee -a $OUT

# benchmark build: CPU attribution
adb install -r $APKDIR/benchmark/app-play-benchmark.apk >/dev/null
adb shell settings put system screen_off_timeout 1800000
open_book; sleep 40
p=$(adb shell pidof $PKG | tr -d '\r')
tcpu $p > $D/c0; sleep 60; tcpu $p > $D/c1
for i in $(seq 10); do swipe; sleep 3; done; tcpu $p > $D/c2
{ echo "=== benchmark: CPU per thread, 60 s still page"; diff_cpu $D/c0 $D/c1; echo "=== benchmark: CPU per thread, 10 swipes"; diff_cpu $D/c1 $D/c2; } | tee -a $OUT
adb shell dumpsys gfxinfo $PKG | grep -E 'Total frames|Janky|50th|90th' | tee -a $OUT
echo DONE >> $OUT
