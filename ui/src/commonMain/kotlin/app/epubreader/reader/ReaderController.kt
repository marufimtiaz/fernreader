package app.epubreader.reader

import app.epubreader.core.data.LibraryRepository
import app.epubreader.core.data.OpenedBookInfo
import app.epubreader.core.data.PositionWriter
import app.epubreader.core.data.Align
import app.epubreader.core.data.Flow
import app.epubreader.core.data.ReaderSettings
import app.epubreader.core.data.SearchHistoryPort
import app.epubreader.core.data.SettingsPort
import app.epubreader.core.data.SourceRef
import app.epubreader.core.epub.PositionMap
import app.epubreader.core.epub.TextPos
import app.epubreader.core.epub.meta.BookIdentity
import app.epubreader.reader.bridge.BridgeJson
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.reader.bridge.ToWeb
import app.epubreader.reader.bridge.TocItem
import app.epubreader.reader.bridge.WebSettings
import app.epubreader.reader.bridge.WebTexts
import app.epubreader.reader.search.ReaderSearch
import app.epubreader.reader.search.SearchBookRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ReaderError {
    data class Open(val reason: OpenFailure) : ReaderError
    data class Engine(val kind: String, val message: String) : ReaderError
    /** R-008: the open book's file was deleted or replaced; the book was closed. */
    data object FileChanged : ReaderError
}

enum class Notice { MissingTarget, HitNotFound }

