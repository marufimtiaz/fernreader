#!/usr/bin/env python3
"""Milestone/release gate: medians of benchmark results vs performance.md Must values (C-015: Must only).

Usage: python3 -I bench/gate.py RESULTS.jsonl bench/targets.json
Every target is an upper bound (ms, MB, score, count, pct). Empty results fail. A result id fails when it has no target, fewer than
3 runs, or a median above the Must value. Exit code 1 if anything fails.
"""
import json
import sys


def median(xs):
    s = sorted(xs)
    n = len(s)
    return s[n // 2] if n % 2 else (s[n // 2 - 1] + s[n // 2]) / 2


def evaluate(rows, targets):
    by_id = {}
    for r in rows:
        by_id.setdefault(r["id"], []).append(float(r["value"]))
    out = {}
    for id_, vals in sorted(by_id.items()):
        m = median(vals)
        t = targets.get(id_)
        if t is None:
            out[id_] = {"status": "FAIL", "median": m, "reason": "no target defined"}
        elif len(vals) < 3:
            out[id_] = {"status": "FAIL", "median": m, "reason": f"only {len(vals)} runs (need >= 3)"}
        elif m > t["must"]:
            out[id_] = {"status": "FAIL", "median": m, "reason": f"median {m:g} > must {t['must']:g}"}
        else:
            out[id_] = {"status": "PASS", "median": m, "reason": ""}
    return out


def main(argv):
    results, targets = argv
    with open(results, encoding="utf-8") as f:
        rows = [json.loads(l) for l in f if l.strip()]
    with open(targets, encoding="utf-8") as f:
        tgt = json.load(f)
    if not rows:
        print("FAIL  no results recorded (group missing or crashed?)")
        return 1
    res = evaluate(rows, tgt)
    bad = 0
    for id_, r in res.items():
        print(f"{r['status']:4}  {id_:24} median={r['median']:g}  {r['reason']}")
        bad += r["status"] != "PASS"
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
