package app.epubreader.reader

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.epubreader.MainActivity
import app.epubreader.core.data.Align
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

/**
 * C-072: paged and scrolled flow render the same typography and, on a phone in portrait, the same text column, so a
 * paragraph breaks its lines at the same words. Checked on one War and Peace passage at the default settings and at
 * the user report's settings (justified, line spacing 1.5, margins 5 %).
 */
@RunWith(AndroidJUnit4::class)
class FlowTypographyTest {
    private val styleKeys = listOf("fontFamily", "fontSize", "fontWeight", "letterSpacing", "wordSpacing", "lineHeight", "textAlign",
        "rootTransform", "rootZoom", "frameTransform", "frameZoom", "textSizeAdjust")

    private suspend fun ReaderHarness.typography(index: Int): JSONObject {
        var last: JSONObject? = null
        repeat(20) { // two equal readings 150 ms apart (fonts settle after the relocate)
            delay(150)
            val t = JSONObject(JSONTokener(sc.js("JSON.stringify(window.__readerTest.typography($index))")).nextValue() as String)
            if (last?.toString() == t.toString()) return t
            last = t
        }
        return checkNotNull(last)
    }

    private suspend fun ReaderHarness.measure(flow: Flow, index: Int): JSONObject {
        sc.onActivity { it.controller.updateSettings { s -> s.copy(flow = flow) } }
        delay(800)
        drain()
        sc.js("window.__readerTest.goIndex($index)")
        relocate(10_000) { it.sectionIndex == index }
        return typography(index)
    }

    private fun lines(o: JSONObject) = o.getJSONArray("lines").let { a -> List(a.length()) { a.getString(it) } }

    @Test fun pages_and_scroll_give_the_same_typography_and_line_breaks(): Unit = runBlocking {
        val wp = warAndPeace()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready()
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                h.open(wp)
                val index = h.tocJump("BOOK ONE: 1805").sectionIndex + 1 // chapter 1: long paragraphs
                val problems = mutableListOf<String>()
                for ((name, settings) in listOf(
                    "defaults" to { s: ReaderSettings -> ReaderSettings(flow = s.flow) },
                    "justified, 1.5, 5 %" to { s: ReaderSettings -> ReaderSettings(flow = s.flow, align = Align.JUSTIFY, lineHeight = 1.5, marginPercent = 5) },
                )) {
                    sc.onActivity { it.controller.updateSettings(settings) }
                    val paged = h.measure(Flow.PAGED, index)
                    val scrolled = h.measure(Flow.SCROLLED, index)
                    Log.i("EPR", "C-072 $name paged=$paged")
                    Log.i("EPR", "C-072 $name scrolled=$scrolled")
                    for (k in styleKeys) if (paged.optString(k) != scrolled.optString(k)) problems += "$name $k: paged ${paged.optString(k)}, scrolled ${scrolled.optString(k)}"
                    for (k in listOf("columnWidth", "columnLeft")) {
                        val d = kotlin.math.abs(paged.getDouble(k) - scrolled.getDouble(k))
                        if (d > 1.0) problems += "$name $k: paged ${paged.getDouble(k)}, scrolled ${scrolled.getDouble(k)}"
                    }
                    if (kotlin.math.abs(paged.getDouble("wordWidth") - scrolled.getDouble("wordWidth")) > 0.1) problems += "$name word width differs (text autosizing?)"
                    val lp = lines(paged); val ls = lines(scrolled)
                    assertTrue("$name: a multi-line paragraph", lp.size >= 4)
                    if (lp != ls) problems += "$name line starts: paged $lp, scrolled $ls"
                }
                assertEquals(emptyList<String>(), problems)
            } finally {
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                h.stop()
            }
        }
    }
}
