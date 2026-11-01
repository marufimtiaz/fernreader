package app.epubreader.robustness

import androidx.test.core.app.ActivityScenario
import app.epubreader.MainActivity
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epubreader.TestBooks
import app.epubreader.core.data.SourceRef
import app.epubreader.ui.Route
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * R-005, functional guard in the debug build: Back 1 s after opening the huge chapter (10 MB XHTML) returns to the
 * library at once and the load never blocks the main thread for long (no ANR). Measured in-process from the injected
 * Back key to the first frame drawn with the library route on top, one cold open.
 *
 * The 200 ms Must is judged by bench/robust_r005.sh on the benchmark build: in this debug build, composing the library
 * screen alone takes ~250-300 ms on the emulator (sampled main-thread stacks: LibraryScreen, its drawer, list items),
 * the release build ~110-120 ms in total (M9.md).
 */
@RunWith(AndroidJUnit4::class)
class HugeChapterBackTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val huge = File("/sdcard/Books/generated/stress-huge-single-chapter.epub")

    @Test fun back_one_second_after_opening_the_huge_chapter_returns_to_the_library_debug_guard(): Unit = runBlocking {
        inst.uiAutomation.executeShellCommand("appops set ${inst.targetContext.packageName} MANAGE_EXTERNAL_STORAGE allow").close()
        check(huge.isFile) { "corpus not pushed: $huge" }
        val other = TestBooks.write(inst.targetContext, "r5-other.epub", TestBooks.epub(id = "urn:uuid:r5-other"))
        val times = mutableListOf<Long>()
        val stalls = mutableListOf<Long>()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            lateinit var a: app.epubreader.MainActivity
            sc.onActivity { a = it }
            withTimeout(15_000) { a.reader.isReady.first { it } }
            repeat(1) { run -> // one cold open (a guard); the 200 ms Must: bench/robust_r005.sh
                // A small book first, so the huge one is not the warm session: every trial is a cold open.
                sc.onActivity { it.openBook(SourceRef.File(other.path)) }
                withTimeout(20_000) { a.controller.state.first { it.open && !it.loading } }
                sc.onActivity { it.goBack() }
                val beat = Heartbeat().also { it.start() }
                sc.onActivity { it.openBook(SourceRef.File(huge.path)) }
                delay(1_000)
                // Injected without waiting (UiDevice.pressBack waits up to ~1 s for an accessibility event before returning).
                val t0 = SystemClock.uptimeMillis()
                for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    val now = SystemClock.uptimeMillis()
                    inst.uiAutomation.injectInputEvent(KeyEvent(t0, now, action, KeyEvent.KEYCODE_BACK, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), false)
                }
                // Watched on the main thread itself: ActivityScenario.onActivity first waits for the main looper to be
                // idle, which it rarely is while the huge chapter loads, and that wait would be measured.
                val shown = CompletableDeferred<Pair<Long, Long>>()
                val main = android.os.Handler(android.os.Looper.getMainLooper())
                main.post(object : Runnable {
                    override fun run() {
                        if (a.nav.top != Route.Library) { main.postDelayed(this, 2); return }
                        val nav = SystemClock.uptimeMillis()
                        Choreographer.getInstance().postFrameCallback { shown.complete(nav to SystemClock.uptimeMillis()) }
                    }
                })
                val (navAt, frameAt) = withTimeout(10_000) { shown.await() }
                val ms = frameAt - t0
                Log.i("EPR", "R-005 run ${run + 1}: library route after ${navAt - t0} ms")
                beat.stop()
                Log.i("EPR", "R-005 run ${run + 1}: Back -> library frame $ms ms; longest main-thread stall ${beat.maxGap} ms")
                stalls += beat.maxGap
                times += ms
            }
        }
        Log.i("EPR", "R-005 times $times ms; main-thread stalls $stalls ms")
        assertTrue("R-005 (debug guard): Back -> library $times ms (guard 1000)", times.max() <= 1_000)
        assertTrue("R-005 (debug guard): main-thread stalls $stalls ms (guard 1000)", stalls.max() < 1_000)
    }
}

/** Longest gap between main-thread runs of a 16 ms tick: how long the UI thread was blocked (R-005 diagnosis). */
private class Heartbeat {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile var maxGap = 0L; private set
    @Volatile private var last = 0L
    @Volatile private var on = false
    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (last != 0L) maxGap = maxOf(maxGap, now - last - 16)
            last = now
            if (on) main.postDelayed(this, 16)
        }
    }
    fun start() { on = true; main.post(tick) }
    fun stop() { on = false; main.removeCallbacks(tick) }
}
