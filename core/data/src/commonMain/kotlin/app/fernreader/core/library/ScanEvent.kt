package app.fernreader.core.library

import app.fernreader.core.epub.pkg.FailReason

sealed interface ScanEvent {
    data class Started(val full: Boolean) : ScanEvent
    /** A new or changed file was recorded (unchanged files emit nothing). */
    data class BookAdded(val bookId: String, val path: String) : ScanEvent
    data class Failed(val path: String, val reason: FailReason) : ScanEvent
    /** [added] files recorded, [removed] scanned files gone, [failed] files reported, [files] candidate files seen. */
    data class Complete(val added: Int, val removed: Int, val failed: Int, val files: Int, val ms: Long) : ScanEvent
}

/**
 * What the library screen shows about scanning (F-012 progress). [quiet]: an automatic scan (start, return, a view
 * shown), which shows progress only once it finds a book (C-075: an unchanged scan shows nothing).
 */
data class ScanState(val running: Boolean = false, val found: Int = 0, val lastComplete: ScanEvent.Complete? = null, val quiet: Boolean = false) {
    val showsProgress: Boolean get() = running && (!quiet || found > 0)
}
