package app.fernreader.reader

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.fernreader.EpubReaderApp
import app.fernreader.MainActivity
import app.fernreader.core.data.SourceRef
import app.fernreader.reader.search.SearchResult
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** F-050/F-051: the real index over a real book, a search through the controller, and the jump with its highlight. */
@RunWith(AndroidJUnit4::class)
class SearchFlowTest {
    private val graph get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EpubReaderApp).graph

    /** Waits (at most 30 s) until the open book's index is complete. */
    private suspend fun awaitIndex(file: File) {
        val bookId = withTimeout(10_000) {
            var id: String? = null
            while (id == null) { id = graph.library.knownBookId(SourceRef.File(file.path)); if (id == null) delay(100) }
            id
        }
        withTimeout(30_000) { graph.search.progress(bookId).first { it.complete } }
    }

    private suspend fun ReaderHarness.searchDone(text: String): List<SearchResult> {
        sc.onActivity { it.controller.searchFor(text) }
        val s = withTimeout(15_000) { a.controller.searcher.state.first { it.submitted == text && it.done } }
        return s.results
    }

    private suspend fun ReaderHarness.select(r: SearchResult) {
        sc.onActivity { it.controller.searcher.select(r) }
        val at = relocate(10_000) { it.sectionIndex == r.hit.spineIndex }
        painted()
        assertEquals(r.hit.spineIndex, at.sectionIndex)
        // the highlight is drawn once the section's text is laid out: poll briefly
        var drawn = false
        repeat(50) { if (!drawn) { drawn = sc.js("window.__readerTest.overlayCount(${r.hit.spineIndex})").toInt() > 0; if (!drawn) delay(100) } }
        assertNotEquals("null", sc.js("window.__readerTest.searchHighlight()"))
        assertTrue("highlight overlay in section ${r.hit.spineIndex}", drawn)
    }

    @Test fun borodino_has_108_hits_and_the_first_one_is_highlighted_after_the_jump(): Unit = runBlocking {
        val wp = warAndPeace()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            h.ready(); h.open(wp)
            awaitIndex(wp)
            val results = h.searchDone("Borodinó")
            assertEquals(108, h.a.controller.searcher.state.value.total)
            assertEquals(108, results.size)
            assertNotNull(results.first().chapter)
            h.select(results.first())
            h.stop()
        }
    }

    @Test fun marker_word_in_chapter_1777_of_the_2000_chapter_book(): Unit = runBlocking {
        val f = File("/sdcard/Books/generated/stress-2000-chapters.epub").also { check(it.isFile) { "corpus not pushed: $it" } }
        warAndPeace() // grants all-files access
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            h.ready(); h.open(f)
            awaitIndex(f)
            val results = h.searchDone("MARKERWORDMANY")
            assertEquals(1, results.size)
            assertTrue("chapter label: ${results.single().chapter}", results.single().chapter.orEmpty().contains("1777"))
            h.stop()
        }
    }

    @Test fun a_hit_in_the_preloaded_next_section_is_highlighted_after_the_swap(): Unit = runBlocking {
        val wp = warAndPeace()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            h.ready(); h.open(wp)
            awaitIndex(wp)
            val at = h.tocJump("BOOK ONE: 1805")
            h.lastPage()
            val next = at.sectionIndex + 1
            var preloaded = false
            repeat(100) { // the neighbour after the current section has a loaded document
                if (!preloaded) {
                    preloaded = JSONArray(sc.js("window.__readerTest.loaded()")).let { a -> (0 until a.length()).any { a.getInt(it) == next } }
                    if (!preloaded) delay(50)
                }
            }
            assertTrue("section $next preloaded", preloaded)
            // Do not wait for the whole (up to 5000) list: take the first hit in the next section as soon as it arrives.
            sc.onActivity { it.controller.searchFor("the") }
            val hit = withTimeout(10_000) {
                h.a.controller.searcher.state.first { st -> st.submitted == "the" && st.results.any { it.hit.spineIndex == next } }
            }.results.first { it.hit.spineIndex == next }
            h.select(hit) // S2: the decoration must be drawn in the section that was preloaded
            h.stop()
        }
    }
}
