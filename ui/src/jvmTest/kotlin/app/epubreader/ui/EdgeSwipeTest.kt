package app.epubreader.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.HistoricalChange
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import kotlin.math.PI
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** C-098, C-101, C-102: the recognition shared by the library's drawer and the reader's Contents, in dp from the edge. */
@OptIn(ExperimentalComposeUiApi::class)
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

    /**
     * C-102: the user's swipe as measured on the phone (EdgeContentsTest replays it): from 8 dp in, 113 dp left and 240 dp
     * up over 300 ms (0.47 left per unit down), every 8 ms at an even speed. No step of it is at 0.6; it counts 24 dp in.
     */
    @Test fun the_users_swipe_counts_once_24_dp_in_and_not_before() {
        val path = (0..300 step 8).map { ms -> 8f + 113f * ms / 300 to 600f - 240f * ms / 300 }
        val w = SidewaysWatch(path[0].first, path[0].second)
        val at = path.drop(1).indexOfFirst { (x, y) -> w.sideways(x, y) } + 1
        assertEquals(8, at, "64 ms: 24.1 dp in, 51.2 up")
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

    // ---- the flick's speed: every reported position counts ----

    /**
     * The user's swipe (above) as the recogniser got it on the emulator while the panel's first frames held the main thread:
     * one change a frame up to 91 ms, then nothing until 296 ms, where one change brings the 24 moves of the stall in its
     * history, then the lift at 300 ms. Taking only each change's latest position left a 205 ms gap before the last 4 ms,
     * which read as a stop (speed 0): a flick short of a third slid back.
     */
    @Test fun a_flick_delivered_in_one_batch_after_a_stall_keeps_its_speed() {
        val path = { ms: Long -> Offset(1000f - (8f + 113f * ms / 300), 600f - 240f * ms / 300) } // px at density 1, from the right
        var prev: PointerInputChange? = null
        fun change(ms: Long, pressed: Boolean = true, history: List<Long> = emptyList()): PointerInputChange = PointerInputChange(
            id = PointerId(0), uptimeMillis = ms, position = path(ms), pressed = pressed,
            previousUptimeMillis = prev?.uptimeMillis ?: ms, previousPosition = prev?.position ?: path(ms), previousPressed = prev?.pressed ?: false,
            isInitiallyConsumed = false, type = PointerType.Touch,
        ).let { if (history.isEmpty()) it else it.copy(historical = history.map { t -> HistoricalChange(t, path(t)) }) }.also { prev = it }
        val f = FlickTracker()
        f.add(change(0))
        for (ms in listOf(16L, 33L, 50L, 66L, 83L, 91L)) f.add(change(ms))
        f.add(change(296, history = (99L..288L step 8).toList()))
        f.add(change(300, pressed = false))
        val speed = -f.xVelocity() / 1000f // inward from the right, dp/ms
        assertEquals(113f / 300, speed, 0.03f)
    }

    @Test fun a_thumb_scrolling_the_strip_15_degrees_off_vertical_never_counts() {
        val k = tan(15 * PI / 180).toFloat()
        for (dir in listOf(1f, -1f)) {
            val w = SidewaysWatch(5f, 1000f)
            for (d in 0..1500 step 4) assertFalse(w.sideways(5f + k * d, 1000f + dir * d), "at $d dp, ${if (dir > 0) "down" else "up"}")
        }
    }
}
