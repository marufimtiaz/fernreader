package app.epubreader.reader

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.epubreader.MainActivity
import app.epubreader.core.data.ReaderSettings
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** F-034 / C-016 / C-017: after a text-size change, "page N", then "page N of ≈ M" (within 5 %), then the exact total. */
@RunWith(AndroidJUnit4::class)
class PageIndicatorTest {
    @Test fun font_change_shows_page_then_estimate_then_exact(): Unit = runBlocking {
        val wp = warAndPeace()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready(); h.open(wp)
                h.tocJump("BOOK ONE: 1805")
                withTimeout(15_000) { while (h.a.controller.sectionStats == null) delay(50) } // first open of the book: extraction
                withTimeout(15_000) { h.a.controller.page.first { it.exact } } // the first layout counted
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
            } finally { h.change { ReaderSettings() }; h.stop() }
        }
    }

    private fun ReaderHarness.change(f: (ReaderSettings) -> ReaderSettings) = sc.onActivity { it.controller.updateSettings(f) }
}
