package app.epubreader.reader

import app.epubreader.core.data.Bookmark
import app.epubreader.core.data.BookmarkPort
import app.epubreader.core.data.HybridClock
import app.epubreader.core.data.LibraryRepository
import app.epubreader.core.data.PageNumbers
import app.epubreader.core.data.PositionWriter
import app.epubreader.core.data.ReaderSettings
import app.epubreader.core.data.SettingsPort
import app.epubreader.core.data.SourceRef
import app.epubreader.core.data.buildDatabase
import app.epubreader.core.data.jvmDatabaseBuilder
import app.epubreader.core.epub.PositionMap
import app.epubreader.core.epub.pages.BookPages
import app.epubreader.core.epub.pages.PageSource
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.reader.bridge.ToWeb
import app.epubreader.reader.bridge.WebTexts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** C-083 in the controller: pages after the first paint, stored per file, shown per setting, go to page, list labels. */
class BookPagesControllerTest {
    private class FakeReader : ReaderPort {
        val sent = mutableListOf<ToWeb>()
        override val events = MutableSharedFlow<FromWeb>(extraBufferCapacity = 64)
        override val isReady = MutableStateFlow(true)
        override fun send(m: ToWeb) { sent += m }
    }
    private class FakeOpener : BookOpenerPort {
        var pagesCalls = 0
        var statsCalls = 0
        var pages: BookPages? = BookPages.decode(PageSource.NAV.name, "i\nii\nxii", "0.0,0.1000,2.0", "3000,0,500")
        var map: PositionMap = PositionMap.Identity
        /** While set, the book pages pass does not end until it is completed. */
        var gate: CompletableDeferred<Unit>? = null
        /** The opener calls in order ("stats", "pages" or "pages+stats"). */
        val calls = mutableListOf<String>()
        override suspend fun open(ref: SourceRef) = OpenResult.Ok("s-1", "d".repeat(64), 100, map)
        override suspend fun close(sessionId: String) {}
        override suspend fun sectionStats(sessionId: String): List<SectionStat>? { statsCalls++; calls += "stats"; return listOf(SectionStat(1, 0), SectionStat(0, 0), SectionStat(1, 0)) }
        /** The fixedLayout flag of the last book pages pass. */
        var askedFixed: Boolean? = null
        override suspend fun bookPages(sessionId: String, withStats: Boolean, fixedLayout: Boolean): BookPagesResult? {
            pagesCalls++; calls += if (withStats) "pages+stats" else "pages"; askedFixed = fixedLayout
            gate?.await()
            return pages?.let { BookPagesResult(it, if (withStats) listOf(SectionStat(3000, 0), SectionStat(0, 0), SectionStat(500, 0)) else null) }
        }
    }
    private class MemSettings : SettingsPort {
        val map = mutableMapOf<String, String>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(key: String, value: String) { map[key] = value }
    }
    private class FakeBookmarks : BookmarkPort {
        val all = MutableStateFlow(listOf(Bookmark("b1", "", "epubcfi(/6/2!/4/2/1:5)", 0, 0.1, "One", 1, 1)))
        override fun bookmarks(bookId: String) = kotlinx.coroutines.flow.flow { all.collect { emit(it) } }
        override suspend fun add(bookId: String, cfi: String, spineIndex: Int, fraction: Double, chapter: String) = error("unused")
        override suspend fun remove(id: String) {}
        override suspend fun removeAll(ids: List<String>) {}
    }

    private val dispatcher = StandardTestDispatcher()
    private val reader = FakeReader()
    private val opener = FakeOpener()
    private val db = buildDatabase(jvmDatabaseBuilder(Files.createTempDirectory("db").resolve("t.db").toString()), dispatcher)
    private val clock = HybridClock(System::currentTimeMillis)
    private val library = LibraryRepository(db, clock)
    private val settings = MemSettings()
    private val crashes = mutableListOf<Throwable>()
    private val scope = CoroutineScope(dispatcher + SupervisorJob() + CoroutineExceptionHandler { _, e -> crashes += e })
    private val appScope = CoroutineScope(dispatcher + SupervisorJob() + CoroutineExceptionHandler { _, e -> crashes += e })
    @AfterTest fun cancelScope() { scope.cancel(); appScope.cancel(); assertEquals(emptyList(), crashes) }
    private val ref = SourceRef.File("/books/a.epub")

