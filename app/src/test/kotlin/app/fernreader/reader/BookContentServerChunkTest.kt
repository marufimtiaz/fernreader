package app.fernreader.reader

import app.fernreader.core.data.SourceRef
import app.fernreader.core.epub.chunk.ChunkCacheException
import app.fernreader.core.epub.chunk.ChunkMap
import app.fernreader.core.epub.chunk.ChunkPolicy
import app.fernreader.core.epub.chunk.ChunkStore
import app.fernreader.core.epub.chunk.MemoryChunkStore
import app.fernreader.core.epub.zip.ByteArraySource
import app.fernreader.core.epub.zip.ZipReader
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
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
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

    /** Review I1: a failed rebuild (ENOSPC, OOM) is remembered: one attempt, every request a 500, nothing escapes. */
    @Test fun a_failed_rebuild_is_tried_once_and_never_escapes() {
        for (failure in listOf<Throwable>(IOException("ENOSPC"), OutOfMemoryError("test"))) {
            var calls = 0
            val (sid, map) = open { _, _ -> if (++calls > 1) throw failure; store }
            store.clear()
            val paths = map.docs.getValue("OPS/big.xhtml").parts.take(4).map { it.path } + "OPS/p.opf"
            val statuses = paths.map { p -> try { server.serve("$sid/$p").status } catch (t: Throwable) { fail("escaped serve(): $t"); 0 } }
            assertEquals("$failure", List(5) { 500 }, statuses)
            assertEquals("$failure: the open, then one rebuild", 2, calls)
            assertEquals(200, server.serve("$sid/mimetype").status)
        }
    }

    /** Review M1: a book deleted while its cache is being rebuilt does not get its directory back. */
    @Test fun a_rebuild_in_flight_does_not_recreate_a_deleted_cache() {
        val dirs = ChunkDirs(Files.createTempDirectory("chunks").toFile())
        val ref = SourceRef.File("/b.epub")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var calls = 0
        val sources = object : Sources {
            override fun open(ref: SourceRef) = OpenedSource(ByteArraySource(bytes), bytes.size.toLong())
            override fun stamp(ref: SourceRef) = "1"
        }
        val o = BookOpener(sources, server, Dispatchers.Unconfined, warn = {}, chunkStore = { r, p ->
            if (++calls > 1) { entered.countDown(); release.await() } // the rebuild blocks here
            dirs.store(r, p)
        }).also { it.policy = ChunkTestBook.small }
        val sid = (runBlocking { o.open(ref) } as OpenResult.Ok).sessionId
        assertTrue(dirs.dirOf(ref.key).isDirectory)
        dirs.delete(listOf(ref.key)) // the system trimmed it
        val pool = Executors.newFixedThreadPool(2)
        try {
            val request = pool.submit<Int> { server.serve("$sid/OPS/p.opf").status }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            // The M7 delete path (AppGraph.forgetChunks): forget, then delete the directory.
            val deletion = pool.submit { runBlocking { o.forget(listOf(ref.key)) }; dirs.delete(listOf(ref.key)) }
            Thread.sleep(300)
            assertFalse("forget waits for the rebuild in flight", deletion.isDone)
            release.countDown()
            assertEquals(500, request.get(10, TimeUnit.SECONDS)) // its result is dropped: the book is gone
            deletion.get(10, TimeUnit.SECONDS)
            assertFalse(dirs.dirOf(ref.key).exists())
        } finally { release.countDown(); pool.shutdown() }
    }
}
