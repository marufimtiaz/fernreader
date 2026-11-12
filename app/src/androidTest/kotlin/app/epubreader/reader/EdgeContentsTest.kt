package app.epubreader.reader

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import app.epubreader.MainActivity
import app.epubreader.SlideFrames
import app.epubreader.core.data.Flow
import app.epubreader.core.data.PageAnimation
import app.epubreader.core.data.ReaderSettings
import app.epubreader.core.data.ScrollChapters
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.reader.bridge.ToWeb
import app.epubreader.ui.Copy
import app.epubreader.ui.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * C-097, C-102: Contents from the right edge, recognised by the app above the WebView. Motion events are injected one by one,
 * as in DragTurnTest. Probes: the controller's `contentsDrag` (every hold during a gesture: [holdsDuring]) and `tocVisible`,
 * the page's own touches (the paginator's trace: [touchesDuring]; a gesture the app took ends there with a `cancel`), and the
 * Contents tab's place on screen for the panel.
 *
 * Navigation: each test says which mode it needs; it is set before the activity starts and put back afterwards.
 *
 * Injected events go through the input dispatcher to SystemUI's Back detector like a finger's:
 * `a_straight_swipe_is_back_on_gesture_navigation` is the control, and the angled tests pass with the same detector
 * listening. C-103: the detector listens with the bars hidden too (Fullscreen), so the C-103 tests below run with them hidden.
 */
