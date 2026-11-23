package app.fernreader.reader

import app.fernreader.core.data.HybridClock
import app.fernreader.core.data.LibraryRepository
import app.fernreader.core.data.PageNumbers
import app.fernreader.core.data.PositionWriter
import app.fernreader.core.data.SettingsPort
import app.fernreader.core.data.SourceRef
import app.fernreader.core.data.buildDatabase
import app.fernreader.core.data.jvmDatabaseBuilder
import app.fernreader.core.epub.pages.BookPages
import app.fernreader.core.epub.pages.PageSource
import app.fernreader.reader.bridge.FromWeb
import app.fernreader.reader.bridge.ToWeb
import app.fernreader.reader.bridge.WebTexts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * C-108 (c): the screen-page counter runs only while screen pages are shown (the Screen settings, or Book pages for a book
 * without them), and its counts are stored per book and layout: a reopen with the same layout uses them, with no count.
 */
class ScreenPageCountTest {
    private class FakeReader : ReaderPort {
        val sent = mutableListOf<ToWeb>()
        override val events = MutableSharedFlow<FromWeb>(extraBufferCapacity = 64)
        override val isReady = MutableStateFlow(true)
        override fun send(m: ToWeb) { sent += m }
    }
    private class FakeOpener : BookOpenerPort {
        var pages: BookPages? = BookPages.decode(PageSource.NAV.name, "i\nii\nxii", "0.0,0.1000,2.0", "3000,0,500")
        override suspend fun open(ref: SourceRef) = OpenResult.Ok("s-1", "d".repeat(64), 100)
        override suspend fun close(sessionId: String) {}
        override suspend fun sectionStats(sessionId: String): List<SectionStat>? = listOf(SectionStat(3000, 0), SectionStat(0, 0), SectionStat(500, 0))
        override suspend fun bookPages(sessionId: String, withStats: Boolean, fixedLayout: Boolean) = pages?.let { BookPagesResult(it) }
    }
    private class MemSettings : SettingsPort {
        val map = mutableMapOf<String, String>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(key: String, value: String) { map[key] = value }
    }

    private val dispatcher = StandardTestDispatcher()
    private val reader = FakeReader()
    private val opener = FakeOpener()
    private val db = buildDatabase(jvmDatabaseBuilder(Files.createTempDirectory("db").resolve("t.db").toString()), dispatcher)
    private val clock = HybridClock(System::currentTimeMillis)
    private val library = LibraryRepository(db, clock)
    private val crashes = mutableListOf<Throwable>()
    private val scope = CoroutineScope(dispatcher + SupervisorJob() + CoroutineExceptionHandler { _, e -> crashes += e })
    private val appScope = CoroutineScope(dispatcher + SupervisorJob() + CoroutineExceptionHandler { _, e -> crashes += e })
    @AfterTest fun cancelScope() { scope.cancel(); appScope.cancel(); assertEquals(emptyList(), crashes) }
    private val ref = SourceRef.File("/books/a.epub")

    private suspend fun controller(background: CoroutineContext = EmptyCoroutineContext) = ReaderController(
        reader, opener, library, CompletableDeferred(PositionWriter.create(db, clock, appScope)), scope, WebTexts("missing"),
        closeScope = appScope, settingsStore = MemSettings(), background = background,
    )

    /** Counts the work handed to it (the app's background-priority threads in the app). */
    private class Recording(private val inner: CoroutineDispatcher) : CoroutineDispatcher() {
        var dispatched = 0
        override fun dispatch(context: CoroutineContext, block: Runnable) { dispatched++; inner.dispatch(context, block) }
    }
    private val opened = FromWeb.Opened(sessionId = "s-1", identifier = "urn:x", title = "A", authors = emptyList(), dir = "ltr", fixedLayout = false, sections = 3, toc = emptyList(), sizes = listOf(3000, 0, 500))
    private val counts = listOf(listOf(0, 7), listOf(1, 1), listOf(2, 2))

