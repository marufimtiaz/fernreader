# M2 — First Readable Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The first usable reader on the emulator (master plan revision 2). The user installs the app, taps
"Open book" (system document picker, no storage permission) or a book in the recent-books list, and reads it in
place: paged mode, swipe page turns, a TOC panel, and the same position after closing, reopening or force-stopping
the app.

**Architecture:** foliate-js already parses the package (container, OPF, nav/NCX) and resolves CFIs in JS, so
the Kotlin side of this slice only needs to serve bytes and store data:
- `core:epub`: the zip reader (moved unchanged from the old M2 Task 2.1), `BookIdentity`, `PositionMap` (identity).
- `core:data`: `book`, `book_file`, `reading_position` (schema v2, AutoMigration from M1's v1), `LibraryRepository`,
  `PositionWriter`.
- `app`: `BookSources` (file path or `content://` descriptor), `BookContentServer` (a WebView path handler serving
  zip entries per session), `BookOpener`, `ReaderEngine` (M1) kept full size **behind** the Compose UI, `MainActivity`
  (route stack, Back, picker).
- `ui`: `ReaderController` (state holder: open → `opened` → identity → resume → position writes → close), reader
  chrome, TOC panel, home screen.
- `reader-web`: bridge v1 subset, a loader over `/book/<sessionId>/…`, TOC flattening with missing-target flags,
  center-tap events, and a warm last book for P-016.

Not in this slice (deferred, see master plan revision 2): markup scanner, package loader, metadata/cover, scanning
and storage permission (M3); preloading, settings, page numbers (M4); text extraction (M4); chunking (M8). The
huge-chapter book opens but slowly (≈ 10 s, ~1 GB) until M8. Chapter-boundary turns show foliate's normal section
load until M4.

**Tech Stack:** as M1 (Kotlin 2.4.20, AGP 9.1.0, Room KMP + bundled SQLite, Compose Multiplatform + Material 3,
androidx.webkit, kotlinx.serialization, TypeScript + Vite + Vitest, foliate-js vendored).

**Spec:** master plan; features.md F-013, F-030, F-033, F-037, F-040, F-084, F-115; performance.md P-010..P-016,
P-030, P-031, P-060, R-001, R-002, R-005, R-007, R-009; clarifications C-014, C-015.

## Global Constraints

See master plan. In addition for this milestone:
- No storage permission in the main manifest (that is M3). The `benchmark` build type declares
  `MANAGE_EXTERNAL_STORAGE` so the bench harness can open corpus books by path after `appops set … allow`.
- The reader WebView is created after the first frame (P-001) and stays attached, full size, under the Compose UI.
  Opaque Compose screens (home, loading, error) cover it; the reader route draws only chrome on top.
- Every position crossing between JS and the database goes through `PositionMap` (identity here; M8 swaps in
  `ChunkMap`). The database stores original CFIs only.
- Schema changes bump the Room version with an `AutoMigration`; the user's reading data from this milestone on
  must survive every later upgrade.
- UI text is our own wording, kept in `ui/src/commonMain/kotlin/app/epubreader/ui/Copy.kt`.

## Review Focus

1. **Identity from foliate's identifier.** `BookIdentity.of(opened.identifier, cdDigest)`. foliate takes the
   element referenced by `unique-identifier` (else the first `dc:identifier`) and collapses whitespace. M3's Kotlin
   parser must produce the same string. Pinned now: `IdentityTest.null_and_empty_identifier_are_equal`; and in M3
   Task 3.3 by `IdentityAgreementTest`.
2. **Stale events after a fast close/open.** `relocate` from the previous book arriving after a new `open` would
   write the old CFI to the new book. Pinned: every book-scoped message carries `sessionId`;
   `ReaderControllerTest.events_from_previous_session_are_ignored`.
3. **Pipe descriptors from cloud providers** (non-seekable). Pinned: `BookSourcesTest.pipe_is_copied_then_opened`.
4. **Touches between Compose and the WebView behind it.** Pinned: `ReaderTouchTest` (swipe with hidden chrome turns
   the page; tapping the Contents button opens the TOC and does not turn or emit `tap`).
5. **Back while JS is busy** (R-005). `ReaderController.close()` pops the route synchronously and sends `close`
   without awaiting anything. Pinned: `ReaderControllerTest.close_returns_without_waiting_for_engine`.
6. **Position lost on kill** (R-009). Pinned: `PositionWriterTest.burst_ends_on_last_and_never_older` and
   `bench/check_resume.sh` (force-stop after 27 turns → same CFI).

## File structure

```
core/epub/src/commonMain/kotlin/app/epubreader/core/epub/
  Platform.kt  PositionMap.kt  zip/{RandomAccessSource,ByteStream,ZipReader,Cp437}.kt  meta/BookIdentity.kt
core/epub/src/jvmShared/kotlin/app/epubreader/core/epub/  Platform.jvmShared.kt FileChannelSource.kt InflaterByteStream.kt
core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/    TestZips.kt Corpus.kt zip/ZipReaderTest.kt meta/IdentityTest.kt
core/data/src/commonMain/kotlin/app/epubreader/core/data/
  AppDatabase.kt (v2)  BookEntity.kt  BookFileEntity.kt  ReadingPositionEntity.kt  LibraryDao.kt  PositionDao.kt
  HybridClock.kt  Ids.kt  SourceRef.kt  LibraryRepository.kt  PositionWriter.kt
core/data/src/jvmTest/kotlin/app/epubreader/core/data/    TestDb.kt HybridClockTest.kt LibraryRepositoryTest.kt PositionWriterTest.kt MigrationTest.kt
ui/src/commonMain/kotlin/app/epubreader/
  reader/bridge/Protocol.kt (v1 subset)  reader/{Ports.kt, ReaderController.kt, ReaderScreen.kt}
  reader/toc/{TocTreeState.kt, TocPanel.kt}  home/{HomeState.kt, HomeScreen.kt}  ui/{App.kt, Navigator.kt, Copy.kt}
ui/src/jvmTest/...   ProtocolFixtureTest.kt ReaderControllerTest.kt HomeStateTest.kt   ui/src/commonTest/... TocTreeStateTest.kt
reader-web/src/      bridge/{protocol.ts,bridge.ts}  reader/{loader.ts,toc.ts,reader.ts}  main.ts
reader-web/test/     protocol.test.ts loader.test.ts toc.test.ts
bridge/fixtures/     open.json opened.json relocate.json painted.json tap.json next.json prev.json goTo.json close.json error.json
app/src/main/kotlin/app/epubreader/
  AppGraph.kt  EpubReaderApp.kt  MainActivity.kt  reader/{BookSources.kt, BookSession.kt, BookContentServer.kt,
  MimeTypes.kt, BookOpener.kt, ReaderEngine.kt, ReaderSurface.kt}  bench/BenchCommands.kt
app/src/benchmark/   AndroidManifest.xml  kotlin/app/epubreader/bench/BenchCommandReceiver.kt
app/src/test/kotlin/app/epubreader/reader/BookContentServerTest.kt
app/src/androidTest/ AndroidManifest.xml  kotlin/app/epubreader/{TestBooks.kt, TestBookProvider.kt, SmokeTest.kt,
  reader/BookSourcesTest.kt, reader/ReaderFlowTest.kt, reader/ReaderTouchTest.kt}
bench/groups/{open,action,mem}.sh  bench/check_resume.sh
```

---

### Task 2.1: Random-access sources and the zip reader

Moved unchanged from the old M2 file (revision 1). It is needed in this slice because it serves bytes from a
picker's `content://` descriptor without copying (Review Focus 3) and provides the central-directory digest for
book identity. `Corpus.kt` reads the host-test corpus generated by M1 Task 1.4.

**Files:**
- Create: `core/epub/src/commonMain/kotlin/app/epubreader/core/epub/{Platform.kt, zip/RandomAccessSource.kt, zip/ByteStream.kt, zip/ZipReader.kt, zip/Cp437.kt}`
- Create: `core/epub/src/jvmShared/kotlin/app/epubreader/core/epub/{Platform.jvmShared.kt, FileChannelSource.kt, InflaterByteStream.kt}`
- Test: `core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/{TestZips.kt, Corpus.kt, zip/ZipReaderTest.kt}`

**Interfaces:**
- Produces: `interface RandomAccessSource : AutoCloseable { val size: Long; fun read(position: Long, dst: ByteArray, off: Int, len: Int): Int }`,
  `fun RandomAccessSource.readFully(position: Long, len: Int): ByteArray`, `class ByteArraySource(bytes)`,
  `interface ByteStream : AutoCloseable { fun read(dst: ByteArray, off: Int, len: Int): Int }`, `fun ByteStream.readAllBytes(limit: Long): ByteArray`,
  `enum class ZipError { NOT_A_ZIP, DAMAGED }`, `class ZipFormatException(error, message)`,
  `class ZipEntry(name, method, compressedSize, size, crc32, localHeaderOffset, encrypted)`,
  `class ZipReader : AutoCloseable { entries; centralDirectoryDigest: String; entry(path): ZipEntry?; openStream(e); readBytes(e, limit) ; companion open(src) }`,
  `expect fun openFileSource(path: String): RandomAccessSource`, `expect fun sha256Hex(bytes: ByteArray): String`,
  `expect fun inflateStream(raw: ByteStream): ByteStream`.

- [ ] **Step 1: Test helpers**

```kotlin
// core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/TestZips.kt
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
```

```kotlin
// core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/Corpus.kt
package app.epubreader.core.epub

import java.io.File

/** Generated corpus (always present: Gradle generateTestCorpus) and the optional downloaded corpus. */
object Corpus {
    val dir = File(System.getProperty("corpus.dir") ?: error("corpus.dir not set; run through Gradle"))
    fun generated(name: String) = File(dir, "generated/$name").also { require(it.exists()) { "missing $it" } }
    fun folder(name: String) = File(dir, "folders/$name").also { require(it.exists()) { "missing $it" } }
    val full: File? = System.getProperty("fullcorpus.dir")?.let(::File)?.takeIf { File(it, "gutenberg").isDirectory }
}
```

- [ ] **Step 2: Write the failing tests**

```kotlin
// core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/zip/ZipReaderTest.kt
package app.epubreader.core.epub.zip

import app.epubreader.core.epub.Corpus
import app.epubreader.core.epub.b
import app.epubreader.core.epub.openFileSource
import app.epubreader.core.epub.zipOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class ZipReaderTest {
    private fun open(bytes: ByteArray) = ZipReader.open(ByteArraySource(bytes))

    @Test fun reads_stored_and_deflated_entries() {
        val big = ByteArray(200_000) { (it % 251).toByte() }
        val z = open(zipOf("mimetype" to "application/epub+zip".b(), "OEBPS/big.bin" to big, stored = setOf("mimetype")))
        assertEquals("application/epub+zip", z.readBytes(z.entry("mimetype")!!).decodeToString())
        assertContentEquals(big, z.readBytes(z.entry("OEBPS/big.bin")!!))
    }

    @Test fun lookup_is_exact_then_case_insensitive() {
        val z = open(zipOf("OEBPS/Text/Ch1.xhtml" to "x".b()))
        assertNotNull(z.entry("OEBPS/Text/Ch1.xhtml"))
        assertNotNull(z.entry("oebps/text/ch1.xhtml"))
        assertNotNull(z.entry("/OEBPS/Text/Ch1.xhtml"))
    }

    @Test fun utf8_name_decoded() {
        val z = open(zipOf("Livres Été/ch.xhtml" to "x".b()))
        assertNotNull(z.entry("Livres Été/ch.xhtml"))
    }

    @Test fun cp437_name_decoded() {
        val z = open(zipOf("Été.xhtml" to "x".b(), charset = charset("Cp437")))
        assertEquals("Été.xhtml", z.entries.single().name)
    }

    @Test fun not_a_zip() {
        val e = assertFailsWith<ZipFormatException> { open("This file is plain text pretending to be an EPUB.\n".repeat(50).b()) }
        assertEquals(ZipError.NOT_A_ZIP, e.error)
    }

    @Test fun tiny_file_is_not_a_zip() {
        assertEquals(ZipError.NOT_A_ZIP, assertFailsWith<ZipFormatException> { open("not an epub at all\n".b()) }.error)
    }

    @Test fun truncated_zip_is_damaged() {
        val full = zipOf("a.txt" to ByteArray(50_000) { 1 }, "b.txt" to ByteArray(50_000) { 2 })
        val e = assertFailsWith<ZipFormatException> { open(full.copyOf(full.size * 2 / 3)) }
        assertEquals(ZipError.DAMAGED, e.error)
    }

    @Test fun digest_equal_for_identical_bytes_and_differs_on_change() {
        val a = zipOf("x.txt" to "one".b()); val b = a.copyOf(); val c = zipOf("x.txt" to "two".b())
        assertEquals(open(a).centralDirectoryDigest, open(b).centralDirectoryDigest)
        assertNotEquals(open(a).centralDirectoryDigest, open(c).centralDirectoryDigest)
    }

    @Test fun zip64_archive_with_many_entries() {
        val entries = (0 until 70_000).map { "e$it" to ByteArray(0) }.toTypedArray()
        val z = open(zipOf(*entries))
        assertEquals(70_000, z.entries.size)
    }

    @Test fun corpus_malformed_files_classified() {
        fun err(name: String) = assertFailsWith<ZipFormatException> {
            openFileSource(Corpus.generated(name).path).use { ZipReader.open(it) }
        }.error
        assertEquals(ZipError.NOT_A_ZIP, err("malformed-not-a-zip.epub"))
        assertEquals(ZipError.DAMAGED, err("malformed-truncated.epub"))
        assertEquals(ZipError.NOT_A_ZIP, assertFailsWith<ZipFormatException> {
            openFileSource(Corpus.folder("fake.epub").path).use { ZipReader.open(it) }
        }.error)
    }

    @Test fun file_source_reads_corpus_book() {
        openFileSource(Corpus.generated("stress-200-images.epub").path).use { src ->
            val z = ZipReader.open(src)
            assertNotNull(z.entry("META-INF/container.xml"))
            assertEquals(200, z.entries.count { it.name.endsWith(".png") && it.name.contains("/images/") })
        }
    }
}
```
Run: `./gradlew :core:epub:jvmTest` → Expected: compilation FAIL (types missing).

- [ ] **Step 3: Implement sources, streams and platform pieces**

```kotlin
// core/epub/src/commonMain/kotlin/app/epubreader/core/epub/Platform.kt
package app.epubreader.core.epub

import app.epubreader.core.epub.zip.ByteStream
import app.epubreader.core.epub.zip.RandomAccessSource

/** Opens a local file for positional reads (thread-safe). */
expect fun openFileSource(path: String): RandomAccessSource

/** Lower-case hex SHA-256. */
expect fun sha256Hex(bytes: ByteArray): String

/** Raw DEFLATE (no zlib header) decoder over [raw]. */
expect fun inflateStream(raw: ByteStream): ByteStream
```

```kotlin
// core/epub/src/commonMain/kotlin/app/epubreader/core/epub/zip/RandomAccessSource.kt
package app.epubreader.core.epub.zip

/** Random-access bytes (a file, a file descriptor, or memory). Implementations must allow concurrent reads. */
interface RandomAccessSource : AutoCloseable {
    val size: Long
    /** Reads up to [len] bytes at [position] into [dst]; returns the count, or -1 at end of data. */
    fun read(position: Long, dst: ByteArray, off: Int, len: Int): Int
}

fun RandomAccessSource.readFully(position: Long, len: Int): ByteArray {
    val out = ByteArray(len)
    var done = 0
    while (done < len) {
        val n = read(position + done, out, done, len - done)
        if (n <= 0) throw ZipFormatException(ZipError.DAMAGED, "unexpected end of data at ${position + done}")
        done += n
    }
    return out
}

class ByteArraySource(private val bytes: ByteArray) : RandomAccessSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(position: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (position >= bytes.size) return -1
        val n = minOf(len.toLong(), bytes.size - position).toInt()
        bytes.copyInto(dst, off, position.toInt(), position.toInt() + n)
        return n
    }
    override fun close() {}
}
```

```kotlin
// core/epub/src/commonMain/kotlin/app/epubreader/core/epub/zip/ByteStream.kt
package app.epubreader.core.epub.zip

/** Minimal sequential stream (KMP-friendly replacement for InputStream). */
interface ByteStream : AutoCloseable {
    /** Reads up to [len] bytes; returns the count or -1 at the end. */
    fun read(dst: ByteArray, off: Int, len: Int): Int
}

fun ByteStream.readAllBytes(limit: Long): ByteArray {
    var buf = ByteArray(8192)
    var n = 0
    while (true) {
        if (n == buf.size) {
            if (buf.size.toLong() >= limit) throw ZipFormatException(ZipError.DAMAGED, "entry exceeds $limit bytes")
            buf = buf.copyOf(minOf(buf.size.toLong() * 2, limit + 1).toInt())
        }
        val r = read(buf, n, buf.size - n)
        if (r < 0) return buf.copyOf(n)
        n += r
        if (n > limit) throw ZipFormatException(ZipError.DAMAGED, "entry exceeds $limit bytes")
    }
}

/** [length] bytes of [src] starting at [start]. */
class RangeStream(private val src: RandomAccessSource, start: Long, length: Long) : ByteStream {
    private var pos = start
    private val end = start + length
    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        if (pos >= end) return -1
        val n = src.read(pos, dst, off, minOf(len.toLong(), end - pos).toInt())
        if (n <= 0) throw ZipFormatException(ZipError.DAMAGED, "unexpected end of entry data")
        pos += n
        return n
    }
    override fun close() {}
}
```

```kotlin
// core/epub/src/jvmShared/kotlin/app/epubreader/core/epub/FileChannelSource.kt
package app.epubreader.core.epub

import app.epubreader.core.epub.zip.RandomAccessSource
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Positional reads on a FileChannel are thread-safe; used for files and (M4) ParcelFileDescriptors. */
class FileChannelSource(private val channel: FileChannel) : RandomAccessSource {
    override val size: Long = channel.size()
    override fun read(position: Long, dst: ByteArray, off: Int, len: Int): Int =
        if (position >= size) -1 else channel.read(ByteBuffer.wrap(dst, off, len), position)
    override fun close() = channel.close()
}
```

```kotlin
// core/epub/src/jvmShared/kotlin/app/epubreader/core/epub/InflaterByteStream.kt
package app.epubreader.core.epub

import app.epubreader.core.epub.zip.ByteStream
import app.epubreader.core.epub.zip.ZipError
import app.epubreader.core.epub.zip.ZipFormatException
import java.util.zip.DataFormatException
import java.util.zip.Inflater

class InflaterByteStream(private val raw: ByteStream) : ByteStream {
    private val inflater = Inflater(true)
    private val input = ByteArray(64 * 1024)
    private var eofIn = false

    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (true) {
            try {
                val n = inflater.inflate(dst, off, len)
                if (n > 0) return n
            } catch (e: DataFormatException) {
                throw ZipFormatException(ZipError.DAMAGED, "corrupt deflate data: ${e.message}")
            }
            if (inflater.finished() || inflater.needsDictionary()) return -1
            if (inflater.needsInput()) {
                if (eofIn) { // raw deflate may need one extra dummy byte at the end
                    if (inflater.finished()) return -1
                    throw ZipFormatException(ZipError.DAMAGED, "truncated deflate data")
                }
                val r = raw.read(input, 0, input.size)
                if (r < 0) { eofIn = true; inflater.setInput(ByteArray(1), 0, 1) } else inflater.setInput(input, 0, r)
            }
        }
    }

    override fun close() { inflater.end(); raw.close() }
}
```

```kotlin
// core/epub/src/jvmShared/kotlin/app/epubreader/core/epub/Platform.jvmShared.kt
package app.epubreader.core.epub

import app.epubreader.core.epub.zip.ByteStream
import app.epubreader.core.epub.zip.RandomAccessSource
import java.io.RandomAccessFile
import java.security.MessageDigest

actual fun openFileSource(path: String): RandomAccessSource = FileChannelSource(RandomAccessFile(path, "r").channel)

actual fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

actual fun inflateStream(raw: ByteStream): ByteStream = InflaterByteStream(raw)
```
(If the Kotlin compiler rejects `actual` declarations in the custom `jvmShared` source set, move these three
`actual`s into identical files in `androidMain` and `jvmMain` and keep the helper classes in `jvmShared`.)

- [ ] **Step 4: Implement CP437 and the zip reader**

```kotlin
// core/epub/src/commonMain/kotlin/app/epubreader/core/epub/zip/Cp437.kt
package app.epubreader.core.epub.zip

/** IBM code page 437, bytes 0x80..0xFF (zip's legacy default encoding for entry names). */
internal const val CP437_HIGH =
    "ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜ¢£¥₧ƒáíóúñÑªº¿⌐¬½¼¡«»░▒▓│┤╡╢╖╕╣║╗╝╜╛┐└┴┬├─┼╞╟╚╔╩╦╠═╬╧╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀" +
    "αßΓπΣσµτΦΘΩδ∞φε∩≡±≥≤⌠⌡÷≈°∙·√ⁿ²■ "

internal fun decodeCp437(b: ByteArray, off: Int, len: Int): String = buildString(len) {
    for (i in off until off + len) {
        val v = b[i].toInt() and 0xff
        append(if (v < 0x80) v.toChar() else CP437_HIGH[v - 0x80])
    }
}
```
Add to `ZipReaderTest`: `@Test fun cp437_table_has_128_chars() = assertEquals(128, CP437_HIGH.length)` (make the
constant `internal` visible to tests — jvmTest is in the same module).

```kotlin
// core/epub/src/commonMain/kotlin/app/epubreader/core/epub/zip/ZipReader.kt
package app.epubreader.core.epub.zip

import app.epubreader.core.epub.inflateStream
import app.epubreader.core.epub.sha256Hex

enum class ZipError { NOT_A_ZIP, DAMAGED }

class ZipFormatException(val error: ZipError, message: String) : Exception(message)

class ZipEntry(
    val name: String,
    val method: Int,
    val compressedSize: Long,
    val size: Long,
    val crc32: Long,
    internal val localHeaderOffset: Long,
    val encrypted: Boolean,
) {
    val isDirectory: Boolean get() = name.endsWith('/')
}

/**
 * Zip reader working from the central directory (random access, nothing extracted up front).
 * [centralDirectoryDigest] is the SHA-256 of the raw central directory: byte-identical archives have equal
 * digests, and any change to an entry's name, size or CRC changes it. Used for book identity (F-015).
 */
class ZipReader private constructor(
    private val src: RandomAccessSource,
    val entries: List<ZipEntry>,
    val centralDirectoryDigest: String,
) : AutoCloseable {
    private val byName = HashMap<String, ZipEntry>(entries.size * 2).apply { entries.forEach { putIfAbsent(it.name, it) } }
    private val byLower by lazy { HashMap<String, ZipEntry>().apply { entries.forEach { putIfAbsent(it.name.lowercase(), it) } } }

    /** Exact name, then without a leading slash / with forward slashes, then case-insensitive. */
    fun entry(path: String): ZipEntry? {
        val p = path.replace('\\', '/').removePrefix("/")
        return byName[p] ?: byLower[p.lowercase()]
    }

    fun openStream(e: ZipEntry): ByteStream {
        if (e.encrypted) throw ZipFormatException(ZipError.DAMAGED, "encrypted entry ${e.name}")
        val lh = src.readFully(e.localHeaderOffset, 30)
        if (u32(lh, 0) != LOCAL_SIG) throw ZipFormatException(ZipError.DAMAGED, "bad local header for ${e.name}")
        val dataStart = e.localHeaderOffset + 30 + u16(lh, 26) + u16(lh, 28)
        if (dataStart + e.compressedSize > src.size) throw ZipFormatException(ZipError.DAMAGED, "entry ${e.name} beyond end of file")
        val raw = RangeStream(src, dataStart, e.compressedSize)
        return when (e.method) {
            0 -> raw
            8 -> inflateStream(raw)
            else -> throw ZipFormatException(ZipError.DAMAGED, "unsupported compression method ${e.method} for ${e.name}")
        }
    }

    fun readBytes(e: ZipEntry, limit: Long = DEFAULT_LIMIT): ByteArray {
        if (e.size > limit) throw ZipFormatException(ZipError.DAMAGED, "entry ${e.name} is ${e.size} bytes (limit $limit)")
        return openStream(e).use { it.readAllBytes(limit) }
    }

    override fun close() = src.close()

    companion object {
        const val DEFAULT_LIMIT = 64L shl 20
        private const val MAX_ENTRIES = 200_000L
        private const val MAX_CD_BYTES = 64L shl 20
        private const val LOCAL_SIG = 0x04034b50L
        private const val CEN_SIG = 0x02014b50L
        private const val EOCD_SIG = 0x06054b50L
        private const val Z64_LOC_SIG = 0x07064b50L
        private const val Z64_EOCD_SIG = 0x06064b50L

        private fun damaged(msg: String) = ZipFormatException(ZipError.DAMAGED, msg)

        fun open(src: RandomAccessSource): ZipReader {
            val size = src.size
            if (size < 22) throw ZipFormatException(ZipError.NOT_A_ZIP, "file too small to be a zip archive")
            val tailLen = minOf(size, 22L + 65_535L).toInt()
            val tailPos = size - tailLen
            val tail = src.readFully(tailPos, tailLen)
            var i = tailLen - 22
            while (i >= 0 && u32(tail, i) != EOCD_SIG) i--
            if (i < 0) {
                val head = src.readFully(0, 4)
                throw if (u32(head, 0) == LOCAL_SIG) damaged("end of central directory not found (truncated archive?)")
                else ZipFormatException(ZipError.NOT_A_ZIP, "not a zip archive")
            }
            val eocdPos = tailPos + i
            var total = u16(tail, i + 10).toLong()
            var cdSize = u32(tail, i + 12)
            var cdOff = u32(tail, i + 16)
            if (total == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOff == 0xFFFFFFFFL) {
                if (eocdPos < 20) throw damaged("zip64 locator missing")
                val loc = src.readFully(eocdPos - 20, 20)
                if (u32(loc, 0) != Z64_LOC_SIG) throw damaged("zip64 locator missing")
                val recPos = u64(loc, 8)
                if (recPos < 0 || recPos + 56 > size) throw damaged("zip64 record out of range")
                val rec = src.readFully(recPos, 56)
                if (u32(rec, 0) != Z64_EOCD_SIG) throw damaged("bad zip64 end record")
                total = u64(rec, 32); cdSize = u64(rec, 40); cdOff = u64(rec, 48)
            }
            if (cdOff < 0 || cdSize < 0 || cdOff + cdSize > eocdPos) throw damaged("central directory out of range")
            if (total > MAX_ENTRIES || cdSize > MAX_CD_BYTES) throw damaged("archive too large ($total entries)")
            val cd = src.readFully(cdOff, cdSize.toInt())
            val entries = ArrayList<ZipEntry>(total.toInt())
            var p = 0
            while (p + 46 <= cd.size && entries.size < total) {
                if (u32(cd, p) != CEN_SIG) throw damaged("bad central directory header at $p")
                val flags = u16(cd, p + 8)
                val method = u16(cd, p + 10)
                val crc = u32(cd, p + 16)
                var csize = u32(cd, p + 20)
                var usize = u32(cd, p + 24)
                val nameLen = u16(cd, p + 28)
                val extraLen = u16(cd, p + 30)
                val commentLen = u16(cd, p + 32)
                var lho = u32(cd, p + 42)
                val nameStart = p + 46
                val extraStart = nameStart + nameLen
                val extraEnd = extraStart + extraLen
                if (extraEnd + commentLen > cd.size) throw damaged("central directory entry overruns")
                val name = decodeName(cd, nameStart, nameLen, utf8Flag = flags and 0x800 != 0)
                var e = extraStart
                while (e + 4 <= extraEnd) {
                    val id = u16(cd, e); val len = u16(cd, e + 2); var q = e + 4; val fieldEnd = minOf(q + len, extraEnd)
                    if (id == 1) {
                        if (usize == 0xFFFFFFFFL && q + 8 <= fieldEnd) { usize = u64(cd, q); q += 8 }
                        if (csize == 0xFFFFFFFFL && q + 8 <= fieldEnd) { csize = u64(cd, q); q += 8 }
                        if (lho == 0xFFFFFFFFL && q + 8 <= fieldEnd) { lho = u64(cd, q) }
                    }
                    e += 4 + len
                }
                entries += ZipEntry(name, method, csize, usize, crc, lho, encrypted = flags and 1 != 0)
                p = extraEnd + commentLen
            }
            if (entries.size.toLong() != total) throw damaged("central directory lists ${entries.size} of $total entries")
            return ZipReader(src, entries, sha256Hex(cd))
        }

        private fun decodeName(b: ByteArray, off: Int, len: Int, utf8Flag: Boolean): String =
            if (utf8Flag) b.decodeToString(off, off + len)
            else runCatching { b.decodeToString(off, off + len, throwOnInvalidSequence = true) }.getOrElse { decodeCp437(b, off, len) }

        internal fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
        internal fun u32(b: ByteArray, o: Int): Long = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)
        internal fun u64(b: ByteArray, o: Int): Long = u32(b, o) or (u32(b, o + 4) shl 32)
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew :core:epub:jvmTest --tests 'app.epubreader.core.epub.zip.*'`
Expected: all PASS (zip64 test takes a few seconds).

- [ ] **Step 6: Commit**

```bash
git add core/epub
git commit -m "core/epub: random-access zip reader with central-directory digest

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---


### Task 2.2: Reading data — identity, position map, schema v2, repository, position writer

**Files:**
- Create: `core/epub/src/commonMain/kotlin/app/epubreader/core/epub/{meta/BookIdentity.kt, PositionMap.kt}`
- Test: `core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/meta/IdentityTest.kt`
- Create: `core/data/src/commonMain/kotlin/app/epubreader/core/data/{BookEntity.kt, BookFileEntity.kt, ReadingPositionEntity.kt, LibraryDao.kt, PositionDao.kt, HybridClock.kt, Ids.kt, SourceRef.kt, LibraryRepository.kt, PositionWriter.kt}`
- Modify: `core/data/src/commonMain/kotlin/app/epubreader/core/data/AppDatabase.kt` (version 2, AutoMigration)
- Test: `core/data/src/jvmTest/kotlin/app/epubreader/core/data/{TestDb.kt, HybridClockTest.kt, LibraryRepositoryTest.kt, PositionWriterTest.kt, MigrationTest.kt}`

**Interfaces:**
- Produces: `object BookIdentity { fun of(uniqueId: String?, centralDirectoryDigest: String): String }`;
  `interface PositionMap { fun cfiToOriginal(String): String; fun cfiToVirtual(String): String; companion object { val Identity } }`;
  `sealed interface SourceRef { val key: String; File(path); Content(uri); companion fun fromKey(key) }`;
  `class HybridClock(wall: () -> Long) { fun now(): Long }`; `fun newId(): String`;
  `class LibraryRepository(db, clock) { suspend fun knownBookId(ref): String?; suspend fun position(bookId): ReadingPositionEntity?; suspend fun recordOpened(info: OpenedBookInfo): OpenRecord; fun recent(): Flow<List<RecentBook>>; suspend fun forget(ref) }`;
  `class PositionWriter(db, clock, scope) { fun submit(u: Update); suspend fun writeNow(u: Update) }`.
- Tables (master plan Task list; columns final so M3 needs no migration for them): `book` (synced),
  `book_file` (device-local; `source` = `opened` | `scan`), `reading_position` (synced).

- [ ] **Step 1: Identity and position map (core:epub), test first**

```kotlin
// core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/meta/IdentityTest.kt
package app.epubreader.core.epub.meta

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class IdentityTest {
    private val d1 = "a".repeat(64); private val d2 = "b".repeat(64)

    @Test fun format() = assertEquals(49, BookIdentity.of("urn:uuid:1", d1).length) // 16 + '-' + 32
    @Test fun same_inputs_same_identity() = assertEquals(BookIdentity.of("x", d1), BookIdentity.of("x", d1))
    @Test fun identifier_or_digest_change_identity() {
        assertNotEquals(BookIdentity.of("x", d1), BookIdentity.of("y", d1))
        assertNotEquals(BookIdentity.of("x", d1), BookIdentity.of("x", d2))
    }
    /** foliate reports a missing identifier as "" or null; both must give the same identity. */
    @Test fun null_and_empty_identifier_are_equal() = assertEquals(BookIdentity.of(null, d1), BookIdentity.of("", d1))
}
```

```kotlin
// core/epub/src/commonMain/kotlin/app/epubreader/core/epub/meta/BookIdentity.kt
package app.epubreader.core.epub.meta

import app.epubreader.core.epub.sha256Hex

/**
 * F-012/F-015: identity by content — package identifier plus a digest of the zip central directory.
 * The identifier must be the unique-identifier element's text with whitespace collapsed and trimmed (the rule
 * foliate-js uses; M3's Kotlin parser uses the same rule — master plan Review Focus 6).
 */
object BookIdentity {
    fun of(uniqueId: String?, centralDirectoryDigest: String): String =
        sha256Hex((uniqueId ?: "").encodeToByteArray()).take(16) + "-" + centralDirectoryDigest.take(32)
}
```

```kotlin
// core/epub/src/commonMain/kotlin/app/epubreader/core/epub/PositionMap.kt
package app.epubreader.core.epub

/**
 * Maps positions between what the reader shows (virtual documents) and what is stored (original documents).
 * Identity until M8, where ChunkMap implements it for chunked books. Every CFI that crosses between the reader
 * and the database goes through this, so stored data never contains chunk-part positions.
 */
interface PositionMap {
    fun cfiToOriginal(virtualCfi: String): String
    fun cfiToVirtual(originalCfi: String): String

    companion object {
        val Identity: PositionMap = object : PositionMap {
            override fun cfiToOriginal(virtualCfi: String) = virtualCfi
            override fun cfiToVirtual(originalCfi: String) = originalCfi
        }
    }
}
```
Run: `./gradlew :core:epub:jvmTest --tests '*IdentityTest'` → 4 PASS.

- [ ] **Step 2: Write the failing data tests**

```kotlin
// core/data/src/jvmTest/kotlin/app/epubreader/core/data/TestDb.kt
package app.epubreader.core.data

import java.nio.file.Files

fun testDb(): AppDatabase = buildDatabase(jvmDatabaseBuilder(Files.createTempDirectory("db").resolve("t.db").toString()))
```

```kotlin
// core/data/src/jvmTest/kotlin/app/epubreader/core/data/HybridClockTest.kt
package app.epubreader.core.data

import kotlin.test.Test
import kotlin.test.assertTrue

class HybridClockTest {
    @Test fun strictly_increasing_even_if_wall_clock_goes_back() {
        var wall = 1_000L
        val c = HybridClock { wall }
        val a = c.now(); wall = 500; val b = c.now(); val d = c.now(); wall = 2_000; val e = c.now()
        assertTrue(a < b && b < d && d < e, "$a $b $d $e")
    }
}
```

```kotlin
// core/data/src/jvmTest/kotlin/app/epubreader/core/data/LibraryRepositoryTest.kt
package app.epubreader.core.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class LibraryRepositoryTest {
    private val repo = LibraryRepository(testDb(), HybridClock(System::currentTimeMillis))
    private fun info(id: String, ref: SourceRef, title: String = "T") = OpenedBookInfo(id, title, listOf("A"), ref, 100)

    @Test fun first_open_creates_book_and_remembers_source() = runTest {
        val ref = SourceRef.Content("content://x/1")
        assertNull(repo.knownBookId(ref))
        val rec = repo.recordOpened(info("id-1", ref, "Alice"))
        assertEquals(rec.bookId, repo.knownBookId(ref))
        assertNull(rec.position)
        assertEquals(listOf("Alice"), repo.recent().first().map { it.title })
    }

    @Test fun same_identity_from_another_source_is_the_same_book() = runTest {
        val a = repo.recordOpened(info("id-1", SourceRef.File("/sdcard/a.epub")))
        val b = repo.recordOpened(info("id-1", SourceRef.Content("content://x/2")))
        assertEquals(a.bookId, b.bookId)
        assertEquals(1, repo.recent().first().size)
    }

    @Test fun different_identity_is_a_new_book() = runTest {
        val a = repo.recordOpened(info("id-1", SourceRef.File("/a.epub")))
        val b = repo.recordOpened(info("id-2", SourceRef.File("/b.epub")))
        assertNotEquals(a.bookId, b.bookId)
    }

    @Test fun recent_is_newest_first_and_forget_removes() = runTest {
        repo.recordOpened(info("id-1", SourceRef.File("/a.epub"), "A"))
        repo.recordOpened(info("id-2", SourceRef.File("/b.epub"), "B"))
        assertEquals(listOf("B", "A"), repo.recent().first().map { it.title })
        repo.forget(SourceRef.File("/b.epub"))
        assertEquals(listOf("A"), repo.recent().first().map { it.title })
    }
}
```

```kotlin
// core/data/src/jvmTest/kotlin/app/epubreader/core/data/PositionWriterTest.kt
package app.epubreader.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PositionWriterTest {
    /** R-009: a burst of page changes ends with the last one stored; an older write never replaces a newer row. */
    @Test fun burst_ends_on_last_and_never_older() = runBlocking {
        val db = testDb()
        val clock = HybridClock { 1_000L } // frozen wall clock: HybridClock must still order the writes
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val w = PositionWriter(db, clock, scope)
        repeat(1000) { w.submit(PositionWriter.Update("b1", "cfi$it", it / 1000.0, it / 100)) }
        withTimeout(5_000) { while (db.positionDao().get("b1")?.cfi != "cfi999") delay(10) }
        assertFalse(db.positionDao().put("b1", "stale", 0.0, 0, at = 1))
        assertEquals("cfi999", db.positionDao().get("b1")!!.cfi)
        scope.cancel()
    }

    @Test fun write_now_is_visible_immediately() = runBlocking {
        val db = testDb()
        val w = PositionWriter(db, HybridClock(System::currentTimeMillis), CoroutineScope(Dispatchers.IO))
        w.writeNow(PositionWriter.Update("b2", "epubcfi(/6/4!/4/2)", 0.1, 1))
        assertEquals("epubcfi(/6/4!/4/2)", db.positionDao().get("b2")!!.cfi)
    }
}
```

```kotlin
// core/data/src/jvmTest/kotlin/app/epubreader/core/data/MigrationTest.kt
package app.epubreader.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/** Every schema version from the first one with user data (2) on must migrate; M1's v1 must reach v2. */
class MigrationTest {
    @Test fun v1_to_v2() {
        val dbPath = Files.createTempDirectory("mig").resolve("m.db")
        val helper = MigrationTestHelper(
            schemaDirectoryPath = Path.of("schemas"), databasePath = dbPath,
            driver = BundledSQLiteDriver(), databaseClass = AppDatabase::class,
        )
        helper.createDatabase(1).close()
        helper.runMigrationsAndValidate(2).close()
    }
}
```
Add `jvmTest.dependencies { implementation("androidx.room:room-testing:<room version>") }` (catalog entry
`room-testing`). † The JVM `MigrationTestHelper` constructor above is Room 2.7+'s KMP form; if the resolved Room
version names its parameters differently, adapt the call and keep the test.

Run: `./gradlew :core:data:jvmTest` → Expected: compilation FAIL (new types unresolved).

- [ ] **Step 3: Entities, clock, ids, source refs**

```kotlin
// core/data/src/commonMain/kotlin/app/epubreader/core/data/BookEntity.kt
package app.epubreader.core.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A book (synced row, F-115). `authors` is newline-separated. M3 fills language, titleDirection, coverThumb. */
@Entity(tableName = "book", indices = [Index(value = ["identityKey"], unique = true), Index("lastOpenedAt")])
data class BookEntity(
    @PrimaryKey val id: String,
    val identityKey: String,
    val title: String,
    val sortTitle: String,
    val authors: String,
    val sortAuthor: String,
    val language: String?,
    val titleDirection: String, // "auto" | "ltr" | "rtl"
    val coverThumb: String?,
    val addedAt: Long,
    val lastOpenedAt: Long?,
    val hidden: Boolean,
    val updatedAt: Long,
    val deleted: Boolean,
)
```

```kotlin
// core/data/src/commonMain/kotlin/app/epubreader/core/data/BookFileEntity.kt
package app.epubreader.core.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Where a book's file is (device-local, not synced). `path` is a file path or a content URI.
 * `source` = "opened" (picker / open-with / bench) or "scan" (M3). M3's scanner prunes only "scan" rows.
 */
@Entity(tableName = "book_file", indices = [Index("bookId")])
data class BookFileEntity(
    @PrimaryKey val path: String,
    val bookId: String,
    val source: String,
    val folder: String,
    val size: Long,
    val mtimeMs: Long,
    val lastSeenScan: Long,
)
```

```kotlin
// core/data/src/commonMain/kotlin/app/epubreader/core/data/ReadingPositionEntity.kt
package app.epubreader.core.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Last reading position of a book (synced row). `cfi` is always against the original documents. */
@Entity(tableName = "reading_position", indices = [Index(value = ["bookId"], unique = true)])
data class ReadingPositionEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val cfi: String,
    val fraction: Double,
    val spineIndex: Int,
    val updatedAt: Long,
    val deleted: Boolean,
)
```

```kotlin
// core/data/src/commonMain/kotlin/app/epubreader/core/data/HybridClock.kt
package app.epubreader.core.data

