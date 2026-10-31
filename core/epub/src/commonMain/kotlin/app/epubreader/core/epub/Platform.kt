package app.epubreader.core.epub

import app.epubreader.core.epub.zip.ByteStream
import app.epubreader.core.epub.zip.RandomAccessSource

/** Opens a local file for positional reads (thread-safe). */
expect fun openFileSource(path: String): RandomAccessSource

/** Lower-case hex SHA-256. */
expect fun sha256Hex(bytes: ByteArray): String

/** Raw DEFLATE (no zlib header) decoder over [raw]. */
expect fun inflateStream(raw: ByteStream): ByteStream
