#!/usr/bin/env bash
# bench/groups/search.sh — M5: in-book search, measured from the search command (event search_cmd) after the book has
# been open for 6 s (the index is built by then, as in the bake-off):
#   P-040 "the" in War and Peace: first results (search_cmd -> search_first)
#   P-041 "Borodinó" in War and Peace: complete list (search_cmd -> search_done; 108 hits)
#   P-042 "zygote" in War and Peace: "0 results" shown (search_cmd -> search_done; 0 hits)
#   P-043 "augmented matrix" in Linear Algebra: first results (search_cmd -> search_first)
# P-044 (huge chapter) is a release check (M9). Needs no agent-device: only broadcasts commands and reads logcat.
source "$(dirname "$0")/../lib.sh"
$A shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
$A shell am force-stop "$PKG"; $A shell am start -W -n "$PKG/$ACT" >/dev/null; sleep 3
LA=$B/epub3-samples/linear-algebra.epub

# ev_extra <event>: the text after "t=<ms> " of the first <event> line in logcat (search_done carries the hit total).
ev_extra() {
  $A logcat -d -s EPR:I | awk -v a="EVT $1 " 'index($0, a) { sub(/.*t=[0-9]+ ?/, ""); print; found=1; exit } END { if (!found) print "NA" }'
}

# run_search <id> <text> <from-event> <to-event> [expected-total]: one command, one emitted value.
run_search() {
  local id=$1 text=$2 to=$3 want=${4:-}
  $A logcat -c
  bench_cmd search "$text"; sleep 3
  local note="query='$text'"
  if [ "$to" = search_done ]; then
    local total; total=$(ev_extra search_done)
    note="$note total=$total"
    [ -z "$want" ] || [ "$total" = "$want" ] || note="$note WRONG_TOTAL(want $want)"
  fi
  emit "$id" "$(ev_delta search_cmd "$to")" "$note"
  bench_cmd hideSearch; sleep 1
}

for RUN in 1 2 3; do
  bench_cmd openPath "$WP"; sleep 6
  run_search P-040 "the" search_first
  run_search P-041 "Borodinó" search_done 108
  run_search P-042 "zygote" search_done 0
  bench_cmd back; sleep 2
done
for RUN in 1 2 3; do
  bench_cmd openPath "$LA"; sleep 6
  run_search P-043 "augmented matrix" search_first
  bench_cmd back; sleep 2
done
