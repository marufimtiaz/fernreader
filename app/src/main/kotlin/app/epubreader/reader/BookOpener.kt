package app.epubreader.reader

import android.util.Log
import app.epubreader.core.data.SourceRef
import app.epubreader.core.data.newId
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
 */
class BookOpener(
    private val sources: Sources,
    private val server: BookContentServer,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val warn: (String) -> Unit = { Log.w("EPR", it) },
) : BookOpenerPort {
    private val lock = Mutex()
    @Volatile private var current: BookSession? = null
    private var warm: BookSession? = null

    override suspend fun open(ref: SourceRef): OpenResult = withContext(io) {
        lock.withLock {
            current?.let { retire(it) }
            current = null
            warm?.let { w ->
                warm = null
                if (w.ref == ref && w.stamp == runCatching { sources.stamp(ref) }.getOrNull()) {
                    current = w
                    return@withContext OpenResult.Ok(w.id, w.cdDigest, w.size)
                }
                server.unregister(w.id); w.close()
            }
            // Any failure becomes a reason (R-002): providers may throw IllegalArgumentException,
            // IllegalStateException, UnsupportedOperationException or their own RuntimeExceptions.
            val opened = try {
                sources.open(ref)
            } catch (e: Exception) {
                logFailure(ref, e, warn)
                return@withContext OpenResult.Failed(OpenFailure.NO_ACCESS)
            }
            val s = try {
                BookSession(newId(), ref, ZipReader.open(opened.source), opened.size, sources.stamp(ref))
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
            server.register(s)
            current = s
            OpenResult.Ok(s.id, s.cdDigest, s.size)
        }
    }

    override suspend fun close(sessionId: String) {
        withContext(io) {
            lock.withLock { current?.takeIf { it.id == sessionId }?.let { retire(it); current = null } }
        }
    }

    override suspend fun sectionStats(sessionId: String): List<SectionStat>? = withContext(io) {
        val s = lock.withLock { current?.takeIf { it.id == sessionId } } ?: return@withContext null
        try { sectionStats(s.zip) } catch (e: Exception) { null } // closed under us (Back) or unreadable
    }

    /**
     * Text of the current session's book for the search index builder (F-050); null if [sessionId] is not the current
     * session. Reads the package, so call it on an I/O thread. Later reads fail with BookUnavailableException when the
     * session is closed under the build (the index keeps what it has and resumes).
     */
    fun bookText(sessionId: String): BookText? = current?.takeIf { it.id == sessionId }?.let { ZipBookText(it.zip) }

    /** Current session becomes the warm one; the previous warm session is closed for good. */
    private fun retire(s: BookSession) {
        warm?.let { server.unregister(it.id); it.close() }
        warm = s
    }
}

private fun logFailure(ref: SourceRef, e: Exception, warn: (String) -> Unit) {
    if (e !is FileNotFoundException && e !is SecurityException) warn("open failed: ${ref.key}: $e")
}
