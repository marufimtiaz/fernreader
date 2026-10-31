package app.epubreader.library

import app.epubreader.core.data.LibraryBook
import app.epubreader.core.data.ScanFailureEntity
import app.epubreader.core.data.SettingsPort
import app.epubreader.core.data.SettingsStore
import app.epubreader.core.library.ScanEvent
import app.epubreader.core.library.ScanState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LibraryLayout { GRID, LIST }
enum class LibrarySort { RECENT, ADDED, TITLE, AUTHOR }

data class LibraryUi(
    val books: List<LibraryBook> = emptyList(),
    val total: Int = 0,
    val query: String = "",
    val layout: LibraryLayout = LibraryLayout.GRID,
    val sort: LibrarySort = LibrarySort.RECENT,
    val scan: ScanState = ScanState(),
    val failures: List<ScanFailureEntity> = emptyList(),
    val failureNoticeVisible: Boolean = false,
    val loaded: Boolean = false,
    /** F-018: the entry the user tapped whose file is gone (dialog: remove or rescan). */
    val missing: LibraryBook? = null,
)

/**
 * The library screen's state (F-020, F-022, F-023, F-016). [books] is every library entry; the UI gets them
 * filtered by the search query and sorted. Layout and sort are persisted in [settings].
 */
class LibraryState(
    books: Flow<List<LibraryBook>>,
    failures: Flow<List<ScanFailureEntity>>,
    scan: StateFlow<ScanState>,
    private val settings: SettingsPort,
    private val remove: suspend (String) -> Unit,
    private val scope: CoroutineScope,
    /** Whether a local file exists (one stat, off the main thread); content:// sources are not checked here. */
    private val exists: suspend (String) -> Boolean = { true },
) {
    private data class Prefs(val query: String = "", val layout: LibraryLayout = LibraryLayout.GRID, val sort: LibrarySort = LibrarySort.RECENT,
                             val dismissed: ScanEvent.Complete? = null, val missing: LibraryBook? = null)
    private val prefs = MutableStateFlow(Prefs())

    val ui: StateFlow<LibraryUi> = combine(books, failures, scan, prefs) { all, fails, s, p ->
        LibraryUi(
            books = arrange(all, p.query, p.sort), total = all.size, query = p.query, layout = p.layout, sort = p.sort, scan = s,
            failures = fails,
            failureNoticeVisible = fails.isNotEmpty() && !s.running && s.lastComplete != null && s.lastComplete !== p.dismissed,
            loaded = true, missing = p.missing,
        )
    }.stateIn(scope, SharingStarted.Eagerly, LibraryUi())

    init {
        scope.launch {
            val layout = settings.get(SettingsStore.LIBRARY_LAYOUT)?.let { v -> LibraryLayout.entries.firstOrNull { it.name == v } }
            val sort = settings.get(SettingsStore.LIBRARY_SORT)?.let { v -> LibrarySort.entries.firstOrNull { it.name == v } }
            prefs.value = prefs.value.copy(layout = layout ?: prefs.value.layout, sort = sort ?: prefs.value.sort)
        }
    }

    fun setQuery(q: String) { prefs.value = prefs.value.copy(query = q) }
    fun setLayout(l: LibraryLayout) { prefs.value = prefs.value.copy(layout = l); scope.launch { settings.put(SettingsStore.LIBRARY_LAYOUT, l.name) } }
    fun setSort(s: LibrarySort) { prefs.value = prefs.value.copy(sort = s); scope.launch { settings.put(SettingsStore.LIBRARY_SORT, s.name) } }
    fun dismissFailures() { prefs.value = prefs.value.copy(dismissed = ui.value.scan.lastComplete) }
    fun remove(bookId: String) { scope.launch { remove.invoke(bookId) } }

    /**
     * F-018: opens the first of the book's files that still exists (a duplicate copy may remain); if none does, shows
     * the missing-file dialog instead of a blank reader.
     */
    fun open(book: LibraryBook, onOpen: (path: String) -> Unit) {
        scope.launch {
            val path = (listOf(book.path) + book.paths).distinct().firstOrNull { !it.startsWith("/") || exists(it) }
            if (path != null) onOpen(path) else prefs.value = prefs.value.copy(missing = book)
        }
    }
    fun dismissMissing() { prefs.value = prefs.value.copy(missing = null) }
    fun removeMissing() { prefs.value.missing?.let { remove(it.id) }; dismissMissing() }

    companion object {
        /** F-023 search (case-insensitive substring over title and authors), then F-022 sort. */
        fun arrange(books: List<LibraryBook>, query: String, sort: LibrarySort): List<LibraryBook> {
            val q = query.trim()
            val hits = if (q.isEmpty()) books else books.filter { b -> b.title.contains(q, ignoreCase = true) || b.authors.any { it.contains(q, ignoreCase = true) } }
            val byTitle = compareBy<LibraryBook> { it.sortTitle.lowercase() }.thenBy { it.id }
            return hits.sortedWith(
                when (sort) {
                    LibrarySort.RECENT -> compareByDescending<LibraryBook> { it.lastOpenedAt ?: Long.MIN_VALUE }.thenByDescending { it.addedAt }.then(byTitle)
                    LibrarySort.ADDED -> compareByDescending<LibraryBook> { it.addedAt }.then(byTitle)
                    LibrarySort.TITLE -> byTitle
                    LibrarySort.AUTHOR -> compareBy<LibraryBook> { it.sortAuthor.ifEmpty { "￿" } }.then(byTitle)
                },
            )
        }
    }
}

/** Case-insensitive, non-overlapping ranges of [query] in [text] (for match emphasis). */
fun matchRanges(text: String, query: String): List<IntRange> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    val out = ArrayList<IntRange>()
    var i = text.indexOf(q, ignoreCase = true)
    while (i >= 0) { out += i until i + q.length; i = text.indexOf(q, i + q.length, ignoreCase = true) }
    return out
}
