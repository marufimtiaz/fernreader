package app.epubreader.reader

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import app.epubreader.core.data.SourceRef
import app.epubreader.core.epub.FileChannelSource
import app.epubreader.core.epub.openFileSource
import app.epubreader.core.epub.sha256Hex
import app.epubreader.core.epub.zip.RandomAccessSource
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException

class OpenedSource(val source: RandomAccessSource, val size: Long)

/**
 * Opens a SourceRef for positional reads. A content URI is read through the provider's file descriptor (no copy);
 * a non-seekable descriptor (a pipe, e.g. from a cloud provider) is copied to cacheDir/opened/ first.
 * Throws FileNotFoundException / SecurityException when the book cannot be reached.
 */
class BookSources(private val context: Context) {
    fun open(ref: SourceRef): OpenedSource = when (ref) {
        is SourceRef.File -> File(ref.path).let { f ->
            if (!f.canRead()) throw FileNotFoundException(ref.path)
            OpenedSource(openFileSource(f.path), f.length())
        }
        is SourceRef.Content -> openContent(Uri.parse(ref.uri))
    }

    /** Changes when the file's content may have changed (used to decide whether a warm session can be reused). */
    fun stamp(ref: SourceRef): String = when (ref) {
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
            val channel = FileInputStream(pfd.fileDescriptor).channel
            return OpenedSource(PfdSource(pfd, FileChannelSource(channel)), pfd.statSize)
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
