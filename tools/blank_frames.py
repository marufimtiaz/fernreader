#!/usr/bin/env python3
"""Detect blank/partial frames in a page-turn recording.

Usage: python3 -I tools/blank_frames.py VIDEO [t_from] [t_to]
For every frame (108x240 grayscale), prints the fraction of "ink" pixels (darker than 128) in the page
area (rows 30-200, i.e. excluding status bar and the prototype's bottom bar and the top-left marker).
A text page has a few percent ink; a blank frame has ~0. Prints the minimum ink fraction in the window.
"""
import subprocess
import sys

W, H = 108, 240


def main():
    video = sys.argv[1]
    t0 = float(sys.argv[2]) if len(sys.argv) > 2 else 0
    t1 = float(sys.argv[3]) if len(sys.argv) > 3 else 1e9
    pts = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                          "frame=pts_time", "-of", "csv=p=0", video], capture_output=True, text=True).stdout.split()
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", video, "-fps_mode", "passthrough", "-vf",
                          f"scale={W}:{H},format=gray", "-f", "rawvideo", "-"], capture_output=True).stdout
    n = len(raw) // (W * H)
    lo = None
    for i in range(n):
        t = float(pts[i]) if i < len(pts) else -1
        if not (t0 <= t <= t1):
            continue
        f = raw[i * W * H:(i + 1) * W * H]
        area = f[30 * W:200 * W]
        ink = sum(1 for x in area if x < 128) / len(area)
        print(f"{t:9.3f} ink={ink:6.4f}")
        lo = ink if lo is None else min(lo, ink)
    print(f"# min_ink={lo}")


if __name__ == "__main__":
    main()
