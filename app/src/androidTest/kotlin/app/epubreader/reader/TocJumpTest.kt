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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** F-040 jump through the real TOC panel; R-001 missing target keeps the reader where it is. */
@RunWith(AndroidJUnit4::class)
class TocJumpTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val dev = UiDevice.getInstance(inst)

    @Test fun toc_entry_jumps_and_missing_entry_stays(): Unit = runBlocking {
        val f = TestBooks.write(inst.targetContext, "tocjump.epub", TestBooks.epub(id = "urn:uuid:toc-${System.nanoTime()}", chapters = 5, paragraphs = 20, missingTocTarget = true))
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            lateinit var a: MainActivity
            sc.onActivity { a = it }
            withTimeout(15_000) { a.reader.isReady.first { it } }
            val firstReloc = async(start = CoroutineStart.UNDISPATCHED) { a.reader.events.filterIsInstance<FromWeb.Relocate>().first() }
            sc.onActivity { it.openBook(SourceRef.File(f.path)) }
            withTimeout(10_000) { firstReloc.await() }
            withTimeout(5_000) { a.controller.state.first { !it.loading && it.toc.isNotEmpty() } }

            fun openToc() {
                dev.click(dev.displayWidth / 2, dev.displayHeight / 2)
                assertTrue(dev.wait(Until.hasObject(By.text(Copy.contents)), 3_000))
                dev.findObject(By.text(Copy.contents)).click()
                assertTrue(dev.wait(Until.hasObject(By.text(Copy.close)), 3_000))
            }
            openToc()
            val jumped = async(start = CoroutineStart.UNDISPATCHED) { a.reader.events.filterIsInstance<FromWeb.Relocate>().first() }
            dev.findObject(By.text("Chapter 4")).click()
            assertEquals(3, withTimeout(5_000) { jumped.await() }.sectionIndex)
            withTimeout(3_000) { a.controller.state.first { !it.tocVisible } }

            openToc()
            dev.findObject(By.text("Missing chapter")).click()
            assertTrue(dev.wait(Until.hasObject(By.text(Copy.missingTarget)), 3_000))
            assertTrue(a.controller.state.value.tocVisible) // stays in the panel; reader position unchanged
        }
    }
}
