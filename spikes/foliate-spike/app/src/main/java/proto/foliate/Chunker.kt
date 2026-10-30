package proto.foliate

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import proto.common.Probe
import java.util.zip.ZipFile

/**
 * Prototype of a book-transform layer in front of the rendering engine: spine documents larger than
 * [threshold] bytes are split at element boundaries into parts of about [target] bytes, presented to the
 * engine as separate spine items ("virtual sections"). The OPF spine/manifest and the nav/NCX hrefs are
 * rewritten so TOC anchors land in the right part. Everything is served as virtual zip entries.
 *
 * Splitting is a single forward scan over the markup (no DOM), cutting only where the element depth is back
 * at the body level (or one wrapper level below it when the body has a single wrapper element; the wrapper's
 * start tag is repeated in every part). Each part keeps the original <head>, so styles still apply.
 *
 * Not handled in the prototype: mapping reading positions/CFIs between original and parts, CSS that relies on
 * sibling position across a cut (e.g. :first-child, counters), and links into a part from other documents
 * other than the nav/NCX.
 */
class Chunker(private val zip: ZipFile, private val threshold: Int = 512 * 1024, private val target: Int = 128 * 1024) {
    /** Virtual entries: path in zip -> bytes (rewritten OPF/nav/NCX and the parts). */
    val virtual = HashMap<String, ByteArray>()
    /** Original document path -> (id -> part path). */
    private val anchorMap = HashMap<String, Map<String, String>>()
    private val firstPart = HashMap<String, String>()

