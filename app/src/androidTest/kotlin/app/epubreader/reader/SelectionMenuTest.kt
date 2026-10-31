package app.epubreader.reader

import android.content.ClipboardManager
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.epubreader.EpubReaderApp
import app.epubreader.MainActivity
import app.epubreader.core.data.Flow
import app.epubreader.core.data.ReaderSettings
import app.epubreader.core.data.SourceRef
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.ui.Copy
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
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * F-060/F-051 selection menu on the device: a real long-press on a word (UiDevice, at the word's on-screen rectangle),
 * our items in the WebView's floating toolbar, Copy (exact text on the clipboard), Search in book, Highlight (stored row
 * and the overlay draws it), the colour row, and a tap that only dismisses the selection. War and Peace from the corpus.
 */
@RunWith(AndroidJUnit4::class)
class SelectionMenuTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val dev = UiDevice.getInstance(inst)
    private val graph get() = (inst.targetContext.applicationContext as EpubReaderApp).graph

    private suspend fun ActivityScenario<MainActivity>.jsValue(expr: String): Any? =
        JSONTokener(js("JSON.stringify($expr)")).nextValue().let { if (it is String) JSONTokener(it).nextValue() else null }

    /** Long-presses the first on-screen whole-word [word] for real and waits for the web side's settled selection. */
    private suspend fun ReaderHarness.longPress(word: String): FromWeb.Selection {
        val r = sc.jsValue("window.__readerTest.wordRect(${JSONObject.quote(word)})") as? JSONObject
        assertNotNull("\"$word\" on screen", r)
        r!!
        val loc = IntArray(2)
        sc.onActivity { it.reader.webView.getLocationOnScreen(loc) }
        val dpr = r.getDouble("dpr")
        val x = loc[0] + ((r.getDouble("x") + r.getDouble("w") / 2) * dpr).toInt()
        val y = loc[1] + ((r.getDouble("y") + r.getDouble("h") / 2) * dpr).toInt()
        drain()
        assertTrue(dev.swipe(x, y, x, y, 150)) // ~0.75 s without moving: a long-press
        return next(5_000) { e -> (e as? FromWeb.Selection)?.takeIf { it.cfi != null } }
    }

    /** One of our items in the floating toolbar (opening its overflow if the item is there). */
    private fun menuItem(text: String): UiObject2 {
        dev.wait(Until.findObject(By.text(text)), 1_500)?.let { return it }
        dev.findObject(By.desc("More options"))?.click()
        return dev.wait(Until.findObject(By.text(text)), 3_000) ?: error("menu item \"$text\" not found; screen: ${screenTexts()}")
    }

    private fun screenTexts() = dev.findObjects(By.textStartsWith("")).mapNotNull { it.text }.filter { it.isNotBlank() }

    private suspend fun ActivityScenario<MainActivity>.selectionText(): String? = jsValue("window.__readerTest.selectionText()") as? String

    private suspend fun ReaderHarness.quiet() { delay(800); drain(); settle() }

    private fun run(block: suspend CoroutineScope.(ReaderHarness, ActivityScenario<MainActivity>) -> Unit): Unit = runBlocking {
        val wp = warAndPeace()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val hr = ReaderHarness(sc, this)
            hr.ready()
            try {
                sc.onActivity { it.getSystemService(ClipboardManager::class.java).clearPrimaryClip() }
                hr.open(wp)
                hr.tocJump("CHAPTER I"); hr.quiet()
                block(hr, sc)
            } finally {
                dev.pressBack(); delay(300) // a menu or panel left open
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                delay(800)
                hr.stop()
            }
        }
    }

    @Test fun long_press_shows_our_items_first_and_copy_puts_the_exact_selection_on_the_clipboard(): Unit = run { hr, sc ->
        inst.uiAutomation.executeShellCommand("setprop log.tag.EPRSEL DEBUG").close()
        val sel = hr.longPress("Prince")
        assertNotNull(dev.wait(Until.findObject(By.text(Copy.selHighlight)), 5_000))
        val pageSelection = sc.selectionText()
        assertEquals("the web side reports the page's own selection string", pageSelection, sel.text)
        assertEquals("Prince", pageSelection!!.trim())
        // Screenshot of the open menu for the milestone note (pulled by the runner from the app's external files).
        delay(500)
        dev.takeScreenshot(File(inst.targetContext.getExternalFilesDir(null), "M6-selection.png"))
        val visible = screenTexts()
        Log.i("EPRSEL", "toolbar texts: $visible")
        // Ours, in order, before any system item; the WebView's own Select all / Web search are gone.
        val ours = listOf(Copy.selHighlight, Copy.selUnderline, Copy.selSearch, Copy.selCopy, Copy.selShare).filter { it in visible }
        assertTrue("at least Highlight and Underline in the toolbar: $visible", ours.size >= 2)
        assertFalse("no Select all: $visible", visible.any { it.equals("Select all", ignoreCase = true) })
        assertFalse("no Web search: $visible", visible.any { it.equals("Web search", ignoreCase = true) })
        assertFalse("selection still there with the menu open", sc.selectionText().isNullOrEmpty())
        menuItem(Copy.selCopy).click()
        val clip = withTimeout(5_000) {
            var t: String? = null
            while (t == null) {
                sc.onActivity { a -> t = a.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString() }
                if (t == null) delay(100)
            }
            t!!
        }
        assertEquals(pageSelection, clip)
        delay(500)
        assertNull("the selection is cleared after Copy", sc.selectionText())
        assertFalse(dev.hasObject(By.text(Copy.selHighlight)))
    }

    @Test fun search_in_book_runs_the_selected_word(): Unit = run { hr, sc ->
        val sel = hr.longPress("Prince")
        menuItem(Copy.selSearch).click()
        val st = withTimeout(30_000) { hr.a.controller.searcher.state.first { it.submitted == "Prince" && it.done && it.results.isNotEmpty() } }
        assertTrue(hr.a.controller.state.value.searchVisible)
        assertEquals(selectionQuery(sel.text), st.query)
        assertTrue("results: ${st.total}", st.total > 100)
    }

    @Test fun highlight_stores_a_row_draws_it_and_the_colour_row_recolours_it(): Unit = run { hr, sc ->
        val bookId = graph.library.knownBookId(SourceRef.File(warAndPeace().path))!!
        val before = graph.annotations.annotations(bookId).first().size
        val sel = hr.longPress("Prince")
        menuItem(Copy.selHighlight).click()
        val rows = withTimeout(5_000) { graph.annotations.annotations(bookId).first { it.size == before + 1 } }
        val a = rows.last { it.text == "Prince" }
        assertEquals(sel.cfi, a.cfi) // identity map until M8
        assertEquals(sel.sectionIndex, a.spineIndex)
        assertEquals("highlight", a.style)
        var drawn = false
        repeat(50) { if (!drawn) { drawn = sc.js("window.__readerTest.overlayCount(${sel.sectionIndex})").toInt() > 0; if (!drawn) delay(100) } }
        assertTrue("highlight drawn in section ${sel.sectionIndex}", drawn)
        withTimeout(3_000) { hr.a.controller.state.first { it.highlightPicker?.annotationId == a.id } }
        dev.wait(Until.findObject(By.desc(Copy.highlightColorName("green"))), 3_000)!!.click()
        withTimeout(5_000) { graph.annotations.annotations(bookId).first { l -> l.any { it.id == a.id && it.color == "green" } } }
        assertNull(hr.a.controller.state.value.highlightPicker)
        assertEquals("green", hr.a.controller.settings.value.highlightColor)
        graph.annotations.remove(a.id)
    }

    @Test fun a_tap_with_a_selection_only_dismisses_it(): Unit = run { hr, sc ->
        val from = hr.settle()
        hr.longPress("Prince")
        assertNotNull(dev.wait(Until.findObject(By.text(Copy.selHighlight)), 5_000))
        // A tap in the page's middle (would toggle the controls) while text is selected.
        val loc = IntArray(2); var w = 0; var h = 0
        sc.onActivity { val v = it.reader.webView; v.getLocationOnScreen(loc); w = v.width; h = v.height }
        dev.click(loc[0] + w / 2, loc[1] + (h * 0.8).toInt())
        hr.next(3_000) { e -> (e as? FromWeb.Selection)?.takeIf { it.cfi == null } } // the app heard it is gone
        delay(500)
        assertNull("selection gone", sc.selectionText())
        assertFalse("the controls stay hidden", hr.a.controller.state.value.chromeVisible)
        assertEquals("no page turn", from.cfi, hr.settle().cfi)
    }

    @Test fun scrolled_flow_long_press_and_copy(): Unit = run { hr, sc ->
        sc.onActivity { it.controller.updateSettings { s -> s.copy(flow = Flow.SCROLLED) } }
        hr.relocate(10_000); hr.quiet()
        val sel = hr.longPress("Prince")
        menuItem(Copy.selCopy).click()
        val clip = withTimeout(5_000) {
            var t: String? = null
            while (t == null) {
                sc.onActivity { a -> t = a.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString() }
                if (t == null) delay(100)
            }
            t!!
        }
        assertEquals(sel.text, clip)
    }
}
