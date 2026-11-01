package app.epubreader.core.epub.chunk

import app.epubreader.core.epub.b
import app.epubreader.core.epub.markup.TextDecoding
import app.epubreader.core.epub.markup.XmlTree
import app.epubreader.core.epub.pkg.BookPackage
import app.epubreader.core.epub.pkg.LoadResult
import app.epubreader.core.epub.pkg.PackageLoader
import app.epubreader.core.epub.text.TextExtractor
import app.epubreader.core.epub.zip.ByteArraySource
import app.epubreader.core.epub.zip.ZipReader
import app.epubreader.core.epub.zipOf
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A small book whose OPS/big.xhtml is large enough to split under [small]. */
internal object ChunkBooks {
    val small = ChunkPolicy(thresholdBytes = 20_000, targetChars = 10_000)

    fun paras(n: Int) = (0 until n).joinToString("\n") {
        "<p id=\"p$it\">Paragraph $it ${"lorem ipsum ".repeat(20)}<a href=\"#p${(it * 7) % n}\">x</a></p>"
    }

    fun book(
        body: String,
        other: String = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p><a href=\"big.xhtml#p77\">to 77</a> <a href=\"big.xhtml\">top</a></p></body></html>",
        bigName: String = "big.xhtml",
        bigHref: String = bigName,
        bigMediaType: String = "application/xhtml+xml",
        bigBytes: ((String) -> ByteArray)? = null,
        otherBytes: ((String) -> ByteArray)? = null,
        extraMeta: String = "",
        prolog: String = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>",
    ): Pair<ZipReader, BookPackage> {
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="u"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="u">t</dc:identifier><dc:title>T</dc:title>$extraMeta</metadata><manifest><item id="big" href="$bigHref" media-type="$bigMediaType" properties="mathml"/><item id="o" href="other.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/></manifest><spine toc="ncx"><itemref idref="o" id="ref-o"/><itemref idref="ghost"/><itemref idref="big" id="ref-big" properties="page-spread-left"/><itemref idref="o" linear="no"/></spine><guide><reference type="text" href="$bigHref#p3" title="Start"/></guide></package>"""
        val nav = """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="$bigHref#p500">P500</a></li><li><a href="other.xhtml">O</a></li></ol></nav></body></html>"""
        val ncx = """<?xml version="1.0"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap><navPoint id="n1"><navLabel><text>P900</text></navLabel><content src="$bigHref#p900"/></navPoint></navMap></ncx>"""
        val big = """$prolog
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>B</title></head><body>$body</body></html>"""
        val z = ZipReader.open(ByteArraySource(zipOf(
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OPS/p.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".b(),
            "OPS/p.opf" to opf.b(), "OPS/$bigName" to (bigBytes?.invoke(big) ?: big.b()), "OPS/other.xhtml" to (otherBytes?.invoke(other) ?: other.b()),
            "OPS/nav.xhtml" to nav.b(), "OPS/toc.ncx" to ncx.b())))
        return z to (PackageLoader.load(z) as LoadResult.Ok).pkg
    }

    fun text(markup: String) = TextExtractor.extract(markup).text
    fun original(z: ZipReader, path: String) = TextDecoding.decode(z.readBytes(z.entry(path)!!))

    /** The book the reader would see: zip entries overlaid with the chunked book's virtual entries. */
    fun virtualZip(z: ZipReader, cb: ChunkedBook): ZipReader {
        val entries = z.entries.map { it.name }.filter { !it.endsWith("/") }.map { it to (cb.virtualEntry(it) ?: z.readBytes(z.entry(it)!!)) } +
            cb.map.partPaths.map { it to cb.virtualEntry(it)!! }
        return ZipReader.open(ByteArraySource(zipOf(*entries.toTypedArray())))
    }
}

class ChunkerTest {
    private val small = ChunkBooks.small
    private fun chunk(z: ZipReader, pkg: BookPackage, store: ChunkStore = MemoryChunkStore(), policy: ChunkPolicy = small) =
        Chunker(policy).chunk(z, pkg, store)
    private fun chunk(b: Pair<ZipReader, BookPackage>) = chunk(b.first, b.second)

