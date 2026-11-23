package app.fernreader.core.library

/** A candidate book file found by a walk. [folder] is the directory that directly contains it. */
data class FoundFile(val path: String, val folder: String, val size: Long, val mtimeMs: Long)

/** F-010/F-011: where scans look. */
sealed interface ScanScope {
    data object Everywhere : ScanScope
    /** Only this folder and its subfolders (absolute path). */
    data class Folder(val path: String) : ScanScope
}

/**
 * Finds candidate book files (C-008 seam). Both flavors use FileStorageWalker (all-files access); a `play` build
 * may replace it with a walker over user-chosen folders. [emit] is called while the walk is still running, so
 * per-file work can start on the first file (M0 S3).
 */
interface StorageWalker {
    /** The root that [ScanScope.Everywhere] covers; pruning after a full scan is limited to it. */
    val root: String
    suspend fun walk(scope: ScanScope, emit: suspend (FoundFile) -> Unit)
}
