# tools/power/test_energy.py — run: python3 -I tools/power/test_energy.py  (from the repo root)
import os, sys, tempfile, unittest
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import energy  # noqa: E402


class EnergyTest(unittest.TestCase):
    RAIL = [(0, 0.0), (1_000_000_000, 500_000.0), (2_000_000_000, 1_500_000.0), (3_000_000_000, 1_600_000.0)]

    def test_energy_in_window_from_cumulative_uws(self):
        self.assertEqual(energy.window_energy_mj(self.RAIL, 1_000_000_000, 2_000_000_000), 1000.0)
        self.assertEqual(energy.window_energy_mj(self.RAIL, 1_500_000_000, 3_500_000_000), 1100.0)

    def test_before_first_sample_uses_the_first(self):
        self.assertEqual(energy.value_at(self.RAIL, -5), 0.0)

    def test_no_samples(self):
        self.assertIsNone(energy.window_energy_mj([], 0, 1))

    def test_windows_and_names(self):
        with tempfile.NamedTemporaryFile("w", delete=False) as f:
            f.write("a 100.5 160.5\n")
        self.addCleanup(os.unlink, f.name)
        self.assertEqual(energy.read_windows(f.name), [("a", 100_500_000_000, 160_500_000_000)])
        self.assertEqual(energy.rail_name("power.rails.cpu.big"), "cpu.big")
        self.assertEqual(energy.rail_name("power.S4M_VDD_CPUCL0_uws"), "S4M_VDD_CPUCL0")


if __name__ == "__main__":
    unittest.main()