    @Test fun splits_at_body_level_and_keeps_all_text() {
        val (z, pkg) = ChunkBooks.book("\n" + ChunkBooks.paras(1000) + "\n")
        val cb = chunk(z, pkg)
        val d = cb.map.docs.getValue("OPS/big.xhtml")
        assertFalse(d.wrapped)
        assertEquals(4, d.bodyStep)
        assertTrue(d.parts.size > 10, "${d.parts.size} parts")
        assertEquals(1000, d.parts.sumOf { it.childCount })
        val partTexts = d.parts.map { ChunkBooks.text(cb.virtualEntry(it.path)!!.decodeToString()) }
        assertEquals(ChunkBooks.text(ChunkBooks.original(z, "OPS/big.xhtml")), partTexts.joinToString(""))
        d.parts.zip(partTexts).forEach { (p, t) -> assertEquals(t.length, p.textLength, p.path) }
        d.parts.zipWithNext().forEach { (a, b) -> assertEquals(a.textStart + a.textLength, b.textStart) }
        assertEquals("OPS/big.part0.xhtml", d.parts[0].path)
    }

    @Test fun every_part_is_a_well_formed_document_with_the_original_head() {
        val (z, pkg) = ChunkBooks.book(ChunkBooks.paras(1000))
        val cb = chunk(z, pkg)
        for (p in cb.map.docs.values.single().parts) {
            val s = cb.virtualEntry(p.path)!!.decodeToString()
            assertTrue(XmlTree.parse(s).wellFormed, p.path)
            assertTrue(s.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>B</title></head><body>"), p.path)
            assertTrue(s.endsWith("</body></html>"), p.path)
        }
    }

    @Test fun splits_inside_single_wrapper() {
        val (z, pkg) = ChunkBooks.book("Before <div class=\"wrap\" id=\"w\">${ChunkBooks.paras(1000)}</div> After")
        val cb = chunk(z, pkg)
        val d = cb.map.docs.getValue("OPS/big.xhtml")
        assertTrue(d.wrapped)
        assertTrue(d.parts.size > 10)
        val parts = d.parts.map { cb.virtualEntry(it.path)!!.decodeToString() }
        assertTrue(parts.all { "<div class=\"wrap\" id=\"w\">" in it && "</div></body>" in it.replace(" After", "") })
        assertTrue(parts.first().contains("<body>Before <div") && !parts[1].contains("Before"))
        assertTrue(parts.last().contains("</div> After</body>"))
        assertEquals(ChunkBooks.text(ChunkBooks.original(z, "OPS/big.xhtml")), parts.joinToString("") { ChunkBooks.text(it) })
    }

    @Test fun unbalanced_markup_is_not_split() {
        val (z, pkg) = ChunkBooks.book(ChunkBooks.paras(1000) + "<div><p>unclosed")
        val store = MemoryChunkStore()
        val cb = chunk(z, pkg, store)
        assertTrue(cb.map.isIdentity)
        assertNull(cb.virtualEntry("OPS/big.xhtml"))
        // the negative result is cached too, so a huge unsplittable document is not planned again on every open
        assertEquals(1, store.size)
        val w = store.writeCount
        assertTrue(chunk(z, pkg, store).map.isIdentity)
        assertEquals(w, store.writeCount)
    }

    @Test fun only_reflowable_xhtml_spine_documents_are_split() {
        val big = ChunkBooks.paras(1000)
        assertTrue(chunk(ChunkBooks.book(big, bigMediaType = "text/html")).map.isIdentity)
        assertTrue(chunk(ChunkBooks.book(big, extraMeta = "<meta property=\"rendition:layout\">pre-paginated</meta>")).map.isIdentity)
        assertFalse(chunk(ChunkBooks.book(big)).map.isIdentity)
    }

    @Test fun links_toc_and_package_are_retargeted() {
        val (z, pkg) = ChunkBooks.book(ChunkBooks.paras(1000))
        val cb = chunk(z, pkg)
        val d = cb.map.docs.getValue("OPS/big.xhtml")
        fun partOf(id: String) = d.parts[d.idToPart.getValue(id)].path.substringAfterLast('/')
        assertNotEquals(0, d.idToPart.getValue("p500"))

        val other = cb.virtualEntry("OPS/other.xhtml")!!.decodeToString()
        assertTrue("href=\"${partOf("p77")}#p77\"" in other, other)
        assertTrue("href=\"big.part0.xhtml\"" in other, other)
        assertTrue("href=\"${partOf("p500")}#p500\"" in cb.virtualEntry("OPS/nav.xhtml")!!.decodeToString())
        assertTrue("src=\"${partOf("p900")}#p900\"" in cb.virtualEntry("OPS/toc.ncx")!!.decodeToString())

        // an in-document link to an id in another part becomes a cross-part link; same-part links stay
        val p0 = cb.virtualEntry(d.parts[0].path)!!.decodeToString()
        assertEquals(0, d.idToPart.getValue("p7"))
        assertTrue("<a href=\"#p7\">" in p0, "same part")
        assertTrue(Regex("href=\"big\\.part[1-9]\\d*\\.xhtml#p\\d+\"").containsMatchIn(p0))

        val opf = cb.virtualEntry("OPS/p.opf")!!.decodeToString()
        assertTrue("<itemref idref=\"big\"" !in opf)
        assertTrue("<item id=\"big\" href=\"big.xhtml\"" in opf, "the original item stays in the manifest")
        assertTrue("<item id=\"big-part0\" href=\"big.part0.xhtml\" media-type=\"application/xhtml+xml\" properties=\"mathml\"/>" in opf, opf)
        assertTrue("<itemref idref=\"big-part1\" properties=\"page-spread-left\"/>" in opf, opf)
        assertTrue(opf.indexOf("idref=\"big-part0\"") < opf.indexOf("idref=\"big-part1\""))
        assertTrue("<reference type=\"text\" href=\"${partOf("p3")}#p3\"" in opf)
        assertEquals(setOf("OPS/other.xhtml", "OPS/nav.xhtml", "OPS/toc.ncx", "OPS/p.opf"), cb.virtualPaths - cb.map.partPaths)
    }

    @Test fun virtual_package_matches_the_map() {
        val (z, pkg) = ChunkBooks.book(ChunkBooks.paras(1000))
        val cb = chunk(z, pkg)
        assertEquals(listOf(ChunkMap.Slot("OPS/other.xhtml", "ref-o"), ChunkMap.Slot(null), ChunkMap.Slot("OPS/big.xhtml", "ref-big"),
            ChunkMap.Slot("OPS/other.xhtml")), cb.map.spine)
        val vz = ChunkBooks.virtualZip(z, cb)
        val vpkg = (PackageLoader.load(vz) as LoadResult.Ok).pkg
        assertEquals(cb.map.virtualSpinePaths, vpkg.spine.map { it.path })
        assertTrue(vpkg.spine.all { it.present })
        // the reader's CFI for a virtual itemref (its position among <spine>'s children) is the one the map expects
        val n = cb.map.docs.values.single().parts.size
        val opfSpine = XmlTree.parse(cb.virtualEntry("OPS/p.opf")!!.decodeToString()).root!!.first("spine")!!.elements()
        assertEquals(n + 3, opfSpine.size)
        assertEquals("big-part1", opfSpine[3].attr("idref"))
        assertEquals("epubcfi(/6/6[ref-big]!/4/2)", cb.map.cfiToOriginal("epubcfi(/6/6!/4/2)"))
        assertEquals("epubcfi(/6/${2 * (n + 3)}!/4/2)", cb.map.cfiToVirtual("epubcfi(/6/8!/4/2)"))
    }

    @Test fun percent_encoded_names_are_kept_encoded() {
        val (z, pkg) = ChunkBooks.book(ChunkBooks.paras(1000), bigName = "big chapter.xhtml", bigHref = "big%20chapter.xhtml",
            other = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><a href=\"big%20chapter.xhtml#p77\">x</a></body></html>")
        val cb = chunk(z, pkg)
        val d = cb.map.docs.getValue("OPS/big chapter.xhtml")
        assertEquals("OPS/big chapter.part0.xhtml", d.parts[0].path)
        val part77 = d.parts[d.idToPart.getValue("p77")].path.substringAfterLast('/').replace(" ", "%20")
        assertTrue("href=\"$part77#p77\"" in cb.virtualEntry("OPS/other.xhtml")!!.decodeToString())
        assertTrue("href=\"big%20chapter.part0.xhtml\"" in cb.virtualEntry("OPS/p.opf")!!.decodeToString())
        val vpkg = (PackageLoader.load(ChunkBooks.virtualZip(z, cb)) as LoadResult.Ok).pkg
        assertTrue(vpkg.spine.all { it.present })
    }

    @Test fun links_in_another_percent_encoding_are_retargeted() {
        val (z, pkg) = ChunkBooks.book(ChunkBooks.paras(1000), bigName = "café.xhtml", bigHref = "caf%C3%A9.xhtml",
            other = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><a href=\"caf%c3%a9.xhtml#p77\">x</a></body></html>")
        val cb = chunk(z, pkg)
        val d = cb.map.docs.getValue("OPS/café.xhtml")
        val part77 = d.parts[d.idToPart.getValue("p77")].path.substringAfterLast('/').replace("é", "%C3%A9")
        assertTrue("href=\"$part77#p77\"" in cb.virtualEntry("OPS/other.xhtml")!!.decodeToString())
    }

    @Test fun legacy_encodings_are_written_as_utf8() {
        val body = "<p>Café ‘quoted’</p>\n" + ChunkBooks.paras(1000)
        val (z, pkg) = ChunkBooks.book(body, prolog = "<?xml version=\"1.0\" encoding=\"windows-1252\"?>",
            bigBytes = { s -> s.toByteArray(charset("windows-1252")) })
        val cb = chunk(z, pkg)
        val p0 = cb.virtualEntry(cb.map.docs.values.single().parts[0].path)!!
        val s = p0.decodeToString()
        assertTrue(s.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"), s.take(60))
        assertEquals(s, TextDecoding.decode(p0))
        assertTrue("Café ‘quoted’" in s)
    }

    @Test fun documents_in_encodings_we_cannot_decode_are_left_alone() {
        val sjis = { s: String -> s.toByteArray(charset("Shift_JIS")) }
        val big = "<p>日本語のテキスト</p>\n" + ChunkBooks.paras(1000)
        assertTrue(chunk(ChunkBooks.book(big, prolog = "<?xml version=\"1.0\" encoding=\"Shift_JIS\"?>", bigBytes = sjis)).map.isIdentity)
        // undeclared and not UTF-8
        assertTrue(chunk(ChunkBooks.book("<p>Café</p>\n" + ChunkBooks.paras(1000), prolog = "", bigBytes = { it.toByteArray(Charsets.ISO_8859_1) })).map.isIdentity)
        // a document that links into the split one and would have to be rewritten
        val other = "<?xml version=\"1.0\" encoding=\"Shift_JIS\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>日本語</p><a href=\"big.xhtml#p77\">x</a></body></html>"
        assertTrue(chunk(ChunkBooks.book(ChunkBooks.paras(1000), other = other, otherBytes = sjis)).map.isIdentity)
    }

    @Test fun small_books_are_untouched_and_the_store_is_not_written() {
        val (z, pkg) = ChunkBooks.book(ChunkBooks.paras(10))
        val store = MemoryChunkStore()
        val cb = chunk(z, pkg, store)
        assertTrue(cb.map.isIdentity)
        assertEquals(0, store.size)
        assertEquals(listOf("OPS/other.xhtml", "OPS/big.xhtml", "OPS/other.xhtml"), cb.map.spinePaths)
    }

    @Test fun cache_is_reused_and_invalidated() {
        val (z, pkg) = ChunkBooks.book(ChunkBooks.paras(1000))
        val store = MemoryChunkStore()
        val a = chunk(z, pkg, store)
        val writes = store.writeCount
        val b = chunk(z, pkg, store)
        assertEquals(a.map, b.map)
        assertEquals(writes, store.writeCount, "a complete cache is not rewritten")
        assertEquals(a.virtualEntry("OPS/nav.xhtml")!!.decodeToString(), b.virtualEntry("OPS/nav.xhtml")!!.decodeToString())

        val other = chunk(z, pkg, store, ChunkPolicy(thresholdBytes = 20_000, targetChars = 30_000))
        assertTrue(other.map.docs.values.single().parts.size < a.map.docs.values.single().parts.size, "a policy change rebuilds")
        assertEquals(other.map, chunk(z, pkg, store, ChunkPolicy(thresholdBytes = 20_000, targetChars = 30_000)).map)

        store.write(Chunker.CACHE, "{broken".b())
        assertEquals(a.map, chunk(z, pkg, store).map, "a damaged cache rebuilds")
    }

    @Test fun cache_with_missing_or_truncated_entries_is_rebuilt() {
        val (z, pkg) = ChunkBooks.book(ChunkBooks.paras(1000))
        val store = MemoryChunkStore()
        val a = chunk(z, pkg, store)
        val part = a.map.docs.values.single().parts[3].path
        val expected = a.virtualEntry(part)!!.decodeToString()
        store.remove(Chunker.entryName(part))          // e.g. the system trimmed one cache file
        val w = store.writeCount
        val b = chunk(z, pkg, store)
        assertTrue(store.writeCount > w, "rebuilt")
        assertEquals(expected, b.virtualEntry(part)!!.decodeToString())
        store.write(Chunker.entryName("OPS/p.opf"), ByteArray(0))   // e.g. lost on power failure
        val c = chunk(z, pkg, store)
        assertTrue(c.virtualEntry("OPS/p.opf")!!.isNotEmpty())
    }

    @Test fun a_virtual_entry_that_disappears_is_an_error_not_the_zip_entry() {
        val (z, pkg) = ChunkBooks.book(ChunkBooks.paras(1000))
        val store = MemoryChunkStore()
        val cb = chunk(z, pkg, store)
        store.remove(Chunker.entryName("OPS/p.opf"))
        assertFailsWith<ChunkCacheException> { cb.virtualEntry("OPS/p.opf") }
        assertNull(cb.virtualEntry("OPS/images/cover.png"))   // not virtual: serve the zip entry
    }

    /** The content server lists virtual entries in entries.json with their sizes, without reading them (M8 8.3). */
    @Test fun virtual_sizes_are_the_store_sizes_and_missing_ones_throw() {
        val (z, pkg) = ChunkBooks.book(ChunkBooks.paras(1000))
        val store = MemoryChunkStore()
        val cb = chunk(z, pkg, store)
        for (p in cb.virtualPaths) assertEquals(cb.virtualEntry(p)!!.size.toLong(), cb.virtualSize(p), p)
        assertNull(cb.virtualSize("OPS/other-not-virtual.png"))
        store.remove(Chunker.entryName("OPS/p.opf"))
        assertFailsWith<ChunkCacheException> { cb.virtualSize("OPS/p.opf") }
    }

    /** M8 8.3 review I1: the opener asks before it creates a cache directory. */
    @Test fun has_candidates_only_when_chunk_would_use_the_store() {
        val (z, pkg) = ChunkBooks.book(ChunkBooks.paras(1000))
        assertTrue(Chunker(small).hasCandidates(z, pkg))
        assertFalse(Chunker(ChunkPolicy(thresholdBytes = Int.MAX_VALUE)).hasCandidates(z, pkg))
        val store = MemoryChunkStore()
        Chunker(ChunkPolicy(thresholdBytes = Int.MAX_VALUE)).chunk(z, pkg, store)
        assertEquals(0, store.writeCount)
    }

    @Test fun cache_from_another_file_is_not_used() {
        val store = MemoryChunkStore()
        val (z1, p1) = ChunkBooks.book(ChunkBooks.paras(1000))
        chunk(z1, p1, store)
        val (z2, p2) = ChunkBooks.book(ChunkBooks.paras(900))
        assertEquals(900, chunk(z2, p2, store).map.docs.values.single().parts.sumOf { it.childCount })
    }

    @Test fun file_store_round_trips_and_survives_reopen() {
        val dir = Files.createTempDirectory("chunks").toFile()
        try {
            val (z, pkg) = ChunkBooks.book(ChunkBooks.paras(1000))
            val a = chunk(z, pkg, FileChunkStore(dir))
            val b = chunk(z, pkg, FileChunkStore(dir))
            assertEquals(a.map, b.map)
            val p = a.map.docs.values.single().parts[3].path
            assertEquals(a.virtualEntry(p)!!.decodeToString(), b.virtualEntry(p)!!.decodeToString())
            assertTrue(dir.walk().none { it.name.endsWith(".tmp") })
            FileChunkStore(dir).clear()
            assertEquals(0, dir.listFiles()!!.size)
        } finally { dir.deleteRecursively() }
    }
}
