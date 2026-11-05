package app.epubreader.reader.contents

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
}