    private suspend fun controller() = ReaderController(
        reader, opener, library, CompletableDeferred(PositionWriter.create(db, clock, appScope)), scope, WebTexts("missing"),
        closeScope = appScope, settingsStore = settings, bookmarks = FakeBookmarks(),
    )
    private fun relocate(section: Int, start: Int, end: Int = start + 700) =
        FromWeb.Relocate(sessionId = "s-1", cfi = "c$section:$start", fraction = 0.2, sectionIndex = section, textOffset = start, textEnd = end)
    private var fixedLayout = false
    private fun opened() = FromWeb.Opened(sessionId = "s-1", identifier = "urn:x", title = "A", authors = emptyList(), dir = "ltr", fixedLayout = fixedLayout, sections = 3, toc = emptyList(), sizes = listOf(3000, 0, 500))

    private suspend fun kotlinx.coroutines.test.TestScope.openPainted(c: ReaderController) {
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        reader.events.emit(relocate(0, 1200)); reader.events.emit(FromWeb.Painted(sessionId = "s-1")); advanceUntilIdle()
    }

    @Test fun pages_come_after_the_first_paint_with_the_stats_and_are_stored_per_file() = runTest(dispatcher) {
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        reader.events.emit(relocate(0, 1200)); advanceUntilIdle()
        assertEquals(0, opener.pagesCalls) // never on the open path
        assertNull(c.shownPage.value.page) // Book pages (default): nothing until the pages are known
        reader.events.emit(FromWeb.Painted(sessionId = "s-1")); advanceUntilIdle()
        assertEquals(1, opener.pagesCalls); assertEquals(0, opener.statsCalls) // one pass gave both
        assertEquals("ii", c.shownPage.value.label)
        assertEquals("Page ii of 3", app.epubreader.ui.Copy.pageLabel(c.shownPage.value))
        c.close(); advanceUntilIdle()
        openPainted(c)
        assertEquals(1, opener.pagesCalls); assertEquals(0, opener.statsCalls) // both stored
        assertEquals("ii", c.shownPage.value.label)
    }

    @Test fun the_setting_chooses_book_or_screen_pages_and_text_size_keeps_the_book_page() = runTest(dispatcher) {
        val c = controller()
        openPainted(c)
        assertEquals("ii", c.shownPage.value.label)
        c.updateSettings { it.copy(pageNumbers = PageNumbers.SCREEN_BOOK) }; advanceUntilIdle()
        assertNull(c.shownPage.value.label) // screen pages (the counter's)
        c.updateSettings { it.copy(pageNumbers = PageNumbers.BOOK_PAGES) }; advanceUntilIdle()
        c.updateSettings { it.copy(fontScale = 2.0) } // the reflowed screen starts on page i but still shows ii's first character
        reader.events.emit(relocate(0, 800, end = 1250)); advanceUntilIdle()
        assertEquals("ii", c.shownPage.value.label)
        assertEquals(PageNumbers.BOOK_PAGES, ReaderSettings.decode(settings.map[ReaderSettings.KEY]).pageNumbers)
    }

    @Test fun go_to_page_takes_print_labels_and_says_when_there_is_none() = runTest(dispatcher) {
        val c = controller()
        openPainted(c); reader.sent.clear()
        c.showGoToPage(); assertTrue(c.state.value.goToPageVisible)
        assertEquals("i" to "xii", c.bookPageRange())
        assertFalse(c.goToPage("xiii")); assertTrue(reader.sent.isEmpty()); assertTrue(c.state.value.goToPageVisible)
        assertTrue(c.goToPage("XII"))
        assertEquals(ToWeb.GoToOffset(sectionIndex = 2, offset = 0), reader.sent.single())
        assertFalse(c.state.value.goToPageVisible)
        reader.events.emit(relocate(2, 0)); advanceUntilIdle()
        assertEquals("xii", c.shownPage.value.label)
    }

