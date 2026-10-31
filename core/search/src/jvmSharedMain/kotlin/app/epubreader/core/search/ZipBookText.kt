package app.epubreader.core.search

import app.epubreader.core.epub.markup.TextDecoding
import app.epubreader.core.epub.pkg.LoadResult
import app.epubreader.core.epub.pkg.PackageLoader
import app.epubreader.core.epub.text.ExtractedText
import app.epubreader.core.epub.text.TextExtractor
import app.epubreader.core.epub.zip.ZipReader
import java.io.IOException

/**
 * [BookText] over an open EPUB: spine items in spine order (all of them, as the reader's sections), read like
 * `sectionStats`. Reads the package on construction and sections on demand: use it on an I/O thread (R-005).
 */
class ZipBookText(private val zip: ZipReader) : BookText {
    private val spine = try {
        when (val r = PackageLoader.load(zip)) {
            is LoadResult.Ok -> r.pkg.spine
            // Not an index of "no text": the build fails (and resumes later) instead of completing an empty index.
            else -> throw BookUnavailableException("package rejected: $r")
        }
    } catch (e: IOException) {
        throw BookUnavailableException("book closed", e)
    }

    override val sectionCount: Int get() = spine.size

    override fun section(index: Int): ExtractedText? {
        val item = spine.getOrNull(index)?.takeIf { it.present } ?: return null
        val entry = zip.entry(item.path) ?: return null
        return try {
            TextExtractor.extract(TextDecoding.decode(zip.readBytes(entry)))
        } catch (e: IOException) {
            throw BookUnavailableException("book closed", e) // the session was closed or the file went away
        } catch (e: Exception) {
            null // a damaged entry has no text (an OutOfMemoryError is not caught: the build fails and resumes later)
        }
    }
}
