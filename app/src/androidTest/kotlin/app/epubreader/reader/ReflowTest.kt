package app.epubreader.reader

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.epubreader.MainActivity
import app.epubreader.core.data.ReaderSettings
import app.epubreader.reader.bridge.ToWeb
import kotlinx.coroutines.runBlocking
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** F-070: every text-size step reflows live and keeps the first visible paragraph on screen (War and Peace). */
@RunWith(AndroidJUnit4::class)
class ReflowTest {
    private suspend fun ReaderHarness.str(js: String) = JSONTokener(sc.js(js)).nextValue() as? String

    private fun ReaderHarness.change(f: (ReaderSettings) -> ReaderSettings) = sc.onActivity { it.controller.updateSettings(f) }

    @Test fun each_font_step_keeps_first_paragraph(): Unit = runBlocking {
        val wp = warAndPeace()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready(); h.open(wp)
                h.change { it.copy(fontScale = ReaderSettings.MIN_FONT) }
                h.tocJump("BOOK ONE: 1805")
                repeat(3) { h.send(ToWeb.Next()); h.settle() }
                val block = h.str("window.__readerTest.firstVisibleBlock()")!!.take(40)
                val steps = List(18) { +1 } + List(18) { -1 }
                var scale = ReaderSettings.MIN_FONT
                for (d in steps) {
                    h.change { it.stepFont(d) }
                    scale = ReaderSettings(fontScale = scale).stepFont(d).fontScale
                    h.relocate(5_000); h.settle()
                    assertTrue("at ${scale}: '$block' not visible", sc.js("window.__readerTest.blockVisible(${org.json.JSONObject.quote(block)})") == "true")
                }
                assertEquals(ReaderSettings.MIN_FONT, scale, 1e-9)
            } finally {
                h.change { ReaderSettings() }
                h.stop()
            }
        }
    }

    @Test fun settings_change_reaches_preloaded_neighbour(): Unit = runBlocking {
        val wp = warAndPeace()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready(); h.open(wp)
                val at = h.tocJump("BOOK ONE: 1805")
                h.lastPage()
                val next = at.sectionIndex + 1
                repeat(100) { if (h.str("String(window.__readerTest.loaded().includes($next))") == "true") return@repeat; kotlinx.coroutines.delay(50) }
                h.change { it.copy(fontScale = 1.3) }
                h.settle()
                // the visible page reflows first; the hidden neighbour right after that paint
                repeat(20) { if (h.str("window.__readerTest.htmlVar($next, '--er-font-scale')") == "1.3") return@repeat; kotlinx.coroutines.delay(50) }
                assertEquals("1.3", h.str("window.__readerTest.htmlVar($next, '--er-font-scale')"))
                h.lastPage() // larger text: the chapter has more pages now
                h.send(ToWeb.Next())
                h.relocate(5_000) { it.sectionIndex == next }
                assertEquals("1.3", h.str("window.__readerTest.htmlVar($next, '--er-font-scale')"))
            } finally {
                h.change { ReaderSettings() }
                h.stop()
            }
        }
    }
}
