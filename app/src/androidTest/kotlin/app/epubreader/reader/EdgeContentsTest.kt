package app.epubreader.reader

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
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
import org.json.JSONObject
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
 * C-097: Contents from the right edge. Motion events are injected one by one, as in DragTurnTest. Probes: `edge()` (what the
 * page last reported), the controller's `contentsDrag` and `tocVisible`, and the Contents tab's place on screen for the panel.
 *
 * Navigation: each test says which mode it needs; it is set before the activity starts and put back afterwards.
 *
 * Injected events go through the input dispatcher to SystemUI's Back detector like a finger's:
 * `a_straight_swipe_is_back_on_gesture_navigation` is the control, and the angled tests pass with the same detector
 * listening. The detector only listens while the bars are shown: those tests turn Fullscreen off.
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
    private suspend fun ReaderHarness.edge() = sc.hook("edge()")
    private suspend fun ReaderHarness.starts() = edge().getInt("starts")
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
            slowDrag(w / 2) {
                assertFalse("the finger is down", h.tocOpen)
                assertEquals("the panel's edge is under the finger", 0.5f, assertHeld(h), 0.05f)
                val left = withTimeout(2_000) { var l = panelLeft(); while (l == null) { delay(30); l = panelLeft() }; l }
                assertTrue("the panel is where the finger is: $left of $w", abs(left - w / 2) < w / 6)
            }
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
        assertFalse(h.edge().getBoolean("open"))
        delay(400)
        h.closed()
        assertEquals(start, h.pos())
    }

    @Test fun a_flick_opens_it() = run { h ->
        val start = h.midChapter()
        flick(strip, w * 8 / 10)
        h.awaitOpen()
        assertTrue("under a third: ${h.edge()}", h.edge().getDouble("fraction") < 0.33)
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
        val s0 = h.starts()
        val at = w - 50 // about 19 dp from the edge
        val under = h.sc.js("document.elementFromPoint(innerWidth * $at / $w, innerHeight / 2)?.tagName")
        val outer = h.sc.js("document.elementFromPoint(innerWidth - 2, innerHeight / 2)?.tagName")
        dev.click(at, hgt / 2)
        h.quiet()
        assertEquals("under the tap: $under, at the very edge: $outer", start.copy(page = start.page + 1), h.pos())
        assertEquals(s0, h.starts())
        assertFalse(h.tocOpen)
    }

    @Test fun in_a_right_to_left_book_it_is_the_right_edge_too_and_turns_nothing() = run(book = File("/sdcard/Books/epub3-samples/regime-anticancer-arabic.epub")) { h ->
        val start = h.pos()
        slowDrag(w / 2)
        h.awaitOpen()
        assertEquals(start, h.pos())
    }

    // ---- scrolled flow ----

    private suspend fun ReaderHarness.scrollTop() = sc.hook("edges()").getDouble("scrollTop")

    /** Up the strip for a while, then left: the scroll runs until the sideways part starts and stands from then on. */
    private suspend fun ReaderHarness.scrollThenSideways() {
        val top0 = scrollTop()
        val f = Finger(strip, hgt * 3 / 4)
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
        val s0 = h.starts()
        Finger(strip).run { move(w / 2, steps = 24); delay(250); up() }
        withTimeout(5_000) { while (h.a.nav.top == Route.Reader) delay(20) }
        assertEquals("the page never saw it as a gesture", s0, h.starts())
        assertFalse(h.tocOpen)
    }

    @Test fun an_angled_swipe_reaches_the_app_on_gesture_navigation() = run(nav = GESTURAL, settings = barsShown) { h ->
        val start = h.midChapter()
        val f = Finger(strip, hgt * 2 / 5)
        f.move(w * 2 / 5, hgt * 4 / 5, steps = 30) // 0.6 w left, 0.4 h down: more down than sideways all the way
        delay(250)
        assertHeld(h)
        f.up()
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
     * still 0.45 s before the lift. Fullscreen and gesture navigation, as on the phone. It never opened the panel there.
     */
    @Test fun a_steep_swipe_recorded_on_the_phone_opens_it() = run(nav = GESTURAL) { h ->
        val start = h.midChapter()
        val d = h.a.resources.displayMetrics.density
        val path = listOf(
            0 to (8.4f to 646.9f), 11 to (18.9f to 623.6f), 20 to (33.1f to 591.6f), 31 to (48f to 559.3f), 43 to (60f to 536.4f),
            53 to (72.7f to 512f), 59 to (86.2f to 488f), 69 to (96f to 462.5f), 80 to (108.4f to 434.2f), 91 to (121.1f to 406.5f),
            103 to (128.7f to 393.1f), 114 to (140.7f to 371.3f), 122 to (145.1f to 365.8f),
        )
        val down = SystemClock.uptimeMillis() + 20
        fun send(action: Int, ms: Int, inDp: Float, yDp: Float) {
            while (SystemClock.uptimeMillis() < down + ms) Thread.sleep(1)
            val e = MotionEvent.obtain(down, down + ms, action, w - inDp * d, yDp * d, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            check(inst.uiAutomation.injectInputEvent(e, false)) { "injection refused: $e" }
            e.recycle()
        }
        // Every 8 ms between the recorded points, as a phone's touch screen reports them.
        val s0 = h.starts()
        withContext(Dispatchers.IO) {
            send(MotionEvent.ACTION_DOWN, 0, path[0].second.first, path[0].second.second)
            for ((a, b) in path.zipWithNext()) {
                var ms = a.first + 8
                while (ms < b.first) {
                    val f = (ms - a.first).toFloat() / (b.first - a.first)
                    send(MotionEvent.ACTION_MOVE, ms, a.second.first + (b.second.first - a.second.first) * f, a.second.second + (b.second.second - a.second.second) * f)
                    ms += 8
                }
                send(MotionEvent.ACTION_MOVE, b.first, b.second.first, b.second.second)
            }
            val (inDp, yDp) = path.last().second
            send(MotionEvent.ACTION_UP, 589, inDp, yDp)
        }
        h.awaitOpen()
        assertEquals("one gesture", s0 + 1, h.starts())
        assertEquals(Route.Reader, h.a.nav.top)
        assertEquals(start, h.pos())
    }

    /** With the bars hidden (Fullscreen, the default) the system has no Back gesture, so a straight swipe reaches the page. */
    @Test fun with_the_bars_hidden_a_straight_swipe_opens_it_on_gesture_navigation_too() = run(nav = GESTURAL) { h ->
        slowDrag(w / 2)
        h.awaitOpen()
        assertEquals(Route.Reader, h.a.nav.top)
    }

    private companion object {
        const val GESTURAL = "gestural"
        const val THREE_BUTTON = "threebutton"
    }
}
