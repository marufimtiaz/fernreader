#!/usr/bin/env bash
# Memory (P-060..P-062), huge-chapter rotation (P-023) and R-005 Back-while-loading for one prototype.
# Usage: tools/run_heavy.sh <pkg>   (needs AD)
set -u
cd "$(dirname "$0")/.."
PKG=$1; export PKG
A=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
cmd() { $A shell am broadcast -a "$PKG.CMD" --es cmd "$1" --es arg "'${2:-}'" >/dev/null; sleep "${3:-2}"; }
openbook() {
  $A shell am force-stop proto.readium; $A shell am force-stop proto.foliate
  $A shell am start -W -n "$PKG/proto.common.LibraryActivity" --ez reset true --ez prewarm true --es only x >/dev/null
  sleep 2
  $A logcat -c
  $A shell am start -n "$PKG/.ReaderActivity" --es path "$1" >/dev/null
}
died() { $A logcat -d | grep -cE "Process $PKG.*(has died|killed)|lowmemorykiller.*$PKG|FATAL EXCEPTION" ; }
# P-060: War and Peace, after 20 page turns
openbook /sdcard/Books/gutenberg/war-and-peace-epub3.epub; sleep 6
cmd toc "BOOK THREE: 1812" 3
for i in $(seq 20); do cmd next "" 0.7; done; sleep 3
echo "P-060 $(tools/mem_total.sh)"
# P-061: huge single chapter, peak while loading (sampled every ~2 s) and steady state
openbook /sdcard/Books/generated/stress-huge-single-chapter.epub
peak=0
for i in $(seq 20); do m=$(tools/mem_total.sh | sed 's/.*total_mb=//'); [ "$m" -gt "$peak" ] && peak=$m; done
for i in $(seq 10); do cmd next "" 1; done; sleep 5
echo "P-061 peak_sampled_mb=$peak steady $(tools/mem_total.sh) deaths=$(died)"
# P-023: rotation on the huge chapter
for r in 1 2 3; do
  OUT=results/raw/action tools/bench_action.sh "$PKG" "huge-rot-land-$r" 40 rotate land | sed "s/^/P-023 run=$r /"
  OUT=results/raw/action tools/bench_action.sh "$PKG" "huge-rot-port-$r" 40 rotate port | sed "s/^/P-023 run=$r /"
done
# R-005: Back 1 s after opening the huge chapter -> library visible within 200 ms
for r in 1 2 3; do
  openbook /sdcard/Books/generated/stress-huge-single-chapter.epub; sleep 1
  OUT=results/raw/action tools/bench_action.sh "$PKG" "huge-back-$r" 4 back | sed "s/^/R-005 run=$r /"
done
# P-062: 200-image book, 50 plates
openbook /sdcard/Books/generated/stress-200-images.epub; sleep 6
for i in $(seq 10); do cmd next "" 1.5; done; sleep 2; m10=$(tools/mem_total.sh)
for i in $(seq 40); do cmd next "" 1.5; done; sleep 2; m50=$(tools/mem_total.sh)
echo "P-062 after10 $m10 | after50 $m50 deaths=$(died)"
