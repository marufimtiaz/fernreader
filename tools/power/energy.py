#!/usr/bin/env python3
"""Energy per power rail and scenario from a Perfetto trace (tools/power/energy.sh). Report only.

  energy.py <trace.pftrace> <windows.txt>      windows: "<scenario> <start s> <end s>" in CLOCK_BOOTTIME seconds

Needs the `perfetto` Python package (pip install perfetto); it fetches trace_processor on first use.
Power rails are cumulative energy counters (uWs); the energy in a window is the counter at its end minus at its start.
"""
import sys


def value_at(samples, t):
    """Counter value at time t (ns): the last sample at or before t, else the first one."""
    best = samples[0][1]
    for ts, v in samples:
        if ts > t:
            break
        best = v
    return best


def window_energy_mj(samples, t0, t1):
    """mJ consumed by a cumulative uWs rail between t0 and t1 (ns)."""
    if not samples:
        return None
    return (value_at(samples, t1) - value_at(samples, t0)) / 1000.0


def mean_in_window(samples, t0, t1):
    vals = [v for ts, v in samples if t0 <= ts <= t1]
    return sum(vals) / len(vals) if vals else None


def read_windows(path):
    out = []
    with open(path) as f:
        for line in f:
            p = line.split()
            if len(p) == 3:
                out.append((p[0], int(float(p[1]) * 1e9), int(float(p[2]) * 1e9)))
    return out


def rail_name(track):
    for pre in ("power.rails.", "power."):
        if track.startswith(pre):
            return track[len(pre):].removesuffix("_uws")
    return track


def main(argv):
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 3
    try:
        from perfetto.trace_processor import TraceProcessor
    except ImportError:
        print("The `perfetto` Python package is missing: see docs/power-audit.md \"Energy run on a phone\".\n"
              f"The trace and windows are kept; open the trace at https://ui.perfetto.dev and read the power rails "
              f"over the windows in {argv[1]}.")
        return 0
    tp = TraceProcessor(trace=argv[0])
    q = tp.query("select t.name as name, c.ts as ts, c.value as value from counter c "
                 "join counter_track t on c.track_id = t.id "
                 "where t.name glob 'power.*' or t.name glob 'batt.*' order by c.ts")
    series = {}
    for r in q:
        series.setdefault(r.name, []).append((r.ts, r.value))
    tp.close()
    windows = read_windows(argv[1])
    rails = sorted(n for n in series if n.startswith("power."))
    if not rails:
        print("No power rails in the trace: this phone does not report them (only battery counters below).")
    names = [w[0] for w in windows]
    print(f"{'energy, mJ':<34}" + "".join(f"{n:>11}" for n in names))
    print(f"{'(duration, s)':<34}" + "".join(f"{(b - a) / 1e9:>11.0f}" for _, a, b in windows))
    totals = [0.0] * len(windows)
    for rail in rails:
        row = [window_energy_mj(series[rail], a, b) for _, a, b in windows]
        totals = [t + (x or 0) for t, x in zip(totals, row)]
        print(f"{rail_name(rail):<34}" + "".join(f"{x:>11.1f}" for x in row))
    if rails:
        print(f"{'all rails':<34}" + "".join(f"{x:>11.1f}" for x in totals))
        print(f"{'all rails, mean mW':<34}" + "".join(f"{x / ((b - a) / 1e9):>11.1f}" for x, (_, a, b) in zip(totals, windows)))
    cur = series.get("batt.current_ua")
    if cur:
        print(f"{'battery current, mean mA':<34}" + "".join(
            f"{(mean_in_window(cur, a, b) or 0) / 1000:>11.1f}" for _, a, b in windows))
    chg = series.get("batt.charge_uah")
    if chg:
        print(f"{'battery charge used, uAh':<34}" + "".join(
            f"{value_at(chg, a) - value_at(chg, b):>11.0f}" for _, a, b in windows))
    print("Scenarios: a still page 60 s, b 20 turns, c1 library after Back 60 s, c2 library idle 60 s, d background 180 s.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
