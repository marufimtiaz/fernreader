package app.epubreader.library

import app.epubreader.core.data.CollectionEntity
import app.epubreader.core.data.LibraryBook
import app.epubreader.core.data.LibraryOps
import app.epubreader.core.data.SettingsPort
import app.epubreader.core.library.ScanState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** F-024 selection, F-026 collections, F-017 folder views, F-028 navigation in the library state. */
class LibraryViewsTest {
    private val root = "/storage/emulated/0"
    private fun book(id: String, title: String, dir: String, vararg collections: String) =
        LibraryBook(id, title, listOf("A"), "auto", null, 1, null, title.lowercase(), "a", "$root/$dir/$id.epub", listOf("$root/$dir/$id.epub"), collections.toList())

    /** In-memory [LibraryOps]: collections and memberships change the book flow like the database does. */
    class FakeOps(val books: MutableStateFlow<List<LibraryBook>>) : LibraryOps {
        val collections = MutableStateFlow(listOf<CollectionEntity>())
        private var n = 0
        override fun collections() = collections
        override suspend fun createCollection(name: String) = "c${++n}".also { id -> collections.update { it + CollectionEntity(id, name.trim(), 0, 0, false) } }
        override suspend fun renameCollection(id: String, name: String) = collections.update { l -> l.map { if (it.id == id) it.copy(name = name) else it } }
        override suspend fun deleteCollection(id: String) {
            collections.update { l -> l.filter { it.id != id } }
            books.update { l -> l.map { it.copy(collectionIds = it.collectionIds - id) } }
        }
        override suspend fun addToCollection(id: String, bookIds: List<String>) =
            books.update { l -> l.map { if (it.id in bookIds && id !in it.collectionIds) it.copy(collectionIds = it.collectionIds + id) else it } }
        override suspend fun removeFromCollection(id: String, bookIds: List<String>) =
            books.update { l -> l.map { if (it.id in bookIds) it.copy(collectionIds = it.collectionIds - id) else it } }
        val hidden = mutableListOf<String>()
        val deleted = mutableListOf<Pair<List<String>, Boolean>>()
        override suspend fun hide(bookIds: List<String>) { hidden += bookIds; books.update { l -> l.filter { it.id !in bookIds } } }
        override suspend fun deleteBooks(bookIds: List<String>, deleteFiles: Boolean): app.epubreader.core.data.DeleteResult {
            deleted += bookIds to deleteFiles; books.update { l -> l.filter { it.id !in bookIds } }; return app.epubreader.core.data.DeleteResult(if (deleteFiles) bookIds.size else 0, 0)
        }
    }

    private class FakeSettings : SettingsPort {
        val map = mutableMapOf<String, String>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(key: String, value: String) { map[key] = value }
    }

    private val all = MutableStateFlow(listOf(
        book("alice", "Alice", "Books/gutenberg"), book("frank", "Frankenstein", "Books/gutenberg"),
        book("moby", "Moby-Dick", "Download"), book("dup", "Dup", "Documents/Books"), book("old", "Old", "Download/Old"),
    ))
    private val ops = FakeOps(all)
    private fun TestScope.state(folderView: Boolean = true) = LibraryState(all, MutableStateFlow(emptyList()), MutableStateFlow(ScanState()), FakeSettings(), {},
        backgroundScope, compute = Dispatchers.Unconfined, ops = ops, folderView = MutableStateFlow(folderView)).also { runCurrent() }
    private fun LibraryState.shown() = ui.value.books.map { it.id }.toSet()

    @Test fun classics_collection_shows_exactly_its_two_books() = runTest {
        val s = state()
        s.createCollection("Classics"); runCurrent()
        val c = ui(s).collections.single()
        s.startSelection("alice"); s.toggleSelection("frank"); runCurrent()
        assertEquals(setOf("alice", "frank"), s.ui.value.selected)
        s.addSelectedTo(c.id); runCurrent()
        assertTrue(s.ui.value.selected.isEmpty())
        assertEquals(2, s.ui.value.collections.single().count)
        s.openView(LibraryView.Collection(c.id)); runCurrent()
        assertEquals(setOf("alice", "frank"), s.shown())
        assertEquals("Classics", s.ui.value.viewTitle)
    }

    private fun ui(s: LibraryState) = s.ui.value

    @Test fun tap_toggles_and_select_all_takes_the_visible_books() = runTest {
        val s = state()
        s.startSelection("alice"); s.toggleSelection("alice"); runCurrent()
        assertTrue(s.ui.value.selected.isEmpty()) // deselecting the last book leaves selection mode
        s.setQuery("o"); s.startSelection("moby"); s.selectAll(); runCurrent()
        assertEquals(s.shown(), s.ui.value.selected)
        assertEquals(setOf("moby", "old"), s.ui.value.selected)
    }

