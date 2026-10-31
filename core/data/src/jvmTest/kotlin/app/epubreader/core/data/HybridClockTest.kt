package app.epubreader.core.data

import kotlin.test.Test
import kotlin.test.assertTrue

class HybridClockTest {
    @Test fun strictly_increasing_even_if_wall_clock_goes_back() {
        var wall = 1_000L
        val c = HybridClock { wall }
        val a = c.now(); wall = 500; val b = c.now(); val d = c.now(); wall = 2_000; val e = c.now()
        assertTrue(a < b && b < d && d < e, "$a $b $d $e")
    }
}
