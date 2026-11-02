package app.epubreader.core.epub.pages

import app.epubreader.core.epub.TextPos
import app.epubreader.core.epub.markup.Attr
import app.epubreader.core.epub.markup.Entities
import app.epubreader.core.epub.markup.MarkupScanner
import app.epubreader.core.epub.markup.MarkupVisitor
import app.epubreader.core.epub.markup.Paths
import app.epubreader.core.epub.markup.TextDecoding
import app.epubreader.core.epub.markup.XmlElement
import app.epubreader.core.epub.markup.XmlTree
import app.epubreader.core.epub.pkg.BookPackage
import app.epubreader.core.epub.text.ExtractedText
import app.epubreader.core.epub.text.TextExtractor
import app.epubreader.core.epub.zip.ZipReader
import java.io.IOException

/** One print page as a page list names it: its [label] and its target, spine document [path] and [fragment] (null = its start). */
data class PageTarget(val label: String, val path: String, val fragment: String?)

/**
 * The book's print page lists (C-083 (1)), each in its own order: the EPUB 3 navigation document's `page-list`, the
 * NCX `pageList`, an Adobe `page-map.xml`. A list the book does not have is empty. Small documents: parsed whole.
 */
object PageLists {
    fun read(zip: ZipReader, pkg: BookPackage): Map<PageSource, List<PageTarget>> = mapOf(
        PageSource.NAV to safe { nav(zip, pkg) },
        PageSource.NCX to safe { ncx(zip, pkg) },
        PageSource.PAGE_MAP to safe { pageMap(zip, pkg) },
    )

    private inline fun safe(read: () -> List<PageTarget>): List<PageTarget> = try { read() } catch (e: IOException) { throw e } catch (e: Exception) { emptyList() }

    private fun doc(zip: ZipReader, path: String, html: Boolean): XmlElement? =
        zip.entry(path)?.let { XmlTree.parse(TextDecoding.decode(zip.readBytes(it)), html).root }

    private fun target(base: String, href: String?, label: String?): PageTarget? {
        val h = href?.trim()?.takeIf { it.isNotEmpty() && !Paths.isExternal(it) } ?: return null
        val l = label?.let { WS.replace(it, " ").trim() }?.takeIf { it.isNotEmpty() } ?: return null
        return PageTarget(l, Paths.resolve(Paths.dirOf(base), h), Paths.fragmentOf(h)?.takeIf { it.isNotEmpty() })
    }

    private fun tokens(v: String?) = v?.split(' ', '\t', '\n')?.filter { it.isNotEmpty() }.orEmpty()

    fun nav(zip: ZipReader, pkg: BookPackage): List<PageTarget> {
        val item = pkg.manifest.firstOrNull { "nav" in it.properties && it.present } ?: return emptyList()
        val root = doc(zip, item.path, html = true) ?: return emptyList()
        val list = root.all("nav").firstOrNull { n -> "page-list" in tokens(n.attr("type")) || "doc-pagelist" in tokens(n.attr("role")) }
            ?: return emptyList()
        return list.all("a").mapNotNull { a -> target(item.path, a.attr("href"), a.text()) }.toList()
    }

    fun ncx(zip: ZipReader, pkg: BookPackage): List<PageTarget> {
        val byToc = pkg.opf.first("spine")?.attr("toc")?.let(pkg::item)
        val item = (byToc ?: pkg.manifest.firstOrNull { it.mediaType == "application/x-dtbncx+xml" })?.takeIf { it.present } ?: return emptyList()
        val list = doc(zip, item.path, html = false)?.first("pagelist") ?: return emptyList()
        return list.all("pagetarget").mapNotNull { t ->
            target(item.path, t.first("content")?.attr("src"), t.first("navlabel")?.first("text")?.text() ?: t.attr("value"))
        }.toList()
    }

    fun pageMap(zip: ZipReader, pkg: BookPackage): List<PageTarget> {
        val byAttr = pkg.opf.first("spine")?.attr("page-map")?.let(pkg::item)
        val item = (byAttr ?: pkg.manifest.firstOrNull { it.mediaType == "application/oebps-page-map+xml" })?.takeIf { it.present } ?: return emptyList()
        val root = doc(zip, item.path, html = false) ?: return emptyList()
        return root.all("page").mapNotNull { p -> target(item.path, p.attr("href"), p.attr("name")) }.toList()
    }

    private val WS = Regex("\\s+")
}

/**
 * Computes a book's pages (C-083) in one pass over its spine documents (original documents, so a chunked book gets
 * the same pages as unchunked, (5)): the first page list of NAV, NCX, PAGE_MAP with at least one entry that resolves
 * (a spine document, and the fragment's element in it), else pagebreak markers in the text, else virtual pages of
 * [BookPages.VIRTUAL_CHARS] characters. Each spine document is read once and extracted once; [onSection] sees
 * every extraction (null for a missing or unreadable item), so a caller can derive more from the same pass. Call on
 * an I/O thread: it reads the whole book.
 */
