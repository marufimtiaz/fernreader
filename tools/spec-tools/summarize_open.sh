#!/usr/bin/env bash
# Summarize open-time recordings: t_tap = first visible change (touch ripple),
# t_ready = last change with mean-abs-diff >= 2 (content painted; later changes are only status-bar noise).
# Parses "mad=" by pattern (frame_changes.py prints "mad=112.70" with no padding when mad >= 100).
# Usage: tools/summarize_open.sh VIDEO...
for f in "$@"; do
  python3 -I "$(dirname "$0")/frame_changes.py" "$f" 0.5 | awk -v f="$(basename "$f")" '
    /^#/ {next}
    { t=$1; match($0, /mad= *[0-9.]+/); m=substr($0, RSTART+4, RLENGTH-4)+0; if (t0=="") t0=t; if (m>=2) tr=t }
    END { if (t0=="" || tr=="") printf "%-40s open_ms=NA\n", f; else printf "%-40s tap=%.3f ready=%.3f open_ms=%d\n", f, t0, tr, (tr-t0)*1000 }'
done
