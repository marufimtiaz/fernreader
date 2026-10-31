package app.epubreader.core.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/**
 * When scans run (F-003, F-012): a full scan right after access is granted, an incremental one on app start and
 * when the library comes back to the front (skipped within [debounceMs] of the last completed scan), and a manual
 * rescan. One scan at a time; requests while one runs collapse into a single follow-up scan.
 */
class ScanController(
    private val scan: suspend (ScanScope, (ScanEvent) -> Unit) -> ScanEvent.Complete,
    scope: CoroutineScope,
    private val access: () -> Boolean,
    private val scopeSetting: suspend () -> ScanScope = { ScanScope.Everywhere },
    private val probe: (String, String) -> Unit = { _, _ -> },
    private val onBookAdded: (bookId: String) -> Unit = {},
    private val onComplete: () -> Unit = {},
    private val nowMs: () -> Long = { START.elapsedNow().inWholeMilliseconds },
    private val debounceMs: Long = 2_000,
) {
    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state
    private val requests = Channel<Unit>(Channel.CONFLATED)
    @kotlin.concurrent.Volatile private var lastCompleteAt: Long? = null

    init {
        scope.launch { for (r in requests) runOnce() }
    }

    fun onAccessGranted() = request()
    fun onAppStart() = request()
    fun rescan() = request()
    fun onLibraryResumed() {
        val last = lastCompleteAt
        if (_state.value.running || (last != null && nowMs() - last < debounceMs)) return
        request()
    }

    private fun request() { if (access()) requests.trySend(Unit) }

    private suspend fun runOnce() {
        _state.update { it.copy(running = true, found = 0) }
        probe("scan_started", "")
        try {
            val done = scan(scopeSetting()) { e ->
                when (e) {
                    is ScanEvent.BookAdded -> { _state.update { it.copy(found = it.found + 1) }; probe("scan_book_added", e.path); onBookAdded(e.bookId) }
                    is ScanEvent.Complete -> probe("scan_complete", "ms=${e.ms} added=${e.added} removed=${e.removed} failed=${e.failed} files=${e.files}")
                    else -> Unit
                }
            }
            _state.update { it.copy(lastComplete = done) }
            onComplete()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) { // a scan failure must never take the app down; the next trigger retries
            probe("scan_error", e.toString())
        } finally {
            lastCompleteAt = nowMs()
            _state.update { it.copy(running = false) }
        }
    }

    private companion object { val START = TimeSource.Monotonic.markNow() }
}
