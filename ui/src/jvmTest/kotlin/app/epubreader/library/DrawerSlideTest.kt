package app.epubreader.library

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DrawerSlideTest {
    @Test fun the_strip_is_about_24_dp() {
        assertTrue(inDrawerStrip(0f)); assertTrue(inDrawerStrip(24f)); assertFalse(inDrawerStrip(25f))
    }

    @Test fun nothing_is_judged_inside_the_slop() {
        val w = SidewaysWatch(10f, 100f)
        assertFalse(w.sideways(17f, 100f))
        assertTrue(w.sideways(18f, 100f))
    }

    @Test fun an_angled_start_more_down_than_sideways_counts() {
        assertTrue(SidewaysWatch(10f, 100f).sideways(16f, 108f))
    }

    @Test fun a_straight_scroll_and_a_drift_do_not() {
        val w = SidewaysWatch(10f, 100f)
        assertFalse(w.sideways(10f, 110f))
        for (i in 1..20) assertFalse(w.sideways(10f + i, 110f + 30f * i), "step $i")
    }

    @Test fun a_move_towards_the_edge_does_not() {
        assertFalse(SidewaysWatch(20f, 100f).sideways(10f, 100f))
    }

    @Test fun a_long_scroll_weighs_nothing_once_it_goes_sideways() {
        val w = SidewaysWatch(10f, 100f)
        for (y in 120..800 step 20) assertFalse(w.sideways(10f, y.toFloat()))
        assertFalse(w.sideways(29f, 800f), "the step isn't finished")
        assertTrue(w.sideways(30f, 800f))
    }

    @Test fun a_release_finishes_past_a_third_or_with_a_flick() {
        assertTrue(drawerSwipeFinishes(120f, 360f, 0f))
        assertFalse(drawerSwipeFinishes(119f, 360f, 0f))
        assertTrue(drawerSwipeFinishes(30f, 360f, 0.5f))
        assertFalse(drawerSwipeFinishes(20f, 360f, 0.5f), "too short for a flick")
        assertFalse(drawerSwipeFinishes(200f, 360f, -0.5f), "a flick back")
    }
}
