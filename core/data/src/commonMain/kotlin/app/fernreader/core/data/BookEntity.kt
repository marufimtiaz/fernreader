package app.fernreader.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A book (synced row, F-115). `authors` is newline-separated. M3 fills language, titleDirection, coverThumb.
 *
 * C-096: a separate copy is its own row with its own [identityKey] (see [SeparateCopies.identityKey]), so everything
 * keyed by book id or identity treats it as a book of its own. [identityKey] of an ordinary book is what its file
 * yields; a file with that content joins that book unless [SeparateCopies] gives it to a separate copy.
 */
@Entity(tableName = "book", indices = [Index(value = ["identityKey"], unique = true), Index("lastOpenedAt"), Index("separateOf")])
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
    /** When [hidden] last changed (F-025): restore merges the user's hide/un-hide by it, not by [updatedAt], which scans bump. */
    @ColumnInfo(defaultValue = "0") val hiddenUpdatedAt: Long = 0,
    /** C-096: the identity key of this separate copy's content; null for every other book. */
    val separateOf: String? = null,
    /** C-096: where this copy's file last was, and its modified time: how it finds its file when it has none. */
    val separatePath: String? = null,
    val separateMtimeMs: Long? = null,
)
