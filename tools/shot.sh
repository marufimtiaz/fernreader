#!/usr/bin/env bash
# Opens a book in a prototype, runs a sequence of debug commands, then saves a screenshot and the event log.
# Usage: tools/shot.sh <pkg> <book path> <out.png> [wait s] [cmd[=arg] ...]
#   e.g. tools/shot.sh proto.foliate /sdcard/Books/x.epub /tmp/x.png 6 next next "toc=Chapter 2"
set -u
PKG=$1; BOOK=$2; PNG=$3; WAIT=${4:-6}; shift 4 || shift $#
A=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
AD=${AD:?set AD}
$A shell am force-stop "$PKG"
$A logcat -c
$A shell am start -W -n "$PKG/proto.common.LibraryActivity" --ez reset true --es only x >/dev/null
$A shell am start -n "$PKG/.ReaderActivity" --es path "$BOOK" >/dev/null
sleep "$WAIT"
for c in "$@"; do
  a=""; [[ "$c" == *=* ]] && a=${c#*=}; $A shell am broadcast -a "$PKG.CMD" --es cmd "${c%%=*}" --es arg "'$a'" >/dev/null
  sleep "${STEP:-1.5}"
done
$AD screenshot "$PNG" >/dev/null
$A logcat -d -s PROTO:I AndroidRuntime:E | grep -v '^-' > "${PNG%.png}.log"
grep -E "open_error|FATAL|first_page_cb|book_opened|pub_opened" "${PNG%.png}.log" | cut -c 32- | head -5
