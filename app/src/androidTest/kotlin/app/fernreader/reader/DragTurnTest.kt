package app.fernreader.reader

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.fernreader.MainActivity
import app.fernreader.core.data.Flow
import app.fernreader.core.data.PageAnimation
import app.fernreader.core.data.ReaderSettings
import app.fernreader.core.data.ScrollChapters
import app.fernreader.reader.bridge.FromWeb
import app.fernreader.reader.bridge.ToWeb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * C-094: the page follows the finger, in Pages mode and in One at a time's chapter swipe. On release it finishes past about
 * a third of the screen or as a flick, else it snaps back; a boundary is staged and swapped last, so a cancel costs nothing.
 * Motion events are injected one by one so that the page can be read while the finger is down. Probes: `drag()` (the
 * paginator's `debugDrag` and the page's offset) and `page()`.
 */
@RunWith(AndroidJUnit4::class)
class DragTurnTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val dev = UiDevice.getInstance(inst)
    private val w get() = dev.displayWidth
    private val hgt get() = dev.displayHeight

    private data class Pos(val index: Int, val page: Int)
    private suspend fun ReaderHarness.pos() = sc.hook("page()").let { Pos(it.getInt("index"), it.getInt("page")) }
    private suspend fun ReaderHarness.drag() = sc.hook("drag()")
    private suspend fun ReaderHarness.loaded(): List<Int> = JSONArray(sc.js("window.__readerTest.loaded()")).let { a -> List(a.length()) { a.getInt(it) }.sorted() }
    private fun JSONObject.kinds() = getJSONArray("kinds").let { a -> List(a.length()) { a.getString(it) } }
    private fun JSONObject.finished() = if (isNull("finished")) null else getBoolean("finished")

    /** One finger on the screen: [move] goes there in steps, the page having time to answer each. */
    private inner class Finger(var x: Int, var y: Int = hgt / 2) {
        private val down = SystemClock.uptimeMillis()
        var lifted = false
        private fun send(action: Int) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            assertTrue(inst.uiAutomation.injectInputEvent(e, true))
            e.recycle()
        }
        init { send(MotionEvent.ACTION_DOWN); fingers += this }
        suspend fun move(toX: Int, toY: Int = y, steps: Int = 12, stepMs: Long = 16) {
            val x0 = x; val y0 = y
            for (s in 1..steps) {
                x = x0 + (toX - x0) * s / steps; y = y0 + (toY - y0) * s / steps
                send(MotionEvent.ACTION_MOVE); delay(stepMs)
            }
        }
        fun up() { send(MotionEvent.ACTION_UP); lifted = true }
    }

    /** A test that fails with a finger down must lift it: the system refuses the next test's down. */
    private val fingers = mutableListOf<Finger>()

    /**
     * A run killed with a finger down leaves the system's event check waiting for its end, across processes: every later
     * injected down fails ("Inconsistent event") until an up comes. Without such a finger this up is refused, which is fine.
     */
    @Before fun liftAStrayFinger() {
        val now = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 0f, 0f, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        inst.uiAutomation.injectInputEvent(e, true)
        e.recycle()
    }

    /** Waits until the drag has settled: nothing holds the page. */
    private suspend fun ReaderHarness.settled(): JSONObject = withTimeout(15_000) {
        while (true) {
            val d = drag()
            if (!d.getBoolean("active") && sc.js("document.querySelector('foliate-view').renderer.busy") == "false") return@withTimeout d
            delay(30)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    /** A slow drag over [fraction] of the screen, [dir] -1 leftward, held still before the lift (so no flick). */
    private suspend fun ReaderHarness.slowDrag(dir: Int, fraction: Double, at: suspend () -> Unit = {}): JSONObject {
        val from = if (dir < 0) w * 9 / 10 else w / 10
        val f = Finger(from)
        f.move(from + (dir * w * fraction).toInt())
        at()
        delay(250)
        f.up()
        delay(50)
        return settled()
    }

    private suspend fun ReaderHarness.relocatesDuring(scope: CoroutineScope, block: suspend () -> Unit): List<FromWeb.Relocate> {
        val moves = mutableListOf<FromWeb.Relocate>()
        val watch = scope.launch(start = CoroutineStart.UNDISPATCHED) { a.reader.events.filterIsInstance<FromWeb.Relocate>().collect { moves += it } }
        try { block() } finally { watch.cancel() }
        return moves
    }

    private suspend fun ReaderHarness.awaitLoaded(index: Int) = withTimeout(10_000) { while (index !in loaded()) delay(50) }

    /** A chapter a third into the book, two pages in. */
    private suspend fun ReaderHarness.midChapter(): Pos {
        val toc = a.controller.state.value.toc.filter { !it.missing && it.index >= 0 }
        for ((n, item) in toc.drop(toc.size / 3).withIndex()) {
            val id = 9_000 + n
            send(ToWeb.GoTo(target = item.href, id = id))
            next(10_000) { e -> (e as? FromWeb.Seeked)?.takeIf { it.id == id } }
            settle()
            val p = sc.hook("page()")
            if (p.getInt("textPages") - p.getInt("page") >= 5) break
        }
        send(ToWeb.Next()); quiet()
        send(ToWeb.Next()); quiet()
        return pos()
    }

    /** The last page of a chapter, the next section laid out. */
    private suspend fun ReaderHarness.chapterEnd(label: String = "BOOK ONE: 1805"): Int {
        val i = tocJump(label).sectionIndex
        lastPage(); still(); awaitLoaded(i + 1); drain()
        return i
    }

    private fun run(book: File? = null, block: suspend CoroutineScope.(ReaderHarness) -> Unit): Unit = runBlocking {
        val wp = warAndPeace() // all-files access before the activity starts
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready()
                if (book != null) h.open(book) else h.open(wp)
                block(h)
            } finally {
                fingers.filterNot { it.lifted }.forEach { it.up() }
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                h.a.settingsStored()
                h.stop()
            }
        }
    }

    private fun arabic() = File("/sdcard/Books/epub3-samples/regime-anticancer-arabic.epub").also { check(it.isFile) { "corpus not pushed: $it" } }
    private fun kusamakura() = File("/sdcard/Books/epub3-samples/kusamakura-japanese-vertical-writing.epub").also { check(it.isFile) { "corpus not pushed: $it" } }

    // ---- inside a chapter ----

    @Test fun the_page_follows_the_finger_and_finishes_past_a_third() = run { h ->
        val start = h.midChapter()
        val offset = h.sc.hook("edges()").getDouble("start")
        val moved = h.relocatesDuring(this) {
            val d = h.slowDrag(-1, 0.4) { // under half: `page()` reads the page at the middle of the screen
                val live = h.drag()
                assertTrue("the finger is down: $live", live.getBoolean("active"))
                assertEquals(listOf("page"), live.kinds())
                assertEquals("the finger's share of the screen: $live", 0.4, live.getDouble("t"), 0.12)
                assertTrue("the page moved with the finger: $offset -> $live", live.getDouble("start") > offset + 50)
                assertEquals("the location waits for the release", start, h.pos())
            }
            assertEquals("finished: $d", true, d.finished())
        }
        assertEquals(start.copy(page = start.page + 1), h.pos())
        assertEquals("one relocate", 1, moved.size)
    }

    @Test fun a_short_slow_drag_snaps_back() = run { h ->
        val start = h.midChapter()
        val offset = h.sc.hook("edges()").getDouble("start")
        val moved = h.relocatesDuring(this) {
            val d = h.slowDrag(-1, 0.2)
            assertEquals("snapped back: $d", false, d.finished())
            assertTrue("it did follow: $d", d.getDouble("maxT") > 0.1)
        }
        assertEquals(start, h.pos())
        assertEquals("back on the page exactly", offset, h.sc.hook("edges()").getDouble("start"), 1.0)
        assertEquals("a cancel moves nothing", emptyList<FromWeb.Relocate>(), moved)
    }

    /** A flick over 48 ms, with its times fixed up front: a stall in this test must not read as a finger held still. */
    private fun flick(from: Int, to: Int) {
        val down = SystemClock.uptimeMillis() - 48
        val y = hgt / 2f
        val events = (0..3).map { i ->
            val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; 3 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            MotionEvent.obtain(down, down + i * 16L, action, from + (to - from) * i / 3f, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        }
        for (e in events) { check(inst.uiAutomation.injectInputEvent(e, false)) { "injection refused: $e" }; e.recycle() }
    }

    @Test fun a_flick_finishes_however_short() = run { h ->
        val start = h.midChapter()
        flick(w * 7 / 10, w / 2)
        delay(50)
        val d = withTimeout(5_000) { while (h.drag().isNull("release")) delay(30); h.settled() }
        assertTrue("under a third: $d", d.getDouble("maxT") < 0.33)
        assertEquals("finished: $d", true, d.finished())
        assertEquals(start.copy(page = start.page + 1), h.pos())
    }

    @Test fun a_drag_brought_back_and_a_drag_the_other_way() = run { h ->
        val start = h.midChapter()
        val f = Finger(w / 2)
        f.move(w / 5) // forward
        f.move(w * 9 / 10, steps = 20) // through the start, back, past a third of the screen
        delay(250)
        f.up()
        delay(50)
        assertEquals(true, h.settled().finished())
        assertEquals(start.copy(page = start.page - 1), h.pos())
    }

    // ---- across a chapter boundary ----

    @Test fun boundary_forward_then_back_is_staged_and_swapped_last() = run { h ->
        val i = h.chapterEnd()
        val end = h.pos()
        var d = h.slowDrag(-1, 0.6) {
            val live = h.drag()
            assertEquals(listOf("section"), live.kinds())
            assertTrue("the next section is on stage: $live", live.getBoolean("sliding"))
            assertEquals("still the old section", i, h.pos().index)
        }
        assertEquals(true, d.finished())
        assertEquals("the next section, its first page", Pos(i + 1, 1), h.pos())
        h.awaitLoaded(i)
        d = h.slowDrag(1, 0.6) { assertEquals(listOf("section"), h.drag().kinds()) }
        assertEquals(true, d.finished())
        assertEquals("back on the last page", end, h.pos())
    }

    @Test fun a_cancelled_boundary_drag_costs_nothing() = run { h ->
        val i = h.chapterEnd()
        val before = h.pos()
        val sections = h.loaded()
        val moved = h.relocatesDuring(this) {
            val d = h.slowDrag(-1, 0.25) { assertEquals(listOf("section"), h.drag().kinds()) }
            assertEquals(false, d.finished())
        }
        assertEquals(before, h.pos())
        assertEquals("no relocate", emptyList<FromWeb.Relocate>(), moved)
        assertEquals("nothing loaded or dropped", sections, h.loaded())
        assertFalse("the stage is down", h.drag().getBoolean("sliding"))
        // and the turn works as if nothing had happened
        h.send(ToWeb.Next())
        assertEquals(i + 1, h.relocate(5_000) { it.sectionIndex != i }.sectionIndex)
    }

    @Test fun a_section_still_loading_holds_the_drag_and_it_finishes_when_it_comes() = run { h ->
        val i = h.tocJump("BOOK ONE: 1805").sectionIndex
        h.still()
        // the section two ahead takes 12 s to load: going one ahead, it is the neighbour still on its way
        val slow = i + 2
        h.sc.js("""(() => { const s = document.querySelector('foliate-view').renderer.sections[$slow], load = s.load.bind(s)
            s.load = () => new Promise(r => setTimeout(r, 12000)).then(load); return true })()""")
        h.tocNext(i + 1)
        h.lastPage(); h.drain()
        val f = Finger(w * 9 / 10)
        f.move(w / 10)
        val live = h.drag()
        assertEquals("it waits at the edge: $live", "page-edge", live.kinds().first())
        assertTrue("with resistance: $live", live.getDouble("t") < 0.2)
        delay(250)
        f.up()
        delay(200)
        assertEquals("it holds after the release", i + 1, h.pos().index)
        assertEquals("it finished when the section came", i + 2, h.relocate(20_000) { it.sectionIndex != i + 1 }.sectionIndex)
        assertEquals(true, h.settled().finished())
    }

    /** Jumps to section [index]; its neighbours start loading. */
    private suspend fun ReaderHarness.tocNext(index: Int) {
        sc.js("window.__readerTest.goIndex($index)")
        relocate(10_000) { it.sectionIndex == index }
        still()
    }

    // ---- the book's edges ----

    @Test fun nothing_turns_past_the_books_start_and_the_page_comes_back() = run { h ->
        val first = JSONArray(JSONTokener(h.sc.js("JSON.stringify(window.__readerTest.linear())")).nextValue() as String).getInt(0)
        h.drain(); h.sc.js("window.__readerTest.goIndex($first)"); h.still(); h.drain()
        val start = h.pos()
        val moved = h.relocatesDuring(this) {
            val d = h.slowDrag(1, 0.7) {
                val live = h.drag()
                assertEquals(listOf("page-edge"), live.kinds())
                assertTrue("resisted: $live", live.getDouble("t") < 0.2)
            }
            assertEquals(false, d.finished())
        }
        assertEquals(start, h.pos())
        assertEquals(emptyList<FromWeb.Relocate>(), moved)
    }

    // ---- right to left, vertical writing ----

    @Test fun in_a_right_to_left_book_the_page_follows_a_finger_moving_right() = run(arabic()) { h ->
        val start = h.midChapter()
        assertEquals("a finger moving right is forward: it snaps back here", false, h.slowDrag(1, 0.15).finished())
        assertEquals(start, h.pos())
        val d = h.slowDrag(1, 0.5) { assertEquals(listOf("page"), h.drag().kinds()) }
        assertEquals(true, d.finished())
        assertEquals(start.copy(page = start.page + 1), h.pos())
        assertEquals(true, h.slowDrag(-1, 0.5).finished())
        assertEquals(start, h.pos())
    }

    @Test fun a_right_to_left_boundary_both_ways() = run(arabic()) { h ->
        val linear = JSONArray(JSONTokener(h.sc.js("JSON.stringify(window.__readerTest.linear())")).nextValue() as String)
        val i = linear.getInt(1); val next = linear.getInt(2)
        h.drain(); h.sc.js("window.__readerTest.goIndex($i)"); h.relocate(10_000) { it.sectionIndex == i }; h.still()
        h.sc.js("window.__readerTest.lastPage()"); h.still(); h.awaitLoaded(next); h.drain() // (a one-page section stays where it is)
        val end = h.pos()
        assertEquals(true, h.slowDrag(1, 0.6) { assertEquals(listOf("section"), h.drag().kinds()) }.finished())
        assertEquals(Pos(next, 1), h.pos())
        h.awaitLoaded(i)
        assertEquals(true, h.slowDrag(-1, 0.6).finished())
        assertEquals(end, h.pos())
    }

    @Test fun vertical_writing_follows_along_its_reading_axis() = run(kusamakura()) { h ->
        val start = h.midChapter()
        val horizontal = h.sc.js("document.querySelector('foliate-view').renderer.verticalPages") == "true"
        if (horizontal) { // F-081: pages run right to left
            val d = h.slowDrag(1, 0.5) { assertEquals(listOf("page"), h.drag().kinds()) }
            assertEquals(true, d.finished())
        } else { // foliate's top-to-bottom pages
            val f = Finger(w / 2, hgt * 9 / 10)
            f.move(w / 2, hgt * 3 / 10)
            assertEquals(listOf("page"), h.drag().kinds())
            delay(250); f.up(); delay(50)
            assertEquals(true, h.settled().finished())
        }
        assertEquals(start.copy(page = start.page + 1), h.pos())
    }

    // ---- quick swipes and touches during a settle ----

    /** [count] quick leftward swipes, built up front with explicit times, so the test's own pacing can't stretch them. */
    private fun fastSwipesLeft(count: Int) {
        val y = hgt / 2f
        val xs = listOf(w * 0.85f, w * 0.6f, w * 0.35f, w * 0.15f)
        val base = SystemClock.uptimeMillis() - count * 80L
        val events = (0 until count).flatMap { k ->
            val down = base + k * 80L
            xs.mapIndexed { i, x ->
                val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; xs.lastIndex -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                MotionEvent.obtain(down, down + i * 16L, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            }
        }
        for (e in events) { check(inst.uiAutomation.injectInputEvent(e, false)) { "injection refused: $e" }; e.recycle() }
    }

    @Test fun quick_repeated_swipes_turn_one_page_each() = run { h ->
        val start = h.midChapter()
        fastSwipesLeft(3)
        h.quiet()
        assertEquals(start.copy(page = start.page + 3), h.pos())
    }

    @Test fun quick_swipes_across_a_boundary_turn_one_page_each() = run { h ->
        val i = h.chapterEnd()
        fastSwipesLeft(3)
        h.quiet()
        assertEquals(Pos(i + 1, 3), h.pos())
    }

    /** The finger comes down while the page still settles: the turn is over at once, and the new gesture is a turn of its own. */
    @Test fun a_touch_during_a_settle_finishes_it_at_once() = run { h ->
        val start = h.midChapter()
        val first = Finger(w * 9 / 10)
        first.move(w / 2, steps = 6, stepMs = 8)
        delay(120)
        first.up() // a slow release past a third: ~180 ms of settling
        val second = Finger(w * 9 / 10) // at once
        delay(30)
        assertEquals("the first turn is done with the finger down", start.copy(page = start.page + 1), h.pos())
        second.move(w / 5)
        // the finger is still down; the page's input can lag this test on a busy emulator
        val followed = try { withTimeout(1_500) { while (!h.drag().getBoolean("active")) delay(30); true } } catch (x: TimeoutCancellationException) { false }
        assertTrue("and the page follows again: ${h.drag()}", followed)
        delay(250)
        second.up()
        delay(50)
        assertEquals(true, h.settled().finished())
        assertEquals(start.copy(page = start.page + 2), h.pos())
    }

    @Test fun a_touch_during_a_boundary_settle_lands_on_the_next_section() = run { h ->
        val i = h.chapterEnd()
        val first = Finger(w * 9 / 10)
        first.move(w / 2, steps = 6, stepMs = 8)
        delay(120)
        first.up()
        val second = Finger(w / 2)
        delay(30)
        assertEquals(Pos(i + 1, 1), h.pos())
        second.up()
        h.quiet()
        assertEquals("the touch itself turned nothing", Pos(i + 1, 1), h.pos())
    }

    // ---- what stays as it was ----

    @Test fun with_no_animation_the_page_does_not_follow_and_the_turn_is_instant_on_release() = run { h ->
        val start = h.midChapter()
        h.sc.onActivity { it.controller.updateSettings { s -> s.copy(pageAnimation = PageAnimation.NONE) } }
        h.quiet()
        val offset = h.sc.hook("edges()").getDouble("start")
        val f = Finger(w * 9 / 10)
        f.move(w / 2)
        assertEquals("nothing moved under the finger", offset, h.sc.hook("edges()").getDouble("start"), 1.0)
        f.up()
        h.quiet()
        assertEquals(start.copy(page = start.page + 1), h.pos())
    }

    @Test fun a_tap_and_a_vertical_drag_turn_nothing() = run { h ->
        val start = h.midChapter()
        dev.click(w / 2, hgt / 2); delay(600)
        dev.click(w / 2, hgt / 2); h.quiet()
        assertEquals(start, h.pos())
        val f = Finger(w / 2, hgt / 4)
        f.move(w / 2, hgt * 3 / 4)
        assertEquals("not followed", start, h.pos())
        f.up(); h.quiet()
        assertEquals(start, h.pos())
    }

    @Test fun a_swipe_over_selected_text_neither_follows_nor_turns() = run { h ->
        val start = h.midChapter()
        val r = JSONObject(JSONTokener(h.sc.js("JSON.stringify(window.__readerTest.wordRect('the'))")).nextValue() as String)
        val loc = IntArray(2)
        h.sc.onActivity { it.reader.webView.getLocationOnScreen(loc) }
        val dpr = r.getDouble("dpr")
        val x = loc[0] + ((r.getDouble("x") + r.getDouble("w") / 2) * dpr).toInt()
        val y = loc[1] + ((r.getDouble("y") + r.getDouble("h") / 2) * dpr).toInt()
        h.drain()
        assertTrue(dev.swipe(x, y, x, y, 150)) // a long-press
        h.next(5_000) { e -> (e as? FromWeb.Selection)?.takeIf { it.cfi != null } }
        val before = h.sc.js("JSON.stringify(window.__readerTest.drag())")
        dev.swipe(w * 4 / 5, hgt * 3 / 4, w / 5, hgt * 3 / 4, 20)
        delay(800)
        assertEquals(start, h.pos())
        assertEquals("no drag", before.substringBefore("\\\"start"), h.sc.js("JSON.stringify(window.__readerTest.drag())").substringBefore("\\\"start"))
    }

    // ---- One at a time ----

    private suspend fun ReaderHarness.oneAtATime(label: String): Int {
        sc.onActivity { it.controller.updateSettings { s -> s.copy(flow = Flow.SCROLLED, scrollChapters = ScrollChapters.ONE_AT_A_TIME) } }
        still()
        val i = tocJump(label).sectionIndex
        still(); drain()
        withTimeout(15_000) {
            while (true) {
                val slots = sc.hook("slots()").getJSONArray("slots")
                if ((0 until slots.length()).count { slots.getJSONObject(it).let { s -> s.optBoolean("side") && s.optBoolean("loaded") } } >= 2) break
                delay(100)
            }
        }
        return i
    }

    @Test fun one_at_a_time_a_chapter_swipe_follows_and_can_be_cancelled() = run { h ->
        val i = h.oneAtATime("CHAPTER II")
        val top = h.sc.hook("edges()").getDouble("start")
        val moved = h.relocatesDuring(this) {
            val d = h.slowDrag(-1, 0.25) {
                assertEquals(listOf("chapter"), h.drag().kinds())
                assertTrue("the next chapter is on stage", h.drag().getBoolean("sliding"))
            }
            assertEquals(false, d.finished())
        }
        assertEquals(emptyList<FromWeb.Relocate>(), moved)
        val e = h.sc.hook("edges()")
        assertEquals(i, e.getInt("current"))
        assertEquals(top, e.getDouble("start"), 2.0)
        assertTrue("it scrolls again: nothing left of the stage", h.sc.hook("edges()").getInt("scrollHeight") > 0)
        assertFalse(h.drag().getBoolean("sliding"))
        val before = e.getDouble("start")
        dev.swipe(w / 2, hgt * 7 / 10, w / 2, hgt * 3 / 10, 40); h.still()
        assertTrue("vertical scrolling still works", h.sc.hook("edges()").getDouble("start") > before + 20)
    }

    @Test fun one_at_a_time_past_a_third_finishes_forward_and_back() = run { h ->
        val i = h.oneAtATime("CHAPTER II")
        assertEquals(true, h.slowDrag(-1, 0.5).finished())
        h.still(timeoutMs = 30_000)
        var e = h.sc.hook("edges()")
        assertEquals(i + 1, e.getInt("current"))
        assertEquals("at its top", 0.0, e.getDouble("start"), 2.0)
        h.drain()
        assertEquals(true, h.slowDrag(1, 0.5).finished())
        h.still(timeoutMs = 30_000)
        e = h.sc.hook("edges()")
        assertEquals(i, e.getInt("current"))
        assertEquals("at its end", e.getDouble("viewSize") - e.getDouble("viewport"), e.getDouble("start"), 2.0)
    }

    @Test fun one_at_a_time_a_touch_during_a_settle_keeps_both_swipes() = run { h ->
        val i = h.oneAtATime("CHAPTER II")
        val first = Finger(w * 9 / 10)
        first.move(w / 2, steps = 6, stepMs = 8)
        delay(120)
        first.up()
        val second = Finger(w * 9 / 10)
        second.move(w / 5)
        delay(250)
        second.up()
        h.relocate(15_000) { it.sectionIndex == i + 2 }
        h.still(timeoutMs = 30_000)
        assertEquals(i + 2, h.sc.hook("edges()").getInt("current"))
    }
}
