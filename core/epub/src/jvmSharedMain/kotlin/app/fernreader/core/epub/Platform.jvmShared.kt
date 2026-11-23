package app.fernreader.core.epub

import app.fernreader.core.epub.zip.ByteStream
import app.fernreader.core.epub.zip.RandomAccessSource
import java.io.RandomAccessFile
import java.security.MessageDigest

actual fun openFileSource(path: String): RandomAccessSource = FileChannelSource(RandomAccessFile(path, "r").channel)

actual fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

actual fun inflateStream(raw: ByteStream): ByteStream = InflaterByteStream(raw)
