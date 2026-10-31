package app.epubreader.core.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
abstract class PositionDao {
    @Query("SELECT * FROM reading_position WHERE bookId = :bookId AND deleted = 0")
    abstract suspend fun get(bookId: String): ReadingPositionEntity?

    @Query("SELECT * FROM reading_position WHERE bookId = :bookId")
    abstract suspend fun anyRow(bookId: String): ReadingPositionEntity?

    @Upsert abstract suspend fun upsert(row: ReadingPositionEntity)

    /** One transaction (R-009). Never replaces a row with a newer or equal `updatedAt`; returns false then. */
    @Transaction
    open suspend fun put(bookId: String, cfi: String, fraction: Double, spineIndex: Int, at: Long): Boolean {
        val cur = anyRow(bookId)
        if (cur != null && cur.updatedAt >= at) return false
        upsert(ReadingPositionEntity(cur?.id ?: newId(), bookId, cfi, fraction, spineIndex, at, deleted = false))
        return true
    }
}
