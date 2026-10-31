package app.epubreader.core.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZipBookTextTest {
    @Test fun sections_are_the_canonical_text_in_spine_order() {
        val b = miniBook(listOf("<p>First &amp; one</p>", "<p>Second</p><script>x()</script>", "<p>Third</p>"))
        assertEquals(3, b.sectionCount)
        assertEquals("First & one", b.section(0)!!.text.trim())
        assertEquals("Second", b.section(1)!!.text.trim())
        assertNull(b.section(3))
    }
}
