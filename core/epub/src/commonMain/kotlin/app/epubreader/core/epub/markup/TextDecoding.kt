package app.epubreader.core.epub.markup

/** Decodes content/package documents: BOM, then XML declaration or HTML meta charset, default UTF-8. */
object TextDecoding {
    private val XML_ENC = Regex("""^<\?xml[^>]*encoding\s*=\s*["']([A-Za-z0-9._-]+)["']""")
    private val META_CHARSET = Regex("""<meta[^>]+charset\s*=\s*["']?([A-Za-z0-9._-]+)""", RegexOption.IGNORE_CASE)
    private const val CP1252_80_9F = "€\u0081‚ƒ„…†‡ˆ‰Š‹Œ\u008DŽ\u008F\u0090‘’“”•–—˜™š›œ\u009DžŸ"

    fun decode(bytes: ByteArray): String {
        fun b(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xff else -1
        if (b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF) return bytes.decodeToString(3, bytes.size)
        if (b(0) == 0xFE && b(1) == 0xFF) return utf16(bytes, 2, bigEndian = true)
        if (b(0) == 0xFF && b(1) == 0xFE) return utf16(bytes, 2, bigEndian = false)
        if (b(0) == 0 && b(1) == '<'.code) return utf16(bytes, 0, bigEndian = true)
        if (b(0) == '<'.code && b(1) == 0) return utf16(bytes, 0, bigEndian = false)
        val head = latin1(bytes, minOf(bytes.size, 1024))
        val enc = (XML_ENC.find(head) ?: META_CHARSET.find(head))?.groupValues?.get(1)?.lowercase()
        return when (enc) {
            "iso-8859-1", "latin1", "latin-1", "iso8859-1" -> latin1(bytes, bytes.size)
            "windows-1252", "cp1252" -> buildString(bytes.size) {
                for (x in bytes) { val v = x.toInt() and 0xff; append(if (v in 0x80..0x9F) CP1252_80_9F[v - 0x80] else v.toChar()) }
            }
            else -> bytes.decodeToString()
        }
    }

    /**
     * Like [decode], or null when the bytes cannot be decoded exactly: an encoding [decode] does not support (it
     * would fall back to UTF-8) or invalid UTF-8. A document that is rewritten and stored as UTF-8 must come from here.
     */
    fun decodeExact(bytes: ByteArray): String? {
        fun b(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xff else -1
        val bom = (b(0) == 0xEF && b(1) == 0xBB && b(2) == 0xBF) || (b(0) == 0xFE && b(1) == 0xFF) || (b(0) == 0xFF && b(1) == 0xFE) ||
            (b(0) == 0 && b(1) == '<'.code) || (b(0) == '<'.code && b(1) == 0)
        if (bom) return decode(bytes)
        val head = latin1(bytes, minOf(bytes.size, 1024))
        return when ((XML_ENC.find(head) ?: META_CHARSET.find(head))?.groupValues?.get(1)?.lowercase()) {
            "iso-8859-1", "latin1", "latin-1", "iso8859-1", "windows-1252", "cp1252" -> decode(bytes)
            null, "utf-8", "utf8", "us-ascii", "ascii" ->
                try { bytes.decodeToString(throwOnInvalidSequence = true) } catch (e: CharacterCodingException) { null }
            else -> null
        }
    }

    private fun latin1(b: ByteArray, n: Int) = buildString(n) { for (i in 0 until n) append((b[i].toInt() and 0xff).toChar()) }

    private fun utf16(b: ByteArray, start: Int, bigEndian: Boolean) = buildString((b.size - start) / 2) {
        var i = start
        while (i + 1 < b.size) {
            val hi = b[i].toInt() and 0xff; val lo = b[i + 1].toInt() and 0xff
            append((if (bigEndian) (hi shl 8) or lo else (lo shl 8) or hi).toChar()); i += 2
        }
    }
}
