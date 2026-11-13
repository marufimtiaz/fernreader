#!/usr/bin/env python3
"""Power check (C-114): metrics from tools/power/check.sh's raw snapshots, environment validity, limits.

  check.py check  <limits.tsv> <rundir>...            table + exit 0 pass, 1 fail, 2 environment invalid
  check.py write-limits <limits.tsv> <rundir>... [--loosen]   new limit = max over runs * mult + add (rounded up);
                                                       only lowers a limit unless --loosen
  check.py noise  <rundir>...                          min / median / max per metric over runs

A run directory holds <scenario>.before / <scenario>.after snapshots ("pid N", "app_ticks N", "rend_ticks N",
"t <tid> <comm> <voluntary> <nonvoluntary> <ticks>", "io_write_bytes N"; the .after adds "frames N" and, for b,
"frame_p50_ms N") and an "env" file ("key value" lines). Ticks are 10 ms (CLK_TCK 100).
"""
import math, os, re, statistics, sys

TICK_S = 0.01
TURNS = 20                  # scenario b
SLOW_FRAME_MS = 80          # median frame of a turn above this: the emulator's slow-WebView state (drawGl 115-270 ms)
MIN_HOST_IDLE = 70          # mean host CPU idle % during the run

# Threads that belong to the runtime, the framework or WebView's in-process Chromium, not to app code.
# Everything else in the app process (kotlinx workers, Room, WorkManager, the app's own named threads) is app code.
# The main thread (tid == pid) runs both app code and WebView/Choreographer work; it is counted on its own.
PLATFORM_THREAD = re.compile(
    r"^(RenderThread|hwuiTask\d*|Chrome_.*|CrGpuMain|CrRendererMain|ThreadPool.*|binder:.*|HwBinder:.*|"
    r"HeapTaskDaemon|FinalizerDaemon|FinalizerWatchd|ReferenceQueueD|Signal_Catcher|Profile_Saver|"
    r"Jit_thread_pool|perfetto_hprof_|ADB-JDWP_Connec|CleanupReferenc|GpuMemoryThread|VizCompositorTh|"
    r"AudioThread|CookieMonster.*|queued-work-loo|RenderThread|GLThread.*|Binder:.*|android\.bg|"
    r"process_reaper|NetworkService|VizWebView|MemoryInfra|TracingMuxer|PerfettoTrace|ProtoLogConfigL|"
    r"SurfaceSyncGrou|VideoCaptureThr|GoogleApiHandle|AudioPortEventH|CameraAvailabil|allocateBuffers)$")


def is_app_thread(comm):
    return not PLATFORM_THREAD.match(comm)


def parse_snap(text):
    s = {"threads": {}}
    for line in text.splitlines():
        p = line.split()
        if not p:
            continue
        if p[0] == "t" and len(p) >= 6:
            s["threads"][int(p[1])] = (p[2], int(p[3]), int(p[4]), int(p[5]))
        elif len(p) == 2:
            try:
                s[p[0]] = int(p[1])
            except ValueError:
                try:
                    s[p[0]] = float(p[1])
                except ValueError:
                    s[p[0]] = p[1]
    return s


def wakeups(before, after):
    """(main thread, app threads): voluntary context switches during the window. A thread born in the window counts
    from 0; one that ended in it is lost (rare: kotlinx idle workers)."""
    pid = after.get("pid")
    main = app = 0
    for tid, (comm, vol, _nv, _t) in after["threads"].items():
        d = vol - before["threads"].get(tid, (comm, 0, 0, 0))[1]
        if tid == pid:
            main += d
        elif is_app_thread(comm):
            app += d
    return main, app


def woke_app_threads(before, after):
    """{comm: wakeups} of app threads that woke in the window, for the report."""
    out = {}
    for tid, (comm, vol, _nv, _t) in after["threads"].items():
        d = vol - before["threads"].get(tid, (comm, 0, 0, 0))[1]
        if tid != after.get("pid") and is_app_thread(comm) and d > 0:
            out[comm] = out.get(comm, 0) + d
    return out


def woke_report(dirs):
    lines = []
    for d in dirs:
        for sc in ("a", "c2", "d"):
            p = _pair(d, sc)
            if p:
                w = woke_app_threads(*p)
                lines.append(f"  {os.path.basename(d)} {sc}: " + (", ".join(f"{k} {v}" for k, v in sorted(w.items(), key=lambda x: -x[1])) or "none"))
    return "app threads that woke (wakeups):\n" + "\n".join(lines)


def _read(path):
    with open(path) as f:
        return f.read()


