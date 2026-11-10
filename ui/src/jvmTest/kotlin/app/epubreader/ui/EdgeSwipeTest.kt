package app.epubreader.ui

import kotlin.math.PI
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** C-098, C-101, C-102: the recognition shared by the library's drawer and the reader's Contents, in dp from the edge. */
class EdgeSwipeTest {
    @Test fun the_strip_is_about_24_dp() {
        assertTrue(inEdgeStrip(0f)); assertTrue(inEdgeStrip(24f)); assertFalse(inEdgeStrip(25f))
    }

    @Test fun nothing_is_judged_inside_the_slop() {
        val w = SidewaysWatch(10f, 100f)
        assertFalse(w.sideways(17f, 100f))
        assertTrue(w.still)
        assertTrue(w.sideways(18f, 100f))
    }

    @Test fun an_angled_start_more_down_than_sideways_counts() {
        assertTrue(SidewaysWatch(10f, 100f).sideways(16f, 108f))
    }

    @Test fun a_straight_scroll_and_a_drift_do_not() {
        val w = SidewaysWatch(10f, 100f)
        assertFalse(w.sideways(10f, 110f))
        assertFalse(w.still)
        for (i in 1..20) assertFalse(w.sideways(10f + i, 110f + 30f * i), "step $i")
    }

    @Test fun a_move_towards_the_edge_does_not() {
        assertFalse(SidewaysWatch(20f, 100f).sideways(10f, 100f))
        assertFalse(SidewaysWatch(20f, 100f).sideways(-20f, 100f))
    }

    @Test fun a_long_scroll_weighs_nothing_once_it_goes_sideways() {
        val w = SidewaysWatch(10f, 100f)
        for (y in 120..800 step 20) assertFalse(w.sideways(10f, y.toFloat()))
        assertFalse(w.sideways(29f, 800f), "the step isn't finished")
        assertTrue(w.sideways(30f, 800f))
    }

    // ---- C-101: the angle from the touch-down ----

    /** The user's swipe from the phone (EdgeContentsTest's replay), every 8 ms: no 20 dp step of it is at 0.6. */
    private val phone = listOf(
        8.4f to 646.9f, 18.9f to 623.6f, 33.1f to 591.6f, 48f to 559.3f, 60f to 536.4f, 72.7f to 512f, 86.2f to 488f,
        96f to 462.5f, 108.4f to 434.2f, 121.1f to 406.5f, 128.7f to 393.1f, 140.7f to 371.3f, 145.1f to 365.8f,
    )

    @Test fun the_phones_swipe_counts_once_24_dp_in() {
        val w = SidewaysWatch(phone[0].first, phone[0].second)
        val at = phone.drop(1).indexOfFirst { (x, y) -> w.sideways(x, y) } + 1
        assertEquals(2, at, "24.7 dp in, 55.3 up")
    }

    @Test fun not_before_24_dp_in() {
        assertFalse(SidewaysWatch(0f, 300f).sideways(23f, 350f))
        assertTrue(SidewaysWatch(0f, 300f).sideways(24f, 350f))
    }

    @Test fun nearly_vertical_never_inward_must_be_at_least_0_3_of_the_vertical() {
        assertTrue(SidewaysWatch(0f, 300f).sideways(30f, 399f))
        assertFalse(SidewaysWatch(0f, 300f).sideways(30f, 401f))
        assertFalse(SidewaysWatch(0f, 300f).sideways(30f, 199f))
    }

    @Test fun a_thumb_scrolling_the_strip_15_degrees_off_vertical_never_counts() {
        val k = tan(15 * PI / 180).toFloat()
        for (dir in listOf(1f, -1f)) {
            val w = SidewaysWatch(5f, 1000f)
            for (d in 0..1500 step 4) assertFalse(w.sideways(5f + k * d, 1000f + dir * d), "at $d dp, ${if (dir > 0) "down" else "up"}")
        }
    }
}
