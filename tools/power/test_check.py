# tools/power/test_check.py — run: python3 -I tools/power/test_check.py  (from the repo root)
import os, sys, tempfile, unittest
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check  # noqa: E402


def snap(pid=100, app=0, rend=0, threads=(), io=None):
    lines = [f"pid {pid}", f"app_ticks {app}", f"rend_ticks {rend}"]
    lines += [f"t {tid} {comm} {vol} {nonvol} {ticks}" for tid, comm, vol, nonvol, ticks in threads]
    if io is not None:
        lines.append(f"io_write_bytes {io}")
    return "\n".join(lines) + "\n"


class ParseAndThreads(unittest.TestCase):
    def test_parse_snapshot(self):
        s = check.parse_snap(snap(pid=7, app=12, rend=3, threads=[(7, "app.fernreader", 5, 1, 2)], io=4096))
        self.assertEqual((s["pid"], s["app_ticks"], s["rend_ticks"], s["io_write_bytes"]), (7, 12, 3, 4096))
        self.assertEqual(s["threads"][7], ("app.fernreader", 5, 1, 2))

    def test_platform_threads_are_not_app_threads(self):
        for comm in ["RenderThread", "Chrome_IOThread", "Chrome_InProcGp", "ThreadPoolForeg", "binder:4242_3",
                     "HwBinder:4242_1", "HeapTaskDaemon", "Signal_Catcher", "Profile_Saver", "Jit_thread_pool",
                     "hwuiTask1", "FinalizerDaemon", "ReferenceQueueD", "FinalizerWatchd", "perfetto_hprof_",
                     "ADB-JDWP_Connec", "CleanupReferenc", "GpuMemoryThread", "VizCompositorTh",
                     "AudioThread", "CookieMonsterCl", "CookieMonsterBa", "queued-work-loo", "RenderThread"]:
            self.assertFalse(check.is_app_thread(comm), comm)

    def test_kotlin_and_app_threads_are_app_threads(self):
        for comm in ["DefaultDispatch", "DefaultExecutor", "file-watch", "search-index", "arch_disk_io_0",
                     "WM.task-1", "pool-3-thread-1", "FileObserver", "bg-pages"]:
            self.assertTrue(check.is_app_thread(comm), comm)

    def test_wakeups_split_main_and_app_threads_and_count_new_threads(self):
        before = check.parse_snap(snap(pid=100, threads=[
            (100, "app.fernreader", 10, 0, 0), (101, "DefaultDispatch", 5, 0, 0), (102, "Chrome_IOThread", 1, 0, 0)]))
        after = check.parse_snap(snap(pid=100, threads=[
            (100, "app.fernreader", 40, 2, 0), (101, "DefaultDispatch", 9, 0, 0), (102, "Chrome_IOThread", 99, 0, 0),
            (103, "DefaultExecutor", 3, 0, 0)]))
        self.assertEqual(check.wakeups(before, after), (30, 4 + 3))


class Metrics(unittest.TestCase):
    def write_run(self, d, files):
        for name, text in files.items():
            with open(os.path.join(d, name), "w") as f:
                f.write(text)

    def test_metrics_from_a_run_directory(self):
        with tempfile.TemporaryDirectory() as d:
            T = [(100, "app.fernreader", 0, 0, 0), (101, "DefaultDispatch", 0, 0, 0)]
            T2 = [(100, "app.fernreader", 60, 0, 0), (101, "DefaultDispatch", 4, 0, 0)]
            self.write_run(d, {
                "a.before": snap(app=100, rend=50, threads=T), "a.after": snap(app=102, rend=50, threads=T2) + "frames 0\n",
                "b.before": snap(app=0, rend=0), "b.after": snap(app=600, rend=200) + "frames 240\nframe_p50_ms 12\n",
                "bio.before": snap(io=0), "bio.after": snap(io=20 * 12 * 1024),
                "c1.before": snap(app=0), "c1.after": snap(app=110) + "frames 27\n",
                "c2.before": snap(app=0, rend=0, threads=T), "c2.after": snap(app=2, rend=1, threads=T2) + "frames 0\n",
                "d.before": snap(app=0, rend=0, threads=T), "d.after": snap(app=15, rend=9, threads=T) + "frames 0\n",
                "env": "gpu_mode host\nprobe_p50_ms 14\nhost_idle_pct 91\n",
            })
            m = check.run_metrics(d)
        self.assertAlmostEqual(m["a.app_cpu_s"], 0.02)
        self.assertEqual(m["a.renderer_cpu_s"], 0.0)
        self.assertEqual(m["a.frames"], 0)
        self.assertEqual(m["a.app_thread_wakeups"], 4)
        self.assertEqual(m["a.main_thread_wakeups"], 60)
        self.assertAlmostEqual(m["b.app_cpu_s_per_turn"], 0.30)
        self.assertAlmostEqual(m["b.renderer_cpu_s_per_turn"], 0.10)
        self.assertEqual(m["b.frames_per_turn"], 12)
        self.assertAlmostEqual(m["b.disk_write_kb_per_turn"], 12.0)
        self.assertAlmostEqual(m["c1.app_cpu_s"], 1.10)
        self.assertAlmostEqual(m["c2.app_cpu_s"], 0.02)
        self.assertAlmostEqual(m["d.cpu_s"], 0.24)
        self.assertAlmostEqual(m["d.app_cpu_s"], 0.15)
        self.assertEqual(m["d.app_thread_wakeups"], 0)

    def test_missing_scenario_gives_no_metric(self):
        with tempfile.TemporaryDirectory() as d:
            self.write_run(d, {"a.before": snap(), "a.after": snap(app=1) + "frames 0\n"})
            m = check.run_metrics(d)
        self.assertIn("a.app_cpu_s", m)
        self.assertNotIn("d.cpu_s", m)


