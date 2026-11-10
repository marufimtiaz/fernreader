package app.epubreader.library

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import app.epubreader.MainActivity
import app.epubreader.SlideFrames
import app.epubreader.ui.Copy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.tan
import java.util.regex.Pattern
import kotlin.math.abs

/**
 * C-098: the drawer from the left edge. Motion events are injected one by one, as in EdgeContentsTest, and go through the
 * input dispatcher to SystemUI's Back detector like a finger's: `a_straight_swipe_is_back_on_gesture_navigation` is the
 * control for the angled tests. Probes: `drawerOpen`, and the right end of the All books count for the drawer's place.
 */
@RunWith(AndroidJUnit4::class)
class EdgeDrawerTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val dev = UiDevice.getInstance(inst)
    private val w get() = dev.displayWidth
    private val hgt get() = dev.displayHeight
    private val strip = 12

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

    private fun run(nav: String = THREE_BUTTON, block: suspend CoroutineScope.(MainActivity) -> Unit): Unit = runBlocking {
        val before = navMode()
        try {
            setNav(nav)
            ActivityScenario.launch(MainActivity::class.java).use { sc ->
                lateinit var a: MainActivity
                sc.onActivity { a = it }
                withTimeout(15_000) { a.library.ui.first { it.loaded && !it.scan.running && it.books.size >= 12 } }
                total = a.library.ui.value.total.toString()
                delay(500)
                try {
                    block(a)
                } finally {
                    fingers.filterNot { it.lifted }.forEach { it.up() }
                    sc.onActivity { it.library.setDrawer(false); it.library.clearSelection(); it.library.closeSearch(); it.library.openView(LibraryView.All) }
                }
            }
        } finally {
            setNav(if (before == "2") GESTURAL else THREE_BUTTON)
        }
    }

    private var total = ""

    /** The drawer's right end, less about 37 dp: the count is the rightmost text in it. Null when it isn't on screen. */
    private fun drawerRight(): Int? {
        inst.uiAutomation.clearCache()
        return dev.findObject(By.text(total))?.visibleBounds?.right
    }
    private val MainActivity.open get() = library.ui.value.drawerOpen
    private suspend fun MainActivity.awaitOpen() {
        withTimeout(3_000) { while (!open) delay(20) }
        withTimeout(3_000) { while ((drawerRight() ?: 0) < w * 3 / 4) delay(30) }
    }
    private suspend fun MainActivity.awaitClosed() {
        withTimeout(3_000) { while (open) delay(20) }
        withTimeout(3_000) { while (drawerRight() != null) delay(30) }
    }

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

    /** What the screen says: it changes when the grid has moved. */
    private fun onScreen(): Set<String> {
        inst.uiAutomation.clearCache()
        return dev.findObjects(By.text(Pattern.compile(".+"))).mapNotNull { runCatching { it.text }.getOrNull() }.toSet()
    }

    // ---- the gesture (button navigation: nothing of the system's is in the way) ----

    @Test fun the_drawer_follows_the_finger_and_opens_past_a_third() = run { a ->
        slowDrag(w / 2) {
            assertFalse("the finger is down", a.open)
            val right = withTimeout(2_000) { var r = drawerRight(); while (r == null) { delay(30); r = drawerRight() }; r }
            assertTrue("the drawer's end is at the finger: $right of $w", abs(right - w / 2) < w / 8)
        }
        a.awaitOpen()
    }

    /** C-100: the drawer comes from the screen edge and catches up with the finger; it never pops in where the finger is. */
    @Test fun the_drawer_starts_from_the_edge_and_catches_up_with_the_finger() = run { a ->
        val rec = SlideFrames(inst, "drawer")
        rec.record()
        rec.drag(strip, hgt / 2, dir = 1)
        rec.assertSmoothFromTheEdge { x -> x }
        a.awaitClosed()
    }

    /** C-100: the menu button's slide starts at the edge too. */
    @Test fun the_menu_button_slide_starts_from_the_edge() = run { a ->
        val scale = shell("settings get global animator_duration_scale")
        val rec = SlideFrames(inst, "drawer")
        try {
            shell("settings put global animator_duration_scale 1")
            delay(500)
            rec.record()
            a.library.setDrawer(true)
            a.awaitOpen()
            delay(200)
        } finally {
            rec.stop()
            shell("settings put global animator_duration_scale ${scale.takeUnless { it == "null" } ?: "1"}")
        }
        rec.assertSlidesInFromTheEdge(rec.frames.maxOf { it.edge }.toInt())
    }

    @Test fun short_of_a_third_it_slides_back() = run { a ->
        slowDrag(w / 5) {
            assertFalse(a.open)
            assertTrue("it is out a little", (drawerRight() ?: 0) > 0)
        }
        a.awaitClosed()
    }

    @Test fun a_flick_opens_it() = run { a ->
        val down = SystemClock.uptimeMillis() - 48
        val to = w / 5f
        for (i in 0..3) {
            val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; 3 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val e = MotionEvent.obtain(down, down + i * 16L, action, strip + (to - strip) * i / 3f, hgt / 2f, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            check(inst.uiAutomation.injectInputEvent(e, false)) { "injection refused: $e" }
            e.recycle()
        }
        a.awaitOpen()
    }

    @Test fun outside_the_strip_a_swipe_opens_nothing() = run { a ->
        Finger(w / 8).run { move(w * 3 / 4, steps = 24); delay(150); up() }
        delay(500)
        assertFalse(a.open)
        assertNull(drawerRight())
    }

    @Test fun the_grid_scrolls_until_the_sideways_part_starts() = run { a ->
        val before = onScreen()
        val f = Finger(strip, hgt * 3 / 4)
        f.move(strip, hgt / 3, steps = 24)
        delay(300)
        val scrolled = onScreen()
        assertNotEquals("the strip scrolls", before, scrolled)
        assertFalse(a.open)
        assertNull("no drawer yet", drawerRight())
        f.move(w / 2, hgt / 3 - 8, steps = 12)
        delay(250)
        assertTrue("the drawer is out", (drawerRight() ?: 0) > 0)
        f.up()
        a.awaitOpen()
    }

    /** C-101, C-102: the angle rule shared with the reader never takes a scroll within about 15 degrees of vertical. */
    @Test fun a_scroll_15_degrees_off_vertical_from_the_strip_scrolls_and_opens_nothing() = run { a ->
        val before = onScreen()
        val f = Finger(strip, hgt * 3 / 4)
        val up = hgt / 2
        f.move(strip + (up * tan(15 * PI / 180)).toInt(), hgt / 4, steps = 40)
        delay(300)
        assertNotEquals("the strip scrolls", before, onScreen())
        assertNull("no drawer", drawerRight())
        f.up()
        delay(500)
        assertFalse(a.open)
        assertNull(drawerRight())
    }

    @Test fun a_leftward_swipe_closes_it_a_short_one_does_not() = run { a ->
        a.library.setDrawer(true)
        a.awaitOpen()
        val short = Finger(w * 3 / 5, hgt * 2 / 3)
        short.move(w * 2 / 5, steps = 16)
        delay(250)
        short.up()
        delay(600)
        assertTrue(a.open)
        a.awaitOpen()
        val long = Finger(w * 3 / 4, hgt * 2 / 3)
        long.move(w / 5, steps = 16)
        delay(250)
        long.up()
        a.awaitClosed()
    }

    @Test fun in_selection_and_search_the_gesture_does_nothing() = run { a ->
        a.library.toggleSelection(a.library.ui.value.books.first().id)
        delay(300)
        slowDrag(w / 2) { assertNull(drawerRight()) }
        assertFalse(a.open)
        a.library.clearSelection()
        a.library.openSearch()
        delay(300)
        slowDrag(w / 2) { assertNull(drawerRight()) }
        assertFalse(a.open)
    }

    // ---- gesture navigation: what the Back detector does with injected events ----

    /** The control: Back takes it, and leaves the view. Fast enough to be a flick, had the cancel read as a release. */
    @Test fun a_straight_swipe_is_back_on_gesture_navigation() = run(nav = GESTURAL) { a ->
        a.library.openView(LibraryView.Folders)
        delay(300)
        Finger(strip).run { move(w / 2, steps = 24); delay(250); up() }
        withTimeout(5_000) { while (a.library.ui.value.view != LibraryView.All) delay(20) }
        delay(500)
        assertFalse(a.open)
        assertNull(drawerRight())
    }

    @Test fun an_angled_swipe_reaches_the_app_on_gesture_navigation() = run(nav = GESTURAL) { a ->
        val f = Finger(strip, hgt * 2 / 5)
        f.move(w * 3 / 5, hgt * 4 / 5, steps = 30) // 0.6 w right, 0.4 h down: more down than sideways all the way
        delay(250)
        assertTrue("the drawer is out", (drawerRight() ?: 0) > 0)
        f.up()
        a.awaitOpen()
        assertEquals(LibraryView.All, a.library.ui.value.view)
    }

    @Test fun slightly_down_then_sideways_reaches_the_app_on_gesture_navigation() = run(nav = GESTURAL) { a ->
        val f = Finger(strip, hgt * 2 / 5)
        f.move(strip, hgt * 2 / 5 + 60, steps = 6)
        f.move(w / 2, steps = 16)
        delay(250)
        assertTrue("the drawer is out", (drawerRight() ?: 0) > 0)
        f.up()
        a.awaitOpen()
    }

    private companion object {
        const val GESTURAL = "gestural"
        const val THREE_BUTTON = "threebutton"
    }
}
