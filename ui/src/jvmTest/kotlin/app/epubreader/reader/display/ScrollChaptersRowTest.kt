package app.epubreader.reader.display

import app.epubreader.core.data.Flow
import app.epubreader.core.data.ReaderSettings
import app.epubreader.core.data.ScrollChapters
import app.epubreader.ui.Copy
import kotlin.test.Test
import kotlin.test.assertEquals

/** C-085: the display panel's "Chapters" row (under Reading mode) is shown only while the book scrolls. */
class ScrollChaptersRowTest {
    @Test fun shown_for_scroll_and_for_auto_in_landscape_only() {
        for (landscape in listOf(false, true)) {
            assertEquals(true, ReaderSettings(flow = Flow.SCROLLED).scrolls(landscape))
            assertEquals(false, ReaderSettings(flow = Flow.PAGED).scrolls(landscape))
        }
        assertEquals(true, ReaderSettings(flow = Flow.AUTO).scrolls(landscape = true))
        assertEquals(false, ReaderSettings(flow = Flow.AUTO).scrolls(landscape = false))
    }

    @Test fun options_are_continuous_then_one_at_a_time() {
        assertEquals(listOf(ScrollChapters.CONTINUOUS to "Continuous", ScrollChapters.ONE_AT_A_TIME to "One at a time"), ChaptersOptions)
        assertEquals("Chapters", Copy.scrollChapters)
    }
}
