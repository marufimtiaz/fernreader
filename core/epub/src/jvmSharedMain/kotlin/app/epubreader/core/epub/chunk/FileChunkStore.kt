package app.epubreader.core.epub.chunk

import app.epubreader.core.epub.sha256Hex
import java.io.File
import java.io.IOException

/**
 * [ChunkStore] in a directory: one file per entry, names hashed into safe file names (entry names are zip paths and
 * may hold any character). Writes go to a temporary file first and are renamed into place.
 */
class FileChunkStore(private val dir: File) : ChunkStore {
    private fun file(name: String) = File(dir, fileName(name))

    override fun read(name: String): ByteArray? = file(name).takeIf { it.isFile }?.let {
        try { it.readBytes() } catch (e: IOException) { null }
    }

    override fun write(name: String, bytes: ByteArray) {
        dir.mkdirs()
        val target = file(name)
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) { tmp.delete(); throw IOException("cannot write $target") }
        }
    }

    override fun clear() { dir.listFiles()?.forEach { it.deleteRecursively() } }

    private fun fileName(name: String): String {
        val safe = name.substringAfterLast('/').filter { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }.takeLast(40)
        return sha256Hex(name.encodeToByteArray()).take(32) + "-" + safe
    }
}
