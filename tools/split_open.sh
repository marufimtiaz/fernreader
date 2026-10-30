#!/usr/bin/env bash
# Splits an open recording into shell time (tap -> first large change = reader frame shown) and
# engine time (reader frame -> last large change = page painted). Same thresholds as summarize_open.
# Usage: tools/split_open.sh VIDEO...
SPEC=${SPEC:-$HOME/Projects/epub-reader-spec}
for f in "$@"; do
  python3 -I "$SPEC/tools/frame_changes.py" "$f" 0.5 | awk -v f="$(basename "$f")" '
    /^#/ {next}
    { t=$1; match($0, /mad= *[0-9.]+/); m=substr($0, RSTART+4, RLENGTH-4)+0
      if (t0=="") t0=t; if (m>=2) { if (tf=="") tf=t; tr=t } }
    END { if (tr=="") print f, "NA"; else printf "%s shell_ms=%d engine_ms=%d total_ms=%d\n", f, (tf-t0)*1000, (tr-tf)*1000, (tr-t0)*1000 }'
done
