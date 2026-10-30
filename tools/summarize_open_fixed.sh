#!/usr/bin/env bash
# Same rule as the spec's tools/summarize_open.sh (t_tap = first visible change, t_ready = last change with
# mean-abs-diff >= 2), but parses "mad=" by pattern: the spec script reads awk field $3, which is wrong when
# mad >= 100 (frame_changes.py then prints "mad=112.70" without a space) and silently drops the paint frame.
# Usage: tools/summarize_open_fixed.sh VIDEO...
SPEC=${SPEC:-$HOME/Projects/epub-reader-spec}
for f in "$@"; do
  python3 -I "$SPEC/tools/frame_changes.py" "$f" 0.5 | awk -v f="$(basename "$f")" '
    /^#/ {next}
    { t=$1; match($0, /mad= *[0-9.]+/); m=substr($0, RSTART+4, RLENGTH-4)+0; if (t0=="") t0=t; if (m>=2) tr=t }
    END { if (t0=="" || tr=="") printf "%-40s open_ms=NA\n", f; else printf "%-40s tap=%.3f ready=%.3f open_ms=%d\n", f, t0, tr, (tr-t0)*1000 }'
done
