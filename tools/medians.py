#!/usr/bin/env python3
"""Median per (prefix fields) of key=value numbers in result lines.

Usage: python3 -I tools/medians.py RESULTS.txt KEY [KEY...]
Groups lines by everything before the first "run=" field; prints median and all values per key.
"""
import re
import statistics
import sys
from collections import OrderedDict


def main():
    path, keys = sys.argv[1], sys.argv[2:]
    groups = OrderedDict()
    for line in open(path, encoding="utf-8"):
        if "run=" not in line:
            continue
        head = line.split("run=")[0].strip()
        kv = dict(re.findall(r"(\w+)=(-?\d+)", line))
        g = groups.setdefault(head, {k: [] for k in keys})
        for k in keys:
            if k in kv:
                g[k].append(int(kv[k]))
    for head, g in groups.items():
        cells = []
        for k in keys:
            v = g[k]
            cells.append(f"{k}: median={statistics.median(v):.0f} {v}" if v else f"{k}: NA")
        print(f"{head:70s} " + " | ".join(cells))


if __name__ == "__main__":
    main()
