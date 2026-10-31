package app.epubreader.reader

import app.epubreader.core.epub.TextPos
import app.epubreader.core.data.HybridClock
import app.epubreader.core.data.LibraryRepository
import app.epubreader.core.data.PositionWriter
import app.epubreader.core.data.Flow
import app.epubreader.core.data.ReaderSettings
import app.epubreader.core.data.ReadingThemeId
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
    private suspend fun controller(controllerScope: CoroutineScope = scope, opener: FakeOpener = this.opener) = ReaderController(
        reader, opener, library, CompletableDeferred(PositionWriter.create(db, clock, appScope)), controllerScope, WebTexts("missing"),
        closeScope = appScope, settingsStore = settings, search = searchPort, searchHistory = searchHistory,
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
        val goTo = reader.sent.last() as ToWeb.GoTo
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

    @Test fun toggle_bookmark_stub_is_harmless() = runTest(dispatcher) {
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        val before = c.state.value
        reader.sent.clear()
        c.toggleBookmark()
        assertEquals(before, c.state.value)
        assertTrue(reader.sent.isEmpty())
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
}
