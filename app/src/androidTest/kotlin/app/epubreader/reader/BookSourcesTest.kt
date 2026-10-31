package app.epubreader.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epubreader.TestBooks
import app.epubreader.core.data.SourceRef
import app.epubreader.core.epub.zip.ZipReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookSourcesTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val sources = BookSources(ctx)
    private fun opf(ref: SourceRef) = sources.open(ref).let { ZipReader.open(it.source).use { z -> z.entry("OEBPS/content.opf") } }

    @Test fun file_path() { val f = TestBooks.write(ctx, "a.epub"); assertNotNull(opf(SourceRef.File(f.path))) }

    @Test fun content_uri_with_seekable_descriptor() {
        TestBooks.write(ctx, "b.epub"); assertNotNull(opf(SourceRef.Content("content://app.epubreader.test.books/b.epub")))
    }

    @Test fun pipe_is_copied_then_opened() {
        TestBooks.write(ctx, "c.epub")
        assertNotNull(opf(SourceRef.Content("content://app.epubreader.test.books/c.epub?pipe=1")))
        assertTrue(ctx.cacheDir.resolve("opened").listFiles()!!.isNotEmpty())
    }

    @Test fun opener_classifies_failures_and_reuses_warm_session() = runBlocking {
        val server = BookContentServer(); val opener = BookOpener(sources, server)
        val bad = ctx.cacheDir.resolve("testbooks/bad.epub").apply { parentFile!!.mkdirs(); writeText("not an epub at all\n") }
        assertEquals(OpenResult.Failed(OpenFailure.NOT_A_ZIP), opener.open(SourceRef.File(bad.path)))
        assertEquals(OpenResult.Failed(OpenFailure.NO_ACCESS), opener.open(SourceRef.File("/nonexistent/x.epub")))
        val f = TestBooks.write(ctx, "d.epub"); val ref = SourceRef.File(f.path)
        val first = opener.open(ref) as OpenResult.Ok
        opener.close(first.sessionId)
        val again = opener.open(ref) as OpenResult.Ok
        assertEquals(first.sessionId, again.sessionId)            // warm reuse
        assertEquals(200, server.serve("${again.sessionId}/entries.json").status)
    }
}