    @Test fun back_clears_selection_then_leaves_the_view_then_lets_the_app_go() = runTest {
        val s = state()
        s.openView(LibraryView.Folders); s.pushView(LibraryView.Folder("$root/Download")); s.startSelection("moby"); runCurrent()
        assertTrue(s.back()); runCurrent(); assertTrue(s.ui.value.selected.isEmpty()); assertEquals(LibraryView.Folder("$root/Download"), s.ui.value.view)
        assertTrue(s.back()); runCurrent(); assertEquals(LibraryView.Folders, s.ui.value.view)
        assertTrue(s.back()); runCurrent(); assertEquals(LibraryView.All, s.ui.value.view)
        assertFalse(s.back())
    }

    @Test fun drawer_closes_on_back_first() = runTest {
        val s = state()
        s.setDrawer(true); runCurrent()
        assertTrue(s.back()); runCurrent()
        assertFalse(s.ui.value.drawerOpen)
    }

    @Test fun folder_lists_books_directly_inside_and_subfolders_on_request() = runTest {
        val s = state()
        s.openView(LibraryView.Folder("$root/Download")); runCurrent()
        assertEquals(setOf("moby"), s.shown())
        s.setIncludeSubfolders(true); runCurrent()
        assertEquals(setOf("moby", "old"), s.shown())
    }

    @Test fun an_emptied_folder_view_reports_empty_with_the_view_kept() = runTest {
        val s = state()
        s.openView(LibraryView.Folder("$root/Download/Old")); runCurrent()
        all.update { l -> l.filter { it.id != "old" } }; runCurrent()
        assertTrue(s.ui.value.books.isEmpty()); assertEquals(0, s.ui.value.inView)
        assertEquals(LibraryView.Folder("$root/Download/Old"), s.ui.value.view) // the screen offers "All books"
    }

    @Test fun deleting_the_open_collection_returns_to_all_books() = runTest {
        val s = state()
        s.createCollection("Temp"); runCurrent()
        val c = s.ui.value.collections.single().id
        s.openView(LibraryView.Collection(c)); runCurrent()
        s.deleteCollection(c); runCurrent()
        assertEquals(LibraryView.All, s.ui.value.view)
        assertTrue(s.ui.value.collections.isEmpty())
    }

    @Test fun remove_selected_from_the_open_collection() = runTest {
        val s = state()
        s.createCollection("C"); runCurrent()
        val c = s.ui.value.collections.single().id
        ops.addToCollection(c, listOf("alice", "frank")); runCurrent()
        s.openView(LibraryView.Collection(c)); s.startSelection("alice"); s.removeSelectedFromCollection(); runCurrent()
        assertEquals(setOf("frank"), s.shown())
    }

    @Test fun folders_entry_follows_the_setting() = runTest {
        assertFalse(state(folderView = false).ui.value.folderViewEnabled)
        assertTrue(state(folderView = true).ui.value.folderViewEnabled)
    }

    @Test fun folder_tree_mirrors_directories_and_counts_books() = runTest {
        val s = state()
        s.openView(LibraryView.Folders); runCurrent()
        val rows = s.ui.value.folderTree.map { Triple(it.name, it.depth, it.total) }
        assertEquals(listOf(
            Triple("Books", 0, 2), Triple("gutenberg", 1, 2),
            Triple("Documents", 0, 1), Triple("Books", 1, 1),
            Triple("Download", 0, 2), Triple("Old", 1, 1),
        ), rows)
        // two folders named Books are told apart by their parent path
        assertEquals(listOf("", "Documents"), s.ui.value.folderTree.filter { it.name == "Books" }.map { it.parent })
    }

    /** C-074: the drawer lists the folders with books (collapsed below the top level) while the folder view is on. */
    @Test fun drawer_folders_follow_the_setting_expand_and_update_after_scans() = runTest {
        assertTrue(state(folderView = false).ui.value.drawerFolders.rows.isEmpty())
        val s = state()
        assertEquals(listOf("Books", "Documents", "Download"), s.ui.value.drawerFolders.rows.map { it.node.name })
        s.toggleDrawerFolder("$root/Documents"); runCurrent()
        assertEquals(listOf("Books", "Documents", "Books", "Download"), s.ui.value.drawerFolders.rows.map { it.node.name })
        assertEquals(listOf("Device storage", "Documents"), s.ui.value.drawerFolders.rows.filter { it.node.name == "Books" }.map { it.parentLine })
        // the full-screen tree keeps its own folding (all open)
        s.openView(LibraryView.Folders); runCurrent()
        assertEquals(6, s.ui.value.folderTree.size)
        // a folder that no longer holds books leaves the drawer
        all.update { l -> l.filter { it.id != "dup" } }; runCurrent()
        assertEquals(listOf("Books", "Download"), s.ui.value.drawerFolders.rows.map { it.node.name })
        s.toggleDrawerFolder("$root/Documents"); runCurrent() // folding again a folder that is gone is harmless
        assertEquals(listOf("Books", "Download"), s.ui.value.drawerFolders.rows.map { it.node.name })
    }

