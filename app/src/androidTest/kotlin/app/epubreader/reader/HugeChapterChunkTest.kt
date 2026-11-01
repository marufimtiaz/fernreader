package app.epubreader.reader

import android.util.Log
import androidx.room.useWriterConnection
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epubreader.EpubReaderApp
import app.epubreader.MainActivity
import app.epubreader.core.data.Annotation
import app.epubreader.core.data.Bookmark
import app.epubreader.core.data.SourceRef
import app.epubreader.core.epub.chunk.ChunkMap
import app.epubreader.core.epub.chunk.ChunkPolicy
import app.epubreader.core.epub.chunk.Chunker
import app.epubreader.core.epub.chunk.FileChunkStore
import app.epubreader.core.epub.openFileSource
import app.epubreader.core.epub.pkg.LoadResult
import app.epubreader.core.epub.pkg.PackageLoader
import app.epubreader.core.epub.zip.ZipReader
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.reader.search.SearchResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M8 8.3 (F-037, F-050, F-061, R-006): the huge single chapter (10 MB XHTML, MARKERWORDHUGE 138 times, P-044) read
 * unchunked as a control, then chunked with the default policy. The chunked run resumes on the same text, search hit
 * #100 lands on the same word, the control's highlight is drawn on the same words and its bookmark is marked on its
 * page; a highlight made on the hit in the chunked run stores the very CFI the control stored. The control's rows are
 * what M2..M7 wrote (no chunking existed), so they are also the "rows written before chunking".
 *
 * Hit #100 is the 1-based hundredth hit in book order: index 99 of the result list.
 */
