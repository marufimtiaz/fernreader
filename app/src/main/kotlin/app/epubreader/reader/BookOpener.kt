package app.epubreader.reader

import android.util.Log
import androidx.annotation.VisibleForTesting
import app.epubreader.core.data.SourceRef
import app.epubreader.core.data.newId
import app.epubreader.core.epub.chunk.ChunkPolicy
import app.epubreader.core.epub.chunk.ChunkStore
import app.epubreader.core.epub.chunk.Chunker
import app.epubreader.core.epub.pkg.LoadResult
import app.epubreader.core.epub.pkg.PackageLoader
import app.epubreader.core.epub.zip.ZipError
import app.epubreader.core.epub.zip.ZipFormatException
import app.epubreader.core.epub.zip.ZipReader
import app.epubreader.core.search.BookText
import app.epubreader.core.search.ZipBookText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Opens books for the reader on the I/O dispatcher (R-005). Keeps the most recently closed session open and
 * registered ("warm"): reopening the same, unchanged file reuses it, and the JS host reuses its parsed book for
 * the same session id (P-016, C-014). Opening any other book closes the warm session.
 *
 * M8 (P-014, P-023, P-061, R-006): a book with a spine document over [policy]'s threshold is split by the [Chunker]
 * into the cache [chunkStore] gives for its file (built once, loaded on later opens), and the session serves the
 * virtual book; [OpenResult.Ok.map] carries its ChunkMap to the reader. A book with no entry over the threshold pays
 * nothing (no package parse, P-010/P-011). Any failure while chunking serves the book unsplit (identity map): stored
 * positions are original ones either way.
 */
