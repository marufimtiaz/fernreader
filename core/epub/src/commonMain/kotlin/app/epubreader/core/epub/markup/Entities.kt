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
