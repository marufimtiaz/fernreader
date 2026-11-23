package app.fernreader.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert

/** A query the reader ran in a book (F-050). Device-local, not synced; searching it again only moves [usedAt]. */
@Entity(tableName = "search_history", primaryKeys = ["bookId", "query"])
data class SearchHistoryEntity(val bookId: String, val query: String, val usedAt: Long)

@Dao
interface SearchHistoryDao {
    @Query("SELECT `query` FROM search_history WHERE bookId = :bookId ORDER BY usedAt DESC, `query` LIMIT :limit")
    suspend fun recent(bookId: String, limit: Int): List<String>

    @Upsert suspend fun put(row: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE bookId = :bookId") suspend fun clear(bookId: String)
}

/** Per-book search history as the UI sees it (a seam for tests). */
interface SearchHistoryPort {
    /** Newest first. */
    suspend fun recent(bookId: String, limit: Int = SearchHistoryStore.LIMIT): List<String>
    suspend fun add(bookId: String, query: String)
    suspend fun clear(bookId: String)
}

class SearchHistoryStore(private val db: AppDatabase, private val clock: HybridClock) : SearchHistoryPort {
    override suspend fun recent(bookId: String, limit: Int) = db.searchHistoryDao().recent(bookId, limit)
    override suspend fun add(bookId: String, query: String) = db.searchHistoryDao().put(SearchHistoryEntity(bookId, query, clock.now()))
    override suspend fun clear(bookId: String) = db.searchHistoryDao().clear(bookId)

    companion object { const val LIMIT = 20 }
}