    @Test fun a_drawer_folder_opens_as_a_view_with_the_menu_button() = runTest {
        val s = state()
        s.setDrawer(true); s.openView(LibraryView.Folder("$root/Download")); runCurrent()
        assertFalse(s.ui.value.drawerOpen); assertFalse(s.ui.value.nested)
        assertEquals("Download", s.ui.value.viewTitle)
        assertEquals(setOf("moby"), s.shown())
    }

    // --- C-077 top bar -------------------------------------------------------------------------------------------

    @Test fun search_icon_opens_the_field_and_closing_clears_the_query() = runTest {
        val s = state()
        assertFalse(s.ui.value.searchOpen)
        s.openSearch(); s.setQuery("moby"); runCurrent()
        assertTrue(s.ui.value.searchOpen); assertEquals(setOf("moby"), s.shown())
        s.closeSearch(); runCurrent()
        assertFalse(s.ui.value.searchOpen); assertEquals("", s.ui.value.query); assertEquals(5, s.ui.value.books.size)
    }

    @Test fun back_ends_selection_then_closes_search_then_leaves_the_view() = runTest {
        val s = state()
        s.openView(LibraryView.Folder("$root/Download")); s.setIncludeSubfolders(true)
        s.openSearch(); s.setQuery("old"); s.startSelection("old"); runCurrent()
        assertTrue(s.back()); runCurrent()
        assertTrue(s.ui.value.selected.isEmpty()); assertTrue(s.ui.value.searchOpen); assertEquals("old", s.ui.value.query)
        assertTrue(s.back()); runCurrent()
        assertFalse(s.ui.value.searchOpen); assertEquals("", s.ui.value.query); assertEquals(setOf("moby", "old"), s.shown())
        assertEquals(LibraryView.Folder("$root/Download"), s.ui.value.view)
        assertTrue(s.back()); runCurrent(); assertEquals(LibraryView.All, s.ui.value.view)
        assertFalse(s.back())
    }

    @Test fun another_view_closes_the_search() = runTest {
        val s = state()
        s.openSearch(); s.setQuery("a"); s.openView(LibraryView.Folder("$root/Download")); runCurrent()
        assertFalse(s.ui.value.searchOpen); assertEquals("", s.ui.value.query)
    }

    @Test fun sort_menu_choice_orders_the_view_and_persists() = runTest {
        val settings = FakeSettings()
        val s = LibraryState(all, MutableStateFlow(emptyList()), MutableStateFlow(ScanState()), settings, {}, backgroundScope,
            compute = Dispatchers.Unconfined, ops = ops).also { runCurrent() }
        assertEquals(LibrarySort.RECENT, s.ui.value.sort)
        s.setSort(LibrarySort.TITLE); runCurrent()
        assertEquals(LibrarySort.TITLE, s.ui.value.sort)
        assertEquals(listOf("alice", "dup", "frank", "moby", "old"), s.ui.value.books.map { it.id })
        assertEquals(LibrarySort.TITLE.name, settings.map[app.epubreader.core.data.SettingsStore.LIBRARY_SORT])
    }

    @Test fun layout_icon_toggles_grid_and_list_and_persists() = runTest {
        val settings = FakeSettings()
        val s = LibraryState(all, MutableStateFlow(emptyList()), MutableStateFlow(ScanState()), settings, {}, backgroundScope,
            compute = Dispatchers.Unconfined, ops = ops).also { runCurrent() }
        assertEquals(LibraryLayout.GRID, s.ui.value.layout)
        s.toggleLayout(); runCurrent()
        assertEquals(LibraryLayout.LIST, s.ui.value.layout); assertEquals("LIST", settings.map[app.epubreader.core.data.SettingsStore.LIBRARY_LAYOUT])
        s.toggleLayout(); runCurrent()
        assertEquals(LibraryLayout.GRID, s.ui.value.layout)
    }

    @Test fun delete_selected_asks_ops_with_the_file_choice_and_reports() = runTest {
        val s = state()
        s.startSelection("moby"); s.toggleSelection("old"); s.deleteSelected(deleteFiles = false); runCurrent()
        assertEquals(listOf(listOf("moby", "old") to false), ops.deleted)
        assertTrue(s.ui.value.selected.isEmpty())
        assertEquals("Removed 2 books from the library", s.messages.receive())
        s.startSelection("alice"); s.deleteSelected(deleteFiles = true); runCurrent()
        assertEquals("Deleted 1 book and its file", s.messages.receive())
    }
}
