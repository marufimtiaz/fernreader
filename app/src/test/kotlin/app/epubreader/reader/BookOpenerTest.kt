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

    private val epub = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("mimetype")); z.write("application/epub+zip".toByteArray()); z.closeEntry() }
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
}
