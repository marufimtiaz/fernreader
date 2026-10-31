package app.epubreader.reader

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.epubreader.MainActivity
import app.epubreader.TestBooks
import app.epubreader.core.data.SourceRef
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.ui.Copy
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Review Focus 4: swipes reach the WebView under the Compose chrome; taps on chrome never reach the book. */
@RunWith(AndroidJUnit4::class)
class ReaderTouchTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val dev = UiDevice.getInstance(inst)

    @Test fun swipe_turns_page_and_chrome_buttons_do_not_leak(): Unit = runBlocking {
        val f = TestBooks.write(inst.targetContext, "touch.epub", TestBooks.epub(paragraphs = 80))
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            lateinit var a: MainActivity
            sc.onActivity { a = it }
            withTimeout(15_000) { a.reader.isReady.first { it } }
            val firstReloc = async(start = CoroutineStart.UNDISPATCHED) { a.reader.events.filterIsInstance<FromWeb.Relocate>().first() }
            sc.onActivity { it.openBook(SourceRef.File(f.path)) }
            val first = withTimeout(10_000) { firstReloc.await() }
            withTimeout(5_000) { a.controller.state.first { !it.loading } }

            val w = dev.displayWidth; val h = dev.displayHeight
            val turned = async(start = CoroutineStart.UNDISPATCHED) { a.reader.events.filterIsInstance<FromWeb.Relocate>().first() }
            dev.swipe((w * 0.85).toInt(), h / 2, (w * 0.15).toInt(), h / 2, 10)
            assertNotEquals(first.cfi, withTimeout(5_000) { turned.await() }.cfi)

            dev.click(w / 2, h / 2) // center tap → chrome
            assertTrue(dev.wait(Until.hasObject(By.desc(Copy.contents)), 3_000))
            val leaked = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(1_500) { a.reader.events.filterIsInstance<FromWeb.Tap>().first() }
            }
            dev.findObject(By.desc(Copy.contents)).click()
            assertNull("tap on a chrome button reached the WebView", leaked.await())
            withTimeout(3_000) { a.controller.state.first { it.tocVisible } }
        }
    }
}
