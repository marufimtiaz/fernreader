package app.epubreader.reader

import app.epubreader.core.data.SourceRef
import app.epubreader.core.data.newId
import app.epubreader.core.epub.zip.ZipError
import app.epubreader.core.epub.zip.ZipFormatException
import app.epubreader.core.epub.zip.ZipReader
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
    private val sources: BookSources,
    private val server: BookContentServer,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : BookOpenerPort {
    private val lock = Mutex()
    private var current: BookSession? = null
    private var warm: BookSession? = null

    override suspend fun open(ref: SourceRef): OpenResult = withContext(io) {
        lock.withLock {
            current?.let { retire(it) }
            current = null
            warm?.let { w ->
                warm = null
                if (w.ref == ref && w.stamp == sources.stamp(ref)) {
                    current = w
                    return@withContext OpenResult.Ok(w.id, w.cdDigest, w.size)
                }
                server.unregister(w.id); w.close()
            }
            val opened = try {
                sources.open(ref)
            } catch (e: FileNotFoundException) {
                return@withContext OpenResult.Failed(OpenFailure.NO_ACCESS)
            } catch (e: SecurityException) {
                return@withContext OpenResult.Failed(OpenFailure.NO_ACCESS)
            } catch (e: IOException) {
                return@withContext OpenResult.Failed(OpenFailure.NO_ACCESS)
            }
            val zip = try {
                ZipReader.open(opened.source)
            } catch (e: ZipFormatException) {
                opened.source.close()
                return@withContext OpenResult.Failed(if (e.error == ZipError.NOT_A_ZIP) OpenFailure.NOT_A_ZIP else OpenFailure.DAMAGED)
            } catch (e: IOException) {
                opened.source.close()
                return@withContext OpenResult.Failed(OpenFailure.NO_ACCESS)
            }
            val s = BookSession(newId(), ref, zip, opened.size, sources.stamp(ref))
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

    /** Current session becomes the warm one; the previous warm session is closed for good. */
    private fun retire(s: BookSession) {
        warm?.let { server.unregister(it.id); it.close() }
        warm = s
    }
}
