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
    /** Live collections the book is in (F-026). */
    val collectionIds: List<String> = emptyList(),
    /** C-096: a separate copy, or the entry one was split from: they look the same, so the library shows their folder. */
    val split: Boolean = false,
) {
    /** The folder of the file [path] opens, for a [split] entry; null for other books and for a file with no folder path. */
    val splitFolder: String? get() = if (split && path.startsWith("/")) path.substringBeforeLast('/').ifEmpty { "/" } else null
    /** Folders that directly contain a local file of this book (F-017). */
    val folders: List<String> get() = paths.filter { it.startsWith("/") }.map { it.substringBeforeLast('/').ifEmpty { "/" } }.distinct()
}

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
