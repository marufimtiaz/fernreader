package app.epubreader.core.epub.chunk

/**
 * The reader-facing view of a book: [virtualEntry] overrides the zip entry of the same path (rewritten package,
 * nav, NCX and content documents) or adds one (parts). Null means "serve the zip entry". Entries are read from the
 * [ChunkStore] on demand, so memory holds only the map.
 *
 * A virtual entry missing from the store (the cache was deleted while the book was open) throws
 * [ChunkCacheException] rather than falling back to the zip, whose original package and documents do not match the
 * map; the caller re-runs [Chunker.chunk], which rebuilds.
 */
class ChunkedBook internal constructor(val map: ChunkMap, private val store: ChunkStore, rewritten: Set<String>) {
    /** Paths of every virtual entry: parts and rewritten documents. */
    val virtualPaths: Set<String> = map.partPaths + rewritten

    fun virtualEntry(path: String): ByteArray? {
        if (path !in virtualPaths) return null
        return store.read(Chunker.entryName(path)) ?: throw ChunkCacheException("chunk cache entry missing: $path")
    }
}

class ChunkCacheException(message: String) : RuntimeException(message)
