package app.epubreader.core.library

import app.epubreader.core.data.AppDatabase
import app.epubreader.core.data.Backup
import app.epubreader.core.data.BookmarkStore
import app.epubreader.core.data.AnnotationStore
import app.epubreader.core.data.HybridClock
import app.epubreader.core.data.LibraryRepository
import app.epubreader.core.data.OpenedBookInfo
import app.epubreader.core.data.ReadingDataSummary
import app.epubreader.core.data.SourceRef
import app.epubreader.core.data.testDb
import app.epubreader.core.epub.meta.BookIdentity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** C-096: a byte-identical file kept as its own library entry, through scans, moves, backups and a merge. */
class SeparateCopyTest {
    private val clock = HybridClock(System::currentTimeMillis)
    private val db = testDb()
    private val repo = LibraryRepository(db, clock)
    private val root = Files.createTempDirectory("storage").toFile()
    private val title = "Folder Test dup"
    private val first = f("A/first.epub")
    private val second = f("B/second.epub")

    private fun f(rel: String) = File(root, rel)
    /** The book's bytes at [rel]; every file gets its own modified time unless told otherwise. */
    private fun put(rel: String, mtime: Long = next()): File = f(rel).also {
        it.parentFile.mkdirs(); ScanFixtures.book("dup.epub").copyTo(it, overwrite = true); it.setLastModified(mtime)
    }
    private var stamp = 1_600_000_000_000
    private fun next() = (stamp + 1_000).also { stamp = it }
    private fun File.moveTo(to: File): File { to.parentFile.mkdirs(); assertTrue(renameTo(to)); return to }

    private suspend fun scan(on: AppDatabase = db) = Scanner(on, clock, FileStorageWalker(root.path)).scan(ScanScope.Everywhere)
    private suspend fun bookOf(file: File, on: AppDatabase = db) = on.libraryDao().bookIdForPath(file.path)
    private suspend fun entries(r: LibraryRepository = repo) = r.library().first()
    private suspend fun paths(id: String, r: LibraryRepository = repo) = entries(r).single { it.id == id }.paths.sorted()

    /** Two identical files, the second kept separate. Returns (the original entry, the separate entry). */
    private suspend fun separated(): Pair<String, String> {
        put("A/first.epub"); put("B/second.epub")
        scan()
        val original = bookOf(first)!!
        assertEquals(original, bookOf(second))
        return original to assertNotNull(repo.keepSeparate(second.path))
    }

    private suspend fun position(id: String, cfi: String, on: AppDatabase = db) = on.positionDao().put(id, cfi, 0.5, 1, clock.now())

    @Test fun keeping_a_copy_makes_a_fresh_entry_and_leaves_the_original_alone() = runBlocking {
        put("A/first.epub"); put("B/second.epub")
        scan()
        val original = bookOf(first)!!
        position(original, "epubcfi(/6/2!/4/2/1:5)")
        BookmarkStore(db, clock).add(original, "epubcfi(/6/2!/4/2/1:5)", 1, 0.5, "One")
        AnnotationStore(db, clock).add(original, "epubcfi(/6/2!/4/2,/1:0,/1:4)", 1, "text", "One", "yellow", "highlight")
        repo.addToCollection(repo.createCollection("Classics"), listOf(original))
        val before = repo.details(original)!!
        assertEquals(listOf(false, true), before.files.map { it.canSeparate }) // never the first location

        val copy = repo.keepSeparate(second.path)!!

        assertNotEquals(original, copy)
        assertEquals(listOf(title, title), entries().map { it.title })
        assertEquals(listOf(first.path), paths(original))
        assertEquals(listOf(second.path), paths(copy))
        assertEquals("epubcfi(/6/2!/4/2/1:5)", repo.position(original)!!.cfi)
        val d = repo.details(copy)!!
        assertTrue(d.separate); assertTrue(d.canMerge)
        assertEquals(ReadingDataSummary(), d.readingData) // nothing copied
        assertEquals(emptyList(), d.collections)
        assertNull(d.lastOpenedAt)
        assertFalse(repo.details(original)!!.separate)
        assertEquals(ReadingDataSummary(position = true, bookmarks = 1, highlights = 1), repo.details(original)!!.readingData)
    }

    @Test fun the_first_location_and_an_only_location_stay() = runBlocking {
        put("A/first.epub"); put("B/second.epub")
        scan()
        assertNull(repo.keepSeparate(first.path))
        assertNotNull(repo.keepSeparate(second.path))
        assertNull(repo.keepSeparate(second.path)) // now the only file of its entry
        assertEquals(2, entries().size)
    }

