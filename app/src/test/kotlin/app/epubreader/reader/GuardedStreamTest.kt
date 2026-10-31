package app.epubreader.reader

import app.epubreader.core.epub.zip.ZipError
import app.epubreader.core.epub.zip.ZipFormatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.nio.channels.ClosedChannelException

/** WebView only handles IOException from a response body; anything else thrown on its I/O thread kills the app. */
class GuardedStreamTest {
    private fun failing(e: Exception) = object : InputStream() {
        override fun read(): Int = throw e
        override fun read(b: ByteArray, off: Int, len: Int): Int = throw e
    }

    @Test fun zip_format_error_while_reading_becomes_io_exception() {
        val e = runCatching { GuardedStream(failing(ZipFormatException(ZipError.DAMAGED, "bad entry"))).read(ByteArray(8), 0, 8) }.exceptionOrNull()
        assertTrue(e is IOException)
        assertTrue(e!!.cause is ZipFormatException)
    }

    @Test fun io_exceptions_pass_through_unchanged() {
        val closed = ClosedChannelException()
        assertEquals(closed, runCatching { GuardedStream(failing(closed)).read() }.exceptionOrNull())
    }

    @Test fun normal_reads_are_unchanged() {
        val s = GuardedStream("abc".byteInputStream())
        assertEquals("abc", s.readBytes().decodeToString())
    }
}
