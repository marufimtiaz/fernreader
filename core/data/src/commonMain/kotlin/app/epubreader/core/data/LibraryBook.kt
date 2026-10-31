package app.epubreader.core.data

/**
 * One library entry (F-020): a book that is neither deleted nor hidden, with every file it is known under.
 * [path] is the preferred file to open: a scanned file if there is one, else the one it was opened from.
 */
data class LibraryBook(
    val id: String,
    val title: String,
    val authors: List<String>,
    val titleDirection: String,
    val coverThumb: String?,
    val addedAt: Long,
    val lastOpenedAt: Long?,
    val sortTitle: String,
    val sortAuthor: String,
    val path: String,
    val paths: List<String>,
)

/** What the scanner learned about one file; recorded by [LibraryDao.recordScanned]. */
data class ScannedBook(
    val identityKey: String,
    val title: String,
    val sortTitle: String,
    val authors: String,
    val sortAuthor: String,
    val language: String?,
    val titleDirection: String,
    val path: String,
    val folder: String,
    val size: Long,
    val mtimeMs: Long,
)
