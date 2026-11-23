package app.fernreader.core.epub.chunk

import app.fernreader.core.epub.Corpus
import app.fernreader.core.epub.TextPos
import app.fernreader.core.epub.cfi.Cfi
import app.fernreader.core.epub.markup.XmlElement
import app.fernreader.core.epub.markup.XmlTree
import app.fernreader.core.epub.openFileSource
import app.fernreader.core.epub.pkg.BookPackage
import app.fernreader.core.epub.pkg.LoadResult
import app.fernreader.core.epub.pkg.PackageLoader
import app.fernreader.core.epub.zip.ZipReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** End-to-end: CFIs and text offsets computed on parts and on the original address the same content. */
class ChunkMapTest {
    private val kidCache = java.util.IdentityHashMap<XmlElement, Pair<List<XmlElement>, IntArray>>()

    /** Element children of [e] and their positions in [XmlElement.children]. */
    private fun kids(e: XmlElement) = kidCache.getOrPut(e) {
        val pos = e.children.indices.filter { e.children[it] is XmlElement }
        pos.map { e.children[it] as XmlElement } to pos.toIntArray()
    }

    /** What a CFI's path addresses: an element's name and text, or the text of a gap between elements. */
    private fun resolve(root: XmlElement, cfi: String): String {
        var e = root
        for (st in Cfi.parse(cfi)!!.parent.steps.drop(2)) {
            val (kids, pos) = kids(e)
            if (st.index % 2 == 1) {
                val k = (st.index - 1) / 2   // gap before element k
                val from = if (k == 0) 0 else pos[k - 1] + 1
                val to = if (k < kids.size) pos[k] else e.children.size
                return "gap:" + e.children.subList(from, to).joinToString("") { it as String }
            }
            e = kids.getOrNull(st.index / 2 - 1) ?: return "virtual:${st.index}"
        }
        return e.localName + ":" + e.text()
    }

    private class Book(val zip: ZipReader, val pkg: BookPackage, val origRoot: XmlElement, val origText: String)

    private fun load(z: ZipReader, pkg: BookPackage, path: String): Book {
        val markup = ChunkBooks.original(z, path)
        return Book(z, pkg, XmlTree.parse(markup).root!!, ChunkBooks.text(markup))
    }

    private class Parts(val cb: ChunkedBook) {
        private val roots = HashMap<String, XmlElement>()
        private val texts = HashMap<String, String>()
        val d = cb.map.docs.values.single()
        fun root(vCfi: String) = roots.getOrPut(pathOf(vCfi)) { XmlTree.parse(cb.virtualEntry(pathOf(vCfi))!!.decodeToString()).root!! }
        fun text(section: Int) = cb.map.virtualSpinePaths[section].let { p -> texts.getOrPut(p) { ChunkBooks.text(cb.virtualEntry(p)!!.decodeToString()) } }
        private fun pathOf(vCfi: String): String {
            val slot = Cfi.parse(vCfi)!!.parent.steps[1].index / 2 - 1
            val before = cb.map.spine.indexOfFirst { it.path == d.path }
            return d.parts[slot - before].path
        }
    }

    private fun <T> huge(block: (Book) -> T): T = openFileSource(Corpus.generated("stress-huge-single-chapter.epub").path).use { src ->
        val (z, r) = PackageLoader.open(src)
        val pkg = (r as LoadResult.Ok).pkg
        block(load(z!!, pkg, pkg.spine.single().path))
    }

    private fun slotStep(map: ChunkMap, path: String) = 2 * (map.spine.indexOfFirst { it.path == path } + 1)

    /** The spine step as the reader writes it for the original document (with the itemref's id, if any). */
    private fun origSpine(map: ChunkMap, path: String) =
        "/6/${slotStep(map, path)}" + (map.spine.first { it.path == path }.id?.let { "[$it]" } ?: "") + "!"

    private fun checkEveryChildStep(b: Book, cb: ChunkedBook, container: String, stride: Int = 1) {
        val parts = Parts(cb)
        val spine = origSpine(cb.map, parts.d.path)
        val n = parts.d.parts.sumOf { it.childCount }
        for (s in 1..2 * n + 1 step stride) {
            val o = "epubcfi($spine$container/$s)"
            val v = cb.map.cfiToVirtual(o)
            assertEquals(o, cb.map.cfiToOriginal(v), o)
            assertEquals(resolve(b.origRoot, o), resolve(parts.root(v), v), "$o -> $v")
        }
    }

    private fun checkEveryTextOffset(b: Book, cb: ChunkedBook) {
        val parts = Parts(cb)
        val i = cb.map.spinePaths.indexOf(parts.d.path)
        for (off in 0..b.origText.length) {
            val v = cb.map.textToVirtual(TextPos(i, off))
            assertEquals(TextPos(i, off), cb.map.textToOriginal(v))
            if (off < b.origText.length) assertEquals(b.origText[off], parts.text(v.spineIndex)[v.offset], "offset $off -> $v")
        }
    }

    @Test fun every_element_and_gap_round_trips_and_addresses_the_same_content() = huge { b ->
        val cb = Chunker().chunk(b.zip, b.pkg, MemoryChunkStore())
        assertTrue(cb.map.docs.values.single().parts.size > 10)
        checkEveryChildStep(b, cb, "/4")
    }

    @Test fun every_text_offset_maps_to_the_same_character() = huge { b ->
        checkEveryTextOffset(b, Chunker().chunk(b.zip, b.pkg, MemoryChunkStore()))
    }

