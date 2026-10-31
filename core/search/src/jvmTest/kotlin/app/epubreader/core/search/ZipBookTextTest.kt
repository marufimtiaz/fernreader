package app.epubreader.core.search

import app.epubreader.core.epub.zip.ByteArraySource
import app.epubreader.core.epub.zip.ZipReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ZipBookTextTest {
    @Test fun sections_are_the_canonical_text_in_spine_order() {
        val b = miniBook(listOf("<p>First &amp; one</p>", "<p>Second</p><script>x()</script>", "<p>Third</p>"))
        assertEquals(3, b.sectionCount)
        assertEquals("First & one", b.section(0)!!.text.trim())
        assertEquals("Second", b.section(1)!!.text.trim())
        assertNull(b.section(3))
    }

    @Test fun a_package_the_loader_rejects_throws_instead_of_yielding_no_sections() {
        val notEpub = ZipReader.open(ByteArraySource(java.io.ByteArrayOutputStream().also { o -> java.util.zip.ZipOutputStream(o).use { z -> z.putNextEntry(java.util.zip.ZipEntry("x.txt")); z.write(1); z.closeEntry() } }.toByteArray()))
        assertFailsWith<BookUnavailableException> { ZipBookText(notEpub) }
    }
}