import kotlin.jvm.Synchronized

/** Change timestamps for synced rows (F-115): wall-clock ms, but never ≤ the previous value. */
class HybridClock(private val wall: () -> Long) {
    private var last = 0L

    @Synchronized
    fun now(): Long {
        val w = wall()
        last = if (w > last) w else last + 1
        return last
    }
}
```

```kotlin
// core/data/src/commonMain/kotlin/app/epubreader/core/data/Ids.kt
package app.epubreader.core.data

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Stable row ids (F-115): random UUID v4 text. */
@OptIn(ExperimentalUuidApi::class)
fun newId(): String = Uuid.random().toString()
```

```kotlin
// core/data/src/commonMain/kotlin/app/epubreader/core/data/SourceRef.kt
package app.epubreader.core.data

/** Where to read a book from. [key] is what `book_file.path` stores. */
sealed interface SourceRef {
    val key: String

    data class File(val path: String) : SourceRef { override val key: String get() = path }
    data class Content(val uri: String) : SourceRef { override val key: String get() = uri }

    companion object {
        fun fromKey(key: String): SourceRef = if (key.startsWith("content://")) Content(key) else File(key)
    }
}
```

- [ ] **Step 4: DAOs, database v2, repository, position writer**

```kotlin
// core/data/src/commonMain/kotlin/app/epubreader/core/data/LibraryDao.kt
package app.epubreader.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class RecentBookRow(val id: String, val title: String, val authors: String, val path: String)

