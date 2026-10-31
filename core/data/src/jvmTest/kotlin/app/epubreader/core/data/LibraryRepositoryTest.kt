package app.epubreader.core.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class LibraryRepositoryTest {
    private val db = testDb()
    private val repo = LibraryRepository(db, HybridClock(System::currentTimeMillis))
    private fun info(id: String, ref: SourceRef, title: String = "T") = OpenedBookInfo(id, title, listOf("A"), ref, 100)

    @Test fun first_open_creates_book_and_remembers_source() = runTest {
        val ref = SourceRef.Content("content://x/1")
        assertNull(repo.knownBookId(ref))
        val rec = repo.recordOpened(info("id-1", ref, "Alice"))
        assertEquals(rec.bookId, repo.knownBookId(ref))
        assertNull(rec.position)
        assertEquals(listOf("Alice"), repo.library().first().map { it.title })
    }

    @Test fun same_identity_from_another_source_is_the_same_book() = runTest {
        val a = repo.recordOpened(info("id-1", SourceRef.File("/sdcard/a.epub")))
        val b = repo.recordOpened(info("id-1", SourceRef.Content("content://x/2")))
        assertEquals(a.bookId, b.bookId)
        assertEquals(1, repo.library().first().size)
    }

    @Test fun file_path_prefers_a_scanned_plain_file_and_skips_content_uris() = runTest {
        val id = repo.recordOpened(info("id-1", SourceRef.Content("content://x/9"))).bookId
        assertNull(repo.filePath(id)) // only a content URI: nothing a background job can open
        repo.recordOpened(info("id-1", SourceRef.File("/sdcard/z-opened.epub")))
        assertEquals("/sdcard/z-opened.epub", repo.filePath(id))
        db.libraryDao().upsertFile(BookFileEntity("/sdcard/a-scanned.epub", id, "scan", "/sdcard", 100, 0, 1))
        assertEquals("/sdcard/a-scanned.epub", repo.filePath(id))
        assertNull(repo.filePath("unknown"))
    }

    @Test fun different_identity_is_a_new_book() = runTest {
        val a = repo.recordOpened(info("id-1", SourceRef.File("/a.epub")))
        val b = repo.recordOpened(info("id-2", SourceRef.File("/b.epub")))
        assertNotEquals(a.bookId, b.bookId)
    }

    @Test fun recent_is_newest_first_and_forget_removes() = runTest {
        repo.recordOpened(info("id-1", SourceRef.File("/a.epub"), "A"))
        repo.recordOpened(info("id-2", SourceRef.File("/b.epub"), "B"))
        assertEquals(setOf("A", "B"), repo.library().first().map { it.title }.toSet()) // ordering: LibraryState
        repo.forget(SourceRef.File("/b.epub"))
        assertEquals(listOf("A"), repo.library().first().map { it.title })
    }
}
