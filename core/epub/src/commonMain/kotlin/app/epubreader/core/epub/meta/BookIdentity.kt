package app.epubreader.core.epub.meta

import app.epubreader.core.epub.sha256Hex

/**
 * F-012/F-015: identity by content — package identifier plus a digest of the zip central directory.
 * The identifier must be the unique-identifier element's text with whitespace collapsed and trimmed (the rule
 * foliate-js uses; M3's Kotlin parser uses the same rule — master plan Review Focus 6).
 */
object BookIdentity {
    fun of(uniqueId: String?, centralDirectoryDigest: String): String =
        sha256Hex(normalize(uniqueId).encodeToByteArray()).take(16) + "-" + centralDirectoryDigest.take(32)

    private val whitespace = Regex("\\s+")
    private fun normalize(id: String?): String = (id ?: "").replace(whitespace, " ").trim()
}
