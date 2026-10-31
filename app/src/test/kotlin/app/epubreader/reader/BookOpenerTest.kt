package app.epubreader.reader

import app.epubreader.core.data.SourceRef
import app.epubreader.core.epub.zip.ByteArraySource
import app.epubreader.core.epub.zip.RandomAccessSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** I2 (R-002): whatever a provider or the zip reader throws, open() answers with a reason and releases the source. */
class BookOpenerTest {
    private class TrackingSource(private val inner: RandomAccessSource, private val failRead: Exception? = null) : RandomAccessSource {
        var closed = false
        override val size get() = inner.size
        override fun read(position: Long, dst: ByteArray, off: Int, len: Int): Int {
            failRead?.let { throw it }
            return inner.read(position, dst, off, len)
        }
        override fun close() { closed = true; inner.close() }
    }

    private class FakeSources(var open: () -> OpenedSource) : Sources {
        override fun open(ref: SourceRef) = open.invoke()
        override fun stamp(ref: SourceRef) = "1"
    }

    /** A loadable one-section package (the search text of a session needs a valid one). */
    private val epub = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            fun put(name: String, data: String) { z.putNextEntry(ZipEntry(name)); z.write(data.toByteArray()); z.closeEntry() }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OPS/p.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("OPS/p.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="u"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="u">x</dc:identifier><dc:title>X</dc:title></metadata><manifest><item id="c0" href="c0.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c0"/></spine></package>""")
            put("OPS/c0.xhtml", """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>c</title></head><body><p>x</p></body></html>""")
        }
    }.toByteArray()
    private val ref = SourceRef.Content("content://provider/doc/1")

    private fun opener(sources: Sources) = BookOpener(sources, BookContentServer(), Dispatchers.Unconfined, warn = {})

    @Test fun provider_runtime_exceptions_mean_no_access() = runBlocking {
        for (e in listOf(IllegalArgumentException("bad uri"), IllegalStateException("provider died"), UnsupportedOperationException("no"), RuntimeException("x"))) {
            assertEquals(e.toString(), OpenResult.Failed(OpenFailure.NO_ACCESS), opener(FakeSources { throw e }).open(ref))
        }
    }

    @Test fun unexpected_zip_reader_failure_means_damaged_and_closes_the_source() = runBlocking {
        val src = TrackingSource(ByteArraySource(epub), failRead = IllegalStateException("fd closed under us"))
        assertEquals(OpenResult.Failed(OpenFailure.DAMAGED), opener(FakeSources { OpenedSource(src, epub.size.toLong()) }).open(ref))
        assertTrue(src.closed)
    }

    @Test fun io_failure_while_reading_closes_the_source() = runBlocking {
        val src = TrackingSource(ByteArraySource(epub), failRead = java.io.IOException("EIO"))
        assertEquals(OpenResult.Failed(OpenFailure.NO_ACCESS), opener(FakeSources { OpenedSource(src, epub.size.toLong()) }).open(ref))
        assertTrue(src.closed)
    }

    @Test fun a_good_book_still_opens() = runBlocking {
        val src = TrackingSource(ByteArraySource(epub))
        val r = opener(FakeSources { OpenedSource(src, epub.size.toLong()) }).open(ref)
        assertTrue(r.toString(), r is OpenResult.Ok)
    }

    @Test fun book_text_is_available_for_the_current_session_only() = runBlocking {
        val o = opener(FakeSources { OpenedSource(TrackingSource(ByteArraySource(epub)), epub.size.toLong()) })
        val id = (o.open(ref) as OpenResult.Ok).sessionId
        assertTrue(o.bookText(id) != null)
        assertEquals(null, o.bookText("another-session"))
        o.close(id)
        assertEquals(null, o.bookText(id)) // closed: the warm session is not "current"
    }
}