def _pair(d, sc):
    b, a = os.path.join(d, sc + ".before"), os.path.join(d, sc + ".after")
    if not (os.path.exists(b) and os.path.exists(a)):
        return None
    return parse_snap(_read(b)), parse_snap(_read(a))


def _cpu(b, a, key):
    return round((a.get(key, 0) - b.get(key, 0)) * TICK_S, 2)


def run_metrics(d):
    m = {}
    p = _pair(d, "a")
    if p:
        b, a = p
        m["a.app_cpu_s"] = _cpu(b, a, "app_ticks")
        m["a.renderer_cpu_s"] = _cpu(b, a, "rend_ticks")
        m["a.frames"] = a.get("frames", 0)
        m["a.main_thread_wakeups"], m["a.app_thread_wakeups"] = wakeups(b, a)
    p = _pair(d, "b")
    if p:
        b, a = p
        m["b.app_cpu_s_per_turn"] = round(_cpu(b, a, "app_ticks") / TURNS, 3)
        m["b.renderer_cpu_s_per_turn"] = round(_cpu(b, a, "rend_ticks") / TURNS, 3)
        m["b.frames_per_turn"] = round(a.get("frames", 0) / TURNS, 1)
    p = _pair(d, "bio")
    if p:
        b, a = p
        m["b.disk_write_kb_per_turn"] = round((a.get("io_write_bytes", 0) - b.get("io_write_bytes", 0)) / 1024 / TURNS, 1)
    p = _pair(d, "c1")
    if p:
        b, a = p
        m["c1.app_cpu_s"] = _cpu(b, a, "app_ticks")
        m["c1.frames"] = a.get("frames", 0)
    p = _pair(d, "c2")
    if p:
        b, a = p
        m["c2.app_cpu_s"] = _cpu(b, a, "app_ticks")
        m["c2.renderer_cpu_s"] = _cpu(b, a, "rend_ticks")
        m["c2.frames"] = a.get("frames", 0)
        m["c2.main_thread_wakeups"], m["c2.app_thread_wakeups"] = wakeups(b, a)
    p = _pair(d, "d")
    if p:
        b, a = p
        m["d.app_cpu_s"] = _cpu(b, a, "app_ticks")
        m["d.renderer_cpu_s"] = _cpu(b, a, "rend_ticks")
        m["d.cpu_s"] = round(m["d.app_cpu_s"] + m["d.renderer_cpu_s"], 2)
        m["d.main_thread_wakeups"], m["d.app_thread_wakeups"] = wakeups(b, a)
    return m


def read_env(d):
    path = os.path.join(d, "env")
    env = {}
    if os.path.exists(path):
        for line in _read(path).splitlines():
            k, _, v = line.partition(" ")
            if k:
                env[k] = v.strip()
    return env


def env_problems(env):
    probs = []
    if env.get("abort"):
        probs.append(f"run stopped early: {env['abort']}")
    if env.get("gpu_mode", "?") != "host":
        probs.append(f"emulator GPU mode is '{env.get('gpu_mode', '?')}', not host (bench/emulator.sh)")
    if "probe_p50_ms" not in env:
        probs.append("no redraw probe result")
    for key, what in (("probe_p50_ms", "redraw probe"), ("b_p50_ms", "turns in b")):
        if key in env and float(env[key]) > SLOW_FRAME_MS:
            probs.append(f"slow WebView state: median frame {env[key]} ms in the {what} (> {SLOW_FRAME_MS} ms)")
    min_idle = float(env.get("min_idle_pct", MIN_HOST_IDLE))
    if float(env.get("host_idle_pct", 0)) < min_idle:
        probs.append(f"host busy: mean CPU idle {env.get('host_idle_pct', '?')} % (< {min_idle:g} %)")
    if env.get("pid_changed", "0") != "0":
        probs.append("the app process restarted during the scenarios (crash or kill: look at logcat)")
    return probs


def _num(v):
    if v == "-":
        return None
    f = float(v)
    return int(f) if f.is_integer() and "." not in v else f


def load_limits(path):
    rows = []
    for line in _read(path).splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        p = line.split("\t")
        rows.append({"metric": p[0], "limit": _num(p[1]), "mult": _num(p[2]), "add": _num(p[3]),
                     "unit": p[4], "what": p[5] if len(p) > 5 else ""})
    return rows


def _fmt(v):
    if v is None:
        return "-"
    if isinstance(v, float):
        return f"{v:g}"
    return str(v)


def save_limits(path, rows):
    head = [l for l in _read(path).splitlines() if l.startswith("#")]
    with open(path, "w") as f:
        for l in head:
            f.write(l + "\n")
        for r in rows:
            f.write("\t".join([r["metric"], _fmt(r["limit"]), _fmt(r["mult"]), _fmt(r["add"]), r["unit"], r["what"]]) + "\n")