    @Test fun lists_get_book_pages_bookmarks_through_the_web_side_and_search_hits_directly() = runTest(dispatcher) {
        val c = controller()
        openPainted(c)
        val ask = reader.sent.filterIsInstance<ToWeb.TextOffsets>().single()
        assertEquals(listOf("epubcfi(/6/2!/4/2/1:5)"), ask.cfis)
        reader.events.emit(FromWeb.TextOffsets(sessionId = "s-1", id = ask.id, offsets = listOf(listOf(0, 1500)))); advanceUntilIdle()
        assertEquals(mapOf("epubcfi(/6/2!/4/2/1:5)" to "ii"), c.state.value.bookPageLabels)
        assertEquals("xii", c.searchHitPage(2, 10))
        c.updateSettings { it.copy(pageNumbers = PageNumbers.SCREEN_CHAPTER) }
        assertNull(c.searchHitPage(2, 10))
        c.showGoToPage(); assertFalse(c.state.value.goToPageVisible) // offered with Book pages only
    }

    @Test fun a_book_without_pages_falls_back_to_screen_pages() = runTest(dispatcher) {
        opener.pages = null
        val c = controller()
        openPainted(c)
        assertEquals(c.page.value, c.shownPage.value)
        assertFalse(c.goToPage("1"))
    }

    /** Review fix 1: cached section stats reach the page totals at once, never after the (slow) book pages pass. */
    @Test fun cached_stats_reach_the_totals_before_the_book_pages_exist() = runTest(dispatcher) {
        opener.pages = null // first open: no pages, so the stats come from their own pass and are cached
        val c = controller()
        openPainted(c); c.close(); advanceUntilIdle()
        assertEquals(1, opener.statsCalls)
        opener.pages = BookPages.decode(PageSource.NAV.name, "i\nii\nxii", "0.0,0.1000,2.0", "3000,0,500")
        opener.gate = CompletableDeferred()
        openPainted(c)
        assertEquals(2, opener.pagesCalls) // the second pass is running (held)
        assertNotNull(c.sectionStats) // the cached stats are with the totals already
        assertEquals(listOf(SectionStat(1, 0), SectionStat(0, 0), SectionStat(1, 0)), c.sectionStats)
        assertNull(c.bookPage.value.page)
        assertEquals(1, opener.statsCalls) // from the cache
        opener.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals("ii", c.shownPage.value.label)
    }

    /** Review fix 1: a chunked book's stats (its parts) come before its pages pass (over the original document). */
    @Test fun a_chunked_book_gets_its_stats_before_the_book_pages_pass() = runTest(dispatcher) {
        opener.map = object : PositionMap by PositionMap.Identity {} // any map but the identity: chunked
        opener.gate = CompletableDeferred()
        val c = controller()
        openPainted(c)
        assertEquals(listOf("stats", "pages"), opener.calls) // the pages pass never computes a chunked book's stats
        assertNotNull(c.sectionStats)
        assertNull(c.bookPage.value.page)
        opener.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals("ii", c.shownPage.value.label)
        assertEquals(listOf("stats", "pages"), opener.calls)
    }

    private fun fixedRelocate(section: Int) = FromWeb.Relocate(sessionId = "s-1", cfi = "f$section", fraction = 0.0, sectionIndex = section) // no text range

