package app.epubreader.reader

import app.epubreader.core.data.LibraryRepository
import app.epubreader.core.data.OpenedBookInfo
import app.epubreader.core.data.PositionWriter
import app.epubreader.core.data.SourceRef
import app.epubreader.core.epub.PositionMap
import app.epubreader.core.epub.meta.BookIdentity
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.reader.bridge.ToWeb
import app.epubreader.reader.bridge.TocItem
import app.epubreader.reader.bridge.WebTexts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ReaderError {
    data class Open(val reason: OpenFailure) : ReaderError
    data class Engine(val kind: String, val message: String) : ReaderError
}

enum class Notice { MissingTarget }

data class ReaderUiState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val title: String = "",
    val toc: List<TocItem> = emptyList(),
    val currentTocHref: String? = null,
    val chromeVisible: Boolean = false,
    val tocVisible: Boolean = false,
    val notice: Notice? = null,
    val error: ReaderError? = null,
    val source: SourceRef? = null,
)

/**
 * Drives one open book at a time: open → `opened` (identity, record, resume) → `relocate` (positions) → close.
 * Confined to [scope] (the main thread in the app). Never blocks: database and file work are suspending calls
 * on their own dispatchers, and close() returns at once (R-005).
 *
 * [positions] is a Deferred because PositionWriter.create seeds its clock from the database (suspend, IO); it is
 * awaited only when a position is stored.
 */
class ReaderController(
    private val reader: ReaderPort,
    private val opener: BookOpenerPort,
    private val library: LibraryRepository,
    private val positions: Deferred<PositionWriter>,
    private val scope: CoroutineScope,
    private val texts: WebTexts,
    private val probe: EventProbe = EventProbe.None,
) {
    private class Session(val id: String, val ref: SourceRef, val cdDigest: String, val size: Long, val hadPosition: Boolean) {
        var bookId: String? = null
        var last: PositionWriter.Update? = null
        var painted = false
        val map: PositionMap = PositionMap.Identity // M8: the book's ChunkMap
    }

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state
    private var session: Session? = null
    private var openJob: Job? = null
    private var closing: Job? = null

    init {
        scope.launch { reader.events.collect { on(it) } }
    }

    fun open(ref: SourceRef) {
        close()
        _state.value = ReaderUiState(open = true, loading = true, source = ref)
        val previous = closing
        openJob = scope.launch {
            previous?.join() // the last position of the book just closed is stored before we read positions
            val known = library.knownBookId(ref)
            val saved = known?.let { library.position(it) }
            when (val r = opener.open(ref)) {
                is OpenResult.Failed -> _state.update { it.copy(loading = false, error = ReaderError.Open(r.reason)) }
                is OpenResult.Ok -> try {
                    val s = Session(r.sessionId, ref, r.cdDigest, r.size, hadPosition = saved != null).also { it.bookId = known }
                    reader.isReady.first { it }
                    session = s
                    probe.ev("open_sent", ref.key)
                    reader.send(ToWeb.Open(sessionId = s.id, position = saved?.cfi?.let(s.map::cfiToVirtual), texts = texts))
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { opener.close(r.sessionId) }
                    throw e
                }
            }
        }
    }

    /** Back from the reader (R-005): returns immediately; the engine and the session close in the background. */
    fun close() {
        openJob?.cancel(); openJob = null
        val s = session
        session = null
        _state.value = ReaderUiState()
        if (s == null) return
        reader.send(ToWeb.Close())
        closing = scope.launch {
            s.last?.let { positions.await().writeNow(it) }
            opener.close(s.id)
        }
    }

    /** Handles Back inside the reader (closes the TOC first). Returns false if Back should leave the reader. */
    fun backInside(): Boolean = if (_state.value.tocVisible) { hideToc(); true } else false

    fun toggleChrome() = _state.update { it.copy(chromeVisible = !it.chromeVisible) }
    fun showToc() { probe.ev("toc_cmd", ""); _state.update { it.copy(tocVisible = true, chromeVisible = false) } }
    fun hideToc() = _state.update { it.copy(tocVisible = false) }
    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /** R-001: a missing target shows a notice and the page stays; otherwise jump (href is the reader's own). */
    fun onTocSelected(item: TocItem) {
        if (item.missing) { _state.update { it.copy(notice = Notice.MissingTarget) }; return }
        _state.update { it.copy(tocVisible = false) }
        reader.send(ToWeb.GoTo(target = item.href))
    }

    private suspend fun on(m: FromWeb) {
        val s = session ?: return
        when (m) {
            is FromWeb.Opened -> if (m.sessionId == s.id) {
                val identity = BookIdentity.of(m.identifier, s.cdDigest)
                val rec = library.recordOpened(OpenedBookInfo(identity, m.title, m.authors, s.ref, s.size))
                s.bookId = rec.bookId
                if (session !== s) return // closed (or another book opened) while recording
                // Same book reached through a new source (e.g. picked again): resume where it was left.
                if (!s.hadPosition) rec.position?.let { reader.send(ToWeb.GoTo(target = s.map.cfiToVirtual(it.cfi))) }
                _state.update { it.copy(title = m.title, toc = m.toc) }
            }
            is FromWeb.Relocate -> if (m.sessionId == s.id) {
                probe.ev("relocate", m.cfi)
                s.bookId?.let { id ->
                    val u = PositionWriter.Update(id, s.map.cfiToOriginal(m.cfi), m.fraction, m.sectionIndex)
                    s.last = u
                    positions.await().submit(u)
                }
                if (session === s) _state.update { it.copy(currentTocHref = m.tocHref) }
            }
            is FromWeb.Painted -> if (m.sessionId == s.id) {
                probe.ev("painted", "")
                if (!s.painted) { s.painted = true; probe.ev("painted_first", ""); _state.update { it.copy(loading = false) } }
            }
            is FromWeb.Tap -> if (m.sessionId == s.id && m.x in 0.33..0.67) toggleChrome()
            is FromWeb.Error -> if (m.sessionId == null || m.sessionId == s.id) {
                _state.update { it.copy(loading = false, error = ReaderError.Engine(m.kind, m.message)) }
            }
            else -> Unit
        }
    }
}
