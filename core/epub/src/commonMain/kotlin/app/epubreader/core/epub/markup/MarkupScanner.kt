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
