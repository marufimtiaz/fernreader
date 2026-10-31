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
