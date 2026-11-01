package app.epubreader.core.epub.chunk

import app.epubreader.core.epub.markup.Attr
import app.epubreader.core.epub.markup.Entities
import app.epubreader.core.epub.markup.MarkupRewriter
import app.epubreader.core.epub.markup.MarkupScanner
import app.epubreader.core.epub.markup.MarkupVisitor
import app.epubreader.core.epub.markup.Paths
import app.epubreader.core.epub.markup.TextDecoding
import app.epubreader.core.epub.markup.XmlTree
import app.epubreader.core.epub.pkg.BookPackage
import app.epubreader.core.epub.text.TextExtractor
import app.epubreader.core.epub.zip.ZipReader
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Splits oversized spine documents so the reader lays out one part at a time (P-014, P-023, P-061, R-006).
 *
 * Pass 1 plans every candidate (cut points, ids per part, text ranges). Pass 2 writes the parts and every document
 * that links into a split one (other content documents, nav, NCX) with links retargeted to the right part, then the
 * package with each split itemref replaced by its parts. Everything goes to the [ChunkStore]; [CACHE] (map, policy,
 * file digest) is written last and marks a complete cache, which later calls reuse.
 *
 * A document is split only when it is a present, reflowable XHTML spine item over the threshold, not the nav, and
 * [SplitPlanner] finds a safe plan; otherwise the book is served unchanged (identity map, store untouched).
 * Not thread-safe per store: run one chunk() per book at a time, off the main thread.
 */
class Chunker(private val policy: ChunkPolicy = ChunkPolicy()) {
    @Serializable
    private class CacheFile(val policyKey: String, val digest: String, val rewritten: List<String>, val map: ChunkMap,
                            /** Store name -> byte size of every entry written before this file. */
                            val entries: Map<String, Long>)

    private class Planned(val item: String, val doc: ChunkMap.SplitDoc, val plan: SplitPlan)

    /**
     * Whether [chunk] would look at the store at all: some spine document is a split candidate. False means [chunk]
     * returns the identity without reading or writing the store, so a caller can skip creating one.
     */
    fun hasCandidates(zip: ZipReader, pkg: BookPackage): Boolean = !pkg.fixedLayout && candidates(zip, pkg).isNotEmpty() && slotsOf(pkg) != null

    private fun candidates(zip: ZipReader, pkg: BookPackage): List<String> {
        val navPaths = pkg.manifest.filter { "nav" in it.properties }.map { it.path }.toSet()
        return pkg.spine.filter { s ->
            s.present && s.mediaType.substringBefore(';').trim().equals(XHTML, ignoreCase = true) && s.path !in navPaths &&
                (zip.entry(s.path)?.size ?: 0) > policy.thresholdBytes
        }.map { it.path }.distinct()
    }

