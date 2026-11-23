package app.fernreader.reader

import app.fernreader.core.epub.zip.ByteStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

class Served(val status: Int, val reason: String, val mime: String, val body: () -> InputStream)

@Serializable private data class EntryInfo(val name: String, val size: Long)

/**
 * Serves book entries to the reader WebView (via ReaderEngine's path handler for "/book/"). Pure Kotlin so it
 * can be unit-tested; called on WebView I/O threads, so it only does positional, thread-safe zip reads.
 *
 * M8: a chunked session's virtual entries (parts, the rewritten package, nav, NCX and linking documents) replace or
 * add to the zip's; `entries.json` lists them with their own sizes. A virtual entry that is gone (cache trimmed
 * while open) is rebuilt by the session; if it still cannot be read the answer is 500, never the zip's entry, whose
 * package and documents do not match the reader's map.
 */
class BookContentServer {
    private val sessions = ConcurrentHashMap<String, BookSession>()
    private val closed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun register(s: BookSession) { sessions[s.id] = s }

    fun unregister(id: String): BookSession? = sessions.remove(id)?.also { closed += id }

    /** [path] = "<sessionId>/<entry path>", already URL-decoded by WebViewAssetLoader. */
    fun serve(path: String): Served {
        val slash = path.indexOf('/')
        if (slash <= 0) return empty(404, "Not Found")
        val sid = path.substring(0, slash)
        val name = path.substring(slash + 1)
        if (name.replace('\\', '/').split('/').any { it == "." || it == ".." }) return empty(403, "Forbidden")
        val s = sessions[sid] ?: return if (sid in closed) empty(410, "Gone") else empty(404, "Not Found")
        if (name == "entries.json") {
            val json = try { Json.encodeToString(entries(s)) } catch (e: Exception) { return empty(500, "Internal Server Error") }
            return Served(200, "OK", "application/json") { ByteArrayInputStream(json.encodeToByteArray()) }
        }
        val virtual = try { s.virtualEntry(name) } catch (e: Exception) { return empty(500, "Internal Server Error") }
        if (virtual != null) return Served(200, "OK", MimeTypes.of(name)) { ByteArrayInputStream(virtual) }
        val e = s.zip.entry(name) ?: return empty(404, "Not Found")
        return Served(200, "OK", MimeTypes.of(name)) { s.zip.openStream(e).asInputStream() }
    }

    /** The zip's files (a rewritten one once, with its new size), then the parts, which the zip does not have. */
    private fun entries(s: BookSession): List<EntryInfo> {
        val c = s.chunked
        val files = s.zip.entries.filterNot { it.isDirectory }
        if (c == null) return files.map { EntryInfo(it.name, it.size) }
        val names = files.mapTo(HashSet()) { it.name }
        return files.map { EntryInfo(it.name, s.virtualSize(it.name) ?: it.size) } +
            c.virtualPaths.filter { it !in names }.sorted().map { EntryInfo(it, s.virtualSize(it)!!) }
    }

    private fun empty(status: Int, reason: String) = Served(status, reason, "text/plain") { ByteArrayInputStream(ByteArray(0)) }
}

private fun ByteStream.asInputStream(): InputStream = object : InputStream() {
    private val one = ByteArray(1)
    override fun read(): Int = if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
    override fun read(b: ByteArray, off: Int, len: Int): Int = this@asInputStream.read(b, off, len)
    override fun close() = this@asInputStream.close()
}
