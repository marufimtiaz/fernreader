package app.epubreader.reader

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.epubreader.MainActivity
import app.epubreader.core.data.Flow
import app.epubreader.core.data.ReaderSettings
import app.epubreader.ui.Copy
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern
import kotlin.math.abs

/**
 * F-034 / C-016 / C-017: after a text-size change, "page N", then "page N of ≈ M" (within 5 %), then the exact total.
 * C-112: the page shows no page number while the controls are hidden; the controls' bottom bar does.
 */
@RunWith(AndroidJUnit4::class)
class PageIndicatorTest {
    private val dev = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** Anything on screen that reads as the page indicator: its spoken "Page …" or its short text. */
    private fun pageNumbersOnScreen(short: String): List<String> {
        InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
        return (dev.findObjects(By.desc(Pattern.compile("^Page .*"))).map { "desc ${it.contentDescription}" } +
            dev.findObjects(By.text(short)).map { "text ${it.text}" })
    }

    /** C-112: in Pages and in Scroll, no page number on the page with the controls hidden; the controls show it. */
    @Test fun page_number_only_in_the_controls(): Unit = runBlocking {
        val wp = warAndPeace()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready(); h.open(wp)
                h.tocJump("BOOK ONE: 1805")
                for (flow in listOf(Flow.PAGED, Flow.SCROLLED)) {
                    h.change { it.copy(flow = flow) }
                    h.a.settingsStored()
                    h.quiet()
                    assertEquals("$flow: controls hidden", false, h.a.controller.state.value.chromeVisible)
                    val short = withTimeout(15_000) { h.a.controller.shownPage.first { Copy.pageLabelShort(it) != null } }.let { Copy.pageLabelShort(it)!! }
                    delay(500) // a line drawn with the page would be on screen by now
                    assertEquals("$flow: no page number on the page ($short)", emptyList<String>(), pageNumbersOnScreen(short))
                    sc.onActivity { it.controller.toggleChrome() }
                    val now = Copy.pageLabelShort(h.a.controller.shownPage.value)!!
                    assertNotNull("$flow: the controls show the page number $now", dev.wait(Until.findObject(By.text(now)), 5_000))
                    val spoken = Copy.pageLabel(h.a.controller.shownPage.value)!!
                    assertTrue("$flow: and say it ($spoken)", dev.hasObject(By.descContains(spoken)))
                    sc.onActivity { it.controller.toggleChrome() }
                    assertTrue("$flow: controls hidden again", dev.wait(Until.gone(By.text(now)), 5_000))
                }
            } finally { h.change { ReaderSettings() }; h.a.settingsStored(); h.stop() }
        }
    }

    @Test fun font_change_shows_page_then_estimate_then_exact(): Unit = runBlocking {
        val wp = warAndPeace()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready(); h.screenPages(); h.open(wp)
                h.tocJump("BOOK ONE: 1805")
                withTimeout(15_000) { while (h.a.controller.sectionStats == null) delay(50) } // first open of the book: extraction
                withTimeout(15_000) { h.a.controller.page.first { it.exact } } // the first layout counted
                h.a.forgetStoredCounts(wp) // the next layout is counted, as on its first use, not read back (C-108 c)
                val seen = mutableListOf<PageIndicator>()
                val watch = launch(start = CoroutineStart.UNDISPATCHED) { h.a.controller.page.collect { seen += it } }
                sc.onActivity { it.controller.updateSettings { s -> s.stepFont(+1) } }
                val exact = withTimeout(15_000) { h.a.controller.page.first { it.exact && it.total != null && seen.any { s -> !s.exact } } }
                delay(200); watch.cancel()
                Log.i("EPR", "page states: $seen")
                val fresh = seen.dropWhile { it.exact } // states of the new layout
                assertTrue("page number without a total first (C-017): $fresh", fresh.any { it.page != null && it.total == null })
                val estimate = fresh.firstOrNull { it.total != null && !it.exact }
                assertTrue("an estimate before the exact total: $fresh", estimate != null)
                val err = abs(estimate!!.total!! - exact.total!!).toDouble() / exact.total!!
                assertTrue("estimate ${estimate.total} vs exact ${exact.total}: ${"%.1f".format(err * 100)} %", err <= 0.05)
            } finally { h.change { ReaderSettings() }; h.a.settingsStored(); h.stop() }
        }
    }

    /** C-017 for vertical writing (F-081): the page number shows (the book-wide total, C-032, is checked in VerticalWritingTest). */
    @Test fun vertical_book_shows_a_page_number(): Unit = runBlocking {
        warAndPeace() // storage access
        val book = java.io.File("/sdcard/Books/epub3-samples/kusamakura-japanese-vertical-writing.epub")
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready(); h.open(book)
                repeat(3) { h.send(app.epubreader.reader.bridge.ToWeb.Next()); h.settle() }
                withTimeout(15_000) { while (h.a.controller.sectionStats == null) delay(50) }
                val p = withTimeout(5_000) { h.a.controller.page.first { it.page != null } }
                assertTrue("$p", p.page!! >= 1)
            } finally { h.stop() }
        }
    }

    private fun ReaderHarness.change(f: (ReaderSettings) -> ReaderSettings) = sc.onActivity { it.controller.updateSettings(f) }
}
