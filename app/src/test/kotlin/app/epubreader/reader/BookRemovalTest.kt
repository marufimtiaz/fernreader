package app.epubreader.reader

import app.epubreader.core.data.SearchHistoryPort
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.assertEquals

class BookRemovalTest {
    private val calls = mutableListOf<String>()
    private val history = object : SearchHistoryPort {
        override suspend fun recent(bookId: String, limit: Int) = emptyList<String>()
        override suspend fun add(bookId: String, query: String) {}
        override suspend fun clear(bookId: String) { calls += "history:$bookId" }
    }

    @Test fun removing_a_book_also_clears_its_search_history_and_deletes_its_index() = runBlocking<Unit> {
        BookRemoval({ calls += "library:$it" }, history, { calls += "index:$it" }).remove("b1")
        assertEquals(listOf("library:b1", "history:b1", "index:b1"), calls)
    }

    @Test fun a_failing_cleanup_does_not_stop_the_rest() = runBlocking<Unit> {
        BookRemoval({ calls += "library:$it" }, history, { error("disk") }).remove("b1")
        assertEquals(listOf("library:b1", "history:b1"), calls)
    }

    /** M7 delete-with-files: the library row is already gone (LibraryRepository.deleteBooks); search data goes too. */
    @Test fun forgetting_search_data_leaves_the_library_alone() = runBlocking<Unit> {
        BookRemoval({ calls += "library:$it" }, history, { calls += "index:$it" }).forgetSearch("b1")
        assertEquals(listOf("history:b1", "index:b1"), calls)
    }
}
