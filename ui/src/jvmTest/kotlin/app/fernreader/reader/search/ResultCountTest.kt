package app.fernreader.reader.search

import app.fernreader.ui.Copy
import kotlin.test.Test
import kotlin.test.assertEquals

class ResultCountTest {
    @Test fun count_line() {
        assertEquals("Searching…", Copy.resultCount(0, false, running = true))
        assertEquals("No results", Copy.resultCount(0, false, running = false))
        assertEquals("1 result", Copy.resultCount(1, false, false))
        assertEquals("108 results", Copy.resultCount(108, false, false))
        assertEquals("1234 results", Copy.resultCount(1234, false, true)) // still counting up
        assertEquals("5000+ results", Copy.resultCount(5000, true, false))
    }
}
