#!/usr/bin/env bash
# Usage: spikes/tools/s1_pagecount.sh <strategy> <fontScale> <runs> [lanes|batch]
# Prints: run=N font=F count_done strategy=S arg=A font=F total=T ms=M  (and saves count_results to $OUTDIR)
set -u
S=$1; F=$2; RUNS=$3; B=${4:-0}; PKG=spike.foliate
A=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
OUTDIR=${OUTDIR:-/tmp/s1}; mkdir -p "$OUTDIR"
for r in $(seq 1 "$RUNS"); do
  $A shell am force-stop $PKG
  $A shell am start -W -n $PKG/proto.foliate.ReaderActivity --es path /sdcard/Books/gutenberg/war-and-peace-epub3.epub >/dev/null
  sleep 6
  $A shell am broadcast -a $PKG.CMD --es cmd font --es arg "$F" >/dev/null; sleep 2
  $A logcat -c
  $A shell am broadcast -a $PKG.CMD --es cmd count --es arg "'$S $B'" >/dev/null
  for _ in $(seq 1 150); do sleep 1; $A logcat -d -s PROTO:I | grep -q count_done && break; done
  $A logcat -d -s PROTO:I | grep -o 'count_results.*' > "$OUTDIR/results-$S-$B-$F-$r.json"
  $A logcat -d -s PROTO:I | grep -o 'count_done.*' | sed "s/^/run=$r font=$F /"
done
