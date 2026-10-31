package app.epubreader.core.epub.chunk

/** Flat name → bytes storage for one book's chunk cache (one directory per book and policy in the app). */
interface ChunkStore {
    fun read(name: String): ByteArray?
    /** Replaces [name] completely or not at all. */
    fun write(name: String, bytes: ByteArray)
    /** Removes every entry. */
    fun clear()
}

class MemoryChunkStore : ChunkStore {
    private val data = HashMap<String, ByteArray>()
    /** Number of writes so far (tests use it to see whether a cache was reused). */
    var writeCount = 0
        private set
    val size: Int get() = data.size

    override fun read(name: String): ByteArray? = data[name]
    override fun write(name: String, bytes: ByteArray) { data[name] = bytes; writeCount++ }
    override fun clear() = data.clear()
}
