package app.fernreader.reader

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.fernreader.EpubReaderApp
import app.fernreader.MainActivity
import app.fernreader.settled
import app.fernreader.core.data.Flow
import app.fernreader.core.data.ReaderSettings
import app.fernreader.core.data.ReaderSheetTab
import app.fernreader.core.data.ScrollChapters
import app.fernreader.reader.bridge.FromWeb
import app.fernreader.ui.Copy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * C-085: scroll mode with "Chapters: One at a time". Scrolling (flicks, not only slow drags) stops at the chapter's last
 * line, with "Next: <title>" below it; the button and a deliberate overscroll pull open the next chapter at its top;
 * backwards, "Previous: <title>" and a pull open the previous chapter at its end; the book's first and last chapters have
 * no control on that side; Continuous has none at all. The display panel shows the setting only while the book scrolls,
 * and it is stored. Probe: `window.__readerTest.edges()` (the paginator's `debugEdges`).
 */
@RunWith(AndroidJUnit4::class)
class ChaptersOneAtATimeTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val dev = UiDevice.getInstance(inst)
    private val graph get() = (inst.targetContext.applicationContext as EpubReaderApp).graph

    // ---- probes ----

    private suspend fun ReaderHarness.edges(): JSONObject = sc.hook("edges()")
    private fun JSONObject.atEnd() = getDouble("scrollTop") + getDouble("viewport") >= getDouble("scrollHeight") - 2
    private fun JSONObject.atTop() = getDouble("scrollTop") <= 2
    /** The current section's first line is at the top of the viewport. */
    private fun JSONObject.sectionTop() = abs(getDouble("start")) < 2
    /** The current section's last screen is shown (its last line at the bottom). */
    private fun JSONObject.sectionEnd() = abs(getDouble("start") + getDouble("viewport") - getDouble("viewSize")) < 2
    private fun JSONObject.edge(which: String): JSONObject? = optJSONObject(which)

    /** What the controller's contents call section [index] (the label the web side builds from the same list). */
    private fun ReaderHarness.titleOf(index: Int): String {
        val toc = a.controller.state.value.toc.filter { it.label.isNotBlank() && it.index >= 0 }
        return (toc.firstOrNull { it.index == index } ?: toc.last { it.index < index }).label.trim()
    }

    private fun ReaderHarness.change(f: (ReaderSettings) -> ReaderSettings) = sc.onActivity { it.controller.updateSettings(f) }

    /** Scroll mode, One at a time, at the Contents entry [label]; returns its section. */
    private suspend fun ReaderHarness.oneAtATimeAt(label: String): Int {
        change { it.copy(flow = Flow.SCROLLED, scrollChapters = ScrollChapters.ONE_AT_A_TIME) }
        still()
        val i = tocJump(label).sectionIndex
        still(); drain()
        return i
    }

    private val x get() = dev.displayWidth / 2
    /** A quick flick: the finger moves 15 % of the screen (less than a pull), the text runs on by momentum. */
    private fun flickUp() = dev.swipe(x, dev.displayHeight * 55 / 100, x, dev.displayHeight * 40 / 100, 4)
    private fun flickDown() = dev.swipe(x, dev.displayHeight * 40 / 100, x, dev.displayHeight * 55 / 100, 4)
    /** A deliberate pull: the finger moves slowly over 55 % of the screen. */
    private fun pullUp() = dev.swipe(x, dev.displayHeight * 4 / 5, x, dev.displayHeight / 4, 80)
    private fun pullDown() = dev.swipe(x, dev.displayHeight / 4, x, dev.displayHeight * 4 / 5, 80)

    private suspend fun ReaderHarness.flickTo(end: Boolean): JSONObject {
        repeat(120) {
            val e = edges()
            if (if (end) e.atEnd() else e.atTop()) return e
            if (end) flickUp() else flickDown()
            delay(600) // a finger's pace: the next flick comes during the run-out of this one, as a user's does
        }
        fail("never reached the chapter's ${if (end) "end" else "top"}: ${edges()}"); error("unreachable")
    }

    /** Taps the edge control [which] ("head" or "foot") for real. */
    private suspend fun ReaderHarness.tapEdge(which: String) {
        val e = edges()
        val r = checkNotNull(e.edge(which)) { "no $which control: $e" }
        val loc = IntArray(2)
        sc.onActivity { it.reader.webView.getLocationOnScreen(loc) }
        val dpr = e.getDouble("dpr")
        assertTrue(dev.click(loc[0] + ((r.getDouble("x") + r.getDouble("w") / 2) * dpr).toInt(), loc[1] + ((r.getDouble("y") + r.getDouble("h") / 2) * dpr).toInt()))
    }

    /** Relocates seen while [block] runs. */
    private suspend fun ReaderHarness.relocatesDuring(scope: CoroutineScope, block: suspend () -> Unit): List<FromWeb.Relocate> {
        val moves = mutableListOf<FromWeb.Relocate>()
        val watch = scope.launch(start = CoroutineStart.UNDISPATCHED) { a.reader.events.filterIsInstance<FromWeb.Relocate>().collect { moves += it } }
        try { block() } finally { watch.cancel() }
        return moves
    }

    /** The landing holds: 3 s later the same section and the same offset in it, and no relocate left it. */
    private suspend fun ReaderHarness.holds(scope: CoroutineScope, section: Int, what: String) {
        val before = edges()
        val moves = relocatesDuring(scope) { delay(3_000) }
        val after = edges()
        assertEquals("$what: section 3 s later ($after)", section, after.getInt("current"))
        assertEquals("$what: offset in the section 3 s later", before.getDouble("start"), after.getDouble("start"), 2.0)
        assertEquals("$what: relocates left the section", emptyList<Int>(), moves.map { it.sectionIndex }.filter { it != section })
    }

    private fun run(block: suspend CoroutineScope.(ReaderHarness) -> Unit): Unit = runBlocking {
        val wp = warAndPeace()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready(); h.open(wp)
                block(h)
            } finally {
                h.change { ReaderSettings() }
                h.a.settingsStored()
                h.stop()
            }
        }
    }

    // ---- chapter end ----

    @Test fun flicks_stop_at_the_chapter_end_with_next_title_below_the_last_line() = run { h ->
        val i = h.oneAtATimeAt("CHAPTER II")
        lateinit var end: JSONObject
        val moves = h.relocatesDuring(this) {
            end = h.flickTo(end = true)
            flickUp(); delay(1_500) // a flick at the end is not a pull: it stays (the window in which nothing may happen: fixed)
        }
        assertEquals("relocates left the chapter while flicking", emptyList<Int>(), moves.map { it.sectionIndex }.filter { it != i })
        val e = h.edges()
        assertTrue("still at the end: $e", e.atEnd())
        assertEquals(i, e.getInt("current"))
        val foot = checkNotNull(e.edge("foot")) { "a Next control at the end: $e" }
        assertEquals(Copy.nextChapter.replace("{title}", h.titleOf(i + 1)), foot.getString("label"))
        assertEquals(i + 1, foot.getInt("index"))
        assertTrue("Next control on screen: $foot ($e)", foot.getDouble("y") >= 0 && foot.getDouble("y") + foot.getDouble("h") <= e.getDouble("vh"))
        assertTrue("the section ends above the control", e.getDouble("viewSize") - e.getDouble("start") <= foot.getDouble("y") + 1)
        h.holds(this, i, "at the chapter end")
    }

    @Test fun next_button_opens_the_next_chapter_at_its_top_and_it_holds() = run { h ->
        val i = h.oneAtATimeAt("CHAPTER II")
        h.flickTo(end = true); h.still(); h.drain()
        h.tapEdge("foot")
        assertEquals(i + 1, h.relocate(10_000) { it.sectionIndex != i }.sectionIndex)
        h.still()
        val e = h.edges()
        assertEquals(i + 1, e.getInt("current"))
        assertTrue("next chapter opens at its top: $e", e.sectionTop())
        assertEquals(Copy.previousChapter.replace("{title}", h.titleOf(i)), e.getJSONObject("head").getString("label"))
        assertTrue("its Previous control is above the screen: $e", e.getJSONObject("head").let { it.getDouble("y") + it.getDouble("h") <= 0 })
        h.holds(this, i + 1, "after Next")
    }

    @Test fun pull_past_the_end_opens_the_next_chapter_at_its_top() = run { h ->
        val i = h.oneAtATimeAt("CHAPTER II")
        h.flickTo(end = true); h.still(); h.drain()
        pullUp()
        assertEquals(i + 1, h.relocate(10_000) { it.sectionIndex != i }.sectionIndex)
        h.still()
        val e = h.edges()
        assertEquals(i + 1, e.getInt("current"))
        assertTrue("next chapter opens at its top: $e", e.sectionTop())
        h.holds(this, i + 1, "after the pull")
    }

    // ---- chapter start ----

    @Test fun pull_above_the_top_opens_the_previous_chapter_at_its_end() = run { h ->
        val k = h.oneAtATimeAt("CHAPTER III")
        h.sc.js("window.__readerTest.goIndex($k)") // the section's very top (the Contents target is its heading)
        h.relocate(10_000) { it.sectionIndex == k }; h.still()
        assertTrue("starts at the chapter's top: ${h.edges()}", h.edges().sectionTop())
        pullDown() // the Previous control comes into view, then the pull past it
        assertEquals(k - 1, h.relocate(10_000) { it.sectionIndex != k }.sectionIndex)
        h.still()
        val e = h.edges()
        assertEquals(k - 1, e.getInt("current"))
        assertTrue("previous chapter opens at its end: $e", e.sectionEnd())
        h.holds(this, k - 1, "after the pull back")
    }

    @Test fun previous_button_opens_the_previous_chapter_at_its_end() = run { h ->
        val k = h.oneAtATimeAt("CHAPTER III")
        val top = h.flickTo(end = false)
        val head = checkNotNull(top.edge("head")) { "a Previous control at the top: $top" }
        assertEquals(Copy.previousChapter.replace("{title}", h.titleOf(k - 1)), head.getString("label"))
        assertTrue("Previous control on screen: $head", head.getDouble("y") >= 0)
        assertEquals("flicking up to the top stays in the chapter", k, top.getInt("current"))
        h.drain()
        h.tapEdge("head")
        assertEquals(k - 1, h.relocate(10_000) { it.sectionIndex != k }.sectionIndex)
        h.still()
        val e = h.edges()
        assertEquals(k - 1, e.getInt("current"))
        assertTrue("previous chapter opens at its end: $e", e.sectionEnd())
        h.holds(this, k - 1, "after Previous")
    }

    // ---- the book's ends, Continuous, switching ----

    @Test fun first_and_last_chapters_have_no_control_on_the_book_side() = run { h ->
        h.oneAtATimeAt("CHAPTER II")
        val linear = h.sc.js("JSON.stringify(window.__readerTest.linear())").let { org.json.JSONArray(org.json.JSONTokener(it).nextValue() as String) }
        val first = linear.getInt(0); val last = linear.getInt(linear.length() - 1)
        h.sc.js("window.__readerTest.goIndex($first)"); h.relocate(10_000) { it.sectionIndex == first }; h.still()
        var e = h.edges()
        assertNull("no Previous before the first chapter: $e", e.edge("head"))
        h.sc.js("window.__readerTest.goIndex($last)"); h.relocate(10_000) { it.sectionIndex == last }; h.still()
        h.lastPage(); h.still()
        e = h.edges()
        assertTrue("at the book's end: $e", e.atEnd())
        assertNull("no Next after the last chapter: $e", e.edge("foot"))
        assertNotNull("but a Previous: $e", e.edge("head"))
        h.drain()
        val moves = h.relocatesDuring(this) { pullUp(); delay(2_000) } // the window in which nothing may happen: fixed
        assertEquals("a pull at the book's end goes nowhere", emptyList<Int>(), moves.map { it.sectionIndex }.filter { it != last })
    }

    @Test fun continuous_has_no_chapter_controls_and_switching_keeps_the_passage() = run { h ->
        h.change { it.copy(flow = Flow.SCROLLED, scrollChapters = ScrollChapters.CONTINUOUS) }
        h.still()
        val i = h.tocJump("CHAPTER II").sectionIndex
        flickUp(); h.still()
        var e = h.edges()
        assertFalse(e.getBoolean("single"))
        assertNull(e.edge("head")); assertNull(e.edge("foot"))
        h.drain()
        val before = h.sc.js("JSON.stringify(window.__readerTest.firstVisibleBlock())")
        val offset = e.getDouble("start")
        h.change { it.copy(scrollChapters = ScrollChapters.ONE_AT_A_TIME) }
        h.still()
        e = h.edges()
        assertTrue(e.getBoolean("single"))
        assertEquals(i, e.getInt("current"))
        assertEquals("the text on screen did not move ($e)", offset, e.getDouble("start"), 2.0)
        assertEquals("switching to One at a time keeps the passage on screen", before, h.sc.js("JSON.stringify(window.__readerTest.firstVisibleBlock())"))
        h.change { it.copy(scrollChapters = ScrollChapters.CONTINUOUS) }
        h.still()
        e = h.edges()
        assertFalse(e.getBoolean("single")); assertNull(e.edge("foot"))
        assertEquals("nor back ($e)", offset, e.getDouble("start"), 2.0)
        assertEquals("and back to Continuous too", before, h.sc.js("JSON.stringify(window.__readerTest.firstVisibleBlock())"))
    }

    // ---- the display panel ----

    /** Standing still: a click on a list that still moves can miss. */
    private fun obj(text: String): UiObject2 = checkNotNull(dev.settled(By.text(text), 5_000)) { "no '$text' on screen" }
    private fun button(text: String): UiObject2 {
        var o: UiObject2? = obj(text)
        while (o != null && !o.isCheckable) o = o.parent
        return checkNotNull(o) { "no button around '$text'" }
    }

    /** Scrolls the display panel with slow drags in its middle until [text] is on screen (see ReadingModeSyncTest). */
    private fun panelShow(text: String): Boolean {
        val size = checkNotNull(dev.wait(Until.findObject(By.text(Copy.textSize)), 3_000)) { "display panel not shown" }
        val r = dev.findObjects(By.scrollable(true)).map { it.visibleBounds }
            .filter { it.contains(size.visibleBounds) && it.height() < dev.displayHeight - 50 }.minByOrNull { it.height() }
            ?: android.graphics.Rect(0, dev.displayHeight / 2, dev.displayWidth, dev.displayHeight)
        repeat(8) {
            if (dev.hasObject(By.text(text))) return true
            dev.swipe(r.centerX(), r.top + r.height() * 3 / 4, r.centerX(), r.top + r.height() / 4, 40)
            dev.waitForIdle(300)
            if (android.os.Build.VERSION.SDK_INT >= 34) inst.uiAutomation.clearCache()
        }
        return dev.hasObject(By.text(text))
    }

    private suspend fun stored(): ReaderSettings = ReaderSettings.decode(graph.settings.get(ReaderSettings.KEY))

    @Test fun panel_shows_chapters_under_reading_mode_only_in_scroll_and_the_choice_is_stored(): Unit = runBlocking {
        val wp = warAndPeace()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready()
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                h.open(wp)
                sc.onActivity { it.controller.showSheet(ReaderSheetTab.DISPLAY) }
                assertTrue(panelShow(Copy.flowScrolled))
                assertNull("no Chapters row in Pages", dev.findObject(By.text(Copy.scrollChapters)))
                obj(Copy.flowScrolled).click()
                withTimeout(5_000) { h.a.controller.settings.first { it.flow == Flow.SCROLLED } }
                // shown again from its top (panelShow finds the panel by its first rows)
                sc.onActivity { it.controller.hideSheet() }
                assertTrue("display panel closed", app.fernreader.waitFresh(5_000) { dev.findObject(By.text(Copy.textSize)) == null })
                sc.onActivity { it.controller.showSheet(ReaderSheetTab.DISPLAY) }
                assertTrue(panelShow(Copy.chaptersOneAtATime))
                val mode = obj(Copy.layout); val chapters = obj(Copy.scrollChapters)
                assertTrue("Chapters is under Reading mode", chapters.visibleBounds.top > mode.visibleBounds.top)
                assertTrue("Continuous is the default", button(Copy.chaptersContinuous).isChecked)
                obj(Copy.chaptersOneAtATime).click()
                withTimeout(5_000) { h.a.controller.settings.first { it.scrollChapters == ScrollChapters.ONE_AT_A_TIME } }
                withTimeout(5_000) { while (stored().scrollChapters != ScrollChapters.ONE_AT_A_TIME) delay(100) } // persisted
                withTimeout(5_000) { while (!h.edges().getBoolean("single")) delay(100) } // and applied
                if (android.os.Build.VERSION.SDK_INT >= 34) inst.uiAutomation.clearCache()
                assertTrue(button(Copy.chaptersOneAtATime).isChecked)
                obj(Copy.flowPaged).click()
                withTimeout(5_000) { h.a.controller.settings.first { it.flow == Flow.PAGED } }
                assertTrue("Chapters hidden again in Pages", app.fernreader.waitFresh(5_000) { dev.findObject(By.text(Copy.scrollChapters)) == null })
                sc.onActivity { it.controller.hideSheet() }
                assertEquals("the choice is kept while hidden", ScrollChapters.ONE_AT_A_TIME, h.a.controller.settings.value.scrollChapters)
            } finally {
                h.stop()
            }
        }
        // a new activity reads it back from storage
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            lateinit var a: MainActivity
            sc.onActivity { a = it }
            try {
                withTimeout(10_000) { while (a.controller.settings.value.scrollChapters != ScrollChapters.ONE_AT_A_TIME) delay(100) }
            } finally {
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                a.settingsStored()
            }
        }
    }
}