class BookOpener(
    private val sources: Sources,
    private val server: BookContentServer,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val warn: (String) -> Unit = { Log.w("EPR", it) },
    /** The chunk cache of a book file under a policy (app: [ChunkDirs.store]); null = books are never split. */
    private val chunkStore: ((SourceRef, ChunkPolicy) -> ChunkStore)? = null,
) : BookOpenerPort {
    /**
     * Chunk policy of the opens from now on. A test hook too: an instrumented test opens the same book unsplit (a
     * threshold over the file's size) as a control. A warm session is reused only under the policy it was opened with.
     */
    @VisibleForTesting
    @Volatile internal var policy: ChunkPolicy = ChunkPolicy()
    private val lock = Mutex()
    @Volatile private var current: BookSession? = null
    private var warm: BookSession? = null

    override suspend fun open(ref: SourceRef): OpenResult = withContext(io) {
        lock.withLock {
            val policy = policy
            current?.let { retire(it) }
            current = null
            warm?.let { w ->
                warm = null
                if (!w.forgotten && w.ref == ref && w.policy == policy && w.stamp == runCatching { sources.stamp(ref) }.getOrNull()) {
                    current = w
                    return@withContext OpenResult.Ok(w.id, w.cdDigest, w.size, w.map)
                }
                server.unregister(w.id); w.closeQuietly()
            }
            // Any failure becomes a reason (R-002): providers may throw IllegalArgumentException,
            // IllegalStateException, UnsupportedOperationException or their own RuntimeExceptions.
            val opened = try {
                sources.open(ref)
            } catch (e: Exception) {
                logFailure(ref, e, warn)
                return@withContext OpenResult.Failed(OpenFailure.NO_ACCESS)
            }
            val (zip, stamp) = try {
                ZipReader.open(opened.source) to sources.stamp(ref)
            } catch (e: Exception) {
                runCatching { opened.source.close() }
                if (e !is ZipFormatException) logFailure(ref, e, warn)
                return@withContext OpenResult.Failed(
                    when {
                        e is ZipFormatException && e.error == ZipError.NOT_A_ZIP -> OpenFailure.NOT_A_ZIP
                        e is ZipFormatException -> OpenFailure.DAMAGED
                        e is IOException -> OpenFailure.NO_ACCESS // the file went away or a read failed
                        else -> OpenFailure.DAMAGED // anything unexpected from the zip reader
                    },
                )
            }
            val s = session(ref, zip, opened.size, stamp, policy)
            server.register(s)
            current = s
            OpenResult.Ok(s.id, s.cdDigest, s.size, s.map)
        }
    }

    /** The session, split if it has to be; never throws (a chunking failure is logged and the book served unsplit). */
    private fun session(ref: SourceRef, zip: ZipReader, size: Long, stamp: String, policy: ChunkPolicy): BookSession {
        val id = newId()
        val stores = chunkStore
        if (stores == null || !mayNeedSplit(zip, policy)) return BookSession(id, ref, zip, size, stamp, policy = policy)
        val pkg = try { (PackageLoader.load(zip) as? LoadResult.Ok)?.pkg } catch (e: Exception) { null }
            ?: return BookSession(id, ref, zip, size, stamp, policy = policy)
        val chunker = Chunker(policy)
        // Nothing the chunker would split (a large image, CSS or non-spine document): no store, no directory.
        if (!chunker.hasCandidates(zip, pkg)) return BookSession(id, ref, zip, size, stamp, pkg, policy = policy)
        return try {
            val book = chunker.chunk(zip, pkg, stores(ref, policy))
            // A rebuild asks for the store again: the directory may have been trimmed away, protection with it.
            BookSession(id, ref, zip, size, stamp, pkg, book.takeUnless { it.map.isIdentity }, policy) { chunker.chunk(zip, pkg, stores(ref, policy)) }
        } catch (e: Exception) {
            warn("chunking failed, serving unsplit: ${ref.key}: $e")
            BookSession(id, ref, zip, size, stamp, pkg, policy = policy)
        } catch (e: OutOfMemoryError) {
            warn("chunking ran out of memory, serving unsplit: ${ref.key}")
            BookSession(id, ref, zip, size, stamp, pkg, policy = policy)
        }
    }

    /**
     * The chunk caches of these sources are being deleted (M7 delete path, F-025): a warm session of one of them is
     * closed for good, and an open one is marked so it never rebuilds (recreates) the deleted cache.
     */
    suspend fun forget(sourceKeys: Collection<String>) = withContext(io) {
        lock.withLock {
            current?.takeIf { it.ref.key in sourceKeys }?.forget()
            warm?.takeIf { it.ref.key in sourceKeys }?.let { w ->
                warm = null
                w.forget()
                server.unregister(w.id); w.close()
            }
        }
    }

    override suspend fun close(sessionId: String) {
        withContext(io) {
            lock.withLock { current?.takeIf { it.id == sessionId }?.let { retire(it); current = null } }
        }
    }

    override suspend fun sectionStats(sessionId: String): List<SectionStat>? = withContext(io) {
        val s = lock.withLock { current?.takeIf { it.id == sessionId } } ?: return@withContext null
        try { sectionStats(s) } catch (e: Exception) { null } // closed under us (Back), unreadable, or a lost chunk cache
    }

    override suspend fun bookPages(sessionId: String, withStats: Boolean): BookPagesResult? = withContext(io) {
        val s = lock.withLock { current?.takeIf { it.id == sessionId } } ?: return@withContext null
        try {
            bookPages(s, withStats)
        } catch (e: Exception) {
            null // closed under us (Back), or unreadable
        } catch (e: OutOfMemoryError) {
            warn("book pages ran out of memory: ${s.ref.key}"); null
        }
    }

    /**
     * Text of the current session's book for the search index builder (F-050); null if [sessionId] is not the current
     * session. Reads the package, so call it on an I/O thread. Later reads fail with BookUnavailableException when the
     * session is closed under the build (the index keeps what it has and resumes).
     */
    fun bookText(sessionId: String): BookText? = current?.takeIf { it.id == sessionId }?.let { ZipBookText(it.zip) }

    /** Current session becomes the warm one; the previous warm session is closed for good. */
    private fun retire(s: BookSession) {
        warm?.let { server.unregister(it.id); it.closeQuietly() }
        warm = s
    }

    /**
     * Closing a finished session never fails what comes next (C-049): a file on shared storage that was deleted
     * meanwhile can throw `IOException: close failed: EIO` from the FUSE layer, and that used to fail the next open.
     */
    private fun BookSession.closeQuietly() {
        try { close() } catch (e: Exception) { warn("closing ${ref.key} failed: $e") }
    }
}

/**
 * Whether [zip] has an entry the chunker could split under [policy]: an XHTML/HTML document by its extension, over
 * the threshold. False for almost every book, which then opens without a package parse (P-010/P-011). A large SVG,
 * CSS, NCX, font or image never costs a parse. (An XHTML spine item with another extension is served unsplit.)
 */
internal fun mayNeedSplit(zip: ZipReader, policy: ChunkPolicy): Boolean = zip.entries.any { e ->
    !e.isDirectory && e.size > policy.thresholdBytes && e.name.substringAfterLast('/').substringAfterLast('.', "").lowercase() in DOCUMENTS
}

private val DOCUMENTS = setOf("xhtml", "xht", "html", "htm")

private fun logFailure(ref: SourceRef, e: Exception, warn: (String) -> Unit) {
    if (e !is FileNotFoundException && e !is SecurityException) warn("open failed: ${ref.key}: $e")
}
