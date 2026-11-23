package app.fernreader.reader

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import app.fernreader.core.data.SourceRef
import app.fernreader.core.epub.FileChannelSource
import app.fernreader.core.epub.openFileSource
import app.fernreader.core.epub.sha256Hex
import app.fernreader.core.epub.zip.RandomAccessSource
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException

class OpenedSource(val source: RandomAccessSource, val size: Long)

/** What [BookOpener] needs from the file layer (a seam for tests). */
interface Sources {
    /** May throw anything a provider throws; [BookOpener] maps every failure to an OpenResult. */
    fun open(ref: SourceRef): OpenedSource
    fun stamp(ref: SourceRef): String
}

/**
 * Opens a SourceRef for positional reads. A content URI is read through the provider's file descriptor (no copy);
 * a non-seekable descriptor (a pipe, e.g. from a cloud provider) is copied to cacheDir/opened/ first.
 * Throws when the book cannot be reached (FileNotFoundException, SecurityException, or whatever the provider throws).
 */
class BookSources(private val context: Context) : Sources {
    override fun open(ref: SourceRef): OpenedSource = when (ref) {
        is SourceRef.File -> File(ref.path).let { f ->
            if (!f.canRead()) throw FileNotFoundException(ref.path)
            OpenedSource(openFileSource(f.path), f.length())
        }
        is SourceRef.Content -> openContent(Uri.parse(ref.uri))
    }

    /** Changes when the file's content may have changed (used to decide whether a warm session can be reused). */
    override fun stamp(ref: SourceRef): String = when (ref) {
        is SourceRef.File -> File(ref.path).let { "${it.length()}:${it.lastModified()}" }
        is SourceRef.Content -> runCatching {
            context.contentResolver.query(Uri.parse(ref.uri), arrayOf(OpenableColumns.SIZE, "last_modified"), null, null, null)
                ?.use { c ->
                    if (!c.moveToFirst()) return@use "?"
                    val sizeCol = c.getColumnIndex(OpenableColumns.SIZE)
                    val modCol = c.getColumnIndex("last_modified")
                    val size = if (sizeCol >= 0 && !c.isNull(sizeCol)) c.getLong(sizeCol) else -1L
                    val mod = if (modCol >= 0 && !c.isNull(modCol)) c.getLong(modCol) else 0L
                    "$size:$mod"
                }
        }.getOrNull() ?: "?"
    }

    private fun openContent(uri: Uri): OpenedSource {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString())
        if (pfd.statSize >= 0) {
            try {
                val channel = FileInputStream(pfd.fileDescriptor).channel
                return OpenedSource(PfdSource(pfd, FileChannelSource(channel)), pfd.statSize)
            } catch (e: Exception) {
                runCatching { pfd.close() } // FileChannelSource reads channel.size(), which can throw
                throw e
            }
        }
        // Non-seekable (pipe): copy from the same descriptor, so the provider is asked only once.
        val dir = File(context.cacheDir, "opened").apply { mkdirs() }
        val f = File(dir, sha256Hex(uri.toString().encodeToByteArray()).take(16) + ".epub")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input -> f.outputStream().use { input.copyTo(it) } }
        return OpenedSource(openFileSource(f.path), f.length())
    }
}

private class PfdSource(private val pfd: ParcelFileDescriptor, private val inner: FileChannelSource) : RandomAccessSource {
    override val size: Long get() = inner.size
    override fun read(position: Long, dst: ByteArray, off: Int, len: Int) = inner.read(position, dst, off, len)
    override fun close() { inner.close(); pfd.close() }
}
