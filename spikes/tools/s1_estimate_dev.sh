#!/usr/bin/env bash
# Usage: spikes/tools/s1_estimate_dev.sh <fromFont> <toFont> <runs> <scale|text> [firstBatch=24]
# Prints count_estimate (time from count start to estimate) and count_done for the layout change from->to.
set -u
F0=$1; F1=$2; RUNS=$3; BASIS=$4; K=${5:-24}; PKG=spike.foliate; A=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
cmd() { $A shell am broadcast -a $PKG.CMD --es cmd "$1" --es arg "'$2'" >/dev/null; }
waitfor() { for _ in $(seq 1 120); do sleep 1; $A logcat -d -s PROTO:I | grep -q "$1" && break; done; }
for r in $(seq 1 "$RUNS"); do
  $A shell am force-stop $PKG
  $A shell am start -W -n $PKG/proto.foliate.ReaderActivity --es path /sdcard/Books/gutenberg/war-and-peace-epub3.epub >/dev/null
  sleep 6; $A logcat -c
  if [ "$BASIS" = text ]; then cmd stats ""; waitfor section_stats; else cmd font "$F0"; sleep 2; cmd count "batched 24"; waitfor count_done; fi
  cmd font "$F1"; sleep 2; $A logcat -c
  cmd count "${MODE:-stratified} $K"; waitfor count_done
  $A logcat -d -s PROTO:I | grep -o 'count_estimate.*\|count_done.*' | tr '\n' ' ' | sed "s/^/run=$r $F0->$F1 /"; echo
done