@Dao
abstract class LibraryDao {
    @Query("SELECT * FROM book WHERE identityKey = :key") abstract suspend fun byIdentity(key: String): BookEntity?
    @Query("SELECT bookId FROM book_file WHERE path = :path") abstract suspend fun bookIdForPath(path: String): String?
    @Insert abstract suspend fun insertBook(b: BookEntity)
    @Upsert abstract suspend fun upsertFile(f: BookFileEntity)

    @Query(
        "UPDATE book SET title = :title, sortTitle = :sortTitle, authors = :authors, sortAuthor = :sortAuthor, " +
            "lastOpenedAt = :at, updatedAt = :at, deleted = 0 WHERE id = :id",
    )
    abstract suspend fun touchOpened(id: String, title: String, sortTitle: String, authors: String, sortAuthor: String, at: Long)

    /** One row per book that was opened through a picker/open-with/bench path, newest first (M2 home screen). */
    @Query(
        "SELECT b.id AS id, b.title AS title, b.authors AS authors, MAX(f.path) AS path " +
            "FROM book b JOIN book_file f ON f.bookId = b.id " +
            "WHERE b.deleted = 0 AND b.hidden = 0 AND f.source = 'opened' " +
            "GROUP BY b.id ORDER BY b.lastOpenedAt DESC LIMIT 200",
    )
    abstract fun recent(): Flow<List<RecentBookRow>>

    @Query("DELETE FROM book_file WHERE path = :path AND source = 'opened'") abstract suspend fun forgetOpened(path: String)

    /** Book by identity (created on first sight) + its source; one transaction. Returns the book id. */
    @Transaction
    open suspend fun recordOpened(identityKey: String, title: String, authors: String, path: String, size: Long, now: Long): String {
        val id = byIdentity(identityKey)?.id ?: newId().also {
            insertBook(
                BookEntity(
                    id = it, identityKey = identityKey, title = title, sortTitle = title.lowercase(), authors = authors,
                    sortAuthor = authors.substringBefore('\n').lowercase(), language = null, titleDirection = "auto",
                    coverThumb = null, addedAt = now, lastOpenedAt = now, hidden = false, updatedAt = now, deleted = false,
                ),
            )
        }
        touchOpened(id, title, title.lowercase(), authors, authors.substringBefore('\n').lowercase(), now)
        upsertFile(BookFileEntity(path = path, bookId = id, source = "opened", folder = "", size = size, mtimeMs = 0, lastSeenScan = 0))
        return id
    }
}
```

```kotlin
// core/data/src/commonMain/kotlin/app/epubreader/core/data/PositionDao.kt
package app.epubreader.core.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
abstract class PositionDao {
    @Query("SELECT * FROM reading_position WHERE bookId = :bookId AND deleted = 0")
    abstract suspend fun get(bookId: String): ReadingPositionEntity?

    @Query("SELECT * FROM reading_position WHERE bookId = :bookId")
    abstract suspend fun anyRow(bookId: String): ReadingPositionEntity?

    @Upsert abstract suspend fun upsert(row: ReadingPositionEntity)

