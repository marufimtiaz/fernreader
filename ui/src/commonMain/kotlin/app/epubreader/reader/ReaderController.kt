package app.epubreader.reader

import app.epubreader.core.data.Annotation
import app.epubreader.core.data.AnnotationPort
import app.epubreader.core.data.AnnotationStyle
import app.epubreader.core.data.Bookmark
import app.epubreader.core.data.HighlightColors
import app.epubreader.core.data.BookmarkPort
import app.epubreader.core.data.LibraryRepository
import app.epubreader.core.data.OpenedBookInfo
import app.epubreader.core.data.PositionWriter
import app.epubreader.core.data.Align
import app.epubreader.core.data.Flow
import app.epubreader.core.data.PageNumbers
import app.epubreader.core.data.PageAnimation
import app.epubreader.core.data.SwipeDirection
import app.epubreader.core.data.ReaderSettings
import app.epubreader.core.data.SearchHistoryPort
import app.epubreader.core.data.SettingsPort
import app.epubreader.core.data.SourceRef
import app.epubreader.core.epub.PositionMap
import app.epubreader.core.epub.TextPos
import app.epubreader.core.epub.chunk.ChunkMap
import app.epubreader.core.epub.meta.BookIdentity
import app.epubreader.reader.bridge.BridgeJson
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.reader.bridge.ToWeb
import app.epubreader.reader.bridge.TocItem
import app.epubreader.reader.bridge.WebSettings
import app.epubreader.reader.bridge.WebAnnotation
import app.epubreader.reader.bridge.WebTexts
import app.epubreader.reader.search.ReaderSearch
import app.epubreader.reader.search.SearchBookRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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

enum class Notice { MissingTarget, HitNotFound, NoAppForLink, BookmarkAdded, BookmarkRemoved, HighlightRemoved }

