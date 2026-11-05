#!/usr/bin/env bash
# tools/desktop.sh — the shared UI in a desktop window (docs/DEVELOPMENT.md).
#   tools/desktop.sh          hot reload: save a file under ui/ and the window updates
#   tools/desktop.sh --plain  a plain run, on any JDK
# Hot reload needs the JetBrains Runtime 21; the first run downloads it to the cache folder (not into the project).
set -euo pipefail
cd "$(dirname "$0")/.."

JBR_VERSION=21.0.9-linux-x64-b1038.91
JBR_URL=https://cache-redirector.jetbrains.com/intellij-jbr/jbrsdk-$JBR_VERSION.tar.gz
JBR_DIR=${XDG_CACHE_HOME:-$HOME/.cache}/fernreader-jbr/$JBR_VERSION
GRADLE=${GRADLE:-./gradlew}

if [ "${1:-}" = "--plain" ]; then exec "$GRADLE" :devtools:desktop:run; fi
[ -z "${1:-}" ] || { echo "usage: tools/desktop.sh [--plain]" >&2; exit 2; }

if [ ! -x "$JBR_DIR/bin/java" ]; then
  echo "downloading the JetBrains Runtime to $JBR_DIR"
  tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
  curl -fL --progress-bar -o "$tmp/jbr.tar.gz" "$JBR_URL"
  curl -fsSL -o "$tmp/jbr.checksum" "$JBR_URL.checksum"
  [ "$(sha512sum "$tmp/jbr.tar.gz" | cut -d' ' -f1)" = "$(cut -d' ' -f1 "$tmp/jbr.checksum")" ] || { echo "JBR checksum mismatch" >&2; exit 1; }
  mkdir -p "$JBR_DIR"
  tar -xzf "$tmp/jbr.tar.gz" -C "$JBR_DIR" --strip-components=1
fi

exec "$GRADLE" :devtools:desktop:hotRunJvm --auto "-Pcompose.reload.jbr.binary=$JBR_DIR/bin/java"
