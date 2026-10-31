package app.epubreader.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.sample

data class OpenedBookInfo(val identityKey: String, val title: String, val authors: List<String>, val source: SourceRef, val size: Long)
data class OpenRecord(val bookId: String, val position: ReadingPositionEntity?)

class LibraryRepository(private val db: AppDatabase, private val clock: HybridClock) {
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
    )

    fun failures(): Flow<List<ScanFailureEntity>> = db.libraryDao().failures()
    suspend fun removeBook(bookId: String) = db.libraryDao().removeBook(bookId, clock.now())
    suspend fun setCover(bookId: String, thumbPath: String?) = db.libraryDao().setCover(bookId, thumbPath)
    suspend fun coverJobs(): List<CoverJob> = db.libraryDao().coverJobs()
}
