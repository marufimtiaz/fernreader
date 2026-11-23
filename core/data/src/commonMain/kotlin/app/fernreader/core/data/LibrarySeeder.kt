package app.fernreader.core.data

import app.fernreader.core.epub.meta.MetadataReader

/**
 * P-063 release check (benchmark build only, bench command `seedLibrary N`): fills the library with [n] extra books
 * so the grid can be scrolled with 1000 entries. Each seed copies a real library book that has a cover (title with a
 * number, same authors) and gets its **own** copy of that book's thumbnail, so covers load from the thumbnail cache
 * as they would for 1000 different books. Seeds have ids starting with [PREFIX] and a file row under `/seed/`
 * (source "seed": scans neither prune nor tombstone them). `seed(0)` removes them and their thumbnails.
 */
class LibrarySeeder(
    private val db: AppDatabase,
    private val clock: HybridClock,
    /** Copies the thumbnail at `from` for the seed `id`; returns the copy's path. */
    private val copyThumb: (from: String, id: String) -> String,
    private val deleteThumb: (String) -> Unit,
) {
    /** Replaces any earlier seeds with [n] new ones; returns how many were added. */
    suspend fun seed(n: Int): Int {
        val dao = db.libraryDao()
        val old = dao.seedThumbs()
        dao.deleteSeeds()
        old.forEach(deleteThumb)
        if (n <= 0) return 0
        val real = dao.librarySnapshot().filter { !it.id.startsWith(PREFIX) }
        val templates = real.filter { !it.coverThumb.isNullOrEmpty() }.ifEmpty { real }
        if (templates.isEmpty()) return 0
        val now = clock.now()
        val books = ArrayList<BookEntity>(n); val files = ArrayList<BookFileEntity>(n)
        for (i in 1..n) {
            val t = templates[(i - 1) % templates.size]
            val id = PREFIX + i.toString().padStart(4, '0')
            val title = "${t.title} · $i"
            books += BookEntity(
                id = id, identityKey = id, title = title, sortTitle = MetadataReader.sortKey(title), authors = t.authors,
                sortAuthor = t.sortAuthor, language = null, titleDirection = t.titleDirection,
                coverThumb = t.coverThumb?.takeIf { it.isNotEmpty() }?.let { copyThumb(it, id) },
                addedAt = now - i, lastOpenedAt = null, hidden = false, updatedAt = now, deleted = false,
            )
            files += BookFileEntity(path = "/seed/$id.epub", bookId = id, source = "seed", folder = "/seed", size = 0, mtimeMs = 0, lastSeenScan = 0)
        }
        dao.insertSeeds(books, files)
        return n
    }

    companion object { const val PREFIX = "seed-" }
}