data class ReaderUiState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val title: String = "",
    /** The book's authors from `opened` (C-073: the title dialog). */
    val authors: List<String> = emptyList(),
    val toc: List<TocItem> = emptyList(),
    val currentTocHref: String? = null,
    /** Book fraction of the current location (seek slider, F-034). */
    val fraction: Double = 0.0,
    val chromeVisible: Boolean = false,
    val tocVisible: Boolean = false,
    /** The Contents panel's tab (F-041, F-062); opening the panel always starts on Contents. */
    val contentsTab: ContentsTab = ContentsTab.Contents,
    /** F-041: the open book's bookmarks, in book order (live). */
    val bookmarks: List<Bookmark> = emptyList(),
    /** F-041: the visible page holds a bookmark: the marker is drawn and the bookmark button is filled. */
    val bookmarked: Boolean = false,
    val searchVisible: Boolean = false,
    val displayVisible: Boolean = false,
    val settingsVisible: Boolean = false,
    /** F-043: the footnote popup is shown (in the web page); Back closes it. */
    val footnoteOpen: Boolean = false,
    /** F-044: an external link waiting for the reader's confirmation (a dialog); Back cancels it. */
    val externalLink: ExternalLink? = null,
    /** F-060: the colour row for the annotation just made from the selection menu; Back or a tap on the page closes it. */
    val highlightPicker: HighlightPicker? = null,
    /** F-062: the open book's highlights and underlines, in book order (live; read once the first page is painted). */
    val annotations: List<Annotation> = emptyList(),
    /** F-061: the sheet of a tapped highlight (colour, style, note, remove); Back or a tap on the page closes it. */
    val annotationSheet: AnnotationSheet? = null,
    val notice: Notice? = null,
    /** C-083: book page labels of the bookmarks and highlights, by their stored (original) CFI; filled as they resolve. */
    val bookPageLabels: Map<String, String> = emptyMap(),
    /** C-083: the "Go to page" dialog is shown (Book pages only). */
    val goToPageVisible: Boolean = false,
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
    /** Opens external links in another app (F-044); null in tests that do not care (links are then dropped). */
    private val links: ExternalLinkOpener? = null,
    /** Bookmarks (F-041); null in tests that do not care (the bookmark button then does nothing). */
    private val bookmarks: BookmarkPort? = null,
    /** Highlights and underlines (F-060, F-061); null in tests that do not care (those menu items then do nothing). */
    private val annotations: AnnotationPort? = null,
    /** The selection menu's Copy and Share (F-060); null in tests that do not care. */
    private val textActions: TextActions? = null,
) {
    /**
     * [map] is the opened book's (M8: its ChunkMap when a huge document was split): every CFI, section index and text
     * position the web side sends is virtual and becomes original through it before it is stored or compared with
     * stored data; every stored one becomes virtual before it is sent.
     */
    private class Session(
        val id: String, val ref: SourceRef, val cdDigest: String, val size: Long, val hadPosition: Boolean,
        val map: PositionMap = PositionMap.Identity,
    ) {
        var bookId: String? = null
        var last: PositionWriter.Update? = null
        var painted = false
        var stats: List<SectionStat>? = null
        var sections = 0
        /** Section sizes from `opened`, for where a seek lands (C-073). */
        var sizes: List<Long> = emptyList()
        /** A slider seek was sent: its preview stays until the next `relocate` (where it landed). */
        var seekSent = false
        /** The book's page-progression-direction from `opened` ("ltr" | "rtl"). */
        var dir = "ltr"
        /**
         * Id of the resume jump sent after `opened` (same book, new source) until the web side answers `seeked`.
         * Relocates before that are where the book opened (its start), not where the reader is: not stored (R-009).
         */
        var seek: Int? = null
        /**
         * F-042: entries in the web side's followed-links history, as last reported (`history`) and lowered at once by
         * each Back sent, so Back decides synchronously (R-005) and repeated Backs never wait for the web side.
         */
        var linkDepth = 0
        /** `back` commands sent and not yet answered by a `history`. */
        var pendingBacks = 0
        /** F-041: the book's bookmarks as last read (book order), watched from `opened` until the session ends. */
        var bookmarkList: List<Bookmark> = emptyList()
        var bookmarkJob: Job? = null
        /** Virtual CFIs of the bookmarks on the visible page, as the web side reported them (edited at once by add/remove). */
        var marks: Set<String> = emptySet()
        /** The latest location, which a new bookmark takes. */
        var at: FromWeb.Relocate? = null
        /** An add/remove is being written: a second tap meanwhile is ignored (no duplicate bookmarks). */
        var bookmarkBusy = false
        /** F-060: the settled text selection as the web side last reported it (virtual CFI); null when there is none. */
        var selection: FromWeb.Selection? = null
        /** F-060: the annotation the colour row recolours (the one just made from the selection menu). */
        var picked: Annotation? = null
        /** F-062: the book's annotations as last read (book order), watched from the first `painted` until the session ends. */
        var annotationList: List<Annotation> = emptyList()
        var annotationJob: Job? = null
        /**
         * F-061: what the web side draws, by id (virtual CFIs), so each change is sent once: null until the first
         * `setAnnotations`; then every list change goes as a delta, and changes made here are not sent twice.
         */
        var drawn: MutableMap<String, WebAnnotation>? = null
        /** The annotation removed last, for Undo. */
        var removed: Annotation? = null
        /** C-083: list CFIs (original) asked of the web side for their book pages, and the open requests by id. */
        val pagesAsked = HashSet<String>()
        val pageRequests = HashMap<Int, List<String>>()
    }

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state
    private val _seekPreview = MutableStateFlow<SeekPreview?>(null)
    /** C-073: where the dragged slider would land (chapter and page); null when it is not dragged. */
    val seekPreview: StateFlow<SeekPreview?> = _seekPreview
    private var session: Session? = null
    private var openJob: Job? = null
    private var closing: Job? = null
    private var seeks = 0
    /** Text sizes of the open book's spine items (page estimates); null until loaded or if unreadable. */
    val sectionStats: List<SectionStat>? get() = session?.stats
    private val totals = PageTotals(probe)
    /** Page indicator: "page N", then "page N of ≈ M", then exact (F-034, C-016, C-017). */
    val page: StateFlow<PageIndicator> = totals.state
    private val bookNumbers = BookPageNumbers()
    /** C-083: the book page at the visible location (null page until the book's pages are known). */
    val bookPage: StateFlow<PageIndicator> = bookNumbers.state
    /** C-083: the book's pages could not be computed (an unreadable book): Book pages falls back to screen pages. */
    private val bookPagesMissing = MutableStateFlow(false)
    private var listRequests = 0
    /** Counted pages of a section in the current layout (diagnostics for the C-003 agreement test). */
    fun countedPages(index: Int): Int? = totals.counted(index)
    private val _settings = MutableStateFlow(ReaderSettings())
    /** Display settings (F-070..F-075), one value for every book. */
    val settings: StateFlow<ReaderSettings> = _settings
    /** C-083: what the indicator, the bar and the lists show: book pages or screen pages, as the Page numbers setting says. */
    val shownPage: StateFlow<PageIndicator> = combine(_settings, totals.state, bookNumbers.state, bookPagesMissing) { set, screen, book, missing ->
        if (set.pageNumbers == PageNumbers.BOOK_PAGES && !missing) book else screen
    }.stateIn(scope, SharingStarted.Eagerly, PageIndicator(null, null, false, false))
    private val bookPagesShown: Boolean get() = _settings.value.pageNumbers == PageNumbers.BOOK_PAGES && bookNumbers.pages != null
    /** F-050 search state for the open book (reset when another opens). */
    val searcher = ReaderSearch(
        scope, search, searchHistory, probe,
        book = { session?.let { s -> s.bookId?.let { SearchBookRef(it, s.cdDigest, s.id) } } },
        toc = { _state.value.toc },
        jump = { h -> goToText(TextPos(h.spineIndex, h.offset), h.length, h.text) },
        // Hits are in original sections, the contents' indexes in the reader's (virtual) ones.
        section = { h -> session?.map?.textToVirtual(TextPos(h.spineIndex, h.offset))?.spineIndex ?: h.spineIndex },
        hidePanel = ::hideSearch,
    )
    private var landscape = false
    private var saveJob: Job? = null
    /** Settings are read before the first open is sent, so a book never opens with the defaults first. */
    private val settingsLoaded: Deferred<Unit> = scope.async {
        try {
            settingsStore?.get(ReaderSettings.KEY)?.let { _settings.value = ReaderSettings.decode(it) }
            totals.scope = _settings.value.pageNumbers.screenScope
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
        bookNumbers.reset(); bookPagesMissing.value = false
        searcher.reset()
        _state.value = ReaderUiState(open = true, loading = true, source = ref); _seekPreview.value = null
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
                    val s = Session(r.sessionId, ref, r.cdDigest, r.size, hadPosition = saved != null, map = r.map).also { it.bookId = known }
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
                val kind = statsKind(s.map)
                var stats = library.cached(id, kind, s.cdDigest)?.let { runCatching { BridgeJson.decodeFromString(STATS, it) }.getOrNull() }
                // C-083: the book pages, stored with the book once computed; the computing pass also gives the section
                // stats of an unchunked book, so a first open reads the book once for both.
                val pages = try {
                    library.bookPages(id, s.cdDigest) ?: opener.bookPages(s.id, withStats = stats == null)?.let { r ->
                        if (stats == null && r.stats != null && s.map === PositionMap.Identity) {
                            stats = r.stats
                            library.storeCache(id, kind, s.cdDigest, BridgeJson.encodeToString(STATS, r.stats))
                        }
                        library.storeBookPages(id, s.cdDigest, r.pages)
                        r.pages
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed("loading book pages", e, showIfOpen = false); null
                }
                if (session !== s) return@launch
                bookNumbers.setPages(pages, s.map)
                bookPagesMissing.value = pages == null
                probe.ev("book_pages_ready", pages?.let { "${it.source} ${it.count}" } ?: "none")
                requestListPages(s)
                if (stats == null) stats = opener.sectionStats(s.id)?.also { library.storeCache(id, kind, s.cdDigest, BridgeJson.encodeToString(STATS, it)) }
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

    /** C-073: the slider is dragged to [fraction]: preview the chapter (contents entry) and page a release lands on. */
    fun previewSeek(fraction: Double) {
        val s = session ?: return
        val f = fraction.coerceIn(0.0, 1.0)
        val (section, inSection) = sectionAt(s.sizes, f) ?: run { _seekPreview.value = SeekPreview(f, null, PageIndicator(null, null, false, false)); return }
        val st = _state.value
        val entry = tocEntryAt(st.toc, section, inSection, st.currentTocHref.takeIf { s.at?.sectionIndex == section })
        val page = if (bookPagesShown) bookNumbers.previewAt(section, inSection) else totals.previewAt(section, inSection)
        _seekPreview.value = SeekPreview(f, entry?.label?.trim()?.takeIf { it.isNotEmpty() }, page)
    }

    /** C-073: the drag ended without a seek: the bar shows the current position again. */
    fun cancelSeek() { _seekPreview.value = null }

    /** F-034 seek slider: jump to [fraction] of the book. A preview (C-073) stays until the jump has landed. */
    fun seek(fraction: Double) {
        val s = session ?: return
        s.seekSent = _seekPreview.value != null
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
        s?.bookmarkJob?.cancel()
        s?.annotationJob?.cancel()
        s?.let(::saveNote) // a sheet open when the book closes keeps its note
        searcher.reset()
        bookNumbers.reset()
        _state.value = ReaderUiState(); _seekPreview.value = null
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
        totals.scope = next.pageNumbers.screenScope
        if (next.pageNumbers != before.pageNumbers) session?.let(::requestListPages)
        if (web(before) != web(next)) { bookNumbers.pinHere(); probe.ev("layout_change", ""); reader.send(ToWeb.SetSettings(settings = web())) }
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
        if (web() != before) { bookNumbers.pinHere(); probe.ev("layout_change", "flow"); reader.send(ToWeb.SetSettings(settings = web())) }
    }

    /** The settings as the web side applies them (AUTO flow resolved by orientation). */
    private fun web(s: ReaderSettings = _settings.value) = WebSettings(
        fontScale = s.fontScale, fontFamily = s.fontFamily, lineHeight = s.lineHeight, marginPercent = s.marginPercent,
        align = when (s.align) { Align.PUBLISHER -> "publisher"; Align.START -> "start"; Align.JUSTIFY -> "justify" },
        theme = s.theme.name.lowercase(),
        flow = when (s.flow) { Flow.PAGED -> "paginated"; Flow.SCROLLED -> "scrolled"; Flow.AUTO -> if (landscape) "scrolled" else "paginated" },
        swipeDirection = when (s.swipeDirection) { SwipeDirection.AUTO -> "auto"; SwipeDirection.LTR -> "ltr"; SwipeDirection.RTL -> "rtl" },
        pageAnimation = if (s.pageAnimation == PageAnimation.NONE) "none" else "slide",
    )

    /** One page turn through the web side's queue (same path as the bench's `next`/`prev`). */
    private fun turn(t: PageTurn) = reader.send(if (t == PageTurn.Next) ToWeb.Next() else ToWeb.Prev())

    /**
     * Volume keys (F-032, C-005), called by the activity for every volume key event on the reader route. Returns true
     * when the event is the reader's (consume it: no volume change, no volume UI): a book is open, no panel or sheet is
     * shown and the setting is not OFF. Only a [press] (key down, not an auto-repeat) turns a page: one page per press;
     * the release and the repeats are consumed too, so the volume never changes in between.
     */
    fun onVolumeKey(volumeDown: Boolean, press: Boolean): Boolean {
        val st = _state.value
        if (session == null || st.error != null || st.tocVisible || st.searchVisible || st.displayVisible || st.settingsVisible ||
            st.footnoteOpen || st.externalLink != null || st.annotationSheet != null) return false
        val t = volumeTurn(volumeDown, _settings.value.volumeKeys) ?: return false
        if (press) { probe.ev("volume_turn", t.name); turn(t) }
        return true
    }

    /** R-008: the book's file went away or was rewritten under the reader: close it safely and say so. */
    fun fileGone() {
        if (!_state.value.open) return
        close()
        _state.value = ReaderUiState(open = true, error = ReaderError.FileChanged); _seekPreview.value = null
    }

    /**
     * Handles Back inside the reader. Returns false if Back should leave the reader. Order: the external-link dialog,
     * a text selection, the footnote popup, panels and sheets, then the followed-links history (F-042: back to where the link was tapped),
     * then leave. Decided from state already here, never waiting for the web side (R-005).
     */
    fun backInside(): Boolean {
        val st = _state.value
        val s = session
        return when {
            st.externalLink != null -> { cancelExternalLink(); true }
            // C-073: Back during a slider drag cancels it: the controls close (the slider with them), nothing seeks.
            _seekPreview.value != null && s?.seekSent == false && st.chromeVisible -> { cancelSeek(); _state.update { it.copy(chromeVisible = false) }; true }
            // A selection first: Back never reaches the WebView (OnBackInvokedCallback), so it is dismissed here.
            s?.selection != null -> { s.selection = null; reader.send(ToWeb.ClearSelection()); true }
            st.highlightPicker != null -> { dismissHighlightPicker(); true }
            st.annotationSheet != null -> { closeAnnotationSheet(); true }
            st.footnoteOpen -> { _state.update { it.copy(footnoteOpen = false) }; reader.send(ToWeb.CloseFootnote()); true }
            st.searchVisible -> { hideSearch(); true }
            st.settingsVisible -> { hideSettings(); true }
            st.displayVisible -> { hideDisplay(); true }
            st.tocVisible -> { hideToc(); true }
            s != null && s.linkDepth > 0 -> { s.linkDepth--; s.pendingBacks++; probe.ev("link_back", "${s.linkDepth}"); reader.send(ToWeb.Back()); true }
            else -> false
        }
    }

    /** F-044 dialog: Open. */
    fun confirmExternalLink() {
        val l = _state.value.externalLink ?: return
        _state.update { it.copy(externalLink = null) }
        openExternal(l)
    }

    /** F-044 dialog: Cancel (or Back, or a tap outside). */
    fun cancelExternalLink() = _state.update { it.copy(externalLink = null) }

    private fun openExternal(l: ExternalLink) {
        probe.ev("external_open", l.label)
        val ok = try { links?.open(l.url) ?: false } catch (e: Exception) { failed("opening a link", e, showIfOpen = false); false }
        if (!ok) _state.update { it.copy(notice = Notice.NoAppForLink) }
    }

    fun toggleChrome() = _state.update { it.copy(chromeVisible = !it.chromeVisible) }
    fun showToc() { probe.ev("toc_cmd", ""); _state.update { it.copy(tocVisible = true, contentsTab = ContentsTab.Contents, chromeVisible = false) } }
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
    fun showSettings() { probe.ev("settings_cmd", ""); _state.update { it.copy(settingsVisible = true, chromeVisible = false) } }
    fun hideSettings() = _state.update { it.copy(settingsVisible = false) }

    /** Opens the Contents panel on a tab (the Highlights tab is filled by the annotations list). */
    fun selectContentsTab(tab: ContentsTab) = _state.update { it.copy(contentsTab = tab) }

    /**
     * F-041, the controls' bookmark button: a bookmark on the visible page is removed, otherwise the page is
     * bookmarked (the page's relocate CFI, a range from its first visible position, against the original documents;
     * a jump to it lands on that very page, which a bare point at a line break does not, see bridge-protocol.md.
     * Also the book fraction, section and current chapter label). Both show a
     * confirmation. Does nothing before the book is known and a location has arrived, or while the last tap is written.
     */
    fun toggleBookmark() {
        val s = session ?: return
        val port = bookmarks ?: return
        val bookId = s.bookId ?: return
        val at = s.at ?: return
        if (s.bookmarkBusy) return
        val here = s.bookmarkList.filter { s.map.cfiToVirtual(it.cfi) in s.marks }
        val st = _state.value
        val chapter = chapterTitle(st.toc, st.currentTocHref, st.title)
        s.bookmarkBusy = true
        scope.launch {
            try {
                if (here.isNotEmpty()) {
                    port.removeAll(here.map { it.id }) // one write (R-009)
                    if (session !== s) return@launch
                    s.bookmarkList = s.bookmarkList.filter { b -> here.none { it.id == b.id } }
                    s.marks = s.marks - here.map { s.map.cfiToVirtual(it.cfi) }.toSet()
                    publishBookmarks(s, Notice.BookmarkRemoved)
                } else {
                    val b = port.add(bookId, s.map.cfiToOriginal(at.cfi), s.map.textToOriginal(TextPos(at.sectionIndex, 0)).spineIndex, at.fraction, chapter)
                    if (session !== s) return@launch
                    s.bookmarkList = s.bookmarkList + b
                    s.marks = s.marks + s.map.cfiToVirtual(b.cfi)
                    publishBookmarks(s, Notice.BookmarkAdded)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed("toggling a bookmark", e, showIfOpen = false)
            } finally {
                s.bookmarkBusy = false
            }
        }
    }

    /** The list's remove button (F-041): removes that bookmark; the page marker follows if it was on this page. */
    fun removeBookmark(b: Bookmark) {
        val s = session ?: return
        val port = bookmarks ?: return
        scope.launch {
            try {
                port.remove(b.id)
                if (session !== s) return@launch
                s.bookmarkList = s.bookmarkList.filter { it.id != b.id }
                publishBookmarks(s, Notice.BookmarkRemoved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed("removing a bookmark", e, showIfOpen = false)
            }
        }
    }

    /** The list's row (F-041): jump to the bookmark and close the panel. */
    fun onBookmarkSelected(b: Bookmark) {
        val s = session ?: return
        _state.update { it.copy(tocVisible = false) }
        reader.send(ToWeb.GoTo(target = s.map.cfiToVirtual(b.cfi)))
    }

    /** Shows the session's list and page marker; [notice] is the confirmation of the change that caused it, if any. */
    private fun publishBookmarks(s: Session, notice: Notice? = null) {
        if (session !== s) return
        val on = s.bookmarkList.any { s.map.cfiToVirtual(it.cfi) in s.marks }
        _state.update { it.copy(bookmarks = s.bookmarkList, bookmarked = on, notice = notice ?: it.notice) }
        requestListPages(s)
    }

    /**
     * C-083: asks the web side where the bookmarks and highlights not yet known start (their book pages for the lists),
     * only while Book pages are shown. One request per change; each CFI is asked once per session.
     */
    private fun requestListPages(s: Session) {
        if (session !== s || !bookPagesShown) return
        val want = (s.bookmarkList.map { it.cfi } + s.annotationList.map { it.cfi }).distinct().filter { it !in s.pagesAsked }
        if (want.isEmpty()) return
        s.pagesAsked += want
        val id = ++listRequests
        s.pageRequests[id] = want
        reader.send(ToWeb.TextOffsets(sessionId = s.id, id = id, cfis = want.map(s.map::cfiToVirtual)))
    }

    /** C-083: the book page label of a search hit ([spineIndex], [offset] original), when Book pages are shown. */
    fun searchHitPage(spineIndex: Int, offset: Int): String? = if (bookPagesShown) bookNumbers.labelAt(TextPos(spineIndex, offset)) else null

    /** C-083: "Go to page" (the page label in the bottom bar), offered while Book pages are shown. */
    fun showGoToPage() { if (session != null && bookPagesShown) _state.update { it.copy(goToPageVisible = true) } }
    fun hideGoToPage() = _state.update { it.copy(goToPageVisible = false) }
    /** The first and the last book page label ("Go to page" shows the range). */
    fun bookPageRange(): Pair<String, String>? = bookNumbers.pages?.let { it.label(0) to it.label(it.count - 1) }

    /**
     * C-083 go to page: [input] is a page label as the book prints it ("xii", any case) or, for virtual pages, a
     * number. Jumps to that page's start and returns true; false (nothing happens) if the book has no such page.
     */
    fun goToPage(input: String): Boolean {
        val s = session ?: return false
        if (!bookPagesShown) return false
        val (index, at) = bookNumbers.goTo(input) ?: return false
        probe.ev("goto_page", "${index + 1}")
        _state.update { it.copy(goToPageVisible = false, chromeVisible = false) }
        reader.send(ToWeb.GoToOffset(sectionIndex = at.spineIndex, offset = at.offset))
        return true
    }

    /** Follows the book's bookmarks: every change goes to the web side (which reports the ones on the page) and to the state. */
    private fun watchBookmarks(s: Session, bookId: String) {
        val port = bookmarks ?: return
        s.bookmarkJob = scope.launch {
            try {
                port.bookmarks(bookId).collect { list ->
                    if (session !== s) return@collect
                    s.bookmarkList = list
                    reader.send(ToWeb.SetBookmarks(sessionId = s.id, cfis = list.map { s.map.cfiToVirtual(it.cfi) }))
                    publishBookmarks(s)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed("reading bookmarks", e, showIfOpen = false)
            }
        }
    }
    fun dismissNotice() = _state.update { it.copy(notice = null) }

    // ---- F-060 selection menu (the app's action mode calls these; each one ends the selection) ----

    /** The current selection, taken: the web side is told to remove it, so the next action needs a new one. */
    private fun takeSelection(s: Session): FromWeb.Selection? {
        val sel = s.selection?.takeIf { it.cfi != null } ?: return null
        s.selection = null
        reader.send(ToWeb.ClearSelection())
        return sel
    }

    /** Copy: exactly the selected text (Selection.toString(), whitespace as the browser gives it) as plain text. */
    fun copySelection() {
        val s = session ?: return
        val t = textActions ?: return
        val sel = takeSelection(s) ?: return
        probe.ev("selection_copy", "${sel.text.length}")
        try { t.copy(sel.text) } catch (e: Exception) { failed("copying the selection", e, showIfOpen = false) }
    }

    /** Share: the selected text to another app (the system share sheet). */
    fun shareSelection() {
        val s = session ?: return
        val t = textActions ?: return
        val sel = takeSelection(s) ?: return
        try { t.share(sel.text) } catch (e: Exception) { failed("sharing the selection", e, showIfOpen = false) }
    }

    /** F-051 Search in book: the search panel runs the selected text (trimmed, whitespace collapsed, capped). */
    fun searchSelection() {
        val s = session ?: return
        val sel = takeSelection(s) ?: return
        val q = selectionQuery(sel.text)
        if (q.isNotEmpty()) searchFor(q)
    }

    fun highlightSelection() = annotateSelection(AnnotationStyle.HIGHLIGHT)
    fun underlineSelection() = annotateSelection(AnnotationStyle.UNDERLINE)

    /**
     * Highlight/Underline: stored at once in the last-used colour (original CFI and section, current chapter label),
     * drawn on the page (virtual CFI), and the colour row opens to change the colour with one more tap.
     */
    private fun annotateSelection(style: String) {
        val s = session ?: return
        val port = annotations ?: return
        val bookId = s.bookId ?: return
        val sel = takeSelection(s) ?: return
        val cfi = sel.cfi ?: return
        val color = _settings.value.highlightColor
        val st = _state.value
        val chapter = chapterTitle(st.toc, st.currentTocHref, st.title)
        val spine = s.map.textToOriginal(TextPos(sel.sectionIndex, 0)).spineIndex
        scope.launch {
            try {
                val a = port.add(bookId, s.map.cfiToOriginal(cfi), spine, annotationText(sel.text), chapter, color, style)
                if (session !== s) return@launch
                probe.ev("annotation_added", style)
                s.picked = a
                drawAnnotation(s, a)
                _state.update { it.copy(highlightPicker = HighlightPicker(a.id, a.color), chromeVisible = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed("adding a highlight", e, showIfOpen = false)
            }
        }
    }

    private fun webAnnotation(s: Session, a: Annotation) = WebAnnotation(id = a.id, cfi = s.map.cfiToVirtual(a.cfi), color = a.color, style = a.style)

    /** Draws (or redraws) [a] on the page, unless the web side already draws exactly that. */
    private fun drawAnnotation(s: Session, a: Annotation) {
        val w = webAnnotation(s, a)
        if (s.drawn?.get(a.id) == w) return
        s.drawn?.set(a.id, w)
        reader.send(ToWeb.AddAnnotation(sessionId = s.id, id = w.id, cfi = w.cfi, color = w.color, style = w.style))
    }

    private fun eraseAnnotation(s: Session, id: String) {
        val drawn = s.drawn
        if (drawn != null && id !in drawn) return
        drawn?.remove(id)
        reader.send(ToWeb.RemoveAnnotation(sessionId = s.id, id = id))
    }

    /**
     * F-061/F-062: follows the book's annotations once its first page is on screen (never on the open path, P-010):
     * the first list goes to the web side whole (`setAnnotations`), every later one as the differences. The state's
     * list feeds the Highlights tab; an open sheet follows its annotation (and closes if it went away).
     */
    private fun watchAnnotations(s: Session) {
        val port = annotations ?: return
        val bookId = s.bookId ?: return
        if (!s.painted || s.annotationJob != null) return
        s.annotationJob = scope.launch {
            try {
                port.annotations(bookId).collect { list ->
                    if (session !== s) return@collect
                    s.annotationList = list
                    requestListPages(s)
                    val web = LinkedHashMap<String, WebAnnotation>().apply { for (a in list) put(a.id, webAnnotation(s, a)) }
                    val drawn = s.drawn
                    if (drawn == null) {
                        s.drawn = web
                        reader.send(ToWeb.SetAnnotations(sessionId = s.id, items = web.values.toList()))
                    } else {
                        for ((id, w) in web) if (drawn[id] != w) { drawn[id] = w; reader.send(ToWeb.AddAnnotation(sessionId = s.id, id = id, cfi = w.cfi, color = w.color, style = w.style)) }
                        for (id in drawn.keys - web.keys) { drawn.remove(id); reader.send(ToWeb.RemoveAnnotation(sessionId = s.id, id = id)) }
                    }
                    _state.update { st ->
                        val sheet = st.annotationSheet?.let { sh -> list.firstOrNull { it.id == sh.id }?.let { a -> sh.copy(color = a.color, style = a.style) } }
                        st.copy(annotations = list, annotationSheet = sheet)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed("reading highlights", e, showIfOpen = false)
            }
        }
    }

    /** F-061: a tap on a highlight on the page opens its sheet (another sheet open closes first, saving its note). */
    private fun openAnnotation(s: Session, id: String) {
        val a = s.annotationList.firstOrNull { it.id == id } ?: return
        saveNote(s)
        if (_state.value.highlightPicker != null) dismissHighlightPicker()
        probe.ev("annotation_sheet", a.style)
        _state.update { it.copy(annotationSheet = AnnotationSheet(a.id, a.text, a.color, a.style, a.note ?: ""), chromeVisible = false) }
    }

    /** The sheet's colour swatches: recoloured at once on the page and stored (the default colour stays as it is). */
    fun setAnnotationColor(color: String) {
        if (color !in HighlightColors.ALL) return
        changeSheet { it.copy(color = color) }
    }

    /** The sheet's Highlight | Underline toggle. */
    fun setAnnotationStyle(style: String) {
        if (style != AnnotationStyle.HIGHLIGHT && style != AnnotationStyle.UNDERLINE) return
        changeSheet { it.copy(style = style) }
    }

    private fun changeSheet(change: (AnnotationSheet) -> AnnotationSheet) {
        val s = session ?: return
        val port = annotations ?: return
        val sh = _state.value.annotationSheet ?: return
        val next = change(sh)
        if (next == sh) return
        val a = s.annotationList.firstOrNull { it.id == sh.id } ?: return
        _state.update { it.copy(annotationSheet = next) }
        val changed = a.copy(color = next.color, style = next.style)
        s.annotationList = s.annotationList.map { if (it.id == a.id) changed else it }
        drawAnnotation(s, changed)
        scope.launch {
            try {
                port.update(a.id, color = next.color.takeIf { it != sh.color }, style = next.style.takeIf { it != sh.style })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed("changing a highlight", e, showIfOpen = false)
            }
        }
    }

    /** The note field: the draft as typed (saved when the sheet closes). */
    fun editNote(text: String) = _state.update { st -> st.copy(annotationSheet = st.annotationSheet?.copy(note = text)) }

    /** Done, Back, a tap on the page: the sheet closes and its note is saved (trimmed; blank = no note). */
    fun closeAnnotationSheet() {
        session?.let(::saveNote)
        _state.update { it.copy(annotationSheet = null) }
    }

    /**
     * Stores the open sheet's draft note now, keeping the sheet open (R-009): the activity calls it when it stops, as the
     * process may be killed in the background without onDestroy.
     */
    fun flushNote() { session?.let(::saveNote) }

    /** Stores the open sheet's note if it changed; never throws. */
    private fun saveNote(s: Session) {
        val sh = _state.value.annotationSheet ?: return
        val port = annotations ?: return
        val a = s.annotationList.firstOrNull { it.id == sh.id } ?: return
        val note = noteToStore(sh.note)
        if (note == a.note) return
        s.annotationList = s.annotationList.map { if (it.id == a.id) it.copy(note = note) else it }
        closeScope.launch { // also when the reader closes meanwhile
            try { port.update(a.id, note = note ?: "") } catch (e: CancellationException) { throw e } catch (e: Exception) { failed("saving a note", e, showIfOpen = false) }
        }
    }

    /** The sheet's Remove, or the Highlights tab's remove button: erased at once, with Undo in the confirmation. */
    fun removeAnnotation(id: String) {
        val s = session ?: return
        val port = annotations ?: return
        val a = s.annotationList.firstOrNull { it.id == id } ?: return
        val sh = _state.value.annotationSheet
        s.annotationList = s.annotationList - a
        s.removed = if (sh?.id == id) a.copy(note = noteToStore(sh.note)) else a
        eraseAnnotation(s, id)
        _state.update { st -> st.copy(annotationSheet = st.annotationSheet?.takeIf { it.id != id }, annotations = st.annotations.filter { it.id != id }, notice = Notice.HighlightRemoved) }
        scope.launch {
            try { port.remove(id) } catch (e: CancellationException) { throw e } catch (e: Exception) { failed("removing a highlight", e, showIfOpen = false) }
        }
    }

    /** Undo in the removal confirmation: the same row comes back (same id, F-115) with its note, in one write (R-009). */
    fun undoRemoveAnnotation() {
        val s = session ?: return
        val port = annotations ?: return
        val a = s.removed ?: return
        s.removed = null
        _state.update { it.copy(notice = null) }
        scope.launch {
            try {
                val back = port.restore(a.id, a.note) ?: return@launch
                if (session !== s) return@launch
                drawAnnotation(s, back)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed("restoring a highlight", e, showIfOpen = false)
            }
        }
    }

    /** A row of the Highlights tab (F-062): jump to the page holding its start and close the panel. */
    fun onAnnotationSelected(a: Annotation) {
        val s = session ?: return
        _state.update { it.copy(tocVisible = false) }
        reader.send(ToWeb.GoTo(target = s.map.cfiToVirtual(a.cfi)))
    }

    /** The colour row: the new annotation takes [color], which becomes the default for the next one. */
    fun recolorHighlight(color: String) {
        val s = session ?: return
        val port = annotations ?: return
        val a = s.picked ?: return
        if (color !in HighlightColors.ALL) return
        dismissHighlightPicker()
        updateSettings { it.copy(highlightColor = color) }
        if (color == a.color) return
        scope.launch {
            try {
                port.update(a.id, color = color)
                if (session !== s) return@launch
                val changed = a.copy(color = color)
                drawAnnotation(s, changed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed("changing a highlight colour", e, showIfOpen = false)
            }
        }
    }

    fun dismissHighlightPicker() {
        session?.picked = null
        _state.update { it.copy(highlightPicker = null) }
    }

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
                s.sizes = m.sizes
                s.dir = m.dir
                _state.update { it.copy(title = m.title, authors = m.authors, toc = m.toc) }
                watchBookmarks(s, rec.bookId)
                watchAnnotations(s) // only if the first page was already painted
            }
            is FromWeb.Relocate -> if (m.sessionId == s.id) {
                probe.ev("relocate", m.cfi)
                if (s.seek == null) s.bookId?.let { id ->
                    val u = PositionWriter.Update(id, s.map.cfiToOriginal(m.cfi), m.fraction, s.map.textToOriginal(TextPos(m.sectionIndex, 0)).spineIndex)
                    s.last = u
                    positions.await().submit(u)
                }
                if (session === s) {
                    s.at = m
                    s.marks = m.bookmarks?.toSet() ?: emptySet()
                    _state.update { it.copy(currentTocHref = m.tocHref, fraction = m.fraction) }
                    publishBookmarks(s)
                    totals.onRelocate(m)
                    bookNumbers.onRelocate(m)
                    if (s.seekSent) { s.seekSent = false; _seekPreview.value = null }
                }
            }
            is FromWeb.PageBookmarks -> if (m.sessionId == s.id) { s.marks = m.cfis.toSet(); publishBookmarks(s) }
            is FromWeb.PageCounts -> if (m.sessionId == s.id) totals.onCounts(m)
            is FromWeb.TextOffsets -> if (m.sessionId == s.id) {
                val cfis = s.pageRequests.remove(m.id) ?: return
                val labels = HashMap<String, String>()
                for ((cfi, o) in cfis.zip(m.offsets)) {
                    if (o.size != 2 || o[0] < 0) continue
                    bookNumbers.labelAt(s.map.textToOriginal(TextPos(o[0], o[1])))?.let { labels[cfi] = it }
                }
                if (labels.isNotEmpty()) _state.update { it.copy(bookPageLabels = it.bookPageLabels + labels) }
            }
            is FromWeb.Seeked -> if (m.sessionId == s.id && m.id == s.seek) { s.seek = null; probe.ev("seeked", "") }
            is FromWeb.Painted -> if (m.sessionId == s.id) {
                probe.ev("painted", "")
                if (!s.painted) { s.painted = true; probe.ev("painted_first", ""); _state.update { it.copy(loading = false) }; loadStats(s); watchAnnotations(s) }
            }
            is FromWeb.Tap -> if (m.sessionId == s.id) {
                val set = _settings.value
                val action = tapAction(m.x, set.tapZones, effectiveRtl(set.swipeDirection, s.dir))
                when {
                    _state.value.highlightPicker != null -> dismissHighlightPicker() // a tap on the page closes the colour row
                    _state.value.annotationSheet != null -> closeAnnotationSheet()
                    _state.value.settingsVisible -> hideSettings() // a tap on the page closes the sheet or panel
                    _state.value.displayVisible -> hideDisplay()
                    action == TapAction.Controls -> toggleChrome()
                    // F-032 tap zones; while the controls are shown an edge tap hides them instead of turning
                    action == TapAction.Next || action == TapAction.Prev ->
                        if (_state.value.chromeVisible) toggleChrome() else turn(if (action == TapAction.Next) PageTurn.Next else PageTurn.Prev)
                    else -> Unit
                }
            }
            is FromWeb.History -> if (m.sessionId == s.id) {
                // The web side answers every `back` with one `history`; Backs not answered yet will still pop entries.
                if (s.pendingBacks > 0) s.pendingBacks--
                s.linkDepth = (m.depth - s.pendingBacks).coerceAtLeast(0)
            }
            is FromWeb.Footnote -> if (m.sessionId == s.id) {
                _state.update { it.copy(footnoteOpen = m.open, chromeVisible = if (m.open) false else it.chromeVisible) }
            }
            is FromWeb.Selection -> if (m.sessionId == s.id) {
                s.selection = m.takeIf { it.cfi != null }
                if (s.selection != null && _state.value.highlightPicker != null) dismissHighlightPicker()
            }
            is FromWeb.AnnotationTapped -> if (m.sessionId == s.id) openAnnotation(s, m.id)
            is FromWeb.ExternalLink -> if (m.sessionId == s.id) {
                val l = externalLinkTarget(m.href) ?: return
                if (_settings.value.confirmExternalLinks) _state.update { it.copy(externalLink = l) } else openExternal(l)
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

/**
 * The `book_cache` kind of the section stats (M4) for the layout the reader shows: per spine item, or for a chunked
 * book (M8) per virtual section under that chunk policy, so an unchunked and a chunked layout never share a row.
 */
internal fun statsKind(map: PositionMap): String = (map as? ChunkMap)?.takeUnless { it.isIdentity }?.let { "$STATS_KIND@${it.policyKey}" } ?: STATS_KIND
private val STATS = kotlinx.serialization.builtins.ListSerializer(SectionStat.serializer())
