#!/usr/bin/env bash
# Build the test corpus into corpus/ (gitignored) with the spec's tools. Downloads are untrusted input.
# Usage: tools/corpus.sh [--no-download]
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd); T=$ROOT/tools/spec-tools; C=$ROOT/corpus
if [ "${1:-}" != "--no-download" ]; then "$T/fetch_corpus.sh" "$C"; date -u +%F > "$C/DOWNLOAD_DATE"; fi
python3 -I "$T/gen_stress.py" "$C/generated"
python3 -I "$T/gen_folder_books.py" "$C/folders" download documents nested3 unicode hidden nomedia androidmedia \
  dup upper livetest renamed moved deletedfolder outsidecustom insidecustom
printf 'not an epub at all\n' > "$C/folders/fake.epub"
mkdir -p "$C/local" && python3 -I "$ROOT/tools/gen_script_test.py" "$C/local"
ls "$C"/*/ | head -100
