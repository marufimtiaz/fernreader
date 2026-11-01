package app.epubreader.reader

import app.epubreader.core.data.SourceRef
import app.epubreader.core.epub.chunk.ChunkPolicy
import app.epubreader.core.epub.chunk.ChunkStore
import app.epubreader.core.epub.chunk.FileChunkStore
import app.epubreader.core.epub.sha256Hex
import java.io.File

/**
 * Where chunk caches live (M8): `<root>/<source key>/<policy key>/`, root = `cacheDir/chunks`. The source key is a hash
 * of the book file's [SourceRef.key] (the `book_file.path` the library stores), so the M7 delete path finds a book's
 * caches from its file rows ([delete]). One directory per policy: a cache of another policy (an older app version,
 * or a test's policy) is removed when a policy's directory is made.
 *
 * [protect] marks a directory as a cache group (Android: `StorageManager.setCacheBehaviorGroup(dir, true)`): the
 * system then trims it as a whole, never a few files of it. A cache trimmed while the book is open is rebuilt by
 * [BookSession]. Failures of [protect] are ignored (the size checks in the chunker's cache.json still catch a partly
 * trimmed cache).
 */
class ChunkDirs(private val root: File, private val protect: (File) -> Unit = {}) {
    fun dirOf(sourceKey: String): File = File(root, keyOf(sourceKey))

    fun dir(ref: SourceRef, policy: ChunkPolicy): File = File(dirOf(ref.key), policy.key)

    /** The store for [ref] under [policy]; the directory exists and is protected when this returns. */
    fun store(ref: SourceRef, policy: ChunkPolicy): ChunkStore {
        val d = dir(ref, policy)
        d.parentFile?.listFiles()?.forEach { if (it.name != d.name) it.deleteRecursively() }
        d.mkdirs()
        runCatching { protect(d) }
        return FileChunkStore(d)
    }

    /** Removes every chunk cache of these file sources (M7: a book deleted with its files, F-025). */
    fun delete(sourceKeys: Collection<String>) {
        for (k in sourceKeys) dirOf(k).deleteRecursively()
    }

    companion object {
        fun keyOf(sourceKey: String) = sha256Hex(sourceKey.encodeToByteArray()).take(32)
    }
}
