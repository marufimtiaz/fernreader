# Core EPUB engine: parked step-level tasks (not a milestone file)

> **Status (revision 2, 2026-10-31):** This was the revision-1 M2 step file. The master plan's revision 2 moved the
> first usable reader forward, and the reader's own parse (foliate-js) covers the first slice. The tasks here are
> **parked**. When the milestone that owns a task starts, its step-level plan is written from this file, after the
> code below has been re-checked against what M1/M2 actually built. Do not execute this file directly.
>
> | Old task | Now | Notes |
> |---|---|---|
> | 2.1 Zip reader | M2 Task 2.1 | moved verbatim |
> | 2.2 Markup foundation | M3 Task 3.1 | as written |
> | 2.3 Package model and failure classes | M3 Task 3.2 | drop `TocParser` wiring (2.4 is dropped); `BookPackage.toc` not built |
> | 2.4 TOC (nav and NCX) | **dropped** | the TOC comes from the reader (`opened.toc`); M5 may reinstate it only if search needs chapter labels without an open book |
> | 2.5 Metadata, cover, identity | M3 Task 3.3 | `BookIdentity` already exists (M2 Task 2.2). **Change:** the unique identifier's text must have whitespace collapsed and trimmed (foliate's rule), not only trimmed (`OpfParser`, line with `.text()?.trim()`); add `IdentityAgreementTest` |
> | 2.6 Text extraction | M4 Task 4.6 | as written |
> | 2.7 CFI + ChunkMap | M8 Task 8.1 | `ChunkMap` implements `PositionMap` (M2) |
> | 2.8 Chunker | M8 Task 8.2 | as written; wiring into the reader is M8 Task 8.3 |
> | 2.9 Corpus classification | M3 Task 3.2 (host `CorpusTest` only) | Step 2 (on-device parse benchmark) is **dropped**: S3 measured scan cost, and C-015 limits benchmark work to Must targets |
>
> Task numbers below are the old ones; the milestone that picks a task up renumbers it.

**Goal:** The platform-neutral book core in `:core:epub`: read any corpus file, classify it (R-001..R-004),
expose its package, TOC, metadata, cover and identity, extract canonical text for search, and split huge
documents with an exact, tested position mapping between original documents and parts.

**Architecture:** Everything lives in `core/epub/src/commonMain` except three tiny platform pieces in `jvmShared`
(file source, SHA-256, inflater). One lenient markup scanner underlies XML parsing (OPF/NCX/nav/container),
text extraction and chunking, so all three agree on what a tag, a text run and an element child are. Positions
are EPUB CFIs against **original** documents; the chunker produces a `ChunkMap` that converts CFIs, hrefs and text
offsets between original and virtual (chunked) spines in both directions.

**Tech Stack:** Kotlin Multiplatform (commonMain + jvmShared), kotlinx.serialization (ChunkMap cache), kotlin.test.
No XML library: one own lenient scanner serves XML and HTML so all consumers agree on structure.

**Spec:** master plan; features.md F-012, F-015, F-016, F-021, F-040, F-050, F-080; performance.md R-001..R-004,
P-014, P-023, P-061; corpus.md §3; engine-evaluation.md §7 (prototype chunker and its open items).

## Global Constraints

See master plan. In addition for this milestone: no Android APIs in `commonMain`; every public function is safe to
call off the main thread and never touches the UI; parsing never throws for content problems — it returns a
classified failure or warnings (exceptions only for programmer errors and I/O failures).

## Review Focus

1. **CRLF, entities, CDATA, script/style** in content documents — canonical text must equal the DOM's text.
   Pinned: `TextExtractorTest.crlf_entities_cdata_script`.
2. **Chunk policy change** between versions — original CFIs must land on the same element text under a different
   policy. Pinned: `ChunkMapTest.position_survives_policy_change`.
3. **Zip entry names in CP437 and percent-encoded hrefs** (`Livres%20%C3%89t%C3%A9.xhtml`). Pinned:
   `ZipReaderTest.cp437_name_decoded`, `PathsTest.percent_encoded_href_resolves`.
4. **A huge document with one wrapper `<div>` around everything** (common in converted books) — must still split.
   Pinned: `ChunkerTest.splits_inside_single_wrapper`.
5. **Unbalanced markup in a huge document** — must not split wrongly (no lost text). Pinned:
   `ChunkerTest.unbalanced_markup_is_not_split`.

## File structure

```
core/epub/src/commonMain/kotlin/app/epubreader/core/epub/
  Platform.kt                     expect: openFileSource, sha256Hex, inflateStream
  zip/  RandomAccessSource.kt  ByteStream.kt  ZipReader.kt  Cp437.kt
  markup/  TextDecoding.kt  HtmlEntities.kt  Entities.kt  MarkupScanner.kt  XmlTree.kt  MarkupRewriter.kt  Paths.kt
  pkg/  BookPackage.kt  ContainerParser.kt  OpfParser.kt  PackageLoader.kt  TocParser.kt
  meta/  MetadataReader.kt  CoverLocator.kt  BookIdentity.kt
  text/  TextExtractor.kt
  chunk/  ChunkPolicy.kt  ChunkStore.kt  SplitPlanner.kt  Chunker.kt  ChunkMap.kt  ChunkedBook.kt
  cfi/  Cfi.kt
core/epub/src/jvmShared/kotlin/app/epubreader/core/epub/
  Platform.jvmShared.kt  FileChannelSource.kt  InflaterByteStream.kt  FileChunkStore.kt
core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/
  TestZips.kt  Corpus.kt  zip/ZipReaderTest.kt  markup/*Test.kt  pkg/*Test.kt  meta/*Test.kt
  text/TextExtractorTest.kt  chunk/ChunkerTest.kt  chunk/ChunkMapTest.kt  cfi/CfiTest.kt  CorpusTest.kt
app/src/androidTest/kotlin/app/epubreader/CorpusParseInstrumentedTest.kt
```

---

### Task 2.1: Random-access sources and the zip reader — moved

Moved unchanged to M2 Task 2.1 in `2026-10-30-mvp-m2-first-reader.md` (it is part of the first readable slice).
The tasks below build on it.

---

### Task 2.2: Markup foundation — decoding, entities, scanner, tree, rewriter, paths

**Files:**
- Create: `core/epub/src/commonMain/kotlin/app/epubreader/core/epub/markup/{TextDecoding.kt, HtmlEntities.kt, Entities.kt, MarkupScanner.kt, XmlTree.kt, MarkupRewriter.kt, Paths.kt}`
- Modify: `core/epub/build.gradle.kts` (add kotlinx.serialization plugin + `libs.serialization.json`, needed by Task 2.7)
- Test: `core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/markup/{TextDecodingTest.kt, EntitiesTest.kt, MarkupScannerTest.kt, XmlTreeTest.kt, MarkupRewriterTest.kt, PathsTest.kt}`

**Interfaces:**
- Produces:
  - `object TextDecoding { fun decode(bytes: ByteArray): String }` (BOM, XML decl, HTML meta charset; UTF-8/16, ISO-8859-1, windows-1252)
  - `object Entities { fun decode(raw: String): String; fun appendDecoded(s: String, start: Int, end: Int, out: StringBuilder) ; fun escapeAttr(v: String): String }` — also normalizes CR/CRLF → LF
  - `class Attr(val name: String, val rawValue: String?, val valueStart: Int, val valueEnd: Int) { val localName: String; val value: String }`
  - `interface MarkupVisitor { startTag(name, attrs, selfClosing, start, end); endTag(name, start, end); text(start, end); cdata(start, end); error(message, at) }`
  - `object MarkupScanner { fun scan(s: String, v: MarkupVisitor, from: Int = 0, to: Int = s.length) }`
  - `class XmlElement(name, attrs, start) { localName; attr(local); elements(); descendants(); first(local); all(local); text() }`, `class XmlDocument(root, errors) { wellFormed }`, `object XmlTree { fun parse(s: String, html: Boolean = false): XmlDocument }`
  - `object MarkupRewriter { fun rewriteAttrValues(s: String, f: (tag: String, attr: Attr) -> String?): String }` (returns `s` itself when nothing changed)
  - `object Paths { fun dirOf(path); fun resolve(baseDir, href): String; fun fragmentOf(href): String?; fun isExternal(href): Boolean; fun percentDecode(s) }`
  - `val HTML_VOID: Set<String>`

- [ ] **Step 1: Write the failing tests**

```kotlin
// core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/markup/EntitiesTest.kt
package app.epubreader.core.epub.markup

import kotlin.test.Test
import kotlin.test.assertEquals

class EntitiesTest {
    @Test fun named_numeric_and_unknown() =
        assertEquals("Entity test — &bogus; and a stray & ampersand é é", Entities.decode("Entity&nbsp;test &mdash; &bogus; and a stray & ampersand &eacute; &#xE9;"))
    @Test fun crlf_and_cr_become_lf() = assertEquals("a\nb\nc", Entities.decode("a\r\nb\rc"))
    @Test fun char_ref_cr_is_kept() = assertEquals("a\rb", Entities.decode("a&#13;b"))
    @Test fun table_is_complete() {
        assertEquals(253, HtmlEntities.map.size)
        assertEquals(0x20AC, HtmlEntities.map["euro"]); assertEquals(0x2026, HtmlEntities.map["hellip"]); assertEquals(0x27, HtmlEntities.map["apos"])
    }
    @Test fun escape_attr() = assertEquals("a&amp;b&quot;c&lt;", Entities.escapeAttr("a&b\"c<"))
}
```

```kotlin
// core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/markup/MarkupScannerTest.kt
package app.epubreader.core.epub.markup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkupScannerTest {
    private fun events(s: String): List<String> {
        val out = ArrayList<String>()
        MarkupScanner.scan(s, object : MarkupVisitor {
            override fun startTag(name: String, attrs: List<Attr>, selfClosing: Boolean, start: Int, end: Int) {
                out += "<$name${attrs.joinToString("") { " ${it.name}=${it.value}" }}${if (selfClosing) "/" else ""}>"
            }
            override fun endTag(name: String, start: Int, end: Int) { out += "</$name>" }
            override fun text(start: Int, end: Int) { out += "T:" + s.substring(start, end) }
            override fun cdata(start: Int, end: Int) { out += "C:" + s.substring(start, end) }
            override fun error(message: String, at: Int) { out += "E:$message" }
        })
        return out
    }

    @Test fun tags_text_attrs() = assertEquals(
        listOf("<p class=a b=x&y>", "T:hi", "<br/>", "</p>"),
        events("""<p class="a" b='x&amp;y'>hi<br/></p>"""))

    @Test fun comments_pi_doctype_skipped_cdata_reported() = assertEquals(
        listOf("<a>", "C:x<y", "</a>"),
        events("""<?xml version="1.0"?><!DOCTYPE html><!-- c --><a><![CDATA[x<y]]></a>"""))

    @Test fun script_is_raw_text() = assertEquals(
        listOf("<script>", "T:if (a<b) x()", "</script>"),
        events("<script>if (a<b) x()</script>"))

    @Test fun quote_aware_tag_end() = assertEquals(listOf("<a title=x>y>", "</a>"), events("""<a title="x>y"></a>"""))

    @Test fun malformed_attributes_reported() =
        assertTrue(events("""<item id="c1" href="good.xhtml"</manifest>""").any { it.startsWith("E:malformed attributes") })

    @Test fun attr_value_offsets() {
        val s = """<a href="x.xhtml#n1">t</a>"""
        var a: Attr? = null
        MarkupScanner.scan(s, object : MarkupVisitor {
            override fun startTag(name: String, attrs: List<Attr>, selfClosing: Boolean, start: Int, end: Int) { a = attrs[0] }
        })
        assertEquals("x.xhtml#n1", s.substring(a!!.valueStart, a!!.valueEnd))
    }
}
```

```kotlin
// core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/markup/XmlTreeTest.kt
package app.epubreader.core.epub.markup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XmlTreeTest {
    @Test fun well_formed_package() {
        val d = XmlTree.parse("""<?xml version="1.0"?><package xmlns="x" version="3.0"><metadata><dc:title>T &amp; U</dc:title></metadata><spine><itemref idref="a"/></spine></package>""")
        assertTrue(d.wellFormed)
        assertEquals("T & U", d.root!!.first("title")!!.text())
        assertEquals("a", d.root!!.first("itemref")!!.attr("idref"))
    }

    @Test fun broken_opf_from_corpus_is_not_well_formed() {
        val d = XmlTree.parse("""<?xml version="1.0"?><package version="3.0"><metadata><dc:title>Broken OPF</metadata><manifest><item id="c1" href="good.xhtml"</manifest>""")
        assertFalse(d.wellFormed)
        assertEquals("package", d.root!!.localName) // lenient tree still available
    }

    @Test fun html_mode_void_elements_do_not_nest() {
        val d = XmlTree.parse("<nav><ol><li><a href='a'>A</a><br><img src=x></li><li><a href='b'>B</a></li></ol></nav>", html = true)
        assertEquals(2, d.root!!.first("ol")!!.elements().size)
    }

    @Test fun mismatched_end_tags_recover() {
        val d = XmlTree.parse("<p><b>bold <i>mis</b> nest</i></p>", html = true)
        assertFalse(d.errors.isEmpty())
        assertEquals("bold mis nest", d.root!!.text())
    }
}
```

```kotlin
// core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/markup/PathsTest.kt
package app.epubreader.core.epub.markup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PathsTest {
    @Test fun resolves_relative() = assertEquals("OEBPS/Text/ch1.xhtml", Paths.resolve("OEBPS/Text", "ch1.xhtml#x"))
    @Test fun parent_segments() = assertEquals("OEBPS/Images/a.png", Paths.resolve("OEBPS/Text", "../Images/a.png"))
    @Test fun never_above_root() = assertEquals("a.png", Paths.resolve("", "../../a.png"))
    @Test fun percent_encoded_href_resolves() = assertEquals("OEBPS/Livres Été.xhtml", Paths.resolve("OEBPS", "Livres%20%C3%89t%C3%A9.xhtml"))
    @Test fun fragment() { assertEquals("n5", Paths.fragmentOf("notes.xhtml#n5")); assertNull(Paths.fragmentOf("notes.xhtml")) }
    @Test fun external() {
        assertTrue(Paths.isExternal("https://x.org/")); assertTrue(Paths.isExternal("mailto:a@b"))
        assertFalse(Paths.isExternal("c.xhtml")); assertFalse(Paths.isExternal("../a:b.xhtml"))
    }
    @Test fun lone_percent_stays_literal() = assertEquals("100%", Paths.percentDecode("100%"))
    @Test fun dir_of() { assertEquals("OEBPS", Paths.dirOf("OEBPS/content.opf")); assertEquals("", Paths.dirOf("content.opf")) }
}
```

```kotlin
// core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/markup/TextDecodingTest.kt
package app.epubreader.core.epub.markup

import kotlin.test.Test
import kotlin.test.assertEquals

class TextDecodingTest {
    @Test fun utf8_with_bom() = assertEquals("<a>é</a>", TextDecoding.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "<a>é</a>".encodeToByteArray()))
    @Test fun utf16le_with_bom() = assertEquals("<a>é</a>", TextDecoding.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "<a>é</a>".toByteArray(Charsets.UTF_16LE)))
    @Test fun latin1_declared() = assertEquals("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><a>é</a>",
        TextDecoding.decode("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><a>é</a>".toByteArray(Charsets.ISO_8859_1)))
    @Test fun cp1252_quotes() = assertEquals("<meta charset=\"windows-1252\">“x”",
        TextDecoding.decode("<meta charset=\"windows-1252\">“x”".toByteArray(charset("windows-1252"))))
    @Test fun default_utf8() = assertEquals("Borodinó", TextDecoding.decode("Borodinó".encodeToByteArray()))
}
```

