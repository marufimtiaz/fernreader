package app.epubreader.reader

import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.epubreader.EpubReaderApp
import app.epubreader.MainActivity
import app.epubreader.TestBooks
import app.epubreader.core.data.Flow
import app.epubreader.core.data.PageNumbers
import app.epubreader.core.data.ReaderSettings
import app.epubreader.core.data.SourceRef
import app.epubreader.core.epub.TextPos
import app.epubreader.core.epub.chunk.ChunkPolicy
import app.epubreader.core.epub.openFileSource
import app.epubreader.core.epub.pages.BookPages
import app.epubreader.core.epub.pages.BookPagesBuilder
import app.epubreader.core.epub.pages.PageSource
import app.epubreader.core.epub.pkg.LoadResult
import app.epubreader.core.epub.pkg.PackageLoader
import app.epubreader.core.epub.zip.ByteArraySource
import app.epubreader.core.epub.zip.ZipReader
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.reader.bridge.ToWeb
import app.epubreader.ui.Copy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * C-083 book pages on the device: the four fixtures (EPUB 3 page-list, EPUB 2 pageList, page-map, none) show their
 * pages; the number stays after text size, margin and reading-mode changes; "Go to page" takes a roman label; and a
 * chunked huge chapter (M8) gets the unchunked book's pages.
 */
