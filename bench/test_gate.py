# bench/test_gate.py  — run: python3 -I bench/test_gate.py  (from repo root)
import json, os, sys, tempfile, unittest
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gate  # noqa: E402

TARGETS = {"P-001": {"must": 1200, "unit": "ms"}, "P-016": {"must": 1.0, "unit": "score"}}

class GateTest(unittest.TestCase):
    def rows(self, *vals, id="P-001"):
        return [{"id": id, "value": v, "run": i + 1} for i, v in enumerate(vals)]

    def test_median_of_odd_and_even(self):
        self.assertEqual(gate.median([3, 1, 2]), 2)
        self.assertEqual(gate.median([4, 1, 3, 2]), 2.5)

    def test_pass_when_median_within_must(self):
        self.assertEqual(gate.evaluate(self.rows(1300, 900, 1000), TARGETS)["P-001"]["status"], "PASS")

    def test_fail_when_median_above_must(self):
        self.assertEqual(gate.evaluate(self.rows(1300, 1250, 900), TARGETS)["P-001"]["status"], "FAIL")

    def test_fail_when_fewer_than_three_runs(self):
        res = gate.evaluate(self.rows(100, 100), TARGETS)
        self.assertEqual(res["P-001"]["status"], "FAIL")
        self.assertIn("runs", res["P-001"]["reason"])

    def test_unknown_id_is_reported_not_ignored(self):
        self.assertEqual(gate.evaluate(self.rows(1, 1, 1, id="P-999"), TARGETS)["P-999"]["status"], "FAIL")

    def test_main_exit_code(self):
        with tempfile.TemporaryDirectory() as d:
            r, t = os.path.join(d, "r.jsonl"), os.path.join(d, "t.json")
            def write(rows):
                with open(r, "w") as f:
                    f.write("\n".join(json.dumps(x) for x in rows))
            with open(t, "w") as f:
                json.dump(TARGETS, f)
            write(self.rows(900, 900, 900))
            self.assertEqual(gate.main([r, t]), 0)
            write(self.rows(1900, 1900, 1900))
            self.assertEqual(gate.main([r, t]), 1)

    def test_info_target_is_recorded_not_gated(self):
        info = {"P-020.cmd_painted": {"info": True, "unit": "ms"}}
        res = gate.evaluate(self.rows(900, 950, 1000, id="P-020.cmd_painted"), info)
        self.assertEqual(res["P-020.cmd_painted"]["status"], "INFO")
        self.assertEqual(res["P-020.cmd_painted"]["median"], 950)
        with tempfile.TemporaryDirectory() as d:
            r, t = os.path.join(d, "r.jsonl"), os.path.join(d, "t.json")
            with open(r, "w") as f: f.write("\n".join(json.dumps(x) for x in self.rows(900, 950, 1000, id="P-020.cmd_painted")))
            with open(t, "w") as f: json.dump(info, f)
            self.assertEqual(gate.main([r, t]), 0)

    def test_info_target_says_when_a_run_was_not_measured(self):
        res = gate.evaluate(self.rows(900, 999999, 1000, id="X"), {"X": {"info": True}})
        self.assertEqual(res["X"]["status"], "INFO")
        self.assertIn("raw NA", res["X"]["reason"])

    def test_info_and_must_together_fail(self):
        self.assertEqual(gate.evaluate(self.rows(1, 1, 1, id="X"), {"X": {"info": True, "must": 5}})["X"]["status"], "FAIL")

    def test_empty_results_fail(self):
        with tempfile.TemporaryDirectory() as d:
            r, t = os.path.join(d, "r.jsonl"), os.path.join(d, "t.json")
            with open(r, "w") as f:
                f.write("")
            with open(t, "w") as f:
                json.dump(TARGETS, f)
            self.assertEqual(gate.main([r, t]), 1)

if __name__ == "__main__":
    unittest.main()