```kotlin
// core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/markup/MarkupRewriterTest.kt
package app.epubreader.core.epub.markup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MarkupRewriterTest {
    @Test fun rewrites_only_selected_attribute_values() {
        val s = """<p>href="#a" in text</p><a href="#a">x</a><img src="i.png"/>"""
        val r = MarkupRewriter.rewriteAttrValues(s) { tag, a -> if (tag == "a" && a.localName == "href") "p1.xhtml#a" else null }
        assertEquals("""<p>href="#a" in text</p><a href="p1.xhtml#a">x</a><img src="i.png"/>""", r)
    }
    @Test fun escapes_new_values() = assertEquals("""<a href="a&amp;b">x</a>""",
        MarkupRewriter.rewriteAttrValues("""<a href="z">x</a>""") { _, _ -> "a&b" })
    @Test fun unchanged_returns_same_instance() { val s = "<a href='x'/>"; assertSame(s, MarkupRewriter.rewriteAttrValues(s) { _, _ -> null }) }
}
```
Run: `./gradlew :core:epub:jvmTest --tests 'app.epubreader.core.epub.markup.*'` → Expected: compilation FAIL.

- [ ] **Step 2: Entity table**

`HtmlEntities.kt`: `internal object HtmlEntities { val map: Map<String, Int> = mapOf(...) }` containing exactly the
252 character entity references of HTML 4.01 (W3C HTML 4.01 §24: the `HTMLlat1`, `HTMLsymbol` and `HTMLspecial`
sets — 96 + 124 + 32 names) plus `"apos" to 0x27`, total 253. Write them as `"nbsp" to 0xA0, "iexcl" to 0xA1, …`
in the order of the three W3C sets. `EntitiesTest.table_is_complete` checks the count and three samples; also add
a spot-check line for one entry from each set (`"yuml" to 0xFF`, `"diams" to 0x2666`, `"rsaquo" to 0x203A`).

- [ ] **Step 3: Implement `Entities`, `TextDecoding`, `Paths`**

```kotlin
// core/epub/src/commonMain/kotlin/app/epubreader/core/epub/markup/Entities.kt
package app.epubreader.core.epub.markup

/**
 * Character reference decoding with the behaviour browsers show for (X)HTML text: known named and numeric
 * references are decoded, unknown ones and bare '&' stay literal, raw CR and CRLF become LF.
 */
object Entities {
    fun decode(raw: String): String =
        if (raw.indexOf('&') < 0 && raw.indexOf('\r') < 0) raw else StringBuilder(raw.length).also { appendDecoded(raw, 0, raw.length, it) }.toString()

    fun appendDecoded(s: String, start: Int, end: Int, out: StringBuilder) {
        var i = start
        while (i < end) {
            val c = s[i]
            if (c == '\r') { out.append('\n'); i += if (i + 1 < end && s[i + 1] == '\n') 2 else 1; continue }
            if (c != '&') { out.append(c); i++; continue }
            var semi = -1
            var j = i + 1
            while (j < end && j - i <= 33) { if (s[j] == ';') { semi = j; break }; if (s[j] == '&' || s[j] == '<' || s[j].isWhitespace()) break; j++ }
            if (semi < 0) { out.append('&'); i++; continue }
            val name = s.substring(i + 1, semi)
            val cp = when {
                name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16)
                name.startsWith("#") -> name.substring(1).toIntOrNull()
                else -> HtmlEntities.map[name]
            }
            if (cp == null || cp <= 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) { out.append('&'); i++; continue }
            appendCodePoint(out, cp)
            i = semi + 1
        }
    }

    fun escapeAttr(v: String): String = buildString(v.length + 8) {
        for (c in v) when (c) { '&' -> append("&amp;"); '"' -> append("&quot;"); '<' -> append("&lt;"); else -> append(c) }
    }

    internal fun appendCodePoint(out: StringBuilder, cp: Int) {
        if (cp < 0x10000) out.append(cp.toChar())
        else { val v = cp - 0x10000; out.append((0xD800 + (v shr 10)).toChar()); out.append((0xDC00 + (v and 0x3FF)).toChar()) }
    }
}
```

```kotlin
// core/epub/src/commonMain/kotlin/app/epubreader/core/epub/markup/TextDecoding.kt
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

    private fun latin1(b: ByteArray, n: Int) = buildString(n) { for (i in 0 until n) append((b[i].toInt() and 0xff).toChar()) }

    private fun utf16(b: ByteArray, start: Int, bigEndian: Boolean) = buildString((b.size - start) / 2) {
        var i = start
        while (i + 1 < b.size) {
            val hi = b[i].toInt() and 0xff; val lo = b[i + 1].toInt() and 0xff
            append((if (bigEndian) (hi shl 8) or lo else (lo shl 8) or hi).toChar()); i += 2
        }
    }
}
```

```kotlin
// core/epub/src/commonMain/kotlin/app/epubreader/core/epub/markup/Paths.kt
package app.epubreader.core.epub.markup

/** Zip-path arithmetic for hrefs inside a book. Paths have no leading slash and use '/'. */
object Paths {
    fun dirOf(path: String): String = path.substringBeforeLast('/', "")

    fun fragmentOf(href: String): String? = href.indexOf('#').let { if (it < 0) null else percentDecode(href.substring(it + 1)) }

    fun isExternal(href: String): Boolean {
        val colon = href.indexOf(':'); if (colon <= 0) return false
        val slash = href.indexOf('/'); return slash < 0 || colon < slash
    }

    /** Resolves [href] (fragment ignored, percent-decoded) against [baseDir]; never escapes above the root. */
    fun resolve(baseDir: String, href: String): String {
        val path = percentDecode(href.substringBefore('#').substringBefore('?'))
        if (path.isEmpty()) return normalize(baseDir)
        return normalize(if (path.startsWith("/") || baseDir.isEmpty()) path.removePrefix("/") else "$baseDir/$path")
    }

    fun normalize(p: String): String {
        val out = ArrayList<String>()
        for (seg in p.replace('\\', '/').split('/')) when (seg) {
            "", "." -> {}
            ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
            else -> out += seg
        }
        return out.joinToString("/")
    }

    /** Decodes %XX sequences as UTF-8; a '%' not followed by two hex digits stays literal. */
    fun percentDecode(s: String): String {
        if (s.indexOf('%') < 0) return s
        val out = ArrayList<Byte>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) { out += v.toByte(); i += 3; continue }
            }
            c.toString().encodeToByteArray().forEach { out += it }
            i++
        }
        return out.toByteArray().decodeToString()
    }
}
```

- [ ] **Step 4: Implement the scanner**

```kotlin
// core/epub/src/commonMain/kotlin/app/epubreader/core/epub/markup/MarkupScanner.kt
package app.epubreader.core.epub.markup

val HTML_VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")

class Attr(val name: String, val rawValue: String?, val valueStart: Int, val valueEnd: Int) {
    val localName: String get() = name.substringAfter(':')
    val value: String get() = rawValue?.let(Entities::decode) ?: ""
}

interface MarkupVisitor {
    fun startTag(name: String, attrs: List<Attr>, selfClosing: Boolean, start: Int, end: Int) {}
    fun endTag(name: String, start: Int, end: Int) {}
    fun text(start: Int, end: Int) {}
    fun cdata(start: Int, end: Int) {}
    fun error(message: String, at: Int) {}
}

/**
 * Forward, allocation-light scanner for XML and lenient HTML. Reports tags with attribute value offsets, raw text
 * ranges (entities not decoded) and CDATA. Never throws; problems are reported through [MarkupVisitor.error].
 * Shared by XmlTree, TextExtractor and the chunker so they agree on structure.
 */
object MarkupScanner {
    private val RAW_TEXT = setOf("script", "style")

    fun scan(s: String, v: MarkupVisitor, from: Int = 0, to: Int = s.length) {
        var i = from
        while (i < to) {
            val lt = s.indexOf('<', i)
            if (lt < 0 || lt >= to) { v.text(i, to); return }
            if (lt > i) v.text(i, lt)
            when {
                s.startsWith("<!--", lt) -> {
                    val e = s.indexOf("-->", lt + 4)
                    if (e < 0 || e + 3 > to) { v.error("unterminated comment", lt); return }
                    i = e + 3
                }
                s.startsWith("<![CDATA[", lt) -> {
                    val e = s.indexOf("]]>", lt + 9)
                    if (e < 0 || e + 3 > to) { v.error("unterminated CDATA", lt); return }
                    v.cdata(lt + 9, e); i = e + 3
                }
                s.startsWith("<?", lt) || s.startsWith("<!", lt) -> {
                    val e = s.indexOf('>', lt)
                    if (e < 0 || e >= to) { v.error("unterminated declaration", lt); return }
                    i = e + 1
                }
                lt + 1 < to && s[lt + 1] == '/' -> {
                    val e = s.indexOf('>', lt)
                    if (e < 0 || e >= to) { v.error("unterminated end tag", lt); return }
                    v.endTag(s.substring(lt + 2, e).trim(), lt, e + 1); i = e + 1
                }
                lt + 1 < to && isNameStart(s[lt + 1]) -> {
                    var gt = quoteAwareTagEnd(s, lt, to)
                    if (gt < 0) {
                        gt = s.indexOf('>', lt)
                        if (gt < 0 || gt >= to) { v.error("unterminated start tag", lt); return }
                        v.error("unbalanced quote in tag", lt)
                    }
                    val selfClosing = s[gt - 1] == '/'
                    val (name, attrs, ok) = parseTag(s, lt + 1, if (selfClosing) gt - 1 else gt)
                    if (!ok) v.error("malformed attributes in <$name>", lt)
                    v.startTag(name, attrs, selfClosing, lt, gt + 1)
                    i = gt + 1
                    if (!selfClosing && name.lowercase() in RAW_TEXT) {
                        val close = indexOfIgnoreCase(s, "</$name", i, to)
                        val end = if (close < 0) to else close
                        if (end > i) v.text(i, end)
                        i = end
                    }
                }
                else -> { v.error("bare '<' in text", lt); v.text(lt, lt + 1); i = lt + 1 }
            }
        }
    }

    private fun isNameStart(c: Char) = c.isLetter() || c == '_' || c == ':'
    private fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '-' || c == '_' || c == ':' || c == '.'

    private fun quoteAwareTagEnd(s: String, lt: Int, to: Int): Int {
        var q = 0.toChar(); var j = lt + 1
        while (j < to) {
            val c = s[j]
            if (q != 0.toChar()) { if (c == q) q = 0.toChar() }
            else if (c == '"' || c == '\'') q = c
            else if (c == '>') return j
            j++
        }
        return -1
    }

    private fun parseTag(s: String, from: Int, end: Int): Triple<String, List<Attr>, Boolean> {
        var i = from
        while (i < end && isNameChar(s[i])) i++
        val name = s.substring(from, i)
        val attrs = ArrayList<Attr>(4)
        var ok = true
        while (true) {
            while (i < end && s[i].isWhitespace()) i++
            if (i >= end) break
            val ns = i
            while (i < end && isNameChar(s[i])) i++
            if (i == ns) { ok = false; i++; continue }
            val an = s.substring(ns, i)
            while (i < end && s[i].isWhitespace()) i++
            if (i < end && s[i] == '=') {
                i++
                while (i < end && s[i].isWhitespace()) i++
                if (i < end && (s[i] == '"' || s[i] == '\'')) {
                    val q = s[i]; val ve = s.indexOf(q, i + 1)
                    if (ve < 0 || ve >= end) { ok = false; attrs += Attr(an, s.substring(i + 1, end), i + 1, end); break }
                    attrs += Attr(an, s.substring(i + 1, ve), i + 1, ve); i = ve + 1
                } else {
                    val vs = i
                    while (i < end && !s[i].isWhitespace()) i++
                    attrs += Attr(an, s.substring(vs, i), vs, i); ok = false
                }
            } else { attrs += Attr(an, null, -1, -1); ok = false }
        }
        return Triple(name, attrs, ok)
    }

    internal fun indexOfIgnoreCase(s: String, needle: String, from: Int, to: Int): Int {
        var i = from
        while (i <= to - needle.length) { if (s.regionMatches(i, needle, 0, needle.length, ignoreCase = true)) return i; i++ }
        return -1
    }
}
```

- [ ] **Step 5: Implement the tree and the rewriter**

```kotlin
// core/epub/src/commonMain/kotlin/app/epubreader/core/epub/markup/XmlTree.kt
package app.epubreader.core.epub.markup

class XmlElement(val name: String, val attrs: List<Attr>, val start: Int) {
    val children = ArrayList<Any>(4) // XmlElement or String (decoded text)
    val localName: String get() = name.substringAfter(':').lowercase()
    fun attr(local: String): String? = attrs.firstOrNull { it.localName.equals(local, ignoreCase = true) }?.value
    /** Attribute by qualified name, e.g. "epub:type". */
    fun qattr(qname: String): String? = attrs.firstOrNull { it.name.equals(qname, ignoreCase = true) }?.value
    fun elements(): List<XmlElement> = children.filterIsInstance<XmlElement>()
    fun descendants(): Sequence<XmlElement> = sequence {
        for (c in children) if (c is XmlElement) { yield(c); yieldAll(c.descendants()) }
    }
    fun first(local: String): XmlElement? = descendants().firstOrNull { it.localName == local }
    fun all(local: String): Sequence<XmlElement> = descendants().filter { it.localName == local }
    fun text(): String = buildString { fun walk(e: XmlElement) { for (c in e.children) if (c is String) append(c) else walk(c as XmlElement) }; walk(this@XmlElement) }
}

class XmlDocument(val root: XmlElement?, val errors: List<String>) {
    val wellFormed: Boolean get() = root != null && errors.isEmpty()
}

/** Small-document tree builder (package, container, NCX, nav). Lenient: always returns the best tree it can, plus errors. */
object XmlTree {
    fun parse(s: String, html: Boolean = false): XmlDocument {
        val errors = ArrayList<String>()
        val doc = XmlElement("#document", emptyList(), 0)
        val stack = ArrayList<XmlElement>().apply { add(doc) }
        MarkupScanner.scan(s, object : MarkupVisitor {
            override fun startTag(name: String, attrs: List<Attr>, selfClosing: Boolean, start: Int, end: Int) {
                val e = XmlElement(name, attrs, start)
                if (stack.size == 1 && doc.elements().isNotEmpty()) errors += "multiple root elements"
                stack.last().children += e
                val void = html && name.lowercase() in HTML_VOID
                if (!selfClosing && !void) stack += e
            }
            override fun endTag(name: String, start: Int, end: Int) {
                if (html && name.lowercase() in HTML_VOID) return
                val idx = stack.indexOfLast { it.name.equals(name, ignoreCase = html) }
                when {
                    idx <= 0 -> errors += "stray </$name> at $start"
                    idx == stack.size - 1 -> stack.removeAt(idx)
                    else -> { errors += "mismatched </$name> at $start"; while (stack.size > idx) stack.removeAt(stack.size - 1) }
                }
            }
            override fun text(start: Int, end: Int) {
                if (stack.size == 1) { if (s.substring(start, end).isNotBlank()) errors += "text outside root"; return }
                stack.last().children += Entities.decode(s.substring(start, end))
            }
            override fun cdata(start: Int, end: Int) { stack.last().children += s.substring(start, end) }
            override fun error(message: String, at: Int) { errors += "$message at $at" }
        })
        if (stack.size > 1) errors += "unclosed <${stack.last().name}>"
        return XmlDocument(doc.elements().firstOrNull(), errors)
    }
}
```

