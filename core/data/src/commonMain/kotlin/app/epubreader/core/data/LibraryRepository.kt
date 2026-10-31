package app.epubreader.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.sample

data class OpenedBookInfo(val identityKey: String, val title: String, val authors: List<String>, val source: SourceRef, val size: Long)
data class OpenRecord(val bookId: String, val position: ReadingPositionEntity?)

/** F-025 result: local files deleted, and files that could not be deleted (their books stay hidden instead). */
data class DeleteResult(val deleted: Int, val failed: Int)

/**
 * [deleteFile] deletes one local file (platform); [onBookFilesDeleted] drops what the app derived from a book's file
 * outside the database (its cover thumbnail).
 */
class LibraryRepository(
    private val db: AppDatabase,
    private val clock: HybridClock,
    private val deleteFile: (String) -> Boolean = { false },
    private val onBookFilesDeleted: (bookId: String) -> Unit = {},
) : LibraryOps {
    suspend fun knownBookId(ref: SourceRef): String? = db.libraryDao().bookIdForPath(ref.key)

    /** A plain file path for [bookId] (scanned files preferred; content URIs are not usable from a background job), or null. */
    suspend fun filePath(bookId: String): String? = db.libraryDao().pathsOf(bookId).firstOrNull { !it.contains("://") }

    suspend fun position(bookId: String): ReadingPositionEntity? = db.positionDao().get(bookId)

    suspend fun recordOpened(info: OpenedBookInfo): OpenRecord {
        val id = db.libraryDao().recordOpened(
            info.identityKey, info.title, info.authors.joinToString("\n"), info.source.key, info.size, clock.now(),
        )
        return OpenRecord(id, db.positionDao().get(id))
    }


    /** Derived per-book data for this file version ([digest]), or null (missing or computed for another version). */
    suspend fun cached(bookId: String, kind: String, digest: String): String? = db.bookCacheDao().get(bookId, kind, digest)
    suspend fun storeCache(bookId: String, kind: String, digest: String, json: String) = db.bookCacheDao().put(BookCacheEntity(bookId, kind, digest, json))

    suspend fun forget(ref: SourceRef) = db.libraryDao().forgetOpened(ref.key)

    fun library(): Flow<List<LibraryBook>> = db.libraryDao().library().map { rows -> rows.map(::toBook) }

    /**
     * [library] plus a fresh read each time [added] changes (at most every [everyMs]). During a scan the workers hold
     * the database's writer connection, and Room's change tracking needs it too, so the plain flow only updates when
     * the scan ends; the extra reads use a reader connection, so books appear as they are found (F-012, P-050).
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun libraryLive(added: Flow<Int>, everyMs: Long = 150): Flow<List<LibraryBook>> = merge(
        library(),
        added.distinctUntilChanged().filter { it > 0 }.sample(everyMs).map { db.libraryDao().librarySnapshot().map(::toBook) },
    )

    private fun toBook(it: LibraryRow) = LibraryBook(
        it.id, it.title, it.authors.split('\n').filter(String::isNotBlank), it.titleDirection, it.coverThumb, it.addedAt,
        it.lastOpenedAt, it.sortTitle, it.sortAuthor, it.path, it.paths.split('\n'),
        it.collectionIds?.split('\n')?.filter(String::isNotEmpty) ?: emptyList(),
    )

    // --- M7: collections (F-026, F-024) ------------------------------------------------------------------------

    override fun collections(): Flow<List<CollectionEntity>> = db.collectionDao().live()

    /** [name] must pass [CollectionNames.problem]; returns the new collection's id. */
    override suspend fun createCollection(name: String): String {
        val now = clock.now()
        return newId().also { db.collectionDao().upsert(CollectionEntity(it, name.trim(), now, now, deleted = false)) }
    }
    override suspend fun renameCollection(id: String, name: String) = db.collectionDao().rename(id, name.trim(), clock.now())
    override suspend fun deleteCollection(id: String) = db.collectionDao().delete(id, clock.now())
    override suspend fun addToCollection(id: String, bookIds: List<String>) = db.collectionDao().setMembers(id, bookIds, present = true, clock::now)
    override suspend fun removeFromCollection(id: String, bookIds: List<String>) = db.collectionDao().setMembers(id, bookIds, present = false, clock::now)

    // --- M7: hide and delete (F-025) ---------------------------------------------------------------------------

    fun hiddenBooks(): Flow<List<LibraryBook>> = db.libraryDao().hiddenLibrary().map { rows -> rows.map(::toBook) }
    override suspend fun hide(bookIds: List<String>) = bookIds.chunked(900).forEach { db.libraryDao().setHidden(it, true, clock.now()) }
    suspend fun unhide(bookIds: List<String>) = bookIds.chunked(900).forEach { db.libraryDao().setHidden(it, false, clock.now()) }

    /**
     * Without [deleteFiles] the books are hidden (F-025: a rescan must not bring them back). With it, every local copy
     * is deleted and the entry removed; reading data stays keyed by the id, so the same book found later resumes.
     * A book with a file that could not be deleted (or a content:// copy) is hidden instead, so it does not return.
     */
    override suspend fun deleteBooks(bookIds: List<String>, deleteFiles: Boolean): DeleteResult {
        if (!deleteFiles) { hide(bookIds); return DeleteResult(0, 0) }
        val dao = db.libraryDao()
        var deleted = 0; var failed = 0
        for (id in bookIds) {
            var kept = 0
            for (f in dao.filesOf(id)) {
                if (f.path.startsWith("/") && deleteFile(f.path)) { deleted++; dao.deleteFile(f.path) } else kept++
            }
            failed += kept
            dao.deleteCache(id)
            if (kept == 0) { dao.removeBook(id, clock.now()); onBookFilesDeleted(id) } else hide(listOf(id))
        }
        return DeleteResult(deleted, failed)
    }

    fun failures(): Flow<List<ScanFailureEntity>> = db.libraryDao().failures()
    suspend fun removeBook(bookId: String) = db.libraryDao().removeBook(bookId, clock.now())
    suspend fun setCover(bookId: String, thumbPath: String?) = db.libraryDao().setCover(bookId, thumbPath)
    suspend fun coverJobs(): List<CoverJob> = db.libraryDao().coverJobs()
}
