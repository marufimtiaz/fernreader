package app.epubreader.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class RecentBookRow(val id: String, val title: String, val authors: String, val path: String)

@Dao
abstract class LibraryDao {
    @Query("SELECT * FROM book WHERE identityKey = :key") abstract suspend fun byIdentity(key: String): BookEntity?
    @Query("SELECT bookId FROM book_file WHERE path = :path") abstract suspend fun bookIdForPath(path: String): String?
    @Insert abstract suspend fun insertBook(b: BookEntity)
    @Upsert abstract suspend fun upsertFile(f: BookFileEntity)

    @Query(
        "UPDATE book SET title = :title, sortTitle = :sortTitle, authors = :authors, sortAuthor = :sortAuthor, " +
            "lastOpenedAt = :at, updatedAt = :at, deleted = 0 WHERE id = :id",
    )
    abstract suspend fun touchOpened(id: String, title: String, sortTitle: String, authors: String, sortAuthor: String, at: Long)

    /** One row per book that was opened through a picker/open-with/bench path, newest first (M2 home screen). */
    @Query(
        "SELECT b.id AS id, b.title AS title, b.authors AS authors, MAX(f.path) AS path " +
            "FROM book b JOIN book_file f ON f.bookId = b.id " +
            "WHERE b.deleted = 0 AND b.hidden = 0 AND f.source = 'opened' " +
            "GROUP BY b.id ORDER BY b.lastOpenedAt DESC LIMIT 200",
    )
    abstract fun recent(): Flow<List<RecentBookRow>>

    @Query("DELETE FROM book_file WHERE path = :path AND source = 'opened'") abstract suspend fun forgetOpened(path: String)

    /** Book by identity (created on first sight) + its source; one transaction. Returns the book id. */
    @Transaction
    open suspend fun recordOpened(identityKey: String, title: String, authors: String, path: String, size: Long, now: Long): String {
        val id = byIdentity(identityKey)?.id ?: newId().also {
            insertBook(
                BookEntity(
                    id = it, identityKey = identityKey, title = title, sortTitle = title.lowercase(), authors = authors,
                    sortAuthor = authors.substringBefore('\n').lowercase(), language = null, titleDirection = "auto",
                    coverThumb = null, addedAt = now, lastOpenedAt = now, hidden = false, updatedAt = now, deleted = false,
                ),
            )
        }
        touchOpened(id, title, title.lowercase(), authors, authors.substringBefore('\n').lowercase(), now)
        upsertFile(BookFileEntity(path = path, bookId = id, source = "opened", folder = "", size = size, mtimeMs = 0, lastSeenScan = 0))
        return id
    }
}
