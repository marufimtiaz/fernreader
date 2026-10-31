package app.epubreader.reader

import app.epubreader.core.epub.markup.TextDecoding
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
    return pkg.spine.map { item ->
        val entry = if (item.present) zip.entry(item.path) else null
        if (entry == null) SectionStat(0, 0) else try {
            val x = TextExtractor.extract(TextDecoding.decode(zip.readBytes(entry)))
            SectionStat(x.compactLength, x.imageCount)
        } catch (e: OutOfMemoryError) {
            SectionStat(0, 0)
        } catch (e: java.io.IOException) {
            throw e // the session was closed or the file went away: no stats at all
        } catch (e: Exception) {
            SectionStat(0, 0) // a damaged entry counts as empty
        }
    }
}
