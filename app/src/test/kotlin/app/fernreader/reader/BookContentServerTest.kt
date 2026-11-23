package app.fernreader.reader

import app.fernreader.core.data.SourceRef
import app.fernreader.core.epub.zip.ByteArraySource
import app.fernreader.core.epub.zip.ZipReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BookContentServerTest {
    private fun zip(vararg e: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> e.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
    }.toByteArray()

    private val bytes = zip(
        "mimetype" to "application/epub+zip",
        "OEBPS/content.opf" to "<package/>",
        "OEBPS/Text/Livres Été.xhtml" to "<html>été</html>",
    )
    private val src = ByteArraySource(bytes)
    private val session = BookSession("s1", SourceRef.File("/x.epub"), ZipReader.open(src), bytes.size.toLong(), stamp = "1")
    private val server = BookContentServer().apply { register(session) }

    private fun body(p: String) = server.serve(p).let { it.status to it.body().readBytes().decodeToString() }

    @Test fun entries_json_lists_every_entry_with_size() {
        val (status, json) = body("s1/entries.json")
        assertEquals(200, status)
        assertTrue(json, json.contains("\"name\":\"OEBPS/Text/Livres Été.xhtml\""))
        assertTrue(json, json.contains("\"size\":10"))
    }

    @Test fun entry_is_streamed_with_mime_by_extension() {
        val r = server.serve("s1/OEBPS/Text/Livres Été.xhtml")
        assertEquals(200, r.status); assertEquals("application/xhtml+xml", r.mime)
        assertEquals("<html>été</html>", r.body().readBytes().decodeToString())
    }

    @Test fun unknown_entry_and_unknown_session_are_404() {
        assertEquals(404, server.serve("s1/OEBPS/missing.xhtml").status)
        assertEquals(404, server.serve("nope/OEBPS/content.opf").status)
    }

    @Test fun dot_segments_are_rejected() {
        assertEquals(403, server.serve("s1/OEBPS/../mimetype").status)
        assertEquals(403, server.serve("s1/./mimetype").status)
    }

    @Test fun closed_session_is_410() {
        server.unregister("s1")!!.close()
        assertEquals(410, server.serve("s1/OEBPS/content.opf").status)
    }
}
