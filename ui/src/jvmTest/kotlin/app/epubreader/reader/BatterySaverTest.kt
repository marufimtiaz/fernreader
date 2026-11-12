package app.epubreader.reader

import app.epubreader.core.data.HybridClock
import app.epubreader.core.data.LibraryRepository
import app.epubreader.core.data.PageAnimation
import app.epubreader.core.data.PageNumbers
import app.epubreader.core.data.PositionWriter
import app.epubreader.core.data.ReaderSettings
import app.epubreader.core.data.ReadingThemeId
import app.epubreader.core.data.SettingsPort
import app.epubreader.core.data.SourceRef
import app.epubreader.core.data.buildDatabase
import app.epubreader.core.data.jvmDatabaseBuilder
import app.epubreader.core.epub.pages.BookPages
import app.epubreader.core.epub.pages.PageSource
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.reader.bridge.ToWeb
import app.epubreader.reader.bridge.WebTexts
import app.epubreader.ui.Copy
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C-107 "Follow Battery Saver": while the phone's power save mode is on, the reader shows Black, turns pages with no
 * animation, does not run the screen-page counter (a percentage where screen pages would show) and the activity asks for
 * the lowest refresh rate ([batterySaving]); all of it live and back, the saved settings untouched. With the setting
 * off nothing changes.
 */
class BatterySaverTest {
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
    private val store = MemSettings()
    private val db = buildDatabase(jvmDatabaseBuilder(Files.createTempDirectory("db").resolve("t.db").toString()), dispatcher)
    private val clock = HybridClock(System::currentTimeMillis)
    private val library = LibraryRepository(db, clock)
    private val crashes = mutableListOf<Throwable>()
    private val scope = CoroutineScope(dispatcher + SupervisorJob() + CoroutineExceptionHandler { _, e -> crashes += e })
    private val appScope = CoroutineScope(dispatcher + SupervisorJob() + CoroutineExceptionHandler { _, e -> crashes += e })
    @AfterTest fun cancelScope() { scope.cancel(); appScope.cancel(); assertEquals(emptyList(), crashes) }

    private suspend fun controller() = ReaderController(
        reader, opener, library, CompletableDeferred(PositionWriter.create(db, clock, appScope)), scope, WebTexts("missing"),
        closeScope = appScope, settingsStore = store,
    )
    private val opened = FromWeb.Opened(sessionId = "s-1", identifier = "urn:x", title = "A", authors = emptyList(), dir = "ltr", fixedLayout = false, sections = 3, toc = emptyList(), sizes = listOf(3000, 0, 500))

    /** A book open on screen pages at 42 % of the book, its layout generation 1 ready and being counted. */
    private suspend fun TestScope.openOnScreenPages(c: ReaderController, saved: ReaderSettings = ReaderSettings(pageNumbers = PageNumbers.SCREEN_BOOK, theme = ReadingThemeId.WARM)) {
        c.updateSettings { saved }; advanceUntilIdle()
        c.open(SourceRef.File("/books/a.epub")); advanceUntilIdle(); reader.events.emit(opened); advanceUntilIdle()
        reader.events.emit(FromWeb.Relocate(sessionId = "s-1", cfi = "c", fraction = 0.42, sectionIndex = 0, page = 2, pages = 7, sectionFraction = 0.2))
        reader.events.emit(FromWeb.Painted(sessionId = "s-1")); advanceUntilIdle()
        reader.events.emit(FromWeb.PageCounts(sessionId = "s-1", gen = 1, linear = listOf(0, 1, 2)))
        reader.events.emit(FromWeb.CountReady(sessionId = "s-1", gen = 1, key = "k1")); advanceUntilIdle()
    }
    private fun lastSettings() = reader.sent.filterIsInstance<ToWeb.SetSettings>().last().settings
    private fun counts() = reader.sent.filterIsInstance<ToWeb.CountPages>()

    @Test fun battery_saver_switches_everything_live_and_back() = runTest(dispatcher) {
        val c = controller()
        openOnScreenPages(c)
        val saved = c.settings.value
        assertEquals(listOf(ToWeb.CountPages(sessionId = "s-1", gen = 1, run = true)), counts())
        assertEquals("warm", lastSettings().theme)
        assertEquals(2, c.shownPage.value.page)
        reader.sent.clear()

        c.setPowerSave(true); advanceUntilIdle()
        assertTrue(c.batterySaving.value)
        assertEquals("black", lastSettings().theme)
        assertEquals("none", lastSettings().pageAnimation)
        assertEquals(ReadingThemeId.BLACK, c.shownSettings.value.theme)
        assertEquals(PageAnimation.NONE, c.shownSettings.value.pageAnimation)
        assertEquals(listOf(ToWeb.CountPages(sessionId = "s-1", gen = 1, run = false)), counts()) // the running count stops
        assertEquals(42, c.shownPage.value.percent)
        assertEquals("42%", Copy.pageLabelShort(c.shownPage.value))
        // A new layout meanwhile is not counted.
        reader.events.emit(FromWeb.CountReady(sessionId = "s-1", gen = 2, key = "k2")); advanceUntilIdle()
        assertEquals(1, counts().size)
        // The saved settings are what the reader chose, in memory and in storage.
        assertEquals(saved, c.settings.value)
        assertEquals(saved, ReaderSettings.decode(store.map[ReaderSettings.KEY]))
        reader.sent.clear()

        c.setPowerSave(false); advanceUntilIdle()
        assertFalse(c.batterySaving.value)
        assertEquals("warm", lastSettings().theme)
        assertEquals("slide", lastSettings().pageAnimation)
        assertEquals(saved, c.shownSettings.value)
        assertEquals(listOf(ToWeb.CountPages(sessionId = "s-1", gen = 2, run = true)), counts()) // counting resumes
        assertNull(c.shownPage.value.percent)
        assertEquals(2, c.shownPage.value.page)
    }