    fun chunk(zip: ZipReader, pkg: BookPackage, store: ChunkStore): ChunkedBook {
        val slots = slotsOf(pkg)
        val identity = ChunkedBook(ChunkMap.identity(slots ?: pkg.spine.map { ChunkMap.Slot(it.path) }, policy.key), store, emptySet())
        if (slots == null || pkg.fixedLayout) return identity
        val candidates = candidates(zip, pkg)
        if (candidates.isEmpty()) return identity
        readCache(store, zip.centralDirectoryDigest, slots)?.let { return it }

        // From here on "nothing to split" is cached too, so a huge unsplittable document is not planned on every open.
        fun unsplit(): ChunkedBook {
            store.clear()
            val c = CacheFile(policy.key, zip.centralDirectoryDigest, emptyList(), identity.map, emptyMap())
            store.write(CACHE, json.encodeToString(CacheFile.serializer(), c).encodeToByteArray())
            return identity
        }
        val opfText = load(zip, pkg.opfPath) ?: return unsplit()
        if (!XmlTree.parse(opfText).wellFormed) return unsplit()

        // Pass 1: plans.
        val taken = zip.entries.mapTo(HashSet()) { it.name }
        val planned = LinkedHashMap<String, Planned>()
        for (path in candidates) {
            val markup = load(zip, path) ?: continue
            val plan = SplitPlanner.plan(markup, policy.targetChars) ?: continue
            val names = partNames(path, plan.parts.size, taken)
            val cuts = IntArray(plan.parts.size) { if (it == 0) 0 else plan.parts[it].sliceStart }
            val text = TextExtractor.extract(markup, cuts)
            val ids = HashMap<String, Int>()
            plan.parts.forEachIndexed { p, part -> collectIds(markup, part.sliceStart, part.sliceEnd) { ids.getOrPut(it) { p } } }
            val parts = plan.parts.mapIndexed { p, part ->
                val start = if (p == 0) 0 else text.cutTextOffsets[p]
                val end = if (p + 1 < cuts.size) text.cutTextOffsets[p + 1] else text.text.length
                ChunkMap.Part(names[p], part.childOffset, part.childCount, start, end - start)
            }
            planned[path] = Planned(path, ChunkMap.SplitDoc(path, plan.bodyStep, plan.wrapper != null, parts, ids), plan)
        }
        if (planned.isEmpty()) return unsplit()
        val docs = planned.mapValues { it.value.doc }
        val map = ChunkMap(policy.key, slots, docs)
        val opf = rewriteOpf(opfText, pkg, map) ?: return unsplit()

        // Pass 2: parts, linking documents, package; the cache file last.
        store.clear()
        for ((path, pl) in planned) {
            val markup = load(zip, path) ?: return unsplit()
            pl.doc.parts.forEachIndexed { p, part ->
                val raw = renderPart(markup, pl.plan, p)
                store.write(entryName(part.path), utf8(retarget(raw, Paths.dirOf(path), docs, pl.doc, p)).encodeToByteArray())
            }
        }
        val rewritten = LinkedHashSet<String>()
        // Cheap filter: documents that do not mention a split document's file name need no rewriting. Names that are
        // not plain ASCII can be spelled in several percent-encodings, so then every document is scanned.
        val names = docs.keys.map { it.substringAfterLast('/') }
        val needles = names.takeIf { n -> n.all { name -> name.all { it.code in 33..126 && it != '%' } } }
        for (item in pkg.manifest) {
            if (!item.present || item.path in docs || item.path == pkg.opfPath || !isLinkingDocument(item.mediaType)) continue
            val bytes = readBytes(zip, item.path) ?: continue
            val markup = TextDecoding.decodeExact(bytes)
            val lenient = markup ?: TextDecoding.decode(bytes)
            if (needles != null && needles.none { lenient.contains(it, ignoreCase = true) }) continue
            val out = retarget(lenient, Paths.dirOf(item.path), docs, null, -1)
            if (out === lenient) continue
            // Rewriting a document we cannot decode exactly would corrupt its text: serve the book unsplit.
            if (markup == null) return unsplit()
            store.write(entryName(item.path), utf8(out).encodeToByteArray())
            rewritten += item.path
        }
        store.write(entryName(pkg.opfPath), utf8(opf).encodeToByteArray())
        rewritten += pkg.opfPath
        val entries = (map.partPaths + rewritten).associate { entryName(it) to (store.size(entryName(it)) ?: -1L) }
        val cache = CacheFile(policy.key, zip.centralDirectoryDigest, rewritten.toList(), map, entries)
        store.write(CACHE, json.encodeToString(CacheFile.serializer(), cache).encodeToByteArray())
        return ChunkedBook(map, store, rewritten)
    }

    private fun readCache(store: ChunkStore, digest: String, slots: List<ChunkMap.Slot>): ChunkedBook? {
        val bytes = store.read(CACHE) ?: return null
        val c = try { json.decodeFromString(CacheFile.serializer(), bytes.decodeToString()) } catch (e: Exception) { return null }
        if (c.policyKey != policy.key || c.digest != digest || c.map.spine != slots) return null
        if (c.map.isIdentity) return ChunkedBook(c.map, store, emptySet())
        // Single cache files can vanish (the system trims cache directories) or be cut short (power loss).
        if (c.entries.size != c.map.partPaths.size + c.rewritten.size || c.entries.any { (name, size) -> store.size(name) != size }) return null
        return ChunkedBook(c.map, store, c.rewritten.toSet())
    }

