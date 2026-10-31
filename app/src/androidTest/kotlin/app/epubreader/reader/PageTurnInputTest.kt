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
     * A quick swipe injected directly (down, two moves, up: about 50 ms), so a second one can end while the first turn
     * still animates; UiDevice.swipe takes ~400 ms per swipe on the emulator.
     */
    private fun fastSwipeLeft() {
        val y = h / 2f
        val xs = listOf(w * 0.85f, w * 0.6f, w * 0.35f, w * 0.15f)
        val down = SystemClock.uptimeMillis()
        xs.forEachIndexed { i, x ->
            val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; xs.lastIndex -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val e = MotionEvent.obtain(down, down + i * 16L, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            inst.uiAutomation.injectInputEvent(e, true)
            e.recycle()
            if (i < xs.lastIndex) SystemClock.sleep(16)
        }
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

    /** C-026: a second swipe that ends while the first turn still animates is not lost. */
    @Test fun two_quick_swipes_move_two_pages(): Unit = run { hr, sc ->
        val start = hr.openMid(warAndPeace(), sc)
        fastSwipeLeft()
        val firstEnd = SystemClock.uptimeMillis()
        fastSwipeLeft()
        val gap = SystemClock.uptimeMillis() - firstEnd
        hr.quiet()
        assertTrue("second swipe ended $gap ms after the first (should be inside the 300 ms animation)", gap < 300)
        assertEquals(start.copy(page = start.page + 2), sc.pos())
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
}