@RunWith(AndroidJUnit4::class)
class BookPagesTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val dev = UiDevice.getInstance(inst)
    private val graph get() = (inst.targetContext.applicationContext as EpubReaderApp).graph
    private fun clearCache() { dev.waitForIdle(300); if (android.os.Build.VERSION.SDK_INT >= 34) inst.uiAutomation.clearCache() }

    private fun expected(bytes: ByteArray): BookPages {
        val z = ZipReader.open(ByteArraySource(bytes))
        return BookPagesBuilder.build(z, (PackageLoader.load(z) as LoadResult.Ok).pkg)
    }

    private suspend fun ReaderHarness.bookPage(): PageIndicator = withTimeout(20_000) { a.controller.shownPage.first { it.page != null } }

    /** Applies a settings change and waits until the reflowed page is reported and settled. */
    private suspend fun ReaderHarness.change(f: (ReaderSettings) -> ReaderSettings) {
        drain()
        sc.onActivity { it.controller.updateSettings(f) }
        delay(1_500); drain(); settle() // the reflow and its relocate (a change may also keep the same location)
    }

    @Test fun each_page_source_gives_its_pages(): Unit = runBlocking {
        val sources = mapOf(TestBooks.Pages.NAV to PageSource.NAV, TestBooks.Pages.NCX to PageSource.NCX, TestBooks.Pages.PAGE_MAP to PageSource.PAGE_MAP, TestBooks.Pages.NONE to PageSource.VIRTUAL)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready()
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } } // Book pages is the default
                for ((kind, source) in sources) {
                    val bytes = TestBooks.pagesEpub(kind)
                    val want = expected(bytes)
                    assertEquals(source, want.source)
                    h.drain()
                    h.open(TestBooks.write(inst.targetContext, "pages-$kind-${System.nanoTime()}.epub", bytes))
                    val p = h.bookPage()
                    Log.i("EPR", "C-083 $kind: $p")
                    assertEquals("$kind total", want.count, p.total)
                    assertEquals("$kind first page", 1, p.page)
                    if (source == PageSource.VIRTUAL) { assertNull(p.label); assertEquals("Page 1 of ${want.count}", Copy.pageLabel(p)) }
                    else { assertEquals("i", p.label); assertEquals("Page i of 6", Copy.pageLabel(p)) }
                    // Chapter 2 (spine 1) starts on its own page: iii, or the virtual page holding its first text.
                    h.drain()
                    h.send(ToWeb.GoTo(target = "c2.xhtml", id = 77)); h.next { e -> (e as? FromWeb.Seeked)?.takeIf { it.id == 77 } }
                    val c2 = withTimeout(5_000) { h.a.controller.shownPage.first { it.page != 1 } }
                    assertEquals("$kind chapter 2", want.label(want.indexAt(TextPos(1, 0))), c2.label ?: c2.page.toString())
                    sc.onActivity { it.goBack() }
                    withTimeout(10_000) { h.a.controller.state.first { !it.open } }
                }
            } finally { h.stop() }
        }
    }

    @Test fun numbers_stay_after_text_size_margin_and_mode_changes(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready()
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                for (kind in listOf(TestBooks.Pages.NONE, TestBooks.Pages.NAV)) {
                    h.drain()
                    h.open(TestBooks.write(inst.targetContext, "stay-$kind-${System.nanoTime()}.epub", TestBooks.pagesEpub(kind, paragraphs = 90)))
                    h.bookPage()
                    repeat(7) { h.send(ToWeb.Next()) }; h.settle(); delay(300)
                    val before = h.bookPage()
                    val screenBefore = h.a.controller.page.value
                    Log.i("EPR", "C-083 $kind before: $before (screen $screenBefore)")
                    assertTrue("$kind moved into the book: $before", (before.page ?: 0) > 1 || kind == TestBooks.Pages.NAV)
                    for ((what, f) in listOf<Pair<String, (ReaderSettings) -> ReaderSettings>>(
                        "text 140 %" to { s -> s.copy(fontScale = 1.4) },
                        "margins 10 %" to { s -> s.copy(marginPercent = 10) },
                        "scroll mode" to { s -> s.copy(flow = Flow.SCROLLED) },
                        "page mode, text 90 %" to { s -> s.copy(flow = Flow.PAGED, fontScale = 0.9) },
                        "defaults" to { _ -> ReaderSettings() },
                    )) {
                        h.change(f)
                        val now = h.bookPage()
                        Log.i("EPR", "C-083 $kind after $what: $now (screen ${h.a.controller.page.value})")
                        assertEquals("$kind after $what", before, now)
                    }
                    sc.onActivity { it.goBack() }
                    withTimeout(10_000) { h.a.controller.state.first { !it.open } }
                }
            } finally { sc.onActivity { it.controller.updateSettings { ReaderSettings() } }; h.stop() }
        }
    }

    @Test fun go_to_page_takes_a_roman_label(): Unit = runBlocking {
        val bytes = TestBooks.pagesEpub(TestBooks.Pages.NAV, paragraphs = 90)
        val want = expected(bytes)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            try {
                h.ready()
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                h.open(TestBooks.write(inst.targetContext, "goto-${System.nanoTime()}.epub", bytes))
                assertEquals("i", h.bookPage().label)
                // The controls' page label opens "Go to page".
                sc.onActivity { it.controller.toggleChrome() }
                clearCache()
                val label = dev.wait(Until.findObject(By.text("Page i of 6").clickable(true)), 5_000)
                assertNotNull("page label in the bottom bar", label)
                label.click()
                assertTrue("dialog", dev.wait(Until.hasObject(By.text(Copy.goToPage)), 5_000))
                assertTrue("range shown", dev.hasObject(By.text(Copy.pageRange("i", "vi"))))
                val field = dev.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000)
                // A label the book does not have: the dialog says so and stays.
                field.text = "xii"
                dev.findObject(By.text(Copy.goToPageAction)).click()
                assertTrue("no such page", dev.wait(Until.hasObject(By.text(Copy.noSuchPage("xii"))), 5_000))
                h.drain()
                clearCache()
                dev.findObject(By.clazz("android.widget.EditText")).text = "IV"
                dev.findObject(By.text(Copy.goToPageAction)).click()
                val landed = h.relocate(10_000)
                assertTrue("dialog closed", dev.wait(Until.gone(By.text(Copy.goToPage)), 5_000))
                val p = withTimeout(5_000) { h.a.controller.shownPage.first { it.label == "iv" } }
                assertEquals(4, p.page)
                // The landed screen holds page iv's start (chapter 2, before "Chapter 2, paragraph 46.").
                assertEquals(want.start(3).spineIndex, landed.sectionIndex)
                assertTrue("iv's start on screen: $landed", want.start(3).offset in landed.textOffset..landed.textEnd)
                assertEquals("true", sc.js("window.__readerTest.blockVisible('Chapter 2, paragraph 46.')"))
                // Turning on moves past it by the usual rule.
                h.drain(); h.send(ToWeb.Next()); h.relocate()
                assertTrue(withTimeout(5_000) { h.a.controller.shownPage.first { it.label != null } }.page!! >= 4)
            } finally { h.stop() }
        }
    }

    /** C-083 (5): the huge single chapter, read chunked, gets the unchunked book's pages, and go-to lands right in a part. */
    @Test fun a_chunked_chapter_gets_the_unchunked_pages(): Unit = runBlocking {
        val f = File("/sdcard/Books/generated/stress-huge-single-chapter.epub")
        warAndPeace() // grants all-files access
        check(f.isFile) { "corpus not pushed: $f" }
        val want = ZipReader.open(openFileSource(f.path)).use { z -> BookPagesBuilder.build(z, (PackageLoader.load(z) as LoadResult.Ok).pkg) }
        assertEquals(PageSource.VIRTUAL, want.source)
        val target = want.count * 2 / 3
        val seen = mutableListOf<Pair<PageIndicator, FromWeb.Relocate>>()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            val c = h.a.controller
            try {
                h.ready()
                sc.onActivity { it.controller.updateSettings { ReaderSettings(pageNumbers = PageNumbers.BOOK_PAGES) } }
                for (policy in listOf(ChunkPolicy(thresholdBytes = Int.MAX_VALUE), ChunkPolicy())) {
                    graph.opener.policy = policy
                    h.drain()
                    sc.onActivity { it.openBook(SourceRef.File(f.path)) }
                    h.relocate(120_000); h.painted(60_000)
                    withTimeout(60_000) { c.state.first { !it.loading } }
                    h.settle()
                    val first = withTimeout(120_000) { c.shownPage.first { it.page != null } }
                    assertEquals("$policy total", want.count, first.total)
                    h.drain()
                    var ok = false
                    sc.onActivity { ok = it.controller.goToPage("${target + 1}") }
                    assertTrue(ok)
                    val landed = h.relocate(60_000)
                    val p = withTimeout(10_000) { c.shownPage.first { it.page == target + 1 } }
                    seen += p to landed
                    Log.i("EPR", "C-083 chunked=${policy.thresholdBytes != Int.MAX_VALUE}: $p at $landed")
                    sc.onActivity { it.goBack() }
                    withTimeout(10_000) { c.state.first { !it.open } }
                    delay(800)
                }
                val (unchunked, chunked) = seen
                assertEquals(unchunked.first, chunked.first)
                assertTrue("the chunked reader shows a part: ${chunked.second.sectionIndex}", chunked.second.sectionIndex != unchunked.second.sectionIndex)
                // The number held after landing: the chunked screen, mapped to the original, holds the page's start
                // (BookPageNumbers drops the go-to pin otherwise, and the screen's first character is on the page before).
            } finally { graph.opener.policy = ChunkPolicy(); h.stop() }
        }
    }
}
