package app.epubreader.core.library

import app.epubreader.core.data.HybridClock
import app.epubreader.core.data.LibraryRepository
import app.epubreader.core.data.OpenedBookInfo
import app.epubreader.core.data.SourceRef
import app.epubreader.core.data.testDb
import app.epubreader.core.epub.openFileSource
import app.epubreader.core.epub.pkg.FailReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScannerTest {
    private val db = testDb()
    private val clock = HybridClock(System::currentTimeMillis)
    private val repo = LibraryRepository(db, clock)
    private val root = ScanFixtures.folderSet()
    private fun scanner(walker: StorageWalker = FileStorageWalker(root.path), open: (String) -> app.epubreader.core.epub.zip.RandomAccessSource = ::openFileSource) =
        Scanner(db, clock, walker, open)
    private suspend fun titles() = repo.library().first().map { it.title }.sorted()
    private suspend fun bookOf(path: String) = db.libraryDao().bookIdForPath(path)
    private fun f(rel: String) = File(root, rel)
    private suspend fun position(bookId: String, cfi: String) = db.positionDao().put(bookId, cfi, 0.5, 1, clock.now())

    @Test fun folder_set_finds_13_valid_and_reports_fake() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<ScanEvent>())
        val done = scanner().scan(ScanScope.Everywhere) { events += it }
        val expected = listOf("androidmedia", "deletedfolder", "documents", "download", "dup", "hidden", "insidecustom", "moved",
            "nested3", "nomedia", "outsidecustom", "renamed", "unicode", "upper").map { "Folder Test $it" }.sorted()
        assertEquals(expected, titles()) // 15 valid files, dup twice → 14 books (corpus.md's 13 + insidecustom/outsidecustom - dup)
        val fails = repo.failures().first()
        assertEquals(listOf("fake.epub" to FailReason.NOT_A_ZIP.name), fails.map { it.name to it.reason })
        assertEquals(f("Download").path, fails.single().folder)
        assertEquals(1, done.failed); assertEquals(16, done.files)
        assertTrue(events.first() is ScanEvent.Started && events.last() is ScanEvent.Complete)
    }

    @Test fun rename_move_delete_add() = runBlocking {
        scanner().scan(ScanScope.Everywhere)
        val moved = bookOf(f("Download/moved.epub").path)!!
        val renamed = bookOf(f("Download/renamed.epub").path)!!
        val deleted = bookOf(f("Books/ToDelete/deletedfolder.epub").path)!!
        position(moved, "epubcfi(/6/2!/4/2/1:5)")
        f("Download/renamed.epub").renameTo(f("Download/renamed-NEW.epub"))
        f("Documents/Books").mkdirs(); f("Download/moved.epub").renameTo(f("Documents/Books/moved.epub"))
        f("Books/ToDelete").deleteRecursively()
        ScanFixtures.book("livetest.epub").copyTo(f("Download/Live/livetest.epub"))
        scanner().scan(ScanScope.Everywhere)
        assertEquals(renamed, bookOf(f("Download/renamed-NEW.epub").path))
        assertEquals(1, repo.library().first().count { it.title == "Folder Test renamed" })
        assertEquals(moved, bookOf(f("Documents/Books/moved.epub").path))
        assertEquals("epubcfi(/6/2!/4/2/1:5)", repo.position(moved)!!.cfi)
        assertFalse("Folder Test deletedfolder" in titles())
        assertTrue(db.libraryDao().byId(deleted)!!.deleted)
        assertTrue("Folder Test livetest" in titles())
    }

    @Test fun duplicate_files_one_book() = runBlocking {
        scanner().scan(ScanScope.Everywhere)
        val a = bookOf(f("Download/dup.epub").path); val b = bookOf(f("Documents/Books/dup-copy.epub").path)
        assertEquals(a, b)
        assertEquals(2, repo.library().first().single { it.id == a }.paths.size)
    }

    @Test fun two_editions_two_books() = runBlocking {
        val full = ScanFixtures.full ?: return@runBlocking println("SKIP: downloaded corpus not present")
        File(full, "gutenberg/pride-and-prejudice-epub3-images.epub").copyTo(f("Books/pp3.epub"))
        File(full, "gutenberg/pride-and-prejudice-epub2-noimages.epub").copyTo(f("Books/pp2.epub"))
        scanner().scan(ScanScope.Everywhere)
        assertNotEquals(bookOf(f("Books/pp3.epub").path), bookOf(f("Books/pp2.epub").path))
    }

    @Test fun same_path_different_content() = runBlocking {
        scanner().scan(ScanScope.Everywhere)
        val old = bookOf(f("Download/download.epub").path)!!
        position(old, "epubcfi(/6/2!/4/1:0)")
        ScanFixtures.book("documents.epub").copyTo(f("Download/download.epub"), overwrite = true)
        f("Download/download.epub").setLastModified(System.currentTimeMillis() + 5_000)
        scanner().scan(ScanScope.Everywhere)
        val now = bookOf(f("Download/download.epub").path)!!
        assertNotEquals(old, now)
        assertEquals(bookOf(f("Documents/Books/documents.epub").path), now)
        assertTrue(db.libraryDao().byId(old)!!.deleted)
        assertNull(repo.position(now))
    }

    @Test fun same_identifier_updated_in_place() = runBlocking {
        val p = f("Books/inplace.epub").apply { writeBytes(ScanFixtures.epub("urn:uuid:inplace", "In Place")) }
        scanner().scan(ScanScope.Everywhere)
        val id = bookOf(p.path)!!
        position(id, "epubcfi(/6/2!/4/1:3)")
        p.writeBytes(ScanFixtures.epub("urn:uuid:inplace", "In Place", extra = "a new entry changes the central directory"))
        p.setLastModified(System.currentTimeMillis() + 5_000)
        scanner().scan(ScanScope.Everywhere)
        assertEquals(id, bookOf(p.path)) // C-009: same book
        assertEquals("epubcfi(/6/2!/4/1:3)", repo.position(id)!!.cfi)
        assertFalse(db.libraryDao().byId(id)!!.deleted)
    }

    @Test fun unchanged_rescan_does_no_zip_io() = runBlocking {
        scanner().scan(ScanScope.Everywhere)
        val opens = AtomicInteger()
        val done = scanner(open = { opens.incrementAndGet(); openFileSource(it) }).scan(ScanScope.Everywhere)
        assertEquals(0, opens.get()); assertEquals(0, done.added); assertEquals(0, done.removed)
    }

    @Test fun interrupted_scan_resumes() = runBlocking {
        val all = root.walkTopDown().filter { it.isFile && it.name.endsWith(".epub", true) }.sortedBy { it.path }.map(::found).toList()
        // First run: everything once, so a later partial scan has rows that it must not prune.
        Scanner(db, clock, ScriptedWalker(root.path, all)).scan(ScanScope.Everywhere)
        val before = repo.library().first().size
        f("Download/new.epub").writeBytes(ScanFixtures.epub("urn:uuid:new", "New Book"))
        val withNew = all + found(f("Download/new.epub"))
        val gate = CompletableDeferred<Unit>()
        val added = AtomicInteger()
        val job = async { Scanner(db, clock, ScriptedWalker(root.path, withNew.reversed(), pauseAt = 3, release = gate)).scan(ScanScope.Everywhere) { if (it is ScanEvent.BookAdded) added.incrementAndGet() } }
        withTimeout(5_000) { while (added.get() < 1) kotlinx.coroutines.delay(5) }
        job.cancelAndJoin() // killed mid-walk
        assertEquals(before + 1, repo.library().first().size) // nothing pruned, the new book is there once
        Scanner(db, clock, ScriptedWalker(root.path, withNew)).scan(ScanScope.Everywhere)
        assertEquals(before + 1, repo.library().first().size)
        assertEquals(1, repo.library().first().count { it.title == "New Book" })
    }

    @Test fun first_book_before_walk_ends() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val files = listOf(found(f("Download/download.epub")), found(f("Download/dup.epub")))
        val first = CompletableDeferred<String>()
        val job = async { Scanner(db, clock, ScriptedWalker(root.path, files, pauseAt = 1, release = gate)).scan(ScanScope.Everywhere) { if (it is ScanEvent.BookAdded) first.complete(it.path) } }
        assertEquals(files[0].path, withTimeout(5_000) { first.await() }) // the walker is still suspended
        gate.complete(Unit); job.await(); Unit
    }

    @Test fun picker_books_survive_scan() = runBlocking {
        val rec = repo.recordOpened(OpenedBookInfo("pickid-0000000000-x", "Picked", listOf("A"), SourceRef.Content("content://p/1"), 10))
        scanner().scan(ScanScope.Everywhere)
        assertTrue(repo.library().first().any { it.id == rec.bookId })
    }

    @Test fun scope_folder_excludes_outside_adds() = runBlocking {
        scanner().scan(ScanScope.Folder(f("Books").path))
        assertTrue("Folder Test insidecustom" in titles()); assertFalse("Folder Test outsidecustom" in titles())
        scanner().scan(ScanScope.Everywhere)
        assertTrue("Folder Test outsidecustom" in titles())
        scanner().scan(ScanScope.Folder(f("Books").path)) // books outside the folder are not pruned (F-011 fallback)
        assertTrue("Folder Test outsidecustom" in titles())
    }

    @Test fun untitled_book_uses_file_name() = runBlocking {
        f("Books/My Untitled Book.epub").writeBytes(ScanFixtures.epub("urn:uuid:untitled", "   "))
        scanner().scan(ScanScope.Everywhere)
        assertTrue("My Untitled Book" in titles())
    }

    // Final review C1: a small file with a hostile package never stops the scan; it is reported.
    @Test fun hostile_package_is_reported_and_scan_completes() = runBlocking {
        val deep = "<x>".repeat(200_000)
        f("Download/hostile.epub").writeBytes(ScanFixtures.epub("urn:uuid:hostile", "Hostile$deep"))
        val done = scanner().scan(ScanScope.Everywhere)
        assertTrue("Folder Test download" in titles())
        assertEquals(2, done.failed, "${repo.failures().first()}")
        assertTrue(repo.failures().first().any { it.name == "hostile.epub" })
    }

    // Final review I1: reading a scanned book keeps its file a scanned file.
    @Test fun opened_scanned_book_still_leaves_when_deleted_and_rescans_cheaply() = runBlocking {
        scanner().scan(ScanScope.Everywhere)
        val path = f("Download/download.epub").path
        val id = bookOf(path)!!
        val key = db.libraryDao().byId(id)!!.identityKey
        repo.recordOpened(OpenedBookInfo(key, "Folder Test download", listOf("Corpus Generator"), SourceRef.File(path), 10))
        val opens = AtomicInteger()
        scanner(open = { opens.incrementAndGet(); openFileSource(it) }).scan(ScanScope.Everywhere)
        assertEquals(0, opens.get())
        assertTrue(db.libraryDao().byId(id)!!.lastOpenedAt != null)
        f("Download/download.epub").delete()
        scanner().scan(ScanScope.Everywhere)
        assertFalse("Folder Test download" in titles())
    }

    // Review M1: an unreadable root prunes nothing.
    @Test fun unreadable_root_prunes_nothing() = runBlocking {
        scanner().scan(ScanScope.Everywhere)
        val before = titles()
        runCatching { scanner(FileStorageWalker(File(root, "missing-root").path)).scan(ScanScope.Everywhere) }
        assertEquals(before, titles())
    }

    // Review M2: a known file that cannot be read right now keeps its entry.
    @Test fun momentarily_unreadable_file_keeps_its_entry() = runBlocking {
        scanner().scan(ScanScope.Everywhere)
        val p = f("Download/download.epub"); p.setLastModified(System.currentTimeMillis() + 9_000)
        scanner(open = { if (it == p.path) throw java.io.IOException("busy") else openFileSource(it) }).scan(ScanScope.Everywhere)
        assertTrue("Folder Test download" in titles())
    }

    // Review M3: two different books without identifiers at the same path are two books.
    @Test fun identifier_less_books_are_not_merged() = runBlocking {
        val p = f("Books/noid.epub").apply { writeBytes(ScanFixtures.epub("", "First")) }
        scanner().scan(ScanScope.Everywhere)
        val a = bookOf(p.path)!!
        p.writeBytes(ScanFixtures.epub("", "Second", extra = "x")); p.setLastModified(System.currentTimeMillis() + 9_000)
        scanner().scan(ScanScope.Everywhere)
        assertNotEquals(a, bookOf(p.path))
    }
}
