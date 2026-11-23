package app.fernreader.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.fernreader.TestBooks
import app.fernreader.core.data.SourceRef
import app.fernreader.core.epub.zip.ZipReader
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
        TestBooks.write(ctx, "b.epub"); assertNotNull(opf(SourceRef.Content("content://app.fernreader.test.books/b.epub")))
    }

    @Test fun pipe_is_copied_then_opened() {
        TestBooks.write(ctx, "c.epub")
        assertNotNull(opf(SourceRef.Content("content://app.fernreader.test.books/c.epub?pipe=1")))
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

    // I4: the debug provider serves only plain names from cache/testbooks, never a path out of it.
    @Test fun test_provider_rejects_path_escapes_and_opener_maps_them() = runBlocking {
        TestBooks.write(ctx, "e.epub")
        val opener = BookOpener(sources, BookContentServer())
        for (uri in listOf(
            "content://app.fernreader.test.books/..%2F..%2Fdatabases%2Flibrary.db",
            "content://app.fernreader.test.books/..%2Ftestbooks%2Fe.epub",
            "content://app.fernreader.test.books/..",
        )) {
            assertEquals(uri, OpenResult.Failed(OpenFailure.NO_ACCESS), opener.open(SourceRef.Content(uri)))
        }
        assertTrue(opener.open(SourceRef.Content("content://app.fernreader.test.books/e.epub")) is OpenResult.Ok)
    }

    @Test fun test_provider_is_not_exported() {
        val info = ctx.packageManager.resolveContentProvider("app.fernreader.test.books", 0)!!
        assertEquals(false, info.exported)
    }
}