def evaluate(runs, limits):
    out = []
    for lim in limits:
        vals = [r[lim["metric"]] for r in runs if lim["metric"] in r]
        row = dict(lim, value=None, min=None, max=None)
        if not vals:
            row["status"] = "MISSING"
        else:
            row["value"] = round(statistics.median(vals), 3)
            row["min"], row["max"] = min(vals), max(vals)
            if lim["limit"] is None:
                row["status"] = "INFO"
            else:
                row["status"] = "PASS" if row["value"] <= lim["limit"] + 1e-9 else "FAIL"
        out.append(row)
    return out


def exit_code(rows, env_ok):
    if not env_ok:
        return 2
    return 1 if any(r["status"] in ("FAIL",) or (r["status"] == "MISSING" and r["limit"] is not None) for r in rows) else 0


def _round_up(v, unit):
    step = 0.01 if unit == "s" else (0.1 if unit in ("kb", "frames/turn") else 1)
    return round(math.ceil(v / step - 1e-9) * step, 2) if step < 1 else int(math.ceil(v - 1e-9))


def new_limits(limits, runs, loosen=False):
    out = []
    for lim in limits:
        r = dict(lim)
        vals = [x[lim["metric"]] for x in runs if lim["metric"] in x]
        if lim["limit"] is not None and vals:
            cand = _round_up(max(vals) * lim["mult"] + lim["add"], lim["unit"])
            r["limit"] = cand if (loosen or cand < lim["limit"]) else lim["limit"]
        out.append(r)
    return out


def table(rows):
    w = max(len(r["metric"]) for r in rows)
    lines = [f"{'metric':<{w}}  {'value':>8}  {'limit':>8}  {'unit':<11} {'result':<7} what"]
    for r in rows:
        rng = f"  (runs {_fmt(r['min'])}..{_fmt(r['max'])})" if r["min"] is not None and r["min"] != r["max"] else ""
        lines.append(f"{r['metric']:<{w}}  {_fmt(r['value']):>8}  {_fmt(r['limit']):>8}  {r['unit']:<11} "
                     f"{r['status']:<7} {r['what']}{rng}")
    return "\n".join(lines)


def main(argv):
    if len(argv) == 2 and argv[0] == "const":
        print(globals()[argv[1]])
        return 0
    if len(argv) < 2 or argv[0] not in ("check", "write-limits", "noise"):
        print(__doc__, file=sys.stderr)
        return 3
    cmd = argv[0]
    if cmd == "noise":
        runs = [run_metrics(d) for d in argv[1:]]
        keys = sorted({k for r in runs for k in r})
        for k in keys:
            v = [r[k] for r in runs if k in r]
            print(f"{k:<28} n={len(v)} min={_fmt(min(v))} median={_fmt(round(statistics.median(v), 3))} max={_fmt(max(v))}")
        return 0
    loosen = "--loosen" in argv
    args = [a for a in argv[1:] if a != "--loosen"]
    limits_path, dirs = args[0], args[1:]
    valid, invalid = [], []
    for d in dirs:
        p = env_problems(read_env(d))
        (invalid if p else valid).append((d, p))
    for d, p in invalid:
        print(f"ENVIRONMENT INVALID in {d}:\n  " + "\n  ".join(p))
    if invalid and cmd == "check":
        # Any invalid run voids the check: its numbers say nothing about the app.
        rows = evaluate([run_metrics(d) for d, _ in valid + invalid], load_limits(limits_path))
        print(table(rows))
        print("RESULT: ENVIRONMENT INVALID (not a pass or a fail; fix the environment and run again)")
        return 2
    if not valid:
        print("RESULT: no valid run")
        return 2
    runs = [run_metrics(d) for d, _ in valid]
    limits = load_limits(limits_path)
    if cmd == "write-limits":
        new = new_limits(limits, runs, loosen)
        for o, n in zip(limits, new):
            if o["limit"] != n["limit"]:
                print(f"{o['metric']}: {_fmt(o['limit'])} -> {_fmt(n['limit'])}")
        save_limits(limits_path, new)
        print(f"wrote {limits_path} from {len(runs)} valid run(s)")
        return 0
    rows = evaluate(runs, limits)
    print(table(rows))
    print(woke_report([d for d, _ in valid]))
    code = exit_code(rows, True)
    print(f"RESULT: {'PASS' if code == 0 else 'FAIL'} ({len(runs)} run(s){', median' if len(runs) > 1 else ''})")
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
