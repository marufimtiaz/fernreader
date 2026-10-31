package app.epubreader.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import app.epubreader.core.data.LibraryRepository
import app.epubreader.core.epub.openFileSource
import app.epubreader.core.epub.pkg.LoadResult
import app.epubreader.core.epub.pkg.PackageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * F-021: cover thumbnails for the library, written once to `filesDir/thumbs/<bookId>.webp` and loaded from there
 * (P-063 prerequisite). Decoding is downsampled (R-006). Returns null when the book has no usable cover image.
 */
class CoverExtractor(private val dir: File) {
    /** Thrown for a failure worth retrying on a later pass (the file could not be read right now). */
    class Retry(cause: Throwable) : Exception(cause)

    /** The thumbnail path, or null when the book has no usable cover. Throws [Retry] on an I/O failure. */
    fun extract(bookId: String, path: String): String? = try {
        val source = try { openFileSource(path) } catch (e: java.io.IOException) { throw Retry(e) } // not readable right now
        val bytes = source.use { src ->
            val (zip, r) = PackageLoader.open(src)
            val cover = (r as? LoadResult.Ok)?.pkg?.coverPath ?: return null
            zip!!.readBytes(zip.entry(cover) ?: return null, 20L shl 20)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null // not a bitmap (e.g. an SVG cover)
        // R-006: bounded by both sides, so a very tall image never decodes at full size.
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_WIDTH || bounds.outHeight / (sample * 2) >= MAX_HEIGHT) sample *= 2
        if (bounds.outWidth.toLong() / sample * (bounds.outHeight.toLong() / sample) > MAX_PIXELS) return null
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val thumb = if (decoded.width > MAX_WIDTH) {
            Bitmap.createScaledBitmap(decoded, MAX_WIDTH, (decoded.height.toLong() * MAX_WIDTH / decoded.width).toInt().coerceAtLeast(1), true)
        } else decoded
        dir.mkdirs()
        val out = File(dir, "$bookId.webp"); val tmp = File(dir, "$bookId.tmp")
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        tmp.outputStream().use { thumb.compress(format, 80, it) }
        if (!tmp.renameTo(out)) { tmp.delete(); return null }
        out.path
    } catch (e: Retry) {
        throw e
    } catch (e: Exception) { // a damaged book or image never stops the queue
        Log.w("EPR", "cover failed: $path: $e")
        null
    } catch (e: OutOfMemoryError) {
        Log.w("EPR", "cover too large: $path")
        null
    }

    companion object {
        const val MAX_WIDTH = 360
        const val MAX_HEIGHT = 1080
        const val MAX_PIXELS = 4_000_000L
    }
}

/**
 * Fills in missing covers after each scan, off the scan path (M0 S3), two at a time. Requests while it runs
 * collapse into one more pass. A book with no usable cover is marked "" so it is not tried again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoverQueue(private val repo: LibraryRepository, private val extractor: CoverExtractor, scope: CoroutineScope) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val work = Dispatchers.IO.limitedParallelism(2)
    private val paused = MutableStateFlow(false)

    /** While a book is open the queue waits: decoding covers must not compete with the reader (P-060, R-005). */
    fun setPaused(p: Boolean) { paused.value = p }

    init {
        scope.launch {
            for (r in requests) runCatching {
                kotlinx.coroutines.coroutineScope {
                    for (job in repo.coverJobs()) launch(work) {
                        paused.first { !it }
                        // One book at a time: a failure skips that book only. I/O failures are retried after the next scan.
                        try { repo.setCover(job.bookId, extractor.extract(job.bookId, job.path) ?: "") } catch (e: CoverExtractor.Retry) { Log.w("EPR", "cover later: ${job.path}") }
                    }
                }
            }.onFailure { Log.w("EPR", "cover queue: $it") }
        }
    }

    fun fillMissing() { requests.trySend(Unit) }
}
