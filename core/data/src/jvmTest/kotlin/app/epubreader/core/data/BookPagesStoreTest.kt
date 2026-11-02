package app.epubreader.core.data

import app.epubreader.core.epub.TextPos
import app.epubreader.core.epub.pages.BookPages
import app.epubreader.core.epub.pages.PageSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BookPagesStoreTest {
    private val print = BookPages.decode(PageSource.NAV.name, "i\nii\nxii", "0.0,0.120,2.5", "500,0,900")!!
    private val virtual = BookPages.decode(PageSource.VIRTUAL.name, null, "0.0,1.40", "50,80")!!

    @Test fun pages_are_stored_per_book_and_file_version() = runTest {
        val repo = LibraryRepository(testDb(), HybridClock { 1 })
        repo.storeBookPages("b1", "d1", print)
        assertEquals(print, repo.bookPages("b1", "d1"))
        assertEquals("xii", repo.bookPages("b1", "d1")!!.label(2))
        assertEquals(TextPos(0, 120), repo.bookPages("b1", "d1")!!.start(1))
        assertNull(repo.bookPages("b1", "d2")) // the file changed: computed again
        assertNull(repo.bookPages("b2", "d1"))
        repo.storeBookPages("b1", "d2", virtual) // replaces
        assertEquals(virtual, repo.bookPages("b1", "d2"))
        assertNull(repo.bookPages("b1", "d1"))
    }

    @Test fun pages_of_another_algorithm_version_are_not_used() = runTest {
        val db = testDb()
        db.bookPagesDao().put(BookPagesEntity.of("b1", "d1", virtual).copy(version = BookPages.VERSION + 1))
        assertNull(LibraryRepository(db, HybridClock { 1 }).bookPages("b1", "d1"))
    }

    @Test fun deleting_a_book_with_its_files_drops_its_pages() = runTest {
        val db = testDb()
        val repo = LibraryRepository(db, HybridClock { 1 })
        repo.storeBookPages("b1", "d1", print)
        repo.deleteBooks(listOf("b1"), deleteFiles = true)
        assertNull(repo.bookPages("b1", "d1"))
    }
}
