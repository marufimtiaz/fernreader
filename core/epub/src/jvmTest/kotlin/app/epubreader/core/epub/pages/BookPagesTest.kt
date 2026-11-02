package app.epubreader.core.epub.pages

import app.epubreader.core.epub.TextPos
import app.epubreader.core.epub.b
import app.epubreader.core.epub.chunk.ChunkBooks
import app.epubreader.core.epub.chunk.Chunker
import app.epubreader.core.epub.chunk.MemoryChunkStore
import app.epubreader.core.epub.pkg.BookPackage
import app.epubreader.core.epub.pkg.LoadResult
import app.epubreader.core.epub.pkg.PackageLoader
import app.epubreader.core.epub.text.TextExtractor
import app.epubreader.core.epub.zip.ByteArraySource
import app.epubreader.core.epub.zip.ZipReader
import app.epubreader.core.epub.zipOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C-083 fixtures: three chapters, each with a print page break in the middle of its text (`<span id="pgN">`, roman
 * labels i..vi, two per chapter: one at the chapter start, one mid-way), carried by an EPUB 3 nav page-list, an EPUB 2
 * NCX pageList, a page-map, only inline markers, or nothing (virtual pages).
 */
internal object PageBooks {
    enum class Kind { NAV, NCX, PAGE_MAP, MARKERS, NONE }
    val ROMAN = listOf("i", "ii", "iii", "iv", "v", "vi")

    fun chapter(c: Int, kind: Kind, paragraphs: Int = 20): String {
        val marker = if (kind == Kind.NONE) "" else if (kind == Kind.MARKERS) " epub:type=\"pagebreak\" role=\"doc-pagebreak\"" else ""
        fun brk(n: Int) = if (kind == Kind.NONE) "" else "<span id=\"pg$n\"$marker title=\"${ROMAN[n - 1]}\"/>"
        val first = (c - 1) * 2 + 1
        val paras = (1..paragraphs).joinToString("\n") { p ->
            (if (p == paragraphs / 2 + 1) brk(first + 1) else "") + "<p>Chapter $c paragraph $p. ${"Lorem ipsum dolor sit amet. ".repeat(6)}</p>"
        }
        return """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>C$c</title></head><body>${brk(first)}<h1>Chapter $c</h1>
$paras
</body></html>"""
    }

    fun zip(kind: Kind, chapters: Int = 3, extraNav: String = "", paragraphs: Int = 20): ByteArray {
        val labels = (1..chapters * 2).map { n -> ROMAN.getOrElse(n - 1) { "p$n" } to "c${(n + 1) / 2}.xhtml#pg$n" }
        val pageList = if (kind == Kind.NAV) "<nav epub:type=\"page-list\" hidden=\"\"><ol>" + labels.joinToString("") { (l, h) -> "<li><a href=\"$h\">$l</a></li>" } + extraNav + "</ol></nav>" else ""
        val ncxPages = if (kind == Kind.NCX) "<pageList><navLabel><text>Pages</text></navLabel>" + labels.withIndex().joinToString("") { (i, p) ->
            "<pageTarget id=\"pt$i\" type=\"front\" value=\"${i + 1}\" playOrder=\"${i + 1}\"><navLabel><text>${p.first}</text></navLabel><content src=\"${p.second}\"/></pageTarget>"
        } + "</pageList>" else ""
        val pageMap = "<page-map xmlns=\"http://www.idpf.org/2007/opf\">" + labels.joinToString("") { (l, h) -> "<page name=\"$l\" href=\"$h\"/>" } + "</page-map>"
        val items = (1..chapters).joinToString("") { "<item id=\"c$it\" href=\"c$it.xhtml\" media-type=\"application/xhtml+xml\"/>" }
        val spine = (1..chapters).joinToString("") { "<itemref idref=\"c$it\"/>" }
        val map = if (kind == Kind.PAGE_MAP) "<item id=\"map\" href=\"page-map.xml\" media-type=\"application/oebps-page-map+xml\"/>" else ""
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="${if (kind == Kind.NCX) "2.0" else "3.0"}" unique-identifier="u"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="u">pages-$kind</dc:identifier><dc:title>Pages $kind</dc:title></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>$map$items</manifest><spine toc="ncx"${if (kind == Kind.PAGE_MAP) " page-map=\"map\"" else ""}>$spine</spine></package>"""
        val toc = (1..chapters).joinToString("") { "<li><a href=\"c$it.xhtml\">Chapter $it</a></li>" }
        val nav = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Nav</title></head><body><nav epub:type="toc"><ol>$toc</ol></nav>$pageList</body></html>"""
        val ncx = """<?xml version="1.0"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head/><docTitle><text>T</text></docTitle><navMap>${(1..chapters).joinToString("") { "<navPoint id=\"n$it\" playOrder=\"$it\"><navLabel><text>Chapter $it</text></navLabel><content src=\"c$it.xhtml\"/></navPoint>" }}</navMap>$ncxPages</ncx>"""
        val entries = mutableListOf(
            "mimetype" to "application/epub+zip".b(),
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OPS/p.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".b(),
            "OPS/p.opf" to opf.b(), "OPS/nav.xhtml" to nav.b(), "OPS/toc.ncx" to ncx.b(),
        )
        if (kind == Kind.PAGE_MAP) entries += "OPS/page-map.xml" to pageMap.b()
        for (c in 1..chapters) entries += "OPS/c$c.xhtml" to chapter(c, kind, paragraphs).b()
        return zipOf(*entries.toTypedArray())
    }

