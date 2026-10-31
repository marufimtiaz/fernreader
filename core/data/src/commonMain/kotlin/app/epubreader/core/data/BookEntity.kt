package app.epubreader.core.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A book (synced row, F-115). `authors` is newline-separated. M3 fills language, titleDirection, coverThumb. */
@Entity(tableName = "book", indices = [Index(value = ["identityKey"], unique = true), Index("lastOpenedAt")])
data class BookEntity(
    @PrimaryKey val id: String,
    val identityKey: String,
    val title: String,
    val sortTitle: String,
    val authors: String,
    val sortAuthor: String,
    val language: String?,
    val titleDirection: String, // "auto" | "ltr" | "rtl"
    val coverThumb: String?,
    val addedAt: Long,
    val lastOpenedAt: Long?,
    val hidden: Boolean,
    val updatedAt: Long,
    val deleted: Boolean,
)