    @Test fun position_survives_policy_change() = huge { b ->
        val a = Chunker(ChunkPolicy()).chunk(b.zip, b.pkg, MemoryChunkStore())
        val c = Chunker(ChunkPolicy(thresholdBytes = 256 * 1024, targetChars = 64 * 1024)).chunk(b.zip, b.pkg, MemoryChunkStore())
        val pa = Parts(a)
        val pc = Parts(c)
        assertTrue(pc.d.parts.size > pa.d.parts.size)
        val section = a.map.spinePaths.indexOf(pa.d.path)
        for ((vi, part) in pa.d.parts.withIndex()) {
            val slot = slotStep(a.map, pa.d.path) + 2 * vi
            for (k in listOf(1, 2, part.childCount / 2, part.childCount)) {
                // a position, a highlight range and a gap saved while reading under policy A
                for (vA in listOf("epubcfi(/6/$slot!/4/${2 * k}/1:3)", "epubcfi(/6/$slot!/4/${2 * k},/1:0,/1:5)", "epubcfi(/6/$slot!/4/${2 * k - 1}:0)")) {
                    val saved = a.map.cfiToOriginal(vA)          // what the database stores
                    val vC = c.map.cfiToVirtual(saved)           // reopened under policy C
                    assertEquals(resolve(pa.root(vA), vA), resolve(pc.root(vC), vC), "$vA -> $saved -> $vC")
                    assertEquals(resolve(b.origRoot, saved), resolve(pc.root(vC), vC))
                    assertEquals(saved, c.map.cfiToOriginal(vC))
                }
            }
            // a search hit / text position saved under A
            val tA = TextPos(a.map.virtualSpinePaths.indexOf(part.path), part.textLength / 3)
            val tC = c.map.textToVirtual(a.map.textToOriginal(tA))
            assertEquals(pa.text(tA.spineIndex)[tA.offset], pc.text(tC.spineIndex)[tC.offset])
            assertEquals(TextPos(section, part.textStart + part.textLength / 3), c.map.textToOriginal(tC))
        }
    }

    @Test fun positions_written_without_chunking_resolve() = huge { b ->
        // M2..M7 rows were written by the reader on the unsplit document; their CFIs are original CFIs as they are.
        val cb = Chunker().chunk(b.zip, b.pkg, MemoryChunkStore())
        val parts = Parts(cb)
        val step = slotStep(cb.map, parts.d.path)
        val marker = b.origRoot.first("body")!!.elements().indexOfFirst { "MARKERWORDHUGE" in it.text() }
        val o = "epubcfi(/6/$step!/4/${2 * (marker + 1)}/1:4)"
        val v = cb.map.cfiToVirtual(o)
        assertTrue("MARKERWORDHUGE" in resolve(parts.root(v), v), v)
        val hit = b.origText.indexOf("MARKERWORDHUGE")
        val tv = cb.map.textToVirtual(TextPos(0, hit))
        assertTrue(parts.text(tv.spineIndex).startsWith("MARKERWORDHUGE", tv.offset))
    }

    @Test fun toc_targets_land_in_the_right_part() = huge { b ->
        val cb = Chunker().chunk(b.zip, b.pkg, MemoryChunkStore())
        val d = cb.map.docs.values.single()
        val nav = cb.virtualPaths.filter { it != b.pkg.opfPath && it !in cb.map.partPaths }.map { cb.virtualEntry(it)!!.decodeToString() }
        assertTrue(nav.isNotEmpty())
        val s40 = cb.map.hrefToVirtual("${d.path}#s40").substringAfterLast('/')
        assertTrue(nav.all { "$s40\"" in it || "$s40'" in it }, s40)
        assertTrue("id=\"s40\"" in cb.virtualEntry(cb.map.hrefToVirtual("${d.path}#s40").substringBefore('#'))!!.decodeToString())
    }

    @Test fun splits_inside_single_wrapper() {
        val (z, pkg) = ChunkBooks.book("Before <div class=\"wrap\">\n${ChunkBooks.paras(1000)}\n</div> After")
        val b = load(z, pkg, "OPS/big.xhtml")
        val cb = Chunker(ChunkBooks.small).chunk(z, pkg, MemoryChunkStore())
        assertTrue(cb.map.docs.values.single().wrapped)
        checkEveryChildStep(b, cb, "/4/2")
        checkEveryTextOffset(b, cb)
        val spine = origSpine(cb.map, "OPS/big.xhtml")
        val parts = Parts(cb)
        for (s in listOf(1, 3)) {
            val o = "epubcfi($spine/4/$s:2)"
            val v = cb.map.cfiToVirtual(o)
            assertEquals(resolve(b.origRoot, o), resolve(parts.root(v), v))
        }
    }

    @Test fun unbalanced_markup_is_not_split() {
        val (z, pkg) = ChunkBooks.book(ChunkBooks.paras(1000) + "<div><p>unclosed")
        val map = Chunker(ChunkBooks.small).chunk(z, pkg, MemoryChunkStore()).map
        assertTrue(map.isIdentity)
        val cfi = "epubcfi(/6/6[ref-big]!/4/1000/1:3)"
        assertEquals(cfi, map.cfiToVirtual(cfi))
        assertEquals(cfi, map.cfiToOriginal(cfi))
        assertEquals(TextPos(1, 123456), map.textToVirtual(TextPos(1, 123456)))
    }
}
