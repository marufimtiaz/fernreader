package app.epubreader.reader

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.epubreader.MainActivity
import app.epubreader.TestBooks
import app.epubreader.core.data.Flow
import app.epubreader.core.data.ReaderSettings
import app.epubreader.core.data.ScrollChapters
import app.epubreader.reader.bridge.ToWeb
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import kotlin.math.ceil

/**
 * C-099: fullscreen on a screen with a camera cutout, the emulator's emulated "tall" cutout. In Scroll the page reaches the
 * top of the screen and what comes to rest sits below the cutout; Pages keep their band. The overlay is put back after
 * the class.
 */
@RunWith(AndroidJUnit4::class)
class CutoutScrollTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val dev = UiDevice.getInstance(inst)

    companion object {
        private const val OVERLAY = "com.android.internal.display.cutout.emulation.tall"
        private fun shell(cmd: String): String {
            val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
            return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().decodeToString() }.trim()
        }
        private fun cutoutShown() = Regex("""DisplayCutout\{insets=Rect\(0, [1-9]""").containsMatchIn(shell("dumpsys display"))
        private fun overlayIs(on: Boolean) {
            shell("cmd overlay ${if (on) "enable" else "disable"} $OVERLAY")
            val end = System.currentTimeMillis() + 10_000
            while (cutoutShown() != on && System.currentTimeMillis() < end) Thread.sleep(100)
            assertEquals("the display's cutout", on, cutoutShown())
        }
        // Once for the class, with no activity running: an overlay change recreates activities and upsets the system for a while.
        @BeforeClass @JvmStatic fun cutoutOn() = overlayIs(true)
        @AfterClass @JvmStatic fun cutoutOff() = overlayIs(false)
    }

    @get:Rule val name = TestName()

    private data class Band(val px: Int, val css: Int)
    private fun MainActivity.band(): Band {
        val px = ViewCompat.getRootWindowInsets(window.decorView)?.getInsets(WindowInsetsCompat.Type.displayCutout())?.top ?: 0
        return Band(px, ceil(px / resources.displayMetrics.density).toInt())
    }

    private suspend fun ReaderHarness.cutout(): JSONObject = sc.hook("cutout()")
    private suspend fun ReaderHarness.h1Top(index: Int): Double = sc.js("window.__readerTest.blockTop($index, 'h1')").toDouble()
    private fun ReaderHarness.change(f: (ReaderSettings) -> ReaderSettings) = sc.onActivity { it.controller.updateSettings(f) }
    private fun dragUp() = dev.swipe(dev.displayWidth / 2, dev.displayHeight * 4 / 5, dev.displayWidth / 2, dev.displayHeight / 3, 60)

    private fun reading(s: ReaderSettings, body: suspend (ReaderHarness, Band) -> Unit) = runBlocking {
        val f = TestBooks.write(inst.targetContext, "cutout-${name.methodName}.epub", TestBooks.epub(id = "urn:uuid:cut-${System.nanoTime()}", chapters = 3, paragraphs = 60))
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready()
                h.change { _ -> s }
                h.open(f)
                val end = SystemClock.uptimeMillis() + 10_000
                var band = h.a.band()
                while ((band.px == 0 || h.cutout().getInt("inset") != (if (s.fullscreen) band.css else 0)) && SystemClock.uptimeMillis() < end) {
                    delay(50)
                    band = h.a.band()
                }
                assertTrue("window cutout $band, page ${h.cutout()}", band.px > 0)
                assertEquals("the band the page was given", if (s.fullscreen) band.css else 0, h.cutout().getInt("inset"))
                h.still()
                body(h, band)
            } finally {
                h.change { ReaderSettings() }
                h.a.settingsStored()
                h.stop()
            }
        }
    }

    private val scroll = ReaderSettings(flow = Flow.SCROLLED, fullscreen = true)
    private val pages = ReaderSettings(flow = Flow.PAGED, fullscreen = true)
    private val oneAtATime = scroll.copy(scrollChapters = ScrollChapters.ONE_AT_A_TIME)

    private fun assertBelow(what: String, band: Band, top: Double, dpr: Double) =
        assertTrue("$what: top $top css x $dpr is under the cutout of ${band.px} px", top * dpr >= band.px)

    @Test fun the_top_of_a_chapter_rests_below_the_cutout() = reading(scroll) { h, band ->
        val dpr = h.cutout().getDouble("dpr")
        assertBelow("the book's first chapter", band, h.h1Top(0), dpr)
        h.tocJump("Chapter 2"); h.still()
        assertBelow("a later chapter", band, h.h1Top(1), dpr)
    }

    @Test fun one_at_a_time_rests_below_the_cutout() = reading(oneAtATime) { h, band ->
        val dpr = h.cutout().getDouble("dpr")
        assertBelow("the book's first chapter", band, h.h1Top(0), dpr)
        h.tocJump("Chapter 2"); h.still()
        assertBelow("a later chapter", band, h.h1Top(1), dpr)
        val e = h.sc.hook("edges()")
        assertEquals("the scroller stays under the head control's room", band.css, e.getInt("inset"))
        h.sc.js("window.__readerTest.goIndex(2)"); h.still()
        assertBelow("the next chapter", band, h.h1Top(2), dpr)
    }

    @Test fun no_empty_band_while_scrolling() = reading(scroll) { h, _ ->
        var y = -1
        h.sc.onActivity { a -> IntArray(2).also(a.reader.webView::getLocationOnScreen).let { y = it[1] } }
        assertEquals("the WebView reaches the top of the screen", 0, y)
        assertEquals("the scroller too", 0.0, h.cutout().getDouble("containerTop"), 0.5)
        dragUp(); h.still()
        val c = h.cutout()
        assertTrue("text in the band: $c", c.getString("bandText").isNotEmpty())
    }

    @Test fun a_jump_lands_below_the_cutout() = reading(scroll) { h, band ->
        val dpr = h.cutout().getDouble("dpr")
        dragUp(); dragUp(); h.still()
        val mid = h.quiet()
        h.tocJump("Chapter 3"); h.still()
        h.send(ToWeb.GoTo(target = mid.cfi, id = 7_001))
        h.next { e -> (e as? app.epubreader.reader.bridge.FromWeb.Seeked)?.takeIf { it.id == 7_001 } }
        h.still()
        val c = h.cutout()
        assertBelow("the landing", band, c.getDouble("positionTop"), dpr)
        assertEquals("the same chapter", mid.sectionIndex, h.at!!.sectionIndex)
    }

    @Test fun pages_keep_their_band() = reading(pages) { h, band ->
        val c = h.cutout()
        assertBelow("the page", band, c.getDouble("containerTop"), c.getDouble("dpr"))
        assertEquals("nothing in the band", "", c.getString("bandText"))
        assertBelow("the first line", band, h.h1Top(0), c.getDouble("dpr"))
    }

    private suspend fun ReaderHarness.exactPage() = withTimeout(30_000) { a.controller.page.first { it.exact && it.total != null } }

    /** C-002, C-003: the counter's page is the same size in both flows, so the numbers are; F-037: so is the place. */
    @Test fun a_mode_switch_keeps_the_place_and_the_numbers() = reading(scroll.copy(pageNumbers = app.epubreader.core.data.PageNumbers.SCREEN_BOOK)) { h, _ ->
        dragUp(); dragUp(); h.still()
        val before = h.quiet()
        val shown = h.exactPage()
        val height = h.cutout().getDouble("pagedHeight")
        h.change { it.copy(flow = Flow.PAGED) }; h.still()
        val paged = h.quiet()
        val shownPaged = h.exactPage()
        assertEquals("the page counter's page", height, h.cutout().getDouble("pagedHeight"), 0.5)
        assertEquals(before.sectionIndex, paged.sectionIndex)
        assertEquals("the total", shown.total, shownPaged.total)
        assertEquals("the page", shown.page, shownPaged.page)
        h.change { it.copy(flow = Flow.SCROLLED) }; h.still()
        val back = h.quiet()
        assertEquals(before.sectionIndex, back.sectionIndex)
        assertEquals("back at the same place", before.fraction, back.fraction, 0.01)
        assertEquals("the total", shown.total, h.exactPage().total)
    }

    @Test fun without_fullscreen_nothing_changes() = reading(scroll.copy(fullscreen = false)) { h, _ ->
        var y = -1
        h.sc.onActivity { a -> IntArray(2).also(a.reader.webView::getLocationOnScreen).let { y = it[1] } }
        assertNotEquals("the WebView stays below the bars", 0, y)
        assertEquals(0, h.cutout().getInt("inset"))
    }
}
