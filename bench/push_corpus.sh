#!/usr/bin/env bash
# Push the corpus and the folder-discovery set to the emulator (placement per spec corpus.md section 4).
# Usage: bench/push_corpus.sh
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd); C=$ROOT/corpus; F=$C/folders
A=${ADB:-$HOME/Android/Sdk/platform-tools/adb}
command -v "$A" >/dev/null 2>&1 || A=adb
S=/sdcard
# Start from a clean placement so reruns and leftovers from earlier tests (Live/, renamed-NEW, moved copies) don't skew counts.
$A shell "cd $S && rm -rf Books/gutenberg Books/epub3-samples Books/generated Books/local Books/Fiction Books/ToDelete \
  Books/insidecustom.epub Download/Live Documents/Books .hidden NoMedia Android/media/com.example.test 'Livres Été 2026' \
  Download/download.epub Download/dup.epub Download/renamed.epub Download/renamed-NEW.epub Download/moved.epub \
  Download/outsidecustom.epub Download/fake.epub Download/UPPER.EPUB"
$A shell mkdir -p $S/Books $S/Download "$S/Documents/Books" $S/Books/Fiction/Classics/Old "'$S/Livres Été 2026'" \
  $S/.hidden $S/NoMedia $S/Android/media/com.example.test $S/Books/ToDelete
$A push "$C/gutenberg" "$C/epub3-samples" "$C/local" $S/Books/ >/dev/null
# generated/: skip a stray fake.epub if an older run left one there (the real fake.epub lives in folders/).
$A shell mkdir -p $S/Books/generated
for f in "$C"/generated/*.epub; do
  [ "$(basename "$f")" = fake.epub ] && continue
  $A push "$f" $S/Books/generated/ >/dev/null
done
for f in download dup renamed moved outsidecustom; do $A push "$F/$f.epub" $S/Download/ >/dev/null; done
$A push "$F/fake.epub" $S/Download/fake.epub >/dev/null
$A push "$F/upper.epub" $S/Download/UPPER.EPUB >/dev/null
$A push "$F/documents.epub" "$S/Documents/Books/" >/dev/null
$A push "$F/dup.epub" "$S/Documents/Books/dup-copy.epub" >/dev/null
$A push "$F/nested3.epub" $S/Books/Fiction/Classics/Old/ >/dev/null
$A push "$F/unicode.epub" "$S/Livres Été 2026/" >/dev/null
$A push "$F/hidden.epub" $S/.hidden/ >/dev/null
$A shell touch $S/NoMedia/.nomedia && $A push "$F/nomedia.epub" $S/NoMedia/ >/dev/null
$A push "$F/androidmedia.epub" $S/Android/media/com.example.test/ >/dev/null
$A push "$F/deletedfolder.epub" $S/Books/ToDelete/ >/dev/null
$A push "$F/insidecustom.epub" $S/Books/ >/dev/null
# livetest.epub is pushed later by the F-012 test into Download/Live/.
$A shell "find $S/ -iname '*.epub' -not -path '*/Android/data/*' | wc -l"