    @Test fun a_rescan_changes_nothing_and_a_touched_file_stays_with_its_entry() = runBlocking {
        val (original, copy) = separated()
        scan()
        assertEquals(original, bookOf(first)); assertEquals(copy, bookOf(second))
        second.setLastModified(next()); first.setLastModified(next()) // loaded again, same content
        scan()
        assertEquals(original, bookOf(first)); assertEquals(copy, bookOf(second))
    }

    @Test fun a_renamed_or_moved_copy_stays_separate() = runBlocking {
        val (original, copy) = separated()
        position(copy, "epubcfi(/6/4!/4/2/1:1)")
        val renamed = second.moveTo(f("B/first.epub")) // even under the original's own file name
        scan()
        assertEquals(copy, bookOf(renamed))
        assertEquals(original, bookOf(first))
        assertEquals(2, entries().size)
        val moved = renamed.moveTo(f("C/D/elsewhere.epub"))
        scan()
        assertEquals(copy, bookOf(moved))
        assertNull(bookOf(renamed))
        assertEquals("epubcfi(/6/4!/4/2/1:1)", repo.position(copy)!!.cfi)
    }

    @Test fun a_move_that_changes_the_modified_time_stays_separate() = runBlocking {
        val (_, copy) = separated()
        val moved = second.moveTo(f("C/moved.epub"))
        moved.setLastModified(next()) // a copy and delete
        scan()
        assertEquals(copy, bookOf(moved))
    }

    @Test fun a_moved_original_stays_the_original() = runBlocking {
        val (original, copy) = separated()
        val moved = first.moveTo(f("C/moved.epub"))
        scan()
        assertEquals(original, bookOf(moved))
        assertEquals(copy, bookOf(second))
    }

    @Test fun both_files_moved_in_one_scan_go_back_by_their_modified_times() = runBlocking {
        val (original, copy) = separated()
        // the sort order points the wrong way: the first file left for the copy would be the original's
        val movedFirst = first.moveTo(f("C/a-was-first.epub"))
        val movedSecond = second.moveTo(f("C/z-was-second.epub"))
        scan()
        assertEquals(original, bookOf(movedFirst))
        assertEquals(copy, bookOf(movedSecond))
    }

    @Test fun both_files_moved_with_equal_times_still_leave_two_entries() = runBlocking {
        val t = next()
        put("A/first.epub", t); put("B/second.epub", t)
        scan()
        val original = bookOf(first)!!
        val copy = repo.keepSeparate(second.path)!!
        val x = first.moveTo(f("C/x.epub")); val y = second.moveTo(f("C/y.epub"))
        scan()
        assertEquals(setOf(original, copy), setOf(bookOf(x), bookOf(y))) // no evidence for which, but one file each
    }

    @Test fun a_third_identical_file_joins_the_original_never_the_copy() = runBlocking {
        val (original, copy) = separated()
        val third = put("C/third.epub")
        scan()
        assertEquals(original, bookOf(third))
        assertEquals(listOf(second.path), paths(copy))
        assertEquals(2, entries().size)
    }

    @Test fun a_third_file_arriving_with_a_moved_copy_leaves_the_copy_its_moved_file() = runBlocking {
        val (original, copy) = separated()
        val third = put("A/a-third.epub") // sorts first
        val moved = second.moveTo(f("C/moved.epub"))
        scan()
        assertEquals(copy, bookOf(moved))
        assertEquals(original, bookOf(third))
    }

    @Test fun the_originals_file_deleted_leaves_the_copy_with_nothing_to_merge_with() = runBlocking {
        val (original, copy) = separated()
        position(original, "epubcfi(/6/2!/4/2/1:5)")
        position(copy, "epubcfi(/6/4!/4/2/1:1)")
        assertTrue(first.delete())
        scan()
        assertEquals(listOf(copy), entries().map { it.id })
        assertTrue(repo.details(copy)!!.separate)
        assertFalse(repo.details(copy)!!.canMerge)
        assertFalse(repo.mergeCopy(copy)) // the other entry is gone: nothing happens
        assertEquals("epubcfi(/6/4!/4/2/1:1)", repo.position(copy)!!.cfi)
        assertEquals(listOf(second.path), paths(copy))

        val third = put("C/third.epub") // the original comes back with its own data, and merging is possible again
        scan()
        assertEquals(original, bookOf(third))
        assertEquals("epubcfi(/6/2!/4/2/1:5)", repo.position(original)!!.cfi)
        assertTrue(repo.details(copy)!!.canMerge)
    }