    /** Every element child of `<spine>`, as the reader counts them for CFIs; null if it disagrees with [pkg]'s spine. */
    private fun slotsOf(pkg: BookPackage): List<ChunkMap.Slot>? {
        val byId = pkg.manifest.associateBy { it.id }
        val spine = pkg.opf.elements().firstOrNull { it.localName == "spine" } ?: return null
        val slots = spine.elements().map { e ->
            val item = e.attr("idref")?.let(byId::get)
            ChunkMap.Slot(if (e.localName == "itemref") item?.path else null, e.attr("id"))
        }
        return slots.takeIf { s -> s.mapNotNull { it.path } == pkg.spine.map { it.path } }
    }

    private fun readBytes(zip: ZipReader, path: String): ByteArray? =
        zip.entry(path)?.let { e -> try { zip.readBytes(e) } catch (x: Exception) { null } }

    /** Decoded exactly, or null (missing, unreadable, or an encoding we would corrupt by rewriting as UTF-8). */
    private fun load(zip: ZipReader, path: String): String? = readBytes(zip, path)?.let(TextDecoding::decodeExact)

    /** Part p: the original prolog and head, its slice of the container (wrapper start tag repeated), the original end. */
    private fun renderPart(s: String, plan: SplitPlan, p: Int): String {
        val part = plan.parts[p]
        val last = p == plan.parts.size - 1
        val w = plan.wrapper
        val sb = StringBuilder(plan.bodyContentStart + (part.sliceEnd - part.sliceStart) + (s.length - plan.bodyEnd) + 256)
        sb.append(s, 0, plan.bodyContentStart)
        if (w != null) sb.append(s, if (p == 0) plan.bodyContentStart else w.openStart, w.openEnd)
        sb.append(s, part.sliceStart, part.sliceEnd)
        if (w != null) sb.append(s, w.closeStart, if (last) plan.bodyEnd else w.closeEnd)
        sb.append(s, plan.bodyEnd, s.length)
        return sb.toString()
    }

    private fun collectIds(s: String, from: Int, to: Int, f: (String) -> Unit) =
        MarkupScanner.scan(s, object : MarkupVisitor {
            override fun startTag(name: String, attrs: List<Attr>, selfClosing: Boolean, start: Int, end: Int) {
                for (a in attrs) if (a.localName == "id" && a.valueStart >= 0) f(a.value)
            }
        }, from, to)

    /**
     * Retargets links into split documents: `href` everywhere (also `xlink:href`) and NCX `<content src>`. In a part
     * ([self], [selfPart]) a `#id` link to another part's element becomes a link to that part.
     */
    private fun retarget(markup: String, baseDir: String, docs: Map<String, ChunkMap.SplitDoc>, self: ChunkMap.SplitDoc?, selfPart: Int,
                         onlyTags: Set<String>? = null): String =
        MarkupRewriter.rewriteAttrValues(markup) { tag, a ->
            if (onlyTags != null && tag !in onlyTags) return@rewriteAttrValues null
            val ln = a.localName.lowercase()
            if (ln != "href" && !(ln == "src" && tag == "content")) return@rewriteAttrValues null
            newHref(a.value, baseDir, docs, self, selfPart)
        }