    fun open(bytes: ByteArray): Pair<ZipReader, BookPackage> {
        val z = ZipReader.open(ByteArraySource(bytes))
        return z to (PackageLoader.load(z) as LoadResult.Ok).pkg
    }

    fun build(bytes: ByteArray): BookPages = open(bytes).let { (z, p) -> BookPagesBuilder.build(z, p) }
}

class BookPagesTest {
    private fun textOf(bytes: ByteArray, spine: Int) = PageBooks.open(bytes).let { (z, p) -> TextExtractor.extract(z.readBytes(z.entry(p.spine[spine].path)!!).decodeToString()).text }

    private fun assertPrint(kind: PageBooks.Kind, source: PageSource) {
        val bytes = PageBooks.zip(kind)
        val pages = PageBooks.build(bytes)
        assertEquals(source, pages.source)
        assertEquals(PageBooks.ROMAN, (0 until pages.count).map(pages::label))
        // i at each chapter's start, ii mid-way: right before "Chapter 1 paragraph 11".
        assertEquals(TextPos(0, 0), pages.start(0))
        val c1 = textOf(bytes, 0)
        assertEquals(c1.indexOf("Chapter 1 paragraph 11."), pages.start(1).offset)
        assertEquals(TextPos(1, 0), pages.start(2))
        assertEquals(1, pages.indexAt(TextPos(0, c1.indexOf("Chapter 1 paragraph 11."))))
        assertEquals(0, pages.indexAt(TextPos(0, c1.indexOf("Chapter 1 paragraph 11.") - 1)))
        assertEquals(3, pages.indexAt(TextPos(1, 100_000)))
        assertEquals(pages.lengths.toList(), (0..2).map { textOf(bytes, it).length })
    }

    @Test fun epub3_nav_page_list() = assertPrint(PageBooks.Kind.NAV, PageSource.NAV)
    @Test fun epub2_ncx_page_list() = assertPrint(PageBooks.Kind.NCX, PageSource.NCX)
    @Test fun adobe_page_map() = assertPrint(PageBooks.Kind.PAGE_MAP, PageSource.PAGE_MAP)
    @Test fun pagebreak_markers_without_a_list() = assertPrint(PageBooks.Kind.MARKERS, PageSource.MARKERS)

    @Test fun entries_that_do_not_resolve_are_skipped() {
        val extra = "<li><a href=\"c1.xhtml#nowhere\">vii</a></li><li><a href=\"gone.xhtml#pg1\">viii</a></li><li><a href=\"http://example.com/#x\">ix</a></li>"
        val pages = PageBooks.build(PageBooks.zip(PageBooks.Kind.NAV, extraNav = extra))
        assertEquals(PageBooks.ROMAN, (0 until pages.count).map(pages::label))
    }

    @Test fun a_list_where_nothing_resolves_gives_virtual_pages() {
        val bytes = PageBooks.zip(PageBooks.Kind.NONE).let { b ->
            // NONE has no ids: a nav page-list pointing at them resolves to nothing.
            val (z, _) = PageBooks.open(b)
            val nav = z.readBytes(z.entry("OPS/nav.xhtml")!!).decodeToString().replace("</body>", "<nav epub:type=\"page-list\"><ol><li><a href=\"c1.xhtml#pg1\">i</a></li></ol></nav></body>")
            zipOf(*z.entries.filter { !it.isDirectory }.map { e -> e.name to (if (e.name == "OPS/nav.xhtml") nav.b() else z.readBytes(e)) }.toTypedArray())
        }
        assertEquals(PageSource.VIRTUAL, PageBooks.build(bytes).source)
    }

