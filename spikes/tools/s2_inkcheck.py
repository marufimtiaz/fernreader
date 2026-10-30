#!/usr/bin/env python3
"""Blank-frame verdict for a boundary-turn recording (S2).
Usage: python3 -I spikes/tools/s2_inkcheck.py VIDEO...
A frame is "blank/partial" if its ink is below half of the smaller of the first and last frame's ink
(the source and target pages). Prints first/last/min ink and the verdict per video.
"""
import subprocess, sys
for v in sys.argv[1:]:
    out = subprocess.run([sys.executable, '-I', 'tools/blank_frames.py', v], capture_output=True, text=True).stdout
    ink = [float(l.split('ink=')[1]) for l in out.splitlines() if not l.startswith('#') and 'ink=' in l]
    if not ink:
        print(f'{v} no-frames'); continue
    lo = min(ink[0], ink[-1]) / 2
    bad = [x for x in ink if x < lo]
    print(f"{v} first={ink[0]:.4f} last={ink[-1]:.4f} min={min(ink):.4f} frames={len(ink)} {'BLANK' if bad else 'ok'}")
