package app.fernreader.core.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Where a book's file is (device-local, not synced). `path` is a file path or a content URI.
 * `source` = "opened" (picker / open-with / bench) or "scan" (M3). M3's scanner prunes only "scan" rows.
 */
@Entity(tableName = "book_file", indices = [Index("bookId")])
data class BookFileEntity(
    @PrimaryKey val path: String,
    val bookId: String,
    val source: String,
    val folder: String,
    val size: Long,
    val mtimeMs: Long,
    val lastSeenScan: Long,
)