    @Test fun a_copy_whose_file_went_away_takes_it_back_when_it_returns() = runBlocking {
        val (original, copy) = separated()
        position(copy, "epubcfi(/6/4!/4/2/1:1)")
        val parked = File(Files.createTempDirectory("outside").toFile(), "parked.epub")
        val t = second.lastModified()
        ScanFixtures.book("dup.epub").copyTo(parked); parked.setLastModified(t)
        assertTrue(second.delete())
        scan()
        assertEquals(listOf(original), entries().map { it.id })
        val back = put("C/back.epub", t) // moved out of the library, then back to another folder
        scan()
        assertEquals(copy, bookOf(back))
        assertEquals("epubcfi(/6/4!/4/2/1:1)", repo.position(copy)!!.cfi)
        assertEquals(2, entries().size)
    }

    @Test fun a_moved_copy_opened_before_the_scan_goes_back_to_its_entry() = runBlocking {
        val (original, copy) = separated()
        val moved = second.moveTo(f("C/moved.epub"))
        // Open-with of the new path: the app doesn't know the file yet, so it lands on the content's book
        repo.recordOpened(OpenedBookInfo(contentKey(original), title, emptyList(), SourceRef.File(moved.path), moved.length()))
        assertEquals(original, bookOf(moved))
        scan()
        assertEquals(copy, bookOf(moved))
    }

    @Test fun opening_a_copys_file_stays_on_the_copy() = runBlocking {
        val (original, copy) = separated()
        position(copy, "epubcfi(/6/4!/4/2/1:1)")
        position(original, "epubcfi(/6/2!/4/2/1:5)")
        val ref = SourceRef.File(second.path)
        assertEquals(copy, repo.knownBookId(ref))
        val rec = repo.recordOpened(OpenedBookInfo(contentKey(original), title, emptyList(), ref, second.length()))
        assertEquals(copy, rec.bookId)
        assertEquals("epubcfi(/6/4!/4/2/1:1)", rec.position!!.cfi)
        assertEquals(copy, bookOf(second))
        assertNotNull(db.libraryDao().byId(copy)!!.lastOpenedAt)
        assertNull(db.libraryDao().byId(original)!!.lastOpenedAt)
    }

    @Test fun a_copys_file_edited_in_place_is_an_ordinary_book() = runBlocking {
        val (original, copy) = separated()
        position(copy, "epubcfi(/6/4!/4/2/1:1)")
        second.writeBytes(ScanFixtures.epub("urn:uuid:corpus-Folder-Test-dup", "Edited")) // same package identifier (C-009)
        scan()
        assertEquals(original, bookOf(first))
        assertEquals(copy, bookOf(second))
        assertEquals("Edited", repo.details(copy)!!.title)
        assertFalse(repo.details(copy)!!.separate)
        assertEquals("epubcfi(/6/4!/4/2/1:1)", repo.position(copy)!!.cfi)
        assertEquals(2, entries().size)
    }

    @Test fun merge_loses_the_copys_data_and_its_file_joins_the_original() = runBlocking {
        val (original, copy) = separated()
        position(original, "epubcfi(/6/2!/4/2/1:5)")
        position(copy, "epubcfi(/6/4!/4/2/1:1)")
        BookmarkStore(db, clock).add(copy, "epubcfi(/6/4!/4/2/1:1)", 2, 0.5, "Two")
        val ann = AnnotationStore(db, clock)
        ann.add(copy, "epubcfi(/6/4!/4/2,/1:0,/1:4)", 2, "text", "Two", "yellow", "highlight")
        ann.update(ann.add(copy, "epubcfi(/6/4!/4/2,/1:5,/1:9)", 2, "more", "Two", "yellow", "highlight").id, note = "a note")
        assertEquals(ReadingDataSummary(position = true, bookmarks = 1, highlights = 1, notes = 1), repo.details(copy)!!.readingData)
        val c = repo.createCollection("Classics")
        repo.addToCollection(c, listOf(copy))

        assertTrue(repo.mergeCopy(copy))

        assertEquals(listOf(original), entries().map { it.id })
        assertEquals(listOf(first.path, second.path).sorted(), paths(original))
        assertEquals("epubcfi(/6/2!/4/2/1:5)", repo.position(original)!!.cfi)
        assertNull(repo.position(copy))
        assertEquals(emptyList(), BookmarkStore(db, clock).bookmarks(copy).first())
        assertEquals(emptyList(), ann.annotations(copy).first())
        assertTrue(db.collectionDao().memberships().all { it.deleted })
        assertFalse(repo.mergeCopy(copy)) // twice: nothing happens

        scan()
        assertEquals(listOf(original), entries().map { it.id })
        val third = put("C/third.epub")
        scan()
        assertEquals(original, bookOf(third))
        assertNotNull(repo.keepSeparate(second.path)) // and it can be separated again, fresh
        assertEquals(2, entries().size)
    }

