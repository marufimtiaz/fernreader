#!/usr/bin/env bash
# Usage: spikes/tools/s1_agree.sh <fontScale> <strategy> <arg> <indices...>
# Counts with the given strategy, then shows each listed section in the visible paginator and logs both counts.
set -u
F=$1; S=$2; B=$3; shift 3; PKG=spike.foliate; A=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
$A shell am force-stop $PKG
$A shell am start -W -n $PKG/proto.foliate.ReaderActivity --es path /sdcard/Books/gutenberg/war-and-peace-epub3.epub >/dev/null
sleep 6
$A shell am broadcast -a $PKG.CMD --es cmd font --es arg "$F" >/dev/null; sleep 2
$A logcat -c
$A shell am broadcast -a $PKG.CMD --es cmd count --es arg "'$S $B'" >/dev/null
for _ in $(seq 1 60); do sleep 1; $A logcat -d -s PROTO:I | grep -q count_done && break; done
$A shell am broadcast -a $PKG.CMD --es cmd sample --es arg "'$*'" >/dev/null
for _ in $(seq 1 600); do sleep 1; $A logcat -d -s PROTO:I | grep -q sample_done && break; done
$A logcat -d -s PROTO:I | grep -o 'count_done.*\|visible_pages.*' | sed "s/^/font=$F /"
