#!/usr/bin/env python3
"""Report of one tools/run_suite.sh run: per-test and per-class times, the slowest 10, failures, what never finished.

Usage: tools/suite_report.py <run.txt: `am instrument -r` output> <logcat.txt: `logcat -v epoch`, tag TestRunner included>
Writes <dir of run.txt>/times.tsv and prints the report. `am instrument -r` has no timestamps, so times come from the
TestRunner started/finished lines. Exit code: 0 all passed, 1 failures, 3 no result line (the run was cut off).
"""
import os
import re
import sys

CODES = {0: "ok", -1: "ERROR", -2: "FAILED", -3: "ignored", -4: "assumption"}
LOG = re.compile(r"^\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+TestRunner\s*:\s*(started|finished): (.+)\((.+)\)\s*$")


def parse_run(path):
    """-> ({(class, test): status}, {(class, test): first stack lines}, numtests, final result lines)"""
    status, stacks, numtests, final = {}, {}, None, []
    cur, in_stack, stack = {}, False, []
    for line in open(path, errors="replace"):
        line = line.rstrip("\n")
        m = re.match(r"INSTRUMENTATION_STATUS: (\w+)=(.*)", line)
        c = re.match(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", line)
        if m:
            in_stack = m.group(1) == "stack"
            if in_stack:
                stack = [m.group(2)]
            cur[m.group(1)] = m.group(2)
            if m.group(1) == "numtests":
                numtests = int(m.group(2))
        elif c:
            in_stack = False
            key = (cur.get("class", "?"), cur.get("test", "?"))
            code = int(c.group(1))
            if code != 1:
                status[key] = CODES.get(code, str(code))
                if code in (-1, -2):
                    stacks[key] = stack[:8]
            cur, stack = {}, []
        elif in_stack:
            stack.append(line)
        elif re.match(r"(INSTRUMENTATION_(RESULT|CODE|ABORTED|FAILED)|OK \(|Tests run:|FAILURES!!!|Time: )", line):
            final.append(line)
    return status, stacks, numtests, final


def parse_log(path):
    """-> ({(class, test): seconds}, name of a test started but not finished, or None, wall seconds or None)"""
    times, open_, first, last = {}, {}, None, None
    for line in open(path, errors="replace"):
        m = LOG.match(line)
        if not m:
            continue
        t, what, test, cls = float(m.group(1)), m.group(2), m.group(3), m.group(4)
        first = t if first is None else first
        last = t
        if what == "started":
            open_[(cls, test)] = t
        elif (cls, test) in open_:
            times[(cls, test)] = t - open_.pop((cls, test))
    return times, next(iter(open_), None), (last - first if first is not None else None)


def short(cls):
    return cls.rsplit(".", 1)[-1]


def mmss(s):
    return f"{int(s // 60)}:{int(s % 60):02d}"


def main():
    run, log = sys.argv[1], sys.argv[2]
    status, stacks, numtests, final = parse_run(run)
    times, unfinished, wall = parse_log(log)

    with open(os.path.join(os.path.dirname(os.path.abspath(run)), "times.tsv"), "w") as f:
        f.write("seconds\tstatus\tclass\ttest\n")
        for key, s in sorted(times.items(), key=lambda kv: -kv[1]):
            f.write(f"{s:.1f}\t{status.get(key, '?')}\t{key[0]}\t{key[1]}\n")

    classes = {}
    for (cls, _), s in times.items():
        n, total = classes.get(cls, (0, 0.0))
        classes[cls] = (n + 1, total + s)
    print("\nPer class (seconds, tests):")
    for cls, (n, total) in sorted(classes.items(), key=lambda kv: -kv[1][1]):
        print(f"  {total:8.1f}  {n:3d}  {short(cls)}")
    print("\nSlowest 10 tests:")
    for (cls, test), s in sorted(times.items(), key=lambda kv: -kv[1])[:10]:
        print(f"  {s:8.1f}  {short(cls)}#{test}")

    bad = {k: v for k, v in status.items() if v in ("FAILED", "ERROR")}
    for (cls, test), v in sorted(bad.items()):
        print(f"\n{v}: {short(cls)}#{test}")
        for line in stacks.get((cls, test), []):
            print("    " + line)

    done = len(status)
    wall_s = f", {mmss(wall)} from first start to last finish" if wall is not None else ""
    print(f"\nTests: {done} finished of {numtests if numtests is not None else '?'}, {len(bad)} failed{wall_s}")
    has_result = any(re.match(r"(OK \(|Tests run:)", line) for line in final)
    for line in final:
        if not line.startswith("INSTRUMENTATION_RESULT: stream="):
            print("  " + line)
    if not has_result:
        print("NO RESULT LINE: the run ended without `OK (...)` or `Tests run: ...`.")
        if unfinished:
            print(f"  running when it stopped: {short(unfinished[0])}#{unfinished[1]}")
        for line in open(log, errors="replace"):
            if "FATAL EXCEPTION" in line or "has died" in line and "fernreader" in line:
                print("  " + line.strip())
        return 3
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
