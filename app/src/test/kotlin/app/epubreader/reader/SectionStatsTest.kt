package app.epubreader.reader

import app.epubreader.core.epub.zip.ByteArraySource
import app.epubreader.core.epub.zip.ZipReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SectionStatsTest {
    private fun zip(files: Map<String, String>): ZipReader {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, t) in files) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
        return ZipReader.open(ByteArraySource(out.toByteArray()))
    }
    private val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/p.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
    private val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="u"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="u">x</dc:identifier><dc:title>T</dc:title></metadata>
        <manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/><item id="gone" href="gone.xhtml" media-type="application/xhtml+xml"/></manifest>
        <spine><itemref idref="a"/><itemref idref="gone" linear="no"/><itemref idref="b"/></spine></package>"""

    @Test fun one_stat_per_spine_item_in_spine_order() {
        val z = zip(mapOf(
            "META-INF/container.xml" to container, "OPS/p.opf" to opf,
            "OPS/a.xhtml" to "<html><head><title>x</title></head><body>\n <p>a  \n b</p>\n</body></html>",
            "OPS/b.xhtml" to "<html><body><img src='i.png'/><svg/></body></html>",
        ))
        assertEquals(listOf(SectionStat(3, 0), SectionStat(0, 0), SectionStat(0, 2)), sectionStats(z))
    }

    /** C-083: the book-pages pass gives the stats pass's stats for an unchunked book (a first open reads it once). */
    @Test fun the_book_pages_pass_gives_the_same_stats() {
        val files = mapOf(
            "META-INF/container.xml" to container, "OPS/p.opf" to opf,
            "OPS/a.xhtml" to "<html><head><title>x</title></head><body>\n <p>a  \n b</p>\n</body></html>",
            "OPS/b.xhtml" to "<html><body><img src='i.png'/><svg/></body></html>",
        )
        val s = BookSession("s", app.epubreader.core.data.SourceRef.File("/x.epub"), zip(files), 1, "t")
        val r = bookPages(s, withStats = true)!!
        assertEquals(sectionStats(zip(files)), r.stats)
        assertEquals(1, r.pages.count)
        assertNull(bookPages(s, withStats = false)!!.stats)
    }

    @Test fun no_package_gives_no_stats() = assertNull(sectionStats(zip(mapOf("x.txt" to "x"))))
}
