package app.epubreader.reader

import app.epubreader.core.epub.chunk.ChunkCacheException
import app.epubreader.core.epub.markup.TextDecoding
import app.epubreader.core.epub.pages.BookPagesBuilder
import app.epubreader.core.epub.pkg.LoadResult
import app.epubreader.core.epub.pkg.PackageLoader
import app.epubreader.core.epub.text.TextExtractor
import app.epubreader.core.epub.zip.ZipReader

/**
 * Per spine item (all items, in spine order, as foliate's sections): canonical text size and image count, the input
 * of the page-total estimate (S1 text model). A missing or unreadable item counts as empty. Null if there is no
 * readable package. Call on an I/O thread (R-005).
 */
fun sectionStats(zip: ZipReader): List<SectionStat>? {
    val pkg = (PackageLoader.load(zip) as? LoadResult.Ok)?.pkg ?: return null
    return pkg.spine.map { item -> stat { if (item.present) zip.entry(item.path)?.let(zip::readBytes) else null } }
}

/**
 * The stats of what the session's reader shows (M8): for a chunked book its virtual sections (each part counted on
 * its own, as the web side counts pages per part), else the spine items. The package the opener parsed is reused.
 * A virtual entry that cannot be read even after a rebuild means no stats (never stats of the unsplit document).
 */
fun sectionStats(s: BookSession): List<SectionStat>? {
    val c = s.chunked ?: return s.pkg?.let { pkg ->
        pkg.spine.map { item -> stat { if (item.present) s.zip.entry(item.path)?.let(s.zip::readBytes) else null } }
    } ?: sectionStats(s.zip)
    return c.map.virtualSpinePaths.map { path -> stat { s.virtualEntry(path) ?: s.zip.entry(path)?.let(s.zip::readBytes) } }
}

/**
 * C-083: the book's pages, from the original spine (a chunked book's parts are never read: its pages are the
 * unchunked book's). With [withStats] and an unchunked session, the same pass gives the section stats (so a first
 * open reads the book once for both). [fixedLayout]: as the reader shows the book (C-087: one page per spine item).
 * Null without a readable package. Call on an I/O thread.
 */
fun bookPages(s: BookSession, withStats: Boolean, fixedLayout: Boolean = false): BookPagesResult? {
    val pkg = s.pkg ?: (PackageLoader.load(s.zip) as? LoadResult.Ok)?.pkg ?: return null
    val stats = if (withStats && s.chunked == null) arrayOfNulls<SectionStat>(pkg.spine.size) else null
    val pages = BookPagesBuilder.build(s.zip, pkg, fixedLayout) { i, ex -> stats?.set(i, ex?.let { SectionStat(it.compactLength, it.imageCount) } ?: SectionStat(0, 0)) }
    return BookPagesResult(pages, stats?.map { it ?: SectionStat(0, 0) })
}

/** Stats of one section's bytes (null = missing, counts as empty). */
private inline fun stat(bytes: () -> ByteArray?): SectionStat = try {
    bytes()?.let { b -> TextExtractor.extract(TextDecoding.decode(b)).let { SectionStat(it.compactLength, it.imageCount) } } ?: SectionStat(0, 0)
} catch (e: OutOfMemoryError) {
    SectionStat(0, 0)
} catch (e: java.io.IOException) {
    throw e // the session was closed or the file went away: no stats at all
} catch (e: ChunkCacheException) {
    throw e
} catch (e: Exception) {
    SectionStat(0, 0) // a damaged entry counts as empty
}
