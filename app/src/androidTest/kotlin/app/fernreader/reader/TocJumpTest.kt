package app.fernreader.reader

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.fernreader.EpubReaderApp
import app.fernreader.MainActivity
import app.fernreader.TestBooks
import app.fernreader.core.data.SourceRef
import app.fernreader.reader.bridge.FromWeb
import app.fernreader.ui.Copy
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
                // a tap right after the first page can be lost while the page settles: one more if the controls don't come
                dev.click(dev.displayWidth / 2, dev.displayHeight / 2)
                if (!dev.wait(Until.hasObject(By.desc(Copy.contents)), 3_000)) dev.click(dev.displayWidth / 2, dev.displayHeight / 2)
                assertTrue(dev.wait(Until.hasObject(By.desc(Copy.contents)), 3_000))
                dev.findObject(By.desc(Copy.contents)).click()
                assertTrue(dev.wait(Until.hasObject(By.text(Copy.close)), 3_000))
            }
            openToc()
            val jumped = async(start = CoroutineStart.UNDISPATCHED) { a.reader.events.filterIsInstance<FromWeb.Relocate>().first() }
            dev.findObject(By.text("Chapter 4")).click()
            val at = withTimeout(5_000) { jumped.await() }
            assertEquals(3, at.sectionIndex)
            withTimeout(3_000) { a.controller.state.first { !it.tocVisible } }

            openToc()
            val moves = mutableListOf<FromWeb.Relocate>()
            val watch = launch(start = CoroutineStart.UNDISPATCHED) { a.reader.events.filterIsInstance<FromWeb.Relocate>().collect { moves += it } }
            dev.findObject(By.text("Missing chapter")).click()
            assertTrue(dev.wait(Until.hasObject(By.text(Copy.missingTarget)), 3_000))
            assertTrue(a.controller.state.value.tocVisible) // stays in the panel
            delay(1_000) // a jump would have relocated by now
            watch.cancel()
            // The reader did not move. foliate may report the same page again (its snap after the tap that opened
            // the panel), so compare locations, not the number of events.
            assertEquals("jumped to $at", emptyList<String>(), moves.map { it.cfi }.filter { it != at.cfi })
            assertEquals(at.tocHref, a.controller.state.value.currentTocHref)
            val library = (a.application as EpubReaderApp).graph.library
            sc.onActivity { it.controller.flushPosition() } // C-108 (e): stored at most every 5 s while reading; now at once
            val id = library.knownBookId(SourceRef.File(f.path))!!
            withTimeout(3_000) { while (library.position(id)?.cfi != at.cfi) delay(20) } // nor the stored position
        }
    }
}
