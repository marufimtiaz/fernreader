package app.epubreader.reader

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.epubreader.MainActivity
import app.epubreader.core.data.ReaderSettings
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.reader.bridge.ToWeb
import app.epubreader.ui.Copy
import app.epubreader.ui.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * F-042 internal links and Back, F-043 footnote popups, F-044 external links, with the corpus book footnotes-links.epub
 * (bench/push_corpus.sh). Links are tapped for real with UiDevice at their on-screen position.
 */
@RunWith(AndroidJUnit4::class)
class LinksFootnotesTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val dev = UiDevice.getInstance(inst)

    private fun book(): File {
        inst.uiAutomation.executeShellCommand("appops set ${inst.targetContext.packageName} MANAGE_EXTERNAL_STORAGE allow").close()
        return File("/sdcard/Books/generated/footnotes-links.epub").also { check(it.isFile) { "corpus not pushed: $it" } }
    }

    private suspend fun ActivityScenario<MainActivity>.jsValue(expr: String): Any? =
        JSONTokener(js("JSON.stringify($expr)")).nextValue().let { if (it is String) JSONTokener(it).nextValue() else null }

    private suspend fun ActivityScenario<MainActivity>.footnoteText(): String? = jsValue("window.__readerTest.footnote()") as? String


    /** Opens the book at its first chapter (a stored position from an earlier test is not used) and returns the place. */
    private suspend fun ReaderHarness.openAtStart(): FromWeb.Relocate {
        open(book())
        tocJump("Notes Test")
        return settle()
    }

    /**
     * Taps the link whose text is [text] at its screen position, turning pages until it is fully on screen; returns the
     * location the tap was made from.
     */
    private suspend fun ReaderHarness.tapLink(text: String): FromWeb.Relocate {
        repeat(15) {
            val r = sc.jsValue("window.__readerTest.linkRect(${JSONObject.quote(text)})") as? JSONObject
            if (r != null) {
                val x = r.getDouble("x"); val y = r.getDouble("y"); val w = r.getDouble("w"); val h = r.getDouble("h")
                if (x >= 0 && y >= 0 && x + w <= r.getDouble("vw") && y + h <= r.getDouble("vh")) {
                    val from = settle()
                    val loc = IntArray(2)
                    sc.onActivity { it.reader.webView.getLocationOnScreen(loc) }
                    val dpr = r.getDouble("dpr")
                    assertTrue(dev.click(loc[0] + ((x + w / 2) * dpr).toInt(), loc[1] + ((y + h / 2) * dpr).toInt()))
                    return from
                }
            }
            send(ToWeb.Next()); quiet()
        }
        fail("link \"$text\" never came on screen")
        error("unreachable")
    }

    private suspend fun ReaderHarness.state(ok: (ReaderUiState) -> Boolean) = withTimeout(5_000) { a.controller.state.first(ok) }

    private fun run(block: suspend CoroutineScope.(ReaderHarness, ActivityScenario<MainActivity>) -> Unit): Unit = runBlocking {
        book()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val hr = ReaderHarness(sc, this)
            hr.ready()
            try {
                block(hr, sc)
            } finally {
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                hr.a.settingsStored()
                hr.stop()
            }
        }
    }

    @Test fun note_5_opens_a_popup_and_back_closes_it_on_the_same_page(): Unit = run { hr, sc ->
        hr.openAtStart()
        val from = hr.tapLink("5")
        hr.state { it.footnoteOpen }
        assertTrue("popup text: ${sc.footnoteText()}", sc.footnoteText()!!.contains("Footnote number 5"))
        assertFalse("a tap on a link does not show the controls", hr.a.controller.state.value.chromeVisible)
        dev.pressBack()
        hr.state { !it.footnoteOpen }
        assertNull(sc.footnoteText())
        assertEquals(Route.Reader, hr.a.nav.top)
        assertEquals(from.cfi, hr.settle().cfi)
    }

    @Test fun the_aside_note_opens_a_popup_and_a_tap_outside_closes_it(): Unit = run { hr, sc ->
        hr.openAtStart()
        val from = hr.tapLink("*")
        hr.state { it.footnoteOpen }
        assertTrue("popup text: ${sc.footnoteText()}", sc.footnoteText()!!.contains("This is an aside footnote in the same file."))
        dev.click(dev.displayWidth / 2, (dev.displayHeight * 0.08).toInt()) // above the centred panel
        hr.state { !it.footnoteOpen }
        assertNull(sc.footnoteText())
        assertEquals(from.cfi, hr.settle().cfi)
        assertFalse(hr.a.controller.state.value.chromeVisible)
    }

    @Test fun a_link_jumps_to_its_target_and_back_returns_to_the_origin_then_leaves(): Unit = run { hr, sc ->
        hr.openAtStart()
        val from = hr.tapLink("the target in chapter two")
        val landed = hr.relocate { it.sectionIndex == 1 }
        hr.settle()
        assertEquals(true, sc.jsValue("window.__readerTest.blockVisible('This is the link target paragraph.')"))
        assertEquals(1, sc.jsValue("window.__readerTest.historyDepth()"))
        dev.pressBack()
        val back = hr.relocate { it.sectionIndex == 0 }
        assertEquals("Back returns to the exact origin (landed at ${landed.cfi})", from.cfi, back.cfi)
        assertEquals(Route.Reader, hr.a.nav.top)
        dev.pressBack()
        withTimeout(2_000) { while (hr.a.nav.top != Route.Library) delay(20) }
        assertFalse(hr.a.controller.state.value.open)
    }

    @Test fun an_external_link_asks_naming_the_host_and_cancel_stays(): Unit = run { hr, _ ->
        hr.openAtStart()
        val from = hr.tapLink("external website")
        val title = Copy.openLinkTitle("www.gutenberg.org", mail = false)
        assertTrue("confirmation naming the host", dev.wait(Until.hasObject(By.text(title)), 5_000))
        assertNotNull(hr.a.controller.state.value.externalLink)
        dev.findObject(By.text(Copy.cancel)).click()
        hr.state { it.externalLink == null }
        assertFalse(dev.wait(Until.hasObject(By.text(title)), 1_000))
        assertEquals(Route.Reader, hr.a.nav.top)
        assertEquals(from.cfi, hr.settle().cfi)
    }

    /** Task 3 swipes: a swipe that starts on a link is a page turn, not a link tap. */
    @Test fun a_swipe_starting_on_a_link_turns_the_page(): Unit = run { hr, sc ->
        hr.openAtStart()
        var r: JSONObject? = null
        repeat(15) {
            r = (sc.jsValue("window.__readerTest.linkRect('the target in chapter two')") as? JSONObject)?.takeIf { it.getDouble("x") >= 0 && it.getDouble("x") < it.getDouble("vw") }
            if (r != null) return@repeat
            hr.send(ToWeb.Next()); hr.quiet()
        }
        val rect = r ?: error("link never on screen")
        val before = sc.hook("page()")
        val loc = IntArray(2)
        sc.onActivity { it.reader.webView.getLocationOnScreen(loc) }
        val dpr = rect.getDouble("dpr")
        val y = loc[1] + ((rect.getDouble("y") + rect.getDouble("h") / 2) * dpr).toInt()
        val x = loc[0] + ((rect.getDouble("x") + rect.getDouble("w") / 2) * dpr).toInt()
        dev.swipe(x, y, (dev.displayWidth * 0.05).toInt(), y, 10)
        hr.quiet()
        val after = sc.hook("page()")
        assertFalse(hr.a.controller.state.value.footnoteOpen)
        assertEquals(0, sc.jsValue("window.__readerTest.historyDepth()"))
        assertTrue("page moved on: $before -> $after",
            after.getInt("index") > before.getInt("index") || after.getInt("page") == before.getInt("page") + 1)
    }
}