@RunWith(AndroidJUnit4::class)
class EdgeContentsTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val dev = UiDevice.getInstance(inst)
    private val w get() = dev.displayWidth
    private val hgt get() = dev.displayHeight
    private val strip get() = w - 12

    private data class Pos(val index: Int, val page: Int)
    private suspend fun ReaderHarness.pos() = sc.hook("page()").let { Pos(it.getInt("index"), it.getInt("page")) }
    /** Where a finger held the panel (0..1) during [block]. */
    private suspend fun ReaderHarness.holdsDuring(scope: CoroutineScope, block: suspend () -> Unit): List<Float> {
        val seen = mutableListOf<Float>()
        val watch = scope.launch(start = CoroutineStart.UNDISPATCHED) { a.controller.contentsDrag.collect { if (it != null) seen += it } }
        try { block() } finally { watch.cancel() }
        return seen
    }
    /** The touches the page saw during [block] (down, move, up, cancel), from the paginator's trace. */
    private suspend fun ReaderHarness.touchesDuring(block: suspend () -> Unit): List<String> {
        sc.js("window.__readerTest.traceStart(); true")
        try { block() } finally {
            val trace = JSONArray(JSONTokener(sc.js("window.__readerTest.traceStop()")).nextValue() as String)
            touches = (0 until trace.length()).map { trace.getJSONArray(it).getString(1) }.filter { it in TOUCH_KINDS }
        }
        return touches
    }
    private var touches = emptyList<String>()
    /** C-102: the page had the touch from its start and a cancel when the app took it; never its lift. */
    private fun List<String>.takenFromThePage() {
        assertEquals("the page saw the touch go down: $this", "down", firstOrNull())
        assertEquals("and then a cancel, nothing after it: $this", "cancel", lastOrNull())
        assertFalse("no lift reached the page: $this", "up" in this)
    }
    private suspend fun ReaderHarness.lastDrag(): String = sc.js("JSON.stringify(window.__readerTest.drag())")
    private val ReaderHarness.tocOpen get() = a.controller.state.value.tocVisible
    private val ReaderHarness.held get() = a.controller.contentsDrag.value

    private fun shell(cmd: String): String {
        val fd = inst.uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().decodeToString() }.trim()
    }
    private fun navMode() = shell("settings get secure navigation_mode")
    private fun setNav(mode: String) {
        shell("cmd overlay enable com.android.internal.systemui.navbar.$mode")
        val want = if (mode == GESTURAL) "2" else "0"
        val until = SystemClock.uptimeMillis() + 10_000
        while (navMode() != want) { check(SystemClock.uptimeMillis() < until) { "navigation mode stayed ${navMode()}" }; Thread.sleep(100) }
        Thread.sleep(2_500) // SystemUI rebuilds its bar and its Back detector after the setting
    }

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
    private val fingers = mutableListOf<Finger>()

    @Before fun liftAStrayFinger() {
        val now = SystemClock.uptimeMillis()
        val e = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 0f, 0f, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        inst.uiAutomation.injectInputEvent(e, true)
        e.recycle()
    }

    private fun run(nav: String = THREE_BUTTON, book: File? = null, settings: (ReaderSettings) -> ReaderSettings = { it }, block: suspend CoroutineScope.(ReaderHarness) -> Unit): Unit = runBlocking {
        val before = navMode()
        val wp = warAndPeace()
        try {
            setNav(nav)
            ActivityScenario.launch(MainActivity::class.java).use { sc ->
                val h = ReaderHarness(sc, this)
                try {
                    h.ready()
                    h.a.controller.updateSettings(settings)
                    h.a.settingsStored()
                    h.open(book ?: wp)
                    h.quiet()
                    block(h)
                } finally {
                    fingers.filterNot { it.lifted }.forEach { it.up() }
                    sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                    h.a.settingsStored()
                    h.stop()
                }
            }
        } finally {
            setNav(if (before == "2") GESTURAL else THREE_BUTTON)
        }
    }

    private suspend fun ReaderHarness.midChapter(): Pos {
        val toc = a.controller.state.value.toc.filter { !it.missing && it.index >= 0 }
        tocJump(toc[toc.size / 3].label)
        quiet()
        return pos()
    }

    private suspend fun ReaderHarness.relocatesDuring(scope: CoroutineScope, block: suspend () -> Unit): List<FromWeb.Relocate> {
        val moves = mutableListOf<FromWeb.Relocate>()
        val watch = scope.launch(start = CoroutineStart.UNDISPATCHED) { a.reader.events.filterIsInstance<FromWeb.Relocate>().collect { moves += it } }
        try { block() } finally { watch.cancel() }
        return moves
    }

    /** The left of the Contents tab: the panel's left edge and a little; null when it isn't on screen. */
    private fun panelLeft(): Int? {
        inst.uiAutomation.clearCache()
        // A panel that has just slid out is gone between the find and the read of its bounds: it isn't on screen.
        return try { dev.findObject(By.text(Copy.contents))?.visibleBounds?.left } catch (_: StaleObjectException) { null }
    }
    private suspend fun awaitPanelOpen() = withTimeout(3_000) { while ((panelLeft() ?: w) > w / 8) delay(30) }
    private suspend fun awaitPanelGone() = withTimeout(3_000) { while (panelLeft() != null) delay(30) }
    private suspend fun ReaderHarness.awaitOpen() { withTimeout(3_000) { while (!tocOpen) delay(20) }; awaitPanelOpen() }
    private suspend fun ReaderHarness.closed() { assertFalse(tocOpen); assertNull(held); awaitPanelGone() }

    /** A slow drag from the strip to [toX], held still before the lift (so no flick). */
    private suspend fun slowDrag(toX: Int, at: suspend () -> Unit = {}) {
        val f = Finger(strip)
        f.move(toX, steps = 24)
        delay(150)
        at()
        delay(250)
        f.up()
        delay(100)
    }

    /** A flick over 48 ms, with its times fixed up front. */
    private fun flick(from: Int, to: Int, y: Float = hgt / 2f) {
        val down = SystemClock.uptimeMillis() - 48
        val events = (0..3).map { i ->
            val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; 3 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            MotionEvent.obtain(down, down + i * 16L, action, from + (to - from) * i / 3f, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        }
        for (e in events) { check(inst.uiAutomation.injectInputEvent(e, false)) { "injection refused: $e" }; e.recycle() }
    }

    /** The panel's positions seen during the next [ms]. */
    private fun sampleLeft(ms: Long): List<Int?> {
        val seen = mutableListOf<Int?>()
        val until = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < until) seen += panelLeft()
        return seen
    }
    private fun List<Int?>.inBetween() = filterNotNull().any { it in (w / 8)..(w - w / 16) }

    // ---- the gesture (button navigation: nothing of the system's is in the way) ----

    @Test fun the_panel_follows_the_finger_and_opens_past_a_third() = run { h ->
        val start = h.midChapter()
        val drag0 = h.lastDrag()
        val moved = h.relocatesDuring(this) {
            h.touchesDuring { slowDrag(w / 2) {
                assertFalse("the finger is down", h.tocOpen)
                assertEquals("the panel's edge is under the finger", 0.5f, assertHeld(h), 0.05f)
                val left = withTimeout(2_000) { var l = panelLeft(); while (l == null) { delay(30); l = panelLeft() }; l }
                assertTrue("the panel is where the finger is: $left of $w", abs(left - w / 2) < w / 6)
            } }.takenFromThePage()
            h.awaitOpen()
        }
        assertEquals(start, h.pos())
        assertEquals(emptyList<FromWeb.Relocate>(), moved)
        assertEquals("no page drag", drag0, h.lastDrag())
    }
    private fun assertHeld(h: ReaderHarness): Float { val v = h.held; assertNotNull("a finger holds the panel", v); return v!! }

    @Test fun short_of_a_third_it_slides_back() = run { h ->
        val start = h.midChapter()
        slowDrag(w * 8 / 10) { assertHeld(h) }
        delay(400)
        h.closed()
        assertEquals(start, h.pos())
    }

    @Test fun a_flick_opens_it() = run { h ->
        val start = h.midChapter()
        // The four events arrive at once, so the hold may be set and let go between two looks at the controller (a StateFlow
        // keeps the last value only): what shows that the app took it is the page's cancel. The finger never got a third in.
        val holds = h.holdsDuring(this) {
            h.touchesDuring {
                flick(strip, w * 8 / 10)
                h.awaitOpen()
            }.takenFromThePage()
        }
        assertTrue("held, under a third: $holds", holds.all { it < 0.33f })
        assertEquals(start, h.pos())
    }

    @Test fun no_page_turn_from_the_strip_whatever_the_gesture() = run { h ->
        val start = h.midChapter()
        val drag0 = h.lastDrag()
        val moved = h.relocatesDuring(this) {
            Finger(strip).run { move(strip, hgt * 3 / 4); up() } // down the strip
            delay(300)
            Finger(strip).run { move(w - 2); up() } // right
            delay(300)
            slowDrag(w * 8 / 10) // left, cancelled
            delay(400)
            h.closed()
        }
        assertEquals(start, h.pos())
        assertEquals(emptyList<FromWeb.Relocate>(), moved)
        assertEquals("no page drag", drag0, h.lastDrag())
    }

    @Test fun taps_in_the_strip_stay_page_turns() = run(settings = { it.copy(tapZones = true) }) { h ->
        val start = h.midChapter()
        val at = w - 50 // about 19 dp from the edge
        val under = h.sc.js("document.elementFromPoint(innerWidth * $at / $w, innerHeight / 2)?.tagName")
        val outer = h.sc.js("document.elementFromPoint(innerWidth - 2, innerHeight / 2)?.tagName")
        val holds = h.holdsDuring(this) {
            assertEquals("the page has the whole tap", listOf("down", "up"), h.touchesDuring { dev.click(at, hgt / 2); delay(300) })
            h.quiet()
        }
        assertEquals("under the tap: $under, at the very edge: $outer", start.copy(page = start.page + 1), h.pos())
        assertEquals(emptyList<Float>(), holds)
        assertFalse(h.tocOpen)
    }

    @Test fun in_a_right_to_left_book_it_is_the_right_edge_too_and_turns_nothing() = run(book = File("/sdcard/Books/epub3-samples/regime-anticancer-arabic.epub")) { h ->
        val start = h.pos()
        slowDrag(w / 2)
        h.awaitOpen()
        assertEquals(start, h.pos())
    }

    /** A long press in the strip is the page's (it selects text there): the drag after it is the page's too. */
    @Test fun a_long_press_in_the_strip_then_a_drag_stays_the_pages() = run { h ->
        h.midChapter()
        val holds = h.holdsDuring(this) {
            val seen = h.touchesDuring {
                val f = Finger(strip)
                delay(900)
                f.move(w / 2, steps = 24)
                delay(150)
                f.up()
                delay(300)
            }
            assertFalse("no cancel: $seen", "cancel" in seen)
            assertEquals("the page had the lift: $seen", "up", seen.lastOrNull())
        }
        assertEquals(emptyList<Float>(), holds)
        assertFalse(h.tocOpen)
    }

    // ---- scrolled flow ----

    private suspend fun ReaderHarness.scrollTop() = sc.hook("edges()").getDouble("scrollTop")

    /** Up the strip for a while, then left: the scroll runs until the sideways part starts and stands from then on. */
    private suspend fun ReaderHarness.scrollThenSideways() {
        val top0 = scrollTop()
        val seen = touchesDuring { scrollThenSidewaysWith(Finger(strip, hgt * 3 / 4), top0) }
        seen.takenFromThePage()
        // Chromium passes a native scroll's touchmoves on to the page at a throttled rate; the scroll itself is measured above.
        assertTrue("the page had the scroll's moves: $seen", seen.count { it == "move" } >= 3)
    }
    private suspend fun ReaderHarness.scrollThenSidewaysWith(f: Finger, top0: Double) {
        f.move(strip, hgt / 3, steps = 24)
        delay(300)
        val top1 = scrollTop()
        assertTrue("the strip scrolls: $top0 -> $top1", top1 > top0 + 50)
        assertNull("no panel yet", held)
        f.move(w / 2, hgt / 3 - 8, steps = 12)
        delay(250)
        assertHeld(this)
        val top2 = scrollTop()
        f.move(w / 3, hgt / 5, steps = 12) // a stopped scroll stays stopped when the finger drifts
        delay(250)
        assertEquals("the scroll stopped", top2, scrollTop(), 1.0)
        f.up()
        awaitOpen()
        delay(400)
        assertEquals("no fling behind the panel", top2, scrollTop(), 1.0)
    }

    @Test fun a_scroll_in_the_strip_scrolls_until_it_goes_sideways() = run(settings = { it.copy(flow = Flow.SCROLLED) }) { h ->
        h.midChapter()
        h.scrollThenSideways()
    }

    @Test fun one_at_a_time_never_a_chapter_swipe_from_the_strip() = run(settings = { it.copy(flow = Flow.SCROLLED, scrollChapters = ScrollChapters.ONE_AT_A_TIME) }) { h ->
        val start = h.midChapter()
        slowDrag(w / 2)
        h.awaitOpen()
        h.sc.onActivity { it.controller.hideToc() }
        delay(500)
        h.closed()
        assertEquals(start.index, h.pos().index)
        h.scrollThenSideways()
        assertEquals(start.index, h.pos().index)
    }

    // ---- C-100: the start of the slide ----

    /** The panel comes from the screen edge and catches up with the finger; it never pops in where the finger is. */
    @Test fun the_panel_starts_from_the_edge_and_catches_up_with_the_finger() = run { h ->
        val start = h.midChapter()
        val rec = SlideFrames(inst, "contents")
        rec.record()
        rec.drag(strip, hgt / 2, dir = -1)
        rec.assertSmoothFromTheEdge { x -> w - x }
        delay(600)
        h.closed()
        assertEquals(start, h.pos())
    }

    /** The same after a scroll in the strip: there the finger is well in when the sideways part starts. */
    @Test fun after_a_scroll_in_the_strip_it_starts_from_the_edge_too() = run(settings = { it.copy(flow = Flow.SCROLLED) }) { h ->
        h.midChapter()
        val f = Finger(strip, hgt * 3 / 4)
        f.move(strip - 30, hgt / 2, steps = 16)
        delay(200)
        assertNull("no panel yet", h.held)
        val rec = SlideFrames(inst, "contents")
        rec.record()
        val x0 = f.x
        f.move(x0 - 240, f.y - 8, steps = 40)
        delay(400)
        rec.stop()
        val seen = rec.frames.filter { it.edge > 0.5f }
        val trace = seen.joinToString(" ") { "%.0f".format(it.edge) }
        assertTrue("the panel came out: $trace", seen.size >= 5)
        assertTrue("first visible frame at ${seen.first().edge} px, the finger is ${w - x0} px in before it moves sideways: $trace", seen.first().edge <= 2 * 240f / 40 + 1)
        seen.zipWithNext().forEach { (a, b) -> assertTrue("never back: ${a.edge} -> ${b.edge} in $trace", b.edge >= a.edge - 0.5f) }
        assertEquals("caught up: $trace", (w - f.x).toFloat(), seen.last().edge, 3f)
        f.up()
    }

    @Test fun the_button_slide_starts_from_the_edge() = run { h ->
        val scale = shell("settings get global animator_duration_scale")
        val rec = SlideFrames(inst, "contents")
        try {
            shell("settings put global animator_duration_scale 1")
            delay(500)
            rec.record()
            h.sc.onActivity { it.controller.showToc() }
            awaitPanelOpen()
            delay(200)
        } finally {
            rec.stop()
            shell("settings put global animator_duration_scale ${scale.takeUnless { it == "null" } ?: "1"}")
        }
        rec.assertSlidesInFromTheEdge(w)
    }

    // ---- the slide ----

    /** UiAutomator takes longer per look than the slide lasts, so the animator is slowed down ten times for these looks. */
    @Test fun button_close_and_back_slide() = run { h ->
        val scale = shell("settings get global animator_duration_scale")
        try {
            shell("settings put global animator_duration_scale 10")
            delay(500)
            h.sc.onActivity { it.controller.showToc() }
            val inn = sampleLeft(3_300)
            assertTrue("in from the right: $inn", inn.inBetween())
            awaitPanelOpen()
            inst.uiAutomation.clearCache()
            dev.findObject(By.text(Copy.close)).visibleCenter.let { dev.click(it.x, it.y) } // a node can go stale in a slide
            val out = sampleLeft(3_300)
            assertFalse(h.tocOpen)
            assertTrue("out to the right: $out", out.inBetween())
            awaitPanelGone()

            h.sc.onActivity { it.controller.showToc() }
            sampleLeft(3_300)
            awaitPanelOpen()
            dev.pressBack()
            val back = sampleLeft(3_300)
            assertTrue("Back: $back", back.inBetween())
            awaitPanelGone()
            assertEquals("still in the book", Route.Reader, h.a.nav.top)
        } finally {
            shell("settings put global animator_duration_scale ${scale.takeUnless { it == "null" } ?: "1"}")
        }
    }

    @Test fun a_rightward_swipe_on_the_panel_closes_it_a_short_one_does_not() = run { h ->
        h.sc.onActivity { it.controller.showToc() }
        awaitPanelOpen()
        val short = Finger(w / 4, hgt * 2 / 3)
        short.move(w * 2 / 5, steps = 16)
        delay(250)
        assertHeld(h)
        short.up()
        delay(600)
        assertTrue(h.tocOpen)
        awaitPanelOpen()
        val long = Finger(w / 4, hgt * 2 / 3)
        long.move(w * 3 / 4, steps = 16)
        delay(250)
        long.up()
        withTimeout(2_000) { while (h.tocOpen) delay(20) }
        awaitPanelGone()
    }

    @Test fun with_animations_off_the_panel_just_appears_and_opens_on_release() = run(settings = { it.copy(pageAnimation = PageAnimation.NONE) }) { h ->
        slowDrag(w / 2) {
            assertNull("nothing follows the finger", h.held)
            assertNull(panelLeft())
        }
        val seen = sampleLeft(300).filterNotNull()
        assertTrue("it appears open: $seen", seen.isNotEmpty() && seen.all { it < w / 8 })
        assertTrue(h.tocOpen)
        h.sc.onActivity { it.controller.hideToc() }
        delay(150)
        assertNull(panelLeft())
    }

    // ---- gesture navigation: what the Back detector does with injected events ----

    private val barsShown: (ReaderSettings) -> ReaderSettings = { it.copy(fullscreen = false) }

    /** The control: Back takes it, before the page sees a move. Without this the two tests below would prove nothing. */
    @Test fun a_straight_swipe_is_back_on_gesture_navigation() = run(nav = GESTURAL, settings = barsShown) { h ->
        val holds = h.holdsDuring(this) {
            Finger(strip).run { move(w / 2, steps = 24); delay(250); up() }
            withTimeout(5_000) { while (h.a.nav.top == Route.Reader) delay(20) }
        }
        assertEquals("the app never saw it as a gesture", emptyList<Float>(), holds)
        assertFalse(h.tocOpen)
    }

    @Test fun an_angled_swipe_reaches_the_app_on_gesture_navigation() = run(nav = GESTURAL, settings = barsShown) { h ->
        val start = h.midChapter()
        h.touchesDuring {
            val f = Finger(strip, hgt * 2 / 5)
            f.move(w * 2 / 5, hgt * 4 / 5, steps = 30) // 0.6 w left, 0.4 h down: more down than sideways all the way
            delay(250)
            assertHeld(h)
            f.up()
        }.takenFromThePage()
        h.awaitOpen()
        assertEquals(Route.Reader, h.a.nav.top)
        assertEquals(start, h.pos())
    }

    @Test fun slightly_down_then_sideways_reaches_the_app_on_gesture_navigation() = run(nav = GESTURAL, settings = barsShown) { h ->
        val f = Finger(strip, hgt * 2 / 5)
        f.move(strip, hgt * 2 / 5 + 60, steps = 6)
        f.move(w / 2, steps = 16)
        delay(250)
        assertHeld(h)
        f.up()
        h.awaitOpen()
        assertEquals(Route.Reader, h.a.nav.top)
    }

    /**
     * C-100c: a swipe from the user's phone, from a screen recording with the touches shown (27.6 s in it): where the finger
     * was, ms after the touch-down, as dp from the right edge and from the top. Up and left, twice as far up as left; held
     * still 0.45 s before the lift. Fullscreen and gesture navigation, as on the phone. It never opened the panel there while
     * the page recognised the gesture (C-100c, C-101); now the app does (C-102), with C-101's angle from the touch-down.
     */
    @Test fun a_steep_swipe_recorded_on_the_phone_opens_it() = run(nav = GESTURAL) { h ->
        val start = h.midChapter()
        val path = listOf(
            0 to (8.4f to 646.9f), 11 to (18.9f to 623.6f), 20 to (33.1f to 591.6f), 31 to (48f to 559.3f), 43 to (60f to 536.4f),
            53 to (72.7f to 512f), 59 to (86.2f to 488f), 69 to (96f to 462.5f), 80 to (108.4f to 434.2f), 91 to (121.1f to 406.5f),
            103 to (128.7f to 393.1f), 114 to (140.7f to 371.3f), 122 to (145.1f to 365.8f),
        )
        val holds = h.holdsDuring(this) { h.touchesDuring { replay(h, path, upMs = 589) }.takenFromThePage(); h.awaitOpen() }
        assertTrue("the panel followed the finger: $holds", holds.isNotEmpty())
        assertEquals(Route.Reader, h.a.nav.top)
        assertEquals(start, h.pos())
    }

    /**
     * Replays [path] (ms after the touch-down to where the finger was: dp from the right edge, dp from the top) with a move
     * every 8 ms between the points, as a phone's touch screen reports them, and lifts at [upMs] where it last was. An
     * [upMs] at the last point's time lifts there while still moving, as a finger does at the end of a flick.
     */
    private suspend fun replay(h: ReaderHarness, path: List<Pair<Int, Pair<Float, Float>>>, upMs: Int) = withContext(Dispatchers.IO) {
        val d = h.a.resources.displayMetrics.density
        val down = SystemClock.uptimeMillis() + 20
        fun send(action: Int, ms: Int, inDp: Float, yDp: Float) {
            while (SystemClock.uptimeMillis() < down + ms) Thread.sleep(1)
            val e = MotionEvent.obtain(down, down + ms, action, w - inDp * d, yDp * d, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            check(inst.uiAutomation.injectInputEvent(e, false)) { "injection refused: $e" }
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 0, path[0].second.first, path[0].second.second)
        for ((a, b) in path.zipWithNext()) {
            var ms = a.first + 8
            while (ms < b.first) {
                val f = (ms - a.first).toFloat() / (b.first - a.first)
                send(MotionEvent.ACTION_MOVE, ms, a.second.first + (b.second.first - a.second.first) * f, a.second.second + (b.second.second - a.second.second) * f)
                ms += 8
            }
            if (b !== path.last() || upMs > b.first) send(MotionEvent.ACTION_MOVE, b.first, b.second.first, b.second.second)
        }
        val (inDp, yDp) = path.last().second
        send(MotionEvent.ACTION_UP, upMs, inDp, yDp)
    }

    /**
     * C-101, C-102: the user's swipe on the phone as measured there: from about 8 dp in from the right edge, about 113 dp
     * left and 240 dp up in about 300 ms (0.47 left per unit down), lifted while moving. Built from those numbers at an even
     * speed (the recording itself is gone); no 20 dp step of it is at the step rule's 0.6, so the angle from the touch-down
     * takes it, 24 dp in. Short of a third of the width, it opens as a flick (about 0.38 dp/ms).
     */
    private fun ReaderHarness.usersSwipe(): List<Pair<Int, Pair<Float, Float>>> {
        val d = a.resources.displayMetrics.density
        val top = hgt / d * 0.7f
        return listOf(0, 300).map { ms -> ms to (8f + 113f * ms / 300 to top - 240f * ms / 300) }
    }
    private suspend fun ReaderHarness.usersSwipeOpensIt(scope: CoroutineScope) {
        val start = midChapter()
        val holds = holdsDuring(scope) {
            touchesDuring { replay(this, usersSwipe(), upMs = 300) }.takenFromThePage()
            awaitOpen()
        }
        assertTrue("the panel followed the finger: $holds", holds.isNotEmpty())
        assertEquals(Route.Reader, a.nav.top)
        assertEquals(start, pos())
    }

    @Test fun the_users_swipe_from_the_phone_opens_it_fullscreen_on_gesture_navigation() = run(nav = GESTURAL) { h -> h.usersSwipeOpensIt(this) }

    @Test fun the_users_swipe_from_the_phone_opens_it_on_button_navigation() = run { h -> h.usersSwipeOpensIt(this) }

    // ---- C-102: the app's recogniser, in the WebView's parent, takes a gesture the page already has ----

    /**
     * The page has the touch first: its down and the moves of a straight 40 dp downward start (no sideways part yet, so
     * the app leaves it alone). Then the finger goes left: the app takes it there, the page gets a cancel and nothing after
     * it, the panel follows and opens, and the page turned nothing.
     */
    private suspend fun ReaderHarness.takenFromThePageMidGesture(scope: CoroutineScope) {
        val start = midChapter()
        val d = a.resources.displayMetrics.density
        val moved = relocatesDuring(scope) {
            val seen = touchesDuring {
                val f = Finger(strip, hgt / 3)
                f.move(strip, hgt / 3 + (40 * d).toInt(), steps = 10)
                delay(250)
                assertNull("the app hasn't taken it yet", held)
                f.move(w / 2, f.y + 8, steps = 16)
                delay(250)
                assertHeld(this)
                f.up()
            }
            seen.takenFromThePage()
            val before = seen.subList(0, seen.size - 1)
            assertTrue("the page had the moves before the app took it: $seen", before.count { it == "move" } >= 5)
            awaitOpen()
        }
        assertEquals(Route.Reader, a.nav.top)
        assertEquals(start, pos())
        assertEquals(emptyList<FromWeb.Relocate>(), moved)
    }

    @Test fun the_app_takes_a_gesture_the_page_had_on_button_navigation() = run { h -> h.takenFromThePageMidGesture(this) }

    /** Fullscreen (the bars hidden): the downward start passes the Back detector, so the whole gesture reaches the app. */
    @Test fun the_app_takes_a_gesture_the_page_had_on_gesture_navigation_with_the_bars_hidden() = run(nav = GESTURAL) { h -> h.takenFromThePageMidGesture(this) }

    // ---- C-103: Fullscreen on gesture navigation keeps the system's Back on both edges ----

    private fun ReaderHarness.barsVisible(): Boolean {
        var v = false
        sc.onActivity { v = ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: false }
        return v
    }
    private suspend fun ReaderHarness.awaitBars(visible: Boolean, withinMs: Long = 3_000) {
        val until = SystemClock.uptimeMillis() + withinMs
        while (barsVisible() != visible) { assertTrue("the bars ${if (visible) "shown" else "hidden"}", SystemClock.uptimeMillis() < until); delay(30) }
    }

    /** A straight pull in from an edge with the bars hidden: Back, before the page turns anything. */
    private suspend fun ReaderHarness.straightPullIsBack(scope: CoroutineScope, fromX: Int, toX: Int) {
        awaitBars(false)
        midChapter()
        val holds = holdsDuring(scope) {
            val moved = relocatesDuring(scope) {
                val backs = backsDuring {
                    Finger(fromX).run { move(toX, steps = 24); delay(250); up() }
                    withTimeout(5_000) { while (a.nav.top == Route.Reader) delay(20) }
                }
                assertEquals("one Back", 1, backs)
            }
            assertEquals("no page turn", emptyList<FromWeb.Relocate>(), moved)
        }
        assertEquals("the app never saw it as a gesture", emptyList<Float>(), holds)
        assertFalse(tocOpen)
    }

    @Test fun with_the_bars_hidden_a_straight_pull_from_the_left_edge_is_back() = run(nav = GESTURAL) { h -> h.straightPullIsBack(this, 12, w / 2) }

    @Test fun with_the_bars_hidden_a_straight_pull_from_the_right_edge_is_back() = run(nav = GESTURAL) { h -> h.straightPullIsBack(this, strip, w / 2) }

    /**
     * How many times the system's Back reached the activity during [block]: a callback ahead of the app's counts it and
     * hands it on. (A rightward swipe on the open panel closes it too, by the app: this tells the two apart.)
     */
    private suspend fun ReaderHarness.backsDuring(block: suspend () -> Unit): Int {
        var n = 0
        var cb: androidx.activity.OnBackPressedCallback? = null
        sc.onActivity { act ->
            cb = object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { n++; isEnabled = false; act.onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }.also { act.onBackPressedDispatcher.addCallback(it) }
        }
        try { block() } finally { sc.onActivity { cb?.remove() } }
        return n
    }

    /** Back with Contents open closes it first, from either edge; the reader stays. */
    @Test fun with_the_bars_hidden_back_closes_contents_first() = run(nav = GESTURAL) { h ->
        h.awaitBars(false)
        for (fromX in listOf(12, strip)) {
            h.sc.onActivity { it.controller.showToc() }
            h.awaitOpen()
            // The panel's own swipe may see the first moves before the system takes the gesture (as with the bars shown):
            // what closes it is the one Back.
            val backs = h.backsDuring {
                Finger(fromX).run { move(w / 2, steps = 24); delay(250); up() }
                withTimeout(3_000) { while (h.tocOpen) delay(20) }
            }
            assertEquals("from $fromX: one Back", 1, backs)
            awaitPanelGone()
            assertEquals("still in the book", Route.Reader, h.a.nav.top)
        }
    }

    @Test fun with_the_bars_hidden_an_angled_pull_from_the_right_still_opens_contents() = run(nav = GESTURAL) { h ->
        h.awaitBars(false)
        val start = h.midChapter()
        h.touchesDuring {
            val f = Finger(strip, hgt * 2 / 5)
            f.move(w * 2 / 5, hgt * 4 / 5, steps = 30)
            delay(250)
            assertHeld(h)
            f.up()
        }.takenFromThePage()
        h.awaitOpen()
        assertEquals(Route.Reader, h.a.nav.top)
        assertEquals(start, h.pos())
    }

    // ---- C-104: a top swipe brings back only the bars; they hide by themselves or at the next tap ----

    /** A swipe down from the top over the fullscreen reader: the bars come back and the controls stay hidden. */
    private suspend fun ReaderHarness.swipeTheBarsIn() {
        awaitBars(false)
        dev.swipe(w / 2, 1, w / 2, hgt / 3, 20)
        awaitBars(true)
    }

    /** C-104: the bars come back without the controls, stay a while, and hide again by themselves (about 3 s). */
    @Test fun a_top_swipe_brings_back_only_the_bars_and_they_hide_after_the_timeout() = run(nav = GESTURAL) { h ->
        val start = h.midChapter()
        h.awaitBars(false)
        dev.swipe(w / 2, 1, w / 2, hgt / 3, 20)
        h.awaitBars(true)
        val shownAt = SystemClock.uptimeMillis()
        // Watch until well into the timeout: the controls never come.
        while (SystemClock.uptimeMillis() - shownAt < 1_500) {
            assertFalse("no controls with the bars", h.a.controller.state.value.chromeVisible)
            delay(30)
        }
        assertTrue("the bars stay a while", h.barsVisible())
        h.awaitBars(false, withinMs = BARS_REVEAL_MS + 2_000)
        assertFalse(h.a.controller.state.value.chromeVisible)
        assertEquals("no page turn", start, h.pos())
    }

    /** C-104: a tap on the page while the bars show only hides them: no controls, no page turn. */
    @Test fun a_tap_while_the_swiped_in_bars_show_only_hides_them() = run(nav = GESTURAL, settings = { it.copy(tapZones = true) }) { h ->
        val start = h.midChapter()
        for (x in listOf(w / 2, w * 9 / 10)) { // the centre (controls) and the next-page zone
            h.swipeTheBarsIn()
            dev.click(x, hgt / 2)
            h.awaitBars(false, withinMs = 1_500) // well before the timeout
            delay(300)
            assertFalse("at $x: no controls", h.a.controller.state.value.chromeVisible)
            assertEquals("at $x: no page turn", start, h.pos())
        }
    }

    /** C-104: opening the controls while the bars show works as usual, and the bars then hide with the controls. */
    @Test fun the_controls_opened_while_the_swiped_in_bars_show_take_the_bars() = run(nav = GESTURAL) { h ->
        h.midChapter()
        h.swipeTheBarsIn()
        h.sc.onActivity { it.controller.toggleChrome() }
        delay(BARS_REVEAL_MS + 1_000)
        assertTrue("the controls stay", h.a.controller.state.value.chromeVisible)
        assertTrue("the bars stay with them", h.barsVisible())
        dev.click(w / 2, hgt / 2)
        withTimeout(3_000) { while (h.a.controller.state.value.chromeVisible) delay(20) }
        h.awaitBars(false)
    }

    /** The app reserves no edge from Back while reading: no view in the window asks for a gesture exclusion. */
    @Test fun no_gesture_exclusion_while_reading() = run(nav = GESTURAL) { h ->
        h.midChapter()
        h.sc.onActivity { it.controller.showToc() }
        h.awaitOpen()
        h.sc.onActivity { it.controller.hideToc() }
        awaitPanelGone()
        var found = emptyList<String>()
        h.sc.onActivity { a ->
            val seen = mutableListOf<String>()
            fun walk(v: android.view.View) {
                if (v.systemGestureExclusionRects.isNotEmpty()) seen += "${v.javaClass.simpleName}: ${v.systemGestureExclusionRects}"
                if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(a.window.decorView)
            found = seen
        }
        assertEquals(emptyList<String>(), found)
    }

    private companion object {
        val TOUCH_KINDS = setOf("down", "move", "up", "cancel")
        const val GESTURAL = "gestural"
        const val THREE_BUTTON = "threebutton"
    }
}
