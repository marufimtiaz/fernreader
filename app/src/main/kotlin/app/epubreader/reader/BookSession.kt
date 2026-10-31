package app.epubreader.reader

import app.epubreader.core.data.SourceRef
import app.epubreader.core.epub.zip.ZipReader

/** One opened book file. [id] is random and appears in every URL the WebView loads for this book. */
class BookSession(val id: String, val ref: SourceRef, val zip: ZipReader, val size: Long, val stamp: String) : AutoCloseable {
    val cdDigest: String get() = zip.centralDirectoryDigest
    override fun close() = zip.close() // closes the RandomAccessSource too
}
