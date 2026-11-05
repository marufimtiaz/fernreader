package app.epubreader.reader

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.epubreader.EpubReaderApp
import app.epubreader.MainActivity
import app.epubreader.core.data.Flow
import app.epubreader.core.data.ReaderSettings
import app.epubreader.core.data.ScrollChapters
import app.epubreader.core.data.SourceRef
import app.epubreader.core.epub.chunk.ChunkPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * C-085 with M8 (gatekeeper): in "Chapters: One at a time" the hidden parts of a huge document the app split are one
 * chapter: scrolling past a part boundary goes straight on (no stop, no Next/Previous control, no pull), and the
 * chapter's controls are only at the ends of the whole document. Book: stress-huge-single-chapter.epub (default policy).
 */
@RunWith(AndroidJUnit4::class)
class ChaptersOneAtATimeChunkedTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val dev = UiDevice.getInstance(inst)
    private val graph get() = (inst.targetContext.applicationContext as EpubReaderApp).graph

    private suspend fun ReaderHarness.edges(): JSONObject = sc.hook("edges()")
    /** Finger up by 60 % of the screen, slowly (the text follows the finger, no momentum; less than a screen). */
    private fun dragUp() = dev.swipe(dev.displayWidth / 2, dev.displayHeight * 4 / 5, dev.displayWidth / 2, dev.displayHeight / 5, 60)

    @Test fun scrolling_past_a_hidden_part_boundary_continues_without_a_stop(): Unit = runBlocking {
        warAndPeace() // grants all-files access
        val f = File("/sdcard/Books/generated/stress-huge-single-chapter.epub").also { check(it.isFile) { "corpus not pushed: $it" } }
        graph.opener.policy = ChunkPolicy()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready()
                sc.onActivity { it.controller.updateSettings { s -> s.copy(flow = Flow.SCROLLED, scrollChapters = ScrollChapters.ONE_AT_A_TIME) } }
                sc.onActivity { it.openBook(SourceRef.File(f.path)) }
                h.relocate(120_000); h.painted(60_000)
                withTimeout(30_000) { h.a.controller.state.first { !it.loading } }
                h.still(timeoutMs = 60_000); h.drain()
                android.util.Log.i("C085", "opened at ${h.at}")

                // the split document: its parts are one chapter (span over several sections)
                val first = h.at!!.sectionIndex
                var e = h.edges()
                val span = e.getJSONArray("span")
                val (from, to) = span.getInt(0) to span.getInt(1)
                assertTrue("the huge document is split into parts that form one chapter: $e", to > from)

                // the end of a part that is not the chapter's last: the next part is already below, no control between
                val part = if (first in from until to) first else from
                android.util.Log.i("C085", "span $from..$to, part $part: ${h.edges()}")
                if (h.at!!.sectionIndex != part) { h.sc.js("window.__readerTest.goIndex($part)"); h.relocate(30_000) { it.sectionIndex == part } }
                h.still(timeoutMs = 30_000)
                h.sc.js("window.__readerTest.lastPage()"); h.still(timeoutMs = 30_000)
                android.util.Log.i("C085", "at part end: ${h.edges()}")
                e = h.edges()
                assertEquals(part, e.getInt("current"))
                assertNull("no Next control at a hidden part boundary: $e", e.optJSONObject("foot"))
                assertTrue("not stopped: there is more to scroll below the part's end: $e",
                    e.getDouble("scrollTop") + e.getDouble("viewport") < e.getDouble("scrollHeight") - 50)
                suspend fun nextPartLoaded() = h.sc.hook("slots()").getJSONArray("slots").let { s -> (0 until s.length()).any { s.getJSONObject(it).optInt("index", -1) == part + 1 && s.getJSONObject(it).optBoolean("loaded") } }
                withTimeoutOrNull(10_000) { while (!nextPartLoaded()) delay(100) }
                assertTrue("the next part is laid out below: ${h.sc.hook("slots()")}", nextPartLoaded())

                // one ordinary drag goes on into the next part (no pull, no jump)
                h.drain()
                // (the part's last screen stays the current section until its top leaves the screen: two drags)
                repeat(2) { dragUp(); h.still(timeoutMs = 30_000) }
                val r = try { h.relocate(20_000) { it.sectionIndex != part } } catch (x: Exception) { throw AssertionError("no relocate out of part $part after the drags: ${h.edges()}", x) }
                assertEquals("the drags continued into the next part", part + 1, r.sectionIndex)
                h.still(timeoutMs = 30_000)
                e = h.edges()
                assertEquals(part + 1, e.getInt("current"))
                assertTrue("scrolled on, not opened at the next part's top: $e", e.getDouble("start") > 0 && e.getDouble("start") < e.getDouble("viewport"))
                e.optJSONObject("head")?.let { assertTrue("no control on screen in the middle of the chapter: $e", it.getDouble("y") + it.getDouble("h") <= 0) }
                assertNull("no Next control in the middle of the chapter: $e", e.optJSONObject("foot")?.takeIf { it.getDouble("y") < e.getDouble("vh") })

                // the chapter's own end: the control is after its last part only
                h.sc.js("window.__readerTest.goIndex($to)"); h.relocate(30_000) { it.sectionIndex == to }; h.still(timeoutMs = 30_000)
                h.sc.js("window.__readerTest.lastPage()"); h.still(timeoutMs = 30_000)
                e = h.edges()
                val linear = org.json.JSONArray(org.json.JSONTokener(h.sc.js("JSON.stringify(window.__readerTest.linear())")).nextValue() as String)
                val hasNext = (0 until linear.length()).any { linear.getInt(it) > to }
                if (hasNext) assertEquals("the Next control opens the section after the whole chapter: $e", (0 until linear.length()).map { linear.getInt(it) }.first { it > to }, e.getJSONObject("foot").getInt("index"))
                else assertNull("the book ends with this chapter: no Next: $e", e.optJSONObject("foot"))
            } finally {
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                h.a.settingsStored()
                h.stop()
            }
        }
    }
}
