#!/usr/bin/env bash
# Rendering (F-080..F-083) and robustness (R-001..R-004, R-007) screenshots for one prototype.
# Usage: tools/run_checks.sh <pkg>  -> results/raw/shots/<engine>/<case>.png + .log, summary on stdout
set -u
cd "$(dirname "$0")/.."
PKG=$1; E=${PKG##*.}; D=results/raw/shots/$E; mkdir -p "$D"
B=/sdcard/Books
s() { local name=$1; shift; echo "== $name: $(tools/shot.sh "$PKG" "$@" | tr '\n' ' ')"; }
s rtl-arabic        $B/epub3-samples/regime-anticancer-arabic.epub $D/rtl-arabic.png 6 next next
s vertical-ja       $B/epub3-samples/kusamakura-japanese-vertical-writing.epub $D/vertical-ja.png 8 next next next
s fxl-page-blanche  $B/epub3-samples/page-blanche.epub $D/fxl-page-blanche.png 6 next
s fxl-haruko        $B/epub3-samples/haruko-html-jpeg.epub $D/fxl-haruko.png 6 next next
STEP=12 s mathml    $B/epub3-samples/linear-algebra.epub $D/mathml.png 8 "search=augmented matrix" "hit=3"
s font-obf          $B/epub3-samples/wasteland-otf-obf.epub $D/font-obf.png 6 next next
s font-woff         $B/epub3-samples/wasteland-woff.epub $D/font-woff.png 6 next next
s svg-spine         $B/epub3-samples/svg-in-spine.epub $D/svg-spine.png 6 next
s svg-spine-2       $B/epub3-samples/sous-le-vent_svg-in-spine.epub $D/svg-spine-2.png 6 next next
s epub2-ncx         $B/generated/epub2-ncx-only.epub $D/epub2-ncx.png 6 "toc=#3"
s script-test       $B/local/script-test.epub $D/script-test.png 6
for m in missing-files-bad-xml broken-opf no-container not-a-zip truncated empty-spine; do
  s malformed-$m $B/generated/malformed-$m.epub $D/malformed-$m.png 6
done
for i in 1 2 3 4 5; do
  s bad-xml-page$i $B/generated/malformed-missing-files-bad-xml.epub $D/bad-xml-page$i.png 6 $(yes next | head -$i)
done
s bad-xml-toc-missing $B/generated/malformed-missing-files-bad-xml.epub $D/bad-xml-toc-missing.png 6 "toc=#2"
s fake              $B/generated/fake.epub $D/fake.png 6
