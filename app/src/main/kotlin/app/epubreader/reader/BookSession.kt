package app.epubreader.reader

import app.epubreader.core.data.SourceRef
import app.epubreader.core.epub.PositionMap
import app.epubreader.core.epub.chunk.ChunkCacheException
import app.epubreader.core.epub.chunk.ChunkPolicy
import app.epubreader.core.epub.chunk.ChunkedBook
import app.epubreader.core.epub.pkg.BookPackage
import app.epubreader.core.epub.zip.ZipReader

/**
 * One opened book file. [id] is random and appears in every URL the WebView loads for this book.
 *
 * M8: [chunked] is the reader's view of the book when the opener split a huge document (virtual entries over the
 * zip, P-014/P-023/P-061); null = the zip as it is. [pkg] is the package if the opener parsed it (only books with an
 * entry over the chunk threshold), reused for the section stats. [policy] is the chunk policy the session was opened
 * with (a warm session is reused only under the same policy).
 */
class BookSession(
    val id: String, val ref: SourceRef, val zip: ZipReader, val size: Long, val stamp: String,
    val pkg: BookPackage? = null,
    chunked: ChunkedBook? = null,
    val policy: ChunkPolicy = ChunkPolicy(),
    /** Runs the chunker again (same zip, policy and store): rebuilds a cache trimmed while the book is open. */
    private val rechunk: (() -> ChunkedBook)? = null,
) : AutoCloseable {
    @Volatile var chunked: ChunkedBook? = chunked
        private set
    /** Its book's chunk cache was deleted with the book ([forget]): never rebuilt, so never recreated. */
    @Volatile var forgotten = false
        private set
    /**
     * A rebuild failed (no space, out of memory, a different map): later reads fail at once instead of re-running the
     * chunker (which starts by clearing the store) on every WebView request.
     */
    @Volatile private var rebuildFailed: String? = null
    private val rebuildLock = Any()

    val cdDigest: String get() = zip.centralDirectoryDigest

    /** What the reader's positions go through: the chunk map of a split book, else the identity. */
    val map: PositionMap get() = chunked?.map?.takeUnless { it.isIdentity } ?: PositionMap.Identity

    /**
     * The virtual entry at [path] (null = serve the zip entry). A virtual entry that vanished (the system trimmed the
     * cache while the book is open) is rebuilt once; if that fails too this throws, and the caller must not fall back
     * to the zip entry, whose package and documents do not match the reader's map.
     */
    fun virtualEntry(path: String): ByteArray? = withRebuild { it.virtualEntry(path) }

    /** Byte size of the virtual entry at [path] (null = not virtual), rebuilding like [virtualEntry]. */
    fun virtualSize(path: String): Long? = withRebuild { it.virtualSize(path) }

    private inline fun <T> withRebuild(read: (ChunkedBook) -> T?): T? {
        val c = chunked ?: return null
        try { return read(c) } catch (e: ChunkCacheException) { /* rebuilt below */ }
        return read(rebuilt(c))
    }

    /**
     * One rebuild at a time; a reader that failed on the cache another thread has already rebuilt takes the new one.
     * The chunker is deterministic for the same zip and policy, so the map cannot change: if it did, the reader's
     * positions would not match, so that is an error too.
     */
    private fun rebuilt(seen: ChunkedBook): ChunkedBook = synchronized(rebuildLock) {
        val now = chunked!!
        if (now !== seen) return now
        if (forgotten) throw ChunkCacheException("chunk cache deleted with its book")
        rebuildFailed?.let { throw ChunkCacheException("chunk cache rebuild failed before: $it") }
        val r = rechunk ?: throw ChunkCacheException("chunk cache gone and no way to rebuild it")
        val b = try {
            r()
        } catch (e: Exception) {
            rebuildFailed = e.toString()
            throw ChunkCacheException("chunk cache rebuild failed: $e")
        } catch (e: OutOfMemoryError) { // never on a WebView thread: the server answers 500
            rebuildFailed = e.toString()
            throw ChunkCacheException("chunk cache rebuild ran out of memory")
        }
        // Deleted with its book while rebuilding: the caller deletes the directory once [forget] returns.
        if (forgotten) throw ChunkCacheException("chunk cache deleted with its book")
        if (b.map != seen.map) {
            rebuildFailed = "map differs"
            throw ChunkCacheException("rebuilt chunk map differs from the open book's")
        }
        chunked = b
        b
    }

    /**
     * The book's chunk cache is being deleted (M7 delete path, a forgotten source): no rebuild starts from now on,
     * and this returns only after a rebuild in flight has finished, so the caller's deletion comes after its writes.
     */
    fun forget() {
        forgotten = true
        synchronized(rebuildLock) {}
    }

    override fun close() = zip.close() // closes the RandomAccessSource too
}