object BookPagesBuilder {
    fun build(zip: ZipReader, pkg: BookPackage, onSection: (Int, ExtractedText?) -> Unit = { _, _ -> }): BookPages {
        val lists = PageLists.read(zip, pkg)
        val wanted = HashMap<String, HashSet<String>>() // spine path -> fragments any list names
        for (l in lists.values) for (t in l) t.fragment?.let { wanted.getOrPut(t.path) { HashSet() } += it }
        val spineOf = HashMap<String, Int>().apply { for (s in pkg.spine) if (s.present) putIfAbsent(s.path, s.index) }
        val idOffsets = HashMap<String, Map<String, Int>>() // path -> fragment -> text offset
        val markers = ArrayList<Pair<String, TextPos>>()
        val lengths = IntArray(pkg.spine.size)
        val counter = VirtualCounter()

        for (item in pkg.spine) {
            val markup = if (!item.present) null else try {
                zip.entry(item.path)?.let { TextDecoding.decode(zip.readBytes(it)) }
            } catch (e: IOException) { throw e } catch (e: Exception) { null }
            if (markup == null) { onSection(item.index, null); counter.section(item.index, ""); continue }
            val found = scan(markup, wanted[item.path].orEmpty())
            val cuts = (found.ids.values + found.markers.map { it.second }).distinct().sorted().toIntArray()
            val ex = try { TextExtractor.extract(markup, cuts) } catch (e: Exception) { null }
            onSection(item.index, ex)
            if (ex == null) { counter.section(item.index, ""); continue }
            val at = HashMap<Int, Int>().apply { cuts.forEachIndexed { i, c -> put(c, ex.cutTextOffsets[i]) } }
            if (spineOf[item.path] == item.index) idOffsets[item.path] = found.ids.mapValues { at.getValue(it.value) }
            for ((label, pos) in found.markers) markers += label to TextPos(item.index, at.getValue(pos))
            lengths[item.index] = ex.text.length
            counter.section(item.index, ex.text)
        }

        for (source in listOf(PageSource.NAV, PageSource.NCX, PageSource.PAGE_MAP)) {
            val resolved = lists.getValue(source).mapNotNull { t ->
                val spine = spineOf[t.path] ?: return@mapNotNull null
                val off = if (t.fragment == null) 0 else idOffsets[t.path]?.get(t.fragment) ?: return@mapNotNull null
                t.label to TextPos(spine, off)
            }
            if (resolved.isNotEmpty()) return print(source, resolved, lengths)
        }
        if (markers.isNotEmpty()) return print(PageSource.MARKERS, markers, lengths)
        return BookPages.of(PageSource.VIRTUAL, null, counter.starts, lengths)
    }

    /** Sorted into reading order (stable: a list's own order among equal positions). */
    private fun print(source: PageSource, entries: List<Pair<String, TextPos>>, lengths: IntArray): BookPages {
        val sorted = entries.sortedWith(compareBy<Pair<String, TextPos>> { it.second.spineIndex }.thenBy { it.second.offset })
        return BookPages.of(source, sorted.map { it.first }, sorted.map { it.second }, lengths)
    }

    private class Found(val ids: Map<String, Int>, val markers: List<Pair<String, Int>>)

    /**
     * Markup positions (start tags) of the elements whose id (or `a` name) is one of [fragments], and of pagebreak
     * markers with their labels (`title`, else `aria-label`, else the marker's own text).
     */
    private fun scan(markup: String, fragments: Set<String>): Found {
        val ids = HashMap<String, Int>()
        val markers = ArrayList<Pair<String, Int>>()
        var open: Pair<String, Int>? = null // a marker without a label attribute: its text is collected up to its end tag
        val text = StringBuilder()
        MarkupScanner.scan(markup, object : MarkupVisitor {
            override fun startTag(name: String, attrs: List<Attr>, selfClosing: Boolean, start: Int, end: Int) {
                if (fragments.isNotEmpty()) {
                    val id = attrs.firstOrNull { it.localName.equals("id", true) }?.value
                        ?: attrs.firstOrNull { it.localName.equals("name", true) && name.substringAfter(':').equals("a", true) }?.value
                    if (id != null && id in fragments) ids.putIfAbsent(id, start)
                }
                if (open != null) return
                val marker = attrs.any { (it.localName.equals("type", true) && "pagebreak" in tokens(it.value)) || (it.localName.equals("role", true) && "doc-pagebreak" in tokens(it.value)) }
                if (!marker) return
                val label = (attrs.firstOrNull { it.localName.equals("title", true) } ?: attrs.firstOrNull { it.localName.equals("aria-label", true) })?.value?.let(::clean)
                when {
                    !label.isNullOrEmpty() -> markers += label to start
                    !selfClosing -> { open = name to start; text.setLength(0) }
                }
            }
            override fun endTag(name: String, start: Int, end: Int) {
                val o = open ?: return
                if (!name.equals(o.first, true)) return
                clean(text.toString()).takeIf { it.isNotEmpty() }?.let { markers += it to o.second }
                open = null
            }
            override fun text(start: Int, end: Int) { if (open != null && text.length < 64) Entities.appendDecoded(markup, start, end, text) }
        })
        return Found(ids, markers)
    }

    private fun tokens(v: String) = v.split(' ', '\t', '\n')
    private val WS = Regex("\\s+")
    private fun clean(s: String) = WS.replace(s, " ").trim()

    /**
     * C-083 (2): page starts every [BookPages.VIRTUAL_CHARS] characters of text in spine order, every whitespace run
     * (also one across a section boundary, and a boundary itself) counted as one character, none before the first
     * text. A page that starts at a whitespace run starts at the text after it. The first page starts at (0, 0).
     */
    private class VirtualCounter {
        val starts = arrayListOf(TextPos(0, 0))
        private var n = 0L
        private var gap = false

        fun section(index: Int, text: String) {
            if (n > 0) gap = true
            for (j in text.indices) {
                if (text[j].isWhitespace()) { if (n > 0) gap = true; continue }
                if (gap) { count(index, j); gap = false }
                count(index, j)
            }
        }

        private fun count(index: Int, j: Int) {
            if (n > 0 && n % BookPages.VIRTUAL_CHARS == 0L) starts += TextPos(index, j)
            n++
        }
    }
}