```kotlin
// core/epub/src/commonMain/kotlin/app/epubreader/core/epub/markup/MarkupRewriter.kt
package app.epubreader.core.epub.markup

/** Rewrites attribute values in place (by scanner offsets), leaving all other bytes untouched. */
object MarkupRewriter {
    /** [f] returns the new (unescaped) value, or null to keep the attribute as is. */
    fun rewriteAttrValues(s: String, f: (tag: String, attr: Attr) -> String?): String {
        val edits = ArrayList<Triple<Int, Int, String>>()
        MarkupScanner.scan(s, object : MarkupVisitor {
            override fun startTag(name: String, attrs: List<Attr>, selfClosing: Boolean, start: Int, end: Int) {
                for (a in attrs) if (a.valueStart >= 0) f(name.substringAfter(':').lowercase(), a)?.let { edits += Triple(a.valueStart, a.valueEnd, Entities.escapeAttr(it)) }
            }
        })
        if (edits.isEmpty()) return s
        val sb = StringBuilder(s.length + edits.size * 16)
        var last = 0
        for ((a, b, v) in edits) { sb.append(s, last, a).append(v); last = b }
        return sb.append(s, last, s.length).toString()
    }
}
```

- [ ] **Step 6: Add serialization to `core:epub`**

In `core/epub/build.gradle.kts` add plugin `alias(libs.plugins.kotlin.serialization)` and
`implementation(libs.serialization.json)` to `commonMain`.

- [ ] **Step 7: Run tests and commit**

Run: `./gradlew :core:epub:jvmTest --tests 'app.epubreader.core.epub.markup.*'` → Expected: all PASS.
```bash
git add core/epub
git commit -m "core/epub: lenient markup scanner, entity decoding, XML tree, rewriter and path utilities

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2.3: Package model, container/OPF parsing and failure classification

**Files:**
- Create: `core/epub/src/commonMain/kotlin/app/epubreader/core/epub/pkg/{BookPackage.kt, ContainerParser.kt, OpfParser.kt, PackageLoader.kt}`
- Test: `core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/pkg/PackageLoaderTest.kt`

**Interfaces:**
- Consumes: `ZipReader`, `XmlTree`, `TextDecoding`, `Paths`.
- Produces:
```kotlin
enum class FailReason { NOT_A_ZIP, DAMAGED_ARCHIVE, NO_PACKAGE, BROKEN_PACKAGE, NOTHING_READABLE }
enum class WarningKind { MISSING_FILE, UNKNOWN_IDREF, DANGLING_COVER, TOC_MISSING_TARGET, PACKAGE_RECOVERED, OPF_FOUND_BY_SEARCH, NAV_UNREADABLE }
data class PackageWarning(val kind: WarningKind, val detail: String)
data class ManifestItem(val id: String, val path: String, val mediaType: String, val properties: Set<String>, val present: Boolean)
data class SpineItem(val index: Int, val idref: String, val path: String, val mediaType: String, val linear: Boolean, val present: Boolean)
data class TocEntry(val label: String, val path: String?, val fragment: String?, val targetMissing: Boolean, val children: List<TocEntry>)
data class BookMetadata(val title: String?, val authors: List<String>, val language: String?, val titleRtl: Boolean)
data class BookPackage(val opfPath: String, val version: String, val uniqueId: String?, val metadata: BookMetadata,
    val manifest: List<ManifestItem>, val spine: List<SpineItem>, val toc: List<TocEntry>, val pageProgression: String?,
    val fixedLayout: Boolean, val coverPath: String?, val opf: XmlElement)
sealed interface LoadResult { data class Ok(val pkg: BookPackage, val warnings: List<PackageWarning>) : LoadResult
                              data class Failed(val reason: FailReason, val detail: String) : LoadResult }
object PackageLoader { fun load(zip: ZipReader): LoadResult; fun open(source: RandomAccessSource): Pair<ZipReader?, LoadResult> }
```
  `toc` and `coverPath`/`metadata` are filled by Tasks 2.4/2.5 (`PackageLoader` calls `TocParser`, `MetadataReader`,
  `CoverLocator` once those exist; until then: `emptyList()`, `null`, metadata from `dc:title` only).
  `opf` is the parsed OPF root (used by later readers; not serialized).

- [ ] **Step 1: Write the failing tests**

```kotlin
// core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/pkg/PackageLoaderTest.kt
package app.epubreader.core.epub.pkg

