#!/usr/bin/env python3
"""Print timestamps of visible changes in a (variable-frame-rate) screen recording.

Usage: python -I tools/frame_changes.py VIDEO [threshold]
Each output line: pts_seconds  mean_abs_diff  changed_fraction  bbox(row0-row1 in 0..239)
Frames are downscaled to 108x240 grayscale; a frame is reported when it differs from the previous one.
"""
import subprocess
import sys

W, H = 108, 240


def main():
    video = sys.argv[1]
    thr = float(sys.argv[2]) if len(sys.argv) > 2 else 0.5
    pts = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                          "frame=pts_time", "-of", "csv=p=0", video], capture_output=True, text=True).stdout.split()
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", video, "-fps_mode", "passthrough", "-vf",
                          f"scale={W}:{H},format=gray", "-f", "rawvideo", "-"], capture_output=True).stdout
    n = len(raw) // (W * H)
    prev = None
    for i in range(n):
        f = raw[i * W * H:(i + 1) * W * H]
        if prev is not None:
            rows = []
            tot = 0
            changed = 0
            for r in range(H):
                a = f[r * W:(r + 1) * W]
                b = prev[r * W:(r + 1) * W]
                d = sum(abs(x - y) for x, y in zip(a, b))
                c = sum(1 for x, y in zip(a, b) if abs(x - y) > 12)
                tot += d
                changed += c
                if c:
                    rows.append(r)
            mad = tot / (W * H)
            if mad >= thr or (thr <= 0.5 and changed > 20):
                t = float(pts[i]) if i < len(pts) else -1
                print(f"{t:9.3f}  mad={mad:6.2f}  changed={changed / (W * H):5.3f}  rows={rows[0] if rows else '-'}-{rows[-1] if rows else '-'}")
        prev = f
    print(f"# frames={n} last_pts={pts[-1] if pts else '?'}")


if __name__ == "__main__":
    main()
