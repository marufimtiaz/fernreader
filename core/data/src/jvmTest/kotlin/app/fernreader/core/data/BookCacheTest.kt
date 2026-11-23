package app.fernreader.core.data

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BookCacheTest {
    @Test fun cache_is_keyed_by_book_kind_and_file_digest() = runTest {
        val db = testDb()
        val repo = LibraryRepository(db, HybridClock { 1 })
        repo.storeCache("b1", "section-stats.v1", "d1", "[1,2]")
        assertEquals("[1,2]", repo.cached("b1", "section-stats.v1", "d1"))
        assertNull(repo.cached("b1", "section-stats.v1", "d2")) // the file changed: recompute
        assertNull(repo.cached("b2", "section-stats.v1", "d1"))
        repo.storeCache("b1", "section-stats.v1", "d2", "[3]") // replaces
        assertEquals("[3]", repo.cached("b1", "section-stats.v1", "d2"))
        assertNull(repo.cached("b1", "section-stats.v1", "d1"))
    }
}
