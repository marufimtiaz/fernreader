package app.epubreader.reader

import app.epubreader.core.data.SourceRef
import app.epubreader.core.epub.PositionMap
import app.epubreader.core.epub.TextPos
import app.epubreader.core.epub.chunk.ChunkMap
import app.epubreader.core.epub.chunk.ChunkPolicy
import app.epubreader.core.epub.chunk.ChunkStore
import app.epubreader.core.epub.chunk.MemoryChunkStore
import app.epubreader.core.epub.zip.ByteArraySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** M8 8.3: BookOpener splits huge documents into the chunk cache and hands the book's ChunkMap to the reader. */
class BookOpenerChunkTest {
    private val ref = SourceRef.File("/books/huge.epub")
    private val warnings = mutableListOf<String>()
    private val stores = mutableListOf<Pair<SourceRef, ChunkPolicy>>()
    private val store = MemoryChunkStore()

    private class BytesSources(var bytes: ByteArray, var stamp: String = "1") : Sources {
        override fun open(ref: SourceRef) = OpenedSource(ByteArraySource(bytes), bytes.size.toLong())
        override fun stamp(ref: SourceRef) = stamp
    }

    private fun opener(sources: Sources, server: BookContentServer = BookContentServer(), chunkStore: ChunkStore = store, policy: ChunkPolicy = ChunkTestBook.small) =
        BookOpener(sources, server, Dispatchers.Unconfined, warn = { warnings += it }, chunkStore = { r, p -> stores += r to p; chunkStore }).also { it.policy = policy }

    private fun ok(r: OpenResult) = r as? OpenResult.Ok ?: error("not opened: $r")

    @Test fun a_huge_document_is_split_and_its_map_goes_to_the_reader() = runBlocking {
        val o = opener(BytesSources(ChunkTestBook.bytes()))
        val r = ok(o.open(ref))
        val map = r.map as ChunkMap
        val parts = map.docs.getValue("OPS/big.xhtml").parts
        assertTrue("${parts.size} parts", parts.size > 3)
        assertEquals(ChunkTestBook.small.key, map.policyKey)
        assertEquals(listOf(ref to ChunkTestBook.small), stores)
        assertTrue(store.size > parts.size) // parts, the rewritten package/nav/a.xhtml and cache.json
        // Positions cross through the map: original section 1 (big.xhtml) is virtual section 1 + part.
        assertEquals(TextPos(1, 0), map.textToVirtual(TextPos(1, 0)))
        assertTrue(map.textToVirtual(TextPos(1, 20_000)).spineIndex > 1)
    }

    @Test fun a_book_without_a_large_entry_is_not_parsed_and_gets_the_identity() = runBlocking {
        val o = opener(BytesSources(ChunkTestBook.bytes(paragraphs = 20)))
        assertSame(PositionMap.Identity, ok(o.open(ref)).map)
        assertEquals(emptyList<Any>(), stores) // no package parse, no cache directory (P-010/P-011)
        assertEquals(0, store.writeCount)
    }

    /** Review I1: a large entry the chunker would never split costs no store, no directory and no cache. */
    @Test fun large_entries_that_are_not_split_candidates_create_no_store() = runBlocking {
        val big = "x".repeat(30_000)
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><desc>$big</desc></svg>"
        val doc = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>$big</p></body></html>"
        val books = listOf(
            // A large SVG and CSS: not even a package parse.
            ChunkTestBook.bytes(paragraphs = 20, extra = mapOf("OPS/plate.svg" to svg, "OPS/style.css" to "p{}/*$big*/"),
                extraManifest = """<item id="s" href="plate.svg" media-type="image/svg+xml"/><item id="c" href="style.css" media-type="text/css"/>"""),
            // A large XHTML document in the manifest but not in the spine, and one not even in the manifest.
            ChunkTestBook.bytes(paragraphs = 20, extra = mapOf("OPS/appendix.xhtml" to doc, "OPS/stray.xhtml" to doc),
                extraManifest = """<item id="ap" href="appendix.xhtml" media-type="application/xhtml+xml"/>"""),
        )
        for (b in books) {
            val o = opener(BytesSources(b))
            val r = ok(o.open(ref))
            assertSame(PositionMap.Identity, r.map)
            assertEquals(2, o.sectionStats(r.sessionId)!!.size)
        }
        assertEquals(emptyList<Any>(), stores)
        assertEquals(0, store.writeCount)
    }

