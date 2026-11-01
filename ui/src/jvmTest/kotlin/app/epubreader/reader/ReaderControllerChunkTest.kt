package app.epubreader.reader

import app.epubreader.core.data.AnnotationStore
import app.epubreader.core.data.BookmarkStore
import app.epubreader.core.data.HybridClock
import app.epubreader.core.data.LibraryRepository
import app.epubreader.core.data.PositionWriter
import app.epubreader.core.data.SourceRef
import app.epubreader.core.data.buildDatabase
import app.epubreader.core.data.jvmDatabaseBuilder
import app.epubreader.core.epub.PositionMap
import app.epubreader.core.epub.TextPos
import app.epubreader.core.epub.chunk.ChunkMap
import app.epubreader.core.epub.chunk.ChunkPolicy
import app.epubreader.core.epub.chunk.ChunkedBook
import app.epubreader.core.epub.chunk.Chunker
import app.epubreader.core.epub.chunk.MemoryChunkStore
import app.epubreader.core.epub.markup.TextDecoding
import app.epubreader.core.epub.pkg.LoadResult
import app.epubreader.core.epub.pkg.PackageLoader
import app.epubreader.core.epub.text.TextExtractor
import app.epubreader.core.epub.zip.ByteArraySource
import app.epubreader.core.epub.zip.ZipReader
import app.epubreader.core.search.SearchHit
import app.epubreader.core.search.SearchQuery
import app.epubreader.core.search.SearchUpdate
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.reader.bridge.ToWeb
import app.epubreader.reader.bridge.TocItem
import app.epubreader.reader.bridge.WebAnnotation
import app.epubreader.reader.bridge.WebTexts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M8 8.3: the controller's M2..M6 paths with a real ChunkMap (a small policy on a generated book whose one big section
 * is split into parts). The web side sees virtual CFIs and sections only; storage holds exactly what an unchunked
 * reader would have stored ("control" CFIs), so rows written before chunking existed resolve, and a chunked run stores
 * the same bytes as an unchunked one.
 */
