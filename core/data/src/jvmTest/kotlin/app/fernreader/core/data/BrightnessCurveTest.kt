package app.fernreader.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** C-109: the reader's slider uses the system slider's curve (AOSP SettingsLib BrightnessUtils, HLG). */
class BrightnessCurveTest {
    private val min = 0.0035 // a typical panel minimum (config_screenBrightnessSettingMinimumFloat)

    @Test fun zero_middle_and_full_match_the_system_curve() {
        assertEquals(min, BrightnessCurve.toLinear(0.0, min, 1.0))
        // The system slider's middle is 1/12 of the range: (0.5 / R)^2 = 1 on the 0..12 HLG scale.
        assertEquals(min + (1 - min) / 12, BrightnessCurve.toLinear(0.5, min, 1.0), 1e-12)
        assertEquals(1.0, BrightnessCurve.toLinear(1.0, min, 1.0))
        assertEquals(0.0, BrightnessCurve.toPosition(min, min, 1.0))
        assertEquals(0.5, BrightnessCurve.toPosition(min + (1 - min) / 12, min, 1.0), 1e-12)
        assertEquals(1.0, BrightnessCurve.toPosition(1.0, min, 1.0), 1e-4)
    }

    /** Values computed from the AOSP formula independently: exp((p - C) / A) + B over 12. */
    @Test fun upper_half_is_logarithmic() {
        assertEquals((kotlin.math.exp((0.75 - 0.55991073) / 0.17883277) + 0.28466892) / 12, BrightnessCurve.toLinear(0.75, 0.0, 1.0), 1e-12)
        assertEquals(0.2650, BrightnessCurve.toLinear(0.75, 0.0, 1.0), 1e-4)
        assertEquals(0.25 * 0.25 / 0.25 / 12, BrightnessCurve.toLinear(0.25, 0.0, 1.0), 1e-12) // (0.25/0.5)^2/12
    }

    @Test fun most_of_the_travel_is_in_the_dim_range() {
        // Half of full brightness is at about 87 % of the slider, a tenth at about 54 %.
        assertTrue(BrightnessCurve.toPosition(0.5, 0.0, 1.0) in 0.86..0.88)
        assertTrue(BrightnessCurve.toPosition(0.1, 0.0, 1.0) in 0.53..0.55)
    }

    @Test fun round_trips_and_is_monotonic() {
        var last = -1.0
        for (i in 0..1000) {
            val p = i / 1000.0
            val l = BrightnessCurve.toLinear(p, min, 1.0)
            assertTrue(l >= last, "monotonic at $p"); last = l
            assertEquals(p, BrightnessCurve.toPosition(l, min, 1.0), 1e-4, "round trip at $p")
        }
    }

    @Test fun out_of_range_input_is_clamped() {
        assertEquals(min, BrightnessCurve.toLinear(-1.0, min, 1.0))
        assertEquals(1.0, BrightnessCurve.toLinear(2.0, min, 1.0))
        assertEquals(0.0, BrightnessCurve.toPosition(0.0, min, 1.0))
        assertEquals(1.0, BrightnessCurve.toPosition(1.5, min, 1.0))
    }
}
