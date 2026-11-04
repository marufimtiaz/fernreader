package app.epubreader.reader

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.epubreader.MainActivity
import app.epubreader.core.data.ReaderSettings
import app.epubreader.core.data.VolumeKeys
import app.epubreader.reader.bridge.ToWeb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * F-032: each page-turn input moves exactly one page (swipes through the turn queue, C-026), tap zones, volume keys
 * (C-005, media volume unchanged), and an rtl book's mirrored swipe (F-081). Corpus books (bench/push_corpus.sh).
 */
@RunWith(AndroidJUnit4::class)
class PageTurnInputTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val dev = UiDevice.getInstance(inst)
    private val w get() = dev.displayWidth
    private val h get() = dev.displayHeight

    private data class Pos(val index: Int, val page: Int, val pages: Int)

    private suspend fun ActivityScenario<MainActivity>.pos(): Pos = hook("page()").let { Pos(it.getInt("index"), it.getInt("page"), it.getInt("textPages")) }

    /** Opens [f] at a chapter a third into the book (several pages long) and returns the settled location. */
    private suspend fun ReaderHarness.openMid(f: File, sc: ActivityScenario<MainActivity>): Pos {
        open(f)
        val toc = a.controller.state.value.toc.filter { !it.missing && it.index >= 0 }
        var p: Pos? = null
        for (item in toc.drop(toc.size / 3)) { // the first chapter from there with room for a few turns
            val id = 9_000 + toc.indexOf(item)
            send(ToWeb.GoTo(target = item.href, id = id))
            next(10_000) { e -> (e as? app.epubreader.reader.bridge.FromWeb.Seeked)?.takeIf { it.id == id } }
            settle()
            p = sc.pos()
            if (p.pages - p.page >= 4 && p.page >= 1) break
        }
        assertTrue("no chapter with room for turns: $p", p != null && p.pages - p.page >= 4)
        // one page in, so a previous-page turn stays in the chapter too
        send(ToWeb.Next()); quiet(); p = sc.pos()
        return p
    }

    /** Waits until the turns asked for have run (each ~0.3 s), then makes sure everything sent before was applied. */
    private suspend fun ReaderHarness.quiet() { delay(1_500); drain(); settle() }

    /**
     * [count] quick leftward swipes (down, two moves, up: 48 ms each, 32 ms apart), built up front with explicit event
     * times and injected back to back without waiting for each to be handled, so the test's own pacing on a busy
     * emulator cannot stretch the gap between them (UiDevice.swipe takes ~400 ms per swipe; injecting one event at a
     * time with a wait for each took 364 ms between the two ends in the full suite).
     */
    private fun fastSwipesLeft(count: Int) {
        val y = h / 2f
        val xs = listOf(w * 0.85f, w * 0.6f, w * 0.35f, w * 0.15f)
        val base = SystemClock.uptimeMillis() - count * 80L // all in the past: none waits for its time in the input queue
        val events = (0 until count).flatMap { k ->
            val down = base + k * 80L
            xs.mapIndexed { i, x ->
                val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; xs.lastIndex -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                MotionEvent.obtain(down, down + i * 16L, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            }
        }
        for (e in events) { check(inst.uiAutomation.injectInputEvent(e, false)) { "injection refused: $e" }; e.recycle() }
    }

    /** Uptime at which each touch ACTION_UP reached the activity's window (the app's own view of the input). */
    private fun ActivityScenario<MainActivity>.recordUps(): MutableList<Long> {
        val ups = java.util.Collections.synchronizedList(mutableListOf<Long>())
        onActivity { a ->
            val orig = a.window.callback
            a.window.callback = object : android.view.Window.Callback by orig {
                override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                    if (event.actionMasked == MotionEvent.ACTION_UP) ups += SystemClock.uptimeMillis()
                    return orig.dispatchTouchEvent(event)
                }
            }
        }
        return ups
    }

    private fun swipeLeft() = dev.swipe((w * 0.85).toInt(), h / 2, (w * 0.15).toInt(), h / 2, 10)
    private fun swipeRight() = dev.swipe((w * 0.15).toInt(), h / 2, (w * 0.85).toInt(), h / 2, 10)

    private fun run(block: suspend CoroutineScope.(ReaderHarness, ActivityScenario<MainActivity>) -> Unit): Unit = runBlocking {
        warAndPeace() // all-files access before the activity starts
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val hr = ReaderHarness(sc, this)
            hr.ready()
            try {
                block(hr, sc)
            } finally {
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } } // defaults again (stored after 300 ms)
                delay(800)
                hr.stop()
            }
        }
    }

    @Test fun one_swipe_moves_one_page_and_back(): Unit = run { hr, sc ->
        val start = hr.openMid(warAndPeace(), sc)
        swipeLeft(); hr.quiet()
        assertEquals(start.copy(page = start.page + 1), sc.pos())
        swipeRight(); hr.quiet()
        assertEquals(start, sc.pos())
    }

    /**
     * C-026: a second swipe that ends while the first turn still animates (300 ms) is not lost. The gap is measured
     * where the app receives the two ends; if the system delivered them late (a busy emulator), that attempt proves
     * nothing about the window and is repeated (at most 3 attempts), but every attempt must move exactly two pages.
     */
    @Test fun two_quick_swipes_move_two_pages(): Unit = run { hr, sc ->
        var start = hr.openMid(warAndPeace(), sc)
        val ups = sc.recordUps()
        val gaps = mutableListOf<Long>()
        for (attempt in 1..3) {
            ups.clear()
            fastSwipesLeft(2)
            hr.quiet()
            assertEquals("ends delivered: $ups", 2, ups.size)
            gaps += ups[1] - ups[0]
            val now = sc.pos()
            assertEquals("attempt $attempt (gap ${gaps.last()} ms)", start.copy(page = start.page + 2), now)
            if (gaps.last() < 300) break
            if (now.pages - now.page < 3) start = hr.openMid(warAndPeace(), sc) else start = now
        }
        android.util.Log.i("EPR", "C-026 two quick swipes: delivered gaps $gaps ms")
        assertTrue("the second swipe never reached the app inside the 300 ms animation: gaps $gaps ms", gaps.last() < 300)
    }

    @Test fun tap_zones_turn_pages_when_on(): Unit = run { hr, sc ->
        val start = hr.openMid(warAndPeace(), sc)
        dev.click((w * 0.92).toInt(), h / 2); hr.quiet()
        assertEquals("tap zones off: an edge tap does nothing", start, sc.pos())
        sc.onActivity { it.controller.updateSettings { s -> s.copy(tapZones = true) } }
        dev.click((w * 0.92).toInt(), h / 2); hr.quiet()
        assertEquals(start.copy(page = start.page + 1), sc.pos())
        dev.click((w * 0.08).toInt(), h / 2); hr.quiet()
        assertEquals(start, sc.pos())
    }

    @Test fun volume_keys_turn_pages_without_changing_the_volume(): Unit = run { hr, sc ->
        val audio = inst.targetContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, max / 2, 0) // room both ways, so a change would show
        try {
            val start = hr.openMid(warAndPeace(), sc)
            sc.onActivity { it.controller.updateSettings { s -> s.copy(volumeKeys = VolumeKeys.ON) } }
            val vol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            dev.pressKeyCode(KeyEvent.KEYCODE_VOLUME_DOWN); hr.quiet()
            assertEquals(start.copy(page = start.page + 1), sc.pos())
            dev.pressKeyCode(KeyEvent.KEYCODE_VOLUME_UP); hr.quiet()
            assertEquals(start, sc.pos())
            assertEquals("media volume changed while the keys turned pages", vol, audio.getStreamVolume(AudioManager.STREAM_MUSIC))

            sc.onActivity { it.controller.updateSettings { s -> s.copy(volumeKeys = VolumeKeys.OFF) } }
            dev.pressKeyCode(KeyEvent.KEYCODE_VOLUME_DOWN); hr.quiet()
            assertEquals("volume keys off: no page change", start, sc.pos())
            delay(3_500) // the system volume panel that OFF lets through goes away
        } finally {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, before, 0)
        }
    }

    /** F-081: an rtl book (AUTO) turns forward with a swipe toward the right. */
    @Test fun rtl_book_swipe_toward_the_right_is_next(): Unit = run { hr, sc ->
        val f = File("/sdcard/Books").walkTopDown().first { it.name.startsWith("regime-anticancer-arabic") && it.extension == "epub" }
        val start = hr.openMid(f, sc)
        swipeRight(); hr.quiet()
        assertEquals(start.copy(page = start.page + 1), sc.pos())
        swipeLeft(); hr.quiet()
        assertEquals(start, sc.pos())
    }

    /** F-032 + F-082: fixed-layout pages turn by swipe too (foliate-fxl has no touch handling; the host recognises them). */
    @Test fun fixed_layout_swipes_turn_pages(): Unit = run { hr, sc ->
        for ((name, rtl) in listOf("page-blanche" to false, "haruko-html-jpeg" to true)) {
            val f = File("/sdcard/Books/epub3-samples/$name.epub")
            hr.open(f)
            // from a fixed page: the book resumes where another test left it, which can be its last page
            hr.drain(); sc.js("window.__readerTest.goIndex(2)"); hr.relocate(10_000) { it.sectionIndex >= 2 }; hr.quiet()
            val start = hr.at!!.sectionIndex
            if (rtl) swipeRight() else swipeLeft()
            hr.quiet()
            assertTrue("$name: a swipe toward the ${if (rtl) "right" else "left"} is next: $start -> ${hr.at!!.sectionIndex}", hr.at!!.sectionIndex > start)
            if (rtl) swipeLeft() else swipeRight()
            hr.quiet()
            assertEquals("$name: the opposite swipe goes back", start, hr.at!!.sectionIndex)
        }
    }
}