    private fun newHref(v: String, baseDir: String, docs: Map<String, ChunkMap.SplitDoc>, self: ChunkMap.SplitDoc?, selfPart: Int): String? {
        if (v.isEmpty() || Paths.isExternal(v)) return null
        val hash = v.indexOf('#')
        if (hash == 0) {
            val d = self ?: return null
            val p = d.idToPart[Paths.percentDecode(v.substring(1))] ?: return null
            return if (p == selfPart) null else encodeSegment(d.parts[p].path.substringAfterLast('/')) + v
        }
        val d = docs[Paths.resolve(baseDir, v)] ?: return null
        val p = Paths.fragmentOf(v)?.let { d.idToPart[it] } ?: 0
        val pathPart = (if (hash < 0) v else v.substring(0, hash)).substringBefore('?')
        return pathPart.substring(0, pathPart.lastIndexOf('/') + 1) + encodeSegment(d.parts[p].path.substringAfterLast('/')) +
            (if (hash < 0) "" else v.substring(hash))
    }

    /**
     * The package with each split document's itemrefs replaced by one itemref per part (other attributes kept) and
     * one manifest item per part (media type and properties of the original). The original item stays. Guide
     * references are retargeted. Null if the package does not look the way [slotsOf] read it.
     */
    private fun rewriteOpf(opf: String, pkg: BookPackage, map: ChunkMap): String? {
        val items = pkg.opf.elements().firstOrNull { it.localName == "manifest" }?.elements().orEmpty().filter { it.localName == "item" }
        val usedIds = items.mapNotNullTo(HashSet()) { it.attr("id") }
        val partIds = HashMap<String, List<String>>()   // manifest id of a split document -> ids of its parts
        val newItems = StringBuilder()
        for (e in items) {
            val id = e.attr("id") ?: continue
            val item = pkg.item(id) ?: continue
            val d = map.docs[item.path] ?: continue
            if (id in partIds) continue
            val href = e.attr("href") ?: continue
            val dirPrefix = href.substringBefore('#').let { it.substring(0, it.lastIndexOf('/') + 1) }
            val props = e.attr("properties")?.let { " properties=\"${Entities.escapeAttr(it)}\"" } ?: ""
            partIds[id] = d.parts.mapIndexed { p, part ->
                var nid = "$id-part$p"
                while (nid in usedIds) nid += "_"
                usedIds += nid
                newItems.append('<').append(e.name).append(" id=\"").append(Entities.escapeAttr(nid))
                    .append("\" href=\"").append(Entities.escapeAttr(dirPrefix + encodeSegment(part.path.substringAfterLast('/'))))
                    .append("\" media-type=\"").append(Entities.escapeAttr(item.mediaType)).append('"').append(props).append("/>")
                nid
            }
        }
        val edits = ArrayList<Triple<Int, Int, String>>()
        var manifestEnd = -1
        var slot = 0
        var ok = true
        MarkupScanner.scan(opf, object : MarkupVisitor {
            var depth = 0
            var spineDepth = -1
            var replacing = -1   // depth of an itemref with an end tag whose replacement extends to that end tag
            override fun startTag(name: String, attrs: List<Attr>, selfClosing: Boolean, start: Int, end: Int) {
                val ln = name.substringAfter(':').lowercase()
                if (spineDepth >= 0 && depth == spineDepth) {
                    val s = map.spine.getOrNull(slot++)
                    if (s == null) ok = false
                    val ids = if (ln == "itemref") attrs.firstOrNull { it.localName == "idref" }?.value?.let(partIds::get) else null
                    if (s != null && ids != null && s.path != null && s.path in map.docs) {
                        val keep = attrs.filter { it.valueStart >= 0 && it.localName != "idref" && it.localName != "id" }
                            .joinToString("") { " ${it.name}=\"${Entities.escapeAttr(it.value)}\"" }
                        edits += Triple(start, end, ids.joinToString("") { "<$name idref=\"${Entities.escapeAttr(it)}\"$keep/>" })
                        if (!selfClosing) replacing = depth + 1
                    } else if (s != null && s.path != null && s.path in map.docs) ok = false
                }
                if (ln == "spine" && spineDepth == -1 && depth == 1 && !selfClosing) spineDepth = depth + 1
                if (!selfClosing) depth++
            }
            override fun endTag(name: String, start: Int, end: Int) {
                if (replacing == depth) { val last = edits.removeAt(edits.size - 1); edits += Triple(last.first, end, last.third); replacing = -1 }
                depth--
                val ln = name.substringAfter(':').lowercase()
                if (ln == "manifest" && manifestEnd < 0) manifestEnd = start
                if (ln == "spine" && depth == spineDepth - 1) spineDepth = -2
            }
        })
        if (!ok || slot != map.spine.size || manifestEnd < 0) return null
        edits += Triple(manifestEnd, manifestEnd, newItems.toString())
        edits.sortBy { it.first }
        val sb = StringBuilder(opf.length + newItems.length + edits.size * 64)
        var last = 0
        for ((a, b, v) in edits) { sb.append(opf, last, a).append(v); last = b }
        sb.append(opf, last, opf.length)
        return retarget(sb.toString(), pkg.opfDir, map.docs, null, -1, onlyTags = setOf("reference"))
    }