    @Test fun merging_with_the_original_gone_joins_another_copy() = runBlocking {
        val (_, one) = separated()
        val third = put("C/third.epub")
        scan()
        val two = repo.keepSeparate(third.path)!!
        assertTrue(first.delete())
        scan()
        assertTrue(repo.mergeCopy(two))
        assertEquals(listOf(one), entries().map { it.id })
        assertEquals(listOf(second.path, third.path).sorted(), paths(one))
    }

    @Test fun a_hidden_original_is_not_merged_into() = runBlocking {
        val (original, copy) = separated()
        repo.hide(listOf(original))
        assertFalse(repo.details(copy)!!.canMerge)
        assertFalse(repo.mergeCopy(copy))
        assertEquals(listOf(second.path), paths(copy))
    }

    @Test fun a_removed_copy_is_over_and_the_next_identical_file_joins_the_original() = runBlocking {
        val (original, copy) = separated()
        repo.removeBook(copy)
        assertTrue(second.delete())
        scan()
        val third = put("C/third.epub")
        scan()
        assertEquals(original, bookOf(third))
        assertEquals(listOf(original), entries().map { it.id })
    }

    @Test fun deleting_a_copy_with_its_file_keeps_the_original() = runBlocking {
        val deletes = LibraryRepository(db, clock, deleteFile = { File(it).delete() })
        val (original, copy) = separated()
        deletes.deleteBooks(listOf(copy), deleteFiles = true)
        assertFalse(second.exists()); assertTrue(first.exists())
        assertEquals(listOf(original), entries().map { it.id })
    }

    // --- backup and restore ----------------------------------------------------------------------------------------

    private inner class Device {
        val db = testDb(); val clock = HybridClock(System::currentTimeMillis)
        val repo = LibraryRepository(this.db, this.clock)
        suspend fun scan() = Scanner(this.db, this.clock, FileStorageWalker(root.path)).scan(ScanScope.Everywhere)
        suspend fun restore(text: String) = Backup(this.db, this.clock).restore(text)
    }

    @Test fun clear_data_rescan_restore_brings_the_copy_back_on_its_own_file() = runBlocking {
        val (original, copy) = separated()
        position(original, "epubcfi(/6/2!/4/2/1:5)")
        position(copy, "epubcfi(/6/4!/4/2/1:1)")
        BookmarkStore(db, clock).add(copy, "epubcfi(/6/4!/4/2/1:1)", 2, 0.5, "Two")
        val text = Backup(db, clock).export("1.1")

        val d = Device()
        d.scan()
        assertEquals(1, entries(d.repo).size) // a fresh library knows nothing of the choice
        val result = d.restore(text)
        assertEquals(2, result.booksMatched); assertEquals(0, result.booksWaiting)
        assertEquals(copy, bookOf(second, d.db)) // its id survives, on the same file
        assertEquals(listOf(second.path), paths(copy, d.repo))
        assertEquals("epubcfi(/6/4!/4/2/1:1)", d.repo.position(copy)!!.cfi)
        val back = bookOf(first, d.db)!!
        assertEquals("epubcfi(/6/2!/4/2/1:5)", d.repo.position(back)!!.cfi)
        assertEquals(1, BookmarkStore(d.db, d.clock).bookmarks(copy).first().size)
        d.scan()
        assertEquals(copy, bookOf(second, d.db))
        assertEquals(2, entries(d.repo).size)
    }

    @Test fun restore_before_the_first_scan_attaches_when_the_scan_finds_the_files() = runBlocking {
        val (_, copy) = separated()
        position(copy, "epubcfi(/6/4!/4/2/1:1)")
        val text = Backup(db, clock).export("1.1")
        val d = Device()
        val result = d.restore(text)
        assertEquals(0, result.booksMatched); assertEquals(2, result.booksWaiting)
        d.scan()
        assertEquals(copy, bookOf(second, d.db))
        assertEquals(2, entries(d.repo).size)
    }

