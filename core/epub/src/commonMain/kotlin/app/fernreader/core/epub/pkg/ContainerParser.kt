package app.fernreader.core.epub.pkg

import app.fernreader.core.epub.markup.TextDecoding
import app.fernreader.core.epub.markup.XmlTree
import app.fernreader.core.epub.zip.ZipReader

internal object ContainerParser {
    /** Path of the package document from META-INF/container.xml, or null if missing/unusable. */
    fun opfPath(zip: ZipReader): String? {
        val e = zip.entry("META-INF/container.xml") ?: return null
        val root = runCatching { XmlTree.parse(TextDecoding.decode(zip.readBytes(e, 1L shl 20))).root }.getOrNull() ?: return null
        val rootfiles = root.all("rootfile").toList()
        val pick = rootfiles.firstOrNull { it.attr("media-type") == "application/oebps-package+xml" } ?: rootfiles.firstOrNull()
        return pick?.attr("full-path")?.takeIf { it.isNotBlank() && zip.entry(it) != null }
    }
}
