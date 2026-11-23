package app.fernreader.core.epub.chunk

import app.fernreader.core.epub.EPUB_CORE_VERSION

/**
 * Spine documents larger than [thresholdBytes] (uncompressed) are split into parts of about [targetChars]
 * characters of markup (P-014, P-023, P-061; engine-evaluation §7). [key] names the cache a policy builds.
 */
data class ChunkPolicy(val thresholdBytes: Int = 512 * 1024, val targetChars: Int = 128 * 1024) {
    val key: String get() = "v$EPUB_CORE_VERSION.$VERSION-t$thresholdBytes-p$targetChars"

    companion object {
        /** Bump when the chunker's output for the same input and policy changes (invalidates caches). */
        const val VERSION = 1
    }
}