class ReaderControllerChunkTest {
    // ---- the book: OPS/a.xhtml (section 0) and OPS/big.xhtml (section 1, 600 body-level paragraphs, split) ----
    private val policy = ChunkPolicy(thresholdBytes = 20_000, targetChars = 10_000)
    private val bigMarkup = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>B</title></head><body>\n" +
        (0 until 600).joinToString("\n") { "<p id=\"p$it\">Paragraph $it ${"lorem ipsum ".repeat(20)}</p>" } + "\n</body></html>"
    private val zip = ZipReader.open(ByteArraySource(ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            fun put(name: String, data: String) { z.putNextEntry(ZipEntry(name)); z.write(data.toByteArray()); z.closeEntry() }
            put("META-INF/container.xml", """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OPS/p.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("OPS/p.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="u"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="u">x</dc:identifier><dc:title>X</dc:title></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="big" href="big.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="a"/><itemref idref="big"/></spine></package>""")
            put("OPS/nav.xhtml", """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>c</title></head><body><nav epub:type="toc"><ol><li><a href="a.xhtml">A</a></li><li><a href="big.xhtml#p500">P500</a></li></ol></nav></body></html>""")
            put("OPS/a.xhtml", """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>a</title></head><body><p>Start</p></body></html>""")
            put("OPS/big.xhtml", bigMarkup)
        }
    }.toByteArray()))
    private val book: ChunkedBook = Chunker(policy).chunk(zip, (PackageLoader.load(zip) as LoadResult.Ok).pkg, MemoryChunkStore())
    private val map: ChunkMap = book.map

    /** What an unchunked reader (foliate on the original book) writes for paragraph [k] of big.xhtml. */
    private fun pRange(k: Int, from: Int, to: Int) = "epubcfi(/6/4!/4/${2 * (k + 1)},/1:$from,/1:$to)"
    private fun pPoint(k: Int, at: Int = 0) = "epubcfi(/6/4!/4/${2 * (k + 1)}/1:$at)"
    /** The virtual section a virtual CFI is in (every spine slot here is a section). */
    private fun vSection(vcfi: String) = Regex("""^epubcfi\(/6/(\d+)""").find(vcfi)!!.groupValues[1].toInt() / 2 - 1
    private val bigText = TextExtractor.extract(bigMarkup).text
    private fun partText(v: Int) = TextExtractor.extract(TextDecoding.decode(book.virtualEntry(map.virtualSpinePaths[v]) ?: zip.readBytes(zip.entry(map.virtualSpinePaths[v])!!))).text

    // ---- the controller with real stores ----
    private class FakeReader : ReaderPort {
        val sent = mutableListOf<ToWeb>()
        override val events = MutableSharedFlow<FromWeb>(extraBufferCapacity = 64)
        override val isReady = MutableStateFlow(true)
        override fun send(m: ToWeb) { sent += m }
    }
    private inner class Opener : BookOpenerPort {
        var map: PositionMap = this@ReaderControllerChunkTest.map
        var statsCalls = 0
        override suspend fun open(ref: SourceRef) = OpenResult.Ok("s-1", "d".repeat(64), 100, map)
        override suspend fun close(sessionId: String) {}
        override suspend fun sectionStats(sessionId: String): List<SectionStat> { statsCalls++; return List(this@ReaderControllerChunkTest.map.virtualSpinePaths.size) { SectionStat(100, 0) } }
    }
    private class Search : SearchPort {
        var hits: List<SearchHit> = emptyList()
        override fun ensureIndex(bookId: String, digest: String, sessionId: String) {}
        override fun search(bookId: String, digest: String, sessionId: String, query: SearchQuery) = flowOf(SearchUpdate(hits, hits.size, false, true))
    }

    private val dispatcher = StandardTestDispatcher()
    private val db = buildDatabase(jvmDatabaseBuilder(Files.createTempDirectory("db").resolve("t.db").toString()), dispatcher)
    private val clock = HybridClock(System::currentTimeMillis)
    private val library = LibraryRepository(db, clock)
    private val bookmarks = BookmarkStore(db, clock)
    private val annotations = AnnotationStore(db, clock)
    private val reader = FakeReader()
    private val opener = Opener()
    private val search = Search()
    private val crashes = mutableListOf<Throwable>()
    private val scope = CoroutineScope(dispatcher + SupervisorJob() + CoroutineExceptionHandler { _, e -> crashes += e })
    @AfterTest fun end() { scope.cancel(); assertEquals(emptyList(), crashes) }
    private val ref = SourceRef.File("/books/huge.epub")
    private val p500 = map.cfiToVirtual(pRange(500, 0, 9))
    private val toc by lazy { listOf(TocItem("A", "OPS/a.xhtml", 0, index = 0), TocItem("P500", "x#p500", 0, index = vSection(p500))) }

    private suspend fun controller() = ReaderController(
        reader, opener, library, CompletableDeferred(PositionWriter.create(db, clock, scope)), scope, WebTexts("missing"),
        search = search, bookmarks = bookmarks, annotations = annotations,
    )
    private suspend fun TestScope.open(c: ReaderController) {
        c.open(ref); advanceUntilIdle()
        reader.events.emit(FromWeb.Opened(sessionId = "s-1", identifier = "urn:x", title = "X", authors = emptyList(), dir = "ltr", fixedLayout = false, sections = map.virtualSpinePaths.size, toc = toc))
        advanceUntilIdle()
    }
    private suspend fun TestScope.at(c: ReaderController, vcfi: String, marks: List<String>? = null) {
        reader.events.emit(FromWeb.Relocate(sessionId = "s-1", cfi = vcfi, fraction = 0.8, sectionIndex = vSection(vcfi), bookmarks = marks)); advanceUntilIdle()
    }
    private suspend fun TestScope.painted() { reader.events.emit(FromWeb.Painted(sessionId = "s-1")); advanceUntilIdle() }
    private suspend fun bookId() = library.knownBookId(ref)!!

    @Test fun the_fixture_splits_the_big_section() {
        assertTrue(map.docs.getValue("OPS/big.xhtml").parts.size > 5)
        assertTrue(vSection(p500) > 2, p500)
        assertEquals(pRange(500, 0, 9), map.cfiToOriginal(p500))
    }

    /** F-037: a position stored unchunked (M2..M7) opens in the right part; positions are stored as the control's. */
    @Test fun resume_and_stored_positions_are_original() = runTest(dispatcher) {
        val c = controller(); open(c); c.close(); advanceUntilIdle()
        val pre = pPoint(420, 5)
        db.positionDao().put(bookId(), pre, 0.7, 1, clock.now())
        reader.sent.clear()
        open(c)
        val sentOpen = reader.sent.filterIsInstance<ToWeb.Open>().single()
        assertEquals(map.cfiToVirtual(pre), sentOpen.position)
        assertTrue(partText(vSection(sentOpen.position!!)).contains("Paragraph 420 "))
        at(c, p500)
        c.close(); advanceUntilIdle()
        val stored = library.position(bookId())!!
        assertEquals(pRange(500, 0, 9), stored.cfi) // byte-identical to what an unchunked reader stores
        assertEquals(1, stored.spineIndex)
    }

    /** F-041: add, marker, remove and list jump in a later part; an unchunked bookmark row shows its marker too. */
    @Test fun bookmarks_are_stored_original_and_shown_virtual() = runTest(dispatcher) {
        val c = controller(); open(c); at(c, p500)
        c.toggleBookmark(); advanceUntilIdle()
        val b = bookmarks.bookmarks(bookId()).first().single()
        assertEquals(pRange(500, 0, 9), b.cfi)
        assertEquals(1, b.spineIndex)
        assertEquals(p500, reader.sent.filterIsInstance<ToWeb.SetBookmarks>().last().cfis.single())
        at(c, p500, marks = listOf(p500))
        assertTrue(c.state.value.bookmarked)
        c.onBookmarkSelected(b)
        assertEquals(ToWeb.GoTo(target = p500), reader.sent.last())
        c.toggleBookmark(); advanceUntilIdle() // the page's bookmark: removed
        assertEquals(emptyList(), bookmarks.bookmarks(bookId()).first())

        // A row written before chunking existed (original CFI of a page start in paragraph 300).
        val pre = pPoint(300)
        bookmarks.add(bookId(), pre, 1, 0.5, "B"); advanceUntilIdle()
        val v = map.cfiToVirtual(pre)
        assertEquals(listOf(v), reader.sent.filterIsInstance<ToWeb.SetBookmarks>().last().cfis)
        assertTrue(partText(vSection(v)).contains("Paragraph 300 "))
        at(c, v, marks = listOf(v))
        assertTrue(c.state.value.bookmarked)
    }

    /** F-060..F-062: create in two parts, display, list order by original position, jump, note; old rows resolve. */
    @Test fun annotations_are_stored_original_drawn_virtual_and_listed_in_book_order() = runTest(dispatcher) {
        val c = controller(); open(c); at(c, p500); painted()
        // The later paragraph first, then an earlier one in another part: the list is in book order.
        for (k in listOf(510, 20)) {
            val sel = map.cfiToVirtual(pRange(k, 10, 13))
            reader.events.emit(FromWeb.Selection(sessionId = "s-1", text = "$k", cfi = sel, sectionIndex = vSection(sel))); advanceUntilIdle()
            c.highlightSelection(); advanceUntilIdle()
            c.dismissHighlightPicker()
        }
        val rows = annotations.annotations(bookId()).first()
        assertEquals(listOf(pRange(20, 10, 13), pRange(510, 10, 13)), rows.map { it.cfi })
        assertEquals(listOf(1, 1), rows.map { it.spineIndex })
        assertEquals(rows.map { it.id }, c.state.value.annotations.map { it.id })
        val drawn = reader.sent.filterIsInstance<ToWeb.AddAnnotation>().associate { it.id to it.cfi }
        for (a in rows) assertEquals(map.cfiToVirtual(a.cfi), drawn[a.id])
        assertNotEquals(vSection(drawn.getValue(rows[0].id)), vSection(drawn.getValue(rows[1].id)))

        c.onAnnotationSelected(rows[1])
        assertEquals(ToWeb.GoTo(target = map.cfiToVirtual(rows[1].cfi)), reader.sent.last())
        reader.events.emit(FromWeb.AnnotationTapped(sessionId = "s-1", id = rows[1].id)); advanceUntilIdle()
        c.editNote("a note"); c.closeAnnotationSheet(); advanceUntilIdle()
        assertEquals("a note", annotations.annotations(bookId()).first().first { it.id == rows[1].id }.note)
        assertEquals(pRange(510, 10, 13), annotations.annotations(bookId()).first().first { it.id == rows[1].id }.cfi)

        // Reopen with a row written before chunking: it is drawn in its part, on the same words.
        val pre = annotations.add(bookId(), pRange(333, 0, 9), 1, "Paragraph", "B", "yellow", "highlight")
        c.close(); advanceUntilIdle(); reader.sent.clear()
        open(c); at(c, p500); painted()
        val items = reader.sent.filterIsInstance<ToWeb.SetAnnotations>().single().items
        val w = items.first { it.id == pre.id }
        assertEquals(WebAnnotation(pre.id, map.cfiToVirtual(pre.cfi), "yellow", "highlight"), w)
        assertTrue(partText(vSection(w.cfi)).contains("Paragraph 333 "))
    }

    /** F-050: hits stay in original coordinates; the jump lands on the hit's text in its part; labels by virtual section. */
    @Test fun a_search_hit_in_a_split_section_lands_in_the_right_part() = runTest(dispatcher) {
        val c = controller(); open(c)
        fun hit(k: Int): SearchHit {
            val off = bigText.indexOf("Paragraph $k ")
            return SearchHit(1, off, "Paragraph $k".length, "Paragraph $k", 0, 9, "Paragraph $k")
        }
        search.hits = listOf(hit(10), hit(520))
        c.searchFor("Paragraph"); advanceUntilIdle()
        val results = c.searcher.state.value.results
        assertEquals(listOf("A", "P500"), results.map { it.chapter }) // p520 is after the P500 entry's part
        c.searcher.select(results[1]); advanceUntilIdle()
        val go = reader.sent.filterIsInstance<ToWeb.GoToText>().single()
        assertTrue(go.sectionIndex > 1)
        assertTrue(partText(go.sectionIndex).substring(go.offset).startsWith("Paragraph 520 "), "lands on the hit's text")
        assertEquals(TextPos(1, results[1].hit.offset), map.textToOriginal(TextPos(go.sectionIndex, go.offset)))
    }

    /** M4 page data: computed on the virtual sections and cached under a kind of its own per chunk policy. */
    @Test fun section_stats_are_cached_per_layout() = runTest(dispatcher) {
        val c = controller(); open(c)
        library.storeCache(bookId(), "section-stats.v1", "d".repeat(64), "[{\"chars\":1,\"images\":0},{\"chars\":2,\"images\":0}]") // an unchunked row (M4..M7)
        painted()
        assertEquals(1, opener.statsCalls) // the unchunked row is not the chunked layout's
        assertEquals(map.virtualSpinePaths.size, c.sectionStats!!.size)
        assertNotNull(library.cached(bookId(), statsKind(map), "d".repeat(64)))
        assertNotEquals("section-stats.v1", statsKind(map))
        assertEquals(statsKind(map), statsKind(Chunker(policy).chunk(zip, (PackageLoader.load(zip) as LoadResult.Ok).pkg, MemoryChunkStore()).map))
        assertNotEquals(statsKind(map), statsKind(Chunker(ChunkPolicy(20_000, 5_000)).chunk(zip, (PackageLoader.load(zip) as LoadResult.Ok).pkg, MemoryChunkStore()).map))
        assertEquals("section-stats.v1", statsKind(PositionMap.Identity))
        assertNull(library.cached(bookId(), "section-stats.v1@nope", "d".repeat(64)))
    }
}
