#!/usr/bin/env python3
"""Evaluate the page-total estimator against exact per-section counts (S1 step 5).
Usage: python3 -I spikes/tools/s1_estimate.py TEXTLEN.json RESULTS.json [RESULTS.json ...]
RESULTS files hold the `count_results [..]` line logged by the counter (exact pages per section).
Estimator: from the sections laid out visibly (seed = current +/- 1), cpp = sum(chars)/sum(pages);
estimate = sum(seed pages) + sum over other sections of max(1, ceil(chars_i/cpp)) + block images_i
(cover-like sections with < 200 chars count as 1 page + 0 for images).
--mode=half: cpp = chars / (pages - 0.5) over seeds with >= 200 chars (corrects the partial last page).
"""
import json, math, sys


MODE = 'naive'


def estimate(tl, exact, seeds):
    ch = [x['chars'] for x in tl]; im = [x['images'] for x in tl]
    seeds = [i for i in seeds if ch[i] >= 200] or seeds  # cover/title pages carry no density information
    half = 0.5 if MODE == 'half' else 0.0  # the last page of a section is on average half full
    cpp = sum(ch[i] for i in seeds) / max(1, sum(exact[i] - half for i in seeds))
    est = 0
    for i in range(len(tl)):
        if i in seeds: est += exact[i]
        elif ch[i] < 200: est += 1
        else: est += max(1, math.ceil(ch[i] / cpp)) + im[i]
    return est, cpp


def main():
    global MODE
    if sys.argv[1].startswith('--mode='):
        MODE = sys.argv.pop(1).split('=')[1]
    tl = json.load(open(sys.argv[1]))
    n = len(tl)
    for f in sys.argv[2:]:
        exact = json.loads(open(f).read().split(' ', 1)[1])
        total = sum(exact)
        rows = []
        for name, seeds in [('front 0..2', [0, 1, 2]), ('front 5..7', [5, 6, 7]), ('middle', [n // 2 - 1, n // 2, n // 2 + 1]),
                            ('late', [n - 40, n - 39, n - 38])]:
            e, cpp = estimate(tl, exact, seeds)
            rows.append(f"{name}: est={e} err={100 * (e - total) / total:+.1f}% cpp={cpp:.0f}")
        # worst case over every possible current section
        errs = []
        for c in range(1, n - 1):
            e, _ = estimate(tl, exact, [c - 1, c, c + 1])
            errs.append(100 * (e - total) / total)
        within = sum(1 for x in errs if abs(x) <= 5)
        print(f"{f.split('/')[-1]} exact={total} | " + ' | '.join(rows) +
              f" | all seeds: min={min(errs):+.1f}% max={max(errs):+.1f}% within5%={within}/{len(errs)}")


main()


def scaled(prev, new, seeds):
    """Estimate after a layout change from the previous layout's exact per-section counts:
    total_prev * (pages of seed sections now / pages of the same sections before)."""
    r = sum(new[i] for i in seeds) / max(1, sum(prev[i] for i in seeds))
    return sum(new[i] if i in seeds else prev[i] * r for i in range(len(prev)))


if __name__ == '__main__' and False:
    pass
