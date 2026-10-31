package app.epubreader.reader

import app.epubreader.core.data.SearchHistoryPort
import kotlinx.coroutines.CancellationException

/**
 * Removing a book from the library also drops what search keeps for it: its recent queries and its index file.
 * The library removal is the one that matters; a failing cleanup is logged and never undoes or blocks it.
 */
class BookRemoval(
    private val removeFromLibrary: suspend (String) -> Unit,
    private val history: SearchHistoryPort,
    private val deleteIndex: suspend (String) -> Unit,
) {
    suspend fun remove(bookId: String) {
        removeFromLibrary(bookId)
        forgetSearch(bookId)
    }

    /** Only the search data (M7: a book deleted with its files has already left the library). */
    suspend fun forgetSearch(bookId: String) {
        cleanup("search history") { history.clear(bookId) }
        cleanup("search index") { deleteIndex(bookId) }
    }

    private suspend fun cleanup(what: String, block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { println("BookRemoval: removing $what failed: $e") }
    }
}
