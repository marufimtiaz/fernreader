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
        // No settle() here: its goTo to the current location is a navigation, which would re-anchor the reader at the
        // reflowed screen's start (the drift F-037 forbids comes from that, not from the reflow). Only waits.
        runCatching { relocate(5_000) }; delay(1_500); drain()
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
                    h.tocJump("Chapter 2")
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
                    repeat(7) { h.drain(); h.send(ToWeb.Next()); h.relocate() } // one turn at a time (the queue keeps 3)
                    delay(300); h.settle()
                    val before = h.bookPage()
                    val screenBefore = h.a.controller.page.value
                    Log.i("EPR", "C-083 $kind before: $before (screen $screenBefore) at ${h.at?.sectionIndex}:${h.at?.textOffset} block ${sc.js("window.__readerTest.firstVisibleBlock()").take(50)}")
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
                        Log.i("EPR", "C-083 $kind after $what: $now (screen ${h.a.controller.page.value}) at ${h.at?.textOffset} block ${sc.js("window.__readerTest.firstVisibleBlock()").take(50)}")
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
                // Review fix 3 (C-086): a 48 dp button found by its accessible name, the label text shown in it.
                val label = dev.wait(Until.findObject(By.desc(Copy.goToPageEntry("Page i of 6"))), 5_000)
                assertNotNull("Go to page button in the bottom bar", label)
                // The accessibility tree exposes the clickable 48 dp node and its description as parent and child.
                val button = if (label.isClickable) label else label.parent
                assertTrue("a button", button?.isClickable == true)
                val minPx = 48 * inst.targetContext.resources.displayMetrics.density - 1
                assertTrue("48 dp target: ${button!!.visibleBounds}", button.visibleBounds.height() >= minPx && button.visibleBounds.width() >= minPx)
                assertTrue("label text kept", dev.findObjects(By.text("i/6")).any { button.visibleBounds.contains(it.visibleBounds) })
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

    /** The indicator's text once the shown page is [ok]. */
    private suspend fun ReaderHarness.shown(ok: (PageIndicator) -> Boolean = { it.page != null }): String =
        Copy.pageLabel(withTimeout(20_000) { a.controller.shownPage.first(ok) })!!

    /**
     * C-087: a fixed-layout book (page-blanche, a picture book: 10 spine items, a nav page-list "1".."8" from the third)
     * shows "Page n of N" under every Page numbers setting: on the first open 1..10 at once, then its print pages once
     * the pages pass has run; on the next open the print pages at once. Turning a page moves n; the bottom bar's label is
     * the Go to page entry.
     */
    @Test fun a_fixed_layout_book_shows_its_own_pages(): Unit = runBlocking {
        warAndPeace() // grants all-files access
        val f = File("/sdcard/Books/epub3-samples/page-blanche.epub").also { check(it.isFile) { "corpus not pushed: $it" } }
        val want = ZipReader.open(openFileSource(f.path)).use { z -> BookPagesBuilder.build(z, (PackageLoader.load(z) as LoadResult.Ok).pkg, fixedLayout = true) }
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            val c = h.a.controller
            try {
                h.ready()
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } } // Book pages is the default
                h.drain()
                fun text(section: Int) = want.indexAt(TextPos(section, 0)).let { i -> "Page ${want.label(i)} of ${want.count}" }
                sc.onActivity { it.openBook(SourceRef.File(f.path)) }
                var first = h.relocate(30_000)
                val early = h.shown() // a number from `opened` on, before the pages pass (a fresh install: nothing stored)
                Log.i("EPR", "C-087 page-blanche first open: $early")
                assertTrue(early, Regex("Page \\S+ of \\d+").matches(early))
                h.painted(30_000)
                withTimeout(20_000) { c.state.first { !it.loading } }
                assertEquals(text(first.sectionIndex), h.shown { it.total == want.count }) // the print pages, after the pass
                assertTrue(c.bookPagesInUse.value)
                sc.onActivity { it.goBack() }
                withTimeout(10_000) { c.state.first { !it.open } }
                delay(800); h.drain()
                sc.onActivity { it.openBook(SourceRef.File(f.path)) } // again: the stored print pages at once
                first = h.relocate(30_000)
                assertEquals(text(first.sectionIndex), h.shown())
                h.painted(30_000)
                withTimeout(20_000) { c.state.first { !it.loading } }
                delay(2_000)
                Log.i("EPR", "C-087 page-blanche: ${c.shownPage.value} (${want.source} ${want.count}) screen ${c.page.value}")
                assertEquals(want.count, c.shownPage.value.total)
                h.drain(); h.send(ToWeb.Next())
                val turned = h.relocate(10_000) { it.sectionIndex != first.sectionIndex }
                val after = h.shown { Copy.pageLabel(it) == text(turned.sectionIndex) }
                assertTrue("n moved: $after", turned.sectionIndex > first.sectionIndex)
                for (mode in listOf(PageNumbers.SCREEN_BOOK, PageNumbers.SCREEN_CHAPTER)) {
                    sc.onActivity { it.controller.updateSettings { s -> s.copy(pageNumbers = mode) } }
                    delay(300)
                    assertEquals("$mode", after, Copy.pageLabel(c.shownPage.value))
                }
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                sc.onActivity { it.controller.toggleChrome() }
                clearCache()
                val label = dev.wait(Until.findObject(By.text(after)), 5_000)
                assertNotNull("page label $after on the bar", label)
                assertTrue("the go-to entry is offered", dev.hasObject(By.descContains(Copy.goToPage)))
                sc.onActivity { it.controller.toggleChrome() }
                h.drain()
                var ok = false
                val last = want.count - 1 // the last page: "8", spine item 9
                sc.onActivity { ok = it.controller.goToPage(want.label(last)) }
                assertTrue(ok)
                h.relocate(10_000) { it.sectionIndex == want.start(last).spineIndex }
                assertEquals(text(want.start(last).spineIndex), h.shown { it.page == last + 1 })
            } finally { h.stop() }
        }
    }

    /** C-087: a fixed-layout book with a print page-list shows its labels ("Page ii of 4"); go to page takes them. */
    @Test fun a_fixed_layout_book_with_a_page_list_shows_its_labels(): Unit = runBlocking {
        val bytes = TestBooks.fixedLayoutEpub(pages = 4, pageList = true)
        val want = expected(bytes)
        assertEquals(PageSource.NAV, want.source); assertEquals(4, want.count)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            val h = ReaderHarness(sc, this)
            val c = h.a.controller
            try {
                h.ready()
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                h.drain()
                h.open(TestBooks.write(inst.targetContext, "fxl-list-${System.nanoTime()}.epub", bytes), withToc = false)
                assertEquals("Page i of 4", h.shown { it.label == "i" })
                h.drain(); h.send(ToWeb.Next()); h.relocate(10_000) { it.sectionIndex == 1 }
                assertEquals("Page ii of 4", h.shown { it.label == "ii" })
                sc.onActivity { it.controller.updateSettings { s -> s.copy(pageNumbers = PageNumbers.SCREEN_CHAPTER) } }
                delay(300)
                assertEquals("Page ii of 4", Copy.pageLabel(c.shownPage.value))
                h.drain()
                var ok = false
                sc.onActivity { ok = it.controller.goToPage("IV") }
                assertTrue(ok)
                h.relocate(10_000) { it.sectionIndex == 3 }
                assertEquals("Page iv of 4", h.shown { it.label == "iv" })
            } finally {
                sc.onActivity { it.controller.updateSettings { ReaderSettings() } }
                h.stop()
            }
        }
    }
}
