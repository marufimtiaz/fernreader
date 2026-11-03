package app.epubreader.reader

import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.epubreader.MainActivity
import app.epubreader.TestBooks
import app.epubreader.core.data.ReaderSettings
import app.epubreader.ui.Copy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * C-073: the top bar shows the book title (two lines at most; a tap shows it in full with the authors); the bottom bar
 * has the chapter and the page label on one row, and while the slider is dragged both show where a release lands.
 */
@RunWith(AndroidJUnit4::class)
class ControlsTitleSeekPreviewTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val dev = UiDevice.getInstance(inst)

    private suspend fun <T> wait(what: String, ms: Long = 3_000, f: suspend () -> T): T =
        withTimeoutOrNull(ms) { f() } ?: throw AssertionError("timed out: $what")

    private fun clearCache() { if (android.os.Build.VERSION.SDK_INT >= 34) inst.uiAutomation.clearCache() }

    private suspend fun showControls(h: ReaderHarness) {
        if (!h.a.controller.state.value.chromeVisible) dev.click(dev.displayWidth / 2, dev.displayHeight / 2)
        wait("controls shown", 10_000) { h.a.controller.state.first { it.chromeVisible } }
        assertTrue(dev.wait(Until.hasObject(By.desc(Copy.seek)), 3_000))
    }

    /** A text node of the bottom bar's chapter/page row: just above the slider, in the bar. */
    private fun inRow(text: String, slider: Rect): UiObject2? {
        clearCache()
        return dev.findObjects(By.text(text)).firstOrNull { val b = it.visibleBounds; b.bottom <= slider.top + 4 && b.top >= slider.top - slider.height() * 3 }
    }

    /** Diagnostics only: texts in the bottom quarter (a node that went away meanwhile is skipped, never a failure). */
    private fun texts(): String = dev.findObjects(By.textContains("")).mapNotNull { o ->
        runCatching { o.visibleBounds.takeIf { it.top > dev.displayHeight * 3 / 4 }?.let { "'${o.text}' $it" } }.getOrNull()
    }.joinToString()

    /** A finger held on the screen after [pressAndMove]; [lift] ends the gesture (once). */
    private inner class Held(val down: Long, var x: Int, val y: Int) {
        private var up = false
        fun lift() { if (!up) { up = true; inject(MotionEvent.ACTION_UP, down, x, y) } }
    }

    /** Touches at [fromX] and moves to [toX] without lifting (the drag stays in progress until [Held.lift]). */
    private fun pressAndMove(fromX: Int, toX: Int, y: Int): Held {
        val down = SystemClock.uptimeMillis()
        val held = Held(down, fromX, y)
        inject(MotionEvent.ACTION_DOWN, down, fromX, y)
        try {
            val steps = 20
            for (i in 1..steps) { Thread.sleep(16); held.x = fromX + (toX - fromX) * i / steps; inject(MotionEvent.ACTION_MOVE, down, held.x, y) }
        } catch (e: Throwable) { held.lift(); throw e }
        return held
    }
    private fun inject(action: Int, down: Long, x: Int, y: Int) {
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x.toFloat(), y.toFloat(), 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        assertTrue(inst.uiAutomation.injectInputEvent(e, true))
        e.recycle()
    }

    @Test fun dragging_the_slider_previews_the_target_chapter_and_page_before_release(): Unit = runBlocking {
        val wp = warAndPeace()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready()
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } } // paged, default zones (another run may have changed them)
                h.open(wp)
                h.tocJump("CHAPTER III") // a known chapter near the start
                h.drain()
                val c = h.a.controller
                val startFraction = c.state.value.fraction
                val current = checkNotNull(chapterLabel(c.state.value.toc, c.state.value.currentTocHref))
                showControls(h)
                val slider = dev.findObject(By.desc(Copy.seek)).visibleBounds
                assertNotNull("chapter on the bottom bar's row", inRow(current, slider))

                val y = slider.centerY()
                // not at the screen edge: a touch there starts the system Back gesture instead
                val fromX = slider.left + slider.width() / 5
                val toX = slider.left + slider.width() * 6 / 10
                h.drain(); val before = h.at
                val held = pressAndMove(fromX, toX, y)
                try {
                    withTimeoutOrNull(3_000) { c.seekPreview.first { it != null && it.fraction > 0.5 } }
                        ?: throw AssertionError("no preview past the middle: ${c.seekPreview.value}, slider $slider, $fromX → $toX")
                    delay(300) // held still: the preview is shown, nothing has moved yet
                    val p = checkNotNull(c.seekPreview.value) // where the finger rests now
                    val target = checkNotNull(p.chapter) { "no chapter for ${p.fraction}" }
                    assertTrue("a contents entry's title", c.state.value.toc.any { it.label.trim() == target })
                    assertNotEquals("the label changes from where the reader is", current, target)
                    inRow(target, slider) ?: throw AssertionError("target chapter '$target' shown mid-drag; on screen: ${texts()}")
                    Copy.pageLabel(p.page)?.let { assertNotNull("target page '$it' shown mid-drag", inRow(it, slider)) }
                    h.drain(); assertEquals("the page did not move before release", before!!.sectionIndex, h.at!!.sectionIndex)
                    assertEquals(startFraction, c.state.value.fraction, 0.005)

                    held.lift()
                    wait("landed", 10_000) { c.state.first { kotlin.math.abs(it.fraction - p.fraction) < 0.01 } }
                    withTimeoutOrNull(3_000) { c.seekPreview.first { it == null }; true } ?: throw AssertionError("preview stays: ${c.seekPreview.value} at ${c.state.value.fraction} (released at ${p.fraction})")
                    // the preview named the chapter the reader landed in
                    assertEquals(target, chapterLabel(c.state.value.toc, c.state.value.currentTocHref))
                    assertTrue(dev.wait(Until.hasObject(By.desc(Copy.seek)), 3_000))
                    assertNotNull(inRow(target, dev.findObject(By.desc(Copy.seek)).visibleBounds))
                } finally { held.lift() }
            } finally { h.stop() }
        }
    }

    @Test fun back_during_a_drag_cancels_it(): Unit = runBlocking {
        val wp = warAndPeace()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready()
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } } // paged, default zones (another run may have changed them)
                h.open(wp)
                h.tocJump("CHAPTER III")
                val c = h.a.controller
                val at = c.state.value.fraction
                showControls(h)
                val slider = dev.findObject(By.desc(Copy.seek)).visibleBounds
                h.drain(); val before = h.at
                val held = pressAndMove(slider.left + slider.width() / 5, slider.left + slider.width() * 7 / 10, slider.centerY())
                try {
                    wait("preview") { c.seekPreview.first { it != null } }
                    dev.pressBack()
                    wait("controls closed") { c.state.first { !it.chromeVisible } }
                } finally { held.lift() }
                delay(800)
                assertEquals(null, c.seekPreview.value)
                h.drain(); assertEquals("cancelled: the page did not move", before!!.sectionIndex, h.at!!.sectionIndex)
                assertEquals(at, c.state.value.fraction, 0.005)
                assertTrue("still reading", c.state.value.open)
            } finally { h.stop() }
        }
    }

    @Test fun a_long_title_takes_two_lines_and_a_tap_shows_it_in_full_with_the_authors(): Unit = runBlocking {
        val title = "The Remarkably Long and Winding Title of a Book That Goes On and On Well Past Two Lines of the Bar"
        val f = TestBooks.write(inst.targetContext, "long-title.epub", TestBooks.epub(id = "urn:uuid:c073-${System.nanoTime()}", title = title, chapters = 2, paragraphs = 20))
        val frank = File("/sdcard/Books/gutenberg/frankenstein-epub3.epub").also { warAndPeace() }
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready()
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } } // paged, default zones (another run may have changed them)
                // a corpus title of 39 characters wraps to exactly two lines
                h.open(frank)
                val frankTitle = h.a.controller.state.value.title
                showControls(h)
                val line = oneLineHeight()
                val t = checkNotNull(dev.findObject(By.text(frankTitle))) { "title '$frankTitle' in the top bar" }
                assertEquals("'$frankTitle' on two lines", 2, lines(t.visibleBounds.height(), line))

                // longer than two lines: two, ellipsized; the dialog shows it all and the authors
                h.open(f, withToc = false)
                showControls(h)
                clearCache()
                val bar = checkNotNull(dev.wait(Until.findObject(By.textStartsWith("The Remarkably Long")), 3_000)) { "long title in the top bar" }
                assertEquals("capped at two lines", 2, lines(bar.visibleBounds.height(), line))
                bar.click()
                assertTrue("dialog with the full title", dev.wait(Until.hasObject(By.text(title)), 3_000))
                assertTrue("and the authors", dev.hasObject(By.text(Copy.authorsLine(h.a.controller.state.value.authors))))
                dev.pressBack()
                assertTrue("Back closes it", dev.wait(Until.gone(By.text(Copy.close)), 3_000)) // the bar's title node holds the full text too
                assertTrue("the book stays open", h.a.controller.state.value.open)
            } finally { h.stop() }
        }
    }

    /** One line of the top bar's title: titleSmall's line height, 20 sp. */
    private fun oneLineHeight(): Int = (20 * inst.targetContext.resources.displayMetrics.scaledDensity).toInt()
    /** Lines in a title node [h] px tall, given its vertical padding (2 × 6 dp) and a line height of [line] px. */
    private fun lines(h: Int, line: Int): Int = Math.round((h - 12 * inst.targetContext.resources.displayMetrics.density) / line)
}
