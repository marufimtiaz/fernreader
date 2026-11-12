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
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * C-003 self-check (M9 Part B): two books whose shared batch documents counted differently from the paginator
 * (CorpusPageCountTest) count the sections that disagreed exactly as the reader turns them, at the default settings.
 */
@RunWith(AndroidJUnit4::class)
class CounterSelfCheckTest {
    @Test fun moby_dick_counts_equal_visible_pages() = check("moby-dick.epub", listOf(8, 22, 33))
    @Test fun jlreq_counts_equal_visible_pages() = check("jlreq-in-japanese.epub", listOf(4, 40, 68))

    private fun check(name: String, sections: List<Int>): Unit = runBlocking {
        warAndPeace() // storage access + corpus present
        val book = File("/sdcard/Books/epub3-samples/$name").also { check(it.isFile) { "corpus not pushed: $it" } }
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            sc.onActivity { it.controller.updateSettings { ReaderSettings(pageNumbers = app.epubreader.core.data.PageNumbers.SCREEN_BOOK) } } // C-108 (c): counted only then
            try {
                h.ready(); h.open(book, withToc = false)
                val t0 = System.currentTimeMillis()
                withTimeout(180_000) { h.a.controller.page.first { it.exact } }
                Log.i("EPR", "self-check $name exact total after ${System.currentTimeMillis() - t0} ms")
                val wrong = mutableListOf<String>()
                for (i in sections) {
                    h.drain()
                    sc.js("window.__readerTest.goIndex($i)")
                    h.relocate(15_000) { it.sectionIndex == i && it.pages > 0 }
                    var visible = -1
                    var tries = 0
                    while (tries++ < 30) { // two equal readings 150 ms apart (fonts and images settle after the first relocate)
                        val p = JSONObject(JSONTokener(sc.js("JSON.stringify(window.__readerTest.page())")).nextValue() as String).getInt("textPages")
                        if (p == visible) break
                        visible = p
                        delay(150)
                    }
                    val counted = h.a.controller.countedPages(i)
                    if (counted != visible) wrong += "section $i: counted $counted, visible $visible"
                }
                Log.i("EPR", "self-check $name mismatches: $wrong")
                assertEquals(emptyList<String>(), wrong)
            } finally { sc.onActivity { it.controller.updateSettings { ReaderSettings() } }; h.a.settingsStored(); h.stop() }
        }
    }
}
