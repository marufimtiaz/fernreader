#!/usr/bin/env bash
# Places the corpus.md §4 folder-discovery set on the device (livetest.epub left unpushed).
# Usage: spikes/tools/s3_place_folders.sh <corpus/folders dir>
set -eu
D=$1; A=${ADB:-$HOME/Android/Sdk/platform-tools/adb}; S=/sdcard
p() { $A shell mkdir -p "\"$S/$2\"" >/dev/null; $A push "$D/$1" "$S/$2/${3:-$1}" >/dev/null; echo "placed $2/${3:-$1}"; }
for f in download dup fake renamed moved outsidecustom; do p $f.epub Download; done
p upper.epub Download UPPER.EPUB
p documents.epub Documents/Books
p dup.epub Documents/Books dup-copy.epub
p nested3.epub Books/Fiction/Classics/Old
p unicode.epub "Livres Été 2026"
p hidden.epub .hidden
p nomedia.epub NoMedia; $A shell touch $S/NoMedia/.nomedia
p androidmedia.epub Android/media/com.example.test
p deletedfolder.epub Books/ToDelete
p insidecustom.epub Books
