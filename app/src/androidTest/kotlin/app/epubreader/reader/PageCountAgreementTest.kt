package app.epubreader.reader

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.epubreader.MainActivity
import app.epubreader.core.data.ReaderSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** C-003: the counted pages of a section equal the pages the reader turns through (War and Peace, two text sizes). */
@RunWith(AndroidJUnit4::class)
class PageCountAgreementTest {
    @Test fun counted_pages_equal_visible_pages(): Unit = runBlocking {
        val wp = warAndPeace()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready(); h.open(wp)
                val linear = JSONArray(sc.js("JSON.stringify(window.__readerTest.linear())").let { org.json.JSONTokener(it).nextValue() as String })
                    .let { a -> List(a.length()) { a.getInt(it) } }
                val wrong = mutableListOf<String>()
                for (scale in listOf(1.0, 1.4)) {
                    sc.onActivity { it.controller.updateSettings { s -> s.copy(fontScale = scale) } }
                    delay(400)
                    withTimeout(20_000) { h.a.controller.page.first { it.exact } }
                    for (i in linear.filterIndexed { k, _ -> k % 8 == 3 }) {
                        h.drain()
                        sc.js("window.__readerTest.goIndex($i)")
                        h.relocate(5_000) { it.sectionIndex == i && it.pages > 0 }
                        // the section's own layout settles (fonts, images) a little after the first relocate
                        var visible = -1
                        var tries = 0
                        while (tries++ < 20) { // two equal readings 150 ms apart
                            delay(150)
                            val p = org.json.JSONObject(org.json.JSONTokener(sc.js("JSON.stringify(window.__readerTest.page())")).nextValue() as String).getInt("textPages")
                            if (p == visible) break
                            visible = p
                        }
                        val counted = h.a.controller.countedPages(i)
                        if (counted != visible) wrong += "font $scale section $i: counted $counted, visible $visible"
                    }
                }
                Log.i("EPR", "agreement mismatches: $wrong")
                assertEquals(emptyList<String>(), wrong)
            } finally { sc.onActivity { it.controller.updateSettings { ReaderSettings() } }; h.stop() }
        }
    }
}
