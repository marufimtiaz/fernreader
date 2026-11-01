package app.epubreader.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A bookmarked page (F-041), synced row (F-115). `cfi` is against the original documents. */
@kotlinx.serialization.Serializable
@Entity(tableName = "bookmark", indices = [Index(value = ["bookId"])])
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val cfi: String,
    val spineIndex: Int,
    val fraction: Double,
    val chapter: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean,
)

@Dao
abstract class BookmarkDao {
    @Query("SELECT * FROM bookmark WHERE bookId = :bookId AND deleted = 0")
    abstract fun live(bookId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmark WHERE id = :id")
    abstract suspend fun anyRow(id: String): BookmarkEntity?

    @Upsert abstract suspend fun upsert(row: BookmarkEntity)

    /** One transaction (R-009). */
    @Transaction
    open suspend fun tombstone(id: String, at: Long) {
        val cur = anyRow(id) ?: return
        upsert(cur.copy(deleted = true, updatedAt = at))
    }

    /** Several bookmarks in one transaction (R-009); unknown ids are skipped. */
    @Transaction
    open suspend fun tombstoneAll(ids: List<String>, at: Long) {
        for (id in ids) { val cur = anyRow(id) ?: continue; upsert(cur.copy(deleted = true, updatedAt = at)) }
    }
}

data class Bookmark(
    val id: String,
    val bookId: String,
    val cfi: String,
    val spineIndex: Int,
    val fraction: Double,
    val chapter: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Bookmarks as the UI sees them (a seam for tests). */
interface BookmarkPort {
    /** Live bookmarks of a book (not removed), in book order. */
    fun bookmarks(bookId: String): Flow<List<Bookmark>>
    suspend fun add(bookId: String, cfi: String, spineIndex: Int, fraction: Double, chapter: String): Bookmark
    /** Tombstones the row (the row stays for sync). */
    suspend fun remove(id: String)
    /** Tombstones several rows in one write (all or none). */
    suspend fun removeAll(ids: List<String>)
}

class BookmarkStore(private val db: AppDatabase, private val clock: HybridClock) : BookmarkPort {
    private val comparator = compareBy<Bookmark> { it.spineIndex }.thenBy(CfiOrder) { it.cfi }.thenBy { it.createdAt }

    override fun bookmarks(bookId: String): Flow<List<Bookmark>> =
        db.bookmarkDao().live(bookId).map { rows -> rows.map { it.toDomain() }.sortedWith(comparator) }

    override suspend fun add(bookId: String, cfi: String, spineIndex: Int, fraction: Double, chapter: String): Bookmark {
        val now = clock.now()
        val row = BookmarkEntity(newId(), bookId, cfi, spineIndex, fraction, chapter, now, now, deleted = false)
        db.bookmarkDao().upsert(row)
        return row.toDomain()
    }

    override suspend fun remove(id: String) = db.bookmarkDao().tombstone(id, clock.now())

    override suspend fun removeAll(ids: List<String>) = db.bookmarkDao().tombstoneAll(ids, clock.now())

    private fun BookmarkEntity.toDomain() = Bookmark(id, bookId, cfi, spineIndex, fraction, chapter, createdAt, updatedAt)
}
