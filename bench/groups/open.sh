#!/usr/bin/env bash
# bench/groups/open.sh — P-010..P-013 tap → first readable page (screen recording, performance.md "Screen timings";
# measured from the home screen of a running app, "Open targets"), P-015 tap → reader frame (events),
# P-016 reopen / first open in the same process (C-014). --release adds P-014 (huge chapter, M9).
source "$(dirname "$0")/../lib.sh"; require_ad
OUT=$ROOT/bench/results/video; mkdir -p "$OUT"
declare -A BOOKS=([P-010]=$B/gutenberg/alice-epub3-images.epub [P-011]=$WP
                  [P-012]=$B/generated/stress-2000-chapters.epub [P-013]=$B/generated/stress-200-images.epub)
[ "$RELEASE" = 1 ] && BOOKS[P-014]=$B/generated/stress-huge-single-chapter.epub
$A shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
row0() { $A logcat -d -s EPR:I | awk '/EVT row0 /{ match($0,/y=[0-9]+/); y=substr($0,RSTART+2,RLENGTH-2) } END{ print y }'; }
tap_row0() { # <video>: record, tap the first recent row, wait, stop; prints the video open time in ms
  $A logcat -c
  $AD record start "$1" --scope device --hide-touches >/dev/null 2>&1 || echo "WARN record start failed" >&2
  sleep 1.5; $AD press 540 "$Y" >/dev/null; sleep "${WAIT:-6}"
  $AD record stop >/dev/null 2>&1 || echo "WARN record stop failed" >&2
  video_ms "$1"
}
: > "$OUT/p016.txt"
for id in "${!BOOKS[@]}"; do
  # Make the book the most recent one (first row): open it once by path, then return home.
  $A shell am force-stop "$PKG"; $A shell am start -W -n "$PKG/$ACT" >/dev/null; sleep 3
  bench_cmd openPath "${BOOKS[$id]}"; sleep 8; bench_cmd back; sleep 1
  for RUN in 1 2 3; do
    $A shell am force-stop "$PKG"; $A logcat -c
    $A shell am start -W -n "$PKG/$ACT" >/dev/null; sleep 4 # home visible, reader pre-warmed
    Y=$(row0)
    first=$(tap_row0 "$OUT/$id-first-$RUN.mp4"); p15=$(ev_delta tap_book reader_frame)
    bench_cmd back; sleep 2
    again=$(tap_row0 "$OUT/$id-reopen-$RUN.mp4")
    bench_cmd back; sleep 1
    emit "$id" "${first:-NA}" "first open, video"
    emit P-015 "${p15:-NA}" "$id"
    echo "$RUN $id ${first:-NA} ${again:-NA}" | tee -a "$OUT/p016.txt"
  done
done
# P-016 (C-014, C-023): per book score = min((reopen/first) / 0.5, reopen / 400ms); passes at <= 1.0. Per run the worst book.
# Non-numeric or non-positive timings count as 999999 (certain FAIL); the divide-by-zero case is guarded.
for RUN in 1 2 3; do
  emit P-016 "$(awk -v r="$RUN" '
    function num(x) { return (x ~ /^[0-9]+(\.[0-9]+)?$/) ? x + 0 : -1 }
    $1 == r { f = num($3); a = num($4)
              if (f <= 0 || a < 0) q = 999999
              else { q = (a / f) / 0.5; s = a / 400; if (s < q) q = s }
              if (q > m) m = q; seen = 1 }
    END { if (seen) printf "%.3f", m; else print "NA" }' "$OUT/p016.txt")"
done
