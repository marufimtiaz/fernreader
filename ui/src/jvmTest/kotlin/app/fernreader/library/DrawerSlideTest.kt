package app.fernreader.library

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** C-098: the drawer's release; its recognition is shared with the reader (EdgeSwipeTest). */
class DrawerSlideTest {
    @Test fun a_release_finishes_past_a_third_or_with_a_flick() {
        assertTrue(drawerSwipeFinishes(120f, 360f, 0f))
        assertFalse(drawerSwipeFinishes(119f, 360f, 0f))
        assertTrue(drawerSwipeFinishes(30f, 360f, 0.5f))
        assertFalse(drawerSwipeFinishes(20f, 360f, 0.5f), "too short for a flick")
        assertFalse(drawerSwipeFinishes(200f, 360f, -0.5f), "a flick back")
    }
}
