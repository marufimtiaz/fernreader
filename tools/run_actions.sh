#!/usr/bin/env bash
# In-reader action timings for one prototype (3 runs each): P-020 text size, P-022 rotation,
# P-030 TOC jump, P-026 chapter-boundary turn (+ blank-frame check), all on War and Peace.
# Usage: tools/run_actions.sh <pkg>   (needs AD)
set -u
cd "$(dirname "$0")/.."
PKG=$1; P=${PKG##*.}
A=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
cmd() { $A shell am broadcast -a "$PKG.CMD" --es cmd "$1" --es arg "'${2:-}'" >/dev/null; sleep "${3:-3}"; }
openbook() {
  $A shell am force-stop "$PKG"
  $A shell am start -W -n "$PKG/proto.common.LibraryActivity" --ez reset true --ez prewarm true --es only x >/dev/null
  sleep 2
  $A shell am start -W -n "$PKG/.ReaderActivity" --es path "$1" >/dev/null
  sleep "${2:-6}"
}
WP=/sdcard/Books/gutenberg/war-and-peace-epub3.epub
openbook $WP
cmd toc "BOOK THREE: 1805" 4
cmd next "" 2; cmd next "" 2
[ "${ONLY:-all}" = all ] || [ "$ONLY" = reflow ] && for r in 1 2 3; do
  tools/bench_action.sh "$PKG" "font-up-$r" 4 fontUp | sed "s/^/P-020 run=$r /"
  tools/bench_action.sh "$PKG" "font-down-$r" 4 fontDown | sed "s/^/P-020 run=$r /"
done
[ "${ONLY:-all}" = all ] || [ "$ONLY" = reflow ] && for r in 1 2 3; do
  tools/bench_action.sh "$PKG" "rot-land-$r" 5 rotate land | sed "s/^/P-022 run=$r /"
  tools/bench_action.sh "$PKG" "rot-port-$r" 5 rotate port | sed "s/^/P-022 run=$r /"
done
[ "${ONLY:-all}" = all ] && for r in 1 2 3; do
  cmd toc "#0" 4
  tools/bench_action.sh "$PKG" "toc-$r" 6 toc "SECOND EPILOGUE" | sed "s/^/P-030 run=$r /"
done
[ "${ONLY:-all}" = all ] && for r in 1 2 3; do
  ch=$((20 + r * 7))
  cmd toc "#$ch" 4      # start of a chapter
  cmd prev "" 4         # -> last page of the previous chapter
  line=$(tools/bench_action.sh "$PKG" "boundary-$r" 5 next)
  v=${line##*video=}
  echo "P-026 run=$r $line min_ink=$(python3 -I tools/blank_frames.py "$v" | tail -1 | sed 's/.*=//')"
done
