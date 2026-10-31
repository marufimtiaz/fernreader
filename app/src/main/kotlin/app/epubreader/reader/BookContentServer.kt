package app.epubreader.reader

import app.epubreader.core.epub.zip.ByteStream
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
            val json = Json.encodeToString(s.zip.entries.filterNot { it.isDirectory }.map { EntryInfo(it.name, it.size) })
            return Served(200, "OK", "application/json") { ByteArrayInputStream(json.encodeToByteArray()) }
        }
        val e = s.zip.entry(name) ?: return empty(404, "Not Found")
        return Served(200, "OK", MimeTypes.of(name)) { s.zip.openStream(e).asInputStream() }
    }

    private fun empty(status: Int, reason: String) = Served(status, reason, "text/plain") { ByteArrayInputStream(ByteArray(0)) }
}

private fun ByteStream.asInputStream(): InputStream = object : InputStream() {
    private val one = ByteArray(1)
    override fun read(): Int = if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
    override fun read(b: ByteArray, off: Int, len: Int): Int = this@asInputStream.read(b, off, len)
    override fun close() = this@asInputStream.close()
}
