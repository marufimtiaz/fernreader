#!/usr/bin/env bash
# Usage: AD=... spikes/tools/s2_boundary.sh <tag> <turns> [preload]
# Opens War and Peace at "BOOK ONE: 1805", then repeatedly: turn to the last page of the current chapter
# (no recording), record one boundary `next` with tools/bench_action.sh and report blank-frame min ink.
set -u
TAG=$1; N=$2; PRE=${3:-}; PKG=spike.foliate; A=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
export OUT=${OUT:-results/raw/s2}
cmd() { $A shell am broadcast -a $PKG.CMD --es cmd "$1" --es arg "'${2:-}'" >/dev/null; }
label() { $A logcat -d -s PROTO:I | grep -o 'EVT page_cb t=[0-9]* page [0-9]*/[0-9]*' | tail -1 | awk '{print $NF}'; }
$A shell am force-stop $PKG
$A shell am start -W -n $PKG/proto.foliate.ReaderActivity --es path /sdcard/Books/gutenberg/war-and-peace-epub3.epub \
  ${PRE:+--ez preload true} >/dev/null
sleep 6; $A logcat -c
cmd toc "BOOK ONE: 1805"; sleep 2
for t in $(seq 1 "$N"); do
  for _ in $(seq 1 60); do
    l=$(label); p=${l%/*}; q=${l#*/}
    [ -n "$l" ] && [ "$p" = "$q" ] && break
    cmd next; sleep 0.8
  done
  sleep 1.5
  r=$(bash tools/bench_action.sh $PKG $TAG-$t 3 next)
  v=${r##*video=}
  ink=$(python3 -I tools/blank_frames.py "$v" | tail -1)
  cmd js "logCfi()"; sleep 0.5
  c=$($A logcat -d -s PROTO:I | grep -o "cfi index=.*" | tail -1)
  echo "$r from=$l $ink $c"
done
