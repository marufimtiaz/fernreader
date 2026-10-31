#!/usr/bin/env bash
# Fetch the public test corpus. Each source goes into its own subdirectory of corpus/.
# Usage: tools/fetch_corpus.sh [corpus-dir]
set -euo pipefail
OUT="${1:-$(dirname "$0")/../corpus}"
mkdir -p "$OUT/gutenberg" "$OUT/epub3-samples"

# Project Gutenberg (public domain). epub3.images = EPUB3 with images; epub.noimages = legacy EPUB2 + NCX.
# fetch URL DEST: download with retries, keep only if it is a complete zip (partial downloads happen).
fetch() {
  [ -s "$2" ] && return 0
  for _ in 1 2 3 4 5; do
    curl --http1.1 --retry 3 -fsSL -A "Mozilla/5.0 corpus-fetch" -o "$2.part" "$1" \
      && python3 -I -c 'import sys,zipfile; sys.exit(zipfile.ZipFile(sys.argv[1]).testzip() is not None)' "$2.part" \
      && mv "$2.part" "$2" && return 0
    sleep 3
  done
  rm -f "$2.part"; echo "FAILED: $1" >&2; return 1
}
gb() { fetch "https://www.gutenberg.org/ebooks/$1.$2" "$OUT/gutenberg/$3"; }
gb 1342 epub3.images  pride-and-prejudice-epub3-images.epub
gb 11   epub3.images  alice-epub3-images.epub
gb 84   epub3.images  frankenstein-epub3.epub
gb 2600 epub3.images  war-and-peace-epub3.epub
gb 100  epub3.images  shakespeare-complete-epub3.epub
gb 1342 epub.noimages pride-and-prejudice-epub2-noimages.epub
gb 2701 epub.noimages moby-dick-epub2-noimages.epub
gb 1400 epub3.images  great-expectations-epub3-images.epub

# IDPF/W3C epub3-samples, release 20230704
REL=https://github.com/IDPF/epub3-samples/releases/download/20230704
for f in page-blanche cole-voyage-of-life haruko-html-jpeg kusamakura-japanese-vertical-writing \
         regime-anticancer-arabic linear-algebra wasteland-otf-obf wasteland-woff mymedia_lite \
         svg-in-spine sous-le-vent_svg-in-spine childrens-literature moby-dick georgia-cfi \
         internallinks accessible_epub_3 epub30-spec hefty-water israelsailing \
         vertically-scrollable-manga jlreq-in-japanese figure-gallery-bindings; do
  fetch "$REL/$f.epub" "$OUT/epub3-samples/$f.epub"
done
ls -la "$OUT"/gutenberg "$OUT"/epub3-samples
