package app.epubreader.library

import app.epubreader.core.data.CollectionEntity
import app.epubreader.core.data.LibraryBook
import app.epubreader.core.data.LibraryOps
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import app.epubreader.ui.Copy

enum class LibraryLayout { GRID, LIST }
enum class LibrarySort { RECENT, ADDED, TITLE, AUTHOR }

/** What the library screen shows (F-028): all books, one collection, the folder tree, or one folder. */
sealed interface LibraryView {
    data object All : LibraryView
    data class Collection(val id: String) : LibraryView
    data object Folders : LibraryView
    data class Folder(val path: String) : LibraryView
}

/** A collection in the navigation drawer, with the number of library books in it. */
data class CollectionSummary(val id: String, val name: String, val count: Int)

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
    val view: LibraryView = LibraryView.All,
    /** The view's name: a collection's name or a folder's; null for all books and the folder list. */
    val viewTitle: String? = null,
    /** Books in the view before the search filter (the view's empty state when 0). */
    val inView: Int = 0,
    val collections: List<CollectionSummary> = emptyList(),
    /** F-024 selection mode while not empty (book ids). */
    val selected: Set<String> = emptySet(),
    val drawerOpen: Boolean = false,
    /** The view was reached from another view (a folder from the folder list): the screen shows a way up. */
    val nested: Boolean = false,
    val folderViewEnabled: Boolean = true,
    val folderTree: List<FolderNode> = emptyList(),
    /** C-074: the drawer's folder rows (empty while the folder view is off). */
    val drawerFolders: DrawerFolders = DrawerFolders(),
    val includeSubfolders: Boolean = false,
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
    /** Where search and sort run (R-005: not the main thread). */
    compute: CoroutineDispatcher = Dispatchers.Default,
    private val ops: LibraryOps? = null,
    /** F-017: whether the folder view is on (settings; on by default, C-074). */
    folderView: Flow<Boolean> = flowOf(true),
) {
    private data class Prefs(val query: String = "", val layout: LibraryLayout = LibraryLayout.GRID, val sort: LibrarySort = LibrarySort.RECENT,
                             val dismissed: ScanEvent.Complete? = null, val missing: LibraryBook? = null, val loaded: Boolean = false,
                             val views: List<LibraryView> = listOf(LibraryView.All), val selected: Set<String> = emptySet(),
                             val drawerOpen: Boolean = false, val includeSubfolders: Boolean = false, val collapsed: Set<String> = emptySet(),
                             val drawerExpanded: Set<String> = emptySet())
    private val prefs = MutableStateFlow(Prefs())
    /** Every library book, unfiltered (pickers and the delete dialog's file count). */
    val allBooks: Flow<List<LibraryBook>> = books
    private val collectionsFlow: Flow<List<CollectionEntity>> = ops?.collections() ?: flowOf(emptyList())

    val ui: StateFlow<LibraryUi> = combine(
        books, failures, scan, prefs, combine(collectionsFlow, folderView) { c, f -> c to f },
    ) { all, fails, s, p, (cols, folderOn) ->
        val view = p.views.last()
        val inView = inView(all, view, p.includeSubfolders)
        val shown = arrange(inView, p.query, p.sort)
        val visible = all.mapTo(HashSet()) { it.id }
        val counts = if (folderOn || view == LibraryView.Folders) folderCounts(all) else emptyMap()
        LibraryUi(
            books = shown, total = all.size, query = p.query, layout = p.layout, sort = p.sort, scan = s,
            failures = fails,
            failureNoticeVisible = fails.isNotEmpty() && !s.running && s.lastComplete != null && s.lastComplete !== p.dismissed,
            loaded = p.loaded, missing = p.missing, // shown once the saved layout/sort are known (no flash)
            view = view,
            viewTitle = when (view) {
                is LibraryView.Collection -> cols.firstOrNull { it.id == view.id }?.name
                is LibraryView.Folder -> view.path.trimEnd('/').substringAfterLast('/')
                else -> null
            },
            inView = inView.size,
            collections = cols.map { c -> CollectionSummary(c.id, c.name, all.count { c.id in it.collectionIds }) },
            selected = p.selected.filterTo(LinkedHashSet()) { it in visible },
            drawerOpen = p.drawerOpen, nested = p.views.size > 2, folderViewEnabled = folderOn, includeSubfolders = p.includeSubfolders,
            folderTree = if (view == LibraryView.Folders) FolderTree.build(counts, p.collapsed) else emptyList(),
            drawerFolders = if (folderOn) DrawerFolders.build(counts, p.drawerExpanded) else DrawerFolders(),
        )
    }.flowOn(compute).stateIn(scope, SharingStarted.Eagerly, LibraryUi()) // R-005: sorting off the main thread

    /** True once the screen has composed a non-empty library (the app waits for it before heavy main-thread work). */
    val shown = MutableStateFlow(false)

    init { reloadPrefs() }

    /** Reads the saved layout and sort (at start, and after a restore). */
    fun reloadPrefs() {
        scope.launch {
            val layout = settings.get(SettingsStore.LIBRARY_LAYOUT)?.let { v -> LibraryLayout.entries.firstOrNull { it.name == v } }
            val sort = settings.get(SettingsStore.LIBRARY_SORT)?.let { v -> LibrarySort.entries.firstOrNull { it.name == v } }
            prefs.value = prefs.value.copy(layout = layout ?: prefs.value.layout, sort = sort ?: prefs.value.sort, loaded = true)
        }
    }

    private fun edit(f: (Prefs) -> Prefs) { prefs.value = f(prefs.value) }

    // --- F-028 navigation: a small stack of views inside the library route -------------------------------------

    /** From the drawer: [v] on top of all books. */
    fun openView(v: LibraryView) = edit { it.copy(views = if (v == LibraryView.All) listOf(v) else listOf(LibraryView.All, v), selected = emptySet(), drawerOpen = false, query = "") }
    /** Deeper (a folder from the folder list). */
    fun pushView(v: LibraryView) = edit { it.copy(views = it.views + v, selected = emptySet(), query = "") }
    fun setDrawer(open: Boolean) = edit { it.copy(drawerOpen = open) }
    fun setIncludeSubfolders(on: Boolean) = edit { it.copy(includeSubfolders = on) }
    fun toggleFolder(path: String) = edit { it.copy(collapsed = if (path in it.collapsed) it.collapsed - path else it.collapsed + path) }
    /** C-074: folds or unfolds a folder's subfolders in the drawer (they start folded). */
    fun toggleDrawerFolder(path: String) = edit { it.copy(drawerExpanded = if (path in it.drawerExpanded) it.drawerExpanded - path else it.drawerExpanded + path) }
    private val _messages = Channel<String>(Channel.BUFFERED)
    /** Short confirmations ("Added 3 books to “Classics”"), each shown once by the screen's snackbar. */
    val messages: ReceiveChannel<String> = _messages
    private fun say(m: String) { _messages.trySend(m) }

    /** System Back inside the library: closes the drawer, ends selection, leaves a view. False: nothing left to undo. */
    fun back(): Boolean {
        val p = prefs.value
        when {
            p.drawerOpen -> edit { it.copy(drawerOpen = false) }
            p.selected.isNotEmpty() -> edit { it.copy(selected = emptySet()) }
            p.views.size > 1 -> edit { it.copy(views = it.views.dropLast(1), query = "") }
            else -> return false
        }
        return true
    }

    // --- F-024 selection -----------------------------------------------------------------------------------------

    fun startSelection(id: String) = edit { it.copy(selected = it.selected + id) }
    fun toggleSelection(id: String) = edit { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) }
    fun selectAll() = edit { p -> p.copy(selected = p.selected + ui.value.books.map { it.id }) }
    fun clearSelection() = edit { it.copy(selected = emptySet()) }
    private fun takeSelection(): List<String> = ui.value.selected.toList().also { clearSelection() }

    // --- F-026 collections -------------------------------------------------------------------------------------

    fun createCollection(name: String, thenAddSelected: Boolean = false) {
        val ids = if (thenAddSelected) takeSelection() else emptyList()
        scope.launch {
            val ops = ops ?: return@launch
            val id = ops.createCollection(name)
            if (ids.isNotEmpty()) { ops.addToCollection(id, ids); say(Copy.added(ids.size, name.trim())) }
        }
    }
    fun renameCollection(id: String, name: String) { scope.launch { ops?.renameCollection(id, name) } }
    fun deleteCollection(id: String) {
        edit { p -> p.copy(views = p.views.filter { it != LibraryView.Collection(id) }.ifEmpty { listOf(LibraryView.All) }) }
        scope.launch { ops?.deleteCollection(id) }
    }
    fun addSelectedTo(collectionId: String) {
        val ids = takeSelection()
        val name = ui.value.collections.firstOrNull { it.id == collectionId }?.name ?: ""
        scope.launch { ops?.addToCollection(collectionId, ids); say(Copy.added(ids.size, name)) }
    }
    /** F-026 "add books from a picker inside the collection". */
    fun addToCurrentCollection(bookIds: List<String>) {
        val v = prefs.value.views.last() as? LibraryView.Collection ?: return
        scope.launch { ops?.addToCollection(v.id, bookIds) }
    }
    fun removeSelectedFromCollection() {
        val v = prefs.value.views.last() as? LibraryView.Collection ?: return
        val ids = takeSelection()
        scope.launch { ops?.removeFromCollection(v.id, ids) }
    }

    // --- F-025 delete ------------------------------------------------------------------------------------------

    /** Library only ([deleteFiles] false): the books are hidden. With files: every local copy is deleted. */
    fun deleteSelected(deleteFiles: Boolean) {
        val ids = takeSelection()
        if (ids.isEmpty()) return
        scope.launch {
            val r = ops?.deleteBooks(ids, deleteFiles) ?: return@launch
            say(Copy.deletedMessage(ids.size, deleteFiles, r.failed))
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
        /** The books a view holds (before search and sort). */
        fun inView(all: List<LibraryBook>, view: LibraryView, includeSubfolders: Boolean): List<LibraryBook> = when (view) {
            LibraryView.All -> all
            is LibraryView.Collection -> all.filter { view.id in it.collectionIds }
            LibraryView.Folders -> emptyList()
            is LibraryView.Folder -> {
                val dir = view.path.trimEnd('/')
                all.filter { b -> b.folders.any { it == dir || (includeSubfolders && it.startsWith("$dir/")) } }
            }
        }

        /** Books per folder that directly holds one of their files (a duplicate counts in each of its folders). */
        fun folderCounts(all: List<LibraryBook>): Map<String, Int> {
            val m = HashMap<String, Int>()
            for (b in all) for (f in b.folders) m[f] = (m[f] ?: 0) + 1
            return m
        }

        /** F-023 search (case-insensitive substring over title and authors), then F-022 sort. */
        fun arrange(books: List<LibraryBook>, query: String, sort: LibrarySort): List<LibraryBook> {
            val q = query.trim()
            val hits = if (q.isEmpty()) books else books.filter { b -> b.title.contains(q, ignoreCase = true) || b.authors.any { it.contains(q, ignoreCase = true) } }
            val byTitle = compareBy<LibraryBook> { it.sortTitle }.thenBy { it.id } // sort keys are stored lowercase
            return hits.sortedWith(
                when (sort) {
                    // C-076: recency is the later of last opened and added, so a newly found book starts on top (in every view)
                    LibrarySort.RECENT -> compareByDescending<LibraryBook> { maxOf(it.lastOpenedAt ?: Long.MIN_VALUE, it.addedAt) }.thenByDescending { it.addedAt }.then(byTitle)
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
