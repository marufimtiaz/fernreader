package app.epubreader.reader

import app.epubreader.core.data.HybridClock
import app.epubreader.core.data.LibraryRepository
import app.epubreader.core.data.PositionWriter
import app.epubreader.core.data.SourceRef
import app.epubreader.core.data.buildDatabase
import app.epubreader.core.data.jvmDatabaseBuilder
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.reader.bridge.ToWeb
import app.epubreader.reader.bridge.TocItem
import app.epubreader.reader.bridge.WebTexts
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderControllerTest {
    private class FakeReader : ReaderPort {
        val sent = mutableListOf<ToWeb>()
        override val events = MutableSharedFlow<FromWeb>(extraBufferCapacity = 64)
        override val isReady = MutableStateFlow(true)
        override fun send(m: ToWeb) { sent += m }
    }
    private class FakeOpener(var next: OpenResult = OpenResult.Ok("s-1", "d".repeat(64), 100)) : BookOpenerPort {
        val closed = mutableListOf<String>()
        override suspend fun open(ref: SourceRef) = next
        override suspend fun close(sessionId: String) { closed += sessionId }
    }

    // R15: Room runs its queries on the test scheduler, so advanceUntilIdle() also waits for the database.
    private val dispatcher = StandardTestDispatcher()
    private val reader = FakeReader()
    private val opener = FakeOpener()
    private val db = buildDatabase(jvmDatabaseBuilder(Files.createTempDirectory("db").resolve("t.db").toString()), dispatcher)
    private val clock = HybridClock(System::currentTimeMillis)
    private val library = LibraryRepository(db, clock)
    private val ref = SourceRef.File("/books/a.epub")
    private val toc = listOf(TocItem("One", "c1.xhtml", 0), TocItem("Gone", "gone.xhtml", 0, missing = true))
    private fun opened(sid: String = "s-1") = FromWeb.Opened(sessionId = sid, identifier = "urn:x", title = "A", authors = listOf("B"), dir = "ltr", fixedLayout = false, sections = 3, toc = toc)
    // The controller's event collector runs forever, so it gets its own scope (cancelled after each test) instead of
    // the test's. Not backgroundScope: advanceUntilIdle() stops once only background tasks remain, so it would never
    // run the controller's work.
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    @AfterTest fun cancelScope() = scope.cancel()
    private suspend fun controller() = ReaderController(
        reader, opener, library, CompletableDeferred(PositionWriter.create(db, clock, scope)), scope, WebTexts("missing"),
    )

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
}
