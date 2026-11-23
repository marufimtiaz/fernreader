package app.fernreader.core.library

import app.fernreader.core.data.HybridClock
import app.fernreader.core.data.LibraryRepository
import app.fernreader.core.data.testDb
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-015 / F-021 book details: metadata, every file location of a duplicate, sizes, identity, collections. */
class BookDetailsTest {
    private val db = testDb()
    private val clock = HybridClock(System::currentTimeMillis)
    private val repo = LibraryRepository(db, clock)
    private val root = ScanFixtures.folderSet()

    @Test fun duplicate_shows_both_paths_with_sizes_and_identity(): Unit = runBlocking {
        Scanner(db, clock, FileStorageWalker(root.path)).scan(ScanScope.Everywhere)
        val id = db.libraryDao().bookIdForPath(File(root, "Download/dup.epub").path)!!
        val c = repo.createCollection("Dups"); repo.addToCollection(c, listOf(id))
        val d = repo.details(id)!!
        assertEquals("Folder Test dup", d.title)
        assertEquals(listOf("Corpus Generator"), d.authors)
        assertEquals(setOf(File(root, "Download/dup.epub").path, File(root, "Documents/Books/dup-copy.epub").path), d.files.map { it.path }.toSet())
        assertTrue(d.files.all { it.size == File(root, "Download/dup.epub").length() })
        assertEquals(db.libraryDao().byId(id)!!.identityKey, d.identityKey)
        assertEquals(listOf("Dups"), d.collections)
        assertNull(repo.details("nope"))
    }
}
