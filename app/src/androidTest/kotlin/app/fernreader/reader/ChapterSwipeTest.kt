package app.fernreader.reader

import android.graphics.Point
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.fernreader.EpubReaderApp
import app.fernreader.MainActivity
import app.fernreader.core.data.Flow
import app.fernreader.core.data.PageAnimation
import app.fernreader.core.data.ReaderSettings
import app.fernreader.core.data.ScrollChapters
import app.fernreader.core.data.SourceRef
import app.fernreader.core.epub.chunk.ChunkPolicy
import app.fernreader.reader.bridge.FromWeb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * C-093: in scroll mode with "Chapters: One at a time" a clearly horizontal swipe changes chapter with the slide of a page
 * turn (C-071): forward to the next chapter's top, back to the previous one's end, following the reading direction.
 * Nothing happens at the book's ends. A diagonal, a vertical scroll, a selection, a link tap and content that scrolls
 * sideways by itself keep working; hidden parts of a split chapter are one chapter; Continuous has no such swipe.
 * Probes: `edges()` (the paginator's `debugEdges`) and `slide()` (the frames of the last slide).
 */
@RunWith(AndroidJUnit4::class)
class ChapterSwipeTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val dev = UiDevice.getInstance(inst)
    private val graph get() = (inst.targetContext.applicationContext as EpubReaderApp).graph

    private val w get() = dev.displayWidth
    private val hgt get() = dev.displayHeight
    /** The finger left (forward in a left-to-right book) or right, over 60 % of the screen in about 0.1 s. */
    private fun swipeLeft(y: Int = hgt / 2) = dev.swipe(w * 4 / 5, y, w / 5, y, 20)
    private fun swipeRight(y: Int = hgt / 2) = dev.swipe(w / 5, y, w * 4 / 5, y, 20)
    private fun dragUp() = dev.swipe(w / 2, hgt * 7 / 10, w / 2, hgt * 3 / 10, 40)
    private fun flickUp() = dev.swipe(w / 2, hgt * 55 / 100, w / 2, hgt * 40 / 100, 4)

    private suspend fun ReaderHarness.edges(): JSONObject = sc.hook("edges()")
    private fun JSONObject.current() = getInt("current")
    private fun JSONObject.start() = getDouble("start")
    private fun JSONObject.sectionTop() = abs(start()) < 2
    private fun JSONObject.sectionEnd() = abs(start() + getDouble("viewport") - getDouble("viewSize")) < 2

    private suspend fun ReaderHarness.frames(): List<JSONObject> =
        JSONArray(JSONTokener(sc.js("JSON.stringify(window.__readerTest.slide() ?? [])")).nextValue() as String).let { a -> List(a.length()) { a.getJSONObject(it) } }

    /** One slide in the direction [sign] (-1: content to the left): frames in between, the pages one width apart, ending in place. */
    private fun assertSlide(frames: List<JSONObject>, sign: Int) {
        assertTrue("an in-between frame, not one swap: $frames", frames.map { it.getDouble("t") }.any { it > 0.0 && it < 1.0 })
        val width = abs(frames.first().getDouble("next"))
        assertTrue("starts one width away: $frames", width > 100)
        for (f in frames) assertEquals("adjacent pages at t=${f.getDouble("t")}", sign * width, f.getDouble("old") - f.getDouble("next"), 0.5)
        assertEquals(1.0, frames.last().getDouble("t"), 1e-9)
        assertEquals(0.0, frames.last().getDouble("next"), 0.5)
    }

    private fun ReaderHarness.change(f: (ReaderSettings) -> ReaderSettings) = sc.onActivity { it.controller.updateSettings(f) }

    private suspend fun ReaderHarness.oneAtATimeAt(label: String): Int {
        change { it.copy(flow = Flow.SCROLLED, scrollChapters = ScrollChapters.ONE_AT_A_TIME) }
        still()
        val i = tocJump(label).sectionIndex
        still(); drain()
        return i
    }

    /** Waits until the chapters on both sides are laid out, so a swipe starts the slide at once. */
    private suspend fun ReaderHarness.sidesLoaded(count: Int = 2) = withTimeout(15_000) {
        while (true) {
            val slots = sc.hook("slots()").getJSONArray("slots")
            if ((0 until slots.length()).count { slots.getJSONObject(it).let { s -> s.optBoolean("side") && s.optBoolean("loaded") } } >= count) break
            delay(100)
        }
    }

    private suspend fun ReaderHarness.relocatesDuring(scope: CoroutineScope, block: suspend () -> Unit): List<FromWeb.Relocate> {
        val moves = mutableListOf<FromWeb.Relocate>()
        val watch = scope.launch(start = CoroutineStart.UNDISPATCHED) { a.reader.events.filterIsInstance<FromWeb.Relocate>().collect { moves += it } }
        try { block() } finally { watch.cancel() }
        return moves
    }

    /** Nothing that [block] does changes chapter, and no slide runs. */
    private suspend fun ReaderHarness.staysIn(scope: CoroutineScope, section: Int, what: String, block: suspend () -> Unit) {
        sc.js("window.__readerTest.slide()?.splice(0); true")
        val moves = relocatesDuring(scope) { block(); delay(1_500) } // the window in which nothing may happen: stays fixed
        assertEquals("$what: relocates left the chapter", emptyList<Int>(), moves.map { it.sectionIndex }.filter { it != section })
        assertEquals("$what: still in the chapter", section, edges().current())
        assertEquals("$what: no slide", 0, frames().size)
    }

    private fun run(book: File? = null, block: suspend CoroutineScope.(ReaderHarness) -> Unit): Unit = runBlocking {
        val f = warAndPeace().let { book ?: it }
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready(); h.open(f)
                block(h)
            } finally {
                h.change { ReaderSettings() }
                h.a.settingsStored()
                h.stop()
            }
        }
    }

    // ---- both directions ----

    @Test fun forward_opens_the_next_chapter_at_its_top_with_the_slide() = run { h ->
        val i = h.oneAtATimeAt("CHAPTER II")
        h.sidesLoaded()
        dragUp(); h.still() // from the middle of the chapter, not only its end
        h.drain()
        swipeLeft()
        assertEquals(i + 1, h.relocate(10_000) { it.sectionIndex != i }.sectionIndex)
        h.still(timeoutMs = 30_000)
        val e = h.edges()
        assertEquals(i + 1, e.current())
        assertTrue("the next chapter opens at its top: $e", e.sectionTop())
        assertSlide(h.frames(), -1)
        delay(2_000) // the check that it stays there: fixed
        assertEquals("the landing holds", e.start(), h.edges().start(), 2.0)
    }

    @Test fun back_opens_the_previous_chapter_at_its_end_with_the_slide() = run { h ->
        val k = h.oneAtATimeAt("CHAPTER III")
        h.sidesLoaded()
        swipeRight()
        assertEquals(k - 1, h.relocate(10_000) { it.sectionIndex != k }.sectionIndex)
        h.still(timeoutMs = 30_000)
        val e = h.edges()
        assertEquals(k - 1, e.current())
        assertTrue("the previous chapter opens at its end: $e", e.sectionEnd())
        assertSlide(h.frames(), 1)
        delay(2_000) // the check that it stays there: fixed
        assertEquals("the landing holds", e.start(), h.edges().start(), 2.0)
    }

    @Test fun a_swipe_straight_after_a_landing_still_slides_and_two_swipes_move_two_chapters() = run { h ->
        val i = h.oneAtATimeAt("CHAPTER II")
        h.sidesLoaded()
        swipeLeft(); swipeLeft()
        try { h.relocate(15_000) { it.sectionIndex == i + 2 } } catch (x: Exception) { throw AssertionError("the second swipe never landed: ${h.edges()}", x) }
        h.still(timeoutMs = 30_000)
        assertEquals(i + 2, h.edges().current())
        assertSlide(h.frames(), -1)
    }

    @Test fun in_a_right_to_left_book_a_finger_moving_right_is_forward() = run(File("/sdcard/Books/epub3-samples/regime-anticancer-arabic.epub")) { h ->
        h.change { it.copy(flow = Flow.SCROLLED, scrollChapters = ScrollChapters.ONE_AT_A_TIME) }
        h.still()
        val linear = JSONArray(JSONTokener(h.sc.js("JSON.stringify(window.__readerTest.linear())")).nextValue() as String)
        val mid = linear.getInt(1)
        h.drain(); h.sc.js("window.__readerTest.goIndex($mid)"); h.relocate(10_000) { it.sectionIndex == mid }; h.still()
        assertEquals(mid, h.edges().current())
        h.sidesLoaded(); h.drain()
        swipeRight()
        val next = linear.getInt(2)
        assertEquals(next, h.relocate(15_000) { it.sectionIndex != mid }.sectionIndex)
        h.still(timeoutMs = 30_000)
        assertTrue(h.edges().sectionTop())
        assertSlide(h.frames(), 1) // forward moves the content right here, as a page turn does
        h.drain()
        swipeLeft()
        assertEquals(mid, h.relocate(15_000) { it.sectionIndex != next }.sectionIndex)
    }

    // ---- the book's first and last chapter ----

    @Test fun nothing_happens_past_the_books_first_and_last_chapter() = run { h ->
        h.oneAtATimeAt("CHAPTER II")
        val linear = JSONArray(JSONTokener(h.sc.js("JSON.stringify(window.__readerTest.linear())")).nextValue() as String)
        val first = linear.getInt(0); val last = linear.getInt(linear.length() - 1)
        h.sc.js("window.__readerTest.goIndex($first)"); h.relocate(10_000) { it.sectionIndex == first }; h.still()
        h.drain()
        h.staysIn(this, first, "back at the first chapter") { swipeRight() }
        h.sc.js("window.__readerTest.goIndex($last)"); h.relocate(10_000) { it.sectionIndex == last }; h.still()
        h.drain()
        val before = h.edges().start()
        h.staysIn(this, last, "forward at the last chapter") { swipeLeft() }
        assertEquals("the text stays where it was", before, h.edges().start(), 2.0)
        // and the way back in works: the book's edge did not leave the swipe stuck
        swipeRight()
        assertEquals(linear.getInt(linear.length() - 2), h.relocate(10_000) { it.sectionIndex != last }.sectionIndex)
    }

    // ---- what stays a scroll ----

    @Test fun a_diagonal_scrolls_and_does_not_change_chapter() = run { h ->
        val i = h.oneAtATimeAt("CHAPTER II")
        h.sidesLoaded()
        val before = h.edges().start()
        h.staysIn(this, i, "a 45 degree diagonal") { dev.swipe(w / 5, hgt * 7 / 10, w * 3 / 5, hgt * 7 / 10 - w * 2 / 5, 40) }
        val diagonal = h.edges().start()
        assertTrue("the diagonal scrolled the text: $before -> $diagonal", diagonal > before + 20)
        h.staysIn(this, i, "a steeper one the other way") { dev.swipe(w * 3 / 5, hgt * 3 / 10, w * 2 / 5, hgt * 6 / 10, 40) }
        assertTrue("it scrolled back: $diagonal -> ${h.edges().start()}", h.edges().start() < diagonal - 20)
    }

    @Test fun vertical_drags_and_flings_are_as_before() = run { h ->
        val i = h.oneAtATimeAt("CHAPTER II")
        h.sidesLoaded()
        h.traced {
        val top = h.edges().start()
        h.staysIn(this, i, "a drag") { dragUp() }
        val dragged = h.edges().start()
        // the text follows the finger from its first move: nothing of the drag went to the lock (slop aside)
        val finger = (hgt * 4 / 10) / h.edges().getDouble("dpr")
        assertTrue("the text followed the finger: ${dragged - top} >= $finger - 40", dragged - top >= finger - 40)
        h.staysIn(this, i, "a fling") { flickUp() }
        val flung = h.edges().start()
        val fingerFling = (hgt * 15 / 100) / h.edges().getDouble("dpr")
        assertTrue("the fling ran on past the finger: ${flung - dragged} > $fingerFling", flung - dragged > fingerFling)
        }
    }

    @Test fun the_pull_and_the_next_control_stay() = run { h ->
        val i = h.oneAtATimeAt("CHAPTER II")
        h.lastPage(); h.still(); h.drain()
        assertNotNull("Next under the last line", h.edges().optJSONObject("foot"))
        dev.swipe(w / 2, hgt * 4 / 5, w / 2, hgt / 4, 80)
        assertEquals(i + 1, h.relocate(10_000) { it.sectionIndex != i }.sectionIndex)
        h.still(timeoutMs = 30_000)
        assertTrue(h.edges().sectionTop())
    }

    @Test fun at_the_chapter_end_the_next_control_leaves_with_the_text() = run { h ->
        val i = h.oneAtATimeAt("CHAPTER II")
        h.lastPage(); h.still(); h.sidesLoaded(); h.drain()
        swipeLeft()
        assertEquals(i + 1, h.relocate(10_000) { it.sectionIndex != i }.sectionIndex)
        h.still(timeoutMs = 30_000)
        val e = h.edges()
        assertTrue("next chapter at its top: $e", e.sectionTop())
        assertTrue("its Previous control above the screen: $e", e.getJSONObject("head").let { it.getDouble("y") + it.getDouble("h") <= 0 })
        assertEquals("no leftover offset on the control", 0.0, e.getJSONObject("head").getDouble("x") - (e.getDouble("vw") - e.getJSONObject("head").getDouble("w")) / 2, 2.0)
    }

    // ---- selection and links ----

    private suspend fun ReaderHarness.wordPoint(word: String): Pair<Int, Int> {
        val r = JSONObject(JSONTokener(sc.js("JSON.stringify(window.__readerTest.wordRect(${JSONObject.quote(word)}))")).nextValue() as String)
        val loc = IntArray(2)
        sc.onActivity { it.reader.webView.getLocationOnScreen(loc) }
        val dpr = r.getDouble("dpr")
        return loc[0] + ((r.getDouble("x") + r.getDouble("w") / 2) * dpr).toInt() to loc[1] + ((r.getDouble("y") + r.getDouble("h") / 2) * dpr).toInt()
    }
    private suspend fun ReaderHarness.selected() = next(5_000) { e -> (e as? FromWeb.Selection)?.takeIf { it.cfi != null } }

    private fun inject(action: Int, down: Long, x: Int, y: Int) {
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        assertTrue(inst.uiAutomation.injectInputEvent(e, true))
        e.recycle()
    }

    @Test fun a_sideways_swipe_with_text_selected_does_not_change_chapter() = run { h ->
        val i = h.oneAtATimeAt("CHAPTER II")
        h.sidesLoaded()
        val (x, y) = h.wordPoint("the")
        h.drain()
        assertTrue(dev.swipe(x, y, x, y, 150)) // ~0.75 s without moving: a long-press
        h.selected()
        h.staysIn(this, i, "a swipe over selected text") { swipeLeft(hgt * 3 / 4) }
    }

    @Test fun long_press_and_drag_sideways_selects_and_does_not_change_chapter() = run { h ->
        val i = h.oneAtATimeAt("CHAPTER II")
        h.sidesLoaded()
        val (x, y) = h.wordPoint("the")
        h.drain()
        h.staysIn(this, i, "a selection drag") {
            val down = SystemClock.uptimeMillis()
            inject(MotionEvent.ACTION_DOWN, down, x, y)
            delay(900) // the long-press selects the word
            val to = if (x > w / 2) x - w / 3 else x + w / 3
            for (s in 1..20) { inject(MotionEvent.ACTION_MOVE, down, x + (to - x) * s / 20, y); delay(16) }
            inject(MotionEvent.ACTION_UP, down, to, y)
        }
        h.selected()
    }

    private fun footnotes() = File("/sdcard/Books/generated/footnotes-links.epub").also { check(it.isFile) { "corpus not pushed: $it" } }
    /** Where [text]'s link is on screen (px), or would be: its middle can lie outside the screen. */
    private suspend fun ReaderHarness.linkPoint(text: String): Pair<Int, Int>? {
        val raw = JSONTokener(sc.js("JSON.stringify(window.__readerTest.linkRect(${JSONObject.quote(text)}))")).nextValue() as? String ?: return null
        val r = JSONTokener(raw).nextValue() as? JSONObject ?: return null
        val loc = IntArray(2)
        sc.onActivity { it.reader.webView.getLocationOnScreen(loc) }
        val dpr = r.getDouble("dpr")
        return loc[0] + ((r.getDouble("x") + r.getDouble("w") / 2) * dpr).toInt() to loc[1] + ((r.getDouble("y") + r.getDouble("h") / 2) * dpr).toInt()
    }
    private suspend fun ReaderHarness.scrollToLink(text: String): Pair<Int, Int> {
        val section = edges().current()
        repeat(40) {
            assertEquals("still in the link's chapter", section, edges().current())
            val p = checkNotNull(linkPoint(text)) { "no link '$text'" }
            if (p.second in hgt / 8..hgt * 7 / 8) return p
            // straight towards the link, held still before the lift: no run-out, the text moves just the drag's length,
            // so it can't overshoot the link and pull past the chapter's end
            val by = (p.second - hgt / 2).coerceIn(-hgt * 7 / 10, hgt * 7 / 10)
            val from = Point(w / 2, hgt / 2 + by / 2); val to = Point(w / 2, hgt / 2 - by / 2)
            dev.swipe(arrayOf(from, to, to), 40); still()
        }
        error("link '$text' never on screen")
    }

    @Test fun a_link_tap_follows_the_link_and_a_swipe_starting_on_it_changes_chapter() = run(footnotes()) { h ->
        val i = h.oneAtATimeAt("Notes Test")
        h.sidesLoaded(1)
        var p = h.scrollToLink("the target in chapter two")
        h.drain()
        dev.swipe(p.first, p.second, maxOf(20, p.first - w / 2), p.second, 20)
        val swiped = h.relocate(10_000) { it.sectionIndex != i }
        assertEquals(i + 1, swiped.sectionIndex)
        assertEquals("the swipe followed no link", 0, h.sc.js("window.__readerTest.historyDepth()").toInt())
        assertFalse(h.a.controller.state.value.footnoteOpen)
        h.still(); h.drain()
        h.sc.js("window.__readerTest.goIndex($i)"); h.relocate(10_000) { it.sectionIndex == i }; h.still(); h.drain()
        p = h.scrollToLink("the target in chapter two")
        h.drain()
        assertTrue(dev.click(p.first, p.second))
        h.relocate(10_000) { it.sectionIndex != i }
        assertEquals("the tap followed the link", 1, h.sc.js("window.__readerTest.historyDepth()").toInt())
    }

    // ---- content that scrolls sideways by itself ----

    /** Puts a wide scroll box at the top of the current section; returns where to touch it (screen px). */
    private suspend fun ReaderHarness.wideBoxAtTop(): Pair<Int, Int> {
        val r = JSONObject(JSONTokener(sc.js("""(() => {
            const d = document.querySelector('foliate-view').renderer.getContents()[0].doc
            const box = d.createElement('div'); box.id = 'wide'
            box.style.cssText = 'overflow-x: auto; height: 200px; background: #eee'
            box.innerHTML = '<div style="width: 3000px; height: 200px">wide wide wide</div>'
            d.body.prepend(box)
            const f = d.defaultView.frameElement.getBoundingClientRect()
            const b = box.getBoundingClientRect()
            return JSON.stringify({ y: f.top + b.top + b.height / 2, dpr: devicePixelRatio })
        })()""")).nextValue() as String)
        val loc = IntArray(2)
        sc.onActivity { it.reader.webView.getLocationOnScreen(loc) }
        return w / 2 to loc[1] + (r.getDouble("y") * r.getDouble("dpr")).toInt()
    }
    private suspend fun ReaderHarness.wideScroll() = sc.js("document.querySelector('foliate-view').renderer.getContents()[0].doc.getElementById('wide').scrollLeft").toDouble()

    @Test fun content_that_scrolls_sideways_keeps_its_swipe_and_the_text_beside_it_changes_chapter() = run { h ->
        val i = h.oneAtATimeAt("CHAPTER II")
        h.drain(); h.sc.js("window.__readerTest.goIndex($i)"); h.relocate(10_000) { it.sectionIndex == i }; h.still()
        h.sidesLoaded()
        val (_, y) = h.wideBoxAtTop()
        h.still(); h.drain()
        assertTrue("the box is on screen: $y", y in 0..hgt)
        h.staysIn(this, i, "a swipe on the wide box") { swipeLeft(y) }
        val scrolled = h.wideScroll()
        assertTrue("the box scrolled: $scrolled", scrolled > 50)
        h.staysIn(this, i, "and back") { swipeRight(y) }
        assertTrue("it scrolled back: ${h.wideScroll()}", h.wideScroll() < scrolled)
        swipeLeft(hgt * 3 / 4)
        assertEquals("beside it the swipe is a chapter swipe", i + 1, h.relocate(10_000) { it.sectionIndex != i }.sectionIndex)
    }

    // ---- settings ----

    @Test fun with_no_animation_the_swipe_changes_chapter_without_a_slide() = run { h ->
        val i = h.oneAtATimeAt("CHAPTER II")
        h.change { it.copy(pageAnimation = PageAnimation.NONE) }
        h.still(); h.sidesLoaded(); h.drain()
        h.sc.js("window.__readerTest.slide()?.splice(0); true")
        swipeLeft()
        assertEquals(i + 1, h.relocate(10_000) { it.sectionIndex != i }.sectionIndex)
        h.still()
        assertTrue(h.edges().sectionTop())
        assertEquals("no slide", 0, h.frames().size)
    }

    @Test fun continuous_has_no_chapter_swipe() = run { h ->
        h.change { it.copy(flow = Flow.SCROLLED, scrollChapters = ScrollChapters.CONTINUOUS) }
        h.still()
        val i = h.tocJump("CHAPTER II").sectionIndex
        h.still(); h.drain()
        val before = h.edges().start()
        val moves = h.relocatesDuring(this) { swipeLeft(); delay(1_000); swipeRight(); delay(1_000) } // windows in which nothing may happen: fixed
        assertEquals("no chapter change", emptyList<Int>(), moves.map { it.sectionIndex }.filter { it != i })
        val e = h.edges()
        assertEquals(i, e.current())
        assertEquals("nor any scrolling", before, e.start(), 2.0)
    }

    // ---- a split chapter ----

    /** The hidden parts of a huge document are one chapter: a swipe goes past all of them, or nowhere at the book's end. */
    @Test fun a_swipe_in_a_split_chapter_leaves_the_whole_chapter(): Unit = runBlocking {
        warAndPeace() // grants all-files access
        val f = File("/sdcard/Books/generated/stress-huge-single-chapter.epub").also { check(it.isFile) { "corpus not pushed: $it" } }
        graph.opener.policy = ChunkPolicy()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready()
                sc.onActivity { it.controller.updateSettings { s -> s.copy(flow = Flow.SCROLLED, scrollChapters = ScrollChapters.ONE_AT_A_TIME) } }
                sc.onActivity { it.openBook(SourceRef.File(f.path)) }
                h.relocate(120_000); h.painted(60_000)
                withTimeout(30_000) { h.a.controller.state.first { !it.loading } }
                h.still(timeoutMs = 60_000); h.drain()

                val linear = JSONArray(JSONTokener(h.sc.js("JSON.stringify(window.__readerTest.linear())")).nextValue() as String).let { a -> List(a.length()) { a.getInt(it) } }
                var big: Pair<Int, Int>? = null
                for (i in linear) {
                    h.drain(); h.sc.js("window.__readerTest.goIndex($i)"); h.relocate(30_000) { it.sectionIndex == i }; h.still(timeoutMs = 30_000)
                    val span = h.edges().getJSONArray("span")
                    if (span.getInt(1) > span.getInt(0)) { big = span.getInt(0) to span.getInt(1); break }
                }
                val (from, to) = checkNotNull(big) { "no split chapter in $linear" }
                val part = from + (to - from) / 2
                assertTrue("a part inside the chapter: $from..$to", part in from..to)
                suspend fun goPart() {
                    h.sc.js("window.__readerTest.goIndex($part)"); h.relocate(30_000) { it.sectionIndex == part }; h.still(timeoutMs = 30_000); h.drain()
                    assertEquals(part, h.edges().current())
                }
                val after = linear.firstOrNull { it > to }
                val before = linear.lastOrNull { it < from }
                android.util.Log.i("C093", "chapter $from..$to, part $part, before $before, after $after")

                goPart()
                swipeLeft()
                if (after != null) {
                    assertEquals("forward leaves the whole chapter", after, h.relocate(30_000) { it.sectionIndex != part }.sectionIndex)
                    h.still(timeoutMs = 30_000)
                    assertTrue(h.edges().sectionTop())
                } else {
                    delay(2_500); assertEquals("the book's last chapter: the swipe goes nowhere", part, h.edges().current())
                }

                goPart()
                swipeRight()
                if (before != null) {
                    assertEquals("back leaves the whole chapter", before, h.relocate(30_000) { it.sectionIndex != part }.sectionIndex)
                    h.still(timeoutMs = 30_000)
                    assertTrue(h.edges().sectionEnd())
                } else {
                    delay(2_500); assertEquals("the book's first chapter: the swipe goes nowhere", part, h.edges().current())
                }

                // into the split chapter from behind: its last part, at its end
                if (after != null) {
                    h.sc.js("window.__readerTest.goIndex($after)"); h.relocate(30_000) { it.sectionIndex == after }; h.still(timeoutMs = 30_000); h.drain()
                    swipeRight()
                    assertEquals("back opens the split chapter's last part", to, h.relocate(30_000) { it.sectionIndex != after }.sectionIndex)
                    h.still(timeoutMs = 30_000)
                    assertTrue("at its end: ${h.edges()}", h.edges().sectionEnd())
                }
            } finally {
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                h.a.settingsStored()
                h.stop()
            }
        }
    }
}
