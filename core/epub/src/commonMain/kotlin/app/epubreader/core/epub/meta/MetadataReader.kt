package app.epubreader.core.epub.meta

import app.epubreader.core.epub.markup.XmlElement
import app.epubreader.core.epub.pkg.BookMetadata

object MetadataReader {
    private val WS = Regex("\\s+")
    private val ARTICLES = listOf("the ", "a ", "an ", "le ", "la ", "les ", "l'", "der ", "die ", "das ", "el ", "los ", "las ")

    fun read(opf: XmlElement): BookMetadata {
        val md = opf.first("metadata")
        val refines = md?.all("meta")?.mapNotNull { m -> m.attr("refines")?.removePrefix("#")?.let { it to ((m.attr("property") ?: "") to m.text().trim()) } }?.toList().orEmpty()
        fun refined(id: String?, prop: String) = refines.firstOrNull { it.first == id && it.second.first == prop }?.second?.second
        val titles = md?.all("title")?.toList().orEmpty()
        val title = (titles.firstOrNull { refined(it.attr("id"), "title-type") == "main" } ?: titles.firstOrNull())?.text()?.let { WS.replace(it, " ").trim() }?.takeIf { it.isNotEmpty() }
        val creators = md?.all("creator")?.toList().orEmpty()
        fun role(c: XmlElement) = c.attr("role") ?: refined(c.attr("id"), "role")
        val authors = (creators.filter { role(it) == "aut" }.ifEmpty { creators.filter { role(it) == null }.ifEmpty { creators } })
            .map { WS.replace(it.text(), " ").trim() }.filter { it.isNotEmpty() }
        val lang = md?.first("language")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        return BookMetadata(title, authors, lang, title?.let(::isRtl) ?: false)
    }

    /** True if the first strong directional character is Hebrew or Arabic script. */
    fun isRtl(text: String): Boolean {
        for (c in text) {
            val v = c.code
            if (v in 0x0590..0x08FF || v in 0xFB1D..0xFDFF || v in 0xFE70..0xFEFF) return true
            if (c.isLetter()) return false
        }
        return false
    }

    fun sortKey(title: String): String {
        val t = title.trim().lowercase()
        val a = ARTICLES.firstOrNull { t.startsWith(it) && t.length > it.length }
        return if (a != null) t.substring(a.length) else t
    }
}