import app.epubreader.core.epub.Corpus
import app.epubreader.core.epub.b
import app.epubreader.core.epub.openFileSource
import app.epubreader.core.epub.zip.ByteArraySource
import app.epubreader.core.epub.zipOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PackageLoaderTest {
    private fun corpus(name: String) = openFileSource(Corpus.generated(name).path).use { PackageLoader.open(it).second }
    private fun reason(r: LoadResult) = (r as? LoadResult.Failed)?.reason

    @Test fun not_a_zip() = assertEquals(FailReason.NOT_A_ZIP, reason(corpus("malformed-not-a-zip.epub")))
    @Test fun truncated() = assertEquals(FailReason.DAMAGED_ARCHIVE, reason(corpus("malformed-truncated.epub")))
    @Test fun broken_opf() = assertEquals(FailReason.BROKEN_PACKAGE, reason(corpus("malformed-broken-opf.epub")))
    @Test fun no_container_and_no_opf() = assertEquals(FailReason.NO_PACKAGE, reason(corpus("malformed-no-container.epub")))
    @Test fun empty_spine() = assertEquals(FailReason.NOTHING_READABLE, reason(corpus("malformed-empty-spine.epub")))

    @Test fun partially_damaged_book_opens_with_warnings() {
        val r = corpus("malformed-missing-files-bad-xml.epub") as LoadResult.Ok
        assertEquals(listOf("c1", "c2", "c3", "c4"), r.pkg.spine.map { it.idref })          // ghost skipped
        assertEquals(listOf(true, true, false, true), r.pkg.spine.map { it.present })        // c3 file missing
        val kinds = r.warnings.map { it.kind }.toSet()
        assertTrue(WarningKind.MISSING_FILE in kinds && WarningKind.UNKNOWN_IDREF in kinds, "$kinds")
        assertEquals("Malformed & Broken", r.pkg.metadata.title)
    }

    @Test fun container_missing_but_single_opf_is_used() {
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="u"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="u">x</dc:identifier><dc:title>T</dc:title></metadata><manifest><item id="c" href="c.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c"/></spine></package>"""
        val zip = zipOf("mimetype" to "application/epub+zip".b(), "OPS/book.opf" to opf.b(), "OPS/c.xhtml" to "<html><body>x</body></html>".b())
        val r = PackageLoader.open(ByteArraySource(zip)).second as LoadResult.Ok
        assertEquals("OPS/book.opf", r.pkg.opfPath)
        assertTrue(r.warnings.any { it.kind == WarningKind.OPF_FOUND_BY_SEARCH })
        assertEquals("OPS/c.xhtml", r.pkg.spine.single().path)
    }

    @Test fun epub2_ncx_only_and_fixed_layout_flags() {
        val r = corpus("epub2-ncx-only.epub") as LoadResult.Ok
        assertEquals("2.0", r.pkg.version); assertEquals(20, r.pkg.spine.size); assertEquals(false, r.pkg.fixedLayout)
    }

    @Test fun all_good_generated_books_load() {
        for (n in listOf("stress-huge-single-chapter.epub", "stress-2000-chapters.epub", "stress-200-images.epub", "no-cover.epub", "footnotes-links.epub"))
            assertTrue(corpus(n) is LoadResult.Ok, n)
    }
}
```
Run: `./gradlew :core:epub:jvmTest --tests '*PackageLoaderTest'` → Expected: compilation FAIL.

- [ ] **Step 2: Implement the model and parsers**

```kotlin
// core/epub/src/commonMain/kotlin/app/epubreader/core/epub/pkg/BookPackage.kt
package app.epubreader.core.epub.pkg

import app.epubreader.core.epub.markup.XmlElement

enum class FailReason { NOT_A_ZIP, DAMAGED_ARCHIVE, NO_PACKAGE, BROKEN_PACKAGE, NOTHING_READABLE }
enum class WarningKind { MISSING_FILE, UNKNOWN_IDREF, DANGLING_COVER, TOC_MISSING_TARGET, PACKAGE_RECOVERED, OPF_FOUND_BY_SEARCH, NAV_UNREADABLE }
data class PackageWarning(val kind: WarningKind, val detail: String)

data class ManifestItem(val id: String, val path: String, val mediaType: String, val properties: Set<String>, val present: Boolean)
data class SpineItem(val index: Int, val idref: String, val path: String, val mediaType: String, val linear: Boolean, val present: Boolean)
data class TocEntry(val label: String, val path: String?, val fragment: String?, val targetMissing: Boolean, val children: List<TocEntry>)
data class BookMetadata(val title: String?, val authors: List<String>, val language: String?, val titleRtl: Boolean)

data class BookPackage(
    val opfPath: String,
    val version: String,
    val uniqueId: String?,
    val metadata: BookMetadata,
    val manifest: List<ManifestItem>,
    val spine: List<SpineItem>,
    val toc: List<TocEntry>,
    val pageProgression: String?,
    val fixedLayout: Boolean,
    val coverPath: String?,
    val opf: XmlElement,
) {
    val opfDir: String get() = opfPath.substringBeforeLast('/', "")
    fun item(id: String): ManifestItem? = manifest.firstOrNull { it.id == id }
}

sealed interface LoadResult {
    data class Ok(val pkg: BookPackage, val warnings: List<PackageWarning>) : LoadResult
    data class Failed(val reason: FailReason, val detail: String) : LoadResult
}
```

```kotlin
// core/epub/src/commonMain/kotlin/app/epubreader/core/epub/pkg/ContainerParser.kt
package app.epubreader.core.epub.pkg

import app.epubreader.core.epub.markup.TextDecoding
import app.epubreader.core.epub.markup.XmlTree
import app.epubreader.core.epub.zip.ZipReader

internal object ContainerParser {
    /** Path of the package document from META-INF/container.xml, or null if missing/unusable. */
    fun opfPath(zip: ZipReader): String? {
        val e = zip.entry("META-INF/container.xml") ?: return null
        val root = runCatching { XmlTree.parse(TextDecoding.decode(zip.readBytes(e, 1L shl 20))).root }.getOrNull() ?: return null
        val rootfiles = root.all("rootfile").toList()
        val pick = rootfiles.firstOrNull { it.attr("media-type") == "application/oebps-package+xml" } ?: rootfiles.firstOrNull()
        return pick?.attr("full-path")?.takeIf { it.isNotBlank() && zip.entry(it) != null }
    }
}
```

```kotlin
// core/epub/src/commonMain/kotlin/app/epubreader/core/epub/pkg/PackageLoader.kt
package app.epubreader.core.epub.pkg

import app.epubreader.core.epub.markup.Paths
import app.epubreader.core.epub.markup.TextDecoding
import app.epubreader.core.epub.markup.XmlTree
import app.epubreader.core.epub.zip.RandomAccessSource
import app.epubreader.core.epub.zip.ZipError
import app.epubreader.core.epub.zip.ZipFormatException
import app.epubreader.core.epub.zip.ZipReader

/** Loads and classifies a book (R-001..R-004). Never throws for content problems. */
object PackageLoader {
    /** Opens the zip and loads the package. The returned reader (if any) is owned by the caller. */
    fun open(source: RandomAccessSource): Pair<ZipReader?, LoadResult> {
        val zip = try { ZipReader.open(source) } catch (e: ZipFormatException) {
            return null to LoadResult.Failed(if (e.error == ZipError.NOT_A_ZIP) FailReason.NOT_A_ZIP else FailReason.DAMAGED_ARCHIVE, e.message ?: "")
        }
        return zip to load(zip)
    }

    fun load(zip: ZipReader): LoadResult {
        val warnings = ArrayList<PackageWarning>()
        val opfPath = ContainerParser.opfPath(zip) ?: run {
            val opfs = zip.entries.filter { it.name.endsWith(".opf", ignoreCase = true) }.map { it.name }.sorted()
            if (opfs.isEmpty()) return LoadResult.Failed(FailReason.NO_PACKAGE, "no package document found")
            warnings += PackageWarning(WarningKind.OPF_FOUND_BY_SEARCH, opfs.first())
            opfs.first()
        }
        val text = try { TextDecoding.decode(zip.readBytes(zip.entry(opfPath)!!, 8L shl 20)) } catch (e: ZipFormatException) {
            return LoadResult.Failed(FailReason.DAMAGED_ARCHIVE, e.message ?: "")
        }
        val doc = XmlTree.parse(text)
        val root = doc.root?.takeIf { it.localName == "package" }
            ?: return LoadResult.Failed(FailReason.BROKEN_PACKAGE, "package document has no <package> root")
        val opfDir = Paths.dirOf(opfPath)

        val manifest = root.first("manifest")?.elements()?.filter { it.localName == "item" }?.mapNotNull { e ->
            val id = e.attr("id") ?: return@mapNotNull null
            val href = e.attr("href") ?: return@mapNotNull null
            val path = Paths.resolve(opfDir, href)
            ManifestItem(id, path, e.attr("media-type") ?: "", e.attr("properties")?.split(' ')?.filter { it.isNotBlank() }?.toSet() ?: emptySet(),
                present = zip.entry(path) != null)
        } ?: emptyList()
        val byId = manifest.associateBy { it.id }

        val spineEl = root.first("spine")
        val spine = ArrayList<SpineItem>()
        spineEl?.elements()?.filter { it.localName == "itemref" }?.forEach { ref ->
            val idref = ref.attr("idref") ?: return@forEach
            val item = byId[idref]
            if (item == null) { warnings += PackageWarning(WarningKind.UNKNOWN_IDREF, idref); return@forEach }
            if (!item.present) warnings += PackageWarning(WarningKind.MISSING_FILE, item.path)
            spine += SpineItem(spine.size, idref, item.path, item.mediaType, ref.attr("linear") != "no", item.present)
        }

        if (!doc.wellFormed) {
            // R-002: an unparseable package is fatal unless the lenient tree still gives a usable spine.
            if (spine.none { it.present }) return LoadResult.Failed(FailReason.BROKEN_PACKAGE, doc.errors.first())
            warnings += PackageWarning(WarningKind.PACKAGE_RECOVERED, doc.errors.first())
        }
        if (spine.none { it.present }) return LoadResult.Failed(FailReason.NOTHING_READABLE, "the spine has no readable items")

        val uidAttr = root.attr("unique-identifier")
        val identifiers = root.first("metadata")?.all("identifier")?.toList().orEmpty()
        val uniqueId = (identifiers.firstOrNull { it.attr("id") == uidAttr } ?: identifiers.firstOrNull())?.text()?.trim()
        val metas = root.first("metadata")?.all("meta")?.toList().orEmpty()
        val fixed = metas.any { it.attr("property") == "rendition:layout" && it.text().trim() == "pre-paginated" } ||
            metas.any { it.attr("name") == "fixed-layout" && it.attr("content") == "true" }

        val pkg = BookPackage(
            opfPath = opfPath,
            version = root.attr("version") ?: "",
            uniqueId = uniqueId,
            metadata = BookMetadata(root.first("metadata")?.first("title")?.text()?.trim(), emptyList(), null, false),
            manifest = manifest,
            spine = spine,
            toc = emptyList(),
            pageProgression = spineEl?.attr("page-progression-direction"),
            fixedLayout = fixed,
            coverPath = null,
            opf = root,
        )
        return LoadResult.Ok(pkg, warnings)
    }
}
```

- [ ] **Step 3: Run tests, commit**

Run: `./gradlew :core:epub:jvmTest --tests '*PackageLoaderTest'` → Expected: all PASS.
```bash
git add core/epub
git commit -m "core/epub: package loading with R-001..R-004 classification

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2.4: Table of contents (nav and NCX)

**Files:**
- Create: `core/epub/src/commonMain/kotlin/app/epubreader/core/epub/pkg/TocParser.kt`
- Modify: `PackageLoader.kt` (set `toc = TocParser.parse(zip, pkgWithoutToc, warnings)`)
- Test: `core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/pkg/TocParserTest.kt`

**Interfaces:**
- Produces: `object TocParser { fun parse(zip: ZipReader, pkg: BookPackage, warnings: MutableList<PackageWarning>): List<TocEntry> }`.
  Prefers the EPUB 3 nav (`properties` contains `nav`, `nav` element with `epub:type` containing `toc`, else the first
  `nav`); falls back to the NCX (spine `toc` attribute, else media type `application/x-dtbncx+xml`). Labels have
  whitespace collapsed. `targetMissing = path != null && zip.entry(path) == null`, plus a `TOC_MISSING_TARGET` warning.

- [ ] **Step 1: Write the failing tests**

```kotlin
// core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/pkg/TocParserTest.kt
package app.epubreader.core.epub.pkg

import app.epubreader.core.epub.Corpus
import app.epubreader.core.epub.openFileSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TocParserTest {
    private fun pkg(name: String) = openFileSource(Corpus.generated(name).path).use { (PackageLoader.open(it).second as LoadResult.Ok).pkg }

    @Test fun nested_2000_chapters() {
        val toc = pkg("stress-2000-chapters.epub").toc
        assertEquals(20, toc.size)
        assertEquals(100, toc[0].children.size)
        val c1777 = toc.flatMap { it.children }.single { it.label == "Chapter 1777" }
        assertEquals("OEBPS/ch1777.xhtml", c1777.path)
    }

    @Test fun anchors_inside_single_document() {
        val toc = pkg("stress-huge-single-chapter.epub").toc
        assertTrue(toc.size >= 50)
        assertEquals("s40", toc.single { it.label == "Section 40" }.fragment)
    }

    @Test fun ncx_only_book() {
        val toc = pkg("epub2-ncx-only.epub").toc
        assertEquals(20, toc.size); assertEquals("Chapter 3", toc[2].label)
    }

    @Test fun missing_targets_are_marked() {
        val toc = pkg("malformed-missing-files-bad-xml.epub").toc
        assertEquals(true, toc.single { it.label == "Missing" }.targetMissing)
        assertEquals(false, toc.single { it.label == "Missing anchor" }.targetMissing) // file exists; anchor checked at tap time (M3)
        assertEquals("nope", toc.single { it.label == "Missing anchor" }.fragment)
    }
}
```

- [ ] **Step 2: Implement**

```kotlin
// core/epub/src/commonMain/kotlin/app/epubreader/core/epub/pkg/TocParser.kt
package app.epubreader.core.epub.pkg

import app.epubreader.core.epub.markup.Paths
import app.epubreader.core.epub.markup.TextDecoding
import app.epubreader.core.epub.markup.XmlElement
import app.epubreader.core.epub.markup.XmlTree
import app.epubreader.core.epub.zip.ZipReader

object TocParser {
    private val WS = Regex("\\s+")

    fun parse(zip: ZipReader, pkg: BookPackage, warnings: MutableList<PackageWarning>): List<TocEntry> {
        fun load(path: String): String? = zip.entry(path)?.let { runCatching { TextDecoding.decode(zip.readBytes(it, 16L shl 20)) }.getOrNull() }
        fun entry(label: String, href: String?, baseDir: String, kids: List<TocEntry>): TocEntry {
            if (href.isNullOrBlank() || Paths.isExternal(href)) return TocEntry(label, null, null, false, kids)
            val path = Paths.resolve(baseDir, href)
            val missing = zip.entry(path) == null
            if (missing) warnings += PackageWarning(WarningKind.TOC_MISSING_TARGET, "$label -> $path")
            return TocEntry(label, path, Paths.fragmentOf(href), missing, kids)
        }

        pkg.manifest.firstOrNull { "nav" in it.properties && it.present }?.let { nav ->
            val text = load(nav.path)
            val root = text?.let { XmlTree.parse(it, html = true).root }
            if (root == null) warnings += PackageWarning(WarningKind.NAV_UNREADABLE, nav.path)
            val navs = root?.all("nav")?.toList().orEmpty()
            val tocNav = navs.firstOrNull { (it.qattr("epub:type") ?: it.attr("type") ?: "").split(' ').contains("toc") } ?: navs.firstOrNull()
            val dir = Paths.dirOf(nav.path)
            fun ol(e: XmlElement?): List<TocEntry> = e?.elements()?.filter { it.localName == "li" }?.map { li ->
                val a = li.elements().firstOrNull { it.localName == "a" || it.localName == "span" }
                entry(WS.replace(a?.text() ?: "", " ").trim(), a?.attr("href"), dir, ol(li.elements().firstOrNull { it.localName == "ol" }))
            } ?: emptyList()
            val list = ol(tocNav?.first("ol"))
            if (list.isNotEmpty()) return list
        }

        val ncxId = pkg.opf.first("spine")?.attr("toc")
        val ncx = (ncxId?.let { pkg.item(it) } ?: pkg.manifest.firstOrNull { it.mediaType == "application/x-dtbncx+xml" })?.takeIf { it.present } ?: return emptyList()
        val root = load(ncx.path)?.let { XmlTree.parse(it).root } ?: return emptyList()
        val dir = Paths.dirOf(ncx.path)
        fun points(e: XmlElement?): List<TocEntry> = e?.elements()?.filter { it.localName == "navpoint" }?.map { np ->
            val label = WS.replace(np.first("navlabel")?.first("text")?.text() ?: "", " ").trim()
            entry(label, np.elements().firstOrNull { it.localName == "content" }?.attr("src"), dir, points(np))
        } ?: emptyList()
        return points(root.first("navmap"))
    }
}
```
In `PackageLoader.load`, after building `pkg`: `return LoadResult.Ok(pkg.copy(toc = TocParser.parse(zip, pkg, warnings)), warnings)`.

- [ ] **Step 3: Run tests, commit**

Run: `./gradlew :core:epub:jvmTest --tests '*TocParserTest' --tests '*PackageLoaderTest'` → Expected: PASS.
Commit: `core/epub: nav and NCX table of contents` (with trailer).

---

### Task 2.5: Metadata, cover location, book identity

**Files:**
- Create: `core/epub/src/commonMain/kotlin/app/epubreader/core/epub/meta/{MetadataReader.kt, CoverLocator.kt, BookIdentity.kt}`
- Modify: `PackageLoader.kt` (metadata + `coverPath` + `DANGLING_COVER` warning)
- Test: `core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/meta/{MetadataTest.kt, IdentityTest.kt}`

**Interfaces:**
- Produces: `object MetadataReader { fun read(opf: XmlElement): BookMetadata; fun isRtl(text: String): Boolean; fun sortKey(title: String): String }`,
  `object CoverLocator { fun locate(zip: ZipReader, pkg: BookPackage): CoverResult }` with
  `data class CoverResult(val path: String?, val danglingReference: String?)`,
  `object BookIdentity { fun of(uniqueId: String?, centralDirectoryDigest: String): String }` → `"<16 hex of sha256(uniqueId)>-<32 hex of cd digest>"`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/meta/MetadataTest.kt
package app.epubreader.core.epub.meta

import app.epubreader.core.epub.Corpus
import app.epubreader.core.epub.markup.XmlTree
import app.epubreader.core.epub.openFileSource
import app.epubreader.core.epub.pkg.LoadResult
import app.epubreader.core.epub.pkg.PackageLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetadataTest {
    private fun ok(name: String) = openFileSource(Corpus.generated(name).path).use { (PackageLoader.open(it).second as LoadResult.Ok) }

    @Test fun title_author_language() {
        val m = ok("no-cover.epub").pkg.metadata
        assertEquals("No Cover Book", m.title); assertEquals(listOf("Corpus Generator"), m.authors); assertEquals("en", m.language)
    }

    @Test fun epub3_creator_roles_prefer_authors() {
        val opf = XmlTree.parse("""<package xmlns:dc="d"><metadata><dc:creator id="a">Ann</dc:creator><meta refines="#a" property="role">aut</meta><dc:creator id="i">Ian</dc:creator><meta refines="#i" property="role">ill</meta></metadata></package>""").root!!
        assertEquals(listOf("Ann"), MetadataReader.read(opf).authors)
    }

    @Test fun rtl_detection() {
        assertTrue(MetadataReader.isRtl("نظام مضاد للسرطان")); assertTrue(MetadataReader.isRtl("שלום")); assertEquals(false, MetadataReader.isRtl("Alice"))
    }

    @Test fun sort_key_ignores_case_and_leading_article() = assertEquals("whale", MetadataReader.sortKey("The Whale"))

    @Test fun cover_from_epub3_property_or_meta() {
        assertTrue(ok("stress-200-images.epub").pkg.coverPath!!.endsWith(".png"))
        assertTrue(ok("epub2-ncx-only.epub").pkg.coverPath != null)  // EPUB 2 meta name="cover"
    }

    @Test fun no_cover_and_dangling_cover() {
        assertNull(ok("no-cover.epub").pkg.coverPath)
        val r = ok("malformed-missing-files-bad-xml.epub")
        assertTrue(r.warnings.any { it.kind.name == "DANGLING_COVER" })
    }
}
```

```kotlin
// core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/meta/IdentityTest.kt
package app.epubreader.core.epub.meta

import app.epubreader.core.epub.Corpus
import app.epubreader.core.epub.openFileSource
import app.epubreader.core.epub.pkg.LoadResult
import app.epubreader.core.epub.pkg.PackageLoader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class IdentityTest {
    private fun id(f: File) = openFileSource(f.path).use { src ->
        val (zip, r) = PackageLoader.open(src)
        BookIdentity.of((r as LoadResult.Ok).pkg.uniqueId, zip!!.centralDirectoryDigest)
    }

    @Test fun byte_identical_copies_have_equal_identity() {
        val a = Corpus.folder("dup.epub"); val b = File.createTempFile("dup-copy", ".epub").also { a.copyTo(it, overwrite = true) }
        assertEquals(id(a), id(b))
    }

    @Test fun different_books_differ() = assertNotEquals(id(Corpus.folder("download.epub")), id(Corpus.folder("documents.epub")))

    @Test fun format() = assertEquals(49, id(Corpus.folder("dup.epub")).length) // 16 + '-' + 32
}
```
(The P&P EPUB 2 vs EPUB 3 check runs in `CorpusTest` when the downloaded corpus is present.)

- [ ] **Step 2: Implement**

```kotlin
// core/epub/src/commonMain/kotlin/app/epubreader/core/epub/meta/MetadataReader.kt
package app.epubreader.core.epub.meta

import app.epubreader.core.epub.markup.XmlElement
import app.epubreader.core.epub.pkg.BookMetadata

object MetadataReader {
    private val WS = Regex("\\s+")
    private val ARTICLES = listOf("the ", "a ", "an ", "le ", "la ", "les ", "l'", "der ", "die ", "das ", "el ", "los ", "las ")

    fun read(opf: XmlElement): BookMetadata {
        val md = opf.first("metadata")
        val refines = md?.all("meta")?.mapNotNull { m -> m.attr("refines")?.removePrefix("#")?.let { it to ((m.attr("property") ?: "") to m.text().trim()) } }?.toList().orEmpty()
        fun refined(id: String?, prop: String) = refines.firstOrNull { it.first == id && it.second.first == prop }?.second?.second
        val titles = md?.all("title")?.toList().orEmpty()
        val title = (titles.firstOrNull { refined(it.attr("id"), "title-type") == "main" } ?: titles.firstOrNull())?.text()?.let { WS.replace(it, " ").trim() }?.takeIf { it.isNotEmpty() }
        val creators = md?.all("creator")?.toList().orEmpty()
        fun role(c: XmlElement) = c.attr("role") ?: refined(c.attr("id"), "role")
        val authors = (creators.filter { role(it) == "aut" }.ifEmpty { creators.filter { role(it) == null }.ifEmpty { creators } })
            .map { WS.replace(it.text(), " ").trim() }.filter { it.isNotEmpty() }
        val lang = md?.first("language")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        return BookMetadata(title, authors, lang, title?.let(::isRtl) ?: false)
    }

    /** True if the first strong directional character is Hebrew or Arabic script. */
    fun isRtl(text: String): Boolean {
        for (c in text) {
            val v = c.code
            if (v in 0x0590..0x08FF || v in 0xFB1D..0xFDFF || v in 0xFE70..0xFEFF) return true
            if (c.isLetter()) return false
        }
        return false
    }

    fun sortKey(title: String): String {
        val t = title.trim().lowercase()
        val a = ARTICLES.firstOrNull { t.startsWith(it) && t.length > it.length }
        return if (a != null) t.substring(a.length) else t
    }
}
```

```kotlin
// core/epub/src/commonMain/kotlin/app/epubreader/core/epub/meta/CoverLocator.kt
package app.epubreader.core.epub.meta

import app.epubreader.core.epub.markup.MarkupScanner
import app.epubreader.core.epub.markup.MarkupVisitor
import app.epubreader.core.epub.markup.Attr
import app.epubreader.core.epub.markup.Paths
import app.epubreader.core.epub.markup.TextDecoding
import app.epubreader.core.epub.pkg.BookPackage
import app.epubreader.core.epub.zip.ZipReader

data class CoverResult(val path: String?, val danglingReference: String?)

/** F-021: EPUB 3 cover-image property, EPUB 2 meta name="cover", else the first image of the first spine item. */
object CoverLocator {
    fun locate(zip: ZipReader, pkg: BookPackage): CoverResult {
        pkg.manifest.firstOrNull { "cover-image" in it.properties && it.present }?.let { return CoverResult(it.path, null) }
        var dangling: String? = null
        pkg.opf.first("metadata")?.all("meta")?.firstOrNull { it.attr("name") == "cover" }?.attr("content")?.let { ref ->
            val item = pkg.item(ref) ?: pkg.manifest.firstOrNull { it.path.endsWith(ref) }
            if (item != null && item.present && item.mediaType.startsWith("image/")) return CoverResult(item.path, null)
            dangling = ref
        }
        val first = pkg.spine.firstOrNull { it.present } ?: return CoverResult(null, dangling)
        val markup = runCatching { TextDecoding.decode(zip.readBytes(zip.entry(first.path)!!, 4L shl 20)) }.getOrNull() ?: return CoverResult(null, dangling)
        var src: String? = null
        MarkupScanner.scan(markup, object : MarkupVisitor {
            override fun startTag(name: String, attrs: List<Attr>, selfClosing: Boolean, start: Int, end: Int) {
                if (src != null) return
                val n = name.substringAfter(':').lowercase()
                if (n == "img") src = attrs.firstOrNull { it.localName == "src" }?.value
                if (n == "image") src = attrs.firstOrNull { it.localName == "href" }?.value
            }
        })
        val path = src?.takeIf { !Paths.isExternal(it) }?.let { Paths.resolve(Paths.dirOf(first.path), it) }?.takeIf { zip.entry(it) != null }
        return CoverResult(path, dangling)
    }
}
```
(The scanner keeps scanning after the first image; for 10 MB first documents this costs one pass — acceptable at
open/scan time; the first spine item is almost always small.)

```kotlin
// core/epub/src/commonMain/kotlin/app/epubreader/core/epub/meta/BookIdentity.kt
package app.epubreader.core.epub.meta

import app.epubreader.core.epub.sha256Hex

/** F-012/F-015: identity by content — package identifier plus a digest of the zip central directory. */
object BookIdentity {
    fun of(uniqueId: String?, centralDirectoryDigest: String): String =
        sha256Hex((uniqueId ?: "").encodeToByteArray()).take(16) + "-" + centralDirectoryDigest.take(32)
}
```
In `PackageLoader.load`: `metadata = MetadataReader.read(root)`; after TOC:
`val cover = CoverLocator.locate(zip, pkg); cover.danglingReference?.let { warnings += PackageWarning(WarningKind.DANGLING_COVER, it) }`
and `pkg.copy(toc = …, coverPath = cover.path)`.

- [ ] **Step 3: Run tests, commit**

Run: `./gradlew :core:epub:jvmTest` → Expected: all PASS.
Commit: `core/epub: metadata, cover location and content identity` (with trailer).

---

### Task 2.6: Canonical text extraction

**Files:**
- Create: `core/epub/src/commonMain/kotlin/app/epubreader/core/epub/text/TextExtractor.kt`
- Test: `core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/text/TextExtractorTest.kt`

**Interfaces:**
- Produces: `class ExtractedText(val text: String, val segments: List<Segment>, val imageCount: Int, val cutTextOffsets: IntArray)`,
  `data class Segment(val start: Int, val end: Int)`, `object TextExtractor { fun extract(markup: String, cuts: IntArray = IntArray(0)): ExtractedText }`.
- **Canonical text rules** (the JS hit resolver in M5 Task 5.3 implements the same walk; copy this list into
  `docs/bridge-protocol.md` §"Canonical text"): (1) only content inside `<body>` (whole document if there is no body
  tag, still excluding `<head>`); (2) text and CDATA in document order; (3) skip everything inside `script`, `style`,
  `template`, `head`; (4) character references decoded as in `Entities` (unknown stay literal); (5) raw CR and CRLF →
  LF; (6) no whitespace collapsing. Segments break at block-element boundaries, merged to ≥ 200 chars, split at
  whitespace to ≤ 8000 chars; whitespace-only segments dropped. `cutTextOffsets[i]` = text length at the first token
  starting at or after markup offset `cuts[i]` (cuts must be ascending).

- [ ] **Step 1: Write the failing tests**

```kotlin
// core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/text/TextExtractorTest.kt
package app.epubreader.core.epub.text

import app.epubreader.core.epub.Corpus
import app.epubreader.core.epub.markup.TextDecoding
import app.epubreader.core.epub.openFileSource
import app.epubreader.core.epub.zip.ZipReader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextExtractorTest {
    @Test fun crlf_entities_cdata_script() {
        val x = TextExtractor.extract("<html><head><title>T</title><style>p{}</style></head>\r\n<body>\r\n<p>A&nbsp;b &amp; c&#13;d</p><script>var x=1</script><![CDATA[e<f]]>\r<p>&bogus; &</p></body></html>")
        assertEquals("\nA b & c\rd" + "e<f\n" + "&bogus; &", x.text)
    }

    @Test fun no_body_tag_counts_everything_but_head() =
        assertEquals("hi there", TextExtractor.extract("<head><title>x</title></head><p>hi</p> <p>there</p>").text)

    @Test fun cut_offsets_map_markup_to_text() {
        val m = "<body><p>abc</p><p>defg</p><p>h</p></body>"
        val x = TextExtractor.extract(m, intArrayOf(m.indexOf("<p>defg"), m.indexOf("<p>h")))
        assertContentEquals(intArrayOf(3, 7), x.cutTextOffsets)
    }

    @Test fun segments_respect_blocks_and_bounds() {
        val m = "<body>" + (1..50).joinToString("") { "<p>${"word ".repeat(30)}$it</p>" } + "<div>${"x".repeat(20_000)}</div></body>"
        val x = TextExtractor.extract(m)
        assertTrue(x.segments.all { it.end - it.start <= 8000 })
        assertTrue(x.segments.dropLast(1).all { it.end - it.start >= 200 })
        assertEquals(x.text.length, x.segments.last().end)
    }

    @Test fun huge_chapter_contains_marker_138_times_and_counts_images() {
        openFileSource(Corpus.generated("stress-huge-single-chapter.epub").path).use { src ->
            val z = ZipReader.open(src)
            val m = TextDecoding.decode(z.readBytes(z.entry("OEBPS/huge.xhtml")!!))
            val t0 = System.nanoTime()
            val x = TextExtractor.extract(m)
            println("extract 10 MB: ${(System.nanoTime() - t0) / 1_000_000} ms")
            assertEquals(138, Regex("MARKERWORDHUGE").findAll(x.text).count())
        }
    }
}
```

- [ ] **Step 2: Implement**

```kotlin
// core/epub/src/commonMain/kotlin/app/epubreader/core/epub/text/TextExtractor.kt
package app.epubreader.core.epub.text

import app.epubreader.core.epub.markup.Attr
import app.epubreader.core.epub.markup.Entities
import app.epubreader.core.epub.markup.MarkupScanner
import app.epubreader.core.epub.markup.MarkupVisitor

data class Segment(val start: Int, val end: Int)

class ExtractedText(val text: String, val segments: List<Segment>, val imageCount: Int, val cutTextOffsets: IntArray)

/** Canonical plain text of a content document; see the rules in the M2 plan / docs/bridge-protocol.md. */
object TextExtractor {
    const val MIN_SEGMENT = 200
    const val MAX_SEGMENT = 8000
    private val SKIP = setOf("script", "style", "template", "head")
    private val BLOCK = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre", "td", "th", "tr",
        "dt", "dd", "section", "article", "aside", "figcaption", "figure", "table", "ul", "ol", "header", "footer", "nav", "br", "hr")
    private val IMAGES = setOf("img", "image", "svg")

    fun extract(markup: String, cuts: IntArray = IntArray(0)): ExtractedText {
        val hasBody = MarkupScanner.indexOfIgnoreCase(markup, "<body", 0, markup.length) >= 0
        val sb = StringBuilder(markup.length / 2)
        val bounds = ArrayList<Int>()
        val cutOut = IntArray(cuts.size)
        var nextCut = 0
        var inBody = !hasBody
        var skip = 0
        var images = 0
        fun mark(pos: Int) { while (nextCut < cuts.size && cuts[nextCut] <= pos) cutOut[nextCut++] = sb.length }

        MarkupScanner.scan(markup, object : MarkupVisitor {
            override fun startTag(name: String, attrs: List<Attr>, selfClosing: Boolean, start: Int, end: Int) {
                mark(start)
                val n = name.substringAfter(':').lowercase()
                if (n == "body") { inBody = true; return }
                if (n in SKIP && !selfClosing) skip++
                if (n in BLOCK) bounds += sb.length
                if (n in IMAGES && inBody && skip == 0) images++
            }
            override fun endTag(name: String, start: Int, end: Int) {
                mark(start)
                val n = name.substringAfter(':').lowercase()
                if (n == "body") { inBody = false; return }
                if (n in SKIP && skip > 0) skip--
                if (n in BLOCK) bounds += sb.length
            }
            override fun text(start: Int, end: Int) {
                mark(start)
                if (inBody && skip == 0) Entities.appendDecoded(markup, start, end, sb)
            }
            override fun cdata(start: Int, end: Int) {
                mark(start)
                if (!inBody || skip != 0) return
                // CDATA is literal: only newline normalization applies.
                var i = start
                while (i < end) {
                    val c = markup[i]
                    if (c == '\r') { sb.append('\n'); if (i + 1 < end && markup[i + 1] == '\n') i++ } else sb.append(c)
                    i++
                }
            }
        })
        mark(Int.MAX_VALUE)
        val text = sb.toString()
        return ExtractedText(text, segments(text, bounds), images, cutOut)
    }

    private fun segments(text: String, bounds: List<Int>): List<Segment> {
        val out = ArrayList<Segment>()
        fun emit(a: Int, b: Int) {
            var s = a
            while (b - s > MAX_SEGMENT) {
                // Prefer a space in the second half of the window so no piece ends up tiny.
                val ws = text.lastIndexOf(' ', s + MAX_SEGMENT)
                val cut = if (ws >= s + MAX_SEGMENT / 2) ws + 1 else s + MAX_SEGMENT
                if (text.substring(s, cut).isNotBlank()) out += Segment(s, cut)
                s = cut
            }
            if (b > s && text.substring(s, b).isNotBlank()) out += Segment(s, b)
        }
        var start = 0
        for (b in bounds.asSequence().plus(text.length).distinct().sorted()) {
            if (b - start >= MIN_SEGMENT || b == text.length) { emit(start, b); start = b }
        }
        return out
    }
}
```

- [ ] **Step 3: Run tests, commit**

Run: `./gradlew :core:epub:jvmTest --tests '*TextExtractorTest'` → Expected: PASS; printed extraction time for 10 MB
(record it in the commit body). Commit: `core/epub: canonical text extraction with segments and cut offsets`.

---

### Task 2.7: CFI utilities and the chunk map

**Files:**
- Create: `core/epub/src/commonMain/kotlin/app/epubreader/core/epub/cfi/Cfi.kt`
- Create: `core/epub/src/commonMain/kotlin/app/epubreader/core/epub/chunk/ChunkMap.kt`
- Test: `core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/cfi/CfiTest.kt`

(Task 2.8 adds the chunker and the end-to-end `ChunkMapTest`; this task builds and unit-tests the mapping on a
hand-written map so the maths is pinned before the splitter exists.)

**Interfaces:**
- Produces:
```kotlin
data class CfiStep(val index: Int, val assertion: String? = null, val indirect: Boolean = false)
data class CfiPath(val steps: List<CfiStep>, val terminal: String = "")      // terminal: raw ":12[...]" / "~..." / "@..."
data class Cfi(val parent: CfiPath, val start: CfiPath? = null, val end: CfiPath? = null) {
    val isRange: Boolean; override fun toString(): String   // "epubcfi(...)"
    fun startPoint(): CfiPath; fun endPoint(): CfiPath       // parent + start/end
    companion object { fun parse(s: String): Cfi?; fun range(a: CfiPath, b: CfiPath): Cfi }
}
data class TextPos(val spineIndex: Int, val offset: Int)
@Serializable data class ChunkMap(val policyKey: String, val spinePaths: List<String>, val virtualSpine: List<VSpine>, val docs: Map<String, SplitDoc>) {
    @Serializable data class VSpine(val orig: Int, val part: Int)                       // part = -1: not split
    @Serializable data class SplitDoc(val path: String, val wrapperStep: Int?, val parts: List<Part>, val idToPart: Map<String, Int>)
    @Serializable data class Part(val path: String, val childOffset: Int, val childCount: Int, val textStart: Int, val textLength: Int)
    val isIdentity: Boolean
    fun virtualIndexOf(orig: Int, part: Int): Int
    fun cfiToOriginal(cfi: String): String; fun cfiToVirtual(cfi: String): String
    fun hrefToVirtual(pathWithFragment: String): String; fun hrefToOriginal(pathWithFragment: String): String
    fun textToVirtual(p: TextPos): TextPos; fun textToOriginal(p: TextPos): TextPos
    val virtualPaths: Set<String>        // all part paths
    companion object { fun identity(spinePaths: List<String>, policyKey: String): ChunkMap }
}
```

- [ ] **Step 1: Write the failing tests**

```kotlin
// core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/cfi/CfiTest.kt
package app.epubreader.core.epub.cfi

import app.epubreader.core.epub.chunk.ChunkMap
import app.epubreader.core.epub.chunk.ChunkMap.Part
import app.epubreader.core.epub.chunk.ChunkMap.SplitDoc
import app.epubreader.core.epub.chunk.ChunkMap.VSpine
import app.epubreader.core.epub.chunk.TextPos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CfiTest {
    @Test fun parse_and_print_point() {
        val s = "epubcfi(/6/14[chap05ref]!/4[body01]/10/2/1:3[2^[1^]])"
        assertEquals(s, Cfi.parse(s).toString())
    }
    @Test fun parse_and_print_range() {
        val s = "epubcfi(/6/4!/4/10,/2/1:1,/3:4)"
        val c = Cfi.parse(s)!!
        assertEquals(s, c.toString()); assertEquals(true, c.isRange)
        assertEquals("epubcfi(/6/4!/4/10/2/1:1)", Cfi(c.startPoint()).toString())
    }
    @Test fun garbage_is_null() = assertNull(Cfi.parse("not a cfi"))

    // Original spine: [a.xhtml, big.xhtml, c.xhtml]; big split into 3 parts at body level:
    // part0 children 0..9, part1 10..24, part2 25..29. Virtual spine: a, big#0, big#1, big#2, c.
    private val map = ChunkMap(
        policyKey = "test",
        spinePaths = listOf("a.xhtml", "big.xhtml", "c.xhtml"),
        virtualSpine = listOf(VSpine(0, -1), VSpine(1, 0), VSpine(1, 1), VSpine(1, 2), VSpine(2, -1)),
        docs = mapOf("big.xhtml" to SplitDoc("big.xhtml", null, listOf(
            Part("big.xhtml.part0.xhtml", 0, 10, 0, 1000),
            Part("big.xhtml.part1.xhtml", 10, 15, 1000, 1500),
            Part("big.xhtml.part2.xhtml", 25, 5, 2500, 500)), mapOf("x" to 1))),
    )

    @Test fun virtual_to_original_shifts_spine_and_child_step() {
        // 3rd element of part1 (step 6) = original element 12 (step 26); virtual spine 2 -> original spine 1
        assertEquals("epubcfi(/6/4!/4/26/1:5)", map.cfiToOriginal("epubcfi(/6/6!/4/6/1:5)"))
        // non-split doc after the split one: virtual 4 -> original 2
        assertEquals("epubcfi(/6/6!/4/2/1:0)", map.cfiToOriginal("epubcfi(/6/10!/4/2/1:0)"))
    }

    @Test fun original_to_virtual_picks_part() {
        assertEquals("epubcfi(/6/6!/4/6/1:5)", map.cfiToVirtual("epubcfi(/6/4!/4/26/1:5)"))
        assertEquals("epubcfi(/6/8!/4/2)", map.cfiToVirtual("epubcfi(/6/4!/4/52)"))         // element 25 -> part2 first
        assertEquals("epubcfi(/6/8!/4/11)", map.cfiToVirtual("epubcfi(/6/4!/4/61)"))        // gap after last element
    }

    @Test fun round_trip_every_element_and_gap() {
        for (step in 1..61) {
            val o = "epubcfi(/6/4!/4/$step/1:0)"
            assertEquals(o, map.cfiToOriginal(map.cfiToVirtual(o)), o)
        }
    }

    @Test fun range_within_part_round_trips() {
        val o = "epubcfi(/6/4!/4/26,/1:2,/1:9)"
        assertEquals(o, map.cfiToOriginal(map.cfiToVirtual(o)))
    }

    @Test fun body_only_cfi_points_at_part_start() =
        assertEquals("epubcfi(/6/4!/4/22)", map.cfiToOriginal("epubcfi(/6/6!/4)"))

    @Test fun hrefs() {
        assertEquals("big.xhtml.part1.xhtml#x", map.hrefToVirtual("big.xhtml#x"))
        assertEquals("big.xhtml.part0.xhtml", map.hrefToVirtual("big.xhtml"))
        assertEquals("big.xhtml#x", map.hrefToOriginal("big.xhtml.part1.xhtml#x"))
        assertEquals("c.xhtml#y", map.hrefToVirtual("c.xhtml#y"))
    }

    @Test fun text_positions() {
        assertEquals(TextPos(2, 200), map.textToVirtual(TextPos(1, 1200)))
        assertEquals(TextPos(1, 1200), map.textToOriginal(TextPos(2, 200)))
        assertEquals(TextPos(4, 7), map.textToVirtual(TextPos(2, 7)))
        assertEquals(TextPos(3, 500), map.textToVirtual(TextPos(1, 3000)))  // end of text -> last part end
    }

    @Test fun identity_map_is_pass_through() {
        val id = ChunkMap.identity(listOf("a", "b"), "p")
        assertEquals("epubcfi(/6/4!/4/2/1:0)", id.cfiToVirtual("epubcfi(/6/4!/4/2/1:0)"))
        assertEquals(TextPos(1, 5), id.textToVirtual(TextPos(1, 5)))
    }
}
```
Run: `./gradlew :core:epub:jvmTest --tests '*CfiTest'` → Expected: compilation FAIL.

- [ ] **Step 2: Implement `Cfi`**

```kotlin
// core/epub/src/commonMain/kotlin/app/epubreader/core/epub/cfi/Cfi.kt
package app.epubreader.core.epub.cfi

data class CfiStep(val index: Int, val assertion: String? = null, val indirect: Boolean = false)
data class CfiPath(val steps: List<CfiStep>, val terminal: String = "")

/** Minimal EPUB CFI model: enough to remap spine and element steps; terminals are kept verbatim. */
data class Cfi(val parent: CfiPath, val start: CfiPath? = null, val end: CfiPath? = null) {
    val isRange: Boolean get() = start != null

    fun startPoint() = if (start == null) parent else CfiPath(parent.steps + start.steps, start.terminal)
    fun endPoint() = if (end == null) parent else CfiPath(parent.steps + end.steps, end.terminal)

    override fun toString(): String = buildString {
        append("epubcfi("); appendPath(this, parent)
        if (start != null && end != null) { append(','); appendPath(this, start); append(','); appendPath(this, end) }
        append(')')
    }

    companion object {
        fun parse(s: String): Cfi? {
            val t = s.trim()
            if (!t.startsWith("epubcfi(") || !t.endsWith(")")) return null
            val parts = splitTopLevel(t.substring(8, t.length - 1))
            return runCatching {
                when (parts.size) {
                    1 -> Cfi(parsePath(parts[0]))
                    3 -> Cfi(parsePath(parts[0]), parsePath(parts[1]), parsePath(parts[2]))
                    else -> null
                }
            }.getOrNull()
        }

        /** Builds a range from two full points by factoring out their common step prefix. */
        fun range(a: CfiPath, b: CfiPath): Cfi {
            var n = 0
            while (n < a.steps.size - 1 && n < b.steps.size - 1 && a.steps[n] == b.steps[n]) n++
            return Cfi(CfiPath(a.steps.take(n)), CfiPath(a.steps.drop(n), a.terminal), CfiPath(b.steps.drop(n), b.terminal))
        }

        private fun appendPath(sb: StringBuilder, p: CfiPath) {
            for (st in p.steps) {
                sb.append('/').append(st.index)
                st.assertion?.let { sb.append('[').append(it).append(']') }
                if (st.indirect) sb.append('!')
            }
            sb.append(p.terminal)
        }

        private fun splitTopLevel(s: String): List<String> {
            val out = ArrayList<String>(); var depth = 0; var last = 0; var i = 0
            while (i < s.length) {
                when (s[i]) { '^' -> i++; '[' -> depth++; ']' -> depth--; ',' -> if (depth == 0) { out += s.substring(last, i); last = i + 1 } }
                i++
            }
            out += s.substring(last)
            return out
        }

        private fun parsePath(s: String): CfiPath {
            val steps = ArrayList<CfiStep>(); var i = 0
            while (i < s.length && s[i] == '/') {
                i++
                val ns = i
                while (i < s.length && s[i].isDigit()) i++
                require(i > ns) { "step index expected" }
                val idx = s.substring(ns, i).toInt()
                var assertion: String? = null
                if (i < s.length && s[i] == '[') {
                    val a = i + 1; i++
                    while (i < s.length && s[i] != ']') { if (s[i] == '^') i++; i++ }
                    assertion = s.substring(a, i); i++
                }
                var indirect = false
                if (i < s.length && s[i] == '!') { indirect = true; i++ }
                steps += CfiStep(idx, assertion, indirect)
            }
            return CfiPath(steps, s.substring(i))
        }
    }
}
```

- [ ] **Step 3: Implement `ChunkMap`**

```kotlin
// core/epub/src/commonMain/kotlin/app/epubreader/core/epub/chunk/ChunkMap.kt
package app.epubreader.core.epub.chunk

import app.epubreader.core.epub.cfi.Cfi
import app.epubreader.core.epub.cfi.CfiPath
import app.epubreader.core.epub.cfi.CfiStep
import kotlinx.serialization.Serializable

data class TextPos(val spineIndex: Int, val offset: Int)

/**
 * Bidirectional mapping between the original spine/documents and the virtual (chunked) ones the engine sees.
 * Stored positions are always ORIGINAL; the engine only ever receives VIRTUAL ones.
 *
 * CFI rule: a part's container (body, or the single wrapper element) holds original element children
 * [childOffset, childOffset + childCount); a container-child step s (even = element, odd = gap) maps by ±2*childOffset.
 */
@Serializable
data class ChunkMap(
    val policyKey: String,
    val spinePaths: List<String>,
    val virtualSpine: List<VSpine>,
    val docs: Map<String, SplitDoc>,
) {
    @Serializable data class VSpine(val orig: Int, val part: Int)
    @Serializable data class SplitDoc(val path: String, val wrapperStep: Int?, val parts: List<Part>, val idToPart: Map<String, Int>)
    @Serializable data class Part(val path: String, val childOffset: Int, val childCount: Int, val textStart: Int, val textLength: Int)

    val isIdentity: Boolean get() = docs.isEmpty()
    val virtualPaths: Set<String> by lazy { docs.values.flatMap { d -> d.parts.map { it.path } }.toSet() }
    private val vIndex: Map<Long, Int> by lazy { virtualSpine.withIndex().associate { (i, v) -> key(v.orig, v.part) to i } }
    private val partToDoc: Map<String, Pair<SplitDoc, Int>> by lazy { docs.values.flatMap { d -> d.parts.mapIndexed { i, p -> p.path to (d to i) } }.toMap() }
    private fun key(orig: Int, part: Int) = (orig.toLong() shl 32) or (part.toLong() and 0xffffffffL)

    fun virtualIndexOf(orig: Int, part: Int): Int = vIndex[key(orig, part)] ?: error("no virtual spine item for ($orig, $part)")

    // ---- hrefs -------------------------------------------------------------------------------------------
    fun hrefToVirtual(pathWithFragment: String): String {
        val path = pathWithFragment.substringBefore('#'); val frag = pathWithFragment.substringAfter('#', "")
        val d = docs[path] ?: return pathWithFragment
        val p = if (frag.isEmpty()) 0 else d.idToPart[frag] ?: 0
        return d.parts[p].path + if (frag.isEmpty()) "" else "#$frag"
    }

    fun hrefToOriginal(pathWithFragment: String): String {
        val path = pathWithFragment.substringBefore('#'); val frag = pathWithFragment.substringAfter('#', "")
        val (d, _) = partToDoc[path] ?: return pathWithFragment
        return d.path + if (frag.isEmpty()) "" else "#$frag"
    }

    // ---- text positions ------------------------------------------------------------------------------------
    fun textToVirtual(p: TextPos): TextPos {
        val d = docs[spinePaths.getOrNull(p.spineIndex)] ?: return if (isIdentity) p else TextPos(virtualIndexOf(p.spineIndex, -1), p.offset)
        val i = d.parts.indexOfLast { it.textStart <= p.offset }.coerceAtLeast(0)
        return TextPos(virtualIndexOf(p.spineIndex, i), p.offset - d.parts[i].textStart)
    }

    fun textToOriginal(p: TextPos): TextPos {
        val v = virtualSpine.getOrNull(p.spineIndex) ?: return p
        if (v.part < 0) return TextPos(v.orig, p.offset)
        val d = docs.getValue(spinePaths[v.orig])
        return TextPos(v.orig, d.parts[v.part].textStart + p.offset)
    }

    // ---- CFIs ------------------------------------------------------------------------------------------------
    fun cfiToOriginal(cfi: String): String = mapCfi(cfi, ::pointToOriginal)
    fun cfiToVirtual(cfi: String): String = mapCfi(cfi, ::pointToVirtual)

    private fun mapCfi(cfi: String, f: (List<CfiStep>) -> List<CfiStep>): String {
        if (isIdentity) return cfi
        val c = Cfi.parse(cfi) ?: return cfi
        if (!c.isRange) return Cfi(CfiPath(f(c.parent.steps), c.parent.terminal)).toString()
        val a = c.startPoint(); val b = c.endPoint()
        return Cfi.range(CfiPath(f(a.steps), a.terminal), CfiPath(f(b.steps), b.terminal)).toString()
    }

    /** Index in the step list of the container-child step: /6/N!/4/<k> or /6/N!/4/<wrapper>/<k>. */
    private fun childStepIndex(d: SplitDoc) = if (d.wrapperStep == null) 3 else 4

    private fun pointToOriginal(steps: List<CfiStep>): List<CfiStep> {
        if (steps.size < 2 || !steps[1].indirect) return steps
        val v = virtualSpine.getOrNull(steps[1].index / 2 - 1) ?: return steps
        val out = steps.toMutableList()
        out[1] = CfiStep(2 * (v.orig + 1), null, true)
        if (v.part < 0) return out
        val d = docs.getValue(spinePaths[v.orig]); val part = d.parts[v.part]; val k = childStepIndex(d)
        if (out.size <= k) {
            if (d.wrapperStep != null && out.size == 3) out += CfiStep(d.wrapperStep)
            out += CfiStep(2 * (part.childOffset + 1))
            return out
        }
        out[k] = out[k].copy(index = out[k].index + 2 * part.childOffset)
        return out
    }

    private fun pointToVirtual(steps: List<CfiStep>): List<CfiStep> {
        if (steps.size < 2 || !steps[1].indirect) return steps
        val orig = steps[1].index / 2 - 1
        val out = steps.toMutableList()
        val d = docs[spinePaths.getOrNull(orig) ?: return steps]
        if (d == null) { out[1] = CfiStep(2 * (virtualIndexOf(orig, -1) + 1), null, true); return out }
        val k = childStepIndex(d)
        if (out.size <= k) { out[1] = CfiStep(2 * (virtualIndexOf(orig, 0) + 1), null, true); return out }
        val s = out[k].index
        val elem = if (s % 2 == 0) s / 2 - 1 else (s - 1) / 2
        val p = d.parts.indexOfLast { it.childOffset <= elem }.coerceAtLeast(0)
        out[1] = CfiStep(2 * (virtualIndexOf(orig, p) + 1), null, true)
        out[k] = out[k].copy(index = s - 2 * d.parts[p].childOffset)
        return out
    }

    companion object {
        fun identity(spinePaths: List<String>, policyKey: String) =
            ChunkMap(policyKey, spinePaths, spinePaths.indices.map { VSpine(it, -1) }, emptyMap())
    }
}
```

- [ ] **Step 4: Run tests, commit**

Run: `./gradlew :core:epub:jvmTest --tests '*CfiTest'` → Expected: PASS.
Commit: `core/epub: CFI model and chunk position mapping` (with trailer).

---

### Task 2.8: Production chunker with disk cache and link retargeting

**Files:**
- Create: `core/epub/src/commonMain/kotlin/app/epubreader/core/epub/chunk/{ChunkPolicy.kt, ChunkStore.kt, SplitPlanner.kt, Chunker.kt, ChunkedBook.kt}`
- Create: `core/epub/src/jvmShared/kotlin/app/epubreader/core/epub/FileChunkStore.kt`
- Test: `core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/chunk/{ChunkerTest.kt, ChunkMapTest.kt}`

**Interfaces:**
- Consumes: `ZipReader`, `BookPackage`, `MarkupScanner`, `MarkupRewriter`, `TextExtractor` (cut offsets), `ChunkMap`.
- Produces:
```kotlin
data class ChunkPolicy(val thresholdBytes: Int = 512 * 1024, val targetBytes: Int = 128 * 1024) { val key: String }
interface ChunkStore { fun read(name: String): ByteArray?; fun write(name: String, bytes: ByteArray) }
class MemoryChunkStore : ChunkStore
class FileChunkStore(dir: String) : ChunkStore        // jvmShared; atomic write (temp + rename)
class ChunkedBook(val map: ChunkMap, private val store: ChunkStore) {
    val virtualPaths: Set<String>                     // parts + rewritten OPF/nav/NCX/content docs
    fun virtualEntry(path: String): ByteArray?        // null -> serve from the zip
}
class Chunker(private val policy: ChunkPolicy = ChunkPolicy()) {
    fun chunk(zip: ZipReader, pkg: BookPackage, store: ChunkStore): ChunkedBook   // cached when store has map.json
}
internal class SplitPlan(val bodyContentStart: Int, val bodyEnd: Int, val wrapperOpen: IntRange?, val wrapperName: String?,
    val wrapperInnerEnd: Int, val partRanges: List<IntRange>, val childOffsets: IntArray, val childCounts: IntArray)
internal object SplitPlanner { fun plan(markup: String, targetBytes: Int): SplitPlan? }
```
- Store layout: `map.json` (ChunkMap, written **last** = commit marker), `rewritten.json` (list of rewritten
  non-part paths), and one file per virtual path. The app passes a `FileChunkStore` rooted at
  `cacheDir/chunks/<identity>/<policy.key>/`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/chunk/ChunkerTest.kt
package app.epubreader.core.epub.chunk

import app.epubreader.core.epub.Corpus
import app.epubreader.core.epub.b
import app.epubreader.core.epub.markup.TextDecoding
import app.epubreader.core.epub.openFileSource
import app.epubreader.core.epub.pkg.LoadResult
import app.epubreader.core.epub.pkg.PackageLoader
import app.epubreader.core.epub.text.TextExtractor
import app.epubreader.core.epub.zip.ByteArraySource
import app.epubreader.core.epub.zip.ZipReader
import app.epubreader.core.epub.zipOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChunkerTest {
    private fun book(body: String, other: String = "<html><body><p><a href=\"big.xhtml#p77\">to 77</a></p></body></html>"): Pair<ZipReader, LoadResult.Ok> {
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="u"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="u">t</dc:identifier><dc:title>T</dc:title></metadata><manifest><item id="big" href="big.xhtml" media-type="application/xhtml+xml"/><item id="o" href="other.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="big"/><itemref idref="o"/></spine></package>"""
        val nav = """<html xmlns:epub="http://www.idpf.org/2007/ops"><body><nav epub:type="toc"><ol><li><a href="big.xhtml#p500">P500</a></li><li><a href="other.xhtml">O</a></li></ol></nav></body></html>"""
        val big = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>B</title></head><body>$body</body></html>"""
        val z = ZipReader.open(ByteArraySource(zipOf("META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OPS/p.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".b(),
            "OPS/p.opf" to opf.b(), "OPS/big.xhtml" to big.b(), "OPS/other.xhtml" to other.b(), "OPS/nav.xhtml" to nav.b())))
        return z to PackageLoader.load(z) as LoadResult.Ok
    }
    private fun paras(n: Int) = (0 until n).joinToString("\n") { "<p id=\"p$it\">Paragraph $it ${"lorem ipsum ".repeat(20)}<a href=\"#p${(it * 7) % n}\">x</a></p>" }
    private val small = ChunkPolicy(thresholdBytes = 20_000, targetBytes = 10_000)

    @Test fun splits_at_body_level_and_keeps_all_text() {
        val (z, r) = book(paras(1000))
        val cb = Chunker(small).chunk(z, r.pkg, MemoryChunkStore())
        val d = cb.map.docs.getValue("OPS/big.xhtml")
        assertTrue(d.parts.size > 10)
        assertEquals(1000, d.parts.sumOf { it.childCount })
        val orig = TextExtractor.extract(TextDecoding.decode(z.readBytes(z.entry("OPS/big.xhtml")!!))).text
        val joined = d.parts.joinToString("") { TextExtractor.extract(cb.virtualEntry(it.path)!!.decodeToString()).text }
        assertEquals(orig.trim(), joined.trim())
        d.parts.zipWithNext().forEach { (a, b) -> assertEquals(a.textStart + a.textLength, b.textStart) }
    }

    @Test fun splits_inside_single_wrapper() {
        val (z, r) = book("<div class=\"wrap\">${paras(1000)}</div>")
        val d = Chunker(small).chunk(z, r.pkg, MemoryChunkStore()).map.docs.getValue("OPS/big.xhtml")
        assertEquals(2, d.wrapperStep); assertTrue(d.parts.size > 10)
    }

    @Test fun unbalanced_markup_is_not_split() {
        val (z, r) = book(paras(1000) + "<div><p>unclosed")
        assertTrue(Chunker(small).chunk(z, r.pkg, MemoryChunkStore()).map.isIdentity)
    }

    @Test fun links_and_toc_retargeted() {
        val (z, r) = book(paras(1000))
        val cb = Chunker(small).chunk(z, r.pkg, MemoryChunkStore())
        val d = cb.map.docs.getValue("OPS/big.xhtml")
        val part77 = d.parts[d.idToPart.getValue("p77")].path.substringAfterLast('/')
        assertTrue(cb.virtualEntry("OPS/other.xhtml")!!.decodeToString().contains("href=\"$part77#p77\""))
        val part500 = d.parts[d.idToPart.getValue("p500")].path.substringAfterLast('/')
        assertTrue(cb.virtualEntry("OPS/nav.xhtml")!!.decodeToString().contains("href=\"$part500#p500\""))
        // in-document link to an id in another part becomes a cross-part link
        val p0 = cb.virtualEntry(d.parts[0].path)!!.decodeToString()
        assertTrue(Regex("href=\"big\\.xhtml\\.part\\d+\\.xhtml#p\\d+\"").containsMatchIn(p0))
        // OPF spine lists the parts in order, not the original
        val opf = cb.virtualEntry("OPS/p.opf")!!.decodeToString()
        assertTrue(opf.indexOf("big__p0") < opf.indexOf("big__p1")); assertTrue(!opf.contains("<itemref idref=\"big\""))
    }

    @Test fun small_books_are_untouched_and_store_is_not_written() {
        val (z, r) = book(paras(10))
        val store = MemoryChunkStore()
        val cb = Chunker(small).chunk(z, r.pkg, store)
        assertTrue(cb.map.isIdentity); assertNull(store.read("map.json"))
    }

    @Test fun cached_map_is_reused() {
        val (z, r) = book(paras(1000)); val store = MemoryChunkStore()
        val a = Chunker(small).chunk(z, r.pkg, store).map
        val b = Chunker(small).chunk(z, r.pkg, store).map
        assertEquals(a, b); assertEquals(1, store.writes("map.json"))
    }

    @Test fun huge_chapter_with_default_policy() {
        openFileSource(Corpus.generated("stress-huge-single-chapter.epub").path).use { src ->
            val (z, res) = PackageLoader.open(src)
            val t0 = System.nanoTime()
            val cb = Chunker().chunk(z!!, (res as LoadResult.Ok).pkg, MemoryChunkStore())
            println("chunk 10 MB: ${(System.nanoTime() - t0) / 1_000_000} ms")
            val d = cb.map.docs.values.single()
            assertTrue(d.parts.size in 60..100, "${d.parts.size} parts")
            assertTrue(d.parts.all { cb.virtualEntry(it.path)!!.size < 300_000 })
            assertEquals("s40", res.pkg.toc.single { it.label == "Section 40" }.fragment)
            assertTrue(cb.map.hrefToVirtual("${d.path}#s40").contains(".part"))
        }
    }
}
```
`MemoryChunkStore` needs a test hook `fun writes(name: String): Int` (count of writes per name) — add it to the class.

```kotlin
// core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/chunk/ChunkMapTest.kt
package app.epubreader.core.epub.chunk

import app.epubreader.core.epub.Corpus
import app.epubreader.core.epub.cfi.Cfi
import app.epubreader.core.epub.markup.TextDecoding
import app.epubreader.core.epub.markup.XmlElement
import app.epubreader.core.epub.markup.XmlTree
import app.epubreader.core.epub.openFileSource
import app.epubreader.core.epub.pkg.LoadResult
import app.epubreader.core.epub.pkg.PackageLoader
import kotlin.test.Test
import kotlin.test.assertEquals

/** End-to-end: CFIs computed on parts and on the original must address the same element. */
class ChunkMapTest {
    private fun bodyChild(markup: String, cfi: String): String {
        val steps = Cfi.parse(cfi)!!.parent.steps
        var e: XmlElement = XmlTree.parse(markup).root!!.first("body")!!
        for (st in steps.drop(3)) { if (st.index % 2 == 1) break; e = e.elements()[st.index / 2 - 1] }
        return e.text().trim()
    }

    private fun withBook(block: (orig: String, chunkA: ChunkedBook, chunkB: ChunkedBook) -> Unit) =
        openFileSource(Corpus.generated("stress-huge-single-chapter.epub").path).use { src ->
            val (z, r) = PackageLoader.open(src); val pkg = (r as LoadResult.Ok).pkg
            val orig = TextDecoding.decode(z!!.readBytes(z.entry(pkg.spine.single().path)!!))
            block(orig, Chunker(ChunkPolicy()).chunk(z, pkg, MemoryChunkStore()),
                Chunker(ChunkPolicy(thresholdBytes = 256 * 1024, targetBytes = 64 * 1024)).chunk(z, pkg, MemoryChunkStore()))
        }

    @Test fun every_element_round_trips_and_addresses_same_text() = withBook { orig, a, _ ->
        val d = a.map.docs.values.single()
        val total = d.parts.sumOf { it.childCount }
        for (i in 0 until total step 7) {
            val o = "epubcfi(/6/2!/4/${2 * (i + 1)})"
            val v = a.map.cfiToVirtual(o)
            assertEquals(o, a.map.cfiToOriginal(v))
            val vSteps = Cfi.parse(v)!!.parent.steps
            val part = d.parts[a.map.virtualSpine[vSteps[1].index / 2 - 1].part]
            assertEquals(bodyChild(orig, o), bodyChild(a.virtualEntry(part.path)!!.decodeToString(), v), "element $i")
        }
    }

    @Test fun position_survives_policy_change() = withBook { orig, a, b ->
        val da = a.map.docs.values.single(); val db = b.map.docs.values.single()
        for (vi in a.map.virtualSpine.indices step 5) {
            val partA = da.parts[a.map.virtualSpine[vi].part]
            val vA = "epubcfi(/6/${2 * (vi + 1)}!/4/${2 * minOf(3, partA.childCount)})"   // saved while reading under policy A
            val saved = a.map.cfiToOriginal(vA)                                           // what the database stores
            val vB = b.map.cfiToVirtual(saved)                                            // reopened under policy B
            val partB = db.parts[b.map.virtualSpine[Cfi.parse(vB)!!.parent.steps[1].index / 2 - 1].part]
            assertEquals(bodyChild(a.virtualEntry(partA.path)!!.decodeToString(), vA), bodyChild(b.virtualEntry(partB.path)!!.decodeToString(), vB))
            assertEquals(bodyChild(orig, saved), bodyChild(b.virtualEntry(partB.path)!!.decodeToString(), vB))
        }
    }
}
```
Run: `./gradlew :core:epub:jvmTest --tests '*ChunkerTest' --tests '*ChunkMapTest'` → Expected: compilation FAIL.

- [ ] **Step 2: Policy and stores**

```kotlin
// core/epub/src/commonMain/kotlin/app/epubreader/core/epub/chunk/ChunkPolicy.kt
package app.epubreader.core.epub.chunk

import app.epubreader.core.epub.EPUB_CORE_VERSION

/** Documents larger than [thresholdBytes] (uncompressed) are split into parts of about [targetBytes]. */
data class ChunkPolicy(val thresholdBytes: Int = 512 * 1024, val targetBytes: Int = 128 * 1024) {
    val key: String get() = "v$EPUB_CORE_VERSION-t$thresholdBytes-p$targetBytes"
}
```

```kotlin
// core/epub/src/commonMain/kotlin/app/epubreader/core/epub/chunk/ChunkStore.kt
package app.epubreader.core.epub.chunk

interface ChunkStore {
    fun read(name: String): ByteArray?
    fun write(name: String, bytes: ByteArray)
}

class MemoryChunkStore : ChunkStore {
    private val data = HashMap<String, ByteArray>()
    private val count = HashMap<String, Int>()
    override fun read(name: String) = data[name]
    override fun write(name: String, bytes: ByteArray) { data[name] = bytes; count[name] = (count[name] ?: 0) + 1 }
    fun writes(name: String) = count[name] ?: 0
}
```

```kotlin
// core/epub/src/jvmShared/kotlin/app/epubreader/core/epub/FileChunkStore.kt
package app.epubreader.core.epub

import app.epubreader.core.epub.chunk.ChunkStore
import java.io.File

/** One file per entry under [dir]; names are zip paths, stored with '/' → nested dirs. Writes are atomic. */
class FileChunkStore(private val dir: String) : ChunkStore {
    private fun f(name: String) = File(dir, name.replace("..", "_"))
    override fun read(name: String): ByteArray? = f(name).takeIf { it.isFile }?.readBytes()
    override fun write(name: String, bytes: ByteArray) {
        val target = f(name); target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) { target.delete(); check(tmp.renameTo(target)) { "cannot write $target" } }
    }
}
```

- [ ] **Step 3: Split planner**

```kotlin
// core/epub/src/commonMain/kotlin/app/epubreader/core/epub/chunk/SplitPlanner.kt
package app.epubreader.core.epub.chunk

import app.epubreader.core.epub.markup.Attr
import app.epubreader.core.epub.markup.HTML_VOID
import app.epubreader.core.epub.markup.MarkupScanner
import app.epubreader.core.epub.markup.MarkupVisitor

/**
 * Where to cut one document. Containers are the body, or — when the body has exactly one element child — that
 * wrapper element (its start tag is repeated in every part). Parts are contiguous slices of the container's inner
 * markup, cut only after a complete child element, each ≥ targetBytes except the last.
 */
internal class SplitPlan(
    val bodyContentStart: Int,
    val bodyEnd: Int,
    val wrapperOpen: IntRange?,        // offsets of the wrapper's start tag, if wrapped
    val wrapperName: String?,
    val innerStart: Int,
    val innerEnd: Int,
    val partRanges: List<IntRange>,    // markup [start, endExclusive) per part, as IntRange(start, end - 1)
    val childOffsets: IntArray,
    val childCounts: IntArray,
)

internal object SplitPlanner {
    fun plan(s: String, targetBytes: Int): SplitPlan? {
        var bodyStart = -1; var bodyEnd = -1; var depth = 0; var bad = false
        val top = ArrayList<IntArray>()     // [start, end) of each element child of body
        val inner = ArrayList<IntArray>()   // children of the first top-level element
        var firstTopName = ""; var firstOpen: IntRange? = null; var topCount = 0
        var curTop = -1; var curInner = -1; var wrapperCloseStart = -1
        MarkupScanner.scan(s, object : MarkupVisitor {
            override fun startTag(name: String, attrs: List<Attr>, selfClosing: Boolean, start: Int, end: Int) {
                val n = name.substringAfter(':').lowercase()
                if (bodyStart < 0) { if (n == "body") bodyStart = end; return }
                if (bodyEnd >= 0) return
                val void = selfClosing || n in HTML_VOID
                if (depth == 0) { topCount++; curTop = start; if (topCount == 1) { firstTopName = name; firstOpen = start until end } }
                if (depth == 1 && topCount == 1) curInner = start
                if (void) {
                    if (depth == 0) top += intArrayOf(start, end)
                    if (depth == 1 && topCount == 1) inner += intArrayOf(start, end)
                } else depth++
            }
            override fun endTag(name: String, start: Int, end: Int) {
                val n = name.substringAfter(':').lowercase()
                if (bodyStart < 0 || bodyEnd >= 0) return
                if (n == "body") { if (depth != 0) bad = true; bodyEnd = start; return }
                if (n in HTML_VOID) return
                depth--
                if (depth < 0) { bad = true; depth = 0; return }
                if (depth == 0) { top += intArrayOf(curTop, end); if (topCount == 1) wrapperCloseStart = start }
                if (depth == 1 && topCount == 1) inner += intArrayOf(curInner, end)
            }
            override fun error(message: String, at: Int) { if (message.startsWith("unterminated")) bad = true }
        })
        if (bodyStart < 0 || bodyEnd < 0 || bad || depth != 0) return null
        val wrapped = topCount == 1 && inner.size > 1
        val children = if (wrapped) inner else top
        if (children.size < 2) return null
        val innerStart = if (wrapped) firstOpen!!.last + 1 else bodyStart
        val innerEnd = if (wrapped) wrapperCloseStart else bodyEnd
        val ranges = ArrayList<IntRange>(); val offs = ArrayList<Int>(); val counts = ArrayList<Int>()
        var partStart = innerStart; var firstChild = 0
        for ((k, c) in children.withIndex()) {
            if (c[1] - partStart >= targetBytes && k < children.size - 1) {
                ranges += partStart until c[1]; offs += firstChild; counts += k - firstChild + 1
                partStart = c[1]; firstChild = k + 1
            }
        }
        ranges += partStart until innerEnd; offs += firstChild; counts += children.size - firstChild
        if (ranges.size < 2) return null
        return SplitPlan(bodyStart, bodyEnd, if (wrapped) firstOpen else null, if (wrapped) firstTopName else null,
            innerStart, innerEnd, ranges, offs.toIntArray(), counts.toIntArray())
    }
}
```

- [ ] **Step 4: Chunker and ChunkedBook**

```kotlin
// core/epub/src/commonMain/kotlin/app/epubreader/core/epub/chunk/ChunkedBook.kt
package app.epubreader.core.epub.chunk

/** The engine-facing view of a book: virtual entries override zip entries with the same path. */
class ChunkedBook(val map: ChunkMap, private val store: ChunkStore, rewritten: Set<String>) {
    val virtualPaths: Set<String> = map.virtualPaths + rewritten
    fun virtualEntry(path: String): ByteArray? = if (path in virtualPaths) store.read(path) else null

    companion object {
        fun identity(map: ChunkMap) = ChunkedBook(map, MemoryChunkStore(), emptySet())
    }
}
```

```kotlin
// core/epub/src/commonMain/kotlin/app/epubreader/core/epub/chunk/Chunker.kt
package app.epubreader.core.epub.chunk

import app.epubreader.core.epub.markup.Attr
import app.epubreader.core.epub.markup.MarkupRewriter
import app.epubreader.core.epub.markup.MarkupScanner
import app.epubreader.core.epub.markup.MarkupVisitor
import app.epubreader.core.epub.markup.Paths
import app.epubreader.core.epub.markup.TextDecoding
import app.epubreader.core.epub.pkg.BookPackage
import app.epubreader.core.epub.text.TextExtractor
import app.epubreader.core.epub.zip.ZipReader
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Splits oversized spine documents (P-014, P-023, P-061). Two passes: plan every large document (cut points and id
 * maps), then render parts and rewrite links everywhere (parts, other content documents, nav, NCX, OPF). Results
 * go to [ChunkStore]; map.json is written last and marks a complete cache.
 */
class Chunker(private val policy: ChunkPolicy = ChunkPolicy()) {
    private val json = Json { ignoreUnknownKeys = true }

    fun chunk(zip: ZipReader, pkg: BookPackage, store: ChunkStore): ChunkedBook {
        val spinePaths = pkg.spine.map { it.path }
        store.read(MAP)?.let { bytes ->
            runCatching {
                val map = json.decodeFromString(ChunkMap.serializer(), bytes.decodeToString())
                val rewritten = store.read(REWRITTEN)?.let { json.decodeFromString(ListSerializer(String.serializer()), it.decodeToString()) }.orEmpty()
                if (map.policyKey == policy.key && map.spinePaths == spinePaths) return ChunkedBook(map, store, rewritten.toSet())
            }
        }
        val candidates = pkg.spine.filter { it.present && it.mediaType.contains("html") && (zip.entry(it.path)?.size ?: 0) > policy.thresholdBytes }
            .map { it.path }.distinct()
        if (candidates.isEmpty()) return ChunkedBook.identity(ChunkMap.identity(spinePaths, policy.key))

        // Pass 1: plans and id maps.
        val docs = LinkedHashMap<String, ChunkMap.SplitDoc>()
        val plans = HashMap<String, SplitPlan>()
        for (path in candidates) {
            val markup = load(zip, path) ?: continue
            val plan = SplitPlanner.plan(markup, policy.targetBytes) ?: continue
            val cuts = IntArray(plan.partRanges.size) { plan.partRanges[it].first }
            val text = TextExtractor.extract(markup, cuts)
            val ids = HashMap<String, Int>()
            plan.partRanges.forEachIndexed { p, r -> collectIds(markup, r.first, r.last + 1) { ids.putIfAbsent(it, p) } }
            val parts = plan.partRanges.indices.map { p ->
                val start = text.cutTextOffsets[p]
                val end = if (p + 1 < cuts.size) text.cutTextOffsets[p + 1] else text.text.length
                ChunkMap.Part("$path.part$p.xhtml", plan.childOffsets[p], plan.childCounts[p], start, end - start)
            }
            docs[path] = ChunkMap.SplitDoc(path, if (plan.wrapperOpen != null) 2 else null, parts, ids)
            plans[path] = plan
        }
        if (docs.isEmpty()) return ChunkedBook.identity(ChunkMap.identity(spinePaths, policy.key))

        val virtualSpine = pkg.spine.flatMapIndexed { i, s ->
            docs[s.path]?.parts?.indices?.map { ChunkMap.VSpine(i, it) } ?: listOf(ChunkMap.VSpine(i, -1))
        }
        val map = ChunkMap(policy.key, spinePaths, virtualSpine, docs)

        // Pass 2: parts with retargeted links.
        for ((path, d) in docs) {
            val markup = load(zip, path)!!
            val plan = plans.getValue(path)
            val head = markup.substring(0, plan.bodyContentStart)
            val tail = markup.substring(plan.bodyEnd)
            val open = plan.wrapperOpen?.let { markup.substring(it.first, it.last + 1) } ?: ""
            val close = plan.wrapperName?.let { "</$it>" } ?: ""
            d.parts.forEachIndexed { p, part ->
                val r = plan.partRanges[p]
                val raw = head + open + markup.substring(r.first, r.last + 1) + close + tail
                store.write(part.path, retarget(raw, Paths.dirOf(path), map, selfDoc = d, selfPart = p).encodeToByteArray())
            }
        }
        // Other documents that link into split documents, plus nav/NCX.
        val rewritten = LinkedHashSet<String>()
        for (item in pkg.manifest) {
            if (!item.present || item.path in docs) continue
            val isDoc = item.mediaType.contains("html") || item.mediaType.contains("ncx") || item.path.endsWith(".ncx")
            if (!isDoc) continue
            val markup = load(zip, item.path) ?: continue
            if (docs.keys.none { markup.contains(it.substringAfterLast('/')) }) continue
            val out = retarget(markup, Paths.dirOf(item.path), map, selfDoc = null, selfPart = -1)
            if (out !== markup) { store.write(item.path, out.encodeToByteArray()); rewritten += item.path }
        }
        store.write(pkg.opfPath, rewriteOpf(load(zip, pkg.opfPath)!!, pkg, docs).encodeToByteArray()); rewritten += pkg.opfPath
        store.write(REWRITTEN, json.encodeToString(ListSerializer(String.serializer()), rewritten.toList()).encodeToByteArray())
        store.write(MAP, json.encodeToString(ChunkMap.serializer(), map).encodeToByteArray())
        return ChunkedBook(map, store, rewritten)
    }

    private fun load(zip: ZipReader, path: String): String? =
        zip.entry(path)?.let { runCatching { TextDecoding.decode(zip.readBytes(it)) }.getOrNull() }

    private fun collectIds(s: String, from: Int, to: Int, f: (String) -> Unit) =
        MarkupScanner.scan(s, object : MarkupVisitor {
            override fun startTag(name: String, attrs: List<Attr>, selfClosing: Boolean, start: Int, end: Int) {
                attrs.firstOrNull { it.localName == "id" }?.let { f(it.value) }
            }
        }, from, to)

    /** Rewrites href/src/xlink:href that point into split documents (or, inside a part, to another part's id). */
    private fun retarget(markup: String, baseDir: String, map: ChunkMap, selfDoc: ChunkMap.SplitDoc?, selfPart: Int): String =
        MarkupRewriter.rewriteAttrValues(markup) { _, a ->
            if (a.localName != "href" && a.localName != "src") return@rewriteAttrValues null
            val v = a.value
            if (v.isEmpty() || Paths.isExternal(v)) return@rewriteAttrValues null
            if (v.startsWith("#")) {
                val d = selfDoc ?: return@rewriteAttrValues null
                val p = d.idToPart[v.substring(1)] ?: return@rewriteAttrValues null
                return@rewriteAttrValues if (p == selfPart) null else d.parts[p].path.substringAfterLast('/') + v
            }
            val target = Paths.resolve(baseDir, v)
            val d = map.docs[target] ?: return@rewriteAttrValues null
            val frag = Paths.fragmentOf(v)
            val p = frag?.let { d.idToPart[it] } ?: 0
            val pathPart = v.substringBefore('#')
            pathPart + ".part$p.xhtml" + (v.substringAfter('#', "").let { if (it.isEmpty()) "" else "#$it" })
        }

    /** Replaces each split document's itemref by its parts' itemrefs and adds part manifest items. */
    private fun rewriteOpf(opf: String, pkg: BookPackage, docs: Map<String, ChunkMap.SplitDoc>): String {
        val idOf = pkg.manifest.associate { it.id to it.path }
        val edits = ArrayList<Triple<Int, Int, String>>()
        var manifestEnd = -1
        MarkupScanner.scan(opf, object : MarkupVisitor {
            override fun startTag(name: String, attrs: List<Attr>, selfClosing: Boolean, start: Int, end: Int) {
                if (name.substringAfter(':') != "itemref") return
                val idref = attrs.firstOrNull { it.localName == "idref" }?.value ?: return
                val d = docs[idOf[idref]] ?: return
                val linear = attrs.firstOrNull { it.localName == "linear" }?.value?.let { " linear=\"$it\"" } ?: ""
                edits += Triple(start, end, d.parts.indices.joinToString("") { "<itemref idref=\"${idref}__p$it\"$linear/>" })
            }
            override fun endTag(name: String, start: Int, end: Int) { if (name.substringAfter(':') == "manifest") manifestEnd = start }
        })
        val items = pkg.manifest.filter { it.path in docs }.joinToString("") { item ->
            docs.getValue(item.path).parts.withIndex().joinToString("") { (i, p) ->
                "<item id=\"${item.id}__p$i\" href=\"${p.path.removePrefix(pkg.opfDir.let { if (it.isEmpty()) "" else "$it/" })}\" media-type=\"application/xhtml+xml\"/>"
            }
        }
        if (manifestEnd >= 0) edits += Triple(manifestEnd, manifestEnd, items)
        edits.sortBy { it.first }
        val sb = StringBuilder(opf.length + items.length + 1024); var last = 0
        for ((a, b, v) in edits) { sb.append(opf, last, a).append(v); last = b }
        return sb.append(opf, last, opf.length).toString()
    }

    companion object {
        const val MAP = "map.json"
        const val REWRITTEN = "rewritten.json"
    }
}
```
Notes for the executor: part hrefs in the OPF are relative to the OPF directory (parts are siblings of their
original); percent-encoding of part hrefs is not needed because part names are the original name plus an ASCII
suffix — but if the original href was percent-encoded in the OPF, encode the part href the same way (add a
`ChunkerTest` case with a file named `big chapter.xhtml` referenced as `big%20chapter.xhtml` and fix if it fails).

- [ ] **Step 5: Run tests, commit**

Run: `./gradlew :core:epub:jvmTest --tests '*ChunkerTest' --tests '*ChunkMapTest' --tests '*CfiTest'`
Expected: PASS; printed chunk time for the 10 MB chapter (record in the commit body; bake-off prototype: 726 ms on
the emulator).
Commit: `core/epub: production chunker with disk cache, link retargeting and verified position mapping`.

---

### Task 2.9: Corpus classification (host) and on-device parse benchmark

**Files:**
- Create: `core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/CorpusTest.kt`
- Create: `app/src/androidTest/kotlin/app/epubreader/CorpusParseInstrumentedTest.kt`
- Create: `docs/milestones/M2.md`

**Interfaces:**
- Consumes: everything above. Produces the expected-classification table reused by M4's scanner tests:
  `object ExpectedCorpus { val failures: Map<String, FailReason> }` (in the test file; M4 copies it).

- [ ] **Step 1: Host corpus test**

```kotlin
// core/epub/src/jvmTest/kotlin/app/epubreader/core/epub/CorpusTest.kt
package app.epubreader.core.epub

import app.epubreader.core.epub.chunk.Chunker
import app.epubreader.core.epub.chunk.MemoryChunkStore
import app.epubreader.core.epub.meta.BookIdentity
import app.epubreader.core.epub.pkg.FailReason
import app.epubreader.core.epub.pkg.LoadResult
import app.epubreader.core.epub.pkg.PackageLoader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

object ExpectedCorpus {
    val failures = mapOf(
        "malformed-not-a-zip.epub" to FailReason.NOT_A_ZIP,
        "malformed-truncated.epub" to FailReason.DAMAGED_ARCHIVE,
        "malformed-broken-opf.epub" to FailReason.BROKEN_PACKAGE,
        "malformed-no-container.epub" to FailReason.NO_PACKAGE,
        "malformed-empty-spine.epub" to FailReason.NOTHING_READABLE,
        "fake.epub" to FailReason.NOT_A_ZIP,
    )
}

class CorpusTest {
    private fun load(f: File): Pair<LoadResult, String?> = openFileSource(f.path).use { src ->
        val (zip, r) = PackageLoader.open(src)
        if (r is LoadResult.Ok) Chunker().chunk(zip!!, r.pkg, MemoryChunkStore()) // must not throw
        r to (r as? LoadResult.Ok)?.let { BookIdentity.of(it.pkg.uniqueId, zip!!.centralDirectoryDigest) }
    }

    private fun check(files: List<File>) {
        for (f in files) {
            val t0 = System.nanoTime()
            val (r, _) = load(f)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val expected = ExpectedCorpus.failures[f.name]
            if (expected != null) assertEquals(expected, (r as? LoadResult.Failed)?.reason, f.name)
            else assertTrue(r is LoadResult.Ok, "${f.name}: $r")
            println("corpus ${f.name} ${if (r is LoadResult.Ok) "OK" else (r as LoadResult.Failed).reason} ${ms}ms")
        }
    }

    @Test fun generated_and_folder_books() =
        check((File(Corpus.dir, "generated").listFiles()!! + File(Corpus.dir, "folders").listFiles()!!).filter { it.name.endsWith(".epub") }.sortedBy { it.name })

    @Test fun downloaded_corpus_when_present() {
        val full = Corpus.full ?: return println("SKIP: downloaded corpus not present (tools/corpus.sh)")
        check(listOf("gutenberg", "epub3-samples").flatMap { File(full, it).listFiles()!!.toList() }.filter { it.name.endsWith(".epub") }.sortedBy { it.name })
        val p3 = load(File(full, "gutenberg/pride-and-prejudice-epub3-images.epub")).second
        val p2 = load(File(full, "gutenberg/pride-and-prejudice-epub2-noimages.epub")).second
        assertNotEquals(p3, p2) // F-015: different editions stay two entries
    }
}
```
Run: `./gradlew :core:epub:jvmTest --tests '*CorpusTest'` → Expected: PASS; one `corpus …` line per file.

- [ ] **Step 2: On-device parse benchmark**

```kotlin
// app/src/androidTest/kotlin/app/epubreader/CorpusParseInstrumentedTest.kt
package app.epubreader

import android.os.Environment
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.epubreader.core.epub.FileChunkStore
import app.epubreader.core.epub.chunk.Chunker
import app.epubreader.core.epub.meta.BookIdentity
import app.epubreader.core.epub.openFileSource
import app.epubreader.core.epub.pkg.FailReason
import app.epubreader.core.epub.pkg.LoadResult
import app.epubreader.core.epub.pkg.PackageLoader
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CorpusParseInstrumentedTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val expected = mapOf(
        "malformed-not-a-zip.epub" to FailReason.NOT_A_ZIP, "malformed-truncated.epub" to FailReason.DAMAGED_ARCHIVE,
        "malformed-broken-opf.epub" to FailReason.BROKEN_PACKAGE, "malformed-no-container.epub" to FailReason.NO_PACKAGE,
        "malformed-empty-spine.epub" to FailReason.NOTHING_READABLE, "fake.epub" to FailReason.NOT_A_ZIP)

    @Before fun grant() {
        inst.uiAutomation.executeShellCommand("appops set ${inst.targetContext.packageName} MANAGE_EXTERNAL_STORAGE allow").close()
    }

    @Test fun parse_whole_corpus_on_device() {
        val root = File(Environment.getExternalStorageDirectory(), "Books")
        val files = root.walkTopDown().filter { it.isFile && it.name.endsWith(".epub", true) }.sortedBy { it.path }.toList()
        check(files.isNotEmpty()) { "corpus not on device: run bench/push_corpus.sh" }
        var total = 0L
        for (f in files) {
            val t0 = SystemClock.uptimeMillis()
            val result = openFileSource(f.path).use { src ->
                val (zip, r) = PackageLoader.open(src)
                if (r is LoadResult.Ok) {
                    BookIdentity.of(r.pkg.uniqueId, zip!!.centralDirectoryDigest)
                    Chunker().chunk(zip, r.pkg, FileChunkStore(File(inst.targetContext.cacheDir, "chunks-test/${f.name}").path))
                }
                r
            }
            val ms = SystemClock.uptimeMillis() - t0; total += ms
            Log.i("EPR", "EVT corpus_parse file=${f.name} ms=$ms result=${(result as? LoadResult.Failed)?.reason ?: "OK"}")
            expected[f.name]?.let { assertEquals(f.name, it, (result as? LoadResult.Failed)?.reason) }
        }
        Log.i("EPR", "EVT corpus_parse_total files=${files.size} ms=$total")
    }
}
```
Run: `./gradlew :app:connectedPlayDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.epubreader.CorpusParseInstrumentedTest`
then `adb logcat -d -s EPR:I | grep corpus_parse`.
Expected: PASS; per-file times; huge chapter first-time chunk time noted.

- [ ] **Step 3: Milestone note and commit**

`docs/milestones/M2.md`: host and device timings (extract 10 MB, chunk 10 MB, whole-corpus parse on device), any
book that loaded with warnings and which, and the confirmation that every malformed file classifies as expected.
```bash
git add core/epub app/src/androidTest docs/milestones/M2.md
git commit -m "core/epub: corpus classification tests and on-device parse benchmark

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