class Environment(unittest.TestCase):
    def test_valid(self):
        self.assertEqual(check.env_problems({"gpu_mode": "host", "probe_p50_ms": "14", "host_idle_pct": "88"}), [])

    def test_slow_webview_redraw_is_invalid(self):
        p = check.env_problems({"gpu_mode": "host", "probe_p50_ms": "150", "host_idle_pct": "90"})
        self.assertTrue(any("slow WebView" in x for x in p), p)

    def test_slow_frames_during_turns_are_invalid(self):
        p = check.env_problems({"gpu_mode": "host", "probe_p50_ms": "14", "b_p50_ms": "130", "host_idle_pct": "90"})
        self.assertTrue(any("slow WebView" in x for x in p), p)

    def test_software_gpu_busy_host_restart_and_missing_probe(self):
        p = check.env_problems({"gpu_mode": "lavapipe", "host_idle_pct": "40", "pid_changed": "1"})
        self.assertEqual(len(p), 4, p)

    def test_run_idle_threshold_can_be_lowered_and_is_recorded(self):
        env = {"gpu_mode": "host", "probe_p50_ms": "14", "host_idle_pct": "62"}
        self.assertEqual(len(check.env_problems(env)), 1)
        self.assertEqual(check.env_problems(dict(env, min_idle_pct="60")), [])

    def test_aborted_run_reason_is_reported(self):
        p = check.env_problems({"gpu_mode": "host", "probe_p50_ms": "10", "host_idle_pct": "90", "abort": "no_painted"})
        self.assertTrue(any("no_painted" in x for x in p), p)


LIMITS = """\
# metric\tlimit\tmult\tadd\tunit\twhat
a.app_cpu_s\t0.10\t2\t0.05\ts\tstill page
a.frames\t0\t1\t0\tframes\tstill page
d.app_cpu_s\t-\t-\t-\ts\tinfo only
"""


class Evaluate(unittest.TestCase):
    def limits(self):
        f = tempfile.NamedTemporaryFile("w", suffix=".tsv", delete=False)
        f.write(LIMITS); f.close()
        self.addCleanup(os.unlink, f.name)
        return f.name

    def test_pass_fail_info_missing(self):
        rows = check.evaluate([{"a.app_cpu_s": 0.11, "a.frames": 0}], check.load_limits(self.limits()))
        st = {r["metric"]: r["status"] for r in rows}
        self.assertEqual(st, {"a.app_cpu_s": "FAIL", "a.frames": "PASS", "d.app_cpu_s": "MISSING"})

    def test_limit_is_inclusive(self):
        rows = check.evaluate([{"a.app_cpu_s": 0.10, "a.frames": 0, "d.app_cpu_s": 0.2}], check.load_limits(self.limits()))
        st = {r["metric"]: r["status"] for r in rows}
        self.assertEqual(st, {"a.app_cpu_s": "PASS", "a.frames": "PASS", "d.app_cpu_s": "INFO"})

    def test_several_runs_gate_on_the_median(self):
        rows = check.evaluate([{"a.app_cpu_s": v, "a.frames": 0} for v in (0.30, 0.02, 0.03)], check.load_limits(self.limits()))
        r = [r for r in rows if r["metric"] == "a.app_cpu_s"][0]
        self.assertEqual((r["value"], r["status"], r["min"], r["max"]), (0.03, "PASS", 0.02, 0.30))

    def test_missing_gated_metric_fails_the_check(self):
        self.assertEqual(check.exit_code([{"status": "MISSING", "limit": 1.0}], env_ok=True), 1)
        self.assertEqual(check.exit_code([{"status": "PASS", "limit": 1.0}, {"status": "INFO", "limit": None}], env_ok=True), 0)
        self.assertEqual(check.exit_code([{"status": "FAIL", "limit": 1.0}], env_ok=False), 2)

    def test_new_limits_tighten_only_unless_loosen(self):
        lim = check.load_limits(self.limits())
        runs = [{"a.app_cpu_s": 0.01, "a.frames": 2}, {"a.app_cpu_s": 0.02, "a.frames": 0}]
        new = {r["metric"]: r["limit"] for r in check.new_limits(lim, runs, loosen=False)}
        self.assertAlmostEqual(new["a.app_cpu_s"], 0.09)   # max 0.02 * 2 + 0.05
        self.assertEqual(new["a.frames"], 0)                # would be 2: kept, not loosened
        self.assertIsNone(new["d.app_cpu_s"])               # info rows stay info
        new = {r["metric"]: r["limit"] for r in check.new_limits(lim, runs, loosen=True)}
        self.assertEqual(new["a.frames"], 2)

    def test_write_limits_round_trips(self):
        path = self.limits()
        lim = check.load_limits(path)
        lim[0]["limit"] = 0.07
        check.save_limits(path, lim)
        again = check.load_limits(path)
        self.assertEqual([r["limit"] for r in again], [0.07, 0, None])
        with open(path) as f:
            self.assertTrue(f.readline().startswith("#"))


if __name__ == "__main__":
    unittest.main()
