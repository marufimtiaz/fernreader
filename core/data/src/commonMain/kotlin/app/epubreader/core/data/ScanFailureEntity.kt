package app.epubreader.core.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A file that looks like an EPUB but could not be loaded (F-016, R-002, R-004). Device-local, not synced.
 * `reason` is a `FailReason` name. Rows not seen by a completed scan are removed.
 */
@Entity(tableName = "scan_failure")
data class ScanFailureEntity(
    @PrimaryKey val path: String,
    val folder: String,
    val name: String,
    val reason: String,
    val size: Long,
    val mtimeMs: Long,
    val lastSeenScan: Long,
)
