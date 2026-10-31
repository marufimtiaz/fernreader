package app.epubreader.core.epub

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds zips in memory. Entries listed in [stored] use STORED, others DEFLATED. */
fun zipOf(vararg entries: Pair<String, ByteArray>, stored: Set<String> = emptySet(), charset: Charset = Charsets.UTF_8): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out, charset).use { z ->
        for ((name, data) in entries) {
            val e = ZipEntry(name)
            if (name in stored) {
                e.method = ZipEntry.STORED; e.size = data.size.toLong(); e.compressedSize = data.size.toLong()
                e.crc = CRC32().apply { update(data) }.value
            }
            z.putNextEntry(e); z.write(data); z.closeEntry()
        }
    }
    return out.toByteArray()
}

fun String.b() = encodeToByteArray()