    companion object {
        /** Commit marker and index of a complete cache. */
        const val CACHE = "cache.json"
        private const val XHTML = "application/xhtml+xml"
        private val json = Json { ignoreUnknownKeys = true }

        /** Store name of a virtual entry (zip paths cannot clash with [CACHE]). */
        internal fun entryName(path: String) = "e/$path"

        private fun isLinkingDocument(mediaType: String): Boolean {
            val t = mediaType.substringBefore(';').trim().lowercase()
            return t == XHTML || t == "text/html" || t == "application/x-dtbncx+xml"
        }

        /** `dir/name.ext` → `dir/name.part<p>.ext`, avoiding names already in the zip or taken by other parts. */
        internal fun partNames(path: String, n: Int, taken: MutableSet<String>): List<String> {
            val slash = path.lastIndexOf('/')
            val dot = path.lastIndexOf('.').takeIf { it > slash + 1 } ?: path.length
            var marker = ".part"
            while ((0 until n).any { "${path.substring(0, dot)}$marker$it${path.substring(dot)}" in taken }) marker += "_"
            return (0 until n).map { "${path.substring(0, dot)}$marker$it${path.substring(dot)}" }.also { taken += it }
        }

        /** Percent-encodes a path segment (UTF-8), keeping RFC 3986 unreserved and sub-delimiter characters. */
        internal fun encodeSegment(s: String): String {
            if (s.all { it.isLetterOrDigit() && it.code < 128 || it in SAFE }) return s
            val sb = StringBuilder(s.length + 16)
            for (b in s.encodeToByteArray()) {
                val c = b.toInt() and 0xff
                if (c < 128 && (c.toChar().isLetterOrDigit() || c.toChar() in SAFE)) sb.append(c.toChar())
                else sb.append('%').append(HEX[c shr 4]).append(HEX[c and 15])
            }
            return sb.toString()
        }
        private const val SAFE = "-._~!$&'()*+,;=:@"
        private const val HEX = "0123456789ABCDEF"

        private val XML_ENC = Regex("""^(\s*<\?xml[^>]*?encoding\s*=\s*["'])([A-Za-z0-9._-]+)(["'])""")
        private val META_CHARSET = Regex("""(<meta[^>]+charset\s*=\s*["']?)([A-Za-z0-9._-]+)""", RegexOption.IGNORE_CASE)

        /** Rewritten documents are written as UTF-8: make their declared encoding say so (TextDecoding reads it back). */
        internal fun utf8(s: String): String {
            val headEnd = minOf(s.length, 1024)
            var head = s.substring(0, headEnd)
            head = XML_ENC.replace(head) { m -> if (m.groupValues[2].equals("utf-8", true)) m.value else m.groupValues[1] + "UTF-8" + m.groupValues[3] }
            head = META_CHARSET.replace(head) { m -> if (m.groupValues[2].equals("utf-8", true)) m.value else m.groupValues[1] + "UTF-8" }
            return if (head.length == headEnd && head == s.substring(0, headEnd)) s else head + s.substring(headEnd)
        }
    }
}
