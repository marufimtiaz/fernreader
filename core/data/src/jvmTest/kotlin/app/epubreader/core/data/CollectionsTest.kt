package app.epubreader.core.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-026 collections and F-024 "add to collection" in the data layer (synced rows, F-115). */
class CollectionsTest {
    private val db = testDb()
    private val repo = LibraryRepository(db, HybridClock(System::currentTimeMillis))
    private suspend fun book(key: String, title: String) =
        repo.recordOpened(OpenedBookInfo(key, title, listOf("A"), SourceRef.File("/b/$key.epub"), 1)).bookId
    private suspend fun members(collectionId: String) = repo.library().first().filter { collectionId in it.collectionIds }.map { it.title }.toSet()

    @Test fun classics_with_alice_and_frankenstein_shows_exactly_two() = runTest {
        val alice = book("k-alice", "Alice"); val frank = book("k-frank", "Frankenstein"); book("k-moby", "Moby-Dick")
        val c = repo.createCollection("Classics")
        repo.addToCollection(c, listOf(alice, frank))
        assertEquals(setOf("Alice", "Frankenstein"), members(c))
        assertEquals(listOf("Classics"), repo.collections().first().map { it.name })
    }

    @Test fun a_book_can_be_in_several_collections_and_adding_twice_is_one_membership() = runTest {
        val alice = book("k-alice", "Alice")
        val a = repo.createCollection("Classics"); val b = repo.createCollection("Kids")
        repo.addToCollection(a, listOf(alice)); repo.addToCollection(a, listOf(alice)); repo.addToCollection(b, listOf(alice))
        assertEquals(setOf(a, b), repo.library().first().single().collectionIds.toSet())
        assertEquals(2, db.collectionDao().memberships().size)
    }

    @Test fun rename_keeps_members_and_delete_keeps_books() = runTest {
        val alice = book("k-alice", "Alice")
        val c = repo.createCollection("Clasics")
        repo.addToCollection(c, listOf(alice))
        repo.renameCollection(c, "Classics")
        assertEquals("Classics", repo.collections().first().single().name)
        assertEquals(setOf("Alice"), members(c))
        repo.deleteCollection(c)
        assertTrue(repo.collections().first().isEmpty())
        assertEquals(listOf("Alice"), repo.library().first().map { it.title }) // the book stays in the library
        assertTrue(repo.library().first().single().collectionIds.isEmpty())
        // tombstones, not deleted rows (F-115): sync and restore need them
        assertEquals(true, db.collectionDao().byId(c)?.deleted)
        assertTrue(db.collectionDao().memberships().all { it.deleted })
    }

    @Test fun removing_and_re_adding_reuses_the_membership_row_with_a_newer_stamp() = runTest {
        val alice = book("k-alice", "Alice")
        val c = repo.createCollection("Classics")
        repo.addToCollection(c, listOf(alice))
        val first = db.collectionDao().memberships().single()
        repo.removeFromCollection(c, listOf(alice))
        assertEquals(emptySet(), members(c))
        repo.addToCollection(c, listOf(alice))
        val again = db.collectionDao().memberships().single()
        assertEquals(first.id, again.id)
        assertTrue(again.updatedAt > first.updatedAt && !again.deleted)
    }

    @Test fun names_are_required_and_unique_ignoring_case() = runTest {
        repo.createCollection("Classics")
        val existing = repo.collections().first()
        assertNotNull(CollectionNames.problem("  ", existing))
        assertNotNull(CollectionNames.problem("classics", existing))
        assertNull(CollectionNames.problem("Poetry", existing))
        assertNull(CollectionNames.problem("Classics", existing, renaming = existing.single().id)) // own name is fine
    }
}
