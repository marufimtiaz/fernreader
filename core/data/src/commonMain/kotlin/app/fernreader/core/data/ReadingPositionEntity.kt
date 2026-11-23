package app.fernreader.core.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Last reading position of a book (synced row). `cfi` is always against the original documents. */
@kotlinx.serialization.Serializable
@Entity(tableName = "reading_position", indices = [Index(value = ["bookId"], unique = true)])
data class ReadingPositionEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val cfi: String,
    val fraction: Double,
    val spineIndex: Int,
    val updatedAt: Long,
    val deleted: Boolean,
)
