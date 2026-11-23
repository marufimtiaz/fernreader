package app.fernreader.reader.search

import app.fernreader.core.data.SearchHistoryPort
import app.fernreader.core.data.SearchHistoryStore
import app.fernreader.core.search.SearchHit
import app.fernreader.core.search.SearchQuery
import app.fernreader.reader.EventProbe
import app.fernreader.reader.SearchPort
import app.fernreader.reader.bridge.TocItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the open book is called to the search port. */
data class SearchBookRef(val bookId: String, val digest: String, val sessionId: String)

/** One row of the result list: [chapter] is null before the first contents entry. */
data class SearchResult(val hit: SearchHit, val chapter: String?)

/**
 * The panel's state. [submitted] is the query the results belong to (null before the first submit); [runId] counts
 * searches so that "first result shown" and "done shown" can be reported once each.
 */
data class SearchState(
    val query: String = "",
    val wholeWord: Boolean = false,
    val results: List<SearchResult> = emptyList(),
    val total: Int = 0,
    val capped: Boolean = false,
    val running: Boolean = false,
    val done: Boolean = false,
    val submitted: String? = null,
    val history: List<String> = emptyList(),
    val runId: Int = 0,
)

/**
 * F-050 state holder: one search at a time for the open book, results streamed in as batches arrive. Lives as long as
 * the controller; [reset] when another book opens. Confined to [scope] (main thread).
 */
class ReaderSearch(
    private val scope: CoroutineScope,
    private val port: SearchPort?,
    private val history: SearchHistoryPort?,
    private val probe: EventProbe,
    private val book: () -> SearchBookRef?,
    private val toc: () -> List<TocItem>,
    /** Sends the jump for a hit (the controller maps coordinates). */
    private val jump: (SearchHit) -> Unit,
    private val hidePanel: () -> Unit,
    /** The reader's (virtual, M8) section of a hit, which [toc]'s indexes count; hits are in original sections. */
    private val section: (SearchHit) -> Int = { it.spineIndex },
) {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state
    private var job: Job? = null
    /** A query submitted before the book's id was known: runs from [bookKnown]. */
    private var pending: String? = null
    private var firstReported = 0
    private var doneReported = 0
    private var clears = 0
    private var lastShownRun = 0
    /** The result list's scroll position, kept by the panel so reopening after a jump shows the same place. */
    var scrollIndex = 0
    var scrollOffset = 0

    /** True (and the saved scroll position is dropped) when [runId] is not the search the panel showed last. */
    fun shownRun(runId: Int): Boolean {
        if (runId == lastShownRun) return false
        lastShownRun = runId; scrollIndex = 0; scrollOffset = 0
        return true
    }

    fun setQuery(text: String) {
        _state.update { it.copy(query = text) }
        if (text.isBlank()) stop(clearResults = true)
    }

    /** Runs [text] (default: the field); empty after trimming does nothing. */
    fun submit(text: String = _state.value.query) {
        val q = text.trim()
        if (q.isEmpty()) return
        _state.update { it.copy(query = q, history = (listOf(q) + it.history.filter { h -> h != q }).take(SearchHistoryStore.LIMIT)) }
        book()?.bookId?.let { id -> scope.launch { guarded("storing search history") { history?.add(id, q) } } }
        probe.ev("search_cmd", "")
        run(q)
    }

    /** The open book's id became known (the controller recorded it): runs a query that was submitted before. */
    fun bookKnown() {
        val q = pending ?: return
        val id = book()?.bookId ?: return
        pending = null
        scope.launch { guarded("storing search history") { history?.add(id, q) } }
        run(q)
    }

    fun toggleWholeWord() {
        _state.update { it.copy(wholeWord = !it.wholeWord) }
        _state.value.submitted?.let(::run)
    }

    fun select(result: SearchResult) {
        jump(result.hit)
        hidePanel()
    }

    /** Reads the book's recent queries (when the panel opens). */
    fun loadHistory() {
        val id = book()?.bookId ?: return
        val gen = clears
        scope.launch {
            guarded("reading search history") {
                val rows = history?.recent(id) ?: return@guarded
                if (gen != clears) return@guarded // cleared meanwhile: the read is stale
                // Queries submitted while the read was in flight are newer than any stored row.
                _state.update { it.copy(history = (it.history + rows.filter { r -> r !in it.history }).take(SearchHistoryStore.LIMIT)) }
            }
        }
    }

    fun clearHistory() {
        clears++
        _state.update { it.copy(history = emptyList()) }
        val id = book()?.bookId ?: return
        scope.launch { guarded("clearing search history") { history?.clear(id) } }
    }

    /** A different book opened (or this one closed): nothing carries over. */
    fun reset() {
        job?.cancel(); job = null; pending = null
        firstReported = 0; doneReported = 0; lastShownRun = 0; scrollIndex = 0; scrollOffset = 0; clears++
        _state.value = SearchState()
    }

    /** Called by the panel on the first frame that shows a result row (once per search). */
    fun reportFirst(runId: Int) {
        if (runId <= firstReported) return
        firstReported = runId
        probe.ev("search_first", "")
    }

    /** Called by the panel on the first frame that shows the final update (once per search). */
    fun reportDone(runId: Int, total: Int) {
        if (runId <= doneReported) return
        doneReported = runId
        probe.ev("search_done", "$total")
    }

    private fun stop(clearResults: Boolean) {
        job?.cancel(); job = null; pending = null
        _state.update {
            if (clearResults) it.copy(results = emptyList(), total = 0, capped = false, running = false, done = false, submitted = null)
            else it.copy(running = false)
        }
    }

    private fun run(q: String) {
        job?.cancel(); job = null; pending = null
        val ref = book()
        val p = port
        val whole = _state.value.wholeWord
        val run = _state.value.runId + 1
        if (ref == null && p != null) { // the book is still opening: wait for its id instead of reporting "no results"
            pending = q
            _state.update { it.copy(runId = run, submitted = q, results = emptyList(), total = 0, capped = false, running = true, done = false) }
            return
        }
        if (ref == null || p == null) {
            _state.update { it.copy(runId = run, submitted = q, results = emptyList(), total = 0, capped = false, running = false, done = true) }
            return
        }
        _state.update { it.copy(runId = run, submitted = q, results = emptyList(), total = 0, capped = false, running = true, done = false) }
        val labels = ChapterLabels(toc())
        job = scope.launch {
            try {
                p.ensureIndex(ref.bookId, ref.digest, ref.sessionId)
                p.search(ref.bookId, ref.digest, ref.sessionId, SearchQuery(q, whole)).collect { u ->
                    if (_state.value.runId != run) return@collect // a newer search replaced this one
                    _state.update {
                        it.copy(
                            results = it.results + u.hits.map { h -> SearchResult(h, labels.label(section(h))) },
                            total = u.total, capped = u.capped, running = !u.done, done = u.done,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) { // a failed search ends with what arrived; it never takes the reader down (R-002)
                println("ReaderSearch: search failed: $e")
                probe.ev("controller_error", "search: $e")
                _state.update { if (it.runId == run) it.copy(running = false, done = true) else it }
            }
        }
    }

    private inline fun guarded(where: String, block: () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { println("ReaderSearch: $where failed: $e") }
    }
}
