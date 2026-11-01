package app.epubreader.reader

import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epubreader.MainActivity
import app.epubreader.TestBooks
import app.epubreader.core.data.OrientationLock
import app.epubreader.core.data.ReaderSettings
import app.epubreader.ui.Route
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** F-035: fullscreen, keep-screen-on and orientation lock act in the reader only, and live. */
@RunWith(AndroidJUnit4::class)
class ScreenBehaviourTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    private fun <T> ActivityScenario<MainActivity>.read(f: (MainActivity) -> T): T { var r: T? = null; onActivity { r = f(it) }; @Suppress("UNCHECKED_CAST") return r as T }
    private fun ActivityScenario<MainActivity>.keepOn() = read { it.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0 }
    private fun ActivityScenario<MainActivity>.statusBar() = read { ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true }
    private fun <T> eventually(what: String, want: T, get: () -> T) {
        val end = System.currentTimeMillis() + 5_000
        while (get() != want && System.currentTimeMillis() < end) Thread.sleep(100)
        assertEquals(what, want, get())
    }

    private fun withReader(s: ReaderSettings?, body: suspend (ActivityScenario<MainActivity>, ReaderHarness) -> Unit) = runBlocking {
        val f = TestBooks.write(inst.targetContext, "screen.epub", TestBooks.epub(id = "urn:uuid:scr-${System.nanoTime()}", chapters = 2, paragraphs = 30))
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready()
                if (s != null) sc.onActivity { it.controller.updateSettings { _ -> s } }
                h.open(f)
                body(sc, h)
            } finally {
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                h.stop()
            }
        }
    }

    @Test fun keep_screen_on_in_the_reader_only() = withReader(null) { sc, _ ->
        eventually("flag set in the reader", true) { sc.keepOn() }
        sc.onActivity { it.goBack() }
        eventually("route", Route.Library) { sc.read { it.nav.top } }
        eventually("flag cleared in the library", false) { sc.keepOn() }
    }

    @Test fun keep_screen_on_setting_off_never_sets_it() = withReader(ReaderSettings(keepScreenOn = false)) { sc, _ ->
        Thread.sleep(500)
        assertFalse(sc.keepOn())
    }

    @Test fun fullscreen_hides_the_status_bar_in_the_reader_and_restores_it_in_the_library() = withReader(null) { sc, _ ->
        eventually("hidden in the reader", false) { sc.statusBar() }
        sc.onActivity { it.goBack() }
        eventually("visible in the library", true) { sc.statusBar() }
    }

    @Test fun fullscreen_off_keeps_the_bars() = withReader(ReaderSettings(fullscreen = false)) { sc, _ ->
        Thread.sleep(500)
        assertTrue(sc.statusBar())
    }

    @Test fun orientation_lock_applies_in_the_reader_and_is_released_on_leaving() = withReader(ReaderSettings(orientation = OrientationLock.PORTRAIT)) { sc, _ ->
        // M7 (review): either way up (the sensor decides within portrait)
        eventually("portrait in the reader", ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT) { sc.read { it.requestedOrientation } }
        sc.onActivity { it.goBack() }
        eventually("unspecified in the library", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) { sc.read { it.requestedOrientation } }
    }

    /** I2 (review): page turns do not touch the window (no flags, insets or attributes set, so no relayout per turn). */
    @Test fun page_turns_do_not_reapply_the_window() = withReader(null) { sc, h ->
        eventually("flag set in the reader", true) { sc.keepOn() }
        Thread.sleep(500)
        val before = sc.read { it.windowApplies }
        var here = h.settle()
        repeat(4) { val from = here; h.send(app.epubreader.reader.bridge.ToWeb.Next()); here = h.relocate { it.cfi != from.cfi } }
        h.settle()
        Thread.sleep(300)
        assertEquals("window applies during 4 turns", before, sc.read { it.windowApplies })
        sc.onActivity { it.controller.toggleChrome() } // the controls do change it (bars shown)
        eventually("applied for the controls", before + 1) { sc.read { it.windowApplies } }
        sc.onActivity { it.controller.toggleChrome() }
    }

    /**
     * P-015 (M6 regression): opening a book does not change the window in the reader's first frame. Applied with it, the
     * bars' insets animation makes the next frame draw, and that draw waits for the render thread to finish the first
     * one (the WebView's first draw), ~150-450 ms on the emulator. The bars are still hidden right after.
     */
    @Test fun the_reader_window_is_applied_after_the_readers_first_frame(): Unit = runBlocking {
        val f = TestBooks.write(inst.targetContext, "screen-first.epub", TestBooks.epub(id = "urn:uuid:scr1-${System.nanoTime()}", chapters = 2, paragraphs = 30))
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready()
                eventually("library", Route.Library) { sc.read { it.nav.top } }
                eventually("bars visible in the library", true) { sc.statusBar() }
                var before = -1
                var atFirstDraw = -1
                sc.onActivity { a ->
                    before = a.windowApplies
                    val decor = a.window.decorView
                    val l = object : android.view.ViewTreeObserver.OnDrawListener {
                        override fun onDraw() {
                            if (atFirstDraw >= 0) return
                            atFirstDraw = a.windowApplies
                            decor.post { decor.viewTreeObserver.removeOnDrawListener(this) }
                        }
                    }
                    decor.viewTreeObserver.addOnDrawListener(l)
                    a.openBook(app.epubreader.core.data.SourceRef.File(f.path))
                }
                eventually("first reader frame drawn", true) { sc.read { atFirstDraw >= 0 } }
                assertEquals("window applies in the reader's first frame", before, atFirstDraw)
                eventually("hidden in the reader", false) { sc.statusBar() }
            } finally {
                h.stop()
            }
        }
    }

    /** M6 (review): an error screen lets the screen time out and shows the bars. */
    @Test fun an_open_failure_screen_shows_the_bars_and_does_not_keep_the_screen_on(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { it.openBook(app.epubreader.core.data.SourceRef.File("/sdcard/Books/does-not-exist-${System.nanoTime()}.epub")) }
            eventually("error shown", true) { sc.read { it.controller.state.value.error != null } }
            eventually("reader route", Route.Reader) { sc.read { it.nav.top } }
            eventually("no keep-screen-on on the error screen", false) { sc.keepOn() }
            eventually("bars shown on the error screen", true) { sc.statusBar() }
            sc.onActivity { it.goBack() }
        }
    }
}
