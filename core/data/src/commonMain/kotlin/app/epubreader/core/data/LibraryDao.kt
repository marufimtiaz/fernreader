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

    // --- M3: library and scanning -------------------------------------------------------------------------------

    @Query("SELECT * FROM book WHERE id = :id") abstract suspend fun byId(id: String): BookEntity?
    @Query("SELECT * FROM book_file WHERE source = 'scan'") abstract suspend fun scanFiles(): List<BookFileEntity>
    @Query("SELECT * FROM scan_failure") abstract suspend fun failureRows(): List<ScanFailureEntity>
    @Upsert abstract suspend fun upsertFailure(f: ScanFailureEntity)
    @Query("DELETE FROM scan_failure WHERE path = :path") abstract suspend fun deleteFailure(path: String)

    @Query("UPDATE book_file SET lastSeenScan = :scanId WHERE path IN (:paths)")
    protected abstract suspend fun markSeenChunk(paths: List<String>, scanId: Long)
    @Query("UPDATE scan_failure SET lastSeenScan = :scanId WHERE path IN (:paths)")
    protected abstract suspend fun markFailuresSeenChunk(paths: List<String>, scanId: Long)

    /** Unchanged files of a scan; chunked below SQLite's parameter limit. */
    @Transaction
    open suspend fun markSeen(paths: List<String>, failures: List<String>, scanId: Long) {
        paths.chunked(900).forEach { markSeenChunk(it, scanId) }
        failures.chunked(900).forEach { markFailuresSeenChunk(it, scanId) }
    }

    /** After a completed walk: scanned files under [prefix] that were not seen are gone. Opened files are kept. */
    @Query("DELETE FROM book_file WHERE source = 'scan' AND lastSeenScan < :scanId AND substr(path, 1, length(:prefix)) = :prefix")
    abstract suspend fun pruneScan(scanId: Long, prefix: String): Int
    @Query("DELETE FROM scan_failure WHERE lastSeenScan < :scanId AND substr(path, 1, length(:prefix)) = :prefix")
    abstract suspend fun pruneFailures(scanId: Long, prefix: String): Int

    /** Books left with no file at all disappear (tombstone; reading data stays keyed by the id). Hidden books stay. */
    @Query("UPDATE book SET deleted = 1, updatedAt = :now WHERE deleted = 0 AND hidden = 0 AND id NOT IN (SELECT bookId FROM book_file)")
    abstract suspend fun tombstoneOrphans(now: Long): Int

    @Query("UPDATE book SET identityKey = :key, updatedAt = :now WHERE id = :id")
    protected abstract suspend fun setIdentity(id: String, key: String, now: Long)
    @Query(
        "UPDATE book SET title = :title, sortTitle = :sortTitle, authors = :authors, sortAuthor = :sortAuthor, language = :language, " +
            "titleDirection = :dir, deleted = 0, updatedAt = :now WHERE id = :id",
    )
    protected abstract suspend fun updateScanned(id: String, title: String, sortTitle: String, authors: String, sortAuthor: String, language: String?, dir: String, now: Long)

    /**
     * Records one scanned file in one transaction and returns its book id. A book is found by identity; else, if the
     * same path held a book with the same package identifier (the first half of the identity key), that book's file
     * changed in place and it stays the same book (C-009). A different book at a known path takes the path over; the
     * old book keeps its other files and reading data (Review Focus 4 of the M3 plan).
     */
    @Transaction
    open suspend fun recordScanned(s: ScannedBook, scanId: Long, now: Long): String {
        var book = byIdentity(s.identityKey)
        if (book == null) {
            val prev = bookIdForPath(s.path)?.let { byId(it) }
            if (prev != null && prev.identityKey.substringBefore('-') == s.identityKey.substringBefore('-')) {
                setIdentity(prev.id, s.identityKey, now)
                book = prev
            }
        }
        val id = if (book != null) {
            updateScanned(book.id, s.title, s.sortTitle, s.authors, s.sortAuthor, s.language, s.titleDirection, now)
            book.id
        } else newId().also {
            insertBook(
                BookEntity(
                    id = it, identityKey = s.identityKey, title = s.title, sortTitle = s.sortTitle, authors = s.authors,
                    sortAuthor = s.sortAuthor, language = s.language, titleDirection = s.titleDirection, coverThumb = null,
                    addedAt = now, lastOpenedAt = null, hidden = false, updatedAt = now, deleted = false,
                ),
            )
        }
        upsertFile(BookFileEntity(path = s.path, bookId = id, source = "scan", folder = s.folder, size = s.size, mtimeMs = s.mtimeMs, lastSeenScan = scanId))
        deleteFailure(s.path)
        return id
    }

    @Query(
        "SELECT b.id AS id, b.title AS title, b.authors AS authors, b.titleDirection AS titleDirection, b.coverThumb AS coverThumb, " +
            "b.addedAt AS addedAt, b.lastOpenedAt AS lastOpenedAt, b.sortTitle AS sortTitle, b.sortAuthor AS sortAuthor, " +
            "(SELECT f.path FROM book_file f WHERE f.bookId = b.id ORDER BY f.source = 'scan' DESC, f.path LIMIT 1) AS path, " +
            "(SELECT GROUP_CONCAT(f.path, char(10)) FROM book_file f WHERE f.bookId = b.id) AS paths " +
            "FROM book b WHERE b.deleted = 0 AND b.hidden = 0 AND EXISTS (SELECT 1 FROM book_file f WHERE f.bookId = b.id)",
    )
    abstract fun library(): Flow<List<LibraryRow>>

    @Query("SELECT * FROM scan_failure ORDER BY folder, name") abstract fun failures(): Flow<List<ScanFailureEntity>>
    @Query("SELECT id FROM book WHERE deleted = 0 AND coverThumb IS NULL AND EXISTS (SELECT 1 FROM book_file f WHERE f.bookId = book.id)")
    abstract suspend fun booksWithoutCover(): List<String>
    @Query("UPDATE book SET coverThumb = :thumb WHERE id = :id") abstract suspend fun setCover(id: String, thumb: String?)
    @Query("UPDATE book SET deleted = 1, updatedAt = :now WHERE id = :id") protected abstract suspend fun tombstone(id: String, now: Long)
    @Query("DELETE FROM book_file WHERE bookId = :id") protected abstract suspend fun deleteFiles(id: String)

    /** F-018 "remove entry": the book leaves the library; its reading data stays keyed by the id (a later scan brings it back). */
    @Transaction
    open suspend fun removeBook(id: String, now: Long) { deleteFiles(id); tombstone(id, now) }
}

data class LibraryRow(
    val id: String, val title: String, val authors: String, val titleDirection: String, val coverThumb: String?,
    val addedAt: Long, val lastOpenedAt: Long?, val sortTitle: String, val sortAuthor: String, val path: String, val paths: String,
)
