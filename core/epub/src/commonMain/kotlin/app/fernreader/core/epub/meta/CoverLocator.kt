package app.fernreader.core.epub.meta

import app.fernreader.core.epub.markup.MarkupScanner
import app.fernreader.core.epub.markup.MarkupVisitor
import app.fernreader.core.epub.markup.Attr
import app.fernreader.core.epub.markup.Paths
import app.fernreader.core.epub.markup.TextDecoding
import app.fernreader.core.epub.pkg.BookPackage
import app.fernreader.core.epub.zip.ZipReader

data class CoverResult(val path: String?, val danglingReference: String?)

/** F-021: EPUB 3 cover-image property, EPUB 2 meta name="cover", else the first image of the first spine item. */
object CoverLocator {
    fun locate(zip: ZipReader, pkg: BookPackage): CoverResult {
        pkg.manifest.firstOrNull { "cover-image" in it.properties && it.present }?.let { return CoverResult(it.path, null) }
        var dangling: String? = null
        pkg.opf.first("metadata")?.all("meta")?.firstOrNull { it.attr("name") == "cover" }?.attr("content")?.let { ref ->
            val item = pkg.item(ref) ?: pkg.manifest.firstOrNull { it.path.endsWith(ref) }
            if (item != null && item.present && item.mediaType.startsWith("image/")) return CoverResult(item.path, null)
            dangling = ref
        }
        val first = pkg.spine.firstOrNull { it.present } ?: return CoverResult(null, dangling)
        val markup = runCatching { TextDecoding.decode(zip.readBytes(zip.entry(first.path)!!, 4L shl 20)) }.getOrNull() ?: return CoverResult(null, dangling)
        var src: String? = null
        MarkupScanner.scan(markup, object : MarkupVisitor {
            override fun startTag(name: String, attrs: List<Attr>, selfClosing: Boolean, start: Int, end: Int) {
                if (src != null) return
                val n = name.substringAfter(':').lowercase()
                if (n == "img") src = attrs.firstOrNull { it.localName == "src" }?.value
                if (n == "image") src = attrs.firstOrNull { it.localName == "href" }?.value
            }
        })
        val path = src?.takeIf { !Paths.isExternal(it) }?.let { Paths.resolve(Paths.dirOf(first.path), it) }?.takeIf { zip.entry(it) != null }
        return CoverResult(path, dangling)
    }
}
