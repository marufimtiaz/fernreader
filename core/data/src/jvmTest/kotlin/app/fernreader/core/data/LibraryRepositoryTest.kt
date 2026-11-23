package app.fernreader.core.data

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

    /** M8 review M2: a forgotten source and dropped open-with sources take their chunk caches with them. */
    @Test fun forgetting_sources_drops_their_derived_caches() = runTest {
        val dropped = mutableListOf<List<String>>()
        val r = LibraryRepository(db, HybridClock(System::currentTimeMillis), grantPersisted = { it.endsWith("/kept") },
            onCacheDeleted = { _, keys -> dropped += keys })
        r.recordOpened(info("id-1", SourceRef.Content("content://x/once")))   // a grant that could not be kept
        r.recordOpened(info("id-2", SourceRef.Content("content://x/kept")))
        r.dropSessionFiles()
        assertEquals(listOf(listOf("content://x/once")), dropped)
        r.forget(SourceRef.File("/sdcard/gone.epub"))
        assertEquals(listOf("/sdcard/gone.epub"), dropped.last())
        r.dropSessionFiles() // nothing left to drop: no call
        assertEquals(2, dropped.size)
    }

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

    /** M3 deferred: an open-with content:// grant that could not be persisted is gone after a restart; so is the entry. */
    @Test fun session_only_source_is_dropped_at_the_next_start_and_the_book_resumes_when_reopened() = runTest {
        val db = testDb()
        val clock = HybridClock(System::currentTimeMillis)
        val first = LibraryRepository(db, clock, grantPersisted = { false })
        val ref = SourceRef.Content("content://mail/attachment/7")
        val id = first.recordOpened(OpenedBookInfo("id-mail", "Mail book", listOf("A"), ref, 9)).bookId
        db.positionDao().put(id, "epubcfi(/6/4!/4/2/1:9)", 0.2, 1, clock.now())
        assertEquals(listOf("Mail book"), first.library().first().map { it.title }) // usable in this session
        val next = LibraryRepository(db, clock, grantPersisted = { false })
        next.dropSessionFiles() // next process start
        assertEquals(emptyList(), next.library().first())
        val again = next.recordOpened(OpenedBookInfo("id-mail", "Mail book", listOf("A"), ref, 9))
        assertEquals(id, again.bookId); assertEquals("epubcfi(/6/4!/4/2/1:9)", again.position!!.cfi)
    }

    @Test fun persisted_grants_and_files_are_kept_at_start() = runTest {
        val db = testDb()
        val repo = LibraryRepository(db, HybridClock(System::currentTimeMillis), grantPersisted = { true })
        repo.recordOpened(info("id-1", SourceRef.Content("content://picker/1"), "Kept"))
        repo.recordOpened(info("id-2", SourceRef.File("/sdcard/b.epub"), "File"))
        repo.dropSessionFiles()
        assertEquals(setOf("Kept", "File"), repo.library().first().map { it.title }.toSet())
    }
}
