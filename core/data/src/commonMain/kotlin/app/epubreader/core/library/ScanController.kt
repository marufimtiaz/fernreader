package app.epubreader.core.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * When scans run (F-003, F-012, C-075): a full scan right after access is granted, an incremental one on app start,
 * on every return to the foreground and whenever the library or one of its views becomes visible (even right after
 * another scan: a book can arrive meanwhile while the app is in front, e.g. over USB), and a manual rescan. One scan
 * at a time; requests while one runs collapse into a single follow-up scan (the running walk may already have passed
 * a new folder), so a burst of view changes costs at most two scans. Automatic scans are quiet: no progress unless
 * they find something ([ScanState.showsProgress]).
 */
class ScanController(
    private val scan: suspend (ScanScope, (ScanEvent) -> Unit) -> ScanEvent.Complete,
    scope: CoroutineScope,
    private val access: () -> Boolean,
    private val scopeSetting: suspend () -> ScanScope = { ScanScope.Everywhere },
    private val probe: (String, String) -> Unit = { _, _ -> },
    private val onBookAdded: (bookId: String) -> Unit = {},
    private val onComplete: () -> Unit = {},
) {
    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state
    private val requests = Channel<Unit>(Channel.CONFLATED)
    /** Whether a pending request is a manual one (or after a grant): the collapsed follow-up then shows progress. */
    @kotlin.concurrent.Volatile private var loudPending = false
    /** The app start scan was requested: the library's first composition must not scan before the first frame. */
    @kotlin.concurrent.Volatile private var started = false

    init {
        scope.launch { for (r in requests) runOnce() }
    }

    fun onAccessGranted() = request(quiet = false)
    fun onAppStart() { started = true; request(quiet = true) }
    fun rescan() = request(quiet = false)
    /** Back from another app (recents, launcher), on any screen. */
    fun onForeground() = request(quiet = true)
    /** The library, a collection, the folder list or a folder came into view. */
    fun onViewShown() {
        if (started) request(quiet = true)
    }

    private fun request(quiet: Boolean) {
        if (!access()) return
        if (!quiet) loudPending = true
        requests.trySend(Unit)
    }

    private suspend fun runOnce() {
        val quiet = !loudPending
        loudPending = false
        _state.update { it.copy(running = true, found = 0, quiet = quiet) }
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
            _state.update { it.copy(running = false) }
        }
    }
}
