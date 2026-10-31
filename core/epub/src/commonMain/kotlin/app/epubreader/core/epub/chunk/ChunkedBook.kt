package app.epubreader.core.epub.chunk

/**
 * The reader-facing view of a book: [virtualEntry] overrides the zip entry of the same path (rewritten package,
 * nav, NCX and content documents) or adds one (parts). Null means "serve the zip entry". Entries are read from the
 * [ChunkStore] on demand, so memory holds only the map.
 */
class ChunkedBook internal constructor(val map: ChunkMap, private val store: ChunkStore, rewritten: Set<String>) {
    /** Paths of every virtual entry: parts and rewritten documents. */
    val virtualPaths: Set<String> = map.partPaths + rewritten

    fun virtualEntry(path: String): ByteArray? = if (path in virtualPaths) store.read(Chunker.entryName(path)) else null
}
