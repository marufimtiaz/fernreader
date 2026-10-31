package app.epubreader.core.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A user collection (F-026). Synced row (F-115): stable id, change timestamp, tombstone. */
@Entity(tableName = "collection")
data class CollectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean,
)

/** A book in a collection. Synced row; removing a book tombstones the row, re-adding revives it (same id). */
@Entity(tableName = "collection_book", indices = [Index(value = ["collectionId", "bookId"], unique = true), Index("bookId")])
data class CollectionBookEntity(
    @PrimaryKey val id: String,
    val collectionId: String,
    val bookId: String,
    val updatedAt: Long,
    val deleted: Boolean,
)

@Dao
abstract class CollectionDao {
    @Query("SELECT * FROM collection WHERE deleted = 0 ORDER BY name COLLATE NOCASE, id") abstract fun live(): Flow<List<CollectionEntity>>
    @Query("SELECT * FROM collection") abstract suspend fun all(): List<CollectionEntity>
    @Query("SELECT * FROM collection WHERE id = :id") abstract suspend fun byId(id: String): CollectionEntity?
    @Upsert abstract suspend fun upsert(c: CollectionEntity)
    @Query("SELECT * FROM collection_book") abstract suspend fun memberships(): List<CollectionBookEntity>
    @Query("SELECT * FROM collection_book WHERE collectionId = :collectionId AND bookId = :bookId")
    abstract suspend fun membership(collectionId: String, bookId: String): CollectionBookEntity?
    @Upsert abstract suspend fun upsertMembership(m: CollectionBookEntity)

    @Query("UPDATE collection SET name = :name, updatedAt = :now WHERE id = :id") abstract suspend fun rename(id: String, name: String, now: Long)
    @Query("UPDATE collection SET deleted = 1, updatedAt = :now WHERE id = :id") protected abstract suspend fun tombstone(id: String, now: Long)
    @Query("UPDATE collection_book SET deleted = 1, updatedAt = :now WHERE collectionId = :id AND deleted = 0")
    protected abstract suspend fun tombstoneMembers(id: String, now: Long)

    /** F-026: the collection goes; its books stay in the library. */
    @Transaction
    open suspend fun delete(id: String, now: Long) { tombstone(id, now); tombstoneMembers(id, now) }

    /** Adds or removes ([present] = false) books; an existing row keeps its id. */
    @Transaction
    open suspend fun setMembers(collectionId: String, bookIds: List<String>, present: Boolean, now: () -> Long) {
        for (b in bookIds.distinct()) {
            val cur = membership(collectionId, b)
            if (cur == null && !present) continue
            if (cur != null && cur.deleted == !present) continue
            upsertMembership(CollectionBookEntity(cur?.id ?: newId(), collectionId, b, now(), deleted = !present))
        }
    }
}

/** F-026: a name is required, and two collections never share one (ignoring case). */
object CollectionNames {
    enum class Problem { BLANK, TAKEN }

    fun problem(name: String, existing: List<CollectionEntity>, renaming: String? = null): Problem? {
        val n = name.trim()
        if (n.isEmpty()) return Problem.BLANK
        return if (existing.any { !it.deleted && it.id != renaming && it.name.trim().equals(n, ignoreCase = true) }) Problem.TAKEN else null
    }
}