    @Test fun virtual_pages_every_1800_characters_with_whitespace_runs_as_one() {
        val bytes = PageBooks.zip(PageBooks.Kind.NONE, paragraphs = 40)
        val pages = PageBooks.build(bytes)
        assertEquals(PageSource.VIRTUAL, pages.source)
        assertNull(pages.labels)
        // The book's compact text: per chapter whitespace runs collapsed, chapters joined by one (boundary) space.
        val texts = (0..2).map { textOf(bytes, it) }
        val compact = texts.map { it.trim().replace(Regex("\\s+"), " ") }.joinToString(" ")
        assertEquals((compact.length + BookPages.VIRTUAL_CHARS - 1) / BookPages.VIRTUAL_CHARS, pages.count)
        assertEquals("1", pages.label(0)); assertEquals("${pages.count}", pages.label(pages.count - 1))
        // Page k starts at the compact text's character k*1800 (a non-space character here).
        for (k in 1 until pages.count) {
            val s = pages.start(k)
            val raw = texts[s.spineIndex]
            val before = (texts.take(s.spineIndex).joinToString(" ") { it.trim().replace(Regex("\\s+"), " ") } + (if (s.spineIndex > 0) " " else "") +
                raw.substring(0, s.offset).trimStart().replace(Regex("\\s+"), " ")).length
            // A page whose first character is a whitespace run starts at the text after it.
            val atRun = s.offset > 0 && raw[s.offset - 1].isWhitespace() || s.offset == 0 && s.spineIndex > 0
            assertEquals(k * BookPages.VIRTUAL_CHARS + (if (atRun && before == k * BookPages.VIRTUAL_CHARS + 1) 1 else 0), before, "page ${k + 1}")
            assertTrue(!raw[s.offset].isWhitespace())
        }
    }

    @Test fun virtual_pages_are_the_same_at_every_call() =
        assertEquals(PageBooks.build(PageBooks.zip(PageBooks.Kind.NONE)), PageBooks.build(PageBooks.zip(PageBooks.Kind.NONE)))

    @Test fun go_to_finds_labels_without_case_and_numbers_for_virtual_pages() {
        val print = PageBooks.build(PageBooks.zip(PageBooks.Kind.NAV))
        assertEquals(3, print.find("iv")); assertEquals(3, print.find(" IV ")); assertNull(print.find("4")); assertNull(print.find("xii")); assertNull(print.find(""))
        val virtual = PageBooks.build(PageBooks.zip(PageBooks.Kind.NONE))
        assertEquals(0, virtual.find("1")); assertEquals(virtual.count - 1, virtual.find("${virtual.count}")); assertNull(virtual.find("0")); assertNull(virtual.find("${virtual.count + 1}")); assertNull(virtual.find("ii"))
    }

    @Test fun a_label_used_twice_goes_to_the_next_one_after_the_current_page() {
        val p = BookPages.of(PageSource.NAV, listOf("1", "2", "1", "2"), listOf(TextPos(0, 0), TextPos(0, 5), TextPos(1, 0), TextPos(1, 5)), IntArray(2))
        assertEquals(0, p.find("1")); assertEquals(2, p.find("1", from = 1)); assertEquals(0, p.find("1", from = 3))
    }

    @Test fun stored_form_round_trips() {
        for (k in PageBooks.Kind.entries) {
            val p = PageBooks.build(PageBooks.zip(k))
            assertEquals(p, BookPages.decode(p.source.name, p.encodeLabels(), p.encodeStarts(), p.encodeLengths()), "$k")
        }
        assertNull(BookPages.decode("NAV", null, "0.0", "1"))
        assertNull(BookPages.decode("VIRTUAL", null, "garbage", ""))
        assertNull(BookPages.decode("NOPE", null, "0.0", ""))
    }

    /** C-083 (5): a chunked document's positions, mapped to the original, get the unchunked book's pages. */
    @Test fun chunked_documents_get_the_pages_of_the_unchunked_book() {
        val (z, pkg) = ChunkBooks.book("\n" + ChunkBooks.paras(1000) + "\n")
        val pages = BookPagesBuilder.build(z, pkg)
        assertEquals(PageSource.VIRTUAL, pages.source)
        val cb = Chunker(ChunkBooks.small).chunk(z, pkg, MemoryChunkStore())
        val big = pkg.spine.first { it.path == "OPS/big.xhtml" }.index
        val d = cb.map.docs.getValue("OPS/big.xhtml")
        val firstPart = cb.map.textToVirtual(TextPos(big, 0)).spineIndex
        var checked = 0
        for ((k, part) in d.parts.withIndex()) for (off in listOf(0, part.textLength / 2, part.textLength - 1)) {
            val original = cb.map.textToOriginal(TextPos(firstPart + k, off))
            assertEquals(TextPos(big, part.textStart + off), original)
            assertEquals(pages.indexAt(TextPos(big, part.textStart + off)), pages.indexAt(original)); checked++
        }
        assertTrue(checked > 30)
        assertTrue(pages.count > d.parts.size, "${pages.count} pages")
    }

    @Test fun missing_spine_items_count_as_empty() {
        val (z, _) = PageBooks.open(PageBooks.zip(PageBooks.Kind.NONE))
        val bytes = zipOf(*z.entries.filter { !it.isDirectory && it.name != "OPS/c2.xhtml" }.map { it.name to z.readBytes(it) }.toTypedArray())
        val pages = PageBooks.build(bytes)
        assertEquals(0, pages.lengths[1])
        assertNotNull(pages.start(pages.count - 1))
        assertTrue((0 until pages.count).none { pages.start(it).spineIndex == 1 })
    }
}