    @Test fun restore_over_the_same_library_changes_nothing() = runBlocking {
        val (original, copy) = separated()
        val text = Backup(db, clock).export("1.1")
        val result = Backup(db, clock).restore(text)
        assertEquals(2, result.booksMatched)
        assertEquals(copy, bookOf(second)); assertEquals(original, bookOf(first))
    }

    @Test fun restore_finds_a_moved_copy_by_its_modified_time_else_takes_a_spare_file() = runBlocking {
        val (_, copy) = separated()
        val text = Backup(db, clock).export("1.1")
        val third = put("C/third.epub") // not the copy's file
        val moved = second.moveTo(f("D/moved.epub")) // sorts after the spare file: only its time points to it
        var d = Device()
        d.scan()
        d.restore(text)
        assertEquals(copy, bookOf(moved, d.db))
        assertEquals(bookOf(first, d.db), bookOf(third, d.db))

        moved.setLastModified(next()) // nothing points to any file: one of the spare ones becomes the copy
        d = Device()
        d.scan()
        d.restore(text)
        assertEquals(2, entries(d.repo).size)
        assertEquals(1, paths(copy, d.repo).size)
        assertEquals(first.path, entries(d.repo).single { it.id != copy }.paths.sorted().first())
    }

    @Test fun restore_onto_a_library_without_the_file_waits_for_it() = runBlocking {
        val (original, copy) = separated()
        position(original, "epubcfi(/6/2!/4/2/1:5)")
        position(copy, "epubcfi(/6/4!/4/2/1:1)")
        val text = Backup(db, clock).export("1.1")
        val t = second.lastModified()
        assertTrue(second.delete())
        val d = Device()
        d.scan()
        val result = d.restore(text)
        assertEquals(1, result.booksMatched); assertEquals(1, result.booksWaiting)
        assertEquals(1, entries(d.repo).size) // the one file stays with the original
        assertEquals("epubcfi(/6/2!/4/2/1:5)", d.repo.position(bookOf(first, d.db)!!)!!.cfi)
        val found = put("C/found.epub", t)
        d.scan()
        assertEquals(copy, bookOf(found, d.db))
        assertEquals("epubcfi(/6/4!/4/2/1:1)", d.repo.position(copy)!!.cfi)
    }

    @Test fun restore_when_only_the_copys_file_is_here_gives_it_to_the_copy() = runBlocking {
        val (original, copy) = separated()
        position(original, "epubcfi(/6/2!/4/2/1:5)")
        position(copy, "epubcfi(/6/4!/4/2/1:1)")
        val text = Backup(db, clock).export("1.1")
        assertTrue(first.delete())
        val d = Device()
        d.scan()
        d.restore(text)
        assertEquals(listOf(copy), entries(d.repo).map { it.id })
        assertEquals("epubcfi(/6/4!/4/2/1:1)", d.repo.position(copy)!!.cfi)
    }

    @Test fun a_backup_from_before_the_field_leaves_the_library_as_it_is() = runBlocking {
        put("A/first.epub"); put("B/second.epub")
        scan()
        val original = bookOf(first)!!
        position(original, "epubcfi(/6/2!/4/2/1:5)")
        val old = Regex(""","separate(Of|Path|MtimeMs)":null""").replace(Backup(db, clock).export("1.0"), "")
        assertFalse("separate" in old)

        val copy = repo.keepSeparate(second.path)!! // the local choice survives a restore of an older file
        Backup(db, clock).restore(old)
        assertEquals(copy, bookOf(second)); assertEquals(original, bookOf(first))

        val d = Device() // and on a new device the file is one entry, as it was
        d.scan()
        d.restore(old)
        assertEquals(1, entries(d.repo).size)
        assertEquals(2, entries(d.repo).single().paths.size)
    }

    @Test fun a_backup_from_before_a_merge_doesnt_undo_it() = runBlocking {
        val (original, copy) = separated()
        position(copy, "epubcfi(/6/4!/4/2/1:1)")
        val text = Backup(db, clock).export("1.1")
        repo.mergeCopy(copy)
        Backup(db, clock).restore(text)
        assertEquals(listOf(original), entries().map { it.id })
        assertNull(repo.position(copy))
        assertEquals(listOf(first.path, second.path).sorted(), paths(original))
    }

    @Test fun a_merged_copy_in_a_backup_stays_merged() = runBlocking {
        val (_, copy) = separated()
        repo.mergeCopy(copy)
        val text = Backup(db, clock).export("1.1")
        val d = Device()
        d.scan()
        d.restore(text)
        assertEquals(1, entries(d.repo).size)
    }

    private suspend fun contentKey(id: String) = db.libraryDao().byId(id)!!.identityKey
}
