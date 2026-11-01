package app.epubreader.core.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.sample

data class OpenedBookInfo(val identityKey: String, val title: String, val authors: List<String>, val source: SourceRef, val size: Long)
data class OpenRecord(val bookId: String, val position: ReadingPositionEntity?)

/** F-015 / F-021: what the details sheet shows. */
data class BookDetails(
    val id: String, val identityKey: String, val title: String, val authors: List<String>, val language: String?, val coverThumb: String?,
    val addedAt: Long, val lastOpenedAt: Long?, val files: List<BookFileDetail>, val collections: List<String>,
)
data class BookFileDetail(val path: String, val size: Long, val scanned: Boolean)

/** F-025 result: local files deleted, and files that could not be deleted (their books stay hidden instead). */
data class DeleteResult(val deleted: Int, val failed: Int)

/**
 * [deleteFile] deletes one local file (platform); [onBookFilesDeleted] drops what the app derived from a book's file
 * outside the database (its cover thumbnail, passed in).
 */
class LibraryRepository(
    private val db: AppDatabase,
    private val clock: HybridClock,
    private val deleteFile: (String) -> Boolean = { false },
    private val onBookFilesDeleted: (bookId: String, coverThumb: String?) -> Unit = { _, _ -> },
    /** Whether the app holds a lasting read grant for a content:// source (open-with grants often can't be kept). */
    private val grantPersisted: (uri: String) -> Boolean = { true },
    /** Awaited before recording an open (the start-up [dropSessionFiles] must not remove a source opened just now). */
    private val beforeRecordOpened: suspend () -> Unit = {},
    /**
     * Drops what the app derived from book files outside the database and keyed by their source keys
     * (`book_file.path`, i.e. `SourceRef.key`): M8's chunk caches. Called with the `book_cache` rows' deletion
     * ([deleteBooks], with the book id), and for sources the library forgets ([forget], [dropSessionFiles], no id).
     * A file moved elsewhere keeps its old cache until the system trims the cache directory.
     */
    private val onCacheDeleted: suspend (bookId: String?, sourceKeys: List<String>) -> Unit = { _, _ -> },
    /** Where file deletion runs (R-005: never the main thread). */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LibraryOps {
    suspend fun knownBookId(ref: SourceRef): String? = db.libraryDao().bookIdForPath(ref.key)

    /** A plain file path for [bookId] (scanned files preferred; content URIs are not usable from a background job), or null. */
    suspend fun filePath(bookId: String): String? = db.libraryDao().pathsOf(bookId).firstOrNull { !it.contains("://") }

    suspend fun position(bookId: String): ReadingPositionEntity? = db.positionDao().get(bookId)

    suspend fun recordOpened(info: OpenedBookInfo): OpenRecord {
        beforeRecordOpened()
        val session = info.source is SourceRef.Content && !grantPersisted(info.source.key)
        val id = db.libraryDao().recordOpened(
            info.identityKey, info.title, info.authors.joinToString("\n"), info.source.key, info.size, clock.now(),
            source = if (session) "session" else "opened",
        )
        return OpenRecord(id, db.positionDao().get(id))
    }


    /** Derived per-book data for this file version ([digest]), or null (missing or computed for another version). */
    suspend fun cached(bookId: String, kind: String, digest: String): String? = db.bookCacheDao().get(bookId, kind, digest)
    suspend fun storeCache(bookId: String, kind: String, digest: String, json: String) = db.bookCacheDao().put(BookCacheEntity(bookId, kind, digest, json))

    /**
     * At process start: sources that were readable only in the last session (a content:// grant that could not be
     * persisted) are gone; their entries leave the library. Reading data stays by book id for the next open.
     */
    suspend fun dropSessionFiles() {
        val keys = db.libraryDao().sessionPaths()
        db.libraryDao().dropSessionFiles()
        if (keys.isNotEmpty()) onCacheDeleted(null, keys)
    }

    suspend fun forget(ref: SourceRef) {
        db.libraryDao().forgetOpened(ref.key)
        onCacheDeleted(null, listOf(ref.key))
    }

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
    override suspend fun deleteBooks(bookIds: List<String>, deleteFiles: Boolean): DeleteResult = withContext(io) {
        if (!deleteFiles) { hide(bookIds); return@withContext DeleteResult(0, 0) }
        val dao = db.libraryDao()
        var deleted = 0; var failed = 0
        for (id in bookIds) {
            var kept = 0
            val files = dao.filesOf(id)
            for (f in files) {
                if (f.path.startsWith("/") && deleteFile(f.path)) { deleted++; dao.deleteFile(f.path) } else kept++
            }
            failed += kept
            dao.deleteCache(id)
            onCacheDeleted(id, files.map { it.path })
            if (kept == 0) {
                val thumb = dao.byId(id)?.coverThumb?.takeIf { it.isNotEmpty() }
                dao.removeBook(id, clock.now()); onBookFilesDeleted(id, thumb)
            } else hide(listOf(id))
        }
        DeleteResult(deleted, failed)
    }

    /** F-015 / F-021 details, or null for an unknown id. */
    suspend fun details(bookId: String): BookDetails? {
        val b = db.libraryDao().byId(bookId) ?: return null
        val cd = db.collectionDao()
        val names = cd.memberships().filter { it.bookId == bookId && !it.deleted }.mapNotNull { m -> cd.byId(m.collectionId)?.takeIf { !it.deleted }?.name }.sorted()
        return BookDetails(
            b.id, b.identityKey, b.title, b.authors.split('\n').filter(String::isNotBlank), b.language, b.coverThumb?.ifEmpty { null },
            b.addedAt, b.lastOpenedAt,
            db.libraryDao().filesOf(bookId).sortedWith(compareBy({ it.source != "scan" }, { it.path })).map { BookFileDetail(it.path, it.size, it.source == "scan") },
            names,
        )
    }

    fun failures(): Flow<List<ScanFailureEntity>> = db.libraryDao().failures()
    suspend fun removeBook(bookId: String) = db.libraryDao().removeBook(bookId, clock.now())
    suspend fun setCover(bookId: String, thumbPath: String?) = db.libraryDao().setCover(bookId, thumbPath)
    suspend fun coverJobs(): List<CoverJob> = db.libraryDao().coverJobs()
    /** Thumbnail files books point to (including hidden and tombstoned books, which may come back). */
    suspend fun coverPaths(): Set<String> = db.libraryDao().coverPaths().toSet()
}
