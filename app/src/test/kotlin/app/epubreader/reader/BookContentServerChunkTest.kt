package app.epubreader.reader

import app.epubreader.core.data.SourceRef
import app.epubreader.core.epub.chunk.ChunkCacheException
import app.epubreader.core.epub.chunk.ChunkMap
import app.epubreader.core.epub.chunk.ChunkPolicy
import app.epubreader.core.epub.chunk.ChunkStore
import app.epubreader.core.epub.chunk.MemoryChunkStore
import app.epubreader.core.epub.zip.ByteArraySource
import app.epubreader.core.epub.zip.ZipReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** M8 8.3: the content server serves a chunked session's virtual book and rebuilds a cache trimmed while open. */
class BookContentServerChunkTest {
    private val bytes = ChunkTestBook.bytes()
    private val zip = ZipReader.open(ByteArraySource(bytes))
    private val mem = MemoryChunkStore()
    /** Thread-safe like the app's FileChunkStore (one file per entry, renamed into place). */
    private val store = object : ChunkStore {
        override fun read(name: String) = synchronized(mem) { mem.read(name) }
        override fun size(name: String) = synchronized(mem) { mem.size(name) }
        override fun write(name: String, bytes: ByteArray) = synchronized(mem) { mem.write(name, bytes) }
        override fun clear() = synchronized(mem) { mem.clear() }
    }
    private val server = BookContentServer()
    private var storeCalls = 0

    private fun open(stores: (SourceRef, ChunkPolicy) -> ChunkStore = { _, _ -> storeCalls++; store }): Pair<String, ChunkMap> = runBlocking {
        val sources = object : Sources {
            override fun open(ref: SourceRef) = OpenedSource(ByteArraySource(bytes), bytes.size.toLong())
            override fun stamp(ref: SourceRef) = "1"
        }
        val o = BookOpener(sources, server, Dispatchers.Unconfined, warn = {}, chunkStore = stores).also { it.policy = ChunkTestBook.small }
        val r = o.open(SourceRef.File("/b.epub")) as OpenResult.Ok
        r.sessionId to r.map as ChunkMap
    }

    private fun get(path: String) = server.serve(path).let { it.status to it.body().readBytes().decodeToString() }
    private fun original(path: String) = zip.readBytes(zip.entry(path)!!).decodeToString()

    @Test fun parts_and_rewritten_documents_replace_the_zip_entries() {
        val (sid, map) = open()
        val parts = map.docs.getValue("OPS/big.xhtml").parts
        val (st, opf) = get("$sid/OPS/p.opf")
        assertEquals(200, st)
        assertTrue(opf, opf.contains("idref=\"big-part0\"") && !opf.contains("<itemref idref=\"big\"/>"))
        assertNotEquals(original("OPS/nav.xhtml"), get("$sid/OPS/nav.xhtml").second) // the TOC link points into its part
        val p2 = server.serve("$sid/${parts[2].path}")
        assertEquals(200, p2.status); assertEquals("application/xhtml+xml", p2.mime)
        assertTrue(p2.body().readBytes().decodeToString().contains("Paragraph"))
        assertEquals(original("META-INF/container.xml"), get("$sid/META-INF/container.xml").second) // not virtual: the zip's
        assertEquals(original("OPS/big.xhtml"), get("$sid/OPS/big.xhtml").second) // the original stays reachable
    }

    @Test fun entries_json_lists_the_parts_and_each_rewritten_entry_once_with_its_size() {
        val (sid, map) = open()
        val list = Json.parseToJsonElement(get("$sid/entries.json").second).jsonArray.map { it.jsonObject }
        val sizes = list.associate { it["name"]!!.jsonPrimitive.content to it["size"]!!.jsonPrimitive.long }
        assertEquals("no name twice", list.size, sizes.size)
        for (p in map.partPaths) assertEquals(p, get("$sid/$p").second.encodeToByteArray().size.toLong(), sizes[p])
        assertEquals(get("$sid/OPS/p.opf").second.encodeToByteArray().size.toLong(), sizes["OPS/p.opf"])
        assertNotEquals(zip.entry("OPS/p.opf")!!.size, sizes["OPS/p.opf"])
        assertEquals(zip.entry("mimetype")!!.size, sizes["mimetype"])
    }

    @Test fun a_cache_trimmed_while_open_is_rebuilt_with_the_same_map() {
        val (sid, map) = open()
        val part = map.docs.getValue("OPS/big.xhtml").parts[3].path
        val before = get("$sid/$part").second
        val calls = storeCalls
        store.clear() // the system trimmed the cache directory
        // Several WebView threads at once: one rebuild, every request answered from it.
        val pool = Executors.newFixedThreadPool(4)
        val answers = (0 until 8).map { pool.submit<Pair<Int, String>> { get("$sid/$part") } }.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        assertTrue(answers.all { it == 200 to before })
        assertEquals("one rebuild", calls + 1, storeCalls)
        assertEquals(200, get("$sid/OPS/p.opf").first)
        assertEquals(200, get("$sid/entries.json").first)
    }

    @Test fun a_rebuild_that_fails_is_an_error_never_the_zip_entry() {
        var fail = false
        val (sid, _) = open { _, _ -> if (fail) throw ChunkCacheException("no space") else store }
        fail = true
        store.clear()
        assertEquals(500, server.serve("$sid/OPS/p.opf").status)
        assertEquals(500, server.serve("$sid/entries.json").status)
        assertEquals(200, server.serve("$sid/mimetype").status) // a plain zip entry is still served
    }
}
