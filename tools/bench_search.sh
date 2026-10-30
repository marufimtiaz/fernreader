#!/usr/bin/env bash
# In-book search timing from logged events (search_start -> first results shown / complete list shown).
# Usage: tools/bench_search.sh <pkg> <book path> <query> <runs> [timeout s]
set -u
PKG=$1; BOOK=$2; Q=$3; RUNS=$4; TMO=${5:-60}
A=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
for r in $(seq 1 "$RUNS"); do
  $A shell am force-stop "$PKG"
  $A shell am start -W -n "$PKG/proto.common.LibraryActivity" --ez reset true --ez prewarm true --es only x >/dev/null
  $A shell am start -W -n "$PKG/.ReaderActivity" --es path "$BOOK" >/dev/null
  sleep 6
  $A logcat -c
  $A shell am broadcast -a "$PKG.CMD" --es cmd search --es arg "'$Q'" >/dev/null
  for _ in $(seq 1 "$TMO"); do sleep 1; $A logcat -d -s PROTO:I | grep -q "EVT search_done" && break; done
  $A logcat -d -s PROTO:I | awk -v q="$Q" -v r="$r" -v p="${PKG##*.}" '
    function t() { match($0, /t=[0-9]+/); return substr($0, RSTART+2, RLENGTH-2) }
    /EVT search_start/ { s=t() } /EVT search_first/ && f=="" { f=t() }
    /EVT search_done/ { d=t(); match($0, /n=[0-9]+/); n=substr($0, RSTART+2, RLENGTH-2) }
    END { printf "%s q=\"%s\" run=%s first_ms=%s done_ms=%s hits=%s\n", p, q, r, (f==""?"NA":f-s), (d==""?"TIMEOUT":d-s), n }'
done