    fun run() {
        val t0 = System.currentTimeMillis()
        val opfPath = findOpf() ?: return
        val opfDir = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val opf = zip.getInputStream(zip.getEntry(opfPath) ?: return).readBytes().toString(Charsets.UTF_8)
        val items = HashMap<String, Pair<String, String>>() // id -> href, media-type
        val spine = ArrayList<String>()
        val p = Xml.newPullParser()
        p.setInput(opf.reader())
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name.substringAfter(':')) {
                "item" -> items[p.getAttributeValue(null, "id") ?: continue] =
                    (p.getAttributeValue(null, "href") ?: continue) to (p.getAttributeValue(null, "media-type") ?: "")
                "itemref" -> p.getAttributeValue(null, "idref")?.let { spine += it }
            }
        }
        var newOpf = opf
        for (idref in spine) {
            val (href, type) = items[idref] ?: continue
            if (!type.contains("html")) continue
            val path = opfDir + href
            val e = zip.getEntry(path) ?: continue
            if (e.size in 0..threshold.toLong()) continue
            val text = zip.getInputStream(e).readBytes().toString(Charsets.UTF_8)
            val parts = split(text) ?: continue
            val ids = HashMap<String, String>()
            val manifestItems = StringBuilder()
            val spineRefs = StringBuilder()
            parts.forEachIndexed { i, part ->
                val partHref = "$href.part$i.xhtml"
                virtual[opfDir + partHref] = part.toByteArray()
                ID.findAll(part).forEach { ids.putIfAbsent(it.groupValues[1], partHref) }
                manifestItems.append("""<item id="$idref--p$i" href="$partHref" media-type="application/xhtml+xml"/>""")
                spineRefs.append("""<itemref idref="$idref--p$i"/>""")
            }
            anchorMap[href] = ids
            firstPart[href] = "$href.part0.xhtml"
            // Fix in-document links (href="#x") whose target ended up in another part.
            parts.indices.forEach { i ->
                val key = opfDir + "$href.part$i.xhtml"
                val s = String(virtual[key]!!)
                virtual[key] = LOCAL_LINK.replace(s) { m ->
                    val target = ids[m.groupValues[2]]
                    if (target == null || target == "$href.part$i.xhtml") m.value
                    else "${m.groupValues[1]}${target.substringAfterLast('/')}#${m.groupValues[2]}\""
                }.toByteArray()
            }
            newOpf = Regex("""<itemref\s[^>]*idref="${Regex.escape(idref)}"[^>]*/>""").replace(newOpf, spineRefs.toString())
            newOpf = newOpf.replace("</manifest>", "$manifestItems</manifest>")
            Probe.ev("chunked", "doc=$href bytes=${e.size} parts=${parts.size}")
        }
        if (anchorMap.isEmpty()) return
        virtual[opfPath] = newOpf.toByteArray()
        // Rewrite nav and NCX references to split documents.
        for ((_, pair) in items) {
            val (href, type) = pair
            val isNav = type.contains("ncx") || href.endsWith(".ncx") || (type.contains("html") && href.contains("nav"))
            if (!isNav) continue
            val path = opfDir + href
            val text = zip.getEntry(path)?.let { zip.getInputStream(it).readBytes().toString(Charsets.UTF_8) } ?: continue
            virtual[path] = rewriteRefs(text).toByteArray()
        }
        Probe.ev("chunk_done", "ms=${System.currentTimeMillis() - t0}")
    }

    private fun rewriteRefs(s: String): String = REF.replace(s) { m ->
        val (attr, doc, frag) = Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        val ids = anchorMap[doc] ?: return@replace m.value
        val part = if (frag.isNotEmpty()) ids[frag.drop(1)] ?: firstPart[doc]!! else firstPart[doc]!!
        "$attr\"$part$frag\""
    }

    private fun findOpf(): String? {
        val c = zip.getEntry("META-INF/container.xml") ?: return null
        val s = zip.getInputStream(c).readBytes().toString(Charsets.UTF_8)
        return Regex("""full-path="([^"]+)"""").find(s)?.groupValues?.get(1)
    }

    /** Returns the parts, or null if no safe cut points were found. */
    fun split(s: String): List<String>? {
        val bodyOpen = Regex("<body[^>]*>").find(s) ?: return null
        val bodyEnd = s.lastIndexOf("</body")
        if (bodyEnd < 0) return null
        val head = s.substring(0, bodyOpen.range.last + 1)
        val tail = s.substring(bodyEnd)
        // Cut points at depth 0 (body level) and, for a single wrapper, at depth 1.
        val cuts0 = ArrayList<Int>(); val cuts1 = ArrayList<Int>()
        var wrapperOpen: String? = null; var wrapperName = ""; var topLevelElements = 0
        var depth = 0
        var i = bodyOpen.range.last + 1
        while (i < bodyEnd) {
            val lt = s.indexOf('<', i)
            if (lt < 0 || lt >= bodyEnd) break
            when {
                s.startsWith("<!--", lt) -> { i = s.indexOf("-->", lt).let { if (it < 0) bodyEnd else it + 3 }; continue }
                s.startsWith("<![CDATA[", lt) -> { i = s.indexOf("]]>", lt).let { if (it < 0) bodyEnd else it + 3 }; continue }
                s.startsWith("<?", lt) || s.startsWith("<!", lt) -> { i = s.indexOf('>', lt).let { if (it < 0) bodyEnd else it + 1 }; continue }
            }
            val gt = tagEnd(s, lt); if (gt < 0) break
            if (s[lt + 1] == '/') {
                depth--
                if (depth == 0) cuts0 += gt + 1 else if (depth == 1) cuts1 += gt + 1
            } else {
                val name = s.substring(lt + 1, gt).takeWhile { !it.isWhitespace() && it != '/' && it != '>' }.lowercase()
                val selfClosing = s[gt - 1] == '/' || name in VOID
                if (depth == 0) {
                    topLevelElements++
                    if (topLevelElements == 1) { wrapperOpen = s.substring(lt, gt + 1); wrapperName = name }
                }
                if (selfClosing) { if (depth == 0) cuts0 += gt + 1 else if (depth == 1) cuts1 += gt + 1 }
                else depth++
            }
            i = gt + 1
        }
        val start = bodyOpen.range.last + 1
        val (cuts, open, close) = if (topLevelElements > 1 || wrapperOpen == null)
            Triple(cuts0, "", "")
        else Triple(cuts1, wrapperOpen!!, "</$wrapperName>")
        val parts = ArrayList<String>()
        val innerStart = if (open.isEmpty()) start else s.indexOf(open, start) + open.length
        var last = innerStart
        for (c in cuts) {
            if (c - last >= target) {
                parts += head + open + s.substring(last, c) + close + tail
                last = c
            }
        }
        if (parts.isEmpty()) return null
        val innerEnd = if (close.isEmpty()) bodyEnd else s.lastIndexOf(close, bodyEnd)
        parts += head + open + s.substring(last, innerEnd) + close + tail
        return parts
    }

    private fun tagEnd(s: String, lt: Int): Int {
        var q = 0.toChar(); var j = lt + 1
        while (j < s.length) {
            val c = s[j]
            if (q != 0.toChar()) { if (c == q) q = 0.toChar() }
            else if (c == '"' || c == '\'') q = c
            else if (c == '>') return j
            j++
        }
        return -1
    }

    companion object {
        private val VOID = setOf("br", "hr", "img", "meta", "link", "input", "col", "area", "base", "wbr",
            "source", "track", "embed", "param")
        private val ID = Regex("""\sid="([^"]+)"""")
        private val LOCAL_LINK = Regex("""(href=")#([^"]+)"""")
        private val REF = Regex("""((?:href|src)=)"([^"#]+)(#[^"]*)?"""")
    }
}
