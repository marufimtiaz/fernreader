package app.fernreader.reader

import app.fernreader.core.search.SearchIndexes
import app.fernreader.core.search.SearchQuery
import app.fernreader.core.search.SearchUpdate
import kotlinx.coroutines.flow.Flow

/**
 * The app's [SearchPort]: the open session's text feeds [SearchIndexes] (F-050). [onUnfinished] is called when a book
 * closes while its index is incomplete, to schedule the idle job that finishes it.
 */
class BookSearch(
    private val indexes: SearchIndexes,
    private val opener: BookOpener,
    private val onUnfinished: () -> Unit,
) : SearchPort {
    override fun ensureIndex(bookId: String, digest: String, sessionId: String) =
        indexes.ensureBuilt(bookId, digest) { opener.bookText(sessionId) }

    override fun search(bookId: String, digest: String, sessionId: String, query: SearchQuery): Flow<SearchUpdate> =
        indexes.search(bookId, digest, query)

    override fun bookClosed(bookId: String) {
        if (!indexes.progress(bookId).value.complete) onUnfinished()
    }
}