data class ReaderUiState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val title: String = "",
    val toc: List<TocItem> = emptyList(),
    val currentTocHref: String? = null,
    /** Book fraction of the current location (seek slider, F-034). */
    val fraction: Double = 0.0,
    val chromeVisible: Boolean = false,
    val tocVisible: Boolean = false,
    val searchVisible: Boolean = false,
    val displayVisible: Boolean = false,
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
 *
 * The close-out (last position, session release) runs on [closeScope], an app-scoped scope, so it also completes
 * when [scope] (the activity's lifecycleScope) is already cancelled, e.g. from onDestroy.
 *
 * Failures (database, opener) never escape: each event is handled on its own, and a failure the reader can see is
 * shown as [ReaderError] (R-002).
 */
class ReaderController(
    private val reader: ReaderPort,
    private val opener: BookOpenerPort,
    private val library: LibraryRepository,
    private val positions: Deferred<PositionWriter>,
    private val scope: CoroutineScope,
    private val texts: WebTexts,
    private val probe: EventProbe = EventProbe.None,
    private val closeScope: CoroutineScope = scope,
    /** Where [settings] are stored (F-100 persistence); null in tests that do not care. */
    private val settingsStore: SettingsPort? = null,
    /** In-book search (F-050); null in tests that do not care. The panel is useless without it but harmless. */
    private val search: SearchPort? = null,
    /** Where the per-book search history lives; null in tests that do not care. */
    private val searchHistory: SearchHistoryPort? = null,
) {
    private class Session(val id: String, val ref: SourceRef, val cdDigest: String, val size: Long, val hadPosition: Boolean) {
        var bookId: String? = null
        var last: PositionWriter.Update? = null
        var painted = false
        var stats: List<SectionStat>? = null
        var sections = 0
        /**
         * Id of the resume jump sent after `opened` (same book, new source) until the web side answers `seeked`.
         * Relocates before that are where the book opened (its start), not where the reader is: not stored (R-009).
         */
        var seek: Int? = null
        val map: PositionMap = PositionMap.Identity // M8: the book's ChunkMap
    }

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state
    private var session: Session? = null
    private var openJob: Job? = null
    private var closing: Job? = null
    private var seeks = 0
    /** Text sizes of the open book's spine items (page estimates); null until loaded or if unreadable. */
    val sectionStats: List<SectionStat>? get() = session?.stats
    private val totals = PageTotals(probe)
    /** Page indicator: "page N", then "page N of ≈ M", then exact (F-034, C-016, C-017). */
    val page: StateFlow<PageIndicator> = totals.state
    /** Counted pages of a section in the current layout (diagnostics for the C-003 agreement test). */
    fun countedPages(index: Int): Int? = totals.counted(index)
    private val _settings = MutableStateFlow(ReaderSettings())
    /** Display settings (F-070..F-075), one value for every book. */
    val settings: StateFlow<ReaderSettings> = _settings
    /** F-050 search state for the open book (reset when another opens). */
    val searcher = ReaderSearch(
        scope, search, searchHistory, probe,
        book = { session?.let { s -> s.bookId?.let { SearchBookRef(it, s.cdDigest, s.id) } } },
        toc = { _state.value.toc },
        jump = { h -> goToText(TextPos(h.spineIndex, h.offset), h.length, h.text) },
        hidePanel = ::hideSearch,
    )
    private var landscape = false
    private var saveJob: Job? = null
    /** Settings are read before the first open is sent, so a book never opens with the defaults first. */
    private val settingsLoaded: Deferred<Unit> = scope.async {
        try {
            settingsStore?.get(ReaderSettings.KEY)?.let { _settings.value = ReaderSettings.decode(it) }
            totals.scope = _settings.value.pageIndicatorScope
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed("reading settings", e, showIfOpen = false)
        }
    }

    init {
        scope.launch {
            reader.events.collect { m ->
                try {
                    on(m)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed("event ${m::class.simpleName}", e, showIfOpen = m is FromWeb.Opened)
                }
            }
        }
    }

    /** Logs a failure; shows it when the reader is still open and the failure means the book cannot be used. */
    private fun failed(where: String, e: Exception, showIfOpen: Boolean) {
        println("ReaderController: $where failed: $e")
        probe.ev("controller_error", "$where: $e")
        if (showIfOpen && _state.value.open) {
            _state.update { it.copy(loading = false, error = ReaderError.Engine(STORAGE, e.message ?: e.toString())) }
        }
    }

    fun open(ref: SourceRef) {
        close()
        totals.reset()
        searcher.reset()
        _state.value = ReaderUiState(open = true, loading = true, source = ref)
        val previous = closing
        openJob = scope.launch {
            previous?.join() // the last position of the book just closed is stored before we read positions
            val (known, saved) = try {
                library.knownBookId(ref).let { it to it?.let { id -> library.position(id) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed("open: reading the library", e, showIfOpen = true)
                return@launch
            }
            // Not cancellable: a session the opener produced must be seen here, so a Back during the open (which
            // cancels this job) releases it at once instead of leaving it registered until the next open.
            var produced: OpenResult? = null
            val r = try {
                withContext(NonCancellable) { opener.open(ref).also { produced = it } }
            } catch (e: CancellationException) {
                (produced as? OpenResult.Ok)?.let { release(it.sessionId) }
                throw e
            } catch (e: Exception) { // BookOpener maps its own failures; this is the last guard (R-002)
                failed("open: opening the file", e, showIfOpen = false)
                OpenResult.Failed(OpenFailure.NO_ACCESS)
            }
            if (!isActive) { // closed (Back) while the opener was working
                if (r is OpenResult.Ok) release(r.sessionId)
                return@launch
            }
            when (r) {
                is OpenResult.Failed -> _state.update { it.copy(loading = false, error = ReaderError.Open(r.reason)) }
                is OpenResult.Ok -> try {
                    val s = Session(r.sessionId, ref, r.cdDigest, r.size, hadPosition = saved != null).also { it.bookId = known }
                    reader.isReady.first { it }
                    settingsLoaded.await()
                    session = s
                    probe.ev("open_sent", ref.key)
                    reader.send(ToWeb.Open(sessionId = s.id, position = saved?.cfi?.let(s.map::cfiToVirtual), texts = texts, settings = web()))
                } catch (e: CancellationException) {
                    release(r.sessionId)
                    throw e
                }
            }
        }
    }

    /**
     * Per-section text sizes for the page estimate, after the first page is on screen (never delays it): from the
     * book cache for this file version, else extracted on the opener's I/O thread and cached.
     */
    private fun loadStats(s: Session) {
        scope.launch {
            val id = s.bookId ?: return@launch
            try {
                val stats = library.cached(id, STATS_KIND, s.cdDigest)?.let { runCatching { BridgeJson.decodeFromString(STATS, it) }.getOrNull() }
                    ?: opener.sectionStats(s.id)?.also { library.storeCache(id, STATS_KIND, s.cdDigest, BridgeJson.encodeToString(STATS, it)) }
                if (session !== s) return@launch
                s.stats = stats
                probe.ev("stats_ready", "${stats?.size}")
                onStats(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed("loading section stats", e, showIfOpen = false)
            }
            // The search index starts last, so its text pass never competes with the first page or the stats (5.1).
            try {
                if (session === s) search?.ensureIndex(id, s.cdDigest, s.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed("starting the search index", e, showIfOpen = false)
            }
        }
    }

    private fun onStats(s: Session) = totals.setStats(s.stats, s.sections)

    /** F-034 seek slider: jump to [fraction] of the book. */
    fun seek(fraction: Double) {
        if (session == null) return
        probe.ev("seek_cmd", "$fraction")
        reader.send(ToWeb.GoToFraction(fraction = fraction.coerceIn(0.0, 1.0)))
    }

    /** Releases a session that never became [session] (the open was cancelled); never throws. */
    private suspend fun release(sessionId: String) = withContext(NonCancellable) {
        try {
            opener.close(sessionId)
        } catch (e: Exception) {
            failed("open: releasing a cancelled session", e, showIfOpen = false)
        }
    }

    /** Back from the reader (R-005): returns immediately; the engine and the session close in the background. */
    fun close() {
        openJob?.cancel(); openJob = null
        val s = session
        session = null
        searcher.reset()
        _state.value = ReaderUiState()
        if (s == null) return
        reader.send(ToWeb.Close())
        s.bookId?.let { id -> runCatching { search?.bookClosed(id) } } // an unfinished index is finished later (idle job)
        closing = closeScope.launch {
            try {
                s.last?.let { positions.await().writeNow(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed("close: storing the last position", e, showIfOpen = false)
            }
            try {
                opener.close(s.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed("close: releasing the book", e, showIfOpen = false)
            }
        }
    }

    /**
     * Changes the display settings: clamped, applied at once on the page (live preview, F-075), stored 300 ms after the
     * last change (a slider drag writes once).
     */
    fun updateSettings(change: (ReaderSettings) -> ReaderSettings) {
        val before = _settings.value
        val next = change(before).clamped()
        if (next == before) return
        _settings.value = next
        totals.scope = next.pageIndicatorScope
        if (web(before) != web(next)) { probe.ev("layout_change", ""); reader.send(ToWeb.SetSettings(settings = web())) }
        saveJob?.cancel()
        saveJob = closeScope.launch {
            kotlinx.coroutines.delay(300)
            try { settingsStore?.put(ReaderSettings.KEY, next.encode()) } catch (e: CancellationException) { throw e } catch (e: Exception) { failed("storing settings", e, showIfOpen = false) }
        }
    }

    /** C-004: in AUTO flow the orientation decides between pages (portrait) and scrolling (landscape). */
    fun setLandscape(value: Boolean) {
        if (value == landscape) return
        val before = web()
        landscape = value
        if (web() != before) { probe.ev("layout_change", "flow"); reader.send(ToWeb.SetSettings(settings = web())) }
    }

    /** The settings as the web side applies them (AUTO flow resolved by orientation). */
    private fun web(s: ReaderSettings = _settings.value) = WebSettings(
        fontScale = s.fontScale, fontFamily = s.fontFamily, lineHeight = s.lineHeight, marginPercent = s.marginPercent,
        align = when (s.align) { Align.PUBLISHER -> "publisher"; Align.START -> "start"; Align.JUSTIFY -> "justify" },
        theme = s.theme.name.lowercase(),
        flow = when (s.flow) { Flow.PAGED -> "paginated"; Flow.SCROLLED -> "scrolled"; Flow.AUTO -> if (landscape) "scrolled" else "paginated" },
    )

    /** R-008: the book's file went away or was rewritten under the reader: close it safely and say so. */
    fun fileGone() {
        if (!_state.value.open) return
        close()
        _state.value = ReaderUiState(open = true, error = ReaderError.FileChanged)
    }

    /** Handles Back inside the reader (closes the TOC first). Returns false if Back should leave the reader. */
    fun backInside(): Boolean = when {
        _state.value.searchVisible -> { hideSearch(); true }
        _state.value.displayVisible -> { hideDisplay(); true }
        _state.value.tocVisible -> { hideToc(); true }
        else -> false
    }

    fun toggleChrome() = _state.update { it.copy(chromeVisible = !it.chromeVisible) }
    fun showToc() { probe.ev("toc_cmd", ""); _state.update { it.copy(tocVisible = true, chromeVisible = false) } }
    fun hideToc() = _state.update { it.copy(tocVisible = false) }
    fun showSearch() { probe.ev("search_open", ""); _state.update { it.copy(searchVisible = true, chromeVisible = false) }; searcher.loadHistory() }
    fun hideSearch() = _state.update { it.copy(searchVisible = false) }

    /** F-051 entry point: opens the search panel and runs [text] (the selected text, from M6). */
    fun searchFor(text: String) {
        if (session == null) return
        showSearch()
        searcher.submit(text)
    }
    fun showDisplay() { probe.ev("display_cmd", ""); _state.update { it.copy(displayVisible = true, chromeVisible = false) } }
    fun hideDisplay() = _state.update { it.copy(displayVisible = false) }
    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /** R-001: a missing target shows a notice and the page stays; otherwise jump (href is the reader's own). */
    fun onTocSelected(item: TocItem) {
        if (item.missing) { _state.update { it.copy(notice = Notice.MissingTarget) }; return }
        _state.update { it.copy(tocVisible = false) }
        reader.send(ToWeb.GoTo(target = item.href))
    }

    /** F-050: jump to a search hit ([p] in original coordinates) and highlight it until the next jump. */
    fun goToText(p: TextPos, length: Int, needle: String) {
        val s = session ?: return
        val v = s.map.textToVirtual(p)
        reader.send(ToWeb.GoToText(sectionIndex = v.spineIndex, offset = v.offset, length = length, needle = needle))
    }

    private suspend fun on(m: FromWeb) {
        val s = session ?: return
        when (m) {
            is FromWeb.Opened -> if (m.sessionId == s.id) {
                val identity = BookIdentity.of(m.identifier, s.cdDigest)
                val rec = library.recordOpened(OpenedBookInfo(identity, m.title, m.authors, s.ref, s.size))
                s.bookId = rec.bookId
                if (session !== s) return // closed (or another book opened) while recording
                searcher.bookKnown()
                // Same book reached through a new source (e.g. picked again): resume where it was left. Positions are
                // stored again once the jump has landed (`seeked`); the web side holds the jump until its open settled.
                if (!s.hadPosition) rec.position?.let {
                    val id = ++seeks
                    s.seek = id
                    reader.send(ToWeb.GoTo(target = s.map.cfiToVirtual(it.cfi), id = id))
                }
                s.sections = m.sections
                _state.update { it.copy(title = m.title, toc = m.toc) }
            }
            is FromWeb.Relocate -> if (m.sessionId == s.id) {
                probe.ev("relocate", m.cfi)
                if (s.seek == null) s.bookId?.let { id ->
                    val u = PositionWriter.Update(id, s.map.cfiToOriginal(m.cfi), m.fraction, m.sectionIndex)
                    s.last = u
                    positions.await().submit(u)
                }
                if (session === s) {
                    _state.update { it.copy(currentTocHref = m.tocHref, fraction = m.fraction) }
                    totals.onRelocate(m)
                }
            }
            is FromWeb.PageCounts -> if (m.sessionId == s.id) totals.onCounts(m)
            is FromWeb.Seeked -> if (m.sessionId == s.id && m.id == s.seek) { s.seek = null; probe.ev("seeked", "") }
            is FromWeb.Painted -> if (m.sessionId == s.id) {
                probe.ev("painted", "")
                if (!s.painted) { s.painted = true; probe.ev("painted_first", ""); _state.update { it.copy(loading = false) }; loadStats(s) }
            }
            is FromWeb.Tap -> if (m.sessionId == s.id) when {
                _state.value.displayVisible -> hideDisplay() // a tap on the page closes the panel
                m.x in 0.33..0.67 -> toggleChrome()
            }
            is FromWeb.Error -> if (m.sessionId == null || m.sessionId == s.id) {
                if (m.kind == HIT_NOT_FOUND) _state.update { it.copy(notice = Notice.HitNotFound) }
                else _state.update { it.copy(loading = false, error = ReaderError.Engine(m.kind, m.message)) }
            }
            else -> Unit
        }
    }
}

private const val STORAGE = "storage"
private const val HIT_NOT_FOUND = "hit_not_found"
private const val STATS_KIND = "section-stats.v1"
private val STATS = kotlinx.serialization.builtins.ListSerializer(SectionStat.serializer())
