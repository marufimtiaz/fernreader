package app.epubreader.core.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P-063 (M9 release check): the benchmark build's `seedLibrary N` fills the library with N extra books. */
class LibrarySeederTest {
    private val clock = HybridClock(System::currentTimeMillis)
    private val db = testDb()
    private val repo = LibraryRepository(db, clock)
    private val copied = mutableListOf<Pair<String, String>>()
    private val deleted = mutableListOf<String>()
    private val seeder = LibrarySeeder(db, clock, copyThumb = { from, id -> copied += from to id; "/thumbs/$id.webp" }, deleteThumb = { deleted += it })

    private suspend fun book(key: String, title: String, thumb: String?): String {
        val id = repo.recordOpened(OpenedBookInfo(key, title, listOf("Author"), SourceRef.File("/s/$key.epub"), 1)).bookId
        repo.setCover(id, thumb)
        return id
    }

    @Test fun seeds_n_books_with_their_own_cover_copies_from_books_that_have_covers() = runTest {
        book("k1", "Alice", "/thumbs/a.webp"); book("k2", "Moby-Dick", "/thumbs/m.webp"); book("k3", "No cover", null)
        assertEquals(1000, seeder.seed(1000))
        val lib = repo.library().first()
        assertEquals(1003, lib.size)
        val seeds = lib.filter { it.id.startsWith(LibrarySeeder.PREFIX) }
        assertEquals(1000, seeds.size)
        assertEquals(1000, seeds.map { it.coverThumb }.toSet().size) // every seed its own thumbnail file
        assertTrue(seeds.all { it.coverThumb!!.startsWith("/thumbs/seed-") })
        assertEquals(setOf("/thumbs/a.webp", "/thumbs/m.webp"), copied.map { it.first }.toSet())
        assertEquals(1000, seeds.map { it.title }.toSet().size)
    }

    @Test fun seeding_again_replaces_the_seeds_and_zero_removes_them() = runTest {
        book("k1", "Alice", "/thumbs/a.webp")
        seeder.seed(10); seeder.seed(5)
        assertEquals(6, repo.library().first().size)
        assertEquals(10, deleted.size) // the first round's thumbnails
        assertEquals(0, seeder.seed(0))
        assertEquals(listOf("Alice"), repo.library().first().map { it.title })
        assertEquals(15, deleted.size)
    }

    @Test fun without_any_cover_the_seeds_have_none() = runTest {
        book("k3", "No cover", null)
        seeder.seed(3)
        assertTrue(repo.library().first().all { it.coverThumb == null })
        assertTrue(copied.isEmpty())
    }
}
