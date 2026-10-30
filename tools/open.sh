#!/usr/bin/env bash
# Usage: tools/open.sh <pkg> <book path on device> [wait-seconds]
# Cold-opens a book directly in the prototype's ReaderActivity and prints the PROTO event log.
A=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
$A shell am force-stop "$1"
$A logcat -c
$A shell am start -W -n "$1/.ReaderActivity" --es path "$2" | grep TotalTime
sleep "${3:-5}"
$A logcat -d -s 'PROTO:I' 'AndroidRuntime:E' | grep -v '^-'
