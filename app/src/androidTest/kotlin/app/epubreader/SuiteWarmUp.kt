package app.epubreader

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.epubreader.reader.ReaderHarness
import app.epubreader.reader.warAndPeace
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Not a check of the app: the two steps tools/run_suite.sh runs before a suite (each its own `am instrument`), kept out
 * of the suite itself with `notClass`. After an emulator boot the first touches and the first WebView page are the
 * slowest ones of the session, and two tests failed unexplained in a suite started 2 minutes after boot.
 */
@RunWith(AndroidJUnit4::class)
class SuiteWarmUp {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val dev = UiDevice.getInstance(inst)
    private val graph get() = (inst.targetContext.applicationContext as EpubReaderApp).graph

    /** A real book in the WebView and page turns by finger. Leaves a reading position, so the runner clears the app after it. */
    @Test fun webview_page_and_touches(): Unit = runBlocking {
        val book = warAndPeace()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready()
                h.open(book)
                val y = dev.displayHeight / 2
                repeat(6) { h.drain(); dev.swipe(dev.displayWidth * 4 / 5, y, dev.displayWidth / 5, y, 20); h.relocate() }
                repeat(3) { h.drain(); dev.swipe(dev.displayWidth / 5, y, dev.displayWidth * 4 / 5, y, 20); h.relocate() }
            } finally { h.stop() }
        }
    }

    /** The library the suite starts from: a launch, and the wait for the end of its scan (a fixed 20 s used to stand for it). */
    @Test fun first_scan_finishes(): Unit = runBlocking {
        warAndPeace() // storage access + corpus present
        ActivityScenario.launch(MainActivity::class.java).use {
            withTimeout(120_000) { graph.scans.state.first { it.lastComplete != null && !it.running } }
        }
    }
}