    /** One transaction (R-009). Never replaces a row with a newer or equal `updatedAt`; returns false then. */
    @Transaction
    open suspend fun put(bookId: String, cfi: String, fraction: Double, spineIndex: Int, at: Long): Boolean {
        val cur = anyRow(bookId)
        if (cur != null && cur.updatedAt >= at) return false
        upsert(ReadingPositionEntity(cur?.id ?: newId(), bookId, cfi, fraction, spineIndex, at, deleted = false))
        return true
    }
}
```

Modify `AppDatabase.kt`:
```kotlin
@Database(
    entities = [SettingEntity::class, BookEntity::class, BookFileEntity::class, ReadingPositionEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun settingDao(): SettingDao
    abstract fun libraryDao(): LibraryDao
    abstract fun positionDao(): PositionDao
}
```
(import `androidx.room.AutoMigration`.)

```kotlin
// core/data/src/commonMain/kotlin/app/epubreader/core/data/LibraryRepository.kt
package app.epubreader.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class OpenedBookInfo(val identityKey: String, val title: String, val authors: List<String>, val source: SourceRef, val size: Long)
data class OpenRecord(val bookId: String, val position: ReadingPositionEntity?)
data class RecentBook(val id: String, val title: String, val authors: List<String>, val source: SourceRef)

class LibraryRepository(private val db: AppDatabase, private val clock: HybridClock) {
    suspend fun knownBookId(ref: SourceRef): String? = db.libraryDao().bookIdForPath(ref.key)

    suspend fun position(bookId: String): ReadingPositionEntity? = db.positionDao().get(bookId)

    suspend fun recordOpened(info: OpenedBookInfo): OpenRecord {
        val id = db.libraryDao().recordOpened(
            info.identityKey, info.title, info.authors.joinToString("\n"), info.source.key, info.size, clock.now(),
        )
        return OpenRecord(id, db.positionDao().get(id))
    }

    fun recent(): Flow<List<RecentBook>> = db.libraryDao().recent().map { rows ->
        rows.map { RecentBook(it.id, it.title, it.authors.split('\n').filter(String::isNotBlank), SourceRef.fromKey(it.path)) }
    }

    suspend fun forget(ref: SourceRef) = db.libraryDao().forgetOpened(ref.key)
}
```

```kotlin
// core/data/src/commonMain/kotlin/app/epubreader/core/data/PositionWriter.kt
package app.epubreader.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Writes reading positions off the UI thread (R-005) and transactionally (R-009). Bursts of page turns collapse to
 * the newest update, so at most the last page change can be lost if the process dies. Timestamps are taken at
 * submit time, so a late write of an older update never replaces a newer row (PositionDao.put).
 */
class PositionWriter(private val db: AppDatabase, private val clock: HybridClock, scope: CoroutineScope) {
    data class Update(val bookId: String, val cfi: String, val fraction: Double, val spineIndex: Int)

    private val pending = Channel<Pair<Update, Long>>(Channel.CONFLATED)

    init {
        scope.launch { for ((u, at) in pending) write(u, at) }
    }

    fun submit(u: Update) {
        pending.trySend(u to clock.now())
    }

    /** Bypasses the queue; used when a book is closed so switching books never drops its last position. */
    suspend fun writeNow(u: Update) = write(u, clock.now())

    private suspend fun write(u: Update, at: Long) {
        db.positionDao().put(u.bookId, u.cfi, u.fraction, u.spineIndex, at)
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew :core:data:jvmTest` → Expected: all PASS (M1's 2 + 1 + 4 + 2 + 1).
`core/data/schemas/app.epubreader.core.data.AppDatabase/2.json` exists; commit it next to `1.json`.

- [ ] **Step 6: Commit**

```bash
git add core/epub core/data
git commit -m "data: books, sources and reading positions (schema v2), identity, position writer

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2.3: Book sources, sessions, content server and opener

**Files:**
- Create: `ui/src/commonMain/kotlin/app/epubreader/reader/Ports.kt` (the opener/engine ports used by `ReaderController`)
- Create: `app/src/main/kotlin/app/epubreader/reader/{BookSources.kt, BookSession.kt, BookContentServer.kt, MimeTypes.kt, BookOpener.kt}`
- Test: `app/src/test/kotlin/app/epubreader/reader/BookContentServerTest.kt` (local JVM test; no Android classes)
- Create: `app/src/androidTest/kotlin/app/epubreader/{TestBooks.kt, TestBookProvider.kt}`, `app/src/androidTest/AndroidManifest.xml`
- Test: `app/src/androidTest/kotlin/app/epubreader/reader/BookSourcesTest.kt`

**Interfaces:**
- Produces (ui): `interface BookOpenerPort { suspend fun open(ref: SourceRef): OpenResult; suspend fun close(sessionId: String) }`,
  `sealed interface OpenResult { Ok(sessionId, cdDigest, size); Failed(reason: OpenFailure) }`,
  `enum class OpenFailure { NO_ACCESS, NOT_A_ZIP, DAMAGED }`,
  `interface ReaderPort { val events: Flow<FromWeb>; val isReady: StateFlow<Boolean>; fun send(m: ToWeb) }`,
  `fun interface EventProbe { fun ev(name: String, extra: String) }`.
- Produces (app): `class BookContentServer { fun register(s: BookSession); fun unregister(id: String): BookSession?; fun serve(path: String): Served }`,
  `class BookOpener(sources, server) : BookOpenerPort` (keeps the last closed session warm for P-016),
  `class BookSources(context) { fun open(ref): OpenedSource; fun stamp(ref): String }`.
- HTTP surface (same origin as the host page): `GET /book/<sessionId>/entries.json` →
  `[{"name":"OEBPS/content.opf","size":1234},…]`; `GET /book/<sessionId>/<entry path>` → entry bytes (streamed,
  inflated on the fly), MIME by extension; 404 unknown entry or session; 403 `.`/`..` path segments; 410 a session
  that was closed (a stale request never touches a closed zip).

- [ ] **Step 1: Ports**

```kotlin
// ui/src/commonMain/kotlin/app/epubreader/reader/Ports.kt
package app.epubreader.reader

import app.epubreader.core.data.SourceRef
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.reader.bridge.ToWeb
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** The reader WebView as seen by the UI layer (implemented by app's ReaderEngine). */
interface ReaderPort {
    val events: Flow<FromWeb>
    val isReady: StateFlow<Boolean>
    fun send(m: ToWeb)
}

enum class OpenFailure { NO_ACCESS, NOT_A_ZIP, DAMAGED }

sealed interface OpenResult {
    data class Ok(val sessionId: String, val cdDigest: String, val size: Long) : OpenResult
    data class Failed(val reason: OpenFailure) : OpenResult
}

/** Opens a book's bytes for the reader (implemented by app's BookOpener). Never called on the main thread's time. */
interface BookOpenerPort {
    suspend fun open(ref: SourceRef): OpenResult
    suspend fun close(sessionId: String)
}

/** Benchmark events (app's Probe); a no-op in normal builds. */
fun interface EventProbe {
    fun ev(name: String, extra: String)

    companion object { val None = EventProbe { _, _ -> } }
}
```

- [ ] **Step 2: Write the failing server test**

```kotlin
// app/src/test/kotlin/app/epubreader/reader/BookContentServerTest.kt
package app.epubreader.reader

import app.epubreader.core.data.SourceRef
import app.epubreader.core.epub.zip.ByteArraySource
import app.epubreader.core.epub.zip.ZipReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BookContentServerTest {
    private fun zip(vararg e: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z -> e.forEach { (n, t) -> z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() } }
    }.toByteArray()

    private val bytes = zip(
        "mimetype" to "application/epub+zip",
        "OEBPS/content.opf" to "<package/>",
        "OEBPS/Text/Livres Été.xhtml" to "<html>été</html>",
    )
    private val src = ByteArraySource(bytes)
    private val session = BookSession("s1", SourceRef.File("/x.epub"), ZipReader.open(src), bytes.size.toLong(), stamp = "1")
    private val server = BookContentServer().apply { register(session) }

    private fun body(p: String) = server.serve(p).let { it.status to it.body().readBytes().decodeToString() }

    @Test fun entries_json_lists_every_entry_with_size() {
        val (status, json) = body("s1/entries.json")
        assertEquals(200, status)
        assertTrue(json, json.contains("\"name\":\"OEBPS/Text/Livres Été.xhtml\""))
        assertTrue(json, json.contains("\"size\":10"))
    }

    @Test fun entry_is_streamed_with_mime_by_extension() {
        val r = server.serve("s1/OEBPS/Text/Livres Été.xhtml")
        assertEquals(200, r.status); assertEquals("application/xhtml+xml", r.mime)
        assertEquals("<html>été</html>", r.body().readBytes().decodeToString())
    }

    @Test fun unknown_entry_and_unknown_session_are_404() {
        assertEquals(404, server.serve("s1/OEBPS/missing.xhtml").status)
        assertEquals(404, server.serve("nope/OEBPS/content.opf").status)
    }

    @Test fun dot_segments_are_rejected() {
        assertEquals(403, server.serve("s1/OEBPS/../mimetype").status)
        assertEquals(403, server.serve("s1/./mimetype").status)
    }

    @Test fun closed_session_is_410() {
        server.unregister("s1")!!.close()
        assertEquals(410, server.serve("s1/OEBPS/content.opf").status)
    }
}
```
Run: `./gradlew :app:testPlayDebugUnitTest` → Expected: compilation FAIL.

- [ ] **Step 3: Sessions, MIME types, server**

```kotlin
// app/src/main/kotlin/app/epubreader/reader/BookSession.kt
package app.epubreader.reader

import app.epubreader.core.data.SourceRef
import app.epubreader.core.epub.zip.ZipReader

/** One opened book file. [id] is random and appears in every URL the WebView loads for this book. */
class BookSession(val id: String, val ref: SourceRef, val zip: ZipReader, val size: Long, val stamp: String) : AutoCloseable {
    val cdDigest: String get() = zip.centralDirectoryDigest
    override fun close() = zip.close() // closes the RandomAccessSource too
}
```

```kotlin
// app/src/main/kotlin/app/epubreader/reader/MimeTypes.kt
package app.epubreader.reader

object MimeTypes {
    private val byExt = mapOf(
        "xhtml" to "application/xhtml+xml", "xht" to "application/xhtml+xml", "html" to "text/html", "htm" to "text/html",
        "css" to "text/css", "svg" to "image/svg+xml", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
        "gif" to "image/gif", "webp" to "image/webp", "ttf" to "font/ttf", "otf" to "font/otf", "woff" to "font/woff",
        "woff2" to "font/woff2", "opf" to "application/oebps-package+xml", "ncx" to "application/x-dtbncx+xml",
        "xml" to "application/xml", "smil" to "application/smil+xml", "js" to "text/javascript", "mp3" to "audio/mpeg",
        "mp4" to "video/mp4", "m4a" to "audio/mp4",
    )

    fun of(name: String): String = byExt[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
}
```

```kotlin
// app/src/main/kotlin/app/epubreader/reader/BookContentServer.kt
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
        val s = sessions[sid] ?: return if (sid in closed) empty(410, "Gone") else empty(404, "Not Found")
        if (name == "entries.json") {
            val json = Json.encodeToString(s.zip.entries.filterNot { it.isDirectory }.map { EntryInfo(it.name, it.size) })
            return Served(200, "OK", "application/json") { ByteArrayInputStream(json.encodeToByteArray()) }
        }
        if (name.split('/').any { it == "." || it == ".." }) return empty(403, "Forbidden")
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
```
Check `ByteStream.read`'s end-of-data convention in Task 2.1 (−1 or 0) and map it to `-1` here.
Run: `./gradlew :app:testPlayDebugUnitTest --tests '*BookContentServerTest'` → Expected: 5 PASS.

- [ ] **Step 4: Sources (file and content) and the opener**

```kotlin
// app/src/main/kotlin/app/epubreader/reader/BookSources.kt
package app.epubreader.reader

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import app.epubreader.core.data.SourceRef
import app.epubreader.core.epub.FileChannelSource
import app.epubreader.core.epub.openFileSource
import app.epubreader.core.epub.sha256Hex
import app.epubreader.core.epub.zip.RandomAccessSource
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException

class OpenedSource(val source: RandomAccessSource, val size: Long)

/**
 * Opens a SourceRef for positional reads. A content URI is read through the provider's file descriptor (no copy);
 * a non-seekable descriptor (a pipe, e.g. from a cloud provider) is copied to cacheDir/opened/ first.
 * Throws FileNotFoundException / SecurityException when the book cannot be reached.
 */
class BookSources(private val context: Context) {
    fun open(ref: SourceRef): OpenedSource = when (ref) {
        is SourceRef.File -> File(ref.path).let { f ->
            if (!f.canRead()) throw FileNotFoundException(ref.path)
            OpenedSource(openFileSource(f.path), f.length())
        }
        is SourceRef.Content -> openContent(Uri.parse(ref.uri))
    }

    /** Changes when the file's content may have changed (used to decide whether a warm session can be reused). */
    fun stamp(ref: SourceRef): String = when (ref) {
        is SourceRef.File -> File(ref.path).let { "${it.length()}:${it.lastModified()}" }
        is SourceRef.Content -> runCatching {
            context.contentResolver.query(Uri.parse(ref.uri), arrayOf(OpenableColumns.SIZE, "last_modified"), null, null, null)
                ?.use { c -> if (c.moveToFirst()) "${c.getLong(0)}:${if (c.columnCount > 1 && !c.isNull(1)) c.getLong(1) else 0}" else "?" }
        }.getOrNull() ?: "?"
    }

    private fun openContent(uri: Uri): OpenedSource {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString())
        if (pfd.statSize >= 0) {
            val channel = FileInputStream(pfd.fileDescriptor).channel
            return OpenedSource(PfdSource(pfd, FileChannelSource(channel)), pfd.statSize)
        }
        pfd.close()
        val dir = File(context.cacheDir, "opened").apply { mkdirs() }
        val f = File(dir, sha256Hex(uri.toString().encodeToByteArray()).take(16) + ".epub")
        (context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString()))
            .use { input -> f.outputStream().use { input.copyTo(it) } }
        return OpenedSource(openFileSource(f.path), f.length())
    }
}

private class PfdSource(private val pfd: ParcelFileDescriptor, private val inner: FileChannelSource) : RandomAccessSource {
    override val size: Long get() = inner.size
    override fun read(position: Long, dst: ByteArray, off: Int, len: Int) = inner.read(position, dst, off, len)
    override fun close() { inner.close(); pfd.close() }
}
```

```kotlin
// app/src/main/kotlin/app/epubreader/reader/BookOpener.kt
package app.epubreader.reader

import app.epubreader.core.data.SourceRef
import app.epubreader.core.data.newId
import app.epubreader.core.epub.zip.ZipError
import app.epubreader.core.epub.zip.ZipFormatException
import app.epubreader.core.epub.zip.ZipReader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/**
 * Opens books for the reader on the I/O dispatcher (R-005). Keeps the most recently closed session open and
 * registered ("warm"): reopening the same, unchanged file reuses it, and the JS host reuses its parsed book for
 * the same session id (P-016, C-014). Opening any other book closes the warm session.
 */
class BookOpener(
    private val sources: BookSources,
    private val server: BookContentServer,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : BookOpenerPort {
    private val lock = Mutex()
    private var current: BookSession? = null
    private var warm: BookSession? = null

    override suspend fun open(ref: SourceRef): OpenResult = withContext(io) {
        lock.withLock {
            current?.let { retire(it) }
            current = null
            warm?.let { w ->
                warm = null
                if (w.ref == ref && w.stamp == sources.stamp(ref)) {
                    current = w
                    return@withContext OpenResult.Ok(w.id, w.cdDigest, w.size)
                }
                server.unregister(w.id); w.close()
            }
            val opened = try {
                sources.open(ref)
            } catch (e: FileNotFoundException) {
                return@withContext OpenResult.Failed(OpenFailure.NO_ACCESS)
            } catch (e: SecurityException) {
                return@withContext OpenResult.Failed(OpenFailure.NO_ACCESS)
            }
            val zip = try {
                ZipReader.open(opened.source)
            } catch (e: ZipFormatException) {
                opened.source.close()
                return@withContext OpenResult.Failed(if (e.error == ZipError.NOT_A_ZIP) OpenFailure.NOT_A_ZIP else OpenFailure.DAMAGED)
            }
            val s = BookSession(newId(), ref, zip, opened.size, sources.stamp(ref))
            server.register(s)
            current = s
            OpenResult.Ok(s.id, s.cdDigest, s.size)
        }
    }

    override suspend fun close(sessionId: String) = withContext(io) {
        lock.withLock { current?.takeIf { it.id == sessionId }?.let { retire(it); current = null } }
    }

    /** Current session becomes the warm one; the previous warm session is closed for good. */
    private fun retire(s: BookSession) {
        warm?.let { server.unregister(it.id); it.close() }
        warm = s
    }
}
```

- [ ] **Step 5: Test books, test provider, instrumented source test**

```kotlin
// app/src/androidTest/kotlin/app/epubreader/TestBooks.kt
package app.epubreader

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Small valid EPUB 3 books for instrumented tests (no corpus or storage permission needed). */
object TestBooks {
    fun epub(
        id: String = "urn:uuid:00000000-0000-4000-8000-000000000001",
        title: String = "Smoke Book",
        chapters: Int = 3,
        paragraphs: Int = 40,
        missingTocTarget: Boolean = false,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            val mt = "application/epub+zip".toByteArray()
            z.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED; size = mt.size.toLong(); compressedSize = size; crc = CRC32().apply { update(mt) }.value
            }); z.write(mt); z.closeEntry()
            fun put(name: String, text: String) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
            put("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            val items = (1..chapters).joinToString("") { """<item id="c$it" href="c$it.xhtml" media-type="application/xhtml+xml"/>""" }
            val spine = (1..chapters).joinToString("") { """<itemref idref="c$it"/>""" }
            put("OEBPS/content.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="uid">$id</dc:identifier><dc:title>$title</dc:title><dc:creator>Test Author</dc:creator><dc:language>en</dc:language></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>$items</manifest><spine>$spine</spine></package>""")
            val toc = (1..chapters).joinToString("") { """<li><a href="c$it.xhtml">Chapter $it</a></li>""" } +
                (if (missingTocTarget) """<li><a href="gone.xhtml">Missing chapter</a></li>""" else "")
            put("OEBPS/nav.xhtml", """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Contents</title></head><body><nav epub:type="toc"><ol>$toc</ol></nav></body></html>""")
            for (c in 1..chapters) {
                val body = (1..paragraphs).joinToString("") { "<p>Chapter $c, paragraph $it. " + "Lorem ipsum dolor sit amet. ".repeat(6) + "</p>" }
                put("OEBPS/c$c.xhtml", """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter $c</title></head><body><h1>Chapter $c</h1>$body</body></html>""")
            }
        }
        return out.toByteArray()
    }

    fun write(ctx: Context, name: String, bytes: ByteArray = epub()): File =
        File(ctx.cacheDir, "testbooks").apply { mkdirs() }.resolve(name).apply { writeBytes(bytes) }
}
```

```kotlin
// app/src/androidTest/kotlin/app/epubreader/TestBookProvider.kt
package app.epubreader

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.concurrent.thread

/**
 * Serves files from the test app's cache dir as content://app.epubreader.test.books/<name>[?pipe=1].
 * pipe=1 returns a non-seekable pipe (like many cloud providers). Also used by M3's OpenWithTest.
 */
class TestBookProvider : ContentProvider() {
    private fun file(uri: Uri) =
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "testbooks/${uri.lastPathSegment}")
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/epub+zip"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = file(uri)
        if (uri.getQueryParameter("pipe") != "1") return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        val (r, w) = ParcelFileDescriptor.createPipe()
        thread { ParcelFileDescriptor.AutoCloseOutputStream(w).use { out -> f.inputStream().use { it.copyTo(out) } } }
        return r
    }
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf(uri.lastPathSegment, file(uri).length())) }
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
```

```xml
<!-- app/src/androidTest/AndroidManifest.xml -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <provider android:name="app.epubreader.TestBookProvider" android:authorities="app.epubreader.test.books"
            android:exported="true" />
    </application>
</manifest>
```
(The test APK's components run in the instrumented app's process, so the provider reads the files that
`TestBooks.write` put into the target app's cache dir.)

```kotlin
// app/src/androidTest/kotlin/app/epubreader/reader/BookSourcesTest.kt
package app.epubreader.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epubreader.TestBooks
import app.epubreader.core.data.SourceRef
import app.epubreader.core.epub.zip.ZipReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookSourcesTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val sources = BookSources(ctx)
    private fun opf(ref: SourceRef) = sources.open(ref).let { ZipReader.open(it.source).use { z -> z.entry("OEBPS/content.opf") } }

    @Test fun file_path() { val f = TestBooks.write(ctx, "a.epub"); assertNotNull(opf(SourceRef.File(f.path))) }

    @Test fun content_uri_with_seekable_descriptor() {
        TestBooks.write(ctx, "b.epub"); assertNotNull(opf(SourceRef.Content("content://app.epubreader.test.books/b.epub")))
    }

    @Test fun pipe_is_copied_then_opened() {
        TestBooks.write(ctx, "c.epub")
        assertNotNull(opf(SourceRef.Content("content://app.epubreader.test.books/c.epub?pipe=1")))
        assertTrue(ctx.cacheDir.resolve("opened").listFiles()!!.isNotEmpty())
    }

    @Test fun opener_classifies_failures_and_reuses_warm_session() = runBlocking {
        val server = BookContentServer(); val opener = BookOpener(sources, server)
        val bad = ctx.cacheDir.resolve("testbooks/bad.epub").apply { parentFile!!.mkdirs(); writeText("not an epub at all\n") }
        assertEquals(OpenResult.Failed(OpenFailure.NOT_A_ZIP), opener.open(SourceRef.File(bad.path)))
        assertEquals(OpenResult.Failed(OpenFailure.NO_ACCESS), opener.open(SourceRef.File("/nonexistent/x.epub")))
        val f = TestBooks.write(ctx, "d.epub"); val ref = SourceRef.File(f.path)
        val first = opener.open(ref) as OpenResult.Ok
        opener.close(first.sessionId)
        val again = opener.open(ref) as OpenResult.Ok
        assertEquals(first.sessionId, again.sessionId)            // warm reuse
        assertEquals(200, server.serve("${again.sessionId}/entries.json").status)
    }
}
```
Run: `./gradlew :app:connectedPlayDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.epubreader.reader.BookSourcesTest`
→ Expected: 4 PASS.

- [ ] **Step 6: Commit**

```bash
git add ui/src/commonMain/kotlin/app/epubreader/reader/Ports.kt app/src
git commit -m "reader: file and content sources, per-session content server, opener with warm reuse

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2.4: Bridge v1 (slice subset) and the reader host

**Files:**
- Modify: `reader-web/src/bridge/{protocol.ts, bridge.ts}`, `reader-web/src/main.ts`, `reader-web/src/index.html`
- Create: `reader-web/src/reader/{loader.ts, toc.ts, reader.ts}`
- Test: `reader-web/test/{protocol.test.ts, loader.test.ts, toc.test.ts}`
- Create: `bridge/fixtures/{open,close,next,prev,goTo,opened,relocate,painted,tap,error}.json`
- Modify: `ui/src/commonMain/kotlin/app/epubreader/reader/bridge/Protocol.kt`, `ui/src/jvmTest/kotlin/app/epubreader/reader/bridge/ProtocolFixtureTest.kt`
- Modify: `docs/bridge-protocol.md`

**Interfaces (message table; later milestones add messages, see master plan Task specs):**

| Direction | type | fields |
|---|---|---|
| Kotlin → JS | `ping` | `id` (M1) |
| Kotlin → JS | `open` | `sessionId`, `position` (virtual CFI or null), `texts.missingPart` |
| Kotlin → JS | `close`, `next`, `prev` | — |
| Kotlin → JS | `goTo` | `target` (CFI or href) |
| JS → Kotlin | `ready`, `pong` | (M1) |
| JS → Kotlin | `opened` | `sessionId`, `identifier` (unique-identifier text or null), `title`, `authors[]`, `dir`, `fixedLayout`, `sections`, `toc[] {label, href, depth, missing}` |
| JS → Kotlin | `relocate` | `sessionId`, `cfi`, `fraction`, `sectionIndex`, `tocHref` |
| JS → Kotlin | `painted` | `sessionId` (two animation frames after `relocate`) |
| JS → Kotlin | `tap` | `sessionId`, `x`, `y` (fractions of the viewport; links and selections excluded) |
| JS → Kotlin | `error` | `sessionId` (or null), `kind` (`open_failed` \| `nothing_readable`), `message` |

`opened` is sent after the package is parsed and **before** the first page is displayed, so Kotlin records the
book before the first `relocate` arrives. A reopen with the same `sessionId` as the last closed book reuses the
parsed book (warm, P-016).

- [ ] **Step 1: Fixtures**

```json
// bridge/fixtures/open.json
{"v":1,"type":"open","sessionId":"s-1","position":"epubcfi(/6/4!/4/2/1:0)","texts":{"missingPart":"This part of the book could not be shown."}}
```
```json
// bridge/fixtures/close.json   (next.json and prev.json: same shape with type "next" / "prev")
{"v":1,"type":"close"}
```
```json
// bridge/fixtures/goTo.json
{"v":1,"type":"goTo","target":"OEBPS/c2.xhtml"}
```
```json
// bridge/fixtures/opened.json
{"v":1,"type":"opened","sessionId":"s-1","identifier":"urn:uuid:1","title":"Smoke Book","authors":["Test Author"],"dir":"ltr","fixedLayout":false,"sections":3,"toc":[{"label":"Chapter 1","href":"OEBPS/c1.xhtml","depth":0,"missing":false},{"label":"Missing chapter","href":"OEBPS/gone.xhtml","depth":0,"missing":true}]}
```
```json
// bridge/fixtures/relocate.json
{"v":1,"type":"relocate","sessionId":"s-1","cfi":"epubcfi(/6/4!/4/2/1:0)","fraction":0.12,"sectionIndex":1,"tocHref":"OEBPS/c1.xhtml"}
```
```json
// bridge/fixtures/painted.json
{"v":1,"type":"painted","sessionId":"s-1"}
```
```json
// bridge/fixtures/tap.json
{"v":1,"type":"tap","sessionId":"s-1","x":0.5,"y":0.4}
```
```json
// bridge/fixtures/error.json
{"v":1,"type":"error","sessionId":"s-1","kind":"open_failed","message":"Failed to load container file"}
```

- [ ] **Step 2: Failing TS tests**

```ts
// reader-web/test/protocol.test.ts
import { describe, expect, it, vi } from 'vitest'
import { readFileSync } from 'node:fs'
import { Bridge } from '../src/bridge/bridge'
import type { ToWeb } from '../src/bridge/protocol'

const fixture = (n: string) => readFileSync(new URL(`../../bridge/fixtures/${n}.json`, import.meta.url), 'utf8').trim()

describe('protocol v1', () => {
  it.each(['open', 'close', 'next', 'prev', 'goTo'])('dispatches %s to handlers unchanged', name => {
    const seen: ToWeb[] = []
    const b = new Bridge(vi.fn())
    b.onMessage(m => seen.push(m))
    b.receive(fixture(name))
    expect(seen).toEqual([JSON.parse(fixture(name))])
  })
  it('still ignores unknown types', () => {
    const h = vi.fn(); const b = new Bridge(vi.fn()); b.onMessage(h)
    b.receive(fixture('unknown')); expect(h).not.toHaveBeenCalled()
  })
})
```

```ts
// reader-web/test/loader.test.ts
import { describe, expect, it, vi } from 'vitest'
import { makeLoader } from '../src/reader/loader'

const entries = [{ name: 'OEBPS/content.opf', size: 10 }, { name: 'OEBPS/Text/Livres Été.xhtml', size: 5 }]
const fakeFetch = vi.fn(async (url: string) => {
  if (url.endsWith('/entries.json')) return new Response(JSON.stringify(entries))
  if (url === '/book/s-1/OEBPS/Text/Livres%20%C3%89t%C3%A9.xhtml') return new Response('<html/>')
  return new Response('', { status: 404 })
})

describe('loader', () => {
  it('lists entries in the shape foliate expects and encodes URL segments', async () => {
    const l = await makeLoader('/book/s-1', 'Missing part', fakeFetch as unknown as typeof fetch)
    expect(l.entries.map(e => e.filename)).toEqual(entries.map(e => e.name))
    expect(await l.loadText('OEBPS/Text/Livres Été.xhtml')).toBe('<html/>')
  })
  it('serves a placeholder for a missing content document (F-084) and null for other missing entries', async () => {
    const l = await makeLoader('/book/s-1', 'Missing <part>', fakeFetch as unknown as typeof fetch)
    const doc = await l.loadText('OEBPS/gone.xhtml')
    expect(doc).toContain('Missing &lt;part&gt;'); expect(doc).toContain('OEBPS/gone.xhtml')
    expect(await l.loadBlob('OEBPS/gone.png')).toBeNull()
    expect(l.has('OEBPS/gone.xhtml')).toBe(false)
  })
})
```

```ts
// reader-web/test/toc.test.ts
import { describe, expect, it } from 'vitest'
import { flattenToc } from '../src/reader/toc'

const book = {
  toc: [
    { label: ' Part 1 ', href: 'p1.xhtml', subitems: [{ label: 'Chapter 1', href: 'c1.xhtml#a' }] },
    { label: 'Gone', href: 'gone.xhtml' },
    { label: 'Not in spine', href: 'extra.xhtml' },
  ],
  sections: [{ id: 'p1.xhtml' }, { id: 'c1.xhtml' }, { id: 'gone.xhtml' }],
  resolveHref(href: string) {
    const i = this.sections.findIndex(s => s.id === href.split('#')[0])
    return i < 0 ? null : { index: i }
  },
}
const has = (n: string) => n !== 'gone.xhtml'

describe('flattenToc', () => {
  it('flattens with depth, trims labels and flags missing targets (R-001)', () => {
    expect(flattenToc(book, has)).toEqual([
      { label: 'Part 1', href: 'p1.xhtml', depth: 0, missing: false },
      { label: 'Chapter 1', href: 'c1.xhtml#a', depth: 1, missing: false },
      { label: 'Gone', href: 'gone.xhtml', depth: 0, missing: true },          // in spine, file absent
      { label: 'Not in spine', href: 'extra.xhtml', depth: 0, missing: true }, // no section
    ])
  })
})
```
Run: `cd reader-web && npx vitest run` → Expected: FAIL (modules missing; `open` etc. ignored by the v0 bridge).

- [ ] **Step 3: Protocol and bridge**

```ts
// reader-web/src/bridge/protocol.ts
export const PROTOCOL_VERSION = 1

export type TocItem = { label: string; href: string; depth: number; missing: boolean }

export type ToWeb =
  | { v: 1; type: 'ping'; id: number }
  | { v: 1; type: 'open'; sessionId: string; position: string | null; texts: { missingPart: string } }
  | { v: 1; type: 'close' }
  | { v: 1; type: 'next' }
  | { v: 1; type: 'prev' }
  | { v: 1; type: 'goTo'; target: string }

export const TO_WEB_TYPES: ReadonlyArray<ToWeb['type']> = ['ping', 'open', 'close', 'next', 'prev', 'goTo']

export type FromWeb =
  | { v: 1; type: 'ready'; engine: string }
  | { v: 1; type: 'pong'; id: number }
  | { v: 1; type: 'error'; sessionId: string | null; kind: string; message: string }
  | { v: 1; type: 'opened'; sessionId: string; identifier: string | null; title: string; authors: string[];
      dir: 'ltr' | 'rtl'; fixedLayout: boolean; sections: number; toc: TocItem[] }
  | { v: 1; type: 'relocate'; sessionId: string; cfi: string; fraction: number; sectionIndex: number; tocHref: string | null }
  | { v: 1; type: 'painted'; sessionId: string }
  | { v: 1; type: 'tap'; sessionId: string; x: number; y: number }
```

In `bridge.ts`, replace the body of `receive` after parsing with:
```ts
    if (!TO_WEB_TYPES.includes(m.type as ToWeb['type'])) { console.warn('bridge: ignoring unknown message', m.type); return }
    if (m.type === 'ping') { this.send({ v: 1, type: 'pong', id: Number(m.id) }); return }
    for (const h of this.handlers) h(m as ToWeb)
```
(import `TO_WEB_TYPES` from `./protocol`).

- [ ] **Step 4: Loader and TOC**

```ts
// reader-web/src/reader/loader.ts
/** foliate-js `EPUB` loader over the Kotlin content server (/book/<sessionId>/…), as in prototypes/foliate host.js. */
export type Loader = Awaited<ReturnType<typeof makeLoader>>

const DOC = /\.(xhtml|xht|html|htm)$/i
const esc = (s: string) => s.replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]!)

/** F-084: shown in place of a content document whose file is missing from the archive. */
export const placeholderDoc = (text: string, path: string) =>
  `<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title></title></head>` +
  `<body><p>${esc(text)}</p><p><small>${esc(path)}</small></p></body></html>`

export async function makeLoader(base: string, missingPart: string, fetchFn: typeof fetch = fetch) {
  const res = await fetchFn(`${base}/entries.json`)
  if (!res.ok) throw new Error(`entries.json: HTTP ${res.status}`)
  const list: { name: string; size: number }[] = await res.json()
  const sizes = new Map(list.map(e => [e.name, e.size]))
  const url = (name: string) => `${base}/${name.split('/').map(encodeURIComponent).join('/')}`
  const get = (name: string) => sizes.has(name) ? fetchFn(url(name)).then(r => (r.ok ? r : null)) : Promise.resolve(null)
  return {
    entries: list.map(e => ({ filename: e.name, size: e.size })),
    has: (name: string) => sizes.has(name),
    getSize: (name: string) => sizes.get(name) ?? 0,
    loadText: async (name: string): Promise<string | null> => {
      const r = await get(name)
      if (r) return r.text()
      return DOC.test(name) ? placeholderDoc(missingPart, name) : null
    },
    loadBlob: async (name: string, type?: string): Promise<Blob | null> => {
      const r = await get(name)
      if (r) { const b = await r.blob(); return type && b.type !== type ? new Blob([b], { type }) : b }
      return DOC.test(name) ? new Blob([placeholderDoc(missingPart, name)], { type: 'application/xhtml+xml' }) : null
    },
  }
}
```

```ts
// reader-web/src/reader/toc.ts
import type { TocItem } from '../bridge/protocol'

type RawItem = { label?: string; href?: string; subitems?: RawItem[] }
type BookLike = { toc?: RawItem[]; sections: { id?: unknown }[]; resolveHref(href: string): { index: number } | null }

/** R-001: a TOC target is missing if it resolves to no spine section, or the section's file is not in the zip. */
function isMissing(book: BookLike, href: string, has: (name: string) => boolean): boolean {
  if (!href) return true
  let index: number | undefined
  try { index = book.resolveHref(href)?.index } catch { return true }
  if (index == null || index < 0) return true
  const id = book.sections[index]?.id // foliate-js: a section's id is its resource path
  return typeof id === 'string' && !has(id)
}

export function flattenToc(book: BookLike, has: (name: string) => boolean): TocItem[] {
  const out: TocItem[] = []
  const walk = (items: RawItem[] | undefined, depth: number) => {
    for (const i of items ?? []) {
      const href = i.href ?? ''
      out.push({ label: (i.label ?? '').trim(), href, depth, missing: isMissing(book, href, has) })
      walk(i.subitems, depth + 1)
    }
  }
  walk(book.toc, 0)
  return out
}
```
Run: `npx vitest run` → Expected: protocol, loader and toc tests PASS.

- [ ] **Step 5: The reader**

```ts
// reader-web/src/reader/reader.ts
import '../../vendor/foliate-js/view.js'
import { EPUB } from '../../vendor/foliate-js/epub.js'
import type { Bridge } from '../bridge/bridge'
import { makeLoader } from './loader'
import { flattenToc } from './toc'

/* foliate-js is untyped JS; keep `any` at this boundary only. */
type Book = any
type Warm = { sessionId: string; book: Book; has: (n: string) => boolean }

/** foliate metadata values may be strings, language maps or contributor objects. */
const text = (x: unknown): string =>
  typeof x === 'string' ? x
    : x && typeof x === 'object' ? ('name' in x ? text((x as { name: unknown }).name) : text(Object.values(x)[0])) : ''

export class Reader {
  private view: any = null
  private book: Book = null
  private has: ((n: string) => boolean) | null = null
  private sessionId: string | null = null
  private warm: Warm | null = null

  constructor(private bridge: Bridge) {
    bridge.onMessage(m => {
      switch (m.type) {
        case 'open': void this.open(m.sessionId, m.position, m.texts.missingPart); break
        case 'close': this.close(); break
        case 'next': void this.view?.next(); break
        case 'prev': void this.view?.prev(); break
        case 'goTo': this.view?.goTo(m.target)?.catch?.((e: unknown) => console.warn('goTo failed', e)); break
      }
    })
  }

  private async open(sessionId: string, position: string | null, missingPart: string) {
    this.close()
    this.sessionId = sessionId
    const stale = () => this.sessionId !== sessionId
    try {
      if (this.warm?.sessionId === sessionId) {
        this.book = this.warm.book; this.has = this.warm.has
      } else {
        const loader = await makeLoader(`/book/${encodeURIComponent(sessionId)}`, missingPart)
        this.book = await new EPUB(loader).init()
        this.has = loader.has
      }
      this.warm = null
      if (stale()) return
      const book = this.book
      if (!book.sections?.length) {
        this.bridge.send({ v: 1, type: 'error', sessionId, kind: 'nothing_readable', message: 'The book has no readable sections.' })
        return
      }
      this.bridge.send({
        v: 1, type: 'opened', sessionId,
        identifier: book.metadata?.identifier || null,
        title: text(book.metadata?.title),
        authors: [book.metadata?.author ?? []].flat().map(text).filter(Boolean),
        dir: book.dir === 'rtl' ? 'rtl' : 'ltr',
        fixedLayout: book.rendition?.layout === 'pre-paginated',
        sections: book.sections.length,
        toc: flattenToc(book, this.has!),
      })
      const view = document.createElement('foliate-view') as any
      this.view = view
      document.body.append(view)
      view.addEventListener('relocate', (e: CustomEvent) => this.onRelocate(sessionId, e.detail))
      view.addEventListener('load', (e: CustomEvent) => this.onLoad(sessionId, e.detail.doc))
      view.addEventListener('external-link', (e: Event) => e.preventDefault()) // F-044 arrives in M6
      await view.open(book)
      if (stale()) return
      const r = view.renderer
      r.setAttribute('flow', 'paginated')
      r.setAttribute('animated', '')
      r.setAttribute('margin', '24px')
      r.setAttribute('gap', '6%')
      r.setAttribute('max-column-count', '1')
      // A position that no longer resolves (e.g. the file changed) falls back to the start (C-009 refinement: M3).
      try { await view.init({ lastLocation: position }) } catch { if (!stale()) await view.init({}) }
    } catch (e) {
      if (!stale()) this.bridge.send({ v: 1, type: 'error', sessionId, kind: 'open_failed', message: String((e as Error)?.message ?? e) })
    }
  }

  private onRelocate(sessionId: string, d: any) {
    if (this.sessionId !== sessionId) return
    this.bridge.send({
      v: 1, type: 'relocate', sessionId, cfi: d.cfi ?? '', fraction: d.fraction ?? 0,
      sectionIndex: d.section?.current ?? 0, tocHref: d.tocItem?.href ?? null,
    })
    requestAnimationFrame(() => requestAnimationFrame(() => {
      if (this.sessionId === sessionId) this.bridge.send({ v: 1, type: 'painted', sessionId })
    }))
  }

  /** Center-tap detection for the chrome (F-033). Swipes are handled by foliate's paginator itself. */
  private onLoad(sessionId: string, doc: Document) {
    doc.addEventListener('click', (e: MouseEvent) => {
      if ((e.target as Element | null)?.closest?.('a[href]')) return
      if (doc.getSelection && !doc.getSelection()!.isCollapsed) return
      const frame = doc.defaultView?.frameElement as HTMLElement | null
      const box = frame?.getBoundingClientRect()
      this.bridge.send({
        v: 1, type: 'tap', sessionId,
        x: ((box?.left ?? 0) + e.clientX) / window.innerWidth,
        y: ((box?.top ?? 0) + e.clientY) / window.innerHeight,
      })
    })
  }

  /** Releases the view (section frames) but keeps the parsed book for a same-session reopen (P-016). */
  private close() {
    if (this.view) { this.view.close?.(); this.view.remove() }
    if (this.book && this.sessionId && this.has) this.warm = { sessionId: this.sessionId, book: this.book, has: this.has }
    this.view = null; this.book = null; this.has = null; this.sessionId = null
  }
}
```

```ts
// reader-web/src/main.ts
import { Bridge } from './bridge/bridge'
import { Reader } from './reader/reader'

declare global {
  interface Window {
    ReaderBridge?: { postMessage(s: string): void }
    __reader: { receive(json: string): void }
  }
}

const bridge = new Bridge(json => window.ReaderBridge?.postMessage(json))
new Reader(bridge) // imports foliate-js view.js: the module graph is warm before any book is opened
window.__reader = { receive: json => bridge.receive(json) }
bridge.send({ v: 1, type: 'ready', engine: 'foliate-js@78914ae' })
```
In `index.html` add to the `<style>`: `foliate-view { display: block; position: absolute; inset: 0; }`.
Run: `npx vitest run && npm run build` → Expected: PASS; build succeeds (`tsc --noEmit` clean).

- [ ] **Step 6: Kotlin protocol v1 subset with fixture tests**

Replace `Protocol.kt`'s message types (keep `BridgeJson`, `decodeFromWeb`, `encodeToWeb`):
```kotlin
@Serializable sealed interface ToWeb {
    @Serializable @SerialName("ping") data class Ping(val v: Int = 1, val id: Int) : ToWeb
    @Serializable @SerialName("open") data class Open(val v: Int = 1, val sessionId: String, val position: String? = null, val texts: WebTexts) : ToWeb
    @Serializable @SerialName("close") data class Close(val v: Int = 1) : ToWeb
    @Serializable @SerialName("next") data class Next(val v: Int = 1) : ToWeb
    @Serializable @SerialName("prev") data class Prev(val v: Int = 1) : ToWeb
    @Serializable @SerialName("goTo") data class GoTo(val v: Int = 1, val target: String) : ToWeb
}

@Serializable data class WebTexts(val missingPart: String)
@Serializable data class TocItem(val label: String, val href: String, val depth: Int, val missing: Boolean = false)

@Serializable sealed interface FromWeb {
    @Serializable @SerialName("ready") data class Ready(val v: Int = 1, val engine: String) : FromWeb
    @Serializable @SerialName("pong") data class Pong(val v: Int = 1, val id: Int) : FromWeb
    @Serializable @SerialName("error") data class Error(val v: Int = 1, val sessionId: String? = null, val kind: String, val message: String) : FromWeb
    @Serializable @SerialName("opened") data class Opened(
        val v: Int = 1, val sessionId: String, val identifier: String? = null, val title: String, val authors: List<String>,
        val dir: String, val fixedLayout: Boolean, val sections: Int, val toc: List<TocItem>,
    ) : FromWeb
    @Serializable @SerialName("relocate") data class Relocate(
        val v: Int = 1, val sessionId: String, val cfi: String, val fraction: Double, val sectionIndex: Int, val tocHref: String? = null,
    ) : FromWeb
    @Serializable @SerialName("painted") data class Painted(val v: Int = 1, val sessionId: String) : FromWeb
    @Serializable @SerialName("tap") data class Tap(val v: Int = 1, val sessionId: String, val x: Double, val y: Double) : FromWeb
}
```
Add to `ProtocolFixtureTest`:
```kotlin
    @Test fun every_from_web_fixture_decodes() {
        for (n in listOf("opened", "relocate", "painted", "tap", "error")) assertNotNull(decodeFromWeb(fixture(n)), n)
        val o = decodeFromWeb(fixture("opened")) as FromWeb.Opened
        assertEquals(listOf(false, true), o.toc.map { it.missing })
    }

    @Test fun every_to_web_message_encodes_like_its_fixture() {
        val msgs = mapOf(
            "open" to ToWeb.Open(sessionId = "s-1", position = "epubcfi(/6/4!/4/2/1:0)", texts = WebTexts("This part of the book could not be shown.")),
            "close" to ToWeb.Close(), "next" to ToWeb.Next(), "prev" to ToWeb.Prev(), "goTo" to ToWeb.GoTo(target = "OEBPS/c2.xhtml"),
        )
        for ((n, m) in msgs) assertEquals(tree(fixture(n)), tree(encodeToWeb(m)), n)
    }
```
(imports `kotlin.test.assertNotNull`.) Run: `./gradlew :ui:jvmTest` → Expected: PASS. Then
`./gradlew :app:compilePlayDebugKotlin` must still compile: `ReaderEngine` treats `FromWeb.Error` as before.

- [ ] **Step 7: Protocol doc and commit**

Update `docs/bridge-protocol.md` with the table above and the ordering rule for `opened`.

```bash
git add reader-web bridge ui/src docs/bridge-protocol.md
git commit -m "reader-web: bridge v1 subset, content-server loader, TOC with missing targets, warm reopen

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2.5: Reader — engine behind the UI, controller, chrome, Back, resume

**Files:**
- Create: `ui/src/commonMain/kotlin/app/epubreader/reader/{ReaderController.kt, ReaderScreen.kt}`, `ui/src/commonMain/kotlin/app/epubreader/ui/{Navigator.kt, Copy.kt}`
- Modify: `ui/src/commonMain/kotlin/app/epubreader/ui/App.kt`
- Test: `ui/src/jvmTest/kotlin/app/epubreader/reader/ReaderControllerTest.kt`
- Modify: `app/src/main/kotlin/app/epubreader/reader/ReaderEngine.kt` (book path handler, `ReaderPort`, `created` state)
- Create: `app/src/main/kotlin/app/epubreader/{AppGraph.kt, reader/ReaderSurface.kt}`
- Modify: `app/src/main/kotlin/app/epubreader/{EpubReaderApp.kt, MainActivity.kt}`, `app/src/androidTest/kotlin/app/epubreader/HostPageTest.kt` (constructor)
- Test: `app/src/androidTest/kotlin/app/epubreader/reader/{ReaderFlowTest.kt, ReaderTouchTest.kt}`

**Interfaces:**
- Produces (ui): `class ReaderController(reader: ReaderPort, opener: BookOpenerPort, library: LibraryRepository, positions: PositionWriter, scope: CoroutineScope, texts: WebTexts, probe: EventProbe = EventProbe.None)`
  with `val state: StateFlow<ReaderUiState>`, `fun open(ref: SourceRef)`, `fun close()`, `fun backInside(): Boolean`,
  `fun toggleChrome()`, `fun showToc()`, `fun hideToc()`, `fun onTocSelected(item: TocItem)`, `fun dismissNotice()`;
  `class Navigator { val top: Route; fun push(r: Route); fun pop(): Boolean }`, `sealed interface Route { Home; Reader }`.
- Produces (app): `ReaderEngine(context, server) : ReaderPort` with `val created: StateFlow<Boolean>`;
  `MainActivity.openBook(ref: SourceRef)`; `EpubReaderApp.graph: AppGraph`.

- [ ] **Step 1: Write the failing controller tests (fakes, no Android)**

```kotlin
// ui/src/jvmTest/kotlin/app/epubreader/reader/ReaderControllerTest.kt
package app.epubreader.reader

import app.epubreader.core.data.HybridClock
import app.epubreader.core.data.LibraryRepository
import app.epubreader.core.data.PositionWriter
import app.epubreader.core.data.SourceRef
import app.epubreader.core.data.buildDatabase
import app.epubreader.core.data.jvmDatabaseBuilder
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.reader.bridge.ToWeb
import app.epubreader.reader.bridge.TocItem
import app.epubreader.reader.bridge.WebTexts
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderControllerTest {
    private class FakeReader : ReaderPort {
        val sent = mutableListOf<ToWeb>()
        override val events = MutableSharedFlow<FromWeb>(extraBufferCapacity = 64)
        override val isReady = MutableStateFlow(true)
        override fun send(m: ToWeb) { sent += m }
    }
    private class FakeOpener(var next: OpenResult = OpenResult.Ok("s-1", "d".repeat(64), 100)) : BookOpenerPort {
        val closed = mutableListOf<String>()
        override suspend fun open(ref: SourceRef) = next
        override suspend fun close(sessionId: String) { closed += sessionId }
    }

    private val reader = FakeReader()
    private val opener = FakeOpener()
    private val db = buildDatabase(jvmDatabaseBuilder(Files.createTempDirectory("db").resolve("t.db").toString()))
    private val clock = HybridClock(System::currentTimeMillis)
    private val library = LibraryRepository(db, clock)
    private val ref = SourceRef.File("/books/a.epub")
    private val toc = listOf(TocItem("One", "c1.xhtml", 0), TocItem("Gone", "gone.xhtml", 0, missing = true))
    private fun opened(sid: String = "s-1") = FromWeb.Opened(sessionId = sid, identifier = "urn:x", title = "A", authors = listOf("B"), dir = "ltr", fixedLayout = false, sections = 3, toc = toc)
    // backgroundScope: the controller's event collector runs forever and must not keep runTest waiting.
    private fun TestScope.controller() = ReaderController(
        reader, opener, library, PositionWriter(db, clock, backgroundScope), backgroundScope, WebTexts("missing"),
    )

    @Test fun open_sends_open_and_records_book_on_opened() = runTest {
        val c = controller()
        c.open(ref); advanceUntilIdle()
        assertEquals(ToWeb.Open(sessionId = "s-1", position = null, texts = WebTexts("missing")), reader.sent.single())
        assertTrue(c.state.value.loading)
        reader.events.emit(opened()); advanceUntilIdle()
        assertTrue(library.knownBookId(ref) != null)
        assertEquals(toc, c.state.value.toc)
        reader.events.emit(FromWeb.Painted(sessionId = "s-1")); advanceUntilIdle()
        assertFalse(c.state.value.loading)
    }

    @Test fun reopen_sends_saved_position() = runTest {
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        reader.events.emit(FromWeb.Relocate(sessionId = "s-1", cfi = "epubcfi(/6/6!/4/2)", fraction = 0.3, sectionIndex = 2)); advanceUntilIdle()
        c.close(); advanceUntilIdle()
        reader.sent.clear()
        c.open(ref); advanceUntilIdle()
        assertEquals("epubcfi(/6/6!/4/2)", (reader.sent.single() as ToWeb.Open).position)
    }

    @Test fun events_from_previous_session_are_ignored() = runTest {
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        val bookId = library.knownBookId(ref)!!
        reader.events.emit(FromWeb.Relocate(sessionId = "old", cfi = "epubcfi(/6/99!)", fraction = 0.9, sectionIndex = 9)); advanceUntilIdle()
        c.close(); advanceUntilIdle()
        assertNull(library.position(bookId))
    }

    @Test fun close_returns_without_waiting_for_engine() = runTest {
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        c.close() // no advance: state must already be closed and `close` already sent
        assertFalse(c.state.value.open)
        assertEquals(ToWeb.Close(), reader.sent.last())
        advanceUntilIdle()
        assertEquals(listOf("s-1"), opener.closed)
    }

    @Test fun missing_toc_target_shows_notice_and_stays() = runTest {
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        reader.sent.clear()
        c.onTocSelected(toc[1])
        assertEquals(Notice.MissingTarget, c.state.value.notice)
        assertTrue(reader.sent.isEmpty())
        c.onTocSelected(toc[0])
        assertEquals(ToWeb.GoTo(target = "c1.xhtml"), reader.sent.single())
    }

    @Test fun center_tap_toggles_chrome_side_taps_do_not() = runTest {
        val c = controller()
        c.open(ref); advanceUntilIdle(); reader.events.emit(opened()); advanceUntilIdle()
        reader.events.emit(FromWeb.Tap(sessionId = "s-1", x = 0.1, y = 0.5)); advanceUntilIdle()
        assertFalse(c.state.value.chromeVisible)
        reader.events.emit(FromWeb.Tap(sessionId = "s-1", x = 0.5, y = 0.5)); advanceUntilIdle()
        assertTrue(c.state.value.chromeVisible)
    }

    @Test fun open_failure_is_shown() = runTest {
        opener.next = OpenResult.Failed(OpenFailure.NOT_A_ZIP)
        val c = controller()
        c.open(ref); advanceUntilIdle()
        assertEquals(ReaderError.Open(OpenFailure.NOT_A_ZIP), c.state.value.error)
        assertTrue(reader.sent.isEmpty())
    }
}
```
The database work runs on Room's own dispatcher while the controller runs on the test scheduler; if
`advanceUntilIdle()` returns before a Room call finishes, use `runTest(UnconfinedTestDispatcher())` and replace
`advanceUntilIdle()` by a small `awaitState { … }` helper (poll `state` with a 2 s real-time timeout via
`withContext(Dispatchers.Default.limitedParallelism(1)) { withTimeout … }`). Keep the assertions unchanged.
Run: `./gradlew :ui:jvmTest` → Expected: compilation FAIL.

- [ ] **Step 2: Controller, navigator, copy**

```kotlin
// ui/src/commonMain/kotlin/app/epubreader/reader/ReaderController.kt
package app.epubreader.reader

import app.epubreader.core.data.LibraryRepository
import app.epubreader.core.data.OpenedBookInfo
import app.epubreader.core.data.PositionWriter
import app.epubreader.core.data.SourceRef
import app.epubreader.core.epub.PositionMap
import app.epubreader.core.epub.meta.BookIdentity
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.reader.bridge.ToWeb
import app.epubreader.reader.bridge.TocItem
import app.epubreader.reader.bridge.WebTexts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ReaderError {
    data class Open(val reason: OpenFailure) : ReaderError
    data class Engine(val kind: String, val message: String) : ReaderError
}

enum class Notice { MissingTarget }

data class ReaderUiState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val title: String = "",
    val toc: List<TocItem> = emptyList(),
    val currentTocHref: String? = null,
    val chromeVisible: Boolean = false,
    val tocVisible: Boolean = false,
    val notice: Notice? = null,
    val error: ReaderError? = null,
    val source: SourceRef? = null,
)

/**
 * Drives one open book at a time: open → `opened` (identity, record, resume) → `relocate` (positions) → close.
 * Confined to [scope] (the main thread in the app). Never blocks: database and file work are suspending calls
 * on their own dispatchers, and close() returns at once (R-005).
 */
class ReaderController(
    private val reader: ReaderPort,
    private val opener: BookOpenerPort,
    private val library: LibraryRepository,
    private val positions: PositionWriter,
    private val scope: CoroutineScope,
    private val texts: WebTexts,
    private val probe: EventProbe = EventProbe.None,
) {
    private class Session(val id: String, val ref: SourceRef, val cdDigest: String, val size: Long, val hadPosition: Boolean) {
        var bookId: String? = null
        var last: PositionWriter.Update? = null
        var painted = false
        val map: PositionMap = PositionMap.Identity // M8: the book's ChunkMap
    }

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state
    private var session: Session? = null
    private var openJob: Job? = null
    private var closing: Job? = null

    init {
        scope.launch { reader.events.collect { on(it) } }
    }

    fun open(ref: SourceRef) {
        close()
        _state.value = ReaderUiState(open = true, loading = true, source = ref)
        val previous = closing
        openJob = scope.launch {
            previous?.join() // the last position of the book just closed is stored before we read positions
            val known = library.knownBookId(ref)
            val saved = known?.let { library.position(it) }
            when (val r = opener.open(ref)) {
                is OpenResult.Failed -> _state.update { it.copy(loading = false, error = ReaderError.Open(r.reason)) }
                is OpenResult.Ok -> try {
                    val s = Session(r.sessionId, ref, r.cdDigest, r.size, hadPosition = saved != null).also { it.bookId = known }
                    reader.isReady.first { it }
                    session = s
                    probe.ev("open_sent", ref.key)
                    reader.send(ToWeb.Open(sessionId = s.id, position = saved?.cfi?.let(s.map::cfiToVirtual), texts = texts))
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { opener.close(r.sessionId) }
                    throw e
                }
            }
        }
    }

    /** Back from the reader (R-005): returns immediately; the engine and the session close in the background. */
    fun close() {
        openJob?.cancel(); openJob = null
        val s = session
        session = null
        _state.value = ReaderUiState()
        if (s == null) return
        reader.send(ToWeb.Close())
        closing = scope.launch {
            s.last?.let { positions.writeNow(it) }
            opener.close(s.id)
        }
    }

    /** Handles Back inside the reader (closes the TOC first). Returns false if Back should leave the reader. */
    fun backInside(): Boolean = if (_state.value.tocVisible) { hideToc(); true } else false

    fun toggleChrome() = _state.update { it.copy(chromeVisible = !it.chromeVisible) }
    fun showToc() { probe.ev("toc_cmd", ""); _state.update { it.copy(tocVisible = true, chromeVisible = false) } }
    fun hideToc() = _state.update { it.copy(tocVisible = false) }
    fun dismissNotice() = _state.update { it.copy(notice = null) }

    /** R-001: a missing target shows a notice and the page stays; otherwise jump (href is the reader's own). */
    fun onTocSelected(item: TocItem) {
        if (item.missing) { _state.update { it.copy(notice = Notice.MissingTarget) }; return }
        _state.update { it.copy(tocVisible = false) }
        reader.send(ToWeb.GoTo(target = item.href))
    }

    private suspend fun on(m: FromWeb) {
        val s = session ?: return
        when (m) {
            is FromWeb.Opened -> if (m.sessionId == s.id) {
                val identity = BookIdentity.of(m.identifier, s.cdDigest)
                val rec = library.recordOpened(OpenedBookInfo(identity, m.title, m.authors, s.ref, s.size))
                s.bookId = rec.bookId
                // Same book reached through a new source (e.g. picked again): resume where it was left.
                if (!s.hadPosition && rec.position != null) reader.send(ToWeb.GoTo(target = s.map.cfiToVirtual(rec.position.cfi)))
                _state.update { it.copy(title = m.title, toc = m.toc) }
            }
            is FromWeb.Relocate -> if (m.sessionId == s.id) {
                probe.ev("relocate", m.cfi)
                s.bookId?.let { id ->
                    val u = PositionWriter.Update(id, s.map.cfiToOriginal(m.cfi), m.fraction, m.sectionIndex)
                    s.last = u
                    positions.submit(u)
                }
                _state.update { it.copy(currentTocHref = m.tocHref) }
            }
            is FromWeb.Painted -> if (m.sessionId == s.id) {
                probe.ev("painted", "")
                if (!s.painted) { s.painted = true; probe.ev("painted_first", ""); _state.update { it.copy(loading = false) } }
            }
            is FromWeb.Tap -> if (m.sessionId == s.id && m.x in 0.33..0.67) toggleChrome()
            is FromWeb.Error -> if (m.sessionId == null || m.sessionId == s.id) {
                _state.update { it.copy(loading = false, error = ReaderError.Engine(m.kind, m.message)) }
            }
            else -> Unit
        }
    }
}
```

```kotlin
// ui/src/commonMain/kotlin/app/epubreader/ui/Navigator.kt
package app.epubreader.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

sealed interface Route {
    data object Home : Route
    data object Reader : Route
}

/** Minimal route stack (master plan: no navigation library). Main thread only. */
class Navigator {
    private var stack by mutableStateOf(listOf<Route>(Route.Home))
    val top: Route get() = stack.last()
    fun push(r: Route) { stack = stack + r }
    fun pop(): Boolean = if (stack.size > 1) { stack = stack.dropLast(1); true } else false
}
```

```kotlin
// ui/src/commonMain/kotlin/app/epubreader/ui/Copy.kt
package app.epubreader.ui

import app.epubreader.reader.OpenFailure

/** All user-facing text (our own wording). Moves to Compose resources when the app is translated. */
object Copy {
    const val appTitle = "Books"
    const val openBook = "Open book"
    const val recentEmpty = "No books yet. Use “Open book” to choose an EPUB file."
    const val back = "Back"
    const val contents = "Contents"
    const val close = "Close"
    const val removeFromRecent = "Remove from list"
    const val missingTarget = "This entry points to a part that is missing from the book."
    const val missingPart = "This part of the book is missing from the file, so it can't be shown. You can keep reading."
    const val engineError = "This book can't be displayed."
    fun openFailure(r: OpenFailure) = when (r) {
        OpenFailure.NO_ACCESS -> "This book can't be opened. The file may have been moved or deleted, or access to it was removed."
        OpenFailure.NOT_A_ZIP -> "This file isn't an EPUB book."
        OpenFailure.DAMAGED -> "This book file is damaged and can't be opened."
    }
}
```
Run: `./gradlew :ui:jvmTest` → Expected: `ReaderControllerTest` 7 PASS.

- [ ] **Step 3: Reader chrome and the app root**

```kotlin
// ui/src/commonMain/kotlin/app/epubreader/reader/ReaderScreen.kt
package app.epubreader.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.epubreader.reader.toc.TocPanel
import app.epubreader.ui.Copy

/**
 * Reader chrome drawn over the WebView (which lies underneath, full size). Nothing here reflows the page.
 * While loading or on error the screen is opaque (P-015: frame + progress at once). Icons and the full control
 * set come with M6 Task 6.1; M2 uses text buttons.
 */
@Composable
fun ReaderScreen(c: ReaderController, onBack: () -> Unit, onForget: (app.epubreader.core.data.SourceRef) -> Unit, probe: EventProbe) {
    val s by c.state.collectAsState()
    LaunchedEffect(Unit) { withFrameNanos { }; probe.ev("reader_frame", "") }
    Box(Modifier.fillMaxSize()) {
        when {
            s.error != null -> Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.Center) {
                    val err = s.error
                    Text(if (err is ReaderError.Open) Copy.openFailure(err.reason) else Copy.engineError, style = MaterialTheme.typography.bodyLarge)
                    if (err is ReaderError.Engine) Text(err.message, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = onBack) { Text(Copy.back) }
                        if (err is ReaderError.Open && err.reason == OpenFailure.NO_ACCESS) s.source?.let { src ->
                            TextButton(onClick = { onForget(src); onBack() }) { Text(Copy.removeFromRecent) }
                        }
                    }
                }
            }
            s.loading -> Surface(Modifier.fillMaxSize()) {
                Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
        if (s.chromeVisible && s.error == null) {
            Surface(Modifier.fillMaxWidth().align(Alignment.TopStart), tonalElevation = 3.dp) {
                Row(Modifier.statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text(Copy.back) }
                    Text(s.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = c::showToc, enabled = s.toc.isNotEmpty()) { Text(Copy.contents) }
                }
            }
        }
        if (s.tocVisible) TocPanel(s.toc, s.currentTocHref, onSelect = c::onTocSelected, onDismiss = c::hideToc, probe = probe)
        s.notice?.let {
            LaunchedEffect(it) { kotlinx.coroutines.delay(3_000); c.dismissNotice() }
            Snackbar(Modifier.align(Alignment.BottomCenter).padding(16.dp)) { Text(Copy.missingTarget) }
        }
    }
}
```
(`TocPanel` is written in Task 2.7; until then stub it as an empty composable with the same signature so this
compiles.)

```kotlin
// ui/src/commonMain/kotlin/app/epubreader/ui/App.kt
package app.epubreader.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.epubreader.core.data.SourceRef
import app.epubreader.home.HomeScreen
import app.epubreader.home.HomeState
import app.epubreader.reader.EventProbe
import app.epubreader.reader.ReaderController
import app.epubreader.reader.ReaderScreen
import app.epubreader.ui.theme.AppTheme

/**
 * Root. [readerSurface] (the WebView) is drawn first and stays underneath; the home screen is opaque over it,
 * the reader route draws chrome only.
 */
@Composable
fun App(
    dark: Boolean,
    nav: Navigator,
    home: HomeState,
    reader: ReaderController,
    probe: EventProbe,
    readerSurface: @Composable () -> Unit,
    onOpenPicker: () -> Unit,
    onOpen: (SourceRef) -> Unit,
    onBack: () -> Unit,
) = AppTheme(dark) {
    Box(Modifier.fillMaxSize()) {
        readerSurface()
        when (nav.top) {
            Route.Home -> HomeScreen(home, onOpenPicker = onOpenPicker, onOpen = onOpen, probe = probe)
            Route.Reader -> ReaderScreen(reader, onBack = onBack, onForget = home::forget, probe = probe)
        }
    }
}
```
(`HomeScreen`/`HomeState` are written in Task 2.6; stub them the same way until then.)

- [ ] **Step 4: Engine with the book handler, surface, graph, activity**

In `ReaderEngine.kt` (M1): implement `ReaderPort`, take the server, expose `created`:
```kotlin
class ReaderEngine(private val context: Context, private val server: BookContentServer) : ReaderPort {
    // … M1 fields; `override val events`, `override val isReady` …
    private val _created = MutableStateFlow(false)
    /** True once the WebView exists; MainActivity attaches the surface only then (never during the first frame). */
    val created: StateFlow<Boolean> = _created

    fun prewarm() { webView; _created.value = true }
    override fun send(m: ToWeb) { /* as M1 */ }
```
and in `create()` add the handler before `build()`:
```kotlin
            .addPathHandler("/book/", BookPathHandler(server))
```
```kotlin
private class BookPathHandler(private val server: BookContentServer) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse {
        val r = server.serve(path)
        val body = if (r.status == 200) r.body() else java.io.ByteArrayInputStream(ByteArray(0))
        return WebResourceResponse(r.mime, null, r.status, r.reason, mapOf("Cache-Control" to "no-store"), body)
    }
}
```
Update `HostPageTest` and `SmokeTest` construction to `ReaderEngine(ctx, BookContentServer())` where they create
one directly.

```kotlin
// app/src/main/kotlin/app/epubreader/reader/ReaderSurface.kt
package app.epubreader.reader

import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** The reader WebView, full size, attached once it exists (after the first frame, P-001). */
@Composable
fun ReaderSurface(engine: ReaderEngine) {
    val created by engine.created.collectAsState()
    if (!created) return
    AndroidView(
        factory = { engine.webView.also { (it.parent as? ViewGroup)?.removeView(it) } },
        modifier = Modifier.fillMaxSize(),
    )
}
```

```kotlin
// app/src/main/kotlin/app/epubreader/AppGraph.kt
package app.epubreader

import android.app.Application
import app.epubreader.core.data.HybridClock
import app.epubreader.core.data.LibraryRepository
import app.epubreader.core.data.PositionWriter
import app.epubreader.core.data.androidDatabaseBuilder
import app.epubreader.core.data.buildDatabase
import app.epubreader.reader.BookContentServer
import app.epubreader.reader.BookOpener
import app.epubreader.reader.BookSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process-wide objects (manual DI). The database opens lazily on first query, off the main thread. */
class AppGraph(app: Application) {
    val db = buildDatabase(androidDatabaseBuilder(app))
    val clock = HybridClock(System::currentTimeMillis)
    val library = LibraryRepository(db, clock)
    val positions = PositionWriter(db, clock, CoroutineScope(SupervisorJob() + Dispatchers.IO))
    val server = BookContentServer()
    val opener = BookOpener(BookSources(app), server)
}
```
In `EpubReaderApp`: `val graph by lazy { AppGraph(this) }`.

```kotlin
// app/src/main/kotlin/app/epubreader/MainActivity.kt
package app.epubreader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.lifecycleScope
import app.epubreader.bench.Probe
import app.epubreader.core.data.SourceRef
import app.epubreader.home.HomeState
import app.epubreader.reader.EventProbe
import app.epubreader.reader.ReaderController
import app.epubreader.reader.ReaderEngine
import app.epubreader.reader.ReaderSurface
import app.epubreader.reader.bridge.WebTexts
import app.epubreader.ui.App
import app.epubreader.ui.Copy
import app.epubreader.ui.Navigator
import app.epubreader.ui.Route

class MainActivity : ComponentActivity() {
    lateinit var reader: ReaderEngine
        private set
    lateinit var controller: ReaderController
        private set
    val nav = Navigator()
    private val probe = EventProbe { n, x -> Probe.ev(n, x) }

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        // Keep access across restarts for the recent list; some providers do not allow it (then: this session only).
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        openBook(SourceRef.Content(uri.toString()))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as EpubReaderApp).graph
        reader = ReaderEngine(this, graph.server)
        controller = ReaderController(reader, graph.opener, graph.library, graph.positions, lifecycleScope, WebTexts(Copy.missingPart), probe)
        val home = HomeState(graph.library, lifecycleScope)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!goBack()) { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
        setContent {
            App(
                dark = isSystemInDarkTheme(), nav = nav, home = home, reader = controller, probe = probe,
                readerSurface = { ReaderSurface(reader) },
                onOpenPicker = { pick.launch(arrayOf("application/epub+zip", "application/octet-stream")) },
                onOpen = ::openBook, onBack = { goBack() },
            )
        }
        // Pre-warm after the first frame so it never delays the home screen (P-001).
        window.decorView.post {
            Probe.ev("first_frame")
            window.decorView.post { reader.prewarm() }
        }
    }

    fun openBook(ref: SourceRef) {
        Probe.ev("tap_book", ref.key)
        controller.open(ref)
        if (nav.top != Route.Reader) nav.push(Route.Reader)
    }

    /** Returns false when Back should leave the app. Leaving the reader never waits for the engine (R-005). */
    fun goBack(): Boolean = when (nav.top) {
        Route.Reader -> { if (!controller.backInside()) { controller.close(); nav.pop(); Probe.ev("home_shown") }; true }
        Route.Home -> false
    }

    override fun onDestroy() {
        controller.close()
        reader.destroy()
        super.onDestroy()
    }
}
```
Add `implementation("androidx.lifecycle:lifecycle-runtime-ktx:<version>")` (catalog `lifecycle-runtime`) for
`lifecycleScope` if `activity-compose` does not bring it.

- [ ] **Step 5: Instrumented flow and touch tests**

```kotlin
// app/src/androidTest/kotlin/app/epubreader/reader/ReaderFlowTest.kt
package app.epubreader.reader

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epubreader.EpubReaderApp
import app.epubreader.MainActivity
import app.epubreader.TestBooks
import app.epubreader.core.data.SourceRef
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.reader.bridge.ToWeb
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/** F-030 in place, F-037 resume, R-009 position written. Uses a generated book: no corpus, no permission. */
@RunWith(AndroidJUnit4::class)
class ReaderFlowTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun open_turn_back_reopen_resumes_same_cfi() = runBlocking {
        val f = TestBooks.write(ctx, "flow.epub", TestBooks.epub(id = "urn:uuid:flow-${System.nanoTime()}", chapters = 3, paragraphs = 80))
        val ref = SourceRef.File(f.path)
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            lateinit var a: MainActivity
            sc.onActivity { a = it }
            withTimeout(15_000) { a.reader.isReady.first { it } }
            val relocates = Channel<FromWeb.Relocate>(Channel.UNLIMITED)
            val job = launch(start = CoroutineStart.UNDISPATCHED) { a.reader.events.filterIsInstance<FromWeb.Relocate>().collect { relocates.send(it) } }

            sc.onActivity { it.openBook(ref) }
            val first = withTimeout(10_000) { relocates.receive() }
            var last = first
            repeat(5) { sc.onActivity { it.reader.send(ToWeb.Next()) }; last = withTimeout(5_000) { relocates.receive() } }
            assertNotEquals(first.cfi, last.cfi)

            sc.onActivity { it.goBack() }
            val graph = (a.application as EpubReaderApp).graph
            val bookId = graph.library.knownBookId(ref)!!
            withTimeout(3_000) { while (graph.library.position(bookId)?.cfi != last.cfi) delay(20) } // R-009

            sc.onActivity { it.openBook(ref) } // warm reopen (same session id)
            assertEquals(last.cfi, withTimeout(10_000) { relocates.receive() }.cfi)
            job.cancel()
        }
    }
}
```

```kotlin
// app/src/androidTest/kotlin/app/epubreader/reader/ReaderTouchTest.kt
package app.epubreader.reader

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.epubreader.MainActivity
import app.epubreader.TestBooks
import app.epubreader.core.data.SourceRef
import app.epubreader.reader.bridge.FromWeb
import app.epubreader.ui.Copy
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Review Focus 4: swipes reach the WebView under the Compose chrome; taps on chrome never reach the book. */
@RunWith(AndroidJUnit4::class)
class ReaderTouchTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val dev = UiDevice.getInstance(inst)

    @Test fun swipe_turns_page_and_chrome_buttons_do_not_leak() = runBlocking {
        val f = TestBooks.write(inst.targetContext, "touch.epub", TestBooks.epub(paragraphs = 80))
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            lateinit var a: MainActivity
            sc.onActivity { a = it }
            withTimeout(15_000) { a.reader.isReady.first { it } }
            val firstReloc = async(start = CoroutineStart.UNDISPATCHED) { a.reader.events.filterIsInstance<FromWeb.Relocate>().first() }
            sc.onActivity { it.openBook(SourceRef.File(f.path)) }
            val first = withTimeout(10_000) { firstReloc.await() }
            withTimeout(5_000) { a.controller.state.first { !it.loading } }

            val w = dev.displayWidth; val h = dev.displayHeight
            val turned = async(start = CoroutineStart.UNDISPATCHED) { a.reader.events.filterIsInstance<FromWeb.Relocate>().first() }
            dev.swipe((w * 0.85).toInt(), h / 2, (w * 0.15).toInt(), h / 2, 10)
            assertNotEquals(first.cfi, withTimeout(5_000) { turned.await() }.cfi)

            dev.click(w / 2, h / 2) // center tap → chrome
            assertTrue(dev.wait(Until.hasObject(By.text(Copy.contents)), 3_000))
            val leaked = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(1_500) { a.reader.events.filterIsInstance<FromWeb.Tap>().first() }
            }
            dev.findObject(By.text(Copy.contents)).click()
            assertNull("tap on a chrome button reached the WebView", leaked.await())
            withTimeout(3_000) { a.controller.state.first { it.tocVisible } }
        }
    }
}
```
Run: `./gradlew :app:connectedPlayDebugAndroidTest` → Expected: all instrumented tests PASS (M1's, `BookSourcesTest`,
`ReaderFlowTest`, `ReaderTouchTest`). If the swipe does not turn the page, the WebView is not receiving touches:
check that nothing in the reader route has a pointer-input modifier covering the page while the chrome is hidden.

- [ ] **Step 6: Commit**

```bash
git add ui app
git commit -m "reader: in-place open behind the home screen, controller with resume and transactional positions

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2.6: Home screen — open with the system picker, recent books

**Files:**
- Create: `ui/src/commonMain/kotlin/app/epubreader/home/{HomeState.kt, HomeScreen.kt}`
- Test: `ui/src/jvmTest/kotlin/app/epubreader/home/HomeStateTest.kt`
- (Picker wiring is already in `MainActivity`, Task 2.5.)

**Interfaces:**
- Produces: `class HomeState(library: LibraryRepository, scope: CoroutineScope) { val recent: StateFlow<List<RecentBook>>; fun forget(ref: SourceRef) }`;
  `@Composable fun HomeScreen(state: HomeState, onOpenPicker: () -> Unit, onOpen: (SourceRef) -> Unit, probe: EventProbe)`.
- Bench event `EVT row0 y=<px>`: the vertical centre of the first row, logged once per process (the harness taps it).

Scope: this is the M2 stand-in for the library and is replaced by M3 Task 3.8. Only books opened through
the picker (or the bench `openPath` command) are listed. A picked file keeps its read grant across restarts when
the provider allows a persistable grant. Otherwise opening it later fails with `NO_ACCESS`, and the error screen
offers "Remove from list" (Task 2.5).

- [ ] **Step 1: Failing test**

```kotlin
// ui/src/jvmTest/kotlin/app/epubreader/home/HomeStateTest.kt
package app.epubreader.home

import app.epubreader.core.data.HybridClock
import app.epubreader.core.data.LibraryRepository
import app.epubreader.core.data.OpenedBookInfo
import app.epubreader.core.data.SourceRef
import app.epubreader.core.data.buildDatabase
import app.epubreader.core.data.jvmDatabaseBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeStateTest {
    @Test fun recent_lists_opened_books_and_forget_removes_them() = runTest {
        val repo = LibraryRepository(buildDatabase(jvmDatabaseBuilder(Files.createTempDirectory("db").resolve("t.db").toString())), HybridClock(System::currentTimeMillis))
        val home = HomeState(repo, backgroundScope)
        val ref = SourceRef.Content("content://p/1")
        repo.recordOpened(OpenedBookInfo("id-1", "Alice", listOf("Carroll"), ref, 1))
        assertEquals(listOf("Alice"), home.recent.first { it.isNotEmpty() }.map { it.title })
        home.forget(ref)
        home.recent.first { it.isEmpty() }
    }
}
```

- [ ] **Step 2: Implement**

```kotlin
// ui/src/commonMain/kotlin/app/epubreader/home/HomeState.kt
package app.epubreader.home

import app.epubreader.core.data.LibraryRepository
import app.epubreader.core.data.RecentBook
import app.epubreader.core.data.SourceRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeState(private val library: LibraryRepository, private val scope: CoroutineScope) {
    val recent: StateFlow<List<RecentBook>> = library.recent().stateIn(scope, SharingStarted.Eagerly, emptyList())
    fun forget(ref: SourceRef) { scope.launch { library.forget(ref) } }
}
```

```kotlin
// ui/src/commonMain/kotlin/app/epubreader/home/HomeScreen.kt
package app.epubreader.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.epubreader.core.data.SourceRef
import app.epubreader.reader.EventProbe
import app.epubreader.ui.Copy

/** M2 home: "Open book" + recent books. Opaque (Material Surface also blocks touches to the WebView below). */
@Composable
fun HomeScreen(state: HomeState, onOpenPicker: () -> Unit, onOpen: (SourceRef) -> Unit, probe: EventProbe) {
    val books by state.recent.collectAsState()
    val logged = remember { booleanArrayOf(false) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(Copy.appTitle, style = MaterialTheme.typography.headlineSmall)
                Button(onClick = onOpenPicker) { Text(Copy.openBook) }
            }
            if (books.isEmpty()) Text(Copy.recentEmpty, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            LazyColumn {
                itemsIndexed(books, key = { _, b -> b.id }) { i, b ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onOpen(b.source) }.padding(horizontal = 16.dp, vertical = 12.dp)
                            .let { m -> if (i != 0) m else m.onGloballyPositioned { c ->
                                if (!logged[0]) { logged[0] = true; probe.ev("row0", "y=${c.boundsInWindow().center.y.toInt()}") }
                            } },
                    ) {
                        Text(b.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (b.authors.isNotEmpty()) Text(b.authors.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
```
(Window coordinates equal screen coordinates here: the activity is full screen with edge-to-edge drawing.)

- [ ] **Step 3: Run and check on the emulator**

Run: `./gradlew :ui:jvmTest :app:installPlayDebug`. Open the app through `$AD open app.epubreader`, tap
"Open book", choose `Books/gutenberg/alice-epub3-images.epub` (pushed by `bench/push_corpus.sh`) in the system
picker, read a few pages, go back: Alice is in the list. Close and relaunch the app through `$AD`, tap Alice:
the same page shows.
Take `device_screenshot`s of the home list and of a page for the milestone note.

- [ ] **Step 4: Commit** — `ui: home screen with system picker and recent books` (with trailer).

---

### Task 2.7: TOC panel over the reader's TOC

**Files:**
- Create: `ui/src/commonMain/kotlin/app/epubreader/reader/toc/{TocTreeState.kt, TocPanel.kt}` (replace the stub)
- Test: `ui/src/commonTest/kotlin/app/epubreader/reader/toc/TocTreeStateTest.kt`

**Interfaces:**
- Produces: `class TocTreeState(items: List<TocItem>) { fun visibleRows(): List<Int>; fun toggle(i: Int); fun hasChildren(i: Int): Boolean; fun isExpanded(i: Int): Boolean; fun indexOfHref(href: String?): Int?; fun reveal(i: Int) }`;
  `@Composable fun TocPanel(items, currentHref, onSelect, onDismiss, probe)`.
- The tree comes from `opened.toc` (flattened with depth, Task 2.4); built when the panel opens (O(n) over the flattened list, R17), not on every recomposition
  (P-031). Bench events `toc_cmd` (controller) → `toc_shown` (first frame of the panel).

- [ ] **Step 1: Failing tests**

```kotlin
// ui/src/commonTest/kotlin/app/epubreader/reader/toc/TocTreeStateTest.kt
package app.epubreader.reader.toc

import app.epubreader.reader.bridge.TocItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TocTreeStateTest {
    /** Shape of stress-2000-chapters: 20 parts × 100 chapters. */
    private val items = (1..20).flatMap { p ->
        listOf(TocItem("Part $p", "p$p.xhtml", 0)) + (1..100).map { c -> TocItem("Chapter $p.$c", "c$p-$c.xhtml", 1) }
    }

    @Test fun collapsed_by_default_shows_top_level_only() = assertEquals(20, TocTreeState(items).visibleRows().size)

    @Test fun expanding_one_part_shows_its_chapters() {
        val t = TocTreeState(items); t.toggle(0)
        assertEquals(120, t.visibleRows().size)
        t.toggle(0)
        assertEquals(20, t.visibleRows().size)
    }

    @Test fun reveal_current_entry_expands_its_ancestors() {
        val t = TocTreeState(items)
        val i = t.indexOfHref("c18-77.xhtml")!!
        t.reveal(i)
        assertTrue(i in t.visibleRows())
        assertEquals(120, t.visibleRows().size)
    }

    @Test fun irregular_depths_do_not_break_the_tree() {
        val t = TocTreeState(listOf(TocItem("a", "a", 0), TocItem("b", "b", 2), TocItem("c", "c", 1), TocItem("d", "d", 0)))
        assertEquals(listOf(0, 3), t.visibleRows())
        t.toggle(0)
        assertEquals(listOf(0, 1, 2, 3), t.visibleRows())
    }
}
```

- [ ] **Step 2: Implement**

```kotlin
// ui/src/commonMain/kotlin/app/epubreader/reader/toc/TocTreeState.kt
package app.epubreader.reader.toc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.epubreader.reader.bridge.TocItem

/** Expand/collapse over a depth-annotated flat TOC. O(n) to build; rows are recomputed only on toggle. */
class TocTreeState(val items: List<TocItem>) {
    private val parent = IntArray(items.size)
    private val children = BooleanArray(items.size)
    private val byHref = HashMap<String, Int>(items.size * 2)
    private var expanded by mutableStateOf(emptySet<Int>())

    init {
        val stack = ArrayList<Int>()
        items.forEachIndexed { i, it ->
            while (stack.isNotEmpty() && items[stack.last()].depth >= it.depth) stack.removeAt(stack.lastIndex)
            parent[i] = stack.lastOrNull() ?: -1
            if (parent[i] >= 0) children[parent[i]] = true
            stack += i
            byHref.getOrPut(it.href) { i }
        }
    }

    fun hasChildren(i: Int) = children[i]
    fun isExpanded(i: Int) = i in expanded
    fun toggle(i: Int) { expanded = if (i in expanded) expanded - i else expanded + i }
    fun indexOfHref(href: String?): Int? = href?.let { byHref[it] }

    fun reveal(i: Int) {
        var p = parent[i]; val add = HashSet<Int>()
        while (p >= 0) { add += p; p = parent[p] }
        if (add.isNotEmpty()) expanded = expanded + add
    }

    fun visibleRows(): List<Int> {
        val out = ArrayList<Int>()
        val open = expanded
        var hideDeeperThan = Int.MAX_VALUE
        for (i in items.indices) {
            val d = items[i].depth
            if (d > hideDeeperThan) continue
            hideDeeperThan = Int.MAX_VALUE
            out += i
            if (children[i] && i !in open) hideDeeperThan = d
        }
        return out
    }
}
```

```kotlin
// ui/src/commonMain/kotlin/app/epubreader/reader/toc/TocPanel.kt
package app.epubreader.reader.toc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.epubreader.reader.EventProbe
import app.epubreader.reader.bridge.TocItem
import app.epubreader.ui.Copy

@Composable
fun TocPanel(items: List<TocItem>, currentHref: String?, onSelect: (TocItem) -> Unit, onDismiss: () -> Unit, probe: EventProbe) {
    val tree = remember(items) { TocTreeState(items) }
    val current = remember(items, currentHref) { tree.indexOfHref(currentHref)?.also(tree::reveal) }
    val rows = tree.visibleRows()
    val list = rememberLazyListState(initialFirstVisibleItemIndex = current?.let { rows.indexOf(it).coerceAtLeast(0) } ?: 0)
    LaunchedEffect(Unit) { withFrameNanos { }; probe.ev("toc_shown", "") }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Copy.contents, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss) { Text(Copy.close) }
            }
            LazyColumn(state = list) {
                items(rows, key = { it }) { i ->
                    val item = tree.items[i]
                    Row(Modifier.fillMaxWidth().clickable { onSelect(item) }.padding(start = (16 + 20 * item.depth).dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            item.label.ifBlank { "—" }, Modifier.weight(1f).padding(vertical = 12.dp),
                            fontWeight = if (i == current) FontWeight.Bold else null,
                            color = if (item.missing) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
                        )
                        if (tree.hasChildren(i)) TextButton(onClick = { tree.toggle(i) }, Modifier.width(56.dp)) {
                            Text(if (tree.isExpanded(i)) "−" else "+")
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 3: Run tests, check on the emulator, commit**

Run: `./gradlew :ui:allTests` (or `:ui:jvmTest`) → `TocTreeStateTest` 4 PASS. On the emulator open
`stress-2000-chapters` (picker → `Books/generated/`), center tap → Contents: 20 parts, current part expanded and
scrolled into view; open `malformed-missing-files-bad-xml` → the TOC entry to the missing file shows the notice and
the page stays (R-001).
Commit: `ui: TOC panel with expand/collapse, current entry and missing-target notice` (with trailer).

---

### Task 2.8: Smoke test for the slice and the M2 milestone suite

**Files:**
- Modify: `app/src/androidTest/kotlin/app/epubreader/SmokeTest.kt`, `bench/lib.sh`, `bench/README.md`
- Create: `app/src/main/kotlin/app/epubreader/bench/{BenchCommands.kt, BenchMarker.kt}`
- Create: `app/src/benchmark/kotlin/app/epubreader/bench/BenchCommandReceiver.kt`, `app/src/benchmark/AndroidManifest.xml`
- Modify: `app/src/main/kotlin/app/epubreader/MainActivity.kt` (register bench commands and the marker when Probe is on)
- Create: `bench/groups/{open.sh, action.sh, mem.sh}`, `bench/check_resume.sh`
- Create: `docs/milestones/M2.md`

**Interfaces:**
- Bench command broadcast (benchmark build only): `adb shell am broadcast -a app.epubreader.BENCH -n app.epubreader/.bench.BenchCommandReceiver --es cmd <cmd> [--es arg <arg>]`
  with `cmd` ∈ `openPath <path>`, `back`, `next`, `prev`, `toc`, `tocJump <label substring>`. Every command toggles
  a small black marker square in the top-left corner, so the command is the first visible change in a recording
  (the same method as the bake-off's `tools/bench_action.sh`).
- Events used: `tap_book`, `reader_frame`, `painted_first`, `painted`, `relocate <cfi>`, `row0 y=`, `toc_cmd`,
  `toc_shown`, `home_shown`.

- [ ] **Step 1: Extend the smoke test (per build)**

Add to `SmokeTest.kt` (keep the M1 test; imports as in `ReaderFlowTest` plus `app.epubreader.ui.Route`,
`org.junit.Assert.assertTrue`):
```kotlin
    @Test fun opens_a_book_turns_a_page_and_goes_back() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val f = TestBooks.write(ctx, "smoke.epub")
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            lateinit var a: MainActivity
            sc.onActivity { a = it }
            withTimeout(15_000) { a.reader.isReady.first { it } }
            val r1 = async(start = CoroutineStart.UNDISPATCHED) { a.reader.events.filterIsInstance<FromWeb.Relocate>().first() }
            sc.onActivity { it.openBook(SourceRef.File(f.path)) }
            val first = withTimeout(10_000) { r1.await() }
            val r2 = async(start = CoroutineStart.UNDISPATCHED) { a.reader.events.filterIsInstance<FromWeb.Relocate>().first() }
            sc.onActivity { it.reader.send(ToWeb.Next()) }
            assertNotEquals(first.cfi, withTimeout(5_000) { r2.await() }.cfi)
            sc.onActivity { assertTrue(it.goBack()); assertEquals(Route.Home, it.nav.top) }
        }
    }
```
Run: `tools/smoke.sh` → `smoke: OK`. Record the duration in the milestone note. It must stay within a few minutes.
If it grows, move tests out of the smoke class, not the other way round.

- [ ] **Step 2: Bench commands, marker, receiver**

```kotlin
// app/src/main/kotlin/app/epubreader/bench/BenchCommands.kt
package app.epubreader.bench

import java.util.concurrent.ConcurrentHashMap

/** Bench command handlers (registered by MainActivity only when Probe is enabled, i.e. in the benchmark build). */
object BenchCommands {
    private val handlers = ConcurrentHashMap<String, (String) -> Unit>()
    fun register(cmd: String, h: (String) -> Unit) { handlers[cmd] = h }
    fun dispatch(cmd: String, arg: String) {
        BenchMarker.toggle()
        handlers[cmd]?.invoke(arg) ?: Probe.ev("cmd_unknown", cmd)
    }
}
```

```kotlin
// app/src/main/kotlin/app/epubreader/bench/BenchMarker.kt
package app.epubreader.bench

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Visible start marker for screen-recorded bench actions (benchmark build only). */
object BenchMarker {
    private var on by mutableStateOf(false)
    fun toggle() { on = !on }

    @Composable fun Draw() { if (on) Box(Modifier.size(24.dp).background(Color.Black)) }
}
```

```kotlin
// app/src/benchmark/kotlin/app/epubreader/bench/BenchCommandReceiver.kt
package app.epubreader.bench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/** Harness commands (see bench/README.md). Dispatched on the main thread. */
class BenchCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val arg = intent.getStringExtra("arg") ?: ""
        Probe.ev("cmd", "$cmd $arg")
        Handler(Looper.getMainLooper()).post { BenchCommands.dispatch(cmd, arg) }
    }
}
```

```xml
<!-- app/src/benchmark/AndroidManifest.xml -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android" xmlns:tools="http://schemas.android.com/tools">
    <!-- Harness only: open corpus books by path after `appops set app.epubreader MANAGE_EXTERNAL_STORAGE allow`.
         The main manifest gets storage access in M3 (onboarding). -->
    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" tools:ignore="ScopedStorage" />
    <application>
        <receiver android:name=".bench.BenchCommandReceiver" android:exported="true">
            <intent-filter><action android:name="app.epubreader.BENCH" /></intent-filter>
        </receiver>
    </application>
</manifest>
```

In `MainActivity.onCreate`, after `setContent`, and wrap the content as `Box { App(…); if (Probe.enabled) BenchMarker.Draw() }`:
```kotlin
        if (Probe.enabled) {
            BenchCommands.register("openPath") { openBook(SourceRef.File(it)) }
            BenchCommands.register("back") { goBack() }
            BenchCommands.register("next") { reader.send(ToWeb.Next()) }
            BenchCommands.register("prev") { reader.send(ToWeb.Prev()) }
            BenchCommands.register("toc") { controller.showToc() }
            BenchCommands.register("tocJump") { label ->
                controller.state.value.toc.firstOrNull { it.label.contains(label, ignoreCase = true) }?.let(controller::onTocSelected)
            }
        }
```

Append to `bench/lib.sh`:
```bash
bench_cmd() { $A shell am broadcast -a app.epubreader.BENCH -n "$PKG/.bench.BenchCommandReceiver" --es cmd "$1" --es arg "'${2:-}'" >/dev/null; }
video_ms() { bash "$SPEC_TOOLS/summarize_open.sh" "$1" | sed 's/.*open_ms=//'; }
B=/sdcard/Books
WP=$B/gutenberg/war-and-peace-epub3.epub
```

- [ ] **Step 3: Groups**

```bash
#!/usr/bin/env bash
# bench/groups/open.sh — P-010..P-013 tap → first readable page (screen recording, performance.md "Screen timings";
# measured from the home screen of a running app, "Open targets"), P-015 tap → reader frame (events),
# P-016 reopen / first open in the same process (C-014). --release adds P-014 (huge chapter, M9).
source "$(dirname "$0")/../lib.sh"; require_ad
OUT=$ROOT/bench/results/video; mkdir -p "$OUT"
declare -A BOOKS=([P-010]=$B/gutenberg/alice-epub3-images.epub [P-011]=$WP
                  [P-012]=$B/generated/stress-2000-chapters.epub [P-013]=$B/generated/stress-200-images.epub)
[ "$RELEASE" = 1 ] && BOOKS[P-014]=$B/generated/stress-huge-single-chapter.epub
$A shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
row0() { $A logcat -d -s EPR:I | awk '/EVT row0 /{ match($0,/y=[0-9]+/); y=substr($0,RSTART+2,RLENGTH-2) } END{ print y }'; }
tap_row0() { # <video>: record, tap the first recent row, wait, stop; prints the video open time in ms
  $A logcat -c
  $AD record start "$1" --scope device --hide-touches >/dev/null 2>&1 || echo "WARN record start failed" >&2
  sleep 1.5; $AD press 540 "$Y" >/dev/null; sleep "${WAIT:-6}"
  $AD record stop >/dev/null 2>&1 || echo "WARN record stop failed" >&2
  video_ms "$1"
}
: > "$OUT/p016.txt"
for id in "${!BOOKS[@]}"; do
  # Make the book the most recent one (first row): open it once by path, then return home.
  $A shell am force-stop "$PKG"; $A shell am start -W -n "$PKG/$ACT" >/dev/null; sleep 3
  bench_cmd openPath "${BOOKS[$id]}"; sleep 8; bench_cmd back; sleep 1
  for RUN in 1 2 3; do
    $A shell am force-stop "$PKG"; $A logcat -c
    $A shell am start -W -n "$PKG/$ACT" >/dev/null; sleep 4 # home visible, reader pre-warmed
    Y=$(row0)
    first=$(tap_row0 "$OUT/$id-first-$RUN.mp4"); p15=$(ev_delta tap_book reader_frame)
    bench_cmd back; sleep 2
    again=$(tap_row0 "$OUT/$id-reopen-$RUN.mp4")
    bench_cmd back; sleep 1
    emit "$id" "${first:-999999}" "first open, video"
    emit P-015 "${p15:-999999}" "$id"
    echo "$RUN $id $first $again" | tee -a "$OUT/p016.txt"
  done
done
# P-016: per run, the worst reopen/first ratio over the books (C-014: relative to our own first open).
for RUN in 1 2 3; do
  emit P-016 "$(awk -v r="$RUN" '$1==r { q = ($3>0 && $4!="NA") ? $4/$3 : 9; if (q>m) m=q } END { printf "%.3f", m }' "$OUT/p016.txt")"
done
```

```bash
#!/usr/bin/env bash
# bench/groups/action.sh — M2: P-030 TOC jump to "Second Epilogue" in War and Peace (recording: command marker →
# last large change), P-031 TOC panel open with 2000 entries (events toc_cmd → toc_shown).
# M4 adds reflow (P-020), rotation (P-022) and boundary turns (P-026); M9 (--release) adds rotation on the huge chapter.
source "$(dirname "$0")/../lib.sh"; require_ad
OUT=$ROOT/bench/results/video; mkdir -p "$OUT"
$A shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
$A shell am force-stop "$PKG"; $A shell am start -W -n "$PKG/$ACT" >/dev/null; sleep 3
for RUN in 1 2 3; do
  bench_cmd openPath "$WP"; sleep 6
  v="$OUT/P-030-$RUN.mp4"
  $AD record start "$v" --scope device --hide-touches >/dev/null 2>&1; sleep 1.5
  bench_cmd tocJump "second epilogue"; sleep 4
  $AD record stop >/dev/null 2>&1
  emit P-030 "$(video_ms "$v")"
  bench_cmd back; sleep 2
done
bench_cmd openPath "$B/generated/stress-2000-chapters.epub"; sleep 6
for RUN in 1 2 3; do
  $A logcat -c; bench_cmd toc; sleep 2
  emit P-031 "$(ev_delta toc_cmd toc_shown)"
  bench_cmd back; sleep 1 # closes the panel
done
bench_cmd back
```

```bash
#!/usr/bin/env bash
# bench/groups/mem.sh — P-060: PSS of app + renderer while reading War and Peace (tools/mem_total.sh, as in S2).
# M9 (--release) adds P-061 (huge chapter) and P-062 (50 plates of the 200-image book).
source "$(dirname "$0")/../lib.sh"
$A shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
$A shell am force-stop "$PKG"; $A shell am start -W -n "$PKG/$ACT" >/dev/null; sleep 3
bench_cmd openPath "$WP"; sleep 6
for i in $(seq 1 20); do bench_cmd next; sleep 0.7; done; sleep 3
for RUN in 1 2 3; do emit P-060 "$(PKG=$PKG "$ROOT/tools/mem_total.sh" | awk '{print $3}')"; sleep 2; done
bench_cmd back
```

```bash
#!/usr/bin/env bash
# bench/check_resume.sh — functional F-037/R-009 check (not a timing gate): 27 turns in War and Peace,
# force-stop, reopen → the first relocate has the CFI of the last one before the kill.
RESULTS=/dev/null source "$(dirname "$0")/lib.sh"
last_cfi() { $A logcat -d -s EPR:I | awk '/EVT relocate /{ c=$NF } END { print c }'; }
$A shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow
$A shell am force-stop "$PKG"; $A shell am start -W -n "$PKG/$ACT" >/dev/null; sleep 3
bench_cmd openPath "$WP"; sleep 6
for i in $(seq 1 27); do bench_cmd next; sleep 0.5; done; sleep 1
want=$(last_cfi)
$A shell am force-stop "$PKG"; $A logcat -c
$A shell am start -W -n "$PKG/$ACT" >/dev/null; sleep 3
bench_cmd openPath "$WP"; sleep 6
got=$($A logcat -d -s EPR:I | awk '/EVT relocate /{ print $NF; exit }')
echo "want=$want got=$got"; [ -n "$want" ] && [ "$want" = "$got" ] && echo "resume: PASS" || { echo "resume: FAIL"; exit 1; }
```
Update `bench/README.md`'s group table: `open` → P-010..P-013, P-015, P-016 (P-014 with `--release`);
`action` → P-030, P-031; `mem` → P-060; `check_resume.sh` → F-037/R-009 (functional).

- [ ] **Step 4: Run the M2 milestone suite**

```bash
./gradlew :app:installPlayBenchmark
bench/push_corpus.sh            # if not pushed yet
chmod +x bench/groups/*.sh bench/check_resume.sh
bench/run_all.sh start open action mem && bench/check_resume.sh
```
Expected: every gate line PASS; `resume: PASS`. A failing group is re-run once (bench/README.md). If P-016 still fails
with the warm reopen, record the numbers and raise it with the gatekeeper (master plan risk 11). Do not add caching
beyond the warm session without a decision.

- [ ] **Step 5: Milestone note and commit**

`docs/milestones/M2.md`: the gate output; `tools/smoke.sh` duration; `check_resume.sh` result; two screenshots
(home list, a page; `device_screenshot`); known gaps by design: the huge-chapter book is slow until M8, boundary turns
show a section load until M4, there are no page numbers until M4, and the library and scanning come in M3.

```bash
git add app bench docs/milestones/M2.md
git commit -m "bench: M2 milestone suite (open, TOC, memory), resume check, extended smoke test

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
