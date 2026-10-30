#!/usr/bin/env bash
# Search (P-040..P-044, F-050) and memory (P-060..P-062) for one prototype; R-005 back-while-loading.
# Usage: tools/run_lean.sh <pkg> [chunk=true|false]
set -u
cd "$(dirname "$0")/.."
PKG=$1; CH=${2:-false}; export PKG CH
A=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
B=/sdcard/Books
WP=$B/gutenberg/war-and-peace-epub3.epub
tools/bench_search.sh $PKG $WP "the" 3 90 | sed 's/^/P-040 /'
tools/bench_search.sh $PKG $WP "Borodinó" 3 60 | sed 's/^/P-041 /'
tools/bench_search.sh $PKG $WP "Borodino" 1 60 | sed 's/^/F-050-diacritics /'
tools/bench_search.sh $PKG $WP "zygote" 3 60 | sed 's/^/P-042 /'
tools/bench_search.sh $PKG $B/epub3-samples/linear-algebra.epub "augmented matrix" 3 90 | sed 's/^/P-043 /'
tools/bench_search.sh $PKG $B/generated/stress-2000-chapters.epub "MARKERWORDMANY" 1 90 | sed 's/^/F-050-2000ch /'
cmd() { $A shell am broadcast -a "$PKG.CMD" --es cmd "$1" --es arg "'${2:-}'" >/dev/null; sleep "${3:-2}"; }
open() { # path wait
  $A shell am force-stop proto.readium; $A shell am force-stop proto.foliate; $A logcat -c
  $A shell am start -W -n "$PKG/.ReaderActivity" --es path "$1" --ez chunk "$CH" >/dev/null; sleep "$2"
}
died() { $A logcat -d | grep -cE "FATAL EXCEPTION|has died|lowmemorykiller.*$PKG"; }
open $WP 6; cmd toc "BOOK THREE: 1805" 3
for i in $(seq 20); do cmd next "" 0.7; done; sleep 3
echo "P-060 $(tools/mem_total.sh)"
HW=${HUGEWAIT:-15}
$A shell am force-stop proto.readium; $A shell am force-stop proto.foliate; $A logcat -c
$A shell am start -n "$PKG/.ReaderActivity" --es path $B/generated/stress-huge-single-chapter.epub --ez chunk "$CH" >/dev/null
peak=0; end=$(( $(date +%s) + HW ))
while [ "$(date +%s)" -lt $end ]; do m=$(tools/mem_total.sh | sed 's/.*total_mb=//'); [ "$m" -gt "$peak" ] && peak=$m; done
for i in $(seq 10); do cmd next "" 1; done; sleep 5
echo "P-061 peak_sampled_mb=$peak steady $(tools/mem_total.sh) deaths=$(died)"
OPENWAIT=$HW tools/bench_search.sh $PKG $B/generated/stress-huge-single-chapter.epub "MARKERWORDHUGE" 1 $((HW + 60)) | sed 's/^/P-044 /'
open $B/generated/stress-200-images.epub 6
for i in $(seq 10); do cmd next "" 1.5; done; sleep 2; m10=$(tools/mem_total.sh)
for i in $(seq 40); do cmd next "" 1.5; done; sleep 2; m50=$(tools/mem_total.sh)
echo "P-062 after10 $m10 | after50 $m50 deaths=$(died)"
