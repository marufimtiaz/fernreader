package app.epubreader.reader

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.epubreader.MainActivity
import app.epubreader.reader.bridge.ToWeb
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** P-026 / F-031 basis: neighbour sections are preloaded and boundary turns swap them in (War and Peace). */
@RunWith(AndroidJUnit4::class)
class ReaderBoundaryTest {
    private suspend fun ReaderHarness.loaded(): List<Int> = JSONArray(sc.js("window.__readerTest.loaded()")).let { a -> List(a.length()) { a.getInt(it) } }

    /** Waits until the neighbour after the current section has a loaded document. */
    private suspend fun ReaderHarness.awaitPreloaded(index: Int) {
        repeat(100) { if (index in loaded()) return; delay(50) }
        error("section $index not preloaded; loaded=${loaded()}")
    }

    @Test fun ten_boundaries_in_order(): Unit = runBlocking {
        val wp = warAndPeace()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            h.ready(); h.open(wp)
            var at = h.tocJump("BOOK ONE: 1805")
            repeat(10) {
                h.lastPage()
                h.awaitPreloaded(at.sectionIndex + 1)
                h.send(ToWeb.Next())
                val next = h.relocate(5_000) { it.sectionIndex != at.sectionIndex }
                assertEquals(at.sectionIndex + 1, next.sectionIndex)
                h.painted()
                at = h.settle()
            }
            h.stop()
        }
    }

    @Test fun decoration_in_preloaded_section_survives_swap(): Unit = runBlocking {
        val wp = warAndPeace()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            h.ready(); h.open(wp)
            val at = h.tocJump("BOOK ONE: 1805")
            h.lastPage()
            val next = at.sectionIndex + 1
            h.awaitPreloaded(next)
            val cfi = org.json.JSONTokener(sc.js("window.__readerTest.firstParagraphCfi($next)")).nextValue() as String
            sc.js("window.__readerTest.decorate(${org.json.JSONObject.quote(cfi)})")
            delay(300)
            assertTrue("drawn while preloaded", sc.js("window.__readerTest.overlayCount($next)").toInt() > 0)
            h.send(ToWeb.Next())
            h.relocate(5_000) { it.sectionIndex == next }
            h.painted()
            assertTrue("still drawn after the swap", sc.js("window.__readerTest.overlayCount($next)").toInt() > 0)
            h.stop()
        }
    }
}
