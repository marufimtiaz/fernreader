package app.fernreader.reader.contents

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentsSlideTest {
    @Test fun a_whole_slide_takes_300_ms() {
        assertEquals(300, contentsSlideMs(0f, 1f))
        assertEquals(300, contentsSlideMs(1f, 0f))
    }
    @Test fun the_rest_of_a_slide_takes_its_share_and_is_never_a_jump() {
        assertEquals(150, contentsSlideMs(0.5f, 1f))
        assertEquals(60, contentsSlideMs(0.99f, 1f))
    }
    @Test fun a_swipe_past_a_third_closes() {
        assertTrue(panelSwipeCloses(140f, 400f, 0f))
        assertFalse(panelSwipeCloses(120f, 400f, 0f))
    }
    @Test fun a_flick_closes_however_short() {
        assertTrue(panelSwipeCloses(30f, 400f, 0.5f))
        assertFalse(panelSwipeCloses(20f, 400f, 0.5f), "under 24 dp")
    }
    @Test fun a_flick_back_or_a_leftward_swipe_never_closes() {
        assertFalse(panelSwipeCloses(200f, 400f, -0.5f))
        assertFalse(panelSwipeCloses(-50f, 400f, 1f))
    }

    // ---- C-100: a finger takes hold of a slide ----

    @Test fun the_first_frame_shows_only_what_the_finger_moved_since_it_took_hold() {
        val h = FingerHold(finger = 0.08f, shown = 0f)
        assertEquals(0f, h.at(0.08f), "at the edge, not under the finger")
        assertEquals(0.02f, h.at(0.10f), 1e-6f)
        assertEquals(0.03f, h.at(0.11f, frameNanos = 5_000_000L), 1e-6f, "the frame that starts the catch-up still lags in full")
    }
    @Test fun it_catches_up_within_the_catch_up_time_and_smoothly() {
        val h = FingerHold(finger = 0.1f, shown = 0f)
        val t0 = 1_000_000_000L
        h.at(0.1f, t0)
        var last = 0f
        for (i in 1..20) {
            val p = h.at(0.1f, t0 + i * 8_000_000L)
            assertTrue(p >= last, "never back: $last -> $p")
            assertTrue(p - last <= 0.03f, "no step bigger than a share of the lag: $last -> $p")
            last = p
        }
        assertEquals(0.1f, last, 1e-6f)
        assertTrue(h.caughtUp)
        assertTrue(CATCH_UP_MS <= 150, "a few frames")
    }
    @Test fun a_hold_on_a_moving_panel_takes_it_where_it_is() {
        val h = FingerHold(finger = 0.97f, shown = 1f)
        assertEquals(1f, h.at(0.97f))
        assertEquals(0.95f, h.at(0.92f), 1e-6f)
    }
    @Test fun the_catch_up_starts_slow_and_ends_at_nothing() {
        assertEquals(1f, catchUpLeft(0f))
        assertTrue(catchUpLeft(16f) > 0.9f)
        assertEquals(0f, catchUpLeft(CATCH_UP_MS.toFloat()))
        assertEquals(0f, catchUpLeft(10_000f))
    }
}
