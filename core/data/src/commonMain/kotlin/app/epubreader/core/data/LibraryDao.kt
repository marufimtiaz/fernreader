package app.epubreader.core.data

import androidx.room.Dao
import app.epubreader.core.epub.meta.MetadataReader
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class LibraryDao {
    @Query("SELECT * FROM book WHERE identityKey = :key") abstract suspend fun byIdentity(key: String): BookEntity?
    @Query("SELECT bookId FROM book_file WHERE path = :path") abstract suspend fun bookIdForPath(path: String): String?
    /** A book's file paths, scanned files first (the idle search-index job wants a plain file it can open later). */
    @Query("SELECT path FROM book_file WHERE bookId = :bookId ORDER BY (source = 'scan') DESC, path") abstract suspend fun pathsOf(bookId: String): List<String>
    @Insert abstract suspend fun insertBook(b: BookEntity)
    @Upsert abstract suspend fun upsertFile(f: BookFileEntity)

    /** Opening a book marks it read now (and brings back a tombstoned one); the scanner owns its metadata. */
    @Query("UPDATE book SET lastOpenedAt = :at, updatedAt = :at, deleted = 0 WHERE id = :id")
    abstract suspend fun touchOpened(id: String, at: Long)
    @Query("SELECT * FROM book_file WHERE path = :path") abstract suspend fun fileRow(path: String): BookFileEntity?

    @Query("DELETE FROM book_file WHERE path = :path AND source IN ('opened', 'session')") abstract suspend fun forgetOpened(path: String)
    @Query("SELECT path FROM book_file WHERE source = 'session'") abstract suspend fun sessionPaths(): List<String>
    @Query("DELETE FROM book_file WHERE source = 'session'") abstract suspend fun dropSessionFiles()

    /** Book by identity (created on first sight) + its source; one transaction. Returns the book id. */
    @Transaction
    open suspend fun recordOpened(identityKey: String, title: String, authors: String, path: String, size: Long, now: Long, source: String = "opened"): String {
        val id = byIdentity(identityKey)?.id ?: newId().also {
            insertBook(
                BookEntity(
                    id = it, identityKey = identityKey, title = title, sortTitle = MetadataReader.sortKey(title), authors = authors,
                    sortAuthor = authors.substringBefore('\n').lowercase(), language = null, titleDirection = "auto",
                    coverThumb = null, addedAt = now, lastOpenedAt = now, hidden = false, updatedAt = now, deleted = false,
                ),
            )
        }
        touchOpened(id, now)
        // A scanned file stays a scanned file (final review I1): an "opened" row would never be pruned when the file
        // is deleted, and the next scan would load it again as changed.
        val row = fileRow(path)
        if (row?.source != "scan" || row.bookId != id) {
            upsertFile(BookFileEntity(path = path, bookId = id, source = source, folder = "", size = size, mtimeMs = 0, lastSeenScan = 0))
        }
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
    @Query("UPDATE book SET coverThumb = NULL WHERE id = :id") protected abstract suspend fun forgetCover(id: String)
    @Query(
        "UPDATE book SET title = :title, sortTitle = :sortTitle, authors = :authors, sortAuthor = :sortAuthor, language = :language, " +
            // C-076: a book that had left the library (tombstoned) and is found again is newly added (sorts first)
            "titleDirection = :dir, addedAt = CASE WHEN deleted = 1 THEN :now ELSE addedAt END, deleted = 0, updatedAt = :now WHERE id = :id",
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
        val before = fileRow(s.path)
        var book = byIdentity(s.identityKey)
        if (book == null) {
            val prev = bookIdForPath(s.path)?.let { byId(it) }
            val idHash = s.identityKey.substringBefore('-')
            // Only for books that have a package identifier: all identifier-less books share one hash (review M3).
            if (prev != null && idHash != NO_IDENTIFIER_HASH && prev.identityKey.substringBefore('-') == idHash) {
                setIdentity(prev.id, s.identityKey, now)
                book = prev
            }
        }
        val id = if (book != null) {
            updateScanned(book.id, s.title, s.sortTitle, s.authors, s.sortAuthor, s.language, s.titleDirection, now)
            // This book's own file changed in place (M3 deferred): its cover may have changed too, so look again.
            if (before?.bookId == book.id && (before.size != s.size || before.mtimeMs != s.mtimeMs)) forgetCover(book.id)
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

    @Query(LIBRARY_QUERY) abstract fun library(): Flow<List<LibraryRow>>
    /** F-025: books removed from the library only (Settings → Hidden books). */
    @Query(HIDDEN_QUERY) abstract fun hiddenLibrary(): Flow<List<LibraryRow>>
    @Query("UPDATE book SET hidden = :hidden, hiddenUpdatedAt = :now, updatedAt = :now WHERE id IN (:ids) AND hidden != :hidden")
    abstract suspend fun setHidden(ids: List<String>, hidden: Boolean, now: Long)
    @Query("SELECT * FROM book_file WHERE bookId = :bookId") abstract suspend fun filesOf(bookId: String): List<BookFileEntity>
    @Query("DELETE FROM book_file WHERE path = :path") abstract suspend fun deleteFile(path: String)
    @Query("DELETE FROM book_cache WHERE bookId = :bookId") abstract suspend fun deleteCache(bookId: String)
    @Query("DELETE FROM book_pages WHERE bookId = :bookId") abstract suspend fun deletePages(bookId: String)
    /** The same rows read once (a reader connection): used while a scan holds the writer, see LibraryRepository. */
    @Query(LIBRARY_QUERY) abstract suspend fun librarySnapshot(): List<LibraryRow>

    @Query("SELECT * FROM scan_failure ORDER BY folder, name") abstract fun failures(): Flow<List<ScanFailureEntity>>
    /** Books whose cover was never looked for, with a local file to read it from (`coverThumb` "" = none found). */
    @Query(
        "SELECT b.id AS bookId, (SELECT f.path FROM book_file f WHERE f.bookId = b.id AND substr(f.path, 1, 1) = '/' ORDER BY f.source = 'scan' DESC LIMIT 1) AS path " +
            "FROM book b WHERE b.deleted = 0 AND b.coverThumb IS NULL AND path IS NOT NULL",
    )
    abstract suspend fun coverJobs(): List<CoverJob>
    @Query("SELECT coverThumb FROM book WHERE coverThumb IS NOT NULL AND coverThumb != ''") abstract suspend fun coverPaths(): List<String>
    @Query("UPDATE book SET coverThumb = :thumb WHERE id = :id") abstract suspend fun setCover(id: String, thumb: String?)
    @Query("UPDATE book SET deleted = 1, updatedAt = :now WHERE id = :id") protected abstract suspend fun tombstone(id: String, now: Long)
    @Query("DELETE FROM book_file WHERE bookId = :id") protected abstract suspend fun deleteFiles(id: String)

    // --- P-063 (benchmark build only): seeded books, ids starting with "seed-" -----------------------------------
    @Query("SELECT coverThumb FROM book WHERE substr(id, 1, 5) = 'seed-' AND coverThumb IS NOT NULL") abstract suspend fun seedThumbs(): List<String>
    @Query("DELETE FROM book_file WHERE substr(bookId, 1, 5) = 'seed-'") protected abstract suspend fun deleteSeedFiles()
    @Query("DELETE FROM book WHERE substr(id, 1, 5) = 'seed-'") protected abstract suspend fun deleteSeedBooks()
    @Transaction open suspend fun deleteSeeds() { deleteSeedFiles(); deleteSeedBooks() }
    @Transaction open suspend fun insertSeeds(books: List<BookEntity>, files: List<BookFileEntity>) {
        books.forEach { insertBook(it) }; files.forEach { upsertFile(it) }
    }

    /** F-018 "remove entry": the book leaves the library; its reading data stays keyed by the id (a later scan brings it back). */
    @Transaction
    open suspend fun removeBook(id: String, now: Long) { deleteFiles(id); tombstone(id, now) }
}

/** The identity-key half of a book with no package identifier. */
private val NO_IDENTIFIER_HASH = app.epubreader.core.epub.meta.BookIdentity.of(null, "").substringBefore('-')

private const val LIBRARY_COLUMNS =
    "SELECT b.id AS id, b.title AS title, b.authors AS authors, b.titleDirection AS titleDirection, b.coverThumb AS coverThumb, " +
        "b.addedAt AS addedAt, b.lastOpenedAt AS lastOpenedAt, b.sortTitle AS sortTitle, b.sortAuthor AS sortAuthor, " +
        "(SELECT f.path FROM book_file f WHERE f.bookId = b.id ORDER BY f.source = 'scan' DESC, f.path LIMIT 1) AS path, " +
        "(SELECT GROUP_CONCAT(f.path, char(10)) FROM book_file f WHERE f.bookId = b.id) AS paths, " +
        "(SELECT GROUP_CONCAT(m.collectionId, char(10)) FROM collection_book m JOIN collection c ON c.id = m.collectionId " +
        "WHERE m.bookId = b.id AND m.deleted = 0 AND c.deleted = 0) AS collectionIds " +
        "FROM book b WHERE b.deleted = 0 AND EXISTS (SELECT 1 FROM book_file f WHERE f.bookId = b.id) "

private const val LIBRARY_QUERY = LIBRARY_COLUMNS + "AND b.hidden = 0"
private const val HIDDEN_QUERY = LIBRARY_COLUMNS + "AND b.hidden = 1 ORDER BY b.sortTitle"

data class LibraryRow(
    val id: String, val title: String, val authors: String, val titleDirection: String, val coverThumb: String?,
    val addedAt: Long, val lastOpenedAt: Long?, val sortTitle: String, val sortAuthor: String, val path: String, val paths: String,
    val collectionIds: String?,
)

data class CoverJob(val bookId: String, val path: String)
