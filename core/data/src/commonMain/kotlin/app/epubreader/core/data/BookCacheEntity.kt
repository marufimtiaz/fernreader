package app.epubreader.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert

/**
 * Data derived from a book file and rebuildable from it (not synced, not backed up), e.g. per-section text sizes for
 * page estimates (M4). [digest] is the file's central-directory digest: a changed file does not match and recomputes.
 */
@Entity(tableName = "book_cache", primaryKeys = ["bookId", "kind"])
data class BookCacheEntity(val bookId: String, val kind: String, val digest: String, val json: String)

@Dao
interface BookCacheDao {
    @Query("SELECT json FROM book_cache WHERE bookId = :bookId AND kind = :kind AND digest = :digest")
    suspend fun get(bookId: String, kind: String, digest: String): String?

    @Upsert suspend fun put(row: BookCacheEntity)
}