@RunWith(AndroidJUnit4::class)
class HugeChapterChunkTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val graph get() = (inst.targetContext.applicationContext as EpubReaderApp).graph
    private val hit100 = 99
    /** A threshold over the file's size: nothing is split (the control run). */
    private val unchunked = ChunkPolicy(thresholdBytes = Int.MAX_VALUE)

    private suspend fun ActivityScenario<MainActivity>.jsValue(expr: String): Any? =
        JSONTokener(js("JSON.stringify($expr)")).nextValue().let { if (it is String) JSONTokener(it).nextValue() else null }
    private suspend fun ActivityScenario<MainActivity>.str(expr: String): String? = jsValue(expr) as? String

    private suspend fun bookId(f: File): String = withTimeout(10_000) {
        var id: String? = null
        while (id == null) { id = graph.library.knownBookId(SourceRef.File(f.path)); if (id == null) delay(100) }
        id
    }

    private suspend fun ReaderHarness.searchHit(text: String): Pair<SearchResult, Int> {
        sc.onActivity { it.controller.searchFor(text) }
        val st = withTimeout(30_000) { a.controller.searcher.state.first { it.submitted == text && it.done } }
        return st.results[hit100] to st.total
    }

    /** Jumps to [r] and returns the search highlight's CFI (the reader's coordinates) once it is drawn. */
    private suspend fun ReaderHarness.jump(r: SearchResult): String {
        drain()
        sc.onActivity { it.controller.searcher.select(r) }
        relocate(30_000); painted(30_000)
        return withTimeout(15_000) {
            while (true) { sc.str("window.__readerTest.searchHighlight()")?.let { return@withTimeout it }; delay(100) }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }

    /** Selects the range [cfi] and makes it a highlight through the controller (the F-060 path); returns the stored row. */
    private suspend fun ReaderHarness.highlight(bookId: String, cfi: String): Annotation {
        val before = graph.annotations.annotations(bookId).first().map { it.id }.toSet()
        drain()
        assertEquals("true", sc.js("window.__readerTest.selectCfi(${JSONObject.quote(cfi)})"))
        next(5_000) { e -> (e as? FromWeb.Selection)?.takeIf { it.cfi != null } }
        sc.onActivity { it.controller.highlightSelection() }
        val row = withTimeout(5_000) { graph.annotations.annotations(bookId).first { l -> l.any { it.id !in before } } }.first { it.id !in before }
        sc.onActivity { it.controller.dismissHighlightPicker() }
        return row
    }

    private suspend fun ActivityScenario<MainActivity>.drawnText(id: String): String = withTimeout(15_000) {
        while (true) {
            ((jsValue("window.__readerTest.annotation(${JSONObject.quote(id)})") as? JSONObject)?.takeIf { !it.isNull("text") })?.let { return@withTimeout it.getString("text") }
            delay(100)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private suspend fun ReaderHarness.closeBook() {
        sc.onActivity { it.goBack() }
        withTimeout(10_000) { a.controller.state.first { !it.open } }
        delay(800) // the close-out stores the last position
    }

    /** The CFI's package part (`epubcfi(/6/N[id]`): the spine step, the original itemref's in stored rows. */
    private fun spineStep(cfi: String) = cfi.substringBefore('!')

    /** The chunked book's map, as the opener built it (read back from its cache in the app's chunk directory). */
    private fun chunkMap(f: File, policy: ChunkPolicy): ChunkMap {
        val zip = ZipReader.open(openFileSource(f.path))
        try {
            val pkg = (PackageLoader.load(zip) as LoadResult.Ok).pkg
            return Chunker(policy).chunk(zip, pkg, FileChunkStore(graph.chunkDirs.dir(SourceRef.File(f.path), policy))).map
        } finally { zip.close() }
    }

    @Test fun chunked_reading_lands_on_the_same_text_as_an_unchunked_control(): Unit = runBlocking {
        val f = File("/sdcard/Books/generated/stress-huge-single-chapter.epub")
        warAndPeace() // grants all-files access
        check(f.isFile) { "corpus not pushed: $f" }
        val ref = SourceRef.File(f.path)
        val policy = ChunkPolicy()
        graph.forgetChunks(listOf(f.path)) // the chunked run builds its cache from scratch
        var bookId: String? = graph.library.knownBookId(ref)
        val savedPosition = bookId?.let { graph.library.position(it) }
        val made = mutableListOf<String>()
        val marks = mutableListOf<String>()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            h.ready()
            val c = h.a.controller
            try {
                // ---- Control: the same book, nothing split (as M2..M7 read it) ----
                graph.opener.policy = unchunked
                h.drain()
                sc.onActivity { it.openBook(ref) }
                h.relocate(120_000); h.painted(60_000)
                withTimeout(60_000) { c.state.first { !it.loading && it.toc.isNotEmpty() } }
                h.settle()
                val id = bookId(f).also { bookId = it }
                for (a in graph.annotations.annotations(id).first()) graph.annotations.remove(a.id) // a clean book
                graph.bookmarks.bookmarks(id).first().forEach { graph.bookmarks.remove(it.id) }
                val controlSections = JSONArray(sc.js("window.__readerTest.linear()")).length()
                withTimeout(180_000) { graph.search.progress(id).first { it.complete } }

                val (hitC, total) = h.searchHit("MARKERWORDHUGE")
                assertEquals(138, total) // P-044
                val hitCfiC = h.jump(hitC)
                assertEquals("MARKERWORDHUGE", sc.str("window.__readerTest.cfiText(${JSONObject.quote(hitCfiC)})"))
                h.settle()
                val pageBlockC = sc.str("window.__readerTest.firstVisibleBlock()")
                assertNotNull("page start text", pageBlockC)
                val hlC = h.highlight(id, hitCfiC).also { made += it.id }
                assertEquals("MARKERWORDHUGE", hlC.text)
                sc.onActivity { it.controller.toggleBookmark() }
                val bmC: Bookmark = withTimeout(5_000) { c.state.first { it.bookmarked && it.bookmarks.size == 1 } }.bookmarks.single().also { marks += it.id }
                val stoppedAt = h.settle()
                h.closeBook()
                val posC = graph.library.position(id)!!
                assertEquals(stoppedAt.cfi, posC.cfi) // identity map: the stored position is the reader's own

                // ---- Chunked: the default policy splits the chapter ----
                graph.opener.policy = policy
                h.drain()
                sc.onActivity { it.openBook(ref) }
                h.relocate(60_000); h.painted(30_000)
                withTimeout(30_000) { c.state.first { !it.loading && it.toc.isNotEmpty() } }
                h.settle()
                val chunkedSections = JSONArray(sc.js("window.__readerTest.linear()")).length()
                assertTrue("split: $controlSections -> $chunkedSections sections", chunkedSections > controlSections + 10)
                val map = chunkMap(f, policy)
                assertTrue(!map.isIdentity)

                // Resume (F-037) from the control's (unchunked) position row: the same text is on screen.
                assertEquals("true", sc.js("window.__readerTest.blockVisible(${JSONObject.quote(pageBlockC!!)})"))
                // The control's highlight is drawn on the same words; its bookmark is marked on this page.
                assertEquals(hlC.text, sc.drawnText(hlC.id))
                withTimeout(10_000) { c.state.first { it.bookmarked } }

                // Hit #100 lands on the same word: the same original range as the control's.
                val (hitK, totalK) = h.searchHit("MARKERWORDHUGE")
                assertEquals(138, totalK)
                assertEquals(hitC.hit, hitK.hit) // search stays on original sections
                val hitCfiK = h.jump(hitK)
                assertNotEquals("virtual coordinates on the web side", hitCfiC, hitCfiK)
                assertEquals("MARKERWORDHUGE", sc.str("window.__readerTest.cfiText(${JSONObject.quote(hitCfiK)})"))
                assertEquals(hitCfiC, map.cfiToOriginal(hitCfiK))

                // A highlight made there now stores exactly the control's CFI (original coordinates), same section.
                val hlK = h.highlight(id, hitCfiK).also { made += it.id }
                assertEquals(hlC.cfi, hlK.cfi)
                assertEquals(hlC.spineIndex, hlK.spineIndex)
                assertEquals(hlC.text, hlK.text)
                assertEquals(hlC.text, sc.drawnText(hlK.id))

                // The control's bookmark: the list's jump lands on its page, with the marker.
                h.tocJump("Section 1"); h.settle()
                withTimeout(10_000) { c.state.first { !it.bookmarked } }
                h.drain()
                sc.onActivity { it.controller.onBookmarkSelected(bmC) }
                h.relocate(15_000)
                withTimeout(10_000) { c.state.first { it.bookmarked } }
                assertEquals("true", sc.js("window.__readerTest.blockVisible(${JSONObject.quote(pageBlockC)})"))

                // Stored rows were not rewritten by the chunked session.
                assertEquals(bmC.cfi, graph.bookmarks.bookmarks(id).first().single { it.id == bmC.id }.cfi)
                assertEquals(hlC.cfi, graph.annotations.annotations(id).first().single { it.id == hlC.id }.cfi)

                // A bookmark made in the chunked run on that page (the button removes the control's first). Its CFI is
                // the part's page start in original coordinates: same section, same itemref step and same text as the
                // control's; byte-identical only if both layouts start the page at the same place (logged).
                sc.onActivity { it.controller.toggleBookmark() }
                withTimeout(5_000) { c.state.first { !it.bookmarked && it.bookmarks.isEmpty() } }
                sc.onActivity { it.controller.toggleBookmark() }
                val bmK = withTimeout(5_000) { c.state.first { it.bookmarked && it.bookmarks.size == 1 } }.bookmarks.single().also { marks += it.id }
                Log.i("HugeChapterChunkTest", "bookmark control=${bmC.cfi} chunked=${bmK.cfi} identical=${bmC.cfi == bmK.cfi}")
                assertEquals(bmC.spineIndex, bmK.spineIndex)
                assertEquals(spineStep(bmC.cfi), spineStep(bmK.cfi))
                assertEquals(bmK.cfi, map.cfiToOriginal(map.cfiToVirtual(bmK.cfi)))
                assertNotEquals("stored original, not the part's", map.cfiToVirtual(bmK.cfi), bmK.cfi)
                h.tocJump("Section 1"); h.settle()
                withTimeout(10_000) { c.state.first { !it.bookmarked } }
                h.drain()
                sc.onActivity { it.controller.onBookmarkSelected(bmK) }
                h.relocate(15_000)
                withTimeout(10_000) { c.state.first { it.bookmarked } }
                assertEquals("true", sc.js("window.__readerTest.blockVisible(${JSONObject.quote(pageBlockC)})"))
                val stoppedK = h.settle()
                h.closeBook()

                // The position the chunked run stored on close: original coordinates, the control's section and itemref.
                val posK = graph.library.position(id)!!
                Log.i("HugeChapterChunkTest", "position control=${posC.cfi} chunked=${posK.cfi} identical=${posC.cfi == posK.cfi}")
                assertEquals(map.cfiToOriginal(stoppedK.cfi), posK.cfi)
                assertEquals(posC.spineIndex, posK.spineIndex)
                assertEquals(spineStep(posC.cfi), spineStep(posK.cfi))
                assertEquals(posK.cfi, map.cfiToOriginal(map.cfiToVirtual(posK.cfi)))
                assertNotEquals("stored original, not the part's", map.cfiToVirtual(posK.cfi), posK.cfi)
            } finally {
                graph.opener.policy = policy
                runCatching { if (c.state.value.open) h.closeBook() }
                for (a in made) graph.annotations.remove(a)
                for (b in marks) graph.bookmarks.remove(b)
                bookId?.let { id ->
                    if (savedPosition != null) {
                        graph.db.positionDao().put(id, savedPosition.cfi, savedPosition.fraction, savedPosition.spineIndex, (graph.library.position(id)?.updatedAt ?: savedPosition.updatedAt) + 1)
                    }
                    // The position row this test created (if the book had none) and the chunked layout's page data.
                    graph.db.useWriterConnection { t ->
                        if (savedPosition == null) t.usePrepared("DELETE FROM reading_position WHERE bookId = ?") { it.bindText(1, id); it.step() }
                        t.usePrepared("DELETE FROM book_cache WHERE bookId = ? AND kind LIKE 'section-stats.v1@%'") { it.bindText(1, id); it.step() }
                    }
                }
                graph.forgetChunks(listOf(f.path)) // also evicts the warm chunked session, whose cache is deleted here
                h.stop()
            }
        }
    }
}
