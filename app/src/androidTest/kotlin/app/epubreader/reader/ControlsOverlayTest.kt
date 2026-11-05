package app.epubreader.reader

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.epubreader.MainActivity
import app.epubreader.TestBooks
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.ui.Copy
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** F-033: the controls float over the page; toggling them never changes the page content or position. */
@RunWith(AndroidJUnit4::class)
class ControlsOverlayTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val dev = UiDevice.getInstance(inst)

    @Test fun toggling_the_controls_does_not_move_or_reflow_the_page(): Unit = runBlocking {
        val f = TestBooks.write(inst.targetContext, "controls.epub", TestBooks.epub(id = "urn:uuid:ctl-${System.nanoTime()}", chapters = 3, paragraphs = 60))
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            h.ready()
            val start = h.open(f)
            // a turn first, so the check is not at the book's first page
            dev.swipe((dev.displayWidth * 0.85).toInt(), dev.displayHeight / 2, (dev.displayWidth * 0.15).toInt(), dev.displayHeight / 2, 10)
            val here = h.relocate { it.cfi != start.cfi }
            val block = sc.js("window.__readerTest.firstVisibleBlock()")
            val pageBefore = sc.js("JSON.stringify(window.__readerTest.page())")
            h.drain()

            val moved = mutableListOf<String>()
            val watch = launch(start = CoroutineStart.UNDISPATCHED) { h.a.reader.events.filterIsInstance<FromWeb.Relocate>().collect { moved += it.cfi } }
            val cx = dev.displayWidth / 2; val cy = dev.displayHeight / 2
            dev.click(cx, cy)
            withTimeout(3_000) { h.a.controller.state.first { it.chromeVisible } }
            assertTrue(dev.wait(Until.hasObject(By.desc(Copy.contents)), 3_000))
            // F-035: fullscreen is on by default; the bars come with the controls and still nothing reflows
            assertTrue(h.a.controller.settings.value.fullscreen)
            assertTrue("bars shown with the controls", waitStatusBar(sc, true))
            delay(600) // the window in which the insets must not reflow the page: fixed
            assertEquals(block, sc.js("window.__readerTest.firstVisibleBlock()"))
            assertEquals(pageBefore, sc.js("JSON.stringify(window.__readerTest.page())"))
            dev.click(cx, cy)
            withTimeout(3_000) { h.a.controller.state.first { !it.chromeVisible } }
            assertTrue(dev.wait(Until.gone(By.desc(Copy.contents)), 3_000))
            assertTrue("bars hidden with the controls", waitStatusBar(sc, false))
            delay(600) // the same window as above
            watch.cancel()

            assertEquals("page moved while toggling: $moved", emptyList<String>(), moved.filter { it != here.cfi })
            assertEquals(block, sc.js("window.__readerTest.firstVisibleBlock()"))
            assertEquals(pageBefore, sc.js("JSON.stringify(window.__readerTest.page())"))
            assertFalse(h.a.controller.state.value.chromeVisible)
            h.stop()
        }
    }

    private fun waitStatusBar(sc: ActivityScenario<MainActivity>, visible: Boolean): Boolean {
        val end = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < end) {
            var v = !visible
            sc.onActivity { v = androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars()) ?: !visible }
            if (v == visible) return true
            Thread.sleep(100)
        }
        return false
    }
}
