package app.epubreader.settings

import app.epubreader.core.data.BackupException
import app.epubreader.core.data.BackupFileV1
import app.epubreader.core.data.LibraryBook
import app.epubreader.core.data.RestoreResult
import app.epubreader.core.data.SettingsPort
import app.epubreader.core.data.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsStateTest {
    private val r = "/storage/emulated/0"
    private fun book(id: String, vararg paths: String) = LibraryBook(id, id, emptyList(), "auto", null, 1, null, id, "", paths.first(), paths.toList())

    private class FakeSettings : SettingsPort {
        val map = mutableMapOf<String, String>()
        override suspend fun get(key: String) = map[key]
        override suspend fun put(key: String, value: String) { map[key] = value }
    }

    private val books = MutableStateFlow(listOf(
        book("inside", "$r/Books/a.epub"), book("deep", "$r/Books/x/y/b.epub"), book("outside", "$r/Download/c.epub"),
        book("both", "$r/Download/d.epub", "$r/Books/d.epub"), book("picked", "content://p/1"), book("prefix", "$r/BooksOld/e.epub"),
    ))
    private val hidden = MutableStateFlow(listOf<LibraryBook>())
    private val store = FakeSettings()
    private var rescans = 0
    private val hiddenIds = mutableListOf<String>()

    private fun kotlinx.coroutines.test.TestScope.state() = SettingsState(
        store, books, hidden, hide = { ids -> hiddenIds += ids; books.update { l -> l.filter { it.id !in ids } } },
        unhide = { ids -> hidden.update { l -> l.filter { it.id !in ids } } }, rescan = { rescans++ }, scope = backgroundScope,
    ).also { runCurrent() }

    @Test fun choosing_a_folder_offers_to_hide_books_now_outside_it() = runTest {
        val s = state()
        assertNull(s.ui.value.folder) // everywhere
        s.chooseFolder("$r/Books"); runCurrent()
        assertEquals("$r/Books", store.map[SettingsStore.LIBRARY_SCOPE])
        assertEquals("$r/Books", s.ui.value.folder)
        assertEquals(1, rescans)
        val offer = assertNotNull(s.ui.value.outside)
        assertEquals(setOf("outside", "prefix"), offer.bookIds.toSet()) // a copy inside keeps "both"; content:// books are not scanned anyway
        s.hideOutside(); runCurrent()
        assertEquals(listOf("outside", "prefix"), hiddenIds.sorted()); assertNull(s.ui.value.outside)
    }

    @Test fun keeping_them_hides_nothing_and_everywhere_rescans() = runTest {
        val s = state()
        s.chooseFolder("$r/Books"); runCurrent()
        s.keepOutside(); runCurrent()
        assertTrue(hiddenIds.isEmpty()); assertNull(s.ui.value.outside)
        s.useEverywhere(); runCurrent()
        assertEquals("everywhere", store.map[SettingsStore.LIBRARY_SCOPE]); assertNull(s.ui.value.folder)
        assertEquals(2, rescans)
    }

    @Test fun unhide_from_the_hidden_list() = runTest {
        hidden.value = listOf(book("h", "$r/Download/h.epub"))
        val s = state()
        assertEquals(listOf("h"), s.ui.value.hidden.map { it.id })
        s.unhide("h"); runCurrent()
        assertTrue(s.ui.value.hidden.isEmpty())
    }

    @Test fun tree_document_ids_become_paths() {
        assertEquals("/storage/emulated/0/Books", FolderPaths.fromTreeDocumentId("primary:Books"))
        assertEquals("/storage/emulated/0", FolderPaths.fromTreeDocumentId("primary:"))
        assertEquals("/storage/emulated/0/Livres Été 2026/a b", FolderPaths.fromTreeDocumentId("primary:Livres Été 2026/a b"))
        assertEquals("/storage/1A2B-3C4D/Books", FolderPaths.fromTreeDocumentId("1A2B-3C4D:Books"))
        assertEquals("/storage/emulated/0/Documents", FolderPaths.fromTreeDocumentId("home:"))
        assertNull(FolderPaths.fromTreeDocumentId("msf:42")) // a provider that is not shared storage
    }
}

class BackupStateTest {
    private val file = BackupFileV1(createdAt = 1_793_400_000_000, books = emptyList())

    /** Final review I-3: the file is parsed once, off the main thread, and restored from the parsed form. */
    @Test fun parsing_happens_on_the_compute_dispatcher_once() = runTest {
        val compute = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler, "compute")
        var parses = 0; var restoredFrom: BackupFileV1? = null
        val s = BackupState(parse = { parses++; file }, restore = { f -> restoredFrom = f; RestoreResult(0, 0, 0, 0, 0) }, onRestored = {},
            scope = backgroundScope, compute = compute)
        s.offer("{}")
        assertEquals(0, parses) // not on the caller's (main) thread
        runCurrent()
        assertEquals(1, parses); assertNotNull(s.ui.value.offer)
        s.confirm(); runCurrent()
        assertEquals(1, parses); assertEquals(file, restoredFrom)
    }

    @Test fun offer_then_restore_reports_the_result_and_reloads() = runTest {
        var reloaded = 0
        val s = BackupState(parse = { file }, restore = { RestoreResult(3, 1, 3, 1, 2) }, onRestored = { reloaded++ }, scope = backgroundScope, compute = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        s.offer("{}"); runCurrent()
        assertNotNull(s.ui.value.offer)
        s.confirm(); runCurrent()
        assertNull(s.ui.value.offer)
        assertEquals(RestoreResult(3, 1, 3, 1, 2), s.ui.value.result)
        assertEquals(1, reloaded)
        s.dismiss(); assertNull(s.ui.value.result)
    }

    @Test fun a_file_that_is_not_a_backup_shows_why() = runTest {
        val s = BackupState(parse = { throw BackupException.NotABackup("x") }, restore = { error("not called") }, onRestored = {}, scope = backgroundScope, compute = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        s.offer("hello"); runCurrent()
        assertNull(s.ui.value.offer); assertEquals(BackupProblem.NOT_A_BACKUP, s.ui.value.problem)
        val t = BackupState(parse = { throw BackupException.TooNew(2) }, restore = { error("not called") }, onRestored = {}, scope = backgroundScope, compute = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        t.offer("{}"); runCurrent()
        assertEquals(BackupProblem.TOO_NEW, t.ui.value.problem)
    }

    @Test fun saving_reports_success_or_failure() = runTest {
        val s = BackupState(parse = { file }, restore = { error("") }, onRestored = {}, scope = backgroundScope)
        s.saved("epub-reader-backup-2026-10-31.json"); assertEquals("epub-reader-backup-2026-10-31.json", s.ui.value.savedAs)
        s.dismiss(); s.saveFailed(); assertEquals(BackupProblem.WRITE_FAILED, s.ui.value.problem)
    }
}
