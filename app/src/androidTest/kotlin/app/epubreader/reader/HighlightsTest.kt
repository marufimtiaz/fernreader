package app.epubreader.reader

import app.epubreader.settled
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
import app.epubreader.TestBooks
import app.epubreader.core.data.Annotation
import app.epubreader.core.data.ReaderSettings
import app.epubreader.core.data.SourceRef
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.ui.Copy
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
 * F-061/F-062 on the device, War and Peace chapter I: a highlight made by a real long-press and the selection menu,
 * two more (one an underline) through the controller; after +2 text-size steps the overlay still covers the same word;
 * reopened (cold) it is drawn again; a tap on it opens the sheet; colour and note are stored; the note shows in the
 * Highlights tab; a row jumps to it; Remove erases the drawing.
 */
@RunWith(AndroidJUnit4::class)
class HighlightsTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val dev = UiDevice.getInstance(inst)
    private val graph get() = (inst.targetContext.applicationContext as EpubReaderApp).graph

    private suspend fun ActivityScenario<MainActivity>.jsValue(expr: String): Any? =
        JSONTokener(js("JSON.stringify($expr)")).nextValue().let { if (it is String) JSONTokener(it).nextValue() else null }

    private data class Box(val x: Double, val y: Double, val w: Double, val h: Double) {
        fun intersects(o: Box) = x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h
        fun covers(o: Box, slack: Double = 1.5) = x <= o.x + slack && y <= o.y + slack && x + w >= o.x + o.w - slack && y + h >= o.y + o.h - slack
    }
    private data class Drawn(val index: Int, val text: String?, val range: Box?, val overlay: Box?, val dpr: Double, val vw: Double)
    private fun JSONObject.box(k: String) = optJSONObject(k)?.let { Box(it.getDouble("x"), it.getDouble("y"), it.getDouble("w"), it.getDouble("h")) }

    /** The annotation's drawing on the page (web probe), or null when it is not drawn in a loaded section. */
    private suspend fun ActivityScenario<MainActivity>.drawn(id: String): Drawn? =
        (jsValue("window.__readerTest.annotation(${JSONObject.quote(id)})") as? JSONObject)?.let {
            Drawn(it.getInt("index"), it.optString("text").takeIf { _ -> !it.isNull("text") }, it.box("range"), it.box("overlay"), it.getDouble("dpr"), it.getDouble("vw"))
        }

    private suspend fun ActivityScenario<MainActivity>.waitDrawn(id: String, ms: Long = 8_000, ok: (Drawn) -> Boolean = { it.text != null && it.overlay != null }): Drawn =
        withTimeout(ms) { while (true) { drawn(id)?.takeIf(ok)?.let { return@withTimeout it }; delay(100) }; error("unreachable") }

    private suspend fun ReaderHarness.quiet() { delay(800); drain(); settle() }

    private suspend fun rows(bookId: String) = graph.annotations.annotations(bookId).first()
    private suspend fun row(bookId: String, ok: (List<Annotation>) -> Boolean) = withTimeout(5_000) { graph.annotations.annotations(bookId).first(ok) }

    /** Taps the screen point at the centre of [b] (host CSS px). */
    private fun ActivityScenario<MainActivity>.tap(b: Box, dpr: Double) {
        val loc = IntArray(2)
        onActivity { it.reader.webView.getLocationOnScreen(loc) }
        dev.click(loc[0] + ((b.x + b.w / 2) * dpr).toInt(), loc[1] + ((b.y + b.h / 2) * dpr).toInt())
    }

    /** Long-presses the on-screen whole word [word] for real (as SelectionMenuTest) and waits for the settled selection. */
    private suspend fun ReaderHarness.longPress(word: String): FromWeb.Selection {
        val r = sc.jsValue("window.__readerTest.wordRect(${JSONObject.quote(word)})") as? JSONObject
        assertNotNull("\"$word\" on screen", r)
        val dpr = r!!.getDouble("dpr")
        val loc = IntArray(2)
        sc.onActivity { it.reader.webView.getLocationOnScreen(loc) }
        drain()
        dev.swipe(loc[0] + ((r.getDouble("x") + r.getDouble("w") / 2) * dpr).toInt(), loc[1] + ((r.getDouble("y") + r.getDouble("h") / 2) * dpr).toInt(),
            loc[0] + ((r.getDouble("x") + r.getDouble("w") / 2) * dpr).toInt(), loc[1] + ((r.getDouble("y") + r.getDouble("h") / 2) * dpr).toInt(), 150)
        return next(5_000) { e -> (e as? FromWeb.Selection)?.takeIf { it.cfi != null } }
    }

    /** Selects [word] programmatically and makes it an annotation through the controller (fast path). */
    private suspend fun ReaderHarness.annotate(bookId: String, word: String, color: String, underline: Boolean): Annotation {
        drain()
        assertEquals("true", sc.js("window.__readerTest.selectWord(${JSONObject.quote(word)})"))
        next(5_000) { e -> (e as? FromWeb.Selection)?.takeIf { it.cfi != null } }
        val n = rows(bookId).size
        sc.onActivity { if (underline) it.controller.underlineSelection() else it.controller.highlightSelection() }
        val a = row(bookId) { it.size == n + 1 }.first { it.text == word }
        sc.onActivity { it.controller.recolorHighlight(color) }
        return row(bookId) { l -> l.any { it.id == a.id && it.color == color } }.first { it.id == a.id }
    }

    private fun menuItem(text: String): UiObject2 {
        dev.settled(By.text(text), 1_500)?.let { return it }
        dev.settled(By.desc("More options"), 1_000)?.click()
        return dev.settled(By.text(text), 3_000) ?: error("menu item \"$text\" not found")
    }

    private fun shot(name: String) = dev.takeScreenshot(File(inst.targetContext.getExternalFilesDir(null), name))

    @Test fun highlight_survives_reflow_and_reopen_sheet_note_list_jump_remove(): Unit = runBlocking {
        val wp = warAndPeace()
        val other = TestBooks.write(inst.targetContext, "hl-other-${System.nanoTime()}.epub", TestBooks.epub(id = "urn:uuid:hl-${System.nanoTime()}", chapters = 2, paragraphs = 10))
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val hr = ReaderHarness(sc, this)
            hr.ready()
            val c = hr.a.controller
            try {
                hr.open(wp)
                hr.tocJump("CHAPTER I"); hr.quiet()
                val bookId = graph.library.knownBookId(SourceRef.File(wp.path))!!
                for (a in rows(bookId)) graph.annotations.remove(a.id) // a clean book
                delay(300)

                // 1. A real long-press + the menu's Highlight; then a green highlight and a blue underline.
                val sel = hr.longPress("Prince")
                menuItem(Copy.selHighlight).click()
                val prince = row(bookId) { l -> l.any { it.text == "Prince" } }.first { it.text == "Prince" }
                assertEquals(sel.cfi, prince.cfi)
                withTimeout(3_000) { c.state.first { it.highlightPicker != null } }
                sc.onActivity { it.controller.dismissHighlightPicker() }
                val genoa = hr.annotate(bookId, "Genoa", "green", underline = false)
                val lucca = hr.annotate(bookId, "Lucca", "blue", underline = true)
                val before = sc.waitDrawn(prince.id)
                sc.waitDrawn(genoa.id); sc.waitDrawn(lucca.id)
                assertEquals("Prince", before.text)
                assertTrue("overlay ${before.overlay} on the word ${before.range}", before.overlay!!.intersects(before.range!!))
                assertTrue("overlay covers the word", before.overlay.covers(before.range))
                delay(600); shot("M6-highlights.png")

                // 2. Text size +2 steps: after the re-layout the drawing still covers the same word (and moved with it).
                hr.drain()
                sc.onActivity { it.controller.updateSettings { s -> s.stepFont(2) } }
                hr.relocate(10_000); hr.quiet(); delay(800)
                val after = sc.waitDrawn(prince.id)
                assertEquals("Prince", after.text)
                Log.i("HLT", "before ${before.range} after ${after.range} overlay ${after.overlay}")
                assertTrue("the word moved with the larger text: ${before.range} -> ${after.range}", after.range!!.h > before.range.h)
                assertTrue("overlay ${after.overlay} still on the word ${after.range}", after.overlay!!.intersects(after.range) && after.overlay.covers(after.range))
                sc.onActivity { it.controller.updateSettings { s -> s.stepFont(-2) } }
                hr.relocate(10_000); hr.quiet()

                // 3. Reopen (cold: another book in between, so the kept view is gone): drawn again from the database.
                hr.open(other)
                hr.open(wp); hr.quiet()
                val reopened = sc.waitDrawn(prince.id, 10_000)
                assertEquals("Prince", reopened.text)
                sc.waitDrawn(lucca.id)

                // 4. A tap on it opens the sheet (no controls, no page turn); colour and note are stored.
                val at = hr.settle()
                hr.drain()
                sc.tap(reopened.range!!, reopened.dpr)
                withTimeout(5_000) { c.state.first { it.annotationSheet?.id == prince.id } }
                assertTrue(dev.wait(Until.hasObject(By.desc(Copy.highlightSheet)), 3_000))
                assertFalse(c.state.value.chromeVisible)
                assertEquals(at.cfi, hr.settle().cfi)
                dev.findObject(By.desc(Copy.highlightColorName("pink"))).click()
                row(bookId) { l -> l.any { it.id == prince.id && it.color == "pink" } }
                val field = dev.wait(Until.findObject(By.clazz("android.widget.EditText")), 3_000)
                assertNotNull("note field", field)
                field!!.text = "Remember the salon"
                withTimeout(3_000) { c.state.first { it.annotationSheet?.note == "Remember the salon" } }
                dev.pressBack() // the keyboard, if it is up
                delay(300)
                if (c.state.value.annotationSheet != null) dev.findObject(By.text(Copy.done))?.click() ?: dev.pressBack()
                withTimeout(3_000) { c.state.first { it.annotationSheet == null } }
                row(bookId) { l -> l.any { it.id == prince.id && it.note == "Remember the salon" } }

                // 5. The Highlights tab: book order, the note shown; a row jumps there from another chapter.
                hr.tocJump("CHAPTER III"); hr.quiet()
                assertNull("not drawn on this page", sc.drawn(prince.id)?.takeIf { it.overlay != null && it.overlay.x >= 0 && it.overlay.x < it.vw })
                sc.onActivity { it.controller.showToc(); it.controller.selectContentsTab(ContentsTab.Highlights) }
                assertTrue("note in the list", dev.wait(Until.hasObject(By.text("Remember the salon")), 5_000))
                assertEquals(listOf(prince.id, genoa.id, lucca.id), c.state.value.annotations.map { it.id }) // book order
                assertEquals(3, dev.findObjects(By.desc(Copy.removeHighlightFromList)).size)
                delay(500); shot("M6-annotations.png")
                hr.drain()
                dev.findObject(By.text("Remember the salon")).click()
                hr.relocate(10_000)
                withTimeout(5_000) { c.state.first { !it.tocVisible } }
                hr.quiet()
                val landed = sc.waitDrawn(prince.id)
                assertTrue("on the page now: ${landed.overlay}", landed.overlay!!.x >= 0 && landed.overlay.x + landed.overlay.w <= landed.vw)

                // 6. Remove from the sheet: the drawing goes at once; the row is tombstoned.
                sc.tap(landed.range!!, landed.dpr)
                withTimeout(5_000) { c.state.first { it.annotationSheet?.id == prince.id } }
                dev.wait(Until.findObject(By.text(Copy.removeHighlight)), 3_000)!!.click()
                withTimeout(5_000) { while (sc.drawn(prince.id) != null) delay(100) }
                row(bookId) { l -> l.none { it.id == prince.id } }
                assertEquals(Notice.HighlightRemoved, c.state.value.notice)
                assertNotNull(sc.drawn(genoa.id))
            } finally {
                dev.pressBack(); delay(300)
                graph.library.knownBookId(SourceRef.File(wp.path))?.let { id -> for (a in rows(id)) graph.annotations.remove(a.id) }
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                delay(800)
                hr.stop()
            }
        }
    }
}
