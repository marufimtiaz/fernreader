#!/usr/bin/env bash
# Rendering compatibility (F-080..F-085) and robustness (R-001, R-007) screenshots of the app (M6 task 6.7).
# Usage: tools/run_checks.sh [outdir]  -> <outdir>/<case>.png + .log (default docs/milestones/M6-checks)
# Prerequisites: emulator with the corpus pushed (bench/push_corpus.sh), the benchmark build installed
# (./gradlew :app:assemblePlayBenchmark; adb install -r -d app/build/outputs/apk/play/benchmark/app-play-benchmark.apk),
# all-files access granted (adb shell appops set app.fernreader MANAGE_EXTERNAL_STORAGE allow).
# Review every PNG by eye afterwards; results go into docs/milestones/M6.md "Rendering compatibility (6.7)".
set -u
cd "$(dirname "$0")/.."
D=${1:-docs/milestones/M6-checks}; mkdir -p "$D"
B=/sdcard/Books
s() { local name=$1; shift; local book=$1; shift; echo "== $name: $(tools/shot.sh "$book" "$D/$name.png" "$@" | tr '\n' ' ')"; }
s rtl-arabic        $B/epub3-samples/regime-anticancer-arabic.epub 6 next next
# F-081: two consecutive pages (right-to-left progression, ruby), then the next chapter's first page
s vertical-ja-a     $B/epub3-samples/kusamakura-japanese-vertical-writing.epub 8 "tocIndex=4" next
s vertical-ja-b     $B/epub3-samples/kusamakura-japanese-vertical-writing.epub 8 "tocIndex=4" next next
s vertical-ja-toc   $B/epub3-samples/kusamakura-japanese-vertical-writing.epub 8 toc
s fxl-page-blanche  $B/epub3-samples/page-blanche.epub 6 next next   # spine item 2 is the deliberately white page
s fxl-haruko-a      $B/epub3-samples/haruko-html-jpeg.epub 6 next
s fxl-haruko-b      $B/epub3-samples/haruko-html-jpeg.epub 6 next next
STEP=12 s mathml    $B/epub3-samples/linear-algebra.epub 10 "search=augmented matrix" "searchHit=2"
STEP=12 s mathml-2  $B/epub3-samples/linear-algebra.epub 10 "search=augmented matrix" "searchHit=3" next
s font-obf          $B/epub3-samples/wasteland-otf-obf.epub 6 next next
s font-woff         $B/epub3-samples/wasteland-woff.epub 6 next next
s svg-spine         $B/epub3-samples/svg-in-spine.epub 6 next
s svg-spine-2       $B/epub3-samples/sous-le-vent_svg-in-spine.epub 6 next next
s epub2-ncx-toc     $B/generated/epub2-ncx-only.epub 6 toc
s epub2-ncx         $B/generated/epub2-ncx-only.epub 6 toc "tocIndex=3"
s script-test       $B/local/script-test.epub 6
# R-001 / F-084: good chapter, bad-XML chapter (lenient parse), the missing chapter's placeholder, the second good
# chapter, a TOC entry to the missing file (notice, stays)
s bad-xml-good      $B/generated/malformed-missing-files-bad-xml.epub 6
s bad-xml-bad       $B/generated/malformed-missing-files-bad-xml.epub 6 "tocIndex=1"
s bad-xml-missing   $B/generated/malformed-missing-files-bad-xml.epub 6 "tocIndex=1" lastPage next
s bad-xml-good2     $B/generated/malformed-missing-files-bad-xml.epub 6 "tocIndex=1" lastPage next next
s bad-xml-toc-missing $B/generated/malformed-missing-files-bad-xml.epub 6 toc "tocJump=Missing"
for m in broken-opf no-container not-a-zip truncated empty-spine; do
  s malformed-$m    $B/generated/malformed-$m.epub 6
done
s fake              /sdcard/Download/fake.epub 6
s stress-200-images $B/generated/stress-200-images.epub 8 "tocIndex=40" next
du -sh "$D"