    /** Review M1/m3: a deleted book's sessions never rebuild its cache; a warm one is closed at once. */
    @Test fun forgotten_sessions_do_not_rebuild_and_the_warm_one_is_evicted() = runBlocking {
        val server = BookContentServer()
        val o = opener(BytesSources(ChunkTestBook.bytes()), server)
        val a = ok(o.open(ref))
        o.forget(listOf(ref.key)) // deleted while open
        store.clear()
        val writes = store.writeCount
        assertEquals(500, server.serve("${a.sessionId}/OPS/p.opf").status)
        assertEquals("no rebuild", writes, store.writeCount)
        o.close(a.sessionId) // now warm, but forgotten: not reused
        val b = ok(o.open(ref)) // a new session, its cache built again (the book was opened again)
        assertNotEquals(a.sessionId, b.sessionId)
        assertEquals(200, server.serve("${b.sessionId}/OPS/p.opf").status)
        o.close(b.sessionId)
        o.forget(listOf(ref.key))
        assertEquals(410, server.serve("${b.sessionId}/OPS/p.opf").status) // evicted, not kept registered
        assertNotEquals(b.sessionId, ok(o.open(ref)).sessionId)
    }

    @Test fun nothing_to_split_under_the_policy_is_the_identity() = runBlocking {
        val o = opener(BytesSources(ChunkTestBook.bytes()), policy = ChunkTestBook.never)
        assertSame(PositionMap.Identity, ok(o.open(ref)).map)
        assertEquals(emptyList<Any>(), stores)
    }

    @Test fun later_opens_load_the_cache() = runBlocking {
        assertTrue(ChunkTestBook.bytes().contentEquals(ChunkTestBook.bytes())) // deterministic fixture
        val first = ok(opener(BytesSources(ChunkTestBook.bytes())).open(ref)).map
        val writes = store.writeCount
        val again = ok(opener(BytesSources(ChunkTestBook.bytes())).open(ref)).map
        assertEquals(first, again)
        assertEquals("no rewrite on a cached open", writes, store.writeCount)
    }

    @Test fun warm_reopen_keeps_the_chunked_book_unless_the_policy_changed() = runBlocking {
        val o = opener(BytesSources(ChunkTestBook.bytes()))
        val a = ok(o.open(ref))
        o.close(a.sessionId)
        val b = ok(o.open(ref))
        assertEquals(a.sessionId, b.sessionId) // P-016: the same session, the same map
        assertEquals(a.map, b.map)
        o.close(b.sessionId)
        o.policy = ChunkTestBook.never // the instrumented control run
        val c = ok(o.open(ref))
        assertNotEquals(a.sessionId, c.sessionId)
        assertSame(PositionMap.Identity, c.map)
    }

    @Test fun a_chunking_failure_opens_the_book_unsplit() = runBlocking {
        val broken = object : ChunkStore {
            override fun read(name: String): ByteArray? = null
            override fun size(name: String): Long? = null
            override fun write(name: String, bytes: ByteArray) = throw IOException("disk full")
            override fun clear() {}
        }
        val server = BookContentServer()
        val o = opener(BytesSources(ChunkTestBook.bytes()), server, chunkStore = broken)
        val r = ok(o.open(ref))
        assertSame(PositionMap.Identity, r.map)
        assertTrue(warnings.toString(), warnings.single().contains("chunking failed"))
        // Served as the zip has it: the original package.
        val opf = server.serve("${r.sessionId}/OPS/p.opf").body().readBytes().decodeToString()
        assertTrue(opf.contains("<itemref idref=\"big\"/>"))
    }

    @Test fun section_stats_describe_the_virtual_sections() = runBlocking {
        val o = opener(BytesSources(ChunkTestBook.bytes()))
        val r = ok(o.open(ref))
        val map = r.map as ChunkMap
        val stats = o.sectionStats(r.sessionId)!!
        assertEquals(map.virtualSpinePaths.size, stats.size)
        assertTrue(stats.drop(1).all { it.chars > 1000 }) // every part has its own share of the text
        val whole = opener(BytesSources(ChunkTestBook.bytes()), policy = ChunkTestBook.never).let { u -> u.sectionStats(ok(u.open(ref)).sessionId)!! }
        assertEquals(2, whole.size)
        // Same text, cut into parts (a collapsed space at each cut at most).
        assertTrue("${stats.sumOf { it.chars }} vs ${whole.sumOf { it.chars }}", kotlin.math.abs(stats.sumOf { it.chars } - whole.sumOf { it.chars }) <= stats.size)
    }
}