    private suspend fun TestScope.openPainted(c: ReaderController) {
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened); advanceUntilIdle()
        reader.events.emit(FromWeb.Relocate(sessionId = "s-1", cfi = "c", fraction = 0.1, sectionIndex = 0, page = 2, pages = 7, sectionFraction = 0.2))
        reader.events.emit(FromWeb.Painted(sessionId = "s-1")); advanceUntilIdle()
    }
    /** The web side: a layout generation is ready ([key]), announced with its sections first, as the counter does. */
    private suspend fun TestScope.ready(gen: Int, key: String = "k1") {
        reader.events.emit(FromWeb.PageCounts(sessionId = "s-1", gen = gen, linear = listOf(0, 1, 2)))
        reader.events.emit(FromWeb.CountReady(sessionId = "s-1", gen = gen, key = key)); advanceUntilIdle()
    }
    private suspend fun TestScope.counted(gen: Int) {
        reader.events.emit(FromWeb.PageCounts(sessionId = "s-1", gen = gen, linear = listOf(0, 1, 2)))
        reader.events.emit(FromWeb.PageCounts(sessionId = "s-1", gen = gen, counts = counts.take(2), sample = true))
        reader.events.emit(FromWeb.PageCounts(sessionId = "s-1", gen = gen, counts = counts.drop(2), done = true)); advanceUntilIdle()
    }
    private fun asked() = reader.sent.filterIsInstance<ToWeb.CountPages>()

    @Test fun book_pages_shown_nothing_is_counted() = runTest(dispatcher) {
        val c = controller()
        openPainted(c); ready(1)
        assertEquals(emptyList(), asked())
    }

    @Test fun screen_pages_are_counted_and_a_reopen_with_the_same_layout_counts_nothing() = runTest(dispatcher) {
        val c = controller()
        c.updateSettings { it.copy(pageNumbers = PageNumbers.SCREEN_BOOK) }
        openPainted(c); ready(1)
        assertEquals(listOf(ToWeb.CountPages(sessionId = "s-1", gen = 1, run = true)), asked())
        counted(1)
        assertEquals(PageIndicator(2, 10, exact = true, chapterScope = false), c.shownPage.value)
        c.close(); advanceUntilIdle(); reader.sent.clear()
        openPainted(c); ready(1)
        assertEquals(emptyList(), asked()) // stored for this book and layout
        assertEquals(PageIndicator(2, 10, exact = true, chapterScope = false), c.shownPage.value)
        ready(2, key = "k2") // another layout (a text size): counted
        assertEquals(listOf(ToWeb.CountPages(sessionId = "s-1", gen = 2, run = true)), asked())
    }

    @Test fun switching_to_screen_pages_counts_and_back_to_book_pages_stops_the_count() = runTest(dispatcher) {
        val c = controller()
        openPainted(c); ready(1)
        c.updateSettings { it.copy(pageNumbers = PageNumbers.SCREEN_CHAPTER) }; advanceUntilIdle()
        assertEquals(listOf(ToWeb.CountPages(sessionId = "s-1", gen = 1, run = true)), asked())
        c.updateSettings { it.copy(pageNumbers = PageNumbers.BOOK_PAGES) }; advanceUntilIdle()
        assertEquals(ToWeb.CountPages(sessionId = "s-1", gen = 1, run = false), asked().last())
        c.updateSettings { it.copy(pageNumbers = PageNumbers.SCREEN_BOOK) }; advanceUntilIdle()
        assertEquals(ToWeb.CountPages(sessionId = "s-1", gen = 1, run = true), asked().last()) // not stored: counted again
        assertEquals(3, asked().size)
    }

    @Test fun a_book_without_book_pages_counts_screen_pages_under_book_pages() = runTest(dispatcher) {
        opener.pages = null
        val c = controller()
        openPainted(c); ready(1)
        assertEquals(listOf(ToWeb.CountPages(sessionId = "s-1", gen = 1, run = true)), asked())
    }

    @Test fun counts_of_an_unfinished_or_older_generation_are_not_stored() = runTest(dispatcher) {
        val c = controller()
        c.updateSettings { it.copy(pageNumbers = PageNumbers.SCREEN_BOOK) }
        openPainted(c); ready(1)
        reader.events.emit(FromWeb.PageCounts(sessionId = "s-1", gen = 1, counts = counts.take(2), sample = true)); advanceUntilIdle()
        ready(2, key = "k2") // a layout change before the count finished
        counted(1) // late messages of the old generation
        c.close(); advanceUntilIdle(); reader.sent.clear()
        openPainted(c); ready(1)
        assertTrue(asked().isNotEmpty(), "nothing complete was stored for k1")
    }

    @Test fun stored_counts_are_read_and_written_on_the_background_context() = runTest(dispatcher) {
        val bg = Recording(dispatcher)
        val c = controller(bg)
        openPainted(c); ready(1)
        assertEquals(0, bg.dispatched, "Book pages shown: no count work at all")
        c.updateSettings { it.copy(pageNumbers = PageNumbers.SCREEN_BOOK) }; advanceUntilIdle()
        val afterRead = bg.dispatched
        assertTrue(afterRead > 0, "the stored counts are looked up on the background context")
        counted(1)
        assertTrue(bg.dispatched > afterRead, "the finished counts are stored on the background context")
        c.close(); advanceUntilIdle(); reader.sent.clear()
        openPainted(c); ready(1)
        assertEquals(emptyList(), asked()) // what the background store wrote is read back: no count
    }
}
