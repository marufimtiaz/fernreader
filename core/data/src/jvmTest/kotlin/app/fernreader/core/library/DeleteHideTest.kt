package app.fernreader.core.library

import app.fernreader.core.data.BookCacheEntity
import app.fernreader.core.data.HybridClock
import app.fernreader.core.data.LibraryRepository
import app.fernreader.core.data.testDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-025 checks: library-only delete hides (scans keep it hidden, un-hide restores); file delete removes the file. */
class DeleteHideTest {
    private val db = testDb()
    private val clock = HybridClock(System::currentTimeMillis)
    private val gone = mutableListOf<String>()
    /** Book id -> file sources whose derived caches outside the database were dropped (M8 chunk directories). */
    private val cachesDropped = mutableListOf<Pair<String?, Set<String>>>()
    private val repo = LibraryRepository(db, clock, deleteFile = { File(it).delete() }, onBookFilesDeleted = { id, _ -> gone += id },
        onCacheDeleted = { id, keys -> cachesDropped += id to keys.toSet() })
    private val root = ScanFixtures.folderSet()
    private fun f(rel: String) = File(root, rel)
    private suspend fun scan() = Scanner(db, clock, FileStorageWalker(root.path)).scan(ScanScope.Everywhere)
    private suspend fun titles() = repo.library().first().map { it.title }.toSet()
    private suspend fun idOf(rel: String) = db.libraryDao().bookIdForPath(f(rel).path)!!

    @Test fun library_only_delete_keeps_the_file_and_rescans_keep_it_hidden_until_unhidden() = runBlocking {
        scan()
        val id = idOf("Download/download.epub")
        db.positionDao().put(id, "epubcfi(/6/2!/4/2/1:3)", 0.1, 1, clock.now())
        val r = repo.deleteBooks(listOf(id), deleteFiles = false)
        assertEquals(0, r.deleted)
        assertTrue(f("Download/download.epub").exists())
        assertFalse("Folder Test download" in titles())
        scan()
        assertFalse("Folder Test download" in titles())
        assertEquals(listOf("Folder Test download"), repo.hiddenBooks().first().map { it.title })
        repo.unhide(listOf(id))
        assertTrue("Folder Test download" in titles())
        assertEquals("epubcfi(/6/2!/4/2/1:3)", repo.position(id)!!.cfi) // reading data was never touched
    }

    @Test fun delete_with_file_removes_every_copy_and_derived_data_but_not_reading_data(): Unit = runBlocking {
        scan()
        val id = idOf("Download/dup.epub")
        db.positionDao().put(id, "epubcfi(/6/2!/4/2/1:3)", 0.1, 1, clock.now())
        db.bookCacheDao().put(BookCacheEntity(id, "section-stats.v1", "d", "[]"))
        val r = repo.deleteBooks(listOf(id), deleteFiles = true)
        assertEquals(2, r.deleted); assertEquals(0, r.failed)
        assertFalse(f("Download/dup.epub").exists()); assertFalse(f("Documents/Books/dup-copy.epub").exists())
        assertFalse("Folder Test dup" in titles())
        assertNull(db.bookCacheDao().get(id, "section-stats.v1", "d")) // M4 deferred: cache rows go with their book
        assertEquals(listOf(id), gone) // thumbnail cleanup hook
        // M8: the chunk caches (keyed by each file's source key) go with the book_cache rows.
        assertEquals(listOf<Pair<String?, Set<String>>>(id to setOf(f("Download/dup.epub").path, f("Documents/Books/dup-copy.epub").path)), cachesDropped)
        scan()
        assertFalse("Folder Test dup" in titles())
        assertNotNull(repo.position(id)) // kept by id: the same book found again later resumes (F-012)
    }

    @Test fun a_file_that_cannot_be_deleted_leaves_the_book_hidden() = runBlocking {
        scan()
        val id = idOf("Download/download.epub")
        val stubborn = LibraryRepository(db, clock, deleteFile = { false })
        val r = stubborn.deleteBooks(listOf(id), deleteFiles = true)
        assertEquals(0, r.deleted); assertEquals(1, r.failed)
        scan()
        assertFalse("Folder Test download" in titles()) // not back on the next scan
        assertEquals(listOf(id), repo.hiddenBooks().first().map { it.id })
    }

    /** M8: library-only delete (hide) keeps every cache: the book still opens fast when un-hidden. */
    @Test fun hiding_keeps_the_caches() = runBlocking {
        scan()
        val id = idOf("Download/download.epub")
        db.bookCacheDao().put(BookCacheEntity(id, "section-stats.v1", "d", "[]"))
        repo.deleteBooks(listOf(id), deleteFiles = false)
        assertNotNull(db.bookCacheDao().get(id, "section-stats.v1", "d"))
        assertEquals(emptyList(), cachesDropped)
    }

    /** Final review I-4: file deletion never runs on the caller's (main) thread. */
    @Test fun files_are_deleted_on_the_io_dispatcher(): Unit = runBlocking {
        scan()
        val id = idOf("Download/download.epub")
        val threads = mutableListOf<String>()
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "io-test") }
        val io = pool.asCoroutineDispatcher()
        try {
            val r = LibraryRepository(db, clock, deleteFile = { threads += Thread.currentThread().name; File(it).delete() },
                onBookFilesDeleted = { _, _ -> threads += Thread.currentThread().name }, io = io).deleteBooks(listOf(id), deleteFiles = true)
            assertEquals(1, r.deleted)
            assertEquals(2, threads.size); assertTrue(threads.all { it.startsWith("io-test") }, threads.toString())
        } finally { pool.shutdown() }
    }
}
