package app.fernreader.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaceholderTest {
    @Test fun stable_index_in_palette() {
        assertEquals(placeholderColorIndex("No Cover Book"), placeholderColorIndex("No Cover Book"))
        for (t in listOf("", "a", "Hefty Water", "שלום", "x".repeat(500))) assertTrue(placeholderColorIndex(t) in 0..7)
    }
}
