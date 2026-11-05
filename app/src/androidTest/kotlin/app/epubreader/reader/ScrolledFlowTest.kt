package app.epubreader.reader

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.epubreader.MainActivity
import app.epubreader.core.data.Flow
import app.epubreader.core.data.ReaderSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** F-031: in scrolled mode the next chapter follows the end of the current one without another gesture. */
@RunWith(AndroidJUnit4::class)
class ScrolledFlowTest {
    private val dev = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private suspend fun ReaderHarness.str(js: String) = JSONTokener(sc.js(js)).nextValue() as? String

    /** Scroll position inside the current section (CSS px). */
    private suspend fun ReaderHarness.offsetInCurrent(): Double {
        val st = JSONObject(JSONTokener(sc.js("JSON.stringify(window.__readerTest.slots())")).nextValue() as String)
        val slots = st.getJSONArray("slots")
        val cur = (0 until slots.length()).map { slots.getJSONObject(it) }.first { it.optInt("index", -1) == st.getInt("current") }
        return st.getDouble("scrollTop") - cur.getDouble("top")
    }

    private fun ReaderHarness.change(f: (ReaderSettings) -> ReaderSettings) = sc.onActivity { it.controller.updateSettings(f) }

    private suspend fun ReaderHarness.scrolledAt(label: String): Int {
        change { it.copy(flow = Flow.SCROLLED) }
        still()
        return tocJump(label).sectionIndex
    }

    /** Finger up by 60 % of the screen, slowly (no fling momentum, so the distance is the same on every run). */
    private fun dragUp() = dev.swipe(dev.displayWidth / 2, dev.displayHeight * 4 / 5, dev.displayWidth / 2, dev.displayHeight / 5, 60)

    @Test fun scrolling_past_chapter_end_continues_into_next_chapter(): Unit = runBlocking {
        val wp = warAndPeace()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready(); h.open(wp)
                val i = h.scrolledAt("BOOK ONE: 1805")
                h.lastPage(); h.still() // the chapter's last screen; the next chapter is laid out below it
                dragUp()
                h.still()
                // The same gesture went on into the next chapter: its start is on screen under the end of this one.
                val st = JSONObject(sc.js("JSON.stringify(window.__readerTest.slots())").let { JSONTokener(it).nextValue() as String })
                val slots = st.getJSONArray("slots")
                val next = (0 until slots.length()).map { slots.getJSONObject(it) }.first { it.optInt("index", -1) == i + 1 }
                assertTrue("next chapter not on screen: $st", next.getDouble("top") < st.getDouble("scrollTop") + st.getDouble("viewport"))
                dragUp()
                assertEquals(i + 1, h.relocate(4_000) { it.sectionIndex != i }.sectionIndex)
            } finally { h.change { ReaderSettings() }; h.a.settingsStored(); h.stop() }
        }
    }

    @Test fun scrolling_up_across_a_boundary_does_not_jump(): Unit = runBlocking {
        val wp = warAndPeace()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready(); h.open(wp)
                h.scrolledAt("BOOK ONE: 1805")
                val k = h.tocJump("CHAPTER II").sectionIndex // at the start of a section
                h.still()
                val before = h.str("window.__readerTest.firstVisibleBlock()")
                val inSection = h.offsetInCurrent()
                delay(2_000) // the previous section is inserted above meanwhile: what is on screen must not move (fixed: that window is the check)
                assertEquals(before, h.str("window.__readerTest.firstVisibleBlock()"))
                assertEquals(inSection, h.offsetInCurrent(), 2.0)
                // and it is really there: dragging down shows the end of the previous section. Relocates of section k
                // re-reported while the previous section was inserted above are still buffered: drop them, then wait for
                // the drag's own result (a real failure still fails: no relocate into k - 1 within the timeout).
                h.drain()
                dev.swipe(dev.displayWidth / 2, dev.displayHeight / 3, dev.displayWidth / 2, dev.displayHeight * 2 / 3, 40)
                val r = h.relocate(4_000) { it.sectionIndex == k - 1 }
                assertEquals(k - 1, r.sectionIndex)
            } finally { h.change { ReaderSettings() }; h.a.settingsStored(); h.stop() }
        }
    }
}
