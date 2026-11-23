package app.fernreader.core.epub.markup

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