    @Test fun with_the_setting_off_nothing_changes() = runTest(dispatcher) {
        val c = controller()
        openOnScreenPages(c, ReaderSettings(pageNumbers = PageNumbers.SCREEN_BOOK, theme = ReadingThemeId.WARM, followBatterySaver = false))
        val shown = c.shownPage.value
        reader.sent.clear()
        c.setPowerSave(true); advanceUntilIdle()
        assertFalse(c.batterySaving.value)
        assertEquals(emptyList(), reader.sent)
        assertEquals(c.settings.value, c.shownSettings.value)
        assertEquals(shown, c.shownPage.value)
        c.setPowerSave(false); advanceUntilIdle()
        assertEquals(emptyList(), reader.sent)
    }

    @Test fun switching_the_setting_while_battery_saver_is_on_applies_live() = runTest(dispatcher) {
        val c = controller()
        c.setPowerSave(true)
        openOnScreenPages(c, ReaderSettings(pageNumbers = PageNumbers.SCREEN_BOOK, theme = ReadingThemeId.WARM, followBatterySaver = false))
        assertEquals("warm", lastSettings().theme)
        reader.sent.clear()
        c.updateSettings { it.copy(followBatterySaver = true) }; advanceUntilIdle()
        assertEquals("black", lastSettings().theme)
        assertEquals(ToWeb.CountPages(sessionId = "s-1", gen = 1, run = false), counts().last())
        assertEquals(42, c.shownPage.value.percent)
        c.updateSettings { it.copy(followBatterySaver = false) }; advanceUntilIdle()
        assertEquals("warm", lastSettings().theme)
        assertEquals(ToWeb.CountPages(sessionId = "s-1", gen = 1, run = true), counts().last())
    }

    @Test fun the_first_open_under_battery_saver_never_counts() = runTest(dispatcher) {
        val c = controller()
        c.setPowerSave(true)
        openOnScreenPages(c)
        assertEquals(emptyList(), counts())
        assertEquals("black", reader.sent.filterIsInstance<ToWeb.Open>().last().settings.theme)
        assertEquals("42%", Copy.pageLabelShort(c.shownPage.value))
    }

    @Test fun book_pages_stay_book_pages_and_the_slider_preview_shows_percent_for_screen_pages() = runTest(dispatcher) {
        val c = controller()
        openOnScreenPages(c, ReaderSettings()) // Book pages (the default)
        c.setPowerSave(true); advanceUntilIdle()
        assertNull(c.shownPage.value.percent) // the book's own pages need no counter
        c.updateSettings { it.copy(pageNumbers = PageNumbers.SCREEN_CHAPTER) }; advanceUntilIdle()
        assertEquals(42, c.shownPage.value.percent)
        c.previewSeek(0.75)
        assertEquals(75, c.seekPreview.value?.page?.percent)
    }

    /** C-110 under C-107: Battery Saver's Black is dimmed by Extra dim like any other theme. */
    @Test fun extra_dim_still_applies_under_battery_saver() = runTest(dispatcher) {
        val c = controller()
        openOnScreenPages(c, ReaderSettings(theme = ReadingThemeId.WARM, extraDim = true, extraDimLevel = 0.3))
        assertEquals(0.3, lastSettings().dim)
        c.setPowerSave(true); advanceUntilIdle()
        assertEquals("black", lastSettings().theme)
        assertEquals(0.3, lastSettings().dim)
        c.updateSettings { it.copy(extraDimLevel = 0.2) }; advanceUntilIdle()
        assertEquals("black", lastSettings().theme)
        assertEquals(0.2, lastSettings().dim)
    }

    @Test fun indicator_labels() {
        val p = percentIndicator(0.426)
        assertEquals("43%", Copy.pageLabelShort(p))
        assertEquals("43% through the book", Copy.pageLabel(p))
        val screen = PageIndicator(2, 10, true, false)
        val book = PageIndicator(1, 3, true, false, label = "i")
        assertEquals(book, shownIndicator(bookPages = true, saving = true, screen, book, 0.5))
        assertEquals(screen, shownIndicator(bookPages = false, saving = false, screen, book, 0.5))
        assertEquals(50, shownIndicator(bookPages = false, saving = true, screen, book, 0.5).percent)
        val none = PageIndicator(null, null, false, false)
        assertEquals(none, shownIndicator(bookPages = false, saving = true, none, book, 0.0)) // no position yet
    }
}