    /**
     * C-087: a fixed-layout book (a comic) shows its own pages, "Page n of N" with N its pages (spine items), from
     * `opened` on (never a blank number), under every Page numbers setting, in the indicator, the slider preview, go to
     * page and the lists. The pages pass is asked for a fixed-layout book's pages.
     */
    @Test fun a_fixed_layout_book_shows_its_own_pages_under_every_setting() = runTest(dispatcher) {
        fixedLayout = true
        opener.pages = BookPages.fixedLayout(3)
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        reader.events.emit(fixedRelocate(1)); advanceUntilIdle()
        assertEquals("Page 2 of 3", app.epubreader.ui.Copy.pageLabel(c.shownPage.value)) // before the pages pass
        assertTrue(c.bookPagesInUse.value)
        reader.events.emit(FromWeb.Painted(sessionId = "s-1")); advanceUntilIdle()
        assertEquals(1, opener.pagesCalls); assertEquals(true, opener.askedFixed)
        assertEquals(listOf("pages+stats"), opener.calls) // one pass for both, as for any unchunked book
        assertEquals("Page 2 of 3", app.epubreader.ui.Copy.pageLabel(c.shownPage.value))
        reader.events.emit(fixedRelocate(2)); advanceUntilIdle() // a page turn
        assertEquals("Page 3 of 3", app.epubreader.ui.Copy.pageLabel(c.shownPage.value))
        for (mode in listOf(PageNumbers.SCREEN_BOOK, PageNumbers.SCREEN_CHAPTER, PageNumbers.BOOK_PAGES)) {
            c.updateSettings { it.copy(pageNumbers = mode) }; advanceUntilIdle()
            assertEquals("Page 3 of 3", app.epubreader.ui.Copy.pageLabel(c.shownPage.value), "$mode")
            assertTrue(c.bookPagesInUse.value)
            c.previewSeek(0.0)
            assertEquals("Page 1 of 3", app.epubreader.ui.Copy.pageLabel(c.seekPreview.value!!.page))
            c.cancelSeek()
            assertEquals("2", c.searchHitPage(1, 10))
        }
        assertEquals("1" to "3", c.bookPageRange())
        c.showGoToPage(); assertTrue(c.state.value.goToPageVisible)
        reader.sent.clear()
        assertFalse(c.goToPage("4"))
        assertTrue(c.goToPage("1"))
        assertEquals(ToWeb.GoToOffset(sectionIndex = 0, offset = 0), reader.sent.single())
        reader.events.emit(fixedRelocate(0)); advanceUntilIdle()
        assertEquals("Page 1 of 3", app.epubreader.ui.Copy.pageLabel(c.shownPage.value))
    }

    /** C-087: a fixed-layout book with a print page list shows its labels; go to page takes them. */
    @Test fun a_fixed_layout_book_with_a_page_list_shows_its_labels() = runTest(dispatcher) {
        fixedLayout = true
        opener.pages = BookPages.decode(PageSource.NAV.name, "Cover\ni\nii", "0.0,1.0,2.0", "0,0,0")
        val c = controller()
        openPainted(c)
        reader.events.emit(fixedRelocate(1)); advanceUntilIdle()
        assertEquals("Page i of 3", app.epubreader.ui.Copy.pageLabel(c.shownPage.value))
        c.updateSettings { it.copy(pageNumbers = PageNumbers.SCREEN_CHAPTER) }; advanceUntilIdle()
        assertEquals("Page i of 3", app.epubreader.ui.Copy.pageLabel(c.shownPage.value))
        val ask = reader.sent.filterIsInstance<ToWeb.TextOffsets>().last()
        reader.events.emit(FromWeb.TextOffsets(sessionId = "s-1", id = ask.id, offsets = listOf(listOf(2, 0)))); advanceUntilIdle()
        assertEquals(mapOf("epubcfi(/6/2!/4/2/1:5)" to "ii"), c.state.value.bookPageLabels) // the bookmarks list
        reader.sent.clear()
        assertTrue(c.goToPage("II"))
        assertEquals(ToWeb.GoToOffset(sectionIndex = 2, offset = 0), reader.sent.single())
    }

    /** C-087: if a fixed-layout book's pages can't be computed it keeps its pages 1..N: never a blank number. */
    @Test fun a_fixed_layout_book_whose_pages_fail_keeps_its_page_numbers() = runTest(dispatcher) {
        fixedLayout = true
        opener.pages = null
        val c = controller()
        openPainted(c)
        reader.events.emit(fixedRelocate(2)); advanceUntilIdle()
        assertEquals("Page 3 of 3", app.epubreader.ui.Copy.pageLabel(c.shownPage.value))
        assertTrue(c.bookPagesInUse.value)
        assertTrue(c.goToPage("2"))
    }
}
