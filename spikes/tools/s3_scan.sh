#!/usr/bin/env bash
# Usage: spikes/tools/s3_scan.sh <strategy> <mode> <workers> <runs>  -> scan_spike lines (force-stop before each run)
set -u
S=$1; M=$2; W=$3; RUNS=$4; PKG=spike.foliate; A=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
for r in $(seq 1 "$RUNS"); do
  $A shell am force-stop $PKG
  $A shell am start -W -n $PKG/proto.foliate.ReaderActivity --es path /sdcard/Books/gutenberg/alice-epub3-images.epub >/dev/null
  sleep 4; $A logcat -c
  $A shell am broadcast -a $PKG.CMD --es cmd scan --es arg "'$S $M $W'" >/dev/null
  for _ in $(seq 1 120); do sleep 0.5; $A logcat -d -s PROTO:I | grep -q scan_spike && break; done
  $A logcat -d -s PROTO:I | grep -o 'scan_spike.*' | sed "s/^/run=$r /"
done
