package app.epubreader.reader

import app.epubreader.core.epub.TextPos
import app.epubreader.core.data.Annotation
import app.epubreader.core.data.AnnotationPort
import app.epubreader.core.data.Bookmark
import app.epubreader.core.epub.PositionMap
import app.epubreader.core.data.BookmarkPort
import app.epubreader.core.data.HybridClock
import app.epubreader.core.data.LibraryRepository
import app.epubreader.core.data.PositionWriter
import app.epubreader.core.data.Flow
import app.epubreader.core.data.ReaderSettings
import app.epubreader.core.data.ReadingThemeId
import app.epubreader.core.data.SwipeDirection
import app.epubreader.core.data.VolumeKeys
import app.epubreader.core.data.PageAnimation
import app.epubreader.core.data.SearchHistoryStore
import app.epubreader.core.data.SettingsPort
import app.epubreader.core.search.SearchHit
import app.epubreader.core.search.SearchQuery
import app.epubreader.core.search.SearchUpdate
import app.epubreader.core.data.SourceRef
import app.epubreader.core.data.buildDatabase
import app.epubreader.core.data.jvmDatabaseBuilder
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.reader.bridge.ToWeb
import app.epubreader.reader.bridge.TocItem
import app.epubreader.reader.bridge.WebTexts
import app.epubreader.reader.bridge.WebSettings
import app.epubreader.reader.bridge.WebAnnotation
import androidx.room.execSQL
import androidx.room.useWriterConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderControllerTest {
    private class FakeReader : ReaderPort {
        val sent = mutableListOf<ToWeb>()
        override val events = MutableSharedFlow<FromWeb>(extraBufferCapacity = 64)
        override val isReady = MutableStateFlow(true)
        override fun send(m: ToWeb) { sent += m }
    }
    /** With [suspendOpen], open() waits until [finishOpen] (an open still in flight when Back is pressed). */
    private class FakeOpener(
        var next: OpenResult = OpenResult.Ok("s-1", "d".repeat(64), 100),
        private val suspendOpen: Boolean = false,
    ) : BookOpenerPort {
        val closed = mutableListOf<String>()
        private val pending = CompletableDeferred<OpenResult>()
        fun finishOpen(r: OpenResult) { pending.complete(r) }
        override suspend fun open(ref: SourceRef) = if (suspendOpen) pending.await() else next
        override suspend fun close(sessionId: String) { closed += sessionId }
        var statsCalls = 0
        var stats: List<SectionStat>? = listOf(SectionStat(1000, 0), SectionStat(0, 1), SectionStat(500, 0))
        override suspend fun sectionStats(sessionId: String): List<SectionStat>? { statsCalls++; return stats }
    }

    // R15: Room runs its queries on the test scheduler, so advanceUntilIdle() also waits for the database.
    private val dispatcher = StandardTestDispatcher()
    private val reader = FakeReader()
    private val opener = FakeOpener()
    private val db = buildDatabase(jvmDatabaseBuilder(Files.createTempDirectory("db").resolve("t.db").toString()), dispatcher)
    private val clock = HybridClock(System::currentTimeMillis)
    private val library = LibraryRepository(db, clock)
    private val ref = SourceRef.File("/books/a.epub")
    private val toc = listOf(TocItem("One", "c1.xhtml", 0, index = 0), TocItem("Gone", "gone.xhtml", 0, missing = true))
    private fun opened(sid: String = "s-1") = FromWeb.Opened(sessionId = sid, identifier = "urn:x", title = "A", authors = listOf("B"), dir = "ltr", fixedLayout = false, sections = 3, toc = toc)
    // The controller's event collector runs forever, so it gets its own scope (cancelled after each test) instead of
    // the test's. Not backgroundScope: advanceUntilIdle() stops once only background tasks remain, so it would never
    // run the controller's work.
    // Any exception that escapes the controller's coroutines would crash the app (R-002): collect them and fail.
    private val crashes = mutableListOf<Throwable>()
    private val scope = CoroutineScope(dispatcher + SupervisorJob() + CoroutineExceptionHandler { _, e -> crashes += e })
    /** Stands in for the app-scoped IO scope that close-out runs on (survives the activity's lifecycleScope). */
    private val appScope = CoroutineScope(dispatcher + SupervisorJob() + CoroutineExceptionHandler { _, e -> crashes += e })
    @AfterTest fun cancelScope() { scope.cancel(); appScope.cancel(); assertEquals(emptyList(), crashes) }
    private class MemSettings : SettingsPort {
        val map = mutableMapOf<String, String>()
        var puts = 0
        override suspend fun get(key: String) = map[key]
        override suspend fun put(key: String, value: String) { map[key] = value; puts++ }
    }
    private val settings = MemSettings()
    private class FakeSearch : SearchPort {
        val ensured = mutableListOf<Triple<String, String, String>>()
        val queries = mutableListOf<SearchQuery>()
        val closed = mutableListOf<String>()
        override fun bookClosed(bookId: String) { closed += bookId }
        override fun ensureIndex(bookId: String, digest: String, sessionId: String) { ensured += Triple(bookId, digest, sessionId) }
        override fun search(bookId: String, digest: String, sessionId: String, query: SearchQuery): kotlinx.coroutines.flow.Flow<SearchUpdate> {
            queries += query
            return kotlinx.coroutines.flow.flowOf(SearchUpdate(listOf(SearchHit(2, 40, 8, "…Borodinó…", 1, 9, "Borodinó")), 1, false, true))
        }
    }
    private val searchPort = FakeSearch()
    private val searchHistory = SearchHistoryStore(db, clock)
    /** Records the URLs handed to the browser; [works] = false stands in for "no app can open this". */
    private class FakeLinks(var works: Boolean = true) : ExternalLinkOpener {
        val opened = mutableListOf<String>()
        override fun open(url: String): Boolean { opened += url; return works }
    }
    private val links = FakeLinks()
    /** In-memory bookmarks of all books; `bookmarks(id)` is live like the database's. */
    private class FakeBookmarks : BookmarkPort {
        val all = MutableStateFlow<List<Bookmark>>(emptyList())
        val asked = mutableListOf<String>()
        private var n = 0
        override fun bookmarks(bookId: String): kotlinx.coroutines.flow.Flow<List<Bookmark>> {
            asked += bookId
            return kotlinx.coroutines.flow.flow { all.collect { l -> emit(l.filter { it.bookId == bookId }.sortedWith(compareBy({ it.spineIndex }, { it.fraction }))) } }
        }
        override suspend fun add(bookId: String, cfi: String, spineIndex: Int, fraction: Double, chapter: String) =
            Bookmark("b${++n}", bookId, cfi, spineIndex, fraction, chapter, 1_000L + n, 1_000L + n).also { all.value = all.value + it }
        override suspend fun remove(id: String) { all.value = all.value.filter { it.id != id } }
    }
    private val bookmarkPort = FakeBookmarks()
    /** In-memory annotations (F-060); records adds and updates. */
    private class FakeAnnotations : AnnotationPort {
        val all = MutableStateFlow<List<Annotation>>(emptyList())
        val updates = mutableListOf<Triple<String, String?, String?>>()
        /** Note arguments of update() calls (id to note; "" clears). */
        val notes = mutableListOf<Pair<String, String>>()
        val removed = mutableListOf<String>()
        private var n = 0
        override fun annotations(bookId: String) = kotlinx.coroutines.flow.flow { all.collect { l -> emit(l.filter { it.bookId == bookId }) } }
        override suspend fun add(bookId: String, cfi: String, spineIndex: Int, text: String, chapter: String, color: String, style: String) =
            Annotation("a${++n}", bookId, cfi, spineIndex, text, chapter, color, style, null, 1L, 1L).also { all.value = all.value + it }
        override suspend fun update(id: String, color: String?, style: String?, note: String?) {
            if (color != null || style != null) updates += Triple(id, color, style)
            if (note != null) notes += id to note
            all.value = all.value.map {
                if (it.id == id) it.copy(color = color ?: it.color, style = style ?: it.style, note = if (note != null) note.takeIf { n -> n.isNotBlank() } else it.note) else it
            }
        }
        override suspend fun remove(id: String) { removed += id; all.value = all.value.filter { it.id != id } }
    }
    private val annotationPort = FakeAnnotations()
    private class FakeTextActions : TextActions {
        val copied = mutableListOf<String>()
        val shared = mutableListOf<String>()
        override fun copy(text: String) { copied += text }
        override fun share(text: String) { shared += text }
    }
    private val textActions = FakeTextActions()
    /** Virtual CFIs carry a "v:" marker here, so a test sees which side of the map each value is on (M8 chunking). */
    private val markedMap = object : PositionMap {
        override fun cfiToOriginal(virtualCfi: String) = virtualCfi.removePrefix("v:")
        override fun cfiToVirtual(originalCfi: String) = "v:$originalCfi"
        override fun textToVirtual(p: TextPos) = TextPos(p.spineIndex + 10, p.offset)
        override fun textToOriginal(p: TextPos) = TextPos(p.spineIndex - 10, p.offset)
    }
    private suspend fun controller(controllerScope: CoroutineScope = scope, opener: FakeOpener = this.opener, map: PositionMap = PositionMap.Identity) = ReaderController(
        reader, opener, library, CompletableDeferred(PositionWriter.create(db, clock, appScope)), controllerScope, WebTexts("missing"),
        closeScope = appScope, settingsStore = settings, search = searchPort, searchHistory = searchHistory, links = links,
        bookmarks = bookmarkPort, annotations = annotationPort, textActions = textActions, positionMap = { map },
    )
    /** Every later library query fails (stands in for a corrupt database or a full disk). */
    private suspend fun breakDatabase() = db.useWriterConnection { it.execSQL("DROP TABLE book_file"); it.execSQL("DROP TABLE book") }
    private fun relocate(cfi: String, sid: String = "s-1") = FromWeb.Relocate(sessionId = sid, cfi = cfi, fraction = 0.3, sectionIndex = 2)

    @Test fun open_sends_open_and_records_book_on_opened() = runTest(dispatcher) {
        val c = controller()
        c.open(ref); advanceUntilIdle()
        assertEquals(ToWeb.Open(sessionId = "s-1", position = null, texts = WebTexts("missing")), reader.sent.single())
        assertTrue(c.state.value.loading)
        reader.events.emit(opened()); advanceUntilIdle()
        assertTrue(library.knownBookId(ref) != null)
        assertEquals(toc, c.state.value.toc)
        reader.events.emit(FromWeb.Painted(sessionId = "s-1")); advanceUntilIdle()
        assertFalse(c.state.value.loading)
    }

    @Test fun stored_settings_go_with_the_first_open() = runTest(dispatcher) {
        settings.map[ReaderSettings.KEY] = ReaderSettings(fontScale = 1.6, theme = ReadingThemeId.BLACK, flow = Flow.AUTO).encode()
        val c = controller()
        c.open(ref); advanceUntilIdle()
        assertEquals(WebSettings(fontScale = 1.6, theme = "black", flow = "paginated"), (reader.sent.single() as ToWeb.Open).settings)
        assertEquals(1.6, c.settings.value.fontScale)
    }

    @Test fun settings_change_live_and_are_stored_once_after_a_drag() = runTest(dispatcher) {
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.sent.clear()
        repeat(5) { c.updateSettings { it.copy(lineHeight = it.lineHeight + 0.1) } }
        assertEquals(5, reader.sent.filterIsInstance<ToWeb.SetSettings>().size) // every step previews at once (F-075)
        assertEquals(2.0, reader.sent.filterIsInstance<ToWeb.SetSettings>().last().settings.lineHeight)
        advanceUntilIdle()
        assertEquals(1, settings.puts)
        assertEquals(2.0, ReaderSettings.decode(settings.map[ReaderSettings.KEY]).lineHeight)
        c.updateSettings { it.copy(lineHeight = 9.0) } // clamped to 2.0: no change, nothing sent
        assertEquals(5, reader.sent.filterIsInstance<ToWeb.SetSettings>().size)
    }

    @Test fun auto_flow_follows_the_orientation() = runTest(dispatcher) {
        val c = controller()
        c.updateSettings { it.copy(flow = Flow.AUTO) }; reader.sent.clear()
        c.setLandscape(true)
        assertEquals("scrolled", (reader.sent.single() as ToWeb.SetSettings).settings.flow)
        c.updateSettings { it.copy(flow = Flow.PAGED) }; reader.sent.clear()
        c.setLandscape(false); c.setLandscape(true)
        assertTrue(reader.sent.isEmpty()) // paged stays paged in landscape
    }

    @Test fun section_stats_load_after_the_first_paint_and_are_cached_per_file() = runTest(dispatcher) {
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        assertEquals(0, opener.statsCalls) // never before the first page is on screen
        reader.events.emit(FromWeb.Painted(sessionId = "s-1")); advanceUntilIdle()
        assertEquals(1, opener.statsCalls)
        assertEquals(opener.stats, c.sectionStats)
        c.close(); advanceUntilIdle()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        reader.events.emit(FromWeb.Painted(sessionId = "s-1")); advanceUntilIdle()
        assertEquals(1, opener.statsCalls) // from book_cache (same central-directory digest)
        assertEquals(opener.stats, c.sectionStats)
    }

    @Test fun page_indicator_follows_counts_and_stats() = runTest(dispatcher) {
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        reader.events.emit(FromWeb.Relocate(sessionId = "s-1", cfi = "x", fraction = 0.5, sectionIndex = 2, page = 2, pages = 4)); advanceUntilIdle()
        reader.events.emit(FromWeb.Painted(sessionId = "s-1")); advanceUntilIdle() // stats load
        reader.events.emit(FromWeb.PageCounts(sessionId = "s-1", gen = 1, linear = listOf(0, 1, 2))); advanceUntilIdle()
        reader.events.emit(FromWeb.PageCounts(sessionId = "old", gen = 1, counts = listOf(listOf(0, 99)), done = true)); advanceUntilIdle()
        reader.events.emit(FromWeb.PageCounts(sessionId = "s-1", gen = 1, counts = listOf(listOf(0, 3), listOf(1, 1), listOf(2, 4)), sample = true, done = true)); advanceUntilIdle()
        assertEquals(PageIndicator(page = 3 + 1 + 2, total = 8, exact = true, chapterScope = false), c.page.value)
        assertEquals(0.5, c.state.value.fraction)
        c.seek(0.25)
        assertEquals(ToWeb.GoToFraction(fraction = 0.25), reader.sent.last())
    }

    @Test fun reopen_sends_saved_position() = runTest(dispatcher) {
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        reader.events.emit(FromWeb.Relocate(sessionId = "s-1", cfi = "epubcfi(/6/6!/4/2)", fraction = 0.3, sectionIndex = 2)); advanceUntilIdle()
        c.close(); advanceUntilIdle()
        reader.sent.clear()
        c.open(ref); advanceUntilIdle()
        assertEquals("epubcfi(/6/6!/4/2)", (reader.sent.single() as ToWeb.Open).position)
    }

    @Test fun events_from_previous_session_are_ignored() = runTest(dispatcher) {
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        val bookId = library.knownBookId(ref)!!
        reader.events.emit(FromWeb.Relocate(sessionId = "old", cfi = "epubcfi(/6/99!)", fraction = 0.9, sectionIndex = 9)); advanceUntilIdle()
        c.close(); advanceUntilIdle()
        assertNull(library.position(bookId))
    }

    @Test fun close_returns_without_waiting_for_engine() = runTest(dispatcher) {
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        c.close() // no advance: state must already be closed and `close` already sent
        assertFalse(c.state.value.open)
        assertEquals(ToWeb.Close(), reader.sent.last())
        advanceUntilIdle()
        assertEquals(listOf("s-1"), opener.closed)
    }

    @Test fun missing_toc_target_shows_notice_and_stays() = runTest(dispatcher) {
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        reader.sent.clear()
        c.onTocSelected(toc[1])
        assertEquals(Notice.MissingTarget, c.state.value.notice)
        assertTrue(reader.sent.isEmpty())
        c.onTocSelected(toc[0])
        assertEquals(ToWeb.GoTo(target = "c1.xhtml"), reader.sent.single())
    }

    @Test fun go_to_text_maps_through_the_position_map_and_sends_the_hit() = runTest(dispatcher) {
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        reader.sent.clear()
        c.goToText(TextPos(2, 40), 8, "Borodinó")
        assertEquals(ToWeb.GoToText(sectionIndex = 2, offset = 40, length = 8, needle = "Borodinó"), reader.sent.single())
    }

    @Test fun hit_not_found_is_a_notice_not_an_error_screen() = runTest(dispatcher) {
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        reader.events.emit(FromWeb.Error(sessionId = "s-1", kind = "hit_not_found", message = "x")); advanceUntilIdle()
        assertNull(c.state.value.error)
        assertEquals(Notice.HitNotFound, c.state.value.notice)
        reader.events.emit(FromWeb.Error(sessionId = "s-1", kind = "open_failed", message = "x")); advanceUntilIdle()
        assertNotNull(c.state.value.error)
    }

    @Test fun center_tap_toggles_chrome_side_taps_do_not() = runTest(dispatcher) {
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        reader.events.emit(FromWeb.Tap(sessionId = "s-1", x = 0.1, y = 0.5)); advanceUntilIdle()
        assertFalse(c.state.value.chromeVisible)
        reader.events.emit(FromWeb.Tap(sessionId = "s-1", x = 0.5, y = 0.5)); advanceUntilIdle()
        assertTrue(c.state.value.chromeVisible)
    }

    // F-032: tap zones (outer 25 %) mirror like swipes; Kotlin decides from the tap's x.
    private suspend fun kotlinx.coroutines.test.TestScope.openWith(dir: String = "ltr", change: (ReaderSettings) -> ReaderSettings = { it }): ReaderController {
        val c = controller()
        c.updateSettings(change)
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened().copy(dir = dir)); advanceUntilIdle()
        reader.sent.clear()
        return c
    }
    private suspend fun kotlinx.coroutines.test.TestScope.tap(x: Double) { reader.events.emit(FromWeb.Tap(sessionId = "s-1", x = x, y = 0.5)); advanceUntilIdle() }
    private fun turns() = reader.sent.filter { it is ToWeb.Next || it is ToWeb.Prev }.map { if (it is ToWeb.Next) "next" else "prev" }

    @Test fun tap_zones_off_edges_do_nothing() = runTest(dispatcher) {
        val c = openWith()
        tap(0.05); tap(0.95)
        assertEquals(emptyList(), turns())
        assertFalse(c.state.value.chromeVisible)
    }

    @Test fun tap_zones_on_ltr_right_edge_next_left_edge_previous_centre_controls() = runTest(dispatcher) {
        val c = openWith { it.copy(tapZones = true) }
        tap(0.9); tap(0.1); tap(0.3)
        assertEquals(listOf("next", "prev"), turns())
        assertFalse(c.state.value.chromeVisible)
        tap(0.5)
        assertTrue(c.state.value.chromeVisible)
    }

    @Test fun tap_zones_on_rtl_book_mirror() = runTest(dispatcher) {
        openWith("rtl") { it.copy(tapZones = true) }
        tap(0.1); tap(0.9)
        assertEquals(listOf("next", "prev"), turns())
    }

    @Test fun tap_zones_follow_a_forced_direction() = runTest(dispatcher) {
        val c = openWith("rtl") { it.copy(tapZones = true, swipeDirection = SwipeDirection.LTR) }
        tap(0.9)
        c.updateSettings { it.copy(swipeDirection = SwipeDirection.RTL) }
        tap(0.9)
        assertEquals(listOf("next", "prev"), turns())
    }

    @Test fun tap_zone_tap_with_a_sheet_or_the_controls_open_closes_them_instead() = runTest(dispatcher) {
        val c = openWith { it.copy(tapZones = true) }
        c.showSettings(); tap(0.9)
        assertFalse(c.state.value.settingsVisible)
        c.toggleChrome(); tap(0.9)
        assertFalse(c.state.value.chromeVisible)
        assertEquals(emptyList(), turns())
    }

    // C-005: ON = volume-down next, volume-up previous; REVERSED swaps; OFF and open panels leave the keys alone.
    @Test fun volume_keys_off_are_not_handled() = runTest(dispatcher) {
        val c = openWith()
        assertFalse(c.onVolumeKey(volumeDown = true, press = true))
        assertEquals(emptyList(), turns())
    }

    @Test fun volume_keys_on_and_reversed() = runTest(dispatcher) {
        val c = openWith { it.copy(volumeKeys = VolumeKeys.ON) }
        assertTrue(c.onVolumeKey(volumeDown = true, press = true))
        assertTrue(c.onVolumeKey(volumeDown = false, press = true))
        c.updateSettings { it.copy(volumeKeys = VolumeKeys.REVERSED) }
        assertTrue(c.onVolumeKey(volumeDown = true, press = true))
        assertEquals(listOf("next", "prev", "prev"), turns())
    }

    @Test fun volume_key_release_and_repeats_are_consumed_without_a_turn() = runTest(dispatcher) {
        val c = openWith { it.copy(volumeKeys = VolumeKeys.ON) }
        assertTrue(c.onVolumeKey(volumeDown = true, press = false))
        assertEquals(emptyList(), turns())
    }

    @Test fun volume_keys_with_a_panel_open_or_no_book_are_not_handled() = runTest(dispatcher) {
        val c = openWith { it.copy(volumeKeys = VolumeKeys.ON) }
        for (show in listOf(c::showToc, c::showSearch, c::showDisplay, c::showSettings)) {
            show()
            assertFalse(c.onVolumeKey(volumeDown = true, press = true))
            c.backInside()
        }
        assertEquals(emptyList(), turns())
        c.close()
        assertFalse(c.onVolumeKey(volumeDown = true, press = true))
    }

    @Test fun pure_mappings() {
        assertEquals(TapAction.Next, tapAction(0.8, tapZones = true, rtl = false))
        assertEquals(TapAction.Prev, tapAction(0.8, tapZones = true, rtl = true))
        assertEquals(TapAction.None, tapAction(0.8, tapZones = false, rtl = false))
        assertEquals(TapAction.Controls, tapAction(0.4, tapZones = true, rtl = false))
        assertEquals(TapAction.None, tapAction(0.28, tapZones = true, rtl = false))
        assertEquals(PageTurn.Next, volumeTurn(volumeDown = true, VolumeKeys.ON))
        assertEquals(PageTurn.Prev, volumeTurn(volumeDown = true, VolumeKeys.REVERSED))
        assertNull(volumeTurn(volumeDown = true, VolumeKeys.OFF))
    }

    @Test fun web_settings_carry_swipe_direction_and_animation() = runTest(dispatcher) {
        val c = openWith()
        c.updateSettings { it.copy(swipeDirection = SwipeDirection.RTL, pageAnimation = PageAnimation.NONE) }
        val w = (reader.sent.last() as ToWeb.SetSettings).settings
        assertEquals("rtl", w.swipeDirection); assertEquals("none", w.pageAnimation)
        reader.sent.clear()
        c.updateSettings { it.copy(tapZones = true, volumeKeys = VolumeKeys.ON) } // Kotlin-only settings: nothing to send
        assertEquals(emptyList(), reader.sent)
    }

    @Test fun open_failure_is_shown() = runTest(dispatcher) {
        opener.next = OpenResult.Failed(OpenFailure.NOT_A_ZIP)
        val c = controller()
        c.open(ref); advanceUntilIdle()
        assertEquals(ReaderError.Open(OpenFailure.NOT_A_ZIP), c.state.value.error)
        assertTrue(reader.sent.isEmpty())
    }

    // C1 + I1 (F-037, R-009): the same book reached through another source resumes at its saved position, and the
    // book-start relocates before that jump has landed never replace the saved position.
    @Test fun same_book_via_new_source_jumps_to_saved_position_and_keeps_it_until_landed() = runTest(dispatcher) {
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        reader.events.emit(relocate("epubcfi(/6/6!/4/2)")); advanceUntilIdle()
        c.close(); advanceUntilIdle()
        val bookId = library.knownBookId(ref)!!

        opener.next = OpenResult.Ok("s-2", "d".repeat(64), 100)
        val other = SourceRef.Content("content://provider/doc/42")
        reader.sent.clear()
        c.open(other); advanceUntilIdle()
        assertEquals(null, (reader.sent.single() as ToWeb.Open).position) // not known under this source yet
        reader.events.emit(opened("s-2")); advanceUntilIdle()
        val goTo = reader.sent.filterIsInstance<ToWeb.GoTo>().last()
        assertEquals("epubcfi(/6/6!/4/2)", goTo.target)
        val id = assertNotNull(goTo.id)

        reader.events.emit(relocate("epubcfi(/6/2!/4/1)", "s-2")); advanceUntilIdle() // book start, before the jump
        assertEquals("epubcfi(/6/6!/4/2)", library.position(bookId)!!.cfi)
        reader.events.emit(FromWeb.Seeked(sessionId = "s-2", id = id + 1)); advanceUntilIdle() // not ours
        reader.events.emit(relocate("epubcfi(/6/2!/4/9)", "s-2")); advanceUntilIdle()
        assertEquals("epubcfi(/6/6!/4/2)", library.position(bookId)!!.cfi)

        reader.events.emit(relocate("epubcfi(/6/6!/4/2)", "s-2")); advanceUntilIdle() // landed
        reader.events.emit(FromWeb.Seeked(sessionId = "s-2", id = id)); advanceUntilIdle()
        reader.events.emit(relocate("epubcfi(/6/6!/4/8)", "s-2")); advanceUntilIdle() // the reader turns a page
        assertEquals("epubcfi(/6/6!/4/8)", library.position(bookId)!!.cfi)
    }

    @Test fun close_before_the_resume_jump_landed_keeps_the_saved_position() = runTest(dispatcher) {
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        reader.events.emit(relocate("epubcfi(/6/6!/4/2)")); advanceUntilIdle()
        c.close(); advanceUntilIdle()
        val bookId = library.knownBookId(ref)!!
        opener.next = OpenResult.Ok("s-2", "d".repeat(64), 100)
        c.open(SourceRef.Content("content://provider/doc/42")); advanceUntilIdle()
        reader.events.emit(opened("s-2")); advanceUntilIdle()
        reader.events.emit(relocate("epubcfi(/6/2!/4/1)", "s-2")); advanceUntilIdle()
        c.close(); advanceUntilIdle() // the close-out flush must not write the book start either
        assertEquals("epubcfi(/6/6!/4/2)", library.position(bookId)!!.cfi)
    }

    // I3 (R-002): database failures never crash the app.
    @Test fun database_failure_while_recording_the_book_is_shown_and_events_keep_flowing() = runTest(dispatcher) {
        val c = controller()
        c.open(ref); advanceUntilIdle()
        breakDatabase()
        reader.events.emit(opened()); advanceUntilIdle()
        assertTrue(c.state.value.error is ReaderError.Engine)
        reader.events.emit(FromWeb.Tap(sessionId = "s-1", x = 0.5, y = 0.5)); advanceUntilIdle()
        assertTrue(c.state.value.chromeVisible) // the collector is still running
    }

    @Test fun database_failure_while_opening_is_shown() = runTest(dispatcher) {
        val c = controller()
        breakDatabase()
        c.open(ref); advanceUntilIdle()
        assertTrue(c.state.value.error is ReaderError.Engine)
        assertFalse(c.state.value.loading)
    }

    @Test fun failure_storing_a_position_does_not_stop_the_reader() = runTest(dispatcher) {
        val broken = CompletableDeferred<PositionWriter>().apply { completeExceptionally(IllegalStateException("no writer")) }
        val c = ReaderController(reader, opener, library, broken, scope, WebTexts("missing"), closeScope = appScope)
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        reader.events.emit(relocate("epubcfi(/6/6!/4/2)")); advanceUntilIdle()
        reader.events.emit(FromWeb.Painted(sessionId = "s-1")); advanceUntilIdle()
        assertFalse(c.state.value.loading)
        c.close(); advanceUntilIdle() // writeNow fails too
        assertEquals(listOf("s-1"), opener.closed) // the session is still released
    }

    // Minor 1: the close-out (last position, session release) runs even when the controller's scope is gone.
    @Test fun close_out_runs_on_the_app_scope_after_the_activity_scope_is_cancelled() = runTest(dispatcher) {
        val activityScope = CoroutineScope(dispatcher + SupervisorJob())
        val c = controller(activityScope)
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        reader.events.emit(relocate("epubcfi(/6/6!/4/4)")); advanceUntilIdle()
        activityScope.cancel() // onDestroy: lifecycleScope is already cancelled
        c.close(); advanceUntilIdle()
        assertEquals(listOf("s-1"), opener.closed)
        assertEquals("epubcfi(/6/6!/4/4)", library.position(library.knownBookId(ref)!!)!!.cfi)
    }

    // Back while the opener is still working: the session it then produces is released at once, not left registered
    // until the next open retires it.
    @Test fun back_while_opening_closes_the_session_that_was_being_opened() = runTest(dispatcher) {
        val opener = FakeOpener(suspendOpen = true)
        val c = controller(opener = opener)
        c.open(SourceRef.File("/b.epub")); runCurrent()
        c.close()                                   // Back before the opener returned
        opener.finishOpen(OpenResult.Ok("s1", "cd", 10)); advanceUntilIdle()
        assertEquals(listOf("s1"), opener.closed)
        assertTrue(reader.sent.none { it is ToWeb.Open })
        assertFalse(c.state.value.open)
    }

    @Test fun search_for_opens_the_panel_runs_the_query_and_records_it() = runTest(dispatcher) {
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        val bookId = library.knownBookId(ref)!!
        c.searchFor("Borodinó"); advanceUntilIdle()
        assertTrue(c.state.value.searchVisible)
        assertFalse(c.state.value.chromeVisible)
        assertEquals(listOf(Triple(bookId, "d".repeat(64), "s-1")), searchPort.ensured)
        assertEquals(listOf(SearchQuery("Borodinó")), searchPort.queries)
        assertEquals("Borodinó", c.searcher.state.value.query)
        assertEquals(listOf("One"), c.searcher.state.value.results.map { it.chapter })
        assertEquals(listOf("Borodinó"), searchHistory.recent(bookId))
    }

    @Test fun the_search_index_starts_after_the_first_paint_and_the_stats_not_before() = runTest(dispatcher) {
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        assertEquals(emptyList(), searchPort.ensured) // opened, not yet painted
        reader.events.emit(FromWeb.Painted(sessionId = "s-1")); advanceUntilIdle()
        val bookId = library.knownBookId(ref)!!
        assertEquals(1, opener.statsCalls)
        assertEquals(listOf(Triple(bookId, "d".repeat(64), "s-1")), searchPort.ensured)
        reader.events.emit(FromWeb.Painted(sessionId = "s-1")); advanceUntilIdle() // later paints do not restart it
        assertEquals(1, searchPort.ensured.size)
    }

    @Test fun the_search_index_still_starts_when_the_stats_fail() = runTest(dispatcher) {
        opener.stats = null
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        reader.events.emit(FromWeb.Painted(sessionId = "s-1")); advanceUntilIdle()
        assertEquals(1, searchPort.ensured.size)
    }

    @Test fun closing_a_book_tells_the_search_port() = runTest(dispatcher) {
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        val bookId = library.knownBookId(ref)!!
        c.close(); advanceUntilIdle()
        assertEquals(listOf(bookId), searchPort.closed)
    }

    @Test fun selecting_a_result_sends_the_jump_hides_the_panel_and_keeps_the_results() = runTest(dispatcher) {
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        c.showSearch(); c.searcher.submit("Borodinó"); advanceUntilIdle()
        reader.sent.clear()
        c.searcher.select(c.searcher.state.value.results.single())
        assertEquals(ToWeb.GoToText(sectionIndex = 2, offset = 40, length = 8, needle = "Borodinó"), reader.sent.single())
        assertFalse(c.state.value.searchVisible)
        c.showSearch()
        assertEquals(1, c.searcher.state.value.results.size)
    }

    @Test fun back_closes_the_search_panel_first() = runTest(dispatcher) {
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        c.showSearch()
        assertTrue(c.backInside())
        assertFalse(c.state.value.searchVisible)
        assertFalse(c.backInside())
    }

    @Test fun search_state_resets_when_another_book_opens_and_history_is_per_book() = runTest(dispatcher) {
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        c.showSearch(); c.searcher.submit("Borodinó"); advanceUntilIdle()
        c.close(); advanceUntilIdle()
        assertEquals(app.epubreader.reader.search.SearchState(), c.searcher.state.value)
        opener.next = OpenResult.Ok("s-2", "e".repeat(64), 100)
        c.open(SourceRef.File("/books/b.epub")); advanceUntilIdle()
        reader.events.emit(FromWeb.Opened(sessionId = "s-2", identifier = "urn:y", title = "Y", authors = emptyList(), dir = "ltr", fixedLayout = false, sections = 1, toc = emptyList())); advanceUntilIdle()
        c.showSearch(); advanceUntilIdle()
        assertEquals(emptyList<String>(), c.searcher.state.value.history)
        c.searcher.clearHistory(); advanceUntilIdle()
        assertEquals(1, searchHistory.recent(library.knownBookId(ref)!!).size) // the other book's history is untouched
    }

    @Test fun settings_sheet_opens_closes_and_back_closes_it_first() = runTest(dispatcher) {
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        reader.events.emit(FromWeb.Tap(sessionId = "s-1", x = 0.5, y = 0.5)); advanceUntilIdle()
        assertTrue(c.state.value.chromeVisible)
        c.showSettings()
        assertTrue(c.state.value.settingsVisible)
        assertFalse(c.state.value.chromeVisible)
        assertTrue(c.backInside())
        assertFalse(c.state.value.settingsVisible)
        assertFalse(c.backInside())
    }

    @Test fun a_tap_on_the_page_closes_the_settings_sheet() = runTest(dispatcher) {
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        c.showSettings()
        reader.events.emit(FromWeb.Tap(sessionId = "s-1", x = 0.5, y = 0.5)); advanceUntilIdle()
        assertFalse(c.state.value.settingsVisible)
        assertFalse(c.state.value.chromeVisible) // the tap only closed the sheet
    }

    @Test fun settings_sheet_edits_the_new_fields_live_and_persist() = runTest(dispatcher) {
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        reader.sent.clear()
        c.updateSettings { it.copy(tapZones = true, volumeKeys = app.epubreader.core.data.VolumeKeys.REVERSED) }
        assertTrue(c.settings.value.tapZones)
        assertEquals(app.epubreader.core.data.VolumeKeys.REVERSED, c.settings.value.volumeKeys)
        assertTrue(reader.sent.isEmpty()) // nothing for the web side changed
    }

    // F-042..F-044: links, Back history, footnote popup, external links

    private suspend fun kotlinx.coroutines.test.TestScope.openBook(): ReaderController {
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        reader.sent.clear()
        return c
    }

    @Test fun back_order_is_dialog_then_footnote_then_panels_then_link_history_then_leave() = runTest(dispatcher) {
        val c = openBook()
        reader.events.emit(FromWeb.History(sessionId = "s-1", depth = 1))
        reader.events.emit(FromWeb.Footnote(sessionId = "s-1", open = true))
        reader.events.emit(FromWeb.ExternalLink(sessionId = "s-1", href = "https://www.gutenberg.org/")); advanceUntilIdle()
        c.showToc()
        assertTrue(c.backInside()); assertNull(c.state.value.externalLink) // the confirmation first
        assertTrue(c.state.value.footnoteOpen)
        assertTrue(c.backInside()); assertFalse(c.state.value.footnoteOpen)
        assertEquals(listOf<ToWeb>(ToWeb.CloseFootnote()), reader.sent)
        assertTrue(c.state.value.tocVisible)
        assertTrue(c.backInside()); assertFalse(c.state.value.tocVisible)
        reader.sent.clear()
        assertTrue(c.backInside()) // the link history
        assertEquals(listOf<ToWeb>(ToWeb.Back()), reader.sent)
        assertFalse(c.backInside()) // nothing left: leave the book
        assertEquals(listOf<ToWeb>(ToWeb.Back()), reader.sent)
        assertTrue(links.opened.isEmpty())
    }

    /** R-005: Back decides at once from the depth it was last told; repeated Backs do not wait for the web side. */
    @Test fun repeated_back_walks_the_history_without_waiting_then_leaves() = runTest(dispatcher) {
        val c = openBook()
        reader.events.emit(FromWeb.History(sessionId = "s-1", depth = 1))
        reader.events.emit(FromWeb.History(sessionId = "s-1", depth = 2)); advanceUntilIdle()
        assertTrue(c.backInside()); assertTrue(c.backInside()); assertFalse(c.backInside())
        assertEquals(listOf<ToWeb>(ToWeb.Back(), ToWeb.Back()), reader.sent)
        reader.events.emit(FromWeb.History(sessionId = "s-1", depth = 1)); reader.events.emit(FromWeb.History(sessionId = "s-1", depth = 0)); advanceUntilIdle()
        assertFalse(c.backInside()) // the late confirmations do not bring entries back
    }

    @Test fun link_history_is_per_book_and_other_sessions_are_ignored() = runTest(dispatcher) {
        val c = openBook()
        reader.events.emit(FromWeb.History(sessionId = "s-old", depth = 3)); advanceUntilIdle()
        assertFalse(c.backInside())
        reader.events.emit(FromWeb.History(sessionId = "s-1", depth = 1)); advanceUntilIdle()
        c.close(); advanceUntilIdle()
        opener.next = OpenResult.Ok("s-2", "e".repeat(64), 100)
        c.open(SourceRef.File("/books/b.epub")); advanceUntilIdle()
        assertFalse(c.backInside())
    }

    @Test fun a_footnote_popup_hides_the_controls_and_its_close_on_the_web_side_is_followed() = runTest(dispatcher) {
        val c = openBook()
        c.toggleChrome()
        reader.events.emit(FromWeb.Footnote(sessionId = "s-1", open = true)); advanceUntilIdle()
        assertTrue(c.state.value.footnoteOpen); assertFalse(c.state.value.chromeVisible)
        assertFalse(c.onVolumeKey(volumeDown = true, press = true)) // no page turns under the popup
        reader.events.emit(FromWeb.Footnote(sessionId = "s-1", open = false)); advanceUntilIdle() // tapped outside
        assertFalse(c.state.value.footnoteOpen)
        assertFalse(c.backInside())
        assertTrue(reader.sent.isEmpty())
    }

    @Test fun an_external_link_asks_first_naming_the_host_then_opens_the_browser() = runTest(dispatcher) {
        val c = openBook()
        reader.events.emit(FromWeb.ExternalLink(sessionId = "s-1", href = "https://www.gutenberg.org/ebooks/1")); advanceUntilIdle()
        assertEquals(ExternalLink("https://www.gutenberg.org/ebooks/1", "www.gutenberg.org", mail = false), c.state.value.externalLink)
        assertTrue(links.opened.isEmpty())
        c.confirmExternalLink()
        assertEquals(listOf("https://www.gutenberg.org/ebooks/1"), links.opened)
        assertNull(c.state.value.externalLink)
        reader.events.emit(FromWeb.ExternalLink(sessionId = "s-1", href = "mailto:someone@example.com?subject=Hi")); advanceUntilIdle()
        assertEquals(ExternalLink("mailto:someone@example.com?subject=Hi", "someone@example.com", mail = true), c.state.value.externalLink)
        c.cancelExternalLink()
        assertNull(c.state.value.externalLink)
        assertEquals(1, links.opened.size)
    }

    @Test fun with_confirmation_off_an_external_link_opens_directly() = runTest(dispatcher) {
        val c = openBook()
        c.updateSettings { it.copy(confirmExternalLinks = false) }
        reader.events.emit(FromWeb.ExternalLink(sessionId = "s-1", href = "http://example.com/")); advanceUntilIdle()
        assertNull(c.state.value.externalLink)
        assertEquals(listOf("http://example.com/"), links.opened)
    }

    @Test fun unsafe_schemes_are_ignored() = runTest(dispatcher) {
        val c = openBook()
        c.updateSettings { it.copy(confirmExternalLinks = false) }
        for (h in listOf("javascript:alert(1)", "file:///sdcard/a", "content://x/y", "intent://x#Intent;end", "https://", "mailto:"))
            reader.events.emit(FromWeb.ExternalLink(sessionId = "s-1", href = h))
        advanceUntilIdle()
        assertTrue(links.opened.isEmpty())
        assertNull(c.state.value.externalLink)
    }

    @Test fun no_app_for_a_link_shows_a_short_notice() = runTest(dispatcher) {
        val c = openBook()
        links.works = false
        reader.events.emit(FromWeb.ExternalLink(sessionId = "s-1", href = "https://example.com/")); advanceUntilIdle()
        c.confirmExternalLink()
        assertEquals(Notice.NoAppForLink, c.state.value.notice)
    }

    @Test fun external_link_targets_are_classified() {
        assertEquals(ExternalLink("https://a.example.org:8080/x", "a.example.org", false), externalLinkTarget("https://a.example.org:8080/x"))
        assertEquals("example.com", externalLinkTarget("HTTP://user:pw@Example.com/")?.label)
        assertEquals("a@b.c", externalLinkTarget("mailto:a@b.c")?.label)
        for (h in listOf("javascript:x", "JAVASCRIPT:x", "file:///a", "content://a", "intent:#Intent;end", "data:,x", "ftp://a/", "https:///x", "nothing", ""))
            assertNull(externalLinkTarget(h), h)
    }

    // ---- Bookmarks (F-041) ----
    private val rangeCfi = "epubcfi(/6/4!/4,/2/1:0,/6/1:5)"
    private val pointCfi = "epubcfi(/6/4!/4/2/1:0)"
    private suspend fun kotlinx.coroutines.test.TestScope.openedAt(c: ReaderController, cfi: String = rangeCfi, marks: List<String>? = null) {
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        reader.events.emit(FromWeb.Relocate(sessionId = "s-1", cfi = cfi, fraction = 0.3, sectionIndex = 2, tocHref = "c1.xhtml", bookmarks = marks)); advanceUntilIdle()
    }

    @Test fun bookmark_button_adds_the_page_start_with_chapter_and_a_visible_confirmation() = runTest(dispatcher) {
        val c = controller(); openedAt(c)
        assertFalse(c.state.value.bookmarked)
        c.toggleBookmark(); advanceUntilIdle()
        val b = bookmarkPort.all.value.single()
        assertEquals(rangeCfi, b.cfi) // the page's location (a range from its first visible position), against the original documents
        assertEquals(2, b.spineIndex); assertEquals(0.3, b.fraction); assertEquals("One", b.chapter)
        assertEquals(library.knownBookId(ref), b.bookId)
        assertEquals(Notice.BookmarkAdded, c.state.value.notice)
        assertTrue(c.state.value.bookmarked) // the marker shows at once, before the web side has answered
        assertEquals(listOf(b), c.state.value.bookmarks)
        assertEquals(ToWeb.SetBookmarks(sessionId = "s-1", cfis = listOf(rangeCfi)), reader.sent.filterIsInstance<ToWeb.SetBookmarks>().last())
    }

    @Test fun bookmark_button_on_a_bookmarked_page_removes_it_with_a_confirmation() = runTest(dispatcher) {
        val c = controller(); openedAt(c)
        c.toggleBookmark(); advanceUntilIdle(); c.dismissNotice()
        reader.events.emit(FromWeb.PageBookmarks(sessionId = "s-1", cfis = listOf(rangeCfi))); advanceUntilIdle()
        c.toggleBookmark(); advanceUntilIdle()
        assertEquals(emptyList(), bookmarkPort.all.value)
        assertEquals(Notice.BookmarkRemoved, c.state.value.notice)
        assertFalse(c.state.value.bookmarked)
        assertEquals(emptyList(), c.state.value.bookmarks)
        assertEquals(ToWeb.SetBookmarks(sessionId = "s-1", cfis = emptyList()), reader.sent.filterIsInstance<ToWeb.SetBookmarks>().last())
    }

    @Test fun two_bookmarks_on_one_page_are_both_removed() = runTest(dispatcher) {
        val c = controller(); openedAt(c)
        val id = library.knownBookId(ref)!!
        bookmarkPort.add(id, pointCfi, 2, 0.3, "One"); bookmarkPort.add(id, "epubcfi(/6/4!/4/4/1:3)", 2, 0.31, "One"); advanceUntilIdle()
        reader.events.emit(FromWeb.PageBookmarks(sessionId = "s-1", cfis = listOf(pointCfi, "epubcfi(/6/4!/4/4/1:3)"))); advanceUntilIdle()
        c.toggleBookmark(); advanceUntilIdle()
        assertEquals(emptyList(), bookmarkPort.all.value)
    }

    @Test fun the_marker_follows_the_relocates_and_page_bookmark_answers() = runTest(dispatcher) {
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        val id = library.knownBookId(ref)!!
        bookmarkPort.add(id, rangeCfi, 2, 0.3, "One"); advanceUntilIdle()
        assertEquals(ToWeb.SetBookmarks(sessionId = "s-1", cfis = listOf(rangeCfi)), reader.sent.filterIsInstance<ToWeb.SetBookmarks>().last())
        assertFalse(c.state.value.bookmarked)
        reader.events.emit(FromWeb.Relocate(sessionId = "s-1", cfi = rangeCfi, fraction = 0.3, sectionIndex = 2, bookmarks = listOf(rangeCfi))); advanceUntilIdle()
        assertTrue(c.state.value.bookmarked)
        reader.events.emit(FromWeb.Relocate(sessionId = "s-1", cfi = "epubcfi(/6/4!/4,/8/1:0,/10/1:0)", fraction = 0.4, sectionIndex = 2)); advanceUntilIdle()
        assertFalse(c.state.value.bookmarked)
        reader.events.emit(FromWeb.PageBookmarks(sessionId = "s-1", cfis = listOf(rangeCfi))); advanceUntilIdle()
        assertTrue(c.state.value.bookmarked)
        reader.events.emit(FromWeb.PageBookmarks(sessionId = "other", cfis = emptyList())); advanceUntilIdle()
        assertTrue(c.state.value.bookmarked) // another session's answer is ignored
        reader.events.emit(FromWeb.PageBookmarks(sessionId = "s-1", cfis = listOf("epubcfi(/6/4!/4/99/1:0)"))); advanceUntilIdle()
        assertFalse(c.state.value.bookmarked) // a CFI that is no bookmark (removed meanwhile) marks nothing
    }

    @Test fun bookmarks_are_per_book_live_and_survive_a_reopen() = runTest(dispatcher) {
        val c = controller(); openedAt(c)
        val id = library.knownBookId(ref)!!
        assertEquals(listOf(id), bookmarkPort.asked)
        c.toggleBookmark(); advanceUntilIdle()
        bookmarkPort.add("another-book", "epubcfi(/6/2!/4/2/1:0)", 0, 0.0, "x"); advanceUntilIdle()
        assertEquals(1, c.state.value.bookmarks.size) // only this book's
        c.close(); advanceUntilIdle()
        assertEquals(emptyList(), c.state.value.bookmarks)
        reader.sent.clear()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        assertEquals(1, c.state.value.bookmarks.size)
        assertEquals(ToWeb.SetBookmarks(sessionId = "s-1", cfis = listOf(rangeCfi)), reader.sent.filterIsInstance<ToWeb.SetBookmarks>().single())
    }

    @Test fun the_bookmark_button_does_nothing_before_the_book_is_known() = runTest(dispatcher) {
        val c = controller()
        c.toggleBookmark(); advanceUntilIdle() // nothing open
        c.open(ref); advanceUntilIdle()
        c.toggleBookmark(); advanceUntilIdle() // opened not yet received: no bookId, no location
        reader.events.emit(opened()); advanceUntilIdle()
        c.toggleBookmark(); advanceUntilIdle() // no relocate yet
        assertEquals(emptyList(), bookmarkPort.all.value)
        assertNull(c.state.value.notice)
    }

    @Test fun a_quick_double_tap_adds_one_bookmark() = runTest(dispatcher) {
        val c = controller(); openedAt(c)
        c.toggleBookmark(); c.toggleBookmark(); advanceUntilIdle()
        assertEquals(1, bookmarkPort.all.value.size)
    }

    @Test fun tapping_a_bookmark_jumps_to_its_virtual_cfi_and_closes_the_panel() = runTest(dispatcher) {
        val c = controller(); openedAt(c)
        c.toggleBookmark(); advanceUntilIdle()
        c.showToc(); c.selectContentsTab(ContentsTab.Bookmarks)
        reader.sent.clear()
        c.onBookmarkSelected(c.state.value.bookmarks.single())
        assertEquals(ToWeb.GoTo(target = rangeCfi), reader.sent.single())
        assertFalse(c.state.value.tocVisible)
    }

    @Test fun removing_from_the_list_removes_without_a_page_change() = runTest(dispatcher) {
        val c = controller(); openedAt(c)
        c.toggleBookmark(); advanceUntilIdle()
        c.removeBookmark(c.state.value.bookmarks.single()); advanceUntilIdle()
        assertEquals(emptyList(), bookmarkPort.all.value)
        assertEquals(Notice.BookmarkRemoved, c.state.value.notice)
        assertFalse(c.state.value.bookmarked)
    }

    @Test fun the_contents_panel_opens_on_contents_and_remembers_nothing_between_openings() = runTest(dispatcher) {
        val c = controller(); openedAt(c)
        assertEquals(ContentsTab.Contents, c.state.value.contentsTab)
        c.showToc(); c.selectContentsTab(ContentsTab.Bookmarks)
        assertEquals(ContentsTab.Bookmarks, c.state.value.contentsTab)
        c.hideToc(); c.showToc()
        assertEquals(ContentsTab.Contents, c.state.value.contentsTab)
    }

    @Test fun the_list_page_is_derived_from_the_fraction_against_the_book_total() {
        fun p(total: Int?, scope: Boolean = false) = PageIndicator(page = 1, total = total, exact = true, chapterScope = scope)
        assertEquals(1, bookmarkPage(0.0, p(200)))
        assertEquals(101, bookmarkPage(0.5, p(200)))
        assertEquals(200, bookmarkPage(1.0, p(200)))
        assertNull(bookmarkPage(0.5, p(null)))
        assertNull(bookmarkPage(0.5, p(200, scope = true))) // a chapter's total says nothing about the book
        assertEquals(50, bookmarkPercent(0.504))
    }

    // ---- Selection menu (F-060, F-051) ----
    private val selCfi = "epubcfi(/6/4!/4/2,/1:4,/1:10)"
    private fun selection(text: String = " quick\n brown ", cfi: String? = selCfi, index: Int = 2, sid: String = "s-1") =
        FromWeb.Selection(sessionId = sid, text = text, cfi = cfi, sectionIndex = index)
    private suspend fun kotlinx.coroutines.test.TestScope.selected(c: ReaderController, sel: FromWeb.Selection = selection()) {
        openedAt(c); reader.events.emit(sel); advanceUntilIdle(); reader.sent.clear()
    }

    @Test fun copy_puts_exactly_the_selected_text_on_the_clipboard_and_clears_the_selection() = runTest(dispatcher) {
        val c = controller(); selected(c)
        c.copySelection(); advanceUntilIdle()
        assertEquals(listOf(" quick\n brown "), textActions.copied) // no trimming: what Selection.toString() gave
        assertEquals(listOf<ToWeb>(ToWeb.ClearSelection()), reader.sent)
        c.copySelection(); advanceUntilIdle()
        assertEquals(1, textActions.copied.size) // the selection is gone after an action
    }

    @Test fun share_hands_the_selected_text_to_the_share_sheet() = runTest(dispatcher) {
        val c = controller(); selected(c)
        c.shareSelection(); advanceUntilIdle()
        assertEquals(listOf(" quick\n brown "), textActions.shared)
        assertEquals(listOf<ToWeb>(ToWeb.ClearSelection()), reader.sent)
    }

    @Test fun search_in_book_runs_the_search_with_the_selection_trimmed_collapsed_and_capped() = runTest(dispatcher) {
        val c = controller(); selected(c)
        c.searchSelection(); advanceUntilIdle()
        assertTrue(c.state.value.searchVisible)
        assertEquals("quick brown", c.searcher.state.value.query)
        assertEquals(listOf(SearchQuery("quick brown")), searchPort.queries)
        assertTrue(reader.sent.contains(ToWeb.ClearSelection()))
        reader.events.emit(selection(text = "word ".repeat(100))); advanceUntilIdle()
        c.searchSelection(); advanceUntilIdle()
        val q = c.searcher.state.value.query
        assertTrue(q.length in 190..200, "capped: ${q.length}")
        assertEquals(q, q.trim())
        assertTrue(q.startsWith("word word "))
    }

    @Test fun highlight_stores_the_original_cfi_and_draws_the_virtual_one_in_the_last_used_colour() = runTest(dispatcher) {
        val c = controller(map = markedMap)
        openedAt(c, cfi = "v:$rangeCfi")
        reader.events.emit(selection(cfi = "v:$selCfi", index = 12)); advanceUntilIdle(); reader.sent.clear()
        c.highlightSelection(); advanceUntilIdle()
        val a = annotationPort.all.value.single()
        assertEquals(selCfi, a.cfi) // original documents
        assertEquals(2, a.spineIndex)
        assertEquals("quick brown", a.text)
        assertEquals("One", a.chapter)
        assertEquals("yellow", a.color) // default colour
        assertEquals("highlight", a.style)
        assertEquals(library.knownBookId(ref), a.bookId)
        assertEquals(ToWeb.ClearSelection(), reader.sent.first())
        assertEquals(ToWeb.AddAnnotation(sessionId = "s-1", id = a.id, cfi = "v:$selCfi", color = "yellow", style = "highlight"), reader.sent.last())
        assertEquals(HighlightPicker(a.id, "yellow"), c.state.value.highlightPicker) // the colour row offers the other five
    }

    @Test fun underline_is_an_annotation_with_the_underline_style() = runTest(dispatcher) {
        val c = controller(); selected(c)
        c.underlineSelection(); advanceUntilIdle()
        val a = annotationPort.all.value.single()
        assertEquals("underline", a.style)
        assertEquals(ToWeb.AddAnnotation(sessionId = "s-1", id = a.id, cfi = selCfi, color = "yellow", style = "underline"), reader.sent.last())
    }

    @Test fun a_colour_from_the_row_recolours_the_new_highlight_and_becomes_the_next_default() = runTest(dispatcher) {
        val c = controller(); selected(c)
        c.highlightSelection(); advanceUntilIdle()
        val id = annotationPort.all.value.single().id
        reader.sent.clear()
        c.recolorHighlight("purple"); advanceUntilIdle()
        assertEquals(listOf(Triple<String, String?, String?>(id, "purple", null)), annotationPort.updates)
        assertEquals(ToWeb.AddAnnotation(sessionId = "s-1", id = id, cfi = selCfi, color = "purple", style = "highlight"), reader.sent.single())
        assertNull(c.state.value.highlightPicker)
        assertEquals("purple", c.settings.value.highlightColor)
        reader.events.emit(selection(cfi = "epubcfi(/6/4!/4/8,/1:0,/1:3)")); advanceUntilIdle()
        c.highlightSelection(); advanceUntilIdle()
        assertEquals("purple", annotationPort.all.value.last().color)
    }

    @Test fun back_or_a_tap_closes_the_colour_row_first() = runTest(dispatcher) {
        val c = controller(); selected(c)
        c.highlightSelection(); advanceUntilIdle()
        assertTrue(c.backInside())
        assertNull(c.state.value.highlightPicker)
        reader.events.emit(selection(cfi = "epubcfi(/6/4!/4/8,/1:0,/1:3)")); advanceUntilIdle()
        c.highlightSelection(); advanceUntilIdle()
        reader.events.emit(FromWeb.Tap(sessionId = "s-1", x = 0.5, y = 0.5)); advanceUntilIdle()
        assertNull(c.state.value.highlightPicker)
        assertFalse(c.state.value.chromeVisible) // that tap only closed the row
    }

    @Test fun without_a_selection_the_menu_actions_do_nothing() = runTest(dispatcher) {
        val c = controller(); openedAt(c); reader.sent.clear()
        c.copySelection(); c.shareSelection(); c.searchSelection(); c.highlightSelection(); c.underlineSelection(); advanceUntilIdle()
        reader.events.emit(selection()); reader.events.emit(selection(text = "", cfi = null, index = -1)); advanceUntilIdle() // selected, then cleared
        c.copySelection(); c.highlightSelection(); advanceUntilIdle()
        reader.events.emit(selection(sid = "other")); advanceUntilIdle() // another session's selection
        c.copySelection(); advanceUntilIdle()
        assertEquals(emptyList<String>(), textActions.copied + textActions.shared)
        assertEquals(emptyList(), annotationPort.all.value)
        assertFalse(c.state.value.searchVisible)
        assertEquals(emptyList(), reader.sent)
    }

    @Test fun the_selection_belongs_to_the_open_book_only() = runTest(dispatcher) {
        val c = controller(); selected(c)
        c.close(); advanceUntilIdle()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        c.copySelection(); advanceUntilIdle()
        assertEquals(emptyList(), textActions.copied)
    }

    // ---- Highlights, notes and the Highlights tab (F-061, F-062) ----
    private suspend fun stored(cfi: String, spine: Int, color: String = "yellow", style: String = "highlight", note: String? = null, text: String = "word"): Annotation {
        val id = library.knownBookId(ref)!!
        return annotationPort.add(id, cfi, spine, text, "One", color, style).let { a ->
            if (note != null) { annotationPort.update(a.id, note = note); annotationPort.notes.clear() }
            annotationPort.all.value.first { it.id == a.id }
        }
    }
    private val cfiA = "epubcfi(/6/4!/4/2,/1:0,/1:4)"
    private val cfiB = "epubcfi(/6/8!/4/6,/1:2,/1:9)"
    private suspend fun kotlinx.coroutines.test.TestScope.painted(c: ReaderController) {
        reader.events.emit(FromWeb.Painted(sessionId = "s-1")); advanceUntilIdle()
    }
    private fun web(a: Annotation, v: String = "") = WebAnnotation(id = a.id, cfi = v + a.cfi, color = a.color, style = a.style)

    @Test fun the_books_annotations_go_to_the_page_once_after_the_first_paint_as_virtual_cfis() = runTest(dispatcher) {
        val c = controller(map = markedMap)
        openedAt(c, cfi = "v:$rangeCfi")
        val a = stored(cfiA, 1); val b = stored(cfiB, 3, color = "blue", style = "underline")
        assertTrue(reader.sent.none { it is ToWeb.SetAnnotations }) // nothing before the first page is on screen
        reader.sent.clear()
        painted(c)
        assertEquals(listOf<ToWeb>(ToWeb.SetAnnotations(sessionId = "s-1", items = listOf(web(a, "v:"), web(b, "v:")))), reader.sent.filter { it is ToWeb.SetAnnotations })
        assertEquals(listOf(a, b), c.state.value.annotations) // the Highlights tab, in book order
        painted(c) // later paints (every turn) send nothing again
        assertEquals(1, reader.sent.count { it is ToWeb.SetAnnotations })
        c.close(); advanceUntilIdle(); reader.sent.clear()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle(); painted(c)
        assertEquals(1, reader.sent.count { it is ToWeb.SetAnnotations }) // once per open
    }

    @Test fun later_list_changes_go_as_deltas_not_as_the_whole_list() = runTest(dispatcher) {
        val c = controller()
        openedAt(c)
        val a = stored(cfiA, 1); val b = stored(cfiB, 3)
        painted(c); reader.sent.clear()
        annotationPort.update(a.id, color = "green") // e.g. another device (sync) or another screen
        annotationPort.remove(b.id); advanceUntilIdle()
        val n = stored("epubcfi(/6/10!/4/2,/1:0,/1:3)", 4); advanceUntilIdle()
        assertEquals(listOf(
            ToWeb.AddAnnotation(sessionId = "s-1", id = a.id, cfi = cfiA, color = "green", style = "highlight"),
            ToWeb.RemoveAnnotation(sessionId = "s-1", id = b.id),
            ToWeb.AddAnnotation(sessionId = "s-1", id = n.id, cfi = n.cfi, color = "yellow", style = "highlight"),
        ), reader.sent)
    }

    @Test fun a_highlight_made_from_the_menu_is_drawn_once() = runTest(dispatcher) {
        val c = controller(); openedAt(c); painted(c)
        reader.events.emit(selection()); advanceUntilIdle(); reader.sent.clear()
        c.highlightSelection(); advanceUntilIdle()
        assertEquals(1, reader.sent.count { it is ToWeb.AddAnnotation })
        assertEquals(1, c.state.value.annotations.size)
    }

    @Test fun tapping_a_highlight_opens_its_sheet_without_toggling_the_controls() = runTest(dispatcher) {
        val c = controller(); openedAt(c)
        val a = stored(cfiA, 1, color = "pink", note = "my note", text = "quick brown")
        painted(c)
        reader.events.emit(FromWeb.AnnotationTapped(sessionId = "other", id = a.id))
        reader.events.emit(FromWeb.AnnotationTapped(sessionId = "s-1", id = "nope")); advanceUntilIdle()
        assertNull(c.state.value.annotationSheet)
        reader.events.emit(FromWeb.AnnotationTapped(sessionId = "s-1", id = a.id)); advanceUntilIdle()
        assertEquals(AnnotationSheet(a.id, "quick brown", "pink", "highlight", "my note"), c.state.value.annotationSheet)
        assertFalse(c.state.value.chromeVisible)
        assertFalse(c.onVolumeKey(volumeDown = true, press = true)) // typing a note: the volume keys are the system's
    }

    @Test fun the_sheet_recolours_and_restyles_at_once_and_persists() = runTest(dispatcher) {
        val c = controller(map = markedMap); openedAt(c, cfi = "v:$rangeCfi")
        val a = stored(cfiA, 1)
        painted(c)
        reader.events.emit(FromWeb.AnnotationTapped(sessionId = "s-1", id = a.id)); advanceUntilIdle(); reader.sent.clear()
        c.setAnnotationColor("blue")
        assertEquals("blue", c.state.value.annotationSheet?.color) // at once
        advanceUntilIdle()
        c.setAnnotationStyle("underline"); advanceUntilIdle()
        c.setAnnotationColor("nonsense"); c.setAnnotationStyle("strike"); advanceUntilIdle()
        assertEquals(listOf(Triple<String, String?, String?>(a.id, "blue", null), Triple<String, String?, String?>(a.id, null, "underline")), annotationPort.updates)
        assertEquals(listOf<ToWeb>(
            ToWeb.AddAnnotation(sessionId = "s-1", id = a.id, cfi = "v:$cfiA", color = "blue", style = "highlight"),
            ToWeb.AddAnnotation(sessionId = "s-1", id = a.id, cfi = "v:$cfiA", color = "blue", style = "underline"),
        ), reader.sent) // drawn once each (the database echo is not sent again)
        assertEquals("yellow", c.settings.value.highlightColor) // editing an existing one does not change the default
    }

    @Test fun the_note_is_saved_trimmed_when_the_sheet_closes_and_a_blank_one_clears_it() = runTest(dispatcher) {
        val c = controller(); openedAt(c)
        val a = stored(cfiA, 1)
        painted(c)
        reader.events.emit(FromWeb.AnnotationTapped(sessionId = "s-1", id = a.id)); advanceUntilIdle()
        c.editNote("  first line\nsecond  \n")
        assertEquals("  first line\nsecond  \n", c.state.value.annotationSheet?.note) // the draft as typed
        c.closeAnnotationSheet(); advanceUntilIdle()
        assertNull(c.state.value.annotationSheet)
        assertEquals(listOf(a.id to "first line\nsecond"), annotationPort.notes)
        assertEquals("first line\nsecond", c.state.value.annotations.single().note)
        // Unchanged: nothing written. Blank: cleared (no note).
        reader.events.emit(FromWeb.AnnotationTapped(sessionId = "s-1", id = a.id)); advanceUntilIdle()
        c.closeAnnotationSheet(); advanceUntilIdle()
        assertEquals(1, annotationPort.notes.size)
        reader.events.emit(FromWeb.AnnotationTapped(sessionId = "s-1", id = a.id)); advanceUntilIdle()
        c.editNote("   \n ")
        assertTrue(c.backInside()) // Back closes the sheet first, saving like Done
        advanceUntilIdle()
        assertEquals(a.id to "", annotationPort.notes.last())
        assertNull(c.state.value.annotations.single().note)
        assertNull(c.state.value.annotationSheet)
    }

    @Test fun a_tap_on_the_page_or_on_another_highlight_closes_the_sheet_saving_its_note() = runTest(dispatcher) {
        val c = controller(); openedAt(c)
        val a = stored(cfiA, 1); val b = stored(cfiB, 3)
        painted(c)
        reader.events.emit(FromWeb.AnnotationTapped(sessionId = "s-1", id = a.id)); advanceUntilIdle()
        c.editNote("on a")
        reader.events.emit(FromWeb.AnnotationTapped(sessionId = "s-1", id = b.id)); advanceUntilIdle()
        assertEquals(b.id, c.state.value.annotationSheet?.id)
        assertEquals(listOf(a.id to "on a"), annotationPort.notes)
        reader.events.emit(FromWeb.Tap(sessionId = "s-1", x = 0.5, y = 0.5)); advanceUntilIdle()
        assertNull(c.state.value.annotationSheet)
        assertFalse(c.state.value.chromeVisible) // that tap only closed the sheet
    }

    @Test fun remove_erases_it_at_once_and_undo_brings_it_back_with_its_note() = runTest(dispatcher) {
        val c = controller(map = markedMap); openedAt(c, cfi = "v:$rangeCfi")
        val a = stored(cfiA, 1, color = "green", style = "underline", note = "keep me", text = "quick")
        painted(c)
        reader.events.emit(FromWeb.AnnotationTapped(sessionId = "s-1", id = a.id)); advanceUntilIdle(); reader.sent.clear()
        c.removeAnnotation(a.id); advanceUntilIdle()
        assertEquals(listOf(a.id), annotationPort.removed)
        assertEquals(listOf<ToWeb>(ToWeb.RemoveAnnotation(sessionId = "s-1", id = a.id)), reader.sent)
        assertNull(c.state.value.annotationSheet)
        assertEquals(emptyList(), c.state.value.annotations)
        assertEquals(Notice.HighlightRemoved, c.state.value.notice)
        reader.sent.clear()
        c.undoRemoveAnnotation(); advanceUntilIdle()
        val back = annotationPort.all.value.single()
        assertEquals(listOf(a.cfi, a.spineIndex, a.text, a.chapter, a.color, a.style, a.note), listOf(back.cfi, back.spineIndex, back.text, back.chapter, back.color, back.style, back.note))
        assertEquals(listOf<ToWeb>(ToWeb.AddAnnotation(sessionId = "s-1", id = back.id, cfi = "v:$cfiA", color = "green", style = "underline")), reader.sent)
        assertNull(c.state.value.notice)
        c.undoRemoveAnnotation(); advanceUntilIdle()
        assertEquals(1, annotationPort.all.value.size) // undo works once
    }

    @Test fun a_row_of_the_highlights_tab_jumps_to_its_virtual_cfi_and_closes_the_panel() = runTest(dispatcher) {
        val c = controller(map = markedMap); openedAt(c, cfi = "v:$rangeCfi")
        val a = stored(cfiA, 1)
        painted(c)
        c.showToc(); c.selectContentsTab(ContentsTab.Highlights); reader.sent.clear()
        c.onAnnotationSelected(a)
        assertEquals(listOf<ToWeb>(ToWeb.GoTo(target = "v:$cfiA")), reader.sent)
        assertFalse(c.state.value.tocVisible)
        c.removeAnnotation(a.id); advanceUntilIdle() // the list's remove button
        assertEquals(emptyList(), c.state.value.annotations)
    }
}
