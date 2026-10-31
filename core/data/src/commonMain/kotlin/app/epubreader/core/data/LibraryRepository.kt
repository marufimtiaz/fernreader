package app.epubreader.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class OpenedBookInfo(val identityKey: String, val title: String, val authors: List<String>, val source: SourceRef, val size: Long)
data class OpenRecord(val bookId: String, val position: ReadingPositionEntity?)
data class RecentBook(val id: String, val title: String, val authors: List<String>, val source: SourceRef)

class LibraryRepository(private val db: AppDatabase, private val clock: HybridClock) {
    suspend fun knownBookId(ref: SourceRef): String? = db.libraryDao().bookIdForPath(ref.key)

    suspend fun position(bookId: String): ReadingPositionEntity? = db.positionDao().get(bookId)

    suspend fun recordOpened(info: OpenedBookInfo): OpenRecord {
        val id = db.libraryDao().recordOpened(
            info.identityKey, info.title, info.authors.joinToString("\n"), info.source.key, info.size, clock.now(),
        )
        return OpenRecord(id, db.positionDao().get(id))
    }

    fun recent(): Flow<List<RecentBook>> = db.libraryDao().recent().map { rows ->
        rows.map { RecentBook(it.id, it.title, it.authors.split('\n').filter(String::isNotBlank), SourceRef.fromKey(it.path)) }
    }

    suspend fun forget(ref: SourceRef) = db.libraryDao().forgetOpened(ref.key)

    fun library(): Flow<List<LibraryBook>> = db.libraryDao().library().map { rows ->
        rows.map {
            LibraryBook(
                it.id, it.title, it.authors.split('\n').filter(String::isNotBlank), it.titleDirection, it.coverThumb, it.addedAt,
                it.lastOpenedAt, it.sortTitle, it.sortAuthor, it.path, it.paths.split('\n'),
            )
        }
    }

    fun failures(): Flow<List<ScanFailureEntity>> = db.libraryDao().failures()
    suspend fun removeBook(bookId: String) = db.libraryDao().removeBook(bookId, clock.now())
    suspend fun setCover(bookId: String, thumbPath: String?) = db.libraryDao().setCover(bookId, thumbPath)
    suspend fun booksWithoutCover(): List<String> = db.libraryDao().booksWithoutCover()
}
