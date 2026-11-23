package app.fernreader.reader.search

import app.fernreader.core.data.SearchHistoryPort
import app.fernreader.core.search.SearchHit
import app.fernreader.core.search.SearchQuery
import app.fernreader.core.search.SearchUpdate
import app.fernreader.reader.EventProbe
import app.fernreader.reader.SearchPort
import app.fernreader.reader.bridge.TocItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderSearchTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    @AfterTest fun stop() { scope.cancel() }

    private fun hit(spine: Int, offset: Int, text: String = "Prince") =
        SearchHit(spine, offset, text.length, "…the $text said…", 4, 4 + text.length, text)

    private class FakePort : SearchPort {
        val ensured = mutableListOf<Triple<String, String, String>>()
        val searches = mutableListOf<SearchQuery>()
        var script: (SearchQuery) -> Flow<SearchUpdate> = { flow { emit(SearchUpdate(emptyList(), 0, false, true)) } }
        override fun ensureIndex(bookId: String, digest: String, sessionId: String) { ensured += Triple(bookId, digest, sessionId) }
        override fun search(bookId: String, digest: String, sessionId: String, query: SearchQuery): Flow<SearchUpdate> {
            searches += query
            return script(query)
        }
    }
    private class MemHistory : SearchHistoryPort {
        val rows = mutableMapOf<String, MutableList<String>>()
        override suspend fun recent(bookId: String, limit: Int) = rows[bookId].orEmpty().take(limit)
        override suspend fun add(bookId: String, query: String) { rows.getOrPut(bookId) { mutableListOf() }.apply { remove(query); add(0, query) } }
        override suspend fun clear(bookId: String) { rows.remove(bookId) }
    }

    private val port = FakePort()
    private val history = MemHistory()
    private val events = mutableListOf<Pair<String, String>>()
    private val jumps = mutableListOf<SearchHit>()
    private var hidden = 0
    private var book: SearchBookRef? = SearchBookRef("b1", "dig", "s-1")
    private val toc = listOf(TocItem("One", "a", 0, index = 0), TocItem("Two", "b", 0, index = 2))
    private fun search(history: SearchHistoryPort? = this.history, port: SearchPort? = this.port) =
        ReaderSearch(scope, port, history, { n, x -> events += n to x }, { book }, { toc }, { jumps += it }, { hidden++ })

    @Test fun submit_streams_batches_with_chapter_labels() = runTest(dispatcher) {
        val gate = MutableSharedFlow<SearchUpdate>()
        port.script = { gate }
        val s = search()
        s.submit("  prince ")
        advanceUntilIdle()
        assertEquals(listOf(Triple("b1", "dig", "s-1")), port.ensured)
        assertEquals(listOf(SearchQuery("prince")), port.searches)
        assertTrue(s.state.value.running)
        assertEquals("prince", s.state.value.submitted)
        gate.emit(SearchUpdate(listOf(hit(0, 5), hit(1, 9)), 2, false, false)); advanceUntilIdle()
        assertEquals(listOf("One", "One"), s.state.value.results.map { it.chapter })
        assertEquals(2, s.state.value.total)
        assertTrue(s.state.value.running)
        gate.emit(SearchUpdate(listOf(hit(3, 1)), 3, false, true)); advanceUntilIdle()
        assertEquals(listOf("One", "One", "Two"), s.state.value.results.map { it.chapter })
        assertEquals(3, s.state.value.total)
        assertFalse(s.state.value.running)
        assertTrue(s.state.value.done)
        assertFalse(s.state.value.capped)
    }

    @Test fun capped_is_reported() = runTest(dispatcher) {
        port.script = { flow { emit(SearchUpdate(listOf(hit(0, 1)), 5000, true, true)) } }
        val s = search()
        s.submit("the"); advanceUntilIdle()
        assertTrue(s.state.value.capped)
        assertEquals(5000, s.state.value.total)
    }

    @Test fun blank_text_does_nothing() = runTest(dispatcher) {
        val s = search()
        s.submit("   "); advanceUntilIdle()
        assertTrue(port.searches.isEmpty())
        assertNull(s.state.value.submitted)
        assertTrue(events.isEmpty())
    }

    @Test fun submit_sends_the_probe_event_and_records_history() = runTest(dispatcher) {
        val s = search()
        s.submit("Prince"); advanceUntilIdle()
        s.submit("war"); advanceUntilIdle()
        s.submit("prince"); advanceUntilIdle() // a different spelling is its own entry
        assertEquals(listOf("search_cmd", "search_cmd", "search_cmd"), events.map { it.first })
        assertEquals(listOf("prince", "war", "Prince"), s.state.value.history)
        assertEquals(listOf("prince", "war", "Prince"), history.rows["b1"]?.toList())
    }

    @Test fun history_is_listed_on_open_and_can_be_cleared() = runTest(dispatcher) {
        history.rows["b1"] = mutableListOf("a", "b")
        val s = search()
        s.loadHistory(); advanceUntilIdle()
        assertEquals(listOf("a", "b"), s.state.value.history)
        s.clearHistory(); advanceUntilIdle()
        assertEquals(emptyList<String>(), s.state.value.history)
        assertNull(history.rows["b1"])
    }

    @Test fun toggling_whole_word_reruns_the_submitted_query() = runTest(dispatcher) {
        port.script = { q -> flow { emit(SearchUpdate(listOf(hit(0, 1)), 1, false, true)) } }
        val s = search()
        s.toggleWholeWord(); advanceUntilIdle() // nothing submitted: only the flag flips
        assertTrue(s.state.value.wholeWord)
        assertTrue(port.searches.isEmpty())
        s.submit("prince"); advanceUntilIdle()
        s.toggleWholeWord(); advanceUntilIdle()
        assertEquals(listOf(SearchQuery("prince", wholeWord = true), SearchQuery("prince", wholeWord = false)), port.searches)
        assertEquals(1, s.state.value.results.size) // replaced, not appended
        assertEquals(listOf("prince"), s.state.value.history) // a re-run is not a new history entry
    }

    @Test fun a_new_submit_cancels_the_running_search_and_drops_its_late_results() = runTest(dispatcher) {
        val first = MutableSharedFlow<SearchUpdate>()
        port.script = { q -> if (q.text == "one") first else flow { emit(SearchUpdate(listOf(hit(2, 7, "Two")), 1, false, true)) } }
        val s = search()
        s.submit("one"); advanceUntilIdle()
        first.emit(SearchUpdate(listOf(hit(0, 1)), 1, false, false)); advanceUntilIdle()
        s.submit("two"); advanceUntilIdle()
        assertEquals(0, first.subscriptionCount.value)
        first.emit(SearchUpdate(listOf(hit(0, 2)), 2, false, true)); advanceUntilIdle()
        assertEquals(listOf(7), s.state.value.results.map { it.hit.offset })
        assertEquals("two", s.state.value.submitted)
        assertTrue(s.state.value.done)
    }

    @Test fun select_jumps_hides_the_panel_and_keeps_the_results() = runTest(dispatcher) {
        port.script = { flow { emit(SearchUpdate(listOf(hit(0, 5)), 1, false, true)) } }
        val s = search()
        s.submit("prince"); advanceUntilIdle()
        val r = s.state.value.results.single()
        s.select(r)
        assertEquals(listOf(r.hit), jumps)
        assertEquals(1, hidden)
        assertEquals(1, s.state.value.results.size)
        assertEquals("prince", s.state.value.submitted)
    }

    @Test fun typing_edits_the_field_and_emptying_it_returns_to_history() = runTest(dispatcher) {
        port.script = { flow { emit(SearchUpdate(listOf(hit(0, 5)), 1, false, true)) } }
        val s = search()
        s.submit("prince"); advanceUntilIdle()
        s.setQuery("prin")
        assertEquals("prin", s.state.value.query)
        assertEquals("prince", s.state.value.submitted) // results stay until the next submit
        s.setQuery(""); advanceUntilIdle()
        assertNull(s.state.value.submitted)
        assertTrue(s.state.value.results.isEmpty())
    }

    @Test fun reset_clears_everything_and_stops_the_search() = runTest(dispatcher) {
        val gate = MutableSharedFlow<SearchUpdate>()
        port.script = { gate }
        val s = search()
        s.toggleWholeWord()
        s.submit("prince"); advanceUntilIdle()
        s.reset(); advanceUntilIdle()
        assertEquals(SearchState(), s.state.value)
        assertEquals(0, gate.subscriptionCount.value)
    }

    @Test fun probe_first_and_done_are_reported_once_per_search() = runTest(dispatcher) {
        port.script = { flow { emit(SearchUpdate(listOf(hit(0, 5)), 1, false, true)) } }
        val s = search()
        s.submit("a"); advanceUntilIdle()
        val run = s.state.value.runId
        s.reportFirst(run); s.reportFirst(run); s.reportDone(run, 1); s.reportDone(run, 1)
        assertEquals(listOf("search_cmd" to "", "search_first" to "", "search_done" to "1"), events)
        s.submit("b"); advanceUntilIdle()
        s.reportFirst(s.state.value.runId)
        assertEquals(2, events.count { it.first == "search_first" })
    }

    @Test fun without_a_port_nothing_runs() = runTest(dispatcher) {
        val s2 = search(port = null)
        s2.submit("a"); advanceUntilIdle()
        assertFalse(s2.state.value.running)
        assertTrue(s2.state.value.done)
    }

    @Test fun a_query_submitted_before_the_book_id_is_known_waits_and_runs_once_it_is() = runTest(dispatcher) {
        book = null
        val s = search()
        s.submit("alpha"); advanceUntilIdle()
        assertTrue(s.state.value.running && !s.state.value.done, "not a finished 'no results'")
        assertTrue(port.searches.isEmpty())
        book = SearchBookRef("b1", "dig", "s-1")
        s.bookKnown(); advanceUntilIdle()
        assertEquals(listOf(SearchQuery("alpha")), port.searches)
        assertTrue(s.state.value.done && !s.state.value.running)
        assertEquals(listOf("alpha"), history.rows["b1"].orEmpty().toList())
        s.bookKnown(); advanceUntilIdle()
        assertEquals(1, port.searches.size) // nothing pending any more
    }

    @Test fun a_pending_query_is_dropped_by_reset() = runTest(dispatcher) {
        book = null
        val s = search()
        s.submit("alpha"); s.reset()
        book = SearchBookRef("b2", "d", "s-2")
        s.bookKnown(); advanceUntilIdle()
        assertTrue(port.searches.isEmpty())
    }

    @Test fun a_failing_search_ends_as_done_with_what_arrived() = runTest(dispatcher) {
        port.script = { flow { emit(SearchUpdate(listOf(hit(0, 5)), 1, false, false)); throw IllegalStateException("db") } }
        val s = search()
        s.submit("a"); advanceUntilIdle()
        assertTrue(s.state.value.done)
        assertFalse(s.state.value.running)
        assertEquals(1, s.state.value.results.size)
    }

    @Test fun a_history_read_that_lands_late_keeps_the_query_submitted_meanwhile() = runTest(dispatcher) {
        history.rows["b1"] = mutableListOf("old1", "old2")
        val s = search()
        s.loadHistory()
        s.submit("fresh") // before the read resumed, as searchFor does
        advanceUntilIdle()
        assertEquals(listOf("fresh", "old1", "old2"), s.state.value.history)
    }

    @Test fun a_history_read_that_lands_after_clear_does_not_bring_it_back() = runTest(dispatcher) {
        history.rows["b1"] = mutableListOf("old")
        val s = search()
        s.loadHistory()
        s.clearHistory()
        advanceUntilIdle()
        assertEquals(emptyList<String>(), s.state.value.history)
    }

    @Test fun the_scroll_position_survives_a_reopen_of_the_same_search_only() = runTest(dispatcher) {
        val s = search()
        s.submit("a"); advanceUntilIdle()
        val run = s.state.value.runId
        assertTrue(s.shownRun(run)) // first time shown: start at the top
        s.scrollIndex = 12; s.scrollOffset = 7 // the panel saves these when it closes
        assertFalse(s.shownRun(run)) // reopened: same search, keep the place
        assertEquals(12, s.scrollIndex)
        s.submit("b"); advanceUntilIdle()
        assertTrue(s.shownRun(s.state.value.runId))
        assertEquals(0, s.scrollIndex)
        assertEquals(0, s.scrollOffset)
    }
}
